#include "asr_core.h"
#include "llm_core.h"

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <chrono>
#include <vector>

namespace llm {

namespace {
int64_t ms_now() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
constexpr int kMaxTokens = 160;  // a spoken question is a sentence or two
}  // namespace

std::string asr_text(const std::string & raw) {
    std::string t = raw;
    const std::string tag = "<asr_text>";
    const size_t at = t.find(tag);
    if (at != std::string::npos) t = t.substr(at + tag.size());
    else if (t.rfind("language ", 0) == 0) {
        // "language None" and nothing else: no speech was heard
        const size_t nl = t.find_first_of("\n");
        t = nl == std::string::npos ? "" : t.substr(nl + 1);
    }
    const size_t b = t.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    const size_t e = t.find_last_not_of(" \t\r\n");
    return t.substr(b, e - b + 1);
}

Asr::~Asr() { unload(); }

void Asr::unload() {
    if (mctx_) mtmd_free(mctx_);
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
    mctx_ = nullptr; ctx_ = nullptr; model_ = nullptr; rate_ = 0;
}

bool Asr::load(const std::string & model, const std::string & mmproj, int threads) {
    unload();
    error_.clear();
    cancel_.store(false);
    if (threads <= 0) threads = 4;
    reset_load_mem();
    llama_model_params mp = llama_model_default_params();
    mp.devices = cpu_only_devices();  // the CPU only: never the NPU's session or its DMA memory (llm_core.h)
    mp.n_gpu_layers = 0;
    mp.use_extra_bufts = false;   // weights stay mapped from the file (reclaimable), not copied and repacked
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    model_ = llama_model_load_from_file(model.c_str(), mp);
    if (!model_) { error_ = "could not load the speech model"; return false; }
    llama_context_params cp = llama_context_default_params();
    // a 20 s clip (the longest the game records) is ~260 audio tokens, with the names and the words written ~400
    cp.n_ctx = 1024;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    // only the last position's logits are ever read (the prompt is read whole, then one token at a time): without this
    // the output buffer is sized for a whole batch of logits over the 152k vocabulary (~300 MB at 512)
    cp.n_outputs_max = 1;
    cp.n_outputs_max_per_seq = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    // the cache in 8 bits (half the memory; it needs flash attention), else 16 where that cannot be had
    llama_context_params q = cp;
    q.type_k = GGML_TYPE_Q8_0;
    q.type_v = GGML_TYPE_Q8_0;
    q.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctx_ = llama_init_from_model(model_, q);
    if (!ctx_) ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) { error_ = "could not create the speech model's context (not enough memory?)"; unload(); return false; }
    mtmd_context_params mcp = mtmd_context_params_default();
    mcp.use_gpu = false;
    mcp.print_timings = false;
    mcp.n_threads = threads;
    mcp.warmup = false;
    mctx_ = mtmd_init_from_file(mmproj.c_str(), model_, mcp);
    if (!mctx_) { error_ = "could not load the speech model's audio encoder"; unload(); return false; }
    if (!mtmd_support_audio(mctx_)) { error_ = "that encoder does not take audio"; unload(); return false; }
    rate_ = mtmd_get_audio_sample_rate(mctx_);
    last_load_mem(mem_);
    return true;
}

std::string Asr::transcribe(const float * pcm, size_t n, const std::string & context) {
    error_.clear();
    cancel_.store(false);
    encode_ms_ = total_ms_ = 0;
    if (!mctx_) { error_ = "no speech model loaded"; return {}; }
    if (!pcm || n < (size_t) rate_ / 5) return {};  // under 0.2 s: nothing said
    const int64_t t0 = ms_now();
    llama_memory_clear(llama_get_memory(ctx_), true);

    // Qwen3-ASR's chat: the context words as the system message, the clip as the user's
    std::string prompt;
    if (!context.empty()) prompt += "<|im_start|>system\n" + context + "<|im_end|>\n";
    prompt += "<|im_start|>user\n" + std::string(mtmd_default_marker()) + "<|im_end|>\n<|im_start|>assistant\n";

    mtmd_bitmap * bmp = mtmd_bitmap_init_from_audio(n, pcm);
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text text{ prompt.c_str(), prompt.size(), /*add_special*/ false, /*parse_special*/ true };
    const mtmd_bitmap * bptr = bmp;
    std::string out;
    llama_sampler * smpl = nullptr;
    do {
        if (mtmd_tokenize(mctx_, chunks, &text, &bptr, 1) != 0) { error_ = "could not prepare the audio"; break; }
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(mctx_, ctx_, chunks, 0, 0, (int32_t) llama_n_batch(ctx_), true, &n_past) != 0) {
            error_ = "could not read the audio"; break;
        }
        encode_ms_ = ms_now() - t0;
        const llama_vocab * vocab = llama_model_get_vocab(model_);
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        smpl = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        char buf[256];
        for (int i = 0; i < kMaxTokens; ++i) {
            if (cancel_.load()) { error_ = "cancelled"; break; }
            llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;
            const int32_t len = llama_token_to_piece(vocab, tok, buf, sizeof buf, 0, /*special*/ true);
            if (len > 0) out.append(buf, (size_t) len);
            llama_batch b = llama_batch_get_one(&tok, 1);
            if (llama_decode(ctx_, b) != 0) { error_ = "decoding failed"; break; }
        }
    } while (false);
    if (smpl) llama_sampler_free(smpl);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bmp);
    total_ms_ = ms_now() - t0;
    return error_.empty() || error_ == "cancelled" ? asr_text(out) : std::string();
}

}  // namespace llm
