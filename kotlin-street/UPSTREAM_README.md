# Street 2.5D: a pseudo-3D street scene, as built for the LifeMax Habits tab

This is the full source of the Habits tab's street scene from my Android app LifeMax (Kotlin + Jetpack Compose, drawn on a Compose `Canvas`). I want to reuse its approach and as much of its code as makes sense in my other game, but with my own art assets.

**Please read this file first, then `docs/HABITS_TAB_BRIEF.md`.** The brief is the design spec, and its "As built" sections describe what each piece actually does.

## What it is

A side-on city street that genuinely reads as 3D without a 3D engine:

- **Buildings are real boxes.** Each one has a front face, a side face and a roof, projected through an oblique camera that slides along the street. As you scroll, the side face widens or narrows and the roof shifts, as a real box does when you walk past it. The projection is in `domain/Perspective.kt`; building drawing is in `scene/BuildingPainter.kt`.
- **Parallax depth layers.** From back to front: the sky dome (`SkyDome.kt`), clouds (`CloudSprites.kt`), a far skyline (`Backdrop.kt`), a three-layer middle-distance city (`MidCity.kt`, `MidCitySprites.kt`), the street, then a near foreground.
- **Lighting that follows the geometry:**
  - sun direction and soft-penumbra shadows cast from the boxes (`SceneLight.kt`, `softBlock` and `shadeAt` in `BuildingPainter.kt`);
  - lamp light pools and spill (`LightSprites.kt`);
  - a sunburst where the sun peeks past a building edge (`domain/SunEdge.kt`, `scene/SunGlare.kt`);
  - a golden-hour "shooting into the sun" exposure (`domain/Backlit.kt`, `scene/Backlight.kt`);
  - lens flare (`domain/LensFlare.kt`).
- **Street furniture in 2.5D** (trees, lamps, benches), drawn with the same projection. People walk in front of it.
- **Pedestrians:**
  - 2D sprites baked from a procedural rig: `domain/Gait.kt` (gait-lab-accurate walk cycle), `StreetGait.kt`, `PeopleRig.kt`, `StreetRig.kt`;
  - behaviour: `StreetActs.kt` (phone, coffee, scratching and so on), `StreetManners.kt` (stops, overtaking), `StreetLife.kt`;
  - drawing: `scene/StreetLifePainter.kt`, `StreetRaster.kt`, `StreetSprites.kt`;
  - they darken evenly in building shade and pick up lamp light as they pass.
- **Sky life:** time of day, seasons, weather and mist (`domain/Dusk.kt`, `Season.kt`, `StreetWeather.kt`, `SeasonFall.kt`), a moon with phases (`MoonPhase.kt`), clouds passing over the sun and moon with a silver lining, planes that fly into clouds, and a blimp (`AirSprites.kt`, `Flight.kt`).
- **Building detail:** reflective window glass (`GlassSprites.kt`), a canvas awning (`AwningArt.kt`), a roof water tower and roof clutter (`RoofArt.kt`), weathering and decay (`FloorDecay.kt`), and cars (`CarArt.kt`).

## How it's built: the engineering rules worth keeping

1. **Bake once, draw every frame.** Pure-Kotlin "bakers" turn code into pixel arrays (ARGB `IntArray`s), which become `ImageBitmap`s cached in `SceneSprites.kt`. Each frame only draws cached images, with transforms, colour filters and blend modes.
2. **No allocation per frame.** Paints, paths, filters and scratch arrays are created once and reused. This keeps it smooth on a phone.
3. **Memory budgets enforced by tests.** Each sprite family has a byte budget; for example, walkers must stay under 20 MiB.
4. **One art set for every hour.** Colour comes from tints and colour matrices driven by the sky colours of the hour, not from separate day and night art.
5. **Logic is separate from drawing.** `domain/` is plain Kotlin with no Android code (the maths, timing and behaviour) and is unit-tested. `scene/` does the drawing.
6. **Deterministic.** Everything is a function of time and seed, so screenshot tests are repeatable.

## Folder layout

- `src/domain/`: pure Kotlin models and maths, the best part to port first.
- `src/scene/`: the Compose `Canvas` drawing. `HabitsStage.kt` is the entry point that composes every layer each frame. The draw order there is: buildings → lamp air → light sweep → furniture → people → car, plus the sky and glare passes.
- `src/app-specific/`: LifeMax's habit-tracking logic. **You don't need it.** `HabitsStage.kt` and `Silhouettes.kt` reference `HabitsProjection`, `HabitsRules` and `HabitCatalog` to decide which buildings and floors exist. That's the seam to replace with my game's own world data, such as a list of buildings per street.
- `tests/`: the unit tests, as worked examples and specs of the behaviour (the walk-cycle curves, shadow alignment, sprite budgets and so on).
- `docs/HABITS_TAB_BRIEF.md`: the design spec and "As built" notes.

Dependencies: Kotlin, Jetpack Compose (`androidx.compose.ui.graphics`), `android.graphics.Bitmap` in a couple of places, and kotlinx.coroutines for baking off the main thread. Nothing else.

## What I want from you

My other game will use this pseudo-3D style with platformer-like controls that aren't limited to left and right:
- depth lanes (sidewalk, road, far sidewalk), alleys and doorways;
- turning corners onto connecting streets by rotating the camera 90° around the vertical axis, with the corner building's side face becoming the new front;
- a world that is a network of streets, not free roaming.

I own a pack of 2D assets (front-view buildings, props, characters) that I want to use instead of LifeMax's procedural art.

Please:

1. **Assess my engine or framework.** Port this approach to it, or tell me if I'd be better off keeping Kotlin/Compose. If I'm using Godot or Unity, explain whether to reimplement the projection by hand like this code does or use real simple 3D geometry with a tilted orthographic or narrow-FOV camera, with the trade-offs.
2. **Map my assets onto the boxes.** Front-view building art becomes the front face. How should the sides and roofs be made: extend the texture, generate them as this code does procedurally, or paint them? Which props should be flat cards, simple boxes or real models? How do I ground character sprites (contact shadows, shading by depth, sorting)?
3. **Design the corner turn and street network.** The projection in `Perspective.kt` takes a camera offset; extend it with a camera yaw so a box's front and side widths become cos/sin of the angle, and store streets and intersections so corner buildings are shared between two streets.
4. **Reuse the lighting and sky systems.** Most of `domain/` (light direction, shadows, sun edge and glare, weather, seasons, gait) is engine-independent and can be ported directly.
5. **Start small.** Give me a step-by-step plan, beginning with a minimal test scene: one street, three box buildings with my art on their fronts, a sliding camera and one corner turn. Then layer in lighting, people and weather.

Be honest about what won't carry over well.
