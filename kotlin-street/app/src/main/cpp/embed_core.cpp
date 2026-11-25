#include "embed_core.h"

#include "llm_core.h"
#include "llama.h"

#include <cmath>

namespace llm {

Embedder::~Embedder() { unload(); }

void Embedder::unload() {
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
    ctx_ = nullptr; model_ = nullptr; dim_ = 0;
}

bool Embedder::load(const std::string & model, int threads) {
    unload();
    error_.clear();
    llama_model_params mp = llama_model_default_params();
    mp.devices = cpu_only_devices();  // the CPU only: never the NPU's session or its DMA memory (llm_core.h)
    mp.n_gpu_layers = 0;
    mp.use_extra_bufts = false;   // weights stay mapped from the file (reclaimable), not copied and repacked
    model_ = llama_model_load_from_file(model.c_str(), mp);
    if (!model_) { error_ = "could not load the memory model"; return false; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 512;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_LAST;   // Qwen3-Embedding: the hidden state of the last token
    cp.n_threads = threads > 0 ? threads : 4;
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) { error_ = "could not create the memory model's context"; unload(); return false; }
    dim_ = llama_model_n_embd(model_);
    return true;
}

std::vector<float> Embedder::embed(const std::string & text) {
    error_.clear();
    if (!ctx_) { error_ = "no memory model loaded"; return {}; }
    const llama_vocab * vocab = llama_model_get_vocab(model_);
    // the model was trained with its end-of-text token closing every input
    const std::string t = text.substr(0, 1500) + "<|endoftext|>";
    std::vector<llama_token> toks(t.size() + 8);
    int n = llama_tokenize(vocab, t.c_str(), (int32_t) t.size(), toks.data(), (int32_t) toks.size(), false, true);
    if (n <= 0) { error_ = "could not tokenize"; return {}; }
    if (n > 500) { toks[499] = toks[n - 1]; n = 500; }
    toks.resize((size_t) n);
    llama_memory_clear(llama_get_memory(ctx_), true);
    llama_batch b = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; ++i) {
        b.token[i] = toks[(size_t) i]; b.pos[i] = i; b.n_seq_id[i] = 1; b.seq_id[i][0] = 0; b.logits[i] = i == n - 1;
    }
    b.n_tokens = n;
    std::vector<float> out;
    if (llama_decode(ctx_, b) == 0) {
        const float * e = llama_get_embeddings_seq(ctx_, 0);
        if (e) {
            out.assign(e, e + dim_);
            double s = 0;
            for (float v : out) s += (double) v * v;
            const float k = s > 0 ? (float) (1.0 / std::sqrt(s)) : 0.f;
            for (float & v : out) v *= k;
        } else error_ = "no embedding";
    } else error_ = "decoding failed";
    llama_batch_free(b);
    return out;
}

Reranker::~Reranker() { unload(); }

void Reranker::unload() {
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
    ctx_ = nullptr; model_ = nullptr;
}

bool Reranker::load(const std::string & model, int threads) {
    unload();
    error_.clear();
    llama_model_params mp = llama_model_default_params();
    mp.devices = cpu_only_devices();  // the CPU only: never the NPU's session or its DMA memory (llm_core.h)
    mp.n_gpu_layers = 0;
    mp.use_extra_bufts = false;   // weights stay mapped from the file (reclaimable), not copied and repacked
    model_ = llama_model_load_from_file(model.c_str(), mp);
    if (!model_) { error_ = "could not load the reranker"; return false; }
    // a GGUF converted as a reranker carries RANK pooling and a yes/no head; a plain one has its LM head
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024; cp.n_batch = 1024; cp.n_ubatch = 1024; cp.n_seq_max = 1;
    cp.n_threads = threads > 0 ? threads : 4; cp.n_threads_batch = cp.n_threads; cp.no_perf = true;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;
    ctx_ = llama_init_from_model(model_, cp);
    if (ctx_ && llama_pooling_type(ctx_) == LLAMA_POOLING_TYPE_RANK) {
        rank_ = true;
        return true;
    }
    if (ctx_) llama_free(ctx_);
    cp.embeddings = false;
    cp.pooling_type = LLAMA_POOLING_TYPE_NONE;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) { error_ = "could not create the reranker's context"; unload(); return false; }
    rank_ = false;
    const llama_vocab * vocab = llama_model_get_vocab(model_);
    llama_token t[4];
    if (llama_tokenize(vocab, "yes", 3, t, 4, false, false) == 1) yes_ = t[0];
    if (llama_tokenize(vocab, "no", 2, t, 4, false, false) == 1) no_ = t[0];
    if (yes_ < 0 || no_ < 0) { error_ = "the reranker has no yes/no tokens"; unload(); return false; }
    return true;
}

float Reranker::score(const std::string & instruction, const std::string & query, const std::string & doc) {
    error_.clear();
    if (!ctx_) { error_ = "no reranker loaded"; return -1; }
    const std::string p =
        "<|im_start|>system\nJudge whether the Document meets the requirements based on the Query and the Instruct provided. "
        "Note that the answer can only be \"yes\" or \"no\".<|im_end|>\n<|im_start|>user\n<Instruct>: " + instruction +
        "\n<Query>: " + query.substr(0, 600) + "\n<Document>: " + doc.substr(0, 1200) +
        "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    const llama_vocab * vocab = llama_model_get_vocab(model_);
    std::vector<llama_token> toks(p.size() + 8);
    int n = llama_tokenize(vocab, p.c_str(), (int32_t) p.size(), toks.data(), (int32_t) toks.size(), false, true);
    if (n <= 0 || n > 1000) { error_ = "could not tokenize"; return -1; }
    llama_memory_clear(llama_get_memory(ctx_), true);
    llama_batch b = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; ++i) { b.token[i] = toks[(size_t) i]; b.pos[i] = i; b.n_seq_id[i] = 1; b.seq_id[i][0] = 0; b.logits[i] = i == n - 1; }
    b.n_tokens = n;
    float out = -1;
    if (llama_decode(ctx_, b) == 0) {
        float y = 0, no = 0;
        bool ok = false;
        if (rank_) {
            const float * e = llama_get_embeddings_seq(ctx_, 0);
            if (e) { y = e[0]; no = e[1]; ok = true; }
        } else {
            const float * l = llama_get_logits_ith(ctx_, -1);
            if (l) { y = l[yes_]; no = l[no_]; ok = true; }
        }
        // the head's two numbers may already be probabilities or logits: a softmax of logits either way
        if (ok) out = (y >= 0 && y <= 1 && no >= 0 && no <= 1 && std::fabs(y + no - 1) < 1e-3) ? y : 1.f / (1.f + std::exp(no - y));
        else error_ = "no score";
    } else error_ = "decoding failed";
    llama_batch_free(b);
    return out;
}

}  // namespace llm
