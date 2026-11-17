// Speech to text on a computer: asr_host_test MODEL.gguf MMPROJ.gguf [--threads N] [--context "names"] FILE.wav...
// Reads 16-bit or float WAVs (any rate, mono or stereo: mixed down and resampled linearly to the model's rate, as the
// app records it), transcribes each and prints the text and timings.
#include "asr_core.h"
#include "llm_core.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

static bool read_wav(const std::string & path, std::vector<float> & out, int & rate) {
    std::ifstream f(path, std::ios::binary);
    std::vector<char> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (b.size() < 44 || std::memcmp(b.data(), "RIFF", 4) != 0) return false;
    size_t p = 12; int ch = 1, bits = 16, fmt = 1;
    while (p + 8 <= b.size()) {
        uint32_t len; std::memcpy(&len, &b[p + 4], 4);
        if (!std::memcmp(&b[p], "fmt ", 4)) {
            uint16_t v; std::memcpy(&v, &b[p + 8], 2); fmt = v;
            std::memcpy(&v, &b[p + 10], 2); ch = v;
            uint32_t r; std::memcpy(&r, &b[p + 12], 4); rate = (int) r;
            std::memcpy(&v, &b[p + 22], 2); bits = v;
        } else if (!std::memcmp(&b[p], "data", 4)) {
            const size_t n = std::min<size_t>(len, b.size() - p - 8) / (bits / 8) / ch;
            out.resize(n);
            for (size_t i = 0; i < n; ++i) {
                float s = 0;
                for (int c = 0; c < ch; ++c) {
                    const char * q = &b[p + 8 + (i * ch + c) * (bits / 8)];
                    if (fmt == 3 && bits == 32) { float x; std::memcpy(&x, q, 4); s += x; }
                    else { int16_t x; std::memcpy(&x, q, 2); s += x / 32768.0f; }
                }
                out[i] = s / ch;
            }
            return true;
        }
        p += 8 + len + (len & 1);
    }
    return false;
}

static std::vector<float> resample(const std::vector<float> & in, int from, int to) {
    if (from == to || in.empty()) return in;
    const size_t n = (size_t) ((double) in.size() * to / from);
    std::vector<float> out(n);
    for (size_t i = 0; i < n; ++i) {
        const double x = (double) i * from / to;
        const size_t a = (size_t) x; const size_t b = std::min(a + 1, in.size() - 1);
        out[i] = (float) (in[a] + (in[b] - in[a]) * (x - a));
    }
    return out;
}

int main(int argc, char ** argv) {
    if (argc < 4) { std::fprintf(stderr, "usage: %s MODEL MMPROJ [--threads N] [--context TEXT] FILE.wav...\n", argv[0]); return 2; }
    int threads = 4; std::string context; std::vector<std::string> files;
    for (int i = 3; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--threads" && i + 1 < argc) threads = std::atoi(argv[++i]);
        else if (a == "--context" && i + 1 < argc) context = argv[++i];
        else files.push_back(a);
    }
    llm::set_log_sink([](int level, const char * t) { if (level >= 4 && t) std::fputs(t, stderr); });
    std::string info;
    llm::init_backend({}, &info);
    llm::Asr asr;
    if (!asr.load(argv[1], argv[2], threads)) { std::fprintf(stderr, "load failed: %s\n", asr.error().c_str()); return 1; }
    std::printf("speech model loaded; %d Hz; %d threads\n", asr.sample_rate(), threads);
    int failed = 0;
    for (const auto & f : files) {
        std::vector<float> pcm; int rate = 0;
        if (!read_wav(f, pcm, rate)) { std::printf("%s: not a WAV\n", f.c_str()); ++failed; continue; }
        pcm = resample(pcm, rate, asr.sample_rate());
        const std::string t = asr.transcribe(pcm.data(), pcm.size(), context);
        const double secs = (double) pcm.size() / asr.sample_rate();
        std::printf("%s (%.1f s): \"%s\"\n  audio read in %lld ms, %lld ms in all (%.2fx real time)%s%s\n", f.c_str(), secs, t.c_str(),
                    (long long) asr.encode_ms(), (long long) asr.total_ms(), asr.total_ms() / 1000.0 / secs,
                    asr.error().empty() ? "" : "; error: ", asr.error().c_str());
        if (!asr.error().empty()) ++failed;
    }
    // the parser
    const bool ok = llm::asr_text("language English<asr_text> Hello there. ") == "Hello there." && llm::asr_text("language None") == "";
    std::printf("asr_text parser: %s\n", ok ? "ok" : "FAILED");
    return failed || !ok ? 1 : 0;
}
