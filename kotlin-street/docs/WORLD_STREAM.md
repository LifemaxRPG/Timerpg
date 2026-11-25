# World streaming: the open country follows the player (planet step 1, 2026-11-25)

The owner's decision (CLAUDE.md, "Next milestone", 2026-11-25): the planet-scale features start now, each measured on the
S25 before the next. Step 1, this document: **the world streams round the player** (terrain, coast and ocean) within a
fixed memory budget, proven by a drive from the capital to the next town at highway speed. Steps 2-4 (towns everywhere,
travel between them, Dove Beach) come after; the end of this document says what they need from it.

Every number here is **desktop** (the 4-core x86 build server, software GL, the JVM) unless it says S25. Filament's own
memory count matches the phone's to ~2% (MEMORY_AUDIT.md), so the MB are a good stand-in; **the times are not the
phone's**. Nothing in this document has been measured on the S25 yet.

---

## 1. What was there, and what was missing

W4/W5a (WORLD_GEN.md) drew the world round the capital: a far view of three nested terrain levels to 172 km
(`WorldFar`, planned once round the town), the near terrain's quadtree of chunks round the camera (`WorldNear`, but only
on the far view's finest level, 8.6 km round the town), the horizon ring past 167 km (`HorizonPanorama`, already re-baked
as the eye moves), a flat sea plane, and the cars' heightfield chunks round the driven car (`VehicleWorld`). Driving out
of town, three things broke:

1. Past ~5 km the near terrain stopped (no root block lies wholly on the far view's finest level any more) and past 172 km
   there was no far view at all.
2. The capital itself vanished 1.1 km from the eye (`TownFar.REACH`): a pop as you drove off.
3. The app moved the render origin to the player's chunk 3 km out of town while the player, camera and cars stayed in the
   capital's street units: every world part would have jumped by the distance from the capital. (Latent: no one had
   driven that far.)

And the drive found two more on the way (section 4): a 60% terraced cliff under the expressway (the relief was only
calmed to 40% beside roads) and a 10 ms physics hitch each time the car crossed into new heightfield chunks.

## 2. The design

### The chain, near to far (all following the player now)

| distance from the eye | what | code | made where |
|---|---|---|---|
| 3 x 3 chunks of 64 m round the car, and round where it will be in 1.75 and 3.5 s | physics heightfields (Jolt) | `VehicleWorld.streamGround` | a low-priority worker ahead; on the spot only if wanted now and not ready |
| 0 - 3 km (+ a root block) | near terrain: quadtree nodes 2.1 m - 33.6 m quads, pixel-art materials (terrain.mat), cliffs faceted | `WorldNear` | the town's workers, 6 in flight, coarse first then nearest; also the nodes the eye will want **4 s ahead** at its speed |
| 0 - 14 km of sea | the near ocean: Jeff's pixel-art Gerstner ocean (G002), 1 draw, 24 km skirt (**off by default**: section "The near ocean") | `WorldOcean` (`OceanSurface`) | made once, shown only when the sea is near |
| to 8.6 km | far view level 0 (134 m quads wearing the near ground's materials), in root blocks the near terrain hides | `WorldFar` | re-planned on a worker |
| to 52 km | level 1 (1,075 m quads) | `WorldFar` | " |
| to 172 km | level 2 (4.3 km quads), roads, rivers, rail, other towns as blocks, the flat sea | `WorldFar` | " |
| 167 - 640 km | the horizon ring (a baked panorama in the sky) | `HorizonPanorama` | re-baked on a worker every 1.5 km |
| to 60 km | the capital's own far boxes and districts stay on the skyline | `TownFar.reach` | (the town's) |

### The re-plan (`WorldStream`, `WorldFar.streamStep`)

- The far view's levels are planned round a **middle on the coarsest level's lattice** (4 atlas cells, 4.3 km). A plan
  round another lattice middle has every vertex it shares with the old one at the same place and height (the levels'
  vertices are on the cells' centres and the 0.125-cell lattice; only the finest level's outer four quads fade to the
  cells' heights, and those are 8 km off). `WorldStreamTest.aReplanKeepsEveryVertexAndTheGround` checks it: 20,330
  shared vertices bit-identical, and the near terrain's ground identical at 4,000 random points.
- The render origin and the plans' origin stay the capital's frame (section 5): only the rings' extent moves.
- `WorldStream` eases the eye's velocity and asks for a new plan once the eye, or the eye 12 s ahead, is **2.8 km** from
  the middle on either axis; the new middle is the lattice cell (of the four round the way ahead) nearest both. The near
  terrain's roots must lie wholly on the finest level, so the eye may stray at most 8,601.6 - 3,000 - 2,150.4 = **3,451 m**:
  re-planning at 2,800 leaves 650 m (18 s at 130 km/h) for the plan to be made and uploaded; the best middle is at most
  ~2.4 km from the eye, so a new plan never asks for another at once. Over the drive at 130 km/h with a 3 s latency the
  eye stayed within 3,222 m (`theStreamerKeepsTheEyeOnTheFinestLevel`).
- The next plan (its terrain and every part packed) is made on a worker, uploaded **within 1.5 ms a frame** (the first
  part always), hidden until all of it is up, then swapped in whole in one frame (the old parts hidden, the new shown);
  the old parts are freed within the same 1.5 ms a frame (their materials through the graveyard). The near terrain
  takes the new terrain (the same function) and keeps every node it has up.
- **Generate, don't bake** (the brief's "measure and decide"): a plan takes 81-121 ms and its terrain 75 ms on one
  desktop thread (packing: section 4); a re-plan is needed every ~4 km, i.e. every 2 minutes at 120 km/h. Generating on a
  worker is a fraction of a percent of one core; baking the planet to flash would buy nothing. Revisit only for region
  tiles finer than today's (WORLD_GEN T2).

### Per-frame time budget

| | budget a frame | was |
|---|---|---|
| far view parts (upload and free) | 1.5 ms (`WorldFar.UPLOAD_NS`), the first part always | one part a call |
| near terrain nodes | 2 nodes and 2.5 ms (`WorldNear.UPLOADS`, `UPLOAD_BUDGET_NS`) | 3 ms |
| the world together | **4 ms**, the capital's own upload budget (TownSession) | - |
| physics heightfields | ~0 on the game thread at a steady drive (made ahead); an unready chunk wanted now ~3-4 ms (desktop) | 10 ms p99 |

### Memory budget (fixed, wherever the player is)

| | budget | enforced by |
|---|---|---|
| the world's GPU (far view incl. both plans during a swap, near nodes incl. cache, ground pictures, near ocean) | `WorldMemory.GPU_BUDGET` 250 MB; the drive's measured cap `TownBudget.WORLD_MB` | `GpuWorldDriveTest` fails over it |
| the near terrain's nodes (buffers + material maps, cache included) | `WorldMemory.NEAR_GPU_BUDGET` 64 MB | `WorldNear.evict` (least recently drawn first) |
| far view plans | at most two (the drawn and the next); the old freed over frames | `WorldFar` |
| the whole GPU along the drive (capital streamed + world) | `TownBudget.WORLD_ROUTE_MB` | `GpuWorldDriveTest` |
| the world's RAM | `WorldMemory.RAM_BUDGET` 200 MB (skeleton grids 18 MB, plans' arrays while built) | reported; the terrain's line index is not yet counted |

### The near ocean (`WorldOcean`)

**Status: wired, but off by default** (`WorldFar.oceanOn`; previews `-DworldOcean=1`). Jeff's G002 ocean
(`OceanSurface`, ocean.mat, its palettes and wave specs unchanged) placed in the world: its look is the town's look
matrix times the metres' scale and a translation to an anchor on a 4 km lattice near the eye (its coordinates stay small
wherever the coast is; the waves stay put as the eye moves), its world position for Filament's fog the same (a new
`worldOf` parameter in ocean.mat; identity in Jeff's preview, which is unchanged), its sea level the far view's. It is
drawn while a sea cell of the skeleton lies within 14 km (checked every 300 m) and the eye is under 3 km; while drawn,
the far view's flat sea sinks under the troughs (`WorldFar.oceanDropM`, the waves' amplitude + 0.6 m) so the two never
fight; past its 24 km skirt the flat sea runs on to the horizon. Palette by the hour (`OceanPalette.forHour`), calm by
default (the weather's hook: `WorldOcean.spec`).

**What is wrong**: in the town's view the waves read as one flat teal (the palette's crest colour everywhere), not the
stepped bands of Jeff's preview. The geometry, depth and placement are right (it meets the land and the far sea where
it should, and the difference against the flat sea is confined to the water); the shading's inputs are not yet. Not
found in this step; the far view's flat sea is drawn instead until it is. The anchor's step (the waves' phase jumping
when the eye crosses into the next 4 km cell) also wants a fix before ships (step 3).

The coast itself is the near terrain's (sand under 3.5 m by the sea, beaches by biome, WORLD_GEN W5a); the flat sea and
the coastline follow the player with the far view's re-plans.

### Seamless with the capital

- The capital's far boxes are drawn out to 60 km with the streamer on (`WorldFar.TOWN_REACH_M`), so it stays on the
  skyline as you drive off; the air's haze has taken it by then (90 km visibility: 86% at 60 km). The far view still
  leaves the capital out of its town blocks.
- The capital's ground meets the terrain exactly as W5a made it (the terrain flattened to the town's height under and
  150 m round its ground, blending over 900 m; the town's grass at its edge); the streamer does not change it: the
  terrain is the same function whatever plan made it.
- The hand-over is not a switch: the near terrain, the far view and the town's own layers are all drawn together all the
  time; leaving town only moves which nodes and rings are wanted.

## 3. Roads on the terrain

The world's roads (Highways v2 as they are in the town's year) were already drawn as ribbons in the far view and as
asphalt and gravel in the near terrain's materials. New:

- **Graded**: on a road, rail bed and their shoulders the ground is the cells' smooth height; the relief's detail returns
  over 36 m beside it (a cutting or an embankment). Before, it kept 40% of the relief, which on the drive left a 60%
  terraced step on the expressway (the car stopped dead against it).
- **Over rivers**: a road keeps its grade and its asphalt over a river (no carved bed); a real bridge is W6.
- `WorldRoute` (shared/domain): the roads as a graph (segments split where one ends on another's middle), the fastest
  path between two points at the classes' speeds: the drive's route, and the start of step 3's sat-nav and AI drives.

## 4. Measurements

### The drive

The next town from the capital (gamma on `alpha`) by road: **Evanvale** (a town), **24.6 km** by road (highway 2 segments,
expressway 3, county 1; 14 min at the roads' speeds), 21 km straight. (The nearest hub, Yellisbury, is 71.5 km straight.)

### Physics on the streamed ground (`WorldStreamTest.aCarDrivesToTheNextTownAtHighwaySpeedOnTheStreamedGround`, desktop)

The 1956 sedan driven by a pure-pursuit driver at up to 120 km/h (slower only where a bend's radius asks for more than
4 m/s2 across) from the capital's east pin to Evanvale, over the streamed heightfields, its terrain swapped at each
re-plan:

| | |
|---|---|
| driven | 24.6 of 24.7 km in 14.0 simulated minutes, average 105 km/h, top 115 km/h; 8 re-plans (terrain swapped under the car) |
| falls, rescues | **none**: never under the ground's surface (0.00 m), no safety-net rescue |
| off the route | 8.8 m at most (the driver, not the ground) |
| vertical acceleration (the bumps), within 2.5 m of a chunk's seam | p99 **0.3 m/s2**, worst 10.1 (5,895 samples) |
| the same away from seams | p99 0.1 m/s2, worst 1.2 (35,946 samples) |
| physics step with the ground's streaming (game thread) | median 0.031 ms, **p99 0.18 ms** (was 9.96 before the chunks were made ahead), worst 17.6 ms |
| chunks made ahead on the worker / on the spot | 1,491 / 853 (the test steps about 6x faster than real time, so the worker had a sixth of the wall time it gets in play: the on-the-spot count is an upper bound) |

Before the fixes the same drive stopped dead 9.4 km out against a 60% terrace under the expressway, and jolts of up to
218 m/s2 came from the driver taking a county road's bends at 113 km/h (the driver now slows for a bend's radius).
**A small seam signal remains**: bumps within 2.5 m of a chunk's seam are three times those elsewhere at the 99th
percentile (0.3 against 0.1 m/s2, both far below what a driver feels) with one 10 m/s2 jolt at 115 km/h 0.8 m from a seam.
Worth a follow-up (overlapping the chunks by one cell, or one heightfield for the 3 x 3), and a look on the phone.

### Frames and memory (`GpuWorldDriveTest`, desktop)

The capital built as the open-world preview builds it with the world round it, then the chase camera driven to Evanvale
at 120 km/h, 44,340 frames at 60 Hz paced to the wall clock (2026-11-25, three runs agree to a few %; the last):

| | |
|---|---|
| Filament's whole count, peak | **556.4 MB** (0.3 km out: the capital's streamed share still near, plus the world); budget `TownBudget.WORLD_ROUTE_MB` 575 |
| the world's own GPU, peak | **132.0 MB**; budget `TownBudget.WORLD_MB` 140 (design cap 250): ground pictures 64.0 (RGBA8; 16 as ASTC), 200-240 near nodes 31 MB, far view 15-17 MB (both plans while swapping) |
| the world's RAM | 24.9 MB (skeleton grids 19.9, facts ~5) of 200; a plan's arrays only while it is built |
| along the drive | GPU 500-517 MB once out of town, the world 108-125 MB: flat, not growing with distance |
| far view | 8 re-plans swapped in; planned in 88-166 ms and packed in 348-423 ms on a worker each; upload and free on the engine's thread median 0.15 ms, worst 2.05 ms (195 frames did any) |
| near terrain | 3,567 nodes built (median 16.4 ms of one worker each, p90 21.7, max 66); 151 drawn (1.31 M triangles in 151 draws) out of town; a wanted node not yet up (drawn one level coarser) in **218 of 44,340 frames** (0.5%) |
| CPU a frame (streaming, building and the frame's CPU side, not the GPU's draw) | median 1.75 ms, p99 3.73, p99.9 11.8, worst 152 (one GC or the first swap); over 16.7 ms: 20 frames, over 33 ms: 6 |
| the jump to a coast 143 km off (a teleport, for the renders) | settled in 130-200 frames (2-3 s): re-plan, swap, near nodes |

Earlier runs of the same drive measured: peak 557.7 MB, the world 130.0-130.6 MB, p99 4.46-4.65 ms, coarse frames 210-219.
The software GL draws nothing here (the frame call prepares the scene on the CPU; Filament's draw on llvmpipe is not
timed), so the CPU times are the engine thread's share only, and llvmpipe's own work is not in them.

### Precision

The player, camera and cars stay in the capital's street units (section 5). At the drive's end (23 km) a float's step is
0.031 street units (1.5 mm); at 100 km it would be 0.125 units (6 mm), at 400 km 0.5 units (2.4 cm). Fine for step 1's
drive; not for the whole planet (section 5).

## 5. Coordinates: what this step does not do yet

The world's parts are placed relative to the render origin in doubles, but the player, camera, physics and the town's
entities live in the capital's street units (floats). Re-centring the render origin alone would move the world against
everything else, so **the app no longer moves the render origin** (FilamentLayer); everything stays in the capital's
frame. A true floating origin (re-basing the player, the orbit camera, the physics bodies and each town's root when the
origin moves) is needed before journeys of hundreds of km and before a second town is lived in; it belongs with step 3.

## 6. What needs the S25

1. **The drive on the phone**: the same route (Evanvale) at 120 km/h from the play log: the `mem` line (GPU total and
   the world line) every 5 s, frame times in Perfetto while a far plan uploads and swaps (expect the 1.5 ms slices),
   and whether any near node is drawn coarser than wanted at speed (`WorldNear.coarseFrames`).
2. **Heat**: a 15-minute drive is a sustained load of near nodes (14 ms each on the desktop JVM) and re-plans every
   2 minutes; watch the thermal status (HEAT.md) and, if it climbs, lower `WorldNear.IN_FLIGHT` while hot.
3. **Pop-in at speed**: the near terrain's 4 s lead and the far view's swap seen on the phone's screen (a ring's far rim
   changing 8 km off should be invisible in the haze).
4. **The near ocean on the Adreno 830**: its cost per frame with the terrain (one draw, ~100k triangles), and its look.
5. **Physics**: the heightfield worker's share of a core; chunks made on the spot (`VehicleWorld.chunksNow`) should stay
   near zero.

## 7. What steps 2-4 need from this

- **Step 2, towns everywhere**: a place's town streams in where the far view now draws its blocks: `TownStream`-like
  residency per town by distance (TOWNS_EVERYWHERE.md, "Streaming plan"), the far view cutting its roads at each town's
  ground edge (`WorldFarPlan.addCut` for every town, not only the capital) and flattening the terrain under each town
  (`WorldFarPlan.shapeRaw` for many frames: a spatial index of town grounds). The streamer's re-plan already makes
  everything a town needs from the world round any middle.
- **Step 3, travel**: the floating origin (section 5) with each town's frame as the origin while in it; `WorldRoute` for
  routes, signs, the sat-nav and AI traffic; bridges and tunnels (W6) where roads cross rivers and ranges; the near ocean
  under a ship (`OceanSurface.boatTransformInto` is there) and the far coast silhouettes from the sea (WORLD_GEN "Seeing
  land from the sea").
- **Step 4, Dove Beach**: a coast town: the near ocean and beaches must meet its waterfront; judge the ocean against its
  harbour in renders from the beach and from the sea.
- Open from step 1: trees and props on the open country (the land is bare between towns), a small remaining seam signal
  in the physics (section 4), the terrain's line index not yet counted in the RAM budget, region tiles (T2) if the
  far view's 1 km level looks too coarse from mountains.

## 8. Tests and previews

- `gradle :desktop:crossroadsTest --tests WorldStreamTest` (the route, the re-plan's vertices and ground, the streamer's
  stray, the physics drive; about 3 minutes).
- `gradle :desktop:crossroadsTest --tests GpuWorldDriveTest` (the capital built with the world, the chase camera driven
  to Evanvale in real time: about 20 minutes; `WORLD_DRIVE_KM=10` for a shorter run). Writes
  `desktop/build/world-drive/drive.txt` and the renders.
- `-DworldStream=0` (the far view planned once, as before), `-DworldOcean=0` in the previews.
- Unchanged: `GpuBudgetTest`, `GpuRouteTest`, `OpenWalkTest`, `VehiclePhysicsTest`, `HeightfieldSeamTest` (the capital
  alone; the streamer is attached only with the world).
