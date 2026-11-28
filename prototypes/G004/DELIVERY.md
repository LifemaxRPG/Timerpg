# G004 — Make the zero-allocation animation test robust under load

- **Task ID:** `G004`
- **Branch:** `gemini/G004-alloc-test` (from `ccr-52e43ebb-xnmj2e`)
- **Date:** 2026-11-27
- **Author:** Jeff (Gemini)

## Verification status

**tests not run here: no JVM/Gradle/native Filament in sandbox.** (`java` and Gradle are not installed in this environment.) Per G004 acceptance, this delivery provides the change and full JVM/bytecode reasoning for Claude to run `:desktop:crossroadsTest` several times on the build server.

## What changed (`LandmarkAnimationTest.perFrameUpdateAllocatesZeroBytes`)

Only `kotlin-street/desktop/src/filamentTest/kotlin/LandmarkAnimationTest.kt` and this `DELIVERY.md` were modified.

1. **Switched the allocation probe from `bean.getThreadAllocatedBytes(tid)` to `bean.currentThreadAllocatedBytes` (guarded by `isThreadAllocatedMemorySupported` and primed twice):**
   - In OpenJDK (`HotSpotThreadImpl`), `getThreadAllocatedBytes(long id)` allocates `long[1]` wrapper arrays (`getThreadAllocatedBytes(new long[] { id })[0]`) unless C2 has already compiled and scalar-replaced the wrapper. Calling it only once to prime it left it exposed to interpreter/Tier-1 allocation inside the measured bracket.
   - `currentThreadAllocatedBytes` (JDK 14+; `:desktop` targets `jvmToolchain(21)`) invokes the direct zero-allocation JNI scalar method `getCurrentThreadAllocatedBytes0()`, matching `OceanWavesTest`, `SignPainterTest`, and `SkyStateTest` in this repository.

2. **Kept the 5,000-frame warm-up intact:**
   - Unchanged `for (i in 0 until 5000)` loop exercising day/night toggles (frames `0..3999`) and the 1,000-frame steady-state night settle (`dark = 0.9f`), followed by `animator.ringBells(4.0, swings = 5)`.

3. **Measured 6 consecutive 500-frame windows (`3,000` measured frames total) advancing `t` and `hour` continuously:**
   - Each 500-frame window covers `8.0` simulated seconds and `2.5` game hours (`f = base + i`, `t = 4.0 + f * 0.016`, `hour = 14f + f * 0.005f`, `dark = 0.9f`), so **every single window** exercises the full per-frame hot path: 2–3 integer hour crossings (automatic bell re-triggers via `LandmarkAnimator.update`), swinging bell active/ease-out/rest phases, clock hands, armillary `SLOW_SPIN`, beacon `SWEEP_BEAM`, lift dwell/ride cycles, `PropLayer.update`, and `CinemaLayer.update`.
   - **Early transient guard (windows `0..2`, frames `5,000..6,499`):** combined allocation across the first 3 post-warmup windows must satisfy `earlyBytes in 0L..4096L`. This absorbs a one-off asynchronous Tier-3 -> Tier-4 C2 compilation or deoptimisation burst (such as the observed `832 B` on a loaded 4-core server) without allowing a real per-frame allocation (even the smallest 16-byte JVM object once per frame allocates `8,000 B` in a single 500-frame window, immediately exceeding `4,096 B`).
   - **Strict steady-state zero check (windows `3..5`, frames `6,500..7,999`):** each of the final **3 consecutive 500-frame windows** (`1,500` consecutive frames / `24.0` simulated seconds / `7+` hour crossings) must allocate **strictly `0L` bytes** (`assertEquals(..., 0L, windows[w])`). The steady-state threshold remains `0L`, and the test now proves 3× as many consecutive zero-byte frames as before.
   - Failure messages print `windows.contentToString()` so any regression immediately shows whether the allocation is transient or steady-state.

4. **Wrapped Filament setup/measurement in `try { ... } finally { ... }`:**
   - Ensures `props.destroy()`, `engine.destroyMaterial(...)`, `engine.destroyScene(scene)`, and `engine.destroy()` always run even on early return (`!isThreadAllocatedMemorySupported`) or exception, preventing native OpenGL/Filament handle leaks into later tests in `:desktop:crossroadsTest`.

## Why the hot path is genuinely 0 bytes/frame (code audit)

Audited every method invoked inside the measured loop:
- `LandmarkAnimator.update`: primitive loops (`0 until entries.size`, `0 until e.batchCount`), pre-allocated `scratchModel`/`scratchGpu`, primitive `bellAngleRad` / `defaultHourSwings` / `GlbFile.mulInto`. Zero allocations.
- `AnimatedNodePart.computeModelMatrix`: writes directly into `out: FloatArray` via `clockBasis`, `spinAxis`, `mulRestByAxisAngle`, `liftOffsetY`. Zero allocations.
- `PropLayer.setBatchVisible`: returns immediately when `b.visible == visible` (steady-state night `dark = 0.9f`), never reaching line 457's `arrayOf(b.first, b.second)`.
- `PropLayer.setBatchTransform` & `PropLayer.update`: index loops over `batches`, scalar JNI calls (`getInstance`, `setTransform`, `setLayerMask`), zero boxing.
- `CinemaLayer.update`: index loop over `lits`, primitive `floor`/`smooth` and scalar `setParameter("glow", ...)`. Zero allocations.
- Measured loop itself in `LandmarkAnimationTest.kt`: plain `for (i in 0 until framesPerWindow)` primitive counter loops (no `IntRange`, no `repeat` lambda capture, no string formatting inside the `before..after` bracket).

## How to run

From `kotlin-street/`:

```bash
./gradlew :desktop:crossroadsTest --tests LandmarkAnimationTest
```

And full suite under load (repeat several times on the build server):

```bash
./gradlew :desktop:crossroadsTest --rerun-tasks
```

## Usage

- **Model and setting:** Gemini (AI Studio Build, `models/gemini-4-neon`, default reasoning)
- **Tokens:** not visible
- **Effort:** 1 session, ~7 tool calls, ~6 minutes wall-clock from queue check to push
- **Plan usage:** not visible
- **Rework:** round 1 (initial delivery)

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   I cannot compile or run `:desktop:crossroadsTest` locally because this sandbox has no JVM, Gradle, or native Filament. To minimize any compile or runtime surprise, I checked every import, method signature (`currentThreadAllocatedBytes`, `isThreadAllocatedMemorySupported`, `LongArray.contentToString()`), and loop construct against the project's Kotlin 2.0.21 / JDK 21 configuration and sibling allocation tests (`OceanWavesTest`, `SignPainterTest`, `SkyStateTest`), and audited every method on the hot path (`LandmarkAnimator.update`, `AnimatedNodePart.computeModelMatrix`, `PropLayer.update`, `CinemaLayer.update`).

2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked directly in source:** `LandmarkAnimationTest.kt`, `LandmarkAnimation.kt`, `PropLayer.kt`, `CinemaLayer.kt`, `OceanWavesTest.kt`, `SignPainterTest.kt`, `SkyStateTest.kt`, and `desktop/build.gradle.kts` (`jvmToolchain(21)`, Kotlin `2.0.21`).
   - **Reasoned about only (no JVM/Filament in sandbox):** Kotlin compilation, real HotSpot C2 timing across the 6 × 500-frame windows under 4-core contention, and Filament OpenGL cleanup on the build server.

3. **If the owner looked at this for 10 seconds, what would they notice first?**
   The test keeps its 5,000-frame warm-up and strict `0L` steady-state bar (now across 3 consecutive 500-frame windows = 1,500 frames), switches to the allocation-free `currentThreadAllocatedBytes` probe, and cleanly separates bounded early JIT noise (`<= 4096 B`) from real steady-state leaks.

## Ideas for the team

1. **Switch remaining `getThreadAllocatedBytes(tid)` call sites to `currentThreadAllocatedBytes`:** `BikeRideTest.kt`, `OpenWalkTest.kt`, `FilamentBench.kt`, and `FilamentLandmarksPreview.kt` still use `getThreadAllocatedBytes(tid)` (which can allocate `long[1]` wrapper arrays before C2 scalar replacement), whereas `OceanWavesTest`, `SignPainterTest`, `SkyStateTest`, and now `LandmarkAnimationTest` use `currentThreadAllocatedBytes`. Standardizing on `currentThreadAllocatedBytes` (and optionally a tiny multi-window steady-state helper for zero-alloc tests) would prevent the same loaded-server flake in the other allocation tests.
2. **Note on `PropLayer.setBatchVisible` (line 457):** When a landmark's visibility actually toggles (e.g., beacon beam turning on at dusk), `for (m in arrayOf(b.first, b.second))` allocates a tiny 2-element array once on the transition frame. It never runs in steady state, so it's negligible today, but replacing it with a direct two-line check on `b.first` and `b.second` would make dusk/dawn visibility transitions 0-byte too.
