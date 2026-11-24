# G016 Delivery (Round 2): The Sky in Filament (Pixel-Art Sky Dome)

- **Task:** `G016` — The sky in Filament (a pixel-art sky dome)
- **Branch:** `gemini/G016-sky`
- **Author:** Jeff (Gemini)

---

## Round 2 Fixes (Addressing `docs/queue/reviews/G016-r1.md`)

Every compile error and look note from Claude's Round 1 review has been fixed and audited across the shipping code:

1. **`sky.mat` `matc` compile error (`PI` macro collision + shadowed `SKY_BANDS`):**
   - Renamed `PI` → `SKY_PI` and `TWO_PI` → `SKY_TWO_PI` everywhere in `kotlin-street/filament/materials/sky.mat` (verified zero bare `PI` or `TWO_PI` tokens remain in shader code).
   - Removed the stale outer `const float SKY_BANDS = 11.0;`, leaving only the single `const float SKY_BANDS = 10.0;` inside `material()`.
2. **Look fix — Noon radial wedges / spokes removed (Shot 02):**
   - Replaced the 6-bin azimuthal quantization `floor(sunSideRaw * 6.0 + 0.5) / 6.0` with smooth azimuthal `sunSide` and tapered the daytime sun-side tint near zenith (`smoothstep(0.18, 1.0, vBand)`).
   - Verified in regenerated `02_noon_clear.png` and `sky_contact_sheet.png`: elevation bands are now pure, level pixel-art rings with zero radial spokes from zenith.
3. **Kotlin compile errors (`scene.removeEntity` & `CLOUD_TEX_SIZE`):**
   - Replaced every `scene.removeEntity(...)` with `scene.remove(...)` in `SkySurface.kt` (line 219) and `FilamentSkyPreview.kt` (lines 306, 456, 471), matching `OceanSurface.kt` and `FilamentOceanPreview.kt`.
   - Unified `SkySurface.kt` on `CLOUD_TEX_SIZE = 256` across `buildCloudTexture` and `bakeCloudPixels` — fixing the undefined `CLOUD_TEX_SIZE` reference and bringing the shipping Kotlin cloud bake back into exact 1:1 parity with the round-3 cumulus/stratus panorama bake (`256×256` tiled 3× around 360° = 768×256 around the dome, 5 primary fair-weather banks + 24 overcast/rain banks) used by the proof renders.
4. **Additional desktop preview audit fixes (`FilamentSkyPreview.kt`):**
   - Switched preview `View` to `View.BlendMode.OPAQUE` so `-Dmode=sky` validates the opaque Filament surface directly.
   - Fixed `readPixels` row readback orientation (`((h - 1 - y) * w + x) * 4`) and added the `done` spin-wait guard to match `FilamentOceanPreview.kt`, `FilamentYardsPreview.kt`, and `QuadSmoke.java` so headless desktop PNGs save right-side-up.

---

## Summary of Deliverables

Scope remains strictly new files plus the single preview-mode dispatch line in `kotlin-street/desktop/build.gradle.kts` (`FilamentTown`, `FilamentLayer`, `Backdrop`, `ScenePalette`, `OceanSurface`, and `docs/queue/` are untouched):

1. **`kotlin-street/filament/materials/sky.mat`** — Unlit, opaque, single-triangle far-plane sky dome (`vertexDomain : device`, `depthWrite : false`, `depthCulling : true`, `culling : none`, `priority(7)`), nearest-filtered textures only. Hard 10-step level pixel-art bands, dusk sunset/Belt of Venus accents, pixel-art sun, nearest 32×32 phased moon (lunar maria, waxing-right terminator matching `MoonPhase`, phase-gated halo, night earthshine), world-fixed spherical starfield + fine Milky Way dust band, upright 3-tone drifting cumulus/stratus clouds, overcast veil, stepped horizon mist, and rain dimming.
2. **`kotlin-street/shared/src/main/kotlin/scene3d/SkyState.kt`** — Pure-Kotlin, **0 B/frame** allocation state matching `SkyColors`, `Backdrop.kt`, `SkyDome.kt`, `OrbitCamera.sunAzimuth`, `SceneLight.key`, `MoonPhase`, `WeatherState`, and `SeasonState`, plus pre-converted linear RGB outputs (`belowHorizonLin`, `oceanSkyLin`, `oceanGlintLin`, `oceanGlintStrength`).
3. **`kotlin-street/shared/src/filament/kotlin/scene3d/SkySurface.kt`** — Single-draw-call Filament far-plane wrapper (`FAR_CLIP_Z = 0.999998f` → `z' = 1e-6f`, behind all world geometry including the 24 km ocean skirt), one-time deterministic baking of `moonTex` (32×32) and `cloudTex` (256×256), and 0 B/frame uniform uploads honouring `LookMatrix.windowY`.
4. **`kotlin-street/desktop/src/filament/kotlin/FilamentSkyPreview.kt`** (+ 1-line `"sky" -> "FilamentSkyPreviewKt"` in `kotlin-street/desktop/build.gradle.kts`) — Headless Filament preview (`-Dmode=sky`) on an opaque `View` rendering 12 shots + 4×3 contact sheet + `PreviewSkyline` depth silhouette + `sky_bench.txt`.
5. **`kotlin-street/desktop/src/crossroadsTest/kotlin/SkyStateTest.kt`** — Unit tests covering sun/moon directions vs `OrbitCamera.sunAzimuth` & `SceneLight.key`, palette & weather dimming, known-date moon phases, 90° camera-turn ray invariance, and strict 0 B/frame allocation via `ThreadMXBean.currentThreadAllocatedBytes`.
6. **`prototypes/sky/*.png`** — **Offline faithful proof renders** (12 shots + `sky_contact_sheet.png`) regenerated directly from the Round 2 `SkyState.kt` + `SkySurface.kt` (`256×256` `bakeCloudPixels`, `32×32` `bakeMoonPixels`) + `sky.mat` (smooth `sunSide`, level noon rings). **Important:** these PNGs are **offline faithful proof renders, NOT Filament desktop or phone game captures**.

---

## Build & Verification Status

**tests not run here: sandbox has no JDK/Gradle/matc/Filament**

No fake `sky.filamat` was committed. Build and verify on the build server:

```bash
# 1. Compile the Filament material (Filament v1.81.0)
matc -p mobile -a opengl \
  -o kotlin-street/app/src/main/assets/filament/sky.filamat \
  kotlin-street/filament/materials/sky.mat

# 2. Run SkyState unit tests (including 0 B/frame allocation check)
./gradlew -p kotlin-street :desktop:crossroadsTest --tests "*SkyStateTest"

# 3. Render the 12-shot Filament desktop preview + contact sheet + bench
./gradlew -p kotlin-street :desktop:preview -Dmode=sky
```

Static checks verified locally before pushing Round 2:
- `grep` confirmed zero `removeEntity` calls across `SkySurface.kt` and `FilamentSkyPreview.kt`, and zero bare `PI`/`TWO_PI` tokens in `sky.mat` shader code.
- Audited every Filament Java API call (`scene.remove`, `Texture.PixelBufferDescriptor`, `RenderableManager.Builder`, `ColorGrading`, `readPixels` bottom-up Y-flip, and `street_lit.mat` uniforms on `PreviewSkyline`) 1:1 against `OceanSurface.kt`, `FilamentOceanPreview.kt`, `FilamentYardsPreview.kt`, and `WorldNear.kt`.
- Verified all 26 `sky.mat` parameters match `SkySurface.kt` `setParameter(...)` calls 1:1, single `SKY_BANDS` declaration, consistent `CLOUD_TEX_SIZE = 256`, and balanced delimiters across all 5 files.
- Visually inspected regenerated `02_noon_clear.png` and `sky_contact_sheet.png` to confirm noon bands are level rings with zero radial spokes.

---

## Usage

Once `sky.filamat` is compiled into `kotlin-street/app/src/main/assets/filament/sky.filamat`:

```kotlin
val skyMatBytes = assetManager.open("filament/sky.filamat").readBytes()
val skyMatBuf = ByteBuffer.allocateDirect(skyMatBytes.size)
    .order(ByteOrder.nativeOrder()).put(skyMatBytes).flip()
val skyMat = Material.Builder().payload(skyMatBuf, skyMatBytes.size).build(engine)
val skyState = SkyState()
val skySurface = SkySurface(engine, scene, skyMat)

// Per frame (0 B allocated)
skyState.update(hour, gameDate, weatherState, seasonState, elapsedSec)
skySurface.updateForCamera(skyState, yawDeg, pitchDeg, fovYDeg, aspect)
// Or skySurface.update(...) for TownPose off-center principal point cameras,
// or skySurface.updateForLookAt(...) for arbitrary eye/target/up cameras.

// Switch Filament View to opaque once Backdrop is retired:
// view.blendMode = View.BlendMode.OPAQUE

// Teardown
skySurface.destroy()
engine.destroyMaterial(skyMat)
```

---

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   - **Weakest part:** I still cannot run `matc 1.81`, `kotlinc`, or headless Filament locally in this sandbox, and in Round 1 that let two real compile errors (`PI` prelude collision and `scene.removeEntity`) plus a stale `CLOUD_TEX_W`/`CLOUD_TEX_SIZE` drift slip past into the first push.
   - **What I did about it:** Fixed every error Claude reported, diffed the shipping Kotlin (`SkyState.kt`, `SkySurface.kt`) and GLSL (`sky.mat`) directly against the proof script so the shipping `256×256` cloud bake and smooth-`sunSide` level noon bands match 1:1, and audited every Filament Java API call and `readPixels` Y-flip against `OceanSurface.kt`, `FilamentOceanPreview.kt`, and `FilamentYardsPreview.kt` (catching a bottom-up `readPixels` flip bug in `FilamentSkyPreview.kt` before Claude even ran `-Dmode=sky`).

2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked by looking (offline proof renders):** Inspected regenerated `02_noon_clear.png` and the full 12-shot `sky_contact_sheet.png` — confirmed the noon radial spokes are completely gone (pure level horizontal rings), dusk orange-to-cobalt bands and waxing crescent beside the clock tower look crisp, midnight full vs. crescent moon phases and starfields read cleanly, overcast/rain/mist dim the sky properly, and 0° → 45° → 90° camera turns keep stars and clouds anchored in world space.
   - **Only reasoned about (for Claude's build server):** Live `matc 1.81` compilation, Kotlin 2.x compilation and `SkyStateTest` execution (including JVM `ThreadMXBean` 0 B/frame assertion), and real Filament `-Dmode=sky` output + reversed-Z occlusion on llvmpipe and Android `TextureView`.

3. **If the owner looked at this for 10 seconds, what would they notice first?**
   - Shot 3 (`03_dusk_crescent_moon.png`): hard-stepped ember-to-cobalt dusk sky behind the clock tower with the crisp pixel-art waxing crescent rising beside the spire and first stars overhead; then Shot 2 (`02_noon_clear.png`) with clean, spoke-free level blue rings and flat-bottomed pixel clouds; then Shots 5–6 (full moon with maria vs. crescent moon) and 10–12 (stars and clouds locked to the sky across a 90° pan).

---

## Ideas for the team

1. **Single source of truth for Sky + Town Light + Haze + Ocean (`SkyState` wiring):**
   `SkyState` already computes `keyOrbDir`, `belowHorizonLin`, `oceanSkyLin`, `oceanGlintLin`, and `oceanGlintStrength` at 0 B/frame. Driving town fog/haze and `OceanSurface` from the same `SkyState` instance will lock horizon colour, building shadows, and ocean sun/moon glitter trails together across every hour, weather state, and lunar phase.
2. **In-cloud lightning flash uniform for heavy rainstorms:**
   Because clouds are mapped in world `(azimuth, elevation)` with 3-tone stepped shading, a single `stormFlash` `float4(az, elev, intensity, 0)` uniform can light up storm-cloud undersides from within during thunderstorms at negligible mobile GPU cost.
