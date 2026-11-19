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

## The walkable town in the app (T10; desktop renders and JVM tests only, NOT yet run on the S25)

- The app now opens in the whole 3D town `gamma` (Filament, the default renderer): `assets/town/gamma/` ships street.json v2
  (graph, lots, yards), buildings/ (849 files), dialogue.json v2 (140 people at 92 buildings, template lines; the model
  writes the rest on the phone on demand and ahead by distance) and interiors.json (92 rooms); `assets/town/art/` the 37
  pack pictures the ground, greenery and props ask for. The old eta files stay at the assets' root for the crossroads.
- Start: `TownSession` (app) reads and parses the town on a thread, `TownPlay` (shared/shim) spawns the player on Civic
  Avenue (row 8L) and takes the stick/keys through `TownWalk` (along the row, straight on over zebras, up in a mouth turns
  in, down at the kerb crosses, dead ends stop) in place of the stage's crossroads walk (HabitsStage's game loop calls
  `TownPlay.step`). The Filament layer (`FilamentHost`, town mode) builds `FilamentTown` through `TownRun` (scene3d): the
  ground on workers, uploads and buildings nearest the player in a 14 ms budget a frame behind a loading screen (progress
  bar) until every building within 1800 units is in, then 4 ms a frame; drawn through `TownFrame.street` (CameraRig's
  eye preset from the AI panel). The stage's sky stays under it.
- People: `TownPlay.people` stands the street life (StreetLife's passers, seeded per row) and the people to talk to at
  their doors on the rows `liveRows()` keeps, plus the player; `TownPeople` (scene3d) draws them as lit cards of our
  character sheets with shadow twins, cut with the near side. "Talk to <name>" and "Go inside" work at the building in
  front; the always-listening mic picks the nearest person at their door on this row (`TownPlay.nearestKeeper`), and the
  speech bubbles anchor on heads projected through the true camera (`TownPlay.projector` = `FilamentTown.project`).
- DialogueEngine: `useTown("town/gamma/")` loads that folder's dialogue.json against its street.json (same town and year);
  the pre-generation queue orders by rows (`setPlayerRow`: this row, then the live rows nearest first, then the rest).
  Cached lines of both towns are kept.
- HUD: "<street> · <neighbourhood> · gamma · Commuter suburb", the building in front and its owner, "▲ <street>" at a
  corner; time/season/weather chips as before. AI panel: "Town: gamma / Old crossroads" (the classic renderer always
  plays the crossroads).
- Crash reports' breadcrumbs: town load start/end/failure, first view ready (ms), every row change, turn and crossing
  start/end, the whole build's times when it ends, memory (graphics, native, Java, total PSS) at the first view and when
  the whole town is built; the screenshot/crash state JSON has a `walkableTown` block (also while loading: progress,
  error; then row, street, place, transit, live rows, people, the build line and the memory).
- Crash guard: a `townBuilding` mark is on from the scene's start until every building and the ground are in; a launch
  that finds it plays the old crossroads on Filament (the AI panel switches the town back on). The "starting" mark counts
  its 30 frames only after the first view. A town exception also falls back to the Filament crossroads, not the classic.
- Frame time and heat: building is done on the workers (`BuildingMeshes.prepare`), the engine's thread only finishes
  each building (desktop: slowest 156 ms before, about 20 ms now); frames come at the display's rate only while the
  player moves, a transit runs or the town is still building, else at the stage's 30 Hz clock; the stage skips the old
  crossroads' work under the town. While the first view is built the player cannot move and the dialogue's writing
  ahead is paused ("town-load"). The player's world position is kept for the next start.
- Desktop: `-Dshots=cold` builds a fresh town scene through TownRun from the spawn as the app does (loading budget, then
  walking) and writes the time to the first view and the main thread's frame work to town.txt, and cold_first_view.png.
- Memory (the town ran the S25's Java heap out: OutOfMemoryError on a 256 MB heap before largeHeap): the cold build held
  456 MB of live heap and 349 MB of direct buffers at its peak (722 MB together; on Android a direct buffer is a Java
  array); now 93-101 MB and 105-118 MB (190-205 MB), desktop, measured with a full GC every 100 ms (`-Dmemlive`). What
  held it: the buildings' night-light arrays (six atlas-sized int arrays a model, five a front, kept for good: ~240 MB;
  now only the glowing and glass pixels, the mask written straight into Filament's buffer), the whole town's 3D trees
  packed up front and kept (230 MB direct; now packed on a worker as a chunk comes near and let go after upload), the
  ground's unbounded upload queue (now waits at 32 MB), every building made ready at once (now workers + 2), the yard
  kit's model cache (60 MB, now 16 MB LRU and cleared after the yards), zero masks for pictures that never glow, each
  chunk's own copy of a shared mesh's picture, and the classic stage's sprite warm-up under Filament (now only the sky's:
  `SceneSprites.warmSky`, the far road's cars after the town is built). `gradle :desktop:townMemoryCheck` builds the
  whole town cold at `-Xmx320m` and fails over 256 MB held (now 193 MB). The renders are unchanged (the play shots
  pixel-identical). The phone logs the heap's limit and the build's peak to the breadcrumbs and the AI panel's RAM line;
  a failed build step says which and the heap at that moment. Not yet measured on the S25.
- Desktop: `gradle :desktop:preview -Dmode=filament-town -Dshots=play` runs the same TownPlay + TownRun path: play_spawn_13h,
  play_walk_13h, play_mouth_dusk, play_turn_13h, play_turned_13h, play_cross_night, play_crossed_night, with the bubble
  anchors marked (yellow: people to talk to, cyan: the player). Tests: desktop TownPlayTest (the shipped assets agree;
  spawn, walk, turn, cross, events; people on the live rows; who hears), app PregenQueueTest (rows).
- Not yet / to check on the S25: frame time and heat of the whole town with people; the time to the first view; GPU
  memory with the dialogue model loaded (now logged; 652 textures, 67.8M texels: compressed textures and lighter far
  yards, e.g. sideFence 429k and greenhouse 278k triangles, are the levers if it is too much); passers beyond the live
  rows (long views show empty pavements further off); the
  stage's sky does not turn with the town's heading (it keeps the crossroads' yaw: the sun Filament lights by follows the
  view, so they can disagree); passers are drawn as plain walkers (no dogs, bikes, pairs, cars) and only on live rows;
  landmarks have nobody to talk to; going into a room keeps the town's scene (drawn under the room) but a renderer switch
  rebuilds it.

## Added since (all verified by headless renders only)
- Pixel-perfect scaling (zoom snapped to whole pixels; `StageTuning.pixelPerfect`), props/characters at 1x/1.5x pixel scale.
- Shop name and owner shown at the bottom while standing at a shop. Collisions: walk round lamps/trees/benches at the kerb (radii are guesses).
- The corner turn is now the crossroads and the walk round it (see "The crossroads and the walk round the corner" below).
  The old separate turn scene (`scene/CornerTurn.kt`) is no longer used by the app; the desktop `turn`/`turnsky`/`cross`
  modes still render it, and its 180-degree street crossing is kept until it is ported.
- Side walls: three windows per floor, pilasters, grain, brick courses. Night: only real glass glows (fixed blue walls glowing).
- Engine cloud-shadow pass is off for sprite buildings (it drew pale boxes above roofs). A building with a missing PNG no longer
  drops the whole street to the fallback.
- Preview modes: `gradle :desktop:preview -Dmode=pano|weather|rooms|walk|turn|junction|seam|move|stick|houses|models [-Dstreet=0|1] [-Dhour=H]`.

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
  less than half the travel); the faint inner ring that marks the run comes in that way to match (a quarter ellipse each
  way). A full walk holds from 4.3 to 8.5 mm out (it was 5.1 to 7.9: too narrow a band to stay in), the run at the rim.
  Buttons and panels drawn over it get their taps first (panels are opaque to touches); the game's buttons ("Talk to",
  "Go inside"/"Leave") sit on the right, under the right thumb, clear of its zone. Turning the corner is a push up in the
  side street's mouth (mostly up: a diagonal walking past does not turn; see the crossroads below), and the stick stays
  live through the walk round it (down walks it back). It is still (and hidden) while a door fades, the AI panel, the crash
  report or the NPU tuning is open, but a held thumb keeps its place; after a door and when a conversation opens, a held push counts
  again only once the thumb is back within 35% of the travel or lifted (`MoveInput.holdUntilNeutral()`; a thumb already
  that near the middle is not held), and a held key or game pad stick likewise only once let go or back near the middle
  (its repeats no longer bring the push back). Focus loss, pause and a game pad or keyboard going away let go of
  everything. The bottom 200 dp of its corner is excluded from the system's back gesture (Android allows no more of an
  edge); higher up the edge a back swipe still happens, so back is the game's: it closes the crash report, a conversation
  or the AI panel, leaves a room, and on the street takes two within 2 s to leave the game. Leaving a room needs a push
  mostly down (moveLane > 0.5 and more than sideways). A keyboard (arrows/WASD walk, Shift runs) or a game pad's stick and
  D-pad drive the same input. `GameState.moveX`/`moveLane` stay the interface (-1..1; up is moveLane < 0); `MoveInput`
  rewrites them every frame, so code that walks the player itself sets `MoveInput.scripted` instead.
- **The legs** (`shim/Locomotion.kt`, tunable live in `MoveTuning`; tests `app/src/test/kotlin/MovementTest.kt`): the push's
  strength sets the speed: up to 40% it scales a slow walk (lining up at a door), then a flat walk band, and from 92% (the
  rim) a run that lasts until the push drops below 80% (the old 0.6 s hold-to-run timer is gone). One ground velocity eases
  towards it (time constant 0.05 s, at most 1100 units/s² into a walk, 480 into a run, 1400 slowing or turning), shared by
  both axes so a diagonal stays straight. Let go, the stop never slows gentler than 900 units/s², so it ends in a firm last
  step and the figure stands within 0.08 s of letting go of a walk (it used to glide on one frozen walk frame, after a
  run sometimes a raised one, for a quarter of a second). Pushed the other way, the figure keeps facing the way it slides
  until that is under 12 units/s (a skid leaning back, 0.05 s from a walk, 0.1 s from a run), never a moonwalk; the rooms
  turn the same way. Across the pavement a lane is 1/0.16 of a unit of ground, so walking in or out
  moves the legs truthfully (no treading water). The walk cycle's phase comes from the ground really walked (after
  furniture), 26.7 units a cycle up to a walk and a runner's longer stride beyond (x (speed/walk)^0.6); footfalls are
  counted at the cycle's landing frames and each plays one footstep (in sync with the drawn steps, street and rooms). The
  view (side, back, front) comes from the push's angle: side within 60 degrees of the street, back/front beyond 70,
  unchanged between. Footstep sounds alternate a louder and a softer foot, so a stride is heard as one beat.
- **The run's frames** (`scene/RunFrames.kt`): the sheets have no run, and the art's legs are four pixels tall: a walking
  step carries a planted foot 6.5 of the art's pixels (21.4 units a cycle), so the walk's frames at a run's speed left
  about half the ground sliding under the feet. Seen from the side, the run's frames splay the legs (the reaching foot two
  art pixels further forward, the trailing one two further back, less higher up the leg; the passing frames unchanged),
  made at load from each sheet. The art draws the legs as one block, so where they part the gap is left open with the
  art's outline down each leg's inner side (stretching the block across the gap read as a plank under the body); every
  pixel stays on the art's grid. A step then carries the foot 10.5 pixels (34.5 units a cycle): the legs carry 83% of the ground at a
  full run, against 80% at a walk (26.7 units a cycle against the drawn 21.4). They show from 30% of the way from a walk to
  a run, with the spring (the raised frames one art pixel higher). The lean is drawn by whole art pixels: the body above
  the hips one pixel ahead of the legs at a run (tan(lean) x 10 art pixels, rounded), no shear, nothing off the grid.
- **The camera** (`CameraLead`, HabitsStage's game loop): looks ahead of a moving player by 8% of the view at a full run
  (in proportion below), through a critically damped spring (0.45 s) capped at 20 + 0.4 x speed units/s as it grows. When
  the player slows it shrinks at most half their speed, so the view never runs back against them; when they stop, the view
  stops with them (within a frame or two of their last step) and the lead stays where it is until they walk again, so the
  street never slides on after a stop or behind a conversation. Through a door it is centred again. It is snapped to whole
  device pixels so the player never flickers, and it eases into the street's ends: from 80 units short of one its speed
  falls steadily and it rests exactly on the end once the player has walked 160 units into the ease (about 2.2 s walking,
  1.1 s running; at first it stopped dead from 365 px/s in one frame, and the exponential ease that followed crept on by
  single pixels for seconds). The player is redrawn every frame they change, also while
  the camera stands at an end (the street's draw used to follow only the camera and the 30 Hz clock there, so the player
  moved 30 times a second), and while the player or the camera moves the street's clock (passers-by, cars) ticks every
  frame instead of 30 times a second.
- **Rooms**: `RoomWalker` uses the same legs in room pixels (1 room pixel = 1 sprite pixel), 1.2x quicker than the street
  (`MoveTuning.roomPace`; rooms are short walks), the stride grown with the speed so the cadence is the street's; the
  run's frames sideways; speed from the push; a blocked axis stops dead (no stored speed bursting out along a wall);
  facing by the dominant axis with 1.25x hysteresis.
- **Tests** (`app/src/test/kotlin`): `StickTest` (the stick's maths, re-arming, the edge reach and the run ring's),
  `MovementTest` (speeds, starts, firm stops, the skid on turning round, the run and its band, facing, the legs against the
  art, the camera's look ahead, stop and ends with no tail, rooms) and `ControlsTest` (MoveInput's gating: closed, held
  until neutral for the stick and for keys and pads, let go, the pad against the stick, a scripted push; GameState's
  steps across the pavement, turning round and round the lamps). The stick's touch handling (several fingers, buttons over it) is checked only
  by the desktop render `-Dmode=stick`.

Old against new (street units: 1 unit = 2.43 device pixels at FHD+ and QHD+ alike; the figure is 36 units, 88 px tall).
All "new" numbers are from the integrator run at 120 and 60 Hz on a computer (`-Dmode=move` prints them), not the phone:

| | before | now |
|---|---|---|
| Walk | 46 u/s (21 s to cross a 2340-px screen, 5.1 s a building) | 72 u/s (13.3 s a screen, 3.3 s a building) |
| Run | 87 u/s after holding one way 0.6 s, then a ~80 ms jolt (11 s a screen) | 150 u/s at the rim (6.4 s a screen at FHD+, 8.5 s at QHD+, 1.6 s a building) |
| 0 to 90% of a walk / of a run | 0.13 s / 0.70 s (incl. the hold) | 0.125 s / 0.20 s (0.133 s / 0.20 s at 60 Hz) |
| Stop | from a run 4-5 units | from a walk 2.2 units, standing at 0.075 s; from a run 8.1 units (a quarter body), standing at 0.13 s (60 Hz: 1.9 u at 0.08 s, 7.4 u at 0.13 s) |
| Turning round | the figure turned at once and slid backwards | skids facing the old way, turns once under 12 u/s: 0.05 s (1.6 u) from a walk, 0.1 s (7.4 u) from a run |
| In and out (kerb to wall, 11 lanes) | 7 lanes/s linear, 1.57 s | eased, 11.5 lanes/s walking (0.95 s), 24 running (0.46 s) |
| Cadence | walk 1.73 cycles/s, run 3.28 (legs patter, world creeps; in-depth legs slid) | walk 2.7 cycles/s (5.4 steps/s), run 3.6 (7.2 steps/s, stride 41.4 u) |
| Ground the drawn legs carry | walk about 80%, run about 52% (the walk's frames) | walk 80%, run 83% (the run's frames) |
| Footstep sounds | one per 17 units against a drawn step every 13.3 | one per drawn footfall, alternating louder and softer |
| Camera | locked on the player | up to 77 units (187 px) ahead at a run (FHD+), 103 at QHD+; 90% there within 1.5 s of a run from standing, 0.75 s from a walk; still when the player is (0.08 s after letting go of a walk, 0.13 s of a run), never moving back |
| Street ends | the camera stopped dead (365 px/s to 40 in one frame at a run); the player drawn ~30 times a second there | eased in from 80 units short, at rest exactly on the end 1.0 s later running in (an earlier exponential ease crept on by single pixels for 2 s more); the player drawn every frame |
| Rooms | 96 px/s whatever the push, feet skating 2x | 105 px/s walking, 219 running, from the push |

Still to measure on the S25 (all estimates now): the real frame rate (the AI panel now shows frame times and the refresh
asked for; the app asks for the display's fastest mode); whether 48 dp of knob travel and the walk band suit the thumb
(`MoveTuning.walkFrom`, `runAt`, `runOff`); whether 5.4 steps/s
at a walk reads brisk or pattering (the art's short legs leave a choice between a quick cadence and sliding feet:
`MoveTuning.stride` longer means fewer steps and more slide); the run's splayed frames, the one-pixel lean and the hop in
motion; heat at 120 Hz with continuous redraw while moving. The desktop render runs the game's frame loop a step out of
time with its drawing (it renders each frame twice in `-Dmode=move` to measure); on the phone both happen in one frame.

## The crossroads and the walk round the corner (verified in the desktop preview; NOT yet run on a phone)
- One place: `domain/Crossroads.kt` lays both streets on one ground plan (the main street's fronts on Z = 0, the cross
  street's west row on X = 0 facing east, its east row across the 178-unit mouth), every building once. The stage builds
  both streets once (`StageTuning.crossroads`, set by the app from street.json) and draws whichever the player is on,
  with the other seen round the corner.
- One camera: `scene/StageCamera.kt` is a pinhole 700 street units behind its target, its eye at the height that puts
  the vanishing point on the stage's own horizon (~49 degrees across the preview, ~69 on the S25's screen). A row of
  buildings or a stretch of ground can keep the stage's cabinet look (the camera's column for its depth axis leaned to
  the stage's offset): at rest it is exactly the stage's drawing; mid-turn plain perspective.
- One look: `scene/CornerWorld.kt` draws everything with the stage's own painters. Fronts go through
  `BuildingPainter.building` (glass reflections, signs, relief maps, balconies, night windows) under the front's
  projective transform; side faces, back walls, roofs and rooftop boards each on their own plane (`sideFace`,
  `roofFace`, `roofBoard`; `planeFaces` leaves them out of the front pass); faces seen small or edge on are sampled
  smoothly (mipmapped on Android), the rest crisp. The ground (the stage's pavement, kerb and road, lamp pools, shadows)
  goes through the ground's transform in strips that stay in front of the camera; lamps, trees, benches and people stand
  upright where they are. Everything is sorted front to back by the lines that part it. At rest the street in view is
  drawn exactly as the stage always drew it.
- The gap view (walking past the mouth) is now the real side street: Orchard Lane runs to the far city under the sky
  (no flat block), both its rows are there with their glass and roofs, the corner buildings' side walls are real (no
  floating eave, no hard cut, no strip of backdrop), the mouth has the cross street's asphalt with dropped kerbs and a
  zebra on it, the side street takes the hour's light (dark at night, its side walls' glass dark and about half their
  windows lit, as the fronts'), and no road name floats over it. Taller side street buildings rise above the main
  street's roofs and the cloud ceiling counts them; in the game a tall building runs off the top of the screen (the
  habit tracker's fade under its heading is off there). The side street's lamps hold their heads out along their own
  street: seen down it from the main street, each head is by its pole (the arm turns with the camera mid-turn).
- The walk (`shim/CornerWalk.kt`): press the up key in the mouth (within 77 units of its middle) and the player walks a
  smooth curve into the cross street at walking pace (a run when held 0.6 s). It starts in the frame the key goes down
  (a tap shorter than a frame is kept by the app's pad), before the player has stepped anywhere; a key already held
  (walking into the mouth with it down) does not turn. The camera turns with
  how far along they are (90 degrees, eased), the target slides from the main street's view to the cross street's own
  view, and the sky turns with it (clouds, far city, stars, the sun or moon and its glare: a quarter turn takes the sun
  out of the cross street's view; that street is lit by where the sun really is). In the frame the walk ends the stage
  hands over to the cross street's own view: the same picture; the player then walks on 60 units by themselves (still
  holding up: on along the street until let go), so they do not stop at the screen's edge. Down walks it back (it ends
  where it began). Letting go of up lets the walk carry on (`TurnTuning.autoWalk`). Back: a press of up at the cross
  street's start (within 60 units; the hint there reads "▲ ◀ Main Street") or walking on against its end (left held).
  Elsewhere up and down step across the pavement as before. Not while inside, at a door, in a conversation or tuning
  the NPU. The sky settles a little before the street at either end (5% of the walk), so it never slides on behind
  buildings that have stopped; a low sun's glow fades over half a screen's width of the sun's travel as it leaves the
  picture mid-turn (or grows as it comes in), not in three frames.
- The block the camera swings behind mid-turn (the main street's buildings past the mouth and the cross street's east
  row, which the cross street's own view never shows) fades out whole, as a building between a game's camera and its
  player does; nothing is ever drawn cut open. The east row goes between 12 and 28 degrees of the turn, while the bank
  stands in front of most of it; the bank's block as the camera's cut (120 units in front of the corner; between 30 and
  55 degrees moving on to 120 units in front of the camera's target, the same place at a quarter turn; never nearer than
  30 units in front of the player) sweeps through it: gone once the cut is 55% of its depth in, about a fifth of a
  second at walking pace. Lamps and people on that side fade likewise; a fading building's reflection in the wet fades
  with it, and the wet mirror (laid in the street's own flat frame) fades as the street leaves its own look. Under the
  swing the main street's far side is paved, not a field. That dissolve is what is left of the trick; no building is
  more than half there between the camera and the player in any frame (`CornerWalkTest`).
- The seam test (`gradle :desktop:preview -Dmode=seam [-Ddir=fwd|back|abort] [-Dat=west|mid|east] [-Dsize=phone]
  [-Dhour, -Dwx, -Dseason] [-Dseq=1]`) renders the walk frame by frame at 60 fps, as the game steps it, and measures the
  hand-overs; `tools/seam-matrix.sh` runs it over many views. Measured in the desktop preview (Skia, not the S25):
  all 11 runs of `tools/seam-matrix.sh quick` pass (west/mid/east of the
  mouth both ways at 17.6 h; 13 h and 21.5 h rain in winter on the phone's screen both ways; walking back halfway). The
  street's own view and the walk's first frame, and the walk's last frame and the new street's own view, differ by a mean
  of 0.000 (out of 255; no pixel by more than 1); one step either side of each end differs by a mean of 0.11 at most,
  against 3.9-6.9 for one step of ordinary walking; no frame of any walk jumps against its neighbours; the walk takes
  170 frames forward (127.6 units: 170.2 at walking pace) and 128 back (95.3 units). Outside the mouth the stage draws
  as before (both streets' panoramas, weather and seasons against renders from before this work: a mean of 0.18 at
  most, in the sun's glare round one building's outline on the cross street).
- Desktop: `-Dmode=junction` walks past the mouth (nine frames) at any hour, weather, season and `-Dsize=phone`
  (2340x1080 at density 2.625). Tests: `gradle :desktop:crossroadsTest` (camera at rest equals the stage's drawing within
  0.05 px, the side street's vanishing point on the horizon, the ground plan, the walk's keys, path and hand-over).
- Not done yet: the street name sign at the corner; generated corner walls from each front's storeys (the side
  pictures are used, smoothed where seen at a slant); the cross street's east row is the town's two lots there, so past
  them its right side is open sky (the town tool takes the real lots; inventing more would leave the simulation); the
  180-degree street crossing still uses the old turn scene (so `StageTuning.skyOnly` and `GameState.skyPan` stay for the
  desktop `turnsky` mode); frame time (a fading building is one extra layer for about a fifth of a second), heat and
  HWUI's perspective drawing on the S25 are unmeasured.

## Real crossroads (tools/export-street.ts v2)
- street.json now holds named streets from the town's real layout: Main Street (the buildings on the main road west of a real
  crossroads, in order) and the cross street (the buildings along the road that leaves the main street there, named from the seed),
  plus the junction's other buildings for the turn scene. Homes are stood in by the modular condo front in the district's colour
  (front door for a family, lobby for flats), named after the household really living there.
- Both streets, the side street's mouth and the cross street's other side (`junction.crossEast`) are one place in the
  game now (below); there is no turn button any more.
- Demo town is still "eta" (Main Street: 8 shops; Orchard Lane: 6 homes). Checked on 9 seeds: all give 7-8 on Main Street, 2-6 on the cross street.

## Pitched-roof houses: the homes are mobile homes now (verified in the desktop preview only; NOT yet seen on a phone)
- A small or medium home on the exported streets wears the very mobile home the game draws on its lot (`houseSmall`,
  `houseMedium`, in its district's colours; `tools/town-streets.ts`), built by the engine as a real house instead of the
  condo stand-in: Orchard Lane (b8-b13) and the junction's b16. No mobile home repeats either of the two before it in a
  row (b10 and b12 are re-rolled to another of their size and palette, as shops already are). Apartments, villas,
  estates and terraced homes keep the condo front (b17).
- `tools/make_buildings.py` `build_house()` cuts the sprite into pictures (front wall, near slope / ridge / far slope
  taken every other row, gable end, hitch, porch parts, the yard's ground and its fence or hedge, where snow lies on that
  fence) and writes the shape to
  `buildings/houses.json`; `--showcase DIR` builds all 160 sprites. `BuildingPainter.houseBuilding` builds it: back in its
  2-tile front yard (it moves against the pavement as the street scrolls), a ridge along the street (the slope one screen
  row per row of the art, about 21 degrees), a gable end on the shown side under the roof slab's end (its rake boards
  standing out by the verge, and behind the ridge the soffit under them in shade: thin steep bands, filled one whole
  screen pixel per row, `domain/HouseGeometry.kt` `PixelRows`, so they step like the art's own lines), the flue behind the ridge,
  the hitch, the porch or steps as lit boxes, the yard (grass or bare earth, the path to the gate, the game's fence or a
  hedge for a formal yard). The gable end is the front's plain siding carried round the corner (the columns that are
  siding all the way down, so no door, frame or blind colours it). The art's ground is told from the house by the grey
  base design (the green variants paint the hitch and the wheels green). The porch is a platform: its deck's front over
  the dark space under it, the railing open between its balusters. Light: the slope catches a high sun and is in shade
  at golden hour, the gable by its side, the verge's shadow, the eaves' shade, the house's shade over its yard, its fence
  or hedge and (shorter) the pavement. Snow on the roof, the ridge cap and the rake boards, a lip and icicles, a white
  yard, along the fence's and the hedge's tops. A house's wall stands on the screen's own pixels (its place along the
  street counted in, not only whole pixels from its middle). Windows keep their drawn curtains and blinds with the sky mirrored faintly over them,
  rain on the glass, and a lamp lit behind at dusk on a home's hours. The wet road mirrors a house where it stands (its
  own width, back in its yard, its roof over it, mirrored about its own foot); a car's headlights light only its front
  wall, and the lamp posts' shadows they throw land on that wall where it stands back; a tap finds its yard (porch,
  steps), its front or its roof, each leaning with the view as far as it stands back, not the sky beside or above it.
- The corner turn and the street crossing slot a house at its own width, back in its slot, with its pitched roof as a
  prism (`scene/TurnRoof.kt`: both slopes wear the art's slopes through one perspective matrix each, the gables the end
  wall's own picture above the wall, its trim and vent, under a band of the rake's colour), and what the street shows
  round it, so nothing pops in or out as the view turns (`scene/TurnHouse.kt`): its yard's ground, its fence or hedge
  (snow on it in winter), its porch or steps (the railing and the deck's front wearing the art) and its flue as lit
  boxes (the flue between the slopes, so the ridge hides its foot from the front). The turn's tints are kept in a table
  of plain ints, so looking one up makes no garbage. A porch light beside each door comes on at dusk; the
  flues smoke in the cold months (the turn has neither yet).
- Not done yet: Main Street's own pitched roofs (`roof_2/4/6`, `roof_8/9/11`, the same machinery at 120 deep; the
  villas and the hipped roofs are done, below); hedges down the yards' sides, pigeons on the ridges, eave drips in rain. The street's view is nearly level, so a porch's deck boards hide behind its railing (only
  between the balusters do they show); the crossing, from higher, shows them. The turn's houses stand 40 back in its
  120-deep slots against 64 in the street (their porches shortened to fit), and its roofs have no ridge cap.
- Checks: `gradle :desktop:preview -Dmode=houses -Dstreet=1` (each house beside its sprite at the left, middle and right
  of the view, and the lane at day, dusk, night and in snow; `-Dassets=DIR -Dstreet=0` for the showcase);
  `gradle :desktop:test -DtestOnly=HouseGeometryTest`; `npx vitest run kotlin-street/tools/town-streets.test.ts`.
- `make_buildings.py` no longer deletes the lighting maps (`_lit`, `_shade`) of fronts it did not change: the depth
  model that makes them is not kept on disk. The houses have none (their relief is real geometry).
- Draw cost: about 60-90 draw calls per house against about 40 per shop (an estimate; nothing measured on the S25).

## Villas and landmarks: front-facing gables and hipped roofs (verified in the desktop preview only; NOT yet seen on a phone)
- A villa-tier home on the exported streets wears its own villa (`villa_1..5`, `tools/town-streets.ts`; a villa
  repeating one of the two homes before it becomes another of its district's villas when there is one). eta has none
  on its streets (its assets are unchanged); vale has two (one on Oak Street, one at the junction).
- `tools/make_buildings.py` `build_model()` cuts a villa into parts (`buildings/models.json`), all its art in one atlas
  (`bN_atlas.png`, where it glows at night in `bN_glow.png`): the ground floor (its porch posts taken off the wall, the
  siding carried across), the floor between the storeys, the upper floor whose front carries the gable (a true front
  elevation, used as drawn: the round window, the rake boards, the balcony's glass door), a FRONT-FACING gable (its two
  slopes are the art's slopes from above, laid back from the rake), the terrace roof over the ground floor (its boards,
  with the small gable and the balcony painted out), the balcony (rails and a card open between the balusters), the
  small gable over the door (its face as drawn, its slopes), the posts, the raised deck, its railings (open between the
  balusters) and steps. Side walls are each row of the front's own siding with one of its windows. Its yard (grass, the
  path, the game's fence or a hedge) is the houses' helper.
- The clock towers (`landmark_clocktower_1/2`, only the time differs) are a square tower wearing its front on every
  side (a clock on each face) under a HIPPED roof: the front face the art's rows between the hips, every other one (the
  artist's own pixels, one screen row each; the rise is chosen so, about 31 degrees; its last row the tiles' ends along
  the eave, which on the wall's top stayed red under the snow), the side faces laid on their quads and tiled anew with
  the front face's own tiles, each course along the depth at the height of the front's (`hip_side`: the art draws them
  in the front's way, courses across, which sampled into hatching or, averaged, streaks), with a second piece whose
  courses are each one colour for a view that sees them nearly edge on (`along_mean`; the engine fades between the two
  by how far apart the joints fall on the screen); a band of the art's crease along each hip (no sky through it), a
  flat top with its lantern, the box's top round it the stone coping the art shows from above. The brick piers' rows
  are widened to the box's edge (they stood two pixels in: a slit of sky down each corner). The dial's painted hands are
  taken off (`clock_of`) and the engine draws them at the game's hour on every face (`ClockHands`: pixel art in the
  dial's doubled cells, ink down the middle, brown round it, gold tips and hub; a face seen from the left turns them the
  other way, so they still go clockwise); at night the whole dial glows (its face's colour inside its ellipse) with the
  hands dark on it. Each clock has its own small picture of its hands, made blank and mutable (`ImageBitmap(n, n)`) and
  painted again only when the minute changes, with no allocation (on Android a picture made from pixels is immutable:
  writing it again would throw; the desktop preview now fails the same way). The school (`landmark_school_1`) is its long front, a middle bay standing 12 forward (as wide as the
  two doors' arches, which reach past the cross gable's eaves: its edge no longer cuts an arch) with the cross gable's
  face over it, a ledge beside the gable, a hipped roof (about 43 degrees, a flat ridge, its hips the art's light cap
  tiles over a crease), the cross gable's slopes back to the valley (its verge along a slope the view does not see), two
  chimneys (a brick stack under a wider slate cap, the cap's top the art's from above; smoking in the
  cold months), its entrance steps between stone cheeks with the art's balusters (from the cheek's outline in: the
  art's half-clear shadow beside it had come out pure black); its side walls are the front's wall
  colour clear of its windows, with an upper and an arched window of its own. They are not on the two streets:
  `-Dmode=models -Dstreet=3` shows them in a preview row.
- The engine (`domain/ModelGeometry.kt`, `BuildingPainter.modelBuilding`) builds them back to front: boxes wearing the
  art's fronts (a front may reach above its box), sides and tops; front gables, each slope laid on its quad by one
  projective map where the view sees it from above (`RoofFaces`), lit by the side it faces, snow on it with a lip along
  the rake; under each slope's straight front edge the roof's own edge colour (the art's row above the rake ink) fills
  down past the face's stepped rake, so no staircase of sky shows between the gable and its slopes, and two pixels
  above it is the verge, which shows whether or not the slope does (the small gable over the door, whose ridge now
  meets where its face's checkered rake boards do, at 45 degrees, no longer shows a dotted outline on the side the view
  does not see; snow on it, and over its apex). The eave's board and
  its shade along the shown side are drawn after the wall that carries the gable's face (`ModelShape.eaveOn`: that
  wall's side would paint over them). Hipped roofs: side wedges (half the slopes' sunny wash: the full one turned their
  dark red tiles pink), flat top, front face with its eave on the screen's pixels, lit as the houses' slopes are; snow on
  each face's own pixels, along the hips and with a lip hanging over the eave, nearly opaque on these roofs (it had
  taken the roof's colour: pink on a red roof). Windows mirror the sky and gather rain (not a round dial, which would
  show a square) and light on the building's hours (a home's, a shop's, the clock's dial all night): every shade of a
  pane's glass joined to its clean glass, up to its frame and mullions (the school's oval window had lit in blobs with
  dark wedges, the villas' with ragged tops), a little brighter where the art's glass is lighter; the villa's lamp by
  the door. A building wider than a shop takes a wider lot and stands
  in its middle (`BuildingLayout.wide`); shops and the junction are laid out as before. Its shade on the pavement, the
  wet road's mirror (culled by its own width), the sun's glare at its edges, headlights and taps follow it where it
  stands back (as a house's). At the mouth of the side street it shows no side face (`StageTuning.cornerIds`, as the
  shops and houses). Only a box whose side has windows gets the night's light pass on its side (`sg`; not a railing's).
- The corner turn and the crossing build a villa's box of two storeys with its front gable (`scene/TurnVilla.kt`): a
  prism on the box, its ridge from the middle of the front to the middle of the back, each slope its art from above
  laid on its quad by one perspective map from the art's own four points (as the street lays it), each gable end the
  upper storey's own triangle (the round window, the rake boards) over the roof's edge colour; lit by the side it faces,
  white in winter. So a villa keeps its shape as the view comes round the corner. Its box's front picture
  (`bN_front.png`, which the wet road mirrors too) carries the balcony's railing, the small gable over the door and the
  deck's railings as the street sees them from straight ahead (they had been left out: the turn showed the storeys
  bare); they are flat on it there, not built as volumes. A landmark's front gable is never taken for its roof there.
- Not done yet: the villas' storeys are as low as the art draws them (40 pixels: a shade lower than the mobile homes'
  wall); no lighting maps for them; the school's back wing and the clock tower's corner pinnacles above the eave are not
  modelled; the cross gable's rake ink stays dark in snow (the art's boards, under the lip of snow); the cross gable's
  slopes are slivers in the street's nearly level view (the art draws them from high above), and the bay's side return
  is as thin as its 12 pixels of depth; the turn's villa deck, porch and small gable are flat; in the street's frame
  (780 by 360) the landmarks' roofs are above the top (they are not on a street yet).
- Checks: `python3 tools/make_buildings.py --showcase DIR`, then `gradle :desktop:preview -Dmode=models -Dassets=DIR
  -Dstreet=2` (villas) or `-Dstreet=3 -Dtall=760` (landmarks): each beside its sprite at the left, middle and right of
  the view, and the row at day, dusk, night and in snow; `-Dmode=turn|cross -Dassets=` now read another asset folder too.
  `gradle :desktop:test -DtestOnly=ModelGeometryTest,HouseGeometryTest`; `npx vitest run kotlin-street/tools/town-streets.test.ts`.
- Draw cost: a villa is about 20 parts (an estimate of 120-160 draw calls), the school about 12, a clock's hands one
  more image per face (repainted only when the minute changes), a hipped roof's side face one or two pieces (two while
  it fades between its tiled and its flush picture); nothing measured on the S25. Each projective piece
  (a slope, a side wall) is a `withTransform`, which on Android allocates a matrix per call (Compose's
  `AndroidCanvas.concat`), as the turn's houses and roofs already do: more of them now, still unmeasured.

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
- **Always listening** (2026-11-18, the owner's request; built and unit-tested on the computer, NOT yet run on a phone): the
  AI panel's "Mic: always listening" chip (off by default, kept in the `mic` preferences; asks for the microphone when
  switched on) replaces holding the button. While it is on and the game is in front, the mic stays open and
  `shared/.../voice/SpeechSegmenter.kt` (energy VAD per 20 ms frame: a noise floor that follows the room's quiet, a
  4-frame onset, 700 ms hangover, 300 ms kept from before the onset, under 300 ms of voice dropped, cut at 12 s, a steady
  noise learnt as the new quiet) cuts what is said into utterances; only those are written down (at most two waiting).
  The speech model loads when listening starts and stays while it is on; switched off it is let go at once, paused (the
  game in the background) after a minute. Not listening: in the background, turning the corner, going through a door,
  in a room with nobody in it, and while an NPC's voice plays. Ours only (the phone's recognizer cannot listen all the
  time): with too little free memory for the speech model the words are dropped with a note.
- Who hears it: `shared/.../scene/TalkRange.kt` - the nearest person standing at a door within `TalkRange.distance` (70
  street units of ground; the pavement is deep ground, so ~70 along the wall or ~37 along from the kerb) of the player,
  from the same places the street draws them (`centers[b] + 30`, lane 3.6). "Hey" is enough to start; later words go on
  in the same conversation (walking past `leaveDistance`, 90, ends it). Nobody near: a faint "no one near enough to hear
  you". Inside, the person in the room when standing by them. A conversation by voice shows no panel: the answer is a
  bubble over their head (thinking dots while it is written, square-edged and outlined; it fades after 2.5 s + 55 ms a
  character, at most 12 s) and the player's words a smaller one over theirs. The bubbles follow the heads through
  `TalkRange.screen` (the stage's last frame: the same numbers the classic draws people with and the Filament street at
  rest; hidden while the camera turns the corner). "Talk to" still opens the panel; its mic button is hidden while the
  mic always listens. Tests: `gradle :desktop:crossroadsTest --tests TalkByVoiceTest` (the segmenter on made-up audio,
  the nearest-in-range rule).
- **NPC voices are off by default** (the owner, 2026-11-18: until they sound better). "Voices: off" is kept in the
  `voices` preferences; off, nothing is voiced (live, fillers or ahead), queued voice work is dropped and both engines and
  their voices are let go at once. Switching on brings back everything as before.

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

## Live replies: a light model on the NPU in play (built and unit-tested on a computer; NOT yet run on a phone)

- Why (the phone's own numbers): with the town built the S25 Ultra had about 4.1 GB free; the main 9B model's NPU copy
  needs about 6.6 GB with the game's margin, and on the CPU it was much too slow. So a lighter model of the same family
  (Qwen3.5-4B, unsloth Q4_0, 2,583,221,408 bytes: Q4_0 is the format llama.cpp's Hexagon code was built around, as for
  the 9B's NPU extra) is in the one-tap download (now about 14.7 GB) and answers in play on the NPU.
- Memory (estimated from the file and the model's config, not measured on the S25): weights 2.46 GB copied to the NPU,
  KV cache for 1024 tokens ~34 MB (8 of 32 layers have attention, 4 KV heads x 256) plus ~50 MB recurrent state, compute
  buffers in the 300 MB allowance: about 2.8 GB. The load gate asks 3.66 GB free (file + 300 MB + the 900 MB margin),
  under the 4.07 GB the phone had.
- Roles (`dialogue/ModelRoles.kt`, `ModelRolesTest`): in play the main model on the NPU if it fits, else the light one on
  the NPU, else the light one on the CPU from its mapped file; behind the game (the overnight writer) the main model;
  nothing loads while the town is built. The light model does not write ahead (pre-generation paused, reason "light
  model loaded"); the bank is the main model's. Coming back to the game with the main model loaded swaps to the light one
  when that is the choice. The AI panel shows which model is live, on which device, the memory each needs, and a switch
  to put the main model in play for comparison. The 9B's Q4_0 and MTP builds stay optional extras.

## The overnight writer and the player's conversation log (built and JVM-tested on a computer; NOT yet run on a phone)

- While the phone is plugged in and the game is closed, the dialogue model keeps writing lines into the bank
  (`night/`: `NightWriter`, `NightSession`, `NightService`, `NightJob`, `NightRules`, `NightReport`). A foreground service
  of type specialUse with a low-priority notification ("Writing dialogue: 312 of 580", a Stop button); started when the
  game goes out of sight on the charger, or by a JobScheduler job at the next charge (Android 12+ lets that job start the
  service only with "Unrestricted" battery use, which the AI panel asks for; otherwise the job writes itself for its
  ~10 minutes and comes back). Stops on unplug, when the game opens, when switched off, or when the bank is complete.
- Order: every line of the town (580 distinct prompts for gamma: 650 topics, the same person at shop and home says the
  same), most needed first; then the player's own patterns (a follow-up to each person's last conversation, and the
  player's frequent question themes asked of every person); then up to 4 ways of saying each line (rotated in play,
  never the same twice in a row); then other years (none ship yet: the phone cannot make other years' fact sheets, see
  docs/CONVERSATION.md).
- Heat and battery: bursts of 8 minutes with 2 minutes' rest, cooling down at a moderate thermal status or headroom
  past 0.9, paused at severe, stopped at critical; waits below 30% battery. A partial wake lock only while writing.
  Memory: the game's MemoryWatch and load gate; the model loaded once (on the device the panel or NPU tuner chose)
  and let go when it stops. These thresholds are first guesses, not measured on the S25.
- Staleness: each line is keyed by a hash of its whole prompt (fact sheet + question), and records its slot
  (town/year/person/topic): a person whose facts change gets new keys (their old lines unused, counted stale, kept),
  everyone else's stay valid (unit test on gamma's file).
- The log: every exchange said or typed is kept in the person's memories (memories.json, now also town, year, topic,
  ready/live/template and the question's vector), taps as counts (player-stats.json). Themes are clustered from it
  (vectors, or words without the memory model), and a question close enough to a theme (same kind, same subject, past
  the threshold) gets that person's ready line instead of a live one. Stays on the phone.
- AI panel: the switch, the bank (ready, variants, stale, years), last run, the player's top themes, ready/live answers
  by day, buttons for notifications and "Unrestricted". Breadcrumbs: start, stop and why, speed every 10 minutes.
  Report: night/<date>/<time>.json on the `reports` branch when a token is set (counts and theme labels only, unless
  "Night reports: with my words" is on).

## The Filament renderer trial (verified in headless desktop renders on Mesa's software GL; NOT yet run on a phone)
- What it is: the street drawn by Google's Filament (1.81) instead of by hand on Compose's Canvas, as a switch in the AI
  panel ("Renderer: Classic / Filament trial"; the classic stays the default, the choice is kept). The stage still
  runs everything (the walk, the camera, the people, the sky) and hands each frame's crossroads over (`scene/Stage3D.kt`);
  with Filament on it draws only its sky, and a TextureView over it runs `scene3d/FilamentStreet.kt`, drawn from the
  stage's own draw right after it fills the frame (so street and sky are the same frame, nothing is drawn while the stage
  draws nothing, a frame the stage did not change is not drawn again, and nothing at all between onPause and onResume).
  If Filament fails to start or stops, the game goes back to the classic street and the panel says why; a start is
  marked in the preferences until 30 frames are drawn, so a native abort (which no Kotlin catch sees) leaves the mark and
  the next launch starts on the classic street.
- How it keeps the look: every visible thing is real geometry (each shop's front, sign, sides, back, flat or pitched
  roof with its gables and end walls, rooftop billboard; the mobile homes' walls, gabled ends and roof slopes, lawn and
  fence; the ground; lamps, trees, benches and people as upright cards) wearing the same sprites, nearest-sampled when
  magnified and mipmapped when seen small or edge on (no moire), with a real depth buffer. The ground is one painted
  tile per street (the classic's slab gradient and joints, kerb highlight and its shade on the road, white lane dashes,
  specks), with the mouth's road, dropped kerbs and zebra laid over the main pavement. It goes to the screen through CornerWorld's own matrix for it (`device` vertex domain,
  `scene3d/LookMatrix.kt`), so at rest the street stands exactly where the classic draws it (the same matrices that
  `crossroadsTest` holds to the stage's drawing within 0.05 px; `LookMatrixTest` checks the screen is the classic's at
  any lean and the depth is in order) and mid-turn it swings exactly as the classic's does. Light and shadow use each
  thing's true place: a sun from the classic's key light, splayed as the classic's is and turned with the camera as the
  classic relights each street it looks down (its 4096 shadow map, 3 cascades fitted from just before the street, stable;
  the moon throws none), a sky light with
  more from the camera's side, invisible object-domain twins that throw the shadows (things drawn through the look cannot
  cast). The depth is the pinhole's n/d where nothing is leaned and its tangent where a street keeps its cabinet look;
  the ground sits under everything and, at rest, the street in view in front of the rest, as the classic layers them.
- Night and dusk: the upper windows are plain glass mirroring the sky and the far city (a few of its lights at night),
  lit rooms only with interiors on (as the classic), the houses' panes on their hours, the shops' displays glow in their
  own colours; the lamps' lantern glass glows; the ground takes the hour's hue; fronts backlit by a low sun fall into
  their own shade; haze (fog) past the street in view greys the far blocks as the classic's aerial haze.
- Desktop: `gradle :desktop:preview -Dmode=filament [-Dhour=H] [-Dturn=0]` (or `filament-albedo`, unlit sprite colours)
  writes `desktop/build/preview/filament/filament_*.png` (Filament over the stage's sky) and `compare_*.png` (classic
  above, Filament below, the same frame) for Main Street at the mouth and further west at 13, 19.3 and 22.5 h, and the
  walk round the corner at 0, 25, 50, 75 and 100%, at the S25's 2340x1080. Compiled in only when Filament's desktop build
  is there (`tools/build-filament.sh`, `filament/TOOLCHAIN.md`); materials: `filament/materials/*.mat`, compiled by
  `tools/build-materials.sh` into `app/src/main/assets/filament/` (committed).
- Measured on this 4-core server only (software GL, relative numbers; nothing on the S25 yet): a Filament frame at
  2340x1080 (update, render, read back) about 100 ms warm (92-110), 215-390 ms for the first frame of a street (shaders
  compile), building the street 60-110 ms; unlit albedo mode 30-34 ms. The classic's frame in the same harness (Skia on
  the CPU, also read back) 310-340 ms by day, about 950 ms at dusk, about 400 ms at night. The phone's numbers are what
  count: the AI panel shows Filament's CPU and GPU (from Filament's frame history, where the driver gives it) times,
  start and build times, beside the frame line, and the phone's thermal status and headroom.
- By eye against the classic: placement matches at rest and through the turn; the shadows fall from the buildings,
  lamps, trees and people onto the pavement as the classic's do (true sun, a little longer and straighter than the
  classic's splayed ones). A rooftop billboard stands halfway back on its roof as
  the classic's, its depth pulled to its front's so its own roof does not hide it. Not like the classic yet: no lamp
  pools on the ground at night, the houses have no porches, steps, hitches or flues, a low sun's glare is drawn under
  the street (the classic draws it over), and none of the weather, seasons, cars, birds or the wet mirror are drawn by it.
- To do: run it on the S25 (frame times, GPU time, heat, the TextureView's cost); Filament's AAR asks for compileSdk 37
  (this project is on AGP 8.7 and SDK 34: the AAR metadata check is skipped and its own dependencies left out for now);
  the Rich/Exact post-processing switch, Vulkan backend switch and benchmark walk of the design are not built; lamp point
  lights (the device domain's screen is not the camera's, so Filament's light froxels would need the camera to match).

## Screenshots to Claude, and crash reports (built and unit-tested on a computer; NOT yet run on a phone)
A camera button on the game screen sends what is on the screen, with the game's state, to the repository so Claude can
look at it; crash reports go the same way by themselves. Code: `app/src/main/kotlin/shot/` (ShotRequests: paths, request
bodies and JSON with no Android types, tested by `ShotRequestsTest`; ShotUploader: capture, encoding, the GitHub calls;
ShotSettings: the switch and the token; Reports: crash reports and breadcrumbs), the panel and the button in GameActivity.kt.
- **Settings**: AI panel (the "AI" chip, top left) → "Screenshots to Claude": the camera button on/off (off by default),
  the GitHub token field (masked; Save / Forget), "token saved" / "no token", "Test upload" (a GET of the repository),
  "Send crash reports automatically" (on by default once a token exists) and "Send N crash reports" when some wait.
  The token is kept in EncryptedSharedPreferences (androidx.security:security-crypto 1.1.0, an AES key in the Android
  Keystore); if the keystore cannot be opened it falls back to the app's private preferences (the panel says which). It
  is never logged or written anywhere else, and the app has no backup.
- **Making the token**: github.com → Settings → Developer settings → Personal access tokens → Fine-grained tokens →
  Generate new token. Repository access: Only select repositories → lifemaxrpg/timerpg. Permissions → Repository
  permissions → Contents: Read and write (Metadata: read-only comes with it). Pick an expiry (e.g. 90 days), generate,
  copy it, paste it into the panel's field and Save, then Test upload.
- **The button** (📷, small, top right under the weather chips): it hides, the whole window is captured (PixelCopy of
  the window on API 26+, so Filament's TextureView and the Compose HUD are both in it; on API 24-25 the decor view is
  drawn instead and the 3D layer may be missing), then a sheet asks for an optional note: Send / Cancel. The upload runs
  on its own thread: `screenshots/<yyyy-MM-dd>/<HHmmss>-<note slug>.png` on the `screenshots` branch (made from the
  default branch's head the first time), then a `.json` of the same name. Size: a full-resolution PNG if it is under
  3 MB, else lossless WebP (`.webp`, Android 10+), else PNG scaled by 0.75 (again, down to about a third) until it fits;
  the JSON's `image` says which. With no network it waits and tries once more when the phone is back online (or after
  a minute). A toast says "Sent to Claude" or what went wrong (no token, token can't write, can't see the repo, no network).
- **The JSON**: time and note; app version, versionCode and git hash; the image (format, scale, size, how captured);
  screen size and density; heat (thermal status, headroom) and memory; the renderer and camera preset; the street (name,
  index, nearest building), the player (x, lane, street, walking/running), the corner turn (active, direction, progress,
  yaw), the room if inside; the hour, season and weather; frame times (the AI panel's lines, Filament CPU/GPU median
  and p95); the dialogue AI (state, device, queue, speeds), the conversation, voices and mic.
- **Crash reports** (`crashes/<yyyy-MM-dd>/<HHmmss>-<kind>.json` on the `reports` branch): a Kotlin crash is written by
  the uncaught-exception handler before the process dies (the stack with causes, the thread, the game state, the
  breadcrumbs, the app's last 300 logcat lines); on each start, Android's record of earlier exits (API 30+: native
  crash, ANR, crash, low memory, excessive resource use, each since the last one handled) becomes a report with the
  reason, description, importance, PSS/RSS and the tombstone or ANR trace (up to 1 MB), plus the events and game state
  the dying run had saved (and whether Filament's start died: the "starting" mark). Breadcrumbs: the last 100 events
  (rooms, streets, the corner turn, renderer switches, Filament start/stop, the dialogue model's state, mic and voices,
  conversations, pause/resume, onTrimMemory), also kept on disk a second later and the game state every 3 s. Reports
  are sent in the background on the next start when a token is saved; one is deleted only once uploaded, at most 20 kept.
- **Testing the pipeline**: long-press the "Screenshots to Claude" title: "Test crash (Kotlin)" and "Test native crash"
  (a SIGSEGV sent to the game itself) appear. Start the game again and the report goes up.
- Not tested without the phone: PixelCopy with the Filament TextureView, the keystore, the real GitHub calls (branch
  creation, a fine-grained token's 403/404), the offline retry, the exit records and tombstones, logcat from the app.

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
