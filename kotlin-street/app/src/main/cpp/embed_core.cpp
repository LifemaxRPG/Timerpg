#include "embed_core.h"

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
    mp.n_gpu_layers = 0;
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

}  // namespace llm
