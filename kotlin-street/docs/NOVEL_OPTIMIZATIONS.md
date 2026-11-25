# Novel optimizations for Timerpg (brainstorm, 2026-11-25)

Scope: ideas **not** already in OPTIMIZATION_RESEARCH.md, MEMORY_AUDIT.md (steps 1-4 as built) or STREAMING.md, and not
part of the heat job now running (60 fps pacing, ADPF governor, MSAA, streaming time-slicing, build heap). Every gain
below is an **estimate**, worked out from the measured figures in those docs (desktop Filament account, which matched the
phone within ~2%, and the S25 numbers from 0.1.598 / 0.1.848). Nothing here was measured on the S25 for this document.

Rule used for ranking: gain ÷ effort, with visual risk as a veto. An idea that changes a pixel the owner would see is
either dropped or kept only as an opt-in quality setting.

While reading the code I found four things that the existing docs do not mention. Ideas 1-4 come from them:
- **Every town draw is alpha-tested.** `street_lit.mat` and `shadow_proxy.mat` are both `blending : masked`, and so is
  `street_albedo.mat`. Walls, roofs and ground (alpha 1 everywhere) therefore go through the discard path, plus a second
  `textureLod` with derivatives for the cut-out's alpha. This happens in the main pass and in all four shadow cascades.
  OPTIMIZATION_RESEARCH 2.7 assumed opaque materials ("keep LRZ on: no discard ... in opaque materials").
- **The game is drawn into a `TextureView`** (`app/src/main/kotlin/FilamentLayer.kt:331`), so HWUI composites every
  frame with the GPU instead of the display hardware.
- **The dialogue engine keeps up to 8 hybrid-state snapshots in RAM** (`llm_core.cpp`, `kMaxSnapshots = 8`, one taken
  at every line end of the system message). With Qwen3.5-4B's ~50 MB recurrent state (measured, STATUS.md) plus its KV
  part, that is up to ~400-450 MB of anonymous native heap. It sits on top of the NPU copy and the gate does not count it.
- **Every batch has its own material instances** (visible + shadow proxy; `PropLayer.kt:237/251`,
  `FilamentModels.kt:1387/1412`, `BuildingMeshes.kt:495/513`). Step 4 tied them to the batch even more: each slice's
  `posMap/posScale/uvMap` are material parameters (`GeomSlice.decode`). This bears directly on the unexplained
  **Gfx dev 463 MB** (MEMORY_AUDIT G9).

---

## Ranked list

| # | idea | main gain (estimate) | effort | visual risk | do next |
|---|---|---|---|---|---|
| 1 | Opaque variants of street_lit / shadow_proxy for batches with no cut-outs | GPU fragment time and heat: 10-30% of the main pass in dense street views, cheaper depth-only shadow passes | S-M | none (alpha is 1 there) | **TOP** |
| 2 | Hybrid-state snapshots on flash, not in RAM (plus pre-baked prefix snapshots) | up to ~350-400 MB of anonymous RAM in play, plus faster first tokens | S (+M for pre-baked) | none (byte-exact state) | **TOP** |
| 3 | SurfaceView instead of TextureView for the town | one full-screen GPU composite per frame removed (~1.2 GB/s of DRAM traffic at 60 Hz), 1 frame less latency | S-M | none | **TOP** |
| 4 | Fold the vertex decode into the renderable transform and share material instances by texture | Gfx dev / UBO memory, if G9's hypothesis holds: 50-200 MB (speculative); CPU per-frame parameter commits | S-M (measure first) | none | **TOP** |
| 5 | 16-byte vertices: tangent quaternion as snorm8 where it is exact | ~20% of vertex memory: ~30-40 MB on the route, ~55 MB whole town | S-M | none where applied (exactness checked per batch) | **TOP** |
| 6 | One shared "quad index buffer" for pure-quad batches | most index memory: ~25-45 MB route, ~45-80 MB whole town | M | none | next |
| 7 | Ship the capital pre-baked: deterministic page blobs built at APK build time, mmapped and uploaded | first view from tens of seconds to a few; no cold-build heat; build heap peak gone; streaming becomes reads | L | none (same bytes) | biggest absolute; design now |
| 8 | Draft-free n-gram speculation (fact sheet + bank as the draft source), stacked with MTP | decode +15-50% tok/s on fact-heavy lines (speculative) | S | none (lossless verification) | next |
| 9 | Burst power votes for the Hexagon NPU (turbo only while generating) | idle NPU power between replies; size unknown | S | none | built 2026-11-25 (CONVERSATION.md); measure on the S25 |
| 10 | Render only on change while the image is static (conversations, menus, map, pause) | most GPU frames skipped during conversations, which is when the NPU is hot | M | none if the dirty test is exact | later |
| 11 | Pre-retiled NPU weights on flash, with model residency driven by encounter forecasts | ~2.2 GB freed on empty stretches; a 9B swap-in for long talks | L | none | later |
| 12 | Encounter forecasting from deterministic schedules for bank pre-generation | higher bank hit rate, fewer live generations (heat) | M | none | later |
| 13 | Page our own cold memory to zram right before the NPU load (`MADV_PAGEOUT`) | 50-200 MB at the moment of the load (speculative) | S | none | maybe |

Dropped (gain small, speculative or visible): frame generation (AFME / Neural Fusion), rendering at an art-pixel grid
resolution, palette/tile textures for buildings, shader-procedural ground, a low-rate far-view impostor ring, cached
shadows by quantized sun, KV-cache quantization beyond today's, texture-space shading, QuickJS bytecode. The reasons are
at the end.

---

## 1. Opaque variants for batches without cut-outs (LRZ, early Z, depth-only shadows) — TOP

**What.** Compile `street_lit_opaque.mat` and `shadow_proxy_opaque.mat` from the same source with `blending : opaque`.
Their fragment shaders contain no `c.a = 0` paths and skip the cut-out's second `textureLod` and its derivatives. A
batch uses the opaque instance when every texel its UVs touch has alpha 255 and it is not fading. The bake knows this
when it lays out the atlas: walls, roofs, ground, road, most building faces. The batch switches to the masked instance
only while `fade < 1`, or while the see-through / cutaway is on for it (`seeK.z`, `seeCut.w`). Optional, under the
same diff test: `culling : back` in the opaque variant for closed boxes whose winding is consistent.

**Why it is new.** The catalogue assumed our opaque geometry was opaque. It is not: the whole town is `masked`. On
Adreno a discarding shader stops the draw from writing LRZ and late-binds its depth write. Mesa's freedreno/turnip
disable LRZ write for discard and alpha-test ([Mesa LRZ docs](https://docs.mesa3d.org/drivers/freedreno/hw/lrz.html),
[Igalia: LRZ on Adreno](https://blogs.igalia.com/dpiliaiev/adreno-lrz/),
[freedreno alpha-test LRZ fix](https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/5217)). Qualcomm's own FAQ is
vaguer ([Adreno FAQ](https://developer.qualcomm.com/sites/default/files/docs/adreno-gpu/snapdragon-game-toolkit/gdg/gpu/faq.html)),
so the size of the effect must be measured. Hidden walls behind walls are then shaded in full: Filament's lit model
with froxel lights, shadows (PCF on 4 cascades), IBL and the window-page fetches.

**Why it fits.** A street-level camera in a dense town has high depth complexity: building behind building, the yard
behind the fence, the ground under everything. The lit shader is the most expensive thing we run per pixel. In the
shadow pass, a masked caster must fetch albedo for alpha in all 4 cascades; an opaque one is pure depth, the fastest
path a tiler has.

**Gain (estimate).** Main pass: if 30-60% of shaded fragments in a downtown view are later overdrawn and LRZ could have
rejected them, 10-30% of the main pass's fragment cost goes. Shadow passes: one texture fetch and one discard fewer per
caster fragment, ×4 cascades. Together that is a real cut in GPU power (heat) at no memory cost. It has to be measured:
Snapdragon Profiler / AGI LRZ counters and GPU busy % on the same night view downtown.

**Visual risk and proof.** None in principle: masked with alpha 1 equals opaque. Proof: the preview's downtown shots,
`plan_above`, a backyard and night shots rendered with `-DopaqueVariant=0/1` must differ by **0 pixels**. Any non-zero
pixel means a batch was misclassified. Add a unit test: for every batch classified opaque, sample all its atlas texels
under its UV rects and assert alpha 255. Back-face culling goes in only if its own diff is also 0.

**Effort.** S-M. Two material files, a classification bit per batch at bake time, and the instance swap at the places
that set `fade` / see-through (`FilamentTown.kt:1613`, SeeThrough).

**Dependencies.** None. Do it before measuring anything else on the GPU, because it changes the baseline.

**Sketch.**
- `kotlin-street/filament/materials/street_lit.mat`: move the body into a shared include if `matc` allows one,
  otherwise generate the opaque file by a Gradle task with a `#define OPAQUE` so the cut-out code compiles away.
  The same for `shadow_proxy.mat`.
- `YardKit.kt` / `BuildingMeshes.kt` / `FilamentModels.kt`: at pack time compute `opaque = all alpha 255 under the
  batch's UV rects` (the atlas pixels are in hand at that moment).
- `PropLayer.kt:237-251`, `FilamentModels.kt:1387-1418`, `BuildingMeshes.kt:495-513`: create instances from the opaque
  material when `opaque`; keep a lazily made masked twin for fades and see-through.
- `FilamentTown.kt` (fade crossovers) and `SeeThrough.kt`: swap the renderable's material instance on entering or leaving
  a fade or cut, which is rare and short.

## 2. Hybrid-state snapshots on flash (and pre-baked prefix states) — TOP

**What.** Today `Engine::take_snapshot` copies `llama_state_seq_get_data` into a `std::vector` at each line end of the
system message, keeping up to 8. For Qwen3.5-4B (24 Gated DeltaNet layers with a ~50 MB recurrent state, plus its 8
attention layers' KV for the prefix) each snapshot is ~50-60 MB, so up to **~400-450 MB of anonymous native heap**.
Change: keep only the most-used snapshot (the shared rules) in RAM and write the others to an app-private file. Restore
reads them back (`pread` or a read-only `mmap`). Then go further:
- pre-bake the "rules" snapshot once per model version (at first load, or overnight by the writer) and store it on
  flash, so even the first prompt of a session skips the rules' prefill;
- let the overnight writer save per-NPC persona snapshots (rules + persona) for the people the player is likely to meet
  the next day. At ~55 MB each, a 2-4 GB flash budget holds 40-70 people. Size is no concern.

**Why it is new.** Prefix caching is done, but in RAM. Persisting a hybrid model's state to flash in a game is rare.
KVSwap ([arXiv 2511.11907](https://arxiv.org/abs/2511.11907)) shows disk offload of KV on phones, but for long
contexts; our case is simpler: whole exact states, read once.

**Why it fits.** Snapshot memory is exactly what the NPU gate fights for, and it is anonymous memory that Android cannot
drop. The S25's UFS 4.0 reads 50 MB in ~15-25 ms, far less than re-prefilling a fact sheet. Determinism means the
persona prefix of an NPC is the same every day, so a baked state stays valid until the model or the prompt format
changes (key it by a hash of model + tokens).

**Gain (estimate).** RAM: up to ~350-400 MB fewer resident in play once all 8 slots fill. The real figure is 8 ×
`llama_state_seq_get_size` on the phone; log it, since it is the one unknown. Latency: the first reply to a pre-baked NPC
skips their persona prefill (some hundreds of tokens at the NPU's still unmeasured prefill rate).

**Risk.** No visual risk. Output: byte-exact state, so identical tokens. Proof: the host test runs the same prompt
greedily from a RAM snapshot and from a file snapshot, and the token ids must match. Also check `stats_.reused_tokens`,
because upstream reports a hybrid-restore bug that silently recomputes
([llama.cpp report](https://buttondown.com/weekly-project-news/archive/weekly-github-report-for-llamacpp-august-04-2026-6176/)).
Our own restore path already works from RAM, so only the storage changes.

**Effort.** S for RAM → file; M for pre-baked rules and persona snapshots in the writer.

**Sketch.** `app/src/main/cpp/llm_core.cpp` (`Snapshot` gets a file path and an offset instead of `state`; keep the
LRU; one RAM-resident "pinned" slot), `llm_core.h`, `llm_jni.cpp` (the cache directory); the writer in
`app/src/main/kotlin/night` (bake persona states); `ModelRoles` / `NpuGate`: count the pinned snapshot in the model's
own memory. Breadcrumb: snapshot size, count and reads.

## 3. SurfaceView instead of TextureView for the town — TOP

**What.** Filament renders into a `TextureView` (`FilamentLayer.kt:327-331`, "so Compose draws on both sides"). Each
frame HWUI then samples that full-screen texture with the GPU and writes the app window. SurfaceFlinger then composes
the window. With a `SurfaceView` (Filament's `UiHelper` supports both), Filament's buffer is its own layer, which the
display processor composes as a hardware overlay without the GPU. The Compose HUD window then redraws only when the HUD
changes. In town mode the stage behind Filament draws nothing the player sees (`ownSky`, `ui.isOpaque = ownSky`), so
nothing needs to be drawn under the 3D view.

**Why it is new or rare here.** It is Android's documented advice for video (TextureView "increasing total power draw
during video playback by as much as 30% on some devices",
[Media3 battery docs](https://developer.android.com/media/media3/exoplayer/battery-consumption);
[graphics architecture](https://source.android.com/docs/core/graphics/architecture)), but not in our catalogue. Games
built on engines get SurfaceView by default.

**Gain (estimate).** At 2340 × 1080 RGBA8 (10.1 MB a frame) the TextureView path adds a GPU read and write of the frame
(~20 MB) plus the window's own composition: ~1.2 GB/s at 60 Hz, ~2.4 GB/s at 120. That is a second GPU submission each
frame that keeps the GPU awake between Filament frames, and one frame of extra latency. Power: plausibly 100-400 mW
(an estimate scaled from the video figure; a game's own GPU load is larger, so the share is smaller). Memory: about the
same (both paths keep two triple-buffered queues). Measure with `BatteryManager` current over the same 5-minute walk.

**Visual risk and proof.** Same pixels. Proof: a `PixelCopy` of the SurfaceView frame plus the HUD against the
TextureView capture of the same deterministic preview frame must show 0 px difference. Watch for the HUD's alpha
blending over an overlay (HWC blends exactly) and the screenshot uploader (`ShotUploader.kt` copies the window, so it
needs `PixelCopy.request(SurfaceView)` plus the HUD).

**Effort.** S-M: `FilamentLayer.kt` (host on a SurfaceView in town mode, keep TextureView for the crossroads stage),
`ShotUploader.kt`, `FrameSpikes.kt` (its TextureView-specific frame accounting), `LookMatrix.kt` (y-flip assumption
for "drawing to a window").

**Dependencies.** Coordinate with the heat job's frame pacing. With a SurfaceView, Filament's swap chain presents
directly, so Choreographer pacing becomes exact.

## 4. Decode in the transform, share material instances by texture — TOP (measure first)

**What.** `posMap/posScale` is an affine scale plus offset. Put it in the renderable's model matrix (TransformManager)
instead of a material parameter. Put the UV read-back on a fixed grid per texture (k chosen per texture, not per batch),
so `uvMap` depends only on the texture. Then all batches that wear the same atlas with the same constant parameters can
share **one** visible and one proxy material instance. Per-frame parameters (`fade`, see-through) go only to the few
instances that need them (those get a private instance while active).

**Why it is new.** G9 named the hypothesis (per-instance UBOs at driver granularity explain Gfx dev 463 MB) but no
plan. Step 4 made per-batch instances unavoidable by putting the quantization in parameters. Folding it into the
transform removes that reason.

**Gain (estimate, speculative until G9 is measured).** If a town of ~15,000 slices has ~30,000 instances, each with a
UBO of a few hundred bytes rounded up to a 4-64 KB driver allocation, that is 120 MB to over 1 GB of address space, much
of it resident. Sharing by texture would cut instances by 5-20×: plausibly 50-200 MB of Gfx dev, plus fewer UBO
uploads a frame (CPU heat). It could also be ~0 if Filament already sub-allocates UBOs from one pool on our version.
**Measure first:** count live material instances in the `accounts` line next to `gfxDev`, and run `dumpsys meminfo -d`
before and after a scene with 2× instances.

**Visual risk and proof.** None: the same math moves from a material matrix to the model matrix. Proof: render diff
against today. Expect 0 px, or a few single-pixel changes from float order of operations; judge any of those as for G5.

**Effort.** S-M. `TownVertex.kt` (`QuantMap` → a transform), `BufferArena.kt` (`GeomSlice.decode`),
`street_lit.mat` / `street_albedo.mat` / `shadow_proxy.mat` (drop `dq`), the instance creation sites listed in idea 1
(a cache keyed by texture and constant parameters), and `TownBudget` (a material-instance count budget).

**Dependencies.** Do it together with idea 1, since both touch the same instance sites.

## 5. 16-byte vertices (snorm8 tangent frame where it is exact) — TOP

**What.** Today a vertex is position 3 × u16 + pad (8), UV 2 × u16 (4), tangent quaternion 4 × snorm16 (8) = 20
bytes. For almost all town faces the frame is one of a small set: axis-aligned boxes, gables at the pack's fixed
pitches. Their quaternions have components in {0, ±1, ±½, ±√½}, which after `normalize()` in the shader come back
**exactly** from snorm8 (equal components keep their ratio). Store the tangent as BYTE4 normalized (4 bytes) in
batches where every quaternion is exactly representable, and keep 20 bytes elsewhere (GLB landmarks, curved walls).
Exactness is checked at quantize time, the same way `TownVertex.quantize` already rejects batches.

**Why it is new.** Step 4 stopped at 20 bytes, assuming the frame cannot shrink. For our faceted geometry it can, and
losslessly.

**Gain (estimate).** -4 bytes of 20 on quantized vertices: ~20% of vertex memory. Whole town: vertex + index buffers
are 343.8 MB, roughly 270-290 MB of it vertices, so ~55 MB. On the route peak (475 MB total), ~30-40 MB. Also less
vertex fetch bandwidth, twice per vertex in Adreno's binning plus render passes.

**Visual risk and proof.** None where it is applied (the frame is bit-equal after normalization). Proof: a unit test
decodes every converted frame and compares it to the snorm16 one (equal to the float epsilon); render diff 0 px.
Check that Filament accepts BYTE4 normalized for TANGENTS on GLES (it should; glTF loaders use it).

**Effort.** S-M: `TownVertex.kt` (a second quantized layout, QSTRIDE16), `BufferArena.kt` (a third page kind),
`VertexQuantTest`.

## 6. Shared quad index buffer for pure-quad batches — next

**What.** Box geometry is quads of 4 unique vertices. Its index pattern (0,1,2, 2,1,3) + 4k is the same everywhere.
`BufferArena` index pages are 384 KB per 1.25-1.75 MB vertex page, today written per batch. For a batch whose vertices
are pure quads starting at a multiple of 4 in its page, point its renderable at a single shared, read-only index buffer
at offset `6 × (vOff/4)` (no base vertex needed, because the pattern is periodic). Triangle faces (gable ends) become
degenerate quads (one extra vertex each) or stay on the per-batch path.

**Why it is new.** It is a voxel-engine trick, rarely seen in open-world engines, and it fits a world of boxes.

**Gain (estimate).** Index memory is 1.5 × 2 = 3 bytes per vertex for quads: ~13% of quad geometry. If most town
batches qualify: ~45-80 MB whole town, ~25-45 MB on the route. **Count first:** the share of batches that are pure
unique-vertex quads (a counter in `BufferArena.put`). If meshes share vertices between quads (indexed reuse), the gain
shrinks or reverses.

**Visual risk.** None (the same triangles in the same order); diff 0 px.

**Effort.** M (`BufferArena.kt`, the builders' emit order).

## 7. Ship the capital pre-baked: deterministic blobs, mmapped, zero-parse — biggest absolute gain

**What.** The capital is deterministic (seeds plus hand-authored data). Run the town build on the **build server at APK
build time** and write its outputs in the exact upload formats: quantized vertex pages, index pages, ASTC chains, window
pages, the shared yard sheet, far cells, collision and walk data as flat little-endian structs. Lay them out per
streaming cell and 4 KB-aligned in an asset pack (stored, not compressed, so it can be mmapped). On the phone, streaming
a cell becomes `mmap` → `BufferDescriptor` straight from the mapping → upload → `munmap`. There is no Java-heap copy and
no bake. Lots wrecked by cars (and later edits) fall back to the live baker. A hash per cell, compared between the
phone's own build and the shipped blob in a debug mode, proves they are the same bytes.

**Why it is new.** OPTIMIZATION_RESEARCH 2.3/5.3 suggested baked cell files, cached on first visit. Two things go
further: bake **off the phone** (the install size is fine, and determinism makes it exact), and make the immutable town
data file-backed instead of heap objects. File-backed pages are clean, so Android can drop them under pressure, which
the NPU gate counts as reclaim.

**Gain (estimate).** First view: the S25 needed 54 s on 0.1.805 (TOWN.md). With nothing to build, the near ring is a
read of a few hundred MB at 2-4 GB/s plus uploads: a few seconds. The cold build's tens of seconds of 8-core load (heat
and battery at every start) and its Java heap peak (455-514 MB on the desktop 640 MB heap, with `townMemoryCheck`
failing at 320) go away. In play, the heap's town data and collision (part of 170-200 MB used) become reclaimable file
pages. Driving: streaming in becomes a read instead of a rebake, which removes most of the hitch the desktop shows
(p99 ~19 ms, worst 48.9 ms).

**Visual risk.** None if the bytes are identical (the hash test). A stale pack is the real risk: the build must rebake
on any change to town data or bake code, and version the format.

**Effort.** L. A serializer at the end of each builder; a blob reader in `TownStream`; collision in flat arrays; CI
rebake; an asset pack.

**Dependencies.** Streaming (done). It goes well with ideas 5 and 6 (fixed formats first).

## 8. Draft-free n-gram speculation from the fact sheet and the bank — next

**What.** NPC lines quote names, places, prices and phrases from their fact sheet, and the bank holds that NPC's own
earlier lines. llama.cpp's speculative module has draft-free modes (`ngram-simple`, `ngram-map-k`, `ngram-mod`) that
propose continuations from n-grams already in the context or a shared pool
([llama.cpp docs/speculative.md](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/speculative.md)).
Seed the pool with the NPC's bank lines, and chain it after MTP where the model has MTP heads (an open upstream PR
combines types: [#23269](https://github.com/ggml-org/llama.cpp/pull/23269)).

**Why it fits.** Decoding on the NPU is memory-bound, so verifying 4 drafted tokens costs about one step. Drafts cost no
memory and no model. Our engine already runs `common_speculative` for MTP (`llm_core.cpp:586-905`).

**Gain (estimate, speculative).** Prompt-lookup drafting gives ~2× on extraction-like tasks. Our dialogue is two
sentences, partly copied from facts, so +15-50% tok/s is a guess. It is worth an hour on the host test: acceptance rate
over the 75 dialogue prompts.

**Risk.** None: speculative verification is exact under greedy decoding and distribution-preserving under sampling.
Check that `validate()` pass rates are unchanged.

**Effort.** S (if the pinned llama.cpp has the ngram types; else a pin bump).

## 9. Burst power votes for the Hexagon NPU — built (2026-11-25), measure on the S25

**What.** The live model stays loaded all session. Recent llama.cpp exposes an HTP DCVS/HMX power mode
(`GGML_HEXAGON_POWER_MODE`, release b11601, "keep HTP awake for DCVS power profiles";
[GenieX notes](https://raw.githubusercontent.com/qualcomm/GenieX/main/notes/run.md)), set once at process start.
Instead, vote performance only for the duration of a prefill and decode burst, and release the vote (or choose a balanced
profile) between replies, which take most of the wall-clock time.

**Gain.** Unknown. If the backend holds turbo with sleep disabled for the whole session, the idle cost could be tens to
~100+ mW continuously, in the same thermal envelope as the GPU. If it already lets the DSP sleep, ~0. **Measure:**
battery current with the model loaded and idle against unloaded, over 5 minutes standing still.

**Risk.** None visual. First-token latency may rise by the clock ramp (milliseconds).

**Effort.** S (a call around `generate`, maybe a small patch in ggml-hexagon).

## 10. Render only on change while the image is static — later

**What.** In conversations, menus, the map and pause, the camera is still and most motion is pixel-art sprite frames
stepping at ~8-12 fps. If no transform, parameter, light or animation frame changed since the last frame, skip
`Renderer.render`. The compositor keeps showing the last buffer, so the screen is identical.

**Why it fits.** Pixel-art animation is stepped, so a static view is truly identical between steps. Conversations are
exactly when the NPU is busy and hot, so the GPU's heat budget moves to the model.

**Gain (estimate).** In a still conversation shot, 50-80% of frames skipped. The game clock's sun creep and cloud drift
must be stepped (e.g. advanced only when a step changes some pixel, or paused in dialogue). That is a design decision:
the owner should know the sky would advance in steps there.

**Risk and proof.** A missed dirty source shows a stale frame. Proof: a preview run that renders every frame anyway
and asserts the skipped frames equal the previous image (0 px).

**Effort.** M (a dirty tracker across FilamentTown's per-frame setters).

**Dependency.** The heat job's pacing.

## 11. Pre-retiled NPU weights on flash, residency driven by forecasts — later

**What.** The Hexagon load copies and retiles ~2.0 GB into rpcmem at every load. Dump the retiled buffers once to
flash (the install size is fine), so a reload is a sequential read into fresh rpcmem (~0.5-1 s at UFS speeds,
estimate). Then the model no longer needs to stay resident all session: drop it on empty stretches (a countryside
drive, alone indoors), and reload a few seconds before a forecast encounter (idea 12). The bridge lines and the bank
cover any gap. With fast swaps, the 9B could also come in for a long seated conversation when the town ring is small.

**Gain (estimate).** ~2.2 GB freed for much of a drive. Measured load times are not in the docs; if today's load is
already ~1-2 s, only the residency policy is needed.

**Risk.** A conversation can start during a reload (the bridge and template tiers handle it). There is DMA-heap
fragmentation risk on repeated loads, which `LoadValve` watches.

**Effort.** L (a ggml-hexagon patch to accept pre-retiled tensors).

## 12. Encounter forecasting for bank pre-generation — later

**What.** People's positions are a function of seed, schedule and time (`rand(seed, ...)`). Project the player's route
(velocity, a set waypoint, a road) minutes ahead, and evaluate who will be within talking range. Pre-generate their
lines (and persona snapshots, idea 2) first, instead of by distance now.

**Gain.** A higher bank hit rate and fewer live generations, so less NPU heat in play. Small to medium; it only matters
once the bank is the bottleneck.

**Effort.** M (a query on the TS sim; it is cheap because it is closed-form).

## 13. Page our own cold memory to zram before the NPU load — maybe

**What.** MEMORY_AUDIT finding 1: memory freed right before the load counts most. Native regions the game knows are
cold (QuickJS's heap for far sim data, mapped-but-idle caches) can be pushed to zram with `madvise(MADV_PAGEOUT)` the
moment before the copy, so the kernel does not have to find the room under pressure.

**Gain.** Speculative: 50-200 MB at the load moment. Zram still costs ~1/3 of the size.

**Risk.** Stalls if touched soon after.

**Effort.** S. Try only if the gate's headroom is still tight after ideas 2 and 4.

---

## Dropped, and why (honest)

- **Frame generation (Adreno Frame Motion Engine 3.0, Adreno Neural Fusion SDK).** Qualcomm claims up to 40% power
  savings ([Snapdragon Studios](https://www.qualcomm.com/snapdragon-studios),
  [Neural Fusion SDK](https://www.qualcomm.com/developer/blog/2026/09/introducing-adreno-neural-fusion-sdk-for-snapdragon-mobile-platforms)),
  but the SDK is Vulkan plus engine plugins, adds a frame of latency, and interpolating nearest-filtered texel edges
  ghosts them. That is a visual veto for our art.
- **Render at the art-pixel grid and nearest-upscale.** It saves a lot, but it changes geometry and shadow edges
  visibly. Opt-in style setting only.
- **Palette or tile dictionaries for building atlases.** Buildings' atlases are ~19 MB resident at the route peak
  (step 4). Too small now.
- **Procedural ground from seeds in the shader.** G13 is under 15 MB and the hand-made look is at risk.
- **A low-rate impostor ring for the districts (5200-24000 units).** At 250 m-1.1 km the parallax at driving speed
  forces a rebake nearly every frame. The horizon ring already covers the range where it works.
- **Cached or quantized-sun shadows.** Filament re-renders cascades. Night already turns the caster off
  (`FilamentTown.kt:2216`). Idea 1 is the cheap shadow win.
- **More KV quantization.** Qwen3.5-4B is hybrid, and its KV is 19 MB at 8 bits. Nothing left to gain.
- **Texture-space or texel shading.** Lighting stepped per texel is a look change, not a free saving.
- **QuickJS bytecode.** `sim.js` is 267 KB, so parsing it is negligible.
- **Half-resolution HDR buffer and post-process diet.** Already in the catalogue (3.1).

## What to measure on the S25 to settle the estimates

1. Idea 1: GPU busy % and LRZ counters (Snapdragon Profiler) on the same downtown night view, opaque variant on and off.
2. Idea 2: the log line with `llama_state_seq_get_size` and the snapshot count after 10 minutes of talking.
3. Idea 3: battery current (`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`) over the same 5-minute walk, TextureView vs
   SurfaceView.
4. Idea 4: live material instances against `gfxDev` in the `accounts` line.
5. Idea 9: battery current with the model loaded and idle against unloaded.
