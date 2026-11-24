# G016 Delivery — Pixel-Art Sky Dome in Filament

## What was built

A standalone, single-draw-call pixel-art sky dome for Filament (`sky.mat`, `SkyState.kt`, `SkySurface.kt`), plus a headless desktop Filament preview (`-Dmode=sky`), JUnit tests (`SkyStateTest.kt`), and faithful offline pixel-art proof renders (`prototypes/sky/*.png`).

### Files created / modified
- **New** `kotlin-street/filament/materials/sky.mat` — Filament 1.81 unlit opaque `vertexDomain : device` material (`depthWrite : false`, `depthCulling : true`, `culling : none`). Draws a single far-plane full-screen triangle (`z = 0.999998 -> z' = 1e-6` in Filament's reversed-Z buffer, strictly behind every town, terrain, and 24 km ocean pixel while strictly above the cleared `0.0` depth plane). Evaluates everything in 3D world sky direction (`+X` east, `+Y` up, `-Z` north, `+Z` south) so turning the camera 90° keeps stars, Milky Way, sun, moon, and clouds completely fixed in the sky:
  - **11 hard stepped 16-bit sky bands** (no smooth gradients), matching `SkyColors` / `SkyDome` across dawn, day, dusk, and night (sunset orange-ember horizon rising into deep twilight cobalt-blue overhead, rose Belt of Venus, night town horizon glow, winter crispness, seamless below-horizon haze matching `FilamentTown.haze`).
  - **Pixel-art sun disc** (quantized 20×20 pixel grid with stepped core, inner/outer halo rings, and clear-sky cardinal shimmer) pinned to `sunDir`.
  - **Pixel-art moon disc** sampled from a nearest-filtered 32×32 `moonTex` (lunar maria, bright Tycho/Copernicus highlights, stepped limb relief) with a 32-step spherical phase terminator matching `MoonPhase` (lit on the right when waxing), phase-directed 2-pixel halo (no ghost ring on the dark limb of crescents), star occultation, and atmospheric dark side (subtle cool earthshine at night; never darkens the bright dusk sky).
  - **World-fixed pixel-art starfield & delicate Milky Way dust ribbon** evaluated purely from world `(az, elev)` spherical cells (immune to camera rotation), fading in at dusk via `starsShown(skyTone(hour))`, with colour-varied stars (blue-white, white, cream, gold, amber), crisp `+` cross glints on the brightest stars, and gentle stepped twinkle.
  - **World-fixed pixel-art drifting clouds** sampled with `MinFilter.NEAREST` / `MagFilter.NEAREST` from a seamless 512×256 spherical-panorama `cloudTex` (3 panoramas around 360° matching `OrbitCamera.skyPx`), keeping every cloud's flat base strictly level with the horizon at every camera yaw, with 2 parallax decks, 3-tone stepped shading (`cloudLit` / `cloudBody` / `cloudShade`), dusk warm coral under-belly, opaque cloud cores cleanly occluding stars, and scaling smoothly from sparse fair-weather cumulus to heavy overcast & storm decks.
  - **Overcast, mist, and rain dimming** matching `Backdrop.kt` & `StreetWeather.kt` (overcast grey veil darker overhead and paler at horizon, 5-step pale horizon mist bands, rain atmospheric darkening/desaturation and darkened cloud undersides).
- **New** `kotlin-street/shared/src/main/kotlin/scene3d/SkyState.kt` — Pure-Kotlin, zero-allocation-per-frame sky state (package `com.lifemax.rpg.feature.habits.presentation.scene3d`, no Compose/Filament dependency). Computes `sunDir`, `moonDir`, `keyOrbDir`, `moonPhase`/`moonStep`/`moonIllumination`, `starBrightness`, `cloudCoverage`, `overcast`, `mist`, `rain`, `winterCrisp`, all sRGB ARGB and linear RGB sky/cloud/weather colours, plus ocean/lighting bridge values (`oceanSkyLin`, `oceanGlintLin`, `oceanGlintStrength`). Caches parsed `MoonPhase.fraction(date)` so steady-state per-frame updates allocate **0 bytes**.
- **New** `kotlin-street/shared/src/filament/kotlin/scene3d/SkySurface.kt` — Filament wrapper managing the 1-triangle far-plane mesh (`priority(7)`, `culling(false)`), one-time deterministic baking of the nearest-filtered 512×256 `cloudTex` and 32×32 `moonTex`, and zero-allocation per-frame uniform uploads (`update(...)`, `updateForCamera(...)`, `updateForLookAt(...)`) compatible with both `TownPose` off-center principal points and `OrbitCamera` / `FilamentOceanPreview`, honouring `LookMatrix.windowY` on both headless desktop and Android `TextureView`.
- **New** `kotlin-street/desktop/src/filament/kotlin/FilamentSkyPreview.kt` — Standalone headless Filament preview (`-Dmode=sky`) rendering all 12 cases (dawn, noon, dusk crescent moon behind clock tower, dusk western sunset, midnight full moon, midnight crescent moon, day overcast, day rain, dawn mist, and midnight camera turn `yaw = 0° / 45° / 90°`) + 4×3 labeled `sky_contact_sheet.png` + `sky_bench.txt` (including 2,000-frame CPU allocation check) and a foreground rooftop/clock-tower silhouette (`street_lit.filamat`) proving far-plane reversed-Z occlusion.
- **Modified (1 line)** `kotlin-street/desktop/build.gradle.kts` — Registered `"sky" -> "FilamentSkyPreviewKt"` in the `:desktop:preview` task. No other existing files were modified (`FilamentTown`, `FilamentLayer`, `Backdrop`, `ScenePalette`, and `OceanSurface` were left untouched per scope).
- **New** `kotlin-street/desktop/src/crossroadsTest/kotlin/SkyStateTest.kt` — Unit tests checking sun/moon direction parity against `OrbitCamera.sunAzimuth` & `SceneLight.key`, dusk-to-night moon elevation continuity at 20:30, `SkyColors` / weather colour parity, known moon-phase calendar dates (`2000-01-06` new, `2000-01-10` waxing crescent, `2000-01-14` first quarter, `2000-01-21` full, `2000-01-28` last quarter), 90° camera-turn world-ray invariance, and strict 0 B per-frame allocation over 2,000 frames via `ThreadMXBean`.
- **New** `prototypes/sky/*.png` — **Offline faithful proof renders** (12 individual shots + `sky_contact_sheet.png`) generated by executing the exact same `SkyState`, `bakeMoonPixels`, `bakeCloudPixels`, and `sky.mat` fragment math offline in Node so every visual requirement could be visually inspected and iterated across 3 rounds here. **Important:** these PNGs are **offline faithful proof renders, NOT Filament desktop or Android phone game captures**.

## Build & Test Status (Honest Report)

- **tests not run here: sandbox has no JDK/Gradle/matc/Filament** (no `java`, `javac`, `gradle`, or Filament `matc` binary is installed in this environment).
- **No fake `.filamat` committed.** Compile `sky.filamat` with Filament v1.81.0 `matc` on the build server using the exact same flags as `ocean.mat`:
  ```bash
  matc -p mobile -a opengl \
    -o kotlin-street/app/src/main/assets/filament/sky.filamat \
    kotlin-street/filament/materials/sky.mat
  ```
- **Static & visual verification performed here:**
  - Verified 100% 1:1 parameter parity across all 26 uniform/sampler names between `sky.mat` and `SkySurface.kt`.
  - Verified balanced delimiters, zero unused imports, and Filament 1.81 API signatures (`Texture.Builder`, `TextureSampler.MinFilter.NEAREST`, `RenderableManager.Builder`, `MaterialInstance.setParameter`, `View.BlendMode.TRANSLUCENT`) against existing files (`OceanSurface.kt`, `FilamentOceanPreview.kt`, `WorldNear.kt`, `street_lit.mat`).
  - Audited `sky.mat` for mobile GLSL ES 3.00 (`-p mobile`) precision safety (`highp` on spherical `elev`/`az`, cloud UVs, sun/moon tangent math, and zenith `atan(0,0)` guard).
  - Visually inspected all 12 offline proof renders across 3 iterative rounds (fixing planar cloud skew -> spherical 512×256 horizon-level panorama, fixing dark-side dusk crescent darkening, adding phase-directed crescent halo, refining Milky Way pixel dust, and tuning clear-sky cloud coverage).

## How to Verify on the Build Server

1. Compile `sky.filamat` with Filament v1.81.0 `matc`:
   ```bash
   matc -p mobile -a opengl \
     -o kotlin-street/app/src/main/assets/filament/sky.filamat \
     kotlin-street/filament/materials/sky.mat
   ```
2. Run the `SkyStateTest` unit test suite (including the 0 B/frame allocation test):
   ```bash
   cd kotlin-street && gradle :desktop:crossroadsTest --tests "*SkyStateTest*"
   ```
3. Run the headless Filament sky preview (`-Dmode=sky`):
   ```bash
   cd kotlin-street && gradle :desktop:preview -Dmode=sky
   ```
   Outputs land in `kotlin-street/desktop/build/preview/sky/` (`01_dawn_clear.png` .. `12_turn_yaw90_east.png`, `sky_contact_sheet.png`, and `sky_bench.txt`).

## Self-review

- **Visual check (offline faithful proof renders, 3 rounds):**
  - Round 1 caught planar `ray.xz` cloud skewing/giant texels, dusk crescent dark-side brown disc, blocky Milky Way grain, vertical azimuthal seams, and high noon/midnight orb cropping at `-24°` pitch.
  - Round 2 switched clouds to a horizontally seamless 512×256 spherical panorama `(az, elev)` with crisp flat bases parallel to the horizon at every yaw, fixed atmospheric dark-limb moon shading (occludes stars without darkening dusk air), refined Milky Way dust, and framed noon/midnight orbs cleanly.
  - Round 3 added phase-directed lunar halo weighting (eliminating the faint circular ring around the dark side of a crescent moon) and tightened clear-sky cloud thresholds so dusk bands, stars, and crescent moon breathe cleanly.
- **Scope discipline:** Only new files plus the single mode-dispatch line in `kotlin-street/desktop/build.gradle.kts`. `FilamentTown`, `FilamentLayer`, `Backdrop`, `ScenePalette`, and `OceanSurface` remain completely untouched.
- **Known caveats / things to double-check when compiled on the build server:**
  - Confirm `sky.mat` compiles cleanly under `matc 1.81 -p mobile -a opengl` (specifically `depthCulling : true` alongside `depthWrite : false`, and `const float SKY_BANDS = 11.0;` inside `material()`).
  - Confirm `FAR_CLIP_Z = 0.999998f` passes Filament's reversed-Z depth test behind the 24 km `OceanSurface` skirt and `FilamentTown` distant terrain on both desktop llvmpipe (`windowY = false`) and Android Adreno/Mali (`windowY = true`).
  - Confirm `PreviewSkyline` rooftops in `FilamentSkyPreview.kt` align with the sky horizon vertically on llvmpipe (both share `LookMatrix.windowY` sign convention).

## Ideas for the team

1. **Single source of truth for sky + town lighting + ocean:** When wiring G016 into `FilamentTown` and `OceanSurface`, `SkyState` already computes `keyOrbDir`, `belowHorizonLin` (exact `FilamentTown.haze` colour), `oceanSkyLin`, `oceanGlintLin`, and `oceanGlintStrength` in linear RGB at 0 B/frame — both town fog/IBL and the G002 ocean can read from the exact same `SkyState` instance so sky, haze, and sea reflections never drift out of sync across weather or dusk transitions.
2. **Dusk moon-rise handoff:** `SkyState` brings the moon up gently in the east from `6°` to `10°` (`SceneLight.LOW`) between `18:00` and `20:30` so the crescent can rise behind the clock tower while the sun is still setting in the west, then connects continuously into `SceneLight.key`'s night arc at `20:30`. If the team wants the moon's rise time to shift with lunar phase later (full moon rising at sunset, waxing crescent already higher at dusk, waning crescent rising before dawn), `SkyState` is isolated so that astronomy upgrade is a ~10-line change inside one function.
3. **Optional pixel-art rain/snow overlay pass:** The sky dome intentionally stops at atmospheric dimming and brooding storm clouds; pairing it with a tiny screen-space or near-camera pixel rain/snow particle pass in Filament will make `08_day_rain` feel completely alive on the street.

## Usage

- **Run summary:** One uninterrupted G016 implementation + 3-round offline visual critique pass (no Filament/Gradle/matc available in sandbox).
- **Files written:** 6 code/material/test files (`sky.mat`, `SkyState.kt`, `SkySurface.kt`, `FilamentSkyPreview.kt`, `SkyStateTest.kt`, `DELIVERY.md`), 1 one-line edit (`kotlin-street/desktop/build.gradle.kts`), and 13 offline proof PNGs in `prototypes/sky/`.
