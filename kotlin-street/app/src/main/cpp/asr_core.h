// Speech to text for talking to NPCs: Qwen3-ASR (llama.cpp's multimodal library, mtmd) on the phone's CPU. The model and
// its audio encoder load once; each transcription runs the encoder over the clip and writes the text greedily.
#pragma once

#include <atomic>
#include <cstdint>
#include <string>

struct llama_model;
struct llama_context;
struct mtmd_context;

namespace llm {

class Asr {
public:
    Asr() = default;
    ~Asr();
    Asr(const Asr &) = delete;
    Asr & operator=(const Asr &) = delete;

    /** Loads the model (GGUF) and its audio encoder (mmproj GGUF). False on failure, see error(). */
    bool load(const std::string & model, const std::string & mmproj, int threads);
    bool loaded() const { return mctx_ != nullptr; }
    void unload();

    /** The sample rate transcribe() expects (16000 for Qwen3-ASR); 0 before load. */
    int sample_rate() const { return rate_; }

    /**
     * The words in [n] mono float samples at sample_rate(). [context] is a hint of words to expect (the town's and the
     * people's names), passed to the model as its system message. "" on failure (see error()) or silence.
     */
    std::string transcribe(const float * pcm, size_t n, const std::string & context);

    void cancel() { cancel_.store(true); }
    const std::string & error() const { return error_; }
    /** Milliseconds of the last transcription: encoding the audio, writing the text. */
    int64_t encode_ms() const { return encode_ms_; }
    int64_t total_ms() const { return total_ms_; }

private:
    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    mtmd_context * mctx_ = nullptr;
    int rate_ = 0;
    std::atomic<bool> cancel_{false};
    std::string error_;
    int64_t encode_ms_ = 0, total_ms_ = 0;
};

/** The text after Qwen3-ASR's "language X<asr_text>" header, trimmed (exposed for tests). */
std::string asr_text(const std::string & raw);

}  // namespace llm
