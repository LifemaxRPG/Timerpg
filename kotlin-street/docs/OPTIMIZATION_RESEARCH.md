# Optimization research: what studios do, and what Timerpg should borrow

A catalogue of game optimization techniques that studios have shipped and documented, judged for our case: Filament
(OpenGL ES 3.x backend today, Vulkan possible) on the Galaxy S25 Ultra (Adreno 830, Hexagon NPU, 12 GB RAM shared by
CPU, GPU and NPU), stylized pixel art (nearest-filtered LimeZu textures on low-poly geometry), a capital with thousands
of buildings, a 2,000 x 1,000 km planet with no fast travel and a far view, Jolt physics, and a large dialogue model on
the NPU in the same memory as the game.

Our order of bottlenecks: **memory** first (the NPU model must fit with 1.5-2 GB to spare, and memory must stay flat as
the world grows), then **sustained heat and battery**, then **GPU time**.

Researched November 2026 from talks, engine docs and vendor guides (links inline). Nothing here was measured on the S25
for this document. Figures marked "estimate" are worked out from formats and counts, not measured. Figures about our
own game come from `kotlin-street/STATUS.md` and the code as of this writing.

Each entry gives: **What** it is, **Who** used it, what it **Saves**, **For us** (Filament, Adreno, pixel art) and
**Effort / risk**.

---

## 0. Where we stand, and a memory budget to aim at

What the code already does (so the catalogue does not re-propose it):
- Textures: ASTC 4x4 where the GPU reads it, with exact-block checks and an on-disk cache of encoded textures
  (`scene3d/TownQuality.kt`, `Astc.kt`); masks stored in R8/RG8 only where they use those channels.
- Vertices: one shared 28-byte layout (float3 position, float2 UV, tangent frame as four snorm16), about 3.5 M town
  vertices, ~95 MB (`scene3d/TownVertex.kt`). 16-bit indices where a mesh fits.
- Far buildings: per-cell merged boxes with their own front pictures shrunk 4x, cross-faded by screen-door dithering out
  to the haze (`TownFar.kt`); trees as cells with a card level, a simple level and a detail level (`TreeCells.kt`);
  a three-level faceted terrain with skirts to the horizon (`WorldFar.kt`).
- Dynamic resolution with an FSR-style upscale, held for seconds per step (`TownQuality` "Resolution").
- 4 shadow cascades of 2048^2; anti-aliasing off.
- The AI models: weights mapped from their files, one model at a time through `llm/AiLane.kt`, an 8-bit KV cache,
  idle models let go.
- A desktop CI-style guard: `gradle :desktop:townMemoryCheck` fails over 256 MB of held Java heap.
- Logged but not measured on the S25: 652 textures, 67.8 M texels.

`kotlin-street/docs/MEMORY_AUDIT.md` does not exist yet. Section 6 marks the items that belong in that audit.

**A budget sketch (estimate, to be replaced by S25 measurements).** Android on a 12 GB phone keeps roughly 3-4 GB for
the system, the launcher and cached apps before the low-memory killer starts on the foreground game. That leaves
about 8 GB for us. The NPU model at ~2.5 GB of weights plus its KV cache and runtime (~0.3-0.6 GB) leaves about
5 GB. Keeping 1.5-2 GB spare leaves **~3 GB for the whole game** (Java heap, native heap, GPU buffers and textures,
JS engine, Jolt, audio, voices and ASR when loaded). On Android, GPU memory is ordinary RAM and counts in the app's
"Graphics" PSS, which the low-memory killer sees ([Android: manage memory in games](https://developer.android.com/games/optimize/memory-allocation),
[low memory killers](https://developer.android.com/games/optimize/low-memory-insights)). A target that fits:

| Bucket | Budget (proposal) |
|---|---|
| Textures (all, incl. far, UI, shadow maps, render targets) | 400 MB |
| Geometry (vertex + index buffers) | 250 MB |
| Java heap peak | 256 MB (already guarded) |
| Native: Filament, Jolt, JS engine, audio | 400 MB |
| Voices + ASR when loaded (not with dialogue at once) | per `AiLane` |
| Headroom for spikes (building cells, uploads) | 300 MB |

The point of the table is not the exact numbers but that each bucket has an owner, a number and a test (section 1.8).

---

## 1. Memory

### 1.1 Texture streaming: mip streaming and virtual (sparse) texturing

**What.** Keep only the mip levels the camera needs in memory. *Mip streaming* loads per texture the top mips that
on-screen size justifies, under a global budget. *Virtual texturing* goes further: textures are cut into pages (e.g.
128x128), a feedback pass finds which pages are visible at which mip, and a fixed-size physical page cache holds only
those, found through an indirection texture.

**Who.** id Software's MegaTexture in *Rage* (id Tech 5: virtual textures of 128k x 128k texels;
[van Waveren, SIGGRAPH 2012 course](https://www.quaddicted.com/webarchive/mrelusive.com_20170131/publications/presentations/2012_siggraph/Virtual_Texturing_in_Software_and_Hardware_final.pdf));
Sean Barrett's "Sparse Virtual Textures" (GDC 2008, [demo and notes](https://silverspaceship.com/src/svt));
Unreal's Runtime/Streaming Virtual Textures; Unity's mipmap streaming, which takes a memory budget and drops the top
mips of distant textures to stay under it ([Unity: configure mipmap streaming](https://docs.unity3d.com/Manual/TextureStreaming-configure.html)).

**Saves.** Memory, often 50-80% of texture memory in big worlds; the page cache is a constant size whatever the world
holds, which is what "flat as the world grows" means.

**For us.** Full virtual texturing needs either sparse textures (not in ES 3.x; Filament does not expose them) or a
software page table with an indirection lookup per pixel, which Filament could do in a custom material, but at a cost
on Adreno (dependent texture reads, plus our atlases would need page borders for nearest filtering). Not worth it for
a pixel-art game whose texel density is low and uniform. **Mip streaming is the right fit**: a building near the
camera needs mip 0; one 300 m away needs mip 2 or less. Filament textures can be built with fewer levels and replaced;
our far buildings already use 4x-shrunk pictures (`TownFar`), which is the same idea by hand. Go one step further:
- create near-building textures without their top mip until the cell comes within the detail distance, then upload
  it (a 4x saving on every texture not near the camera);
- give the whole texture set one budget (like Unity's) and drop top mips farthest-first when over it.

**Effort / risk.** Mip streaming: medium (a per-texture "resident top mip" and a re-upload path). Risk: visible
pop when a mip arrives; with nearest filtering a missing top mip reads as chunkier pixels, which suits the style better
than blur does. Virtual texturing: high effort, low payoff for us. Skip.

### 1.2 ASTC block sizes

**What.** ASTC always uses 128 bits a block; the block footprint sets the rate: 4x4 = 8 bpp, 6x6 = 3.56 bpp,
8x8 = 2 bpp, 12x12 = 0.89 bpp ([Khronos ASTC wiki](https://wikis.khronos.org/opengl/ASTC_Texture_Compression),
[NVIDIA ASTC guide](https://developer.nvidia.com/astc-texture-compression-for-game-assets),
[Arm: ASTC how to use it](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/astc-does-it---part-ii-how-to-use-it)).
Qualcomm recommends compressing every texture and using sRGB ASTC on Adreno, both for memory and for fetch efficiency
([Adreno best practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html)).

**Who.** Practically every mobile game since ~2016; Unity and Unreal default to ASTC on Android.

**Saves.** Memory and bandwidth: 4x4 is 1/4 of RGBA8; 6x6 is ~1/9; 8x8 is 1/16.

**For us.** Pixel art is the worst case for block compression: a 4x4 block with three or more colours is approximated.
We already handle this (exact-block threshold, RGBA8 fallback). Pick per texture *class*:
- near pixel-art sprites (facades, props, people): 4x4, or the lossless indexed path in 5.1, which beats it;
- far-building pictures (already shrunk 4x, seen at a few pixels per texel): **6x6 or 8x8** is invisible at that
  distance and halves to quarters their memory again;
- terrain palettes, sky, clouds, water, impostor atlases: 6x6;
- masks and lookup data: never ASTC (R8/RG8 as now, or integer formats).

**Effort / risk.** Low (encoder settings by class). Risk: none beyond a visual check per class from far views.

### 1.3 Deduplication and atlases

**What.** Store each unique image once and reference it, and pack many small images into shared atlases (or texture
arrays) so they share mips, draws and memory overhead.

**Who.** Every console generation: PS1 "texture pages" in a 1 MB VRAM, shared per level; Insomniac's *Marvel's
Spider-Man* had to make a fast-growing asset count fit on a Blu-ray by reworking streaming and asset sharing
([Ruskin, GDC 2019 technical postmortem](https://gdcvault.com/play/1026496)); Unreal's HLOD builds merged atlases per
cell ([UE World Partition HLOD](https://dev.epicgames.com/documentation/en-us/unreal-engine/world-partition---hierarchical-level-of-detail-in-unreal-engine)).

**Saves.** Memory (no duplicate pixels), CPU (fewer material instances and draws).

**For us.** This is the biggest memory lever we have, because LimeZu art is *tile-based*: building fronts are built
from a modest set of 16/32 px tiles, but we bake each building into its own atlas. 67.8 M texels across 652 textures
is mostly the same tiles repeated. See 5.2 (tilemaps) for the concrete plan. Short of that, hash every baked picture
(the ASTC cache already keys by content) and share identical ones across buildings.

**Effort / risk.** Hash-and-share: low. Full tilemap: medium-high, see 5.2.

### 1.4 Mesh quantization

**What.** Store vertex attributes in fewer bits: positions as 16-bit integers relative to the mesh's (or cell's)
bounding box, UVs as 16-bit, normals/tangents as 8-16-bit or packed 10:10:10:2. The glTF extension
`KHR_mesh_quantization` turns a typical 48-byte vertex into ~20 bytes "with often negligible quality loss"
([KHR_mesh_quantization](https://wallabyway.github.io/Khronos-glTF-repo-GHPages-test/extensions/2.0/Khronos/KHR_mesh_quantization),
[meshoptimizer](https://github.com/zeux/meshoptimizer)). Qualcomm notes interleaved and compressed vertices raise
Adreno's vertex throughput ([Adreno vertex best practices](https://developer.qualcomm.com/sites/default/files/docs/adreno-gpu/snapdragon-game-toolkit/gdg/gpu/best_practices_vertex.html)).

**Who.** Activision's terrain uses 8 bytes per vertex
([Large-scale terrain rendering in Call of Duty, SIGGRAPH Advances 2023](https://advances.realtimerendering.com/s2023/Etienne%28ATVI%29-Large%20Scale%20Terrain%20Rendering%20with%20notes%20%28Advances%202023%29.pdf));
Activision's geometry pipeline packs and pre-processes all meshes
([Drobot, Geometry Rendering Pipeline Architecture](https://research.activision.com/publications/2021/09/geometry-rendering-pipeline-architecture));
Guerrilla's Decima streams tiles of immutable, pre-converted data
([Decima visibility, GCAP 2017](https://www.guerrilla-games.com/media/News/Files/GCAP2017_DecimaVisibility.pdf));
Filament's own glTF loader stores tangent frames as snorm16 quaternions.

**Saves.** Geometry memory and vertex bandwidth (= heat).

**For us.** Our 28-byte vertex can go to **20 bytes**: position as `SHORT4` normalized to each merged cell's box
(8 bytes; a 64 m cell gives 2 mm precision), UV as `USHORT2` *not* normalized holding integer texel coordinates
(times 2 or 4 for sub-texel edges), divided by the atlas size in the material (exact for power-of-two atlases, so the
nearest-filter texel edges stay exact), tangents as now. That is ~27 MB off 95 MB today (estimate), and the
saving grows with the world. Cells give natural quantization boxes: we already pack per cell, and Filament
renderables carry their own transform to scale the unit box back.

**Effort / risk.** Medium: one vertex layout change and a material change for UV; all builders write through
`TownVertex.put`, so it is one place. Risk: z-fighting where two cells' quantized edges meet (snap cell boxes to a
shared grid to avoid it).

### 1.5 Instancing

**What.** Draw many copies of one mesh in one call with per-copy data.

**Who.** Universal: foliage, props and crowds in every open world; GPU-instanced crowds with animation in a texture
([GPU Gems 3, ch. 2](https://developer.nvidia.com/gpugems/GPUGems3/gpugems3_ch02.html)).

**Saves.** Memory (one mesh instead of N merged copies) and CPU (fewer draws). Merging, which we do now, saves CPU but
*costs* memory: every fence post, bin and tree is stored once per copy.

**For us.** Filament supports it: `RenderableManager.Builder.instances(n)` up to 32,767, or with an `InstanceBuffer` of
transforms (automatic instancing up to `Engine.getMaxAutomaticInstances`, 64 on most platforms); the material reads
`getInstanceIndex()` when built `instanced: true`. All instances share one bounding box for culling, so instance per
cell, not per town ([RenderableManager.h](https://github.com/google/filament/blob/main/filament/include/filament/RenderableManager.h),
[Filament materials](https://google.github.io/filament/main/materials.html)). STATUS names `sideFence` at 429k
triangles and `greenhouse` at 278k: repeated yard kit is exactly what instancing is for. Rule of thumb: merge
small unique things, instance repeated things.

**Effort / risk.** Medium (yard kit, trees and props per cell). Risk: low; shadow casting works the same.

### 1.6 Procedural textures

**What.** Compute a texture in the shader (noise, bricks, stripes, gradients) instead of storing it.

**Who.** Demoscene, *.kkrieger*; open-world terrain shaders everywhere blend a few tiling textures by procedural masks
instead of storing unique terrain textures (e.g. *Horizon Zero Dawn*'s procedural placement and terrain,
[80.lv on HZD procedural placement](https://80.lv/articles/real-time-procedural-placement-in-horizon-zero-dawn/)).

**Saves.** Memory; costs ALU (heat) per pixel.

**For us.** Our ground already uses palettes per biome with one texel a quad. For pixel art, procedural is best kept
to *selection*, not pixels: choose which tile goes where by a hash of the world position (no per-place texture), then
sample a shared tile array. That is section 5.2 again, applied to ground, roads and roofs.

**Effort / risk.** Low-medium. Risk: repetition; mitigate with several variants per tile.

### 1.7 Running a big model next to the game

**What.** Keep the language model's weights, its KV cache and the game in one memory pool without the low-memory
killer taking the game.

**Who / sources.** llama.cpp's Hexagon (HTP) backend uses Snapdragon's unified memory and repacks Q4_0/Q8_0 weights
into its own (non-file-backed) buffers; its examples run with `--no-mmap` and an 8-bit KV cache (`-ctk q8_0 -ctv q8_0`)
([llama.cpp Hexagon backend docs](https://sources.debian.org/src/llama.cpp/7593%2Bdfsg-3/docs/backend/hexagon/README.md),
[developer notes](https://sources.debian.org/src/llama.cpp/7593%2Bdfsg-3/docs/backend/hexagon/developer.md)).
Qualcomm's Genie/QNN bundles fix context length and KV cache size at build time, and QNN *weight sharing* lets
several graphs (e.g. prefill and decode) share one copy of the weights
([Qualcomm AI Hub: prepare a GenAI model](https://docs.qualcomm.com/bundle/publicresource/80-80020-15B/topics/genai-prepare-ai-hub.md),
[QNN weight sharing](https://docs.qualcomm.com/doc/KBA-250306025001/KBA-250306025001_REV_1_QNN_Weight_Sharing_Feature.pdf)).
A 3B W4A16 model on the 8 Elite NPU measured 2.1 GB at 2,048 context
([GenieBlue, arXiv 2503.06019](https://arxiv.org/pdf/2503.06019)).

**Saves.** Memory, in the biggest single bucket.

**For us.**
- **mmap only helps on the CPU path.** File-backed weights are clean pages the kernel can drop and re-read under
  pressure without killing us. Weights copied into NPU (HTP/dmabuf) buffers are anonymous memory and count in full.
  Budget the NPU copy as resident, all of it, and measure it on the S25.
- **Never two copies.** If a CPU copy (mmap) and an NPU copy both stay mapped, the model is counted twice once pages
  are touched. Drop the CPU mapping once the NPU copy is up.
- **KV cache by formula:** bytes = 2 x layers x KV heads x head dim x context x bytes per element. Example (from the
  public model configs; check against ours): Qwen3-4B has 36 layers, 8 KV heads, head dim 128 -> 147 KB per token
  in f16, ~590 MB at 4,096 context, ~295 MB in q8_0. Context length is a memory setting: size it to what dialogue
  actually needs (persona + facts + a few turns) instead of the model's maximum.
- **Weight sharing** between prefill and decode graphs on QNN is mandatory, not optional, or the weights appear twice.
- **Watch for kills:** use the Memory Advice API (better recall than `onTrimMemory`) and read `ApplicationExitInfo`
  for `REASON_LOW_MEMORY` on the next start ([Android memory guidance](https://developer.android.com/games/optimize/memory-allocation)).
- Give the game a lever to answer pressure: drop far mips (1.1), far cells and caches before the system kills us.

**Effort / risk.** Low for measurement and the "one copy" rule; medium for the pressure response. Risk: the HTP
runtime's own overhead is not documented per component; measure.

### 1.8 Budgets and memory tooling as studios run them

**What.** A number per category (textures, meshes, audio, heap, per system), owned by someone, checked every build;
CI fails a change that goes over. A "memory report" lists the top consumers so the cause of a regression is obvious.

**Who.** Standard practice at large studios; Autodesk Stingray's docs describe dividing the total into per-subsystem
budgets ([Stingray: optimize memory](https://help.autodesk.com/cloudhelp/ENU/Stingray-Help/stingray_help/playtesting/optimize_memory.html));
Unreal's tooling flags projects over their memory limit (e.g. UEFN, [Unreal Fest 2023 memory management talk](https://forums.unrealengine.com/t/talks-and-demos-memory-management-in-uefn-and-fortnite-creative-unreal-fest-2023/1643523));
Unity sets the texture budget from the measured desired memory ([Unity docs](https://docs.unity3d.com/Manual/TextureStreaming-configure.html)).

**Saves.** Regressions, which are the main way memory grows.

**For us.** We have the pattern once (`townMemoryCheck`, `GpuAccount`, `GpuBytes`). Extend it: a desktop test that
builds the capital and fails over the GPU-bytes budget per bucket (textures, vertices, indices, shadow maps, render
targets), and a single "top 20 consumers" report in the breadcrumbs and the AI panel. Measure on the S25 with
`dumpsys meminfo` (Graphics, Native, Java, Code) and Android GPU Inspector.

**Effort / risk.** Low. Highest value per hour on this list.

---

## 2. Render distance and open worlds

### 2.1 LOD chains and HLOD

**What.** Each object has simpler versions for distance (LOD); groups of objects in a cell are replaced far away by
one merged, simplified proxy with one baked material (HLOD), which also stands in for cells that are not loaded.

**Who.** Unreal World Partition HLOD: "used to visualize unloaded World Partition grid cells, to reduce the number of
draw calls" ([UE docs](https://dev.epicgames.com/documentation/en-us/unreal-engine/world-partition---hierarchical-level-of-detail-in-unreal-engine),
[UEFN streaming and HLODs](https://dev.epicgames.com/documentation/fortnite/streaming-and-hlods-in-unreal-editor-for-fortnite));
GTA V's LOD chain (cars as light blobs, then low models, then full;
[Courrèges, GTA V graphics study part 2](https://www.adriancourreges.com/blog/2015/11/02/gta-v-graphics-study-part-2/)).

**Saves.** GPU (triangles, draws), CPU (draw submission), memory (far cells hold only the proxy).

**For us.** `TownFar` is already an HLOD level (merged boxes per cell). What studios add and we lack:
- **a second, coarser HLOD** for cells several km away (whole blocks as a handful of boxes, one shared atlas of
  "window rhythm" textures instead of each building's own pictures);
- **HLOD as the only resident form** of far cells: the full building data (meshes, near textures) for a cell is not
  kept in memory once the cell is past the detail line, only its HLOD. This is what makes memory flat as the capital
  grows.
Filament has no LOD system; we pick levels ourselves, as now.

**Effort / risk.** Medium. Risk: transitions; our screen-door cross-fade already handles them.

### 2.2 Impostors, octahedral impostors and billboards

**What.** Render an object from many directions into an atlas once; far away draw a single quad showing the nearest
view (octahedral mapping spreads the views over a hemisphere evenly and blends between neighbours).

**Who.** Epic's Ryan Brucks for *Fortnite* trees ([shaderbits.com, "Octahedral Impostors"](https://shaderbits.com/blog/octahedral-impostors),
[UE4 Impostor Baker on 80.lv](https://80.lv/articles/impostor-baker-for-ue4/)); "true impostors" in
[GPU Gems 3, ch. 21](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-21-true-impostors).

**Saves.** GPU and memory for far vegetation and props (one atlas per species, e.g. 8x8 views of 64 px = 512^2, about
256 KB in ASTC 4x4).

**For us.** A natural fit with pixel art: an impostor rendered at low resolution and drawn nearest-filtered *looks*
like our art. Trees first (thousands of them on the drive), then landmarks seen from 5-20 km. Skip the blending
between views (it blurs); snap to the nearest view, which also matches the stepped, sprite-like look. Bake at build
time from the same models, in Filament, with the same lighting direction family (or bake albedo + normal and light
in the shader, so the sun and night still work).

**Effort / risk.** Medium. Risk: lighting mismatch at the switch; bake normals to avoid it.

### 2.3 Streaming open worlds by cells

**What.** Cut the world into a grid (or a quadtree) of cells; load and unload by distance and by predicted movement;
keep a fixed memory pool; build on worker threads; never block the frame.

**Who.**
- *Zelda: Tears of the Kingdom* on Switch (4 GB RAM): streams seamlessly and *predicts* when the player will drop into
  a chasm, so the Depths are loading before the jump ([CEDEC 2024 coverage, Automaton](https://automaton-media.com/en/game-development/nintendo-made-tears-of-the-kingdom-load-seamlessly-by-predicting-when-the-player-would-jump-in-a-hole/));
  *Breath of the Wild* also uses dynamic resolution to hold its frame rate ([Nintendo Life on DF's finding](https://www.nintendolife.com/news/2017/03/zelda_breath_of_the_wild_uses_dynamic_resolution_scaling_to_maintain_performance)).
- *Horizon Zero Dawn*: Decima streams immutable tiles, loading and unloading on a background thread over several
  frames ([Decima visibility](https://www.guerrilla-games.com/media/News/Files/GCAP2017_DecimaVisibility.pdf),
  [Streaming the World of HZD](https://guerrilla-games.com/read/Streaming-the-World-of-Horizon-Zero-Dawn)).
- *Marvel's Spider-Man*: streaming adapted to the swing speed over Manhattan ([GDC 2019](https://gdcvault.com/play/1026496));
  Insomniac's earlier [Sunset Overdrive streaming talk](https://gdcvault.com/play/1022268/contactUs).
- *GTA V* on PS3/360 with 256 MB + 256 MB: heavy streaming from disc and a mandatory 8 GB install
  ([Rockstar support](https://support.rockstargames.com/articles/70WBCPVYJF1twEcuaxYH98/grand-theft-auto-v-xbox-360-and-ps3-installation-requirements),
  [GamingBolt retrospective](https://gamingbolt.com/its-amazing-to-look-back-at-what-rockstar-did-with-256mb-of-memory-farewell-gta-5-on-last-gen)).
- *Crash Bandicoot* (PS1, 2 MB RAM): 32-40 MB levels in 64 KB pages, laid out on the CD in the order they are needed
  ([Game Developer](https://gamedeveloper.com/design/how-naughty-dog-broke-sony-s-hardware-rules-to-create-i-crash-bandicoot-i-)).

**Saves.** Memory: the defining technique for flat memory in a growing world.

**For us.** We stream chunks and tree cells already; the town is built whole (cold build) and then held. What to
borrow:
- **a fixed pool and an eviction rule** for town cells (full detail only within N cells; HLOD beyond; nothing for
  unloaded ones beyond HLOD);
- **prediction by velocity**: on the bicycle or in a car, load along the heading and ahead by speed x lead time
  (TotK's idea). With no fast travel, all movement is predictable;
- **pre-baked cell files** (Crash, Decima): build cells once (or on first visit, cached on disk like the ASTC cache)
  into upload-ready blobs (quantized vertices, compressed textures), so streaming in is a read and an upload, not a
  rebuild from sprites. Size is not a concern for the install, so ship them baked.

**Effort / risk.** Medium-high (the town builder becomes a cell baker). Risk: hitches on upload; spread uploads over
frames as now.

### 2.4 Terrain: clipmaps and geomipmapping

**What.** *Geometry clipmaps* keep terrain as nested regular grids centred on the viewer, each ring twice as coarse,
shifted as the viewer moves; the GPU version samples heights from textures. *Geomipmapping* uses fixed patches with
per-patch LOD and skirts or stitching against cracks.

**Who.** [Losasso and Hoppe, SIGGRAPH 2004](https://hhoppe.com/proj/geomclipmap/); [GPU Gems 2, ch. 2](https://developer.nvidia.com/gpugems/gpugems2/part-i-geometric-complexity/chapter-2-terrain-rendering-using-gpu-based-geometry):
a 20-billion-sample terrain of the United States in 355 MB at ~90 fps. Activision's modern terrain uses 8-byte
vertices and a simplified mesh ([SIGGRAPH 2023](https://advances.realtimerendering.com/s2023/Etienne%28ATVI%29-Large%20Scale%20Terrain%20Rendering%20with%20notes%20%28Advances%202023%29.pdf)).

**Saves.** Memory (constant size whatever the planet's size) and GPU (triangles follow screen size).

**For us.** The planet (2,000 x 1,000 km) is the textbook case. Clipmap rings give a constant memory cost for the
whole drive. **Adreno caveat:** Qualcomm notes that vertex-shader texture fetches run twice (once in the binning
pass, once in rendering) and push the driver toward direct mode
([Adreno best practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html)). So build the
rings on the CPU with heights in the vertices (quantized, 8-12 bytes a vertex) and update a ring strip as the car
moves, rather than displacing in the vertex shader. The faceted low-poly look also hides LOD change better than
smooth terrain does. `WorldFar`'s three levels with skirts are a first step; WORLD_GEN W5 is where this goes.

**Effort / risk.** Medium-high. Risk: popping on hills; use the transition region from the GPU Gems chapter.

### 2.5 Far horizons the old way: fog, skyboxes with baked distant geometry, 2.5D backdrops

**What.** Hide the draw distance with fog tuned as atmosphere; draw the very far world as a scaled-down model or a
painted backdrop around the camera.

**Who.** *Silent Hill* (PS1) turned its short draw distance into fog that defined the game
([retrospective](https://retro-replay.com/retroreplayfyi-the-accidental-horror-of-silent-hills-fog));
Valve's Source "3D skybox": a 1/16-scale model of the distant scenery drawn around the camera
([Valve Developer Wiki](https://developer.valvesoftware.com/wiki/3D_Skybox)); PS2/N64 games used painted panoramas
and distance fog per level.

**Saves.** GPU and memory for everything beyond a few km.

**For us.** Filament has fog (`View.setFogOptions`, with a large-scale fog entity). Use it as atmosphere (aerial
perspective in the sky's horizon colour, which `TownFar` already uses). A Source-style **horizon ring**: re-render a
cylindrical panorama of everything past ~10-20 km into a texture strip every few hundred metres of driving (or when
the sun moves enough), and draw that strip instead of the real far terrain and towns. Pixel art lets this be low
resolution and nearest-filtered. Mountains and landmarks far off then cost one textured ring, whatever is behind
them. At night, bake the far towns' lit windows into the ring as point sprites (2.6).

**Effort / risk.** Medium. Risk: parallax error when the ring is stale; re-bake by distance travelled.

### 2.6 Distant lights as point sprites

**What.** Far away, a light is not a light: it is a bright dot or small billboard with a glow, no lighting
calculation. Near, it becomes a real light.

**Who.** GTA V: distant cars and lamps are light sources that gain detail as you approach; halos are billboards and
the glow comes from high emissive values through a tone mapper
([Courrèges, GTA V study part 2](https://www.adriancourreges.com/blog/2015/11/02/gta-v-graphics-study-part-2/),
[part 1](https://www.adriancourreges.com/blog/2015/11/02/gta-v-graphics-study/)).

**Saves.** GPU (no per-pixel lighting for thousands of lights) and CPU (no light objects).

**For us.** The capital at night has tens of thousands of windows and street lamps. Filament caps lights at 2,048 per
engine and warns that "performance is very sensitive to overlapping lights"
([LightManager.h](https://github.com/google/filament/blob/main/filament/include/filament/LightManager.h)). So:
windows = emissive texture masks (as now) everywhere; street lamps = real Filament spot lights only within ~50-100 m
(prefer spots to points, small cones, as the header advises); beyond that, one instanced quad per lamp with a glow
sprite, and in the horizon ring past that. This is the GTA look, and it suits pixel art (a 2x2-pixel light dot).

**Effort / risk.** Low-medium. Risk: none significant.

### 2.7 Occlusion culling: hierarchical Z, software occlusion, portals

**What.** Skip objects hidden behind others. Hi-Z: test bounding boxes against a depth pyramid. Software occlusion: a
small CPU depth buffer of big occluders. Portals: indoors, only draw rooms visible through doorways.

**Who.** Decima uses occlusion and tile visibility ([GCAP 2017](https://www.guerrilla-games.com/media/News/Files/GCAP2017_DecimaVisibility.pdf));
Adreno's LRZ builds a low-resolution Z during binning and rejects hidden pixels "at up to 4x the drawn pixel fill
rate" ([Adreno best practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html)).

**Saves.** GPU (vertex work, draw count), CPU on the submit side.

**For us.** Filament only does frustum culling. Adreno's LRZ already rejects hidden *pixels* cheaply, provided we
keep it on: no `discard`, alpha-to-coverage or depth writes in the fragment shader of opaque materials, and our
screen-door cross-fade *does* discard, so keep fades short and rare. What remains is vertex and draw cost of hidden
cells. For a city at street level (buildings block most of the view), a coarse **CPU cell visibility** (rasterize the
merged building boxes of near cells into a 128x64 depth buffer on a worker, test far cells' boxes against it) is the
proven approach, and our merged per-cell meshes make the test cheap. Interiors: **portals** (rooms and doors) are
the classic and the cheapest; the game already enters rooms through doors.

**Effort / risk.** Portals: low. Software occlusion: medium. Hi-Z on GPU: not available without readback or Filament
changes; skip.

---

## 3. GPU and draw calls on Adreno (tile-based)

### 3.1 Bandwidth, tile memory (GMEM) and render pass load/store

**What.** Adreno renders a frame tile by tile ("bins") in fast on-chip GMEM, then writes the result to RAM. Every
extra render target, every load of old contents and every store of something not needed later is RAM traffic, which
is power and heat. FlexRender picks binned or direct mode per pass.

**Who / sources.** [Qualcomm Adreno best practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html),
[tiling architecture](https://developer.qualcomm.com/sites/default/files/docs/adreno-gpu/snapdragon-game-toolkit/gdg/gpu/best_practices_tiling.html),
[remove unused render targets](https://developer.qualcomm.com/software/snapdragon-profiler/app-notes/remove-unused-render-targets),
[Arm: handling framebuffers](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/mali-performance-2-how-to-correctly-handle-framebuffers),
[Android GPU Inspector: expensive render passes](https://developer.android.com/agi/frame-trace/renderpasses).

**Saves.** Bandwidth = heat and battery; GPU time.

**For us.** Filament manages its own passes (it clears and invalidates attachments it owns). What we control:
- every **post-process effect** is a full-screen pass in and out of RAM: SSAO, bloom, SSR, TAA, the FSR upscale.
  Keep only those that earn it; each pass removed is real heat saved;
- `RenderQuality` / HDR colour buffer format (RGB11F vs RGBA16F halves colour bandwidth);
- avoid **extra Views** rendering the scene (each is a full set of passes); the sky surface and UI should not trigger
  a resolve of the 3D view;
- use AGI to confirm which passes run in binned mode and what each loads and stores.

**Effort / risk.** Low (settings and measurement). Risk: none.

### 3.2 MSAA versus FXAA/TAA on a tiler

**What.** On tile GPUs MSAA samples live in tile memory and resolve on the way out, so they cost no RAM bandwidth;
FXAA/TAA are extra full-screen passes.

**Who / sources.** Qualcomm: "prefer MSAA over other anti-aliasing techniques"; "MSAAx2 is likely to be practically
free"; lazily allocated MSAA attachments ([Adreno best practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html)).
Arm: MSAA samples "never hit memory" with `EXT_multisampled_render_to_texture`
([Arm](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/mali-performance-2-how-to-correctly-handle-framebuffers)).

**Saves.** GPU and bandwidth versus post AA; better image.

**For us.** We have AA off. For pixel art, MSAA is the *right* AA: it smooths geometry edges (box silhouettes, roof
lines) and never blurs the nearest-filtered texels inside a face, which FXAA and TAA would. Try Filament's
`View.setMultiSampleAntiAliasingOptions` at 2x (and 4x) and measure. Caveat: it raises the bin count a little, and
the dynamic-resolution upscale pass still exists; check that Filament's GLES backend uses multisampled render-to-texture
on our device (AGI shows it).

**Effort / risk.** Very low. Risk: small GPU cost; measure.

### 3.3 Draw calls, batching and GPU-driven rendering

**What.** Fewer, bigger draws (merging, instancing, texture arrays to avoid material switches). GPU-driven rendering
goes further: the GPU culls and builds its own draw list (multi-draw indirect, mesh shaders).

**Who.** Ubisoft's *Assassin's Creed Unity* (Haar and Aaltonen, "GPU-Driven Rendering Pipelines",
[SIGGRAPH 2015 Advances](https://advances.realtimerendering.com/s2015/index.html)) and Activision
([Drobot 2021](https://research.activision.com/publications/2021/09/geometry-rendering-pipeline-architecture)).

**Saves.** CPU (heat on the big cores), driver time.

**For us.** **GPU-driven rendering does not fit Filament on ES 3.x**: core ES 3.x lacks multi-draw indirect and
Filament exposes no indirect draws. Stay with: merged per-cell meshes (as now), instancing for repeats (1.5),
one material for the whole town using **texture arrays** (`sampler2dArray` is supported by Filament materials) so
cells with different pictures can still share a material and batch. Qualcomm also asks for triangles of at least
~4 pixels (tiny far triangles waste work): another reason for HLOD.

**Effort / risk.** Low-medium. Risk: low.

### 3.4 Half precision

**What.** `mediump` (fp16) arithmetic in shaders.

**Who / sources.** Qualcomm: mediump fragment shaders can be "twice as power-efficient and deliver twice the
performance" ([Adreno best practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html));
Filament keeps the critical bits in highp on mobile ([Romain Guy, Filament on mobile, Arm MMG 2020](https://community.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/5_2D00_mmg2020_2D00_filament_2D00_romain.pdf)).

**Saves.** GPU power (heat).

**For us.** Filament materials default to `quality: low` on mobile, which already uses mediump where safe
([Filament materials](https://google.github.io/filament/main/materials.html)). In our own material code, write
colour and lighting math in `half`/mediump; keep world positions and UVs for large atlases in highp (texel-exact UVs
in a 4096 atlas need more than fp16's 11 bits). Avoid stray float constants that force conversions.

**Effort / risk.** Low. Risk: banding or UV jitter if UVs go to mediump; keep them highp.

### 3.5 Shadows: cascades, cached and static shadow maps

**What.** Cascaded shadow maps split the view into ranges, each with its own map. Studios cache shadows of static
geometry and only redraw dynamic objects, or scroll cached cascades as the camera moves.

**Who.** Insomniac's scrolling cascades ([Acton, SIGGRAPH 2012](https://advances.realtimerendering.com/s2012/insomniac/Acton-CSM_Scrolling(Siggraph2012).pdf));
CryEngine's cached sun cascades ([CryEngine docs](https://www.cryengine.com/docs/static/engines/cryengine-3/categories/1114113/pages/21267738));
Epic's virtual shadow map caching, and why Fortnite gave up caching the moving sun
([Unreal tech blog](https://www.unrealengine.com/en-US/tech-blog/virtual-shadow-maps-in-fortnite-battle-royale-chapter-4)).

**Saves.** GPU (shadow passes re-draw the scene per cascade every frame), memory (map size).

**For us.** We run 4 cascades at 2048^2. Memory: 4 x 2048^2 x 2-4 bytes = 32-64 MB depending on depth format
(estimate), plus the cost of drawing the town four more times a frame. Filament does **not** support cached or
scrolled shadow maps through its API; it re-renders cascades every frame. What we can do within Filament:
- `shadowFar` short (shadows only where they read), cascades by quality setting, `stable` on (less shimmer);
- exclude far HLODs, terrain beyond the town and tiny props from `castShadows`;
- bake **static ambient occlusion and contact darkening** into vertex data or a texture channel for buildings and
  yards (cheap and very much in the PS2 look), so fewer real cascades are needed for the scene to read;
- the sun moves slowly: a fork-level change to update the far cascade every few frames is possible but is engine work.

**Effort / risk.** Low for settings and casters; medium for baked AO; high (engine fork) for cached cascades.

### 3.6 Many lights at night: baked versus dynamic, clustering

**What.** Clustered (froxel) forward shading assigns lights to 3D screen cells so each pixel only evaluates its own
lights. Studios bake static lights into lightmaps or vertex colours and keep few dynamic lights.

**Who.** Filament itself is a clustered forward renderer (froxels); its docs warn about overlapping lights.
*Spider-Man*'s postmortem covers pitfalls of pre-baked lightmaps in a big city ([GDC 2019](https://gdcvault.com/play/1026496)).

**Saves.** GPU.

**For us.** Windows: emissive masks, never lights (as now). The glow windows throw on pavements and walls: bake it as
a night-only light texture or vertex colour per building/yard (a "night light map", PS2-style), switched by time of
day. Real Filament lights only for the few nearest lamps, the player's surroundings and moving lights (car
headlights near the player). Distant lights: point sprites (2.6).

**Effort / risk.** Medium for baked night glow. Risk: low.

### 3.7 Vulkan

**What.** Filament's Vulkan backend instead of GLES.

**Saves.** CPU driver overhead (heat), access to Adreno features (VRS, explicit load/store, tile shading extensions).

**For us.** Possible, but Filament's renderer decides render passes either way; the CPU saving is real but only
matters once CPU submit time shows in profiles. Worth an A/B measurement on the S25, not a commitment.

**Effort / risk.** Low to try (backend switch), medium to make solid (device-specific bugs).

---

## 4. CPU, heat and battery

### 4.1 Frame pacing and frame-rate caps

**What.** Present frames at even intervals (no 16/33 ms alternation), and cap the rate the game can sustain.

**Who / sources.** Android Frame Pacing (Swappy) uses presentation timestamps and fences to avoid buffer stuffing
([Android frame pacing](https://developer.android.com/games/sdk/frame-pacing)).

**Saves.** Heat (a 120 Hz game does roughly twice the work of a 60 Hz one), perceived smoothness.

**For us.** STATUS says the app asks for the display's fastest mode, i.e. 120 Hz. For sustained play that is the
single largest heat setting. Offer 60 (default for long drives), 90 and 120 as a quality setting, and set the display
mode to match so pacing is even. Filament paces with Choreographer timestamps passed to `Renderer.beginFrame` and
its `FrameRateOptions`; Swappy is not built into Filament, but the same principle (even frame intervals, no
over-production) applies.

**Effort / risk.** Low. Risk: the owner's taste for 120 Hz; leave it a setting.

### 4.2 Dynamic resolution and variable rate shading

**What.** Lower the render resolution when the GPU is behind (BotW, most console games); VRS shades some screen areas
at lower rate.

**For us.** Dynamic resolution exists already. Feed it the thermal headroom too (4.4), not only frame time: lower
resolution *before* the phone throttles, not after. VRS: Adreno supports it under Vulkan, Filament does not expose it;
skip. For pixel art, a slight resolution drop is less visible than in realistic games because texel density is low.

**Effort / risk.** Low.

### 4.3 Simulation LOD for crowds and NPC schedules

**What.** Simulate near characters fully, far characters coarsely, and very far ones as statistics; swap
representations without the player noticing.

**Who.** *Assassin's Creed Unity*: AI level of detail and pooling that "swap from low-res NPCs to high-res NPCs
without the player noticing", thousands of persistent NPCs ([GDC 2015, Massive Crowd: AI Recycling](https://www.gdcvault.com/play/1022141/Massive-Crowd-on-Assassin-s),
[systemic crowd events](https://www.gdcvault.com/play/1022211/Postmortem-Developing-Systemic-Crowd-Events));
*Hitman: Absolution*: 1,200-character crowds at 30 fps on PS3/360 ([GDC 2012, Crowds in Hitman](https://gdcvault.com/play/1015315/Crowds-in-Hitman)).

**Saves.** CPU (heat), memory (pooled bodies instead of one per citizen).

**For us.** Our TypeScript simulation is deterministic (`rand(seed, ...)`), which is ideal: a far person does not need
to be simulated, only *evaluated* at the moment they matter ("where is she at 14:00?" is a function of seed, schedule
and time). Tiers:
1. near (rendered): full movement, animation, perception (witnesses);
2. same district (not rendered): schedule steps at coarse ticks, event-driven (arrive, leave, notice);
3. elsewhere: closed-form from the schedule, plus a small set of *state changes* that persist (who saw a crime, who
   told whom). Crime consequences travel through people, so the *memory and gossip graph* runs at all tiers, while
   movement does not.
Pool the rendered bodies (AC Unity): a fixed set of meshes/sprite sheets reused as people come in and out of range.

**Effort / risk.** Medium (the sim is in TS through an embedded JS engine; tiers are an API change there). Risk:
inconsistencies when a far person appears near; determinism makes them rare.

### 4.4 Thermal-aware scheduling and the Performance Hint API

**What.** Read how close the device is to throttling and shed work before it happens; tell the OS how long each
frame's work should take so it picks clocks and cores.

**Who / sources.** ADPF thermal API: `getThermalHeadroom(forecastSeconds)`, 1.0 = severe throttling; games are told
to lower frame rate or fidelity when headroom runs out ([ADPF thermal](https://developer.android.com/games/optimize/adpf/thermal));
Performance Hint API: create a session for the render thread group with a target duration and report actual work each
frame ([Performance Hint API](https://developer.android.com/games/optimize/adpf/performance-hint-api),
[Qualcomm: ADPF and QAPE](https://docs.qualcomm.com/doc/80-PK177-134/topic/google_adpf_and_qape.html)).

**Saves.** Heat and battery; avoids the sudden drop when the phone throttles.

**For us.** The S25's 8 Elite has no little cores, so "move work to small cores" is not an option; the levers are
clocks (via hints), frame rate and work volume. Plan:
- a performance hint session for the Filament render thread + game thread, target = frame budget;
- a second, low-priority session (or none) for workers;
- a **thermal governor** reading headroom every few seconds with a forecast of ~10 s: above ~0.7, lower the frame cap,
  resolution floor and cascade count, and pause background baking; above ~0.9, pause dialogue pre-generation;
- the NPU is part of the same thermal envelope: pre-generate dialogue in bursts while the player is in a quiet scene,
  not during driving at night in the city.

**Effort / risk.** Low-medium. Risk: thresholds need S25 measurement.

### 4.5 Heavy work in bursts, idle and while charging

**What.** Do caching, baking and model work when the game is idle, paused or the phone is charging.

**Who.** Standard on consoles (shader and cache building at install or idle); Android exposes charging and idle
constraints to background work (WorkManager's `setRequiresCharging` / `setRequiresDeviceIdle`).

**For us.** Candidates: baking cell blobs and impostors (2.2, 2.3), ASTC encoding (already cached), horizon rings,
pre-generating dialogue for the people the player is likely to meet, compacting the conversation log. While charging
and the game is open but paused, run them at full speed; while playing, only in short bursts with thermal headroom.

**Effort / risk.** Low-medium.

### 4.6 Job systems on a big-core-only CPU

**What.** A pool of worker threads, one per core minus the main and render threads, taking small jobs.

**For us.** All 8 cores are fast, so the scheduling problem is heat, not core types: fewer threads at full speed
can be hotter than more threads finishing sooner and sleeping ("race to idle"). Keep the town worker pool sized
(it is "workers + 2" now), tag jobs by priority, and let the thermal governor shrink the pool. Do not pin threads to
cores; the Performance Hint API is the supported way to steer.

**Effort / risk.** Low.

---

## 5. Classic tricks from memory-starved consoles

### 5.1 Palette (indexed) textures decoded in the shader

**What.** Store each pixel as a 4- or 8-bit index into a palette (CLUT); look the colour up at draw time. PS1, PS2,
GBA, DS and SNES all did this; several 16-colour palettes can share one 256-entry table
([indexed colour](https://en.wikipedia.org/wiki/Indexed_color),
[PS2 GS textures (ps2tek)](https://israpps.github.io/ps2tek/PS2/GS/Textures.html),
[PSP image formats](https://ppsspp.org/docs/psp-hardware/gpu/image-formats)).
Modern pixel-art games use the same trick for palette swaps
([pvigier: palette swapping with shaders](https://pvigier.github.io/2019/10/06/palette-swapping-with-shaders.html)).

**Saves.** Memory: an 8-bit index texture is the same size as ASTC 4x4 (1 byte a texel) but **lossless**; a 4-bit
index is **half** that and still lossless. Also enables cheap night palettes, seasonal and era palettes, recolours.

**For us.** LimeZu art uses limited palettes, so most tiles fit 16 colours and nearly all fit 256. Filament materials
support integer samplers (`format: int`, non-filterable) and `texelFetch`-style reads, and `sampler2dArray`
([Filament materials](https://google.github.io/filament/main/materials.html)). Plan:
- index texture `R8UI` (8-bit) or 4-bit packed two per byte, palette as a small RGBA8 texture row per palette;
- nearest filtering is what we want anyway; mips are made offline as *index* mips (pick the most common index in each
  2x2), sampled with `texelFetch` at a computed level;
- a palette swap per building gives colour variety for free (fewer unique images), and a night palette can replace
  some of the night-light mask data.
Note honestly: the pixel shader does two dependent reads instead of one; on Adreno this is cheap compared with the
bandwidth saved, but measure.

**Effort / risk.** Medium (material, encoder, mip rule). Risk: alpha edges and emissive masks need their own channel
or palette entries.

### 5.2 Tilemaps and texture-page reuse (the SNES model)

**What.** The screen (or a surface) is a small map of tile indices; the tiles themselves live once in a shared tile
set (VRAM pages). A whole SNES level is a tilemap of a few KB plus a tile set.

**Who.** Every tile-based console game (NES, SNES, GBA, DS) and PS1 texture pages
([tile-based video game](https://en.wikipedia.org/wiki/Tile-based_video_game)).

**Saves.** Memory, enormously for tile-built art: a building face of 12 x 8 tiles costs 96 index entries instead of
a 384 x 256 RGBA image.

**For us.** The best fit in this whole catalogue. Our facades, roofs, fences, ground and road surfaces are LimeZu
tiles. Today each building bakes its own atlas (much of the 67.8 M texels). Instead:
- one **global tile array** (`sampler2dArray`, each 16 or 32 px tile a layer, or a few big tile atlases with gutters),
  ASTC 4x4 or indexed (5.1), with proper per-tile mips (a texture array avoids bleeding between tiles);
- per building or surface, a tiny **tilemap texture** (`R16UI`: tile index + flip/palette bits) or tile indices in
  the vertex data (a quad per tile, which also suits our low-poly faces);
- unique art that is not tile-based (signs, landmarks, the Rex) stays as its own texture.
Estimate (not measured): if most of the 67.8 M texels are repeated tiles, the town's textures go from tens of
megatexels to a few, and stay flat as more buildings are added, because new buildings reuse tiles. This is the
change that makes "memory flat as the world grows" true for textures.

**Effort / risk.** High (touches how buildings are baked, `SpriteBuilding` -> geometry). Risk: details the bake does
today (shading baked into pictures, cut-outs) must be done in the shader or as separate decals. Prototype on one
building type first and compare renders pixel for pixel.

### 5.3 Streaming from disc, laid out in read order

**What.** Lay files out on the medium in the order they will be read; read big sequential blocks; keep a
small fixed buffer.

**Who.** *Crash Bandicoot* (above); PS2-era open worlds streamed from DVD the same way.

**For us.** Flash storage removes seek costs, but the lesson stands: one file (or one pack) per cell, read in one call,
already in upload format; memory-map read-only data so it is file-backed (droppable) rather than anonymous.

**Effort / risk.** Part of 2.3.

### 5.4 Vertex animation

**What.** Store animation as per-frame vertex positions (or bone matrices) in a texture and play it in the vertex
shader; no skeleton evaluation on the CPU.

**Who.** Quake's MD2 models, N64/PS1 morph animation; modern crowds via vertex animation textures
([GPU Gems 3, ch. 2](https://developer.nvidia.com/gpugems/GPUGems3/gpugems3_ch02.html),
[SideFX: VAT for crowds](https://www.sidefx.com/learn/talks/vertex-animation-textures-for-crowds/)).

**Saves.** CPU and memory for crowds; draws via instancing.

**For us.** Useful for 3D people, birds, flags, laundry on lines, trees swaying. Adreno caveat again: vertex texture
fetch runs twice under binning; so for simple motions (sway, flutter) prefer **analytic** vertex animation (a sine by
height, by instance phase), which needs no texture. Use VAT only for complex crowds, and measure. Filament supports
up to 255-bone skinning, 4 bones per vertex, and morph targets
([RenderableManager.h](https://github.com/google/filament/blob/main/filament/include/filament/RenderableManager.h)).

**Effort / risk.** Low (analytic) to medium (VAT).

### 5.5 Fog and draw-distance design

**What.** Choose the view distance and atmosphere on purpose, per place and time: valleys, city streets and forests
hide distance naturally; open plains and coasts need a horizon solution.

**For us.** Streets in the capital: buildings occlude, so near detail can be rich and the far view is mostly the sky
and a few landmarks (impostors). Countryside drives: the horizon ring and fog. Night: draw distance becomes lights
only (2.6). Weather (rain, mist) is a legitimate, era-appropriate way to cut distance when the phone is hot, and it
looks intentional.

**Effort / risk.** Low (art direction).

---

## 6. What Timerpg should adopt, in order

Ranked by the expected win on our bottleneck: memory first (NPU model fits with 1.5-2 GB to spare, memory flat as
the world grows), then heat and battery, then GPU time. Wins are estimates unless stated. **[Audit]** marks items
that belong in the memory audit (`kotlin-street/docs/MEMORY_AUDIT.md`, not yet written): they are measurements or
budgets the audit should establish first, or cheap fixes it can land.

1. **Measure and budget everything, with a CI guard per bucket** (1.8). Extend `townMemoryCheck` and `GpuAccount` to
   per-bucket GPU budgets (textures, vertices, indices, shadow maps, render targets) and a top-20 report on the
   phone; measure on the S25 with `dumpsys meminfo` and AGI, with the NPU model loaded. Win: no direct saving, but
   it is what keeps every later saving from eroding, and it tells us which of the items below matter most.
   **[Audit]**
2. **One copy of the model, sized KV cache, NPU weights counted as resident** (1.7). Drop the CPU mapping once the
   NPU copy is up; QNN weight sharing between prefill and decode; context length set to need; q8_0 (or the NPU's
   8-bit) KV cache; Memory Advice API and `ApplicationExitInfo` logging. Win: hundreds of MB if a duplicate or
   oversized cache exists today; certainty either way. **[Audit]**
3. **Tilemaps on a shared tile array, then palette indices** (5.2, 5.1, 1.3). The largest texture saving and the one
   that makes texture memory flat as the capital grows. Win (estimate): texture memory for buildings, ground and
   yards from ~90+ MB (all-ASTC today, more with RGBA8 fallbacks and mips) to a few tens of MB, *constant* in the
   number of buildings. Start with hash-and-share of identical pictures (cheap, **[Audit]**), then prototype the
   tilemap on one building type.
4. **Far content resident only as HLOD; mip streaming for near textures; ASTC 6x6/8x8 for far classes** (2.1, 1.1,
   1.2). Far cells keep only their merged boxes; near-building textures load their top mip only within the detail
   distance; far pictures and impostors go to 6x6/8x8. Win (estimate): roughly 2-4x on texture memory outside the
   near ring, and building data for far cells released. ASTC class choice is a quick **[Audit]** fix.
5. **Instancing for repeated yard kit, props and trees** (1.5). Win (estimate): tens of MB of geometry (`sideFence`
   429k and `greenhouse` 278k triangles stored per copy today), growing with the world. **[Audit]** (count repeats
   first).
6. **Vertex quantization to 20 bytes** (1.4). Win (estimate): ~27 MB of 95 MB now, ~28% of all future geometry.
   **[Audit]**
7. **Cell streaming with a fixed pool, velocity prediction and pre-baked cell files** (2.3, 5.3). Win: memory flat
   with the world's size (the defining property), faster loads, less heat from rebuilding; prerequisite for the
   planet-wide drive.
8. **Frame-rate setting (60 default for long play), thermal governor and performance hints** (4.1, 4.4, 4.2). The
   biggest heat and battery win available (estimate: up to ~half the GPU and CPU energy when 120 -> 60 Hz), and it
   protects the NPU's dialogue budget from throttling.
9. **Shadows within Filament's limits** (3.5): `shadowFar`, fewer casters, cascades by headroom, baked AO. Win:
   GPU time and heat (four scene passes a frame now), 16-48 MB if cascades or map size drop (estimate). Map
   memory is an **[Audit]** line.
10. **Night lighting: emissive + baked glow + point-sprite lamps; real lights only near** (2.6, 3.6). Win: GPU time
    and a far view of the lit city at night that costs almost nothing.
11. **Horizon ring and impostors for the far view** (2.5, 2.2). Win: constant cost for everything past ~10 km on the
    drives, and trees by the thousand; matches the art style.
12. **CPU-built clipmap terrain** (2.4), for WORLD_GEN W5. Win: constant terrain memory for the whole planet.
13. **Simulation tiers for people** (4.3). Win: CPU and heat as the world's population grows; required for the
    planet scale.
14. **MSAA 2x/4x instead of no AA** (3.2). Not a saving but nearly free on Adreno and the right AA for pixel art;
    try it while measuring.
15. **Post-process and render-target diet** (3.1), half precision in our own materials (3.4), keeping LRZ on (no
    discard in opaque materials except short fades) (2.7). Small, steady heat wins.
16. **Portals for interiors; CPU occlusion for city cells** (2.7). GPU and CPU wins at street level; later.
17. **Vulkan A/B test** (3.7). Measure only; adopt if CPU submit time shows up.

### What does not fit (be honest)

- **Virtual/sparse texturing** (1.1): no sparse textures in ES 3.x or Filament; a software version costs per-pixel
  indirection and border work for little gain with our low texel density.
- **GPU-driven rendering, multi-draw indirect, mesh shaders** (3.3): not in core ES 3.x, not exposed by Filament.
- **Hi-Z occlusion on the GPU** (2.7): needs readback or engine changes; Adreno's LRZ covers the pixel side.
- **Cached or scrolling shadow maps** (3.5): Filament re-renders cascades each frame; only an engine fork changes that.
- **Variable rate shading** (4.2): Adreno has it under Vulkan; Filament does not expose it.
- **Moving work to little cores** (4.4): the 8 Elite for Galaxy has none.
- **Vertex-shader texture fetch for terrain and VAT** works, but runs twice under Adreno's binning; prefer CPU-built
  heights and analytic animation where possible.

---

## Sources (main)

- Qualcomm: [Adreno GPU on mobile: best practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html),
  [tiling](https://developer.qualcomm.com/sites/default/files/docs/adreno-gpu/snapdragon-game-toolkit/gdg/gpu/best_practices_tiling.html),
  [vertex](https://developer.qualcomm.com/sites/default/files/docs/adreno-gpu/snapdragon-game-toolkit/gdg/gpu/best_practices_vertex.html),
  [ADPF and QAPE](https://docs.qualcomm.com/doc/80-PK177-134/topic/google_adpf_and_qape.html),
  [AI Hub GenAI preparation](https://docs.qualcomm.com/bundle/publicresource/80-80020-15B/topics/genai-prepare-ai-hub.md),
  [QNN weight sharing](https://docs.qualcomm.com/doc/KBA-250306025001/KBA-250306025001_REV_1_QNN_Weight_Sharing_Feature.pdf).
- Android: [memory in games](https://developer.android.com/games/optimize/memory-allocation),
  [low memory killers](https://developer.android.com/games/optimize/low-memory-insights),
  [ADPF thermal](https://developer.android.com/games/optimize/adpf/thermal),
  [Performance Hint API](https://developer.android.com/games/optimize/adpf/performance-hint-api),
  [frame pacing](https://developer.android.com/games/sdk/frame-pacing),
  [AGI render passes](https://developer.android.com/agi/frame-trace/renderpasses).
- Filament: [materials](https://google.github.io/filament/main/materials.html),
  [View.h](https://github.com/google/filament/blob/main/filament/include/filament/View.h),
  [LightManager.h](https://github.com/google/filament/blob/main/filament/include/filament/LightManager.h),
  [RenderableManager.h](https://github.com/google/filament/blob/main/filament/include/filament/RenderableManager.h).
- Arm: [framebuffers on tilers](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/mali-performance-2-how-to-correctly-handle-framebuffers),
  [ASTC](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/astc-does-it---part-ii-how-to-use-it).
- Studios and talks: Guerrilla ([Decima visibility](https://www.guerrilla-games.com/media/News/Files/GCAP2017_DecimaVisibility.pdf),
  [HZD streaming](https://guerrilla-games.com/read/Streaming-the-World-of-Horizon-Zero-Dawn)); Insomniac
  ([Spider-Man postmortem](https://gdcvault.com/play/1026496), [CSM scrolling](https://advances.realtimerendering.com/s2012/insomniac/Acton-CSM_Scrolling(Siggraph2012).pdf));
  Activision ([terrain 2023](https://advances.realtimerendering.com/s2023/Etienne%28ATVI%29-Large%20Scale%20Terrain%20Rendering%20with%20notes%20%28Advances%202023%29.pdf),
  [geometry pipeline](https://research.activision.com/publications/2021/09/geometry-rendering-pipeline-architecture));
  Ubisoft ([AC Unity crowds](https://www.gdcvault.com/play/1022141/Massive-Crowd-on-Assassin-s)); IO Interactive
  ([Hitman crowds](https://gdcvault.com/play/1015315/Crowds-in-Hitman)); Epic ([World Partition HLOD](https://dev.epicgames.com/documentation/en-us/unreal-engine/world-partition---hierarchical-level-of-detail-in-unreal-engine),
  [VSM in Fortnite](https://www.unrealengine.com/en-US/tech-blog/virtual-shadow-maps-in-fortnite-battle-royale-chapter-4),
  [octahedral impostors](https://shaderbits.com/blog/octahedral-impostors)); id Software
  ([virtual texturing](https://www.quaddicted.com/webarchive/mrelusive.com_20170131/publications/presentations/2012_siggraph/Virtual_Texturing_in_Software_and_Hardware_final.pdf));
  Nintendo ([TotK streaming, CEDEC 2024](https://automaton-media.com/en/game-development/nintendo-made-tears-of-the-kingdom-load-seamlessly-by-predicting-when-the-player-would-jump-in-a-hole/));
  Naughty Dog ([Crash Bandicoot paging](https://gamedeveloper.com/design/how-naughty-dog-broke-sony-s-hardware-rules-to-create-i-crash-bandicoot-i-));
  Rockstar ([GTA V study](https://www.adriancourreges.com/blog/2015/11/02/gta-v-graphics-study-part-2/));
  Valve ([3D skybox](https://developer.valvesoftware.com/wiki/3D_Skybox)).
- Research: [geometry clipmaps](https://hhoppe.com/proj/geomclipmap/), [GPU clipmaps (GPU Gems 2)](https://developer.nvidia.com/gpugems/gpugems2/part-i-geometric-complexity/chapter-2-terrain-rendering-using-gpu-based-geometry),
  [instanced skinned crowds (GPU Gems 3)](https://developer.nvidia.com/gpugems/GPUGems3/gpugems3_ch02.html),
  [meshoptimizer](https://github.com/zeux/meshoptimizer), [GenieBlue on the 8 Elite NPU](https://arxiv.org/pdf/2503.06019),
  [llama.cpp Hexagon backend](https://sources.debian.org/src/llama.cpp/7593%2Bdfsg-3/docs/backend/hexagon/README.md).
