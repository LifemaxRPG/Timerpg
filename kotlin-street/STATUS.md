# Status (Kotlin street prototype)

## What works (verified by rendering the same code on a computer; NOT yet run on a phone)
- The habit scene's lighting/sky/shadow engine drives everything; all art is ours (LimeZu): buildings, lamps, trees,
  bench, pedestrians and the player. No dogs, bikes, cars, or the habit tab's foreground.
- Buildings are the exact ones our TypeScript game draws for each shop (`tools/export-street.ts` -> `street.json`),
  composed into boxes by `tools/make_buildings.py`: front, matching side wall, flat roof, window-glow mask, rooftop sign.
  Signs sit over the front face as one piece (only the part above the roofline goes on the roof).
- Upper-floor windows are real openings showing plain reflective glass (engine sky reflection + sun glint); the habit scene's furnished rooms/people in the windows are off (`StageTuning.interiors`); the buildings themselves can now be entered (see Interiors below). Shopfront glass keeps its painted display and gets a faint reflection through a mask (`bN_glass.png`, display panes at ~40% strength). Arched/ornate upper windows are left as sprites.
  reflections and sun glints over. Shopfront glass keeps its painted display and gets a faint reflection through a mask (`bN_glass.png`, display panes at ~40% strength). Arched/ornate upper windows are left as sprites.
- Walking: a floating on-screen stick under the left thumb (see "Movement and controls" below); camera follows with a
  look-ahead. Time slider; season
  (spring/summer/autumn/winter) and weather (clear/overcast/rain/mist) buttons; trees are tinted by season.
- `gradle :desktop:preview -Dmode=pano|weather|rooms|walk` renders PNGs without a phone (see desktop/build/preview).

## Added since (all verified by headless renders only)
- Pixel-perfect scaling (zoom snapped to whole pixels; `StageTuning.pixelPerfect`), props/characters at 1x/1.5x pixel scale.
- Shop name and owner shown at the bottom while standing at a shop. Collisions: walk round lamps/trees/benches at the kerb (radii are guesses).
- Corner turn (`shared/.../scene/CornerTurn.kt`; the "Turn the corner" button appears within ~110 units of either end of the street): the camera yaws 90 degrees round a crossroads built
  from our buildings (player walking, time-of-day light, lit windows and lamp glow at night), then fades to the other street
  (the town's shops are split into two). It follows time of day, the weather setting (overcast greying, rain streaks, mist haze) and the season (winter: snowy roofs/pavement and flakes; autumn: warmer pavement). It is a separate scene: it does not use the engine's sun/weather/reflections yet, and
  the junction is not a real place in the world map.
- Side walls: three windows per floor, pilasters, grain, brick courses. Night: only real glass glows (fixed blue walls glowing).
- Engine cloud-shadow pass is off for sprite buildings (it drew pale boxes above roofs). A building with a missing PNG no longer
  drops the whole street to the fallback.
- Preview modes: `gradle :desktop:preview -Dmode=pano|weather|rooms|walk|turn|move|stick [-Dstreet=0|1] [-Dhour=H]`.

## Movement and controls (verified by unit tests and headless renders; NOT yet run on a phone)

- **The stick** (`shared/.../scene/MoveStick.kt`, maths in `shim/Stick.kt`, tests `app/src/test/kotlin/StickTest.kt`): one
  floating stick replaces the four arrow keys. It rests faint (35%) at its home 84 dp in from the lower-left corner; a thumb
  landing in the left 45% of the screen (at most 380 dp), below its top 30%, brings it there at rest, so every start is
  deliberate whatever the grip. Knob travel 48 dp (about 9 mm), ring 60 dp; a 12% dead zone (the push starts from 0 at its
  edge, no jump); within 15 degrees of the street a push is straight along it, within 10 of vertical straight in or out
  (the rest of each quarter is stretched, so sweeping round stays smooth); dragged 16 dp past the rim its centre follows
  the thumb, so turning round is never a long drag back. At the rim the knob turns blue and one light haptic tick marks the
  run. The ring is drawn where the thumb landed (the knob always under the thumb; by an edge the ring runs off the screen),
  and a thumb landing nearer the bottom or left edge than the travel reaches a full push that way in the room left (never
  less than half the travel). Buttons and panels drawn over it get their taps first (panels are opaque to touches); the
  game's buttons ("Talk to", "Go inside"/"Leave", the corner turn) sit on the right, under the right thumb, clear of its
  zone. It is still (and hidden) while the corner turns, a door fades, the AI panel, the crash report or the NPU tuning
  is open, but a held thumb keeps its place; after a door, when a conversation opens and when the corner turn starts, a
  held push counts again only once the thumb is back within 35% of the travel or lifted (`MoveInput.holdUntilNeutral()`).
  Focus loss and pause let go of everything (the game pad's last stick and D-pad values too). The bottom 200 dp of its
  corner is excluded from the system's back gesture (Android allows no more of an edge). Leaving a room needs a
  push mostly down (moveLane > 0.5 and more than sideways). A keyboard (arrows/WASD walk, Shift runs) or a game pad's
  stick and D-pad drive the same input. `GameState.moveX`/`moveLane` stay the interface (-1..1; up is moveLane < 0).
- **The legs** (`shim/Locomotion.kt`, tunable live in `MoveTuning`; tests `app/src/test/kotlin/MovementTest.kt`): the push's
  strength sets the speed: up to half way it scales a slow walk (lining up at a door), then a flat walk band, and from 85%
  a run that lasts until the push drops below 75% (the old 0.6 s hold-to-run timer is gone). One ground velocity eases
  towards it (time constant 0.05 s, at most 1100 units/s² into a walk, 480 into a run, 1400 slowing or turning), shared by
  both axes so a diagonal stays straight. Across the pavement a lane is 1/0.16 of a unit of ground, so walking in or out
  moves the legs truthfully (no treading water). The walk cycle's phase comes from the ground really walked (after
  furniture), 26.7 units a cycle up to a walk and a runner's longer stride beyond (x (speed/walk)^0.6); footfalls are
  counted at the cycle's landing frames and each plays one footstep (in sync with the drawn steps, street and rooms). The
  view (side, back, front) comes from the push's angle: side within 60 degrees of the street, back/front beyond 70,
  unchanged between. Footstep sounds alternate a louder and a softer foot, so a stride is heard as one beat.
- **The run's frames** (`scene/RunFrames.kt`): the sheets have no run, and the art's legs are four pixels tall: a walking
  step carries a planted foot 6.5 of the art's pixels (21.4 units a cycle), so the walk's frames at a run's speed left
  about half the ground sliding under the feet. Seen from the side, the run's frames splay the legs (the reaching foot two
  art pixels further forward, the trailing one two further back, less higher up the leg; the passing frames unchanged),
  made at load from each sheet by repeating the column between the legs, so outlines stay whole and every pixel stays on
  the art's grid. A step then carries the foot 10.5 pixels (34.5 units a cycle): the legs carry 83% of the ground at a
  full run, against 80% at a walk (26.7 units a cycle against the drawn 21.4). They show from 30% of the way from a walk to
  a run, with the spring (the raised frames one art pixel higher). The lean is drawn by whole art pixels: the body above
  the hips one pixel ahead of the legs at a run (tan(lean) x 10 art pixels, rounded), no shear, nothing off the grid.
- **The camera** (`CameraLead`, HabitsStage's game loop): looks ahead of a moving player by 8% of the view at a full run
  (in proportion below), through a critically damped spring (0.45 s) capped at 20 + 0.4 x speed units/s as it grows. When
  the player slows it shrinks at most half their speed, so the view never runs back against them; when they stop, the view
  stops with them (within a frame or two of their last step) and the lead stays where it is until they walk again, so the
  street never slides on after a stop or behind a conversation. Through a door it is centred again. It is snapped to whole
  device pixels so the player never flickers, and it eases into the street's ends over 80 units (the camera's speed stays
  continuous; it used to stop dead from 365 px/s in one frame). The player is redrawn every frame they change, also while
  the camera stands at an end (the street's draw used to follow only the camera and the 30 Hz clock there, so the player
  moved 30 times a second), and while the player or the camera moves the street's clock (passers-by, cars) ticks every
  frame instead of 30 times a second.
- **Rooms**: `RoomWalker` uses the same legs in room pixels (1 room pixel = 1 sprite pixel), 1.2x quicker than the street
  (`MoveTuning.roomPace`; rooms are short walks), the stride grown with the speed so the cadence is the street's; the
  run's frames sideways; speed from the push; a blocked axis stops dead (no stored speed bursting out along a wall);
  facing by the dominant axis with 1.25x hysteresis.
- **Tests** (`app/src/test/kotlin`): `StickTest` (the stick's maths, re-arming, the edge reach), `MovementTest` (speeds,
  starts, stops, the run, facing, the legs against the art, the camera's look ahead, stop and ends, rooms) and
  `ControlsTest` (MoveInput's gating: closed, held until neutral, let go, the pad against the stick; GameState's steps
  across the pavement and round the lamps). The stick's touch handling (several fingers, buttons over it) is checked only
  by the desktop render `-Dmode=stick`.

Old against new (street units: 1 unit = 2.43 device pixels at FHD+ and QHD+ alike; the figure is 36 units, 88 px tall).
All "new" numbers are from the integrator run at 120 and 60 Hz on a computer (`-Dmode=move` prints them), not the phone:

| | before | now |
|---|---|---|
| Walk | 46 u/s (21 s to cross a 2340-px screen, 5.1 s a building) | 72 u/s (13.3 s a screen, 3.3 s a building) |
| Run | 87 u/s after holding one way 0.6 s, then a ~80 ms jolt (11 s a screen) | 150 u/s at the rim (6.4 s a screen at FHD+, 8.5 s at QHD+, 1.6 s a building) |
| 0 to 90% of a walk / of a run | 0.13 s / 0.70 s (incl. the hold) | 0.125 s / 0.20 s (0.133 s / 0.20 s at 60 Hz) |
| Stop | from a run 4-5 units | from a walk 3.3 units, still at 0.25 s; from a run 9.1 units (a quarter body), still at 0.31 s |
| In and out (kerb to wall, 11 lanes) | 7 lanes/s linear, 1.57 s | eased, 11.5 lanes/s walking (0.95 s), 24 running (0.46 s) |
| Cadence | walk 1.73 cycles/s, run 3.28 (legs patter, world creeps; in-depth legs slid) | walk 2.7 cycles/s (5.4 steps/s), run 3.6 (7.2 steps/s, stride 41.4 u) |
| Ground the drawn legs carry | walk about 80%, run about 52% (the walk's frames) | walk 80%, run 83% (the run's frames) |
| Footstep sounds | one per 17 units against a drawn step every 13.3 | one per drawn footfall, alternating louder and softer |
| Camera | locked on the player | up to 77 units (187 px) ahead at a run (FHD+), 103 at QHD+; 90% there within 1.5 s of a run from standing, 0.75 s from a walk; still when the player is (0.25 s after letting go of a walk, 0.31 s of a run), never moving back |
| Street ends | the camera stopped dead (365 px/s to 40 in one frame at a run); the player drawn ~30 times a second there | eased in over 80 units (largest one-frame change 49 units/s, two pixels a frame at 60 Hz); the player drawn every frame |
| Rooms | 96 px/s whatever the push, feet skating 2x | 105 px/s walking, 219 running, from the push |

Still to measure on the S25 (all estimates now): the real frame rate (the AI panel now shows frame times and the refresh
asked for; the app asks for the display's fastest mode); whether 48 dp of knob travel suits the thumb; whether 5.4 steps/s
at a walk reads brisk or pattering (the art's short legs leave a choice between a quick cadence and sliding feet:
`MoveTuning.stride` longer means fewer steps and more slide); the run's splayed frames, the one-pixel lean and the hop in
motion; heat at 120 Hz with continuous redraw while moving. The desktop render runs the game's frame loop a step out of
time with its drawing (it renders each frame twice in `-Dmode=move` to measure); on the phone both happen in one frame.

## Real crossroads (tools/export-street.ts v2)
- street.json now holds named streets from the town's real layout: Main Street (the buildings on the main road west of a real
  crossroads, in order) and the cross street (the buildings along the road that leaves the main street there, named from the seed),
  plus the junction's other buildings for the turn scene. Homes are stood in by the modular condo front in the district's colour
  (front door for a family, lobby for flats), named after the household really living there.
- The turn shows the junction's real buildings; its button appears only at the junction end of each street ("Turn onto <name>" /
  "Back to Main Street"), and turning back plays the turn in reverse and leaves you at the junction end of Main Street.
- Demo town is still "eta" (Main Street: 8 shops; Orchard Lane: 6 homes). Checked on 9 seeds: all give 7-8 on Main Street, 2-6 on the cross street.

## Talking to people out loud (verified on a computer; NOT yet run on a phone)
- In a conversation, hold "Hold to talk", say anything, let go: `voice/PlayerVoice.kt` records 16 kHz mono (at most 20 s),
  trims the silence and `llm/NativeAsr.kt` writes it down with **Qwen3-ASR 1.7B** (Q8_0, 2.2 GB + 356 MB encoder; llama.cpp's
  mtmd library in the same native library, `cpp/asr_core.cpp`), with the town's and everyone's names as hints. The
  NPC's topic nearest the words is chosen (`DialogueEngine.topicFor`) and Qwen answers the player's exact words in that
  topic's fact sheet (`withQuestion`), checked by `validate()` as always, then spoken in the NPC's voice.
- Writing ahead pauses while the player speaks and while the words are written down (the CPU is theirs).
- Measured on the 4-core x86 server (`asr_host_test`, our Kokoro clips): every clip word for word, names included ("Ray
  Vance", "Holloway's Bakery"); ~0.4x real time (a 5 s question in ~2 s) after a one-time load. The S25: not measured yet.
- The AI panel's download fetches the speech model with the rest (about 8.9 GB in all). The microphone permission is
  asked the first time the button is held.

## GPU rain (verified in the desktop preview with Skia's runtime shaders; NOT yet seen on a phone)
- `scene/RainFx.kt`: shaders the platform runs (`StageFx`: Android 13+ RuntimeShader/AGSL via `AndroidFx`, the preview
  Skia RuntimeEffect/SkSL via `SkiaFx`; the same source). `GLASS`: beads on every window pane and the shop glass (a bright
  face, a dark lip) and drops running down with trails; `GROUND`: puddles that hold the sky in a wide, flat pattern
  that grows with how wet the street is, and the rain's rings across the wet ground (thicker in the puddles). Pixel-crisp
  on the sprites' grid, anchored to the street. Older phones keep the drawn rain only.
- `SHEEN`: at night on the wet ground each street lamp's light lies in a long, broken warm streak (up to eight lamps in
  view). `FOG`: mist as banks drifting slowly along the street, thickest low down, over a lighter even veil; it takes the
  light there is (pale by day, dark blue-grey at night).

## Depth and light on the building fronts (verified in the desktop preview; NOT yet seen on a phone)
- `tools/make_lighting.py` (run after `make_buildings.py`, on a computer): **Depth Anything V2 Small** (Apache-2.0, ONNX)
  estimates each front's depth; the scene-wide slope it reads into a picture is taken out, and the depth is snapped to
  the art (each patch of one colour takes its median depth), so light and shade fall along the pixel art's own lines.
  From its normals: `bN_lit.png` / `bN_shade.png` (R, G, B: brighter / darker than a flat wall with the sun from the
  left, the right, above; shade's A: recesses). The shopfront glass is left out (the display is not the wall).
- `BuildingPainter.relief`: by the sun's side and how direct it is, the maps are laid over the front (strength inside the
  colour filter: a paint's alpha is applied before its filter); recesses stay a little dark at night. Windows read as
  set into the wall, ledges and sills throw a thin shadow, signs and the gym's dumbbells have volume.

## People remember (verified on a computer; NOT yet run on a phone)
- Every exchange said out loud is remembered by that person (`dialogue/NpcMemory.kt`, `MemoryService.kt`; saved in
  `filesDir/memories.json`, 200 per person): the player's words and the answer, with a vector from **Qwen3-Embedding-0.6B**
  (native, `cpp/embed_core.cpp`), made once when stored. When the player speaks, the memories that stand out for those
  words (2 standard deviations above the person's mean likeness; a floor with few memories) and the last exchange (10
  minutes) go into the fact sheet, and someone who remembers the player no longer calls them a stranger.
- Which memories go in: the embedder's closest four, judged by **Qwen3-Reranker-4B** (reads what is said and a memory
  together, answers yes or no; at most two yes), plus the last exchange; when nothing comes close the judge is not asked.
  Measured (`tools/memory-eval/cases.json` with `eval_memory`: 30 memories with look-alikes, 20 references, 10 unrelated
  lines): embedder alone 20 of 20 found but something recalled for 8 of the 10 unrelated; with Qwen3-Reranker-0.6B 18 of
  20 and 10 of 10; with the 4B 20 of 20 and 10 of 10, ~4 s per line on the 4-core server (judging 4 memories; the S25:
  not measured, estimated under 1.5 s). Qwen3.5-9B uses them: "Any news about my dog?" -> "No sightings yet. I told you,
  keep your eyes open too."; "Clara's wedding is coming up soon." -> "I hope the preparations go smoothly for her."
- RAM: every model runs in one lane (`llm/AiLane.kt`: dialogue, speech, memory, voices take turns), weights are mapped
  from their files (an idle model's pages can be dropped), the speech and memory models load when someone to talk to is
  near and are let go after a minute idle, the voice engines after 30 s. The AI panel shows the phone's free RAM, the
  game's own and what the lane is running, to measure it on the S25.

## Dialogue and the native dialogue AI (verified on a computer; NOT yet run on a phone)
- `tools/export-dialogue.ts` -> `assets/dialogue.json`: the people at each building of both streets (shop owners; a home's
  head and one other adult), with the game's template lines and the model's prompts for each topic (see README).
- "Talk to <name>" at a building opens a dialogue panel (topics, the reply streaming in, a marker for AI vs built-in line);
  walking away or turning the corner closes it. The "AI" chip (top left) opens the model and speed panel.
- `dialogue/DialogueEngine.kt`: one low-priority worker owns the model; lines are written ahead nearest-first (paused in
  the corner turn and in the background), questions jump the queue, replies pass the `validate()` port or the template
  stays; results cached in `filesDir/dialogue-cache.json`. Model file picked once with the system picker (GGUF header
  checked, copied with progress).
- The engine is `NativeLlm` (`llm/Backends.kt`): llama.cpp built into the APK (arm64-v8a; CPU kernels armv8.0 /
  armv8.2+dotprod+fp16 / armv8.6+i8mm, best one picked at run time), KV prefix reuse, old sampler settings, streaming,
  cancel, errors instead of crashes (missing/invalid model, no native library, low memory).
- The model is **Qwen3.5-9B** (Q4_K_M, 5.7 GB; Apache-2.0), the strongest Qwen that fits a phone (Qwen3.6/3.8 are 27B
  and up). The AI panel downloads it (and both voice engines) from Hugging Face with resume, or a file can be picked.
  Qwen3.5 is a hybrid model (Gated DeltaNet + attention): its recurrent state cannot be cut back, so the engine saves
  snapshots of the state at each line end of the fact sheet (`Engine::take_snapshot`, LRU of 8) and starts the next
  prompt from the longest one it begins with (the shared rules for anyone, the person's lines for the same person).
- Measured on a computer (4-core x86 cloud machine, NOT the S25), `tools/llm-dialogue-check.sh`, all 75 prompts:
  Qwen3.5-9B: 73 of 75 (97%) pass `validate()` (Qwen3-0.6B: 67-68 of 75, ~89%), replies in character ("Get off my
  porch before I call the sheriff."); ~33-44 tok/s prompt processing, ~2.5-5 tok/s generation, ~12 s per line before the
  snapshots; with them 58% of prompt tokens come from a snapshot and a line takes ~6.4 s (first token ~2 s). The S25 Ultra's 8 Oryon cores (6 threads) should be several
  times faster: an estimate until measured there. Kotlin and TypeScript `validate()` agree on all 75.
- `validate()` (both copies) also rejects a name the prompt never mentions (a capitalized word mid-sentence), the fact
  sheet's way of speaking read back ("A curt, measured response: ..."), and the "<town>, <year>" tag.
- Guesses to check on a device: the free-memory thresholds for loading, the 45 s reply timeout, two tries per line, the
  thread count (6 on the S25 Ultra by its cpu_capacity, 2 if only clocks can be read), the panel sizes on small
  landscape screens.

## Known rough edges / not done
- Untested on a real device (touch, speed, memory, the turn's draw cost). Android-only code: image helper, asset loading,
  the stick's back-gesture exclusion, keyboard and game pad input, the refresh-rate request.
- Collision radii and the "near the kerb" band are guesses. The turn's far buildings show the striped side art at grazing angles.
- Storefront glass lights as a flat warm panel at night; sign letters that are pale blue also light up (mask treats them as glass).
- People in shop windows, doors/interiors, NPC schedules and dialogue are not connected to this renderer. The street is a fixed
  snapshot (re-run `tools/export-street.ts <seed> <year>` and `tools/make_buildings.py` to change town).

## Ideas for next
- Feed the street from live game logic (port the TS logic to Kotlin, or export on demand); a real junction between streets.
- Residents appear in their own windows at the times their schedules put them home. Entering shops.


## Interiors (Wave Function Collapse)

- "Go inside" at a building opens its room: `src/town/interiors.ts` plans the walls and room divisions (keyed to the
  property), `src/town/furnish.ts` furnishes each area by Wave Function Collapse (`src/town/wfc.ts`): cells in a
  superposition of "empty" and every placement that could cover them, lowest-entropy cell collapsed by seeded chance,
  overlaps/blocked walkways/counts propagated, neighbour affinities in the weights (kitchen runs, chairs only under
  tables), required pieces first, the finished room checked for reachability and re-solved from another branch of the
  seed if it fails. Homes follow the household (a bed per couple/single, green beds and toys for children, a piano or
  guitar for a musical resident, desk and books for a studious one); shop stock grows with capital. The large public
  buildings keep their drawn layouts.
- `tools/export-interiors.ts eta 2025 > app/src/main/assets/interiors.json`; sprites in `app/src/main/assets/interior/`.
- Kotlin: `shared/.../scene/Interior.kt` draws the room fitted to the screen, top-down 3/4: our furniture sprites with
  contact shadows, walls/windows/skirting as geometry, daylight shafts by the hour, a night tint with warm lamp pools; the
  player walks with the same stick and the same legs as on the street (feet collide with footprints), the shop keeper or resident stands by their counter or
  sofa and can be talked to when near; walking out of the door or "Leave" returns to the street.
  Desktop preview: `gradle :desktop:preview -Dmode=inside -Drooms=b0,b5`.
- Not yet: outdoor sound is not muffled inside; upstairs/large civic buildings are not on the two streets.
