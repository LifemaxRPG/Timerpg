// Calibrated quantization of a dialogue model with our importance matrix (kotlin-street/docs/MODEL_FILES.md, part 2;
// ENGINE_TAILORING.md idea 3). The phone runs this on the charger: the full-precision Hugging Face GGUF (BF16) in,
// Q4_0 (the NPU's) or Q4_K_M out, through llama.cpp's own llama_model_quantize() with exactly the parameters below, so
// the file is the same as `llama-quantize --imatrix ours.gguf --token-embedding-type q8_0 --output-tensor-type q8_0`
// would make. Needs llama.h (libllama) and ggml's gguf API; the app's native library has both.
#pragma once

#include <cstdint>
#include <string>

namespace timerpg {

struct QuantizeOptions {
    std::string ftype = "Q4_0";   // "Q4_0" or "Q4_K_M"
    int threads = 0;              // 0 = every core
    uint64_t max_buf_mib = 512;   // rows kept in memory at once (llama.cpp's default is 8 GiB; this bounds the peak)
    bool keep_ssm = true;         // ssm_alpha/ssm_beta at Q8_0 and ssm_out at Q5_K, as the shipped Hugging Face Q4_0
    bool ssm_out_q8 = false;      // ssm_out at Q8_0 instead of Q5_K (if the NPU backend prefers no K-quants)
};

/** SRC (BF16/F16 GGUF) + IMATRIX (llama-imatrix's GGUF output) -> DST (via DST.part, renamed when complete).
 *  Returns false with err set on failure. Blocking: minutes (see MODEL_FILES.md for the measured time and memory). */
bool quantize_ours(const std::string & src, const std::string & imatrix, const std::string & dst,
                   const QuantizeOptions & opt, std::string & err);

}  // namespace timerpg
