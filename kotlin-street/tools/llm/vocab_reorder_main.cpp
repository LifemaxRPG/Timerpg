// Command line for vocab_reorder.h (the same code the app will run on the phone):
//   vocab_reorder SRC.gguf DST.gguf --list head_vocab.txt [--k N] [--no-verify] [--force]
//   vocab_reorder --hash MODEL.gguf      prints the tokenizer's vocab_hash (the list's header must match it)
#include "vocab_reorder.h"

#include "ggml.h"
#include "gguf.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

int main(int argc, char ** argv) {
    if (argc == 3 && std::strcmp(argv[1], "--hash") == 0) {
        std::string err, h = timerpg::vocab_hash(argv[2], err);
        if (h.empty()) { std::fprintf(stderr, "%s\n", err.c_str()); return 1; }
        std::printf("%s\n", h.c_str());
        return 0;
    }
    if (argc == 3 && std::strcmp(argv[1], "--info") == 0) {  // the tensors that move (the metadata is enough: a file's head will do)
        gguf_init_params p{true, nullptr};
        gguf_context * g = gguf_init_from_file(argv[2], p);
        if (!g) { std::fprintf(stderr, "not a GGUF\n"); return 1; }
        const int64_t kt = gguf_find_key(g, "tokenizer.ggml.tokens");
        const int64_t nv = kt >= 0 ? (int64_t) gguf_get_arr_n(g, kt) : -1;
        std::printf("n_vocab %lld, %lld tensors\n", (long long) nv, (long long) gguf_get_n_tensors(g));
        for (int64_t i = 0; i < gguf_get_n_tensors(g); ++i) {
            const int64_t * ne = gguf_get_tensor_ne(g, i);
            if (ne[0] == nv || ne[1] == nv || ne[2] == nv || ne[3] == nv)
                std::printf("  %s %s [%lld, %lld] %.1f MB\n", gguf_get_tensor_name(g, i), ggml_type_name(gguf_get_tensor_type(g, i)),
                            (long long) ne[0], (long long) ne[1], gguf_get_tensor_size(g, i) / 1e6);
        }
        gguf_free(g);
        return 0;
    }
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s SRC.gguf DST.gguf --list head_vocab.txt [--k N] [--no-verify] [--force]\n"
                             "       %s --hash MODEL.gguf\n", argv[0], argv[0]);
        return 2;
    }
    std::string src = argv[1], dst = argv[2], list_path;
    timerpg::ReorderOptions opt;
    for (int i = 3; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--list" && i + 1 < argc) list_path = argv[++i];
        else if (a == "--k" && i + 1 < argc) opt.k = (uint32_t) std::strtoul(argv[++i], nullptr, 10);
        else if (a == "--no-verify") opt.verify = false;
        else if (a == "--force") opt.force = true;
        else { std::fprintf(stderr, "unknown option %s\n", a.c_str()); return 2; }
    }
    std::string err;
    timerpg::HeadVocab list;
    if (list_path.empty() || !timerpg::load_head_vocab(list_path, list, err)) { std::fprintf(stderr, "%s\n", err.empty() ? "--list is required" : err.c_str()); return 2; }
    int last = -1;
    opt.progress = [&](double f) {
        const int pc = (int) (f * 100);
        if (pc != last) { last = pc; std::fprintf(stderr, "\r%3d%%", pc); }
        return true;
    };
    const auto t0 = std::chrono::steady_clock::now();
    const auto r = timerpg::reorder_vocab(src, dst, list, opt, err);
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::fprintf(stderr, "\n");
    switch (r) {
        case timerpg::ReorderResult::Done: std::printf("done in %.1f s: %s (head_vocab %u)\n", s, dst.c_str(), opt.k ? opt.k : list.k); return 0;
        case timerpg::ReorderResult::AlreadyDone: std::printf("already done: %s\n", dst.c_str()); return 0;
        case timerpg::ReorderResult::Cancelled: std::printf("cancelled\n"); return 3;
        default: std::fprintf(stderr, "failed: %s\n", err.c_str()); return 1;
    }
}
