# Timerpg street (Kotlin)

Our game's street, drawn by the lighting/sky engine from `UPSTREAM_README.md` (the LifeMax Habits street scene) with
**all of the art replaced by ours** (LimeZu): buildings, lamps, trees, benches and pedestrians. The engine keeps the sky
(sun, moon, stars, clouds, planes, weather, lens flare), the shadows cast from the real shapes, building shade and
night, lamp light and the window glow.

## Layout
- `shared/` – the engine's source (`domain/`, `scene/`) plus `shim/` (stand-ins for the habit tracker's types, the
  platform image helper, `StageTuning` switches, `GameState` (the player), `SpriteArt` (our art)).
- `app/` – the Android app (Compose). Controls: a floating stick under the left thumb (put the thumb down anywhere in
  the lower left and push: further is faster, the rim runs; up and down move in and out across the pavement); the
  buttons to talk, go in and turn the corner sit on the right, under the right thumb; drag the slider for the time of day. Arrow keys/WASD (Shift runs) and a game pad also walk. Landscape. See STATUS.md
  ("Movement and controls") for the numbers and `MoveTuning` to tune them.
- `desktop/` – a JVM module that renders the same scene to PNG without a phone: `gradle :desktop:preview`.
- `tools/export-street.ts` – exports a real town's shops from OUR TypeScript game logic (`street.json`: the exact
  sprite parts the game uses for each shop). The street and junction logic lives in `tools/town-streets.ts`.
- `tools/export-dialogue.ts` – exports who the player can talk to on those same two streets and what they say
  (`dialogue.json`, see "Dialogue" below).
- `tools/make_buildings.py` – composes box-building textures (front, side, window-glow, roof, sign) from our sprites.

## Rebuild the street data and the APK
```
npx tsx kotlin-street/tools/export-street.ts eta 2025 > kotlin-street/app/src/main/assets/street.json
npx tsx kotlin-street/tools/export-dialogue.ts eta 2025 > kotlin-street/app/src/main/assets/dialogue.json
python3 kotlin-street/tools/make_buildings.py kotlin-street/app/src/main/assets/street.json kotlin-street/app/src/main/assets/buildings
cd kotlin-street && ANDROID_HOME=/opt/android-sdk gradle :app:assembleDebug     # app/build/outputs/apk/debug/
gradle :app:testDebugUnitTest                                                     # the validate() port, the pre-generation queue, threads
gradle :desktop:preview                                                           # desktop/build/preview/*.png
```
(In the cloud sandbox Maven Central is rate-limited, so `settings.gradle.kts` uses Google's mirror of it.)
`street.json` and `dialogue.json` must be the same town and year (the app ignores the dialogue otherwise); the dialogue
exporter checks that every building with people on the two streets has someone to talk to, and that it agrees with
`street.json`, and fails if not. After changing `validate()` in `src/llm/factsheet.ts`, port the change to
`app/src/main/kotlin/llm/Dialogue.kt` and regenerate its test vectors:
`npx tsx kotlin-street/tools/validate-vectors.ts > kotlin-street/app/src/test/kotlin/ValidateVectors.kt`.

## Dialogue
Stand at a building and tap "Talk to <name>": the shop's owner, or a home's household head (and one other adult who lives
there). Every line comes from our game logic (`tools/export-dialogue.ts`): for each person and topic (greet, chat, work,
family, town; rumors and secrets, asked as a friend, for people who have any) `dialogue.json` holds the game's template line
(`narrate` / `ask` in `src/game/dialogue.ts`) and, for the topics the model may voice (`VOICED`), the full ChatML prompt
built from the fact sheet (`brief` / `toPrompt` in `src/llm/factsheet.ts`) with the words the reply must contain.

The language model (Qwen3-0.6B through llama.cpp, `llm/LlmBackend.kt`; `llm/Backends.kt` makes it `NativeLlm`)
only ever voices those facts. `dialogue/DialogueEngine.kt` runs it on one low-priority worker thread:
- the template shows at once; the model's reply streams in and replaces it if it passes `validate()` (ported exactly to
  `llm/Dialogue.kt`), else the template stays and is remembered as such (two tries per line);
- lines are written ahead of time nearest the player first (the buildings within two of you, then the rest of the
  street, then the other street from the crossroads), re-sorted as you walk, paused during the corner turn and in the
  background; a question you ask jumps the queue;
- results are cached by prompt in `filesDir/dialogue-cache.json` and survive restarts.

The model file is NOT in the APK. Tap the "AI" chip (top left), "Choose model file…", and pick
`qwen3-0.6b-q4_k_m.gguf` (about 400 MB, e.g. from Downloads): it is checked (GGUF header) and copied to
`filesDir/models/qwen3-0.6b.gguf` once. The same panel shows the model's state and speed (threads, load time, prompt
tokens reused, prefill and generation tokens per second, time to first token, lines written ahead / queued) and has a
speed test. Without a model, or on a phone that cannot run the engine (the panel then says so and offers no model
picker), everything works with the template lines.

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
  `--dialogue app/src/main/assets/dialogue.json [--npc b0 --npc Ray ...]` uses the app's own prompts instead (every
  topic the model may voice, as the app sends it) and checks that each is tokenized as one ChatML conversation (no
  BOS, special tokens parsed, `<|im_end|>` ends the turn); `--json-out FILE` writes the replies with their timings.
- `tools/llm-dialogue-check.sh [model.gguf] [options]` does all of that and then judges every reply twice: with the
  game's TypeScript `validate()` (`tools/check-replies.ts`) and with the app's Kotlin code (`HostRepliesTest`:
  `DialogueData` must read the very same prompts, `Dialogue.validate()` must give the same verdicts). Report in
  `/tmp/llm-dialogue-check/report.txt` (`$LLM_OUT`). Needs `ANDROID_HOME` and tsx (`$TSX`, default `npx tsx`).
- Threads: by default the big cores, at most 4, leaving two cores for the game. Big cores are those with at least half
  the fastest core's `cpu_capacity` (so the S25 Ultra's 8 Elite, which has no little cores, gets 4), else those faster
  than the slowest cluster by clock. llama.cpp's threads spin-wait for each other at every step, so if one loses its
  core everything stalls (4 threads on a 4-core machine with other work running: 0.1-0.4 tok/s, at times 10; 2 threads:
  11-26 tok/s).

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
