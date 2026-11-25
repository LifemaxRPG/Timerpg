// Server-side checks for the English-first vocabulary (kotlin-street/docs/MODEL_FILES.md). Links llama.cpp.
//
//   vocab_test count MODEL OUT.tsv [--special] FILE...
//       token counts over text files (chunks of ~64 KB cut at line ends; --special parses <|im_start|> etc.)
//   vocab_test roundtrip ORIG PERM LIST FILE...
//       every chunk tokenizes to the same tokens through the permutation (ORIG ids mapped == PERM ids), and PERM's
//       tokens detokenize back to the exact text
//   vocab_test decode MODEL LIST PROMPTS [--permuted] [--max 56] [--ks 32768,49152,65536] [--samples N] [--temp T]
//                     [--threads N] [--out FILE.jsonl] [--dump FILE] [--no-repack]
//       replies to every prompt (prompts separated by lines "%%", optional first line "# label"), greedy then N
//       sampled (top-k 40, top-p 0.9, repeat penalty 1.15 over the last 64 generated tokens: the app's chain), all in
//       the ORIGINAL numbering (--permuted: MODEL is the reordered file, its logits mapped back through LIST). At every
//       step, on the penalized logits the sampler sees: how many of the top-40 lie outside the first K, whether the
//       top-p nucleus holds one, the best rank any token outside K reaches, the softmax mass outside K.
//       --dump: per prompt its tokens, per step a hash of all logits and the choice, per run the reply.
//   vocab_test compare DUMP_ORIG DUMP_PERM
//       the two dumps line by line: identical tokens, bit-identical logits (by hash), identical choices and replies
//   vocab_test forms MODEL LIST OUT.tsv [N]
//       line-start forms of the first N ranked word tokens (make_head_vocab.py --forms)
#include "llama.h"
#include "vocab_reorder.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

std::string read_file(const std::string & p) {
    std::ifstream f(p, std::ios::binary);
    std::stringstream ss; ss << f.rdbuf();
    return ss.str();
}

std::vector<std::string> chunks_of(const std::string & text, size_t size = 64 << 10) {
    std::vector<std::string> out;
    size_t at = 0;
    while (at < text.size()) {
        size_t end = std::min(text.size(), at + size);
        if (end < text.size()) { size_t nl = text.find('\n', end); end = nl == std::string::npos ? text.size() : nl + 1; }
        out.push_back(text.substr(at, end - at));
        at = end;
    }
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab * v, const std::string & s, bool special) {
    std::vector<llama_token> t(s.size() + 16);
    int n = llama_tokenize(v, s.data(), (int32_t) s.size(), t.data(), (int32_t) t.size(), false, special);
    if (n < 0) { t.resize((size_t) -n); n = llama_tokenize(v, s.data(), (int32_t) s.size(), t.data(), (int32_t) t.size(), false, special); }
    t.resize((size_t) std::max(0, n));
    return t;
}

std::string detok(const llama_vocab * v, const std::vector<llama_token> & t, bool special) {
    std::string s(t.size() * 16 + 64, '\0');
    int n = llama_detokenize(v, t.data(), (int32_t) t.size(), s.data(), (int32_t) s.size(), false, special);
    if (n < 0) { s.resize((size_t) -n); n = llama_detokenize(v, t.data(), (int32_t) t.size(), s.data(), (int32_t) s.size(), false, special); }
    s.resize((size_t) std::max(0, n));
    return s;
}

std::string piece(const llama_vocab * v, llama_token t) {
    char b[256]; int n = llama_token_to_piece(v, t, b, sizeof b, 0, true);
    return n > 0 ? std::string(b, (size_t) n) : std::string();
}

bool g_repack = true;

llama_model * load(const std::string & path, bool vocab_only) {
    auto mp = llama_model_default_params();
    mp.vocab_only = vocab_only;
    mp.n_gpu_layers = 0;
    mp.use_extra_bufts = g_repack;  // false: no repacked heap copy of the weights (less memory, slower on a CPU)
    llama_model * m = llama_model_load_from_file(path.c_str(), mp);
    if (!m) { std::fprintf(stderr, "cannot load %s\n", path.c_str()); std::exit(1); }
    return m;
}

struct Perm { std::vector<uint32_t> perm, inv; };
Perm make_perm(const timerpg::HeadVocab & l) {
    Perm p; p.perm.resize(l.n_vocab); p.inv.assign(l.n_vocab, UINT32_MAX);
    size_t j = 0;
    for (uint32_t id : l.ranked) { p.inv[id] = (uint32_t) j; p.perm[j++] = id; }
    for (uint32_t id = 0; id < l.n_vocab; ++id) if (p.inv[id] == UINT32_MAX) { p.inv[id] = (uint32_t) j; p.perm[j++] = id; }
    return p;
}
timerpg::HeadVocab load_list(const std::string & path) {
    timerpg::HeadVocab l; std::string err;
    if (!timerpg::load_head_vocab(path, l, err)) { std::fprintf(stderr, "%s\n", err.c_str()); std::exit(1); }
    return l;
}

int cmd_count(int argc, char ** argv) {
    if (argc < 4) return 2;
    llama_model * m = load(argv[2], true);
    const llama_vocab * v = llama_model_get_vocab(m);
    const int nv = llama_vocab_n_tokens(v);
    bool special = false;
    std::vector<std::string> files;
    for (int i = 4; i < argc; ++i) { if (!std::strcmp(argv[i], "--special")) special = true; else files.push_back(argv[i]); }
    std::vector<uint64_t> total(nv, 0);
    uint64_t ntok = 0, nbytes = 0;
    for (const auto & f : files) {
        const auto ch = chunks_of(read_file(f));
        const unsigned nt = std::max(1u, std::thread::hardware_concurrency());
        std::vector<std::vector<uint64_t>> part(nt, std::vector<uint64_t>(nv, 0));
        std::atomic<size_t> next{0};
        std::vector<std::thread> th;
        for (unsigned t = 0; t < nt; ++t) th.emplace_back([&, t] {
            for (size_t i; (i = next++) < ch.size();) for (llama_token id : tokenize(v, ch[i], special)) part[t][id]++;
        });
        for (auto & t : th) t.join();
        uint64_t n = 0;
        for (unsigned t = 0; t < nt; ++t) for (int i = 0; i < nv; ++i) { total[i] += part[t][i]; n += part[t][i]; }
        for (const auto & c : ch) nbytes += c.size();
        ntok += n;
        std::fprintf(stderr, "%s: %zu chunks, %llu tokens\n", f.c_str(), ch.size(), (unsigned long long) n);
    }
    FILE * o = std::fopen(argv[3], "w");
    std::fprintf(o, "# tokens %llu bytes %llu\n", (unsigned long long) ntok, (unsigned long long) nbytes);
    for (int i = 0; i < nv; ++i) if (total[i]) std::fprintf(o, "%d\t%llu\n", i, (unsigned long long) total[i]);
    std::fclose(o);
    llama_model_free(m);
    return 0;
}

int cmd_roundtrip(int argc, char ** argv) {
    if (argc < 6) return 2;
    llama_model * a = load(argv[2], true), * b = load(argv[3], true);
    const llama_vocab * va = llama_model_get_vocab(a), * vb = llama_model_get_vocab(b);
    const Perm p = make_perm(load_list(argv[4]));
    uint64_t chunks = 0, tokens = 0, bad_ids = 0, bad_text = 0, bad_text_orig = 0;
    for (int i = 5; i < argc; ++i) {
        const bool special = std::strstr(argv[i], ".prompts") != nullptr;  // our prompt files hold ChatML specials
        const auto ch = chunks_of(read_file(argv[i]));
        std::atomic<uint64_t> bi{0}, bt{0}, bto{0}, nt{0};
        std::atomic<size_t> next{0};
        std::vector<std::thread> th;
        for (unsigned t = 0; t < std::max(1u, std::thread::hardware_concurrency()); ++t) th.emplace_back([&] {
            for (size_t k; (k = next++) < ch.size();) {
                const auto ta = tokenize(va, ch[k], special), tb = tokenize(vb, ch[k], special);
                nt += ta.size();
                bool same = ta.size() == tb.size();
                for (size_t j = 0; same && j < ta.size(); ++j) same = p.inv[(size_t) ta[j]] == (uint32_t) tb[j];
                if (!same) bi++;
                if (detok(vb, tb, special) != detok(va, ta, special)) bt++;
                if (detok(va, ta, special) != ch[k]) bto++;
            }
        });
        for (auto & t : th) t.join();
        std::printf("%s: %zu chunks, %llu tokens, id mismatches %llu, text differs from original model's %llu, "
                    "original model's detokenization differs from the text %llu\n", argv[i], ch.size(),
                    (unsigned long long) nt.load(), (unsigned long long) bi.load(), (unsigned long long) bt.load(), (unsigned long long) bto.load());
        chunks += ch.size(); tokens += nt; bad_ids += bi; bad_text += bt; bad_text_orig += bto;
    }
    std::printf("TOTAL %llu chunks, %llu tokens: id mismatches %llu, permuted-vs-original text mismatches %llu (lossy text in the original tokenizer itself: %llu)\n",
                (unsigned long long) chunks, (unsigned long long) tokens, (unsigned long long) bad_ids, (unsigned long long) bad_text,
                (unsigned long long) bad_text_orig);
    llama_model_free(a); llama_model_free(b);
    return bad_ids || bad_text ? 1 : 0;
}

struct Prompt { std::string label, text; };
std::vector<Prompt> read_prompts(const std::string & file) {
    std::vector<Prompt> out;
    std::istringstream in(read_file(file));
    std::string line, cur, label;
    bool first = true;
    auto flush = [&] {
        while (!cur.empty() && cur.back() == '\n' && cur.size() > 1 && cur[cur.size() - 2] == '\n') cur.pop_back();
        if (!cur.empty()) out.push_back({label, cur});
        cur.clear(); label.clear(); first = true;
    };
    while (std::getline(in, line)) {
        if (line == "%%") { flush(); continue; }
        if (first && line.rfind("# ", 0) == 0) { label = line.substr(2); first = false; continue; }
        first = false;
        cur += line; cur += '\n';
    }
    flush();
    // the prompts end with "<think>\n\n</think>\n\n": the file's last line break per prompt is part of it
    return out;
}

struct Ctx {
    llama_model * m = nullptr; llama_context * c = nullptr; const llama_vocab * v = nullptr;
    void init(const std::string & path, int threads) {
        m = load(path, false);
        v = llama_model_get_vocab(m);
        auto cp = llama_context_default_params();
        cp.n_ctx = 1024; cp.n_batch = 256; cp.n_ubatch = 256; cp.n_seq_max = 1; cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.no_perf = true;
        c = llama_init_from_model(m, cp);
        if (!c) { std::fprintf(stderr, "no context for %s\n", path.c_str()); std::exit(1); }
    }
    bool eval(const std::vector<llama_token> & t) {
        for (size_t i = 0; i < t.size(); i += 256) {
            const int n = (int) std::min<size_t>(256, t.size() - i);
            llama_batch b = llama_batch_get_one(const_cast<llama_token *>(t.data() + i), n);
            if (llama_decode(c, b) != 0) return false;
        }
        return true;
    }
};

void penalize(std::vector<float> & l, const std::vector<llama_token> & gen, float pen = 1.15f, size_t last_n = 64) {
    std::vector<uint8_t> seen;
    const size_t from = gen.size() > last_n ? gen.size() - last_n : 0;
    std::vector<llama_token> ids(gen.begin() + (long) from, gen.end());
    std::sort(ids.begin(), ids.end()); ids.erase(std::unique(ids.begin(), ids.end()), ids.end());
    for (llama_token t : ids) l[(size_t) t] = l[(size_t) t] > 0 ? l[(size_t) t] / pen : l[(size_t) t] * pen;
}

/** The app's chain after the penalties: top-k 40, top-p 0.9, temperature, a draw; temp <= 0: the best. Ties by id. */
llama_token choose(const std::vector<float> & l, float temp, std::mt19937 & rng) {
    std::vector<int> idx(l.size());
    for (size_t i = 0; i < l.size(); ++i) idx[i] = (int) i;
    std::partial_sort(idx.begin(), idx.begin() + 40, idx.end(), [&](int a, int b) { return l[a] != l[b] ? l[a] > l[b] : a < b; });
    if (temp <= 0) return idx[0];
    std::vector<double> p(40);
    double mx = l[idx[0]], sum = 0;
    for (int i = 0; i < 40; ++i) { p[i] = std::exp((l[idx[i]] - mx)); sum += p[i]; }
    double cum = 0; int keep = 40;
    for (int i = 0; i < 40; ++i) { cum += p[i] / sum; if (cum >= 0.9) { keep = i + 1; break; } }
    sum = 0;
    for (int i = 0; i < keep; ++i) { p[i] = std::exp((l[idx[i]] - mx) / temp); sum += p[i]; }
    double r = std::uniform_real_distribution<double>(0, sum)(rng);
    for (int i = 0; i < keep; ++i) { r -= p[i]; if (r <= 0) return idx[i]; }
    return idx[keep - 1];
}

std::string json_str(const std::string & s) {
    std::string o = "\"";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += (char) c; }
        else if (c == '\n') o += "\\n";
        else if (c < 0x20) { char b[8]; std::snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += (char) c;
    }
    return o + "\"";
}

int cmd_decode(int argc, char ** argv) {
    if (argc < 5) return 2;
    std::string model = argv[2], out_path, dump_path;
    const timerpg::HeadVocab list = load_list(argv[3]);
    const std::vector<Prompt> prompts = read_prompts(argv[4]);
    int max_tok = 56, samples = 0, threads = 4;
    float temp = 0.7f;
    bool permuted = false;
    std::vector<uint32_t> ks = {32768, 49152, 65536};
    for (int i = 5; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&] { return i + 1 < argc ? std::string(argv[++i]) : std::string(); };
        if (a == "--permuted") permuted = true;
        else if (a == "--no-repack") g_repack = false;
        else if (a == "--max") max_tok = std::atoi(next().c_str());
        else if (a == "--samples") samples = std::atoi(next().c_str());
        else if (a == "--temp") temp = (float) std::atof(next().c_str());
        else if (a == "--threads") threads = std::atoi(next().c_str());
        else if (a == "--out") out_path = next();
        else if (a == "--dump") dump_path = next();
        else if (a == "--ks") { ks.clear(); std::stringstream ss(next()); std::string x; while (std::getline(ss, x, ',')) ks.push_back((uint32_t) std::stoul(x)); }
    }
    const Perm p = make_perm(list);
    for (uint32_t k : ks) if (k > list.ranked.size()) { std::fprintf(stderr, "K %u is beyond the ranked list (%zu)\n", k, list.ranked.size()); return 1; }
    Ctx A;
    A.init(model, threads);
    const int nv = llama_vocab_n_tokens(A.v);
    // the model's numbering <-> the original numbering (identity for the original model)
    auto orig_id = [&](int t) { return permuted ? (int) p.perm[(size_t) t] : t; };
    auto model_id = [&](int o) { return permuted ? (llama_token) p.inv[(size_t) o] : (llama_token) o; };
    FILE * out = out_path.empty() ? nullptr : std::fopen(out_path.c_str(), "w");
    FILE * dump = dump_path.empty() ? nullptr : std::fopen(dump_path.c_str(), "w");

    struct PerK { uint64_t steps_out_top40 = 0, tokens_out_top40 = 0, steps_out_nucleus = 0; double max_p_nucleus = 0;
                  long best_rank = -1; double max_mass = 0; std::string worst; std::map<int, long> outside; };
    std::vector<PerK> pk(ks.size());
    uint64_t steps = 0, runs = 0;
    const auto t0 = std::chrono::steady_clock::now();

    for (size_t pi = 0; pi < prompts.size(); ++pi) {
        const auto & pr = prompts[pi];
        const auto tm = tokenize(A.v, pr.text, true);
        if (dump) {  // the prompt's tokens in the original numbering
            uint64_t h = 1469598103934665603ULL;
            for (llama_token t : tm) { h ^= (uint64_t) (uint32_t) orig_id(t); h *= 1099511628211ULL; }
            std::fprintf(dump, "P %zu %zu %016llx\n", pi, tm.size(), (unsigned long long) h);
        }
        for (int run = 0; run <= samples; ++run) {
            const float t = run == 0 ? 0.0f : temp;
            std::mt19937 rng(1234u + (uint32_t) pi * 7919u + (uint32_t) run);
            llama_memory_clear(llama_get_memory(A.c), true);
            if (!A.eval(tm)) { std::fprintf(stderr, "decode failed\n"); return 1; }
            std::vector<llama_token> gen;    // original numbering
            std::vector<llama_token> gen_m;  // the model's numbering
            std::vector<float> la(nv);
            std::vector<int> idx(nv);
            for (int step = 0; step < max_tok; ++step) {
                const float * raw = llama_get_logits_ith(A.c, -1);
                for (int i = 0; i < nv; ++i) la[(size_t) orig_id(i)] = raw[i];  // original order
                if (dump) {
                    uint64_t h = 1469598103934665603ULL;
                    for (int i = 0; i < nv; ++i) { uint32_t b; std::memcpy(&b, &la[(size_t) i], 4); h ^= b; h *= 1099511628211ULL; }
                    std::fprintf(dump, "S %zu %d %d %016llx", pi, run, step, (unsigned long long) h);
                }
                penalize(la, gen);
                for (int i = 0; i < nv; ++i) idx[i] = i;
                std::partial_sort(idx.begin(), idx.begin() + 40, idx.end(), [&](int a, int b) { return la[a] != la[b] ? la[a] > la[b] : a < b; });
                const double mx = la[idx[0]];
                double z = 0, s40 = 0;
                for (int i = 0; i < nv; ++i) z += std::exp((double) la[i] - mx);
                for (int r = 0; r < 40; ++r) s40 += std::exp((double) la[idx[r]] - mx);
                for (size_t ki = 0; ki < ks.size(); ++ki) {
                    const uint32_t K = ks[ki];
                    int out40 = 0;
                    for (int r = 0; r < 40; ++r) if (p.inv[(size_t) idx[r]] >= K) { out40++; pk[ki].outside[idx[r]]++; }
                    if (out40) {
                        pk[ki].steps_out_top40++; pk[ki].tokens_out_top40 += (uint64_t) out40;
                        // top-p 0.9 over the top-40 (softmax at temperature 1, as llama.cpp's chain): an outside token in it?
                        double cum = 0;
                        for (int r = 0; r < 40; ++r) {
                            const double pr_r = std::exp((double) la[idx[r]] - mx) / s40;
                            if (p.inv[(size_t) idx[r]] >= K) { pk[ki].steps_out_nucleus++; pk[ki].max_p_nucleus = std::max(pk[ki].max_p_nucleus, pr_r); break; }
                            cum += pr_r;
                            if (cum >= 0.9) break;
                        }
                    }
                    float best = -INFINITY; int best_id = -1; double mass = 0;
                    for (int i = 0; i < nv; ++i) if (p.inv[(size_t) i] >= K) {
                        mass += std::exp((double) la[i] - mx);
                        if (la[i] > best) { best = la[i]; best_id = i; }
                    }
                    long rank = 1;
                    for (int i = 0; i < nv; ++i) rank += la[i] > best;
                    pk[ki].max_mass = std::max(pk[ki].max_mass, mass / z);
                    if (pk[ki].best_rank < 0 || rank < pk[ki].best_rank) {
                        pk[ki].best_rank = rank;
                        pk[ki].worst = "prompt " + std::to_string(pi) + " run " + std::to_string(run) + " step " + std::to_string(step) +
                                       " token " + std::to_string(best_id) + " " + json_str(piece(A.v, model_id(best_id)));
                    }
                }
                steps++;
                const llama_token next = choose(la, t, rng);
                if (dump) std::fprintf(dump, " %d\n", next);
                gen.push_back(next);
                gen_m.push_back(model_id(next));
                if (llama_vocab_is_eog(A.v, gen_m.back())) break;
                std::vector<llama_token> one{gen_m.back()};
                if (!A.eval(one)) return 1;
            }
            runs++;
            const std::string text = detok(A.v, gen_m, false);
            if (dump) std::fprintf(dump, "R %zu %d %s\n", pi, run, json_str(text).c_str());
            if (out) std::fprintf(out, "{\"prompt\":%zu,\"label\":%s,\"run\":%d,\"temp\":%.2f,\"tokens\":%zu,\"reply\":%s}\n",
                                  pi, json_str(pr.label).c_str(), run, t, gen.size(), json_str(text).c_str());
            if (run == 0) std::fprintf(stderr, "[%zu/%zu] %s: %s\n", pi + 1, prompts.size(), pr.label.c_str(), text.c_str());
        }
    }
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("prompts %zu, runs %llu (greedy + %d sampled at %.2f), decode steps %llu, %.0f s\n", prompts.size(),
                (unsigned long long) runs, samples, temp, (unsigned long long) steps, secs);
    for (size_t ki = 0; ki < ks.size(); ++ki) {
        std::printf("K=%u: steps with a top-p 0.9 nucleus token outside K: %llu (max p %.3g); with a top-40 token outside K: %llu "
                    "(%llu tokens); best rank of any token outside K: %ld (%s); max softmax mass outside K: %.3g\n", ks[ki],
                    (unsigned long long) pk[ki].steps_out_nucleus, pk[ki].max_p_nucleus, (unsigned long long) pk[ki].steps_out_top40,
                    (unsigned long long) pk[ki].tokens_out_top40, pk[ki].best_rank, pk[ki].worst.c_str(), pk[ki].max_mass);
        std::vector<std::pair<long, int>> o;
        for (auto & kv : pk[ki].outside) o.push_back({kv.second, kv.first});
        std::sort(o.rbegin(), o.rend());
        std::printf("  most frequent top-40 tokens outside K:");
        for (size_t i = 0; i < std::min<size_t>(25, o.size()); ++i)
            std::printf(" %d%s x%ld", o[i].second, json_str(piece(A.v, model_id(o[i].second))).c_str(), o[i].first);
        std::printf("\n");
    }
    if (out) std::fclose(out);
    if (dump) std::fclose(dump);
    return 0;
}

/** Two dumps of `decode --dump` (the original model, the permuted one): every prompt's tokens, every step's logits
 *  (hashed in the original order) and choice, every reply, compared line by line. */
int cmd_compare(int argc, char ** argv) {
    if (argc < 4) return 2;
    std::ifstream a(argv[2]), b(argv[3]);
    std::string la, lb;
    uint64_t lines = 0, diff = 0, prompts = 0, steps = 0, replies = 0;
    while (true) {
        const bool ga = (bool) std::getline(a, la), gb = (bool) std::getline(b, lb);
        if (!ga && !gb) break;
        if (ga != gb) { std::printf("dumps differ in length\n"); diff++; break; }
        lines++;
        if (la[0] == 'P') prompts++; else if (la[0] == 'S') steps++; else if (la[0] == 'R') replies++;
        if (la != lb) { if (diff < 5) std::printf("differs:\n  %s\n  %s\n", la.c_str(), lb.c_str()); diff++; }
    }
    std::printf("compared %llu lines: %llu prompts (tokens), %llu steps (all logits, bit for bit by hash, and the choice), "
                "%llu replies (text): %llu differ\n", (unsigned long long) lines, (unsigned long long) prompts,
                (unsigned long long) steps, (unsigned long long) replies, (unsigned long long) diff);
    return diff ? 1 : 0;
}

/** Line-start forms: for each of the first N ranked tokens that is a space + word (" word"), the tokens of "word",
 *  "Word" and "\"Word" with no space before them (a reply's first word, a word after a line break or a quote).
 *  Writes "source_id<TAB>form ids..." per source token, in rank order (make_head_vocab.py --forms). */
int cmd_forms(int argc, char ** argv) {
    if (argc < 5) return 2;
    llama_model * m = load(argv[2], true);
    const llama_vocab * v = llama_model_get_vocab(m);
    const timerpg::HeadVocab l = load_list(argv[3]);
    FILE * o = std::fopen(argv[4], "w");
    const size_t n = argc > 5 ? (size_t) std::atol(argv[5]) : l.ranked.size();
    size_t written = 0;
    for (size_t r = 0; r < std::min(n, l.ranked.size()); ++r) {
        const std::string pc = piece(v, (llama_token) l.ranked[r]);
        if (pc.size() < 2 || pc[0] != ' ' || !std::isalpha((unsigned char) pc[1])) continue;
        std::string w = pc.substr(1), W = w;
        W[0] = (char) std::toupper((unsigned char) W[0]);
        std::fprintf(o, "%u", l.ranked[r]);
        for (const std::string & f : {w, W, std::string("\"") + W})
            for (llama_token t : tokenize(v, f, false)) std::fprintf(o, "\t%d", t);
        std::fprintf(o, "\n");
        written++;
    }
    std::fclose(o);
    std::fprintf(stderr, "%zu source tokens\n", written);
    llama_model_free(m);
    return 0;
}

}  // namespace

int main(int argc, char ** argv) {
    llama_log_set([](ggml_log_level l, const char * s, void *) { if (l >= GGML_LOG_LEVEL_ERROR) std::fputs(s, stderr); }, nullptr);
    llama_backend_init();
    int r = 2;
    if (argc >= 2 && !std::strcmp(argv[1], "count")) r = cmd_count(argc, argv);
    else if (argc >= 2 && !std::strcmp(argv[1], "roundtrip")) r = cmd_roundtrip(argc, argv);
    else if (argc >= 2 && !std::strcmp(argv[1], "decode")) r = cmd_decode(argc, argv);
    else if (argc >= 2 && !std::strcmp(argv[1], "compare")) r = cmd_compare(argc, argv);
    else if (argc >= 2 && !std::strcmp(argv[1], "forms")) r = cmd_forms(argc, argv);
    if (r == 2) std::fprintf(stderr, "usage: see the comment at the top of vocab_test.cpp\n");
    llama_backend_free();
    return r;
}
