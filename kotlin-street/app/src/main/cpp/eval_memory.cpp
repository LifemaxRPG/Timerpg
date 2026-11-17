// Memory search quality on a computer: eval_memory CASES.json EMBED.gguf [RERANK.gguf]
// For each thing the player says: the embedder's candidates (top 4), then, with a reranker, its yes-chance for each. A
// memory is recalled when it passes the rule; prints how many related lines found the right memory, how many unrelated
// lines recalled nothing, and the timings.
#include "embed_core.h"
#include "llm_core.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <cstdlib>
#include <nlohmann/json.hpp>

using clk = std::chrono::steady_clock;
static double ms(clk::time_point a) { return std::chrono::duration<double, std::milli>(clk::now() - a).count(); }

int main(int argc, char ** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: %s cases.json embed.gguf [rerank.gguf]\n", argv[0]); return 2; }
    auto j = nlohmann::json::parse(std::ifstream(argv[1]));
    std::vector<std::string> mem = j["memories"].get<std::vector<std::string>>();
    llm::init_backend({}, nullptr);
    llm::Embedder e;
    if (!e.load(argv[2], 4)) { std::fprintf(stderr, "%s\n", e.error().c_str()); return 1; }
    llm::Reranker r;
    const bool rr = argc > 3;
    const float thr = argc > 4 ? std::atof(argv[4]) : .5f;
    if (rr && !r.load(argv[3], 4)) { std::fprintf(stderr, "%s\n", r.error().c_str()); return 1; }
    const std::string inst = "Given what the player says now, find what this person remembers about it";
    std::vector<std::vector<float>> mv;
    for (auto & m : mem) mv.push_back(e.embed(m));
    int hit = 0, nrel = 0, quiet = 0, nun = 0; double tq = 0;
    for (auto & q : j["queries"]) {
        const std::string text = q[0]; const int want = q[1];
        auto t0 = clk::now();
        auto v = e.embed("Instruct: " + inst + "\nQuery: " + text);
        std::vector<std::pair<float, int>> s;
        double mean = 0, sq = 0;
        for (size_t i = 0; i < mv.size(); ++i) { float d = 0; for (size_t k = 0; k < v.size(); ++k) d += v[k] * mv[i][k]; s.push_back({d, (int) i}); mean += d; sq += d * d; }
        mean /= s.size(); const double sd = std::sqrt(std::max(1e-9, sq / s.size() - mean * mean));
        std::sort(s.rbegin(), s.rend());
        int got = -1; float conf = 0;
        if (!rr) {
            if ((s[0].first - mean) / sd >= 2.0 && s[0].first >= .3f) { got = s[0].second; conf = (float) ((s[0].first - mean) / sd); }
        } else {
            // up to two memories the reranker says yes to (the app hands both to the model)
            std::vector<std::pair<float, int>> yes;
            for (int c = 0; c < 4; ++c) {
                const float p = r.score(inst, text, mem[(size_t) s[(size_t) c].second]);
                if (p >= thr) yes.push_back({p, s[(size_t) c].second});
            }
            std::sort(yes.rbegin(), yes.rend());
            if (yes.size() > 2) yes.resize(2);
            for (auto & y : yes) if (y.second == want) got = want;
            if (got < 0 && !yes.empty()) got = yes[0].second;
            conf = yes.empty() ? 0 : yes[0].first;
        }
        tq += ms(t0);
        if (want >= 0) { ++nrel; hit += got == want; } else { ++nun; quiet += got < 0; }
        std::printf("%-55s -> %3d (want %3d, %.2f)%s\n", text.c_str(), got, want, conf, got == want ? "" : "  <-- wrong");
    }
    std::printf("\nrelated: %d of %d found the right memory; unrelated: %d of %d recalled nothing; %.0f ms per line\n", hit, nrel, quiet, nun, tq / (nrel + nun));
    return 0;
}
