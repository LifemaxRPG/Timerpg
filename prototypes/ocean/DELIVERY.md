# Delivery: G002 — Ocean water prototype for Filament (Revision 1)

- **Task ID:** `G002`
- **Branch:** `gemini/G002-ocean`
- **Assignee:** `gemini`
- **Answers review:** `docs/queue/reviews/G002-r1.md`

## What changed in Revision 1 (answering `G002-r1.md`)

1. **Fixed upside-down ocean projection relative to boat and camera (`G002-r1` #1 — MUST):**
   - **Root cause:** In round 1, `ocean.mat` used `vertexDomain : object` (projected by Filament's `Camera.setProjection`, which puts `dot(up, P - eye) < 0` into negative `clip.y`), whereas `street_lit.filamat` (the boat) used `vertexDomain : device` with `LookMatrix` (`windowY = false` on headless `CONFIG_READABLE` desktop, flipping `clip.y` so negative `clip.y` maps to the top of the readback PNG). That inverted the ocean vertically against the upright boat, placing the water in the top half and leaving flat sky at the bottom.
   - **Fix:** Switched `ocean.mat` to `vertexDomain : device` with `materialParams.look` (the exact same projection and reversed-Z depth convention as `street_lit.mat`, `street_albedo.mat`, and `LookMatrix`, honoring `LookMatrix.windowY` on both headless desktop and Android `TextureView`). In `materialVertex`, `ocean.mat` shifts `localPos -> displacedWorld` via `displaceMat` and outputs `material.clipSpaceTransform = materialParams.look * displaceMat`, so the ocean and the boat go through the **exact same clip matrix** and share bit-for-bit identical Y orientation and reversed-Z depth (`D = near / w`).
   - **Verification camera & runtime guard:** Updated `FilamentOceanPreview.kt` to place the camera at `(0, 6.0, -20.0)` — **6 m up and 20 m behind** the boat at `(0, y, 0)` — looking slightly down at `(0, 1.8, 4.0)` (`fovY = 42°`). Added a runtime alpha-distribution assertion in `FilamentOceanPreview.kt` (`topOpaque < 15%`, `botOpaque > 95%`) plus a new unit test (`OceanWavesTest.deviceProjectionPutsWaterBelowHorizonAndBoatUpright`) verifying exact row placement and ocean-vs-boat clip parity.
   - **Expected render appearance:** The stepped pixel-art sky fills the upper ~27% of the frame (with low sunset sun at dusk and crescent/full moon + 2×2 stars at night), meeting a crisp horizon line at ~27% from the top. Ocean water fills the entire lower ~73% of the frame from the horizon straight down to the bottom edge, directly under and in front of the upright boat (boat waterline around ~65% down from top, mast rising to ~41%).

2. **Full coverage to the horizon with nested distance rings + far horizon skirt (`G002-r1` #2):**
   - Replaced the fixed 320 m grid with a watertight conformal nested-ring mesh (`OceanGridMesh` in `OceanWaves.kt`, uploaded once in `OceanSurface.kt`) in **1 single draw call** (`21,841` vertices in 16-bit `USHORT`, `43,456` triangles — fewer triangles than round 1 while extending 75× farther):
     - **9 concentric wave rings** (`ringCells = 56`, `baseCellSize = 1.0 m` core doubling to `2, 4, 8, 16, 32, 64, 128, 256 m`) reaching **`±7,168 m` (7.17 km)** in every direction, with exact 3-triangle border fans + 4-triangle L-corners eliminating all T-junctions across every ring seam.
     - **Watertight far horizon skirt** extending the outermost ring perimeter seamlessly to **`±24,000 m` (24 km)** in the calm flat horizon sea band.
     - **Distance & Nyquist wave fade** in `ocean.mat`: full unattenuated Gerstner amplitude within `40 m` of the camera/boat (preserving exact CPU-to-GPU parity), smooth Nyquist attenuation of shorter wavelengths on coarser outer rings, and smooth overall amplitude fade to `0` over `140 m .. 2,200 m`.
     - **Grid snapping** preserved (`snapStep = 8.0 m`, an exact integer multiple of Rings 0..3 out to `±224 m`).
   - **Expected render appearance:** Unbroken ocean from the very bottom edge of the frame (`~10 m` ahead of the camera, in front of the boat) all the way to a crisp, level, gap-free horizon line with zero seam cracks or distant wave aliasing.

3. **True 16-bit pixel-art sea lines instead of cloud-like blobs (`G002-r1` #3):**
   - **Aligned wave trains (`OceanWaves.kt`):** Re-tuned all 6 waves in `CALM` (`2.6..13.5 m` wavelengths) and `ROUGH` (`3.1..18.0 m` wavelengths) within `±20°` of a coherent main swell axis `(0, -1)` so crests form long parallel horizontal swell lines perpendicular to the wave travel direction rather than isotropic blobs (`CALM foldingIndex = 0.105`, `ROUGH foldingIndex = 0.579`).
   - **16-bit wave-shaped banding (`ocean.mat`):** Rebuilt the fragment band signal around wave elevation + camera-facing swell slope + fine parallel horizontal ripple phase (few metres wide near the camera), mapped cleanly to 4 flat stepped colours: dark parallel troughs (`bandDeep`), main sea body (`bandMid`), lighter lit wave faces (`bandLight`), and thin crisp crest highlights (`bandCrest`).
   - **Calm atmospheric distance band & 16-bit sparkle:** Fades via a crisp Bayer-dithered transition (`55 m .. 520 m`) into a single calm flat distant sea colour (`horizonSeaColor`) toward the horizon, with 16-bit horizontal sun/moon sparkle dashes along the light reflection path and Bayer-dithered white crest foam that fades with distance so the horizon stays serene.
   - **Expected render appearance:** Clearly legible horizontal 16-bit sea bands (few metres wide near the boat, compressing into crisp horizontal scanlines toward the calm flat distant sea band), with shimmering warm-white sun sparkles by day, molten gold sunset ribbon over dark indigo-violet troughs at dusk, and crisp silver moon sparkles across dark midnight-navy water at night.

4. **Boat sitting in the water with a thin waterline foam ring (`G002-r1` #4):**
   - Lowered default `draftOffsetY` in `OceanSurface.boatTransformInto` from `0.25 m` to `0.05 m` so the boat's dark keel sits submerged beneath the surface and the cedar hull rides right at the waterline, clipping accurately against foreground waves in the shared reversed-Z depth buffer.
   - Added `OceanSurface.setHullFoam(...)` (`hullPose` + `hullShape` uniforms in `ocean.mat`) drawing a thin, tapered, Bayer-dithered white pixel-art waterline foam ring hugging the hull's exact footprint and breathing subtly with the local wave crest/Jacobian (`crest`).
   - **Expected render appearance:** The wooden box boat sits naturally immersed in the waves with a crisp thin white pixel-art foam ring tracing the hull's contact line on the water surface as it heaves, pitches, and rolls across the 4-frame bobbing strip.

## Files changed (strict G002 scope — zero town code touched)

- `kotlin-street/filament/materials/ocean.mat`
- `kotlin-street/shared/src/main/kotlin/scene3d/OceanWaves.kt`
- `kotlin-street/shared/src/filament/kotlin/scene3d/OceanSurface.kt`
- `kotlin-street/desktop/src/filament/kotlin/FilamentOceanPreview.kt`
- `kotlin-street/desktop/src/crossroadsTest/kotlin/OceanWavesTest.kt`
- `kotlin-street/desktop/build.gradle.kts` (1 line from round 1 registering `-Dmode=ocean`)
- `prototypes/ocean/DELIVERY.md`

## Tests & verification

### Ran here
- **Plain-JVM test suite (`gradle --configure-on-demand :desktop:crossroadsTest --rerun-tasks`):** **PASSED (40/40 tests, 0 failures, 0 errors, 0 skipped)**
  - `OceanWavesTest`: **7/7 passed** (`0.379 s`)
    - `cpuMatchesShaderFormulaAtSamplePoints` — PASS (exact near-field parity `< 1e-4` + far-field flatten to `0`)
    - `heightInvertsHorizontalGerstnerShiftForBuoyancy` — PASS (`< 5 mm` residual, normal dot `> 0.997`)
    - `presetsStayNonFoldingWithPositiveJacobian` — PASS (`foldingIndex < 0.75`, positive Jacobian, aligned swell directions)
    - `gridSnappingLocksVerticesWithoutSwimming` — PASS
    - `heightIntoAllocatesZeroBytes` — PASS
    - `nestedRingMeshIsWatertightAndReachesHorizon` — PASS (`21,841` verts `<= 65535`, `7,168 m` wave radius, `24,000 m` horizon skirt, **0 T-junctions / 0 non-manifold edges** across all 9 rings + skirt)
    - `deviceProjectionPutsWaterBelowHorizonAndBoatUpright` — PASS (verifies `6 m` up / `20 m` behind camera places horizon in upper ~27%, mast upright above hull, near water filling to bottom edge, and bit-for-bit ocean/boat clip parity)
  - Existing suite regression check: `CornerWalkTest` (13/13), `CrossroadsTest` (5/5), `StageCameraTest` (4/4), `TalkByVoiceTest` (11/11) — all PASS.
- **Filament Kotlin compile check (`gradle --configure-on-demand -Pfilament.dist=<filament-1.81.0-stubs> :desktop:compileKotlin`):** **PASSED (`BUILD SUCCESSFUL`)**
  - Verified `OceanSurface.kt` and `FilamentOceanPreview.kt` compile cleanly against `filament-android:1.81.0` `classes.jar` (0 errors, 0 warnings in `OceanSurface.kt`).
- **Measured mesh & CPU cost (OpenJDK 21):**
  - Mesh: `21,841` verts, `43,456` tris, `1` draw call, `0 B/frame` buffer uploads (reaches `7.17 km` waves + `24 km` flat horizon skirt).
  - `OceanWaves.heightInto` (6 Gerstner waves, 4-iteration horizontal inversion): **1.08 µs / query**, **0 B / query** allocation (~4.3 µs/frame for 4 Jolt hull buoyancy points).

### Not run here
- **tests not run here:** `matc` compilation (`ocean.mat` -> `ocean.filamat`) and headless Filament desktop preview (`gradle :desktop:preview -Dmode=ocean` PNG renders and llvmpipe frame timing) were not run here because prebuilt Filament `v1.81.0` Linux binaries require `GLIBC 2.38` while this environment has `GLIBC 2.36`, and per `docs/queue/README.md` + `G002-r1.md` we do not build `matc`/Filament from source and do not commit `ocean.filamat`.
- **Commands for Claude's build server:**
  ```bash
  matc -p mobile -a opengl \
    -o kotlin-street/app/src/main/assets/filament/ocean.filamat \
    kotlin-street/filament/materials/ocean.mat

  gradle -p kotlin-street :desktop:preview -Dmode=ocean
  gradle -p kotlin-street :desktop:crossroadsTest --tests OceanWavesTest
  ```

## Anything not done

- `ocean.filamat` and desktop preview PNGs (`ocean_day_calm.png`, `ocean_day_rough.png`, `ocean_dusk_calm.png`, `ocean_dusk_rough.png`, `ocean_night_calm.png`, `ocean_night_rough.png`, `ocean_grid_6up.png`, `ocean_boat_bob_strip.png`, `ocean_bench.txt`) await `matc` + `-Dmode=ocean` execution on the build server as instructed in `G002-r1.md`.

## Usage

- **Model / setting:** Gemini (AI Studio Build agent; exact model setting not visible in runtime)
- **Tokens:** not visible
- **Effort:** ~14 tool calls across 1 revision pass (~14 minutes wall-clock from fetch to push)
- **Plan usage:** not visible
- **Rework:** Revision 1 (round 2 on G002, answering `docs/queue/reviews/G002-r1.md`)
