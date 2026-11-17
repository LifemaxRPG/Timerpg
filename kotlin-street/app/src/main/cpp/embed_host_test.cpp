// Memory search on a computer: embed_host_test MODEL.gguf. Embeds a handful of things a shopkeeper might remember and a
// few questions (with the query instruction the app uses), and prints which memory each question finds.
#include "embed_core.h"
#include "llm_core.h"

#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

static float dot(const std::vector<float> & a, const std::vector<float> & b) {
    float s = 0; for (size_t i = 0; i < a.size() && i < b.size(); ++i) s += a[i] * b[i]; return s;
}

int main(int argc, char ** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: %s MODEL.gguf\n", argv[0]); return 2; }
    llm::init_backend({}, nullptr);
    llm::Embedder e;
    if (!e.load(argv[1], 4)) { std::fprintf(stderr, "%s\n", e.error().c_str()); return 1; }
    const std::vector<std::string> mem = {
        "The player said: My sister Clara is getting married next spring. You answered: Congratulations to her.",
        "The player said: I'm looking for work at the docks. You answered: Ask for Henry at the harbor office.",
        "The player said: I lost my dog, a little brown terrier. You answered: I'll keep an eye out for him.",
        "The player said: I think Walter Vance cheated me at cards. You answered: Walter always did play loose with the rules.",
    };
    const std::vector<std::pair<std::string, int>> qs = {
        {"Have you seen my dog anywhere?", 2}, {"Do you remember what I told you about my sister?", 0},
        {"Any luck finding me a job?", 1}, {"What do you think of Walter?", 3},
    };
    const std::string inst = "Instruct: Given what the player says now, find what this person remembers about it\nQuery: ";
    std::vector<std::vector<float>> mv;
    auto t0 = std::chrono::steady_clock::now();
    for (auto & m : mem) mv.push_back(e.embed(m));
    auto t1 = std::chrono::steady_clock::now();
    int ok = 0;
    for (auto & [q, want] : qs) {
        auto v = e.embed(inst + q);
        int best = -1; float bs = -2;
        for (size_t i = 0; i < mv.size(); ++i) { float s = dot(v, mv[i]); if (s > bs) { bs = s; best = (int) i; } }
        std::printf("\"%s\" -> memory %d (%.3f)%s\n", q.c_str(), best, bs, best == want ? "" : "  WRONG");
        ok += best == want;
    }
    std::printf("%d of %zu found; %d dims; %.0f ms per memory\n", ok, qs.size(), e.dim(),
                std::chrono::duration<double, std::milli>(t1 - t0).count() / mem.size());
    return ok == (int) qs.size() ? 0 : 1;
}
