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
