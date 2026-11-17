// The native language-model engine (llama.cpp), independent of Android/JNI so it also builds and is tested on a
// computer (host_test.cpp). llm_jni.cpp is the thin layer the app talks to.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"

namespace llm {

// ---------------------------------------------------------------------------------------------------- text helpers

/** Joins the bytes of token pieces into valid UTF-8: a character split across tokens is held back until it is
 *  complete, and bytes that can never form valid UTF-8 become U+FFFD. What it returns is always valid UTF-8. */
class Utf8Joiner {
public:
    std::string push(const char * data, size_t len);
    /** At the end: an unfinished character becomes U+FFFD. */
    std::string flush();
private:
    std::string pending_;
};

/** Holds back text that could be the start of a stop string (e.g. "<|im" before "_end|>") and cuts the text at
 *  the first stop string. Stop strings must be ASCII. */
class StopFilter {
public:
    explicit StopFilter(std::vector<std::string> stops) : stops_(std::move(stops)) {}
    /** Returns the text that is safe to show; sets *stopped once a stop string has appeared (nothing after it is
     *  returned, now or later). */
    std::string push(const std::string & text, bool * stopped);
    /** At the end: whatever was held back (it never became a stop string). */
    std::string flush();
private:
    std::vector<std::string> stops_;
    std::string held_;
    bool stopped_ = false;
};

/** UTF-8 to UTF-16 (for JNI's NewString, which unlike NewStringUTF accepts any text). Invalid bytes become U+FFFD. */
std::u16string utf8_to_utf16(const std::string & s);

// ---------------------------------------------------------------------------------------------------- backend

/** Log lines from llama.cpp/ggml and from us. level: 1 debug, 2 info, 3 warn, 4 error (ggml_log_level). */
using LogSink = void (*)(int level, const char * text);
/** Where log lines go (default: warnings and errors to stderr). Call before anything else; not thread-safe. */
void set_log_sink(LogSink sink);

/**
 * Makes sure llama.cpp has a CPU backend, once per process. A static build (the host test) has it built in; the
 * Android build loads one of the CPU kernel libraries in [cpu_libs], trying them in order (best first) until one
 * loads and says it supports this CPU. Returns false if none could be used; [info] says what was loaded or why not.
 */
bool init_backend(const std::vector<std::string> & cpu_libs, std::string * info);

/** "dotprod fp16 i8mm"-style list of the ARM features that matter for the kernels (empty elsewhere). */
std::string cpu_features();
/** The CPU kernel libraries to try on this phone, best first (Android arm64). */
std::vector<std::string> cpu_variant_libs();

// ---------------------------------------------------------------------------------------------------- engine

/** Why the last generate() stopped (for tests and logs; not passed to Kotlin). */
enum class End : int32_t {
    none,       // no reply was generated (error before sampling, or max_tokens 0)
    eog,        // the model ended its turn (<|im_end|> or another end-of-generation token)
    stop_text,  // a stop string arrived as plain text
    limit,      // max_tokens reached
    callback,   // the caller's on_text returned false
    cancelled,
    context,    // the context is full
    error,
};
const char * end_name(End e);

struct Stats {
    int64_t load_ms = 0;
    int32_t prompt_tokens = 0;
    int32_t reused_tokens = 0;
    int64_t prefill_ms = 0;
    int32_t gen_tokens = 0;
    int64_t gen_ms = 0;
    int64_t first_token_ms = 0;
    int32_t threads = 0;
    End end = End::none;
};

/** How replies are sampled: the settings the WebAssembly version used. */
struct Sampling {
    int32_t top_k = 40;
    float top_p = 0.9f;
    float repeat_penalty = 1.15f;
    int32_t repeat_last_n = 64;
    /** 0xFFFFFFFF = a new random seed each reply. */
    uint32_t seed = 0xFFFFFFFF;
};

/**
 * One model and its context. Not thread-safe, except [cancel] and [arm]: one thread at a time may call the rest
 * (the Kotlin side holds a lock). The KV cache keeps the last prompt and reply, and the next prompt only processes
 * the tokens after the longest common prefix with it.
 */
class Engine {
public:
    /** Gets each new piece of reply text (always complete UTF-8). Return false to stop. */
    using OnText = std::function<bool(const std::string &)>;

    Engine();
    ~Engine();
    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    /** Loads a GGUF model (replacing any loaded one). threads <= 0: a default. False on failure, see error(). */
    bool load(const std::string & path, int threads, int n_ctx);
    bool loaded() const { return ctx_ != nullptr; }
    /** Frees the model and the context. */
    void unload();

    /**
     * Completes the raw prompt. Returns the reply (without any stop string). On failure returns "" and sets error();
     * when cancelled returns what was generated so far and sets error() to "cancelled".
     */
    std::string generate(const std::string & prompt, int max_tokens, float temperature, const OnText & on_text);

    /** Stops a running load/generate as soon as possible. Safe from any thread. It stays set until [arm]. */
    void cancel() { cancel_.store(true); }
    /** Clears a cancel. The caller arms before each load/generate, under its own lock, so that a cancel aimed at a
     *  request still waiting for the model is not lost and does not stop the next one. */
    void arm() { cancel_.store(false); }
    bool cancelled() const { return cancel_.load(); }

    /** Why the last load/generate failed ("" if it did not). */
    const std::string & error() const { return error_; }
    const Stats & stats() const { return stats_; }
    Sampling & sampling() { return sampling_; }
    /** Tokens currently in the KV cache (for tests). */
    size_t cached_tokens() const { return cached_.size(); }
    const llama_vocab * vocab() const { return vocab_; }
    /** The prompt as generate() tokenizes it: special tokens (<|im_start|>, <think>...) parsed, BOS only if the model
     *  asks for one (Qwen3 does not). False if it cannot be tokenized. */
    bool tokenize(const std::string & text, std::vector<int32_t> & out);

private:
    static bool abort_cb(void * self);
    bool decode_tokens(const std::vector<int32_t> & toks, size_t from, size_t to, bool logits_last);
    void resync_cache(const std::vector<int32_t> & attempted);
    /** Where a recurrent or hybrid model (Qwen3.5) can start: a snapshot whose tokens begin [toks], restored. 0 if none. */
    size_t restore_snapshot(const std::vector<int32_t> & toks);
    void take_snapshot();
    /** The token positions after each line end of the system message: where snapshots are taken. */
    std::vector<size_t> line_ends(const std::vector<int32_t> & toks) const;

    /** The model's state after the first tokens of a prompt (a recurrent state cannot be cut back, so it is saved). */
    struct Snapshot {
        std::vector<int32_t> tokens;
        std::vector<uint8_t> state;
        uint64_t used = 0;
    };
    bool recurrent_ = false;          // the cache cannot be cut back to a shorter prefix (Mamba, Qwen3.5's delta net)
    std::vector<Snapshot> snapshots_;
    uint64_t clock_ = 0;

    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    const llama_vocab * vocab_ = nullptr;
    std::vector<int32_t> cached_;  // exactly the tokens in the KV cache, position i = cached_[i]
    int32_t n_batch_ = 0;
    llama_batch batch_{};  // n_batch_ slots, allocated with the context
    std::atomic<bool> cancel_{false};
    std::string error_;
    Stats stats_;
    Sampling sampling_;
};

}  // namespace llm
