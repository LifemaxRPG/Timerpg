# Architecture cleanup plan (from the audit of 2026-11-23)

The audit was read-only: a symbol-reference graph over shared/ and app/. These are the findings checked by hand.
- **The Filament frame runs on the UI thread, inside Compose's draw phase.**
  - `GameActivity.kt:948` always composes the classic `HabitsStage` outside rooms.
  - Its `withFrameNanos` loop steps `TownPlay`, and its Canvas draw calls `Stage3D.drawn()`, which calls
    `FilamentHost.frame()`.
- **A full-screen blend every frame.** Filament's TextureView is transparent (`FilamentLayer.kt:316-320`,
  `CONFIG_TRANSPARENT`) over Backdrop's 2D sky Canvas, so the sky is composited under the town every frame.

Order (after W5a and the playtest recorder merge; the recorder measures before and after on the S25):

1. **A real game loop.** Move `TownPlay.step` and the Filament frame to a Choreographer-driven loop in FilamentHost (a
   render thread where safe). Compose only draws the HUD. Measure frame time and touch latency before and after.
2. **The sky in Filament.**
   - Make a sky dome from SkyColors and Backdrop's logic: sun, moon, stars and clouds in pixel art.
   - Make the swap chain opaque.
   - Stop composing HabitsStage in town mode.
   - **Done 2026-11-24 (town mode)**: FilamentTown draws Jeff's SkySurface (sky.mat, SkyState) on an opaque view and
     swap chain; HabitsStage is still composed (it drives the frame loop until step 1) but returns right after handing
     the frame over (`Stage3D.townSky3d`), drawing no Backdrop. The crossroads (non-town) mode keeps the 2D sky.
3. **Delete the classic renderer** (about 25-30k lines). First move the live bits out:
   - `StreetLooks`/`Bag`/`Wear`/`StreetLook` out of StreetRig.kt;
   - Habit* data types out of GameActivity and CornerWorld.
   Delete `PeopleActivities.kt` (dead), and the frozen-only tests.
4. **Delete the habits-app leftovers** (`app-specific/`, HabitStubs, the Habits*Test files). Then `:desktop:test`
   compiles without `-DtestOnly`.
5. **One path.** Retire FilamentStreet, CornerWorld, CornerWalk, TownWalk and the classic street.json loader. Keep a
   minimal crash fallback.
6. **Startup off the main thread:** the character sheets, `loadStreet` and the interiors' PNG decoding.
7. **`shared` as a real Gradle module,** compiled once rather than twice into app and desktop.
8. **Split the giant live files:**
   - GameActivity: GameScreen, AiPanel, NightPanel, settings;
   - YardKit: data, texture painters, mesh, items;
   - FilamentTown: build, frame, people;
   - DialogueEngine: lifecycle, generation, pre-generation.
   Add parity fixtures for the TS ports without one: WildChunks, RailNet.
9. **Delete the old Phaser web build** (`src/game/*` except dialogue.ts and art.ts, `src/main.ts`, `android/`,
   `index.html`, vite), after moving what the exporters still import.
10. **Rename the package** `com.lifemax.rpg.feature.habits.*` to a game package, last, in one mechanical pass.

Then write `docs/ARCHITECTURE.md`, a one-page map of where everything lives, for every agent to read first.
