# Engine tailoring: changes only Timerpg can make to llama.cpp and the model files (2026-11-25)

Scope: the in-play light model (Qwen3.5-4B, Q4_0 or Q4_K_M, on the Hexagon NPU via the vendored ggml-hexagon at
`/home/user/vendor/llama.cpp`, commit b1b770d, 2026-11-13) and the overnight writer (Qwen3.5-9B). This is a read-only
audit: nothing in the repo or the vendored tree was edited.

What makes us different from a general-purpose engine, and what every idea below relies on:
- **Output is only short English NPC speech** (56 tokens max, two sentences, `validate()` decides). A general engine has
  to serve every language, code and emoji.
- **Prompts are small and come from our own generator** (`assets/dialogue.json`: ~130-180 tokens per prompt, the same
  rules prefix every time, the facts known to us exactly, including every name a reply may use).
- **One model, one phone, one conversation at a time in play; a batch job overnight.**
- **Sampling is fixed**: penalties (1.15, last-n), then top-k 40, top-p, temperature 0.3 (0.7 variants)
  (`llm_core.h` `Sampling`, `DialogueEngine.TEMPERATURE`).

Measured or read from the code (not estimates): vocabulary 248,320 tokens, n_embd 2560 for the 4B (MEMORY_AUDIT.md,
M3); the tied head is 2560 x 248320 Q6_K = **521.5 MB (497 MiB)**; the 4B's other weights are ~1966 MB; the head now
runs **on the CPU**, mapped (`llm_core.cpp:901-905`, `^token_embd\.weight$` -> CPU buffer), once per generated token
and once per verified/drafted position; Qwen3.5's MTP graph uses the same `model.output` head
(`src/models/qwen35.cpp:657-661`). Everything marked *estimate* was not measured on the S25.

---

## Ranking

| # | idea | main gain (estimate unless marked) | quality risk | effort | verdict |
|---|---|---|---|---|---|
| 1 | **Trimmed output vocabulary (English-only head), head back on the NPU** | decode ~1.2-1.35x on the 4B; ~420-460 MB less read per token; no CPU work per token; no head page faults after idle; 9B: ~650-750 MB less NPU copy | none for drafts; for the main head provably none whenever the top-40 lie in the set (measured, see test) | M | **TOP 1** |
| 2 | **A sampler that enforces `validate()` while generating** (names, places, actions, AI-speak, repeats) | most retries and template fallbacks gone; lower tail latency | changes lines (on purpose): prove with pass rate, first-try rate, blind side-by-side | M | **TOP 2** |
| 3 | **An imatrix from our own prompts and replies** for the NPU quantization (Q4_0 and Q4_K_M), head at Q8_0 once trimmed | higher validate pass rate at the same size and speed | positive by design; measured by pass rate | S | **TOP 3** |
| 4 | **Fixed-shape decode steps + NPU runtime knobs** (padded verify batch so the graph is always reused; 1 HTP session for the 4B; `GGML_HEXAGON_OPPOLL`; no OpenCL in play) | ~3-10% per token, less jitter | none (tokens identical where shapes match; validate pass rate check) | S | **TOP 4** |
| 5 | **Pre-tiled HTP weights on flash** (skip the first-compute repack) | ~0.5-2 s off each NPU load and off the first reply after it | none (byte-identical tiles) | M-L | **TOP 5** |
| 6 | Multi-sequence writer (4-8 prompts per pass overnight) | ~2-3x lines/hour, less energy per line | none (greedy identity per sequence to check) | M-L | next |
| 7 | MTP for the light 4B too, made cheap by idea 1 | ~1.2-1.4x on top of n-grams | none (verification is exact) | M | after 1 |
| 8 | LoRA fine-tune of the 4B on validated 9B lines, with the rules baked in | quality up; ~60 fewer prompt tokens per reply | to prove by eval + blind test | L | later |
| 9 | Fused / shape-specialized HTP kernels for Qwen3.5-4B's dims | unknown until profiled | none | L | measure first |

Dropped (one line each) at the end.

---

## 1. Trimmed output vocabulary, head back on the NPU (TOP 1)

**What.** Keep the full tokenizer and the full input embedding (so every input still tokenizes and round-trips
exactly), but make the **output** head compute logits only for the tokens an English NPC line can contain: K tokens
instead of 248,320. Cleanest form, entirely offline plus a small engine patch:
1. *Choose the set S* (tooling, host): all tokens seen when tokenizing a large English corpus (books, dialogue,
   Wikipedia; a few hundred MB), plus every token of every fact sheet and name list the sim can generate
   (`src/sim` name lists, `tools/export-dialogue.ts` over all seeds/years), plus all special tokens, plus the 256
   byte tokens (so any text is still expressible, if slowly). Rank by frequency; expect K ~32-64k (estimate; VocabTrim
   cut 75% of Llama 3's 128k with negligible acceptance loss).
2. *Permute the vocabulary in the GGUF* (offline tool, `gguf-py`): ids of S become 0..K-1. Reorder
   `tokenizer.ggml.tokens` / `token_type` and the rows of `token_embd.weight` with the same permutation; remap the
   eos/bos/pad ids; merges are stored as strings and stay as they are. Add one key, `timerpg.n_vocab_out = K`. Because
   the head is tied, the trimmed head is then **the first K rows of `token_embd` - a prefix of the same file data**, no
   new tensor (rows are contiguous in GGUF).
3. *Engine patch* (a patch applied by `tools/get-llama.sh`, as CONVERSATION.md already plans for the power vote):
   - `src/models/qwen35.cpp:44-52`: when `n_vocab_out` is set, create `output` as `{n_embd, n_vocab_out}`
     `TENSOR_DUPLICATED` from `token_embd` (relax the shape check in the loader for this key only); the MTP graph
     (`:657`) uses it as is.
   - `src/llama-context.cpp:1991` and `:2419-2424`: copy `n_outputs` rows of K floats into rows of stride n_vocab, and
     fill the tail (K..n_vocab) with -INF once in `output_reserve` (`:2624-2642`). The public API is unchanged: the
     sampler sees full-length logits where the excluded tokens simply cannot win.
   - Our sampler chain (`llm_core.cpp:1165-1178` and the `common_sampler` in `generate_mtp`) then scans 248k entries
     of which 200k are -INF; optional: our own sampler that reads only [0, K) (pairs with idea 2, which needs our own
     sampler anyway).
4. *Drop the CPU override for this file* (`head_mapped_`, `llm_core.cpp:901`): the K-row head goes to the NPU, the
   whole step stays on the HTP (the owner's "never on the CPU" rule holds fully again; today the head runs on the CPU).

**Why only we can.** A general engine must keep every language; our replies are English by design and `validate()`
would reject anything else.

**Gain (estimates, from real sizes).**
- Head bytes per token: 521.5 MB -> 2560 x K x 6.56/8 = **67 MB (K=32k) to 134 MB (K=64k)**, Q6_K. Even at Q8_0 for
  quality (idea 3), K=48k is ~131 MB.
- Decode step today = ~1966 MB on the NPU + 521 MB on the CPU (sequential: the CPU waits for the last layer, then the
  NPU waits for the next token) + a device split round trip + sampling over 248k. At ~60 GB/s effective on the NPU and
  ~40-60 GB/s on the CPU that is ~33 ms + ~9-13 ms + ~1 ms: the head path is roughly a quarter of each step. With a
  K=48k head on the NPU (~1.7 ms) and no split: **~1.2-1.35x tok/s** (estimate).
- **MTP and draft verification:** each MTP draft token runs the same head; today ~10 ms of CPU per drafted token,
  then ~1-2 ms. That is what makes idea 7 pay.
- **Time to first token after idle:** today the head is 497 MiB of file pages touched every token; when Android
  reclaims them between conversations (the whole point of mapping them), the first token re-reads them from UFS
  (several hundred ms of page faults, estimate). Gone with an NPU copy of 67-134 MB.
- **Memory:** NPU copy +67-134 MB versus today's mapped 497 MiB (which is reclaimable, but hot while talking); versus
  the original duplicated head (before M3) -360-430 MB. For the **9B** (its own untied `output.weight`, on the NPU;
  if n_embd is 4096 as expected, check the GGUF metadata) the head is ~834 MB -> 110-215 MB: **~620-720 MB off the 6.6 GB
  need**, and ~15% fewer bytes per token for the overnight writer.
- **Heat:** no 4-thread CPU burst per token while the NPU idles waiting for it.
- Sampling: 248k -> K candidates per position (~0.5-1 ms -> ~0.1-0.2 ms per position, estimate; more with MTP/n-gram
  verification, which samples each position).

A quick read of how few tokens our English uses (measured here, but with llama.cpp's `ggml-vocab-qwen35.gguf` test
vocab of 151,936 tokens, not the real 248,320): `assets/town/gamma/dialogue.json` + `assets/dialogue.json` (312k tokens)
use **728 distinct tokens**; adding all of the repo's English docs (1.27 MB) brings it to **17,569**. Of the 152k test
vocab, 94k tokens are pure ASCII and 52k are non-Latin. Script: scratchpad `llm/work/tokcount.cpp`, `llm/work/vocab.py`.

**Quality risk and the proof.**
- *Drafts only (zero risk, the first step):* trim only the MTP/draft head (VocabTrim / FR-Spec): the verifier keeps the
  full head, so replies are token-identical by construction; only acceptance can fall. Build this first if the full
  head must stay for a while.
- *Main head:* the chain is penalties -> **top-k 40** -> top-p -> temp -> dist. If the 40 best (penalized) logits of the
  full head all lie in S, the trimmed head gives **exactly the same distribution** (greedy: the same token). Test on
  the host (`llm_host_test`, a new `--vocab-test S` mode): run the full model over the existing eval prompts (the 75
  dialogue prompts of `--spec-test`, plus the whole gamma export and a night's bank), and at every step log whether any
  of the top-40 is outside S and the probability mass outside S. Target: zero steps with an outside token in the
  top-40; if a few appear, grow S with them. Then: tokenization of all prompts identical through the permutation
  (map ids, compare), greedy replies token-identical full vs trimmed (15/15, 75/75), sampled replies identical with the
  same seed, `validate()` pass rate unchanged.

**Effort.** M: the set and the permuting converter (S), the llama.cpp patch (~60 lines, S-M), host tests (S), a
download entry for the new file (`ModelDownloads`, `ModelFiles`), the CPU override off for files with
`timerpg.n_vocab_out` (`llm_core.cpp`, `NativeLlm.headMapped`).

**Files.** new `tools/trim-vocab.py` (gguf-py), `tools/get-llama.sh` (+ patch file), vendored `src/models/qwen35.cpp`,
`src/llama-model.cpp` (tensor shape check), `src/llama-context.cpp` (logits copy), `app/src/main/cpp/llm_core.cpp`
(override, optional K-only sampler), `host_test.cpp` (`--vocab-test`), `dialogue/ModelFiles.kt`,
`dialogue/ModelDownloads.kt`, `dialogue/ModelRoles.kt` (need: copy size).

---

## 2. A sampler that enforces `validate()` while it generates (TOP 2)

**What.** Today a reply that breaks a rule is cut at the bad sentence or generated again (`MAX_TRIES` 2), else the
template plays. Most rules in `llm/Dialogue.kt:check` are token-local and can be made impossible to break instead:
- **Never sampled** (static ban list from the vocab): `*` (actions), `<think>`, `<|im_start|>`, quotes at the start;
  whole-word tokens " assistant", " nods", " smiles", " smile", " flickers" (the STAGE and AI_WORDS lists).
- **Names** (the most common reported failure: "made up a name"): at a word start that is not a sentence start, a
  token that begins a capitalized word is allowed only if it walks a trie of the words the sheet names plus `COMMON`;
  its continuation tokens keep walking the trie. Sentence starts stay free (the rule allows them).
- **Places and events** (`PLACES`): the place noun's tokens are banned when the sheet does not contain that noun
  (stronger than the regex's determiner condition, which is fine: the noun alone is what the check rejects; the
  player's words stay allowed when the sentence doubts them would need the hedge test, so keep that case to the check).
- **Repeats** of the last three replies: ban the token that would complete a 4-word sequence of one of `recent`
  (word-level n-gram on the decoded text, mapped to the next word's first token).
- Phrases that need lookahead ("let me know", "talk about something else ... instead", "all my life" when they moved
  here): ban the token that would complete the phrase once its prefix is in place.
- Keep `validate()` as is: it remains the judge; the sampler only removes known failures. Soft option: a small bias up
  for the `must` names (changes the distribution; measure separately).

Implementation without touching llama.cpp: a custom `llama_sampler` (public `llama_sampler_init(iface, ctx)`) first in
our own chain, given per request the sheet's words, `recent` and the bans; in `generate_mtp`, replace
`common_sampler_sample_and_accept_n` with our own loop over the verify positions (the mask for position i+1 assumes
the draft tokens before it, exactly as acceptance does). Masks are computed over K tokens after idea 1 (cheap), or over
a precomputed per-token table (first char class, word start or not) at load.

**Why only we can.** The rules are ours, and the fact sheet tells us exactly which names and places are allowed for
this reply. A general engine can only offer grammars (GBNF), which cannot express "a capitalized word must be one of
these, except at a sentence start" efficiently.

**Gain (estimate).** Retries are whole extra generations and the main source of slow or templated lines. The 9B passes
97%, the 0.6B 89% (CLAUDE.md); the 4B is likely ~93-95% (not measured). If names, places, actions and repeats are most
of its failures (the conversation log of 2026-11-23 says they were), the first-try pass rate should rise to ~98-99%,
removing ~4-6% of generated tokens and almost all double-latency replies and template fallbacks.

**Risk and proof.** This changes lines on purpose. Prove on the host: over the eval set (the 75 prompts plus the gamma
export, 4 seeds each) compare first-try pass rate, final pass rate, retries, template fallbacks, length, the `must`
recall, and repetition across samples; then the owner's blind side-by-side on ~100 prompts (masked vs unmasked, both
passing `validate()`). A mask must never leave nothing sampleable: fall back to unmasked for that step and log it.

**Effort.** M. **Files.** `app/src/main/cpp/llm_core.cpp` / `.h` (sampler, own verify loop, a `Constraints` struct),
`llm_jni.cpp` (pass words/recent/bans), `llm/NativeLlm.kt`, `dialogue/DialogueEngine.kt` (build the constraints from
the topic, the same inputs `validate()` gets), `host_test.cpp` (`--mask-test`), tests vs `ValidateVectors`.

---

## 3. An importance matrix from our own dialogue for the NPU quantization (TOP 3)

**What.** Re-quantize the light model (and the 9B) with `llama-imatrix` computed on **our** text: the exported prompts of
every seed/year (`tools/export-dialogue.ts`), followed by validated replies (the 9B's bank lines). Build Q4_0 (the HTP's
best-supported) and Q4_K_M with it; once idea 1 trims the head, store the head at Q8_0 (it is then small). Keep the
recurrent layers' small tensors (ssm, conv, norms) at their current higher types.

**Why only we can.** Generic quantizations are calibrated on generic text (wiki, code, chat in many languages); the
weights that matter for short English small-town speech are a narrow slice. Our calibration set is exactly what the
model will see.

**Gain (estimate).** Same file size and same tok/s; quality closer to Q8 on our distribution. Typical imatrix gains are
largest at 4 bits (perplexity gap to F16 roughly halved for Q4_0-class quants, from upstream experience); here measured
as `validate()` pass rate and fact recall.

**Risk and proof.** Overfitting the calibration is not a real risk for imatrix (it only weights the rounding). Proof:
pass rate, first-try rate and `must` recall over a held-out set (other seeds and years than the calibration), against
today's file, same sampler seeds; plus KL divergence vs the BF16 model on held-out prompts (`llama-perplexity
--kl-divergence`).

**Effort.** S (host: BF16 weights from Hugging Face ~8 GB for the 4B; imatrix on the 4-core server takes hours for the
4B; a PC GPU for the 9B). **Files.** new `tools/quantize-ours.sh`, the calibration export (`tools/export-dialogue.ts`
over seeds), `dialogue/ModelDownloads.kt` (sizes), `NpuTune.kt` (the Q4_0 vs Q4_K_M comparison already exists).

---

## 4. Fixed-shape decode steps and NPU runtime knobs (TOP 4)

**What.**
- *Constant verify batch.* llama.cpp reuses the last graph only when its shape is the same (`llama-context.cpp:1831`),
  and ggml-hexagon caches its op list by the graph's uid (`ggml-hexagon.cpp`, `cached_uid`). Our steps alternate
  between 1 token (no n-gram match), 2 or 3 (a guess): every change rebuilds the graph, re-splits it and re-prepares the
  HTP op list. Always submit 1 + N tokens (pad a short draft with any tokens: they are rejected by acceptance as wrong
  guesses; the rollback planes for N already exist). On the NPU a 3-token step costs about a 1-token step (bound by
  reading the weights); after idea 1 the head is small enough for this to be free on any device.
- *One HTP session for the 4B.* `init_backend` sets 2 sessions for everyone (`llm_core.cpp:380`) because the 9B needs
  them; a session maps up to 3.2 GB (`HTP_OP_MAX_VMEM_DEFAULT` 3355443200), and the 4B's NPU weights are ~2.0 GB
  (~2.1 GB with the trimmed head): one session means no hand-off between sessions per token. `NpuTune.CONFIGS` has 2, 3
  and 4 sessions but not 1: add it.
- *Polling for completions* (`GGML_HEXAGON_OPPOLL=1`, default 0: `dspqueue_read` with timeout 0 instead of waiting on
  an interrupt, `ggml-hexagon.cpp:1730`). A third-party Hexagon build reported 55 -> 92 tok/s on Qwen3-0.6B with
  polling; on the 4B the fixed overhead is a smaller share. It spins one ARM core while the NPU works, so only for
  live replies (it is read at backend registration: per process, so a NpuTune config first).
- *No OpenCL backend in the game process* unless the panel chose the GPU (`llm_core.cpp:381` loads
  `libggml-opencl.so` always): skips the Adreno OpenCL driver's init and its context memory beside Filament (size to
  measure; estimate tens of MB and ~100 ms).

**Why only we can.** We know every step's shape and that only one model ever runs; a general server cannot fix shapes.

**Gain (estimate).** Graph build + sched + HTP op prep for a ~32-layer hybrid is ~1-3 ms per rebuild on the CPU against
a ~30-40 ms step: ~3-8%, plus steadier token timing; one session and polling ~2-8% more (measure in NpuTune). Check
`n_reused` (llama.cpp perf data) before and after.

**Risk and proof.** None for content; llama.cpp's numbers depend on batch shape, so greedy replies may differ from
today's variable-shape run in rare places, as the snapshot test already saw. Prove: greedy token identity between
padded runs (restored vs fresh), and the `validate()` pass rate on the eval set unchanged.

**Effort.** S. **Files.** `app/src/main/cpp/llm_core.cpp` (`generate_mtp` padding; the always-on n-gram path also for
the plain loop; `init_backend` OpenCL only when asked), `app/src/main/kotlin/NpuTune.kt` (configs "1 session", "2
sessions + poll", "1 session + poll"), `llm/NativeLlm.kt` (env before load).

---

## 5. Pre-tiled HTP weights on flash (TOP 5; NOVEL_OPTIMIZATIONS idea 11, made concrete)

**What.** Today a load copies every weight from the mapped file into rpcmem (`set_tensor`), and then, **at the first
graph compute**, `ggml_hexagon_graph_repack` copies each matmul weight again into a heap vector and retiles it in place
(`ggml-hexagon.cpp:2420-2440`, `opt_rt_repack` 1 by default). So the first reply after every load pays the retiling of
~2 GB on the ARM side. Write the tiled bytes once (at the first load, or overnight on the charger) into a sidecar file
keyed by model hash + backend version + arch (v79) + tile parameters; on later loads `set_tensor` copies the tiled bytes
straight in and marks the tensor `HTP_TENSOR_TILED` (no repack).

**Why only we can.** One phone, one arch, one model: the tiles never need to be portable.

**Gain (estimate).** The repack is a byte shuffle plus a per-tensor heap copy: ~0.5-2 s on the Oryon cores for ~2 GB, and
a peak of one extra copy of the largest tensor. That comes off the **first reply after each load**; it also makes
dropping and re-loading the model on empty stretches (idea 11's residency) cheaper. Load I/O (UFS ~4 GB/s, 2 GB) stays.

**Risk and proof.** None: byte-identical tiles. Proof: dump tiled buffers from a normal load and from the sidecar load
and compare bytes; greedy replies token-identical.

**Effort.** M-L (a ggml-hexagon patch: a "pre-tiled" path in set_tensor and the sidecar writer/reader; the other job is
editing this backend now, so sequence after it). **Files.** vendored `ggml/src/ggml-hexagon/ggml-hexagon.cpp`,
`htp-repack.cpp` (export of the tiling), `tools/get-llama.sh` patch, `llm_core.cpp` (sidecar path and key).

---

## 6. Several prompts per pass for the overnight writer (next)

**What.** The writer generates one line at a time; decoding is bound by reading the weights, so 4-8 sequences in one
`llama_decode` (`n_seq_max` 4-8, one sampler, snapshot and n-gram state per sequence) cost little more than one.
**Why us.** Overnight is a pure batch job with thousands of short independent prompts. **Gain (estimate).** ~2-3x
lines/hour and less energy (heat) per line for the 9B; memory: one recurrent state (+ rollback planes) per sequence
(9B: likely ~100+ MB each, to read from the load's "RS buffer"). **Risk.** None in content; check per-sequence greedy
identity against single runs (may differ in rounding as with batching), and the pass rate. **Effort.** M-L.
**Files.** `llm_core.cpp/.h` (a `generate_batch`), `llm_jni.cpp`, `night/` writer loop, `ModelRoles` (need with N states).

## 7. MTP for the light 4B (after idea 1)

The light model runs without MTP because its file has no head (`DialogueEngine.kt:1545`). Convert the 4B with its
`mtp`/nextn block (convert_hf_to_gguf keeps it when present) and, with idea 1's trimmed head, a draft token costs about
one layer (~60 MB) instead of one layer plus a 521 MB head. Stacked after n-grams (already wired in `generate_mtp`).
Gain ~1.2-1.4x more where n-grams find nothing (estimate). Risk none (exact verification). Effort M (the file, a
download entry, `mtpWanted` for the light role).

## 8. Fine-tune the 4B on our validated lines, rules baked in (later)

The distillation plan in CONVERSATION.md, applied to the 4B: LoRA on validated 9B lines, merged, then idea 3's
quantization. Also train without the rules paragraph (~60 tokens per prompt) so prompts shrink. Gain: quality (target:
the 9B's 97%) and a little prefill. Risk: must be proven by the held-out eval and the owner's blind test. Effort L
(GPU time).

## 9. Fused or shape-specialized HTP kernels (measure first)

The vendored backend already fuses (`htp-fusion.cpp`, `GGML_HEXAGON_OPFUSION` 1), selects HMX/HVX per shape and has
gated-delta-net kernels. Before writing kernels for 2560/9216 and the delta-net dims, run `GGML_HEXAGON_PROFILE=1` on
the S25 over one reply and see which ops dominate after ideas 1 and 4. Only then: L.

---

## Dropped

- **Pruning the tokenizer / input embedding:** input stays full for exact round-trips; the input embedding is a mapped
  `get_rows` that touches one row per token, so trimming it saves only file size.
- **Tokenizer caching of fact sheets:** prompts are ~130-180 tokens; BPE on them takes well under a millisecond.
- **Static graph instead of rebuilding:** llama.cpp already reuses graphs and the HTP caches op lists; the only loss is
  shape changes, handled by idea 4.
- **Zero-copy CPU/NPU weight sharing:** the HTP needs rpcmem/DMA buffers and its own tiled layout (MEMORY_AUDIT M9/M10);
  the only shared tensor (the head) goes away with idea 1.
- **Smaller context:** 1024 -> 512 saves ~10 MB of KV; prompts are short but conversations grow.
- **Recurrent state in F16:** halves snapshots and rollback planes but changes the delta-net's numerics over a whole
  prompt; quality risk for ~25-50 MB.
- **Trimming layers or heads:** no (quality).
- **Batching several NPCs' replies in play:** one conversation at a time; pre-generation in play is the main model's
  job and paused while the light one is loaded.
- **Stripping ggml ops/backends for load time or memory:** unused code pages are not resident; only the OpenCL
  driver init matters (in idea 4).
- **Sampler simplification on its own:** ~1 ms per token today; comes free with ideas 1 and 2.
- **Greedy decoding to save the sampler:** temperature 0.3 gives the variety the bank needs; no speed gain worth it.

---

## How to build the top 5, in order

1. **Day 1-2 (S): measure and the cheap knobs.** On the S25, in NpuTune: add "1 session", "2 sessions + poll",
   "1 session + poll"; log `n_reused`, graph splits (`last_load_info`), per-token ms, and the head's share
   (`GGML_HEXAGON_PROFILE=1` + time between NPU graph end and next token). This gives the real baseline for every
   estimate here. Pad the verify batch (idea 4) behind a setting.
2. **Week 1 (S, host): idea 3's imatrix**, so later files are built once: export calibration text, run
   `llama-imatrix`, quantize Q4_0/Q4_K_M, evaluate pass rate on held-out prompts.
3. **Week 1-2 (M): idea 1.** (a) `--vocab-test` in `llm_host_test`: top-40 coverage over the eval set with a candidate S
   from an English corpus + our exports; (b) `tools/trim-vocab.py` permutes the GGUF and writes `timerpg.n_vocab_out`;
   (c) the llama.cpp patch (qwen35 head as a K-row prefix of `token_embd`, logits copy with stride, -INF tail) kept as
   a patch in `get-llama.sh`; (d) host proofs: tokenization identical, greedy 75/75 identical, sampled identical with
   seeds; (e) S25: tok/s, first token after a minute idle, NPU copy size, CPU load during replies. Build the drafts-only
   variant first if the main head must wait.
4. **Week 2-3 (M): idea 2**, our own sampler and verify loop in `llm_core.cpp`, constraints from `DialogueEngine`,
   `--mask-test` numbers, then the owner's blind side-by-side.
5. **After the other job's Hexagon work lands (M-L): idea 5**, pre-tiled sidecar.

Sources: [VocabTrim (Qualcomm AI Research, ICML 2025)](https://arxiv.org/abs/2506.22694) (trimmed drafter head,
up to 75% smaller, ~16% memory-bound speedup), [FR-Spec](https://arxiv.org/abs/2502.14856) (frequency-ranked draft
vocabulary), [Qwen3-0.6B Hexagon build with polling](https://huggingface.co/h2loop-ai/qwen3-0.6b-hexagon),
[llama.cpp Snapdragon backend docs](https://raw.githubusercontent.com/ggml-org/llama.cpp/HEAD/docs/backend/snapdragon/README.md),
[llama.cpp issue 20603 (Qwen3.5 on Android/Hexagon)](https://github.com/ggml-org/llama.cpp/issues/20603).
