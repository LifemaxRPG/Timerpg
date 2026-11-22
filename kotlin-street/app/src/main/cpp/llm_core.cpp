#include "llm_core.h"

#include "common.h"
#include "sampling.h"
#include "speculative.h"

#include "llama.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#ifndef HWCAP_FPHP
#define HWCAP_FPHP (1 << 9)
#endif
#ifndef HWCAP_ASIMDHP
#define HWCAP_ASIMDHP (1 << 10)
#endif
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1 << 20)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1 << 13)
#endif
#endif

namespace llm {

namespace {

/** Saved states of a recurrent or hybrid model (Engine::take_snapshot): each is tens of MB for a 9B model. */
constexpr size_t kMaxSnapshots = 8;

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// ------------------------------------------------------------------------------------------------ UTF-8

const char kReplacement[] = "\xEF\xBF\xBD";  // U+FFFD

/** Length of the UTF-8 sequence a lead byte starts, or 0 if it cannot start one (continuation, C0/C1, F5+). */
int seq_len(unsigned char c) {
    if (c < 0x80) return 1;
    if (c >= 0xC2 && c <= 0xDF) return 2;
    if (c >= 0xE0 && c <= 0xEF) return 3;
    if (c >= 0xF0 && c <= 0xF4) return 4;
    return 0;
}

/** Is b valid as byte k (1..3) of a sequence starting with lead? (No overlongs, surrogates or > U+10FFFF.) */
bool valid_cont(unsigned char lead, int k, unsigned char b) {
    if ((b & 0xC0) != 0x80) return false;
    if (k == 1) {
        if (lead == 0xE0) return b >= 0xA0;
        if (lead == 0xED) return b <= 0x9F;
        if (lead == 0xF0) return b >= 0x90;
        if (lead == 0xF4) return b <= 0x8F;
    }
    return true;
}

// ------------------------------------------------------------------------------------------------ logging

std::mutex g_log_mu;
LogSink g_sink = nullptr;
std::vector<std::string> g_errors;  // error lines since clear_log_errors(), to explain a failed load
std::string g_splits;                // llama.cpp's "graph splits" line of the last context: how often a token's work
                                     // crosses between devices (more splits: more of it fell back to the CPU)
double g_mem[5] = {0, 0, 0, 0, 0};   // the last load's buffers as llama.cpp logged them (MiB), see last_load_mem

/** A "<buffer> buffer size = N MiB" line of llama.cpp's load, added up by kind. */
void count_buffer(const char * text) {
    const char * at = std::strstr(text, "buffer size = ");
    if (!at) return;
    const double mb = std::atof(at + std::strlen("buffer size = "));
    int k;
    if (std::strstr(text, "model buffer")) k = std::strstr(text, "_Mapped") ? 0 : 1;
    else if (std::strstr(text, "KV buffer") || std::strstr(text, "rotation buffer")) k = 2;
    else if (std::strstr(text, "RS buffer")) k = 3;
    else k = 4;
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_mem[k] += mb;
}

void default_sink(int level, const char * text) {
    if (level >= GGML_LOG_LEVEL_WARN) fputs(text, stderr);
}

void log_cb(ggml_log_level level, const char * text, void *) {
    static thread_local int last = GGML_LOG_LEVEL_INFO;
    int lv = level == GGML_LOG_LEVEL_CONT ? last : (int) level;
    last = lv;
    if (!text) return;
    count_buffer(text);
    if (std::strstr(text, "graph splits")) {
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == ' ')) line.pop_back();
        const size_t at = line.find("graph splits");
        std::lock_guard<std::mutex> lock(g_log_mu);
        g_splits = line.substr(at);
    }
    if (lv >= GGML_LOG_LEVEL_ERROR) {
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == ' ')) line.pop_back();
        std::lock_guard<std::mutex> lock(g_log_mu);
        if (!line.empty() && g_errors.size() < 4) g_errors.push_back(line);
    }
    (g_sink ? g_sink : default_sink)(lv, text);
}

void log_msg(int level, const std::string & msg) { (g_sink ? g_sink : default_sink)(level, (msg + "\n").c_str()); }

void clear_log_errors() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_errors.clear();
    for (double & m : g_mem) m = 0;
}

std::string load_info() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    return g_splits;
}

/** ": <what llama.cpp logged>" or "". */
std::string log_errors() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    std::string out;
    for (const auto & e : g_errors) out += (out.empty() ? ": " : " / ") + e;
    return out;
}

void ggml_abort_cb(const char * message) {
    // ggml calls abort() right after this: leave the reason in the log (logcat on Android).
    (g_sink ? g_sink : default_sink)(GGML_LOG_LEVEL_ERROR, (std::string("ggml abort: ") + (message ? message : "") + "\n").c_str());
}

int default_threads() {
    unsigned n = std::thread::hardware_concurrency();
    return (int) std::max(1u, std::min(4u, n ? n : 4u));
}

}  // namespace

std::string last_load_info() { return load_info(); }

void reset_load_mem() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    for (double & m : g_mem) m = 0;
}

void last_load_mem(double out[5]) {
    std::lock_guard<std::mutex> lock(g_log_mu);
    for (int i = 0; i < 5; i++) out[i] = g_mem[i];
}

// ---------------------------------------------------------------------------------------------------- text helpers

std::string Utf8Joiner::push(const char * data, size_t len) {
    if (data && len) pending_.append(data, len);
    std::string out;
    out.reserve(pending_.size());
    const size_t n = pending_.size();
    size_t i = 0;
    while (i < n) {
        const auto c = (unsigned char) pending_[i];
        const int l = seq_len(c);
        if (l == 0) { out += kReplacement; ++i; continue; }
        if (l == 1) { out += (char) c; ++i; continue; }
        int k = 1;
        bool bad = false;
        for (; k < l && i + k < n; ++k) {
            if (!valid_cont(c, k, (unsigned char) pending_[i + k])) { bad = true; break; }
        }
        if (bad) { out += kReplacement; ++i; continue; }  // the offending byte is looked at again as a start
        if (k < l) break;                                  // a character split across tokens: wait for the rest
        out.append(pending_, i, (size_t) l);
        i += (size_t) l;
    }
    pending_.erase(0, i);
    return out;
}

std::string Utf8Joiner::flush() {
    std::string out = pending_.empty() ? std::string() : std::string(kReplacement);
    pending_.clear();
    return out;
}

std::string StopFilter::push(const std::string & text, bool * stopped) {
    *stopped = stopped_;
    if (stopped_) return {};
    held_ += text;
    size_t cut = std::string::npos;
    for (const auto & s : stops_) cut = std::min(cut, held_.find(s));
    if (cut != std::string::npos) {
        std::string out = held_.substr(0, cut);
        held_.clear();
        stopped_ = *stopped = true;
        return out;
    }
    // Keep back the longest tail that is the start of a stop string.
    size_t keep = 0;
    for (const auto & s : stops_) {
        for (size_t l = std::min(s.size() - 1, held_.size()); l > keep; --l) {
            if (held_.compare(held_.size() - l, l, s, 0, l) == 0) { keep = l; break; }
        }
    }
    std::string out = held_.substr(0, held_.size() - keep);
    held_.erase(0, held_.size() - keep);
    return out;
}

std::string StopFilter::flush() {
    std::string out;
    if (!stopped_) out.swap(held_);
    held_.clear();
    return out;
}

std::u16string utf8_to_utf16(const std::string & s) {
    std::u16string out;
    out.reserve(s.size());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        const auto c = (unsigned char) s[i];
        const int l = seq_len(c);
        if (l == 1) { out += (char16_t) c; ++i; continue; }
        bool ok = l > 0 && i + (size_t) l <= n;
        for (int k = 1; ok && k < l; ++k) ok = valid_cont(c, k, (unsigned char) s[i + k]);
        if (!ok) { out += (char16_t) 0xFFFD; ++i; continue; }
        uint32_t cp = l == 2 ? (c & 0x1F) : l == 3 ? (c & 0x0F) : (c & 0x07);
        for (int k = 1; k < l; ++k) cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3F);
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out += (char16_t) (0xD800 + (cp >> 10));
            out += (char16_t) (0xDC00 + (cp & 0x3FF));
        } else {
            out += (char16_t) cp;
        }
        i += (size_t) l;
    }
    return out;
}

// ---------------------------------------------------------------------------------------------------- backend

void set_log_sink(LogSink sink) { g_sink = sink; }

std::string cpu_features() {
#if defined(__aarch64__) && defined(__linux__)
    const unsigned long hw = getauxval(AT_HWCAP), hw2 = getauxval(AT_HWCAP2);
    std::string f = "neon";
    if (hw & HWCAP_ASIMDDP) f += " dotprod";
    if ((hw & HWCAP_FPHP) && (hw & HWCAP_ASIMDHP)) f += " fp16";
    if (hw2 & HWCAP2_I8MM) f += " i8mm";
    return f;
#else
    return {};
#endif
}

std::vector<std::string> cpu_variant_libs() {
    std::vector<std::string> libs;
#if defined(__aarch64__) && defined(__ANDROID__)
    // Must match TIMERPG_CPU_VARIANTS in CMakeLists.txt. Each is only tried when the CPU has what it was built for
    // (checked here, before it is even loaded, and again by ggml's own score check).
    const unsigned long hw = getauxval(AT_HWCAP), hw2 = getauxval(AT_HWCAP2);
    const bool dot = hw & HWCAP_ASIMDDP, fp16 = (hw & HWCAP_FPHP) && (hw & HWCAP_ASIMDHP), i8mm = hw2 & HWCAP2_I8MM;
    if (dot && fp16 && i8mm) libs.emplace_back("libggml-cpu-android_armv8.6_1.so");
    if (dot && fp16) libs.emplace_back("libggml-cpu-android_armv8.2_2.so");
    libs.emplace_back("libggml-cpu-android_armv8.0_1.so");
#endif
    return libs;
}

/** The accelerators of a kind: 1 the GPU (OpenCL), 2 the NPU (Hexagon's HTP sessions). */
std::vector<ggml_backend_dev_t> devices_of(int kind) {
    std::vector<ggml_backend_dev_t> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU) continue;
        const bool npu = std::string(ggml_backend_dev_name(d)).rfind("HTP", 0) == 0;
        if ((kind == 2) == npu) out.push_back(d);
    }
    return out;
}

bool has_gpu() { return !devices_of(1).empty(); }
bool has_npu() { return !devices_of(2).empty(); }

bool init_backend(const std::vector<std::string> & cpu_libs, std::string * info) {
    static std::once_flag once;
    static bool ok = false;
    static std::string msg;
    std::call_once(once, [&] {
        try {
            llama_log_set(log_cb, nullptr);
            ggml_set_abort_callback(ggml_abort_cb);
            if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                msg = "CPU backend built in";
            } else {
                std::string tried;
                for (const auto & lib : cpu_libs) {
                    if (ggml_backend_load(lib.c_str()) && ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                        msg = "CPU backend " + lib;
                        break;
                    }
                    tried += (tried.empty() ? "" : ", ") + lib;
                }
                if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                    msg = "no CPU backend could be loaded (tried: " + (tried.empty() ? std::string("none") : tried) + ")" + log_errors();
                    log_msg(GGML_LOG_LEVEL_ERROR, msg);
                    return;
                }
            }
#if defined(__ANDROID__)
            // the Adreno GPU (OpenCL) and the Hexagon NPU, if this build has them and the phone's drivers load; else the
            // CPU alone. The NPU opens two sessions (each holds ~3.5 GB of weights: the 9B model is split over both).
            if (!getenv("GGML_HEXAGON_DEVICES")) setenv("GGML_HEXAGON_NDEV", "2", 0);
            ggml_backend_load("libggml-opencl.so");
            ggml_backend_load("libggml-hexagon.so");
            for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
                ggml_backend_dev_t d = ggml_backend_dev_get(i);
                if (ggml_backend_dev_type(d) != GGML_BACKEND_DEVICE_TYPE_CPU) msg += std::string("; ") + ggml_backend_dev_name(d) + " " + ggml_backend_dev_description(d);
            }
#endif
            llama_backend_init();
            if (ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                msg += std::string(" (") + ggml_backend_dev_description(cpu) + ")";
            }
            const std::string feats = cpu_features();
            if (!feats.empty()) msg += "; cpu: " + feats;
            ok = true;
            log_msg(GGML_LOG_LEVEL_INFO, "llm: " + msg);
        } catch (const std::exception & e) {
            msg = std::string("backend init failed: ") + e.what();
        } catch (...) {
            msg = "backend init failed";
        }
    });
    if (info) *info = msg;
    return ok;
}

// ---------------------------------------------------------------------------------------------------- engine

Engine::Engine() = default;

Engine::~Engine() { unload(); }

void Engine::free_mtp() {
    if (spec_) common_speculative_free(spec_);
    spec_ = nullptr;
    if (dft_) llama_free(dft_);
    dft_ = nullptr;
    dft_cached_.clear();
}

void Engine::unload() {
    free_mtp();
    if (batch_.token) llama_batch_free(batch_);
    batch_ = {};
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
    ctx_ = nullptr;
    model_ = nullptr;
    vocab_ = nullptr;
    cached_.clear();
    snapshots_.clear();
    recurrent_ = false;
    n_batch_ = 0;
}

std::vector<size_t> Engine::line_ends(const std::vector<int32_t> & toks) const {
    std::vector<size_t> out;
    char buf[64];
    for (size_t i = 0; i < toks.size(); ++i) {
        const int32_t n = llama_token_to_piece(vocab_, toks[i], buf, sizeof buf, 0, /*special*/ true);
        if (n <= 0) continue;
        const std::string piece(buf, (size_t) n);
        if (piece == "<|im_end|>") break;                    // the system message ends: what follows is the question
        if (piece.back() == '\n') out.push_back(i + 1);
    }
    return out;
}

size_t Engine::restore_snapshot(const std::vector<int32_t> & toks) {
    Snapshot * best = nullptr;
    const uint64_t now = ++clock_;
    for (auto & s : snapshots_) {
        if (s.tokens.size() >= toks.size() || !std::equal(s.tokens.begin(), s.tokens.end(), toks.begin())) continue;
        // every snapshot this prompt starts with stays fresh (the shared rules most of all), not just the one used
        s.used = now;
        if (!best || s.tokens.size() > best->tokens.size()) best = &s;
    }
    if (!best) return 0;
    llama_memory_clear(llama_get_memory(ctx_), true);
    if (llama_state_seq_set_data(ctx_, best->state.data(), best->state.size(), 0) == 0) {
        llama_memory_clear(llama_get_memory(ctx_), true);
        return 0;
    }
    cached_ = best->tokens;
    return cached_.size();
}

void Engine::take_snapshot() {
    for (auto & s : snapshots_) if (s.tokens == cached_) { s.used = ++clock_; return; }
    const size_t size = llama_state_seq_get_size(ctx_, 0);
    if (size == 0) return;
    Snapshot snap;
    snap.state.resize(size);
    if (llama_state_seq_get_data(ctx_, snap.state.data(), size, 0) == 0) return;
    snap.tokens = cached_;
    snap.used = ++clock_;
    // a handful: the rules every prompt starts with stay (they are used every time), the rest come and go
    if (snapshots_.size() >= kMaxSnapshots) {
        auto old = std::min_element(snapshots_.begin(), snapshots_.end(), [](const Snapshot & a, const Snapshot & b) { return a.used < b.used; });
        *old = std::move(snap);
    } else {
        snapshots_.push_back(std::move(snap));
    }
}

const char * end_name(End e) {
    switch (e) {
        case End::none: return "none";
        case End::eog: return "end of turn";
        case End::stop_text: return "stop text";
        case End::limit: return "max tokens";
        case End::callback: return "stopped by caller";
        case End::cancelled: return "cancelled";
        case End::context: return "context full";
        case End::error: return "error";
    }
    return "?";
}

bool Engine::abort_cb(void * self) { return static_cast<Engine *>(self)->cancel_.load(std::memory_order_relaxed); }

bool Engine::load(const std::string & path, int threads, int n_ctx) {
    unload();
    error_.clear();
    stats_ = {};
    const int64_t t0 = now_ms();
    try {
        std::string info;
        if (!init_backend(cpu_variant_libs(), &info)) {
            error_ = info;
            return false;
        }
        // Friendly errors for the usual mistakes, before llama.cpp sees the file.
        FILE * f = std::fopen(path.c_str(), "rb");
        if (!f) {
            error_ = "cannot open the model file " + path + ": " + std::strerror(errno);
            return false;
        }
        char magic[4] = {};
        const size_t got = std::fread(magic, 1, sizeof magic, f);
        std::fclose(f);
        if (got != sizeof magic || std::memcmp(magic, "GGUF", 4) != 0) {
            error_ = "not a GGUF model file: " + path;
            return false;
        }

        clear_log_errors();
        llama_model_params mp = llama_model_default_params();
        // on the GPU or the NPU when asked and there is one (all layers: they share the phone's memory)
        devs_ = device_ > 0 ? devices_of(device_) : std::vector<ggml_backend_dev_t>();
        if (!devs_.empty()) {
            devs_.push_back(nullptr);
            mp.devices = devs_.data();
            mp.n_gpu_layers = 999;
        } else {
            mp.n_gpu_layers = 0;
        }
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        // no repacking unless asked: repacked weights are copied out of the mapped file (the 9B's 5.4 GB in memory the
        // system cannot reclaim, read whole at load), which left no room for speech and the voices beside it
        mp.use_extra_bufts = repack_;
        // the model's own next-token prediction head (MTP), when the file has one and it is wanted
        mp.load_mtp = mtp_n_ > 0;
        // cancel() also stops a load.
        mp.progress_callback = [](float, void * self) { return !static_cast<Engine *>(self)->cancel_.load(); };
        mp.progress_callback_user_data = this;
        model_ = llama_model_load_from_file(path.c_str(), mp);
        if (!model_) {
            error_ = cancel_.load() ? "cancelled" : "could not load the model" + log_errors();
            return false;
        }
        if (llama_model_has_encoder(model_) || !llama_model_has_decoder(model_)) {
            error_ = "unsupported model (not a decoder-only chat model)";
            unload();
            return false;
        }
        vocab_ = llama_model_get_vocab(model_);

        const int train = llama_model_n_ctx_train(model_);
        if (n_ctx <= 0) n_ctx = 1024;
        n_ctx = std::max(128, train > 0 ? std::min(n_ctx, train) : n_ctx);
        if (threads <= 0) threads = default_threads();

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t) n_ctx;
        cp.n_batch = (uint32_t) std::min(n_ctx, batch_max_);
        cp.n_ubatch = cp.n_batch;
        cp.n_seq_max = 1;
        // We only ever ask for the last token's logits (decode_tokens sets one output per batch). Without this the
        // compute buffer is sized for n_batch x vocab logits: ~300 MB more for Qwen3's 152k vocabulary.
        cp.n_outputs_max = 1;
        cp.n_outputs_max_per_seq = 1;
        // MTP: the guesses are checked in one batch (logits for each), and the recurrent state can step back over the
        // ones that were wrong
        const bool want_mtp = mtp_n_ > 0 && llama_model_n_layer_nextn(model_) > 0;
        if (want_mtp) {
            cp.n_outputs_max = (uint32_t) (mtp_n_ + 1);
            cp.n_outputs_max_per_seq = (uint32_t) (mtp_n_ + 1);
            cp.n_rs_seq = (uint32_t) mtp_n_;
        }
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.no_perf = true;
        cp.abort_callback = &Engine::abort_cb;
        cp.abort_callback_data = this;
        kv_q8_used_ = false;
        if (kv_q8_) {
            // the cache in 8 bits: half the memory of 16 (a quantized V cache needs flash attention)
            llama_context_params q = cp;
            q.type_k = GGML_TYPE_Q8_0;
            q.type_v = GGML_TYPE_Q8_0;
            q.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
            ctx_ = llama_init_from_model(model_, q);
            if (ctx_) {
                kv_q8_used_ = true;
            } else {
                log_msg(GGML_LOG_LEVEL_WARN, "an 8-bit cache could not be set up here; using the 16-bit one");
                std::lock_guard<std::mutex> lock(g_log_mu);
                for (int i = 2; i < 5; i++) g_mem[i] = 0;   // (the failed context's buffers were freed)
                g_errors.clear();
            }
        }
        if (!ctx_) ctx_ = llama_init_from_model(model_, cp);
        if (!ctx_) {
            error_ = "could not create the model context (not enough memory?)" + log_errors();
            unload();
            return false;
        }
        if (!llama_get_memory(ctx_)) {
            // an embedding model (BERT, nomic, ...): no cache, no logits to sample from
            error_ = "unsupported model (not a chat model: it has no memory for a conversation)";
            unload();
            return false;
        }
        recurrent_ = llama_model_is_recurrent(model_) || llama_model_is_hybrid(model_);
        n_batch_ = (int32_t) llama_n_batch(ctx_);
        if (want_mtp) {
            // the head's own context, sharing the main one's memory; any failure: plain generation
            llama_context_params dp = cp;
            dp.ctx_type = LLAMA_CONTEXT_TYPE_MTP;
            dp.n_rs_seq = 0;
            dp.ctx_other = ctx_;
            dp.abort_callback = nullptr;
            dp.abort_callback_data = nullptr;
            dft_ = llama_init_from_model(model_, dp);
            const auto rm = common_context_can_seq_rm(ctx_);
            if (dft_ && rm != COMMON_CONTEXT_SEQ_RM_TYPE_FULL && rm != COMMON_CONTEXT_SEQ_RM_TYPE_NO) {
                common_params_speculative sp;
                sp.types = { COMMON_SPECULATIVE_TYPE_DRAFT_MTP };
                sp.draft.n_max = mtp_n_;
                sp.draft.ctx_tgt = ctx_;
                sp.draft.ctx_dft = dft_;
                spec_ = common_speculative_init(sp, 1);
            }
            if (!spec_) {
                log_msg(GGML_LOG_LEVEL_WARN, "MTP could not be set up; generating without it");
                free_mtp();
            }
        }
        batch_ = llama_batch_init(n_batch_, 0, 1);
        stats_.threads = threads;
        stats_.load_ms = now_ms() - t0;
        return true;
    } catch (const std::bad_alloc &) {
        error_ = "out of memory while loading the model";
    } catch (const std::exception & e) {
        error_ = std::string("could not load the model: ") + e.what();
    } catch (...) {
        error_ = "could not load the model";
    }
    unload();
    return false;
}

bool Engine::tokenize(const std::string & text, std::vector<int32_t> & out) {
    if (text.size() > 1u << 20) return false;
    const int32_t len = (int32_t) text.size();
    int32_t n = llama_tokenize(vocab_, text.data(), len, nullptr, 0, /*add_special*/ true, /*parse_special*/ true);
    if (n == INT32_MIN) return false;
    if (n < 0) n = -n;
    out.resize((size_t) n);
    if (n == 0) return true;
    const int32_t m = llama_tokenize(vocab_, text.data(), len, out.data(), n, true, true);
    if (m < 0) return false;
    out.resize((size_t) m);
    return true;
}

void Engine::resync_cache(const std::vector<int32_t> & attempted) {
    // A failed or aborted llama_decode keeps the micro-batches it finished: find out how far the cache got.
    llama_memory_t mem = llama_get_memory(ctx_);
    const llama_pos pmin = llama_memory_seq_pos_min(mem, 0);
    const llama_pos pmax = llama_memory_seq_pos_max(mem, 0);
    size_t n = (pmin == 0 && pmax >= 0) ? std::min((size_t) pmax + 1, attempted.size()) : 0;
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) n, -1)) {
        llama_memory_clear(mem, true);
        n = 0;
    }
    cached_.assign(attempted.begin(), attempted.begin() + (ptrdiff_t) n);
}

bool Engine::decode_tokens(const std::vector<int32_t> & toks, size_t from, size_t to, bool logits_last) {
    // toks[i] goes to position i, so after success the cache holds toks[0, to).
    llama_batch & batch = batch_;
    bool ok = true;
    for (size_t i = from; i < to && ok;) {
        const size_t n = std::min((size_t) n_batch_, to - i);
        for (size_t k = 0; k < n; ++k) {
            batch.token[k] = toks[i + k];
            batch.pos[k] = (llama_pos) (i + k);
            batch.n_seq_id[k] = 1;
            batch.seq_id[k][0] = 0;
            batch.logits[k] = logits_last && i + k == to - 1;
        }
        batch.n_tokens = (int32_t) n;
        const int rc = llama_decode(ctx_, batch);
        if (rc != 0) {
            if (!cancel_.load()) error_ = "llama_decode failed (" + std::to_string(rc) + ")" + log_errors();
            resync_cache(std::vector<int32_t>(toks.begin(), toks.begin() + (ptrdiff_t) (i + n)));
            ok = false;
            break;
        }
        cached_.insert(cached_.end(), toks.begin() + (ptrdiff_t) i, toks.begin() + (ptrdiff_t) (i + n));
        i += n;
    }
    return ok;
}

std::string Engine::generate(const std::string & prompt, int max_tokens, float temperature, const OnText & on_text) {
    error_.clear();
    const Stats keep = stats_;
    stats_ = {};
    stats_.load_ms = keep.load_ms;
    stats_.threads = keep.threads;
    if (!ctx_) {
        error_ = "no model loaded";
        return {};
    }
    std::string reply;
    llama_sampler * smpl = nullptr;
    try {
        const int64_t t0 = now_ms();
        clear_log_errors();
        if (cancel_.load()) {
            error_ = "cancelled";
            return {};
        }
        std::vector<int32_t> toks;
        if (!tokenize(prompt, toks) || toks.empty()) {
            error_ = toks.empty() ? "empty prompt" : "could not tokenize the prompt";
            return {};
        }
        const size_t n_ctx = llama_n_ctx(ctx_);
        if (toks.size() >= n_ctx) {
            error_ = "prompt too long: " + std::to_string(toks.size()) + " tokens, context " + std::to_string(n_ctx);
            return {};
        }
        max_tokens = (int) std::max<int64_t>(0, std::min<int64_t>(max_tokens, (int64_t) (n_ctx - toks.size())));

        // Reuse the longest common prefix with what is in the KV cache; at least the last prompt token is processed
        // again, for its logits.
        size_t reuse = 0;
        while (reuse < cached_.size() && reuse < toks.size() && cached_[reuse] == toks[reuse]) ++reuse;
        reuse = std::min(reuse, toks.size() - 1);
        llama_memory_t mem = llama_get_memory(ctx_);
        if (recurrent_) {
            // the state can only go on from where it is: when the cache holds exactly a start of this prompt, carry on
            // from there; otherwise start from the longest saved snapshot that begins it
            if (reuse != cached_.size()) {
                reuse = restore_snapshot(toks);
                if (reuse == 0) { llama_memory_clear(mem, true); cached_.clear(); }
            }
        } else if (!llama_memory_seq_rm(mem, 0, (llama_pos) reuse, -1)) {
            llama_memory_clear(mem, true);
            reuse = 0;
        }
        cached_.resize(reuse);
        stats_.prompt_tokens = (int32_t) toks.size();
        stats_.reused_tokens = (int32_t) reuse;

        if (spec_ && max_tokens > 0) {
            std::string r = generate_mtp(toks, reuse, max_tokens, temperature, on_text, t0);
            return r;
        }
        const int64_t tp = now_ms();
        bool prefilled = true;
        size_t at = reuse;
        if (recurrent_) {
            // stop at each line end of the facts to save the state there: the next prompt about this person (same lines
            // first) or anyone (the same rules) starts from the longest one
            for (size_t b : line_ends(toks)) {
                if (b <= at || b >= toks.size()) continue;
                if (!(prefilled = decode_tokens(toks, at, b, false))) break;
                at = b;
                take_snapshot();
            }
        }
        if (prefilled) prefilled = decode_tokens(toks, at, toks.size(), /*logits_last*/ true);
        const int64_t tg = now_ms();
        stats_.prefill_ms = tg - tp;
        if (!prefilled) {
            if (cancel_.load()) error_ = "cancelled";
            stats_.end = cancel_.load() ? End::cancelled : End::error;
            return {};
        }
        if (max_tokens == 0) return {};  // prefill only (warms the cache for this prompt)

        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        smpl = llama_sampler_chain_init(sp);
        // As the WebAssembly version: repeat penalty, top-k, top-p, temperature.
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), sampling_.repeat_last_n,
                                                                   sampling_.repeat_penalty, 0.0f, 0.0f));
        if (temperature <= 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampling_.top_k));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sampling_.top_p, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(sampling_.seed));
        }

        Utf8Joiner joiner;
        StopFilter stop({"<|im_end|>", "<|im_start|>", "<|endoftext|>"});
        bool stopped = false, halted = false;
        auto emit = [&](const std::string & text) {
            if (text.empty() || halted) return;
            reply += text;
            if (on_text && !on_text(text)) halted = true;
        };
        std::vector<int32_t> seq = toks;  // prompt + reply so far, as in the cache
        char buf[256];
        for (int n = 0; n < max_tokens; ++n) {
            if (cancel_.load()) { error_ = "cancelled"; stats_.end = End::cancelled; break; }
            const llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
            if (llama_vocab_is_eog(vocab_, tok)) { stats_.end = End::eog; break; }
            if (++stats_.gen_tokens == 1) stats_.first_token_ms = now_ms() - t0;
            int32_t len = llama_token_to_piece(vocab_, tok, buf, sizeof buf, 0, /*special*/ true);
            std::string piece;
            if (len < 0) {
                piece.resize((size_t) -len);
                len = llama_token_to_piece(vocab_, tok, piece.data(), (int32_t) piece.size(), 0, true);
                piece.resize(len > 0 ? (size_t) len : 0);
            } else {
                piece.assign(buf, (size_t) len);
            }
            emit(stop.push(joiner.push(piece.data(), piece.size()), &stopped));
            if (stopped || halted || n + 1 == max_tokens) {
                stats_.end = stopped ? End::stop_text : halted ? End::callback : End::limit;
                break;
            }
            // Feed the token back for the next one.
            seq.push_back(tok);
            if (seq.size() > n_ctx) { stats_.end = End::context; break; }
            if (!decode_tokens(seq, seq.size() - 1, seq.size(), true)) {
                if (cancel_.load()) error_ = "cancelled";
                stats_.end = cancel_.load() ? End::cancelled : End::error;
                break;
            }
        }
        if (!stopped) emit(stop.push(joiner.flush(), &stopped));
        if (!stopped) emit(stop.flush());
        stats_.gen_ms = now_ms() - tg;
    } catch (const std::bad_alloc &) {
        error_ = "out of memory while generating";
    } catch (const std::exception & e) {
        error_ = std::string("generation failed: ") + e.what();
    } catch (...) {
        error_ = "generation failed";
    }
    if (smpl) llama_sampler_free(smpl);
    return reply;
}


std::string Engine::generate_mtp(const std::vector<int32_t> & toks, size_t at, int max_tokens, float temperature,
                                 const OnText & on_text, int64_t t0) {
    std::string reply;
    const size_t n = toks.size();
    const size_t n_ctx = llama_n_ctx(ctx_);
    llama_memory_t mem = llama_get_memory(ctx_);
    llama_memory_t dmem = llama_get_memory(dft_);
    // the head's cache keeps what it shares with this prompt, up to where the main cache goes on from
    {
        size_t keep = 0;
        while (keep < dft_cached_.size() && keep < at && dft_cached_[keep] == toks[keep]) ++keep;
        if (dmem && !llama_memory_seq_rm(dmem, 0, (llama_pos) keep, -1)) { llama_memory_clear(dmem, true); keep = 0; }
        dft_cached_.resize(keep);
    }
    const int64_t tp = now_ms();
    // the prompt but its last token: snapshots at the fact sheet's line ends as usual, the last stretch through the
    // speculator too (the head needs the model's state at its end)
    bool ok = true;
    if (recurrent_) {
        for (size_t b : line_ends(toks)) {
            if (b <= at || b >= n - 1) continue;
            if (!(ok = decode_tokens(toks, at, b, false))) break;
            at = b;
            take_snapshot();
        }
    }
    common_batch cb(ctx_);
    for (size_t i = at; ok && i < n - 1;) {
        const size_t e = std::min(n - 1, i + (size_t) n_batch_);
        cb.clear();
        for (size_t k = i; k < e; ++k) cb.add(toks[k], (llama_pos) k, 0, false);
        if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, cb.get()) != 0) {
            if (!cancel_.load()) error_ = "llama_process failed" + log_errors();
            resync_cache(std::vector<int32_t>(toks.begin(), toks.begin() + (ptrdiff_t) e));
            ok = false;
            break;
        }
        cached_.insert(cached_.end(), toks.begin() + (ptrdiff_t) i, toks.begin() + (ptrdiff_t) e);
        common_speculative_process(spec_, cb);
        i = e;
    }
    const int64_t tg = now_ms();
    stats_.prefill_ms = tg - tp;
    if (!ok) {
        if (cancel_.load()) error_ = "cancelled";
        stats_.end = cancel_.load() ? End::cancelled : End::error;
        return {};
    }

    common_params_sampling ps;
    ps.seed = sampling_.seed;
    ps.temp = temperature;
    ps.top_k = sampling_.top_k;
    ps.top_p = sampling_.top_p;
    ps.min_p = 0.0f;
    ps.penalty_repeat = sampling_.repeat_penalty;
    ps.penalty_last_n = sampling_.repeat_last_n;
    ps.no_perf = true;
    common_sampler * smpl = common_sampler_init(model_, ps);
    if (!smpl) { error_ = "could not set up sampling"; return {}; }

    Utf8Joiner joiner;
    StopFilter stop({"<|im_end|>", "<|im_start|>", "<|endoftext|>"});
    bool stopped = false, halted = false, done = false;
    auto emit = [&](const std::string & text) {
        if (text.empty() || halted) return;
        reply += text;
        if (on_text && !on_text(text)) halted = true;
    };
    llama_token id_last = toks.back();
    std::vector<llama_token> in_ctx(toks.begin(), toks.end() - 1);  // the tokens in the cache, position i = in_ctx[i]
    std::vector<llama_token> draft;
    common_speculative_begin(spec_, 0, in_ctx);
    common_batch bt(ctx_);
    char buf[256];
    int produced = 0;
    try {
        while (!done) {
            if (cancel_.load()) { error_ = "cancelled"; stats_.end = End::cancelled; break; }
            const int n_past = (int) in_ctx.size();
            int n_draft_max = std::min((int) n_ctx - n_past - 2, max_tokens - produced - 1);
            draft.clear();
            if (n_draft_max > 0) {
                auto & dp = common_speculative_get_draft_params(spec_, 0);
                dp.drafting = true;
                dp.n_max = n_draft_max;
                dp.pos0 = n_past;
                dp.id_last = id_last;
                dp.prompt = &in_ctx;
                dp.result = &draft;
                dp.temp = temperature;
                common_speculative_draft(spec_);
                // drafting ran the head ahead in its own cache: back to where the main model is before checking
                if (dmem) llama_memory_seq_rm(dmem, 0, (llama_pos) n_past, -1);
            }
            // check [last, guess0, guess1, ...] in one pass
            bt.clear();
            bt.add(id_last, n_past, 0, true);
            for (size_t i = 0; i < draft.size(); ++i) bt.add(draft[i], n_past + 1 + (llama_pos) i, 0, true);
            if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, bt.get()) != 0) {
                if (!cancel_.load()) error_ = "llama_process failed" + log_errors();
                stats_.end = cancel_.load() ? End::cancelled : End::error;
                if (cancel_.load()) error_ = "cancelled";
                break;
            }
            common_speculative_process(spec_, bt);
            const std::vector<llama_token> ids = common_sampler_sample_and_accept_n(smpl, ctx_, draft);
            if (ids.empty()) { error_ = "sampling failed"; stats_.end = End::error; break; }
            common_speculative_accept(spec_, 0, (uint16_t) (ids.size() - 1));
            stats_.drafted += (int32_t) draft.size();
            stats_.accepted += (int32_t) ids.size() - 1;
            in_ctx.push_back(id_last);  // now in the cache, with the accepted guesses after it
            for (size_t i = 0; i < ids.size(); ++i) {
                const llama_token tok = ids[i];
                if (llama_vocab_is_eog(vocab_, tok)) { stats_.end = End::eog; done = true; break; }
                if (++stats_.gen_tokens == 1) stats_.first_token_ms = now_ms() - t0;
                ++produced;
                int32_t len = llama_token_to_piece(vocab_, tok, buf, sizeof buf, 0, /*special*/ true);
                std::string piece;
                if (len < 0) {
                    piece.resize((size_t) -len);
                    len = llama_token_to_piece(vocab_, tok, piece.data(), (int32_t) piece.size(), 0, true);
                    piece.resize(len > 0 ? (size_t) len : 0);
                } else {
                    piece.assign(buf, (size_t) len);
                }
                emit(stop.push(joiner.push(piece.data(), piece.size()), &stopped));
                if (stopped || halted || produced >= max_tokens) {
                    stats_.end = stopped ? End::stop_text : halted ? End::callback : End::limit;
                    done = true;
                    break;
                }
                if (i + 1 < ids.size()) in_ctx.push_back(tok);  // an accepted guess: already in the cache
                id_last = tok;
            }
            // the wrong guesses (and anything past where we stopped) out of the cache
            if (dmem) llama_memory_seq_rm(dmem, 0, (llama_pos) in_ctx.size(), -1);
            if (!llama_memory_seq_rm(mem, 0, (llama_pos) in_ctx.size(), -1)) {
                llama_memory_clear(mem, true);
                in_ctx.clear();
                if (!done) { error_ = "could not step back over a wrong guess"; stats_.end = End::error; }
                done = true;
            }
            if (!done && in_ctx.size() + 2 >= n_ctx) { stats_.end = End::context; done = true; }
        }
    } catch (...) {
        common_sampler_free(smpl);
        throw;
    }
    common_sampler_free(smpl);
    if (!stopped) emit(stop.push(joiner.flush(), &stopped));
    if (!stopped) emit(stop.flush());
    cached_ = in_ctx;
    dft_cached_ = in_ctx;
    stats_.gen_ms = now_ms() - tg;
    return reply;
}

}  // namespace llm
