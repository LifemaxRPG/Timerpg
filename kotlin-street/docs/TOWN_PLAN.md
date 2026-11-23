# The town plan: a designed American town (phase 1: design)

Status: phases A-D built (branch wf-town-plan, 2026-11-20; see "Implementation" at the end): src/town/plat.ts and
src/town/districts.ts plan every town; gamma's assets are re-exported from it. Phase E (the S25 measurement) is the owner's.
The design below is kept as written, with the owner's decisions of 2026-11-19 recorded under "Decisions for the owner"
(where they differ from the recommendations: the river runs through downtown, the grid varies by seed now).

## Why

Today the plan is grown in one pass: a blob-shaped street web (network.ts), the plaza, the civic landmarks, then every
street frontage filled with lots by distance from the centre (frontage.ts), then the stores and, last of all, the
sixteen hand-made models look for ground no lot uses (glbSites.ts). So the models get what is left at the edge:

- gamma 2025: the funeral home stands alone at the far west end (x 17, the town's edge), the Gazette at the west end of
  Main Street past the last shops, the coin laundry in the north-west woods, the water tower and The Foundry beside the
  supermarket, the 2000s gas station on the drive-in's plot out east; the feed mill has no plot at all, Starling Lanes
  found none either, and the rail's mill site is an empty square in the woods.
- the depot is threaded in after the plan by a path search round the packed blocks, so the railroad runs round the
  town's edge, not through a rail side the town grew along (RAIL.md "Not yet").
- the downtown's frontage (street wall or strip) is decided by distance (`CORE_RANK`), not by what the street is.

Real towns are the other way round: the town was platted (surveyed into blocks and lots) along the railroad and round a
square, Main Street ran from the square to the depot, and every later thing (the strip, the subdivisions, the bypass) was
added at the edge in the order the decades brought it. The plan below does the same: every building type has a district
and a slot reserved for it from the plan's first year, and the town fills them as it grows.

## Units

Plan tiles throughout (as plan.ts): a tile of position is 2.1 m of ground in the 3D town (TOWN.md "Geometry": positions
scale by k = 1.693, buildings keep their art size, so the plan's room behind and between buildings becomes yards). A
street is 2 tiles plus a 1-tile sidewalk each side. "Along" is along the street, "deep" back from it.

## The plan in one picture

```
              farmland (fields, farmsteads, country roads)          N
   +-----------------------------------------------------------+     ^
   | heights   | later subdivisions (1975-2020)   cul-de-sacs   |
   | (villas)  |---------------------------------------------- |
   |  water  R | old town  | civic: school,  | post-war subdiv.  |     superstore
   |  tower  I | (houses,  | church, fire,   | (1947-70)         |     (1990s, on a
   |         V | alleys,   | police          |-------------------|      field)
   |         E |cemetery)  |-----------------|  strip rear:      |
   |  west   R | West Main | CORE |SQUARE|CORE| garden apartments |  bypass
 ==== MAIN STREET ====== (bridge) ===========  HIGHWAY: motel, diner, lanes, drive-in, strip centre, clubs == gas ===
   |  side   P | big house:| CORE: street walls, alley,   | strip: car parks in front, supermarket     |
   |         A |funeral hm | back lots / car parks        | on the drive-in's ground                   |
   |---------R-|------- Railroad Avenue ---- depot faces up Depot Street to the square -------------------|
 ==#===#===#=K=#===#===#==#===# TRACK #==#===#===#===#===#===#===#===#===#===#===#===#===#===#===#===#===#===
   |  works  | feed mill + elevator (siding) | warehouses | iron works / The Foundry | lumber yard          |
   |---------|------- South Street ----------------------------------------------------------------------|
   |  across the tracks: worker houses, small lots, mobile homes later | post-war / later subdivisions    |
   +-----------------------------------------------------------+
```

## Districts, in their order outward

| # | district | platted | what is there | why it is there |
|---|---|---|---|---|
| 1 | **Civic block**: courthouse square (county seat) or plaza and town hall | founding (1880s) | courthouse (a county seat) on its own 38 x 38 block, or the 16 x 13 plaza with the town hall behind it; post office, clock tower, war memorial on its edges | the town was laid out round it; Main Street runs along its front |
| 2 | **Downtown core** | 1885-1910 | two- and three-storey street-wall blocks on both sides of Main Street, 1-2 blocks each way from the civic block (by size); service alleys behind; back lots (yards, then renewal car parks 1962-78, then towers in big towns) | the street wall is the town's face; one frontage type per block face (the owner's rule), decided by district, not distance |
| 3 | **Old neighbourhoods** | 1890-1940 | the original grid: medium, small and house lots with alleys; churches on corners; the school and the fire and police stations on the civic block one street behind the square; a cemetery at the old town's edge (on the river or the hill); the big houses of **West Main** (Main Street's other end, past downtown) | walking distance to Main Street and the depot, as before cars |
| 4 | **Rail side** | with the railroad (1878-1915) | the track one block south of Main Street (behind the downtown's south block); **Railroad Avenue** on its town side; the depot at the foot of Depot Street (the civic block's side street), looking up it to the square; the siding with the elevator and feed mill; the works (iron works, later The Foundry); warehouses, lumber yard, coal dock | the railroad came first: the plat was laid along it, the depot facing downtown, the industry beside the siding |
| 5 | **Across the tracks** | 1900-1940 | worker houses on small lots, later mobile homes and the first garden apartments | "the other side of the tracks" |
| 6 | **Highway strip** | 1946-1990 | Main Street east of the core becomes the highway: deep lots with car parks in front: the Main Street service station on the core's last corner, the diner where the highway leaves town, the motel, the coin laundry and the strip centre (the Reel Time video strip), Starling Lanes, the club plot, the drive-in out on the edge, the market and the supermarket, apartments behind | built for cars, in the order the decades brought it; never beside the old core's street wall |
| 7 | **Post-war subdivisions** | 1947-1970 | behind the strip and north of the old town: longer blocks with no alleys (backyards meet at the rear fence), medium and house lots, a few dead ends | the first car suburbs |
| 8 | **Heights** | 1958 on | villas and estates on the hill (the higher ground across the river or behind the old town), with the water tower on the crest in towns where it is not by the elevator | where the money went; the tower wants the high ground |
| 9 | **Later subdivisions** | 1975-2020 | the outer ring: house, villa and estate lots on cul-de-sacs, apartments by the bypass; the hospital (1965-2002) on their edge | commuter growth |
| 10 | **The bypass and the big boxes** | 1985-2010 | the section road at the strip's far end becomes the bypass; the superstore beyond it on a field, the 2000s gas station at the junction | the last thing a town grows |
| 11 | **Farmland** | always | the farms ring (farms.ts) round all of it: country roads continuing the streets that leave the plan, the section road, farmsteads; fields are sold as districts are platted (`fieldLives`) | unchanged rules |

The river (when the seed has one) is not a district of its own but a **column of the grid**: a 30-tile band with the
river in it and a park on both banks, one block outside the core (west of West Main in the prototype); Main Street and
the street past the track bridge it. In a mill town the works and the dam stand where the river meets the rail side.

## Streets

| class | where | width (tiles) | notes |
|---|---|---|---|
| Main Street | through the core, along the civic block | 2 (+1+1 sidewalk) | benches, lamps every 9, later angled parking (engine work) |
| the highway | Main Street's continuation east of the core, then out of town | 2 | the strip faces it; the country road beyond it is the busy road out (rail crossings get gates) |
| the bypass | the strip's far end, north-south (the section road) | 2 | the 2000s gas station at its junction with the highway |
| Depot Street / Court Street | the civic block's sides, north to the school and south to the depot | 2 | the civic axis: the player sees the depot from the square |
| Railroad Avenue, South Street | both sides of the rail band | 2 | the rail side faces them; streets cross the track only at 3-4 level crossings, the rest stop at the rail band |
| residential streets | the grid | 2 | lamps every 13 |
| cul-de-sacs | later subdivisions | 2 | a dead-end stub into a long block (the axis-aligned stand-in for curving streets) |
| alleys | behind Main Street's shops (service alley) and through old-town blocks | 3 (no sidewalk) | drawn as lanes; not rows in the street graph (walkable ground, like today's service alley) |
| country roads | farms.ts spokes and the section road | as today | unchanged |

All streets stay **axis-aligned rectangles** (the street graph, rows, collision, rail crossings and farms all assume
it). Variety comes from block lengths, dead ends, a jog in Main Street at the river (as network.ts's jogs), long
subdivision blocks, and the seed's choices below, not from curves.

## Blocks and lots (tiles)

| block | between street centre lines (along x deep) | faces | lots |
|---|---|---|---|
| civic block | 42-44 x 38 (county seat 44 x 44: the courthouse square, 80 x 80 m with its lawn and streets) | all four | courthouse or plaza 16 x 13 + town hall 14 x 23 + post office 9 x 13 + clock tower 8 x 15 |
| downtown | 44 x 33 (north of Main 38 in a county seat) | Main Street; back lots onto the next street | 6 shop plots of 7 x 13 a face (the Gazette, the general store, the cinema take 2-4 of them mid-block), 3-tile service alley, back lot 13-17 deep |
| old town | 44 x 41 | north and south | medium 10 x 16, small 8 x 14, house 14 x 17; 1 tile between; a 3-tile alley down the middle |
| West Main | 46 x 38 | Main Street | house 14 x 17 (the big old houses); the funeral home's 22 x 11 corner |
| rail band | any x 44 | Railroad Ave, South Street | the track 26 tiles south of Railroad Ave's centre; the depot 43 x 12 (premier) or 26 x 12; warehouses 14 x 10; the feed mill 25 x 10 + elevator 9 x 9 on the siding; the works 24 x 15 |
| across the tracks | 44-46 x 41 | north and south | small 8 x 14 shoulder to shoulder, medium, mobile 8 x 12 |
| strip | 62 x 33-38 | the highway | the models' own plots (glbLandmarkTiles), strip shops 18 x 14, the strip centre 36 x 12 with its car park; garden apartments 7 x 13 behind |
| post-war | 62 x 41 | north and south | medium and house, no alley |
| heights | 46 x 48 | north and south | villa 11 x 18, estate 25 x 19, 2 tiles between |
| later | 62 x 41-48, dead-end stubs | north and south | house, villa, apartments (7 x 13 blocks of 3-4) |
| the edge | 140-150 x two rows | the highway, the bypass | superstore 112-128 x 75-98, the 2000s gas station 27 x 17 |

The lot depths include the front yard (`FRONT`, 2 tiles) and a backyard; the 3D yard kit (town-yards.ts) keeps its
yards inside the lot plus the alley's edge, so the world is complete from behind: every house has a backyard on an alley
or a rear fence, every Main Street block shows its back doors, dumpsters and car parks to the service alley.

## Town type and size

The size class comes from the town's peak inhabitants (`peakPeople`, 36 a simulated person): **S** under 5,000,
**M** 5,000-9,000, **L** 9,000 and more. The towns of the current world are mostly 6,000-11,500 at their peak (orbit,
4,068, is the small one), so most are M and L; the thresholds can move once more seeds are looked at.

| | S | M | L |
|---|---|---|---|
| core blocks each side of the civic block | 1 | 2 | 2 (and a second row of downtown on the cross streets) |
| block rows north / south of Main | 2 / 2 | 3 / 3 | 3-4 / 3 |
| strip blocks | 2 | 2 | 3 |
| shop plots | ~20 | ~30 | ~45 |
| homes when built out | ~60 | ~200 | ~280 |

Town type (the archetype, `archetypeOf`) changes the plan's emphasis; **county seat** is a separate flag (below).

| type | plan |
|---|---|
| farm town | short Main Street, the elevator, feed mill and water tower together by the siding (the prairie skyline), the strip short (service station, diner, motel, drive-in on the edge), farmland close on every side; a T-plan variant (Main Street running north from the depot, the depot at its foot) for some seeds |
| mill town (and mining) | the works on the river by the rail side, worker houses in rows across the tracks and by the works, Main Street from the works' gate to the square; The Foundry is the works reused |
| commuter suburb | a small old village core, large post-war and later subdivisions, a long strip and the big boxes at the bypass; the heights with villas |
| harbor, resort | as the mill town and the suburb, with the waterfront (lake or river) as the park edge and the hotel facing it (the lake takes the river column's place) |
| county seat (any type) | the courthouse square as the civic block: the four faces round it are street walls (the blocks east and west turn their shops round the corner), the town hall, post office and fire station face it from the block behind, the hotel faces it; a bigger core (+1 block each way); most likely in L towns |

County seat is drawn from the seed (pure, like everything in the plan): about 35% of L towns, 15% of M, 5% of S; a
later world-level rule (one seat per county of cells) can replace the roll.

## Where each building goes

Code-built buildings:

| building | district and slot | before / after |
|---|---|---|
| Main Street shops | downtown core, both sides of Main Street; county seat: all four faces of the square | woods before the plot's year; the facade kit's eras as today |
| corner bank, law office, doctor | the core blocks' corners | |
| towers, mid-rises, hotels (downtown.ts) | the core's back lots (two plots' worth behind the alley) | back yards, then renewal car parks (1962-78), then the tower |
| renewal car parks | the core's back lots | |
| general store | core, south side, west block, mid-block 12 x 15 | |
| cinema | core, opposite the civic block (the view from the square), 30 x 26 through to the alley | shops before it opens |
| market (1950-69) | the core's last block on the strip side, 22 x 27 with its little lot | shops before |
| supermarket (1970-2025) | the strip, on the drive-in's ground when the drive-in has gone (else a field) | the drive-in |
| superstore (1990-2025) | beyond the bypass, north of the highway, on a field | a field |
| town hall, plaza, clock tower, post office | the civic block | |
| school | the civic block of the old town (behind the square), 24 x 23 | |
| fire, police | the same block, facing the cross street | |
| hospital | the edge of the later subdivisions | a field |
| chapel and churches | old-town corners (one per old-town block row; the chapel model for one) | |
| houses (small, medium, house) | old town, across the tracks, post-war | woods or field before their year |
| villas, estates | heights, later subdivisions | |
| condos and terraces | near the core (up to 5 storeys) and behind the strip (2-3 storey walk-ups), as town-downtown.ts does now | |
| mobile homes | across the tracks, from the 1960s | |
| farms | the farmland ring (farms.ts), unchanged | |
| depot (procedural) | the rail band at the foot of Depot Street (towns under 3,000) | |

The hand-made models (src/town/glbLandmarks.ts; the courthouse to come):

| model | district and slot | why | before / after (the plot's chain) |
|---|---|---|---|
| Gazette (1905-40, stands on) | core, north side of Main Street, mid-block in the block east of the civic block, 12 x 10 at the pavement | a weekly paper is mid-block on Main, near the courthouse or town hall | built before 1950: always there; dark after it folds |
| junction diner (1940-65) | where the highway leaves the core (the strip's first corner), facing the highway | the "junction" of Main Street and the road out; first thing a driver sees | a field before; after it is pulled down, a strip shop |
| Main Street service station (1950-69) | the core's last corner on the strip side (north face), 19 x 11 | the gas station at the edge of downtown | repair garage, then pulled down for a strip shop |
| modern gas station (2005-) | the bypass junction, south side of the highway | the 2000s station is on the bypass, not downtown | a field |
| roadside motel (1950-69) | strip, north side, first strip block | motels line the highway into town | after it is pulled down: garden apartments (or an extended-stay) |
| Starling Lanes (1960-69) | strip, second block, north side | needs a big lot and a car park | after: offices or a church (the plot is reused) |
| coin laundromat (1960-75) | strip, first block, south side, in front of the strip centre | the laundromat belongs to the strip centre | the strip centre's ground |
| Reel Time video strip (being added) | the strip centre: first strip block, south side, 1970s, with its car park | the video store is a strip-centre tenant | a field before the strip centre |
| starlight drive-in (1950-66) | the strip's outer block, south side, 29 x 21 (and its screen ground) | on the edge of town, on cheap land | a field before; the supermarket's site after it shuts |
| Mirage, then Pulse (one plot) | strip, the outer block's corner, north side | a club wants the highway corner and a side lot | reserved ground before; Pulse on Mirage's plot |
| iron works, then The Foundry (one plot) | rail side, east of the depot (mill towns: by the river where it meets the rail) | the works were on the siding | the works become the club |
| feed mill and elevator | rail side, west of the depot, on the siding | grain goes out by rail | storage or brewery after it shuts |
| water tower | farm towns: by the elevator; otherwise the heights' crest; county seats without a hill: the civic block behind the courthouse | high or civic ground | |
| premier depot (3,000+) | the rail band, at the foot of Depot Street, looking up it to the square | the depot faces downtown | |
| funeral home | the corner of West Main and the first cross street west of downtown: a big house on a 22 x 11 corner lot | a converted big house on an old residential street near downtown | a house from its build year (1910-35), converted 1935-60 |
| courthouse and square (four states, 1894-2000s) | the civic block of a county seat, 44 x 44, faces round it street walls | the county seat's centre | the square's lawn and memorials before 1894 |
| library, 1912 and the 1970s wing | the old town behind the civic block, near the square | Carnegie libraries stood a block off the square | woods before 1906 |
| corner drugstore (four states) | a core corner on Main Street's north side | a drugstore wants a corner | shops before it |
| town state bank, then the drive-through | the core's south side, mid-block | | shops before it |
| brick church, country church | an old-town corner; the country church at the edge | | woods, a field |
| Lincoln High School campus (four states) | the grounds (needs 188 x 77 tiles) | | a field |
| carhop drive-in (open, shut, restored) | the strip, north side | | a field |
| fire station 1924 / 1988, post office 1938 / 1988 | the blocks behind the civic block, facing the square | replace the sprite landmarks (capital) | woods |
| Great Meridian (four states), the Eyrie, Union Arch, Night Keeper, Rex, rest area, lodge | the capital's grounds, past the highway's far end | landmarks that make the capital worth travelling to | fields |

A town only has the models it earns (glbTenures, unchanged); the plan reserves slots for every model it will ever have
and leaves the others to its district's ordinary use (a strip shop, a house, a warehouse).

## Growth by decade, and reserved plots

The plan is one pure function of the seed (as now): every street and every plot, for all years. What stands in a year is
decided by four things:

1. **District platting year**: each district has an earliest year (table above). Its streets are built when its first
   lot is (layout.ts already shows only the streets with lots; farms.ts sells the fields under them).
2. **The plan order** replaces the distance rank: candidates are ordered district by district (core and old town, then
   across the tracks, then the post-war, the heights, the later subdivisions), nearest first within a district. The
   simulation's households take plots in this order (properties.ts already takes the next candidate in plan order), so
   the town fills outward the way the plan was platted.
3. **Households and filler homes** (a decision for the owner, below): the simulation has a household for about every
   115 inhabitants (gamma 2025: ~70 households for 8,316 inhabitants), so a block built only from simulated households is
   mostly empty. The plan is sized for the town's peak, and a district is **built out** as the town grows into it: lot i
   is built the first year the town's homes, scaled so the plan is nearly full at its peak, pass i (the prototype does
   this). The simulated households live in some of those houses (with dialogue, interiors, schedules); the rest are the
   town's other families: houses with lit windows, cars, yards, nobody to talk to yet (the export's `extra` kind, as the
   downtown towers). gamma 2025 then has ~210 homes instead of ~70.
4. **The reservation ledger**: every model, store and civic building has a plot reserved from the plan's first year,
   with a chain of uses (`[{use, from, to}]`): before it opens the plot shows its before-use (a field, an empty lot with
   a for-sale sign, the house the funeral home was, the shops the cinema replaced); after it is pulled down the next use
   (the drive-in's ground becomes the supermarket, the motel garden apartments, the lanes offices). Time travel to any
   year shows the same plot with what stood there then. This is glbSites.ts's `tenures` per site, extended to every
   reserved plot and decided by the plan instead of searched for afterwards.

The **downtown's frontage** follows the district: a core block face is always a street wall (2-4 storeys, built
1885-1935), a strip face always car parks in front; a face never changes type (the owner's rule), only its buildings
change (renewal car parks behind, a tower, a closed shop).

gamma (Mapleridge, commuter suburb, 2,844 inhabitants in 1950, 8,316 in 2025) through the decades, as the prototype has it:

- **1950**: the square block with plaza and town hall, five downtown blocks (shops on both sides of Main, the Gazette since
  1926, the general store, the cinema), the old town north of Main and on West Main (the big house that becomes the
  funeral home in 1940 is already one), the school and church behind the square, the depot, feed mill (1945) and iron
  works (1909) on the rail side, the worker houses across the tracks, the water tower (1926) on the hill west of the
  river, the river park and the cemetery. The strip, the subdivisions and the bypass are fields; their plots are reserved.
- **1960s**: the first post-war blocks north of the old town; the motel (1962) and the drive-in (1964) on the highway;
  the market (1968), Starling Lanes (1968); the downtown's back lots become car parks.
- **1970s**: the strip centre with the coin laundry (1975); the heights fill; the feed mill shuts (1975).
- **1980s**: the iron works shut (1980), the drive-in is gone (1980) and the supermarket opens on its ground (1984);
  the video store opens in the strip centre; later subdivisions start east and north.
- **1990s-2000s**: Starling Lanes shuts (1996, offices after), the motel goes (2002, apartments after); a hotel and offices
  behind Main Street (1994-2019).
- **2010s-2020s**: the 2000s gas station at the bypass (2016), the superstore beyond it (2015), the brewery in the feed
  mill (2014), The Foundry in the old works (2022).

## How it fits the existing systems

| system | today | under the plan |
|---|---|---|
| street network (network.ts) | blob outline, uneven streets, Main Street with jogs | replaced by a **platting step**: columns and rows of blocks from the town's size and type (widths jittered by the seed), the civic block at the centre, the river column, the rail band; streets between blocks with the exceptions (crossings of the track, dead-end stubs, alleys). Still axis-aligned `Road` rects split at every junction (so `nodes`/`rows` in town-graph.ts work unchanged) |
| frontage (frontage.ts) | fills every frontage by distance and a hill | replaced by **district fillers** (one per district) that place lots of the existing `LotKind`s and sizes; the ids keep the `kind:x,y` form |
| candidates and rank (plan.ts) | rank = distance + jitter | rank = plan order (district, then distance); `Candidate` gains `district` and `from` (the earliest year) |
| neighbourhoods (neighborhoods.ts) | five zones by position | districts map onto them: old town and West Main `old-town`, post-war `gardens`, heights `heights`, later `woodside`, river and across the tracks `waterfront` (or a new id `the-flats` for across the tracks, if the owner wants a sixth) |
| civic landmarks (landmarks.ts) | placed near the plaza where free | placed by the plan in their slots (`placeLandmarks` becomes a lookup); specs and opening years unchanged |
| stores (storeSites.ts, stores.ts) | downtown sites, the edge stores on a padded canvas | sites from the plan (general store and market in the core, supermarket on the drive-in's ground, superstore beyond the bypass); `storeNeeds` and histories unchanged; the canvas padding goes |
| cinema (cinema.ts) | a landmark plot found while growing | the core's slot opposite the civic block |
| downtown (downtown.ts) | `CORE_RANK` by distance, `stripFace` by rank, tower sites from back lots | core and strip by district; tower sites are the core's back lots (as now); town hall in the civic block; the chapel on a reserved church corner |
| models (glbSites.ts, glbLandmarks.ts) | searched for after the plan | `glbTenures` unchanged; `glbSites` returns the plan's reserved plots (the search goes); the GlbSite shape (`rect`, `road`, `face`, `lotType`, `tenures`) stays, so town-glb.ts and the engine need no change |
| water (water.ts) | a river takes a cross street's place | the river runs down the plan's river column (meanders inside it), bridges where Main Street and the street past the track cross; a lake takes a corner of the plan beside the park |
| railroad (rail.ts, world/rail.ts) | path search round the finished plan | the plan reserves the **rail band** (east-west, one block south of Main Street) and, for towns the world gives a north-south line, a north-south band east of the core; rail.ts lays the track in the band (its path search kept, with the band as the cheap ground and everything else blocked) and on out to the world's portals; level crossings only where the plan lets streets cross; the depot and elevator sites come from the plan. A town the world gives no railroad keeps the band as an industrial street (Front Street with warehouses on a spur road) |
| farms (farms.ts) | spokes from the streets' ends, the ring | unchanged rules; fields are sold as districts are platted; the bypass is the section road's piece past the strip |
| history and the simulation (properties.ts, sim) | households take candidates in rank order | the same code, with the plan order |
| the world (world/map.ts) | Main Street's ends link the towns | Main Street's west end and the highway's east end are the ends (mainStreetEnds unchanged) |
| the export (town-graph.ts, town-yards.ts, town-glb.ts) | rows from road rects, yards past plots | unchanged in shape; alleys as walkable ground (like today's service alley); filler homes as `extra` buildings; the yard kit gets real alleys and rear fences |
| dialogue (export-dialogue.ts, town-whole.ts) | buildings named bN in layout order | unchanged code: names are re-derived from the new layout and every asset is re-exported together (below) |

## What changes in which files (phase 2)

| file | change |
|---|---|
| src/town/plat.ts (new) | the platting: size and type profile, columns and rows, districts, streets, river column, rail band, civic block |
| src/town/districts.ts (new) | the district fillers (lots per face), the reserved slots and their use chains (the ledger) |
| src/town/plan.ts | `build()` calls plat + districts instead of network/frontage/placeLandmarks/storeSites; `Candidate` gains `district`, `from`; rank = plan order; `TownPlan` gains `districts`, `reserved`, `railBand`, `countySeat` |
| src/town/network.ts, frontage.ts | kept for the old plan behind a switch until phase 2 is accepted, then removed |
| src/town/landmarks.ts, storeSites.ts | placement from the plan's slots (specs, histories, art keys unchanged) |
| src/town/downtown.ts | core and strip from the district; townHall and chapel from the plan |
| src/town/glbSites.ts | `glbSites` returns the plan's reserved plots (after the video-store job has merged) |
| src/town/water.ts | the river in the river column |
| src/town/rail.ts | the band as the preferred ground; depot, siding and elevator from the plan |
| src/town/neighborhoods.ts | districts to neighbourhoods |
| src/town/layout.ts, properties.ts | only the order (already plan order); the frontier rule becomes the district build-out |
| src/town/farms.ts | spokes from the new street ends (the code is generic); check the bypass |
| kotlin-street/tools/town-graph.ts, town-yards.ts, town-glb.ts, export-rail.ts | filler homes as `extra`; alleys; checks |
| kotlin-street/tools/export-town.ts and every exporter | re-run: street.json, rail.json, dialogue.json, interiors.json, the buildings' assets, the fixtures (town-gamma.json, stores fixture) |
| tests | plan tests (new), and the fixtures and expectations of glbSites, downtown, stores, landmarks, layout, rail, farms, town-graph, town-yards, town-glb tests; Kotlin tests that use town-gamma.json |
| docs | TOWN.md, LANDMARKS_GLB.md, RAIL.md, STORES.md sections that describe placement |

## Risks

- **Dialogue and building ids.** Sprite names (bN) come from the layout's order and the lot slots (`kind:x,y`) from the
  plan, so every name moves. Nothing breaks if every export is re-run together from the same commit (the exporters all
  read town-whole.ts); export-dialogue.ts's own check (every talkable building is on a row) catches a mismatch. The app
  saves only the seed and the player's world position (TownSession.kt): a saved position may now be inside a building, so
  the load must use `nearestFree` (one line). Person and business ids come from the simulation and must not change: a
  test runs the timeline before and after and compares people, businesses and events (properties.ts reads the plan, so
  it must only change who lives where, never who exists).
- **Test fixtures.** Many tests pin gamma's numbers (lot counts, model sites, rail crossings, the desktop fixture
  town-gamma.json, Kotlin tests walking fixed spots). They have to be re-derived, not loosened: each pinned number is
  replaced by the plan's rule it was checking (for example "the Gazette is in a street-wall face of the core").
- **Performance.** Built out, gamma has ~285 buildings in 2025 instead of ~110 (homes ~210). The far boxes merge per
  cell (one draw a cell), so draws grow little, but triangles, yards, props and memory grow; `townMemoryCheck` held 197-201
  MB of 256 before. Measure on the S25 (frame time at the busiest views, memory, the export's build time); the filler
  share is a quality setting if needed (never fewer simulated households).
- **Determinism.** The plan must stay a pure function of the seed: no Map/Set iteration order in placement decisions, no
  floats compared without rounding, every random draw keyed by stable names (`rand(seed, 'plat', ...)`). The export is
  run twice and diffed in the test suite.
- **The world.** The rail band is planned from the seed; the world's lines come from the world seed. Where the world's
  line enters from a side the band does not face, rail.ts's free path outside the town connects them (as today); a
  north-south-only town needs the second band. Towns' farmland overlapping the next town's plan (RAIL.md) is unchanged.
- **Parallel work.** glbSites.ts, glbLandmarks.ts and the gamma export are being changed now (the video store): phase 2
  starts from that merge and touches glbSites.ts only after it.
- **Taste.** A grid this regular can look planned in the wrong way; the seed's jitter (block widths, dead ends, a jog at
  the river, district choices) has to be judged in renders from the street, from behind and from above, not only on the map.

## Phases (phase 2 onward)

| phase | delivers | tested by | size |
|---|---|---|---|
| A. Platting and districts (Opus design, Sonnet fill-in) | src/town/plat.ts, districts.ts: the plan for any seed behind a switch (`PLAN_V2`), the ledger, county seat roll, filler homes | new unit tests: determinism (twice, equal), no overlaps (lots, streets, water, rail band), every street connected, every model the town earns has a reserved slot in its district, core faces all street wall, the strip never touches the core's wall, homes at the peak >= households, plan maps for 6 seeds (plan-map.py) judged by eye | L: ~1,200 lines + ~400 of tests |
| B. Consumers (Sonnet) | plan.ts on the new plan; landmarks, stores, cinema, downtown, glbSites, neighbourhoods, layout/properties from it | the existing src/town tests updated rule by rule; the simulation's people, businesses and events unchanged for 8 seeds | M: ~600 lines changed |
| C. Water, rail, farms (Sonnet, Opus review) | the river column, the rail band in rail.ts, the depot, elevator, crossings, farms' spokes and fields sold | rail.test.ts (crossings square, nothing over a lot, the depot faces Depot Street, a Main-side crossing), water and farms tests | M |
| D. Export and app (Sonnet) | town-graph, yards (alleys, rear fences), filler homes as extras, all assets re-exported (street.json, rail.json, dialogue.json, interiors.json, buildings, fixtures), the saved position's nearestFree | tools tests (town-graph, yards, glb, whole), export run twice and diffed, export-dialogue's row check, the desktop JVM tests, one APK build | M-L |
| E. Judge and measure (Opus) | a handful of Filament renders of gamma (street level on Main Street, the square, the rail side, the strip, from above and behind) in 1950 and 2025; S25 frame time and memory | the owner's eye; townMemoryCheck; the S25 | S-M |
| F. Later | time travel per year in the app (the ledger already has every year), the courthouse model in county seats, more church models, a wider Main Street with angled parking, curving subdivision streets | | |

## Decisions for the owner

1. **Spawn**: start the player on Main Street in front of the civic block (the square or the plaza and town hall),
   looking along Main Street. Today's spawn junction (the civic avenue at x136) does not exist in the new plan.
   *Recommended.*
2. **The river**: one block outside the core (west of West Main), with a park on both banks and Main Street on a bridge,
   instead of through the middle of downtown; in mill towns the works stand on it. *Recommended*; the alternative is a
   river between the core and the strip (Main Street crosses it mid-town).
3. **The railroad**: parallel to Main Street one block south, the depot at the foot of Depot Street looking up to the
   square (most towns); farm towns sometimes the T-plan (Main Street running from the depot). *Recommended.*
4. **Filler homes**: build districts out (~210 homes in gamma 2025, ~140 of them without simulated families, no
   dialogue yet) rather than only the simulated households' ~70 houses with woods between. *Recommended*; costs S25
   frame time and memory, to be measured.
5. **Town footprint**: an M town's built-up area becomes ~610 x 250 tiles (1.3 x 0.5 km) with the strip and the bypass,
   about twice today's. *Recommended* (the strip and the big boxes need the room; the far boxes keep it cheap).
6. **Axis-aligned streets only** for now (cul-de-sac stubs instead of curves). *Recommended*; curves are a later export
   and engine job.
7. **County seats from a seed roll** (about a third of L towns) until the world has counties. *Recommended.*
8. **Successor uses** on the highway: the drive-in's ground becomes the supermarket, the motel garden apartments, the
   lanes offices, the diner a strip shop. *Recommended*; each is a one-line change.
9. **A cemetery and more churches** in the old town (we have one chapel model; a cemetery is ground, headstones and a
   fence from the yard kit). *Recommended.*
10. **A sixth neighbourhood**, "the Flats" (across the tracks), for the worker houses, instead of folding them into
    `waterfront`. *Recommended* only if the dialogue's neighbourhood facts want it.

## The prototype

- `kotlin-street/tools/plan-sketch.ts <seed> <year> [--seat]`: the plan above for a real town's facts (type, people by
  year, models and their years, stores, civic landmarks, river) as JSON. It is rough: one fixed grid template with the
  size and type switches, no jitter of block widths, the T-plan and the north-south rail band not drawn, warehouses
  placed at random, filler homes for every lot.
- `kotlin-street/tools/plan-map.py out.jpg a.json b.json ...`: top-down maps (districts, streets with future ones dotted,
  lots coloured by district, labelled buildings, reserved plots dashed with their year, rail, river, farmland). It also
  draws a dump of today's generator for comparison (a JSON with the plan's roads and candidates, the layout's lots and
  landmarks, the town hall, chapel, model sites, rail and farms: the probe used for the sheet).
- The sheet (2026-11-19): today's gamma 2025, the plan's gamma in 1950 and 2025, orbit (Springford, a small farm town,
  2025) and pi (Riverburg, a mill town planned as a county seat, 2025).

## Implementation (phases A-D, 2026-11-20)

The owner's decisions: the river **through downtown** (Main Street and a parallel street or two on bridges, the banks a
park, a mill town's works where it meets the rail side); **filler homes** fill the blocks (about three homes a simulated
household at the peak: gamma 2025 has 217 home lots built, 166 of them filler homes, no family or dialogue yet, marked
`filler`); **varied grids now**; the start spot on Main Street in front of the civic block, an old save's spot through
the nearest free ground; the other recommendations as written (county seat by seed roll, successor uses as ledger data,
a cemetery and the chapel's corner, the Flats).

- **Platting** (src/town/plat.ts): size class from the peak (S < 5,000 < M < 9,000 < L); county seat roll (35% L, 15% M,
  5% S); plan form (the parallel rail band one block south of Main Street, or the T-plan: a rail column at the core's west
  end, Main Street running up from the depot: 45% of farm towns, 20% of others); the plan mirrored by seed (the strip east
  or west, the rail side north or south); block widths and depths jittered; the core 1-3 blocks each side (the seat +1, L
  towns longer east), 1-3 west columns, 2-3 strip columns, the edge column (the bypass, the gas station, the superstore's
  merged block) sized for the stores the town will have. The river takes a column of its own through the core (at the
  core's edge in mill and mining towns), and in about half the seeds jogs a block sideways north of Main Street through
  the back lots of the core's north row (the shops there back onto it); only Main Street, the street past the track and
  one street north of the core bridge it. The band is crossed only at the core's ends, the town's west column and the
  bypass; the later subdivisions get cul-de-sac stubs. Streets are axis-aligned rects split at junctions as before.
- **Districts and the ledger** (src/town/districts.ts): every civic building, store and model the town earns has its
  reserved plot with a chain of uses (`plan.v2.reserved`: the courthouse's square; plaza, post office and clock tower on
  the civic block, school, fire and police behind it; the cinema opposite; the general store, the market at the core's
  strip end; the Gazette mid-block (in the river-backed block when the river jogs); the service station on the core's last
  corner; the diner, motel, coin laundry, strip centre (never beside the laundry), lanes, the club plot and the drive-in on
  the strip in that order; the supermarket on the drive-in's ground when the drive-in has gone by its year, else a field
  beside; the 2000s gas station at the bypass, the superstore beyond it; the feed mill and the works on the band beside the
  depot's ground (the works by the river in a mill town); the funeral home on West Main (an old-town block in a T-plan); the
  water tower by the elevator (farm towns), on the heights or behind the civic block; the radio station on open ground at
  the post-war edge; the hospital at the later subdivisions; the chapel and the cemetery). Then the lots (the existing
  kinds and sizes) along each block's faces: shop walls on Main Street, old houses and a few apartment blocks behind, old
  town with an alley, worker houses, post-war, heights, later. Plan order: district by district, nearest first.
- **plan.ts** builds the plan from them (`setPlanVersion(1)` keeps the old grown plan for comparison): landmarks and stores
  from their plots, the river down its course (water.ts riverWater), a lake kept off the plan, each lot's build-out year
  (`Candidate.from`), neighbourhoods by district (a sixth: the Flats). The simulation's people, businesses and events are
  unchanged (plat.test.ts runs 8 seeds under both plans).
- **Consumers**: glbSites returns the ledger's plots; downtown's frontage by district; the town hall's own plot facing a
  county seat's square; layout's households take built-out lots first and `layout.fillers` stands the rest; the railroad
  keeps to the band (rail.ts: the town's other blocks are costly ground it never crosses, the depot on the band's track
  line near the foot of Depot Street); farms farm the later districts until they are built, never the band.
- **The world** cells are 1200 x 440 tiles (the planned towns reach ~550 tiles each way from the civic block).
- **Gamma** (Mapleridge, commuter suburb, M, not a seat): a T-plan, mirrored (the strip west, the civic side south), the
  river at the core's strip end, 50 shop plots and 323 home plots in the plan; 1950: 75 homes, 2025: 217 (with 47 homes of
  simulated households). Its models: the Gazette (core, 1926), the motel (1962-2002), drive-in (1964-80, the supermarket
  on its ground 1984), Starling Lanes (1968-97), the strip centre (1972-), coin laundry (1975), the 2000s gas station at
  the bypass (2016), the feed mill and the works / The Foundry on the rail column (1945, 1909/2022), the funeral home in the
  old town (1940), the water tower on the heights (1926), the radio station (1946). Every model it earns has its plot.
- **Export** (street.json v2, 290 buildings): filler homes and shops named after the simulated lots and dressed as their
  kind, the plan's alleys as lanes, the spawn before the civic block. Memory: townMemoryCheck held 236-249 MB of 256 over two runs
  (live 125-136, direct 131-155; was 197-201): under the budget, with little room left. Draws as drawn 295-657 a view in the desktop renders (llvmpipe; not the S25).

Open: road bridges draw as road over water (no deck or rail); the river's banks are bare grass (a riverwalk's trees, path
and benches to come); a T-plan town whose world line runs east-west meets its rail column from an end and loops (gamma's
depot stands two blocks south of Main Street on the column); a town whose through line runs across its band has its depot
on that line (the second band is to come); successor uses after a model goes (garden apartments, offices) are ledger data
only; the cemetery is reserved ground (no headstones yet); one chapel (no more church models); the NPCs to talk to fell
from 146 to 101 in gamma (more households share apartment blocks near the core).

### Final round (2026-11-22)

- **Downtown straddles the river** (src/town/plat.ts): in a river town the core is laid round the river's column, the
  civic block one core block from it on one bank, about as many core blocks on the other (`far` beyond the civic block,
  `other` on the far bank: they differ by at most one), the river on the strip's side of the civic block or the other by
  the seed. Main Street's bridge is in the middle of the core. The jog through the north row's back lots is gone (it put
  a core column on one bank only). plat.test.ts checks the bank counts, the bridge inside the core's span and the block
  between the civic block and the river for every test seed; gamma 3 | 3, pi 3 | 2, orbit 2 | 2, rho 4 | 3 (a seat).
  A mill town's works stay on the rail band by the river.
- **Downtown reads as one**: every core block's shops turn its corners and run down the side streets (the street wall on
  the cross streets too); the civic block is the **town square**: paved from Main Street to a strip short of the back
  street, the post office and the clock tower on its front corners, the town hall across its back (always, in the planned
  town), a fountain (a wide basin) in the middle, benches, two rows of trees in iron grates down the middle and one down
  each side, planters, lamps. The core's blocks are paved to their streets (street.json `graph.paved`: the back lots
  asphalt, the square's block in its stone); greenery keeps out of them.
- **The start**: the player stands on Main Street a block down from the square, facing it (street.json `spawn.dir`), so
  the start view looks along Main Street between its street walls to the square (`OpenWalk.spawn`; the camera starts
  behind the player as before).
- **Memory** (townMemoryCheck, the shipped gamma, three runs): held peak 144-164 MB of 256 (was 249-303 measured the same
  way before this round). See TOWN.md "Memory, the final round".

### The intake models in the capital (2026-11-22, branch wf-intake)

Fifteen hand-made models went into gamma, the capital and test city (LANDMARKS_GLB.md "The intake models"; docs/queue/INTAKE.md). Rules and ledger:

- **Who gets what** (src/town/glbSites.ts `glbTenures`): the capital has every model (`isCapital`, `CAPITAL_SEED` = gamma). The ordinary ones have size rules for other towns
  (library 3,000 inhabitants at the peak, bank 2,000, drugstore, brick church and carhop 1,500 / 2,500, a farm town's country church 70%) behind `INTAKE_FOR_OTHERS`, **off**:
  turning them on moved other towns' lots (measured on eta: the shipped v1 crossroads `street.json` changes, a bait shop gives way) and would mean re-exporting and re-making the
  buildings of every town with shipped assets. The school campus, the fire station and the post office replace the sprite landmarks of the same names (`REPLACED_LANDMARKS`: the
  capital's plan has no sprite school, fire station or post office; the other towns keep theirs), and the roadside and monument models are the capital's alone until other hubs are authored.
- **Years in gamma** (from the seed): country church 1887, library 1906 (the 1970s wing 1981), brick church 1908, fire station 1924 (the 1988 station 1986), drugstore 1927 (retail
  1979, shut 2006, a cafe from 2014), Great Meridian 1928 (wartime 1944, worn 1980, restored 2001), school 1930 (1957, 1975, 2002), bank 1930 (drive-through 1977), lodge 1932, arch 1933,
  Night Keeper 1936, post office 1938 (the 1988 branch 1984), Eyrie 1939, carhop 1956 (shut 1979, restored as a diner 2002), Rex 1968, rest area 1975. The plot's before-use is a field
  (shops for the drugstore and bank, woods in the old town).
- **The grounds** (src/town/plat.ts `GROUNDS`): the Eyrie is 160 x 77 tiles, the campus 188 x 77, the Great Meridian 97 x 58: none fits a 40 x 37 block, and the one big block of the plan
  (the edge column, 148 x 123) holds the superstore. So the capital's plat has one more column, **grounds** (a new district, platted 1885), 200 tiles wide, past the highway's far end:
  Main Street runs on through it, a street closes it on the town side, and four blocks lie on either side of Main Street (north of it in the abstract frame, 62 and 80 tiles deep; south of it
  30 and 80): the Great Meridian, the rest area and the lodge along Main Street, the Eyrie behind them; the Night Keeper, the arch (at the highway's far end) and the Rex along Main Street's
  other side, the campus behind them. Nothing else in the plat moved except that the plan is 200 tiles wider (the canvas 965 x 307; the civic block is at x 779 where it was at 579). Other
  towns have no grounds column. A `future:landmark:3` plot (30 x 20) is reserved there as well: five reserved plots are free in the capital (`future:civic:1`, `:2`, `future:landmark:1`, `:2`,
  `:3`). The brief's reserved plots (24 x 18, 20 x 16, 30 x 20, 40 x 24 tiles) were too small for the big models; the grounds are the reserved landmark ground now.
- **Civic**: the post office (22 x 12) and the fire station (23 x 13) face the square from the blocks behind the civic block (one street back, the post office first: a front corner of the
  square is 10 tiles wide, a post office 22), the library (19 x 18) in the old town behind them; the clock tower still flanks the plaza. The campus is not at the old town's edge as the table
  says: it needs acres, so it is on the grounds, the far end of the highway.
- **Downtown and old town**: the drugstore (13 x 14) at a corner of the core's north side, the bank (22 x 12) mid-block on its south side; the brick church (38 x 27) on an old-town corner;
  the country church (26 x 16) at the north-west edge of the late subdivisions. **Strip**: the carhop (36 x 17) beside the others.
- **The ledger**: `glb:<model>` plots with chains of uses (`glbPlotOf` strips the state suffix); the `landmark:post|fire|school` plots are not reserved when the model is (the plan's
  landmarks lack them). The plan's tests (every tenure has its plot, nothing overlaps, every plot on a street) cover all of them; the start view along Main Street (the spawn test)
  now has only the clock tower beside the plaza in the capital.
- **Export**: gamma's `street.json` (321 shops, 25 hand-made models), buildings, dialogue, interiors, rail.json and the Kotlin fixtures (`town-gamma.json`, `rail-checks.json`,
  `stores-gamma-years.json`) are re-exported; the plan's shift moved every lot, so the building files changed (2,197 files).

### The third intake in the capital (2026-11-23)

Three more models went into gamma (LANDMARKS_GLB.md "The third intake"; docs/queue/INTAKE.md C001-C003), each on a plot of the ledger (`glb:five_and_dime`, `glb:grain_elevator`, `glb:baseball_park`; `glbPlotOf`
strips the state suffix), the capital's alone like the monuments (`INTAKE_STATES`; the other towns keep their ledgers):

- **The five and dime** (`glb:five_and_dime`, 9 x 15 tiles, chain `shops` before 1928): mid-block in the street wall of Main Street's core, north side, in the second block out from the civic block (col 3, x 745; the
  first block, col 1, is the start view's wall and keeps its shops: the spawn test needs two). Reserved with pad 0 (its brick party walls meet the shops either side) before the grounds' reserved plot, so the
  ledger's last entries are still the `future:*` plots. Years in gamma: Marlow Dime 1928, Marlow Variety 1970, Dollar Cove 2000, for lease from 2020 (the vacancy drawn 2010-20); at 2025 the shop stands empty.
- **The grain elevator** (`glb:grain_elevator`, 16 x 58 tiles, fronting the town side of the rail column): its 1950 state is 71 m (58 tiles) of bins along the track and the rail column's blocks are 36-42
  tiles long with no street between them, so the two blocks at the column's far end (rows 1 and 2, no street between) are made **one block of 82 tiles** (plat.ts, the capital only, as the edge's big block is made).
  The elevator sits at its depot end (`near`), the feed mill and the works where they were. Years: timber 1933 (the first opening drawn 1925-35, never before the town's founding), concrete bins 1950, the co-op 1990.
  **Its siding** (rail.ts, the capital only): a second siding, `kind: 'siding'`, laid along the plot's back between the plot and the main track (3.5 tiles off the plot, 4.5 off the track), leaving the main track
  by an 8-tile lead towards the depot; it is checked like every track (clear of lots, streets and the plot's own clearance, else not laid). The model's `rail_siding` reference (z -28.5, x -27..47) is its rear edge.
- **The ballpark** (`glb:baseball_park`, 117 x 74 tiles, chain `field` before 1938): a **third south block of the grounds column** (80 tiles deep, `GROUNDS.south` is now [30, 80, 80]), behind the school: the grounds were
  full (the Eyrie leaves 36 tiles of its block, the campus 8, the rest 5 or 6) and no block of the plan is 117 x 74 but the edge's, which holds the superstore. It fronts the street between it and the campus, so its
  gravel lot (the gate end) is on that street; the grounds' reserved landmark plot is untouched. Years: the wooden park 1938 (drawn 1935-40), lights 1950, steel bleachers 1960, the renovation 2000.
- **The plan grew**: the ballpark's block stands past the plan's old top, so the plan is now 965 x 382 tiles (was 965 x 307), the centre (789, 219) (was (789, 144)): everything moved 75 tiles down the canvas, the
  river channel runs to the new top and bottom (plat.ts: past the grounds too), and the world's pins for the capital (src/world/gen/hubs.ts `GAMMA_PINS`, `GAMMA_PLAN_TILES`) moved with it (the highways' and rail's y +75,
  the south river pin 382) and **the world skeleton was baked again** (`content/world/alpha/skeleton.twsk.gz`; `kotlin-street/tools/bake-world.ts`, 19 s) and its Kotlin fixture re-exported (`world-fixture.ts`).
  The river pin north now stands 460 m from the hub's centre (302 m before): `WorldSkeletonTest` looks for the river within 0.6 cells of the centre (was 0.3).
- **Export**: gamma's `street.json` (322 shops, 28 hand-made models), buildings, dialogue, interiors, rail.json (two sidings now) and the fixtures (`town-gamma.json`, `stores-gamma-years.json`, `rail-checks.json`
  unchanged) re-exported with `tools/build-town.sh gamma 2025` and the exporters; the buildings' files are the same count (2,197) with new pictures.
- **Left**: five reserved plots are still free (`future:civic:1`, `:2`, `future:landmark:1`, `:2`, `:3`); the siding is drawn from the track data only (no loading spout or yard: the model draws none).
