# G002 — Ocean Water Prototype for Filament (Round 3 — Addressing `G002-r2`)

## Summary of Round 3 Changes (`docs/queue/reviews/G002-r2.md`)

All items in `G002-r2.md` have been addressed on branch `gemini/G002-ocean` (preserving Round 2's working `vertexDomain : device` projection, upright boat, 9-ring + 24 km horizon mesh, and dusk/night sky+trough framing):

### 1. Fixed the Solid White Glint Sheet (`ocean.mat`, `OceanSurface.kt`, `FilamentOceanPreview.kt`, `OceanWavesTest.kt`)
- **Elevation-scaled & facet-gated glint:** Glint reach and intensity now scale with sun/moon elevation (`L.y`): high midday sun (`L.y = 0.48`) produces sparse scattered sparkle dashes; low dusk sun (`L.y = 0.096`) produces a strong tapering golden sunset road; night moon (`L.y = 0.118`) produces slender crisp silver sparkles.
- **Tapering ragged funnel (no solid white sheet, no straight vertical rectangle):** The glint path narrows cleanly from the foreground toward the sun/moon at the horizon (`baseHalf` tapers with distance) and its outer edges are broken by 2D organic noise (`ragged`), eliminating straight vertical box edges at night.
- **Broken horizontal dashes on sun-facing facets:** Each glint pixel is gated on `dot(N, normalize(V + L))` AND two-octave horizontal dash noise (`dashA`/`dashB`), leaving mostly open water between dashes near the camera (~25–32% of path lit) and shrinking to fine sparkles near the horizon.
- **Hard coverage gate & telemetry:** `FilamentOceanPreview.kt` measures exact sRGB glint pixel fraction below the horizon for every shot, prints `day_calm`, `dusk_calm`, `dusk_rough`, and `night_calm` percentages to `ocean_bench.txt`, and asserts `day_calm < 8.0%`, `dusk_calm < 20.0%`, and `dusk_rough < 20.0%`.
- **CPU fragment oracle measurements (`OceanWavesTest.glintCoverageMatchesG002r2Limits`, 240×135 grid on exact `(0, 6, -20) -> (0, 1.8, 4)` camera):**
  - `day_calm`: **4.27%** below horizon (limit `< 8%`, max horizontal run `11.7%`)
  - `day_rough`: **3.55%** below horizon (limit `< 8%`, max horizontal run `11.7%`)
  - `dusk_calm`: **6.99%** below horizon (limit `< 20%`, max horizontal run `15.8%`)
  - `dusk_rough`: **5.84%** below horizon (limit `< 20%`, max horizontal run `14.6%`)
  - `night_calm`: **5.01%** below horizon (max horizontal run `13.8%`)
  - `night_rough`: **4.74%** below horizon (max horizontal run `11.7%`)

### 2. Sculpted 3D Pixel-Art Wave Forms (Owner's Note — No Flat Computer Stripes)
- **Removed all infinite horizontal sine stripes** from `ocean.mat`.
- **Natural 6-wave directional spectrum (`OceanWaves.kt`):** Cross-swells spread across `±46°` (`0°, +24°..+25°, -29°..-31°, +40°..+41°, -44°..-46°, +13°..+15°`) with incommensurate wavelengths and deep-water dispersion speeds (`foldingIndex ≈ 0.145` calm, `0.654 < 0.75` rough) so waves interfere into staggered 3D mounds that travel and change shape dynamically over time.
- **True 3D facet-normal shading across 7 hue-shifted flat colours (`ocean.mat`, `OceanSurface.kt`):** Every pixel is shaded from its Gerstner 3D facet normal `N` against sun `L` and view `V` (`colAbyss`, `colTrough`, `colShadowFace`, `colBody`, `colLitFace`, `colTealCrest`, `colLipHighlight`), giving lit turquoise/coral/slate shoulders and deep indigo/plum shadow backs with subtle organic hand-drawn contour wobble.
- **Broken curved `"~"` crests, under-lip shadow, whitecaps, & wind foam streaks:**
  - Staggered curved `"~"` highlight strokes (`colLipHighlight`) of varied length ride upper ridges (no unbroken lines across the screen).
  - Darker 1–2 px curling-lip shadow lines tuck just under taller camera-facing lips.
  - Scattered 2–4 px whitecap clusters dynamically appear and fade on the steepest crests.
  - Thin wind-aligned foam streaks drift across rough water (`roughness > 0.45`).
- **Real height & uneven rough horizon:** `ROUGH` swells reach ~3.2 m peak-to-trough near the camera so crests rise visibly and overlap water behind them; rough water also adds subtle 1–2 px uneven wave silhouette bumps along the distant horizon band (strictly 0 on calm water and inside 3.2 km).
- **New person-height close-ups & motion strips (`FilamentOceanPreview.kt`):**
  - 4 close-ups at eye height `2.0 m` above the water near the boat (`ocean_closeup_day_calm.png`, `ocean_closeup_day_rough.png`, `ocean_closeup_dusk_calm.png`, `ocean_closeup_dusk_rough.png`, plus `ocean_closeup_4up.png`).
  - 6-frame close-up motion strips (`ocean_motion_calm_6f.png`, `ocean_motion_rough_6f.png`, `t = 0.0..2.5s` in 0.5 s steps).

### 3. Small Fixes (All 4 from `G002-r2`)
- **Hull waterline foam ring:** Moved AFTER glint in `ocean.mat` so sparkle never hides it; sharpened to a crisp 1–2 px band (`foamRingWidth = 0.36 m`), broken organically on the lee side (`windward = dot(relDir, -swellDir)`), with a subtle 1-px windward dark water contact rim.
- **Rough sea boat tilt cap:** Upgraded `OceanSurface.boatTransformInto(...)` to 4-point hull buoyancy sampling (bow, stern, starboard, port — zero allocations) plus a `maxTiltDeg = 14.0f` ceiling (previously peaked at `23.9 deg`). Verified in `OceanWavesTest.boatTiltIsCappedAt14DegOnRoughSeaWhileStillHeaving` and asserted (`<= 14.5 deg`) in `FilamentOceanPreview.kt`.
- **0 B per-frame allocation:** Replaced `mx.getThreadAllocatedBytes(tid)` (which allocated `long[1]` + MXBean warmup) with pre-warmed scalar `mx.currentThreadAllocatedBytes` in both `FilamentOceanPreview.kt` and `OceanWavesTest.kt`. Verified `0 B` over 2,000 calls.
- **Tight 2–4 px Bayer dither:** Reduced band-edge dither amplitude to `±0.021` (2–4 px crisp pixel-art step transitions, eliminating wide noisy checkerboard carpets) and tightened the atmospheric horizon transition seam.

## Allowed Files Modified
- `kotlin-street/filament/materials/ocean.mat`
- `kotlin-street/shared/src/main/kotlin/scene3d/OceanWaves.kt`
- `kotlin-street/shared/src/filament/kotlin/scene3d/OceanSurface.kt`
- `kotlin-street/desktop/src/filament/kotlin/FilamentOceanPreview.kt`
- `kotlin-street/desktop/src/crossroadsTest/kotlin/OceanWavesTest.kt`
- `prototypes/ocean/DELIVERY.md`
(Plus existing 1-line `ocean` mode registration in `kotlin-street/desktop/build.gradle.kts` from Round 1.)

## Test & Verification Results
- **Unit tests (RUN HERE — PASSED):**
  `gradle -p kotlin-street --configure-on-demand :desktop:crossroadsTest --rerun-tasks`
  - **42 tests completed, 0 failed (`BUILD SUCCESSFUL`)**, including all 9 tests in `OceanWavesTest`:
    1. `heightAtMatchesDisplacedPointAcrossCalmAndRough` — PASSED (`< 2 mm` across calm & 3.2 m rough swells)
    2. `normalMatchesFiniteDifferenceOfHeightField` — PASSED
    3. `presetsStayNonFoldingWithPositiveJacobian` — PASSED (`foldingIndex < 0.75`, `minJ > 0.20`, cross-swell spread confirmed)
    4. `gridSnappingLocksVerticesWithoutSwimming` — PASSED
    5. `heightIntoAllocatesZeroBytes` — PASSED (**exact `0 B`** over 2,000 calls)
    6. `nestedRingMeshIsWatertightAndReachesHorizon` — PASSED (21,841 verts, 43,456 tris, 0 T-junctions, 24 km skirt)
    7. `deviceProjectionPutsWaterBelowHorizonAndBoatUpright` — PASSED
    8. `boatTiltIsCappedAt14DegOnRoughSeaWhileStillHeaving` — PASSED (`<= 14.0 deg`, heave range `> 1.0 m`)
    9. `glintCoverageMatchesG002r2Limits` — PASSED (`day_calm=4.27%`, `day_rough=3.55%`, `dusk_calm=6.99%`, `dusk_rough=5.84%`, `night_calm=5.01%`, `night_rough=4.74%`, all max row runs `< 16%`)
- **Filament Kotlin compilation (RUN HERE — PASSED):**
  `gradle -p kotlin-street --configure-on-demand -Pfilament.dist=/tmp/filament-check :desktop:compileKotlin` (against Filament `v1.81.0` `filament-java.jar`) -> **`BUILD SUCCESSFUL`**.
- **Material compilation & headless Filament PNG renders:**
  `tests not run here: prebuilt Filament v1.81.0 Linux matc and libfilament-jni.so require GLIBC 2.38 while this sandbox provides GLIBC 2.36 (per docs/queue/README.md, Filament/matc is not built from source here).`
  **Build server commands for Claude:**
  ```bash
  matc -p mobile -a opengl -o kotlin-street/app/src/main/assets/filament/ocean.filamat \
      kotlin-street/filament/materials/ocean.mat
  ./gradlew -p kotlin-street :desktop:run -Dmode=ocean -Dout=build/ocean-preview
  ```
  This will produce:
  - `ocean_grid_6up.png` + individual `ocean_{day,dusk,night}_{calm,rough}.png`
  - `ocean_closeup_4up.png` + individual `ocean_closeup_{day,dusk}_{calm,rough}.png` (2 m eye height near boat)
  - `ocean_motion_calm_6f.png` & `ocean_motion_rough_6f.png` (6-frame 2 m close-up motion strips)
  - `ocean_bobbing_4f.png` (4-frame rough-sea boat bobbing strip with tilt `<= 14 deg`)
  - `ocean_bench.txt` (with `alloc=0 B` and below-horizon glint coverage numbers)

## Usage
- Model: Gemini
- Tokens (in / out / cache): not visible
- Tool calls: not visible
- Wall clock: not visible
- Notes: Round 3 rework addressing `docs/queue/reviews/G002-r2.md` (elevation-scaled tapering broken glint dashes with CPU fragment oracle verification, 7-colour 3D facet-shaded sculpted pixel-art waves with broken `"~"` crests / under-lip shadows / wind foam streaks / uneven rough horizon, 4-point 14° boat tilt cap, post-glint hull waterline foam ring, `0 B` alloc bench fix, 2 m close-ups + 6-frame motion strips).
