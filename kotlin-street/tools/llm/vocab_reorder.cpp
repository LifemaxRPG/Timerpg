// English-first vocabulary order for a Qwen3.5 GGUF: see vocab_reorder.h and kotlin-street/docs/MODEL_FILES.md.
#ifndef _FILE_OFFSET_BITS
#define _FILE_OFFSET_BITS 64
#endif
#include "vocab_reorder.h"

#include "ggml.h"
#include "gguf.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <sstream>

namespace timerpg {
namespace {

constexpr uint64_t FNV_OFFSET = 1469598103934665603ULL, FNV_PRIME = 1099511628211ULL;
constexpr size_t BUF = 4u << 20;  // copy buffer

uint64_t fnv_bytes(uint64_t h, const void * p, size_t n) {
    const auto * b = static_cast<const uint8_t *>(p);
    for (size_t i = 0; i < n; ++i) { h ^= b[i]; h *= FNV_PRIME; }
    return h;
}
/** Fast integrity hash for tensor data, 8 bytes a step, the same however the bytes are split into calls (not a
 *  standard hash: only ever compared with itself). */
struct DataHash {
    uint64_t h = FNV_OFFSET;
    uint8_t carry[8]; size_t nc = 0;
    void word(uint64_t w) { h ^= w; h *= FNV_PRIME; h ^= h >> 29; }
    void update(const uint8_t * p, size_t n) {
        while (nc && n) { carry[nc++] = *p++; --n; if (nc == 8) { uint64_t w; std::memcpy(&w, carry, 8); word(w); nc = 0; } }
        for (; n >= 8; p += 8, n -= 8) { uint64_t w; std::memcpy(&w, p, 8); word(w); }
        while (n) { carry[nc++] = *p++; --n; }
    }
    uint64_t final() const { return fnv_bytes(h, carry, nc); }
};
std::string hex64(uint64_t v) { char s[17]; std::snprintf(s, sizeof s, "%016" PRIx64, v); return s; }

struct GgufPtr {
    gguf_context * g = nullptr; ggml_context * m = nullptr;
    ~GgufPtr() { if (g) gguf_free(g); if (m) ggml_free(m); }
};
bool open_meta(const std::string & path, GgufPtr & out, bool tensors, std::string & err) {
    gguf_init_params p{ /*no_alloc*/ true, tensors ? &out.m : nullptr };
    out.g = gguf_init_from_file(path.c_str(), p);
    if (!out.g) { err = "not a readable GGUF: " + path; return false; }
    return true;
}

struct Fd {
    int fd = -1;
    ~Fd() { if (fd >= 0) ::close(fd); }
};
bool read_at(int fd, void * dst, size_t n, uint64_t off) {
    auto * p = static_cast<uint8_t *>(dst);
    while (n) {
        ssize_t r = ::pread(fd, p, n, (off_t) off);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return false;
        p += r; n -= (size_t) r; off += (uint64_t) r;
    }
    return true;
}
bool write_at(int fd, const void * src, size_t n, uint64_t off) {
    const auto * p = static_cast<const uint8_t *>(src);
    while (n) {
        ssize_t w = ::pwrite(fd, p, n, (off_t) off);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) return false;
        p += w; n -= (size_t) w; off += (uint64_t) w;
    }
    return true;
}

size_t arr_elem_size(gguf_type t) {
    switch (t) {
        case GGUF_TYPE_UINT8: case GGUF_TYPE_INT8: case GGUF_TYPE_BOOL: return 1;
        case GGUF_TYPE_UINT16: case GGUF_TYPE_INT16: return 2;
        case GGUF_TYPE_UINT32: case GGUF_TYPE_INT32: case GGUF_TYPE_FLOAT32: return 4;
        case GGUF_TYPE_UINT64: case GGUF_TYPE_INT64: case GGUF_TYPE_FLOAT64: return 8;
        default: return 0;
    }
}
bool starts_with(const std::string & s, const char * p) { return s.rfind(p, 0) == 0; }
bool ends_with(const std::string & s, const char * p) {
    const size_t n = std::strlen(p); return s.size() >= n && s.compare(s.size() - n, n, p) == 0;
}

std::string vocab_hash_of(const gguf_context * g, uint32_t * n_vocab) {
    const int64_t k = gguf_find_key(g, "tokenizer.ggml.tokens");
    if (k < 0 || gguf_get_kv_type(g, k) != GGUF_TYPE_ARRAY || gguf_get_arr_type(g, k) != GGUF_TYPE_STRING) return "";
    const size_t n = gguf_get_arr_n(g, k);
    uint64_t h = FNV_OFFSET;
    for (size_t i = 0; i < n; ++i) {
        const char * s = gguf_get_arr_str(g, k, i);
        h = fnv_bytes(h, s, std::strlen(s));
        const uint8_t sep = 0xFF;  // never in UTF-8
        h = fnv_bytes(h, &sep, 1);
    }
    if (n_vocab) *n_vocab = (uint32_t) n;
    return hex64(h);
}

std::string order_id(const HeadVocab & list) {
    uint64_t h = fnv_bytes(FNV_OFFSET, &list.n_vocab, 4);
    for (uint32_t id : list.ranked) h = fnv_bytes(h, &id, 4);
    return "head_vocab v1 " + hex64(h);
}

/** How a tensor moves: rows (2D, ne[1] == n_vocab), elements (1D of n_vocab), or not at all. */
struct TensorPlan {
    std::string name;
    uint64_t src_off = 0, dst_off = 0, size = 0;
    size_t row = 0;   // bytes per vocabulary entry when it moves, else 0
    uint64_t hash = 0;
};

}  // namespace

bool load_head_vocab(const std::string & path, HeadVocab & out, std::string & err) {
    std::ifstream f(path);
    if (!f) { err = "cannot read " + path; return false; }
    out = HeadVocab{};
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty()) continue;
        if (line[0] == '#') {
            std::istringstream ss(line.substr(1));
            std::string key, val;
            ss >> key >> val;
            if (key == "n_vocab") out.n_vocab = (uint32_t) std::stoul(val);
            else if (key == "k") out.k = (uint32_t) std::stoul(val);
            else if (key == "vocab_hash") out.vocab_hash = val;
            continue;
        }
        out.ranked.push_back((uint32_t) std::stoul(line));
    }
    if (!out.n_vocab || !out.k || out.vocab_hash.size() != 16) { err = path + ": header needs n_vocab, k and vocab_hash"; return false; }
    if (out.ranked.size() < out.k || out.ranked.size() > out.n_vocab) { err = path + ": the list must hold k..n_vocab ids"; return false; }
    std::vector<uint8_t> seen(out.n_vocab, 0);
    for (uint32_t id : out.ranked) {
        if (id >= out.n_vocab || seen[id]) { err = path + ": id out of range or repeated: " + std::to_string(id); return false; }
        seen[id] = 1;
    }
    return true;
}

std::string vocab_hash(const std::string & gguf_path, std::string & err) {
    GgufPtr g;
    if (!open_meta(gguf_path, g, false, err)) return "";
    std::string h = vocab_hash_of(g.g, nullptr);
    if (h.empty()) err = "no tokenizer.ggml.tokens in " + gguf_path;
    return h;
}

ReorderResult reorder_vocab(const std::string & src, const std::string & dst, const HeadVocab & list,
                            const ReorderOptions & opt, std::string & err) {
    const uint32_t K = opt.k ? opt.k : list.k;
    if (K == 0 || K > list.ranked.size()) { err = "k must be 1.." + std::to_string(list.ranked.size()); return ReorderResult::Failed; }
    const std::string order = order_id(list);

    GgufPtr in;
    if (!open_meta(src, in, true, err)) return ReorderResult::Failed;
    uint32_t n_vocab = 0;
    const std::string src_hash = vocab_hash_of(in.g, &n_vocab);
    if (src_hash.empty()) { err = "no tokenizer.ggml.tokens in " + src; return ReorderResult::Failed; }
    if (gguf_find_key(in.g, KEY_VOCAB_ORDER) >= 0) { err = src + " is already reordered"; return ReorderResult::Failed; }
    if (n_vocab != list.n_vocab || src_hash != list.vocab_hash) {
        err = "the list was made for another tokenizer (" + list.vocab_hash + ", " + std::to_string(list.n_vocab) +
              " tokens) than " + src + " (" + src_hash + ", " + std::to_string(n_vocab) + ")";
        return ReorderResult::Failed;
    }
    if (gguf_get_alignment(in.g) != GGUF_DEFAULT_ALIGNMENT) { err = "unsupported alignment"; return ReorderResult::Failed; }

    // perm[new] = old: the ranked list, then every other id in its original order; inv[old] = new
    std::vector<uint32_t> perm(n_vocab), inv(n_vocab, UINT32_MAX);
    size_t j = 0;
    for (uint32_t id : list.ranked) { inv[id] = (uint32_t) j; perm[j++] = id; }
    for (uint32_t id = 0; id < n_vocab; ++id) if (inv[id] == UINT32_MAX) { inv[id] = (uint32_t) j; perm[j++] = id; }

    const int64_t n_tensors = gguf_get_n_tensors(in.g);
    const uint64_t src_data = gguf_get_data_offset(in.g);

    // already done?
    struct stat st{};
    if (!opt.force && ::stat(dst.c_str(), &st) == 0) {
        GgufPtr d; std::string e2;
        if (open_meta(dst, d, false, e2)) {
            const int64_t ko = gguf_find_key(d.g, KEY_VOCAB_ORDER), kk = gguf_find_key(d.g, KEY_HEAD_VOCAB),
                          ks = gguf_find_key(d.g, KEY_VOCAB_SRC);
            bool same = ko >= 0 && kk >= 0 && ks >= 0 && order == gguf_get_val_str(d.g, ko) && gguf_get_val_u32(d.g, kk) == K &&
                        src_hash == gguf_get_val_str(d.g, ks) && gguf_get_n_tensors(d.g) == n_tensors;
            if (same && n_tensors > 0) {
                const int64_t last = n_tensors - 1;
                same = (uint64_t) st.st_size >= gguf_get_data_offset(d.g) + gguf_get_tensor_offset(d.g, last) + gguf_get_tensor_size(d.g, last);
            }
            if (same) return ReorderResult::AlreadyDone;
        }
    }

    // the new metadata: everything copied, then the per-token arrays and ids replaced
    std::vector<TensorPlan> plan((size_t) n_tensors);
    std::vector<uint8_t> meta;
    {
        gguf_context * out = gguf_init_empty();
        std::unique_ptr<gguf_context, void (*)(gguf_context *)> out_guard(out, gguf_free);
        gguf_set_kv(out, in.g);
        const int64_t n_kv = gguf_get_n_kv(in.g);
        for (int64_t k = 0; k < n_kv; ++k) {
            const std::string key = gguf_get_key(in.g, k);
            if (!starts_with(key, "tokenizer.ggml.")) continue;
            const gguf_type t = gguf_get_kv_type(in.g, k);
            if (t == GGUF_TYPE_ARRAY && gguf_get_arr_n(in.g, k) == n_vocab) {
                const gguf_type at = gguf_get_arr_type(in.g, k);
                if (at == GGUF_TYPE_STRING) {
                    std::vector<const char *> v(n_vocab);
                    for (uint32_t i = 0; i < n_vocab; ++i) v[i] = gguf_get_arr_str(in.g, k, perm[i]);
                    gguf_set_arr_str(out, key.c_str(), v.data(), n_vocab);
                } else {
                    const size_t es = arr_elem_size(at);
                    if (!es) { err = "unsupported per-token array " + key; return ReorderResult::Failed; }
                    const auto * a = static_cast<const uint8_t *>(gguf_get_arr_data(in.g, k));
                    std::vector<uint8_t> v((size_t) n_vocab * es);
                    for (uint32_t i = 0; i < n_vocab; ++i) std::memcpy(&v[(size_t) i * es], a + (size_t) perm[i] * es, es);
                    gguf_set_arr_data(out, key.c_str(), at, v.data(), n_vocab);
                }
            } else if (ends_with(key, "_token_id") && (t == GGUF_TYPE_UINT32 || t == GGUF_TYPE_INT32)) {
                const uint32_t id = t == GGUF_TYPE_UINT32 ? gguf_get_val_u32(in.g, k) : (uint32_t) gguf_get_val_i32(in.g, k);
                if (id < n_vocab) {
                    if (t == GGUF_TYPE_UINT32) gguf_set_val_u32(out, key.c_str(), inv[id]);
                    else gguf_set_val_i32(out, key.c_str(), (int32_t) inv[id]);
                }
            } else if (ends_with(key, "_token_ids") && t == GGUF_TYPE_ARRAY &&
                       (gguf_get_arr_type(in.g, k) == GGUF_TYPE_UINT32 || gguf_get_arr_type(in.g, k) == GGUF_TYPE_INT32)) {
                const size_t n = gguf_get_arr_n(in.g, k);
                std::vector<uint32_t> v(n);
                std::memcpy(v.data(), gguf_get_arr_data(in.g, k), n * 4);
                for (auto & id : v) if (id < n_vocab) id = inv[id];
                gguf_set_arr_data(out, key.c_str(), gguf_get_arr_type(in.g, k), v.data(), n);
            }
        }
        gguf_set_val_u32(out, KEY_HEAD_VOCAB, K);
        gguf_set_val_str(out, KEY_VOCAB_ORDER, order.c_str());
        gguf_set_val_str(out, KEY_VOCAB_SRC, src_hash.c_str());

        for (int64_t i = 0; i < n_tensors; ++i) {
            const char * name = gguf_get_tensor_name(in.g, i);
            const ggml_tensor * t = ggml_get_tensor(in.m, name);
            if (!t) { err = std::string("tensor without metadata: ") + name; return ReorderResult::Failed; }
            gguf_add_tensor(out, t);
            TensorPlan & p = plan[(size_t) i];
            p.name = name;
            p.src_off = src_data + gguf_get_tensor_offset(in.g, i);
            p.size = gguf_get_tensor_size(in.g, i);
            const int64_t * ne = gguf_get_tensor_ne(in.g, i);
            const bool rows = ne[1] == n_vocab && ne[2] == 1 && ne[3] == 1;
            const bool elems = ne[0] == n_vocab && ne[1] == 1 && ne[2] == 1 && ne[3] == 1 && ggml_blck_size(t->type) == 1;
            if (rows || elems) p.row = (size_t) (p.size / n_vocab);
            else if (ne[0] == n_vocab || ne[1] == n_vocab || ne[2] == n_vocab || ne[3] == n_vocab) {
                err = std::string("a vocabulary dimension this tool cannot move: ") + name; return ReorderResult::Failed;
            }
        }
        meta.resize(gguf_get_meta_size(out));
        gguf_get_meta_data(out, meta.data());
        for (int64_t i = 0; i < n_tensors; ++i) plan[(size_t) i].dst_off = meta.size() + gguf_get_tensor_offset(out, i);
    }  // the new metadata context is freed here; the source's stays for the checks

    uint64_t total = meta.size();
    for (const auto & p : plan) total = std::max<uint64_t>(total, p.dst_off + GGML_PAD(p.size, GGUF_DEFAULT_ALIGNMENT));
    const double work = (double) total * (opt.verify ? 2.0 : 1.0);
    double done = 0;
    auto tick = [&](double add) {
        done += add;
        return !opt.progress || opt.progress(std::min(1.0, done / work));
    };

    const std::string part = dst + ".part";
    Fd s, o;
    s.fd = ::open(src.c_str(), O_RDONLY);
    if (s.fd < 0) { err = "cannot open " + src; return ReorderResult::Failed; }
    o.fd = ::open(part.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0644);
    if (o.fd < 0) { err = "cannot create " + part + ": " + std::strerror(errno); return ReorderResult::Failed; }
    auto fail = [&](const std::string & why) { err = why; ::close(o.fd); o.fd = -1; ::unlink(part.c_str()); return ReorderResult::Failed; };
    auto cancel = [&]() { ::close(o.fd); o.fd = -1; ::unlink(part.c_str()); return ReorderResult::Cancelled; };

    if (!write_at(o.fd, meta.data(), meta.size(), 0)) return fail("write failed: " + part);
    std::vector<uint8_t> buf(BUF), zero(GGUF_DEFAULT_ALIGNMENT, 0);
    for (auto & p : plan) {
        DataHash h;
        if (p.row == 0) {
            for (uint64_t at = 0; at < p.size;) {
                const size_t n = (size_t) std::min<uint64_t>(BUF, p.size - at);
                if (!read_at(s.fd, buf.data(), n, p.src_off + at)) return fail("read failed: " + src);
                h.update(buf.data(), n);
                if (!write_at(o.fd, buf.data(), n, p.dst_off + at)) return fail("write failed: " + part);
                at += n;
                if (!tick((double) n)) return cancel();
            }
        } else {
            const size_t per = std::max<size_t>(1, BUF / p.row);
            for (uint32_t r0 = 0; r0 < n_vocab; r0 += (uint32_t) per) {
                const uint32_t r1 = (uint32_t) std::min<uint64_t>(n_vocab, (uint64_t) r0 + per);
                for (uint32_t r = r0; r < r1; ++r)
                    if (!read_at(s.fd, &buf[(size_t) (r - r0) * p.row], p.row, p.src_off + (uint64_t) perm[r] * p.row))
                        return fail("read failed: " + src);
                const size_t n = (size_t) (r1 - r0) * p.row;
                h.update(buf.data(), n);
                if (!write_at(o.fd, buf.data(), n, p.dst_off + (uint64_t) r0 * p.row)) return fail("write failed: " + part);
                if (!tick((double) n)) return cancel();
            }
        }
        p.hash = h.final();
        const uint64_t pad = GGML_PAD(p.size, GGUF_DEFAULT_ALIGNMENT) - p.size;
        if (pad && !write_at(o.fd, zero.data(), (size_t) pad, p.dst_off + p.size)) return fail("write failed: " + part);
    }
    if (::ftruncate(o.fd, (off_t) total) != 0 || ::fsync(o.fd) != 0) return fail("could not finish " + part);

    if (opt.verify) {
        GgufPtr v;
        if (!open_meta(part, v, false, err)) return fail("the written file does not parse: " + err);
        if (gguf_get_n_tensors(v.g) != n_tensors || gguf_get_data_offset(v.g) != meta.size()) return fail("tensor table differs");
        for (int64_t i = 0; i < n_tensors; ++i) {
            const int64_t * a = gguf_get_tensor_ne(in.g, i), * b = gguf_get_tensor_ne(v.g, i);
            if (plan[(size_t) i].name != gguf_get_tensor_name(v.g, i) || gguf_get_tensor_type(in.g, i) != gguf_get_tensor_type(v.g, i) ||
                std::memcmp(a, b, sizeof(int64_t) * GGML_MAX_DIMS) != 0 ||
                meta.size() + gguf_get_tensor_offset(v.g, i) != plan[(size_t) i].dst_off)
                return fail(std::string("tensor info differs: ") + plan[(size_t) i].name);
        }
        const int64_t kt = gguf_find_key(v.g, "tokenizer.ggml.tokens"), ks = gguf_find_key(in.g, "tokenizer.ggml.tokens");
        if (kt < 0 || gguf_get_arr_n(v.g, kt) != n_vocab) return fail("tokens missing");
        for (uint32_t i = 0; i < n_vocab; ++i)
            if (std::strcmp(gguf_get_arr_str(v.g, kt, i), gguf_get_arr_str(in.g, ks, perm[i])) != 0)
                return fail("token " + std::to_string(i) + " is not where it should be");
        const int64_t kk = gguf_find_key(v.g, KEY_HEAD_VOCAB);
        if (kk < 0 || gguf_get_val_u32(v.g, kk) != K) return fail("timerpg.head_vocab missing");
        std::vector<uint8_t> row_a, row_b;
        for (const auto & p : plan) {
            DataHash h;
            for (uint64_t at = 0; at < p.size;) {
                const size_t n = (size_t) std::min<uint64_t>(BUF, p.size - at);
                if (!read_at(o.fd, buf.data(), n, p.dst_off + at)) return fail("re-read failed: " + part);
                h.update(buf.data(), n);
                at += n;
                if (!tick((double) n)) return cancel();
            }
            if (h.final() != p.hash) return fail("data of " + p.name + " differs on re-read");
            if (p.row) {  // the rows really moved: first, last and a stride of others against the source
                row_a.resize(p.row); row_b.resize(p.row);
                for (uint32_t r = 0; r < n_vocab; r = r < 64 ? r + 1 : (r + 977 < n_vocab - 64 ? r + 977 : std::max(r + 1, n_vocab - 64))) {
                    if (!read_at(o.fd, row_a.data(), p.row, p.dst_off + (uint64_t) r * p.row) ||
                        !read_at(s.fd, row_b.data(), p.row, p.src_off + (uint64_t) perm[r] * p.row) || row_a != row_b)
                        return fail("row " + std::to_string(r) + " of " + p.name + " is wrong");
                }
            }
        }
    }

    ::close(o.fd); o.fd = -1;
    if (::rename(part.c_str(), dst.c_str()) != 0) { err = "rename failed: " + std::string(std::strerror(errno)); ::unlink(part.c_str()); return ReorderResult::Failed; }
    const size_t slash = dst.find_last_of('/');
    const std::string dir = slash == std::string::npos ? "." : dst.substr(0, slash);
    Fd d; d.fd = ::open(dir.c_str(), O_RDONLY);
    if (d.fd >= 0) ::fsync(d.fd);
    if (opt.progress) opt.progress(1.0);
    return ReorderResult::Done;
}

ReorderResult retarget_head_vocab(const std::string & path, const HeadVocab & list, uint32_t k, std::string & err) {
    if (k == 0 || k > list.ranked.size()) { err = "k must be 1.." + std::to_string(list.ranked.size()); return ReorderResult::Failed; }
    uint64_t meta_size = 0;
    uint32_t old_k = 0;
    {
        GgufPtr g;
        if (!open_meta(path, g, false, err)) return ReorderResult::Failed;
        const int64_t ko = gguf_find_key(g.g, KEY_VOCAB_ORDER), kk = gguf_find_key(g.g, KEY_HEAD_VOCAB);
        if (ko < 0 || kk < 0 || gguf_get_kv_type(g.g, kk) != GGUF_TYPE_UINT32 || gguf_get_kv_type(g.g, ko) != GGUF_TYPE_STRING) {
            err = path + " is not reordered"; return ReorderResult::Failed;
        }
        if (order_id(list) != gguf_get_val_str(g.g, ko)) { err = path + " is ordered by another list"; return ReorderResult::Failed; }
        old_k = gguf_get_val_u32(g.g, kk);
        meta_size = gguf_get_data_offset(g.g);
    }
    if (old_k == k) return ReorderResult::AlreadyDone;
    // the key as gguf serializes it: u64 length, the name, u32 type (GGUF_TYPE_UINT32), u32 value; found exactly once
    std::vector<uint8_t> pat;
    const uint64_t len = std::strlen(KEY_HEAD_VOCAB);
    const uint32_t type = GGUF_TYPE_UINT32;
    auto put = [&](const void * p, size_t n) { pat.insert(pat.end(), (const uint8_t *) p, (const uint8_t *) p + n); };
    put(&len, 8); put(KEY_HEAD_VOCAB, len); put(&type, 4); put(&old_k, 4);
    Fd f; f.fd = ::open(path.c_str(), O_RDWR);
    if (f.fd < 0) { err = "cannot open " + path + ": " + std::strerror(errno); return ReorderResult::Failed; }
    std::vector<uint8_t> head((size_t) meta_size);
    if (!read_at(f.fd, head.data(), head.size(), 0)) { err = "cannot read the header of " + path; return ReorderResult::Failed; }
    uint64_t at = UINT64_MAX;
    for (auto it = head.begin(); (it = std::search(it, head.end(), pat.begin(), pat.end())) != head.end(); ++it) {
        if (at != UINT64_MAX) { err = "the key is in the header twice"; return ReorderResult::Failed; }
        at = (uint64_t) (it - head.begin()) + pat.size() - 4;
    }
    if (at == UINT64_MAX) { err = "the key's bytes were not found"; return ReorderResult::Failed; }
    if (!write_at(f.fd, &k, 4, at) || ::fsync(f.fd) != 0) { err = "write failed: " + path; return ReorderResult::Failed; }
    GgufPtr v;
    if (!open_meta(path, v, false, err)) return ReorderResult::Failed;
    const int64_t kk = gguf_find_key(v.g, KEY_HEAD_VOCAB);
    if (kk < 0 || gguf_get_val_u32(v.g, kk) != k || gguf_get_data_offset(v.g) != meta_size) { err = "the new K did not read back"; return ReorderResult::Failed; }
    return ReorderResult::Done;
}

}  // namespace timerpg
