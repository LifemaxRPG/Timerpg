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

**Status: on by default since step 1b** (`WorldFar.oceanOn`; previews `-DworldOcean=0` for the flat sea). Jeff's G002 ocean
(`OceanSurface`, ocean.mat, its palettes and wave specs unchanged) placed in the world: its look is the town's look
matrix times the metres' scale and a translation to an anchor on a 4 km lattice near the eye (its coordinates stay small
wherever the coast is; the waves stay put as the eye moves), its world position for Filament's fog the same (a new
`worldOf` parameter in ocean.mat; identity in Jeff's preview, which is unchanged), its sea level the far view's. It is
drawn while a sea cell of the skeleton lies within 14 km (checked every 300 m) and the eye is under 3 km; while drawn,
the far view's flat sea sinks under the troughs (`WorldFar.oceanDropM`, the waves' amplitude + 0.6 m) so the two never
fight; past its 24 km skirt the flat sea runs on to the horizon. Palette by the hour (`OceanPalette.forHour`), calm by
default (the weather's hook: `WorldOcean.spec`).

**Fixed in step 1b** (it read as one flat teal in step 1): in the world the water's coordinates run to kilometres from
the anchor, and ocean.mat's fragment noise was mediump: `hash21`'s `p * 456.21` overflowed to infinity, `fract(inf)` is
NaN, every band test failed on NaN and the last `else` left the whole sea the crest colour. The noise, its inputs and the
time are highp now (found with NaN-coloured debug renders); Jeff's preview, near its origin, computes the same values. The
anchor's step (the waves' phase jumping when the eye crosses into the next 4 km cell) still wants a fix before ships
(step 3).

The coast itself is the near terrain's (wet sand at the water's edge, sand under 3.5 m by the sea, dunes behind, beaches
by biome) with the surf drawn on it (step 1b: section 9); the flat sea and the coastline follow the player with the far
view's re-plans.

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
**The seam signal is gone (step 1b)**: step 1 measured bumps within 2.5 m of a chunk's seam three times those elsewhere
at the 99th percentile (0.3 against 0.1 m/s2) and one 10.1 m/s2 jolt at 115 km/h 0.8 m from a seam. Each heightfield chunk
now reaches one cell (2 m) into its neighbours (`VehicleWorld.chunkOverlap`, 35 samples where it had 33; the shared
samples are the same heights, so the two surfaces coincide where they overlap): the same drive measures p99 **0.1 m/s2
at the seams, worst 1.2** (5,890 samples), away from them p99 0.1, worst 1.7 (35,957); never under the ground, no rescue,
physics step p99 0.21 ms (desktop). `-DchunkOverlap=0` puts the old edge-to-edge chunks back for a comparison.

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
- Open: the terrain's line index (and since step 1b the country's scatter cells, ~5 MB) not yet counted in the RAM
  budget, region tiles (T2) if the far view's 1 km level looks too coarse from mountains; from step 1b (section 9): the
  trees have no collision yet, gas stations at the highways' roadside sites, a floating origin before trees and things
  are placed past a few hundred km.

## 8. Tests and previews

- `gradle :desktop:crossroadsTest --tests WorldStreamTest` (the route, the re-plan's vertices and ground, the streamer's
  stray, the physics drive; about 3 minutes).
- `gradle :desktop:crossroadsTest --tests GpuWorldDriveTest` (the capital built with the world, the chase camera driven
  to Evanvale in real time: about 20 minutes; `WORLD_DRIVE_KM=10` for a shorter run). Writes
  `desktop/build/world-drive/drive.txt` and the renders.
- `gradle :desktop:crossroadsTest --tests WorldViewsTest` (step 1b: the capital built with the world, the camera jumped
  to each view along the drive and at the nearest coast, about 2.5 minutes; `WORLD_VIEWS=name,name` for some only,
  `WORLD_VIEWS_OUT=dir`, `WORLD_OCEAN=0`, `WORLD_PROPS=0`). Writes `desktop/build/world-views`.
- `-DworldStream=0` (the far view planned once, as before), `-DworldOcean=0` (the flat sea), `-DworldProps=0` (the bare
  country), `-DchunkOverlap=0` (the physics chunks edge to edge) in the previews and tests.
- Unchanged: `GpuBudgetTest`, `GpuRouteTest`, `OpenWalkTest`, `VehiclePhysicsTest`, `HeightfieldSeamTest` (the capital
  alone; the streamer is attached only with the world).

## 9. Step 1b: the open country (2026-11-26)

Step 1's drive went through a bare country: one ploughed-field picture everywhere, terraced grey cliffs on every steep
hillside, a 100 m wall of rock beside the road where it ran under a bluff, no trees, fences or farms, the near ocean off.
Step 1b makes it a place (renders: desktop, software GL, not S25 captures).

### The ground (`WorldCountry.cover`, `WorldTerrain.material`, `TerrainPics`, terrain.mat)

- **Land use from the planet's data**: on farmed land (the skeleton's crop belt on a farmable biome) plots of ~200 m
  (each split in two), each one use by its belt (`WorldCountry.USES`: wheat, corn and other row crops, ploughed ground,
  fallow, pasture, hay meadow, orchards, paddies), its rows along its long side, a grass verge round it (at least half a
  coarse quad wide, so the field edges stay lines from afar); steep plots left to woods and pasture. Woods by a slow
  noise, ragged at the edges, more of them on steep ground and along rivers, and the odd woodlot plot; the wild biomes
  their own ground (forest floor, meadow, scrub, dry grass, marsh, dunes, gravel). The drive's country is temperate
  forest in an orchard belt: orchards, pasture, meadow, hedgerows and woods.
- **Nine new pictures** (21 layers): wheat, row crop (two ways), pasture, meadow, scrub, marsh, dunes, wet sand, at the
  town's texel, in the same pixel-art way (flat shades, an ordered dither, small things scattered).
- **The atlas is ASTC** where the GPU reads it (the town's encoder): 21 layers in **28.0 MB** where step 1's 12 took 64.0
  as RGBA8.
- **Rock only where truly steep**: the near ground terraces into cliffs only past a slope of 0.95 (whole at 1.35;
  `CLIFF_NEAR_FROM`), not the relief's 0.62 (cross-language, unchanged: the far view's finest level still uses it, 3 km
  off); scree only right under a cliff. **Cuttings and embankments**: the land's detail comes back beside a road over
  36 m or 1.6 m a metre it stands off the grade, at most 200 m (`CUT_SLOPE`, `CALM_ROAD_MAX_M`): a grassy slope of about
  1 in 1.1, not a rock wall.
- **The shore**: wet sand at the water's edge (under 0.45 m), sand, dunes behind (marram tufts), and **the surf**:
  terrain.mat draws a ragged foam line at the water's edge and a broken second one where the last wave reached, in whole
  art texels, on nodes that reach the sea (a new `sea` parameter: its level, the unit, on).

### The trees and things (`WorldCountry.scatter`, `WorldProps`, `WorldPropKit`)

What stands is a function of the world position alone, made in cells of 134.4 m on the town's workers (median 2.2 ms a
cell, p90 6.5), streamed round the eye:

| | reach | how |
|---|---|---|
| trees and bushes in 3D | cells within 50 m (kept to 75) | the town's TreeKit, simple level, every look grown once at a reference height and scaled; one merged mesh a cell (one draw, its shadow) |
| trees as impostors | to 1.6 km | the town's `TreeImpostors` (8 views, its own atlas baked once for the country's 88 looks: 7.6 MB), tiles of 4 x 4 cells (538 m; 2 x 2 until the batching below) packed again as the eye goes round; a tile leaves out its cells drawn in 3D and swaps in the same frame (never a tree twice, never a gap) |
| fences, poles and wires, mile markers, billboards | to 750 m, faded by screen door | `WorldPropKit`: pixel pictures in one fixed atlas; merged by block (below) |
| farmsteads | to 1.5 km, faded | the capital's farm models (farmhouse, barn, silos, shed, tractor, hay, troughs) round a dirt yard in a plot's corner, each on the lowest ground under it; meshed once into two atlases of their own (`WorldFarmKit`) and merged by block (below) |
| woods past 1.6 km | to 3 km / the horizon | the near terrain's forest floor, the far view's dark green |

- **Trees**: woods on a 7.2 m jittered lattice (species by biome: oak, maple, linden, birch, copper beech, spruce, pine;
  the taiga's conifers; poplars and birches in marsh), smaller trees and bushes at a wood's edge, clearings; orchards in
  rows (one fruit an orchard); **hedgerows** along plot edges (bushes every 1.9 m off a ruler line, an oak or linden now
  and then; half the edges in orchard and dairy country, an eighth in the wheat and corn belts), **tree lines** along
  some edges and along the rivers' banks, lone oaks in the pastures, scrub's bushes.
- **Fences**: post and wire round pastures (from 1880; post and rail before, and on some), clipped at roads and rivers.
- **By the roads**: power poles with crossarms, glass insulators and sagging wires along county roads and most highways
  (from 1925), telegraph poles along the railways (from 1870), mile markers on the highways (from 1930), billboards set
  back from the highways now and then (from 1915; the game's own: Fizz Cola, Acme Tires, a motel, Rosie's, Sunray Gas,
  Golden Loaf, "Visit Dove Beach", "Drive safely"; painted boards on wooden legs before 1950, a steel frame after).
- **Farmsteads**: about one plot in 14 on farmed land with gentle ground; the yard's dirt and grass are the terrain's
  material (the same function), so trees and orchards keep off it.
- Not yet: **gas stations** (the highways' roadside sites, TOWNS_EVERYWHERE, are not in the near world yet: step 2 with
  the towns), seasons on the fields and trees, breaking through a fence (below).

### Batching (`WorldProps`, `WorldFarmKit`, 2026-11-26)

Step 1b first drew the country with about 1,300 draws in the 12 km chase view (fully settled; 1,029 in the first
measurement, before the far impostor tiles were all up), some 900 of them the farm models: each model a draw for every
group of its faces (its atlas, each plain colour, its yard's pictures), as the town draws a building. Now:

- **The farm models are meshed once** (`FilamentModels.Mesher`, the same faces in the same model pixels) and their pictures
  packed into **one atlas** with a swatch for each plain colour (a plain face is the white picture tinted; here its
  colour's texels: the same linear colour once the sRGB picture is read), the farmhouses in **a second** with their
  windows' and glass's mask (the panes reflect the sky as the town's do). A cell's farmsteads are then one mesh, their
  opaque triangles first (each judged on its own picture, as before). The country's farmhouses light a fixed share of
  their windows at night (the town's switch theirs one by one by the hour): a night-only difference, far off.
- **Blocks of 4 x 4 cells** (538 m): where the things and farmsteads stand whole (inside their reach's fade: 630 m for the
  things, 1,305 m for the farmsteads) a block's cells are **merged into one mesh** a kind (things, farm buildings,
  farmhouses), each a batch culled by its box; the cells in the fade at the reach's edge stay a batch each, faded on their
  own exactly as before. A block leaves out what it does not hold whole and swaps in the same frame as those cells' own
  batches come and go (the impostor tiles' way): never a thing twice, never a gap. A whole cell joins 10 m inside the
  line and leaves once past it (no churn standing on it).
- **Impostor tiles of 4 x 4 cells** instead of 2 x 2; a tile still holds a 2 x 2 quarter only where step 1b's 2 x 2 tile
  would have been drawn (the same reach, kept 120 m past it), so the same trees are drawn.
- The textures are one per atlas (shared by content across every batch); each batch keeps its own material instances
  (its own fade, see-through and opaque/cut-out twin, as PropLayer has them).

| the views, fully settled (`WorldViewsTest`, WORLD_VIEWS_SETTLE=600) | draws before | draws after | country's draws before -> after | pixels differing (any / by more than 8 of 255) |
|---|---|---|---|---|
| open_country_12km (chase) | 1,621 | **438** | 1,291 -> **108** | 0.043% / 0.000% |
| open_country_21km (chase) | 1,435 | 454 | 1,088 -> 107 | 0.151% / 0.011% |
| road_06km (chase) | 1,885 | 474 | 1,531 -> 120 | 0.097% / 0.008% |
| road_14km (chase) | 2,020 | 455 | 1,687 -> 122 | 0.018% / 0.000% |
| farm_valley (120 m up) | 1,867 | 464 | 1,523 -> 120 | 0.044% / 0.010% |
| valley_09km (40 m up) | 8,479 | 7,227 | 1,365 -> 113 | 0.103% / 0.000% |

Triangles are the same (12 km: 2,288,568 both). The country's draws are counted as primitives now (a batch split into
its opaque and cut-out triangles is two; the farm models were counted so before); the shadow twins are not counted, as
before. Of the 108 at 12 km: 4 3D tree cells, 41 impostor tiles, 9 things' blocks and 22 things' cells in the fade, 24
farm primitives in 6 blocks and 8 in cells. The country's GPU in that view: 50.2 MB before, 67.3 MB after: the two farm
atlases (2048 x 4960 and 2048 x 912, with the farmhouses' mask) are 17.1 MB; the farm models' own pictures before were
shared with the capital's farms and not counted to the country.

### Collision (`CountrySolids`, physics_core `addStatics`, 2026-11-26)

The car crashed through trees, poles and farm buildings. Now the cells round the driven car are Jolt statics, made from
the same scatter the country is drawn from (`WorldCountry.scatterShared`: the drawn country's cells, made once):

- **Trunks**: an upright cylinder each, 3.5 m tall from the foot, radius 3.5% of the tree's height (TreeKit's trunks), 0.12
  to 0.6 m, rounded to 2 cm so the shapes are shared; **bushes and hedges are not solid** (as the town's hedges).
- **Poles**: a cylinder, 0.15 m, their whole height. **Billboards**: their two legs. Mile markers are left out.
- **Fences**: their runs as thin boxes (0.2 m) as tall as the posts, runs on one line joined up to 18 m: a car crashes
  into a post-and-wire fence (breaking through one at speed, with debris, is for later: the town's Wreckage way).
- **Farm buildings**: each model's standing parts as boxes (FarmSolids' way: walls, posts, tanks, bales, the tractor; not
  roofs or cards), from the farm's models.json (the app's `farmArt`).
- **Streaming**: cells (134 m) within 70 m of the car or of where it will be in 1 and 2 s are made on a worker (its
  records: 2-8 ms a cell on this server) and added on the game's thread as **one batch** (`Physics.addStatics`:
  `AddBodiesPrepare`/`AddBodiesFinalize`, shapes shared by size); cells past 110 m go as one batch (`removeAll`:
  `RemoveBodies`/`DestroyBodies`); the broad phase is optimised (`OptimizeBroadPhase`) after every 4,000 bodies added.
  Freed handles are reused lowest first (a heap: the old scan of every slot made a cell's thousand adds quadratic).
- The world with a terrain allows 32,768 bodies (`VehicleWorld.WORLD_BODIES`); the body pairs and contacts stay at 4,096
  (their buffers come from the 4 MB step allocator: 32,768 of each overran it and aborted).
- The crash: `onImpact` hears the speed lost (12-14 m/s at 45-50 km/h into a tree, a pole, a fence, a barn), so the
  crash sound and the player's hurt come as in the town.

Measured (this server, not the S25): 5,000 statics added as one batch in 2.5 ms, optimised in 0.9 ms, removed in 0.7 ms
(one at a time: 3.8-7.5 ms in, 1.7-5.9 ms out); the drive's cells (a few hundred statics each) add in 0.1-0.2 ms.
`CountryCrashTest`: full throttle from 35 m into an oak, a power pole, a wire fence and a red barn: the car stops with its
middle 1.5-2.1 m short (its bumper against it), 0 m/s, the impact 13.8-14.0 m/s; not solid, it drives on to 135 m; a
bush is driven through. `WorldStreamTest`: the real country, a tree and a power pole beside the drive's road on the
streamed terrain: full throttle from 24 m (45 km/h): stopped 2.7 and 2.2 m short, impacts 12.1 and 11.6 m/s; and the
whole drive to the next town at 120 km/h with the country solid meets none of it: 24.6 km, up to 2,497 statics at once
in 387 batches, the longest add 0.33 ms, removal 0.59 ms, optimisation 0.40 ms (14 of them), a cell's records 2.5 ms
median on the worker; the physics step's p99 4.0 ms as before. (Its pure-pursuit driver used to look 18 m + 0.9 s ahead,
which cut a junction's corner 7.7 m off the route, into a power pole 2 m past the verge, once the country was solid: a
real crash, the driver's; it now looks 8 m + 0.8 s ahead and keeps within 4.7 m of the route.)

GpuWorldDriveTest with both (this server, shared with another job's tests during the run: the CPU tail is not a clean
comparison): Filament's peak **561.3 MB** (533.0 before), the world's own peak **173.7 MB** (145.2), both 8 km out at the
wooded stretch, the difference the farm atlases (17.1) and the blocks' merges swapping in; the budgets raised to 570 and
180 (`TownBudget`, with this reason). CPU a frame: median 2.51 ms (2.76), p99 8.14 (6.43), p99.9 20.1 (17.2), over 16.7
ms 71 of 44,340 (46). The chase camera's frame 12 km out: **440 draws** (1,545), 2.32 M triangles.

### Measurements (desktop, the drive to Evanvale at 120 km/h, `GpuWorldDriveTest`, 2026-11-26)

| | step 1 | step 1b |
|---|---|---|
| Filament's whole count, peak | 556.4 MB (budget 575) | **533.0 MB** (budget lowered to 550) |
| the world's own GPU, peak | 132.0 MB (budget 140) | **145.2 MB** (budget raised to 155, `TownBudget.WORLD_MB`, with this reason) |
| of which the ground's pictures | 64.0 (RGBA8) | 28.0 (ASTC, 21 layers) |
| of which the country (trees, impostors and their atlas, things, farms) | - | 30-60 MB along the drive (the most at the wooded stretch 8 km out) |
| the world along the drive once out of town | 108-125 MB | 107-144 MB |
| CPU a frame (the engine thread's share; this machine) | median 1.75 ms, p99 3.73, p99.9 11.8; over 16.7 ms: 20 frames | median **2.76**, p99 **6.43**, p99.9 17.2; over 16.7 ms: 46 of 44,340 |
| far view uploads | median 0.15, worst 2.05 ms | median 0.12, worst 6.19 ms |
| near terrain drawn coarser than wanted | 218 frames | 236 frames |
| the chase camera's frame 12 km out (draws, triangles) | 330, 1.63 M | 1,545, 2.11 M (21 km: 1,356, 2.33 M) |

The frame's extra CPU is the country's uploads (within 1.5 ms a frame, at least one) and its bookkeeping (the whole
pass every 6 frames or 8 m; between them only finished jobs are taken). The views test (`WorldViewsTest`) settles each
view in 4-10 s.

### What needs the S25 (besides section 6)

1. The country's draw cost on the Adreno 830: since the batching about 110 draws (and their shadow twins near by) and
   ~0.5 M more triangles in a chase view, the impostor tiles' alpha-cut quads, the 3D trees' shadows; the blocks' merges
   uploaded as the eye goes (a block's things or farmsteads, 0.5-2 MB) in Perfetto. If the frame is over, the impostors' reach (`WorldProps.R_IMP`) and the
   3D reach (`R3D`) are the knobs, not the content.
2. The workers' share at speed: a cell's scatter 2-7 ms on the desktop's JVM, tiles packed again as the eye goes round;
   the frame's extra 1 ms of uploads and bookkeeping in Perfetto.
3. The ASTC terrain atlas's look on the phone (the desktop decodes ASTC in software, as the phone's GPU does in hardware:
   the same pixels, but worth a glance), and its encode time on first launch (21 layers, a worker).
4. The surf line and the waves at the beach on the phone's screen, and the near ocean's cost (section 6, item 4).
5. The country's statics on the phone: a cell's batch add and removal on the game's thread (0.1-0.2 ms here), the
   broad-phase optimisation (about 1 ms here for 5,000), the physics step with a few thousand statics round the car, and
   how a crash into a tree feels (the impact sound, the hurt) on the device. The farm atlases' ASTC encode on first launch.
