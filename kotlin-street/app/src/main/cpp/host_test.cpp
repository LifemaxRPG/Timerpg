// Host test for the native engine (llm_core), run on a computer by tools/llm-host-test.sh:
//   llm_host_test <model.gguf> [--threads N] [--ctx N] [--max N] [--prompts FILE] [--cpu-lib NAME]... [--verbose]
// Checks the text helpers and the failure paths (missing/invalid model, cancel, too-long prompt), then answers real
// NPC prompts (made by our game logic, src/llm/factsheet.ts brief() + toPrompt()) and prints replies and timings.
// --prompts FILE: prompts separated by lines "%%" (an optional first line "# label" names one).
// --cpu-lib NAME: with a -DLLM_HOST_DL=ON build, the CPU kernel libraries to try by name, in order, as the app does.
#include "llm_core.h"

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

namespace {

struct Prompt {
    std::string label;
    std::vector<std::string> must;  // words validate() will require in the reply
    std::string self;               // the speaker's own name (an introduction is rejected)
    std::string text;
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

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s model.gguf [--threads N] [--ctx N] [--max N] [--prompts FILE] [--cpu-lib NAME]... [--verbose]\n", argv[0]);
        return 2;
    }
    std::string model = argv[1], prompts_file;
    std::vector<std::string> cpu_libs = llm::cpu_variant_libs();
    int threads = 0, n_ctx = 1024, max_tokens = 56;
    static bool verbose = false;
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&] { return i + 1 < argc ? std::string(argv[++i]) : std::string(); };
        if (a == "--threads") threads = std::atoi(next().c_str());
        else if (a == "--ctx") n_ctx = std::atoi(next().c_str());
        else if (a == "--max") max_tokens = std::atoi(next().c_str());
        else if (a == "--prompts") prompts_file = next();
        else if (a == "--cpu-lib") cpu_libs.push_back(next());
        else if (a == "--verbose") verbose = true;
    }
    llm::set_log_sink([](int level, const char * text) {
        if (level >= 3 || (verbose && level >= 2)) std::fputs(text, stderr);
    });
    std::vector<Prompt> prompts = prompts_file.empty() ? kPrompts : read_prompts(prompts_file);

    std::string info;
    const bool backend = llm::init_backend(cpu_libs, &info);
    std::printf("backend: %s\n%s\n", info.c_str(), llama_print_system_info());
    if (!backend) return 1;

    test_text_helpers();
    const char * tmp = std::getenv("TMPDIR");
    test_bad_models(tmp && *tmp ? tmp : "/tmp");

    std::printf("\n== load %s\n", model.c_str());
    llm::Engine e;
    e.sampling().seed = 42;  // reproducible runs
    e.arm();
    if (!e.load(model, threads, n_ctx)) {
        std::printf("  load failed: %s\n", e.error().c_str());
        return 1;
    }
    std::printf("  loaded in %lld ms, %d threads, context %d; RSS %ld MB\n", (long long) e.stats().load_ms,
                e.stats().threads, n_ctx, rss_kb("VmRSS:") / 1024);

    std::printf("\n== NPC replies (max %d tokens, temperature 0.3)\n", max_tokens);
    int fact_ok = 0;
    for (const auto & p : prompts) {
        std::printf("\n-- %s\n  reply: ", p.label.c_str());
        std::fflush(stdout);
        e.arm();
        std::string streamed;
        const std::string reply = e.generate(p.text, max_tokens, 0.3f, [&](const std::string & t) {
            streamed += t;
            std::fputs(t.c_str(), stdout);
            std::fflush(stdout);
            return true;
        });
        std::printf("\n");
        if (!e.error().empty()) std::printf("  error: %s\n", e.error().c_str());
        print_stats(e.stats());
        bool has_must = true;
        for (const auto & m : p.must) has_must = has_must && lower(reply).find(lower(m)) != std::string::npos;
        const bool intro = !p.self.empty() && lower(reply).find(lower(p.self)) != std::string::npos;
        fact_ok += has_must && !intro;
        std::printf("  fact check: %s%s\n", has_must ? "required words present" : "MISSING required words",
                    intro ? ", but introduces itself" : "");
        check(!reply.empty() && streamed == reply && valid_utf8(reply) && reply.find("<|") == std::string::npos,
              "non-empty, valid UTF-8, streamed == returned, no special tokens");
    }
    std::printf("\n  %d of %zu replies would pass the fact words check\n", fact_ok, prompts.size());

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
        std::string long_prompt = prompts[1].text;
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
