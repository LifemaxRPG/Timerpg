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
gradle :app:testDebugUnitTest                                                     # the validate() port, the pre-generation queue
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

The language model (Qwen3-0.6B through llama.cpp, `llm/LlmBackend.kt`; `llm/Backends.kt` says which engine the app uses)
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
speed test. Without an engine or a model everything works with the template lines.

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
