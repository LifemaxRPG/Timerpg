# Gemini engineering task: make the landmarks move

This is a code task, not a model. You will change the game engine (Kotlin, Filament) so that named parts inside our
hand-made landmark models animate while the game runs. Read this whole brief before starting.

## Background

The game draws its 3D town with **Filament**. Hand-made landmark models (.glb) are loaded by **our own loader**, not
Filament's gltfio, and merged into the town's lit material:
- `kotlin-street/shared/src/filament/kotlin/scene3d/GlbFile.kt`: reads the glTF subset we use (nodes, meshes, materials,
  embedded PNGs).
- `kotlin-street/shared/src/filament/kotlin/scene3d/LandmarkKit.kt`: `LandmarkKit.build(glb, spec, year, seed, ...)`
  turns a .glb into a `LandmarkModel` (wrapping a `CinemaModel`): the parts are **merged by material role** into a
  few renderables, scaled from metres to game units (`UNITS_PER_METRE`), with collision boxes and doors.
- `TownLandmark.kt`, `CinemaLayer.kt` and `CinemaKit.kt`: how a built landmark is put in the town and drawn and lit.
- `FilamentTown.kt`: the town; `frame(pose, hour, seconds)` (around line 1092) is called every frame with the game
  hour and a running time.
- Docs: `kotlin-street/docs/LANDMARKS_GLB.md` (read it all) and `docs/CHATGPT_MODEL_GUIDE.md` (the model contract).
- Tests: `kotlin-street/desktop/src/filamentTest/kotlin/LandmarkGlbTest.kt`. Desktop preview:
  `kotlin-street/desktop/src/filament/kotlin/FilamentLandmarksPreview.kt`, run with
  `gradle :desktop:preview -Dmode=filament-landmarks` (check the exact mode name in the desktop build file).

**The problem:** because the loader merges every part by material, a clock hand or a ring is baked into a big static
mesh and can never move.

## Goal

Named nodes in a landmark .glb can be marked as **animated**. The loader keeps them as their own small renderables
(not merged), pivoted at their node origin, and the town updates their transforms every frame, cheaply.

## Animations to support

Drive each from a small declarative table, not hard-coded per model. A node name pattern maps to an animation kind
and parameters, readable from the landmark's catalogue entry (`landmarks.json`) or from `model-info.json`'s node list:

1. **Clock hands:**
   - `clock_hour*` turns one revolution per 12 game hours;
   - `clock_minute*` one per game hour;
   - both follow the game clock passed to `frame()`.
   - **Stopped clocks:** some states set a fixed time (the 1976-1999 courthouse is stopped at 4:17). Support a
     per-state override "stopped at h:mm".
2. **Slow spin:** a constant rate about a given axis, e.g. armillary rings: `armillary_ecliptic` once per game day;
   `armillary_globe` once per day about its polar axis. The axis is in the node's local frame or given in
   model-info.
3. **Sweep beam:** `beacon_beam` turns about the vertical axis, once every 10 real seconds, **only at night** (between
   dusk and dawn; take the town's existing day/night or lighting state). Off (hidden) by day.
4. **Swing:** a pendulum around a horizontal axis, for bells (`bell_0`...`bell_3`).
   - Amplitude about 25-35°, period 2-3 s, with the bells out of phase with each other.
   - Triggered on the hour for a few swings, then easing to rest.
   - Expose a function so other systems can ring them.
5. **Lift:** `lift_car` moves along its vertical axis between listed stop heights. For now, a slow automatic loop:
   ride, wait 6 s at each stop.

## Requirements

- **Cheap.** Only animated nodes become separate renderables, each with its own material instance where it needs
  to; a model keeps its merged static parts as now. Update transforms with `TransformManager` (no rebuilding meshes,
  no per-frame allocation). Skip animating landmarks that are off-screen or beyond the near-model distance.
- **Correct pivots.** Use each node's own transform as the pivot, so hands turn about the dial centre and rings about
  the sphere centre. glTF node transforms can be TRS or a matrix: honour both, as `GlbFile` already does for static
  parts. Get the scale and axis conversion right (our loader converts metres to game units and the glTF frame to the
  town's frame; see how `LandmarkKit` places parts, and the street's mirrored frame in `docs/INTERIORS_3D.md`).
- **Lighting and shadows:** animated parts are lit and cast shadows like the rest. Glow (emissive) still works on
  them (the clock dials and the beacon orb).
- **Collision:** animated parts have no collision unless the model lists some (the lift car will later; leave a TODO).
- **Phone and desktop:** the same code runs on both (`shared/`). Nothing desktop-only in the engine path.
- **Don't break anything:** every existing landmark still loads and looks the same. The full test task
  `gradle :desktop:crossroadsTest` must pass, and `:app:testDebugUnitTest` too.

## Test models

Use these for development; copy files with `git show`, and never merge the branches:
- **The courthouse:** `origin/codex/county-courthouse`, `prototypes/county-courthouse/` (four state .glb files). It has
  `clock_hour` / `clock_minute` and `bell`.
- **The Great Meridian:** `origin/codex/great-meridian-monument`, `prototypes/great-meridian-monument/`, with
  `armillary_outer`, `armillary_ecliptic`, `armillary_globe`, `beacon_beam` and `lift_car`.

If a model's node names differ slightly, map them in the table; don't rename them in the files. For the courthouse
states, the 1988 state is the stopped clock.

## Tests to add (in `LandmarkGlbTest` or a new test file)

- **Hand angles:** at game time 3:00, the hour hand is at 90° from 12 and the minute hand at 0°. At 4:30, the hour
  hand is at 135° and the minute hand at 180° (measured on the dial face). The stopped state always shows 4:17.
- **Pivots:** after rotating, each animated part's bounding-box centre stays at its pivot (within a tolerance).
- **No allocation:** the per-frame update allocates nothing (check with a simple counter or allocation tracking).
- **Static parts unchanged:** a model without animated nodes produces the same renderables and triangle count as
  before.

## Deliver

- Work on a new branch `gemini/moving-landmarks` made from `ccr-52e43ebb-xnmj2e`.
- **Commits:** small, with clear messages.
- **A short doc section** in `kotlin-street/docs/LANDMARKS_GLB.md`, "Animated nodes": the table format, the kinds and
  their parameters, and how a model author names nodes.
- **Desktop renders** from the landmark preview: the courthouse clock at 3:00 and 4:30, and a 4-frame strip of the
  Meridian's rings and beacon at night (state they are desktop renders, not phone captures). Optionally, a short GIF
  of the rings turning.
- **The measured cost:** the extra per-frame CPU time in the preview with both landmarks animating (`FrameWork` /
  the spike profiler already in the code can help).
- **A README note** listing anything not done.

Don't change unrelated systems (the town plan, the generator, dialogue or audio). Keep to the landmark loader, the
town's per-frame update, the tests, the preview and the docs.
