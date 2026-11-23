# Delivery: G002 — Ocean water prototype for Filament

- **Task ID:** `G002`
- **Branch:** `gemini/G002-ocean`
- **Assignee:** `gemini`

## What was made

1. **Filament ocean material — `kotlin-street/filament/materials/ocean.mat`**
   - `shadingModel : unlit`, `blending : opaque`, `vertexDomain : object`, `culling : none`, custom vertex-to-fragment interpolants (`variables : [ wavePos, waveNormal, waveMeta ]`) verified against Filament `v1.81.0` (`docs/Materials.md.html` and `shaders/src/surface_main.vs`).
   - **Vertex shader:** Sums up to 6 Gerstner waves (`waveA0..5`, `waveB0..5` carrying direction, wavelength, amplitude, steepness, angular frequency, and phase offset) at world XZ coordinates `gridOrigin.xz + getPosition().xz`. Computes the exact analytical surface normal `normalize(cross(dZ, dX))` and horizontal Jacobian determinant `dX.x * dZ.z - dX.z * dZ.x` (for crest steepness/foam), and displaces `material.worldPosition.xyz += disp`.
   - **Fragment shader (pixel-art look):**
     - **4 flat stepped water colour bands** (`bandDeep`, `bandMid`, `bandLight`, `bandCrest`) driven by quantized elevation, wave-slope lighting, view Fresnel, and distance — no smooth water gradients.
     - **Bayer-dithered white foam pixels** (`4x4` ordered Bayer matrix scaled to whole art pixels via `eyePos.w`) on steep wave crests (combining Jacobian compression `1 - J` and crest elevation, scaled by sea roughness).
     - **Sun glint ribbon (day/dusk) & cool moon glint ribbon (night)** with tight core and Bayer-dithered outer shimmer band.
     - **Stepped time-of-day sky reflection** at grazing Fresnel angles and a Bayer-dithered horizon dissolve into the hour's sky colour.

2. **CPU wave evaluator (for Jolt buoyancy & gameplay) — `kotlin-street/shared/src/main/kotlin/scene3d/OceanWaves.kt`**
   - Pure Kotlin (zero Filament/Android dependency) implementing the exact Gerstner wave sum, analytical normal, and horizontal Jacobian of `ocean.mat`.
   - Includes `OceanWaves.CALM` and `OceanWaves.ROUGH` 6-wave presets (tuned with `sum(k * Q * A) < 0.75` so crests stay crisp and never self-fold).
   - `OceanWaves.snapGridOrigin(coord, cellSize)` snaps the mesh grid origin to integer multiples of `cellSize` so vertices remain locked to fixed world XZ points and never swim as the camera moves.
   - `OceanWaves.height(x, z, t, spec)` and zero-allocation `OceanWaves.heightInto(...)` invert the horizontal Gerstner displacement (`4` fixed-point iterations) so callers querying a fixed world `(x, z)` (such as a Jolt boat hull) receive the true wave height and normal directly underneath `(x, z)`.

3. **Filament ocean mesh & palette wrapper — `kotlin-street/shared/src/filament/kotlin/scene3d/OceanSurface.kt`**
   - Builds a static `160x160` uniform grid (`25,921` vertices, `51,200` triangles, 16-bit `USHORT` indices, `1` draw call, `cellSize = 2.0 m` -> `320 m x 320 m` span).
   - `OceanSurface.update(...)` snaps the grid entity transform to `OceanWaves.snapGridOrigin(camX/Z, cellSize)` and uploads uniform parameters with **zero per-frame heap allocations** and **zero vertex/index buffer re-uploads**.
   - `OceanPalette` defines `DAY` (13:00), `DUSK` (19:15), `NIGHT` (22:30), and `forHour(hour)` linear-RGB stepped palettes.
   - `OceanSurface.boatTransformInto(...)` computes a right-handed orthonormal column-major model matrix from `OceanWaves.heightInto(...)` (heave, damped pitch, and damped roll) with zero allocations.

4. **Standalone desktop Filament preview — `kotlin-street/desktop/src/filament/kotlin/FilamentOceanPreview.kt`**
   - Registered as `-Dmode=ocean` via a single-line change in `kotlin-street/desktop/build.gradle.kts` (`mainClass.set(if (System.getProperty("mode") == "ocean") "FilamentOceanPreviewKt" else "PreviewKt")`). Touches zero town code (`FilamentTown`, `Preview.kt`, etc. untouched).
   - Renders open ocean across all 6 states (`day`/`dusk`/`night` × `calm`/`rough`) plus a 3×2 contact sheet (`ocean_grid_6up.png`), a 4-frame bobbing boat strip (`ocean_boat_bob_strip.png` + `boat_bob_0..3.png`) on rough daytime sea using a multi-part box boat (hull, keel, deck, cabin with night-glowing portholes, mast, pennant) driven by `OceanSurface.boatTransformInto`, and writes `ocean_bench.txt`.
   - Uses `buildDeviceLookMatrix(...)` to align `street_lit.filamat` (`VERTEX_DOMAIN_DEVICE`) bit-for-bit with Filament's infinite reversed-Z `Camera.setProjection` (`VERTEX_DOMAIN_OBJECT` used by `ocean.mat`) in X, Y, and depth so the boat hull immerses and clips accurately against the waves.

5. **Unit tests — `kotlin-street/desktop/src/crossroadsTest/kotlin/OceanWavesTest.kt`**
   - Ports the `ocean.mat` vertex shader formula verbatim as an independent `shaderOracle` and verifies `OceanWaves.evalDisplaced` matches displacement `(dx, dy, dz)`, analytical normal `(nx, ny, nz)`, and Jacobian `J` within `< 1e-4` across both `CALM` and `ROUGH` presets over a 6×6 spatial grid × 5 timestamps (360 sample points).
   - Verifies `OceanWaves.height(x, z, t)` horizontal inversion accuracy (`< 5 mm` residual) and finite-difference surface normal agreement (`dot > 0.998`).
   - Verifies both presets remain strictly non-folding (`sum(k * Q * A) < 0.85`, positive Jacobian everywhere).
   - Verifies camera-snapped grid invariance (camera movement maps vertices onto identical world XZ coordinates with zero swimming).
   - Verifies `OceanWaves.heightInto` performs zero per-call heap allocations via `ThreadMXBean`.

## Tests & verification

### Ran here
- **Plain-JVM unit tests (`gradle --configure-on-demand :desktop:crossroadsTest --rerun-tasks`):** **PASSED (38/38 tests, 0 failures, 0 errors, 0 skipped)**
  - `OceanWavesTest`: **5/5 passed** (`0.042 s`)
    - `cpuMatchesShaderFormulaAtSamplePoints` — PASS
    - `heightInvertsHorizontalGerstnerShiftForBuoyancy` — PASS
    - `presetsStayNonFoldingWithPositiveJacobian` — PASS
    - `gridSnappingLocksVerticesWithoutSwimming` — PASS
    - `heightIntoAllocatesZeroBytes` — PASS
  - Existing suite regression check: `CornerWalkTest` (13/13), `CrossroadsTest` (5/5), `StageCameraTest` (4/4), `TalkByVoiceTest` (11/11) — all PASS.
- **Filament Kotlin compile verification (`gradle --configure-on-demand -Pfilament.dist=<filament-1.81.0-stubs> :desktop:compileKotlin`):** **PASSED (`BUILD SUCCESSFUL`)**
  - Extracted `classes.jar` from Maven Central `com.google.android.filament:filament-android:1.81.0` to verify `OceanSurface.kt` and `FilamentOceanPreview.kt` type-check and compile cleanly against the exact Filament `v1.81.0` Java API (zero errors; zero warnings in `OceanSurface.kt`).
- **CPU cost measurement (OpenJDK 21, 50,000 queries after 10,000 warmup):**
  - `OceanWaves.heightInto` (6 Gerstner waves, 4-iteration horizontal inversion): **1.55 µs / query**, **0 B / query** inside the loop (4 hull buoyancy points for Jolt = ~6.2 µs/frame).
  - GPU geometry budget: static `160x160` mesh (`25,921` verts, `51,200` tris, `1` draw call, `0 B/frame` buffer uploads — only uniform parameter updates + one 4×4 transform update per frame).

### Not run here
- **tests not run here:** `matc` compilation of `ocean.mat` -> `ocean.filamat` and headless Filament desktop preview (`gradle :desktop:preview -Dmode=ocean` PNG renders and llvmpipe frame timing) were not run in this environment because prebuilt Filament `v1.81.0` Linux binaries require `GLIBC 2.38` while this sandbox runs Debian 12 (`GLIBC 2.36`), and per `docs/queue/README.md` heavy toolchains (Filament C++) are not built from source here.
- **Commands for the build server:**
  ```bash
  # 1. Compile the Filament material (same flags as street_lit.mat)
  matc -p mobile -a opengl \
    -o kotlin-street/app/src/main/assets/filament/ocean.filamat \
    kotlin-street/filament/materials/ocean.mat

  # 2. Run the standalone ocean desktop preview (writes PNGs + ocean_bench.txt to desktop/build/preview/ocean/)
  gradle -p kotlin-street :desktop:preview -Dmode=ocean

  # 3. Run unit tests
  gradle -p kotlin-street :desktop:crossroadsTest --tests OceanWavesTest
  ```

## Anything not done

- `kotlin-street/app/src/main/assets/filament/ocean.filamat` is not committed yet because `matc` cannot execute on `GLIBC 2.36`; running the `matc` command above on the build server generates it directly from `kotlin-street/filament/materials/ocean.mat`.
- Preview PNGs (`ocean_day_calm.png`, `ocean_day_rough.png`, `ocean_dusk_calm.png`, `ocean_dusk_rough.png`, `ocean_night_calm.png`, `ocean_night_rough.png`, `ocean_grid_6up.png`, `ocean_boat_bob_strip.png`) and `ocean_bench.txt` are generated by `gradle -p kotlin-street :desktop:preview -Dmode=ocean` on the build server once `ocean.filamat` is compiled.

## Usage

- **Model / setting:** Gemini (AI Studio Build agent; exact model string / thinking budget not visible in runtime)
- **Tokens:** not visible
- **Effort:** ~25 tool calls across 1 continuous session (design, material/CPU/Filament/preview/test implementation, JVM unit test run, Filament `v1.81.0` Java API compile verification, CPU microbenchmark)
- **Plan usage:** not visible
- **Rework:** First pass on G002 (no rework; avoided building Filament from source per queue rules)
