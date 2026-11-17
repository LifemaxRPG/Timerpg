# Timerpg street (Kotlin)

Our game's street, drawn by the lighting/sky engine from `UPSTREAM_README.md` (the LifeMax Habits street scene) with
**all of the art replaced by ours** (LimeZu): buildings, lamps, trees, benches and pedestrians. The engine keeps the sky
(sun, moon, stars, clouds, planes, weather, lens flare), the shadows cast from the real shapes, building shade and
night, lamp light and the window glow.

## Layout
- `shared/` – the engine's source (`domain/`, `scene/`) plus `shim/` (stand-ins for the habit tracker's types, the
  platform image helper, `StageTuning` switches, `GameState` (the player), `SpriteArt` (our art)).
- `app/` – the Android app (Compose). Controls: hold ◀ ▶ to walk, ▲ ▼ to move in and out across the pavement, drag the
  slider for the time of day. Landscape.
- `desktop/` – a JVM module that renders the same scene to PNG without a phone: `gradle :desktop:preview`.
- `tools/export-street.ts` – exports a real town's shops from OUR TypeScript game logic (`street.json`: the exact
  sprite parts the game uses for each shop).
- `tools/make_buildings.py` – composes box-building textures (front, side, window-glow, roof, sign) from our sprites.

## Rebuild the street data and the APK
```
npx tsx kotlin-street/tools/export-street.ts alpha 2025 > kotlin-street/app/src/main/assets/street.json
python3 kotlin-street/tools/make_buildings.py kotlin-street/app/src/main/assets/street.json kotlin-street/app/src/main/assets/buildings
cd kotlin-street && ANDROID_HOME=/opt/android-sdk gradle :app:assembleDebug     # app/build/outputs/apk/debug/
gradle :desktop:preview                                                           # desktop/build/preview/*.png
```
(In the cloud sandbox Maven Central is rate-limited, so `settings.gradle.kts` uses Google's mirror of it.)

## Dialogue AI (native llama.cpp)
NPC lines can be voiced by Qwen3-0.6B (`qwen3-0.6b-q4_k_m.gguf`, ~400 MB, never in git or the APK) running natively:
- `app/src/main/cpp/llm_core.{h,cpp}` – the engine: load, generate with KV-cache prefix reuse (the shared rules and an
  NPC's fact sheet are processed once), the old sampler settings (temp 0.3, top-k 40, top-p 0.9, repeat penalty 1.15),
  stop at end-of-turn, cancel, timings. Plain C++, also built on a computer.
- `app/src/main/cpp/llm_jni.cpp` + `app/src/main/kotlin/llm/NativeLlm.kt` – the app side (`LlmBackend`).
- Build: the app's CMake builds llama.cpp from `llama.dir` in `local.properties` (else `third_party/llama.cpp`, fetched
  by `tools/get-llama.sh`), arm64-v8a only, always optimised. The CPU kernels come in three variants (armv8.0;
  armv8.2 + dotprod + fp16; armv8.6 + i8mm) and the best one the phone supports is loaded at run time.
- `tools/llm-host-test.sh [model.gguf]` builds the engine for this computer and runs `host_test.cpp`: real NPC prompts
  from our game logic, timings, prefix reuse, cancellation and the failure paths (missing/invalid model).
- Threads: by default the big cores, at most 4, leaving two cores for the game. llama.cpp's threads spin-wait for each
  other at every step, so if one loses its core everything stalls (4 threads on a 4-core machine with other work
  running: 0.1-0.4 tok/s; 2 threads: ~11 tok/s).

## What was changed in the upstream engine
The three files that tied it to habit tracking were stubbed or adapted; `HabitsStage` gained a camera that follows
`GameState`; `BuildingPainter.spriteBuilding` draws our building sprites on the engine's box (side face and roof lean with
the view, lit by the engine); `furniture`/`shadows` draw our props and cast their shadows; `StreetLifePainter` draws our
pixel characters (and the player) and has no dogs, bikes or cars. All switches are in `shim/Tuning.kt`.

## Windows (spike)
`tools/make_buildings.py` finds the clean, window-shaped glass on a building's upper floors, cuts it out of the front
texture and writes each window's rectangle to `buildings/windows.json`. `BuildingPainter.spriteBuilding` draws, for each
window, the engine's room (a dim room by day, a warm lit room once its lamp is on) BEHIND the front, and the engine's
sky/street reflection and sun glint over it. Shop windows (they show goods) and arched or ornate windows are left as
they are. Not done yet: people and curtains in the windows, windows on the side faces.
