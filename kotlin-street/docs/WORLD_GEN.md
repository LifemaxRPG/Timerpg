# World generation

The world the towns stand in: a small planet made from one seed, with hand-made hub cities placed on it by rules and
generated countryside between them. Phases W1-W3 (the skeleton: terrain, climate, places, links, history) are built in
TypeScript in `src/world/gen/` (see "The generator (W1-W3)" near the end, with measurements); W4 puts the app on it
(see "The world in the app (W4)"). The first prototype was `kotlin-street/tools/world-sketch.py` (see "The prototype" at the end).

The owner's decisions it starts from (CLAUDE.md, "Hand-made hub towns plus procedural fill" and "The planet", and the
briefs of 2026-11-23): a planet about 2,000 km round that wraps east to west, polar ice as the north and south borders
with no invisible walls, **about 30% land split into 8-10 small continents and big islands** (short drives, lots of
sailing; the first brief's 3-5 continents were replaced the same day), **smooth rolling ground with hard low-poly cliff
faces only where it is steep** (section 8), **a realistic climate that drives the world** (section 2.3), about 6 hand-made hubs at launch growing to 20-25
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

As built (`src/world/gen/terrain.ts`, the owner's 8-10 continents): **8-10 continent cores** are dart-thrown round the
planet within |lat| < 54 deg, kept apart by 1.25 x the sum of their radii plus 55 km; the start continent's share of
the land is about 1.6-1.85 times another's (shares 0.65-1.25), and 80% of the land goes to the cores (the rest is the
ragged coast's spill and the islands). Each core owns the cells nearest it (a warped Voronoi with the distance scaled
by the core's size); its land is 2-4 elongated blobs along a random heading, plus domain-warped fBm for a ragged coast
and a second noise that throws off big islands and archipelagos. A trench along the borders between the cores' regions
keeps continents apart, deepened until no landmass holds the bulk of two cores. The sum is thresholded at the quantile
that gives 30% land outside the ice caps (|lat| < 72 deg; 24% of the whole planet). A landmass of 12,000 km2 or more
is a continent, 1,500-12,000 km2 a big island, smaller an island. About 14-18 plates (a warped Voronoi, each with a
drift) give the mountain ranges (2.2).

### 2.2 Elevation and mountains

- Land base height rises with distance from the coast (a smooth 0-400 m plain-and-plateau shape) plus low fBm.
- **Range classes** (built 2026-11-23, "Grand mountains, wonders and winding roads: as built" below): the one or two
  strongest converging boundaries are **great ranges** (4,500-7,000 m, 90-150 km wide, ridgelines and passes), each
  continent's next one or two **middle ranges** (nominal crest 3,200-4,500 m), the rest modest; the start continent's
  heart eases every range back to modest, and a slope limit keeps every land step under 480 m.
- **Mountain ranges** where plates converge: every pair of plates (a, b) whose drift converges has its own range
  (modest: nominal crest 1,300-3,000 m, the pair's; it was 1,500-3,200 before the classes), standing on the pair's boundary distance g = (d_a + d_b - 2 d1) / 2 (d1 the
  nearest plate's distance): the usual half-gap (d2 - d1) / 2 for the nearest pair, growing smoothly past the ends of
  their boundary, so a range tapers out past a triple junction instead of stopping. A cell takes the highest pair's
  term, so the height is continuous everywhere (no step where the second-nearest plate changes: the W4 plateau-wall).
  The profile is a ridged-noise crest falling to nothing at 1.9 x the range's width (11-28 km) on a foothill apron
  (16% of the crest) out to 3.2 x, both (1 - x^2)^3 bumps, so the land rises over tens of km (foothills, then the
  range) and only plates within reach count. The worst step between land cells one atlas cell apart is about 350 m
  (2,945 m before; `gen.test.ts` checks it stays under 500 m everywhere); on a coast it makes a coastal range (the west-coast look), inland a spine. Diverging boundaries at
  sea give mid-ocean ridges (only seen in the depth shading). Old ranges (a per-plate-pair age) are lower and rounder.
- Ocean depth from the distance off the shelf: a 20-40 km shelf at 0-150 m, then the deep at 3-5 km.

### 2.3 Climate (the owner's request of 2026-11-23: a realistic climate that drives the world)

`src/world/gen/climate.ts`: twelve months of temperature and rain per atlas cell (not latitude bands: the sketch's rain
stripes are gone), then a climate class, the biomes and the crop belts from it.

- **Temperature by month**: a sea-level mean from latitude, the yearly swing from the sun (axial tilt 23.4 deg, so the
  seasons flip between the hemispheres; the coldest month lags the solstice by about half a month on land and a month
  and a half at sea), the lapse rate (6.5 C a km), **continentality** (the swing grows with the distance from the sea:
  0.3 of the full swing on a coast, all of it 300 km inland; coasts are mild) and the **ocean currents' anomaly**.
- **Ocean currents**: a parameter model of the gyres on the wrapping planet: warm currents run poleward off the **east**
  coasts of the continents in the subtropics (the western side of each ocean, as the Gulf Stream), cold currents run
  equatorward off the **west** coasts (California, Humboldt: cool dry coasts, fog deserts); past about 45 deg it turns
  round (a warm drift off the west coasts, a cold current off the east coasts). Each sea cell's distance to land west
  and east along its row gives the side; the anomaly (up to about +-5 C) is smoothed over 25 km and fades 120 km inland.
  (The coordinator's note had the warm side as "the eastern edges of oceans"; the real gyres put it on the western
  edges, which is what is built.)
- **Prevailing winds by latitude band**, following the sun through the year (the bands sit a third of the sun's
  declination off the equator): trade winds blowing west (0-30 deg), westerlies blowing east (30-60), polar easterlies;
  the band edges blend over 12 deg so no row flips alone. For each month the wind carries moisture along every row
  (two laps round the planet): it is picked up over the sea in proportion to the sea's warmth (warm currents wet, cold
  currents dry), rained out where the ground rises (windward slopes; the lee of a range is a **rain shadow**), and a
  little everywhere (more in warm months: convection), so it is **lost with distance inland**.
- **The season's bands**: a wet trough on the thermal equator and **dry subtropical highs** about 28 deg from it, both
  moving with the sun (summer-dry Mediterranean coasts at 30-40 deg, wet-and-dry tropics), and the mid-latitude storm
  tracks (about 52 deg).
- **Monsoons**: on continents (not islands) in the tropics and subtropics (fading in over 4-10 deg and out by 36 deg)
  near warm water (a sea warmer than 27 C in its warmest month within about 150 km), the summer months' rain is raised
  up to 3.2 times and the winter's halved.
- Rain is scaled so the median land cell gets about 760 mm a year, with a soft cap (650 mm in the wettest month).
- **Climate classes**: Koppen-Geiger from the months (A tropical: coldest month 18 C or more; B arid: rain under the
  threshold 20 x mean temperature plus 280/140/0 for summer/even/winter rain; C temperate; D continental: coldest
  month under -3 C; E polar: warmest under 10 C, EF under 0; H highland above 2,300 m), with the s/w/f and a/b/c/d
  letters. Measured on three seeds: C 29-45% of the land, B 24-33%, A 22-31%, E 5-8%, D 2-3% (the continents are
  small and maritime, so true continental climates are rare: an honest result of the owner's 8-10 continents).
- The **snow line** per cell and glaciers (cells above it, over 1,000 m) come from the same months: the glaciers'
  equilibrium line, where the warmest month is as warm as the precipitation lets snow outlast (Ohmura, Kasser and Funk
  1992: about 0 C in dry ranges, 2 C at 1,000 mm, 5 C at 2,000 mm; `snowlineTemp` in climate.ts). Until 2026-11-23 it
  was where the warmest month is at freezing: 300-800 m too high in wet ranges. The **timberline** is where the warmest
  month is 10 C (baked as the `timberline` grid).
- **Crop belts** (`makeCrops`): wheat on the cool dry plains (BSk, cool C/D with 380-650 mm), corn in the warm humid
  ones (warmest month 21 C, 550 mm), cotton where summers are long and hot (warmest 25 C, 8 frost-free months), orchards
  on the summer-dry coasts (Cs), rice on wet tropical lowlands (Af, Am, wet Aw, Cwa), dairy in the cool wet country,
  ranching on the steppe and savanna.

### 2.4 Biomes

From the climate class, then height, slope and water: ice (EF, the ice caps), glacier, tundra (ET), alpine (ET or H
high up, or above 1,800 m with a cool summer), rock (steeper than about 15 deg), desert (BW), steppe (BS), savanna (BSh
wet enough, Aw), rainforest (Af), tropical forest (Am, wet Aw), chaparral (summer-dry C under 900 mm), temperate forest,
temperate rainforest (Cfb/Cfc over 1,900 mm), prairie (C or D under 700-850 mm), taiga (Dc/Dd), marsh (low, flat, wet,
by a river or lake), beach (a thin gentle coast), lake. The skeleton stores one biome per atlas cell; the detail level
blends between neighbours with noise so borders are not grid lines. Farmland is a land use (the crop belt says what
grows), not a biome: it goes near towns and roads and grows over the decades (section 9).

### 2.5 Rivers, lakes and coastlines

- **Hydrology on the atlas grid**: priority-flood from the sea (wrapping in x) fills every pit, so every land cell has
  a path down to the sea; a cell's receiver is the cell the flood reached it from (the flood's order jittered by a few
  tenths of a metre, so filled flats drain in branching courses, not parallel lines); **flow accumulation** of each
  month's **runoff** (rain less 0.75 of a simple evaporation, 4.8 mm a month per degree; snow held while the month is
  below -1 C and released in the thaw), so the arid lands have few rivers, the thaw makes spring floods, and each river
  cell knows its year's flow, its **steadiness** (the driest month over the mean month) and its peak month. A cell is a river where the accumulated flow passes a threshold; its width and order
  come from the flow. So every river reaches the sea or a lake, and rivers join, never split (deltas are drawn at the
  mouth as detail).
- **Lakes**: a filled pit deeper than 20 m over 8 cells or more stays a lake (its surface the fill level, its outflow
  the pit's spill point); smaller pits are just filled. A lake whose year's inflow is less than its surface evaporates
  is a **salt lake** (a closed basin in a dry climate). Mountain glacial lakes come from the same
  rule at the detail level. **Reservoirs** are lakes with a year (section 9).
- **River paths as vectors**: the atlas cell chain becomes a smoothed polyline, then a meander pass (noise displacement
  scaled by width, kept inside the valley) at the region level. The detail terrain carves the bed and banks from the
  polyline's distance (a bank profile by width), with gorges where the river crosses a range.
- **Coastlines**: the atlas land mask is the coast to 1 km; the region level adds fractal detail (bays, spits, rocky
  points on steep coasts, sand on gentle ones) by noise on the signed distance to the coast, never flipping an atlas
  cell's land or sea. Ports' harbours are authored (a hub's pad includes its waterfront).

### 2.6 Weather statistics and climate hooks

`src/world/gen/weather.ts`: parameters for the runtime weather (section 12, "Runtime weather and seasons"), not a
simulation, per region of 16 x 16 atlas cells (17.2 km): thunderstorm and gale days, the chance of a hurricane coming
ashore (born over sea warmer than 26.5 C at 8-36 deg, carried west by the trades onto the east coasts, fading 90 km
inland), tornadoes (flat interiors at 28-50 deg with warm humid summers), snow days (and lake-effect snow downwind of a
big lake or strait in a cold winter), fog days (cold-current coasts, cool marine coasts, big river valleys), heat-wave
days, the chance of a drought (dry and erratic, or monsoon, country), the wettest and driest months, and the months
snow lies. Each place gets **climate hooks** for its identity and its NPCs' talk: "hot dry summers", "mild wet
winters", "humid summers", "long cold winters", "lake-effect snow", "hurricane coast", "tornado country", "foggy",
"monsoon rains", "droughts", "heat waves", "rain all year", "mild and damp", "high and cold".

## 3. Hub placement

The hubs are hand-made towns and cities (plans authored with the town generator as a drafting tool, saved as data).
The world seed only decides **where** each hub stands, by site rules and a score, in a fixed order.

| hub type | site rule (hard) | score (soft) |
|---|---|---|
| **capital** (`gamma`, the start) | on the start continent (the largest), temperate (C or D class, mean 5-21 C), a river of the plan's size through the site running north-south, flat lowland (450 m or lower), 12 km or more from the coast, the plan's pad and its 2 km growth margin clear land | temperate and fertile; 20-80 km from the coast (on the way to a port); central on its continent (rail reaches out both ways); the plan's river direction matches the world river's |
| **port** | on the coast, sheltered (a bay: little open sea within 10 km), deep water close in | a river mouth; facing another continent across the shortest ocean; a temperate climate |
| **river city** | on one of the planet's largest rivers (flow in the top 5%, steady enough not to run dry), low and flat | a confluence; a crossing point (narrow valley); not on the coast (that is a port) |
| **mountain town** | 800-2,000 m high, in a valley (lower than its surroundings) | near a pass between two river basins (the rail line's route over the range); forest and snow |
| **mill town** | on a medium river with fall (slope along the river) that is reliable all year (its driest month a quarter of the mean or more) | rapids or falls (steep river); hills |
| **farm town** | flat plains in a farming crop belt (not ranching country, not desert), good rain | the middle of the plains; far from mountains |

- **Order and separation**: the capital first, then the launch hubs (one each of port, river city, mountain town, mill
  town and farm town: the port and two more on the capital's continent, so it holds the capital and about three launch
  hubs plus countryside, the rest on its nearest neighbour or, failing that, any continent; plus a port on the
  neighbour so the first sea route exists), then a port on every continent, then the later hubs by type round the
  continents to 22 (river city, farm town, mountain town, mill town, port). Hubs on one continent at least 70 km apart (about 40 minutes by car on
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

**The owner's decision (2026-11-25): a realistic mix of straight and curvy roads.** The capital's straight grid stays.
Every other road follows the land the way real roads do: straight across flat open country, curving round hills,
rivers and lakes, switchbacks on mountains, and the odd winding country lane. Check any new road network for the mix
(share of curvature over distance against real-world roads of the same kind), not only for connectivity.

- **Highways** between hubs and the bigger towns on a continent: candidate links are the relative-neighbourhood graph of
  the places (no link where a third place sits between), each routed by a least-cost path on the atlas grid (cost: slope
  squared, water crossings dear, steep mountains very dear, existing roads cheap so routes share), then smoothed into
  curves with a maximum grade. Bridges where they cross rivers; passes over ranges.
- **Winding** (built 2026-11-23, winding.ts): every road's drawn line is routed again on 134 m nodes with a hard grade
  limit (county 10%, highways 7%), so roads wind round the hills and climb by switchbacks; see "Winding roads" below.
- **County roads**: every town and hamlet to the nearest road; then a coarse section grid on farmland (the American
  section roads farms.ts already uses round a town: a 1-mile grid, here 1.6 km) where the land is flat.
- **Expressways** (the limited-access network, the Pennant routes, from 1956): built 2026-11-23 as Highways v2, a
  planned network on its own new lines beside the old highways, bypassing the towns; see "Highways that evolve: as
  built" at the end.
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

**The owner's decision (2026-11-23): smooth rolling ground with hard low-poly cliff faces only where it is steep**
(not fully terraced, stepped terrain). Fields, roads and towns sit on gentle smooth ground; where the slope passes the
cliff threshold the ground breaks into faceted rock faces (below). W5 builds this.

- **Heightfield chunks** as above, lit and shadowed by Filament like the buildings (the sun's cascades over the near
  ground; the far tiers have baked ambient occlusion and use the same sun for diffuse light).
- **Ground textures by biome**: the ground is tiles, like a SNES map laid over real 3D ground: each tile has a material
  (grass, prairie grass, dry grass, sand, mud, dirt road, gravel, asphalt, field rows per crop, forest floor, rock,
  scree, snow, ice, marsh) and a variant from its hash; transitions use autotiles (the 47-tile blob set, as the LimeZu
  pack's terrains) so borders are pixel-art edges, not blurs. Nearest filtering near, as the buildings. A tile is 32 px
  (15 px a metre: the existing grass picture's density); far tiers use baked, palette-quantised pictures so the look
  holds at distance.
- **Cliffs** (the owner's decision above): where the slope passes about 40 deg the ground is not stretched: the chunk cuts a cliff face as its own
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
| **W1 skeleton terrain** (built) | src/world/gen: periodic integer-hashed noise, continents, elevation, climate, biomes, hydrology, lakes, coasts on the atlas grid; a map tool (the sketch's renderer in TS or reading its export) | deterministic; wraps seamlessly; land 28-32%; 3-5 continents; every river reaches the sea or a lake; climate bands sane | 1,500 lines; 2 sessions (Opus design, Sonnet tests) |
| **W2 places** (built) | hub catalogue (data: type, pins, footprint), hub placement with gamma first, towns, hamlets, unique names, the registry; `TownSite` from it | hubs meet their hard rules and spacing; launch hubs stay put when hubs are added; names unique | 800 lines; 1 session |
| **W3 networks and history** (built) | roads, rail (the RailGraph interface: gridRail and skeletonRail), sea routes and sailings, the multimodal planner, history by year (roads, dams, rail, ports, growth), lore on the graph | rail.test.ts green on both providers; every nameable place routable in 1930/1955/1975/2000/2025; pins honoured | 1,500 lines; 2 sessions |
| **W4 export and frames** (built, but the NPC fact lines and the redone exports) | skeleton.bin export, Kotlin reader, WorldPos and the render origin, gamma placed on its world site with nothing else changed; NPC fact lines from the registry | the town's previews identical before and after (pixel diff); memory check of the skeleton; first S25 memory measurement with the 9B model loaded | 1,000 lines; 1-2 sessions |
| **W5 terrain renderer** (W5a built: the near terrain T0/T1, its materials and cliffs, the town blended in, the haze; see "The near terrain (W5a)") | FastNoise2 in the native layer (arm64 NEON, desktop), chunk heightfields T0/T1, region tiles T2, atlas horizon T3, streaming with caps and disk cache, the town's ground blended into the terrain, pixel-art ground materials and autotiles, cliffs, beaches, snow, rivers and lakes; Jolt heightfield collision per T0 chunk when Jolt comes | LOD seams (no cracks: a render test); determinism of the skeleton-bound features; previews from ground, bike height and 200 m; frame time and memory on the S25 | the big one: 4,000 lines; 4-6 sessions |
| **W6 countryside** | roads and rail meshes outside towns (RailKit's pieces), bridges, small towns from plat.ts's S plans, hamlets, farms on county roads (farms.ts), forests by biome | previews of a drive between two towns; nothing floating or buried; side/back/above views | 2,500 lines; 3 sessions |
| **W7 sea** | the Gerstner ocean joined to the coast and depth, ports' waterfronts, the sailboat, compression, sailing through the night, liners | the compression never shows land at c > 1 (a test over the routes); arrival times equal the timetables | 2,000 lines; 3 sessions (after the ocean prototype) |
| **W8 time** | the world by year in the renderer: surfaces, expressways, dams and reservoirs, town growth, farmland | previews of one valley in 1945/1965/1985/2025 | 1,200 lines; 2 sessions |
| **W9 hubs 2-6** | author the five other launch hubs (draft from a seed, edit, save) with their landmarks | as the capital's | per hub 1-2 sessions |
| **W10 runtime weather and seasons** | the in-game weather drawn from the skeleton's per-region statistics (2.6) and tied to the game's calendar: rain, snow, storms, fog, heat, hurricanes and tornadoes when the dice say so; the seasons changing the ground (snow cover by the month's temperature, dry summer grass, mud in the thaw, frozen lakes) and the trees (leaves turning and falling in the broadleaf forests, bare winters, palms unchanged); rivers by the month's flow (spring floods, dry beds in arid summers) | the weather of a year at a region matches its statistics (counts of storm, snow and fog days within a tolerance); deterministic by date; previews of one valley in each season | 1,500 lines; 2-3 sessions |

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
- **The drive time** (resolved by the owner's decision of 8-10 continents): the capital's continent is now 343-422 km
  long; from the capital a car reaches the other hubs there in 1-3.6 hours (2025) and the longest hub-to-hub drive is
  3.6-4.5 hours (measured, "The generator").

## 14. Decisions for the owner

Decided on 2026-11-23: 1-2 (30% land in 8-10 small continents and big islands), 5 (smooth ground with hard cliffs),
6 (the cylinder, built), 7 (the capital's name is a placeholder, `CAPITAL_NAME = 'capital'` in src/world/gen/hubs.ts).
The original options, for the record:

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

## The generator (W1-W3)

`src/world/gen/` (TypeScript, the source of truth; pure in the world seed, and in the year for the history):

| module | what |
|---|---|
| grid.ts | the atlas grid (1,856 x 928 cells of 1,075.2 m, x wraps), a typed-array heap, an exact distance transform, box blurs, components |
| noise.ts | 32-bit integer hashes (`Math.imul`, shifts, xor) and value noise / fBm periodic in x: reproducible bit for bit in Kotlin |
| terrain.ts | continent cores, the land mask, landmasses, plates and ranges, elevation |
| climate.ts | the monthly climate, Koppen classes, biomes, crop belts (2.3-2.4) |
| hydro.ts | priority flood, monthly runoff, flow, steadiness, lakes (salt lakes), river segments, coast kinds (2.5) |
| weather.ts | per-region weather statistics and the places' climate hooks (2.6) |
| hubs.ts | the hub catalogue: the capital (`gamma`'s plan, its pins read from planTown and checked by a test), launch hubs, `CAPITAL_NAME`, `PLANET_SEED` |
| places.ts | the hubs' site rules and scores and their placement; towns (weighted Poisson disk) with archetypes from their sites and seeds re-keyed so `archetypeOf(seed)` agrees; founding years (older near the coasts and ports) |
| names.ts | the name registry: unique on the planet, made-up American-style names (settler-surname roots with the usual endings, nature words with a second word, Port, Mount, Falls...), a blocklist of well-known real towns |
| roads.ts | highways along a Gabriel graph of the hubs and major towns (A* on the atlas grid, hub pads closed except at their pins, shared roads cheap), county roads (one multi-source Dijkstra from the highways), hamlets every 4-8 km along them, the road graph and its drive times by year |
| railnet.ts | main lines (Kruskal over the Gabriel links' least-cost paths, grade cubed, plus 22% loops; pinned to the hubs' rail portals), branches to towns within 30 km, stations, links with rail.ts's history; `skeletonRail` (a `RailGraph`) |
| sea.ts | ports, ferry landings (one per landmass with places and no hub port), liner, coastal, round-the-world and ferry routes on a 2-cell sea grid, the compression integral, sailings by era |
| history.ts | dams and reservoirs (1930-1970, hamlets drowned under them), ports' eras, growth facts and populations by year |
| travel.ts | the multimodal planner `planTrip` (road by car or bus, rail through `planRoute`, sea with waits for sailings) |
| world.ts | `generateWorld(seed)` running it all; bake.ts the asset |

**src/world/rail.ts** now runs on a `RailGraph` (`RailWorld.graph`): the cell grid stays the default provider (every
existing rail and town test passes unchanged) and the skeleton's network is the second (`world.railWorld`), with the
same history, stations, timetables, fares and `planRoute`, and id-based twins of the grid calls (`stationOf`,
`linksOf`, `stationEventsOf`, `railFactOf`). A station where two main lines meet stays open (generalised from the grid's
east-west and north-south main lines).

**The asset**: `kotlin-street/tools/bake-world.ts [seed]` writes `content/world/<seed>/skeleton.twsk.gz` (format in
bake.ts: a JSON header with every vector and fact, then typed grids: height, flags, biome, Koppen, crop, flow,
steadiness, landmass, lake, snow line, current, the land cells' monthly temperature and rain, the sea's monthly
temperature on 4 x 4 cells, the region weather, one cell pool for the polylines). For `alpha` (version 2): 40.3 MB raw, **7.3 MB
gzipped** (more than section 7's 5-10 MB guess only because of the monthly climate; about 40 MB in RAM as stored).

**Measured** (desktop, 4-core x86 server, Node 22; not the S25), three seeds:

| | alpha | bravo | charlie |
|---|---|---|---|
| continents (long x across, km) | 9: 385x278 (capital's, 64,700 km2), 387x294, 338x264, 377x193, 335x229, 367x170, 280x223, 250x216, 288x158 | 10: 343x338 (capital's, 68,500 km2), 405x178, 331x280, 313x223, 309x238, 343x211, 291x218, 314x223, 269x173, 261x212 | 9: 422x332 (capital's, 74,400 km2), 425x258, 346x336, 375x270, 362x190, 284x256, 284x178, 312x195, 174x155 |
| big islands, islands; share of land not on continents | 12, 149; 11% | 6, 155; 10% | 7, 116; 7% |
| launch hubs on the capital's continent | port, mountain town, mill town | port, river city, mill town | port, mountain town, mill town |
| capital to the hubs on its continent by car, 2025 (1950) | 60-150 min (73-181) | 90-218 min (94-224) | 39-150 min (48-166) |
| longest hub-to-hub drive there | 520 km, 4.5 h (round a range) | 355 km, 3.6 h | 391 km, 3.8 h |
| towns, hamlets | 1,102, 3,424 | 1,170, 3,807 | 1,023, 3,713 |
| highways (km), county roads | 228 (13,400 km), 828 | 176 (10,100), 913 | 183 (12,200), 808 |
| rail lines main, branch | 85, 157 | 78, 169 | 72, 115 |
| ocean crossings (true km): liner / sailboat / liner at true speed | 262-726 km: 0.6-2.5 h / 2.1-7.4 h / 7-22 h | 199-1,012 km: 0.6-2.9 / 1.9-8.6 / 5-31 h | 246-760 km: 0.8-2.6 / 2.7-7.7 / 7-23 h |
| ferries (to the islands) | 100, 0.2-1.3 h | 105, 0.2-1.6 h | 81, 0.2-2.3 h |
| rivers (segments), lakes (salt), dams | 751, 18 (4), 15 | 678, 1 (1), 9 | 748, 3 (2), 15 |
| generation | about 15-25 s | about 17-26 s | about 17-26 s |

Generation time by stage (alpha, one run): terrain 6 s, climate 4-10 s, hydrology 0.6-2 s, biomes and weather 0.7-2.5 s,
places 2-3 s, roads, rail, sea and history about 0.5 s each (the range is load on the shared server). Ships' visual
speeds: liner 37 km/h, round-the-world liner 33, coastal steamer 22, ferry 28, the player's sailboat 11.1.

**Tests** (src/world/gen/gen.test.ts, about 50 s): determinism (the same seed twice), no seam at the wrap; 30% land
outside the ice caps in 8-10 continents; every river reaches the sea or a lake; temperatures fall with latitude and
height; the seasons flip between the hemispheres; deserts lie under the subtropical highs, in rain shadows, on
cold-current coasts or deep inland (over 90%), and the subtropics are drier than the equator and the mid-latitudes;
windward coasts are wetter than leeward (westerlies and trades); region weather statistics present; the capital first
on the largest continent with gamma's plan and the placeholder name; every hub's site rule and climate fit, the hubs'
spacing; launch hubs never move when later hubs are added; the capital's highways and rail leave by gamma's pins; the
pins match planTown('gamma'); unique, made-up names; every place reachable by road on its landmass; the hubs of a
landmass joined by main lines with trains between them (rail.ts's planner on the skeleton); every port pair joined by
sea with sailings in every year; every town an NPC can name (their county's towns within 40 km, the hubs of their
landmass, the capital, the hub ports, their nearest neighbour) routable by `planTrip` in 1930, 1955, 1975, 2000 and
2025; history by year; the baked asset round-trips.

**Maps**: `npx tsx kotlin-street/tools/world-map.ts <dir> alpha bravo charlie` then
`python3 kotlin-street/tools/world-sheet.py <dir> maps.jpg`: per seed the planet (biomes, relief, rivers, highways,
expressways, rail, sea routes, ferries, hubs), the capital's continent (with county roads, branches, towns, hamlets and
hub names), the Koppen classes, and January and July temperatures.

**Open problems** (for later phases):
- Highways, county roads and rail are 8-neighbour grid paths: straight runs at 0/45/90 deg (most visible on flat
  plains); W6 smooths them into curves with a maximum grade.
- True continental (D) climates are rare (2-3% of the land) because the continents are small; that is the owner's
  geography's honest result, not a bug. A larger capital's continent would add some.
- About 100 tiny islands get ferries; the planner's land legs to a station still use the road graph's drive, but rail.ts
  plans its own road legs by the road graph's km at a 40 km/h bus, which is slower than `planTrip`'s by-car option.
- A crossing to a far continent can take several days in the liners' off years (weekly freighters after 1965-1975),
  which is honest but long; cruise ships run on some routes from the 1980s.
- Names avoid a blocklist of well-known real towns; with invented roots almost all names are new words, but a short
  compound can still match some small real place.
- Generation is 15-26 s on the server: fine for a bake; the climate's twelve wind passes are the slowest stage.

## The world in the app (W4)

- **True paths** (`src/world/gen/paths.ts`): every river, road and rail line as a polyline in atlas cell units. A road or
  rail line ending at a hub ends exactly on the pin it leaves by (then half a cell straight out along the pin's way);
  a river through a hub with river pins (the capital) is bent through them: its cells inside the pad and a 450 m margin
  give way to the inflow pin's approach, the inflow pin, the outflow pin and the outflow's approach (a tributary that met
  it inside meets the bent river outside). Gamma's river pins are at the plan's edge, and the town's own river runs on
  from them to its ground's edge (the farm ring) along the same line, so the world river meets the town's where the
  town's ground ends. Gamma's highway pins moved to Main Street's middle line (plan y 142, not 141).
- **The skeleton, version 3** (2026-11-23): adds `wonders`, `ranges`, places' `setting` and `ore`, the roads' winding
  paths, and the grids `timberline` and `range`; `alpha` is 47.6 MB raw, **9.6 MB gzipped** (the winding paths are most
  of the growth). `WorldSkeleton.kt` reads versions 2 and 3 (`wonders` parsed into `WorldSkeleton.Wonder` with its
  parameters; an unknown section is skipped).
- **The skeleton, version 2** (`bake.ts`): the paths in a fixed-point section `pts` (2^19 a cell: 2 mm), places and pins
  at full precision, each hub's pad and the capital's plan frame (`planTiles`). `alpha` rebaked: 7.4 MB gzipped.
- **The Kotlin reader** (shared/src/main/kotlin/domain, engine-agnostic): `WorldSkeleton` (streams the gzip, keeps only
  the grids asked for: by default height, flags, biome, flow and landmass, 13.5 MB; all the vectors and facts),
  `WorldNoise`/`WorldLattice`/`WorldFbm` (noise.ts ported bit for bit: the same hashes and doubles), `WorldPos`, `Planet`,
  `HubFrame` (a town's frame on the planet), `RenderOrigin` (section 1's rules: the town's own frame within 3 km of its
  centre, else re-centred on the player's chunk corner past 2 km, a generation count for the layers), `WorldSave`
  (WorldPos + hub + tile), `WorldSite` (a town on the planet), `WorldFarPlan` (the far view's data), `WorldMemory`.
- **Gamma on the planet**: `h:capital` at (1004.366, 298.066) km, 228 m up; its frame's origin is plan tile (0, 0), so
  the town's code runs unchanged in its street units (the render origin is the town's own while the player is in it).
  The app reads the skeleton with the town (assets/world/alpha/, copied from content/world at each build), saves the
  player's position in world terms beside the town's own, and restores from it first.
- **The far view** (`WorldFar`, a `TownExtra`, planned and built on the town's workers): three nested terrain levels
  (268.8 m quads to 8.6 km (134.4 m since W5a, sampled from the near terrain's relief and wearing its materials), 1,075 m to 52 km, 4.3 km to 172 km: the horizon), faceted low-poly triangles with a flat
  normal each, a texel a quad in a biome palette (two tones dithered, borders broken by noise, snow above 2,700 m, the
  town's grass dithered in round its ground), skirts on the finer levels' rims; the ground flattened to the town's height
  under and round its ground (blending over 900 m); the ocean a flat plane at sea level; rivers (by order), highways,
  county roads and rail lines as ribbons on the surface, cut at the town's ground's edge, wider far off so they read;
  every other place within reach as a few blocks by rank with lit windows at night. Every mesh in its own local frame,
  its root transform its origin less the render origin. The town's grass apron is left out with the world on, and the
  camera's far plane reaches the horizon (the look's depth is reversed infinite, so only culling sees the far plane).
- **Measured** (desktop, 4-core x86 build server, software GL; not the S25): the skeleton read in 180 ms (the app's
  grids); the far view planned in 90-130 ms and packed in 110-210 ms on a worker, written straight into its buffers;
  84,500 triangles in 6 draws; world RAM 15.9 MB (grids 13.5, facts about 2.4) of the 200 MB budget, GPU 7.4 MB
  (buffers 7.4, pictures 0.03) of 250 MB. The town's memory check (`:desktop:townMemoryCheck`, now with the world as the
  app attaches it; `-Dworld=0` without): held peak 160 MB with the world, 125 MB without (budget 256). (A first try held
  233 MB: the noise port's planet-sized lattice tables, 100 MB for the detail and jitter noise; the lattice values are
  now hashed when asked, the same values.) The phone's numbers come with the next NPU fit test: its report's settings
  carry the world line, the GPU line and the town's memory summary, and the breadcrumbs' GPU and memory lines end with
  the world's.
- **Tests**: desktop `WorldSkeletonTest` (crossroadsTest): the noise and the reader against `tools/world-fixture.ts`'s
  fixture (exact), wrapping and the render origin (a thing stepping 1 cm across the seam 1,995 km out moves a steady
  0.21 units a step; raw floats would jump by units), saves round-trip, gamma's pins on Main Street's ends, the rail
  portals and the town river's ends at its ground's edge, the far plan; `gen.test.ts` "W4 paths". The town's renders
  are pixel-identical before and after (filament-open, four views, without the world).
- **Previews**: `gradle :desktop:preview -Dmode=filament-open -Dworld=1 -Dshots=world`.

**Open problems (W4)**:
- ~~The generator's cliffs~~ (fixed 2026-11-23): east of the old capital site the ground stepped from about 210 m to
  1,930 m in one atlas cell along some 40 km, a plateau-wall. Cause (verified): the range's crest height and
  convergence were taken from the nearest pair of plates only, so they jumped wherever the second-nearest plate
  changed (all 360 land steps over 500 m were at such a change; there a divergent pair, no range, met a convergent
  one at 3 km from its boundary). Now every converging pair's range is a continuous term (2.2). The ground west of the
  old wall became the range's flank, so the capital moved about 44 km west to 326 m (its rule now asks for lowland).
- Far towns are blocks a little bigger than life; 15 km off they are a few pixels, readable only with a long lens or by
  their lights at night (W5's T2/T3 tiers draw landmark silhouettes).
- ~~The open world's own haze~~ (fixed in W5a): it took everything past about 3 km on low ground while ground well above
  the town stood clear of its height falloff, so far mountains showed crisp. With the world round the town the haze is
  now the air's own (W5a, `WorldAir`).
- Rail lines are drawn whatever the year (their links' history is not read yet); roads by their built year.
- Not done in W4: the NPC fact lines from the registry, and rail.json / dialogue.json / rail-checks.json redone from the
  skeleton (gamma's neighbours are still `world-6`'s grid towns in the town's own rail and dialogue).

## The near terrain (W5a)

Built 2026-11-23: the first part of phase W5, the T0/T1 tiers of section 7 drawn as section 8 asks, round the camera,
replacing the town's flat green fields and W4's coarse triangles near the town. Everything is desktop-measured on the
4-core x86 build server with software GL (llvmpipe), not the S25.

**What was built**
- **The relief** (`WorldRelief`, shared/src/main/kotlin/domain; cross-language: `src/world/gen/near.ts` computes the same
  doubles, `tools/world-fixture.ts` writes samples). From the skeleton's bilinear cell heights and the integer-hashed
  noise only (+, -, *, /, floor, abs, sqrt): W4's detail noise, gentle rolling hills everywhere (10-36 m swing over
  268.8-33.6 m wavelengths, more where a slow roughness noise is high), rocky outcrops (ridged knolls up to 160 m where
  the roughness is highest), ridged mountains on high ground (up to 320 m over 450-1,350 m), all fading out at the shore.
  **The owner's terrain decision (section 14)**: where that ground's slope passes 0.62 (32 degrees; whole by 0.9) it is
  terraced into ledges and risers 7-13 m tall (a tread of 72% of the step rising 6% of it, the riser the rest: on a 45
  degree slope a near-vertical rock wall every 7-13 m with a ledge on top). Below the threshold nothing changes: smooth
  rolling ground. The slope is central differences of the smooth height (deterministic: no LOD's vertex grid enters it).
  A ridge's crest is rounded over a few metres (`sqrt(a^2 + 0.003)`), so the slope, and the cliffs it makes, never jump.
- **The ground on the town's planet** (`WorldTerrain`): the relief, then the world's own lines on it (the far view's
  rivers, highways, county roads and rail lines: within 70 m of a river and 36 m of a road the land's detail calms
  towards the cells' own height, a river's bed is carved 1.6 m over 8 m banks), then the town's flattening exactly as the
  far view does it (`WorldFarPlan.shapeRaw`: flat 150 m past the town's ground, blending over 900 m: the graded apron).
  Under the town's own grass (TownGround's extent) the terrain stays 0.6 m down, below the town's river water.
- **Materials** (12 pixel-art pictures, `TerrainPics`, 1024 texels a side at the town's 0.0389 m texel, a texture array):
  grass (the town's own grass picture, its LimeZu tufts stamped in: the countryside matches the town), dry grass (the same
  texels recoloured in straw), dirt, farmland rows two ways (furrows 0.8 m apart, a crop's clumps on the ridges), sand
  with wind ripples, rock (blocky stones, dark cracks, lit top edges), snow, gravel (the farms' palette), forest floor,
  water with ripple dashes, asphalt (the town's asphalt tile). Chosen per quad by `WorldTerrain.material`: the lines first
  (river water, highway asphalt with gravel shoulders, county roads and rail beds gravel), sand under 3.5 m by the sea and
  on beaches, rock on steep quads and cliff risers, gravel scree round them, snow above the skeleton's snowline (and on
  ice and glaciers), the town's grass in organic patches thinning out over 700 m round its ground, fields as a patchwork
  of 100-200 m plots on cropped land (rows one way or the other, fallow dirt, pasture), then the biome's ground with
  patches by a noise.
- **terrain.mat** (filament/materials, compiled to app/src/main/assets/filament/terrain.filamat): a node's material map is
  a byte a quad (with a ring of the neighbours' round it); the border between two materials is **dithered in whole art
  texels** (clumps of three texels and single ones jitter the lookup by up to about a metre), so transitions are jagged
  pixel-art edges, never a blur. Nearest magnification, the town's mip filtering. Steep faces (cliff risers, skirts) take
  the picture projected on the wall (across from x or z, up from the height) instead of stretched from above. A slow
  three-step variation over about 15 m hides the 40 m repeat. Shading: the town's lit model; smooth normals on rolling
  ground, a flat normal a triangle on cliffs (the faceted low-poly rock).
- **Chunks and streaming** (`WorldNear`, a `TownExtra`): a quadtree whose roots are blocks of 1,024 tiles (2,150.4 m),
  each node 64 x 64 quads: 2.1 m (a tile) at level 0 to 33.6 m at level 4. Roots within 3 km of the camera that lie
  wholly on the far view's finest level; a node splits while the camera (its distance including its height over the
  ground) is nearer than 1.3 of its size. Nodes are built on the town's workers (coarse first, then nearest; six at a time),
  at most two uploaded a frame within 3 ms, nothing heavy on the engine's thread. For each root the finest wholly-ready
  cover is drawn (a parent stays until its four children are up, and the other way round), so no hole ever shows while
  streaming; 48 unused nodes are kept for a quick way back. Every node is its own entity in its own frame (its corner),
  its root transform its corner less the render origin. `settle(pose)` builds everything at once (previews, a teleport).
- **No cracks.** Every vertex position comes from integer tile indices, so a vertex shared by two nodes, or two levels,
  has bit-for-bit the same height. Each node drops a skirt from each edge as deep as the largest step to any coarser
  neighbour's edge (its vertices are every 2^d-th of these) or to the far view's surface along it, plus a margin; the
  far view's finest level is now split into the same root blocks (one part a block, with skirts round its rim, and its
  lines cut at the blocks' edges), and a block the near terrain draws is hidden in the far view in the same frame. So the
  two never both draw a block, nor neither.
- **The far view (W4) changed with it**: its finest level is now 134.4 m quads (128 a side, still to 8.6 km), its heights
  sampled from the same relief (fading to the cells' heights at its rim where level 1 takes over), and each quad wears the
  near ground's material at its middle in that picture's mean colour (dithered back to the biome palette over its outer
  5 km), so past the near terrain the same fields, rock and water go on. With the near terrain the town draws no fields
  past its ground (`TownGround(farFields = false)`), and the far view covers everything but the town's grass.
- **The haze** (W4 open problem 3, fixed): with the world round the town the open-world camera's haze is the air's own
  (`WorldAir`: meteorological visibility at sea level, 90 km on a clear day, thinning over a 1,400 m scale height,
  through Filament's height fog from sea level), not the town's thick ground haze, so a clear day shows the land to the
  horizon and a far mountain is hazed as the low ground at its distance is. `FilamentTown.air` is the weather's hook
  (presets: clear, hazy 25 km, rain 6 km, fog 400 m). Without the world the town's haze is unchanged.

**Numbers** (desktop, not the S25; `-Dshots=w5` in `gradle :desktop:preview -Dmode=filament-open -Dworld=1`):
- A node (65 x 65 vertices plus its ring, 66 x 66 material points): median 13.5 ms, p90 19 ms, max 45 ms of one worker,
  in Kotlin on the JVM (the test `nodesBuildInTime`: 14-16 ms). Settling a view from nothing: 0.7-1.1 s on the 4-core
  server for 113-149 nodes (the first view's atlas besides: 12 layers made one at a time on a worker, about 1.5 s).
- Drawn: 0.9-1.3 million triangles in 113-149 draws (levels drawn in the town-edge views: about 40 at 2.1 m, 30 at 4.2
  m, 38 at 8.4 m, 36 at 16.8 m, 2 at 33.6 m); from 4 km up, 6 nodes (52,000 triangles). The far view: 119,000 triangles
  in 128 draws (it was 84,500 in 6: its finest level is four times finer and in blocks).
- GPU: the ground's pictures 64 MB (RGBA8 with mips; ASTC would be 16 MB), the nodes' buffers 24 MB for 194 nodes up
  (146 wanted, the rest the cache), material maps 0.7 MB, the far view's buffers 10.3 MB: the world 100 MB of its 250 MB.
- RAM: the skeleton's grids 18.4 MB (crop and snowline now kept: +5 MB). The town's memory check
  (`gradle :desktop:townMemoryCheck`, the world and its near terrain attached as the app does): held peak 199 MB of the
  256 MB budget (160 MB with W4's far view alone).
- **Native noise (FastNoise2/NEON): not needed yet.** In Kotlin a node costs 14 ms of one worker; on the S25's cores
  that is a few nodes a frame's worth of one worker, and a walk or a ride needs a few nodes a second. Most of the cost is
  the slope's four extra evaluations of the relief at every point: analytic derivatives of the value noise would cut a
  node to about a third before anything native. Native becomes worth it for the T2 region tiles (262,000 samples each)
  and for car speeds; then port `WorldRelief` as it is (the fixture pins its values).

**Tests**: `WorldTerrainTest` (crossroadsTest): the relief is the TypeScript's bit for bit at the fixture's points (a
terraced cliff among them); a level-0 node's vertex there is the fixture's height where nothing shapes the ground; nodes
are deterministic (built again by another terrain, after other nodes: the same bits) and share their edge and
parent-child vertices exactly; the geometric seam test: for four cameras (a person at the town's edge, a bike 2 km out,
200 m up 3 km out, 900 m over the town) the chosen nodes cover their root blocks exactly once, and at every vertex of
1,036 shared edges and 112 edges onto the far view the higher side's skirt reaches below the lower side's edge (the
largest step between nodes 8.5 m, onto the far view 50 m, each covered). `near.test.ts` (vitest): the hashed lattice
equals noise.ts's tabled one, determinism, terracing only where steep, continuity. The town's four filament-open views
(main_behind_13h, plan_above, downtown_wide_13h, chk_world_edge) are pixel-identical without the world; with it the town
itself is unchanged and only the distance above the rooftops differs (the air's haze, the terrain).

**Open problems and what is left (W5b on)**
- The hand-over to the far view can step up to 50 m on the sharpest outcrop crests 3 km off (the far view samples the
  relief every 134 m): no crack (the skirts), but a notch may show in a silhouette. Morph the outer ring of nodes towards
  the far view's surface, or make the far view's finest level from the near nodes' own coarsest heights (W5b's T2).
- The far view is still planned once round the town: the near terrain exists only on its finest level (8.6 km round the
  town). Walking further needs the far rings re-centred and region tiles (T2) streamed: W5b.
- Triangles: about a million near the town (SPLIT 1.3); fine for the Adreno 830 on paper, to be measured on the S25 and
  tuned (a quality setting: SPLIT, or 4.2 m quads at the finest level).
- The relief reads more by its materials than by its shading: the town's light is ambient-heavy, so a hill facing away
  from the sun is one flat tone. Worth a terrain-only light balance or baked ambient occlusion in the near nodes.
- Rivers are painted water on a 1.6 m-deep carved bed, with no water surface of their own; lakes are painted, not flat
  planes at their level; the world river meeting the town's river at the town's edge is not checked in a render yet.
  Roads are asphalt and gravel paint following the ground (no markings, no cuttings or embankments). Bridges: W6.
- Beaches and snow are in the materials but the capital is inland and low: neither shows in these renders (the
  snowline there is 3,700 m). Judge them at a coastal and a mountain hub.
- The town's grass picture and the terrain's are the same pixels with a different phase where they meet (a seam in the
  pattern, not the colour); the town's grass ends at its tile grid, the terrain's quads straddling that edge are below it.
- Out of scope, not started: trees and props on the terrain, Jolt heightfield collision per T0 chunk, the sea view's
  curvature and far-coast impostors, weather beyond the haze's visibility hook, region tiles (T2), the horizon tier (T3),
  the disk cache, native noise.

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

## Seeing land from the sea (the owner's request, 2026-11-23; for W5 and the ocean integration)

Land must be visible from a realistic distance, not only when you're on top of it.
- **A distance budget that follows what's on screen.** At sea, the town layers draw nothing, so the far view takes their
  budget:
  - terrain rings extended past the current 172 km (about 300 km at sea), with coarser outer quads;
  - silhouette-first meshes for distant coasts.
  Approaching land, the budget shifts back to the town as it streams in. A frame-time governor moves the far ring's
  reach with measured load.
- **Horizon and curvature.** Bend distant geometry down by d^2 / 2R (R from the planet's size, or a tuned visual
  radius), so land rises over the horizon as in real life: mountain tops first, then hills, then the shore. Real
  numbers for reference: from a deck 10 m up, a 1,000 m peak shows from about 125 km and a 100 m headland from about
  45 km.
- **Far coast impostors.** Land beyond the mesh rings is drawn as layered silhouette bands on a horizon cylinder,
  rebaked from the skeleton as the ship moves a few km. They are cheap enough to show every land mass in range.
- **Visibility by weather and light.** Haze follows the region's weather (clear: long views; rain or fog: short).
  At night: lighthouses (about 30-40 km), harbour lights and towns' glow on the clouds. Fix the open-world camera's
  3 km low-ground haze (W4 open problem 3) first.
- **Gameplay hooks:** a spyglass and binoculars (a long lens, already tested in the W4 views), a lookout calling "land
  ho", and charts.

## Grand mountains (the owner's request, 2026-11-23; for W5b)

Today every range's crest is drawn between 1,500 and 3,200 m (`terrain.ts`, `maxh`), so no mountain feels grand. The
owner wants some mountains at a realistic, huge scale that you can climb, with mountain villages, and smaller
mountains as well.

- **Ranges by class.** Most ranges stay modest (1,000-3,000 m hills and mountains). A few, where plates collide hard
  (the strongest convergence, ideally where two continents meet), become **great ranges**: crests of 4,500-7,000 m,
  80-150 km wide, with deep valleys, glaciers and snowfields, and passes at 2,000-3,500 m. Aim for 1-2 great ranges on
  the planet, and several middle ranges per continent.
- **Real mountain structure:**
  - long ridgelines with distinct peaks;
  - U-shaped glacial valleys and V-shaped river valleys;
  - cirques and lakes high up;
  - scree fans;
  - a timberline (pines, then bare rock, then snow), set by the climate model (latitude and continentality).
  - The low-poly cliff rule still holds: faceted rock only where it's steep.
- **Climbable.** The player can hike up: trails and switchbacks to summits, scrambles over rock, and huts. A real
  climb takes a real-feeling amount of time. Later, with Jolt: climbing on steep rock, and falls with consequences.
- **Mountain villages.** The small-town placer allows alpine hamlets and villages in high valleys and on passes:
  terraced pastures, a chapel, a guesthouse, and a switchback road or a cog railway. Mining towns appear where the
  geology says ore.
- **The capital stays on gentle land** (the owner's decision, 2026-11-23). Its region is relatively flat but not flat:
  rolling hills, a few steeper knolls, and country roads that wind round the hills rather than running dead straight.
  No great range is near it; the grand mountains are a different region you travel to. Later, gentle grades inside
  the town itself (streets that climb a little) are a possible town-plan change. They are not part of W5b.
- **Seen from far.** Great ranges dominate the skyline from 100+ km (the sea-view and impostor rules in "Seeing land
  from the sea" apply on land too), and their snow catches the dawn and dusk light.
- **Rendering:**
  - rock and cliffs in chunkier pixels with visible facets (not fine noise);
  - snow caps;
  - tree lines;
  - stronger slope lighting so ridges read in 3D.

## Natural wonders (the owner's request, 2026-11-23)

The world must not be mundane. Most land is ordinary and pleasant, but the player should have a real chance of finding
places that stop them in their tracks.

**The rule:** every wonder has a natural cause the generator already models (plates, height, climate, rivers, the
coast). So each appears where it makes sense, feels discovered rather than placed, and is deterministic per seed.
This is a new **wonders pass** after hydrology and biomes (W1 pipeline), stored in the skeleton, drawn as W5b and later
add the features.

**Rarity:**
- **Common:** pleasant everywhere.
- **Uncommon:** a few per region.
- **Rare:** a few per continent. Every continent gets at least one.
- **Legendary:** 1-3 on the whole planet, named and spoken of everywhere.

| Wonder | Where it comes from | Rarity |
|---|---|---|
| Blossom valleys: groves of pink, white or purple flowering trees, golden autumn stands | Sheltered temperate valleys ringed by mountains; bloom by season | uncommon; one legendary valley |
| Wildflower meadows and superblooms | Alpine meadows; arid basins after a wet year | common to uncommon (year-dependent) |
| Great waterfalls, with mist and rainbows at the right sun angle | Rivers crossing escarpments, cliffs or glacial valley lips; flow follows rainfall | uncommon; a legendary drop |
| Canyons and gorges with layered coloured rock | Rivers cutting uplifted plateaus in dry climates | rare |
| Arches, hoodoos and rock spires | Arid sandstone country | rare |
| Karst towers: steep forested limestone pillars over rice-green valleys; sinkholes and cenote pools | Humid subtropical limestone | rare |
| Table mountains with waterfalls off their sheer edges | Old eroded plateaus in wet tropics | legendary |
| Volcanoes, crater lakes, hot springs, geysers, steaming mineral terraces | Plate boundaries and hotspots | rare |
| Glaciers, ice caves, turquoise glacial lakes | Great ranges, high latitudes | uncommon in great ranges |
| Fjords | Glaciated high-latitude coasts | rare |
| Giant ancient trees: groves of colossal trunks | Foggy temperate coastal rainforest | rare |
| Salt flats that mirror the sky; pink and turquoise lakes | Closed basins in arid climates | rare |
| Sea stacks, arches, sea caves, black-sand beaches | Rocky and volcanic coasts | uncommon |
| Atolls and turquoise lagoons | Tropical seas | rare |
| Night wonders: glowing bays, firefly meadows, aurora | Warm sheltered bays; summer meadows; high latitudes on clear nights | uncommon (time-dependent) |
| Caves: big cave systems with underground rivers | Limestone and volcanic rock | uncommon |

**What makes them more than scenery:**
- **Time.** They change with season, weather and hour:
  - blossom weeks;
  - waterfalls roaring after rain;
  - aurora on clear winter nights;
  - low tide opening a sea cave.
- **History.** Across the century:
  - a falls can be dammed in 1950;
  - a grove logged or protected;
  - a park founded in a given year;
  - tourist towns, lodges, postcards and souvenir stands growing round the famous ones.
- **People.**
  - Wonders have names and legends.
  - Nearby NPCs talk about them and give directions (from the place registry).
  - The famous ones are known across the continent.
- **Discovery.**
  - A journal of the wonders the player has found.
  - Some sit on the road; others are hidden off-trail or up a climb.
  - Their silhouettes (a volcano, a table mountain) read from very far away.
- **The look.** Each wonder gets its own pixel-art palette and features, in our style:
  - blossom trees as low-poly crowns with pixel petals;
  - waterfalls as stepped animated pixel water;
  - layered canyon walls as banded pixel rock.

## Grand mountains, wonders and winding roads: as built (2026-11-23)

Built in `src/world/gen/` (terrain.ts, climate.ts, places.ts, wonders.ts, winding.ts, names.ts, bake.ts), baked into
`content/world/alpha/skeleton.twsk.gz` (version 3). Every number below is measured on the 4-core x86 build server
(Node 22), seeds alpha, bravo and charlie; nothing here is an S25 measurement.

### Range classes (terrain.ts)

- **Scoring**: every converging plate pair's boundary is scored by the land along it (land cells within 20 km of it,
  times the convergence), leaving out land within 170 km of the start continent's centre (110 km for middle ranges).
  The strongest becomes a **great range**, and the second too when it scores 60% of the first (1-2 on the planet).
  Each continent's next one or two strongest boundaries are **middle ranges** (nominal crest 3,200-4,500 m,
  convergence at least 0.75), the rest stay **modest** (1,300-3,000 m nominal, the old profile).
- **A great range's profile**: a massif 2,300 m high falling to nothing 46-74 km either side of the boundary (90-150 km
  wide), on it ridgelines parallel to the boundary every 17 km (a triangle wave across it, its phase meandering by a
  noise, so the ridges are continuous lines), peaks along them by a second noise, and passes where a slow noise along
  the range drops the ridges to 12% of their height. Heights over 6,000 m ease towards 7,000 (tanh).
- **The capital's region stays gentle**: middle and great terms ease back to the modest ones over 120 km from the
  start continent's heart, and the capital's site rule now asks for 150 km or more to any great-range cell
  (`CAPITAL_GREAT_KM`).
- **Continuity**: a min-plus cone envelope (`limitSlope`) lowers land so no land neighbour pair differs by more than
  480 m per cell (a no-op where the ground was already gentler: the old worst step was about 350 m). The peaks become
  pyramids, the ridgelines keep their line. The W4 cliff-wall test stays green (worst step 480 m).
- **Snow and trees**: the snow line now follows Ohmura's relation (section 2.3), the timberline the 10 C warmest month.
  Why the 3,251 m summit 58.5 km from the capital looked bare: the summit was above the old snow line (its cell's line
  was 3,108 m, a glacier biome), but it stands on the far view's coarsest levels, which painted snow above one fixed
  2,700 m, and at 58 km the air's haze turns that white into the same pale band as the sky, so it did not read as snow.
  The real error was the snow line's rule (where the warmest month is at freezing: 300-800 m too high in wet ranges);
  now the line is the glaciers' equilibrium line, and the far view paints snow above the skeleton's own line
  (`WorldFarPlan`, the near terrain already did). A test checks every great range's summit stands over 500 m above
  its snow line on glacier, with the timberline under the snow line.

| | alpha | bravo | charlie |
|---|---|---|---|
| great ranges: highest peak, km2 over 500 m | 2: 6,086 m (33,000 km2), 6,753 m (29,600 km2) | 2: 6,003 m, 5,904 m | 1: 6,297 m |
| middle ranges (their peaks) | 7 (1,289-4,801 m) | 9 (2,624-4,692 m) | 4 (3,162-3,496 m) |
| passes: roads' highest points in a great range's core | 2,704-3,545 m (most); a few county roads to mining towns and villages higher | 2,741-4,473 m | 2,732-4,507 m |
| capital: km to the nearest great range; highest ground within 60 km | 419 km; 3,009 m | 473 km; 359 m | 492 km; 1,729 m |
| terrain stage | 10-11 s (was 6-7 s) | 11 s | 10 s |

### Mountain places (places.ts, roads.ts)

`placeMountainPlaces`, after the towns (their ids unchanged; the new ones are `t:<n>` after them), rank small:
- **alpine villages** in high valleys: 1,000-3,600 m, in a range (its term 400 m or more), 70 m or more under the ground
  round them, a warmest month of 8 C or more (hay and pasture), not on ice and 150 m under the snow line, 8 km apart;
- **pass hamlets**: saddles (two shoulders 100 m higher across one axis, the land lower both ways along the other, 3 km
  out), and on every road at its summit crossing a ridge (150 m over both sides 4 km away, 900 m or higher);
- **mining towns** on ore bodies (a slow ore noise in the ranges): gold, silver, copper and tungsten high up, copper,
  lead, zinc and silver in the middle heights, coal and iron in the foothills (a quarter of them rank `town`).
- Hamlets along the county roads in the mountains (over 1,100 m in a range) are marked alpine. Every mountain place gets
  its county road like any town (the roads now avoid ground above the snow line where they can: +6 to its cost).

| | alpha | bravo | charlie |
|---|---|---|---|
| alpine villages, pass places (town + hamlet), mining towns, alpine hamlets | 122, 8 + 15, 203, 541 | 158, 4 + 23, 154, 516 | 44, 5 + 10, 119, 296 |
| towns, hamlets in all (before: 1,102 and 3,424 on alpha) | 1,251, 3,827 | 1,375, 3,969 | 1,119, 3,975 |

### The wonders pass (wonders.ts)

After the places and their names, every wonder where its cause is (a list of the rules in the file's header and per
kind): blossom valleys (temperate valleys ringed by mountains; colour and bloom months from the climate: pink cherries
and white plums where the spring warms through 9 C, white almonds in summer-dry climates, purple jacarandas in warm
subtropics, golden aspen and larch autumns in the cool and continental ones), alpine meadows, desert superblooms (dry
flat basins, by the region's drought chance), waterfalls (river cells whose fall over the next one or two cells is 140 m
or more; drop 0.8 of it), canyons (rivers in high dry country), arches and hoodoos (dry sandstone country), karst towers
(humid subtropical limestone), table mountains (old plateaus in the wet tropics), volcanoes (summits on converging
ranges within 140 km of the coast, and five hotspots), hot springs (by volcanoes and on high ranges), glaciers (snow
line edges, great ranges first), fjords (high-latitude coasts under high ground), giant trees (foggy coastal temperate
rainforest), salt flats (closed dry basins and salt lakes), sea stacks (rocky and cliff coasts), atolls (small tropical
islands), glowing bays, firefly meadows, aurora viewpoints and caves (limestone, lava tubes). Two geology fields are new:
limestone and sandstone country as slow noises (limestone kept off the young ranges).

- **Tiers**: common (meadows), uncommon (one of a kind within 30-160 km), rare (a few per continent, at most 1-2 of a
  kind each), legendary (the finest flowering blossom valley, the greatest waterfall if it drops 300 m or more, the best
  table mountain: 1-3 on the planet). Every continent without a rare wonder gets its best rare candidate (failing that, a
  gorge on its steepest river).
- **Each wonder**: id `w:<kind>:<n>`, kind, tier, name (the place registry's, unique among places and wonders: "Otter
  Falls", "Saddle Table Mountain"), position, landmass, extent (km), fame (planet, continent, county, local) and the
  renderer's parameters (`WONDER_PARAMS`): e.g. a waterfall's river (index into the skeleton's rivers), drop, flow,
  width, facing, peak month, steadiness, style and mist; a blossom valley's colour, tree, bloom months and mountain
  ring; a canyon's river, depth, length, width, heading, band colours and its end cells.
- **Baked** as the skeleton's `wonders` list; Kotlin `WorldSkeleton.wonders` (`num`/`str` accessors).

| | alpha | bravo | charlie |
|---|---|---|---|
| wonders: common, uncommon, rare, legendary | 381: 64, 253, 61, 3 | 352: 54, 239, 56, 3 | 308: 30, 221, 54, 3 |
| legendary | Blue Vale (blossom valley), Otter Falls (waterfall, 710 m), Saddle Table Mountain | Silver Valley, Pemey Veil Falls (442 m), Otter Tableland | Raven Valley, Bramble Cascade (570 m), Lion Tableland |
| within 60 km of the capital | 7: two waterfalls, a blossom valley, a canyon, two meadows, caves | 4 | 5 |
| stage | 2.4-2.6 s | 2.4 s | 2.4 s |

### Winding roads (winding.ts)

roads.ts still routes every road on the atlas grid (the network's topology, junctions and drive times); each road's
drawn line is then routed again on a fine grid of 134.4 m nodes (8 an atlas cell; 32 move directions where the ground is
steep, 16 where it is gentle) in a corridor of 1.5 cells round its cells (widened to 3 and 6 where no line fits), A* with
a weighted heuristic:
- **the grade is a hard limit** (county 10%, highway 7%) on the ground the renderer lays a road on (the skeleton's
  bilinear heights: WorldTerrain calms the detail within 36 m of a road), sampled every 70 m along a move where a cell's
  bilinear patches could be steeper than the limit; a climb steeper than the limit can only be made across the slope,
  so the line zigzags: **switchbacks**;
- grade under the limit costs more near it; the near terrain's knolls and ridges (near.ts's peak term) cost by their
  height (county roads 3.6 times more than highways), so country roads go round the hills and highways stay straighter;
- each river crossing costs a bridge (1.5 km of road), hub pads are closed and the capital's growth margin is dear;
- a road on cells an earlier road already holds (a county road joining a highway, two highways on one trunk) runs on
  that road's line and joins it exactly; hub ends stay on their pins (the W4 paths test).
- A road's `km` is its line's length, and the road graph's stretches share it out (drive times follow the winding).

| | alpha | bravo | charlie |
|---|---|---|---|
| hairpin bends, bridges | 1,143, 632 | 2,053, 606 | 612, 487 |
| median length over the cells' (county, highway) | 1.09, 1.02 | 1.09, 1.02 | 1.10, 1.03 |
| roads over their limit (county > 10.5%, highway > 7.5%), of all | 7 + 4 of 1,186 | 10 + 8 of 1,288 | 3 + 3 of 1,090 |
| stage | 13-16 s | 16 s | 10 s |

The roads over the limit are stretches where even a 6-cell corridor holds no line under it (the grade then relaxes to
1.4, 2 and 3 times the limit) or a county road's few metres to its junction; the test allows 3%. Generation is now about
36 s for alpha (was 20 s): terrain 10 s, winding 13-16 s.

### Tests (gen.test.ts, about 100 s with two generations)

Great-range count (1-2), heights (4,500-7,000 m, 5,000+ km2 over 500 m, 5 or more summits over 4,000 m), middle
ranges on 60% of continents or more, modest ranges under 3,600 m; no land step over 480 m; passes 1,800-3,600 m;
the capital 150 km or more from a great range with nothing over 2,500 m within 30 cells; great summits on glacier over
their snow line; alpine villages, pass places and mining towns with their rules and roads; 1-3 legendary wonders and a
rare one on every continent; unique ids and names, every kind's parameters; every wonder where its cause is (every
waterfall on its own river's cell with a fall at least its drop, glaciers over the snow line, fjords at high latitude...);
every road under its grade limit sampled every 25 m (97% or more), county roads longer than their cells, switchbacks on
climbs over 600 m, hub pins, junctions on the joined road's line; determinism of all of it. Kotlin `WorldSkeletonTest`:
the wonders (one of each kind, every parameter), the settings and the new grids against the fixture.

### Maps and previews

`npx tsx kotlin-street/tools/world-mountains-map.ts <dir> [seed]` (planet height with the great ranges, the wonders by
kind and tier, a close-up of the most switchbacked road to a mountain place on the near terrain's relief), then
`python3 kotlin-street/tools/world-mountains-sheet.py <dir> <preview.png> <out.jpg> [caption]`. The preview's
`-Dshots=mtn` has a new `mtn_great` shot aimed at the nearest great range in the far view's reach; from the capital there
is none (the nearest is 419 km away, the far view reaches 167 km), so it reports that and draws nothing.

### Open problems

- Wonders are data only: W5b and later draw them (and their history: parks, dams on falls, lodges, postcards).
- The skeleton's heights do not carve canyons, fjords or the glacial valleys: the renderer has to (the parameters say
  where and how deep). A waterfall's drop is concentrated from a 1-2 km fall, not a cliff in the skeleton.
- Salt flats are rare to absent on some seeds (alpha has none: few closed dry basins).
- The fine routing has no turning radius: a hairpin is a corner on the 134 m grid; the renderer should fillet it (with a
  level turn) when roads get meshes (W6).
- Mountain places are towns in the registry with archetypes farm, resort and mining; an alpine archetype (chapel,
  guesthouse, terraced pasture) is for the town generator.
- A great range from the capital: none in reach of the far view by design; seeing one from far needs the T3 horizon
  tier and impostors ("Seeing land from the sea").

## Highways that evolve (the owner's request, 2026-11-23; "Highways v2", after the towns-everywhere work)

Already built (`roads.ts`):
- highways and county roads with years (built, paved, rebuilt as an expressway from 1956);
- speeds by surface and era;
- a road graph for drive times;
- winding roads with switchbacks.

What makes it a real, intelligent highway system:
- **A planned national network.** In the 1950s-60s an expressway system is planned as a network of trunk routes
  linking hubs and major towns (like real national systems), not pair by pair. It's built in stages over the
  decades, with the busiest corridors first.
- **New alignments and bypasses.** Expressways are built on new, straighter, flatter lines that skirt towns. The old
  highway becomes the "old route" through Main Street. Bypassed towns lose passing trade in the growth model (the
  classic Main Street decline); towns at the interchanges grow strips, motels and truck stops.
- **City structure:**
  - ring roads and beltways round big cities (1960s-70s);
  - radial expressways into downtown;
  - interchanges (cloverleafs, then later designs);
  - frontage roads;
  - divided highways with medians.
- **Lanes by era and traffic:** two lanes, then four, then six on the busiest corridors. Bridges, tunnels and cuts
  through hills where the terrain demands.
- **Route numbers and signs:** a made-up national numbering scheme, its own shield shape and colours (no real
  trade dress), exit numbers, mile markers.
- **Roadside network:** rest areas (we have the Pine Bend model), weigh stations, truck stops (Cedar Relay), motels and
  diners at interchanges, toll plazas on some routes.
- **Traffic:** AI cars and trucks by era and road, with density from the simulation (more cars each decade, rush
  hours near cities). It's built on the vehicle work (V1 and later).
- **Road meshes (W6):** real road surfaces with lane markings by era, kerbs, shoulders, guard rails, rounded hairpins.

## Highways that evolve: as built (Highways v2, 2026-11-23)

`src/world/gen/highways.ts`, after the winding roads and the names (stage about 5 s for alpha; generation 72 s on the
4-core server under load, 40 s quiet). The old network of roads.ts and winding.ts stays exactly as it was (the capital's
pins, the W4 paths test); this pass gives every piece of road its whole life by year and builds the 20th century on top.

### One rule for every segment

The network is a list of **segments** (`Seg`), and every segment carries its whole history: `built` (the first year it
was any road at all), `gone` (abandoned: it fades over 25 years, then is off every map), and **class, surface, lanes and
number as [year, value] steps**, a `divided` year, its route, its river crossings (each a ford from one year, a bridge
from another) and its structures (cuts, fills, tunnels, viaducts). `segAt(seg, year)` gives a segment in one year;
`networkAt(highways, year)` is exactly that year's roads. The Kotlin reader has the same (`WorldHighways.Seg.at`).
Segment kinds: `original` (a piece of an old road: a stretch of its own path, never copied), `realignment`, `bypass`,
`expressway`, `beltway`, `radial`, `connector` (an interchange to its town), `frontage`.

### A road's life

- **Before 1900: tracks.** Every old road was a wagon track from two years after its places were founded (1840 at the
  earliest) to the year it was built.
- **Dirt, gravel, paved.** Highways gravel when built (1905-29), paved by 1938; county roads dirt, gravel ten years on,
  paved 1935-75. Numbered in 1927 (the national numbering), county roads when paved.
- **1930s realignments.** On every highway paved by 1941, the worst curves are found (a stretch 1.2-6 km long at least
  1.18 times its chord, kept 2.5 km off the ends and 6 km off every hub) and, three times in four, straightened in
  1931-41 when the chord is open land under 7% (a cut or fill up to 15 m allowed) over no more rivers than the curve.
  The new piece takes the number; **the old curvy piece becomes an unnumbered local road**, and 40% of those are
  abandoned later and fade. The road graph's distance follows (`Road.kmBy`).
- **Widening.** Busy highways (the weight of their two places) go to four lanes in 1950-68 unless an expressway relieved
  them first; the busiest are divided four to twelve years later.
- **Bypasses (1948-68).** A town on a highway no expressway came within 8 km of may get a bypass (a 1.7 km arc round it,
  under 7%): the bypass takes the number, the old way through Main Street becomes the business route (`N<n>B`), with two
  at-grade junctions.
- **Expressways and beltways** (below), from 1956.
- **Decommissioning (1970-2000).** An old highway an expressway leg replaced, four to sixteen years after the leg opened:
  40% keep their number, 30% become business routes along their length, 30% are handed to the county (class county,
  a `C` number, 70 km/h; never a highway into a hub, which become business routes instead).

How one route reads (alpha, `hw:16`, Ardey Meadows to Doranton, 68 km):

| year | what happened |
|---|---|
| 1840 | a wagon track between the two settlements (`track`, one lane) |
| 1905 | built as a gravel highway, two lanes |
| 1919 | paved |
| 1927 | numbered **N96** in the national numbering |
| 1938 | realigned: 3.6 km of new straight road replaces a 4.3 km curve; the old curve becomes a local road, unnumbered |
| 1959 | widened to four lanes; divided in 1964 |
| 1967 | the expressway **P48** opens on its own line beside it (its legs cut at six interchanges), Doranton bypassed, its interchange 1.3 km from the centre |
| 1982 | the old highway renumbered **N96B** (the business route) |

### The planned expressway network (the Pennant routes)

- **Plan.** Per landmass with two or more hubs and major towns: a minimum spanning tree of the highways' own Gabriel
  graph of those places, plus (up to a quarter as many again) the links where the tree's way is over 1.5 times the
  direct one, the biggest pairs first. Each link follows the corridor of the old highway between the two places.
- **Corridors.** At each place the links are paired straightest-first (over 120 degrees) and chained into long
  through routes: 43 corridors of 78 legs on alpha.
- **Staging.** A corridor's "busy" is the weight of the places it serves (hubs 40, the capital 100, major towns 12, the
  towns within 8 km of its line half their weight). Corridors start 1956-78 in order of busy (the capital's first,
  1957-58); within one, its legs open busiest first, one to three years apart, all by 1985.
- **Alignment.** Each leg is routed on its own line: A* on 269 m nodes (16 directions) in a corridor 3 cells round the
  old highway (then 6, 10), the grade limit **5%** on every move (relaxed to 7% and 10% only where nothing fits: never
  needed on alpha), costs for grade, for every river crossing (a bridge; a big river two and a half times), and for
  passing within 1.4 km (and 2 km) of a town's centre, the hub pads closed. Then it is **pulled straight** (from each
  point, the farthest later point on an open chord under the grade, off the towns' cores, over no more rivers) and
  **filleted** into wide curves (1.8 km radius where the legs allow). A through town's expressway passes 2.2 km off its
  centre, square to the corridor; a terminal town's ends on its highway 2.5 km out (a trumpet).
- **Profile, cuts and tunnels.** The road's profile is the mean of the highest 5%-limited line under the ground and the
  lowest over it: never steeper than 5%, as near the ground as that allows. Where it leaves the ground by more than 8 m
  it is a cut (a **tunnel** where deeper than 30 m for over 250 m) or a fill (a **viaduct** over 22 m), recorded per
  segment with km and depth. Every river crossing of an expressway is a bridge from its opening year, never a ford.
- **Lanes.** The busiest fifth of corridors open with four lanes and get six (1972-88); the quietest third open as
  two-lane "super twos" and are twinned to four 8-16 years later; the rest open with four. Divided from four lanes.
- **City structure.** Every hub with a leg, and every major town where three or more legs meet, gets a ring of 16
  anchors (hubs: past the pad and its 2 km growth margin plus 1 km, an ellipse; towns 3 km). Each leg reaching it ends at
  the anchor of its old highway's direction, where a **radial** (a spur) runs in to an interchange near the town's edge
  **on the old highway**, 1 km out along it from its pin (1.3 km from a town's centre). The **beltway** joins the longest
  run of open anchors (closed when all are), 1961-65 round the capital, 1963-79 round other hubs, 1967-79 round towns,
  never before a radial plus three years. At the capital: radials join the highways leaving its west and east pins, the
  plan and its pins untouched (tested), nothing of the expressways inside its pad.
- **Interchanges as data**: system (three or more expressway lines meeting, or two crossing), service (an expressway
  crossing a highway, or a county road to a town or bigger, or any town within 6 km: a connector road to the town),
  terminal (an expressway's end on a highway: trumpets) and the bypasses' at-grade junctions. Service interchanges
  closer than 1.5 km merge. Design by era: system cloverleafs before 1966 (60% rebuilt as directional 1986-2015), then
  directional, stacks from 1980; service cloverleafs before 1962 (or 1966 on busy highways; 40% rebuilt as partial
  cloverleafs later), then diamonds and partial cloverleafs, single-point diamonds from 1990.
- **Frontage roads**: both sides of the expressway, 75 m off, 1.2 km each way of every service interchange within 4 km
  of its town, built with it or up to ten years later.
- **The roadside as sites** (kind, position, years, models): rest areas every 60-95 km along a corridor (a year to four
  after it opened; a fifth closed 2003-15; the **Pine Bend** model, `landmark_glb_highway_rest_area`, from 1975), weigh
  stations every 150-220 km (from 1962), truck stops at 45% of the highway interchanges and 20% of the others (**Cedar
  Relay**, `landmark_glb_truck_stop_1960`, to 1989, then `_1990`), motels (45%, a third closed 1985-2012), diners (30%),
  toll plazas on a fifth of the corridors (40% of the tolls lifted 1985-2010).
- **Towns** (`towns`): for every town with a highway Main Street or an interchange: the old highway through it, whether
  and when it was bypassed (by the expressway within 6 km, or a highway bypass), its interchange, how far and since
  when. history.ts's growth reads it: a town grows from its interchange's year (within 6 km), a bypassed Main Street
  declines slowly (to 80%).
- **Route numbers**: kotlin-street/docs/ROUTE_NUMBERS.md (north-south odd, east-west even, ordered across the landmass;
  loops even hundreds, spurs odd hundreds; business routes; exits by km; the Pennant shield).

### Numbers (alpha)

| | 1900 | 1930 | 1960 | 1970 | 1990 | 2020 |
|---|---|---|---|---|---|---|
| expressway km | 0 | 0 | 827 | 4,804 | 7,226 | 7,226 |
| highway km | 0 (12,302 of tracks) | 12,302 | 12,222 | 12,451 | 11,593 | 11,593 |
| county km | 40,169 | 40,169 | 40,169 | 40,169 | 41,137 | 41,137 |
| local (old curves) km | 0 | 0 | 555 | 526 | 394 | 393 |
| frontage km | 0 | 0 | 14 | 116 | 326 | 326 |

- Expressways in 2020: 5,267 km of four lanes, 2,000 km of six (the two-lane super twos all twinned by then).
- 43 Pennant trunks, 37 loops (beltways), 77 spurs (radials); 148 national routes, 32 business routes.
- **635 interchanges**: 126 system (112 directional, 14 cloverleafs), 88 terminal trumpets, 411 service (195 diamonds,
  113 partial cloverleafs, 103 cloverleafs), 10 at-grade junctions; 44 rebuilt later.
- 140 realignments, 5 highway bypasses; **60 bypassed towns** (55 by an expressway, 5 by a highway bypass); 154 towns
  with an interchange.
- Structures: 45 cuts, 55 fills, 4 tunnels, 6 viaducts; 205 expressway bridges.
- Roadside: 73 rest areas, 30 weigh stations, 185 truck stops, 220 motels, 131 diners, 7 toll plazas.
- The skeleton (version 4): 9.84 MB gzipped (was 9.61); the Kotlin reader holds the world in 24.9 MB of RAM in the town's
  memory check (held peak 174 MB of the 256 MB budget).

Drive times between the hubs from the road graph (`RoadGraph.drive`, the fastest car route that year; the expressways are
limited-access links joined only at their interchanges, closed before they open):

| | 1930 | 1960 | 1990 |
|---|---|---|---|
| capital to Port Gilan | 76 min, 101 km | 50 min, 93 km | 50 min, 93 km |
| capital to Coldarcrest | 70 min, 91 km | 45 min, 84 km | 45 min, 84 km |
| capital to Linden Corners | 98 min, 130 km | 66 min, 125 km | 66 min, 125 km |
| Port Gilan to Coldarcrest | 130 min, 148 km | 89 min, 169 km | 73 min, 135 km |
| Coldarcrest to Linden Corners | 163 min, 215 km | 105 min, 200 km | 105 min, 200 km |
| Thunder Mills to Belford Rapids | 275 min, 321 km | 201 min, 321 km | 158 min, 301 km |

The capital's continent is the busiest, so its corridors were all open by 1960: there the gain from 1960 to 1990 is
nil, and from 1930 to 1960 it is the paving and the 1950 speeds as much as the expressways. Elsewhere (the later
corridors) the expressways take a fifth to a quarter off by 1990.

### Bake and app

Skeleton **version 4** (bake.ts): a `highways` object (segments with their steps and path references, interchanges,
routes, sites, towns); a road's old `expressway` year is gone. The Kotlin reader (`WorldHighways`, on
`WorldSkeleton.highways`) parses all of it; the far view (`WorldFarPlan.lines`) draws the network of its year from the
segments: tracks thin, county and local roads, highways by lanes, and **the expressways as their own ribbons (kind
`EXPRESSWAY`, 28/40/50 m by lanes, concrete grey)**; the near terrain lays them as asphalt 6-16 m half wide. The preview
takes `-DworldYear=1990` and has a `world_hwy` shot (the capital and its beltway from 9 km up).
Maps: `npx tsx kotlin-street/tools/world-highways-map.ts <dir>` then
`python3 kotlin-street/tools/world-highways-sheet.py <dir> <out.jpg> [render.png] [caption]`.

### Tests

vitest (gen.test.ts, "Highways v2"): every hub on a landmass with two or more hubs and major towns is on one expressway
network in 1990 (and the drive between two of them takes it); the expressways open 1956-85, more each decade, the
capital's corridors by 1960; every expressway profile under 5%, every point more than 8 m off the ground inside a marked
structure, tunnels under 2% of the length, every river bridged at opening, no fords; every bypassed town's interchange
within 6.5 km (all are within 3.7 km); lanes never fewer, every step in order, each old road's pieces tiling its line,
tracks in 1900, realignments in 1935, local roads, business routes, abandoned roads, county hand-overs in 1990; route
numbers unique per landmass and system, the direction rule, loops and spurs on their parents, business routes, the
national routes in order west to east, exits; nothing in the capital's pad, its radials on its pins' highways; the
Pine Bend and Cedar Relay models placed; drives quicker in 1960 than 1930 and no slower in 1990; determinism of all of
it; the bake round trip. Kotlin (`WorldSkeletonTest.highwaysV2AreTheBakes`): the counts, one segment of each kind in five
years and its path, interchanges with exits, the capital's continent's routes, the bypassed towns; the far view's
expressway ribbons in 1990 and none in 1930.

### Open problems

- **Expressways are data and ribbons only**: no road meshes, ramps or interchange geometry yet (W6); the interchange
  types say what to build.
- **Few highway bypasses** (5 on alpha): the highways link hubs and major towns, so few towns sit on a highway's
  middle; most bypasses are the expressways'. A highway bypass before a late expressway is not modelled.
- **Radials are short** (about 2 km, from the beltway in to the old highway near the edge); the "radial into the
  core" is the old highway through the hand-made plan. Hubs with an authored plan could add their own.
- **The capital's continent's network is complete by 1960**: the staging puts the busiest corridors first, and that
  continent is the busiest. If the owner wants to watch the capital's expressways arrive during play, push its later legs
  (or its beltway, 1961-65) later.
- ~~Connectors run straight~~ (fixed 2026-11-23, TOWNS_EVERYWHERE.md): routed on the land under 7% and filleted; a
  baked town's own roads take over at its ground's edge. Frontage roads are offsets of the expressway line, not routed.
- ~~Rest-area and site positions are on the expressway's line~~ (fixed 2026-11-23): rest areas and weigh stations stand
  beside the carriageway, the interchange sites in the quadrants round their exit.
- ~~Beltways are circles~~ (fixed 2026-11-23): each anchor at its own distance on open, gentle ground off rivers and
  cores, the loop a smooth spline through them (TOWNS_EVERYWHERE.md, "Highways").
- The interchanges near the old highways sometimes merge (1.5 km rule) onto a crossing a little off the town's own
  road; the town's connector then runs to the centre from there.

## The window-seat standard (the owner's direction, 2026-11-26)

Long journeys are part of the game (no fast travel), and the owner is leaning toward unpiloted travel taking real time:
board a train or ship, go live your life, and check in every few hours to see where you are (not yet decided; if it
is, the trip runs on the real clock while the app is closed, the scene is rebuilt from where the vehicle would be,
arrival is announced by a notification, and the ride has things to do: walking the train, the dining car, passengers,
stops, events). Either way the world seen from a moving window must hold up for hours:
- **Never the same view every few seconds:** land use, settlement, trees and colour change with geography and climate
  across a continent: farm belts, forests of different kinds, wetlands, dry country, mountains, coasts, river valleys,
  snow up high, towns at the right spacing.
- **Each region recognisable:** its own tree species, ground colours, field shapes, building styles and road furniture,
  shifting gradually at the boundaries, not tiled.
- **Landmarks along the line:** viaducts, tunnels, gorges, lakes, a distant city's skyline, coastline running beside
  the track, so a long ride has moments.
- **The real train ride's magic, amplified:** light and weather changing over the trip, dawn and dusk across different
  country, night with town lights passing.
Judge world generation by riding it (a long route rendered at intervals), not only by single views.
