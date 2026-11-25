// Calibrated quantization with our importance matrix: see quantize_ours.h and kotlin-street/docs/MODEL_FILES.md.
#include "quantize_ours.h"

#include "ggml.h"
#include "gguf.h"
#include "llama.h"

#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

namespace timerpg {
namespace {

/** llama-imatrix's GGUF output -> per tensor, the mean squared activation of each input column (sums / counts, 1 where a
 *  column was never seen): what tools/quantize/quantize.cpp's load_imatrix() hands llama_model_quantize(). */
bool load_imatrix(const std::string & path, std::map<std::string, std::vector<float>> & out, int & chunks,
                  std::string & dataset, std::string & err) {
    ggml_context * ctx = nullptr;
    gguf_init_params p{ /*no_alloc*/ false, &ctx };
    gguf_context * g = gguf_init_from_file(path.c_str(), p);
    if (!g) { err = "not an imatrix GGUF: " + path; return false; }
    const int64_t kc = gguf_find_key(g, "imatrix.chunk_count"), kd = gguf_find_key(g, "imatrix.datasets");
    chunks = kc >= 0 ? (int) gguf_get_val_u32(g, kc) : 0;
    if (kd >= 0 && gguf_get_arr_n(g, kd) > 0) dataset = gguf_get_arr_str(g, kd, 0);
    std::map<std::string, std::pair<const ggml_tensor *, const ggml_tensor *>> sc;  // in_sum2, counts
    for (ggml_tensor * t = ggml_get_first_tensor(ctx); t; t = ggml_get_next_tensor(ctx, t)) {
        std::string n = t->name;
        auto strip = [&](const char * suf) {
            const size_t l = std::strlen(suf);
            if (n.size() > l && n.compare(n.size() - l, l, suf) == 0) { n.resize(n.size() - l); return true; }
            return false;
        };
        if (strip(".in_sum2")) sc[n].first = t;
        else if (strip(".counts")) sc[n].second = t;
    }
    for (const auto & [name, pr] : sc) {
        const ggml_tensor * s = pr.first, * c = pr.second;
        if (!s || !c || s->type != GGML_TYPE_F32 || c->type != GGML_TYPE_F32) { err = "bad imatrix entry " + name; break; }
        const int64_t nval = ggml_nelements(s), ncnt = ggml_nelements(c), ne0 = nval / ncnt;
        auto & e = out[name];
        e.resize((size_t) nval);
        for (int64_t j = 0; j < ncnt; ++j) {
            const float cnt = ((const float *) c->data)[j];
            for (int64_t i = 0; i < ne0; ++i) e[(size_t) (j * ne0 + i)] = cnt > 0 ? ((const float *) s->data)[j * ne0 + i] / cnt : 1.0f;
        }
    }
    gguf_free(g);
    ggml_free(ctx);
    return err.empty() && !out.empty();
}

}  // namespace

bool quantize_ours(const std::string & src, const std::string & imatrix, const std::string & dst,
                   const QuantizeOptions & opt, std::string & err) {
    llama_ftype ftype;
    if (opt.ftype == "Q4_0") ftype = LLAMA_FTYPE_MOSTLY_Q4_0;
    else if (opt.ftype == "Q4_K_M") ftype = LLAMA_FTYPE_MOSTLY_Q4_K_M;
    else { err = "unsupported type " + opt.ftype; return false; }

    std::map<std::string, std::vector<float>> data;
    int chunks = 0;
    std::string dataset;
    if (!load_imatrix(imatrix, data, chunks, dataset, err)) return false;
    std::vector<llama_model_imatrix_data> im;
    for (const auto & [name, v] : data) im.push_back({name.c_str(), v.data(), v.size()});
    im.push_back({nullptr, nullptr, 0});

    // the same provenance keys llama-quantize writes, plus ours
    std::vector<llama_model_kv_override> kv;
    auto kv_str = [&](const char * k, const std::string & v) {
        llama_model_kv_override o{}; std::snprintf(o.key, sizeof o.key, "%s", k);
        o.tag = LLAMA_KV_OVERRIDE_TYPE_STR; std::snprintf(o.val_str, sizeof o.val_str, "%s", v.c_str()); kv.push_back(o);
    };
    auto kv_int = [&](const char * k, int64_t v) {
        llama_model_kv_override o{}; std::snprintf(o.key, sizeof o.key, "%s", k);
        o.tag = LLAMA_KV_OVERRIDE_TYPE_INT; o.val_i64 = v; kv.push_back(o);
    };
    const size_t slash = imatrix.find_last_of('/');
    kv_str("quantize.imatrix.file", slash == std::string::npos ? imatrix : imatrix.substr(slash + 1));
    if (!dataset.empty()) kv_str("quantize.imatrix.dataset", dataset);
    kv_int("quantize.imatrix.entries_count", (int64_t) data.size());
    kv_int("quantize.imatrix.chunks_count", chunks);
    kv_str("timerpg.quant", "quantize_ours v1 " + opt.ftype + " imatrix token_embd/output q8_0" + (opt.keep_ssm ? (opt.ssm_out_q8 ? " ssm q8_0" : " ssm q8_0/q5_k") : ""));
    kv.emplace_back(); kv.back().key[0] = 0;

    llama_model_quantize_params qp = llama_model_quantize_default_params();
    qp.nthread = opt.threads;
    qp.ftype = ftype;
    qp.token_embedding_type = GGML_TYPE_Q8_0;  // the (tied) head of the 4B: its first K rows go to the NPU once trimmed
    qp.output_tensor_type = GGML_TYPE_Q8_0;    // the 9B's separate head
    // the delta-net layers' small tensors at higher types, as the shipped Hugging Face Q4_0 has them (unsloth: ssm_alpha
    // and ssm_beta Q8_0, ssm_out Q5_K); llama.cpp's own Q4_0 recipe puts them at Q4_0, which measured worse on our text
    std::vector<llama_model_tensor_override> tt;
    if (opt.keep_ssm) {
        tt.push_back({"ssm_alpha\\.weight", GGML_TYPE_Q8_0});
        tt.push_back({"ssm_beta\\.weight", GGML_TYPE_Q8_0});
        tt.push_back({"ssm_out\\.weight", opt.ssm_out_q8 ? GGML_TYPE_Q8_0 : GGML_TYPE_Q5_K});
        tt.push_back({nullptr, GGML_TYPE_COUNT});
        qp.tt_overrides = tt.data();
    }
    qp.imatrix = im.data();
    qp.kv_overrides = kv.data();
    qp.max_buf_size = (size_t) opt.max_buf_mib << 20;

    const std::string part = dst + ".part";
    std::remove(part.c_str());
    if (llama_model_quantize(src.c_str(), part.c_str(), &qp) != 0) { err = "llama_model_quantize failed"; std::remove(part.c_str()); return false; }
    if (std::rename(part.c_str(), dst.c_str()) != 0) { err = "rename failed"; return false; }
    return true;
}

}  // namespace timerpg
