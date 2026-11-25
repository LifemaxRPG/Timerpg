# Heat and battery (2026-11-25)

Heat and battery are handled as scheduling and pacing, not as cuts: the picture is the same as before unless the phone
is actually warm, and then it steps down gradually before Android throttles, and back up as it cools. Background:
OPTIMIZATION_RESEARCH.md sections 3.2 and 4.1-4.6. Every number here is **desktop** (the build server: 4 x86 cores,
Mesa llvmpipe, software GL) unless it says otherwise. **Nothing in this file is measured on the S25 yet.**

---

## 1. Frame pacing (`FramePace`, `FilamentHost.pace`, `GameActivity.matchRefresh`)

- **Settings, "Frame rate"**: 60 (the default, "cooler") or 120. Saved in the renderer's preferences (`fps`).
- The display is asked for the mode whose refresh matches the target (`FramePace.pickRefresh`: the rate itself, else
  the lowest whole multiple of it, else the fastest) through `preferredDisplayModeId` and `preferredRefreshRate`, again
  whenever the target changes (the setting, or the thermal governor's cap). Before this the town always asked for the
  display's fastest mode (120 Hz on the S25), so it drew up to 120 frames a second.
- The town's surface is told its rate (`Surface.setFrameRate`, only if seamless). It is a TextureView's surface, so the
  window's mode request above is what really counts; `setFrameRate` is there for when the town moves to a SurfaceView.
- Filament gets the display's refresh (`Renderer.DisplayInfo`) and the frame interval in refreshes
  (`FrameRateOptions.interval`), and each frame's Choreographer vsync time (`beginFrame(swapChain, vsync)`).
- Even spacing (Swappy's rule): where the display stays faster than the target, a frame is drawn only every whole
  number of refreshes (`FramePace.due`, half a refresh of slack for the clock's jitter), never a 16/8 ms alternation.
- The automatic resolution's GPU budget follows the target (16.7 ms at 60, 8.3 at 120).

Not done: Swappy itself (not in Filament, and a TextureView's consumer ignores presentation timestamps). The frames'
even spacing comes from the Choreographer drive plus the matched display mode.

## 2. ADPF: performance hints and the thermal governor (`Heat.kt`, `HeatSteps`)

**Performance hints** (Android 12+): two `PerformanceHintManager` sessions.
- *The frame*: the main thread (it runs the game and submits Filament's frame) and Filament's own threads
  (`FEngine::loop`, `JobSystem::loop`, found by name in `/proc/self/task`). Each frame reports the main thread's work,
  start to `endFrame`, against the frame's time (16.7 ms at 60).
- *The town workers* (`town-build`): their busy time a frame (`TownJobs.busyNs`: finished jobs plus the running ones'
  share), per worker, against a looser target (the frame's time x the plan's `workerTarget`, 1 to 3): background work,
  so the system may run it at lower clocks.
- Threads are looked up again every 5 s (the workers start with the town) and the sessions remade if they changed.

**The thermal governor**: every 2 s `getThermalHeadroom(10)` (a 10 s forecast; 1.0 = severe throttling) and the
thermal status listener feed `HeatSteps`, which holds a level 0-4 with hysteresis: up one level after the smoothed
headroom has been over that level's line for 4 s, down one after it has been 0.08 under it for 20 s. The status sets a
floor (LIGHT 1, MODERATE 2, SEVERE or worse 4) for phones that report no headroom.

| level | enters at headroom | frame rate | resolution (auto steps) | shadow cascades | horizon ring rebake | workers' hint |
|---|---:|---|---|---:|---|---|
| 0 | - | the player's | as the GPU needs | 4 | as designed | the frame |
| 1 | 0.65 | 120 -> 60 | as the GPU needs | 4 | 2x further apart | 1.5x the frame |
| 2 | 0.75 | 60 | at most 0.86 | 4 | 3x | 2x |
| 3 | 0.85 | 60 | at most 0.72 | 3 | 4x | 2x |
| 4 | 0.95 | 60 | at most 0.72 | 2 | 4x | 3x |

The order is cheapest-to-see first: 120 -> 60 Hz loses no picture, then the far rebakes and the background clocks,
then resolution (upscaled with FSR's filter, in held steps), then shadows near the camera. A fixed resolution setting
is held under the governor's step too. "Shadow update rate" was in the brief, but Filament 1.81 draws every cascade
every frame and has no cached-shadow API, so the cascade count is the lever.

**It never touches the dialogue**: the governor does not pause, move or reschedule the language model; the dialogue
stays on the NPU or pre-written lines, never the CPU (CLAUDE.md).

**Logged**: the play log's `pow` and `mem` lines carry `heatLvl`, `heatHead` (smoothed headroom), `fps` and `fpsSet`,
`res`, `casc`, `rebake`, `msaa`, `hints` (frames reported) and `hint` (the sessions' state); every level change is a
breadcrumb and a `heat.N` phase; the Settings panel's heat line shows the governor.

Tests: `HeatPlanTest` (pacing, the governor's steps up and down and the status floor, the resolution held at the
governor's step, the budget following the rate) and `ResolutionStepsTest`.

## 3. MSAA (`TownQuality.msaa`, Settings "Anti-aliasing")

4x multisampling on the town's view (`View.MultiSampleAntiAliasingOptions`), on by default, switchable live. Desktop
renders before and after (the downtown shots; contact sheet below): geometry edges (lamp posts, sign poles, roof lines,
building corners against the sky) are smooth instead of stair-stepped, and the nearest-filtered pixel art inside each
face is exactly as sharp (a pixel is still shaded once; the samples only weigh triangle coverage). Alpha-cut cards (the
people, leaves) keep hard edges (no alpha-to-coverage). Other differences between the two runs are the people and the
clock, not the AA.

**Cost (estimate, not measured)**: on the Adreno 830 the samples live in tile memory and resolve as each tile is
stored; Filament's GLES backend uses `EXT_multisampled_render_to_texture` where present, so no multisampled buffer is
kept in memory. The extra cost is more bins per frame (4 samples of RGBA16F + depth32 per pixel take ~4x the tile
memory): Qualcomm puts 2x at "practically free" and 4x at a small cost; expected ~0.5-1.5 ms of GPU a frame at
2340 x 1080. The automatic resolution absorbs it if the GPU runs over. On the desktop it is CPU work for llvmpipe and
not a meaningful number.

## 4. Streaming hitches (`Graveyard`, `TownStream.STREAM_MS`, `GpuRouteTest`)

`GpuRouteTest` now reports each part of the frame (worst ms and how many frames each took over 4 ms), the slowest
frames by part with the collector's share, and the stream step by section (what is wanted, buildings out, lots out,
lots in). A sampling profile of the route (JFR) found the cause of nearly every streaming hitch, and of most of the
greenery's: `Engine.destroyMaterialInstance`. Filament 1.81 has a correctness assertion on by default
(`engine.debug.assert_material_instance_in_use`) that walks every renderable's every primitive at each material
instance destroyed: with the town's thousands of renderables each destroy cost ~0.1-1 ms, and the route destroys
36,000 (lots and buildings going, impostors repacked as the camera moves, tree cells let go).

- The town turns that assertion off (our destroys always follow their renderable's; `-DmiCheck=1` keeps it).
- What streaming lets go of leaves the scene at once, and its material instances, then the textures only it held, are
  destroyed in order within 1.5 ms a frame (`Graveyard`, `TownStream.GRAVE_MS`; twice that over a backlog of 2,000).
- A lot coming back uploads its batches over frames within the stream step's 4 ms (`TownStream.STREAM_MS`), not its
  whole yard at once.

The route, 11,484 frames at 60 Hz (desktop; CPU ms a frame: the frame's work, streaming and building, not the render):

| | before | after |
|---|---:|---:|
| median | 2.82 | 3.00 |
| p99 | 26.86 | **8.06** |
| worst | 298 | 83 (the first frame after the build) |
| streaming + building: p99 / p99.9 / worst | 18.3 / 38.6 / 61 | **2.2 / 5.1 / 17** |
| frames over 16.7 ms | 356 | 33 |
| peak GPU (budget 495) | 486.3 MB | 486.9 MB |

(The task's reference p99 of 18.7 ms was measured before the tree impostors baked in the capital, see section 5; with
them, before this fix, it was 26-39 ms over three runs. The server is shared, so runs vary by ~20%.)

## 5. Build heap (`townMemoryCheck`)

The check ran out of its 320 MB heap after the first view. A heap dump (attributed to the nearest object of ours) found
the tree impostor bake: the capital's 55,381 far trees in 2,472 shape-and-height looks, each look's 8 views held apart
(1.4 GB) before a 1.9 GB atlas was made. It never finished at any heap (so the capital's far trees were the old cards
until now) and held ~150 MB of the build's peak while it tried.

- A look is now the tree's species and variant (166 in the capital), baked at its trees' middle height and worn scaled
  to each tree's own; the views are drawn straight into the atlas; the pixel size is the near trees' own if every look
  fits in 2048 x 4096, else coarser in eighths: **1.60x the near trees' texel** for the capital (2040 x 3882, 30 MB
  while baking). At the impostor line (1,100 units from the eye) a near-tree texel is about one screen pixel at
  1080 rows, so an impostor texel is ~1.6 px there, less further off (mipmapped).
- The atlas is packed for the GPU once in the bake job, then its pixels go; the bake runs when the workers are idle.
- The world's far view lets each part's packed buffers go once uploaded (~15 MB kept for the town's life before).

Held peak (live heap + direct buffers) at the check's 320 MB heap: **242-291 MB over 8 runs** (worker timing), was
489-514 MB at a 640 MB heap and out of memory at 320. The budget is now **320 MB** (was 256): the S25 runs the game with
`largeHeap` and a 512 MB limit (the owner's logs, MEMORY_AUDIT.md 1.3); 256 was set when the app ran on a 256-320 MB
heap, before the world's far view (~25 MB of skeleton and terrain held, ~15 MB of far parts in flight) and the
impostors (30 MB while baking). 320 leaves 192 MB of the S25's heap for the rest of the app and the collector. The check
keeps its 320 MB heap plus 160 MB of direct buffers (480 together, under the phone's 512) so an out-of-memory still
fails it.

## 6. What needs the S25

1. **The frame rate**: Settings at 60 and at 120: the `fps` field and the display's mode (breadcrumb "display: N Hz"),
   the frame times (FrameSpikes, Perfetto) evenly spaced, and battery drain (`mW` on the pow line) over 10 minutes of
   the same walk at each.
2. **The governor** over a long play session (30+ minutes, a drive at night): `heatHead` and `heatLvl` should rise
   before `th` (the thermal status) reaches MODERATE, and the levels step back down when cool. The thresholds (0.65,
   0.75, 0.85, 0.95) are estimates and want tuning on the phone.
3. **The hints**: `hint` should read "frame N threads, workers 8"; Perfetto should show the main and Filament threads
   on steady clocks. Whether the S25's ADPF honours sessions for a TextureView app is unknown.
4. **MSAA's cost**: GPU ms with it on and off (the panel's Filament line), and AGI to confirm the multisampled
   render-to-texture path (no stored multisampled buffer).
5. **The streaming hitch** on a drive at 100 km/h: the material-instance check is in the phone's Filament too (same
   1.81 source, on by default), so the fix should carry over; to measure there.
6. **The build's heap**: the "town built, memory trimmed" breadcrumb's peak, and the impostors baked
   ("tree impostors" in the reports) without an OutOfMemoryError.

## 7. Contact sheet

Desktop renders, not S25 captures: `-Dshots=downtown`, `-Dmsaa=1` (left) against `-Dmsaa=4` (right), crops at full
resolution: from the camera's usual height, along the main street, at night, over the plaza. (Scratchpad of the session
that made them: `heat/contact.png`.)
