# The railroad

Railroads join the towns of the endless world; to go far, the player can take the train. Everything is a pure function
of the world seed and the year, so a town an NPC names is a real place the planner finds and the train goes to.

| Part | Where |
|---|---|
| The network, history by year, stations, timetables, fares, the route planner | `src/world/rail.ts` (tests `rail.test.ts`) |
| The railroad in a town: track, level crossings, depot, siding, elevator site | `src/town/rail.ts` (tests `rail.test.ts`) |
| Export for the app's town | `kotlin-street/tools/export-rail.ts` -> `app/src/main/assets/town/gamma/rail.json` |
| Network map pictures | `kotlin-street/tools/rail-map.py rail.json out.png 1955 2025` |
| Kotlin: network, planner (same answers as the TS), keyed randomness, wilderness | `shared/.../domain/RailNet.kt`, `WorldRand.kt` |
| Kotlin: the town's railroad by year, trains through the day, rides | `domain/RailScene.kt`, `domain/RailRide.kt` |
| Filament: meshes (track, crossings, depot, trains) | `scene3d/RailKit.kt` (pack pieces: `RailPackData.kt` from `tools/swatches/pack_rail.py`) |
| Filament: in the town, and the ride's world | `scene3d/TownRailDraw.kt`, `scene3d/RideLayer.kt` (hooks in `FilamentTown`) |
| App: ticket window, boarding, the ride overlay | `app/.../RailTrip.kt` (hooks in `GameActivity`, `FilamentLayer`, `TownSession`) |
| Previews | `gradle :desktop:preview -Dmode=filament-rail` (`desktop/src/filament/kotlin/FilamentRailPreview.kt`) |

## The world's network (src/world/rail.ts)

- **Sites**: the towns of `src/world/map.ts` (one per cell, names from `names.ts`, the same ones lore and NPCs use).
  `RailWorld { seed, home? }`: `home` puts a chosen town at cell 0,0 (the app's gamma, `tools/town-config.ts WORLD`:
  world `world-6`, picked so gamma's main line passes a depot and crosses the road out of town).
- **Main lines**: east-west along every even row of cells (beside the roads `linkRoad` lays between towns), and
  north-south along one column in each block of four: an endless connected grid, built 1852-1890 (never before a town
  was founded), never abandoned, freight to this day.
- **Branch lines**: east-west links on odd rows (70%), north-south spurs bringing an odd-row town with no line to the
  main line (75% of those), a few extra spurs, and a line both ways through every town called "...Junction" (on two
  lines, as its name says; tested). Built 1878-1915 (later only where a town was founded later; all before 1950).
- **History**: branch passenger trains end 1950-1969 (15% already 1934-1948); 45% of branches are lifted 3-33 years
  later, 60% of those becoming rail trails. Main-line stations shut through 1953-1969 (small towns 75%, towns 35%,
  bigger towns never), except junctions (needed for changes). From **1971 the national passenger service, "Pennant"**
  (made-up name), runs on main lines only and stops at the bigger towns, every main-line junction and some others;
  a few stops are dropped 1979-1996 and some come back 1988-2018 (commuter revivals; suburbs likelier). Before 1971 each
  line's trains are its company's ("Maple & Western Railroad", named after a town on its stretch, no real names).
- **Stations** (`stationAt`): `none` (no line yet / never), `passenger`, `freight` (track, depot shut to passengers),
  `gone` (rails lifted). A pre-1971 branch station is open only if a passenger path reaches a main line, so every open
  station is on the connected passenger network of its year. `stationEvents` gives a town's railroad history in words,
  `railFact` one line for an NPC's fact sheet (not wired into the fact sheets yet).
- **Geometry**: each town has a line point per direction (its centre, off to one side); a link's alignment is a smooth
  S between the two (`alignment`), its length the track's length (`linkLength`). 1 tile = 2.1 m in the 3D town
  (`METERS_PER_TILE`), so neighbouring towns are about 1.1 km apart by track.
- **Timetables** (`departures`): trains a day each way by era (main 6 / 5 / 3 / 2 national / 3 modern; branch 3 / 2 / 1),
  5:30 to 22:30, at the era's speed with stops (main 45 / 60 / 75 / 95 km/h; branches 70% of it). A train leaving one
  station is exactly the departure at the next one a link's running time later (each line has one clock along its whole
  length, `alongLine`; tested).
- **Fares** (`fare`): a minimum and so much a km, by year (1950: $0.25 + 2c/km; 1971: $1 + 4.5c; 1990: $3 + 9c; 2025:
  $6 + 16c).
- **Route planner** (`planRoute(world, from, to, year, minute)`): ids or names (names repeat across the endless world:
  `from` is found nearest the player's cell, `to` nearest `from`). Earliest arrival over the year's passenger trains
  (time-dependent search, a change weighs 45 minutes, 5 minutes to change, changes only at open stations, riding through
  shut ones), kept to a box round the two towns. A town without passenger trains gets a road leg (bus or a lift: road km
  = 1.3 x straight, 40 km/h, 15 min wait) to or from the nearest open station within 4 cells, with a note; if no train
  connects them the answer says so and gives the road distance. A route: legs (train: line, operator, stops, the towns it
  passes; road), departure, arrival (past 1440: next day), changes, km, fare, notes.
- **Tests**: deterministic; the grid of main lines; links agree from both ends; Junctions on two lines; dates; stations
  decline 1945 > 1965 > 1975; national era on mains only; every town of a 9 x 7 region reachable from the home town in
  1930, 1955, 1966, 1975, 2000 and 2025, by train to its own station when it has one; legs chain; **every town an NPC can
  name (its own, its legend's target: `lore.ts`) is routable both ways in four years, by name too**.

## The railroad in a town (src/town/rail.ts)

- From the finished plan (the generator is untouched: no plan or layout changes): the portals where each link's
  alignment enters the plan; a path search (8 directions on 2-tile cells; turns cost; lots, landmarks and the plaza
  kept 3 tiles off; streets crossed square, never followed; rivers bridged, never followed; drawn towards the plaza),
  straightened and rounded (Chaikin) where the ground allows.
- **Depot**: a straight 20-tile stretch with the depot's 12 x 6 tile site beside it, on clear ground within 8 tiles of
  a street, nearest the plaza; the through line is laid through the best stretch it can reach without a detour of more
  than 1.7x. A terminus gets a depot site first and the line laid to it; a second line joins the first (a junction).
- **Siding** beside the depot's track, on the far side, with a grain elevator / mill site (8 x 8) at its end (drawn
  later, by the farms work).
- **Level crossings** wherever a track runs over a street: crossbucks from the line's opening, flashing lights from
  1946-1967 (busy) or 1956-1977, gates from 1962-1986 on main lines or main streets (some others).
- `railIn(year)`: each track's state (track / bed / trail / none), each crossing's gear, the depot (open / closed /
  reused / none). Tests: no conflicts on nine towns of two worlds; every line has track from its portal; nothing over a
  lot, landmark or the plaza; the depot clear and near a street; crossings on their streets; history by year; gamma has
  its depot, a crossing on the road out of town and a siding.
- Most towns' blocks are packed edge to edge, so the line runs round the built-up area through its woodland edge (as
  the "other side of the tracks" in many real towns) and crosses the streets that run out of town; a line straight
  through the middle would mean planning the town round it (a later option, a generator change).

## In the app's town (Filament)

- `rail.json` (500 KB): the town's tracks, crossings, depot, siding, elevator site and portals in the street.json
  graph's frame (tiles x 44.5); the 15 x 15 towns round it (name, kind, rank, centre, the station's status letter for
  every year 1900-2025, and for the 5 x 5 nearest their streets, plots, landmarks and own railroad); every link
  (history, company, length, its route's points); each line's timetable phases and clock. The Kotlin planner
  (`RailNet.planRoute`) is a line-for-line port and is checked against 64 routes the TypeScript wrote
  (`desktop/src/test/resources/rail-checks.json`, `RailNetTest`); `WorldRand` / `WildChunks` grow the same keyed
  randomness and wilderness bit for bit (checked too).
- `RailScene(net, year)`: the tracks to draw with their state, each line's path through the town (in from 6500 units
  outside along its route, the town's track, out the other side), `onBed` (the forest, the woods' edge and the town's
  trees keep off the bed: `Town.keepClear`, set before the ground and the collision are made), and the trains at any
  minute: the timetable's passenger trains (braking into the depot, 90 s at the platform, pulling out; straight through
  while the station is shut) and 4 freight trains a day each way (1 on a branch), by era: steam engine and tender with
  orange coaches (to the early 1950s; branches longer), a diesel in a railroad's maroon (1953-1970), the national
  service's silver diesel with green coaches (1971-1999), a modern engine with grey coaches (2000+); freights of boxcars,
  hoppers and tank cars, a caboose until 1985, a green road diesel pair in the national and modern eras.
- `TownRailDraw` (one `FilamentTown` constructor argument `rail = RailNet`, `setRailYear(year)`): the track (the
  pack's tie-and-rail tile on a ballast bank, steel rails on it; concrete ties from 1985 on main lines; a deck girder
  bridge on piers over the river), old beds and rail trails, crossings (a timber deck, crossbucks on each road's right
  before the track, lamps flashing in turn while a train is within ~20 s, gate arms swinging down), the temporary brick
  depot (hipped slate roof on deep eaves, platform, benches, the town's name on boards; boarded up while shut to
  passengers; "TRAIL MUSEUM" / "ANTIQUES" when reused), the trains (a layer per car, stood on its two trucks along the
  path each frame; windows and headlamps lit at night). The depot and the crossing posts are solid in the open world's
  collision (`setExtra`). Trains are not solid (moving obstacles are not in the collision yet).

## Riding

- At an open depot the player gets **"Take the train"**: the ticket window (`TicketWindow`): the depot's operator; a
  search by name (any word's start; towns the player heard of first: the `heard` list, empty until the dialogue
  reports names); the destinations on the lines from here when nothing is typed; picking a town shows the route
  (`RouteCard`: legs with times, operators, the stations called at, road legs, changes, km, fare, notes). **"Board the
  8:41 train"** starts the ride.
- **The ride** (`RailRide`): the track the route's trains follow at world scale: the depot, the town's own track out,
  each link's surveyed route between towns, the towns near enough to have their plans exported on their own track to
  their depots. `FilamentTown.startRide` adds the ride's world to the town's scene: fields under the whole route, the
  track, the wilderness from the world's own seed (`WildChunks`: the same trees as `src/world/wild.ts`), the towns on
  the way as their streets and plots in low boxes with their real depots, and the train; the camera orbits beside the
  train (`ridePose`, through `TownRun.draw`), lit by the town's sky and haze. The time on screen is compressed (about
  1200 units a second, 20-75 s); the clock moves on by the timetable's time.
- **The open hook**: the app ships one town. At the end the overlay says where the player arrived and, until other
  towns ship, steps them off back at the home depot with the clock moved on. To finish: export each destination town
  (street.json, buildings, dialogue, rail.json with that town at its own cell of the same world: `RailWorld.home` is
  only for gamma), and in `RideOverlay`'s "Step off" call `TownSession.load(ctx, seed)` with the player placed at that
  town's depot (`rail.depot`, the platform side). The network, timetables and rides already work for any town.

## Hero depot model (for ChatGPT, docs/CHATGPT_MODEL_GUIDE.md)

The temporary depot is code-built (RailKit.depot). The hero model should be: a small-town combination depot of about
1900, brick (our brick swatch) on a stone base, one storey with a tall hipped slate roof on deep eaves carried by timber
brackets (eaves about 2.5 m on the track side over the platform, 1.2 m elsewhere), a bay window for the agent facing
the track (the operator's window), a passenger waiting room at one end and a freight room with a wide sliding door and
a raised timber freight dock at the other, a brick chimney, the town's name on boards at both gable ends (blank boards
the game letters: a `sign_name` material), a platform of brick pavers along the whole track side with a white edge,
benches, a baggage cart, a lamp post or two (`glow_` lamps), a trackside train-order signal on a mast. About 15 x 6 m
(footprint), 7 m to the ridge; front (+Z) facing the track, origin at the middle of the platform's track edge.
Variants: open (lit windows, `glass_`), shut (boarded windows and doors, faded paint), reused (the same with a shop or
museum sign). The back (street side) needs a door, windows and a small gravel car park. Budget up to ~12,000 triangles.

## Not yet / known issues

- Crossings at a street's very end: gamma's main line crosses the road out of town where the 3D town's road ends (the
  road on to the next town, `linkRoad`, is not drawn in the app yet).
- Trains are not solid, have no sound and no steam or smoke yet (`RailKit.puff` is made, not yet used).
- The 3D town's frame time with trains moving, and the ride's build time (trees for the whole route, built on the
  engine's thread for now), are not measured on the S25: a ride's world should be built on the town's workers first.
- NPCs do not talk about the railroad yet (`railFact`); towns named only in the TS game's lore.
- The elevator / mill site at the siding is reserved but empty (the farms work draws it).
