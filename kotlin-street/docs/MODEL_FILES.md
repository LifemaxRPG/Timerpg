# Our own dialogue model files, made on the phone (2026-11-25)

The offline, model-file side of ENGINE_TAILORING.md ideas 1 (an English-only output head) and 3 (an importance matrix
from our own dialogue). The tools are in `kotlin-street/tools/llm/`. **The owner's decision (2026-11-25): the
customized files are produced on the phone, not hosted anywhere**: the app downloads the Hugging Face files it already
downloads (`dialogue/ModelDownloads.kt`), and rewrites them on the charger with code and small data files shipped in the
APK. Nothing here is uploaded anywhere, and nothing needs publishing.

Everything below marked *measured* was measured on this build server (4 shared Xeon cores, no GPU, 16 GB RAM shared with
other jobs); nothing was measured on the S25 Ultra. Every S25 figure is an estimate.

## What the phone does, in order

1. **Download** (as today) the Hugging Face GGUF, e.g. `unsloth/Qwen3.5-4B-GGUF/Qwen3.5-4B-Q4_0.gguf`
   (2,583,221,408 bytes). With the calibrated quantization (below): instead the full-precision
   `unsloth/Qwen3.5-4B-GGUF/Qwen3.5-4B-BF16.gguf` (8.42 GB) once, then step 2.
2. *(Calibrated quantization)* `llama_model_quantize()` with our importance matrix (`imatrix-qwen3.5-4b.gguf`, in the
   APK) and the parameters in "Calibrated quantization": BF16 -> Q4_0 (the NPU's) and/or Q4_K_M. The BF16 file can then
   be deleted (or kept to requantize when a new imatrix ships).
3. **Reorder the vocabulary** with `timerpg::reorder_vocab()` (`vocab_reorder.cpp/.h`) and `head_vocab.txt` (in the
   APK): a few seconds, the file stays the same size (+160 bytes of keys).
4. The runtime patch (ENGINE_TAILORING.md idea 1, step 3; the other job's) reads `timerpg.head_vocab` and computes the
   head for the first K tokens only.

Steps 2 and 3 commute (the importance matrix is per input column, the permutation moves output rows), but 3 after 2 is
cheaper: it rewrites the 2.6 GB file, not the 8.4 GB one.

## 1. English-first vocabulary order

### The phone-side tool: `vocab_reorder.cpp` / `vocab_reorder.h`

```cpp
timerpg::HeadVocab list;  std::string err;
timerpg::load_head_vocab(assetPath("head_vocab.txt"), list, err);
timerpg::ReorderOptions opt;                 // k = 0: the list's default (49152); verify = true
opt.progress = [&](double f) { return !cancelled(); };   // false cancels (the .part is removed)
auto r = timerpg::reorder_vocab(models + "/qwen3.5-4b-q4_0.gguf", models + "/qwen3.5-4b-q4_0-en.gguf", list, opt, err);
// Done, AlreadyDone (the same list and K are already in DST), Cancelled, Failed (err says why)
```

- **Only ggml's gguf API** (`gguf.h`, in `ggml-base`, which the app's native library already links) plus POSIX
  `pread`/`pwrite`: the next job compiles `vocab_reorder.cpp` into the app unchanged. `vocab_reorder_main.cpp` is the
  same code as a command line (`vocab_reorder SRC DST --list head_vocab.txt [--k N] [--no-verify] [--force]`,
  `--hash MODEL` prints a tokenizer's hash, `--info MODEL` lists the tensors that move; both read only the metadata, so
  the first 25 MB of a file are enough).
- **What moves.** `perm[new] = old`: the list's ranked ids get 0..N-1, every other id follows in its original order.
  Every `tokenizer.ggml.*` array as long as the vocabulary (`tokens`, `token_type`, `scores` when present) is permuted;
  every `tokenizer.ggml.*_token_id` (eos 248046 -> its new id, pad 248055 -> ...) and `*_token_ids` array is remapped;
  `merges` are strings and stay. Every tensor with `ne[1] == n_vocab` has its rows permuted (`token_embd` of the 4B,
  which is also its tied head; `token_embd` and `output` of the 9B; `blk.N.nextn.embed_tokens` /
  `shared_head_head` if an MTP file carries them: the 9B MTP file does not, it shares the main head); a tensor with the
  vocabulary in another dimension makes the tool refuse rather than write something wrong. A quantized row is a
  contiguous byte range (2560 or 4096 values = whole blocks of every type), so rows move as bytes, never requantized.
- **Bounded memory, streaming**: the source's metadata (~50 MB: 248k token strings and 247k merges, held twice while
  the new header is built), a 4 MB copy buffer, the 2 MB permutation. Measured on the server: **peak RSS 88 MB**,
  **3.6-5.1 s** for the 4B Q4_0 (2.58 GB, tmpfs to tmpfs, verification included). On the S25 (estimate): bound by UFS 4.0
  (~2-3 GB/s sequential write, reads of 2 KB rows from the page cache) and the re-read: ~5-15 s.
- **Safe to rerun**: writes `DST.part`, then verifies it before the rename: the header re-parsed, every tensor's type,
  shape and offset, every token string in its new place, every tensor's bytes re-read and hashed against what was
  written, and for each moved tensor the first and last 64 rows and every 977th compared with the source's row
  `perm[r]`. Then `fsync`, `rename`, `fsync` of the directory. A crash leaves at most a `.part` (overwritten next time);
  a finished DST with the same list, K and source tokenizer returns `AlreadyDone`. It refuses a source that is already
  reordered, and a list made for another tokenizer (`vocab_hash`).
- The output is the same size as the source plus ~160 bytes (three keys).

### The kept tokens: `head_vocab.txt`

A text file (390 KB, ships in the APK): a header (`vocab_hash`, `n_vocab 248320`, default `k 49152`, `ranked 65536`)
and 65,536 original token ids, most useful first. Any K up to 65,536 can be chosen from the same file (`--k`), so the
choice of K is a setting, not a new list. Rebuilt from scratch by `tools/llm/make-head-vocab.sh MODEL` (~10
minutes on this server, 750 MB of downloads; the shipped file is its output); the order (`make_head_vocab.py`):

1. **Always** (373 tokens): every control and user-defined token (ChatML, `<think>`, tool and vision markers), the 256
   byte symbols of the byte-level BPE (so any text stays expressible), every single ASCII character token and its
   space-prefixed form.
2. **Our own text** (8,319 more): every token of the dialogue exports of 12 town seeds x 4 years (alpha ... xi, 1950,
   1975, 2000, 2025, plus delta 1965: 27,039 distinct prompts, 5.1 M tokens; `export-dialogue.ts --all`), their
   template lines, names, buildings and jobs, and every string literal in `src/` (names, places, jobs the simulation can
   generate). **The app's own exports (eta 2025 in `assets/dialogue.json`, gamma 2025 in `assets/town/gamma/`) are held
   out**: they are the checks below.
3. **English by frequency**: each corpus's share of each token, weighted: TinyStories (simple narrative English, 42 M
   tokens) 1, PG-19 books (public domain, pre-1919, 86 M tokens) 1, WikiText-103 (57 M tokens) 0.5, the repository's
   docs (0.5 M tokens) 0.25. Downloaded from Hugging Face datasets (`roneneldan/TinyStories`, `emozilla/pg19`,
   `Salesforce/wikitext`); only token counts are kept, no text ships.
4. **Line-start forms**: right after each word token " word", the tokens of "word", "Word", "\"Word", " Word" and
   " word". A reply's first word has no space before it ("Splendid" is "Spl" + "endid", not " Splendid"), and the
   first coverage run found such forms among the best-ranked tokens outside the set (" Clinic", "Spl", " Doctors").

Coverage of each corpus's token occurrences (and distinct tokens) by the first K (measured):

| corpus | K = 32,768 | K = 49,152 | K = 65,536 |
|---|---|---|---|
| TinyStories | 99.823% (16,702 of 21,717) | 99.984% (20,391) | 99.999% (21,471) |
| PG-19 | 97.561% (30,971 of 72,635) | 99.560% (45,466) | 99.935% (57,861) |
| WikiText-103 | 95.318% (27,260 of 72,746) | 99.044% (41,355) | 99.870% (53,650) |
| repository docs | 97.503% (15,248 of 20,472) | 99.472% (18,463) | 100% |
| our exports' prompts (other seeds) | 100% (741 of 741) | 100% | 100% |
| our lines, names, literals | 100% (8,400 of 8,400) | 100% | 100% |
| **held out: the app's eta + gamma prompts** | 99.905% (492 of 493) | **100%** | 100% |

Our whole dialogue world uses only ~9,000 distinct tokens (the prompts 741!); the rest of K is general English for what
the player says and what the model may answer.

### Proofs (measured, server)

All with `tools/llm/vocab_test` (server only; links llama.cpp b1b770d built from the vendored tree into `/dev/shm`).

- **Tokenization round-trip** (`vocab_test roundtrip ORIG PERM LIST FILE...`): every 64 KB chunk, tokenized by the
  original and by the reordered 4B file, gives the same tokens through the permutation, and the reordered file's tokens
  detokenize to exactly the original's text. With the shipped list: **5,985,647 tokens in 357 chunks (our prompts with
  their ChatML specials, the held-out eta/gamma prompts, our lines, literals and docs): 0 id mismatches, 0 text
  mismatches.** With the first list (the same tool, before the space-prefixed forms were added): **191,137,376 tokens
  in 11,993 chunks, adding TinyStories, PG-19 and WikiText: 0 and 0** (the original tokenizer itself was lossless on
  all of it). The tokenizer never depends on the ids (BPE ranks come from the merge strings), as expected.
- **Identical model output** (`vocab_test decode ... --dump`, then `vocab_test compare`): the original and the reordered
  file each answer every prompt greedily and then once sampled (temperature 0.7 with the app's chain: repeat penalty
  1.15 over the last 64, top-k 40, top-p 0.9, the same seed), the reordered file's logits mapped back to the original
  order. The dump holds a hash of **all 248,320 logits at every step**, the token chosen and the reply.
  - **Qwen3.5-4B Q4_0** (the shipped file and its reordered copy): **45 prompts (all of eta), 90 replies, 1,791 decode
    steps: every prompt's tokens, every step's logits bit for bit, every choice, every reply identical. 0 differences.**
  - Qwen3.5-0.8B Q4_0 (the same tokenizer and architecture): 50 prompts (eta + 5 of gamma), 100 replies, 1,575 steps:
    0 differences.
  (CPU backend without weight repacking, as everything here; the identity holds by construction: each logit is the
  same row times the same hidden state, the rows only moved.)
- **Coverage, the test the runtime job needs** (`vocab_test decode`; at every step, on the penalized logits the sampler
  sees). Qwen3.5-4B Q4_0, the 45 eta prompts (held out of the list), greedy + one sample at 0.7, 1,791 steps:

  | K | steps with a top-40 token outside K | best rank of a token outside K | max probability outside K | top-p 0.9 nucleus holds an outside token | the app's chain on trimmed vs full logits |
  |---|---|---|---|---|---|
  | 32,768 | 766 (42.8%) | 2 (" stroll") | 26.7% | 21 steps | candidates differ at 30 steps; total variation max 0.24, mean 2.7e-4 |
  | **49,152** | 389 (21.7%) | 2 ("childs", 1.3%) | 1.3% | **0** | **identical at every step (0 of 1,791; TV 0)** |
  | 65,536 | 285 (15.9%) | 2 ("на") | 1.2% | 0 | identical at every step |

  The 0.8B (less sure of itself) at K = 49,152: an outside token in the nucleus at 29 of 1,575 steps, candidates
  differing at 43 (TV max 0.05, mean 2.6e-4); at 65,536: 3 and 6 steps. The 4B is the model this is for.

  What this means for the runtime patch:
  - The literal criterion "the full head's top-40 always lie within K" **cannot hold for any K up to 64k**: when the
    model is sure (top-1 at ~99%), places 20-40 are noise (Chinese, Cyrillic, byte fragments, "&#"), which no English
    list contains. The worst rank seen outside K is 2, always with little probability (at most 1.3% at K = 49,152).
  - The exact test is the last column (`chain` in vocab_test): the app's whole chain (top-k 40, top-p 0.9 over those
    40's softmax, the temperature; greedy runs judged at 0.3) once on the full logits and once with every logit outside
    K at -INF, the top-40 refilled from the next tokens inside K, exactly what the patched engine will do. **At K =
    49,152 the final sampling distribution was the same at all 1,791 steps of the 4B**: for these prompts the trimmed
    head gives the same replies greedy and sampled. Where it can differ (seen on the 0.8B), it is by one candidate at
    the nucleus edge, because dropping a <1% token from the 40 renormalizes the rest: a near-identity, to be judged by
    the `validate()` pass rate, not bit identity.
  - Recommended: **K = 49,152** as the default (2560 x 49,152 at Q8_0 = 134 MB on the NPU for the 4B; 4096 x 49,152
    at Q8_0 = 214 MB for the 9B), 65,536 as the quality setting; the same file serves both, only `timerpg.head_vocab`
    differs (`vocab_reorder --k`). Re-run the coverage after any new list or model (`vocab_test decode MODEL LIST
    PROMPTS --samples 1`, then `sum_decode.py`), and on the gamma export too before shipping.

### The metadata keys (for the runtime patch)

| key | type | value | use |
|---|---|---|---|
| `timerpg.head_vocab` | u32 | K (49152 by default) | the head's rows that matter: `output` = the first K rows of `token_embd` (tied, 4B) or of `output` (9B); logits K..n_vocab-1 set to -INF |
| `timerpg.vocab_order` | string | `head_vocab v1 <hash of the ranked list>` | which order the file is in (the app's saved model states and n-gram pools are keyed by model file anyway) |
| `timerpg.vocab_src` | string | the source tokenizer's `vocab_hash` (`e43bb84cc590a5a5` for Qwen3.5) | sanity check |

ENGINE_TAILORING.md called it `timerpg.n_vocab_out`; the name here is `timerpg.head_vocab` (the runtime job should use
this one). A file without the key is an ordinary model: the patch must change nothing then. The token ids in the file
are the new ones everywhere (the tokenizer, the special ids), so nothing in the app that goes through the tokenizer
changes; only code holding raw Qwen ids from elsewhere (none found in `llm_core.cpp`: it looks special tokens up by
text) would need the list.

## 2. Calibrated quantization

### The importance matrix: `imatrix-qwen3.5-4b.gguf` (7.3 MB, ships in the APK)

- **Calibration text** (`quantize-ours.sh calib`, `calib_text.py`): every 40th person of the other seeds' exports (152
  people, 760 voiced prompts: family, work, rumours, secrets, chat, across 1950-2025), each prompt followed by that
  topic's template line as the reply, kept when `validate()` passes it (752 of 760), closed with `<|im_end|>`, shuffled:
  exactly the token sequence the model reads and writes in play (583 KB, ~150k tokens). The replies are the game's own
  validated lines, not model output: generating validated model replies for calibration would have cost hours of the
  4B on this shared server, and the template lines are the register we want (short, plain, period-neutral speech).
  The app's own towns (eta, gamma) are held out.
- **Computed** (`llama-imatrix`, the BF16 4B from `unsloth/Qwen3.5-4B-GGUF`, 512-token windows, ChatML specials
  parsed, `--process-output`): **90 windows = 46,080 tokens** (100 were asked; the run was killed by the server's
  memory limit after its 90th save, which is a complete, valid file). 18.6 s a window on 4 shared cores, ~31 min.
  It covers all 248 matmul weights of the 32 layers (attention, delta-net, FFN); `token_embd` has no entry (it is
  stored at Q8_0, where the matrix does not matter). For comparison, the shipped Hugging Face file was made with
  unsloth's own matrix (80 chunks of their general calibration set: `quantize.imatrix.*` in its metadata).

### The quantization the phone runs: `quantize_ours.cpp` / `.h`

```cpp
timerpg::QuantizeOptions q;  q.ftype = "Q4_0";   // or "Q4_K_M"; threads 0 = all; max_buf_mib 512
timerpg::quantize_ours(models + "/qwen3.5-4b-bf16.gguf", assets + "/imatrix-qwen3.5-4b.gguf",
                       models + "/qwen3.5-4b-q4_0.gguf", q, err);   // DST.part, renamed when complete
```

`llama_model_quantize()` (llama.cpp's own code, the same as `llama-quantize`) with exactly:

| parameter | value |
|---|---|
| source | `unsloth/Qwen3.5-4B-GGUF`, `Qwen3.5-4B-BF16.gguf`, 8,424,393,632 bytes (Apache-2.0, Qwen) |
| `ftype` | `LLAMA_FTYPE_MOSTLY_Q4_0` (NPU) or `LLAMA_FTYPE_MOSTLY_Q4_K_M` |
| `imatrix` | ours, loaded with the gguf API as `quantize.cpp` does (`in_sum2 / counts`, 1 where a column was never seen) |
| `token_embedding_type` | Q8_0 (the tied head of the 4B: once trimmed, its first K rows go to the NPU at Q8_0, 134 MB for K = 49,152) |
| `output_tensor_type` | Q8_0 (the 9B's own head; unused by the tied 4B) |
| `tt_overrides` | `ssm_alpha`, `ssm_beta` -> Q8_0, `ssm_out` -> Q5_K (the delta-net layers' small tensors, as the shipped file has them; `--ssm-out-q8` makes `ssm_out` Q8_0 if the NPU wants no K-quants; `--plain` drops the overrides) |
| `max_buf_size` | 512 MiB (rows converted in slabs; llama.cpp's default 8 GiB would let the token embedding's f32 copy, 2.5 GB, sit in RAM) |
| kv | `quantize.imatrix.file/dataset/entries_count/chunks_count` as llama-quantize writes them, plus `timerpg.quant` |

No progress callback or cancel exists in `llama_model_quantize()`: the app runs it in the writer's process on the charger
and treats a kill as "do it again" (the `.part` is overwritten).

### Measured (server)

**Quantizing** (`quantize_ours`, BF16 in tmpfs, 4 threads, the server shared with other jobs):

| output | time | CPU | peak RSS | size |
|---|---|---|---|---|
| Q4_0 | 46-55 s | 150-193 s | 1,647 MB (with the source pages it touched) | 2,737,179,872 bytes (2,600 MiB; the shipped Q4_0: 2,583,221,408, its head Q6_K) |
| Q4_K_M | 138 s | 523 s | 1,647 MB | 2,896,186,592 bytes (2,762 MiB; the shipped: 2,740,937,888) |

Read volume: the whole 8.4 GB source once. On the S25 (estimate, not measured): 8 Oryon cores against 4 shared Xeon
cores, the BF16 read from UFS 4.0 at ~3 GB/s: **~1-2 min for Q4_0, ~2-4 min for Q4_K_M**, peak memory about the same
(~0.5-1 GB of our own plus the source's file pages, which Android may drop). Flash: the 8.4 GB BF16 download plus the
output (delete the BF16 afterwards, or keep it for a future matrix). Schedule it like the overnight writer: plugged in,
game closed, after the bank is written; heat is a few minutes of all-core load.

**Quality** (eval: the app's engine, `eval-pass-rate.sh`: the 45 eta prompts of `assets/dialogue.json`, the app's
temperature 0.3, seed 42, `validate()` by `check-replies.ts`; and perplexity, `eval-ppl.sh`: gamma's 812 prompts with
their validated template replies, the same 8 windows of 512 tokens, ChatML specials parsed; both sets held out of the
calibration):

| file | validate() pass | perplexity (held-out dialogue) |
|---|---|---|
| Q4_0, shipped (unsloth's matrix, head Q6_K) | 42 / 45 (93.3%) | 2.6000 |
| Q4_0, ours without the overrides (llama.cpp's plain recipe) | 41 / 45 (91.1%) | 2.6204 |
| **Q4_0, ours** (our matrix, head Q8_0, delta-net tensors as shipped) | **43 / 45 (95.6%)** | 2.6086 |
| Q4_K_M, shipped | 44 / 45 (97.8%) | 2.5920 |
| **Q4_K_M, ours** | **43 / 45 (95.6%)** | **2.5860** |

**Honest reading: no measurable gain.** One reply in 45 is noise (one seed; more seeds and the gamma prompts were cut
by the server's load: other jobs' memory killed runs several times), and the perplexity differences (0.2-0.5%) are
small against the window-to-window spread (+/-0.14) even though the windows are paired. Our Q4_K_M is a hair better
on perplexity, our Q4_0 a hair worse; neither is a clear win. Reasons it may not pay: unsloth's matrix is already
good; our calibration text is narrow (the same rules paragraph in every prompt, template replies), so it may weight a
few columns too much; and perplexity here is mostly prompt tokens. What the files do give for sure: **the head at Q8_0**
(the trimmed head's rows, ready for idea 1; the full-file size +150 MB) and a quantization we control on the phone.
Before shipping ours instead of the downloaded Q4_0: measure more (4+ seeds x eta + gamma's 818 prompts, the reply
tokens' perplexity alone, KL divergence against BF16 on a computer with the room for its logits) and keep whichever
wins; try a calibration with real validated 9B replies (the overnight bank) instead of templates.

**Why the overrides.** The first file (llama.cpp's own Q4_0 recipe with our matrix: 244 tensors Q4_0, the delta-net's
`ssm_alpha`/`ssm_beta`/`ssm_out` among them) scored *worse* than the shipped file on the held-out text (perplexity
2.620 vs 2.600, the same 8 windows) although it has the bigger head: unsloth keeps those 72 small recurrent tensors at
Q8_0/Q5_K, and they matter more than the calibration. With the same overrides, the comparison isolates the matrix (and
the Q8_0 head).

## The 9B (tools tested on the 4B; commands only)

Its full-precision weights (`unsloth/Qwen3.5-9B-GGUF`, `Qwen3.5-9B-BF16.gguf`, **17.9 GB**) do not fit this server's
memory (16 GB shared with other jobs; `/` has a few GB free), so nothing was run on it beyond reading its header:
**the same tokenizer** (`vocab_hash e43bb84cc590a5a5` for `Qwen3.5-9B-Q4_K_M.gguf` and the MTP file
`unsloth/Qwen3.5-9B-MTP-GGUF/Qwen3.5-9B-Q4_K_M.gguf`, read from their first 25 MB), so **the same `head_vocab.txt`
applies**; the tensors that move are `token_embd` [4096 x 248320] Q4_K (572 MB) and its separate head `output`
[4096 x 248320] Q6_K (834 MB); the MTP file has no vocabulary-sized tensors of its own (442 tensors; its block shares
the main head).

On a computer with ~24 GB free (or the phone):
```sh
# the order (seconds; the phone does this with vocab_reorder.cpp)
vocab_reorder Qwen3.5-9B-Q4_K_M.gguf qwen3.5-9b-en.gguf --list kotlin-street/tools/llm/head_vocab.txt
# the importance matrix (server or PC, once): ~2.3x the 4B's time (~70 min on 4 cores, estimate)
quantize-ours.sh calib WORK                                   # the same calibration text as the 4B
quantize-ours.sh imatrix Qwen3.5-9B-BF16.gguf WORK/calib.txt imatrix-qwen3.5-9b.gguf
# the quantization (the phone's quantize_ours, or this on a computer)
quantize-ours.sh quantize Qwen3.5-9B-BF16.gguf imatrix-qwen3.5-9b.gguf qwen3.5-9b-q4_k_m.gguf Q4_K_M
quantize-ours.sh quantize Qwen3.5-9B-BF16.gguf imatrix-qwen3.5-9b.gguf qwen3.5-9b-q4_0.gguf Q4_0
```
The 9B's matrix must be computed from the 9B (the activations differ per model); it is ~10 MB, to ship beside the 4B's.
Before shipping it, check its `ssm_*` types against `Qwen3.5-9B-Q4_K_M.gguf`'s (`vocab_reorder --info` lists only the
vocabulary tensors; the gguf-py snippet in this doc's history, or `llama-gguf`, lists all) and adjust the overrides.

## Hosting and licence

Nothing is hosted: the phone downloads the Hugging Face files `ModelDownloads.kt` already lists (plus, for the
calibrated quantization, `unsloth/Qwen3.5-4B-GGUF/Qwen3.5-4B-BF16.gguf`, 8,424,393,632 bytes; for the 9B
`Qwen3.5-9B-BF16.gguf`, 17.9 GB) and rewrites them locally. The models are Apache-2.0 (Qwen); a modified copy made on
the owner's phone for the owner is fine under it, and nothing modified is redistributed. The APK ships only our data:
`head_vocab.txt` (390 KB, token ids) and `imatrix-qwen3.5-4b.gguf` (7.3 MB, activation statistics), both derived by us.
The English corpora (PG-19, WikiText-103 CC BY-SA, TinyStories) were only counted; no text of them ships.

## What remains

For the app job (the next one; nothing here touched `app/src/main/cpp`, `llm/` or `dialogue/`):
1. Compile `vocab_reorder.cpp` (and `quantize_ours.cpp`) into the native library; JNI entry points with a progress
   callback; ship `head_vocab.txt` and `imatrix-qwen3.5-4b.gguf` as assets.
2. `ModelDownloads`/`ModelFiles`: the BF16 as an optional download; `qwen3.5-4b-q4_0-en.gguf` (or the calibrated one) as
   a derived file with its expected size computed from the source (same size + the three keys); a job on the charger
   (the writer's process) that runs quantize, then reorder, then deletes the intermediate.
3. The runtime patch (ENGINE_TAILORING idea 1, step 3) reading `timerpg.head_vocab`; then `llm_core.cpp`'s CPU
   override for the head off for files with the key.
4. Re-run the identity check through the app's own engine (`llm_host_test --dialogue` on the original and the
   reordered file: replies must be identical; the ids differ, the text not).

Needs the S25 (estimates above, nothing measured there): reorder and quantize time and peak memory on the phone, heat
during them, and whether the NPU runs the Q8_0 head rows and the Q5_K `ssm_out` (else `--ssm-out-q8`).

Not done here, for lack of memory on this server: the 9B (commands above), more pass-rate seeds and the gamma set, KL
divergence against BF16. The vendored llama.cpp was not edited (all builds were from a copy in `/dev/shm`).
