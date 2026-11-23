# Delivery — G001: Finish the moving landmarks

- **Task ID:** `G001`
- **Assignee:** `gemini`
- **Branch:** `gemini/moving-landmarks` (new commit added on top of `d17663a`, no rebase, no force-push)

## What changed

### 1. Fixed `LandmarkAnimationTest.perFrameUpdateAllocatesZeroBytes` (and benchmark warmup)
- **Root cause analysis:**
  1. **Zero per-frame allocations in compiled bytecode (`javap -c`):** Inspected `LandmarkAnimator.update`, `AnimatedNodePart.computeModelMatrix` / `isVisible` / `clockAngleRad` / `liftOffsetY` / `mulRestByAxisAngle`, `GlbFile.mulInto`, `PropLayer.update` / `setBatchVisible` / `setBatchTransform`, and `CinemaLayer.update`. None contain any `new`, `newarray`, `anewarray`, `invokedynamic`, `valueOf`, or `iterator` instructions on the per-frame path (only unreachable `NoWhenBranchMatchedException` throws).
  2. **Reproduced live on OpenJDK 21 (`ThreadMXBean.getThreadAllocatedBytes`) against the compiled `AnimatedNodePart` + `GlbFile.mulInto` classes:**
     - Under `-Xint` (interpreter only, no JIT): `0 B` allocated even after `200` warmup frames.
     - Under default HotSpot tiered compilation with `-XX:+PrintCompilation`: `warm = 200` frames (`t = 0..3.184 s`) stayed below HotSpot Tier-3/Tier-4 compilation thresholds and never reached `lift_car`'s `waitSeconds = 4.0 s` ride transition or `bell_0`'s ease-out (`> 12 s`) / rest (`>= 15 s`) phases. Mid-measurement JIT compilation and uncommon-trap deoptimization attributed a one-time burst (`216 B` on Claude's build server; `432–952 B` locally; and `736 B` in `rAnimOnly` of `moving_bench.txt` followed immediately by `0 B` in `rCombined`).
     - Across 40 fresh OpenJDK 21 JVMs:
       - `warm = 200`: `952 B` in 6/10 runs (`0 B` in 4/10)
       - `warm = 1000`: `432–952 B` in 8/10 runs (`0 B` in 2/10)
       - `warm = 3000`: `0 B` in 10/10 runs
       - `warm = 5000`: `0 B` in 10/10 runs
- **Fix (without loosening the assertion):**
  - `kotlin-street/desktop/src/filamentTest/kotlin/LandmarkAnimationTest.kt`: Increased warmup from `200` to `5000` frames (`t = 0..250 s`, `hour = 12..27`), exercising full lift dwell+ride cycles, bell ramp-in/steady/ease-out/rest phases, integer hour crossings, and night/day visibility toggles, ending in a 1000-frame steady-state night tail, re-arming `ringBells(4.0, swings = 5)`, and priming `bean.getThreadAllocatedBytes(tid)` before measuring. The strict `assertEquals("per-frame update must allocate 0 bytes across 500 frames", 0L, allocBytes)` check is unchanged.
  - `kotlin-street/desktop/src/filament/kotlin/FilamentLandmarksPreview.kt`: Matched warmup (`warm = 5000` frames, `t = 0..250 s`), re-armed `courtAnim.ringBells(0.0, swings = 500)` after warmup hour-crossings, and primed `tmb?.getThreadAllocatedBytes(tid)` so `rAnimOnly` also avoids the one-time C2-warmup spike.

### 2. Fixed misplaced KDoc comments
- `kotlin-street/shared/src/filament/kotlin/scene3d/FilamentTown.kt` (**comments only**): Moved `fadeBetween`'s KDoc block directly above `fun fadeBetween(t: Float)` so `ringLandmarkBells` no longer sits between `fadeBetween`'s KDoc and signature.
- `kotlin-street/shared/src/filament/kotlin/scene3d/TownLandmark.kt`: Moved `frame`'s KDoc block directly above `fun frame(...)` so `ringBells` no longer sits between `frame`'s KDoc and signature.

### 3. Desktop Filament renders & benchmark (`prototypes/moving-landmarks/`)
> **Note:** All PNGs below are **headless desktop Filament renders** produced by `FilamentLandmarksPreview.kt` (Mesa software GLES), **not** phone game captures.

- `courthouse_clock_3_00.png` — County Courthouse (1894) dial at 3:00 (hour hand at 3, minute at 12)
- `courthouse_clock_4_30.png` — County Courthouse (1894) dial at 4:30 (hour hand halfway between 4 and 5, minute at 6)
- `courthouse_1988_stopped_4_17.png` — County Courthouse (1988) stopped dial frozen at 4:17
- `courthouse_clock_comparison.png` — Side-by-side comparison strip (3:00 / 4:30 / 1988 stopped 4:17)
- `meridian_night_strip.png` — 4-frame night strip showing Great Meridian armillary rings rotating and searchlight beacon sweeping
- `meridian_rings_strip.png` — 4-frame close-up strip of the Great Meridian armillary rings at h=0:00 (0°), 6:00 (90°), 12:00 (180°), 18:00 (270°)
- `meridian_day_check.png` — Great Meridian daytime verification (night-only beacon beam hidden)
- `moving_bench.txt` — Headless desktop Filament per-frame CPU & allocation benchmark with both Courthouse (1894, 9 animated nodes) and Great Meridian (1926, 4 animated nodes) animating simultaneously (13 animated nodes, all 5 animation kinds active):
  - Baseline (`PropLayer + CinemaLayer` only): mean `22.26 us`, p50 `22.85 us`, p95 `25.00 us` | `0 B` alloc over 2000 frames | `FrameWork.bytes` GPU upload delta `0 B`
  - Extra cost (`LandmarkAnimator.update` x2, 13 animated nodes): mean `5.45 us`, p50 `5.01 us`, p95 `6.14 us` | GPU upload delta `0 B` (note: this capture used `warm = 300`, showing the `736 B` one-time JIT warmup burst that dropped to `0 B` in the immediately following `Combined` pass; warmup is now raised to `5000` in `FilamentLandmarksPreview.kt`)
  - Combined (`Animators x2 + PropLayer + CinemaLayer`): mean `24.55 us`, p50 `23.95 us`, p95 `28.11 us` | `0 B` alloc over 2000 frames (`0.00 B/frame`) | `FrameWork.bytes` GPU upload delta `0 B`

## Tests

- **Full Filament `gradle :desktop:crossroadsTest`:** tests not run here: this sandbox does not have prebuilt native `libfilament-jni.so`, and building Filament C++ from source is skipped per `docs/queue/README.md` (don't build heavy toolchains from source). Ready for Claude's build server to run `gradle :desktop:crossroadsTest`.
- **What was run and verified locally:**
  - `gradle :desktop:crossroadsTest --configure-on-demand` (non-Filament crossroads suite): `BUILD SUCCESSFUL`
  - `gradle :desktop:compileCrossroadsTestKotlin --configure-on-demand` (with Filament Java classes compiled from `v1.81.0` headers): `BUILD SUCCESSFUL` (zero errors across `FilamentTown.kt`, `TownLandmark.kt`, `FilamentLandmarksPreview.kt`, `LandmarkAnimationTest.kt`)
  - Bytecode audit (`javap -c`) + 40-JVM `ThreadMXBean` allocation verification on compiled `AnimatedNodePart` + `GlbFile.mulInto`: `0 B` allocated in 20/20 runs at `warm >= 3000`.

## Anything not done

- Full native-JNI run of `gradle :desktop:crossroadsTest` and re-capturing `moving_bench.txt` with `warm = 5000` (needs prebuilt desktop `libfilament-jni.so` on the build server).

## Usage

- **Model and setting:** Gemini (AI Studio Build; exact reasoning setting not visible)
- **Tokens:** not visible
- **Effort:** ~16 turns, ~35 tool calls across resumed session (~15 min active engineering work, excluding an aborted Filament C++ build attempt stopped per updated README guidance)
- **Plan usage:** not visible
- **Rework:** First pass on G001 (follow-up task on existing `gemini/moving-landmarks` branch)
