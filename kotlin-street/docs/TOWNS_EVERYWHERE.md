# Towns everywhere: a real town for every place on the planet

> **Resumed as planet step 2** (2026-11-26, WORLD_STREAM.md section 10): the generated towns stream in round the player
> on the phone ("Streaming", below). It was on hold from 2026-11-23 for the first playable loop (LOOP.md).

The planet (WORLD_GEN.md) has 5,100 places on `alpha`: 21 hubs besides the capital, 64 major towns, 398 towns, 789
villages and 3,827 hamlets (alpine villages, pass hamlets and mining towns among them). Until now only the capital
(`gamma`) was a walkable town; the rest were blocks in the far view. This is the **offline pipeline that generates a
real town for every place**, packed one file per town with a manifest, and the app's **streaming** of them round the
player (planet step 2: "Streaming", below).

Status (2026-11-26): the pipeline runs end to end; the app streams the baked towns in (the desktop's drive and views
tests; the phone reads them from its files when pushed there). Baked so far: the eight places of the drive from the capital
through Evanvale, Roserdale and Chaley (`tools/towns-route.ts`); the whole planet has not been baked (see "Bake
everything" and "Size").

## The recipe (place -> town)

`kotlin-street/tools/towns-site.ts` reads a place's facts from the generated world into a **Site** (plain data: the
place, its region and climate, whether a rail line stops there, its rivers, roads and rail lines within 9 km as
polylines in metres from its point, a 9 x 9-cell height grid, and Highways v2's facts for it). `baseRecipe(site)` turns
that into a **TownRecipe** (`src/town/recipe.ts`), the generator's inputs:

| recipe field | from the world | what the generator does with it |
|---|---|---|
| `rank`, `size` | place kind and rank | plat size class: H hamlet, V village, S town, M major town, L hub |
| `type` | the place's type (farm, harbor, mill, mining, resort, suburb; port -> harbor, mountain town -> mining) | the archetype (`archetypeOf`): shops, growth, wealth, the plan's emphasis |
| `setting`, `ore` | alpine / pass / mining places | a steep site is planned as a hamlet; Main Street along the contour |
| `water` | a world river within 700 m (1.5 km for mill towns), a lake cell beside it, else none | the plan's river column or lake |
| `rail` | a rail line has a station here | the rail band (parallel plan) or rail column (T plan); none otherwise |
| `countySeat` | hubs, major towns, towns with no bigger place within 40 km | the courthouse square |
| `founded` | the place's founding year | `foundedOf` |
| `people` | by rank (hamlet 4 families, village 11, town 22, major 30, hub 38) | founding households, businesses, growth |
| `bypassed`, `interchange` | Highways v2's `towns` record | Main Street's trade declines after a bypass; the strip grows towards the interchange |
| `entries` | where each world road and rail line crosses the town's ground edge | a country road in at exactly that point |

The existing generator was extended with parameters, not replaced: every function that took a seed looks up
`recipeOf(seed)`; without a recipe a seed plans exactly as before (gamma and every test seed are unchanged).

What changes with a recipe:
- **Size by rank** (`src/town/plat.ts`): a hamlet (H) is a crossroads, Main Street (the road through) and one cross
  street, houses fronting both, fields behind, no back lanes; a village (V) has one core block each side of the civic
  block, one strip block and two rows north; towns S, M and L as before.
- **A reason to exist and orientation** (`placeTown`): a river town is laid with its river column on the world's
  river, turned to the river's own way through the town; a **railroad town is laid along its railroad**: the plan's
  rail band (or a T plan's rail column) on the world's line through the station, turned to the line's way through the
  town; otherwise the town stands on its main road (a hamlet up to 3 km off its cell's middle, a town 1.5 km) with
  Main Street along it. A mountain place on a slope over 6% turns Main Street along the contour.
- **The strip** faces the busiest road out, or, with an interchange within 4 km, the interchange (and is one block
  longer there: motels, gas stations, fast food).
- **Bypass decline** (`tradeOf` in recipe.ts, read by `src/sim/simulate.ts`): a bypassed Main Street loses its passing
  trade over 15 years after the bypass opens (to 0.6), so fewer businesses are supported and more fail; the empty shops
  stand as vacant lots in later years. An interchange adds up to 0.3 over 10 years.
- **Off the water**: the town is moved up to 1.8 km (along its river or rail line when it has one) where its built area
  would stand on another river, a lake or the sea.
- **People**: a hamlet that outgrows its homes doubles households up rather than failing (`properties.ts`).

## Roads in

`entriesOf` finds every crossing of the world's roads (Highways v2 segments as they stand in the bake year, 2025) and
rail lines with the town's ground edge (the plan plus the 100-tile farm ring). `planFarms` (`src/town/farms.ts`) then
lays a country road in at **exactly** each crossing (to a tenth of a tile): where one of the town's own country roads
leaves the section road within 40 tiles, that road ends at the section road and the road in leaves the section road at
the world's point (the jog of a surveyed land's section lines); otherwise a new road runs in from the section road.
Expressways are limited access: their crossings are recorded but no town road joins them. The world's roads therefore
never bend to the town: the far view cuts them at the ground's edge exactly where the town's roads take over.

## The baker

`kotlin-street/tools/towns-bake.ts`:

```
npx tsx kotlin-street/tools/towns-bake.ts [--world alpha] [--out kotlin-street/build-towns/<world>] [--jobs N]
    [--sample 30 | --places t:12,m:40 | --all] [--keep | --keep-only id,id] [--resume] [--fresh] [--measure] [--list]
npx tsx kotlin-street/tools/towns-bake.ts --unpack <out>/towns/<id>.ttwn <dir>
npx tsx kotlin-street/tools/towns-route.ts [--to Evanvale] [--more 2] [--km 4]   # the places a drive passes, as a --places list
```

(From the unmerged branch of 2026-11-23, brought in on 2026-11-26: `--list` prints the chosen places and their facts
without baking; `--measure` makes and weighs each pack without writing it, for measuring a bake bigger than the disk;
`--keep-only` keeps just those towns unpacked; a town's whole work folder now goes once it is packed.)

The main process generates the world once (~50 s), writes the sites (cached in `<out>/sites.json.gz`) and runs a pool
of worker processes (one per town, `--jobs` at a time). A worker plans the town, places it, sets its entries, and
exports it in the formats the app loads for gamma: `street.json` v2 (graph, lots, yards, farms), `dialogue.json`,
`interiors.json`, the buildings' pictures (`make_buildings.py`), plus its `recipe.json`; then packs them.

**Pack format `.ttwn`**: `"TTWN"`, version byte, header length (u32 LE), header (JSON: id and files with offset, size,
raw size and encoding), then the files in path order. JSON files are raw-deflated (level 9; `java.util.zip.Inflater`
with nowrap reads them), PNGs stored. Same files, same bytes.

**Manifest** `<out>/manifest.json`: every place (`version`, `world`, `pack`, counts, `towns`): id, name, rank, type,
setting, seed, the place's point `at`, and for a baked town its `frame` (turn, plan anchor, world anchor), its ground in
plan tiles, its world `bounds` (an axis-aligned box in atlas cell units), built size, water, river, rail, county seat,
height, entries, counts, file and bytes, times, and problems. `summary.json` has time and size by rank.

## Hand-made towns replace generated ones

`kotlin-street/tools/towns-handmade.json` maps a place id to a hand-made town's folder and seed:

```json
{ "h:capital": { "dir": "kotlin-street/app/src/main/assets/town/gamma", "seed": "gamma", "note": "..." } }
```

Such a place is never baked; its manifest row points at the folder (`handmade`). Adding a pre-made city later is one
line. The capital's data is unchanged.

The second hand-made city, **Dove Beach** (a beach town on the second launch port's slot), is designed in CITY2.md;
its line here comes with its bake (CITY2.md, phase 2b and 7).

## Size and time (measured, desktop 4-core build server, 4 workers; not the S25)

| rank | places | baked | time per town | pack size | of it JSON | of it pictures | buildings | people | built size (m) |
|---|---|---|---|---|---|---|---|---|---|
| hamlet | 3,827 | 8 | 13 s (max 14) | 2.64 MB (max 2.94) | 0.12 MB | 2.49 MB | 45 | 28 | 480 x 598 |
| village | 789 | 10 | 26 s (max 31) | 6.53 MB (max 7.3) | 0.15 | 6.31 | 116 | 70 | 741 x 403 |
| town | 398 | 7 | 38 s (max 45) | 10.0 MB (max 13.5) | 0.22 | 9.68 | 177 | 156 | 1075 x 439 |
| major | 64 | 4 | 71 s (max 78) | 22.1 MB (max 24.9) | 0.42 | 21.4 | 362 | 227 | 1596 x 608 |
| hub | 21 | 2 | 98 s (max 100) | 29.1 MB (max 30.1) | 0.52 | 28.3 | 464 | 327 | 1639 x 696 |

The planet: **21.3 GB** if every town is baked whole (0.70 GB of it JSON, 20.3 GB pictures), **25 CPU hours** (about
6 hours on this 4-core server; a few hours on a desktop with more cores). The sample's 31 towns: 290 MB.

### Over 4 GB: the split (proposal)

The buildings' pictures are 96% of every pack, and they are made from the art the app already ships (the LimeZu packs,
cut and composited by `make_buildings.py` from each building's recipe in `street.json`). So:

1. **Bake the JSON** (street, dialogue, interiors, recipe): **0.7 GB for the planet**, about 4,400 compressed files.
2. **Composite the pictures on the phone** when a town streams in: port `make_buildings.py`'s compositing to Kotlin
   (it is cutting and layering pack pieces, a few ms a building on the S25's cores; a town's 45-464 buildings in the
   background while the player travels the last kilometres), and keep the pictures of the nearest towns in a cache
   (size is not a concern: a 2-4 GB cache holds a hundred or more towns).
3. Bake pictures whole only for the hand-made towns and, optionally, the starting continent.

A shared picture pool (same picture, stored once) saves only 24% across the sample's 31 towns, not enough on its own.

## Density (measured on alpha, `npx tsx kotlin-street/tools/towns-density.ts alpha`)

Nearest neighbour on the same landmass (km), and the drive to the nearest place of the same rank (the world's
RoadGraph, the fastest car route that year, median of up to 60 places):

| rank | count | same rank: median / p90 | any rank: median / p90 | drive 1930 | 1960 | 1990 | 2020 |
|---|---|---|---|---|---|---|---|
| hamlet | 3,827 | 5.4 / 7.5 | 4.8 / 6.5 | 8 min (6.8 km) | 7 min | 5 min | 5 min |
| village | 789 | 15.4 / 25.6 | 5.8 / 7.8 | 76 min (63.5 km) | 64 min | 50 min | 50 min |
| town | 398 | 23.6 / 37.3 | 5.8 / 7.8 | 81 min (76 km) | 67 min (74 km) | 54 min (72 km) | 54 min |
| major | 64 | 49 / 132 | 4.6 / 6.1 | 49 min (55.5 km) | 34 min | 29 min (51 km) | 29 min |
| hub | 22 | 132 / 298 | 6.8 / 7.4 | 142 min (190 km) | 118 min | 99 min (168 km) | 88 min (167 km) |

Between ranks (median drive, p90 in brackets): hub to its nearest major town 39 min (174) in 1930, 27 min (97) in 1990;
hub to nearest town 29 min (37) / 21 min (27); major to nearest town 34 (56) / 24 (40); town to nearest village 57
(164) / 41 (111); village to nearest hamlet 9 (80) / 6 (57).

**By rail** (`towns-density.ts --rail`, ported from the old branch: `planRoute` on the skeleton's network, leaving 08:00
on a Monday, between neighbouring places of the rank that both have a station; the old branch's 1965 figures):
hamlets 9 min on the train (10 door to door, 10 pairs), villages 34 (175), towns 26 (388), major towns 59 (59), hubs 196
(500). Door to door is long for villages and towns because they sit on branches with few trains.

Reading: the places are close together (a place of some kind every 5-6 km), but **villages and towns are far apart by
road**: their nearest same-rank neighbour is 15-24 km away in a straight line and 64-76 km by road, because the county
roads link each place towards the network's trunk rather than to its neighbours. That is the world's road network, not
the towns; worth a pass of "neighbour roads" in roads.ts before the density is judged.

**Recommendation** (the old branch's, not applied): thin the hamlets to one every 8-14 km of road, none within 6 km of
another place (about 1,700 instead of 3,827: an estimate), the nearest place 8-10 km off, about 10 minutes' drive in the
1960s; keep the towns; let county roads join each other where two towns are close. Best done before anything refers to
hamlets by id (changing the density re-places them all), and before the planet is baked.

**The setting** (`src/world/gen/density.ts`): `SPACING`, one multiplier per rank, 1 everywhere = the world as
generated (no place moved): `hub` (hub spacing), `town` (every town: major towns, towns and villages share one
spacing, then take their rank from the shares in `DENSITY.rank`), `mountain` (alpine villages, pass hamlets, mining
towns) and `hamlet` (hamlets along the roads). 2 spreads that rank twice as far apart (about a quarter as many);
0.7 about twice as many. Changing any knob changes the world, so every baked town must be rebaked.

## Highways: beltways, connectors and roadside sites (Step 0)

- **Beltways** (`highways.ts`): each of a ring's 16 anchors now stands at its own distance (0.78-1.6 times the old
  ellipse; hubs never inside their growth margin), the one nearest a smooth per-place swell that is on open, gentle
  ground clear of rivers and town cores; the loop is a Catmull-Rom spline through the anchors (one smooth curve, wider
  on one side than another, bending round what it avoids), routed on the land only where a stretch is not open. The
  radials still join it at its anchors (system interchanges).
- **Connectors** from a town's interchange to the town are routed on the land (A*, under 7%, filleted), not ruler lines.
- **Roadside sites** stand beside their roads: rest areas 110 m and weigh stations 80 m off the carriageway, truck stops,
  motels and diners in the quadrants round their exit (380, 260, 220 m out); toll plazas stay across the road.
- The `src/world` tests pass (75).

## Streaming (built: planet step 2, 2026-11-26)

The design and its numbers are in WORLD_STREAM.md section 10; in short:

1. **Bake, don't generate on the phone** (measured, decided): a town takes 14 s (a hamlet) to 58-81 s (a village or a
   town) to bake on one desktop core under load (the TypeScript generator in Node's JIT, the pictures in Python), the
   phone has neither: the generator in QuickJS (no JIT) would take minutes a town. The packs are baked offline; the app
   reads them (size is no concern). The pictures on the phone (the split, above) remain the way to a smaller planet.
2. **The manifest** (`WorldTownSet.parse`, shared/domain/WorldTowns.kt) is read when the world is: each baked town's frame,
   ground, height, file. The app looks for it in its files (`<external files>/towns/alpha/manifest.json` and
   `towns/*.ttwn`, pushed with adb for now); the desktop reads `kotlin-street/build-towns/alpha` (`-DworldTowns=<dir>`,
   `WORLD_TOWNS=0` for none).
3. **The world round each town**: the terrain is levelled to the town's height under its ground (0.6 m under its plan,
   0.05 m under its farm ring: the town draws its own ground there) and blends back to the land over 40 + 360 m; the far
   view's roads and rivers are cut at its ground's edge (its entries are where the town's country roads take over); no
   trees, fences or farmsteads of the country stand on it; its stand-in blocks on the far view lie in its own footprint
   (a far view part per town, hidden once the town's own far boxes are up).
4. **Guest towns** (`WorldTowns`, `TownGuest`): the nearest two within 3.5 km of the eye are loaded whole (pack read on a
   worker, a `FilamentTown` made in guest mode: the capital's scene, its workers, every entity under the town's root, its
   frame a turn and a move into the capital's street units) and stream as the capital does (full detail near, its far
   cells and districts out to 6 km, its lights at night as dots); built within 2.5 ms a frame; let go past 5 km, its
   destroys spread over the next frames. Its farm ring is drawn as its roads in only (the world's own fields round them).
5. **Roadside sites** (`WorldRoadside`): Highways v2's truck stops, motels, diners and rest areas, and stops along the
   highways and county roads at `roadsideAlong`'s spacing (diners, gas stations, motels by era), each wearing its landmark
   model on a levelled pad beside the road, made within 1.5 km of the eye.
6. **People**: none yet in the guest towns (lights, parked cars at their stores and lots); the hook for the loop is
   `WorldTowns.onGuest` (called with each town as it is made).

## Tests (the app's side)

`gradle :desktop:crossroadsTest --tests WorldTownsTest` (the views: the approach and arrival in Evanvale, where the road
meets it, its Main Street, from above by day and night, a village and a hamlet from the road, a town's lights from a hill,
a roadside diner; build/world-towns) and `--tests GpuWorldDriveTest` (the tour on through the towns: their GPU share
against `TownBudget.TOWNS_MB`, the whole against `TOWNS_ROUTE_MB`). Both read `kotlin-street/build-towns/alpha`: bake the
drive's towns first (`towns-route.ts`, then `towns-bake.ts --places <its list>`, about 2 minutes); `WORLD_TOWNS=0` runs
without them.

## Tests

`kotlin-street/tools/towns.test.ts` (one world generation, about 100 s): the sample covers every rank on two
continents with alpine and mining places; every world road meets a town road exactly at the ground's edge (to 0.05 tile)
and that road reaches the edge; no other river crosses a built town, no other place stands inside one, and no two
sampled towns overlap; the manifest lists every place once, the capital as its hand-made folder; the pack is
byte-identical for the same files; and a town baked twice in separate processes is byte-identical.
Run `npx vitest run src/town src/world kotlin-street/tools`.

## Preview

`gradle :desktop:preview -Dmode=filament-town -Dtown=<dir> -Dshots=fit_top,fit_oblique,fit_low,street_main_13h -Dfarms=1`
renders any town folder (unpack a pack with `--unpack`): `fit_*` are framed by the town's own size (`-Dyaw=` turns the
oblique), `-Dfarms=1` attaches its farms.

## Bake everything

```
npx tsx kotlin-street/tools/towns-bake.ts --world alpha --all --jobs $(nproc) --resume
```

Output in `kotlin-street/build-towns/alpha/` (git-ignored): 21 GB whole, or about 0.7 GB once the pictures move to the
phone. `--resume` skips towns already baked, so it can be stopped and restarted.

## The quality bar: a town you could not tell was generated

The owner's bar (2026-11-23): "You should never be able to tell it was randomly generated." (From the old branch's
review.) What the generator does, and what it cannot do yet:

- **It grows from its reason to exist** (done): the road through a place is its Main Street; a river town's Main Street
  crosses its river on the bridge downtown; a rail town's depot is on its rail band; the strip runs out towards the busiest
  road or the interchange; a hamlet is the crossroads itself. *Missing*: a mining town has no mine (no headframe, tipple or
  mill model yet), a harbor town no wharf, a pass hamlet no inn.
- **Hierarchy and era layering** (the generator's): one Main Street, side streets, alleys behind the core, cul-de-sacs in
  the late subdivisions; the core platted first, the post-war, heights and strip additions later, lots built as the
  population grows (a hamlet's unbuilt plots are fields).
- **Coherent districts** (the generator's): the square with the hall and post office, school, fire and police behind it,
  industry on the rail band, villas on the heights, a cemetery and chapel at the edge, the water tower.
- **The terrain shapes it** (*not yet*): the plan is flat and levelled to one height; contour streets and terraced alpine
  villages need a "contour" plat form (an estimate: a week of work).
- **Variety within a style** (partly): one American small-town kit for every region; the site carries the landmass, biome
  and climate for when there are cultures.
- **Skyline** (partly): water tower, grain elevator, steeples, the courthouse where a seat; *missing*: headframes, a mill
  chimney, a lighthouse.

## Problems (honest)

- **Evanvale reads sparse** (the coordinator's review of the first renders): its 190 lots are all built (221 buildings),
  but 62 of them are villas and estates on wide lots and the plat leaves lawns between; a town of the same rank as the
  capital's plat has fewer, bigger lots. Densifying is the plat's job (smaller lots, more rows, fewer villas for a
  generated town of this rank), in the shared town generator the capital uses too: not done here (the capital's look
  must not change), a recipe-only plat option is the way.
- **The approaches keep their character** (the owner liked the county road through the woods with the power line beside
  it into Evanvale): the world's roads keep their era's surfaces up to each town's edge; nothing in step 2 paves them.
- **A guest town costs ~1 MB a building** while loaded (its whole ground and every lot's yard), and **the tour leaks**
  after the third town is let go (WORLD_STREAM.md section 10: the first fix).
- **The views' villages hide**: from the road 250-300 m out, a village or hamlet is behind the orchards and woods round
  it (the country's trees stand right up to its ground's edge); a town shows from a hill or from above.
- **The roadside diner's asphalt blob**: in front of the diner the near terrain draws an asphalt patch with a ragged
  gravel rim: by its shape probably a world road's end (an interchange's stub) in the near ground's road material, not the site's lot (not yet checked).
  Not from step 2; worth a look with the roads' ends (W6).

- **The towns are grids.** The town generator lays axis-aligned streets (TOWN_PLAN.md: the street graph, rows,
  collision, rail crossings and farms all assume it). So every town reads as a planned railroad grid, the right look
  only for a rail town; an old river town or an alpine village should be organic, its streets following the contours
  and terracing the slope. Main Street now runs along the contour and steep sites are planned as hamlets, but a
  curving street needs the engine to accept curved roads first. This is the largest gap against "you should never be
  able to tell".
- **Villages look alike**: same template, the same few blocks; variety comes from the seed's jitter, the archetype and
  the land, not from different forms (a street village, a green village, a mill village round its works).
- **Many villages and towns have one road in** (the world's road network is a tree towards the trunk): Main Street is
  a dead end there. A "neighbour roads" pass in roads.ts would fix this and the long drives above.
- **The world's river is not bent to the town's**: a river town's river column follows the world river's way through
  the town, but where the world river curves it leaves the ground's edge up to ~100 m from the town's river. W4 bends
  the world river through the capital's pins; the same is needed per baked town (its river pins can go in the manifest).
- A town on steep ground is levelled (cut and fill) to one height; relief over 60 m is reported in `problems`.
- The hubs other than the capital are baked from the generator like any town, at L size; they are meant to become
  hand-made cities (one line each in towns-handmade.json).
- Pictures dominate size (above).
- **Setting barely shows**: a mining village at 2,362 m and an alpine village read like a farm village on the plain
  (no headframe, mine or terraces; the ground is levelled). The archetype changes shops and growth, not yet the
  landmarks; a mining place needs its headframe and tipple at the works, an alpine one its chapel on the rise.
- The town preview does not draw a generated town's railroad (no `rail.json` is baked yet: the exporter `export-rail.ts`
  is the capital's); the plan's rail band and depot are there.
- Renders: `fit_top`, `fit_oblique` and `street_main_13h` of a hamlet, a village, an alpine village, a mining village, a
  river town and a bypassed major town (desktop Filament renders on the build server, not S25 captures).
