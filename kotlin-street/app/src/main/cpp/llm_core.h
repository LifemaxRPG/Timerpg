// The native language-model engine (llama.cpp), independent of Android/JNI so it also builds and is tested on a
// computer (host_test.cpp). llm_jni.cpp is the thin layer the app talks to.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "guide.h"

struct common_speculative;

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

/** "timerpg.head_vocab" of a GGUF file: K, the rows of its English-first head (docs/ENGINE_TAILORING.md, idea 1;
 *  patches/llama-head-vocab.patch computes only those), 0 for a file with the whole vocabulary. */
int64_t head_vocab(const std::string & path);

/** "dotprod fp16 i8mm"-style list of the ARM features that matter for the kernels (empty elsewhere). */
std::string cpu_features();
/** The CPU kernel libraries to try on this phone, best first (Android arm64). */
std::vector<std::string> cpu_variant_libs();

/** Whether a GPU backend (the Adreno, through OpenCL) / an NPU backend (Hexagon) is loaded. */
bool has_gpu();
bool has_npu();

// ---------------------------------------------------------------------------------------------------- the NPU's power vote

/**
 * How the NPU (ggml-hexagon's HTP sessions) votes its clocks (docs/CONVERSATION.md, "The NPU's power vote"). "auto": full
 * power (burst) only while a reply, bake, load or other compute runs, released (DCVS on, the DSP allowed to sleep)
 * [hold_ms] after the last one ends and at once when a model unloads. Any other name (a GGML_HEXAGON_POWER_MODE profile:
 * "burst", "balanced", ...): that profile for the whole process, as ggml-hexagon did before (the caller sets the
 * environment variable). Set before the backends load. Without our ggml-hexagon patch "auto" changes nothing.
 */
void npu_power_policy(const std::string & mode, int hold_ms);
/** The vote now and its last costs, for the HUD and the probes: e.g. "auto idle, wake 2.1 ms, first decode +0.3 ms". */
std::string npu_power_status();
/** The vote's events since the last call (each burst's hold, wake cost and release), for the app's breadcrumbs. */
std::vector<std::string> npu_power_events();
/** Releases the vote now if no compute holds it (a model unloads). */
void npu_power_release_now(const char * why);

/** Holds the burst vote while it lives ("auto" only; nests; costs a lock when the vote is already burst). */
class NpuBurst {
public:
    explicit NpuBurst(const char * why);
    ~NpuBurst();
    NpuBurst(const NpuBurst &) = delete;
    NpuBurst & operator=(const NpuBurst &) = delete;
private:
    bool held_ = false;
};
/** Called right before a compute starts and after it ends: the first one after a burst vote is logged (wake latency). */
void npu_compute_begin();
void npu_compute_end(int n_tokens);

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
    /** Speculative decoding: tokens guessed ahead (by the MTP head, or by n-gram lookup), and how many of them were right. */
    int32_t drafted = 0;
    int32_t accepted = 0;
    /** Of those, the n-gram lookup's (set_device bits 16-19): copied from the prompt, the reply so far or the pool. */
    int32_t ngram_drafted = 0;
    int32_t ngram_accepted = 0;
    /** Verification passes (one per batch of [last token, guesses...]); gen_tokens / steps is the tokens per pass. */
    int32_t steps = 0;
    /** Fixed-shape steps (set_device bit 20): filler tokens added to short guesses, and how many of them were right. */
    int32_t padded = 0;
    int32_t pad_accepted = 0;
    /** The guide (set_guide): candidate tokens it took away, and steps where every candidate broke a rule. */
    int32_t guide_banned = 0;
    int32_t guide_fallbacks = 0;
};

/** The saved model states of a recurrent or hybrid model (Engine::set_snapshot_dir): where they are and what they cost. */
struct SnapStats {
    int32_t files = 0;           // states of this model on flash
    int64_t file_bytes = 0;      // their size
    int32_t ram = 0;             // states held in RAM (the hot one, or every one without a directory)
    int64_t ram_bytes = 0;
    int64_t state_bytes = 0;     // the size of the last state taken (llama_state_seq_get_size)
    int32_t restores_file = 0;   // restores read from flash / from RAM, since the load
    int32_t restores_ram = 0;
    int64_t restore_ms = 0;      // time in those restores
    int32_t writes = 0;          // states written to flash since the load, and the time it took
    int64_t write_ms = 0;
    int32_t evicted = 0;         // files removed to keep under the budget
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
    /** Where the model runs from the next load: 0 the CPU, 1 the GPU, 2 the NPU (the CPU when there is no such device). */
    /** Bit 4 of [kind] (16): the CPU's fast repacked kernels (the weights copied into the process: faster prompts, but
     *  the whole model in memory the system cannot reclaim); without it the weights stay mapped from the file. */
    /** Bit 5 (32): the attention cache (KV) in 8 bits (q8_0) instead of 16, about half its memory (it needs flash
     *  attention; where that cannot be had the load falls back to the 16-bit cache). */
    /** Bits 12-14: the batch the prompt is processed in (0: 512 tokens, 1: 256, 2: 128): a smaller one shrinks the
     *  compute buffers (memory beside the weights) at some cost in prompt speed. */
    /** Bit 15: on the GPU or NPU, the token embeddings (and so a tied output head) stay on the CPU, mapped from the
     *  file: llama.cpp otherwise copies a tied head to the device beside the mapped embeddings (the same tensor twice,
     *  497 MB for Qwen3.5-4B Q4_0). No effect on a model with its own output weights. */
    void set_device(int kind) { device_ = kind & 3; repack_ = (kind & 16) != 0; kv_q8_ = (kind & 32) != 0; mtp_n_ = (kind >> 8) & 15;
                                const int b = (kind >> 12) & 7; batch_max_ = b == 1 ? 256 : b == 2 ? 128 : 512;
                                head_mapped_ = (kind & (1 << 15)) != 0; ngram_n_ = (kind >> 16) & 15;
                                fixed_steps_ = (kind & (1 << 20)) != 0; n_seq_ = 1 + ((kind >> 24) & 7); }
    /** Bit 20: every guess-and-check pass the same length (1 + the most tokens guessed ahead): a short guess is padded
     *  with filler tokens (checked like any guess: lossless), so llama.cpp and the NPU backend reuse one graph for every
     *  step instead of rebuilding it each time the length changes (ENGINE_TAILORING.md, idea 4). */
    /** Bits 24-26: sequences the context holds at once, minus one (generate_batch: the overnight writer's prompts in one
     *  pass). 0: one, as in play. */
    /**
     * validate()-aware sampling from the next generate() on (ENGINE_TAILORING.md, idea 2; guide.h): tokens that would
     * break the reply check are never sampled. [self]: the speaker's full name; [recent]: their last replies. The prompt
     * itself gives the facts. Off: sampling exactly as before.
     */
    void set_guide(bool on, const std::string & self, const std::vector<std::string> & recent) {
        guide_on_ = on; guide_self_ = self; guide_recent_ = recent;
    }
    bool guide_on() const { return guide_on_; }
    /** The guide's rules for the last reply (for tests). */
    const GuideRules & guide_rules() const { return guide_rules_; }
    /** Bits 16-19 of set_device's kind: tokens to guess ahead by n-gram lookup (0: off). The guess is what followed the
     *  last [ngram_min]..4 tokens where they appeared before: in the prompt (the fact sheet, the conversation so far),
     *  the reply so far, or the pool (set_draft_pool). Checked like MTP's guesses (lossless), tried before the MTP head. */
    void set_ngram_min(int n) { ngram_min_ = n < 1 ? 1 : n; }  // (above 4: no guess is ever made: the same path, for tests)
    /** Text the n-gram guesses may also copy from (the person's lines in the bank, one per line); "" clears it. */
    void set_draft_pool(const std::string & lines);
    /** Whether the loaded model guesses with n-grams (asked for, and its cache can step back over wrong guesses). */
    bool ngram() const { return ctx_ && ngram_used_ > 0; }
    /** Whether the loaded model's cache is in 8 bits. */
    bool kv_q8() const { return ctx_ && kv_q8_used_; }
    /** Whether the loaded model writes with MTP speculative decoding (it has the prediction head and it was asked for). */
    bool mtp() const { return spec_ != nullptr; }
    /** Where the loaded model runs (0 / 1 / 2). */
    int device() const { return ctx_ && devs_.size() > 1 ? device_ : 0; }
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
    /**
     * Saved states (recurrent/hybrid models only) go to files in [dir] instead of RAM: written once where a prompt's
     * system message reaches the end of the shared rules, of the person's persona and of the whole fact sheet, read
     * back (mapped, so the pages stay the system's to drop) on a restore, and kept across loads and sessions (keyed by
     * the model, its cache type and device, and the exact tokens). The directory's files are kept under [budget_bytes]
     * (the least recently used go first). [keep_hot]: the shortest state (the shared rules) also stays in RAM.
     * An empty [dir]: states in RAM as before (up to 8). Takes effect at once and on every later load. [points]: which
     * of them generate() writes (1 the rules, 2 the persona, 4 the fact sheet; bake() writes all three).
     */
    void set_snapshot_dir(const std::string & dir, int64_t budget_bytes, bool keep_hot, int points = 7);
    /**
     * Pre-bakes [prompt]'s states: processes its system message up to the last saved point (rules, persona, fact
     * sheet), writing the states it does not have yet, and leaves the cache there. Returns the tokens the deepest state
     * covers (0: nothing to bake: no system message, or not a recurrent model), -1 on failure (see error()).
     */
    int bake(const std::string & prompt);
    const SnapStats & snap_stats() const { return snap_stats_; }
    /** The token ids of the last reply (for tests: restored and fresh generation must give the same ones). */
    const std::vector<int32_t> & reply_tokens() const { return reply_tokens_; }
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
    /** The n-gram guess for what follows [seq] (its last tokens matched where they appeared before), at most [n_max]. */
    void ngram_draft(const std::vector<int32_t> & seq, int n_max, std::vector<int32_t> & out) const;
    /** generate() with MTP and/or n-gram guesses: the prompt from [at] on (all but its last token), then guess-and-check. */
    std::string generate_mtp(const std::vector<int32_t> & toks, size_t at, int max_tokens, float temperature,
                             const OnText & on_text, int64_t t0);
    void free_mtp();
    /** The token positions after each line end of the system message: where snapshots are taken. */
    std::vector<size_t> line_ends(const std::vector<int32_t> & toks) const;
    /** Where states are saved: every line end in RAM; with a directory, the end of the rules, of the persona and of
     *  the whole fact sheet (see set_snapshot_dir). */
    std::vector<size_t> snapshot_points(const std::vector<int32_t> & toks, int mask = -1) const;
    /** Reads the headers of this model's state files in the directory (again, when it changed). */
    void scan_snapshots(bool force);
    /** Removes the least recently used files until the directory is under its budget. */
    void enforce_budget();
    void stat_snapshots();
    uint64_t model_key() const;
    /** The longest saved state that [toks] starts with (fewer tokens than it), 0 if none. */
    size_t saved_prefix(const std::vector<int32_t> & toks);

    /** The model's state after the first tokens of a prompt (a recurrent state cannot be cut back, so it is saved). */
    struct Snapshot {
        std::vector<int32_t> tokens;
        std::vector<uint8_t> state;   // in RAM (no directory, or the hot one)
        std::string path;             // on flash ("" in RAM only)
        size_t bytes = 0;             // the state's size
        uint64_t used = 0;
    };
    std::string snap_dir_;
    int64_t snap_budget_ = 0;
    bool snap_hot_ = false;
    int snap_points_ = 7;            // which points generate() saves: 1 the rules, 2 the persona, 4 the fact sheet
    uint64_t snap_key_ = 0;
    int64_t snap_dir_mtime_ = -1;
    SnapStats snap_stats_;
    std::vector<int32_t> reply_tokens_;
    /** With a directory: keeps [s]'s state in RAM ([data]: its bytes, when not there yet) if it is the shortest on
     *  flash, and drops every other RAM copy. */
    void keep_hot(Snapshot & s, const uint8_t * data);
    bool recurrent_ = false;
    int device_ = 0;
    bool repack_ = false;
    bool kv_q8_ = false;
    bool kv_q8_used_ = false;
    int mtp_n_ = 0;                          // tokens to guess ahead with the MTP head (0: off)
    int ngram_n_ = 0;                        // tokens to guess ahead by n-gram lookup (0: off)
    int ngram_used_ = 0;                     // what the loaded context allows (0 when its state cannot step back)
    int ngram_min_ = 2;                      // the shortest match a guess is made from (3: right more often, fewer passes saved)
    std::vector<std::vector<int32_t>> pool_; // set_draft_pool's lines, tokenized
    int batch_max_ = 512;                    // the prompt's batch (n_batch = n_ubatch), at most the context
    bool head_mapped_ = false;               // a tied output head kept on the CPU, mapped (set_device bit 15)
    bool fixed_steps_ = false;               // guess-and-check passes padded to one length (set_device bit 20)
    int n_seq_ = 1;                          // sequences the context holds (set_device bits 24-26)
    int n_seq_used_ = 1;                     // what the loaded context has
    std::vector<Stats> batch_stats_;
    std::vector<std::vector<int32_t>> batch_tokens_;
    /** generate()'s prompt reading on sequence 0 (the cache's common prefix, the saved states, the line-end snapshots),
     *  the last token's logits ready. False on failure (error_ set). */
    bool prefill(const std::vector<int32_t> & toks);
    /** Empties sequence 0 (the whole memory when the context has only it). */
    void clear_seq0();
    bool guide_on_ = false;                  // validate()-aware sampling (set_guide)
    std::string guide_self_;
    std::vector<std::string> guide_recent_;
    GuideRules guide_rules_;
    GuideCounts guide_counts_;
    /** The sampler chain: penalties, top-k, the guide (when on), then top-p, temperature and a draw (or the best). */
    llama_sampler * make_chain(float temperature, uint32_t seed);
    llama_model_tensor_buft_override overrides_[2] = {};  // (alive through the load: llama.cpp reads it there)
    llama_context * dft_ = nullptr;          // the MTP head's context (shares the main context's memory)
    common_speculative * spec_ = nullptr;
    std::vector<int32_t> dft_cached_;       // the tokens the head's own cache holds, position i = dft_cached_[i]
    std::vector<ggml_backend_dev_t> devs_;  // the devices it was loaded on, null-terminated          // the cache cannot be cut back to a shorter prefix (Mamba, Qwen3.5's delta net)
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

/** llama.cpp's "graph splits" line for the last model context created ("" if none). */
std::string last_load_info();
/** What llama.cpp said the last load allocated (MiB): [0] weights mapped from the file, [1] weights copied (to a device
 *  or repacked), [2] the attention cache (KV), [3] the recurrent state, [4] compute and output buffers. */
void last_load_mem(double out[5]);
/** Starts [last_load_mem]'s count again (before a load of another kind of model: speech). */
void reset_load_mem();

}  // namespace llm
