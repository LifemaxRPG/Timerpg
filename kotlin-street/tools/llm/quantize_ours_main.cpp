// Command line for quantize_ours.h (the same code the app will run on the phone), with its time and peak memory:
//   quantize_ours SRC-BF16.gguf IMATRIX.gguf DST.gguf [Q4_0|Q4_K_M] [--threads N] [--max-buf-mib N]
#include "quantize_ours.h"

#include "llama.h"

#include <sys/resource.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

int main(int argc, char ** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s SRC.gguf IMATRIX.gguf DST.gguf [Q4_0|Q4_K_M] [--threads N] [--max-buf-mib N]\n", argv[0]);
        return 2;
    }
    timerpg::QuantizeOptions opt;
    for (int i = 4; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--threads" && i + 1 < argc) opt.threads = std::atoi(argv[++i]);
        else if (a == "--max-buf-mib" && i + 1 < argc) opt.max_buf_mib = std::strtoull(argv[++i], nullptr, 10);
        else opt.ftype = a;
    }
    llama_backend_init();
    std::string err;
    const auto t0 = std::chrono::steady_clock::now();
    const bool ok = timerpg::quantize_ours(argv[1], argv[2], argv[3], opt, err);
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    rusage ru{};
    getrusage(RUSAGE_SELF, &ru);
    std::printf("%s %s in %.0f s (cpu %.0f s), peak RSS %.0f MB (includes the source's mapped pages it touched)\n",
                ok ? "quantized" : "FAILED", opt.ftype.c_str(), s, ru.ru_utime.tv_sec + ru.ru_stime.tv_sec + 0.0, ru.ru_maxrss / 1024.0);
    if (!ok) std::fprintf(stderr, "%s\n", err.c_str());
    llama_backend_free();
    return ok ? 0 : 1;
}
