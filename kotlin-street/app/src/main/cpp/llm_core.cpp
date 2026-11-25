#include "llm_core.h"

#include "common.h"
#include "sampling.h"
#include "speculative.h"

#include "llama.h"
#include "gguf.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>

#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#ifndef HWCAP_FPHP
#define HWCAP_FPHP (1 << 9)
#endif
#ifndef HWCAP_ASIMDHP
#define HWCAP_ASIMDHP (1 << 10)
#endif
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1 << 20)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1 << 13)
#endif
#endif

namespace llm {

namespace {

/** Saved states of a recurrent or hybrid model kept in RAM when there is no state directory (Engine::take_snapshot):
 *  each is tens of MB (Qwen3.5-4B: its delta-net state plus the prefix's attention cache). */
constexpr size_t kMaxSnapshots = 8;

// ------------------------------------------------------------------------------------------------ state files

/** A state file: this header, the tokens (int32), then the state (llama_state_seq_get_data). */
struct SnapHeader {
    char magic[8];
    uint64_t key;          // Engine::model_key
    uint32_t n_tokens;
    uint32_t reserved;
    uint64_t state_bytes;
};
static_assert(sizeof(SnapHeader) == 32, "state file header");
constexpr char kSnapMagic[8] = {'T', 'R', 'P', 'G', 'S', 'T', '0', '1'};

uint64_t fnv1a(const void * data, size_t n, uint64_t h = 1469598103934665603ULL) {
    const auto * p = static_cast<const unsigned char *>(data);
    for (size_t i = 0; i < n; ++i) { h ^= p[i]; h *= 1099511628211ULL; }
    return h;
}

std::string hex16(uint64_t v) {
    char b[17];
    std::snprintf(b, sizeof b, "%016llx", (unsigned long long) v);
    return b;
}

bool ends_with(const std::string & s, const char * tail) {
    const size_t n = std::strlen(tail);
    return s.size() >= n && s.compare(s.size() - n, n, tail) == 0;
}

/** Marks a file as just used (its mtime: the budget removes the least recently used first). */
void touch(const std::string & path) { utimensat(AT_FDCWD, path.c_str(), nullptr, 0); }

int64_t mtime_ns(const struct stat & st) { return (int64_t) st.st_mtim.tv_sec * 1000000000LL + st.st_mtim.tv_nsec; }

/** Writes a state file: the header, [tokens], then the state llama.cpp writes into [fill]'s buffer. */
SnapHeader snap_header(uint64_t key, size_t n_tokens, size_t state_bytes) {
    SnapHeader h{};
    std::memcpy(h.magic, kSnapMagic, sizeof kSnapMagic);
    h.key = key;
    h.n_tokens = (uint32_t) n_tokens;
    h.state_bytes = state_bytes;
    return h;
}

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// ------------------------------------------------------------------------------------------------ UTF-8

const char kReplacement[] = "\xEF\xBF\xBD";  // U+FFFD

/** Length of the UTF-8 sequence a lead byte starts, or 0 if it cannot start one (continuation, C0/C1, F5+). */
int seq_len(unsigned char c) {
    if (c < 0x80) return 1;
    if (c >= 0xC2 && c <= 0xDF) return 2;
    if (c >= 0xE0 && c <= 0xEF) return 3;
    if (c >= 0xF0 && c <= 0xF4) return 4;
    return 0;
}

/** Is b valid as byte k (1..3) of a sequence starting with lead? (No overlongs, surrogates or > U+10FFFF.) */
bool valid_cont(unsigned char lead, int k, unsigned char b) {
    if ((b & 0xC0) != 0x80) return false;
    if (k == 1) {
        if (lead == 0xE0) return b >= 0xA0;
        if (lead == 0xED) return b <= 0x9F;
        if (lead == 0xF0) return b >= 0x90;
        if (lead == 0xF4) return b <= 0x8F;
    }
    return true;
}

// ------------------------------------------------------------------------------------------------ logging

std::mutex g_log_mu;
LogSink g_sink = nullptr;
std::vector<std::string> g_errors;  // error lines since clear_log_errors(), to explain a failed load
std::string g_splits;                // llama.cpp's "graph splits" line of the last context: how often a token's work
                                     // crosses between devices (more splits: more of it fell back to the CPU)
double g_mem[5] = {0, 0, 0, 0, 0};   // the last load's buffers as llama.cpp logged them (MiB), see last_load_mem

/** A "<buffer> buffer size = N MiB" line of llama.cpp's load, added up by kind. */
void count_buffer(const char * text) {
    const char * at = std::strstr(text, "buffer size = ");
    if (!at) return;
    const double mb = std::atof(at + std::strlen("buffer size = "));
    int k;
    if (std::strstr(text, "model buffer")) k = std::strstr(text, "_Mapped") ? 0 : 1;
    else if (std::strstr(text, "KV buffer") || std::strstr(text, "rotation buffer")) k = 2;
    else if (std::strstr(text, "RS buffer")) k = 3;
    else k = 4;
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_mem[k] += mb;
}

void default_sink(int level, const char * text) {
    if (level >= GGML_LOG_LEVEL_WARN) fputs(text, stderr);
}

void log_cb(ggml_log_level level, const char * text, void *) {
    static thread_local int last = GGML_LOG_LEVEL_INFO;
    int lv = level == GGML_LOG_LEVEL_CONT ? last : (int) level;
    last = lv;
    if (!text) return;
    count_buffer(text);
    if (std::strstr(text, "graph splits")) {
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == ' ')) line.pop_back();
        const size_t at = line.find("graph splits");
        std::lock_guard<std::mutex> lock(g_log_mu);
        g_splits = line.substr(at);
    }
    if (lv >= GGML_LOG_LEVEL_ERROR) {
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == ' ')) line.pop_back();
        std::lock_guard<std::mutex> lock(g_log_mu);
        if (!line.empty() && g_errors.size() < 4) g_errors.push_back(line);
    }
    (g_sink ? g_sink : default_sink)(lv, text);
}

void log_msg(int level, const std::string & msg) { (g_sink ? g_sink : default_sink)(level, (msg + "\n").c_str()); }

void clear_log_errors() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_errors.clear();
    for (double & m : g_mem) m = 0;
}

std::string load_info() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    return g_splits;
}

/** ": <what llama.cpp logged>" or "". */
std::string log_errors() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    std::string out;
    for (const auto & e : g_errors) out += (out.empty() ? ": " : " / ") + e;
    return out;
}

void ggml_abort_cb(const char * message) {
    // ggml calls abort() right after this: leave the reason in the log (logcat on Android).
    (g_sink ? g_sink : default_sink)(GGML_LOG_LEVEL_ERROR, (std::string("ggml abort: ") + (message ? message : "") + "\n").c_str());
}

int default_threads() {
    unsigned n = std::thread::hardware_concurrency();
    return (int) std::max(1u, std::min(4u, n ? n : 4u));
}

}  // namespace

std::string last_load_info() { return load_info(); }

void reset_load_mem() {
    std::lock_guard<std::mutex> lock(g_log_mu);
    for (double & m : g_mem) m = 0;
}

void last_load_mem(double out[5]) {
    std::lock_guard<std::mutex> lock(g_log_mu);
    for (int i = 0; i < 5; i++) out[i] = g_mem[i];
}

// ---------------------------------------------------------------------------------------------------- text helpers

std::string Utf8Joiner::push(const char * data, size_t len) {
    if (data && len) pending_.append(data, len);
    std::string out;
    out.reserve(pending_.size());
    const size_t n = pending_.size();
    size_t i = 0;
    while (i < n) {
        const auto c = (unsigned char) pending_[i];
        const int l = seq_len(c);
        if (l == 0) { out += kReplacement; ++i; continue; }
        if (l == 1) { out += (char) c; ++i; continue; }
        int k = 1;
        bool bad = false;
        for (; k < l && i + k < n; ++k) {
            if (!valid_cont(c, k, (unsigned char) pending_[i + k])) { bad = true; break; }
        }
        if (bad) { out += kReplacement; ++i; continue; }  // the offending byte is looked at again as a start
        if (k < l) break;                                  // a character split across tokens: wait for the rest
        out.append(pending_, i, (size_t) l);
        i += (size_t) l;
    }
    pending_.erase(0, i);
    return out;
}

std::string Utf8Joiner::flush() {
    std::string out = pending_.empty() ? std::string() : std::string(kReplacement);
    pending_.clear();
    return out;
}

std::string StopFilter::push(const std::string & text, bool * stopped) {
    *stopped = stopped_;
    if (stopped_) return {};
    held_ += text;
    size_t cut = std::string::npos;
    for (const auto & s : stops_) cut = std::min(cut, held_.find(s));
    if (cut != std::string::npos) {
        std::string out = held_.substr(0, cut);
        held_.clear();
        stopped_ = *stopped = true;
        return out;
    }
    // Keep back the longest tail that is the start of a stop string.
    size_t keep = 0;
    for (const auto & s : stops_) {
        for (size_t l = std::min(s.size() - 1, held_.size()); l > keep; --l) {
            if (held_.compare(held_.size() - l, l, s, 0, l) == 0) { keep = l; break; }
        }
    }
    std::string out = held_.substr(0, held_.size() - keep);
    held_.erase(0, held_.size() - keep);
    return out;
}

std::string StopFilter::flush() {
    std::string out;
    if (!stopped_) out.swap(held_);
    held_.clear();
    return out;
}

std::u16string utf8_to_utf16(const std::string & s) {
    std::u16string out;
    out.reserve(s.size());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        const auto c = (unsigned char) s[i];
        const int l = seq_len(c);
        if (l == 1) { out += (char16_t) c; ++i; continue; }
        bool ok = l > 0 && i + (size_t) l <= n;
        for (int k = 1; ok && k < l; ++k) ok = valid_cont(c, k, (unsigned char) s[i + k]);
        if (!ok) { out += (char16_t) 0xFFFD; ++i; continue; }
        uint32_t cp = l == 2 ? (c & 0x1F) : l == 3 ? (c & 0x0F) : (c & 0x07);
        for (int k = 1; k < l; ++k) cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3F);
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out += (char16_t) (0xD800 + (cp >> 10));
            out += (char16_t) (0xDC00 + (cp & 0x3FF));
        } else {
            out += (char16_t) cp;
        }
        i += (size_t) l;
    }
    return out;
}

// ---------------------------------------------------------------------------------------------------- backend

void set_log_sink(LogSink sink) { g_sink = sink; }

std::string cpu_features() {
#if defined(__aarch64__) && defined(__linux__)
    const unsigned long hw = getauxval(AT_HWCAP), hw2 = getauxval(AT_HWCAP2);
    std::string f = "neon";
    if (hw & HWCAP_ASIMDDP) f += " dotprod";
    if ((hw & HWCAP_FPHP) && (hw & HWCAP_ASIMDHP)) f += " fp16";
    if (hw2 & HWCAP2_I8MM) f += " i8mm";
    return f;
#else
    return {};
#endif
}

std::vector<std::string> cpu_variant_libs() {
    std::vector<std::string> libs;
#if defined(__aarch64__) && defined(__ANDROID__)
    // Must match TIMERPG_CPU_VARIANTS in CMakeLists.txt. Each is only tried when the CPU has what it was built for
    // (checked here, before it is even loaded, and again by ggml's own score check).
    const unsigned long hw = getauxval(AT_HWCAP), hw2 = getauxval(AT_HWCAP2);
    const bool dot = hw & HWCAP_ASIMDDP, fp16 = (hw & HWCAP_FPHP) && (hw & HWCAP_ASIMDHP), i8mm = hw2 & HWCAP2_I8MM;
    if (dot && fp16 && i8mm) libs.emplace_back("libggml-cpu-android_armv8.6_1.so");
    if (dot && fp16) libs.emplace_back("libggml-cpu-android_armv8.2_2.so");
    libs.emplace_back("libggml-cpu-android_armv8.0_1.so");
#endif
    return libs;
}

/** The accelerators of a kind: 1 the GPU (OpenCL), 2 the NPU (Hexagon's HTP sessions). */
std::vector<ggml_backend_dev_t> devices_of(int kind) {
    std::vector<ggml_backend_dev_t> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU) continue;
        const bool npu = std::string(ggml_backend_dev_name(d)).rfind("HTP", 0) == 0;
        if ((kind == 2) == npu) out.push_back(d);
    }
    return out;
}

namespace {
std::atomic<bool> g_gpu_at_start{false};
std::atomic<bool> g_gpu_tried{false};
}  // namespace

void set_gpu_backend(bool at_start) { g_gpu_at_start.store(at_start); }

bool ensure_gpu_backend() {
#if defined(__ANDROID__)
    static std::mutex m;
    std::lock_guard<std::mutex> l(m);
    if (!g_gpu_tried.exchange(true)) {
        const auto t0 = std::chrono::steady_clock::now();
        const bool ok = ggml_backend_load("libggml-opencl.so") != nullptr;
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
        log_msg(GGML_LOG_LEVEL_INFO, std::string("llm: the GPU's backend (OpenCL) ") + (ok ? "loaded" : "did not load") + " in " +
                                         std::to_string((long long) ms) + " ms");
    }
#endif
    return !devices_of(1).empty();
}

bool has_gpu() {
#if defined(__ANDROID__)
    if (!g_gpu_tried.load()) return true;  // (not loaded yet: it is, when a load asks for the GPU)
#endif
    return !devices_of(1).empty();
}
bool has_npu() { return !devices_of(2).empty(); }

// ------------------------------------------------------------------------------------------------ the NPU's power vote

namespace {

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

/** ggml-hexagon's runtime vote (our patch, patches/ggml-hexagon-power-vote.patch): sessions voted, or -1. */
using SetPowerFn = int (*)(const char * mode, int * n_failed);

struct NpuPower {
    std::mutex m;
    std::condition_variable cv;
    bool per_burst = true;      // "auto"
    std::string fixed;          // the profile for the whole process when not per burst
    int hold_ms = 400;
    SetPowerFn fn = nullptr;
    bool attached = false;      // the backends are loaded and fn looked up
    int active = 0;             // NpuBurst guards alive
    int vote = -1;              // 1 burst, 0 idle, -1 none made yet
    int64_t release_at_us = 0;  // the hold's end (0: no release pending)
    bool timer = false;
    int sessions = 0;           // HTP sessions the last vote reached
    // the current burst, for the breadcrumb at its release
    int64_t burst_at_us = 0;
    double vote_ms = 0;         // how long the burst vote took (the FastRPC call and HAP_power_set: the clocks' ramp)
    bool first_pending = false;
    int64_t first_begin_us = 0;
    double first_gap_ms = -1;   // from the vote to the first compute's start
    double first_ms = -1;       // the first compute itself
    int first_tokens = 0;
    int bursts = 0;
    std::deque<std::string> events;  // for the app's breadcrumbs (npu_power_events)
};

NpuPower & npu() {
    static NpuPower * p = new NpuPower();  // (never destroyed: the hold's thread may outlive static destruction)
    return *p;
}

void npu_event(NpuPower & p, const std::string & e) {
    log_msg(GGML_LOG_LEVEL_INFO, "llm: npu: " + e);
    p.events.push_back(e);
    while (p.events.size() > 16) p.events.pop_front();
}

std::string fmt_ms(double ms) {
    char b[32];
    std::snprintf(b, sizeof b, ms < 10 ? "%.2f ms" : "%.0f ms", ms);
    return b;
}

/** Votes (p.m held). A failure is logged and changes nothing else: the sessions keep the clocks they had. */
void npu_vote(NpuPower & p, bool burst, const char * why) {
    if (!p.fn) return;
    const int64_t t0 = now_us();
    int failed = 0;
    int n = -1;
    try { n = p.fn(burst ? "burst" : "idle", &failed); } catch (...) { n = -1; }
    const double ms = (double) (now_us() - t0) / 1000.0;
    if (n < 0) {
        npu_event(p, std::string("vote ") + (burst ? "burst" : "idle") + " refused by ggml-hexagon (" + why + ")");
        return;
    }
    const int prev = p.vote;
    p.vote = burst ? 1 : 0;
    p.sessions = n;
    if (failed > 0) {
        npu_event(p, std::string("vote ") + (burst ? "burst" : "idle") + " failed on " + std::to_string(failed) + " of " +
                         std::to_string(n + failed) + " sessions (" + why + "); they keep their clocks");
    }
    if (n == 0) return;  // no session open yet: it only sets the profile sessions start with
    if (burst) {
        p.burst_at_us = t0;
        p.vote_ms = ms;
        p.first_pending = true;
        p.first_begin_us = 0;
        p.first_gap_ms = p.first_ms = -1;
        p.first_tokens = 0;
        ++p.bursts;
        log_msg(GGML_LOG_LEVEL_INFO, "llm: npu: vote burst (" + std::string(why) + ", from " + (prev == 0 ? "idle" : "start") +
                                         ") on " + std::to_string(n) + " sessions in " + fmt_ms(ms));
    } else {
        // one breadcrumb per burst: how long it held, what waking cost, then the release
        std::string e = "burst #" + std::to_string(p.bursts) + " held " + fmt_ms((double) (t0 - p.burst_at_us) / 1000.0) +
                        " (wake vote " + fmt_ms(p.vote_ms);
        if (p.first_gap_ms >= 0) e += ", first compute +" + fmt_ms(p.first_gap_ms) + ": " + std::to_string(p.first_tokens) + " tok in " + fmt_ms(p.first_ms);
        e += "), released to idle in " + fmt_ms(ms) + " (" + why + ", " + std::to_string(n) + " sessions)";
        npu_event(p, e);
    }
}

void npu_timer(NpuPower * pp) {
    NpuPower & p = *pp;
    std::unique_lock<std::mutex> l(p.m);
    for (;;) {
        if (p.release_at_us == 0) { p.cv.wait(l); continue; }
        const int64_t now = now_us();
        if (now < p.release_at_us) { p.cv.wait_for(l, std::chrono::microseconds(p.release_at_us - now)); continue; }
        p.release_at_us = 0;
        if (p.active == 0 && p.vote == 1) npu_vote(p, false, "hold over");
    }
}

/** After the backends loaded (init_backend): finds the runtime vote and, per burst, starts released. */
void npu_attach() {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (p.attached) return;
    p.attached = true;
    if (ggml_backend_reg_t reg = ggml_backend_reg_by_name("HTP")) {
        p.fn = reinterpret_cast<SetPowerFn>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_hexagon_set_power_mode"));
        if (!p.fn) {
            npu_event(p, "this ggml-hexagon has no runtime power vote (unpatched): its start profile stays for the process");
        } else if (!p.per_burst) {
            p.fn = nullptr;  // a fixed profile: ggml-hexagon's own (GGML_HEXAGON_POWER_MODE), never changed
            npu_event(p, "fixed profile " + (p.fixed.empty() ? std::string("burst") : p.fixed) + " for the process");
        } else {
            npu_vote(p, false, "start");  // (no session yet: the profile the sessions start with)
            npu_event(p, "per burst (hold " + std::to_string(p.hold_ms) + " ms)");
        }
    }
}

}  // namespace

void npu_power_policy(const std::string & mode, int hold_ms) {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (p.attached) return;  // (read once, before the backends load, as ggml-hexagon reads its own)
    p.per_burst = mode.empty() || mode == "auto";
    p.fixed = p.per_burst ? std::string() : mode;
    if (hold_ms >= 0) p.hold_ms = std::min(hold_ms, 60000);
}

std::string npu_power_status() {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (!p.attached) return "not loaded";
    if (!p.per_burst) return "fixed " + (p.fixed.empty() ? std::string("burst") : p.fixed);
    if (!p.fn) return "auto (unpatched: burst)";
    std::string s = std::string("auto ") + (p.vote == 1 ? "burst" : p.vote == 0 ? "idle" : "-");
    if (p.sessions == 0) s += " (no session)";
    if (p.bursts > 0) {
        s += ", wake " + fmt_ms(p.vote_ms);
        if (p.first_gap_ms >= 0) s += " +" + fmt_ms(p.first_gap_ms);
    }
    return s;
}

std::vector<std::string> npu_power_events() {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    std::vector<std::string> out(p.events.begin(), p.events.end());
    p.events.clear();
    return out;
}

void npu_power_release_now(const char * why) {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (!p.fn || p.active > 0 || p.vote != 1) return;
    p.release_at_us = 0;
    npu_vote(p, false, why);
}

NpuBurst::NpuBurst(const char * why) {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (!p.fn) return;
    held_ = true;
    ++p.active;
    p.release_at_us = 0;
    if (p.vote != 1) npu_vote(p, true, why);
}

NpuBurst::~NpuBurst() {
    if (!held_) return;
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (--p.active > 0 || p.vote != 1) return;
    if (p.hold_ms <= 0) { npu_vote(p, false, "done"); return; }
    p.release_at_us = now_us() + (int64_t) p.hold_ms * 1000;
    if (!p.timer) {
        try {
            std::thread(npu_timer, &p).detach();
            p.timer = true;
        } catch (...) {
            npu_vote(p, false, "done (no timer thread)");
            return;
        }
    }
    p.cv.notify_all();
}

void npu_compute_begin() {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (!p.fn || !p.first_pending || p.first_begin_us != 0) return;
    p.first_begin_us = now_us();
    p.first_gap_ms = (double) (p.first_begin_us - p.burst_at_us) / 1000.0;
}

void npu_compute_end(int n_tokens) {
    NpuPower & p = npu();
    std::lock_guard<std::mutex> l(p.m);
    if (!p.fn || !p.first_pending || p.first_begin_us == 0) return;
    p.first_ms = (double) (now_us() - p.first_begin_us) / 1000.0;
    p.first_tokens = n_tokens;
    p.first_pending = false;
    p.first_begin_us = 0;
}

namespace {
/** "timerpg.head_vocab" of a GGUF file (the English-first head's K), 0 if it has none. Read from the header only. */
int64_t head_vocab_of(const std::string & path) {
    gguf_init_params gp = {/*no_alloc*/ true, /*ctx*/ nullptr};
    gguf_context * g = gguf_init_from_file(path.c_str(), gp);
    if (!g) return 0;
    int64_t k = 0;
    const int64_t id = gguf_find_key(g, "timerpg.head_vocab");
    if (id >= 0 && gguf_get_kv_type(g, id) == GGUF_TYPE_UINT32) k = gguf_get_val_u32(g, id);
    gguf_free(g);
    return k;
}
}  // namespace

int64_t head_vocab(const std::string & path) {
    static std::mutex m;
    static std::string last_path;
    static int64_t last_mt = -1, last_k = 0;
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return 0;
    std::lock_guard<std::mutex> l(m);
    if (path != last_path || mtime_ns(st) != last_mt) { last_k = head_vocab_of(path); last_path = path; last_mt = mtime_ns(st); }
    return last_k;
}

bool init_backend(const std::vector<std::string> & cpu_libs, std::string * info) {
    static std::once_flag once;
    static bool ok = false;
    static std::string msg;
    std::call_once(once, [&] {
        try {
            llama_log_set(log_cb, nullptr);
            ggml_set_abort_callback(ggml_abort_cb);
            if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                msg = "CPU backend built in";
            } else {
                std::string tried;
                for (const auto & lib : cpu_libs) {
                    if (ggml_backend_load(lib.c_str()) && ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                        msg = "CPU backend " + lib;
                        break;
                    }
                    tried += (tried.empty() ? "" : ", ") + lib;
                }
                if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                    msg = "no CPU backend could be loaded (tried: " + (tried.empty() ? std::string("none") : tried) + ")" + log_errors();
                    log_msg(GGML_LOG_LEVEL_ERROR, msg);
                    return;
                }
            }
#if defined(__ANDROID__)
            // the Adreno GPU (OpenCL) and the Hexagon NPU, if this build has them and the phone's drivers load; else the
            // CPU alone. The NPU opens two sessions (each holds ~3.5 GB of weights: the 9B model is split over both).
            if (!getenv("GGML_HEXAGON_DEVICES")) setenv("GGML_HEXAGON_NDEV", "2", 0);
            // the GPU's backend only when asked for (set_gpu_backend): in play the model runs on the NPU
            if (g_gpu_at_start.load()) ensure_gpu_backend();
            ggml_backend_load("libggml-hexagon.so");
            npu_attach();  // (the NPU's power vote: per burst, or ggml-hexagon's fixed profile)
            for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
                ggml_backend_dev_t d = ggml_backend_dev_get(i);
                if (ggml_backend_dev_type(d) != GGML_BACKEND_DEVICE_TYPE_CPU) msg += std::string("; ") + ggml_backend_dev_name(d) + " " + ggml_backend_dev_description(d);
            }
#endif
            llama_backend_init();
            if (ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                msg += std::string(" (") + ggml_backend_dev_description(cpu) + ")";
            }
            const std::string feats = cpu_features();
            if (!feats.empty()) msg += "; cpu: " + feats;
            ok = true;
            log_msg(GGML_LOG_LEVEL_INFO, "llm: " + msg);
        } catch (const std::exception & e) {
            msg = std::string("backend init failed: ") + e.what();
        } catch (...) {
            msg = "backend init failed";
        }
    });
    if (info) *info = msg;
    return ok;
}

// ---------------------------------------------------------------------------------------------------- engine

Engine::Engine() = default;

Engine::~Engine() { unload(); }

void Engine::free_mtp() {
    if (spec_) common_speculative_free(spec_);
    spec_ = nullptr;
    if (dft_) llama_free(dft_);
    dft_ = nullptr;
    dft_cached_.clear();
}

void Engine::unload() {
    const bool had = model_ != nullptr;
    free_mtp();
    if (batch_.token) llama_batch_free(batch_);
    batch_ = {};
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
    ctx_ = nullptr;
    model_ = nullptr;
    vocab_ = nullptr;
    cached_.clear();
    snapshots_.clear();  // (the files stay: the next load of this model finds them)
    snap_key_ = 0;
    snap_dir_mtime_ = -1;
    snap_stats_ = {};
    recurrent_ = false;
    n_batch_ = 0;
    if (had) npu_power_release_now("unload");  // (the HTP sessions outlive the model: their vote must not)
}

std::vector<size_t> Engine::line_ends(const std::vector<int32_t> & toks) const {
    std::vector<size_t> out;
    char buf[64];
    for (size_t i = 0; i < toks.size(); ++i) {
        const int32_t n = llama_token_to_piece(vocab_, toks[i], buf, sizeof buf, 0, /*special*/ true);
        if (n <= 0) continue;
        const std::string piece(buf, (size_t) n);
        if (piece == "<|im_end|>") break;                    // the system message ends: what follows is the question
        if (piece.back() == '\n') out.push_back(i + 1);
    }
    return out;
}

uint64_t Engine::model_key() const {
    // a state is only valid for the same weights, cache type, device (its numbers) and rollback/MTP layout
    char desc[256] = {};
    llama_model_desc(model_, desc, sizeof desc);
    const std::string k = std::string("timerpg-state-v1|") + desc + "|" + std::to_string(llama_model_size(model_)) + "|" +
        std::to_string(llama_model_n_params(model_)) + "|" + std::to_string(llama_model_n_layer(model_)) + "|" +
        std::to_string(llama_model_n_embd(model_)) + "|" + (kv_q8_used_ ? "q8_0" : "f16") + "|dev" + std::to_string(device()) +
        "|rs" + std::to_string(llama_n_rs_seq(ctx_)) + (dft_ ? "|mtp" : "");
    return fnv1a(k.data(), k.size());
}

void Engine::set_snapshot_dir(const std::string & dir, int64_t budget_bytes, bool keep_hot, int points) {
    std::string d = dir;
    while (d.size() > 1 && d.back() == '/') d.pop_back();
    if (!d.empty()) mkdir(d.c_str(), 0700);
    if (d != snap_dir_) {
        // states held for the old place go (RAM ones would never be written; file ones are another directory's)
        snapshots_.clear();
        snap_dir_mtime_ = -1;
    }
    snap_dir_ = d;
    snap_budget_ = budget_bytes;
    snap_hot_ = keep_hot;
    snap_points_ = points & 7;
    if (!snap_hot_) for (auto & s : snapshots_) if (!s.path.empty()) std::vector<uint8_t>().swap(s.state);
    if (ctx_ && recurrent_) { scan_snapshots(true); enforce_budget(); }
    stat_snapshots();
}

void Engine::scan_snapshots(bool force) {
    if (snap_dir_.empty() || !ctx_ || !recurrent_) return;
    struct stat st;
    if (stat(snap_dir_.c_str(), &st) != 0) return;
    if (!force && mtime_ns(st) == snap_dir_mtime_) return;  // (unchanged; else another process wrote or removed one)
    snap_dir_mtime_ = mtime_ns(st);
    DIR * dir = opendir(snap_dir_.c_str());
    if (!dir) return;
    const std::string prefix = hex16(snap_key_) + "-";
    std::vector<Snapshot> found;
    while (dirent * e = readdir(dir)) {
        const std::string name = e->d_name;
        if (name.rfind(prefix, 0) != 0 || !ends_with(name, ".state")) continue;
        const std::string path = snap_dir_ + "/" + name;
        const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        SnapHeader h;
        struct stat fs;
        bool ok = fstat(fd, &fs) == 0 && pread(fd, &h, sizeof h, 0) == (ssize_t) sizeof h &&
                  std::memcmp(h.magic, kSnapMagic, sizeof kSnapMagic) == 0 && h.key == snap_key_ && h.n_tokens > 0 &&
                  h.n_tokens < (1u << 20) &&
                  (uint64_t) fs.st_size == sizeof h + (uint64_t) h.n_tokens * sizeof(int32_t) + h.state_bytes;
        Snapshot s;
        if (ok) {
            s.tokens.resize(h.n_tokens);
            const ssize_t want = (ssize_t) (h.n_tokens * sizeof(int32_t));
            ok = pread(fd, s.tokens.data(), (size_t) want, sizeof h) == want;
        }
        close(fd);
        if (!ok) { unlink(path.c_str()); continue; }  // torn or foreign: of no use to anyone
        s.path = path;
        s.bytes = (size_t) h.state_bytes;
        // (what this process knew of it stays: its RAM copy and when it was used)
        for (auto & old : snapshots_) if (old.path == path) { s.state.swap(old.state); s.used = old.used; }
        found.push_back(std::move(s));
    }
    closedir(dir);
    snapshots_.swap(found);
    stat_snapshots();
}

void Engine::enforce_budget() {
    if (snap_dir_.empty() || snap_budget_ <= 0) return;
    DIR * dir = opendir(snap_dir_.c_str());
    if (!dir) return;
    struct F { std::string path; int64_t size; int64_t mt; };
    std::vector<F> files;
    int64_t total = 0;
    while (dirent * e = readdir(dir)) {
        const std::string name = e->d_name;
        if (!ends_with(name, ".state")) continue;
        const std::string path = snap_dir_ + "/" + name;
        struct stat st;
        if (stat(path.c_str(), &st) != 0) continue;
        files.push_back({path, (int64_t) st.st_size, mtime_ns(st)});
        total += st.st_size;
    }
    closedir(dir);
    if (total <= snap_budget_) return;
    // every model's states share the budget: the least recently used go first, whichever model they are for
    std::sort(files.begin(), files.end(), [](const F & a, const F & b) { return a.mt < b.mt; });
    for (const auto & f : files) {
        if (total <= snap_budget_) break;
        if (unlink(f.path.c_str()) != 0) continue;
        total -= f.size;
        ++snap_stats_.evicted;
        snapshots_.erase(std::remove_if(snapshots_.begin(), snapshots_.end(), [&](const Snapshot & s) { return s.path == f.path; }),
                         snapshots_.end());
    }
    struct stat st;
    if (stat(snap_dir_.c_str(), &st) == 0) snap_dir_mtime_ = mtime_ns(st);
    stat_snapshots();
}

void Engine::stat_snapshots() {
    SnapStats & t = snap_stats_;
    t.files = 0; t.file_bytes = 0; t.ram = 0; t.ram_bytes = 0;
    for (const auto & s : snapshots_) {
        if (!s.path.empty()) { ++t.files; t.file_bytes += (int64_t) s.bytes; }
        if (!s.state.empty()) { ++t.ram; t.ram_bytes += (int64_t) s.state.size(); }
    }
}

void Engine::keep_hot(Snapshot & s, const uint8_t * data) {
    // with a directory, at most one state stays in RAM: the shortest on flash (the shared rules every prompt starts with)
    if (snap_dir_.empty() || !snap_hot_ || s.path.empty()) return;
    for (const auto & o : snapshots_) if (!o.path.empty() && o.tokens.size() < s.tokens.size()) return;
    for (auto & o : snapshots_) if (&o != &s) std::vector<uint8_t>().swap(o.state);
    if (s.state.empty() && data) s.state.assign(data, data + s.bytes);
}

std::vector<size_t> Engine::snapshot_points(const std::vector<int32_t> & toks, int mask) const {
    if (snap_dir_.empty()) return line_ends(toks);
    // the system message's lines (src/llm/factsheet.ts: "<|im_start|>system", the rules, "FACTS", "You are ...", the
    // manner, "The player is ...", the topic's facts, the example): saved after "FACTS" (the rules, shared by everyone),
    // after the last "The player is" line (this person, whatever the topic) and at the last line end (this fact sheet)
    std::vector<std::pair<size_t, std::string>> lines;
    std::string line;
    char buf[64];
    for (size_t i = 0; i < toks.size(); ++i) {
        const int32_t n = llama_token_to_piece(vocab_, toks[i], buf, sizeof buf, 0, /*special*/ true);
        if (n <= 0) continue;
        const std::string piece(buf, (size_t) n);
        if (piece == "<|im_end|>") break;
        line += piece;
        if (piece.back() == '\n') { lines.emplace_back(i + 1, line); line.clear(); }
    }
    if (lines.empty()) return {};
    size_t rules = 0, persona = 0;
    for (const auto & l : lines) {
        if (!rules && l.second == "FACTS\n") rules = l.first;
        if (l.second.rfind("The player is", 0) == 0) persona = l.first;
    }
    if (!rules && lines.size() > 1) rules = lines[1].first;  // (another prompt format: after its first line of text)
    std::vector<size_t> out;
    if (mask < 0) mask = snap_points_;
    const size_t pts[3] = {rules, persona, lines.back().first};
    for (int k = 0; k < 3; ++k) if ((mask >> k & 1) && pts[k] > 0 && (out.empty() || pts[k] > out.back())) out.push_back(pts[k]);
    return out;
}

size_t Engine::saved_prefix(const std::vector<int32_t> & toks) {
    scan_snapshots(false);
    size_t best = 0;
    for (const auto & s : snapshots_)
        if (s.tokens.size() < toks.size() && s.tokens.size() > best && std::equal(s.tokens.begin(), s.tokens.end(), toks.begin())) best = s.tokens.size();
    return best;
}

size_t Engine::restore_snapshot(const std::vector<int32_t> & toks) {
    scan_snapshots(false);
    Snapshot * best = nullptr;
    const uint64_t now = ++clock_;
    for (auto & s : snapshots_) {
        if (s.tokens.size() >= toks.size() || !std::equal(s.tokens.begin(), s.tokens.end(), toks.begin())) continue;
        // every snapshot this prompt starts with stays fresh (the shared rules most of all), not just the one used
        s.used = now;
        if (!best || s.tokens.size() > best->tokens.size()) best = &s;
    }
    if (!best) return 0;
    const int64_t t0 = now_ms();
    llama_memory_clear(llama_get_memory(ctx_), true);
    bool ok = false;
    if (!best->state.empty()) {
        ok = llama_state_seq_set_data(ctx_, best->state.data(), best->state.size(), 0) != 0;
        if (ok) ++snap_stats_.restores_ram;
    } else if (!best->path.empty()) {
        // mapped, not read into the heap: the pages are the file's, which the system may drop again at once
        const int fd = open(best->path.c_str(), O_RDONLY | O_CLOEXEC);
        struct stat st;
        const size_t off = sizeof(SnapHeader) + best->tokens.size() * sizeof(int32_t);
        if (fd >= 0 && fstat(fd, &st) == 0 && (size_t) st.st_size == off + best->bytes) {
            void * m = mmap(nullptr, (size_t) st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
            if (m != MAP_FAILED) {
                madvise(m, (size_t) st.st_size, MADV_SEQUENTIAL);
                const uint8_t * src = static_cast<const uint8_t *>(m) + off;
                ok = llama_state_seq_set_data(ctx_, src, best->bytes, 0) != 0;
                if (ok) keep_hot(*best, src);
                munmap(m, (size_t) st.st_size);
            }
        }
        if (fd >= 0) close(fd);
        if (ok) { ++snap_stats_.restores_file; touch(best->path); }
        else {
            // unreadable, or refused by llama.cpp: of no use, so it goes
            log_msg(GGML_LOG_LEVEL_WARN, "llm: a saved state could not be restored, removed: " + best->path);
            const std::string gone = best->path;
            unlink(gone.c_str());
            snapshots_.erase(std::remove_if(snapshots_.begin(), snapshots_.end(), [&](const Snapshot & s) { return s.path == gone; }),
                             snapshots_.end());
            best = nullptr;
        }
    }
    snap_stats_.restore_ms += now_ms() - t0;
    stat_snapshots();
    if (!ok) {
        llama_memory_clear(llama_get_memory(ctx_), true);
        return 0;
    }
    cached_ = best->tokens;
    return cached_.size();
}

void Engine::take_snapshot() {
    for (auto & s : snapshots_) if (s.tokens == cached_) { s.used = ++clock_; if (!s.path.empty()) touch(s.path); return; }
    const size_t size = llama_state_seq_get_size(ctx_, 0);
    if (size == 0) return;
    snap_stats_.state_bytes = (int64_t) size;
    Snapshot snap;
    snap.tokens = cached_;
    snap.used = ++clock_;
    snap.bytes = size;
    if (snap_dir_.empty()) {
        snap.state.resize(size);
        if (llama_state_seq_get_data(ctx_, snap.state.data(), size, 0) == 0) return;
        // a handful: the rules every prompt starts with stay (they are used every time), the rest come and go
        if (snapshots_.size() >= kMaxSnapshots) {
            auto old = std::min_element(snapshots_.begin(), snapshots_.end(), [](const Snapshot & a, const Snapshot & b) { return a.used < b.used; });
            *old = std::move(snap);
        } else {
            snapshots_.push_back(std::move(snap));
        }
        stat_snapshots();
        return;
    }
    // on flash: written once into a temporary file mapped in place (no copy in the heap), renamed when complete, so
    // another process (the writer, the game) never reads half of one
    const int64_t t0 = now_ms();
    const std::string name = hex16(snap_key_) + "-" + hex16(fnv1a(cached_.data(), cached_.size() * sizeof(int32_t))) + ".state";
    const std::string path = snap_dir_ + "/" + name;
    const std::string tmp = path + ".tmp" + std::to_string((long) getpid());
    const size_t off = sizeof(SnapHeader) + cached_.size() * sizeof(int32_t);
    const size_t total = off + size;
    const SnapHeader h = snap_header(snap_key_, cached_.size(), size);
    std::vector<uint8_t> hot;  // the state's bytes while the file is mapped, for keep_hot
    bool ok = false;
    int err = 0;
    const int fd = open(tmp.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) err = errno;
    if (fd >= 0) {
        // the blocks reserved first: a full disk is an error here, never a SIGBUS through the mapping
        const int fa = posix_fallocate(fd, 0, (off_t) total);
        if (fa == 0) {
            void * m = mmap(nullptr, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (m != MAP_FAILED) {
                auto * p = static_cast<uint8_t *>(m);
                std::memcpy(p, &h, sizeof h);
                std::memcpy(p + sizeof h, cached_.data(), cached_.size() * sizeof(int32_t));
                ok = llama_state_seq_get_data(ctx_, p + off, size, 0) == size;
                if (ok && snap_hot_) hot.assign(p + off, p + off + size);
                munmap(m, total);
            } else err = errno;
        } else if (fa == ENOSPC) {
            err = fa;
        } else {
            // (no fallocate on this file system: written from the heap instead)
            std::vector<uint8_t> buf(total);
            std::memcpy(buf.data(), &h, sizeof h);
            std::memcpy(buf.data() + sizeof h, cached_.data(), cached_.size() * sizeof(int32_t));
            ok = llama_state_seq_get_data(ctx_, buf.data() + off, size, 0) == size && ftruncate(fd, 0) == 0;
            for (size_t w = 0; ok && w < total;) {
                const ssize_t n = write(fd, buf.data() + w, total - w);
                if (n <= 0) { ok = false; err = errno; } else w += (size_t) n;
            }
            if (ok && snap_hot_) hot.assign(buf.begin() + (ptrdiff_t) off, buf.end());
        }
        if (close(fd) != 0) { ok = false; err = errno; }
        if (ok && rename(tmp.c_str(), path.c_str()) != 0) { ok = false; err = errno; }
        if (!ok) unlink(tmp.c_str());
    }
    snap_stats_.write_ms += now_ms() - t0;
    if (!ok) {
        log_msg(GGML_LOG_LEVEL_WARN, "llm: could not write a saved state to " + snap_dir_ + (err ? std::string(": ") + std::strerror(err) : ""));
        return;
    }
    ++snap_stats_.writes;
    snap.path = path;
    snapshots_.push_back(std::move(snap));
    keep_hot(snapshots_.back(), hot.empty() ? nullptr : hot.data());
    struct stat st;
    if (stat(snap_dir_.c_str(), &st) == 0) snap_dir_mtime_ = mtime_ns(st);  // (our own write: nothing new to read)
    enforce_budget();
    stat_snapshots();
}

int Engine::bake(const std::string & prompt) {
    error_.clear();
    if (!ctx_) { error_ = "no model loaded"; return -1; }
    if (!recurrent_) return 0;  // (an attention-only cache is cut back to any prefix: nothing to save)
    const NpuBurst burst("bake");  // (the NPU at full clocks while it computes, released after: docs/CONVERSATION.md)
    try {
        if (cancel_.load()) { error_ = "cancelled"; return -1; }
        std::vector<int32_t> toks;
        if (!tokenize(prompt, toks) || toks.empty()) { error_ = "could not tokenize the prompt"; return -1; }
        if (toks.size() >= llama_n_ctx(ctx_)) { error_ = "prompt too long"; return -1; }
        const std::vector<size_t> pts = snapshot_points(toks, 7);  // (every point, whatever generate() saves)
        if (pts.empty()) return 0;
        const size_t deep = pts.back();
        scan_snapshots(false);
        for (auto & s : snapshots_) {
            if (s.tokens.size() == deep && std::equal(s.tokens.begin(), s.tokens.end(), toks.begin())) {
                s.used = ++clock_;
                if (!s.path.empty()) touch(s.path);
                return (int) deep;  // already baked
            }
        }
        const std::vector<int32_t> target(toks.begin(), toks.begin() + (ptrdiff_t) deep);
        size_t at = 0;
        if (cached_.size() <= deep && std::equal(cached_.begin(), cached_.end(), target.begin()) &&
            cached_.size() >= saved_prefix(target)) {
            at = cached_.size();
        } else {
            at = restore_snapshot(target);
            if (at == 0) { llama_memory_clear(llama_get_memory(ctx_), true); cached_.clear(); }
        }
        for (size_t p : pts) {
            if (p <= at) continue;
            if (!decode_tokens(toks, at, p, false)) {
                if (cancel_.load()) error_ = "cancelled";
                return -1;
            }
            at = p;
            take_snapshot();
        }
        return (int) deep;
    } catch (const std::bad_alloc &) {
        error_ = "out of memory while baking";
    } catch (const std::exception & e) {
        error_ = std::string("baking failed: ") + e.what();
    }
    return -1;
}

void Engine::set_draft_pool(const std::string & lines) {
    pool_.clear();
    if (!vocab_) return;
    size_t at = 0;
    while (at < lines.size()) {
        size_t e = lines.find('\n', at);
        if (e == std::string::npos) e = lines.size();
        // each line as the reply would hold it: plain text, no special tokens
        const std::string line = lines.substr(at, e - at);
        at = e + 1;
        if (line.empty() || line.size() > 4096) continue;
        std::vector<int32_t> t((size_t) line.size() + 8);
        const int32_t n = llama_tokenize(vocab_, line.data(), (int32_t) line.size(), t.data(), (int32_t) t.size(), false, false);
        if (n <= 1) continue;
        t.resize((size_t) n);
        pool_.push_back(std::move(t));
        if (pool_.size() >= 64) break;
    }
}

void Engine::ngram_draft(const std::vector<int32_t> & seq, int n_max, std::vector<int32_t> & out) const {
    out.clear();
    if (n_max <= 0) return;
    const size_t n = seq.size();
    // the longest match first (4 tokens, down to ngram_min_), the most recent place first: the reply so far, then the
    // prompt (the facts, the conversation), then the pool
    for (int m = 4; m >= ngram_min_; --m) {
        if (n < (size_t) m + 1) continue;
        const int32_t * key = seq.data() + n - m;
        for (size_t i = n - m; i-- > 0;) {
            if (std::equal(key, key + m, seq.data() + i)) {
                for (size_t k = i + m; k < n && (int) out.size() < n_max; ++k) out.push_back(seq[k]);
                if (!out.empty()) return;
            }
        }
        for (const auto & line : pool_) {
            if (line.size() < (size_t) m + 1) continue;
            for (size_t i = 0; i + m < line.size(); ++i) {
                if (std::equal(key, key + m, line.data() + i)) {
                    for (size_t k = i + m; k < line.size() && (int) out.size() < n_max; ++k) out.push_back(line[k]);
                    return;
                }
            }
        }
    }
}

const char * end_name(End e) {
    switch (e) {
        case End::none: return "none";
        case End::eog: return "end of turn";
        case End::stop_text: return "stop text";
        case End::limit: return "max tokens";
        case End::callback: return "stopped by caller";
        case End::cancelled: return "cancelled";
        case End::context: return "context full";
        case End::error: return "error";
    }
    return "?";
}

bool Engine::abort_cb(void * self) { return static_cast<Engine *>(self)->cancel_.load(std::memory_order_relaxed); }

bool Engine::load(const std::string & path, int threads, int n_ctx) {
    unload();
    error_.clear();
    stats_ = {};
    const int64_t t0 = now_ms();
    try {
        std::string info;
        if (!init_backend(cpu_variant_libs(), &info)) {
            error_ = info;
            return false;
        }
        const NpuBurst burst("load");  // (loading at full clocks as before; released after the hold)
        // Friendly errors for the usual mistakes, before llama.cpp sees the file.
        FILE * f = std::fopen(path.c_str(), "rb");
        if (!f) {
            error_ = "cannot open the model file " + path + ": " + std::strerror(errno);
            return false;
        }
        char magic[4] = {};
        const size_t got = std::fread(magic, 1, sizeof magic, f);
        std::fclose(f);
        if (got != sizeof magic || std::memcmp(magic, "GGUF", 4) != 0) {
            error_ = "not a GGUF model file: " + path;
            return false;
        }

        clear_log_errors();
        llama_model_params mp = llama_model_default_params();
        // on the GPU or the NPU when asked and there is one (all layers: they share the phone's memory); the GPU's backend
        // is loaded now if it was left for later
        if (device_ == 1) ensure_gpu_backend();
        devs_ = device_ > 0 ? devices_of(device_) : std::vector<ggml_backend_dev_t>();
        if (!devs_.empty()) {
            devs_.push_back(nullptr);
            mp.devices = devs_.data();
            mp.n_gpu_layers = 999;
        } else {
            mp.n_gpu_layers = 0;
        }
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        // no repacking unless asked: repacked weights are copied out of the mapped file (the 9B's 5.4 GB in memory the
        // system cannot reclaim, read whole at load), which left no room for speech and the voices beside it
        mp.use_extra_bufts = repack_;
        // a tied output head on the CPU, mapped (MEMORY_AUDIT.md, M3). The model's input embeddings always stay on the CPU
        // (mapped); a model without its own output weights (Qwen3.5-4B) gets its head as a duplicate of that tensor
        // (TENSOR_DUPLICATED), put on the output device: on the NPU, a second copy of 497 MB in DMA memory. Matching the
        // tensor's name sends both to the CPU's buffer, the same file pages. A model with an output.weight of its own is
        // untouched (the pattern matches only token_embd). The head's matrix-vector product then runs on the CPU, once
        // per generated token (prompts ask for one row of logits).
        // A file with the English-first head (timerpg.head_vocab, idea 1) puts only its first K rows on the device (a
        // prefix of the same data, 67-134 MB instead of 497): the head stays on the NPU and the whole step with it.
        if (head_mapped_ && !devs_.empty() && head_vocab(path) > 0) {
            log_msg(GGML_LOG_LEVEL_INFO, "llm: the English-first head (" + std::to_string(head_vocab(path)) + " tokens) stays on the device");
        } else if (head_mapped_ && !devs_.empty()) {
            overrides_[0] = { "^token_embd\\.weight$", ggml_backend_cpu_buffer_type() };
            overrides_[1] = { nullptr, nullptr };
            mp.tensor_buft_overrides = overrides_;
        }
        // the model's own next-token prediction head (MTP), when the file has one and it is wanted
        mp.load_mtp = mtp_n_ > 0;
        // cancel() also stops a load.
        mp.progress_callback = [](float, void * self) { return !static_cast<Engine *>(self)->cancel_.load(); };
        mp.progress_callback_user_data = this;
        model_ = llama_model_load_from_file(path.c_str(), mp);
        if (!model_) {
            error_ = cancel_.load() ? "cancelled" : "could not load the model" + log_errors();
            return false;
        }
        if (llama_model_has_encoder(model_) || !llama_model_has_decoder(model_)) {
            error_ = "unsupported model (not a decoder-only chat model)";
            unload();
            return false;
        }
        vocab_ = llama_model_get_vocab(model_);

        const int train = llama_model_n_ctx_train(model_);
        if (n_ctx <= 0) n_ctx = 1024;
        n_ctx = std::max(128, train > 0 ? std::min(n_ctx, train) : n_ctx);
        if (threads <= 0) threads = default_threads();

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t) n_ctx;
        cp.n_batch = (uint32_t) std::min(n_ctx, batch_max_);
        cp.n_ubatch = cp.n_batch;
        cp.n_seq_max = 1;
        // We only ever ask for the last token's logits (decode_tokens sets one output per batch). Without this the
        // compute buffer is sized for n_batch x vocab logits: ~300 MB more for Qwen3's 152k vocabulary.
        cp.n_outputs_max = 1;
        cp.n_outputs_max_per_seq = 1;
        // MTP: the guesses are checked in one batch (logits for each), and the recurrent state can step back over the
        // ones that were wrong
        const bool want_mtp = mtp_n_ > 0 && llama_model_n_layer_nextn(model_) > 0;
        // (n-gram guesses the same way: as many as either asks for)
        const int guess_max = std::max(want_mtp ? mtp_n_ : 0, ngram_n_);
        if (guess_max > 0) {
            cp.n_outputs_max = (uint32_t) (guess_max + 1);
            cp.n_outputs_max_per_seq = (uint32_t) (guess_max + 1);
            cp.n_rs_seq = (uint32_t) guess_max;
        }
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.no_perf = true;
        cp.abort_callback = &Engine::abort_cb;
        cp.abort_callback_data = this;
        kv_q8_used_ = false;
        if (kv_q8_) {
            // the cache in 8 bits: half the memory of 16 (a quantized V cache needs flash attention)
            llama_context_params q = cp;
            q.type_k = GGML_TYPE_Q8_0;
            q.type_v = GGML_TYPE_Q8_0;
            q.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
            ctx_ = llama_init_from_model(model_, q);
            if (ctx_) {
                kv_q8_used_ = true;
            } else {
                log_msg(GGML_LOG_LEVEL_WARN, "an 8-bit cache could not be set up here; using the 16-bit one");
                std::lock_guard<std::mutex> lock(g_log_mu);
                for (int i = 2; i < 5; i++) g_mem[i] = 0;   // (the failed context's buffers were freed)
                g_errors.clear();
            }
        }
        if (!ctx_) ctx_ = llama_init_from_model(model_, cp);
        if (!ctx_) {
            error_ = "could not create the model context (not enough memory?)" + log_errors();
            unload();
            return false;
        }
        if (!llama_get_memory(ctx_)) {
            // an embedding model (BERT, nomic, ...): no cache, no logits to sample from
            error_ = "unsupported model (not a chat model: it has no memory for a conversation)";
            unload();
            return false;
        }
        recurrent_ = llama_model_is_recurrent(model_) || llama_model_is_hybrid(model_);
        n_batch_ = (int32_t) llama_n_batch(ctx_);
        // n-gram guesses need a cache that can step back over the wrong ones: an attention cache always can, a recurrent
        // state only with its rollback snapshots (n_rs_seq, which llama.cpp clamps to 0 where the model cannot)
        ngram_used_ = ngram_n_;
        if (ngram_used_ > 0 && recurrent_) ngram_used_ = std::min(ngram_used_, (int) llama_n_rs_seq(ctx_));
        if (ngram_n_ > 0 && ngram_used_ == 0) log_msg(GGML_LOG_LEVEL_WARN, "n-gram guesses need a state that can step back; generating without them");
        if (want_mtp) {
            // the head's own context, sharing the main one's memory; any failure: plain generation
            llama_context_params dp = cp;
            dp.ctx_type = LLAMA_CONTEXT_TYPE_MTP;
            dp.n_rs_seq = 0;
            dp.ctx_other = ctx_;
            dp.abort_callback = nullptr;
            dp.abort_callback_data = nullptr;
            dft_ = llama_init_from_model(model_, dp);
            const auto rm = common_context_can_seq_rm(ctx_);
            if (dft_ && rm != COMMON_CONTEXT_SEQ_RM_TYPE_FULL && rm != COMMON_CONTEXT_SEQ_RM_TYPE_NO) {
                common_params_speculative sp;
                sp.types = { COMMON_SPECULATIVE_TYPE_DRAFT_MTP };
                sp.draft.n_max = mtp_n_;
                sp.draft.ctx_tgt = ctx_;
                sp.draft.ctx_dft = dft_;
                spec_ = common_speculative_init(sp, 1);
            }
            if (!spec_) {
                log_msg(GGML_LOG_LEVEL_WARN, "MTP could not be set up; generating without it");
                free_mtp();
            }
        }
        batch_ = llama_batch_init(n_batch_, 0, 1);
        if (recurrent_) {
            snap_key_ = model_key();
            scan_snapshots(true);
            enforce_budget();
        }
        stats_.threads = threads;
        stats_.load_ms = now_ms() - t0;
        return true;
    } catch (const std::bad_alloc &) {
        error_ = "out of memory while loading the model";
    } catch (const std::exception & e) {
        error_ = std::string("could not load the model: ") + e.what();
    } catch (...) {
        error_ = "could not load the model";
    }
    unload();
    return false;
}

bool Engine::tokenize(const std::string & text, std::vector<int32_t> & out) {
    if (text.size() > 1u << 20) return false;
    const int32_t len = (int32_t) text.size();
    int32_t n = llama_tokenize(vocab_, text.data(), len, nullptr, 0, /*add_special*/ true, /*parse_special*/ true);
    if (n == INT32_MIN) return false;
    if (n < 0) n = -n;
    out.resize((size_t) n);
    if (n == 0) return true;
    const int32_t m = llama_tokenize(vocab_, text.data(), len, out.data(), n, true, true);
    if (m < 0) return false;
    out.resize((size_t) m);
    return true;
}

void Engine::resync_cache(const std::vector<int32_t> & attempted) {
    // A failed or aborted llama_decode keeps the micro-batches it finished: find out how far the cache got.
    llama_memory_t mem = llama_get_memory(ctx_);
    const llama_pos pmin = llama_memory_seq_pos_min(mem, 0);
    const llama_pos pmax = llama_memory_seq_pos_max(mem, 0);
    size_t n = (pmin == 0 && pmax >= 0) ? std::min((size_t) pmax + 1, attempted.size()) : 0;
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) n, -1)) {
        llama_memory_clear(mem, true);
        n = 0;
    }
    cached_.assign(attempted.begin(), attempted.begin() + (ptrdiff_t) n);
}

bool Engine::decode_tokens(const std::vector<int32_t> & toks, size_t from, size_t to, bool logits_last) {
    // toks[i] goes to position i, so after success the cache holds toks[0, to).
    llama_batch & batch = batch_;
    bool ok = true;
    for (size_t i = from; i < to && ok;) {
        const size_t n = std::min((size_t) n_batch_, to - i);
        for (size_t k = 0; k < n; ++k) {
            batch.token[k] = toks[i + k];
            batch.pos[k] = (llama_pos) (i + k);
            batch.n_seq_id[k] = 1;
            batch.seq_id[k][0] = 0;
            batch.logits[k] = logits_last && i + k == to - 1;
        }
        batch.n_tokens = (int32_t) n;
        npu_compute_begin();
        const int rc = llama_decode(ctx_, batch);
        npu_compute_end((int) n);
        if (rc != 0) {
            if (!cancel_.load()) error_ = "llama_decode failed (" + std::to_string(rc) + ")" + log_errors();
            resync_cache(std::vector<int32_t>(toks.begin(), toks.begin() + (ptrdiff_t) (i + n)));
            ok = false;
            break;
        }
        cached_.insert(cached_.end(), toks.begin() + (ptrdiff_t) i, toks.begin() + (ptrdiff_t) (i + n));
        i += n;
    }
    return ok;
}

llama_sampler * Engine::make_chain(float temperature, uint32_t seed) {
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    // As the WebAssembly version: repeat penalty, top-k, top-p, temperature.
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), sampling_.repeat_last_n,
                                                               sampling_.repeat_penalty, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        // (greedy: the guide needs the candidates in order, so the best 40 first; without it the best of all, as before)
        if (guide_on_) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampling_.top_k));
            llama_sampler_chain_add(smpl, guide_sampler(vocab_, &guide_rules_, &guide_counts_));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampling_.top_k));
        // the guide on the top-k: the tokens that would fail validate() go, the rest keep their order and odds
        if (guide_on_) llama_sampler_chain_add(smpl, guide_sampler(vocab_, &guide_rules_, &guide_counts_));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sampling_.top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    }
    return smpl;
}

std::string Engine::generate(const std::string & prompt, int max_tokens, float temperature, const OnText & on_text) {
    error_.clear();
    const Stats keep = stats_;
    stats_ = {};
    reply_tokens_.clear();
    stats_.load_ms = keep.load_ms;
    stats_.threads = keep.threads;
    if (!ctx_) {
        error_ = "no model loaded";
        return {};
    }
    // the NPU at full clocks from the prefill to the last token, released [hold] after (docs/CONVERSATION.md)
    const NpuBurst burst("reply");
    std::string reply;
    llama_sampler * smpl = nullptr;
    try {
        const int64_t t0 = now_ms();
        clear_log_errors();
        if (cancel_.load()) {
            error_ = "cancelled";
            return {};
        }
        std::vector<int32_t> toks;
        if (!tokenize(prompt, toks) || toks.empty()) {
            error_ = toks.empty() ? "empty prompt" : "could not tokenize the prompt";
            return {};
        }
        const size_t n_ctx = llama_n_ctx(ctx_);
        if (toks.size() >= n_ctx) {
            error_ = "prompt too long: " + std::to_string(toks.size()) + " tokens, context " + std::to_string(n_ctx);
            return {};
        }
        max_tokens = (int) std::max<int64_t>(0, std::min<int64_t>(max_tokens, (int64_t) (n_ctx - toks.size())));

        // Reuse the longest common prefix with what is in the KV cache; at least the last prompt token is processed
        // again, for its logits.
        size_t reuse = 0;
        while (reuse < cached_.size() && reuse < toks.size() && cached_[reuse] == toks[reuse]) ++reuse;
        reuse = std::min(reuse, toks.size() - 1);
        llama_memory_t mem = llama_get_memory(ctx_);
        if (recurrent_) {
            // the state can only go on from where it is: when the cache holds exactly a start of this prompt (and no
            // saved state goes further), carry on from there; otherwise start from the longest saved state that begins it
            if (reuse != cached_.size() || saved_prefix(toks) > reuse) {
                reuse = restore_snapshot(toks);
                if (reuse == 0) { llama_memory_clear(mem, true); cached_.clear(); }
            }
        } else if (!llama_memory_seq_rm(mem, 0, (llama_pos) reuse, -1)) {
            llama_memory_clear(mem, true);
            reuse = 0;
        }
        cached_.resize(reuse);
        stats_.prompt_tokens = (int32_t) toks.size();
        stats_.reused_tokens = (int32_t) reuse;

        guide_counts_ = {};
        if (guide_on_) guide_rules_.build(prompt, guide_self_, guide_recent_);
        if ((spec_ || ngram_used_ > 0) && max_tokens > 0) {
            std::string r = generate_mtp(toks, reuse, max_tokens, temperature, on_text, t0);
            stats_.guide_banned = guide_counts_.banned;
            stats_.guide_fallbacks = guide_counts_.fallbacks;
            return r;
        }
        const int64_t tp = now_ms();
        bool prefilled = true;
        size_t at = reuse;
        if (recurrent_) {
            // stop at each line end of the facts to save the state there: the next prompt about this person (same lines
            // first) or anyone (the same rules) starts from the longest one
            for (size_t b : snapshot_points(toks)) {
                if (b <= at || b >= toks.size()) continue;
                if (!(prefilled = decode_tokens(toks, at, b, false))) break;
                at = b;
                take_snapshot();
            }
        }
        if (prefilled) prefilled = decode_tokens(toks, at, toks.size(), /*logits_last*/ true);
        const int64_t tg = now_ms();
        stats_.prefill_ms = tg - tp;
        if (!prefilled) {
            if (cancel_.load()) error_ = "cancelled";
            stats_.end = cancel_.load() ? End::cancelled : End::error;
            return {};
        }
        if (max_tokens == 0) return {};  // prefill only (warms the cache for this prompt)

        smpl = make_chain(temperature, sampling_.seed);

        Utf8Joiner joiner;
        StopFilter stop({"<|im_end|>", "<|im_start|>", "<|endoftext|>"});
        bool stopped = false, halted = false;
        auto emit = [&](const std::string & text) {
            if (text.empty() || halted) return;
            reply += text;
            if (on_text && !on_text(text)) halted = true;
        };
        std::vector<int32_t> seq = toks;  // prompt + reply so far, as in the cache
        char buf[256];
        for (int n = 0; n < max_tokens; ++n) {
            if (cancel_.load()) { error_ = "cancelled"; stats_.end = End::cancelled; break; }
            const llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
            if (llama_vocab_is_eog(vocab_, tok)) { stats_.end = End::eog; break; }
            if (++stats_.gen_tokens == 1) stats_.first_token_ms = now_ms() - t0;
            reply_tokens_.push_back(tok);
            int32_t len = llama_token_to_piece(vocab_, tok, buf, sizeof buf, 0, /*special*/ true);
            std::string piece;
            if (len < 0) {
                piece.resize((size_t) -len);
                len = llama_token_to_piece(vocab_, tok, piece.data(), (int32_t) piece.size(), 0, true);
                piece.resize(len > 0 ? (size_t) len : 0);
            } else {
                piece.assign(buf, (size_t) len);
            }
            emit(stop.push(joiner.push(piece.data(), piece.size()), &stopped));
            if (stopped || halted || n + 1 == max_tokens) {
                stats_.end = stopped ? End::stop_text : halted ? End::callback : End::limit;
                break;
            }
            // Feed the token back for the next one.
            seq.push_back(tok);
            if (seq.size() > n_ctx) { stats_.end = End::context; break; }
            if (!decode_tokens(seq, seq.size() - 1, seq.size(), true)) {
                if (cancel_.load()) error_ = "cancelled";
                stats_.end = cancel_.load() ? End::cancelled : End::error;
                break;
            }
        }
        if (!stopped) emit(stop.push(joiner.flush(), &stopped));
        if (!stopped) emit(stop.flush());
        stats_.gen_ms = now_ms() - tg;
        stats_.guide_banned = guide_counts_.banned;
        stats_.guide_fallbacks = guide_counts_.fallbacks;
    } catch (const std::bad_alloc &) {
        error_ = "out of memory while generating";
    } catch (const std::exception & e) {
        error_ = std::string("generation failed: ") + e.what();
    } catch (...) {
        error_ = "generation failed";
    }
    if (smpl) llama_sampler_free(smpl);
    return reply;
}


std::string Engine::generate_mtp(const std::vector<int32_t> & toks, size_t at, int max_tokens, float temperature,
                                 const OnText & on_text, int64_t t0) {
    std::string reply;
    const size_t n = toks.size();
    const size_t n_ctx = llama_n_ctx(ctx_);
    llama_memory_t mem = llama_get_memory(ctx_);
    llama_memory_t dmem = dft_ ? llama_get_memory(dft_) : nullptr;
    // the head's cache keeps what it shares with this prompt, up to where the main cache goes on from
    {
        size_t keep = 0;
        while (keep < dft_cached_.size() && keep < at && dft_cached_[keep] == toks[keep]) ++keep;
        if (dmem && !llama_memory_seq_rm(dmem, 0, (llama_pos) keep, -1)) { llama_memory_clear(dmem, true); keep = 0; }
        dft_cached_.resize(keep);
    }
    const int64_t tp = now_ms();
    // the prompt but its last token: snapshots at the fact sheet's line ends as usual, the last stretch through the
    // speculator too (the head needs the model's state at its end)
    bool ok = true;
    if (recurrent_) {
        for (size_t b : snapshot_points(toks)) {
            if (b <= at || b >= n - 1) continue;
            if (!(ok = decode_tokens(toks, at, b, false))) break;
            at = b;
            take_snapshot();
        }
    }
    common_batch cb(ctx_);
    for (size_t i = at; ok && i < n - 1;) {
        const size_t e = std::min(n - 1, i + (size_t) n_batch_);
        cb.clear();
        for (size_t k = i; k < e; ++k) cb.add(toks[k], (llama_pos) k, 0, false);
        if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, cb.get()) != 0) {
            if (!cancel_.load()) error_ = "llama_process failed" + log_errors();
            resync_cache(std::vector<int32_t>(toks.begin(), toks.begin() + (ptrdiff_t) e));
            ok = false;
            break;
        }
        cached_.insert(cached_.end(), toks.begin() + (ptrdiff_t) i, toks.begin() + (ptrdiff_t) e);
        if (spec_) common_speculative_process(spec_, cb);
        i = e;
    }
    const int64_t tg = now_ms();
    stats_.prefill_ms = tg - tp;
    if (!ok) {
        if (cancel_.load()) error_ = "cancelled";
        stats_.end = cancel_.load() ? End::cancelled : End::error;
        return {};
    }

    common_params_sampling ps;
    ps.seed = sampling_.seed;
    ps.temp = temperature;
    ps.top_k = sampling_.top_k;
    ps.top_p = sampling_.top_p;
    ps.min_p = 0.0f;
    ps.penalty_repeat = sampling_.repeat_penalty;
    ps.penalty_last_n = sampling_.repeat_last_n;
    ps.no_perf = true;
    // guided: our own chain (the guide in it), each verify position sampled in order as common_sampler does;
    // otherwise llama.cpp's common sampler as before
    common_sampler * smpl = guide_on_ ? nullptr : common_sampler_init(model_, ps);
    llama_sampler * gchain = guide_on_ ? make_chain(temperature, sampling_.seed) : nullptr;
    if (!smpl && !gchain) { error_ = "could not set up sampling"; return {}; }
    const int pad_to = fixed_steps_ ? std::max(ngram_used_, spec_ ? mtp_n_ : 0) : 0;

    Utf8Joiner joiner;
    StopFilter stop({"<|im_end|>", "<|im_start|>", "<|endoftext|>"});
    bool stopped = false, halted = false, done = false;
    auto emit = [&](const std::string & text) {
        if (text.empty() || halted) return;
        reply += text;
        if (on_text && !on_text(text)) halted = true;
    };
    llama_token id_last = toks.back();
    std::vector<llama_token> in_ctx(toks.begin(), toks.end() - 1);  // the tokens in the cache, position i = in_ctx[i]
    std::vector<llama_token> draft;
    if (spec_) common_speculative_begin(spec_, 0, in_ctx);
    std::vector<int32_t> seq;  // the prompt and the reply so far, ending with id_last: what the n-gram guess looks in
    common_batch bt(ctx_);
    char buf[256];
    int produced = 0;
    try {
        while (!done) {
            if (cancel_.load()) { error_ = "cancelled"; stats_.end = End::cancelled; break; }
            const int n_past = (int) in_ctx.size();
            int n_draft_max = std::min((int) n_ctx - n_past - 2, max_tokens - produced - 1);
            draft.clear();
            bool from_ngram = false;
            if (n_draft_max > 0 && ngram_used_ > 0) {
                // first the n-gram guess (free: no model runs), then the MTP head if it found none
                seq.assign(in_ctx.begin(), in_ctx.end());
                seq.push_back(id_last);
                ngram_draft(seq, std::min(n_draft_max, ngram_used_), draft);
                from_ngram = !draft.empty();
            }
            if (n_draft_max > 0 && spec_ && draft.empty()) {
                auto & dp = common_speculative_get_draft_params(spec_, 0);
                dp.drafting = true;
                dp.n_max = std::min(n_draft_max, mtp_n_);
                dp.pos0 = n_past;
                dp.id_last = id_last;
                dp.prompt = &in_ctx;
                dp.result = &draft;
                dp.temp = temperature;
                common_speculative_draft(spec_);
                // drafting ran the head ahead in its own cache: back to where the main model is before checking
                if (dmem) llama_memory_seq_rm(dmem, 0, (llama_pos) n_past, -1);
            }
            // a fixed length: a short guess padded with filler (the last guess again, or the last token), so every pass
            // has the same shape (graph reuse); filler is checked like any guess (kept only if it is what is sampled)
            const size_t real = draft.size();
            if (pad_to > 0 && (int) draft.size() < std::min(pad_to, n_draft_max)) {
                const llama_token fill = draft.empty() ? id_last : draft.back();
                while ((int) draft.size() < std::min(pad_to, n_draft_max)) draft.push_back(fill);
            }
            // check [last, guess0, guess1, ...] in one pass
            bt.clear();
            bt.add(id_last, n_past, 0, true);
            for (size_t i = 0; i < draft.size(); ++i) bt.add(draft[i], n_past + 1 + (llama_pos) i, 0, true);
            if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, bt.get()) != 0) {
                if (!cancel_.load()) error_ = "llama_process failed" + log_errors();
                stats_.end = cancel_.load() ? End::cancelled : End::error;
                if (cancel_.load()) error_ = "cancelled";
                break;
            }
            if (spec_) common_speculative_process(spec_, bt);
            // each position sampled as without guesses, a guess kept only while it is what was sampled: the same tokens
            // (greedy) and the same distribution (sampling), one draw per token either way
            std::vector<llama_token> ids;
            if (smpl) {
                ids = common_sampler_sample_and_accept_n(smpl, ctx_, draft);
            } else {
                for (size_t i = 0; i <= draft.size(); ++i) {
                    const llama_token t = llama_sampler_sample(gchain, ctx_, (int32_t) i);  // (accepts it too)
                    ids.push_back(t);
                    if (i == draft.size() || t != draft[i]) break;
                }
            }
            if (ids.empty()) { error_ = "sampling failed"; stats_.end = End::error; break; }
            if (spec_) common_speculative_accept(spec_, 0, (uint16_t) (ids.size() - 1));
            ++stats_.steps;
            const int32_t right = (int32_t) ids.size() - 1;
            const int32_t right_real = std::min(right, (int32_t) real);
            stats_.drafted += (int32_t) real;
            stats_.accepted += right_real;
            stats_.padded += (int32_t) (draft.size() - real);
            stats_.pad_accepted += right - right_real;
            if (from_ngram) {
                stats_.ngram_drafted += (int32_t) real;
                stats_.ngram_accepted += right_real;
            }
            in_ctx.push_back(id_last);  // now in the cache, with the accepted guesses after it
            for (size_t i = 0; i < ids.size(); ++i) {
                const llama_token tok = ids[i];
                if (llama_vocab_is_eog(vocab_, tok)) { stats_.end = End::eog; done = true; break; }
                if (++stats_.gen_tokens == 1) stats_.first_token_ms = now_ms() - t0;
                reply_tokens_.push_back(tok);
                ++produced;
                int32_t len = llama_token_to_piece(vocab_, tok, buf, sizeof buf, 0, /*special*/ true);
                std::string piece;
                if (len < 0) {
                    piece.resize((size_t) -len);
                    len = llama_token_to_piece(vocab_, tok, piece.data(), (int32_t) piece.size(), 0, true);
                    piece.resize(len > 0 ? (size_t) len : 0);
                } else {
                    piece.assign(buf, (size_t) len);
                }
                emit(stop.push(joiner.push(piece.data(), piece.size()), &stopped));
                if (stopped || halted || produced >= max_tokens) {
                    stats_.end = stopped ? End::stop_text : halted ? End::callback : End::limit;
                    done = true;
                    break;
                }
                if (i + 1 < ids.size()) in_ctx.push_back(tok);  // an accepted guess: already in the cache
                id_last = tok;
            }
            // the wrong guesses (and anything past where we stopped) out of the cache
            if (dmem) llama_memory_seq_rm(dmem, 0, (llama_pos) in_ctx.size(), -1);
            if (!llama_memory_seq_rm(mem, 0, (llama_pos) in_ctx.size(), -1)) {
                llama_memory_clear(mem, true);
                in_ctx.clear();
                if (!done) { error_ = "could not step back over a wrong guess"; stats_.end = End::error; }
                done = true;
            }
            if (!done && in_ctx.size() + 2 >= n_ctx) { stats_.end = End::context; done = true; }
        }
    } catch (...) {
        if (smpl) common_sampler_free(smpl);
        if (gchain) llama_sampler_free(gchain);
        throw;
    }
    if (smpl) common_sampler_free(smpl);
    if (gchain) llama_sampler_free(gchain);
    if (!stopped) emit(stop.push(joiner.flush(), &stopped));
    if (!stopped) emit(stop.flush());
    cached_ = in_ctx;
    dft_cached_ = in_ctx;
    stats_.gen_ms = now_ms() - tg;
    return reply;
}

}  // namespace llm
