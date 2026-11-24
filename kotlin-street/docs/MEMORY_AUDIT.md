# Memory audit: permanent room for the dialogue model's NPU copy (2026-11-24)

The question: the light dialogue model's NPU copy (Qwen3.5-4B Q4_0) needs **3751 MB** of Android's "available" memory by
the gate (file 2463 + own memory 388 + `Ram.MARGIN_MB` 900; `ModelRoles.needBytes`, `NpuGate`). With the town built the
phone reports ~3.0-3.1 GB, at app start ~3.66 GB. The owner wants the model plus **1.5-2 GB of headroom**, and a memory
budget that stays flat as the capital grows.

Sources, and how much to trust each:

- **Phone, measured** (S25 Ultra, Android 17, build 0.1.848): the screenshot report `ss1124/100847-shot.json` (town built,
  memory and GPU by part), the play log `origin/reports:play/2026-11-24/100305-848.jsonl.gz` (a `mem` line every 5 s and
  an `accounts` line every minute, 6 minutes of play), the two low-memory kills
  `origin/reports:crashes/2026-11-24/050341-low-memory.json` and `050521-low-memory.json` (build 0.1.805).
- **Desktop, measured** (build server, llvmpipe): `gradle :desktop:preview -Dmode=filament-open -Dshots=downtown
  -DgpuOnly=1 -DtexStats=1`, and once more with `-DtownLod=0` (no far yards) to split the yards. Filament's own account
  on the desktop matches the phone's to within 2% (textures 644.5 vs 646-647 MB, yards 453.7 vs 453.7 MB), so the desktop
  is a good stand-in for **what Filament holds**, but it says nothing about the Adreno driver's own memory.
- **Estimates**: every saving below is an estimate unless marked measured. None of them is measured on the S25 yet.

---

## 0. The three findings that change the plan

1. **Android's "free" barely follows the game.** Measured on 0.1.848: at start, game PSS 165 MB with **3665 MB free**; with
   the town built, game PSS 2470-2550 MB with **2950-3120 MB free**. The game grew by ~2.3 GB and "free" fell by only
   ~0.6 GB: Android found ~1.6 GB elsewhere (cached apps ended, zram). So:
   - even with **no town at all**, free (3665) is already below the 3751 the gate asks for. The phone's system and
     background apps hold ~7.4 GB of the 11.1 GB. No amount of trimming the game alone gives "need + 2 GB" as Android's
     available count (that would be ~5.7 GB available);
   - the true room for the model is **available + what Android reclaims on demand**. That reclaim was measured twice:
     1514 MB on 0.1.598 (STATUS.md) and ~1.6 GB implied by this session's free-vs-PSS curve; the gate currently counts
     only 691 MB (the least of its last three loads);
   - a megabyte the game frees shows up as "free" in full only for a while (until Android fills it with cache again), so
     **freeing right before the load** is worth more than the same saving made an hour earlier.
   The plan therefore works on three levers together: make the game smaller (real memory), make the model's own copy and
   need smaller, and make the gate count the phone's real reclaimable memory, measured.
2. **The RSS 5.9 GB / swap 1.4 GB kills were a Filament bug, not the town's size, and it is fixed.** Both kills (05:03
   and 05:05, build 0.1.805) show ART's `Native bytes total: 6,777 MB` against `Total native bytes at last GC: 338 MB`,
   RssAnon 5.3-5.4 GB, VmSwap 1.4-1.5 GB, the main thread waiting in `Renderer.render`: Filament 1.81's handle debug-tag
   robin_map rehashing without end (TOWN.md, "The freeze on the Meridian's steps"; desktop repro 216 MB -> 8.4 GB). The
   patched AAR is in 0.1.848, and the 848 play log confirms it: native PSS 222-262 MB for 6 minutes, swap PSS 24-30 MB.
   See section 1.6 for the general RSS-vs-PSS explanation.
3. **The 181 MB of RG8 are the buildings' night masks, not the yards.** The coincidence of numbers misleads: RG8 261 =
   181.2 MB is `FilamentModels<BuildingMeshes` (each model's atlas-sized night mask, rewritten as windows light up); the
   yards' 181.5 MB of textures are ASTC atlases (135.3 with mips) plus ASTC masks (46.3). There are no RG8 normal or
   depth maps in the game.

Also worth knowing: in the 848 session the model never tried to load (the NPU was not found: fixed in ede8f5f), and no
speech, voice or memory model was resident ("Resident: no model", voices off, the mic on the phone's own recognizer), so
every number below is the game alone.

---

## 1. Where the memory goes (phone, town built, 0.1.848)

### 1.1 The process (PSS, Android's own count)

| part | MB | source |
|---|---:|---|
| graphics | 1873-1999 | phone (shot 1873; play log 1885-1999 while walking) |
| - GL mtrack (textures, buffers, render targets the driver allocated) | 1311 | phone |
| - Gfx dev (kgsl memory mapped into the process) | 463 | phone (369 at first view, grows with the town) |
| - EGL mtrack (window surfaces: Filament's swap chain, Compose's HUD window) | 98 | phone |
| Java heap | 178-265 (heap used 170-200 of a 512 limit; build peak 311) | phone |
| native heap | 222-262 | phone |
| code (mapped .so, .dex, .oat) | 56-57 | phone |
| other + stack | ~30 | phone |
| **total PSS** | **2470-2550** | phone |

Against Filament's own count of 1196 MB, **677 MB of graphics are outside Filament's account** ("graphicsBeyondFilament"):
GL mtrack exceeds Filament's textures+buffers+targets by ~115 MB (driver allocation granularity: Filament's own
estimate rounds to 1173 MB at 64 KB a buffer), EGL 98 MB, and Gfx dev 463 MB. A baseline: the crossroads street scene
before the town (Filament textures 2.9 MB) already had graphics PSS 228-232 MB, i.e. ~230 MB is the engine, render targets,
surfaces and the HUD with nothing in the scene.

**Gfx dev (463 MB) is the largest unexplained item.** Hypotheses, to be measured (section 4, step 1): per-object driver
allocations with a CPU mapping (uniform buffers: every Filament material instance owns a small UBO, and the town has
thousands of instances; at the driver's 4-64 KB granularity, 10,000 instances are 40-640 MB), staging memory for buffer
and texture uploads, and compiled shader variants. It grew 369 -> 463 MB between first view and town built, which points
at something per object.

### 1.2 Filament's GPU memory by part (phone = desktop)

| part | total | textures | buffers | note |
|---|---:|---:|---:|---|
| yards | 453.7 | 181.5 | 272.2 | near: 89.9 tex + 203.4 buf; **far versions: 91.6 tex + 68.8 buf** (desktop, `-DtownLod=0` split) |
| buildings and stores | 334.2 | 325.8 | 8.5 | 181.2 RG8 night masks + 123.5 ASTC atlases + RGBA8 8.6 |
| farms | 178.5 (desktop 191.7) | 93.9 | 84.6 | **85.6 MB RGBA8** (51 textures, one per farm's models) + 8 ASTC |
| GLB landmarks | 41.4 | 29.0 | 12.3 | already ASTC, 382 textures |
| trees | 23-40 | 0.7 | 22-39 | grows as tree cells come in detail |
| ground and terrain | 21.4 | 12.5 | 8.8 | |
| street things and greenery | 14.2 | 0.8 | 13.4 | |
| rail, vehicles, people, PropLayer | ~10 | | | |
| shadow map (2048 x 4 cascades, DEPTH16) | 32 | | | |
| render targets + surfaces (2340 x 1080, estimate) | 86.8 | | | RGBA16F colour + depth 28.9, surfaces 57.8 |
| **total** | **1196** | **646** | **~450** | |

By texture format: SRGB_ASTC_4x4 1792 textures = 308.6 MB, RG8 261 = 181.2, RGBA8 84 = 99.5 (farms 85.6, buildings 8.6,
cinema 2.5, misc), ASTC_4x4 365 = 53.3, R8 27 = 4.5. By owner: FilamentModels 406.9 (buildings 313 + farms 94),
PropLayer 234 (yards 181.5, landmarks 29, cinema 7.7, ground 6.4, far 6.1, ...). Biggest sizes: 512x512 x460 (120.6M
texels, the yards' lot atlases), 512x924 x154, 512x554 x148.

Buffers: 450 MB at 28 bytes a vertex (float3 position, float2 UV, short4 tangent quaternion), 248 shared BufferArena pages
of 327 MB holding 15,854 ranges (0.5-1.8 MB free space: the packing is already tight).

### 1.3 Java heap (178-265 MB PSS)

Heap used 170-200 MB in play, the build's peak 311 MB (largeHeap, 512 MB limit). The 805 kill's dump: 268 GCs and **10 GB
allocated in ~90 s** (12 s of GC): heavy churn, which costs frame time (gc spikes in the breadcrumbs) more than resident
memory, but the heap keeps the size of the build's peak. What it holds in play (TOWN.md, desktop heap dumps): town data,
collision (31,503 shapes), landmark height fields (38 MB for gamma on the desktop, now made on demand), night-light runs
(5 MB), the sim's view of people. `townMemoryCheck` guards the build's peak (256 MB budget).

### 1.4 Native heap (222-262 MB)

Filament (engine, handle arena configured at 64 MB, command buffers 16 + per-pass 16 + per-frame 8 MB: `TownEngine.kt`;
these are touched as used, not all resident), the GL driver's CPU side, QuickJS for the sim (512 MB limit, use not logged:
`SimJs.memoryUsed` exists), Jolt (4 MB temp allocator), scudo's own free lists. Not broken down on the phone yet.

### 1.5 Models (not resident in the measured session)

| model | memory when loaded | reclaimable? |
|---|---|---|
| live dialogue (Qwen3.5-4B Q4_0) NPU copy | measured 0.1.598: weights 2501 MB copied + 497 mapped, KV 19 (8-bit), recurrent state 50, compute 318 = 2888 MB on device | copy: no (DMA buffers shared with the DSP); 497 mapped: yes (file pages) |
| Qwen3-ASR (when the game's own ASR is used) | 1.7B: 2059 mapped + 356 encoder + 119 own; small: 762 + 214 + 91 | mapped weights yes, the rest no |
| embedder (Qwen3-Embedding-0.6B) | ~0.6 GB mapped + small own | mostly yes |
| voices (Kokoro, Supertonic, ONNX Runtime) | a few hundred MB, one engine at a time (estimate) | no |

CONVERSATION.md: the speech, memory and voice models are already let go when the light model's copy is just short.

### 1.6 RSS 5.9 GB vs PSS 2.5 GB, and the 1.4 GB swap

- **The kill's own numbers**: RSS 5.89 GB = RssAnon 5.40 + RssShmem 0.44 + file ~0.05; VmSwap 1.44 GB more. ART counted
  6.78 GB of malloc'd native bytes. PSS was last sampled at first view (2.07 GB), ten seconds before native memory ran away,
  so the two numbers were never of the same moment. The runaway was Filament's handle-tag map (finding 2), fixed in 0.1.848.
- **In normal play the two measure different things**: RSS counts every resident page of the process in full (shared
  libraries, the zygote's boot image, the JIT cache) and **leaves out GPU memory** (kgsl allocations are not mapped pages
  in RSS); PSS splits shared pages among their users and adds the GPU's memory through memtrack ("graphics"). So in play
  RSS is roughly Java + native + code + shmem (~0.7-0.9 GB) while PSS is ~2.5 GB, mostly graphics. Neither alone is "the
  game's footprint": that is PSS (with graphics) plus the game's swapped pages.
- **The swap** was the runaway native heap pushed to zram. In 0.1.848 the game's swapped PSS is 24-30 MB.
- What it proves, usefully: on this phone a single foreground process reached **~7.3 GB of anonymous memory (5.4 resident
  + 1.4 swapped) plus ~1.6 GB of GPU memory** before the low-memory killer ended it. The model's 2.9 GB plus the game's
  2.5 GB (5.4 GB) is well under that ceiling; the gate's 3751 MB of "available" is a conservative proxy for it.

---

## 2. Every opportunity

Savings are PSS on the phone, estimated from the measured parts above. Visual cost judged by the owner's rule (no
visible loss in the pixel-art style). Effort: S (a day or less), M (a few days), L (a week or more).

### 2.1 The town's graphics

| # | opportunity | saves (est.) | effort | visual cost | risk |
|---|---|---:|---|---|---|
| G1 | **Stream the town by distance** (a residency manager: full detail only within a radius, e.g. `READY_RADIUS` 1800 + margin ~3000 units; past it the existing far LOD: `TownFar` cells (6 MB for the whole town), far yards, buildings at their mip tail). Memory then follows the area around the player, not the town's size. The ASTC disk cache (`AstcCache`, ~300 MB) makes streaming in cheap (pre-encoded chains read from flash), and `TownWorkers` already orders work by distance. | 500-700 (town GPU content ~1080 -> ~400-450) and **flat as the capital grows** | L | none if the radius stays beyond the haze (`HAZE_FROM` 1400, yards' far switch at 1800-2400); pop-in is the thing to judge | medium: hitches when blocks stream in while driving (no fast travel: speed bounds the streaming rate), collision and walk data must stay resident or stream with the blocks |
| G2 | **Far yards from the near atlases' mip tail** instead of their own textures (91.6 MB of far-yard textures are separate pictures today); with G1, far yards keep only levels 2+ (1/16 the texels) | 80-85 | S-M | none (far yards are seen past 1800 units, where those levels are what is sampled) | low |
| G3 | **Night masks without the full-size RG8 copy** (181 MB). Smart version: a static mask that says which window each glass/lamp pixel belongs to (an index, R8 or ASTC-exact; it never changes), plus a tiny per-model "which windows are lit" texture rewritten by the hour (a few KB). Simpler version: crop each mask to the rectangle that holds any glass or lamp pixel (one uv-rect parameter in `street_lit.mat`). Or encode the hourly mask to ASTC on a worker when every block is exact (masks are 1-2 values a block almost everywhere), RG8 only where not. | 90-150 | M | none (exact) | low-medium (a material change; the hourly update path) |
| G4 | **Farms' RGBA8** (85.6 MB, 51 textures, one per farm's models: either their night masks forced to RGBA8 by an own-colour light, or atlases that were never made ASTC). As masks: G3's treatment; as atlases: ASTC with mips. | 55-70 | S | none | low |
| G5 | **Quantised vertices**: 28 -> 20 bytes (positions as 16-bit normalised in each batch's box: 3000-unit box = 0.05 units, ~2 mm; UVs as 16-bit texel coordinates scaled in the vertex shader so texel edges stay exact; the tangent quaternion stays short4). One place (`TownVertex`) plus the materials' vertex scale. Octahedral normals would not save more here: Filament's TANGENTS attribute already takes a short4 quaternion. | 110-130 (450 -> ~320) | M | none if UVs stay exact (check with pixel diffs of the preview's play shots) | medium (every builder writes through `TownVertex`; the material's vertex stage) |
| G6 | **Instancing repeated yard props** (fences, bushes, flowers, bins, play sets): the near yards' 203 MB of buffers are every lot's items baked into world space (`YardKit.bake`), ~0.7 MB a lot. One mesh per prop and an instance transform (Filament instances, or a per-batch instance table) | 100-150 | L | none | medium (shadows, the see-through cone and the camera cutaway work per instance; draw-call count) |
| G7 | **One deduplicated yard atlas pool** (sub-picture dedup by content hash: the 460 lot atlases of 512 x 512 repeat the same pack pictures lot by lot; TOWN.md noted it). Whole textures are already shared by content (14,795 shares); this shares the pictures inside them, as a 2D array or a few big shared atlases | 50-70 (near yards 90 -> ~25) | M | none | low-medium (UV remap at bake time) |
| G8 | **Mip-tail-on-demand per texture** (a residency manager driven by each texture's screen-space size/distance: far buildings keep levels 2+, recreated with level 0-1 when the camera comes near). Overlaps G1; worth it alone for landmarks seen from across town | 100-180 alone, mostly inside G1's figure | M | none (the dropped levels are not sampled at that distance) | medium (texture re-creation hitches; do it on the engine thread in small steps) |
| G9 | **Gfx dev 463 MB**: measure first (Perfetto's GPU memory track, `dumpsys meminfo -d`, counting material instances). If it is per-material-instance UBOs: share material instances between renderables with the same parameters (most of the town's batches differ only by texture), or move per-object values into the instance data | unknown; plausible 100-300 | M after measuring | none | low |
| G10 | **Render-target cache and arenas**: `resourceAllocatorCacheSizeMB` (default 64; render targets of every `ResolutionSteps` scale can sit in it) down to ~16; Filament arenas (64+16+16+8 MB, `TownEngine`) are touched as used, so trimming them saves little resident | 20-50 | S | none | low |
| G11 | **ASTC 6x6 / 8x8**: already measured on the desktop (TOWN.md, wf-gpu): only 61-72% of the art's blocks are one or two colours at those sizes, so the pixel art would visibly smear near the camera. Use only for **far-only data**: mip levels 2+ and far impostors | 15-30 | M | none at distance, visible near (so not near) | low |
| G12 | **Sparse / virtual texturing** (one physical page cache, a page table per material): nearest-filtered pixel art is the easiest case for it (no filtering across pages at level 0). Filament has no sparse-texture API on GL or Vulkan, so it would be our own indirection in `street_lit.mat`. G1+G8 get most of the gain with far less risk | ~same as G1+G8 combined | L+ | none | high (every material, every UV path) |
| G13 | Procedural ground/grass/brick in shaders | <15 (ground and terrain total 21 MB) | M | changes the hand-made look | not worth it |
| G14 | Shadow map (2048 x 4 cascades, 32 MB) and render targets (87 MB est.) | keep | | visible | |

### 2.2 CPU side

| # | opportunity | saves (est.) | effort | visual cost | risk |
|---|---|---:|---|---|---|
| C1 | **Java heap after the build**: the heap keeps the build's peak (311 MB) while 170-200 MB is used. Trim after "all built" (a GC then `VMRuntime` trim via the normal path), keep the build's peak lower (TownWorkers already holds far work during the first view), cut the 10 GB/90 s churn (allocation in per-frame paths: also gc spikes) | 50-90 | M | none | low |
| C2 | **Native heap**: `mallopt(M_PURGE_ALL)` (scudo, API 34+) after the town is built and before the model loads; log `mallinfo` and QuickJS's `memoryUsed` in the `mem` line; JS sim GC before the load | 20-60 | S | none | low |
| C3 | CPU copies after upload: already let go (Filament descriptors release them; `blankShared` zeros; townMemoryCheck guards it) | ~0 left | | | |
| C4 | Height fields, collision: on demand already (WalkLevels); with G1, stream the collision of far blocks too | 20-40 | with G1 | none | medium |

### 2.3 The model side (the need, and when the load happens)

| # | opportunity | saves (est.) | effort | quality cost | risk |
|---|---|---:|---|---|---|
| M1 | **Load the NPU copy before the town is built** (at app start: free 3665 + the gate's reclaim 691 = 4356 >= 3751, measured numbers; the town then builds beside it with Android reclaiming for the town, as it already does today for 1.6 GB). Watch the town's build with the same valve (`LoadValve`): if free nears the threshold, the far work waits | removes the problem for the session's start | S-M | none | medium (the town's build is the bigger allocation; the kill ceiling measured above says ~5.4 GB together fits) |
| M2 | **Smaller prompt batch on the NPU** (`batch_max_` 512 -> 128, already a switch: `set_device` bits 12-14): the NPU compute buffer was 318 MB at 512 against 81 MB on the CPU; with 128 it should be ~80-100 MB | ~220-240 of the need | S | prompt speed: to measure on the S25 (in-play prompts are short: fact sheet + reply in 1024 tokens) | low |
| M3 | **Find the copy's inflation**: 2501 MB copied + 497 mapped = 2998 MB for a 2463 MB file. Some tensors are copied in a bigger form than the file holds (likely the output projection or the recurrent layers' tensors). Keeping them quantised (or on the mapped CPU side, as the 497 MB already are) | 0-500 (unknown until a tensor-by-tensor dump) | M | none if the types stay | medium |
| M4 | **An adaptive margin** instead of a flat 900 MB: the margin is Android's threshold (216) + the critical line (500) + ~180 for the town's growth. With the valve already watching every load (stops at threshold + 250), the margin can be measured: the least free memory seen in the last N loads that held, plus the town's measured growth in play (with G1 it is ~0) | 200-400 of the need's arithmetic (not real memory) | S | none | medium (the valve and the gate's failed-try record are the safety net; both exist) |
| M5 | **Count the reclaim the phone really has**: the gate takes the least "found" of the last three loads (691 MB now) though two measurements say ~1.5-1.6 GB. Read `/proc/meminfo` at each load (SwapFree, AnonPages, Inactive(anon), Cached) and record per load what was found; use the median of recent loads with the valve as the guard | 300-800 of the need's arithmetic | S | none | medium |
| M6 | **Free right before the load**: with G1, drop the far ring's detail (out of sight in the haze) and purge malloc (C2) the moment before a load, rebuild after; free is worth most at that moment (finding 1) | 200-400 at load time | S with G1 | none (out of sight) | low |
| M7 | Unload ASR / TTS / embedder during the load | already done (CONVERSATION.md) | | | |
| M8 | KV cache in 8 bits and a 1024-token context | already done: KV is 19 MB; a smaller context saves ~10 MB | | | |
| M9 | Weights file-backed (mmap) on the NPU | **not possible** as far as we can see: the Hexagon backend runs from rpcmem/DMA buffers that the DSP maps; a file's page cache cannot be handed to the DSP (fastrpc maps DMA-buf fds; udmabuf only wraps memfd, which is anonymous memory). The 497 MB that stay on the CPU side are already mapped from the file | 0 | | | |
| M10 | QNN/HTP context binaries mapping weights from the file: the same limit as M9 (QNN's HTP shared buffers are rpcmem too) | 0 | L | | |
| M11 | One model for the writer and live roles | the writer runs with the game closed (overnight) and never beside play, so it saves nothing in play | 0 | | | |
| M12 | Split the model over NPU sessions | the S25 already uses 3 HTP sessions ("npuTuned"); splitting does not shrink the total | 0 | | | |
| M13 | A smaller live model (as a quality setting only) | ~1 GB | S | worse replies | owner's call; last resort |

### 2.4 Outside the game

The phone's system and background apps hold ~7.4 GB at the game's start. Samsung's "RAM Plus" (zram size) and the number
of cached apps are the owner's settings; they change "free" by hundreds of MB. Worth one check (Settings, Device care,
Memory) but not something the game can rely on.

---

## 3. The plan, in order

The projection uses two measures, both honest about their limits:
- **need**: what the gate asks of "available" for the live model's copy (3751 MB now);
- **room at load** = Android's available at the load + what the gate counts as reclaim (691 MB now). In play today:
  ~3050 + 691 = ~3740 MB, i.e. **-10 MB against the need**. "Free" rises by between ~30% (steady state, Android refills its
  caches) and 100% (right after the game frees it) of what the game cuts, so each step gives a range.

| step | what | game PSS after | need | room at load (in play) | headroom |
|---|---|---:|---:|---:|---:|
| now | | ~2500 | 3751 | ~3740 | **~0** |
| 1 | Measure: Gfx dev breakdown, meminfo and malloc stats in the `mem` line, the copy's tensor types (M3), batch 128's prompt speed. No saving, settles G9 and M3 | 2500 | 3751 | 3740 | 0 |
| 2 | Quick model-side wins: M2 batch 128 (-230 need), M5 measured reclaim (gate counts ~1.2-1.5 GB instead of 691, measured on loads that held), M1 load order at app start | 2500 | ~3520 | ~4250-4550 | **+0.7-1.0 GB** |
| 3 | Quick graphics wins: G2 far-yard textures (-80), G4 farm RGBA8 (-60), G10 caches (-30), C1 heap trim (-70), C2 purge (-40) | ~2220 | 3520 | +85-280 | **+0.8-1.3 GB** |
| 4 | G3 night masks (-120), G5 quantised vertices (-120), G7 shared yard atlas (-60), M4 adaptive margin (-300 need) | ~1920 | ~3220 | +90-300 | **+1.2-1.9 GB** |
| 5 | G1 streaming + G8 mip tails + M6 free-before-load (and G9 if Gfx dev is per-instance memory) | **~1300-1450, flat as the town grows** | 3220 | +150-600 | **+1.4-2.5 GB** |
| 6 | G6 instancing (with streaming, it mostly lowers the radius's cost and build time) | ~1250-1400 | 3220 | +30-60 | **+1.4-2.6 GB** |

Read it as: steps 2-4 (two to three weeks of work, mostly S and M) should reach ~1.2-1.9 GB of headroom on today's town;
step 5 is what keeps it there as the capital grows. M3 (the copy's inflation) is a wild card worth up to 0.5 GB more.
Every figure past "now" is an estimate; each step's real effect must be read from the phone's play log (`mem` free and
PSS, `accounts`, the gate's "found") before the next.

### The memory budget guard

So a new feature that blows the budget fails a test instead of breaking the NPU on the phone:

1. **Per-part budgets in one place** (`TownBudget`, shared code, desktop and phone read it), in MB, starting from today's
   measured numbers less the planned savings, and lowered as each step lands. A start:

   | part | today | budget after step 4 | budget with streaming (per resident radius) |
   |---|---:|---:|---:|
   | yards | 454 | 280 | 90 |
   | buildings and stores | 334 | 210 | 120 |
   | farms | 179-192 | 110 | 40 |
   | GLB landmarks | 41 | 45 | 45 |
   | trees, ground, street things, rail, vehicles, people | ~110 | 100 | 60 |
   | shadow map + render targets | 119 | 120 | 120 |
   | **Filament total** | **1196** | **865** | **475** |
   | Java heap in play (used) | 170-200 | 160 | 140 |
   | native heap | 222-262 | 220 | 200 |

2. **A desktop test** (`GpuBudgetTest`, beside `townMemoryCheck` and `MeridianStepsGpuTest`): builds the whole capital as
   the app does and fails when any part of `GpuAccount.categories()` is over its budget, naming the part and the owner
   that grew. With streaming: walks and drives a fixed route through the capital in the preview (`-Dseq`) and fails if the
   resident total at any frame exceeds the radius budget: that is the test that keeps the budget **flat as the world
   grows**, since adding districts must not change it.
3. **The phone logs the same budgets**: the `accounts` line gains "over budget: part X by Y MB" (and a breadcrumb), the
   `mem` line gains MemAvailable, Cached, SwapFree, AnonPages from `/proc/meminfo`, Gfx dev / GL / EGL, malloc's
   allocated bytes and QuickJS's `memoryUsed`, so the next report says which side moved. The AI panel's RAM line shows
   the NPU headroom (room at load minus need) as one number.
4. **The gate's figures in the play log** at each look (need, free, reclaim counted, why), so a regression in headroom
   shows the day it ships, not the day the NPU stops loading.

---

## 4. What to measure on the S25 next (in this order)

1. A screenshot report with the town built after `adb shell dumpsys meminfo -d com.timerpg.street` and a 10 s Perfetto
   trace with the GPU memory data source: what Gfx dev's 463 MB is (G9).
2. One NPU load with llama.cpp's buffer list per tensor type (M3), and its prompt speed at batch 512 vs 128 (M2).
3. The gate's "found" over several loads with `/proc/meminfo` beside each (M5), and one load at app start before the town
   (M1), with the town then built beside the model: free, the valve's minimum, and whether anything got killed.
4. After each plan step: the play log's `mem` (free and PSS) and `accounts` lines over 5 minutes of the same walk.
