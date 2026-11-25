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
    long gen = 0, drafted = 0, accepted = 0, steps = 0;
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
    e.set_device((mtp << 8) | (ngram << 16));
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
    }
    return out;
}

void spec_line(const char * what, const SpecPass & p) {
    std::printf("  %-34s %5ld tokens in %6lld ms: %5.2f tok/s; %ld passes (%.2f tokens a pass); n-gram %ld guessed, %ld right (%.0f%%)\n",
                what, p.gen, (long long) p.gen_ms, per_sec(p.gen, p.gen_ms), p.steps, p.steps ? (double) p.gen / p.steps : 0.0,
                p.drafted, p.accepted, p.drafted ? 100.0 * p.accepted / p.drafted : 0.0);
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

    if (checks) {
        test_text_helpers();
        const char * tmp = std::getenv("TMPDIR");
        test_bad_models(tmp && *tmp ? tmp : "/tmp");
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
