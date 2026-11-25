// Host test for the native engine (llm_core), run on a computer by tools/llm-host-test.sh:
//   llm_host_test <model.gguf> [--threads N] [--ctx N] [--max N] [--temp T] [--prompts FILE] [--dialogue FILE]
//                 [--npc WHO]... [--json-out FILE] [--no-checks] [--seed N] [--cpu-lib NAME]... [--verbose]
// Checks the text helpers and the failure paths (missing/invalid model, cancel, too-long prompt), then answers real
// NPC prompts (made by our game logic, src/llm/factsheet.ts brief() + toPrompt()) and prints replies and timings.
// --prompts FILE: prompts separated by lines "%%" (an optional first line "# label" names one).
// --dialogue FILE: the app's assets/dialogue.json instead: every prompt the model may voice (prompt != null), in the
//   file's order, identical prompts once (the app writes those once too). --npc WHO (repeatable) keeps only the people
//   whose id, full name, first name or building (bN) is WHO.
// --json-out FILE: every reply with its prompt, must/self words, timings and how it ended, for the check with the
//   app's own validate() (tools/llm-dialogue-check.sh runs app/src/test/kotlin/HostRepliesTest.kt on it).
// --no-checks: only the replies (skips the text helper, failure path and cancel checks).
// --seed N: the sampler's seed (default 42, so runs repeat; the app draws a new one for every reply).
// --cpu-lib NAME: with a -DLLM_HOST_DL=ON build, the CPU kernel libraries to try by name, in order, as the app does.
// --snapshot-test DIR: the saved states on flash in DIR (emptied): a new session restores them and answers token for
//   token as the fresh prefill did (greedy), pre-baked states, the hot one, the budget; then exits.
// --ngram N [--ngram-min M]: guess N tokens ahead by n-gram lookup. --spec-test: those guesses against none, greedy and
//   sampled (token-identical replies), with and without the speaker's other lines as the pool; tok/s and acceptance.
#include "llm_core.h"

#include <nlohmann/json.hpp>  // llama.cpp's vendored copy

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <dirent.h>
#include <sys/stat.h>

namespace {

struct Prompt {
    std::string label;
    std::vector<std::string> must;  // words validate() will require in the reply
    std::string self;               // the speaker's own name (an introduction is rejected)
    std::string text;
    // from dialogue.json
    std::string npc, name, building, intent, templ;
};

// Made with the game's own code (seed "eta", 2025): brief(ctx, intent, narrate(ctx, intent)) and toPrompt().
// The 2nd and 3rd share the whole system prompt (fact sheet); all share the RULES line.
const std::vector<Prompt> kPrompts = {
    {"William Calloway: family",
     {"Louise"}, "William Calloway",
     R"P(<|im_start|>system
Roleplay a resident of a small town and reply to what the player says. 1-2 short spoken sentences. Do not introduce yourself. No actions, no quotes, no modern slang. Use only the facts below; never invent names or numbers.
FACTS
You are William Calloway, 92, a man in Eta Springs, 2025. You are gruff and guarded.
The player is an acquaintance.
Married to Louise Radcliffe.
Children: Frank.
Example of the kind of answer: I'm married to Louise Radcliffe. One child: Frank.<|im_end|>
<|im_start|>user
Tell me about your family.<|im_end|>
<|im_start|>assistant
<think>

</think>

)P"},
    {"Lois Fairchild: work",
     {"Fairchild"}, "Lois Fairchild",
     R"P(<|im_start|>system
Roleplay a resident of a small town and reply to what the player says. 1-2 short spoken sentences. Do not introduce yourself. No actions, no quotes, no modern slang. Use only the facts below; never invent names or numbers.
FACTS
You are Lois Fairchild, 81, a woman in Eta Springs, 2025. You are gruff and guarded.
The player is an acquaintance.
Your job: Fairchild's Music Store (owner).
You are poor.
Example of the kind of answer: I run Fairchild's Music Store. It keeps the lights on.<|im_end|>
<|im_start|>user
What do you do for work?<|im_end|>
<|im_start|>assistant
<think>

</think>

)P"},
    {"Lois Fairchild: same fact sheet, a follow-up question",
     {"Fairchild"}, "Lois Fairchild",
     R"P(<|im_start|>system
Roleplay a resident of a small town and reply to what the player says. 1-2 short spoken sentences. Do not introduce yourself. No actions, no quotes, no modern slang. Use only the facts below; never invent names or numbers.
FACTS
You are Lois Fairchild, 81, a woman in Eta Springs, 2025. You are gruff and guarded.
The player is an acquaintance.
Your job: Fairchild's Music Store (owner).
You are poor.
Example of the kind of answer: I run Fairchild's Music Store. It keeps the lights on.<|im_end|>
<|im_start|>user
And how is business these days?<|im_end|>
<|im_start|>assistant
<think>

</think>

)P"},
    {"Dorothy Fairchild: rumors",
     {"Lois"}, "Dorothy Fairchild",
     R"P(<|im_start|>system
Roleplay a resident of a small town and reply to what the player says. 1-2 short spoken sentences. Do not introduce yourself. No actions, no quotes, no modern slang. Use only the facts below; never invent names or numbers.
FACTS
You are Dorothy Fairchild, 79, a woman in Eta Springs, 2025. You are gruff and guarded.
The player is a friend.
You are quietly telling the player this secret: Lois Fairchild is deep in debt and hiding how bad it is. Say it in your own words and ask them to keep it quiet.
Example of the kind of answer: Keep this quiet, but Lois Fairchild is deep in debt and hiding how bad it is.<|im_end|>
<|im_start|>user
Heard anything interesting lately?<|im_end|>
<|im_start|>assistant
<think>

</think>

)P"},
    {"William Calloway: chat",
     {}, "William Calloway",
     R"P(<|im_start|>system
Roleplay a resident of a small town and reply to what the player says. 1-2 short spoken sentences. Do not introduce yourself. No actions, no quotes, no modern slang. Use only the facts below; never invent names or numbers.
FACTS
You are William Calloway, 92, a man in Eta Springs, 2025. You are gruff and guarded.
The player is an acquaintance.
Example of the kind of answer: Things are what they are.<|im_end|>
<|im_start|>user
Nice day, isn't it?<|im_end|>
<|im_start|>assistant
<think>

</think>

)P"},
};

int failures = 0;

void check(bool ok, const std::string & what) {
    std::printf("  [%s] %s\n", ok ? "ok" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool valid_utf8(const std::string & s) {
    llm::Utf8Joiner j;
    return j.push(s.data(), s.size()) == s && j.flush().empty();
}

std::string lower(std::string s) {
    for (auto & c : s) c = (char) std::tolower((unsigned char) c);
    return s;
}

long rss_kb(const char * key) {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind(key, 0) == 0) return std::atol(line.c_str() + std::strlen(key));
    }
    return -1;
}

void print_stats(const llm::Stats & s) {
    const int fresh = s.prompt_tokens - s.reused_tokens;
    if (s.drafted > 0) std::printf("  guesses: %d of %d right (n-gram %d of %d); %d passes\n", s.accepted, s.drafted, s.ngram_accepted, s.ngram_drafted, s.steps);
    std::printf("  prompt %d tokens, reused %d from the KV cache, %d processed in %lld ms (%.1f tok/s)\n", s.prompt_tokens,
                s.reused_tokens, fresh, (long long) s.prefill_ms, s.prefill_ms > 0 ? fresh * 1000.0 / s.prefill_ms : 0.0);
    std::printf("  generated %d tokens in %lld ms (%.1f tok/s); first token after %lld ms; %d threads\n", s.gen_tokens,
                (long long) s.gen_ms, s.gen_ms > 0 ? s.gen_tokens * 1000.0 / s.gen_ms : 0.0, (long long) s.first_token_ms,
                s.threads);
}

void test_text_helpers() {
    std::printf("\n== text helpers\n");
    {   // "é" (C3 A9), "€" (E2 82 AC) and an emoji (F0 9F 98 80) split across pieces
        llm::Utf8Joiner j;
        std::string out;
        const char * pieces[] = {"caf\xC3", "\xA9 5\xE2", "\x82", "\xAC \xF0\x9F", "\x98", "\x80!"};
        std::vector<std::string> got;
        for (const char * p : pieces) got.push_back(j.push(p, std::strlen(p)));
        for (const auto & g : got) out += g;
        out += j.flush();
        check(out == "caf\xC3\xA9 5\xE2\x82\xAC \xF0\x9F\x98\x80!", "UTF-8 split across tokens is joined");
        bool each_valid = true;
        for (const auto & g : got) each_valid = each_valid && valid_utf8(g);
        check(each_valid, "every streamed piece is valid UTF-8 on its own");
        check(got[0] == "caf" && got[2].empty(), "partial characters are held back");
    }
    {   // invalid bytes: lone continuation, overlong, surrogate, truncated at the end
        llm::Utf8Joiner j;
        std::string out = j.push("a\x80" "b\xC0\xAF" "c\xED\xA0\x80" "d\xE2\x82", 12);
        out += j.flush();
        check(valid_utf8(out), "invalid bytes become U+FFFD (output stays valid UTF-8)");
        check(out.find('a') != std::string::npos && out.find('d') != std::string::npos, "valid text around them is kept");
    }
    {
        llm::StopFilter f({"<|im_end|>", "<|im_start|>"});
        bool stop = false;
        std::string a = f.push("Hello <|im", &stop);
        const bool held = a == "Hello " && !stop;
        std::string b = f.push("_end|>more", &stop);
        check(held && b.empty() && stop, "stop string split across tokens: text before it kept, nothing after");
        llm::StopFilter g({"<|im_end|>"});
        std::string c = g.push("a <", &stop);
        c += g.push("b", &stop);
        c += g.flush();
        check(c == "a <b" && !stop, "a '<' that is not a stop string is released");
    }
    {
        const std::u16string u = llm::utf8_to_utf16("h\xC3\xA9\xF0\x9F\x98\x80\xFF");
        check(u.size() == 5 && u[1] == 0xE9 && u[2] == 0xD83D && u[3] == 0xDE00 && u[4] == 0xFFFD,
              "UTF-8 to UTF-16 (surrogate pairs, U+FFFD for bad bytes)");
    }
}

void test_bad_models(const std::string & dir) {
    std::printf("\n== failure paths (must not crash)\n");
    llm::Engine e;
    const std::string r = e.generate("hi", 8, 0.3f, nullptr);
    check(r.empty() && e.error() == "no model loaded", "generate before load: \"" + e.error() + "\"");
    bool ok = e.load(dir + "/does-not-exist.gguf", 2, 512);
    check(!ok && !e.loaded() && e.error().find("cannot open") == 0, "missing file: \"" + e.error() + "\"");
    const std::string junk = dir + "/junk.gguf";
    { std::ofstream(junk) << "this is not a model"; }
    ok = e.load(junk, 2, 512);
    check(!ok && e.error().find("not a GGUF") == 0, "not a GGUF file: \"" + e.error() + "\"");
    const std::string trunc = dir + "/truncated.gguf";
    {   // a real GGUF header followed by garbage
        std::ofstream f(trunc, std::ios::binary);
        const char hdr[] = {'G', 'G', 'U', 'F', 3, 0, 0, 0, (char) 0xFF, (char) 0xFF, (char) 0xFF, (char) 0x7F, 0, 0, 0, 0};
        f.write(hdr, sizeof hdr);
        for (int i = 0; i < 4096; ++i) f.put((char) (i * 37));
    }
    ok = e.load(trunc, 2, 512);
    check(!ok && !e.loaded(), "corrupt GGUF: \"" + e.error() + "\"");
    std::remove(junk.c_str());
    std::remove(trunc.c_str());
}

std::vector<Prompt> read_prompts(const std::string & file) {
    std::ifstream f(file);
    std::vector<Prompt> out;
    Prompt cur;
    std::string line;
    auto finish = [&] {
        if (cur.text.empty()) return;
        if (cur.text.back() == '\n') cur.text.pop_back();
        if (cur.label.empty()) cur.label = "prompt " + std::to_string(out.size() + 1);
        out.push_back(cur);
        cur = {};
    };
    while (std::getline(f, line)) {
        if (line == "%%") { finish(); continue; }
        if (cur.text.empty() && cur.label.empty() && line.rfind("# ", 0) == 0) { cur.label = line.substr(2); continue; }
        cur.text += line + "\n";
    }
    finish();
    return out;
}

/** The prompts the model may voice in the app's dialogue.json, as DialogueEngine sees them (same order, duplicates once). */
std::vector<Prompt> read_dialogue(const std::string & file, const std::vector<std::string> & who) {
    std::ifstream f(file);
    if (!f) {
        std::fprintf(stderr, "cannot read %s\n", file.c_str());
        std::exit(2);
    }
    const nlohmann::json d = nlohmann::json::parse(f, nullptr, /*allow_exceptions*/ false);
    if (d.is_discarded() || !d.contains("npcs") || !d["npcs"].is_array()) {
        std::fprintf(stderr, "%s is not a dialogue.json\n", file.c_str());
        std::exit(2);
    }
    std::vector<Prompt> out;
    std::vector<std::string> seen;
    for (const auto & n : d["npcs"]) {
        const std::string id = n.value("id", ""), name = n.value("name", ""), first = n.value("first", ""),
                          building = n.value("building", "");
        if (!who.empty() && std::none_of(who.begin(), who.end(), [&](const std::string & w) {
                return w == id || w == name || w == first || w == building;
            })) continue;
        for (const auto & t : n.value("topics", nlohmann::json::array())) {
            if (!t.contains("prompt") || !t["prompt"].is_string()) continue;  // always the template (greet, town)
            Prompt p;
            p.text = t["prompt"].get<std::string>();
            if (std::find(seen.begin(), seen.end(), p.text) != seen.end()) continue;
            seen.push_back(p.text);
            p.npc = id;
            p.name = name;
            p.building = building;
            p.intent = t.value("intent", "");
            p.templ = t.value("template", "");
            p.self = t.value("self", "");
            for (const auto & m : t.value("must", nlohmann::json::array())) {
                if (m.is_string()) p.must.push_back(m.get<std::string>());
            }
            p.label = name + " (" + building + "): " + p.intent;
            out.push_back(std::move(p));
        }
    }
    return out;
}

/** The special token a piece of text is, or LLAMA_TOKEN_NULL. */
llama_token special_token(const llama_vocab * v, const char * text) {
    llama_token t[4];
    const int n = llama_tokenize(v, text, (int32_t) std::strlen(text), t, 4, /*add_special*/ false, /*parse_special*/ true);
    return n == 1 ? t[0] : LLAMA_TOKEN_NULL;
}

/**
 * Is the prompt one ChatML conversation, tokenized as the model expects? No BOS in front; every <|im_start|>,
 * <|im_end|>, <think> and </think> one special token (not spelled out as text); nothing lost (it detokenizes to the same
 * text); and <|im_end|> ends a turn. So the app's prompt is not wrapped a second time and the reply stops at its turn's end.
 */
std::string check_prompt_tokens(llm::Engine & e, const Prompt & p, bool * ok) {
    const llama_vocab * v = e.vocab();
    std::vector<int32_t> toks;
    *ok = e.tokenize(p.text, toks) && !toks.empty();
    if (!*ok) return "could not tokenize";
    const llama_token im_start = special_token(v, "<|im_start|>"), im_end = special_token(v, "<|im_end|>"),
                      think = special_token(v, "<think>"), unthink = special_token(v, "</think>");
    int n_start = 0, n_end = 0, n_think = 0, n_unthink = 0;
    for (int32_t t : toks) {
        n_start += t == im_start;
        n_end += t == im_end;
        n_think += t == think;
        n_unthink += t == unthink;
    }
    std::string back(p.text.size() + 64, '\0');
    const int32_t n = llama_detokenize(v, toks.data(), (int32_t) toks.size(), back.data(), (int32_t) back.size(), false, true);
    back.resize(n > 0 ? (size_t) n : 0);
    const bool starts = toks[0] == im_start, same = back == p.text, eog = llama_vocab_is_eog(v, im_end);
    const bool one_turn = n_start == 3 && n_end == 2 && n_think == 1 && n_unthink == 1;
    *ok = starts && same && eog && one_turn && im_start != LLAMA_TOKEN_NULL && think != LLAMA_TOKEN_NULL;
    char buf[256];
    std::snprintf(buf, sizeof buf, "%zu tokens, first %s, %d <|im_start|> / %d <|im_end|> / %d <think> / %d </think>, %s, <|im_end|> %s",
                  toks.size(), starts ? "<|im_start|> (no BOS)" : "NOT <|im_start|>", n_start, n_end, n_think, n_unthink,
                  same ? "detokenizes to the same text" : "DOES NOT detokenize to the same text",
                  eog ? "ends the turn" : "IS NOT end-of-generation");
    return buf;
}

double per_sec(long n, int64_t ms) { return ms > 0 ? n * 1000.0 / ms : 0.0; }

// ------------------------------------------------------------------------------------------------ saved states on flash

void clear_dir(const std::string & dir) {
    std::system(("mkdir -p '" + dir + "' && rm -f '" + dir + "'/*.state '" + dir + "'/*.tmp*").c_str());
}

/** N-gram guesses for the saved-state passes (--ngram): restores must stay exact with the rollback state too. */
int g_ngram = 0;
bool g_fixed_steps = false;
bool g_batch_alone_same = false;
bool g_batch_greedy_only = false;  // --batch-greedy-only  // --batch-same-context: --batch-test's prompts alone in a context like the batch's  // --fixed-steps: guess-and-check passes padded to one length (set_device bit 20)

/** One pass over [prompts] greedily with a fresh engine; the reply tokens of each, and the engine's state stats. */
struct Pass {
    std::vector<std::vector<int32_t>> tokens;
    std::vector<llm::Stats> stats;
    llm::SnapStats snap;
    int64_t ms = 0;
};

Pass run_pass(const std::string & model, int mtp, int threads, int n_ctx, int max_tokens, const std::vector<Prompt> & prompts,
              const std::string & dir, bool hot, bool bake_first, int64_t budget = 8LL << 30) {
    Pass out;
    llm::Engine e;
    e.set_device((mtp << 8) | (g_ngram << 16));
    e.set_snapshot_dir(dir, budget, hot);
    e.arm();
    if (!e.load(model, threads, n_ctx)) { check(false, "load: " + e.error()); return out; }
    const int64_t t0 = now_ms();
    if (bake_first) for (const auto & p : prompts) {
        const int n = e.bake(p.text);
        if (n < 0) check(false, "bake: " + e.error());
    }
    for (const auto & p : prompts) {
        e.generate(p.text, max_tokens, 0.0f, nullptr);
        if (!e.error().empty()) check(false, "generate: " + e.error());
        out.tokens.push_back(e.reply_tokens());
        out.stats.push_back(e.stats());
    }
    out.ms = now_ms() - t0;
    out.snap = e.snap_stats();
    return out;
}

std::string reused_list(const Pass & p) {
    std::string s;
    for (const auto & st : p.stats) s += (s.empty() ? "" : " ") + std::to_string(st.reused_tokens) + "/" + std::to_string(st.prompt_tokens);
    return s;
}

// ------------------------------------------------------------------------------------------------ n-gram guesses

struct SpecPass {
    std::vector<std::vector<int32_t>> tokens;
    std::vector<std::string> replies;
    long gen = 0, drafted = 0, accepted = 0, steps = 0, padded = 0, pad_accepted = 0;
    int64_t gen_ms = 0;
    double mem[5] = {0, 0, 0, 0, 0};
};

/** Who a prompt is for: its npc id, else its label up to the colon. */
std::string speaker(const Prompt & p) { return !p.npc.empty() ? p.npc : p.label.substr(0, p.label.find(':')); }

/**
 * Every prompt with one engine: [ngram] guesses (min match [min_match]; above 4 none are made, on the same path),
 * [mtp] MTP guesses, at [temp] with seed 42. [pool_from]: a pass whose replies of the same speaker (not this prompt's)
 * are the pool, as the bank's lines would be.
 */
SpecPass spec_pass(const std::string & model, int threads, int n_ctx, int max_tokens, const std::vector<Prompt> & prompts,
                   int ngram, int min_match, int mtp, float temp, const SpecPass * pool_from) {
    SpecPass out;
    llm::Engine e;
    e.set_device((mtp << 8) | (ngram << 16) | (g_fixed_steps ? 1 << 20 : 0));
    e.set_ngram_min(min_match);
    e.sampling().seed = 42;
    e.arm();
    llm::reset_load_mem();
    if (!e.load(model, threads, n_ctx)) { check(false, "load: " + e.error()); return out; }
    llm::last_load_mem(out.mem);
    for (size_t i = 0; i < prompts.size(); ++i) {
        std::string pool;
        if (pool_from) for (size_t j = 0; j < prompts.size(); ++j)
            if (j != i && speaker(prompts[j]) == speaker(prompts[i]) && j < pool_from->replies.size()) pool += pool_from->replies[j] + "\n";
        e.set_draft_pool(pool);
        e.sampling().seed = 42 + (uint32_t) i;
        const std::string r = e.generate(prompts[i].text, max_tokens, temp, nullptr);
        if (!e.error().empty()) check(false, "generate: " + e.error());
        const llm::Stats s = e.stats();
        out.tokens.push_back(e.reply_tokens());
        out.replies.push_back(r);
        out.gen += s.gen_tokens;
        out.gen_ms += s.gen_ms;
        out.drafted += s.ngram_drafted;
        out.accepted += s.ngram_accepted;
        out.steps += s.steps;
        out.padded += s.padded;
        out.pad_accepted += s.pad_accepted;
    }
    return out;
}

void spec_line(const char * what, const SpecPass & p) {
    std::printf("  %-34s %5ld tokens in %6lld ms: %5.2f tok/s; %ld passes (%.2f tokens a pass); n-gram %ld guessed, %ld right (%.0f%%)\n",
                what, p.gen, (long long) p.gen_ms, per_sec(p.gen, p.gen_ms), p.steps, p.steps ? (double) p.gen / p.steps : 0.0,
                p.drafted, p.accepted, p.drafted ? 100.0 * p.accepted / p.drafted : 0.0);
    if (p.padded > 0) std::printf("    %-34s %ld filler tokens checked, %ld of them right\n", "(fixed-shape steps)", p.padded, p.pad_accepted);
}

int same_tokens(const SpecPass & a, const SpecPass & b) {
    int n = 0;
    for (size_t i = 0; i < a.tokens.size() && i < b.tokens.size(); ++i) n += a.tokens[i] == b.tokens[i];
    return n;
}

/** Draft-free n-gram speculation: lossless (greedy and sampled), and what it gains. */
void spec_test(const std::string & model, int threads, int n_ctx, int max_tokens, const std::vector<Prompt> & prompts, int ngram,
               int mtp) {
    const int np = (int) prompts.size();
    std::printf("\n== n-gram guesses (%d prompts, %d tokens ahead, MTP %d, %d tokens a reply)\n", np, ngram, mtp, max_tokens);
    for (float temp : {0.0f, 0.3f}) {
        std::printf("\n -- temperature %.1f\n", temp);
        // the same path and context with no guesses made (min match 5): the reference
        const SpecPass off = spec_pass(model, threads, n_ctx, max_tokens, prompts, ngram, 5, mtp, temp, nullptr);
        spec_line("no n-gram guesses:", off);
        std::printf("    memory (MiB): KV %.1f, recurrent state %.1f (rollback for %d), compute %.1f\n", off.mem[2], off.mem[3], ngram, off.mem[4]);
        for (int min_match : {3, 2}) {
            const SpecPass on = spec_pass(model, threads, n_ctx, max_tokens, prompts, ngram, min_match, mtp, temp, nullptr);
            const std::string what = "n-gram (match >= " + std::to_string(min_match) + "):";
            spec_line(what.c_str(), on);
            const int same = same_tokens(on, off);
            check(same == np, std::to_string(same) + " of " + std::to_string(np) + " replies token-identical to no guesses (" +
                                  (temp == 0.0f ? "greedy" : "sampled, same seed") + ")");
            const SpecPass pooled = spec_pass(model, threads, n_ctx, max_tokens, prompts, ngram, min_match, mtp, temp, &off);
            const std::string what2 = "  + the speaker's other lines:";
            spec_line(what2.c_str(), pooled);
            const int same2 = same_tokens(pooled, off);
            check(same2 == np, std::to_string(same2) + " of " + std::to_string(np) + " with the pool token-identical too");
        }
        if (temp == 0.0f) {
            // and the plain loop (no rollback state, no guesses at all), for the record: llama.cpp's numbers can differ
            // with the batch a token is checked in
            const SpecPass plain = spec_pass(model, threads, n_ctx, max_tokens, prompts, 0, 2, 0, temp, nullptr);
            spec_line("plain loop (no rollback state):", plain);
            std::printf("    %d of %d replies identical to the guess path's without guesses; memory: recurrent state %.1f MiB\n",
                        same_tokens(plain, off), np, plain.mem[3]);
        }
    }
}

// ------------------------------------------------------------------------------------------------ the guide (idea 2)

/** The guide's rules on hand-made cases (no model): what validate() would reject is taken away, nothing else. */
void test_guide_rules() {
    std::printf("\n== the guide's rules (validate()-aware sampling)\n");
    llm::GuideRules r;
    r.build(kPrompts[0].text, "William Calloway", {"It is a lovely day for a walk."});
    struct Case { const char * text; const char * piece; bool banned; };
    const Case cases[] = {
        {"I'm married to ", "Louise", false}, {"I'm married to ", "Lou", false}, {"I'm married to Lou", "ise", false},
        {"I'm married to ", "Jake", true},    {"We have two, ", "Lisa", true},  {"", "Jake", false},
        {"Well. ", "Jake", false},            {"One child, ", "Frank", false},  {"I like ", "Monday", false},
        {"I'm married to Louise, and ", "I", false}, {"Louise and ", "Rad", false}, {"Louise and Rad", "x", true},
        {"He ", "nods", true},                {"I", " smile", true},            {"Married to Louise. I ", "nodded", false},
        {"", "*", true},                       {"Fine ", "*laughs", true},       {"We met at the ", "library", true},
        {"We met at the old ", "mill", true}, {"We met at ", "church", false},  {"We met at the old ", "mills", true},
        {"It is a lovely day for", " a", true}, {"It is a lovely day", " today", false},
        {"", "As an AI", true},               {"Sorry, ", "I", true},           {"I am ", "William Calloway", true},
        {"Eta Springs", ", 2025", true},       {"Let me ", "know", true},        {"I'm not an ", "assistant", true},
        {"Is there ", "anything else", true}, {"The ", "fire", true},           {"I'm on ", "fire", false},
        {"You are gruff and ", "guarded", true}, {"I'm married to Louise Radcliffe", ".", false},
    };
    int ok = 0;
    for (const auto & c : cases) {
        const char * why = r.check(c.text, c.piece);
        const bool banned = *why != 0;
        if (banned == c.banned) ++ok;
        else std::printf("  case \"%s\" + \"%s\": %s (expected %s)\n", c.text, c.piece, banned ? why : "allowed", c.banned ? "banned" : "allowed");
    }
    const int n = (int) (sizeof cases / sizeof cases[0]);
    check(ok == n, std::to_string(ok) + " of " + std::to_string(n) + " cases as validate() would judge them");
    check(std::binary_search(r.names().begin(), r.names().end(), std::string("Radcliffe")) &&
              std::find(r.banned_places().begin(), r.banned_places().end(), std::string("library")) != r.banned_places().end(),
          "names from the sheet allowed, places it lacks banned (" + std::to_string(r.names().size()) + " names, " +
              std::to_string(r.banned_places().size()) + " places banned)");
}

/**
 * --guide-eval FILE: every prompt twice per mode, as DialogueEngine does (MAX_TRIES 2: a second try with another seed),
 * without the guide ("old") and with it ("guided"), sampled at the app's temperature; the raw replies, their prompts and
 * must/self go to FILE (tools: validate() then judges first-try and final pass rates, and the blind side-by-side is made
 * from them).
 */
void guide_eval(const std::string & model, int threads, int n_ctx, int max_tokens, float temperature, const std::vector<Prompt> & prompts,
                const std::string & out_file, uint32_t seed) {
    std::printf("\n== guide eval: %zu prompts, 2 tries each, without and with the guide\n", prompts.size());
    nlohmann::json out = nlohmann::json::array();
    llm::Engine e;
    e.arm();
    if (!e.load(model, threads, n_ctx)) { check(false, "load: " + e.error()); return; }
    long banned = 0, fallbacks = 0, gen_old = 0, gen_guided = 0;
    int64_t ms_old = 0, ms_guided = 0;
    for (size_t i = 0; i < prompts.size(); ++i) {
        const Prompt & p = prompts[i];
        nlohmann::json row = {{"label", p.label}, {"prompt", p.text}, {"must", p.must}, {"self", p.self}};
        for (int guided = 0; guided < 2; ++guided) {
            nlohmann::json tries = nlohmann::json::array();
            for (int t = 0; t < 2; ++t) {
                e.set_guide(guided != 0, p.self, {});
                e.sampling().seed = seed + (uint32_t) (i * 7 + t * 1000);
                const std::string r = e.generate(p.text, max_tokens, temperature, nullptr);
                const llm::Stats s = e.stats();
                (guided ? gen_guided : gen_old) += s.gen_tokens;
                (guided ? ms_guided : ms_old) += s.gen_ms;
                banned += s.guide_banned;
                fallbacks += s.guide_fallbacks;
                tries.push_back({{"raw", r}, {"banned", s.guide_banned}, {"fallbacks", s.guide_fallbacks}});
            }
            row[guided ? "guided" : "old"] = tries;
        }
        std::printf("  %zu/%zu %s\n", i + 1, prompts.size(), p.label.c_str());
        std::fflush(stdout);
        out.push_back(row);
    }
    std::ofstream f(out_file);
    f << out.dump(1, ' ', false, nlohmann::json::error_handler_t::replace) << "\n";
    check(f.good(), "wrote " + out_file);
    std::printf("  guided: %ld candidate tokens taken away, %ld steps left unguided (every candidate broke a rule)\n", banned, fallbacks);
    std::printf("  host CPU: old %.1f tok/s, guided %.1f tok/s\n", per_sec(gen_old, ms_old), per_sec(gen_guided, ms_guided));
}

// ------------------------------------------------------------------------------------------------ the writer's batches (idea 6)

/**
 * --batch-test N: generate_batch with N sequences against each prompt alone (generate() on a one-sequence engine), greedy
 * and sampled (the same seed per prompt): every reply must be token-identical; and the time both took.
 */
void batch_test(const std::string & model, int threads, int n_ctx, int max_tokens, const std::vector<Prompt> & prompts, int n_seq) {
    std::printf("\n== the writer's batches: %zu prompts, %d sequences a pass\n", prompts.size(), n_seq);
    std::vector<std::string> texts;
    std::vector<uint32_t> seeds;
    for (size_t i = 0; i < prompts.size(); ++i) { texts.push_back(prompts[i].text); seeds.push_back(1000 + (uint32_t) i); }
    for (float temp : {0.0f, 0.3f}) {
        if (temp > 0 && g_batch_greedy_only) break;
        std::vector<std::vector<int32_t>> alone;
        int64_t alone_ms = 0;
        long alone_tok = 0;
        {
            llm::Engine e;
            e.arm();
            // (the same context as the batch's: its cache size and sequences change llama.cpp's rounding)
            e.set_device(g_batch_alone_same ? (n_seq - 1) << 24 : 0);
            if (!e.load(model, threads, n_ctx)) { check(false, "load: " + e.error()); return; }
            const int64_t t0 = now_ms();
            for (size_t i = 0; i < texts.size(); ++i) {
                e.sampling().seed = seeds[i];
                e.generate(texts[i], max_tokens, temp, nullptr);
                alone.push_back(e.reply_tokens());
                alone_tok += e.stats().gen_tokens;
            }
            alone_ms = now_ms() - t0;
        }
        llm::Engine b;
        b.arm();
        b.set_device((n_seq - 1) << 24);
        llm::reset_load_mem();
        if (!b.load(model, threads, n_ctx)) { check(false, "load with sequences: " + b.error()); return; }
        double mem[5];
        llm::last_load_mem(mem);
        check(b.batch_seqs() == n_seq, "the context holds " + std::to_string(b.batch_seqs()) + " sequences (KV " +
                                           std::to_string((int) mem[2]) + " MiB, recurrent state " + std::to_string((int) mem[3]) + " MiB)");
        const int64_t t0 = now_ms();
        const std::vector<std::string> r = b.generate_batch(texts, max_tokens, temp, seeds);
        const int64_t batch_ms = now_ms() - t0;
        if (!b.error().empty()) check(false, "generate_batch: " + b.error());
        int same = 0;
        long batch_tok = 0;
        for (size_t i = 0; i < texts.size(); ++i) {
            same += b.batch_tokens()[i] == alone[i];
            batch_tok += b.batch_stats()[i].gen_tokens;
            if (b.batch_tokens()[i] != alone[i]) {
                size_t at = 0;
                while (at < alone[i].size() && at < b.batch_tokens()[i].size() && alone[i][at] == b.batch_tokens()[i][at]) ++at;
                std::printf("  differs: %s (from token %zu of %zu; pass %zu)\n", prompts[i].label.c_str(), at, alone[i].size(), i / (size_t) n_seq);
            }
        }
        check(same == (int) texts.size(), std::to_string(same) + " of " + std::to_string(texts.size()) + " replies token-identical to each alone (" +
                                              (temp == 0.0f ? "greedy" : "sampled, the same seed") + ")");
        // and again in the same engine: generate() after a batch still answers as before
        b.sampling().seed = seeds[0];
        b.generate(texts[0], max_tokens, temp, nullptr);
        check(b.reply_tokens() == alone[0], "generate() after a batch gives the same reply as alone");
        std::printf("  alone: %ld tokens in %lld ms (%.1f tok/s); in batches of %d: %ld tokens in %lld ms (%.1f tok/s), %.2fx (host CPU)\n",
                    alone_tok, (long long) alone_ms, per_sec(alone_tok, alone_ms), n_seq, batch_tok, (long long) batch_ms,
                    per_sec(batch_tok, batch_ms), batch_ms > 0 ? (double) alone_ms / batch_ms : 0.0);
    }
}

// ------------------------------------------------------------------------------------------------ the English-first head

/**
 * --vocab-test PERMUTED --perm FILE (ENGINE_TAILORING.md, idea 1): [model] and [permuted], the same model with its
 * vocabulary reordered English-first and "timerpg.head_vocab" = K (only the first K rows of the head computed, the rest
 * -INF). FILE: new id -> original id, one a line. Checks: every prompt tokenizes to the same tokens through the
 * permutation; at every greedy step of the original model, whether its 40 best (penalized) tokens all lie in the first
 * K; greedy and sampled (same seed) replies token-identical through the permutation (required wherever the top-40 lay
 * within K); and the speed of both.
 */
void vocab_test(const std::string & model, const std::string & permuted, const std::string & perm_file, int threads, int n_ctx,
                int max_tokens, const std::vector<Prompt> & prompts) {
    std::printf("\n== English-first head: %s vs %s\n", model.c_str(), permuted.c_str());
    std::vector<int32_t> new_to_old;
    {
        std::ifstream f(perm_file);
        long v;
        while (f >> v) new_to_old.push_back((int32_t) v);
    }
    std::vector<int32_t> old_to_new(new_to_old.size(), -1);
    for (size_t i = 0; i < new_to_old.size(); ++i)
        if (new_to_old[i] >= 0 && (size_t) new_to_old[i] < old_to_new.size()) old_to_new[(size_t) new_to_old[i]] = (int32_t) i;
    int K = 0;
    {
        llama_model_params vp = llama_model_default_params();
        vp.vocab_only = true;
        llama_model * pm = llama_model_load_from_file(permuted.c_str(), vp);
        char buf[64] = {};
        if (pm && llama_model_meta_val_str(pm, "timerpg.head_vocab", buf, sizeof buf) > 0) K = std::atoi(buf);
        if (pm) llama_model_free(pm);
    }
    check(K > 0, "the permuted model has timerpg.head_vocab = " + std::to_string(K));
    if (K <= 0) return;
    const auto map_ids = [&](const std::vector<int32_t> & v) {
        std::vector<int32_t> o;
        for (int32_t x : v) o.push_back(x >= 0 && (size_t) x < old_to_new.size() ? old_to_new[(size_t) x] : -1);
        return o;
    };
    const int np = (int) prompts.size();
    // 1. the original model's own greedy loop, to look at its top 40 (penalties then top-k, as the app's chain)
    std::vector<std::vector<int32_t>> own((size_t) np);
    std::vector<char> all_in_k((size_t) np, 1), pick_in_k((size_t) np, 1);
    long steps = 0, steps_in = 0, argmax_out = 0;
    double max_out_mass = 0;
    int n_vocab = 0;
    {
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        llama_model * om = llama_model_load_from_file(model.c_str(), mp);
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t) n_ctx;
        cp.n_batch = cp.n_ubatch = 512;
        cp.n_outputs_max = cp.n_outputs_max_per_seq = 1;  // (only the last token's logits: not n_batch rows of 248k)
        cp.n_threads = cp.n_threads_batch = threads > 0 ? threads : 4;
        cp.no_perf = true;
        llama_context * oc = om ? llama_init_from_model(om, cp) : nullptr;
        if (!oc) { check(false, "a context for the original model"); if (om) llama_model_free(om); return; }
        const llama_vocab * ov = llama_model_get_vocab(om);
        n_vocab = llama_vocab_n_tokens(ov);
        check(n_vocab == (int) new_to_old.size(), "the permutation has the model's " + std::to_string(n_vocab) + " tokens");
        if (n_vocab != (int) new_to_old.size()) { llama_free(oc); llama_model_free(om); return; }
        std::vector<llama_token_data> cand((size_t) n_vocab);
        for (int pi = 0; pi < np; ++pi) {
            std::vector<int32_t> ta((size_t) prompts[(size_t) pi].text.size() + 16);
            const int nt = llama_tokenize(ov, prompts[(size_t) pi].text.data(), (int32_t) prompts[(size_t) pi].text.size(), ta.data(),
                                          (int32_t) ta.size(), true, true);
            ta.resize(nt > 0 ? (size_t) nt : 0);
            llama_memory_clear(llama_get_memory(oc), true);
            llama_sampler * ch = llama_sampler_chain_init(llama_sampler_chain_default_params());
            llama_sampler_chain_add(ch, llama_sampler_init_penalties(n_vocab, 64, 1.15f, 0.0f, 0.0f));
            llama_sampler_chain_add(ch, llama_sampler_init_top_k(40));
            if (!ta.empty() && llama_decode(oc, llama_batch_get_one(ta.data(), (int32_t) ta.size())) == 0) {
                for (int n = 0; n < max_tokens; ++n) {
                    const float * lg = llama_get_logits_ith(oc, -1);
                    for (int i = 0; i < n_vocab; ++i) cand[(size_t) i] = {i, lg[i], 0.0f};
                    llama_token_data_array arr = {cand.data(), cand.size(), -1, false};
                    llama_sampler_apply(ch, &arr);
                    // the probability mass outside the first K (softmax of the raw logits, temperature 1)
                    float mx = -INFINITY;
                    for (int i = 0; i < n_vocab; ++i) mx = std::max(mx, lg[i]);
                    double z = 0, out = 0;
                    for (int i = 0; i < n_vocab; ++i) {
                        const double e = std::exp((double) (lg[i] - mx));
                        z += e;
                        if (old_to_new[(size_t) i] >= K) out += e;
                    }
                    max_out_mass = std::max(max_out_mass, out / z);
                    bool in = true;
                    for (size_t i = 0; i < arr.size; ++i) in = in && old_to_new[(size_t) arr.data[i].id] < K;
                    ++steps;
                    steps_in += in;
                    if (!in) all_in_k[(size_t) pi] = 0;
                    const llama_token t = arr.data[0].id;  // (top-k sorts: the greedy pick)
                    argmax_out += old_to_new[(size_t) t] >= K;
                    if (old_to_new[(size_t) t] >= K) pick_in_k[(size_t) pi] = 0;
                    if (llama_vocab_is_eog(ov, t)) break;
                    own[(size_t) pi].push_back(t);
                    llama_sampler_accept(ch, t);
                    llama_token tt = t;
                    if (llama_decode(oc, llama_batch_get_one(&tt, 1)) != 0) break;
                }
            }
            llama_sampler_free(ch);
        }
        llama_free(oc);
        llama_model_free(om);
    }
    // 2. both models through the engine (one at a time: memory), greedy and sampled with the same seeds
    struct Run { std::vector<std::vector<int32_t>> prompt, greedy, sampled; int64_t ms = 0; long tok = 0; double mem[5] = {0, 0, 0, 0, 0}; };
    const auto run = [&](const std::string & path, Run & r) {
        llm::Engine e;
        e.arm();
        llm::reset_load_mem();
        if (!e.load(path, threads, n_ctx)) { check(false, "load " + path + ": " + e.error()); return false; }
        llm::last_load_mem(r.mem);
        for (int pi = 0; pi < np; ++pi) {
            std::vector<int32_t> t;
            e.tokenize(prompts[(size_t) pi].text, t);
            r.prompt.push_back(t);
            e.generate(prompts[(size_t) pi].text, max_tokens, 0.0f, nullptr);
            r.greedy.push_back(e.reply_tokens());
            r.ms += e.stats().gen_ms;
            r.tok += e.stats().gen_tokens;
            e.sampling().seed = 42 + (uint32_t) pi;
            e.generate(prompts[(size_t) pi].text, max_tokens, 0.3f, nullptr);
            r.sampled.push_back(e.reply_tokens());
        }
        return true;
    };
    Run a, b;
    if (!run(model, a) || !run(permuted, b)) return;
    int tok_same = 0, greedy_same = 0, sampled_same = 0, all_in = 0, all_in_same = 0, own_loop_same = 0, pick_in = 0, pick_in_same = 0;
    for (int pi = 0; pi < np; ++pi) {
        const size_t i = (size_t) pi;
        tok_same += map_ids(a.prompt[i]) == b.prompt[i];
        own_loop_same += own[i] == a.greedy[i];
        const bool same = map_ids(a.greedy[i]) == b.greedy[i];
        greedy_same += same;
        all_in += all_in_k[i];
        all_in_same += all_in_k[i] && same;
        pick_in += pick_in_k[i];
        pick_in_same += pick_in_k[i] && same;
        sampled_same += map_ids(a.sampled[i]) == b.sampled[i];
        if (!same) std::printf("  differs (%s): top-40 %s\n", prompts[i].label.c_str(), all_in_k[i] ? "always within K" : "not always within K");
    }
    std::printf("  memory (MiB): original weights mapped %.0f copied %.0f compute %.1f; permuted mapped %.0f copied %.0f compute %.1f\n",
                a.mem[0], a.mem[1], a.mem[4], b.mem[0], b.mem[1], b.mem[4]);
    std::printf("  K = %d of %d; %ld greedy steps: top-40 all within the first K in %ld (%.1f%%); the greedy pick outside K in %ld;"
                " the largest probability mass outside K at one step %.2e\n", K, n_vocab, steps, steps_in,
                steps ? 100.0 * steps_in / steps : 0.0, argmax_out, max_out_mass);
    check(tok_same == np, std::to_string(tok_same) + " of " + std::to_string(np) + " prompts tokenize identically through the permutation");
    // (the reference loop's context is llama.cpp's default one, not the engine's: its numbers may differ a little)
    std::printf("  %d of %d engine greedy replies match the reference loop (its own context)\n", own_loop_same, np);
    check(pick_in_same == pick_in, std::to_string(pick_in_same) + " of " + std::to_string(pick_in) +
                                       " replies whose every greedy pick lay within K are greedy-identical (required)");
    check(all_in_same == all_in, std::to_string(all_in_same) + " of " + std::to_string(all_in) +
                                     " replies whose every step had its top-40 within K are greedy-identical (required)");
    std::printf("  greedy identical %d of %d in all; sampled (a seed per prompt, temperature 0.3) identical %d of %d\n", greedy_same,
                np, sampled_same, np);
    std::printf("  host CPU decode: original %.1f tok/s, cut head %.1f tok/s (%ld / %ld tokens)\n", per_sec(a.tok, a.ms),
                per_sec(b.tok, b.ms), a.tok, b.tok);
}

/** Restored generation must be token-identical to a fresh prefill (greedy), and the states must stay off the RAM. */
void snapshot_test(const std::string & model, int mtp, int threads, int n_ctx, int max_tokens, const std::vector<Prompt> & prompts,
                   const std::string & dir) {
    std::printf("\n== saved states on flash (%s), greedy, %d tokens a reply\n", dir.c_str(), max_tokens);
    const long rss0 = rss_kb("VmRSS:");
    // 1. in RAM as before (no directory): the reference the old engine gave
    const Pass ram = run_pass(model, mtp, threads, n_ctx, max_tokens, prompts, "", false, false);
    std::printf("  in RAM (as before): reused %s; %d states held, %.1f MB; state size %.1f MB\n", reused_list(ram).c_str(),
                ram.snap.ram, ram.snap.ram_bytes / 1048576.0, ram.snap.state_bytes / 1048576.0);
    // 2. on flash, an empty directory: every state is written by a fresh prefill
    clear_dir(dir);
    const Pass fresh = run_pass(model, mtp, threads, n_ctx, max_tokens, prompts, dir, false, false);
    std::printf("  on flash, fresh: reused %s; %d written in %lld ms (%.1f ms each, %.1f MB each), %d restored from flash in %lld ms; %d in RAM (%.1f MB)\n",
                reused_list(fresh).c_str(), fresh.snap.writes, (long long) fresh.snap.write_ms,
                fresh.snap.writes ? (double) fresh.snap.write_ms / fresh.snap.writes : 0.0, fresh.snap.state_bytes / 1048576.0,
                fresh.snap.restores_file, (long long) fresh.snap.restore_ms, fresh.snap.ram, fresh.snap.ram_bytes / 1048576.0);
    check(fresh.snap.ram == 0 && fresh.snap.writes > 0, "with a directory no state is held in RAM (" + std::to_string(fresh.snap.files) + " on flash)");
    // 3. a new engine (a new session) on the same directory: every prompt restores a state written by the first
    const Pass restored = run_pass(model, mtp, threads, n_ctx, max_tokens, prompts, dir, false, false);
    bool all_restored = true;
    for (size_t i = 0; i < prompts.size(); ++i) all_restored = all_restored && restored.stats[i].reused_tokens > 0;
    std::printf("  on flash, a new session: reused %s; %d restored from flash in %lld ms (%.1f ms each), %d written\n",
                reused_list(restored).c_str(), restored.snap.restores_file, (long long) restored.snap.restore_ms,
                restored.snap.restores_file ? (double) restored.snap.restore_ms / restored.snap.restores_file : 0.0, restored.snap.writes);
    check(all_restored && restored.snap.restores_file >= (int) prompts.size(), "a new session restores every prompt from flash");
    int same = 0;
    for (size_t i = 0; i < prompts.size(); ++i) same += restored.tokens[i] == fresh.tokens[i] && !fresh.tokens[i].empty();
    check(same == (int) prompts.size(), std::to_string(same) + " of " + std::to_string(prompts.size()) +
                                            " replies restored from flash are token-identical to the fresh prefill's");
    int same_ram = 0;
    for (size_t i = 0; i < prompts.size(); ++i) same_ram += ram.tokens[i] == fresh.tokens[i];
    std::printf("  (%d of %zu also identical to the in-RAM engine's, which stops at every line end: a different batch split)\n",
                same_ram, prompts.size());
    // 4. the shared rules kept hot in RAM: does it pay against a mapped file in the page cache?
    const Pass hot = run_pass(model, mtp, threads, n_ctx, max_tokens, prompts, dir, true, false);
    int same_hot = 0;
    for (size_t i = 0; i < prompts.size(); ++i) same_hot += hot.tokens[i] == fresh.tokens[i];
    std::printf("  hot rules state: %d in RAM (%.1f MB); restores %d from RAM, %d from flash, %lld ms in all\n", hot.snap.ram,
                hot.snap.ram_bytes / 1048576.0, hot.snap.restores_ram, hot.snap.restores_file, (long long) hot.snap.restore_ms);
    check(same_hot == (int) prompts.size() && hot.snap.ram <= 1, "with the hot state: identical replies, at most one state in RAM");
    {   // restore cost alone: the rules state, from RAM and from the mapped file, 20 times each
        llm::Engine e;
        e.set_device((mtp << 8) | (g_ngram << 16));
        e.set_snapshot_dir(dir, 8LL << 30, true);
        e.arm();
        if (e.load(model, threads, n_ctx)) {
            // a prompt that shares only the rules with every saved one
            std::string other = prompts[0].text;
            const size_t f = other.find("FACTS\n");
            other = other.substr(0, f + 6) + "You are Nobody Atall, 50, a man in Nowhere. No more.<|im_end|>\n<|im_start|>user\nHi.<|im_end|>\n<|im_start|>assistant\n";
            const int n = 20;
            for (int i = 0; i < n; ++i) e.generate(other, 0, 0.0f, nullptr);
            const llm::SnapStats a = e.snap_stats();
            std::printf("  restore of the rules state (%.1f MB): from RAM %.2f ms (%d), ", fresh.snap.state_bytes / 1048576.0,
                        a.restores_ram ? (double) a.restore_ms / (a.restores_ram + a.restores_file) : 0.0, a.restores_ram);
            e.set_snapshot_dir(dir, 8LL << 30, false);
            const llm::SnapStats b0 = e.snap_stats();
            for (int i = 0; i < n; ++i) e.generate(other, 0, 0.0f, nullptr);
            const llm::SnapStats b = e.snap_stats();
            std::printf("from the mapped file (page cache) %.2f ms (%d)\n",
                        (b.restores_file - b0.restores_file) ? (double) (b.restore_ms - b0.restore_ms) / (b.restores_file - b0.restores_file) : 0.0,
                        b.restores_file - b0.restores_file);
        }
    }
    // 5. pre-baked: a new engine bakes every prompt's states first, then answers: identical, from the baked states
    clear_dir(dir);
    const Pass baked = run_pass(model, mtp, threads, n_ctx, max_tokens, prompts, dir, false, true);
    int same_baked = 0;
    bool from_baked = true;
    for (size_t i = 0; i < prompts.size(); ++i) {
        same_baked += baked.tokens[i] == fresh.tokens[i];
        from_baked = from_baked && baked.stats[i].reused_tokens > 0;
    }
    std::printf("  pre-baked: reused %s; %d states written by bake()\n", reused_list(baked).c_str(), baked.snap.writes);
    check(from_baked && same_baked == (int) prompts.size(), "pre-baked states: every reply starts from one and is token-identical (" +
                                                                std::to_string(same_baked) + ")");
    // 6. the budget: room for two states only, so older files go
    clear_dir(dir);
    const int64_t budget = (int64_t) (fresh.snap.state_bytes * 2.5);
    const Pass small = run_pass(model, mtp, threads, n_ctx, max_tokens, prompts, dir, false, true, budget);
    long long on_disk = 0;
    if (DIR * d = opendir(dir.c_str())) {
        while (dirent * e = readdir(d)) {
            const std::string name = e->d_name;
            struct stat st;
            if (name.size() > 6 && name.compare(name.size() - 6, 6, ".state") == 0 && stat((dir + "/" + name).c_str(), &st) == 0) on_disk += st.st_size;
        }
        closedir(d);
    }
    check(small.snap.evicted > 0 && on_disk <= budget, "the budget holds (" + std::to_string(on_disk >> 20) + " MB on flash of " +
                                                           std::to_string(budget >> 20) + ", " + std::to_string(small.snap.evicted) + " removed)");
    int same_small = 0;
    for (size_t i = 0; i < prompts.size(); ++i) same_small += small.tokens[i] == fresh.tokens[i];
    check(same_small == (int) prompts.size(), "replies under a tight budget are identical too");
    std::printf("  RSS before %ld MB, peak %ld MB\n", rss0 / 1024, rss_kb("VmHWM:") / 1024);
    clear_dir(dir);
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s model.gguf [--threads N] [--ctx N] [--max N] [--prompts FILE] [--cpu-lib NAME]... [--verbose]\n", argv[0]);
        return 2;
    }
    std::string model = argv[1], prompts_file, dialogue_file, json_out;
    int mtp = 0;
    bool kv_q8 = false, mem_only = false;
    std::string snap_dir;
    int ngram = 0, ngram_min = 2;  // --ngram N: guess N tokens ahead by n-gram lookup (--ngram-min M: the shortest match)
    int batch_n = 0;                     // --batch-test N: generate_batch with N sequences against each prompt alone, then exit
    bool rules_only = false;             // --rules-only: the text helpers and the guide's rules (no model), then exit
    std::string guide_out;               // --guide-eval FILE: replies without and with the guide, then exit
    std::string vocab_model, perm_file;  // --vocab-test PERMUTED --perm FILE: the English-first head, then exit
    bool spec = false;             // --spec-test: n-gram guesses lossless (greedy, sampled) and their gain, then exit  // --snapshot-test DIR: the saved states on flash (restored == fresh, bake, budget), then exit
    std::vector<std::string> cpu_libs = llm::cpu_variant_libs(), who;
    // The app's settings (DialogueEngine: CONTEXT_TOKENS, MAX_TOKENS, TEMPERATURE).
    int threads = 0, n_ctx = 1024, max_tokens = 56;
    float temperature = 0.3f;
    bool checks = true;
    uint32_t seed = 42;  // reproducible runs (the app draws a new seed for every reply)
    static bool verbose = false;
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&] { return i + 1 < argc ? std::string(argv[++i]) : std::string(); };
        if (a == "--threads") threads = std::atoi(next().c_str());
        else if (a == "--ctx") n_ctx = std::atoi(next().c_str());
        else if (a == "--max") max_tokens = std::atoi(next().c_str());
        else if (a == "--temp") temperature = (float) std::atof(next().c_str());
        else if (a == "--prompts") prompts_file = next();
        else if (a == "--dialogue") dialogue_file = next();
        else if (a == "--npc") who.push_back(next());
        else if (a == "--json-out") json_out = next();
        else if (a == "--no-checks") checks = false;
        else if (a == "--seed") seed = (uint32_t) std::strtoul(next().c_str(), nullptr, 10);
        else if (a == "--cpu-lib") cpu_libs.push_back(next());
        else if (a == "--verbose") verbose = true;
        else if (a == "--mtp") mtp = std::atoi(next().c_str());
        else if (a == "--kv-q8") kv_q8 = true;
        else if (a == "--mem-only") mem_only = true;
        else if (a == "--snapshot-test") snap_dir = next();
        else if (a == "--ngram") ngram = std::atoi(next().c_str());
        else if (a == "--ngram-min") ngram_min = std::atoi(next().c_str());
        else if (a == "--spec-test") spec = true;
        else if (a == "--fixed-steps") g_fixed_steps = true;
        else if (a == "--batch-same-context") g_batch_alone_same = true;
        else if (a == "--batch-greedy-only") g_batch_greedy_only = true;
        else if (a == "--vocab-test") vocab_model = next();
        else if (a == "--perm") perm_file = next();
        else if (a == "--guide-eval") guide_out = next();
        else if (a == "--rules-only") rules_only = true;
        else if (a == "--batch-test") batch_n = std::atoi(next().c_str());
        else {
            std::fprintf(stderr, "unknown option %s\n", a.c_str());
            return 2;
        }
    }
    llm::set_log_sink([](int level, const char * text) {
        if (level >= 3 || (verbose && level >= 2)) std::fputs(text, stderr);
    });
    const std::vector<Prompt> prompts = !dialogue_file.empty() ? read_dialogue(dialogue_file, who)
                                      : !prompts_file.empty()  ? read_prompts(prompts_file)
                                                               : kPrompts;
    if (prompts.empty()) {
        std::fprintf(stderr, "no prompts%s\n", who.empty() ? "" : " for those --npc names");
        return 2;
    }

    std::string info;
    const bool backend = llm::init_backend(cpu_libs, &info);
    std::printf("backend: %s\n%s\n", info.c_str(), llama_print_system_info());
    if (!backend) return 1;

    if (checks || rules_only) {
        test_text_helpers();
        test_guide_rules();
        if (rules_only) {
            std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
            return failures ? 1 : 0;
        }
        const char * tmp = std::getenv("TMPDIR");
        test_bad_models(tmp && *tmp ? tmp : "/tmp");
    }

    if (batch_n > 0) {
        batch_test(model, threads, n_ctx, max_tokens, prompts, batch_n);
        std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
        return failures ? 1 : 0;
    }
    if (!guide_out.empty()) {
        guide_eval(model, threads, n_ctx, max_tokens, temperature, prompts, guide_out, seed);
        std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
        return failures ? 1 : 0;
    }
    if (!vocab_model.empty()) {
        vocab_test(model, vocab_model, perm_file, threads, n_ctx, max_tokens, prompts);
        std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
        return failures ? 1 : 0;
    }
    if (spec) {
        spec_test(model, threads, n_ctx, max_tokens, prompts, ngram > 0 ? ngram : 4, mtp);
        std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
        return failures ? 1 : 0;
    }
    if (!snap_dir.empty()) {
        g_ngram = ngram;
        snapshot_test(model, mtp, threads, n_ctx, max_tokens, prompts, snap_dir);
        std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
        return failures ? 1 : 0;
    }
    std::printf("\n== load %s\n", model.c_str());
    llm::Engine e;
    e.sampling().seed = seed;
    e.arm();
    e.set_device((mtp << 8) | (kv_q8 ? 32 : 0) | (ngram << 16));
    e.set_ngram_min(ngram_min);
    if (!e.load(model, threads, n_ctx)) {
        std::printf("  load failed: %s\n", e.error().c_str());
        return 1;
    }
    std::printf("  loaded in %lld ms, %d threads, context %d; RSS %ld MB\n", (long long) e.stats().load_ms,
                e.stats().threads, n_ctx, rss_kb("VmRSS:") / 1024);
    {
        double m[5];
        llm::last_load_mem(m);
        std::printf("  memory (MiB): weights mapped %.0f, copied %.0f; KV %.1f (%s); recurrent state %.1f; compute %.1f\n",
                    m[0], m[1], m[2], e.kv_q8() ? "q8_0" : "f16", m[3], m[4]);
    }
    if (mem_only) return 0;

    std::printf("\n== NPC replies (%zu prompts, max %d tokens, temperature %.2f)\n", prompts.size(), max_tokens, temperature);
    int fact_ok = 0, tokens_ok = 0;
    int64_t sum_prefill_ms = 0, sum_gen_ms = 0, sum_first_ms = 0, wall_ms = 0;
    long sum_fresh = 0, sum_gen = 0, sum_prompt = 0, sum_reused = 0;
    nlohmann::json results = nlohmann::json::array();
    for (const auto & p : prompts) {
        std::printf("\n-- %s\n", p.label.c_str());
        bool tok_ok = false;
        const std::string tok_info = check_prompt_tokens(e, p, &tok_ok);
        tokens_ok += tok_ok;
        std::printf("  prompt: %s%s\n  reply: ", tok_info.c_str(), tok_ok ? "" : "  <-- WRONG");
        std::fflush(stdout);
        e.arm();
        std::string streamed;
        const int64_t t0 = now_ms();
        const std::string reply = e.generate(p.text, max_tokens, temperature, [&](const std::string & t) {
            streamed += t;
            std::fputs(t.c_str(), stdout);
            std::fflush(stdout);
            return true;
        });
        const int64_t took = now_ms() - t0;
        std::printf("\n");
        if (!e.error().empty()) std::printf("  error: %s\n", e.error().c_str());
        const llm::Stats s = e.stats();
        print_stats(s);
        std::printf("  ended: %s; %lld ms in all\n", llm::end_name(s.end), (long long) took);
        bool has_must = true;
        for (const auto & m : p.must) has_must = has_must && lower(reply).find(lower(m)) != std::string::npos;
        const bool intro = !p.self.empty() && lower(reply).find(lower(p.self)) != std::string::npos;
        fact_ok += has_must && !intro;
        std::printf("  fact check: %s%s\n", has_must ? "required words present" : "MISSING required words",
                    intro ? ", but introduces itself" : "");
        check(!reply.empty() && streamed == reply && valid_utf8(reply) && reply.find("<|") == std::string::npos,
              "non-empty, valid UTF-8, streamed == returned, no special tokens");
        sum_prefill_ms += s.prefill_ms;
        sum_gen_ms += s.gen_ms;
        sum_first_ms += s.first_token_ms;
        wall_ms += took;
        sum_fresh += s.prompt_tokens - s.reused_tokens;
        sum_gen += s.gen_tokens;
        sum_prompt += s.prompt_tokens;
        sum_reused += s.reused_tokens;
        results.push_back({
            {"label", p.label}, {"npc", p.npc}, {"name", p.name}, {"building", p.building}, {"intent", p.intent},
            {"must", p.must}, {"self", p.self}, {"template", p.templ}, {"prompt", p.text}, {"raw", reply},
            {"error", e.error()}, {"end", llm::end_name(s.end)}, {"promptTokensOk", tok_ok},
            {"promptTokens", s.prompt_tokens}, {"reusedTokens", s.reused_tokens}, {"prefillMs", s.prefill_ms},
            {"genTokens", s.gen_tokens}, {"genMs", s.gen_ms}, {"firstTokenMs", s.first_token_ms}, {"totalMs", took},
            {"threads", s.threads}, {"prefillPerSec", per_sec(s.prompt_tokens - s.reused_tokens, s.prefill_ms)},
            {"genPerSec", per_sec(s.gen_tokens, s.gen_ms)},
        });
    }
    const size_t np = prompts.size();
    check(tokens_ok == (int) np, std::to_string(tokens_ok) + " of " + std::to_string(np) +
                                     " prompts are one ChatML conversation as the model expects (no BOS, special tokens, <|im_end|> ends the turn)");
    std::printf("\n  %d of %zu replies would pass the fact words check (the app's full validate() runs in HostRepliesTest)\n", fact_ok, np);
    std::printf("  totals: %ld prompt tokens, %ld reused from the KV cache (%.0f%%), %ld processed in %lld ms (%.1f tok/s);"
                " %ld generated in %lld ms (%.1f tok/s)\n",
                sum_prompt, sum_reused, sum_prompt ? 100.0 * sum_reused / sum_prompt : 0.0, sum_fresh, (long long) sum_prefill_ms,
                per_sec(sum_fresh, sum_prefill_ms), sum_gen, (long long) sum_gen_ms, per_sec(sum_gen, sum_gen_ms));
    std::printf("  per reply (mean): first token after %.0f ms, %.0f ms in all\n", (double) sum_first_ms / np, (double) wall_ms / np);
    if (!json_out.empty()) {
        const nlohmann::json doc = {
            {"model", model}, {"backend", info}, {"threads", e.stats().threads}, {"context", n_ctx}, {"maxTokens", max_tokens},
            {"temperature", temperature}, {"dialogue", dialogue_file}, {"replies", results},
        };
        std::ofstream out(json_out);
        out << doc.dump(1, ' ', false, nlohmann::json::error_handler_t::replace) << "\n";
        check(out.good(), "wrote " + json_out);
    }
    if (!checks) {
        std::printf("\n  peak RSS %ld MB\n", rss_kb("VmHWM:") / 1024);
        e.unload();
        std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
        return failures ? 1 : 0;
    }

    std::printf("\n== cancel and robustness (with the model)\n");
    {   // cancel from another thread mid-generation
        e.arm();
        std::atomic<bool> first{false};
        std::thread canceller([&] {
            while (!first.load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
            e.cancel();
        });
        int64_t t_cancel = 0;
        const std::string r = e.generate(prompts[0].text, 400, 0.3f, [&](const std::string &) {
            if (!first.exchange(true)) t_cancel = now_ms();
            return true;
        });
        const int64_t stop_ms = now_ms() - t_cancel;
        canceller.join();
        check(e.error() == "cancelled" && e.stats().gen_tokens < 20,
              "cancel during generation stops it (" + std::to_string(e.stats().gen_tokens) + " tokens, returned " +
                  std::to_string(stop_ms) + " ms after the cancel)");
        const std::string again = e.generate(prompts[0].text, 8, 0.3f, nullptr);
        check(e.error() == "cancelled" && again.empty(), "a cancel stays in force until arm()");
        e.arm();
        e.generate(prompts[0].text, 8, 0.3f, nullptr);
        check(e.error().empty() && e.stats().reused_tokens == e.stats().prompt_tokens - 1,
              "after arm() it runs again and reuses the cached prompt (" + std::to_string(e.stats().reused_tokens) + "/" +
                  std::to_string(e.stats().prompt_tokens) + ")");
    }
    {   // cancel during a long prefill: the cache must stay consistent
        std::string long_prompt = prompts[prompts.size() > 1 ? 1 : 0].text;
        std::string filler;
        for (int i = 0; i < 40; ++i) filler += "The bakery opens at seven and the square fills with people. ";
        long_prompt.insert(long_prompt.find("FACTS\n") + 6, filler + "\n");
        e.arm();
        std::thread canceller([&] {
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
            e.cancel();
        });
        const int64_t t0 = now_ms();
        const std::string r = e.generate(long_prompt, 20, 0.3f, nullptr);
        const int64_t took = now_ms() - t0;
        canceller.join();
        check(e.error() == "cancelled" && r.empty(),
              "cancel during prefill returns (" + std::to_string(took) + " ms, cache now " + std::to_string(e.cached_tokens()) + " tokens)");
        e.arm();
        const std::string r2 = e.generate(long_prompt, 20, 0.3f, nullptr);
        check(e.error().empty() && !r2.empty(), "the same prompt afterwards works, reusing " + std::to_string(e.stats().reused_tokens) +
                                                   " of " + std::to_string(e.stats().prompt_tokens) + " tokens");
    }
    {
        std::string huge = prompts[0].text;
        std::string filler;
        for (int i = 0; i < 400; ++i) filler += "Words and more words. ";
        huge.insert(huge.find("FACTS\n") + 6, filler);
        e.arm();
        const std::string r = e.generate(huge, 20, 0.3f, nullptr);
        check(r.empty() && e.error().rfind("prompt too long", 0) == 0, "prompt longer than the context: \"" + e.error() + "\"");
        const std::string r2 = e.generate(prompts[0].text, 0, 0.3f, nullptr);
        check(e.error().empty() && r2.empty(), "max_tokens 0 only fills the cache (prefill " + std::to_string(e.stats().prefill_ms) + " ms)");
        bool called = false;
        const std::string r3 = e.generate(prompts[0].text, 40, 0.3f, [&](const std::string &) { called = true; return false; });
        check(called && e.stats().gen_tokens <= 2, "returning false from the callback stops: \"" + r3 + "\"");
    }
    std::printf("\n  peak RSS %ld MB\n", rss_kb("VmHWM:") / 1024);
    e.unload();
    std::printf("\n%s (%d failed checks)\n", failures ? "FAILED" : "ALL CHECKS PASSED", failures);
    return failures ? 1 : 0;
}
