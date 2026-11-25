# Tailoring the open source we build on (audit, 2026-11-25)

Read-only audit. llama.cpp is in docs/ENGINE_TAILORING.md and is left out here. Two other jobs are running: opaque
material variants and SurfaceView. Neither is repeated here. Their notes are in docs/NOVEL_OPTIMIZATIONS.md #1 and #3.

**Every gain below is an estimate** worked out from the code and from the S25 figures in the brief (GPU p50 17-20 ms,
p95 about 21 ms, 4x MSAA, CPU render part 8-10 ms, 25k-40k renderables). Nothing here was measured on the S25 for this
document. Line numbers refer to /home/user/filament-src (tag v1.81.0, commit 22c5385) and the repo as it is today.

## Inventory

| dependency | version / how it gets in | what uses it | patchable today? |
|---|---|---|---|
| **Filament** (engine, GLES backend, Java/JNI) | `filament-android:1.81.0@aar` from Maven, with a binary patch (two `ret`s, `patchFilamentAar`, app/build.gradle.kts:152-197); desktop builds it from source (tools/build-filament.sh) | the whole 3D town | **binary only**. Source patches need filament-android built from /home/user/filament-src (see F0) |
| matc (Filament material compiler) | /home/user/filament-dist/bin/matc, `-p mobile -a opengl -a vulkan` (tools/build-materials.sh) | 7 materials in kotlin-street/filament/materials | yes (flags, .mat) |
| gltfio | not used by the app (GLB landmarks go through our own GlbFile.kt) | none | n/a |
| ONNX Runtime | `onnxruntime-android:1.22.0` AAR (desktop: `onnxruntime:1.22.0`) | Kokoro-82M and Supertonic (shared/src/main/kotlin/voice) | session options yes; source needs an ORT build |
| QuickJS-ng | source in third_party/quickjs-ng, our CMake (app/src/main/cpp/simjs) | the TypeScript sim (sim.js, 267 KB) | yes |
| Jolt Physics | source in third_party/JoltPhysics, our CMake (app/src/main/cpp/physics) | cars, debris, statics (VehicleConstraint, JobSystemSingleThreaded) | yes |
| ASR: Qwen3-ASR through llama.cpp's mtmd | app/src/main/cpp/asr_core.cpp | talking to NPCs (the phone's own recognizer is the other option) | the llama.cpp side is covered by ENGINE_TAILORING |
| VAD | our own (shared/.../voice/SpeechSegmenter.kt) | n/a | n/a |
| Compose (BOM 2024.10.01), activity-compose 1.9.3, coroutines 1.9.0, security-crypto 1.1.0 | Maven | HUD, menus | config only |
| OpenCL headers/ICD, Hexagon SDK | /home/user/vendor | llama.cpp backends | covered by ENGINE_TAILORING |

## Top 5 overall

| # | idea | gain (estimate) | effort | visual risk |
|---|---|---|---|---|
| **1** | **F1: take the shadow-proxy twins out of the colour pass** (`setCullingMode(FRONT_AND_BACK, NONE)` on every proxy instance) | GPU about 1-3 ms a frame; about 30-45% fewer colour-pass draw calls | **S**, API only | **none** (they write nothing) |
| **2** | **C1: stop shipping a debuggable APK** (the owner plays `app-debug.apk`: CheckJNI on, no profile AOT), then a baseline profile | the Kotlin/JNI part of the 8-10 ms CPU frame: plausibly 20-50% | **S** | none |
| **3** | **F2: cull shadow casters per cascade** (Filament patch: today all 4 cascades draw every caster in the whole shadow range) | shadow-pass draws and vertices down about 50-65%; GPU about 1-2.5 ms; CPU too | M (+F0 once) | none (identical shadow maps) |
| **4** | **F3: draw straight into a 4x MSAA sRGB swap chain**: no intermediate colour buffer, no colour-grading pass | GPU about 0.8-2.5 ms; about 1.5-2 GB/s less DRAM traffic; about 20 MB less memory | S-M, API only | at most about 1-2 LSB; 24-bit depth to check |
| **5** | **F4: stop rewriting every material instance every frame** (`look` into Filament's camera, globals into `setMaterialGlobal`, a 1-row frame texture, `fade` only when it changes) | main-thread CPU: several ms of the render part; MBs of UBO re-uploads a frame | M | none expected (prove with a render diff) |

1, 2 and 4 are API or configuration changes, with no Filament rebuild. 3 is the first change that needs F0 (a
filament-android build from source). Once F0 exists, the binary `ret` patch becomes a normal source patch too.

---

# Filament (priority #1)

What I checked and found **already free** (we pay nothing, so nothing to cut): SSAO, SSR, bloom, DoF, vignette, TAA,
FXAA and dithering are off (FilamentTown.kt:709-716; Renderer.cpp:802-845). Filament's own fog is unused (our haze is
in-shader). The default IBL cubemap is replaced by SH-only `IndirectLight` (FilamentTown.kt:2312). Lamps at intensity 0
are culled before froxelization (View.cpp:1449-1452), so by day the shaders run without the dynamic-lighting variant.
The `destroyMaterialInstance` walk is already off (FilamentTown.kt:707). The other correctness flags
(`assert_material_instance_texture_descriptor_set_compatible`, `assert_native_window_is_valid`) only run at
`setParameter(texture)` or `makeCurrent`: not worth touching. UBO batching for material instances is on by default
(FeatureFlagManager.h:107) and not disabled on Adreno (OpenGLContext.cpp:490/585 is PowerVR and WebGL only).

Correction for docs/HEAT.md §3, which says the cut-out cards have "no alpha-to-coverage". Filament turns A2C on for
**every** `blending: masked` material unless the .mat says otherwise (MaterialDefinition.cpp:542). With MSAA, the
masked alpha is sharpened by `fwidth` and goes through A2C (surface_main.fs:28-35). So today's cut-out edges are A2C
edges. This changes nothing below, but the opaque-variants job should know it.

## F0. Build filament-android from source with our patches (enabler)

**What.** Replace the Maven AAR and the byte patch (app/build.gradle.kts:152-197) with an AAR built from
/home/user/filament-src at v1.81.0, with `kotlin-street/filament/patches/*.patch` applied by the build. The first patch
is the handle-tag `return` that tools/build-filament.sh already applies for the desktop. Then the desktop and the phone
run the same patched source.
**Why.** F2, F6b and F9 below are source changes. The binary patch only works for one SHA-256.
**Effort.** M: `build.sh -p android -q arm64-v8a release` (NDK, host matc and resgen), about an hour of build on the
4-core server, plus a Gradle task that checks the patches' stamp the way the llama.cpp shadow tree does
(app/src/main/cpp/CMakeLists.txt). **Risk.** None if FilamentHandleTagTest and the render goldens pass.

## F1. Shadow proxies are drawn in the colour pass: cancel them there (TOP 1)

**What the source says.** Every caster in our town is two renderables: the visible one (`castShadows(false)`) and a
twin with `shadow_proxy.mat` (`castShadows(true)`, `colorWrite: false`, `depthWrite: false`, masked, `culling: none`).
Sites: BuildingMeshes.kt:513-516, PropLayer.kt:251-253, FilamentModels.kt:1410-1419, TownBike.kt:341-343,
TownPeople.kt:91-93, FilamentStreet.kt:905-907/960-962. Filament puts a renderable into the colour pass when it is in a
visible layer and in the frustum, whether or not it casts shadows (View.cpp:1068-1095, `visibleRenderable`). So every
proxy near the view is **also a colour-pass draw**. It is vertex-shaded and binned. Wherever it passes the depth test
(the same surface as its visible twin, GE in reversed Z), its fragment shader runs: an albedo fetch, then a discard,
because `baseColor.a = 0` outside VARIANT_DEPTH (shadow_proxy.mat). It writes no colour and no depth. It is pure cost.

**The fix needs no patch.** RenderPass already drops commands whose instance culls both faces: in the colour pass
`select(mi->getCullingMode() == FRONT_AND_BACK)` turns the key into SENTINEL (RenderPass.cpp:835), and sorting removes
it. The shadow pass uses the instance's *shadow* culling mode instead (RenderPass.cpp:836-839, `useShadowCulling` is
true there, because shadow passes get HAS_SHADOWING from Renderer.cpp:1105/1134). The public API sets the two
separately (MaterialInstance.h:524, Java `setCullingMode(colorPassCullingMode, shadowPassCullingMode)`,
MaterialInstance.java:649). So:

```kotlin
proxyMat.createInstance().also { it.setCullingMode(Material.CullingMode.FRONT_AND_BACK, Material.CullingMode.NONE) ... }
```

Do this in one helper that is used at the seven sites (NONE keeps today's `culling: none` in the shadow map).

**Why it fits us.** We have tens of thousands of renderables, and about a third to a half of them are twins (estimate
from the code: every casting batch, building, model, bike part and person has one).
**Gain (estimate).** GPU: the binning and vertex work of every proxy triangle in view goes, and so does one cheap
fragment invocation per covered pixel of every caster (roughly doubled fragment *count* on buildings today). That is
about 10-20% of the colour pass, about 1-3 ms of the 17-20 ms. CPU/driver: about 30-45% fewer colour-pass draw calls,
each a descriptor-set and UBO-offset bind on the GL thread. Command generation and sorting for the twins stay (the cost
of a cancelled key). F6b removes those too.
**Risk / proof.** None by construction: the cancelled draws wrote nothing. Proof: desktop goldens (downtown, a yard, a
farm, the bike) byte-identical, with shadows present. On the S25: the play log's draw count and GPU p50 in the same spot.
**Effort.** S (one helper, seven call sites).

## F2. Per-cascade caster culling (Filament patch, TOP 3)

**What the source says.** Directional casters are culled once, against a light frustum that covers the **whole**
shadowed view (ShadowMapManager.cpp:1029-1036, the `VISIBLE_DIR_SHADOW_RENDERABLE` bit). Then every cascade's pass
gets the same range and the same mask (ShadowMapManager.cpp:396-409). The execute loop has no directional case:
`// we should never be here` (ShadowMapManager.cpp:492-494). So cascade 0, a few metres across at the player, draws
every caster out to `shadowFar`, and so do cascades 1 and 2. We run 4 cascades (TownQuality.cascades).

**Patch.** In the per-pass loop, for DIRECTIONAL: cull `entry.range` against `shadowMap.getCamera().getCullingFrustum()`
into a free visibility bit (bit 3; ShadowMap.h:62-64 use 0-2; `Culler::intersects` clears and sets only that bit,
Culler.cpp:156-159). OR in `!visibility.culling` so that non-culled renderables keep drawing. Set
`entry.visibilityMask` to that bit. Use only the cascade box's four side planes, because the near and far planes are
already widened to the casters (`updateSceneInfoDirectional`), and with depth clamp off we must not drop casters
between the light and the box. Cost: one SIMD cull per cascade (Filament's own comment: about 100 µs per 4000 on a
Pixel 4, View.cpp:1413-1417).
**Gain (estimate).** If the cascades see about 5/15/40/100% of the casters, the shadow passes draw about 1.6x the caster
set instead of 4x: about 60% fewer shadow draws and vertices, about 1-2.5 ms GPU, plus the matching CPU command
generation and GL calls. It stacks with the opaque proxies and with F1.
**Risk / proof.** None if correct: shadow maps byte-identical. Proof: dump the four layers on the desktop before and
after (`engine.debug.shadowmap.display_shadow_texture`), plus goldens. **Effort.** M (about 40 lines + F0).

## F3. No intermediate buffer: render into a 4x MSAA sRGB swap chain (TOP 4)

**What the source says.** With view MSAA on, the subpass colour-grading path is impossible (`isSubpassPossible` needs
`msaaSampleCount <= 1`, Renderer.cpp:865-868). So each frame: the colour pass goes into a frame-graph texture
(`hdrColorBuffer` defaults to HIGH, Options.h:433, which is **RGB16F**, Renderer.cpp:106, renderable on Adreno through
EXT_color_buffer_half_float, OpenGLDriver.cpp:3052-3059) and a 4x **DEPTH32F** (RendererUtils.cpp:130-134), resolved
in tile memory through EXT_multisampled_render_to_texture (OpenGLDriver.cpp:1874-1894) and **stored to DRAM**. Then a
full-screen colour-grading pass reads it, samples the 3D LUT, and writes the swap chain. The viewport is padded to a
multiple of 16 (Renderer.cpp:920-930). Tile footprint: 4x(8 + 4) = 48 B/px.

Filament already supports the cheap path. `SwapChain::CONFIG_MSAA_4_SAMPLES` (SwapChain.h:253) makes the EGL window
multisampled (PlatformEGL.cpp:482-485). With post-processing off and view MSAA off, the colour pass writes the window
directly: the final-blit test only blits for MSAA mismatch, a translucent blend or guard bands (Renderer.cpp:1743-1758).
`CONFIG_SRGB_COLORSPACE` (PlatformEGL.cpp:795-800) gives the hardware sRGB encode that the grading pass does today
(our tone mapper is `LinearToneMapper`, a clamp, FilamentTown.kt:330).

**Change (our code).** FilamentLayer.kt:475: add `CONFIG_MSAA_4_SAMPLES | CONFIG_SRGB_COLORSPACE` for the town's
opaque swap chain. FilamentTown: `view.isPostProcessingEnabled = false`, view MSAA off. When `TownQuality.autoSteps`
drops below 1 (FSR needs post-processing), switch post-processing (and view MSAA) back on for as long as the step
lasts. MSAA off in Settings means a swap chain without the flag (it is rebuilt on change).
**Gain (estimate).** It removes the store of a 2352x1088 RGB16F buffer (about 20 MB written), the grading pass's read
and LUT sampling (about 20 MB read, about 2.5 MP shaded) and the extra RGBA8 write. Net about 30 MB a frame, about
1.8 GB/s at 60 fps. The tile footprint falls from 48 to 32 B/px, so there are about 1/3 fewer bins, and every draw is
replayed per bin on Adreno. About 0.8-2.5 ms GPU, plus heat. Memory: about 20 MB of frame-graph textures freed.
**Risk / proof.** (a) Colour: the exact hardware sRGB encode replaces the LUT's interpolated OETF. Expect at most about
1-2 LSB differences (closer to the true value). Proof: desktop renders, max per-channel diff, with the contact sheet.
(b) Depth: the EGL window's depth is 24-bit UNORM (`EGL_DEPTH_SIZE 24`, PlatformEGL.cpp:450-456), not D32F. With our
reversed infinite n/w depth (LookMatrix.pinhole, n about 20), a depth step at distance w is about w²/(n·2^24): 0.03
units at 3,000, 1.7 units at 24,000 (the far boxes). Check the far skyline, road decals at range and the far-box
crossover in renders before relying on it. If it fails, F3b is the fallback.
**F3b (fallback, F0 patch).** Keep post-processing but let `hdrColorBuffer = LOW` mean **SRGB8_A8** for opaque views
(Renderer.cpp:241-255). That is 4 B/px, sRGB-precise in the darks, and clamps at 1 as the linear tone mapper does. It
halves the stored buffer and the grading read, keeps D32F, and is within about 1 LSB. About 40% of F3's gain.
**Effort.** S-M (swap-chain flags, a post-processing toggle tied to the resolution steps, goldens).

## F4. Per-frame material-instance churn: move `look` and the global values out of the instances (TOP 5)

**What the code does.** For every visible building, every frame, for every one of its material instances:
`look` (mat4), `SeeThrough.apply` (3-5 params), `cell`, `fade`, `glow`, `glass` (FilamentTown.kt:1662-1668). The same
holds for every PropLayer batch (PropLayer.kt:316-319), people, bike, farms and WorldNear. Each Java `setParameter(String
...)` does `GetStringUTFChars`/`Release` plus a name lookup (filament-android/src/main/cpp/MaterialInstance.cpp:66-146,
MaterialInstance.cpp:85-124), and it marks the instance dirty. Filament then re-uploads every dirty instance's whole
UBO (UboManager, a new slot a frame).

**Why most of it is redundant.** `look` is the open world's single pinhole matrix (LookMatrix.pinhole), and the
Filament camera is already set to the same frustum (FilamentTown.kt:2251-2252). Filament renders with an infinite-far
projection like ours (Camera.cpp:72-73, 164-165). The see-through cone, crowns, glow and glass are the same for all
instances in a frame.
**Change.** (1) Drop `vertexDomain: device` and `look` from street_lit, street_albedo and terrain (object domain;
Filament's camera-relative world also gives better float precision). (2) Put the 4 most-read per-frame vec4s in
`View.setMaterialGlobal(0..3)` (View.java:1332, `getMaterialGlobal0..3()` in common_getters.glsl:174+): glow, glass +
cell, seeK, seeA. (3) Put the rest (seeB, seeCrowns[4], seeCrownK) in a 16x1 RGBA32F "frame" texture that every
instance binds once at creation and that is updated once a frame. Read it with `texelFetch` only inside the
`seeK.z > 0` branch. (4) Set `fade` and `seeCut` only when they change (track the last value per slot or batch).
**Gain (estimate).** The steady-state per-frame `setParameter` calls go from about 8-12 per visible instance (1-3k
instances: about 10k-35k JNI calls) to roughly zero, and the dirty-UBO re-uploads (about 0.5-2 MB a frame) go with
them. It is plausibly several ms of the 8-10 ms CPU render part; measure with a Perfetto trace before and after.
Cooler big cores help the GPU's thermal budget too.
**Risk / proof.** The matrices are the same to float rounding. Coplanar ties could flip in a few pixels, so prove it
with goldens (expect identical or a handful of edge pixels). The desktop's y-flip (`LookMatrix.windowY`) becomes
Filament's own per-backend clip handling: check one desktop and one phone frame. **Effort.** M (3 materials, about 12
call sites, SeeThrough).

## F5. Froxel depth range for our units (night)

**What.** The froxel grid's slices span 5 to 100 **scene units** by default (Froxelizer.cpp:90-91). Our unit is about
5 cm, so everything past 5 m real distance falls into the last slice. That covers nearly the whole street view, so at
night each fragment walks every light that overlaps that one giant slice. Our lights reach 260 (lamps,
FilamentTown.kt:2559) and 900 (headlights, TownCars.kt:278).
**Change.** `view.setDynamicLightingOptions(zLightNear ≈ camera near (20-40), zLightFar ≈ 3000)` (View.h:612).
**Gain (estimate).** Night only: fewer lights per fragment in lamp-lit streets. About 0.3-1.5 ms GPU in dense night
views. **Risk.** None: froxel membership only pre-filters lights whose falloff window is already zero outside their
radius. Proof: night goldens identical (or at most 1 LSB from float order). **Effort.** S.

## F6. Fewer renderables in the per-frame scene work

**What the source says.** Every frame `FScene::prepare` copies 15 SoA columns of **every** renderable in the scene
(Scene.cpp:580-605), including those whose layer mask is 0. View culls all of them and then `std::partition`s all
23 columns three times (View.cpp:870-910). We hide a lot through `setLayerMask(…, 0)`: near/far twins, far cells, the
proxy twins out of reach (FilamentTown.kt:1654-1658, PropLayer.kt:310-314, TownFarms.kt:672-674, WorldNear.kt:192).
**F6a (our code).** Use `scene.removeEntity`/`addEntity` for things hidden for longer than a moment (LOD swaps,
streamed cells past the hysteresis, night-only beams). Keep layer masks for per-frame toggles. Gain (estimate): in
proportion to the share of the 25-40k that are hidden, about 0.5-2 ms CPU. Risk: none (scene churn costs a cache
update per entity, so not every frame). Effort: S-M.
**F6b (F0 patch).** A "shadow-caster only" bit (a Builder flag plus JNI) that `computeVisibilityMasks` uses to keep the
twins out of `VISIBLE_RENDERABLE`. That removes F1's leftover command generation and sort entries for them.
Effort: S after F0. Gain: small CPU.

## F7. Measure the Vulkan backend (A/B, not a commitment)

Our materials are already compiled for Vulkan (`-a vulkan`, tools/build-materials.sh). Filament's Vulkan backend has
transient (lazily allocated) MSAA and depth attachments, explicit load/store, and a pipeline cache (feature flags
`backend.vulkan.*`, FeatureFlagManager.h:82-96). Try `Engine.Builder().backend(Engine.Backend.VULKAN)` behind a
Settings switch and compare GPU p50/p95 and the driver thread's CPU in the same spots. Gain unknown (CPU submission
most likely; GPU about the same as F3). Risk: Adreno driver and backend bugs; the desktop goldens are GL only.
Effort: S to try, M to make solid.

## F8. Small, free, identical

- **2-band SH in the materials.** We give 2 bands (`irradiance(2, sh)`, FilamentTown.kt:2312), but materials evaluate
  3 by default (Material.cpp:92). Use `Material.Builder().sphericalHarmonicsBandCount(2)` at load. That saves a few ALU
  per lit fragment. It should be byte-identical (band 3 is zero); prove with a golden.
- **Pre-compile variants at load** (`material.compile(...)`) for street_lit and terrain with the shadow-receiver and
  dynamic-lighting variants, so the first night or the first headlight does not compile a program in a frame (a hitch,
  not an average).
- **JobSystem threads.** Filament makes `hardware_concurrency - 1` = 7 workers (JobSystem.cpp:192-203), next to 6 voice
  threads and the AI. Try `jobSystemThreadCount = 3-4` (TownEngine.config) and read the frame-time p95. Measure only.

## F9. Later, opt-in: refresh the far cascade every other frame (L, not identical)

Keep the shadow-map array persistent (today it is a frame-graph transient, ShadowMapManager.cpp:383-389) and re-render
cascade 3 only every 2nd frame while the sun and camera move less than a texel (`stable` cascades already snap). About
25-40% of what is left of the shadow cost after F2. A far car's shadow lags one frame, so it is not identical: make it
a quality setting, if ever.

## Dropped (do not pay, or change pixels)

- `hdrColorBuffer = MEDIUM` (R11G11B10F): 6-bit mantissa, banding risk on the sky; F3/F3b are better.
- PCF `ShadowSamplingQuality.HARD` (1 tap instead of 3x3, Material.h:134-137): visibly harder shadows. Owner's taste,
  not a free saving.
- Turning A2C off: changes cut-out edges.
- Cached or static command buffers for 30k static renderables: L-sized engine surgery. F1 + F2 + F6 remove most of
  that work more cheaply.
- Uploading only changed rows of the per-renderable UBO (View.cpp:1150-1180): rows move every frame with the
  partition, so it needs a redesign; small next to F4.
- Engine debug flags beyond the one already off: microseconds.

---

# ONNX Runtime (Kokoro-82M, Supertonic)

Lines are cached as WAVs (VoiceBank), so ORT runs only for new lines, but those runs happen in play, on 6 threads
(NpcVoiceService.kt:27), next to the renderer.

| # | idea | where | gain (estimate) | risk / proof | effort |
|---|---|---|---|---|---|
| O1 | **No spin-waiting**: `addConfigEntry("session.intra_op.allow_spinning","0")` and the inter-op one | KokoroTts.kt:51-56, SupertonicTts.kt:70-75 | ORT's default spinning keeps up to 6 big cores busy between ops: less heat and fewer CPU frame spikes while a line is made | none: same numbers (compare the WAV bytes) | S |
| O2 | **Give the arena back after each line**: RunOptions `memory.enable_memory_arena_shrinkage = "cpu:0"` | the `run(...)` calls | the arena holds peak activations between lines (Supertonic's vocoder), tens to a hundred-plus MB | none (identical WAV) | S |
| O3 | **Save the optimised graph** (`setOptimizedModelFilePath` once, then load the .ort/optimised file) | session creation | shorter loads of 5 sessions (ALL_OPT re-optimises at every load) | none (identical WAV) | S |
| O4 | Threads: try 4 intra-op threads in play, 6 only while idle or charging | NpcVoiceService.kt:27 | less contention with Filament's 7 workers; the time per line is measured | none | S |
| O5 | QNN (Hexagon) EP via `onnxruntime-android-qnn`, fp16, length buckets | build.gradle + sessions | voices off the CPU; but the NPU belongs to dialogue, and static shapes need padding that may change audio | needs an audio A/B | L, later |

Dropped: an ORT minimal build with only the needed ops (size is not a concern, and the load-time gain is small next to
O3). int8 Kokoro (changes the voice).

# QuickJS-ng (the sim)

The sim is event-driven on its own thread (SimJsThread), not per frame. The costs that exist are JSON marshalling per
call (LoopGame.kt:263-270) and loops of calls.

| # | idea | gain (estimate) | risk | effort |
|---|---|---|---|---|
| Q1 | Batch per-place calls into one (e.g. `restockFor` makes one `econ` call per shop, LoopGame.kt:241-249): one `simCall` with all shops | fewer JSON round trips on day changes; ms-scale on a big town | none (same rules; compare the JSON) | S |
| Q2 | `JS_SetGCThreshold(rt, 32 MB)` after create (sim_js.cpp:87-91); ng starts at 256 KB, then grows 1.5x live (quickjs.c:2335, 1696-1698) | fewer GC passes while the town state builds up | none (no WeakRef or finalizer semantics in the sim) | S |
| Q3 | `-O3` and `-flto` for quickjs and timerpg_simjs (simjs/CMakeLists.txt: -O2) | a few % per call | none | S |

Dropped: bytecode precompilation (sim.js is 267 KB, as NOVEL_OPTIMIZATIONS already says).

# Jolt Physics

Used now: VehicleConstraint, statics, debris (physics_core.cpp). It is built well already: asserts, profiler and
debug renderer off; CROSS_PLATFORM_DETERMINISTIC on (keep it, the host tests rely on it).

| # | idea | gain | risk | effort |
|---|---|---|---|---|
| J1 | Add streamed statics in batches (`AddBodiesPrepare/Finalize`), or call `OptimizeBroadPhase()` after a streamed batch; today it is one `AddBody` at a time (physics_core.cpp:168-170) | a balanced broad-phase tree: cheaper queries and steps after long drives | contacts are sorted for determinism; prove with a physics_host_test replay | S |
| J2 | `INTERPROCEDURAL_OPTIMIZATION ON` (physics/CMakeLists.txt) | a few % of the step | none | S |

# ASR (Qwen3-ASR through mtmd)

The llama.cpp side is covered by ENGINE_TAILORING. One idea specific to us: ASR runs **before** the reply is
generated, so the NPU is idle then. Moving the ASR text model to the Hexagon backend (the CPU stays for the encoder)
takes a burst of CPU heat off every conversation turn. It costs NPU memory next to the dialogue model (MEMORY_AUDIT:
1.7B is 2,059 MB mapped), so it is L and later. `n_gpu_layers = 0` and `use_gpu = false` (asr_core.cpp:52/79) are right
while the GPU is the frame's bottleneck.

# Compose / ART (and Filament's JNI)

| # | idea | gain (estimate) | risk | effort |
|---|---|---|---|---|
| **C1 (TOP 2)** | **The owner's APK is the debug build** (tools/publish-apk.sh:4-8 publishes `app-debug.apk`). Debuggable apps run with **CheckJNI** on (every Filament JNI call is checked, strings and arrays copied and validated), and ART does not apply profile-guided AOT (JIT code kept debuggable). Publish a non-debuggable variant signed with the same key: `release` already uses `signingConfigs.timerpg` (app/build.gradle.kts buildTypes), or set `isDebuggable = false` on a new "owner" type. Keep `OWNER_BUILD` and the native Release flags | the Kotlin frame loop (32k lines in scene3d), the HUD and the tens of thousands of JNI calls a frame: plausibly 20-50% of that CPU time; no GPU change | no code paths change; `adb run-as` stops working (nothing in the repo uses it) | S |
| C2 | Baseline profile (`androidx.profileinstaller` + a generated profile) | every update reinstalls, so every new build starts JIT-cold today; first minutes of play smoother | none | S-M |
| C3 | R8 on that variant (`isMinifyEnabled = true`, keep rules for JNI natives, org.json, Filament's consumer rules) | a further few-to-15% CPU on Kotlin hot paths (inlining, devirtualisation) | missing keep rules crash at once: test one full session | M |

The minimap stutter is another job's. The composite cost is the SurfaceView job's.

---

## How to prove each (summary)

- Byte-identical goldens (desktop `-Dmode=filament-town` shots: downtown, yard, farm, night street, the bike): F1, F2,
  F5, F6, F8, O1-O3 (WAV bytes), Q1-Q3 (sim JSON), J1 (replay), C1-C3 (same output).
- Near-identical with a stated bound: F3 (max |diff| per channel, expect at most 2; far-depth checks), F4 (a handful of
  edge pixels at most).
- On the S25, the same spots before and after: play-log `gpu`/`cpu` p50/p95, draws, `heatLvl`.
