# World generation (phase 1: design)

The world the towns stand in: a small planet made from one seed, with hand-made hub cities placed on it by rules and
generated countryside between them. This is the design; nothing in `src/` or the app changes yet. The prototype that
tests the ideas is `kotlin-street/tools/world-sketch.py` (maps of three seeds: see "The prototype" at the end).

The owner's decisions it starts from (CLAUDE.md, "Hand-made hub towns plus procedural fill", and the brief of
2026-11-23): a planet about 2,000 km round that wraps east to west, polar ice as the north and south borders with no
invisible walls, 3-5 continents with real oceans and about 30% land, about 6 hand-made hubs at launch growing to 20-25
(the capital near the start: the planned town `gamma`, also the test city), generated countryside, rail between hubs on
a continent, sea routes between ports, a 2-3 hour drive across a continent, distance compressed on the open ocean.

## What exists today, and what this replaces

- `src/world/map.ts`: an endless jittered grid of town sites, one per cell of 1200 x 440 tiles (2.5 x 0.9 km). A site's
  id is its cell (`"cx,cy"`), its seed `"<worldSeed>/<cx>,<cy>"`, its name `townName(seed)` (names.ts, styled by the
  archetype). `linkRoad` joins neighbouring main streets.
- `src/world/rail.ts`: the railroad on that grid (main lines on even rows and one column in four, branches, history
  1852-2025, stations by year, timetables, fares, `planRoute`), mirrored in Kotlin (`domain/RailNet.kt`) and checked
  against the TS. `RailWorld { seed: 'world-6', home: 'gamma' }` (tools/town-config.ts) puts gamma at cell 0,0.
- `src/world/lore.ts`: each town's legend points at a neighbouring cell's town; `wild.ts`: trees in 32-tile chunks.
- The app draws one town (gamma) in street units (21.17 a metre, `LandmarkKit.UNITS_PER_METRE`), its far buildings as
  boxes in 900-unit cells out to 24,000 units (1.1 km, `TownFar`), the open world's haze from 1,600 units past the player.

Towns 1 km apart by track are a dense toy grid. The new world keeps every interface that matters (a `TownSite` with an
id, a seed, a name, a founding year and a centre; links with a history; the planner's answers) and replaces where the
sites and links come from: the generated world skeleton instead of the cell grid. The grid stays as a provider for the
existing tests until the new one passes the same tests.

## 1. Coordinates and precision

### The shape: a cylinder with ice caps

The planet is a **cylinder**: x (east) wraps, z (south) does not. Distances east-west do not shrink towards the poles
(no meridian convergence), which keeps chunks square and the maths simple; the ice caps hide the edges. Latitude is a
label for climate (`lat = 90 deg * z / (H/2)`), not geometry.

| quantity | value | why |
|---|---|---|
| tile | 2.1 m (as today, `METERS_PER_TILE`) | the towns' plans, farms and rail are all in tiles |
| chunk | 256 x 256 tiles = 537.6 m | the unit of near terrain, streaming and caching |
| region | 32 x 32 chunks = 17.2 km | the unit of mid-detail terrain and the world skeleton's tiles |
| circumference C | 116 regions = 3,712 chunks = 950,272 tiles = **1,995.6 km** | the brief's 2,000 km, made a whole number of regions |
| pole to pole H | 58 regions = 1,856 chunks = 475,136 tiles = **997.8 km** | half the circumference, as on a sphere |
| atlas cell | 2 chunks = 1,075 m (1,856 x 928 cells) | the planet-wide skeleton grid (the prototype uses 4 chunks) |

The planet is about 2.0 million km2, so 30% land is about 600,000 km2.

### Units and precision

- **World positions are doubles in metres** (`WorldPos(x: Double, z: Double)`, x in [0, C)), and integer tiles/chunks
  where the world is discrete (`ChunkKey(cx: Int, cz: Int)`, cx mod 3712). Doubles hold millimetres at 2,000 km.
- Floats do not: 2,000 km is 42 million street units, where a float's step is 4 units (19 cm). So nothing is ever
  placed in Filament in world coordinates.
- **Camera-relative rendering with a render origin**: the app keeps one `renderOrigin` (a WorldPos snapped to a
  chunk corner). Every chunk, region tile and town is a Filament entity whose root transform is its own origin minus
  the render origin (computed in doubles, wrapped: `dx = ((x - ox + C/2) mod C) - C/2`, then turned into floats and
  street units). Vertices inside a mesh are in the mesh's own local frame (a chunk is at most 11,400 units across:
  float steps of 0.001 units).
- **Re-centring**: when the player is more than 2 km from the render origin, move it to the player's chunk and reset
  the root transforms (a few hundred `setTransform` calls, done between frames; shadows, haze and the camera follow
  because they are camera-relative already). Wrapping east-west is the same operation: there is no seam, only the
  wrapped difference.
- **The towns keep their frames**: a hub's plan is laid in tiles from its own origin as today; the hub's origin is a
  WorldPos. While the player is in a town (within about 3 km of its origin) the render origin is the town's origin, so
  the existing town code (TownFar, TownGround, yards, rail, collision, OpenWalk) runs exactly as it does now, in the
  same street units. Re-centring only happens out in the country, where there is little to move.
- **Depth**: Filament's reversed-Z float depth holds a 0.1 m near plane to a 150 km far plane, so the horizon tier
  draws in the same pass; no second camera.
- **Saved positions**: WorldPos in doubles plus the hub id the player is in (if any) and the hub-local tile position,
  so a save never depends on the render origin.

### The ice caps (borders without walls)

- Sea ice begins around |lat| 62 deg (floes, slower sailing), pack ice from 68 deg (the boat pushes slower and slower;
  a liner's route never goes there), an ice shelf with 40-60 m ice cliffs at 72-75 deg (no boat goes on; you can see it).
- On land the ice sheet rises to a polar plateau (3,000-4,000 m) with crevasse fields, blizzard whiteouts and katabatic
  wind against you. The last 40 km before the pole line is an icefall: real terrain too steep and broken to cross
  (collision, not a wall). Nothing is generated past it.

## 2. The generation pipeline (from the world seed)

Every step is pure in `(worldSeed, ...keys)` like the rest of the game (`rand` in src/sim/rng.ts: keyed hashes, so one
change does not shift every later roll). Two layers:

- **The skeleton** (src/world/gen, TypeScript, the source of truth): everything that becomes a fact somebody can say or
  check: land and sea, heights on the atlas grid, climate, biomes, rivers and lakes, coastlines, every hub, town and
  hamlet with its name, every road, rail line and sea route and their history by year. Made with our own integer-hashed
  noise (exactly reproducible in TS and Kotlin), baked at build time per world seed and shipped as an asset
  (`assets/world/<seed>/skeleton.bin`, a few MB, like rail.json today), with Kotlin reading it.
- **The detail** (native, FastNoise2): everything below the atlas grid: the shape of the ground between the skeleton's
  samples, rocks, banks, meanders, the field-scale texture of the land. It is constrained by the skeleton (it can add
  bumps, never move a river or a town), so tiny float differences between SIMD paths or devices change pixels, never
  facts.

Rule: **features are vectors, terrain is a function.** Rivers, roads, rail, coasts near towns and town pads are
polylines and polygons in the skeleton; the height of the ground at any (x, z) is a function (atlas height, bicubic,
plus detail octaves, then carved and flattened by the nearby vectors' signed distances). Every LOD samples the same
function at its own spacing, so levels agree.

### 2.1 Continents

Plate-like, cheap: about 12-16 plates as a warped Voronoi on the cylinder (distances wrap in x), each with a drift
vector. 3-5 **continent cores** are placed far apart in longitude (a random spacing with a minimum of C / (n + 1.5)),
in the temperate and tropical bands (|lat| < 55 deg); each is a few overlapping anisotropic blobs (an elongated shape
with a random heading), plus domain-warped fBm that makes the coast ragged and throws off islands. The sum is
thresholded at the quantile that gives the target land fraction (30%, counted outside the ice caps). Separate
continents are checked by connected components (each of the n cores is its own mass; a land bridge between two is
cut by lowering the saddle). Smaller components are islands and archipelagos.

### 2.2 Elevation and mountains

- Land base height rises with distance from the coast (a smooth 0-400 m plain-and-plateau shape) plus low fBm.
- **Mountain ranges** where plates converge: for each cell the nearest and second-nearest plate give a boundary
  distance; if the two plates' drift converges, a ridged-noise range (1,500-3,500 m) runs along the boundary, falling
  off over 30-60 km; on a coast it makes a coastal range (the west-coast look), inland a spine. Diverging boundaries at
  sea give mid-ocean ridges (only seen in the depth shading). Old ranges (a per-plate-pair age) are lower and rounder.
- Ocean depth from the distance off the shelf: a 20-40 km shelf at 0-150 m, then the deep at 3-5 km.

### 2.3 Climate

- **Temperature** from latitude (warm at the equator, freezing past 60 deg) minus 6.5 deg C per km of height, plus a
  small ocean moderation near coasts.
- **Wind** by latitude band, as on Earth: trade winds from the east (0-30 deg), westerlies (30-60 deg), polar easterlies.
- **Rain**: moisture is carried along the wind across the wrapped rows (two laps to settle): it is picked up over the
  ocean, rains out where the ground rises (orographic rain on the windward slopes) and a little everywhere, so the lee
  of a range is dry (**rain shadows**: the deserts and prairies behind the mountains). Plus a wet equatorial belt and
  dry horse latitudes (about 30 deg).
- Seasons (for snow and the calendar's look): the temperature swings with the day of the year, more inland and further
  from the equator; snow cover = temperature below 0 on that date (deterministic weather on top: src/ already has days).

### 2.4 Biomes

A Whittaker table on (temperature, rain), then height and water: ice, tundra, boreal forest (taiga), temperate
broadleaf forest, temperate rainforest (wet coasts), prairie/grassland, steppe, desert, savanna, tropical forest,
alpine (above the tree line), marsh (low, wet and flat beside rivers and lakes), beach (a thin coast band on gentle
shores), rock (steep). The skeleton stores one biome per atlas cell; the detail level blends between neighbours with
noise so borders are not grid lines. Farmland is a land use, not a biome: it goes on flat, fertile, well-watered
ground near towns and roads (2.5) and grows over the decades (section 9).

### 2.5 Rivers, lakes and coastlines

- **Hydrology on the atlas grid**: priority-flood from the sea (wrapping in x) fills every pit, so every land cell has
  a path down to the sea; the D8 direction of steepest descent on the filled surface; **flow accumulation** weighted by
  rain (the river's discharge). A cell is a river where the accumulated flow passes a threshold; its width and order
  come from the flow. So every river reaches the sea or a lake, and rivers join, never split (deltas are drawn at the
  mouth as detail).
- **Lakes**: a filled pit deeper than a few tens of metres, or larger than a few cells, stays a lake (its surface the
  fill level, its outflow the pit's spill point); smaller pits are just filled. Mountain glacial lakes come from the same
  rule at the detail level. **Reservoirs** are lakes with a year (section 9).
- **River paths as vectors**: the atlas cell chain becomes a smoothed polyline, then a meander pass (noise displacement
  scaled by width, kept inside the valley) at the region level. The detail terrain carves the bed and banks from the
  polyline's distance (a bank profile by width), with gorges where the river crosses a range.
- **Coastlines**: the atlas land mask is the coast to 1 km; the region level adds fractal detail (bays, spits, rocky
  points on steep coasts, sand on gentle ones) by noise on the signed distance to the coast, never flipping an atlas
  cell's land or sea. Ports' harbours are authored (a hub's pad includes its waterfront).

## 3. Hub placement

The hubs are hand-made towns and cities (plans authored with the town generator as a drafting tool, saved as data).
The world seed only decides **where** each hub stands, by site rules and a score, in a fixed order.

| hub type | site rule (hard) | score (soft) |
|---|---|---|
| **capital** (`gamma`, the start) | on the start continent (the largest), temperate, a river of the plan's size through the site, flat | temperate and fertile; 20-80 km from the coast (on the way to a port); central on its continent (rail reaches out both ways); the plan's river direction matches the world river's |
| **port** | on the coast, sheltered (a bay: little open sea within 10 km), deep water close in | a river mouth; facing another continent across the shortest ocean; a temperate climate |
| **river city** | on one of the planet's largest rivers (flow in the top 3%), low and flat | a confluence; a crossing point (narrow valley); not on the coast (that is a port) |
| **mountain town** | 800-2,000 m high, in a valley (lower than its surroundings) | near a pass between two river basins (the rail line's route over the range); forest and snow |
| **mill town** | on a medium river with fall (slope along the river), forest near | rapids or falls (steep river); hills |
| **farm town** | flat plains, prairie or temperate, good rain | the middle of the plains; far from mountains |

- **Order and separation**: the capital first, then the launch hubs (one each of port, river city, mountain town, mill
  town and farm town on the start continent or its neighbour, plus a port on the second continent so the first sea
  route exists), then the later hubs by score. Hubs on one continent at least 70 km apart (about 40 minutes by car on
  a highway); ports at least 120 km apart; each continent gets at least one port.
- **The capital at the start**: the player starts in gamma (TOWN.md "Spawn"); the capital's site is where the world
  begins. The gamma plan sets constraints the site must meet, not the other way round: the plan's river column runs
  north-south through the town, so the site needs a world river running roughly north-south (or the river is turned
  to run through the plan within 10 km of it: its polyline is pinned to the plan's river entry and exit); the main line
  comes round the south of the town (rail.json), so the world's main line is pinned to the plan's rail portals; Main
  Street's two ends are where the highways leave east and west.
- **Every hub has the same kind of pins**: river in/out, rail portals, highway exits, the waterfront for ports. Routing
  (sections 5-6) starts from the pins, so authored plans and the generated network meet exactly.
- **Pads**: each hub has a footprint (its plan plus the farm ring, `RING` in src/town/farms.ts, about 3 x 1.3 km for
  gamma, 6-10 km for the big cities later) in which the terrain is the plan's (flattened to the town's height with a
  gentle blend over 300-600 m), so plans do not fight hills.
- **Growth room**: the capital must keep room for more districts (CLAUDE.md): its pad reserves a margin (2 km each way)
  where no generated town, road or rail may go except through the pins.
- **Stable as hubs are added**: the 6 launch hubs are placed first and never move when hub 7-25 are added (each new
  hub is placed with the earlier ones fixed). Countryside near a new hub regenerates (section 4 is placed after the hubs).

## 4. Small towns and hamlets

- **Towns** (generated, as today's towns: plan from `plat.ts`'s S and M sizes, people and history simulated): placed by
  a Poisson-disk sampler weighted by suitability (flat, fertile, near a river or the coast, on the way between hubs),
  12-25 km apart, none within 15 km of a hub. Each town's type (archetype) comes from its site, not a dice roll alone:
  a harbor on the coast, mining in the mountains, mill on a river with fall, farm on the plains, resort by a lake or
  beach, suburb within 25 km of a hub.
- **Hamlets**: crossroads places (a store, a church, a gas station, a dozen houses), 4-8 km apart along the county
  roads; they are named and known locally.
- **Farmsteads** along every county road on farmland (as farms.ts does round a town today).
- Every place has an id, a seed (`<worldSeed>/t/<n>`), a name (section 10) and a founding year (older near the coast
  and the first rail lines: the land was settled from the ports inland).

## 5. Roads

- **Highways** between hubs and the bigger towns on a continent: candidate links are the relative-neighbourhood graph of
  the places (no link where a third place sits between), each routed by a least-cost path on the atlas grid (cost: slope
  squared, water crossings dear, steep mountains very dear, existing roads cheap so routes share), then smoothed into
  curves with a maximum grade. Bridges where they cross rivers; passes over ranges.
- **County roads**: every town and hamlet to the nearest road; then a coarse section grid on farmland (the American
  section roads farms.ts already uses round a town: a 1-mile grid, here 1.6 km) where the land is flat.
- **Expressways** (the limited-access network, made-up name like Pennant, from 1956): a subset of highways rebuilt
  wider and straighter, bypassing the town centres (gamma's plan already has a bypass).
- Roads are vectors with a class, a width and a surface by year (section 9); the detail terrain cuts and fills a road
  bed along them.
- Drive time: highways 90-110 km/h in a car from the 1950s (80 before), expressways 110-120 km/h from the 1960s,
  county roads 60-80.

## 6. Rail lines and sea routes

### Rail

- **Main lines** join the hubs on each continent (a minimum spanning tree of the hubs, plus the shortest extra links that
  make loops), routed like highways but far stricter on grade (cost by grade cubed, so lines follow rivers and take the
  passes: the mountain town sits on one), pinned to each hub's rail portals.
- **Branch lines** reach out to towns (every town of rank `town` or `major` within 30 km of a main line, a share of the
  small ones), routed the same way.
- **History** as rail.ts already does it: mains built 1852-1890, branches 1878-1915, branch passenger service ending
  1950-1969, lifting and rail trails, the station rules, Pennant from 1971, company names after towns on the stretch.

**How it plugs into src/world/rail.ts**: the planner and the history code are written against the cell grid today
(`ewLink`, `nsLink`, `linksAt(cx, cy)`, `"cx,cy"` ids, `alongLine` by cell index). Phase W3 turns that into a small
interface, implemented twice:

```ts
interface RailGraph {
  site(id: string): TownSite;                    // any place
  linksAt(id: string): RailLink[];               // links touching a place (built from the skeleton's rail)
  lineStops(line: string): string[];             // the stations of a line in order (alongLine by index along it)
  near(p: WorldPos, radiusKm: number): TownSite[]; // for findTown / nearestStation / the planner's box
}
```

The current grid becomes `gridRail(world)` (the existing tests stay green on it), the skeleton becomes `skeletonRail(world)`.
`RailLink` keeps its fields; `dir` gives way to the alignment polyline from the skeleton; `linkLength` is the polyline's
length in km; `stationAt`, `departures`, `fare`, `planRoute` keep their signatures with ids that are skeleton ids
(`h:<hub>`, `t:<n>`, `m:<n>` hamlets). The Kotlin port (RailNet.kt) reads the skeleton's graph instead of computing the
grid, and keeps its check against the TS answers (rail-checks.json).

### Sea routes

- **Liner routes** join ports across oceans and along coasts: every port pair whose sea path is shortest to the other
  continent, plus a round-the-world line through the ports in order of longitude. Paths are least-cost over the ocean
  (land impassable, sea ice dear, shallow water near coasts a little dear) and stored as polylines.
- **History**: coastal steamers and liners to the 1950s (the liner era peaks 1950-1960), fewer from the 1960s (jets),
  most liner lines end 1965-1975, ferries on short crossings carry on, cruise ships from the 1980s on some routes.
- **Timetables** like rail's: sailings a week by era and route, a departure hour, the crossing's duration from its
  compressed length (section 8).
- **The route planner becomes multimodal**: the same time-dependent earliest-arrival search over rail legs, sea legs
  (a ship leg costs its sailing time plus waiting for the next sailing: days, not minutes) and road legs (as today's bus
  or lift). A trip to another continent: train to the port, the liner, train on. The NoRoute rules stay (and "the next
  sailing is on Thursday" is an answer).

## 7. Streaming, LOD and memory

### What is made when

| when | what | where it lives |
|---|---|---|
| build time (per world seed) | the skeleton: atlas grid (heights, climate, biome, flow), all places, all vectors with their years, routes | asset, ~5-10 MB packed; loaded whole (~25 MB in RAM) |
| new game / first launch | nothing more (the skeleton is shipped); optionally pre-bake the region tiles near the hubs into the cache | disk cache |
| as the player moves | region tiles (17.2 km, 33.6 m spacing) within the far ring; chunks (537.6 m, 2.1 m tiles) within the near ring | RAM + GPU, LRU; disk cache |
| as a place comes within ~3 km | its town: plan, buildings, yards, people (as the app does for gamma now, built nearest-first) | as today |

### LOD tiers (distance from the camera)

| tier | reach | geometry | ground | trees | buildings |
|---|---|---|---|---|---|
| T0 near | 0-0.8 km | chunk heightfield, a vertex per tile (257 x 257), cliffs as their own meshes | per-tile pixel-art material ids (autotiled, nearest-filtered) | TreeCells 3D, near detail | full (FilamentTown) |
| T1 mid | 0.8-4 km | the same chunk at 1/4 (65 x 65), skirts against cracks | baked chunk colour (pixel art, 2 texels a tile) | cards / merged simple trees | far boxes (TownFar) |
| T2 far | 4-30 km | region tile mesh (129 x 129 of its 512 x 512 heights), skirts | baked region picture at 8.4 m a texel, palette-quantised so it still reads as pixel art | forest as a canopy layer in the height and colour | the town as a few boxes and its lit-window glow |
| T3 horizon | 30-150 km | the atlas as one coarse ring mesh round the camera (a clipmap of 2-3 levels) | biome colour from the atlas | none | hubs' landmark silhouettes (a few boxes each) |

Crossovers use the screen-door fade the town already uses (`PropLayer.inverseFade`), with hysteresis (`TownFrame.LOD_SLACK`'s
idea); height morphs between levels in the vertex shader so the ground does not pop. Haze takes the horizon tier.
The player's speed sets the reach: on foot T0 0.8 km, on the bike 1 km, in a car 1.5 km (the streamer looks ahead
along the heading by 30 seconds of travel), on a train the ride's view (RideLayer) plus T1/T2.

### Cost estimates (native code, 8 Oryon cores; not measured on the S25)

- A chunk: 66k height samples (detail noise with FastNoise2's NEON path, carving by nearby vectors) plus materials and
  the props' placement: about 2-5 ms on one core. Its mesh and textures: a few ms more, uploaded a few a frame.
- A region tile: 262k samples plus its picture: 20-40 ms on one core. Made on workers, never on the render thread.
- At 120 km/h a car crosses a chunk every 16 s: the work is a trickle. Heavy work is scheduled in bursts with pauses
  during heavy animation (CLAUDE.md: heat as a scheduling problem); while idle or charging, the streamer pre-bakes the
  regions round the next destination into the disk cache.

### Memory budget on the S25 (estimates, to be measured)

The language model is the biggest tenant: Qwen3.5-9B Q4_K_M is about 5.6 GB of weights plus its KV cache. The world
must fit around it.

| tenant | RAM (CPU) | GPU (shared memory) |
|---|---|---|
| language model (weights + KV at 4k context) | 6.0 GB | (OpenCL buffers share the same memory) |
| voices (Kokoro + Supertonic), ASR | 0.6 GB | - |
| app, the town (measured held peak 144-164 MB on the desktop) | 0.3 GB | town 0.6 GB after the planned atlas cuts (2.3 GB estimated today on the desktop: the next thing to cut) |
| **world streaming (this design)** | **0.20 GB**: skeleton 25 MB, regions 25 x 2 MB, T0 chunks 25 x 1 MB, T1 chunks 150 x 0.1 MB, vectors and places near 20 MB, slack | **0.25 GB**: chunk meshes 60 MB, region meshes 20 MB, baked pictures (ASTC) 60 MB, ground tile atlases 16 MB, slack |

The disk cache is unbounded in practice (size is not a concern: a few GB of baked regions is fine). The budgets are hard
caps in the streamer (an LRU by distance): if a cap is hit the far tiers drop resolution before anything near goes. A
risk to measure first (phase W4): the 9B model and the town together already crowd the phone (section 13).

## 8. Terrain rendering in the pixel-art style

- **Heightfield chunks** as above, lit and shadowed by Filament like the buildings (the sun's cascades over the near
  ground; the far tiers have baked ambient occlusion and use the same sun for diffuse light).
- **Ground textures by biome**: the ground is tiles, like a SNES map laid over real 3D ground: each tile has a material
  (grass, prairie grass, dry grass, sand, mud, dirt road, gravel, asphalt, field rows per crop, forest floor, rock,
  scree, snow, ice, marsh) and a variant from its hash; transitions use autotiles (the 47-tile blob set, as the LimeZu
  pack's terrains) so borders are pixel-art edges, not blurs. Nearest filtering near, as the buildings. A tile is 32 px
  (15 px a metre: the existing grass picture's density); far tiers use baked, palette-quantised pictures so the look
  holds at distance.
- **Cliffs**: where the slope passes about 40 deg the ground is not stretched: the chunk cuts a cliff face as its own
  mesh (vertical-ish quads following the contour, a pixel-art rock texture projected on the wall's plane, with ledges
  every few metres), so mountains read as low-poly rock faces, not smeared hills. Below the cliff, scree tiles.
- **Beaches**: a sand band where the coast is gentle (from the coast's signed distance and slope), wet sand and foam
  tiles at the waterline (the foam animated in pixel-art steps), dunes as gentle bumps behind it on wide beaches; rocky
  coasts get rock tiles and cliffs.
- **Snow**: above the snowline (from temperature: lower further from the equator and in winter) and on the ice caps,
  snow tiles take over by slope (steep faces stay rock); seasonal snow from the date (deterministic weather), the
  roads' snow ploughed on the main ones.
- **Water**: rivers as ribbon meshes along their polylines (width from flow, a pixel-art flow animation, banks as
  tiles), lakes as flat planes at their level, waterfalls where a river crosses a cliff. **The ocean is a separate task**
  (the Gerstner-wave prototype); terrain hands it the depth (for the shallows' colour and foam) and the coast's distance.
- **Trees** from the biome and the detail noise (wild.ts's groves generalised), drawn by TreeKit/TreeCells near and as
  canopy far; conifers in the taiga and mountains, broadleaves in the temperate forests, palms on tropical coasts.
- Judge it from above and the side too (CLAUDE.md): a mountain range, a coast, the river valley into the capital, from
  the ground and from a 200 m camera.

## 9. Time: the world from the 1940s to the 2020s

Everything has years, and the drawn world is `f(seed, year)`, as rail.ts does now:

- **Roads**: every road has a built year and a surface by year: highways paved by the 1930s, county roads gravel until
  1950-1975 (paved first near towns), section roads often gravel to this day; expressways built 1956-1980 (a few later)
  with the old highway left as the "old road" through the towns; bypasses round towns as traffic grows.
- **Rail**: as section 6 (lines open, lose passengers, close, become trails; Pennant from 1971; commuter revivals).
- **Towns grow**: each place's population by decade from its simulation (hubs) or a cheap curve from its site and its
  connections (generated towns): towns on the expressway grow, towns it bypassed and towns whose branch line closed
  shrink; a few hamlets become ghost towns. The town plans already grow by decade (TOWN_PLAN.md "Growth by decade").
- **Farmland and forest**: cleared farmland spreads from the towns until the 1950s, then some marginal farms go back to
  forest; orchards and new crops come and go.
- **Dams**: on big rivers where they leave a gorge, 1930-1970: the reservoir floods the valley from its year (a hamlet
  drowned under it is a ready-made legend for lore.ts), with a power station and a town at the dam.
- **Ports**: the liner era, then container docks (1970s on) at the big ports, ferries carrying on, marinas later.
- **Places with years**: airports (1950s on, small fields first), drive-ins (as the town's strip), radio masts (the
  radio station already in the plan).

The skeleton stores each feature's years (built, changed, closed); the history rules are pure functions of the
feature's id and the world seed, so NPCs, the planner and the renderer always agree.

## 10. The open ocean's distance compression

The boat looks like it does 6 knots (11 km/h) but crosses open ocean about 30 times faster, with the day and night at
their normal pace, fading back to true speed near land.

- **Coordinates stay true.** The map is the map: a port is where it is. The boat's world position integrates
  `dPos/dt = vVisual * c(pos)`, where `c` is the compression factor at its position; everything the player sees around
  the boat (the hull's wake, the waves passing, the spray) uses `vVisual`.
- **c from the distance to land**: `c = clamp(D / D0, 1, 30)`, where D is the distance to the nearest land (the
  skeleton stores it per atlas cell; the detail level refines it near coasts; islands count) and D0 = 1 km. Seen from
  the deck, the nearest land then never drifts past faster than it does from 1 km off at true speed: the angular speed
  of land at distance D is v/D, and v grows with D. So the boat is at true speed in harbour and close inshore, about 5x
  five km off a coast, and 30x from 30 km out, where land is out of sight in the haze. Every island slows you as a coast
  does. (The prototype first tried "true speed until out of sight, then 30x": coastal legs at 11 km/h made crossings
  10-25 hours, because liners follow coasts for hundreds of km. The proportional rule gives 1.5-6.5 hours.)
  A later refinement may count tall land as nearer (a 2,000 m range is seen further than a beach).
- **The ocean surface**: the Gerstner ocean (the separate task) follows the camera; its wave phase scrolls by the
  distance travelled at visual speed (kept separately from the world position), so the waves never streak at 30x.
- **Other ships**: a sea route's liner or a fishing boat seen at sea is placed in a local bubble round the player's boat
  (positions relative to it, moving at visual speeds); their world positions advance by the same c. A meeting on a sea
  route is decided by the skeleton's timetables, so the liner you see is the one the planner sails.
- **Streaming at sea**: at c = 30 the boat covers 92 m/s of world. Out of sight of land the streamer drops the land
  tiers (only the skeleton and the ocean stay: memory goes back to the language model, a good time to pre-generate the
  arrival port's dialogue); it looks ahead along the course and starts the coast's T2 and T3 tiles when the course is
  within 40 km of sight, so they are ready long before c falls to 1.
- **Durations** (the prototype's sea routes, three seeds): crossings of 285-1,200 km of sea path take **1.5-6.5 game
  hours** (25-108 hours at true speed), most of it in the slow approaches and along coasts; the open-ocean part
  (c = 30) is 100-400 km of each route and takes under an hour. The timetables use the same integral, so the liner's
  crossing time is the one you sail.
- **Sailing through the night**: from the helm, choose a destination port (or "until morning"): fade out, integrate the
  path along the course in one step (deterministic: the same arrival as sailing it), advance the clock and the
  calendar, fade in near the destination, with a log line of the days at sea. The liners' passengers do the same from
  their cabin.

## 11. Names, NPC knowledge and true routes

- **One registry of places** in the skeleton: every hub, town and hamlet with id, name, kind, continent, county, position,
  founding year and population by decade. Names are **unique on the planet** (a clash re-rolls with the next key:
  `townName(seed, n)`), so an NPC saying "Cedar Falls" means one place and the planner needs no "nearest one" rule. Hub
  names are authored (the capital's name is the owner's to give).
- **What an NPC knows** (a fact sheet section, CONVERSATION.md): their county's towns (within about 40 km), every hub on
  their continent, the capital, the ports with a sailing that year, the towns where their relatives live (the sim's
  households), their legend's target (lore.ts, now the nearest neighbour by road instead of a neighbouring cell). Each
  with facts that are true for the year: distance and direction by road, the drive's time, the train's line and time
  (from planRoute) or "no trains since 1959", the next sailing.
- **Checks**: tests that every place an NPC can name is in the registry and routable (by road at least; by rail or sea
  where the fact says so) in a set of years, as rail.test.ts does now for the grid; and `validate()` on generated replies
  rejects a town name that is not in the speaker's known set (the language model cannot invent towns that the map lacks).

## 12. Phased implementation plan

| phase | deliverable | tests | rough size |
|---|---|---|---|
| **W1 skeleton terrain** | src/world/gen: periodic integer-hashed noise, continents, elevation, climate, biomes, hydrology, lakes, coasts on the atlas grid; a map tool (the sketch's renderer in TS or reading its export) | deterministic; wraps seamlessly; land 28-32%; 3-5 continents; every river reaches the sea or a lake; climate bands sane | 1,500 lines; 2 sessions (Opus design, Sonnet tests) |
| **W2 places** | hub catalogue (data: type, pins, footprint), hub placement with gamma first, towns, hamlets, unique names, the registry; `TownSite` from it | hubs meet their hard rules and spacing; launch hubs stay put when hubs are added; names unique | 800 lines; 1 session |
| **W3 networks and history** | roads, rail (the RailGraph interface: gridRail and skeletonRail), sea routes and sailings, the multimodal planner, history by year (roads, dams, rail, ports, growth), lore on the graph | rail.test.ts green on both providers; every nameable place routable in 1930/1955/1975/2000/2025; pins honoured | 1,500 lines; 2 sessions |
| **W4 export and frames** | skeleton.bin export, Kotlin reader, WorldPos and the render origin, gamma placed on its world site with nothing else changed; NPC fact lines from the registry | the town's previews identical before and after (pixel diff); memory check of the skeleton; first S25 memory measurement with the 9B model loaded | 1,000 lines; 1-2 sessions |
| **W5 terrain renderer** | FastNoise2 in the native layer (arm64 NEON, desktop), chunk heightfields T0/T1, region tiles T2, atlas horizon T3, streaming with caps and disk cache, the town's ground blended into the terrain, pixel-art ground materials and autotiles, cliffs, beaches, snow, rivers and lakes; Jolt heightfield collision per T0 chunk when Jolt comes | LOD seams (no cracks: a render test); determinism of the skeleton-bound features; previews from ground, bike height and 200 m; frame time and memory on the S25 | the big one: 4,000 lines; 4-6 sessions |
| **W6 countryside** | roads and rail meshes outside towns (RailKit's pieces), bridges, small towns from plat.ts's S plans, hamlets, farms on county roads (farms.ts), forests by biome | previews of a drive between two towns; nothing floating or buried; side/back/above views | 2,500 lines; 3 sessions |
| **W7 sea** | the Gerstner ocean joined to the coast and depth, ports' waterfronts, the sailboat, compression, sailing through the night, liners | the compression never shows land at c > 1 (a test over the routes); arrival times equal the timetables | 2,000 lines; 3 sessions (after the ocean prototype) |
| **W8 time** | the world by year in the renderer: surfaces, expressways, dams and reservoirs, town growth, farmland | previews of one valley in 1945/1965/1985/2025 | 1,200 lines; 2 sessions |
| **W9 hubs 2-6** | author the five other launch hubs (draft from a seed, edit, save) with their landmarks | as the capital's | per hub 1-2 sessions |

## 13. Risks

- **Memory with the 9B model** (the biggest): the model's 6 GB and the town's GPU textures (2.3 GB estimated on the
  desktop) may already be more than Android lets one app hold on 12 GB. Measure first (W4); the world's tiers are capped
  and drop resolution first; the town's atlas cuts (TOWN.md "Memory") come before W5.
- **Determinism across languages and devices**: facts must come only from the skeleton (integer-hashed, baked); the native
  detail noise may differ by a bit between NEON and AVX and must never decide anything. Test: bake on the desktop, check
  the phone reads identical skeleton facts.
- **Authored hubs vs generated terrain**: the pins (river, rail, highway, waterfront) must be met; a seed that cannot meet
  them for a hub gets the next-best site, and a hub's pad flattens the ground. Test every hub on several seeds.
- **Changing gamma's neighbourhood**: today's `world-6` neighbours, rail.json and the dialogue that names them change
  when gamma moves onto the planet; the exports (rail.json, dialogue.json, rail-checks.json) are redone together in W4.
- **LOD seams and popping** with the pixel-art look (nearest filtering shimmers far off): baked palette pictures for far
  tiers, skirts, morphing; judge in previews in motion, not just stills.
- **Heat**: streaming at car speed while the model pre-generates dialogue: scheduling (bursts, pauses, idle and charging
  work) as CLAUDE.md says.
- **The drive time**: 30% land in 3-4 continents makes them 460-970 km long and 300-530 km across (the prototype,
  "The prototype"), so a crossing by car is 4-5 hours across and 7-10 hours end to end, not the 2-3 hours asked for;
  see the owner's decisions.

## 14. Decisions for the owner

1. **Continent count and shape**: recommended 4-5 continents (one larger start continent, the others medium, one
   small), with islands and archipelagos making up 10-20% of the land. Elongated and ragged, not round.
2. **Land fraction vs drive time** (the main conflict in the brief): on a 2,000 km planet, 30% land is 600,000 km2.
   In 3-4 continents that is 460-970 km long and 300-530 km across (measured in the sketch): a car (90-110 km/h) needs
   4-5 hours across and 7-10 end to end. At 20% land (`--land 0.2`): 4-5 continents of 270-720 by 150-460 km, 2.5-4.5
   hours across. A 2-3 hour crossing every way needs continents of at most about 300 km: about 10% land in 4-5
   continents, or 30% land split into 8-10 smaller continents and big islands. **Recommended: 20% land in 5
   continents**, the start continent capped at about 450 km (2-3 hours across, 4-5 end to end by expressway, faster by
   the express trains), so the drive stays a trip and the bigger ocean costs little time thanks to the compression.
   (If "2-3 hours" means real minutes of play and the game clock runs faster than real time, 30% works as asked.)
3. **How far apart the hubs are**: recommended at least 70 km (40 minutes by car, 30-50 minutes by train) on one
   continent, with towns every 12-25 km and hamlets every 4-8 km between.
4. **Ocean crossings**: recommended 200-500 km gaps between continents, so a liner crossing takes about 1.5-4 game
   hours (the sketch: 1.5-6.5 for 285-1,200 km routes) and the round-the-world sail about a game day; D0 (how close
   the boat is to land before it is at true speed: 1 km) and the cap (30x) are feel settings to tune at sea.
5. **Terrain look**: recommended smooth, gentle ground for fields and roads with hard low-poly cliff faces where it is
   steep (versus fully terraced, stepped terrain). A preview of both early in W5.
6. **The planet's shape**: a cylinder (simple, square chunks, no pole convergence), versus a true sphere (much harder,
   pinching chunks at the poles for little gain since the poles are ice). Recommended: the cylinder.
7. **The capital's name** and the hub names (authored).

## The prototype

`kotlin-street/tools/world-sketch.py <seed> [<seed> ...] --out maps.jpg`: a Python sketch (numpy, scipy, Pillow) of
sections 2-6 on a 4-chunk (2.15 km) atlas grid: continents from plates and blobs, elevation with plate-boundary ranges,
climate with wind-carried rain and rain shadows, biomes, priority-flood hydrology with lakes, hubs by the site rules
(capital first, 6 launch hubs marked), towns by weighted Poisson disk, highways and rail as least-cost paths, sea routes
over the ocean. It draws the unwrapped planet and the capital's continent at about 1 km a pixel, for each seed, on one
sheet. It is a sketch for judging the design, not the generator (that is W1-W3, in TypeScript).

Run: `python3 kotlin-street/tools/world-sketch.py alpha bravo charlie --out maps.jpg --stats stats.json` (about 15 s a
seed; `--land 0.2` for the smaller-continent variant). Measured on the three seeds (2026-11-23, 30% land):

| seed | continents | islands (of land) | start continent | others (long x across) | towns | hub-to-hub drive, longest | sea crossings |
|---|---|---|---|---|---|---|---|
| alpha | 3 | 5% | 723 x 510 km | 792 x 422, 503 x 394 | 1,182 | 908 km | 1.5-6.3 h |
| bravo | 4 | 17% | 968 x 456 km | 489 x 375, 459 x 333, 333 x 295 | 1,149 | 786 km | 2.2-5.6 h |
| charlie | 3 | 10% | 731 x 426 km | 739 x 531, 657 x 369 | 1,049 | 1,262 km | 2.0-6.5 h |

At 20% land the same seeds give 3-5 continents of 266-721 km by 153-461 km (alpha 664 x 461 start; bravo five
continents, the start 479 x 310; charlie 721 x 315), and crossings of 1.4-3.7 hours.

What the sketch shows works: plate ranges make believable spines and coastal ranges; the wind-carried rain gives wet
windward coasts and deserts and prairies in the lee; priority-flood rivers all reach the sea, with lakes in the basins;
hubs land where their rules say (ports in bays, mountain towns in high valleys on the rail line's route, river cities on
the trunks); the rail network takes the valleys and passes, the highways are straighter. What the real generator must
do better: continents too blob-like on some seeds (more plate-driven shape), the latitude bands' rain shows as stripes,
snow fields too broad on the highest ranges, the separation trench sometimes leaves a continent's islands as one mass,
and highways' relative-neighbourhood graph detours (the longest drives are 1.3-1.7x the continent's length).
