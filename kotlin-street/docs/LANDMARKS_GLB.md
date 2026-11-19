# Hand-made landmark models (.glb) in the Filament town

Landmarks modelled by hand to the contract in `docs/CHATGPT_MODEL_GUIDE.md` (glTF 2.0 binary, metres, Y up, entrance
+Z, materials named by role, door nodes, a collision-only mesh, an optional `lot` group) are read and built by the
engine like the code-built cinema and stores, and drawn through the same lit material. Status (2026-11-19): the path
works for twelve models on the desktop preview; the town does not place them yet (that is a later job, see the end).

## The models (app/src/main/assets/landmarks/)

| kind | model | era (design, sample year) | lot (m) | building (m) | triangles | from |
|---|---|---|---|---|---:|---|
| `landmark_glb_modern_gas_station` | Crossfield Fuel & Market: canopy over four pumps, convenience store, price pylon | 2005-2040, 2005 | 32.3 x 35.1 | 17.4 x 8.7 | 2,964 | `codex/modern-gas-station` |
| `landmark_glb_main_street_service_station` | Main Street Service: 1950s-60s full-service station, office, two bays, two pumps | 1950-1969, 1953 | 23.3 x 21.7 | 15.5 x 8.1 | 2,372 | `codex/main-street-service-station` |
| `landmark_glb_junction_diner` | Junction Diner: rounded teal-and-cream enamel diner, brick kitchen | 1940-1965, 1948 | 17.0 x 21.0 | 12.0 x 12.3 | 7,639 | `codex/junction-diner-prototype`, re-skinned here |
| `landmark_glb_starling_lanes` | Starling Lanes: 1960s eight-lane bowling alley, 80 pins | 1960-1969, 1964 | 34.8 x 42.2 | 22.3 x 31.0 | 11,936 | `codex/starling-lanes` |
| `landmark_glb_gazette_office` | Gazette Office & Print Shop: two-storey brick newspaper office, sawtooth print wing behind | 1905-1940, 1925 (stands 1950+) | 13.7 x 19.1 | 9.9 x 14.3 | 3,066 | `codex/gazette-office` |
| `landmark_glb_feed_mill` | Hearthside Feed & Grain: timber elevator (20 m), feed store, two grain bins | 1920-1960, 1938 (stands 1950+) | 29.8 x 19.9 | 26.2 x 16.7 | 6,119 | `codex/feed-mill` |
| `landmark_glb_mirage_nightclub` | Club Mirage: 1980s synthwave club, neon marquee, loading dock | 1980-1989, 1985 | 27.3 x 29.8 | 18.6 x 17.4 | 2,960 | `codex/mirage-nightclub`, touched up here |
| `landmark_glb_pulse_nightclub` | Club Pulse: 2020s louvre-and-LED club with a sky lounge | 2020-2029, 2024 | 27.3 x 29.8 | 18.6 x 17.4 | 2,960 | `codex/pulse-nightclub`, touched up here |
| `landmark_glb_starlight_drive_in` | Starlight Drive-In: concession pavilion, widescreen tower, fenced tiered car lot | 1950-1966, 1956 | 34.8 x 42.2 | 16.1 x 7.4 | 4,298 | `codex/starlight-drive-in` |
| `landmark_glb_water_tower` | Water Tower: four-legged riveted steel tower, 26 m, fenced compound | 1920-1965, 1940 (stands 1940+) | 12.7 x 12.7 | 12.7 x 12.7 | 9,210 | `codex/water-tower` |
| `landmark_glb_roadside_motel` | Sundown Motel: L of twelve rooms round a parking court, pool, neon pylon | 1950-1969, 1957 | 47.2 x 33.5 | 36.0 x 26.1 | 9,970 | `codex/roadside-motel` |
| `landmark_glb_premier_railroad_depot` | Union Depot: brick-and-timber depot, clock tower (15.5 m), 52 m platform behind | 1900-1935, 1915 (stands 1950+) | 52.1 x 23.6 | 34.8 x 12.4 | 3,875 | `codex/premier-railroad-depot` |

Each folder holds the `.glb` and its `model-info.json` (the collision boxes' tags are read from it). The catalogue
`landmarks/landmarks.json` lists them: kind, name, role (the business), era, lot and building in metres, the swatch
re-skins (`swatches`: material -> swatch id), the price panel (`price`) and what the see-through glass shows (`room`:
`shelves` for a shop, `hall` for a diner or bowling hall). `src/town/glbLandmarks.ts` is the generator's copy (kinds,
eras, lots, the plot in tiles, an opening year from the seed, the gas price table); its test keeps the two in step.
The era is the design's; a model may stand long after (or be reused, or the business move) as the simulation says.
The branches were not merged: the files were copied with `git show`.

## The loader (shared/src/filament/kotlin/scene3d/)

- `GlbFile.kt`: a small reader for the subset these files use (nodes with TRS or matrix, triangle meshes with float
  POSITION/NORMAL/TEXCOORD_0 and 8/16/32-bit indices, materials with base colour texture/factor, emissive, alpha mode,
  extras, embedded PNGs) and `Png`, a pure-Kotlin PNG decoder (zlib from java.util.zip). Filament's gltfio is not used:
  the desktop's headless Filament has no gltfio (only `filament-java.jar`), and the town wants the parts in its own lit
  material, merged by role, with the collision mesh turned into walk shapes; the same code runs on the phone (on a
  worker) and the desktop. Checked against ImageIO on every embedded picture (LandmarkGlbTest).
- `LandmarkKit.build(glb, spec, year, seed, keepLot, hide, info)` gives a `LandmarkModel`: a `CinemaModel` (so
  `CinemaLayer` / `CinemaLights` draw and light it as the cinema and the stores), the walk's boxes, the doors, the lot's
  front edge. What it does:
  - **Scale and frame:** metres x `UNITS_PER_METRE` (= TEXEL / 0.0388 = 21.17: the guide's texel is exactly the
    buildings' texel); glTF's frame is the model frame (x along the street as seen from it, y up, z out to the road),
    so the +Z entrance faces the road. The street's mirrored world (docs/INTERIORS_3D.md) is the slot frame's business
    (`at`'s along/out vectors), as for the stores; checked on the renders (the signs read the right way round, the
    office of the 1950s station stands left as in the Blender renders).
  - **Ground lift:** the guide's models lay their forecourts below their zero (asphalt tops at -7.8 cm and -15.5 cm, a
    kerb's height under the paving), which would sink under the town's ground at 0; the whole model is lifted until
    its lowest broad up-facing ground (10 m2 or more) stands 0.3 units up (the stations by 1.95 units, 9 cm).
  - **Materials by role:** merged into one part per material (12-13 draws a model; the pins, pumps and props merge).
    Pictures nearest-sampled and repeat-wrapped (`tiled`), the engine's mips (as every building). `glass_*` -> lit
    windows (`Role.WINDOWS`: the cinema's warm lobby glass from nightfall, the sky in the mask's glass channel by day).
    Our material does not blend, so see-through texels are made solid over a painted room (shelves of goods for a shop,
    a lit wall, booths and lamps for a hall), drawn in whole art pixels; the lamp lights it over its own colours, so a
    lit window shows its shelves. `glow_*` / `sign_*` -> signs lit from dusk in their own colours where their emissive
    picture is bright (luminance over 150 of 255: letters and lamps, not their dark or red backing).
  - **Light:** each lit ceiling (a glow face looking down 2.8 m or more up: the canopy's panels) gets a real spot light
    and a pool on the ground under it: a copy of the ground's own texels at their own places (one atlas for all pools)
    laid a hair above it, glowing over a dithered disc in whole art pixels, so by day it cannot be told from the ground
    (the stores' lots do the same with their lamps). Only outdoor `ground_*` takes pools.
  - **Collision:** the `collision` node (or any node with extras `collision_only` / `render:false`) is never drawn. Its
    boxes come from model-info.json (`boxes_blender_z_up`, converted: Blender (x, y, z) is glTF (x, z, -y), sizes
    (x, z, y)), else from the mesh's lumps. A box wholly under a step (14 units) or over a head (34) is left out (the lane
    beds, floors, ceilings); a `lot` box goes with the lot, a box `attachment`-ed to a hidden node with it. Added to them:
    what the drawn parts put in a walker's way (cut at 16, 25 and 34 units as `StoreSolids` cuts the stores, not filled,
    glass, ground, floors and roofs left out), because the guide's models box a pump's foot, not the pump. Doorways stay
    open as the models give them.
  - **Doors:** every empty node named `door_*` (not `door_panel_*` groups), at its place, facing its node's +Z
    (door_back faces -z, the bowling alley's door_side -x).
  - **Price panel:** `PriceBoard` draws the `sign_price` material's picture for the town's year, at the art's pixel scale
    (each art pixel 2 x 2 texels): the paper, the ink, rows at the art's y, right-aligned 5x7 digits (narrow 3x7 past four
    characters) or the hand-lettered board's 3x5. `GasPrices`: the US average for regular by year (a simple table,
    1935-2025, eased between its years, 3% a year after), plus/premium and diesel from it, a town's few cents either way,
    the 9/10 of a cent; below a dollar the signs spell cents and tenths ("29.9"), as the fifties' did. The panel glows
    where its ink is. Nothing is drawn before the station opens: a site without its landmark has no model.
  - **Lot:** `keepLot = false` drops the `lot` group and its boxes; `hide` hides more nodes (the 1950s station's
    repair-only reuse hides `pump_01`, `pump_02`, `roadside_price_board`, `roadside_fuel_sign`).
- `TownLandmark.kt`: the town's hook, as `TownCinema` / `TownStore`: built on a town worker from the .glb bytes, packed in
  its slot's frame with the lot's front edge on the row's building line, uploaded on the engine's thread into its own
  culled `PropLayer`, lit each frame, its boxes and doors into `TownCollision.setExtra` / `setExtraDoors` (key
  `landmark:<sprite>`). Nothing creates one yet.
- `PropLayer`: a repeating picture's anisotropy now follows the layer's own (at most 8, as before), so a close-up preview
  can turn it off for every picture.

## The diner's re-skin (tools/landmarks/reskin_diner.py)

The Junction Diner came before the guide: flat-colour pictures on arbitrary UVs, 27 materials named by look, a
presentation road strip, the origin 4 m behind the front, a furnished interior, no doors, no collision, no lot group.
`python3 tools/landmarks/reskin_diner.py ORIGINAL.glb tools/swatches/atlas app/src/main/assets/landmarks/junction-diner`
(the original: `git show origin/codex/junction-diner-prototype:prototypes/junction-diner/export/junction-diner.glb`) writes
a guide-conforming .glb and model-info.json:

- origin moved to the front edge's middle; the road and the kerb and lane lines past the sidewalk dropped;
- the interior emptied to its shell (checker floor, walls, partition, ceiling): booths, tables, counter, stools, cups,
  pendant lamps and the menu board go;
- materials renamed by role and re-pictured on the 2 x 2 texel art grid at 0.0388 m a texel, UVs laid anew by each
  face's direction: our swatches for the brick (`brick_red`), the roof (`ballast`: tar and gravel), the sidewalk
  (`pavement`) and planks; painted in the LimeZu palette with outlines, lit edges, stepped dithered shade and chips:
  ribbed teal enamel panels, cream porcelain, brushed aluminium, the outline trim, black-and-cream checker linoleum
  (31 cm tiles), glass, lamps, coral neon, ochre, copper, soil, leaves;
- the signs repainted at the art's size in the original diner's 5x7 lettering (pixel_font.py): JUNCTION, the DINER
  blade (letters fitted to its height), the canopy strap (now 0.70 m deep: at 0.34 m no 7-row letter fits, and
  "COFFEE PIE MEALS": the old line is wider than the strap at the art's size), OPEN LATE in the 3x5 letters on a
  widened glass panel, and a pie poster (no lettering fits 8 art pixels);
- door_main at the front step, door_back at the kitchen step (found from the raised sidewalk pieces);
- collision boxes read off the walls and the lot's things (benches, planters, bins, crates) between knee and chest
  height; `structure` and `lot` groups (lot: what stands outside the walls below 2.4 m, and all ground).

Left: 23 materials (the guide says about 12: the small props' flat pictures could share one atlas); the rounded front
corner is boxed in steps; the bike, bench and planters are the original's geometry.

## The second eight (2026-11-19): what the loader learned

All eight built without a decoder gap (no extension, float attributes, 8/16/32-bit indices, embedded PNGs; 9-13 parts
each, 9-10 ms to 150 ms to build). Changes:

- **Water** (`water_*`, the motel's pool): the pool lies below the model's zero (-0.155 m) with a hole cut in the lot's
  paving, so the town's grass in the hole hid it, and its picture is see-through. It is now drawn solid over deep water
  and laid flush at `WATER_LEVEL` (0.15 units over the town's ground, under the paving's 0.3). The town must not put
  anything but flat ground in that hole (a later placement job: the lot is a rectangle, the hole is 7.4 x 4.3 m at
  x 15.5..23.0, z -8.1..-3.7 m).
- **Swatch re-skins** (landmarks.json): `roof_membrane` -> `ballast` (gazette, both clubs), `roof_built_up` -> `ballast`
  (motel), the Mirage's `ground_asphalt` -> `asphalt`. Every other picture is already painted with outlines and shading
  (the area-weighted check of UV density found 0% of any model's surface off 0.0388 m a texel +-6%, and every picture
  is on the 2 x 2 texel grid), so nothing else was re-skinned.
- **Collision from the info file** works for all eight unchanged (their `boxes_blender_z_up` have centre, size, group,
  attachment). The closed door-panel boxes (`gate_node`) stay closed, as the bowling alley's. The water tower has one
  box for its fenced compound (legs, fence and gate inside it); the loader adds the drawn parts' cuts (232 boxes).
- **Preview** frames a tall model (taller than 1.5 x its lot) from further out and aims at its middle.
- **Test**: the height range is per model (the tower is 26 m), a tower has no door or glass, grounds of the new models
  are at or above 0 after the lift, doors face out along their nodes' +z, the pool is flush and solid.

### The two nightclubs (touched up here)

They were made by a different tool (build_model.py, pure Python, no Blender) and are in `tools/landmarks/mirage-nightclub/`
and `pulse-nightclub/` (make_art.py then build_model.py, paths pointed at our swatch atlas; run in the folder, the
.glb and model-info.json go to the assets). Fixed in the source: the Mirage's lounge fascia read "DANCE CL" (its
10 letters ran 3 art pixels past the plate) and its canopy plate "VIP ENT" (9 letters on a 48-pixel plate): now
"DANCE CLUB" is moved inside the frame and the plate reads "VIP". Loader side: flat membrane roofs and lot asphalt are
re-skinned (above). Checked and left: their glow is on signs, trim, the glass and a lit dance floor and stage backdrop
inside the shell (about 70 m2 of glowing floor and wall behind the glass, invisible from outside; a later interiors job
should hide it or turn it down); the lot's fence, valet plaza and car park do not sink or z-fight; texel scale is exact.
Their lot layouts are the same plot with different dressing nodes (`marquee_pylon`, `service_yard`, `vip_rope_line`
against `led_monolith`, `service_yard`, `valet_plaza`) and different buildings, so no `hide` was needed to tell them apart.

### Breaches of the guide, for the modeller (exact numbers)

| model | breach |
|---|---|
| all eight | `door_panel_*` closed boxes sit in the doorways (kept closed here); glow materials use their whole base picture as the emissive picture (only bright texels glow here) |
| gazette-office | no breach of a must-rule found. Collision boxes stop at 4.5 m (the building is 9.6 m): "use the walls" |
| feed-mill | sign_name is a blank non-emissive plate (fine); the lot is not centred on the origin (x -11.2..18.6 m) as the elevator and bins stand to the right |
| mirage-nightclub | glass_club is BLEND (handled); boxes top at 4.04 m for a 11.3 m building; "DANCE CLUB" and "VIP ENTRY" lettering overflowed (fixed in the re-export); the dance-floor glow (37 m2) and stage backdrop (31 m2) are interior |
| pulse-nightclub | glass_curtain BLEND (handled); boxes top at 4.04 m for 11.2 m; 12 materials (the limit) |
| starlight-drive-in | boxes top at 3.26 m, the screen tower is 11.5 m and its legs are not boxed to the top; 12 materials |
| water-tower | no door and no glass_ (exempt per the model: closed compound); one 12.7 x 12.7 x 26.2 m collision box (a walker cannot go under the tower or through the gate); sign_name is blank for the town's name; 9,210 triangles of which 8,796 are legs and catwalk (inside the 12,000 landmark budget, over the 5,000 ordinary one) |
| roadside-motel | `ground_asphalt` top at -0.078 m (lifted by the loader); `water_pool` at -0.155 m and the pool shell down to -1.86 m (intentional, by a terrain cutout the guide does not cover: say so in the guide); the lot is not centred on the origin (x -9.9..37.2 m, the L runs right); 9,970 triangles of which 5,838 are in `detail_utility` |
| premier-railroad-depot | `ground_asphalt` top at -0.078 m (lifted); the tracks are not in the file (platform edge 16.14 m behind the origin, along x); doors at z -1.09 and -11.33 m are recessed behind the front edge; the model is 52.1 m wide, wider than most Main Street plots |

## Where each of the twelve new and old models belongs (for the placement job)

Lot types: downtown (a Main Street plot, buildings on the sidewalk line), highway edge (a plot on the through road
at the town's edge, a parking apron in front), rail side (beside the rail line), farmland (edge of town, large lots),
civic (near the square). "Replaces" says what we already draw.

| model | towns and decades | lot type | replaces / notes |
|---|---|---|---|
| gazette-office | any town of 1,500+ people, standing from the game's start (built 1905-40) to the 2020s; a weekly paper | downtown, mid-block, 13.7 m front | nothing now (the newspaper is only a name in the simulation); needs the Gazette's facts from the simulation for the name and the PRESS blade |
| feed-mill | farm towns and small towns of the 1940s-1980s; ruined or reused later (a brewery, storage) | rail side or farmland edge, 29.8 m wide, its dock on the siding side | replaces nothing; the rail anchor `anchor_rail_dock` is for the rail job |
| mirage-nightclub | towns over ~8,000 people, 1980-1989 (stands 1990s as a closed or dated club) | highway edge or downtown corner with a side lot | one nightclub per town: Mirage in the 1980s, Pulse in the 2020s, never both |
| pulse-nightclub | towns over ~8,000 people, 2020s (opening 2020-29) | downtown edge or highway edge | same plot as Mirage; if a town has a Mirage it may be demolished for a Pulse |
| starlight-drive-in | 1950-1966 openings, standing to the 1980s; ruin or redeveloped later (the lot becomes a retail plot) | highway edge or farmland edge, 34.8 x 42.2 m | replaces the drive-in we planned to build ourselves: use this one, do not build ours; it is a movie theatre variant (not the cinema model) |
| water-tower | every town from the game's start (built 1920-65), one per town, repainted to the 2020s | civic or rail side, a 12.7 m compound set back from the street, never between buildings | replaces nothing; the name panel is blank for the town's name |
| roadside-motel | towns on a through road, 1950-1969, standing until redeveloped (an extended-stay in the 2000s) | highway edge, 47.2 x 33.5 m, the L open to the parking court | pair with the 1950s station and the diner for a highway strip; the pool needs a flat hole in the ground |
| premier-railroad-depot | towns on a rail line, built 1900-35, standing from 1950 (passenger service ends in the 1960s-70s in the simulation: then closed, a museum or a restaurant) | rail side, 52.1 m wide, front on the street, platform behind along the track | replaces our own procedural rail depot (the 52 m platform and clock tower are better; keep the procedural one only as the small-halt variant for towns under 3,000); needs the rail job for tracks |
| modern-gas-station, main-street-service-station, junction-diner, starling-lanes | as the table above | as placed by the earlier plan | unchanged |

## Preview and tests

- `gradle :desktop:preview -Dmode=filament-landmarks [-Dlandmarks=diner,gas] [-Dyear=2008]`: each model on its lot by
  the road with one of our pack stores beside it (its front on the same line) and two people for scale, from the front,
  three-quarters, behind, above and a person's height (eyes 1.6 m, placed where nothing stands between them and the
  main door) at 13:00, and the front, three-quarters and person's height at dusk (19.3) and night (22.5); a collision
  plan per model (`*_collision.png`: boxes red, doors blue, the road at the bottom); `landmarks.txt` with parts,
  triangles, lights, pictures, build time, footprint, boxes, doors and materials. Close-ups use anisotropy 1 (the
  desktop's software GL blurs a magnified picture with it). These are desktop renders on software GL, not S25 captures.
- `desktop/src/filamentTest/kotlin/LandmarkGlbTest.kt` (crossroadsTest): the PNG decoder matches ImageIO on every
  picture; every model builds to its lot's size and a real height, its collision mesh undrawn, glass and signs lit by
  role, door_main facing the road and not inside a box; dropping the lot drops its things and boxes; the price panel
  changes with the year and stays on the art grid. `src/town/glbLandmarks.test.ts`: the catalogue and the generator
  agree; opening years within the design; the gas table matches the engine's.

Measured on the build server (desktop, not the S25): building a model takes 50-350 ms on one core; pictures on the heap
while building 0.2-3.2 MiB (the gas station's 512 x 512 sign atlas and 256 x 256 glass and utility atlases dominate).

## What the files break or leave open (for the next models)

- **Ground below zero:** all three guide-made models put their lot's asphalt top under y 0 (the stations -0.078 m, the
  bowling alley -0.155 m). The loader lifts them; the guide now says the lot's ground must stand at 0 or above.
- **Collision too thin for props:** the stations box their pumps' feet and plinths (0.16-0.31 m tall), not the pumps
  (1.6-2 m), so a walker went through them; the loader adds the drawn parts' cuts. The guide now asks for a box per
  thing a walker must go round, from the ground to its top.
- **Boxes touching at corners:** the collision mesh's boxes share corner vertices where walls meet, so the mesh alone
  cannot be split back into boxes; the loader reads the boxes from model-info.json (centre, size, group, attachment).
  Keep that list complete and in step with the mesh.
- **Glass:** the models' glass is BLEND with see-through texels; the town draws no transparency, so the panes are made
  solid over a painted room. Fine as a contract (the guide says the game lights glass), noted for the interiors later.
- **Emissive pictures:** the glow materials use their whole base picture as emissive; only the bright texels glow here.
- **The diner** (before the guide): see its re-skin above.
- Not used yet: the 1950s station's `garage_door_open` / `garage_door_closed` swap and `history_handoff`, the bowling
  alley's `door_panel_*` gate boxes (kept closed) and `bowling_equipment` (kept), the `gameplay_prop` extras.

## Placing them in towns (later)

The town generator decides where and when (a plot of `glbLandmarkTiles` on a fitting street, its year from the
simulation's business history), exports it as a landmark slot in street.json, and `FilamentTown` makes a `TownLandmark`
for it with the asset's bytes, as it does `TownStore`. The street props' rules should keep lamps and benches off its
front (as the cinema's). The Main Street station's handoff to the highway station is the simulation's to record.
