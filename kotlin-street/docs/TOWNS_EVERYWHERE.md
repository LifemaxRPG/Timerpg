# Towns everywhere: a real town for every place on the planet

The planet (WORLD_GEN.md) has 5,100 places on `alpha`: 21 hubs besides the capital, 64 major towns, 398 towns, 789
villages and 3,827 hamlets (alpine villages, pass hamlets and mining towns among them). Until now only the capital
(`gamma`) was a walkable town; the rest were blocks in the far view. This is the **offline pipeline that generates a
real town for every place**, packed one file per town with a manifest, so the app can stream a town from storage when
the player comes near (the streaming itself is the next step).

Status (2026-11-23): the pipeline runs end to end. A 31-town sample of every rank on two continents is baked and
rendered; the whole planet has not been baked (see "Bake everything" and "Size").

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
    [--sample 30 | --places t:12,m:40 | --all] [--keep] [--resume] [--fresh]
npx tsx kotlin-street/tools/towns-bake.ts --unpack <out>/towns/<id>.ttwn <dir>
```

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

Reading: the places are close together (a place of some kind every 5-6 km), but **villages and towns are far apart by
road**: their nearest same-rank neighbour is 15-24 km away in a straight line and 64-76 km by road, because the county
roads link each place towards the network's trunk rather than to its neighbours. That is the world's road network, not
the towns; worth a pass of "neighbour roads" in roads.ts before the density is judged.

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

## Streaming plan (the next step, not built)

1. The app ships `manifest.json` (about 2 MB) and the packs (or the JSON packs plus on-device pictures, above).
2. When the player comes within ~3 km of a town's `bounds`, its pack is read and inflated on a worker, its pictures
   composited or read from the cache; within ~1 km the town is built as gamma is today (`FilamentTown`, ground first,
   buildings nearest first in 4 ms batches); past ~5 km it is dropped back to its far-view blocks.
3. The town's frame (`frame`: turn, plan anchor, world anchor) places it on the planet as `HubFrame` places gamma; the
   render origin is the town's own while the player is in it (W4's rules).
4. The far view cuts the world's roads at each town's ground edge (its `entries` are exactly where its roads take over).
5. A hand-made town's row points at its folder instead of a pack.

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

## Problems (honest)

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
