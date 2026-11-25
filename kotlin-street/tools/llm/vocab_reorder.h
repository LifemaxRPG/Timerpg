// English-first vocabulary order for a Qwen3.5 GGUF (kotlin-street/docs/MODEL_FILES.md, ENGINE_TAILORING.md idea 1).
//
// Rewrites a GGUF so that the tokens of a ranked list (head_vocab.txt: the tokens our English NPC lines use, most useful
// first) get the ids 0..N-1 and every other token follows in its original order. Everything indexed by token id moves
// with it: the tokenizer's per-token arrays (tokens, token_type, scores), the special token ids, and the rows of every
// tensor with a vocabulary dimension (token_embd, output, the MTP block's embed_tokens / shared_head_head if present).
// Merges are strings and stay as they are. The output then has the key `timerpg.head_vocab` = K: the first K rows of
// the (tied) head hold every token an English reply needs, so a runtime patch can compute logits for those K only.
// The model computes exactly the same numbers in the new order (each logit is the same row times the same vector).
//
// Only ggml's gguf API (gguf.h, in ggml-base) is used, so the app's native library can compile this file unchanged.
// Streams: the source's tensor data is read in chunks and written once; memory is the GGUF metadata (~50 MB with the
// 248k-token vocabulary and its merges), a 4 MB buffer and the permutation (2 MB). Writes DST.part, verifies it (the
// metadata re-read, every tensor's bytes re-read and hashed, sampled rows compared with the source), fsyncs, renames.
// Safe to rerun: a finished DST with the same list and K is reported as already done; a stale DST.part is overwritten.
#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace timerpg {

/** The ranked list (head_vocab.txt). */
struct HeadVocab {
    std::vector<uint32_t> ranked;    // original token ids, most useful first (at least k of them)
    uint32_t n_vocab = 0;            // the vocabulary it was made for
    uint32_t k = 0;                  // default head size
    std::string vocab_hash;          // vocab_hash() of the tokenizer it was made for (16 hex digits)
};

/** Reads head_vocab.txt ("# key value" header lines, then one original token id per line). */
bool load_head_vocab(const std::string & path, HeadVocab & out, std::string & err);

/** A 64-bit hash of a GGUF's token strings in order (identifies the tokenizer; the 4B and 9B share one). "" on error. */
std::string vocab_hash(const std::string & gguf_path, std::string & err);

struct ReorderOptions {
    uint32_t k = 0;                  // head size written as timerpg.head_vocab; 0 = the list's default
    bool verify = true;              // re-read and check DST.part before the rename
    bool force = false;              // redo even if DST is already done
    /** Called now and then with the fraction done (0..1, writing then verifying); return false to cancel. */
    std::function<bool(double)> progress;
};

enum class ReorderResult { Done, AlreadyDone, Cancelled, Failed };

/** SRC (a Hugging Face Qwen3.5 GGUF) -> DST (same model, English-first ids). err says why on Failed. */
ReorderResult reorder_vocab(const std::string & src, const std::string & dst, const HeadVocab & list,
                            const ReorderOptions & opt, std::string & err);

/** The metadata keys written into DST. */
constexpr const char * KEY_HEAD_VOCAB = "timerpg.head_vocab";    // u32: K, the head's rows that matter
constexpr const char * KEY_VOCAB_ORDER = "timerpg.vocab_order";  // string: "head_vocab v1 <hash of the ranked list>"
constexpr const char * KEY_VOCAB_SRC = "timerpg.vocab_src";      // string: vocab_hash() of the source tokenizer

}  // namespace timerpg
