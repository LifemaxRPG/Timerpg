# Hand-made landmark models (.glb) in the Filament town

Landmarks modelled by hand to the contract in `docs/CHATGPT_MODEL_GUIDE.md` (glTF 2.0 binary, metres, Y up, entrance
+Z, materials named by role, door nodes, a collision-only mesh, an optional `lot` group) are read and built by the
engine like the code-built cinema and stores, and drawn through the same lit material. Status (2026-11-19): fourteen
models (and the funeral home, "The funeral home" below, the fifteenth; the strip centre in four states, "Reel Time Video", the sixteenth to nineteenth files); the generated towns place them (branch wf-place: "In the town" below), desktop renders only, not yet on the S25. Status (2026-11-22): the intake job added fifteen more models in thirty-one files ("The intake models" below); the third intake (2026-11-23) three more in eleven states ("The third intake").

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
| `landmark_glb_coin_laundromat` | Suds & Spin Coin Laundry: 1960s butterfly roof, pylon sign, four parking bays in front | 1960-1975, 1964 | 18.6 x 21.1 | 9.9 x 11.2 | 1,898 | `codex/coin-laundromat` |
| `landmark_glb_foundry_nightclub` | The Foundry: an old sawtooth-roofed works turned club, corner light tower (11.5 m), courtyard behind a street gantry | 2010-2029, 2018 | 28.6 x 29.8 | 19.9 x 18.0 | ~3,000 | `codex/foundry-nightclub`, touched up here |

Each folder holds the `.glb` and its `model-info.json` (the collision boxes' tags are read from it). The catalogue
`landmarks/landmarks.json` lists them: kind, name, role (the business), era, lot and building in metres, the swatch
re-skins (`swatches`: material -> swatch id), the price panel (`price`) and what the see-through glass shows (`room`:
`shelves` for a shop, `hall` for a diner or bowling hall). `src/town/glbLandmarks.ts` is the generator's copy (kinds,
eras, lots, the plot in tiles, an opening year from the seed, the gas price table); its test keeps the two in step.
The era is the design's; a model may stand long after (or be reused, or the business move) as the simulation says.
The branches were not merged: the files were copied with `git show`.

## The last two (2026-11-19, branch wf-place)

- **Suds & Spin Coin Laundry** (`coin-laundromat/`): imported as the eight before it (`git show` of the .glb and
  model-info.json; catalogue; glbLandmarks.ts; LandmarkGlbTest). Against the guide: no must-rule broken (grounds at 0,
  doors `door_main` / `door_back` / `door_service_inside`, a `lot` group, collision boxes plus "use the walls"); 11
  materials; `roof_membrane` re-skinned to `ballast` as the others' flat roofs; its lot is off-centre (x -10.6..8.1 m: the
  pylon and the bays to the left); `glow_fluorescent` is the hall's ceiling (2.8-4.4 m up), so the loader lights it as
  ceiling spots inside the shell (cheap, unseen). Its glass is BLEND (made solid over the painted room, as the others).
  **The optional machine kit** (`laundry-fixtures.glb` + `fixtures-info.json`: six washers, four dryers, a change machine,
  their own colliders; the shell's `machine_slots` say where each stands) ships beside it for the interiors job; the
  town does not load it (no rooms yet).
- **The Foundry** (`foundry-nightclub/`; source in `tools/landmarks/foundry-nightclub/`: `python3 make_art.py` then
  `python3 build_model.py` in the folder, then copy the .glb and model-info.json to the assets). Made by the same tool as
  the other two clubs, given the same scrutiny: the atlas paths pointed at our swatches (the pictures came out identical);
  the entry lintel read "MAIN HALL" in 45 of its 48 pixels with the shadow over the frame: now "ENTER"; the hall's LED
  floor, light wall, bar front and truss lamps were `glow_laser` (70 m2 of glow inside a closed shell, and the truss
  lamps would have become spot lights inside): they are now `detail_led` (the same picture, not emissive), so only the
  signs, the beacon cube, the gantry, the courtyard lights and the service lamp glow (13 materials, one over the guide's
  12); `ground_asphalt` re-skinned to `asphalt` as the Mirage's. Checked and left: grounds at 0 or above, collision walls to
  the eaves (4.35 m, the sawtooth roof above), the tower box full height (11.3 m), doors main / back / side.

## The works before The Foundry (2026-11-19, branch wf-foundry)

`foundry-factory/` (source `tools/landmarks/foundry-factory/`: `./build_all.sh` paints the four name variants and writes
`foundry-factory-<name>.glb` and model-info.json to the assets): Gemini's working-factory version of The Foundry, the
same 28.56 x 29.80 m lot, shell and door nodes. Imported with the clubs' touch-ups, and the owner's notes (2026-11-19):
- **Its own name**, not "The Foundry" (the club's name came with the conversion): an ordinary period company name painted
  on the brick over the front windows as a faded ghost sign (a dark painted field worn through to the brick, cream
  letters, a few pixels flaked), one of four variants the town picks from its seed: Hargrove Iron Works, Whitcomb Foundry &
  Machine Co., Ostrander Iron Works, Pelham Foundry & Machine Co. (made-up surnames; `make_art.py` VARIANTS, glbSites.ts
  FACTORY_NAMES in the same order; the catalogue's `variants` lists the files, street.json's glbLandmarks `variant` picks).
- **Far less signage**: gone are the stack's vertical FOUNDRY board, the gantry's THE FOUNDRY header, the PLANT NO 4
  lintel plate, the IRONWORKS and SHIPPING yard plates and the lettering in the windows (CASTING, SMELTER, FOUNDRY). Left:
  the painted name, one plain unlit WORKS GATE board on the gate gantry, SHIPPING stencilled on the brick over the dock
  door. Nothing glows but the lamps (the entrance, the two gate lamps, the weigh booth, the back door, a new one over the
  dock) and, at night, the furnace-warm windows (the windows' own night light); the hall's casting pit, hearth wall,
  tool wall and truss lamps are `detail_hall` (the same picture, not emissive).
- **A smokestack**: the club's square tower with its louvred light box is replaced by a tall brick chimney narrowing to
  the top (stepped a pixel a side every 20 art pixels, 30 x 32 at its foot, 14 x 16 at the head), a stone collar, an iron
  band, a corbelled cap at 15.8 m (the club's tower is 11.5 m: what it was cut down to). Its collision box is full height.
- Kept: the tin sawtooth roof, the gate gantry, the yard's shelter, crates and weigh booth, the dock; grounds at 0 or
  above; collision walls to the eaves. 13 materials (one over the guide's 12, as the club). The README's dates are not used:
  the simulation gives them (below).

In the town (src/town/glbSites.ts): a town draws the works with the old club chance (mill and mining towns 75%, harbour
50%, others 25%). The works open 1900-45 from the seed (never before five years after the town's founding; its street
must be there by 1950, the plan's first year, or be the main street), and the plot is chosen and held from then. They
shut 1970-2005 (at least 20 years on) and stand dark; when the town has 8,000 in a year of 2010-29 at least five years
after they shut, The Foundry opens on the same plot (the works' model stands until that year: `gone` = the club's
opening). A town that never reaches 8,000 keeps its works shut. No Foundry without its works (a Pulse otherwise). gamma:
Pelham Foundry & Machine Co., 1909-1980, dark 1980-2022, The Foundry 2022, on the west edge (Larch Road side, row 18R).

## The funeral home (2026-11-19, branch wf-funeral)

`funeral-home/` (source `tools/landmarks/funeral-home/`: `source/funeral-home.glb` is the modeller's file, `python3 make_variants.py`
writes the six name variants and the .glb files to the assets; model-info.json copied with `git show`). "Willow House Funeral Home": an
early-1900s house converted, with a portico, a covered side arrival lane, a vaulted chapel wing and a rear service bay; lot 26.4 x 22.7 m,
building 21.7 x 17.7 m, 4,062 triangles, 12 materials, 7 door anchors (5 exterior, 2 internal), 27 listed collision boxes. Imported as the
earlier ones (`git show` of the files, catalogue, glbLandmarks.ts, LandmarkGlbTest).
- **Its name per town**: the sign's two big lines are repainted from the seed (`make_variants.py` SURNAMES; glbSites.ts FUNERAL_FAMILIES /
  FUNERAL_NAMES, the catalogue's `variants`): "<Hollis | Pruitt | Marlow | Whelan | Ashby | Kessler> & Sons Funeral Home", on both faces
  (the front and the back panel share one atlas region, both read left to right from their side: checked on the mesh's UVs). The model's own
  "WILLOW HOUSE" name is not used.
- **Doors**: five exterior doors are entrances (`door_main` at the portico, `door_arrival`, `door_staff`, `door_chapel_exit`,
  `door_service_bay`); the two the info file marks `"external": false` (`door_chapel`, `door_service_inner`, and the laundromat's
  `door_service_inside`) are left out of the entrances (the interiors job's). Every closed door panel and its `gate_node` box stays closed
  (a box in each doorway), so "Go inside" stands at `door_main` 0.24 m past the front edge, outside its closed box (tested).
- **Glow**: `glass_home` is door art and glass in one picture; its `pane-emission.png` (the emissive picture) is a mask. The loader now
  honours an emissive picture of the glass's own size that differs from its base picture (the feed mill's and the Gazette's copy their
  base picture and keep the old rule): only the texels it lights (luminance over 40) mirror the sky by day and glow at night, so the
  wooden doors stay dark (tested on the atlas's door regions). The sign is an externally lit board, not emissive (no glow).
- **Collision**: the 27 boxes are read from model-info.json (Blender Z up, front -Y: glTF x = x, z = -y, height from z) as for all
  models; checked in `funeral_home_collision.png` (preview `-Dmode=filament-landmarks -Dlandmarks=funeral`) and by test against the
  listed centres and sizes. The main floor is at 0.4656 m with real steps (no ground change needed: its grounds are at 0 or above).
- **In the town** (glbSites.ts): a town of 1,500 and more (at its peak) gets one. Lot type `house` (a residential-edge or older-street plot,
  not a highway strip): on a side street (never the main street) that is there when the plan begins, with no used shop plot within four
  tiles (neither an old-core street wall nor a strip), clear of the plot's usual ground with a landmark's plot and the plaza/town hall's
  pad one tile less than the others (a house lot may stand against them); scored for being near the core (about 40 tiles) among houses
  and on the oldest street. It is placed **last**, after every other model, so it never moves another's plot; a town with no house-sized
  ground left (gamma: the plot is 22 x 11 tiles, 27 x 23 m) gives it **the first pick instead**; the others are then placed in their order
  with the big optional ones (the feed mill, the bowling alley, the motel: each subset tried) last, whichever order leaves the most
  models a plot, and what no longer fits is left out (`glbUnplaced`). The house is built 1910-35 from the seed (`houseBuilt`, data) and
  converted to a funeral home 1935-60 from the seed (`opened`), and stays one to the present (never closed or pulled down); `owners` lists the
  families (the founders, and from 1975-2012 in half the towns another: data only, the sign keeps the founders' name). **Before the
  conversion the plot is open ground in the export**, as every model's plot is before it opens: the model's lane, chapel and service bay
  are in one merged `structure` mesh and cannot be hidden into a plain house, and a plan house lot belongs to residents the plot cannot
  take. (An ordinary house on the plot before the conversion needs the engine to draw a plan house on a landmark plot: not done.)
- **gamma**: Hollis & Sons Funeral Home, the house built 1927, converted 1940, on the west edge (Larch Road, row 18R, plot 17,100, 22 x 11
  tiles, 108 tiles from the plaza: the core has no free ground) where the Foundry used to be. Gamma had no house-sized ground left, so the
  funeral home took the first pick: the iron works and its club moved east (237,116) and the **feed mill (Hearthside) found no plot**
  (gamma already had no bowling alley); the Gazette, water tower, laundry and the drive-in's plot (the 2016 gas station) are where they were.
  street.json and the desktop fixture are re-exported. Other test towns: mu, quartz, orbit, theta, kappa, beta, zeta each place one on
  the last pass at 72-107 tiles from their plazas (a residential edge), moving nothing.
- Left: the attic gable window glows at night (the roof void is closed); a lot's lawn is a duller green than the town's grass (the model's
  `ground_lawn` swatch); no shut or reused look.

## Reel Time Video (2026-11-19, branch wf-video)

`reel-time-video/` (source `tools/landmarks/reel-time-video/`, the modeller's scripts and README copied with `git show`; the four .glb files and the combined
model-info.json too): a 32.3 x 11.2 m single-storey strip centre (built 1981, four bays, a covered walk, 12 parking spaces in front, a finished back with a
service lane, bins and a grease trap) on a 36.0 x 28.6 m lot, in **four states on one shell, lot, origin and door nodes** (`door_main` +Z, `door_back` -Z;
1,116 / 1,180 / 1,332 / 1,088 visible triangles, 11 materials each, 22 / 26 / 26 / 19 listed full-height collision boxes). One catalogue entry per state
(`landmark_glb_reel_time_video_1984|1996|2011|2020`; name "Reel Time Video" for the first three, "Cell Fix" for the last); the combined info file nests each
state's `collision`, which the loader reads at the top level, so `model-info-<year>.json` are that file split per state (top-level fields + the state's
materials and collision) and the catalogue points at them.
- 1984: the video store in bay 2 (plain lit box sign, NEW RELEASES banner, two film posters, a VHS return slot), bay 3 FOR LEASE. 1996: bays 2 and 3 (a wider
  backlit sign, 2 NIGHT RENTALS, a curb drop box). 2011: closing down (sign unlit and faded, STORE CLOSING / EVERYTHING MUST GO papers, a taped drop box).
  2020: bay 2 is CELL FIX (a vinyl banner over the old sign frame, the old sign's ghost and bolt holes on the fascia), bay 3 FOR LEASE. Bay 1 "Suds Laundry"
  and bay 4 "Ruby Nails" are quiet in all four. No name variants (the name is the model's story); the kind per state is the whole "variant".
- **Glow**: `glow_signs` has a separate emissive picture (`glow_mask_*.png`: black but for the lit sign faces and the walk's four soffit bulbs, its lit
  values only 90-145 of 255). The loader's sign rule lit texels over 150 of the emissive picture, so **nothing glowed**. Now (as the glass already did) an
  emissive picture of the material's own size that differs from its base picture is a mask, lit wherever it is not dark (over 40, `EMISSION_PANE`); a model that
  copies its base picture keeps the old bright-texel rule (no other model's file has a true sign mask: checked on all of them). Tested: exactly the mask's texels glow
  (about 11-13% of the picture in 1984/1996, 6.5% in 2011/2020), nothing dark does. `glass_storefront` is BLEND with 83% see-through texels (no glass mask): the
  rule for clear glass lights every bay's panes at night, vacant ones too (left, below).
- **Glass room**: `room` is `hall` (a lit wall and a counter band behind the panes). The loader makes the glass solid, so the model's real shelves
  (`wood_shelf`, inside the shell) are hidden; `shelves` would stock every bay's window, the vacant and laundry bays too, so it is not used.
- **No swatch re-skin**: the surfaces are the guide's swatches (block courses, stucco fascia, ballast, membrane, worn asphalt, pavement, planks) painted with
  outlines and wear; nothing flat (checked on the renders, validation.json: 0 bad UV edges, textures on the 2 x 2 grid), so `swatches` is empty.
- **In the town** (glbSites.ts `VIDEO_STATES`, `glbTenures`): a town of 2,500 and more (at its peak) has one strip centre on **one plot through time**:
  built 1972-82 from the seed (never before the town's early years; the 1984 state stands from then, so before the store opens the plot already shows the
  store's lettering: no vacant strip state exists), the store opens 1980-86 (`shopOpened`, data), takes its second bay 1990-97 (at least four years after it
  opens: the 1996 state), closes down 2008-13 (the 2011 state, a year or two) and the bay is reused from 1-2 years later (the 2020 state, to the present).
  Each state is a tenure whose `gone` is the next one's `opened` (like the works and The Foundry, with four models). Lot type `highway`: a post-war strip
  plot with its own car park in front, on a street built by the strip's year or the main street, scored like the other highway models (the main street's outer
  stretches, near them), never beside a used shop plot of the old core's street wall (the frontage rule), and **never within 10 tiles of a coin
  laundry's plot** (its own neighbour bay is a laundry). It never takes a pulled-down model's plot (its own, 30 x 14 tiles).
- **Placement order**: after every other model and after the funeral home, so it never moves another's plot where there is room. Where there is none
  (gamma: its east strip is a single free stretch), the search that gave the funeral home the first pick now also tries the strip centre first, the big
  optional models (feed mill, bowling alley, motel: each subset) last, and the strip centre at either end of the free ground (it packs the strip tighter),
  and keeps the order that places the funeral home, then the most other models, then the strip centre (so the strip centre never costs a town models on balance;
  towns whose baseline order already placed both are untouched). Other test towns: quartz, orbit and beta place one; mu, theta, kappa and zeta have no 30 x 14 ground left for
  it (theta's search found its drive-in a plot on the way: one model gained, none lost).
- **gamma**: the strip centre built 1972 (store opens 1983), the second bay 1992, closing down 2013, Cell Fix from 2014, on the main street's north side at
  plot (237, 116), 30 x 14 tiles, the old iron works' east spot. The east strip (x 237-243 and 327-408 on the main road: the rest is streets and lots) holds
  the strip centre, the iron works / Foundry (now 327), the **feed mill (352: it came back, "Hearthside Brewing Co." from 2008-18)** and the drive-in / Crossfield (379);
  it cannot hold the **motel too** (39 tiles; about 117 tiles of free frontage against about 122 needed), so the motel is the one model gamma gives up for the strip centre (the bowling
  alley was already out): gamma now has the funeral home, strip centre, works, Gazette, water tower, feed mill, drive-in and laundry and lacks the motel and bowling alley
  (before: the motel placed, the feed mill and bowling alley out). street.json and the desktop fixture are re-exported.
- **Breaches of the guide / notes for the modeller**: no must-rule broken (11 materials, 1.1-1.3k triangles, ground at 0, doors, full-height boxes, lot
  group). Format: the combined model-info.json nests collision per state (split here). Looks: the glow mask's lit values never exceed 145, so a glTF viewer lights
  signs at about half strength (use 255); no glass mask, so vacant bays glow at night; the drop box's label is illegible (a 3 x 5 font crammed into about 10 art
  pixels: it reads ".15" in 1996 and "UIT" in 2011); "2 NIGHT RENTALS" starts on the banner's frame; "70 OFF" touches the poster's lower edge; the faded 2011
  sign and the 2020 ghost are fine but the CELL FIX banner is crisp and rigid (not strapped); nothing shows the strip before the store (a 1981-83 state, all
  bays for lease, would give the plot an honest first state); the 2020 ghost shows only "TIME VIDEO" (the banner hides REEL, and overhangs the old frame by 3 m).
- Left: the engine builds the export's year only (gamma 2025 shows Cell Fix); no interior; the sign names of the neighbour bays are the model's.

## The intake models (2026-11-22, branch wf-intake)

Fifteen accepted models from docs/queue/INTAKE.md, brought in through our loader (the contributors' branches were not merged: the .glb files and
model-info files were copied with `git show origin/codex/<branch>:prototypes/<name>/...`, for the Rex only `prototypes/beach-blvd-rex/`; their sources, renders and
.blend files stay on the branches). Placed in the capital (gamma, `CAPITAL_SEED`) only: TOWN_PLAN.md "The intake models in the capital".

| kind (landmark_glb_...) | model (states) | lot (m) | triangles | from |
|---|---|---|---:|---|
| `public_library_1912`, `_1970s` | Carnegie library, 1912; the 1970s wing and a flat roof | 18.6 x 27.3; 23.3 x 36.6 | 5,410; 6,450 | `codex/public-library` |
| `corner_drugstore_family`, `_retail`, `_closed`, `_cafe` | corner drugstore with a soda fountain: family store, retail, shut, reused as a cafe | 15.8 x 27.5 | 5,222; 3,352; 3,448; 5,222 | `codex/corner-drugstore` |
| `small_town_bank_1920s`, `_drive_through` | Town State Bank; the drive-through lane | 16.8 x 23.6; 26.2 x 23.6 | 3,119; 3,749 | `codex/small-town-bank` |
| `brick_church`, `country_church` | brick Gothic church with a parish hall; white clapboard country church | 46.9 x 54.7; 31.0 x 32.6 | 9,328; 4,794 | `codex/brick-church` |
| `lincoln_high_school_1925`, `_1955`, `_1975`, `_2000` | Lincoln High campus through time (far silhouette `school-far.glb`, 246 triangles) | 232.8 x 159.9 | 7,779; 12,501; 13,741; 13,511 | `codex/lincoln-high-school` |
| `carhop_drive_in_original`, `_closed`, `_restored` | Milo's Drive-In; boarded up; restored as Milo's Diner | 43.5 x 34.5 | 2,594; 2,920; 3,478 | `codex/carhop-drive-in` |
| `fire_station_1924`, `_1988` | Engine Co. No. 2 (hose tower); the 1988 station (radio mast) | 28.0 x 25.5 | 2,174; 1,594 | `codex/fire-station` |
| `post_office_1938`, `_1988` | WPA post office; the 1988 branch | 26.7 x 24.5 | 2,186; 2,428 | `codex/post-office` |
| `highway_rest_area` | Pine Bend rest area (1970s) | 82.0 x 66.0 | 7,228 | `codex/highway-rest-area` |
| `lantern_pine_lodge` | log lodge in a pine clearing | 34.0 x 29.0 | 9,191 | `codex/lantern-pine-lodge` |
| `the_eyrie` | The Eyrie, the wings monument (113 m) | 198.0 x 160.0 | 8,392 | `codex/the-eyrie` |
| `union_arch` | Union Arch | 43.0 x 36.0 | 4,648 | `codex/union-arch` |
| `night_keeper` | The Night Keeper, a 34 m giant carrying a lantern pavilion | 62.0 x 50.0 | 8,023 | `codex/night-keeper` |
| `beach_blvd_rex` | the Beach Blvd Rex, a roadside dinosaur | 9.6 x 11.4 | 2,988 | `codex/beach-blvd-rex` (prototypes/ only) |
| `great_meridian_1926`, `_1941`, `_1976`, `_2000` | The Great Meridian (r2, 106 m; far silhouette `great-meridian-far.glb`, 330 triangles; the 5,042-triangle `great-meridian-mid.glb` is shipped but not used: the loader has one far model) | 120.0 x 120.1 | 28,203; 27,833; 28,297; 29,017 | `codex/great-meridian-monument` |

- **The data step is a script**: `python3 tools/landmarks/intake.py [--write]` (with `tools/landmarks/glbinfo.py`, a .glb reader) lists every model (a table in the
  script says which info file and key each state comes from, its years, role and name), measures each .glb (lot = the bounds of everything drawn, x and z; triangles; doors),
  writes the per-state `model-info-<state>.json` next to the model in the loader's shape, and appends the catalogue entries (`landmarks.json` keeps its hand-written layout;
  the script refuses to run twice) and the generator's lines for `glbLandmarks.ts` (written to `$TS_OUT`). The contributors' infos come in four shapes (a state list, one file
  per state, `states` as a dict with per-state extras, a fire station and post office with boxes as `min_gltf_m`/`max_gltf_m` and doors as a dict): all become one flat file
  with `doors[]` (with `external`) and `collision.boxes_gltf_y_up` (centre, size). The Rex's, the rest area's and the lodge's boxes are Blender Z-up lists (`boxes_blender_z_up`):
  turned as before.
- **A chain of states shares one building place**: a state's lot is its own bounds, the chain's plot (`footprint.plot`, TS `plot`: `glbLandmarkTiles` uses it) is the biggest
  state's, and the catalogue's `centre` (metres, model x) is the biggest state's middle: `TownLandmark` puts that on the slot's middle (not each state's own), so the bank's
  1920s building does not slide 4.7 m when the drive-through lane is added, nor the library when its wing is. Eras are the years each state may begin (the generator draws it).
- **Against the guide** (docs/CHATGPT_MODEL_GUIDE.md), what was found and what was done:
  - *Boxes in the wrong axes for our reader*: the loader read only Blender Z-up lists and converted them; the intake infos are mostly glTF Y-up lists
    (`boxes_gltf_y_up`) and would have been turned twice. `LandmarkKit.collisionTags` now reads `boxes_gltf_y_up` as it is (the Rex's two lists in both axes are checked
    against each other in `theIntakeBoxesAreReadInTheirOwnAxes`); `name` is read as `source` for the rest area and lodge.
  - *The Eyrie, the arch and the Night Keeper list no boxes* ("use the walls", and no `collision` node): their walk boxes come from the drawn parts alone (the loader's cuts at knee to head
    height); a test pins at least four.
  - *Closed doors*: none of the fifteen has door panels or gate boxes (their entrances are open); `door_panel_*` was checked in every file. The school's 2000 info marks `door_main`
    internal (a security vestibule): it is an entrance here (`door_security`, `door_access` too) and the classrooms' `door_room_*` stay inner; the carhop's `door_prep_inside` and the Great
    Meridian's stair, gallery, lift and rotunda doors are marked inner. The corner drugstore's `door_main` cuts its corner (faces out at 45 degrees; the scale test allows it).
  - *A door blocked by a box*: the Great Meridian's 1926 dedication stand (a 9 m timber platform, 2.3 m high) stands exactly on `door_main`. `LandmarkKit.clearDoors` now keeps every
    entrance's standing place clear: a box across it (listed or drawn) is cut in two with a 2.4 m opening; nothing of the earlier models needed it.
  - *Pane-only emission*: the glass pictures' emission masks were measured (lit texels on see-through texels): the bank's mask lights 112 texels (0.9%) of mullions, and the lodge's
    `glass_lodge` (an opaque picture) has an emissive picture that is solid amber, so its bars would glow. `LandmarkKit.glass` now cuts a mask to the see-through texels when it lies
    nine tenths on them (the funeral home's door art stays as it was) and, for an opaque picture, drops its darkest eighth (the frame); `theIntakeGlassGlowsOnlyItsPanes` pins it.
    Left: the rest area's clerestory and the Night Keeper's story panels copy their base picture as the emissive one (no mask): the pane rule applies; the fire station's, post office's
    and Great Meridian's glass has no emissive picture (all see-through texels light).
  - *Sunken ground*: the Rex's asphalt lies at -2 cm under its 4 cm pavement and the Great Meridian's pool at 0 (and 62 cm of basin below): the loader's lift and flush water cover both
    (the library's basement, y -1.4, is under the ground, not drawn ground). A thin slab laid at the ground (the Rex's asphalt, whose underside is under it) passes the scale test by its top.
  - *Sizes*: the Great Meridian draws 28-29k triangles (the guide's landmark ceiling is about 12,000; the model uses instancing) and the school 13.7k; the Eyrie is 198 x 160 m and
    the campus 233 x 160 m, which fit no block of the planned town: the capital's grounds (TOWN_PLAN.md). Lots read from the bounds are the models' own, not the info's `lot_bounds`.
  - *Names*: the signs are painted ("PUBLIC LIBRARY", "TOWN STATE BANK", "CORNER DRUGSTORE" with a CAFE blade, "LINCOLN HIGH SCHOOL", "MILO'S DRIVE-IN" / "DINER"): no per-town name
    variants yet (the infos' `binding` notes say repaint per town: a later `make_variants.py` job, as the funeral home's).
- **Not done**: interiors (rooms behind the doors: the interiors job's); the Great Meridian's elevator and observation decks and its animated parts (`armillary`, beacon, lift car: Gemini's
  LandmarkAnimation branch will bind them; the loader's per-frame loop is untouched here); the Eyrie's bronze wings are static.

## The third intake (2026-11-23, C001-C003)

Three accepted models from docs/queue/INTAKE.md, brought in the same way as the fifteen above (files copied with `git show origin/codex/<branch>:prototypes/<name>/...`, no merge; sources, renders and
.blend files stay on the branches): the Marlow Five and Dime, the Lark Bend grain elevator and the Alder Bend Ballpark, eleven states in thirty-three .glb files (near, mid and far each). Placed in the
capital only: TOWN_PLAN.md "The third intake in the capital".

| kind (landmark_glb_...) | model (states) | lot (m) | triangles near / mid / far | from |
|---|---|---|---|---|
| `five_and_dime_1925`, `_1970`, `_2000`, `_closed` | Marlow Dime (1925-69), Marlow Variety (1970-99), Dollar Cove (2000-), for lease (vacant); a 10 x 28 m street-wall shop, party walls, a stockroom door at the back | 10.0 x 31.4 | 2,782 / 1,762 / 1,052; 2,792 / 1,772 / 1,064; 2,796 / 1,776 / 1,068; 2,050 / 1,702 / 1,078 | `codex/C001-five-and-dime` |
| `grain_elevator_1925`, `_1950`, `_1990` | Lark Bend elevator: timber (1925-49), the concrete bins added (1950-89), the co-op (1990-); the siding along its back | 48.0 x 32.7; 71.0 x 32.8; 71.0 x 32.8 | 920 / 800 / 792; 3,228 / 2,076 / 1,472; 2,920 / 1,768 / 1,172 | `codex/C002-grain-elevator` |
| `baseball_park_1935`, `_1950`, `_1960`, `_2000` | Alder Bend Ballpark: the wooden park (1935-49), the lights (1950-59), steel bleachers (1960-99), the aluminium renovation (2000-); a 600-seat grandstand, a ball field, a gravel lot at the gate | 144.3 x 155.2 | 6,519 / 4,023 / 3,375; 7,015 / 4,519 / 3,871 (1950, 1960 and 2000 alike) | `codex/C003-baseball-park` |

- **Data**: `python3 tools/landmarks/intake.py --write --only=five-and-dime,grain-elevator,baseball-park` (the script's new `--only` runs just those folders; its rows are a third, table-driven set) wrote the
  per-state `model-info-<state>.json` and the catalogue entries. The infos came in a fifth shape: a `states` list whose entries hold `models` (near, mid, far), the near one with the doors under `anchors`
  (the shop), `doors` (the elevator) or `door_nodes_gltf_m` (the park) and the boxes under `collision_boxes_gltf_m` (the shop, the park) or `colliders` (the elevator): all flattened to the loader's
  `doors[]` + `collision.boxes_gltf_y_up`. Lots are the near model's bounds (everything drawn).
- **Years**: each state's years are the model-info's own (the catalogue's `era`): shop 1925-69 / 1970-99 / 2000- and, with no years of its own, the vacant state from the 2010s (the simulation closes Dollar Cove 2010-20);
  elevator 1925-49 / 1950-89 / 1990-; park 1935-49 / 1950-59 / 1960-99 / 2000-.
- **The distant models**: every state has its own `far` (and a `mid`) file; the catalogue's `far` is each state's (not one shared silhouette as the school's and the Great Meridian's), the loader draws it past
  `TownLandmark.LOD`. They are 1,000-3,900 triangles, not the few hundred of a silhouette (the contributors' LOD ceiling is "about a third of near"): the ballpark's far model draws 3,871 triangles. The `mid` files are shipped and
  catalogued (`mid`, a key the loader does not read: one far model only, as for the Great Meridian) but not drawn.
- **Chains**: the elevator's states differ in width (48 m of timber; 71 m with the concrete bins), so the chain shares one plot (the 1950 state's, 71 x 32.8 m: `plot`) and the biggest state's middle (`centre`, 10.5 m); the
  shop's and the park's states have one bounds each.
- **The ballpark's gravel**: its `ground_gravel` picture (32 x 32, blue-grey 139,139,158 with white flecks) read almost white under the town's light. `python3 tools/landmarks/reskin_gravel.py` replaces it, in all twelve
  files, with the town's own yard gravel: the picture the general store's yard is paved with (`StoreGrounds.GRAVEL`: 32 x 32 texels in four warm tones, 8A8070 / B0A690 / 6A6458 / 9E9480, drawn by `Paint.Rnd(7)`, here reproduced
  bit for bit). The grid needed no change: the model's UVs lay one 32-texel picture over 1.2416 m, which is 0.0388 m a texel (measured on the lot's quad: 72 m is 57.99 UV units). The picture is baked into the .glb (the new PNG appended
  to the bin chunk, the image's bufferView pointed at it; nothing else moves) rather than done through the catalogue's `swatches` (there is no gravel swatch in the atlas: `ballast` is the roof's tar and gravel). `theBallparksGravelIsOurOwnAndNotWhite` pins it.
- **Against the guide**: no closed door panels (the entrances are open); no `door_` marked inner; collision boxes read as they come (glTF Y-up, all `building` group but the shop's pavement and yard, `lot`: 12 in the shop, 51 in the elevator);
  the elevator's `rail_siding` (a centreline at z -28.5, x -27..47, the timber spout at -16 and the concrete one at 35) is not drawn by the model: the game lays the track (TOWN_PLAN.md: the capital's rail adds a siding along the plot's back).
  The scale test's height limit for the elevator's role is 50 m (the model is 44.7 m); the ballpark is 17.4 m.
- **Measured** (desktop, software GL, not the S25): the town's memory check with them in (gamma, whole town cold start, 320 MB heap): held peak 161 MB (live 133 + direct 58; 160 before, budget 256), 84,514 triangles in the far view; the renders were desktop contact sheets (Filament, software GL); triangles above are the near models', counted by `glbinfo.py`.

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
  its frame (a row slot's: the lot's front edge on the row's building line, the lot's middle on the slot's, so an
  off-centre lot is centred; or the depot's beside the track), uploaded on the engine's thread into its own culled
  `PropLayer`, lit each frame (dark when the business is shut), its boxes and doors into `TownCollision.setExtra` /
  `setExtraDoors` (key `landmark:<sprite>`). See "In the town".
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

## Where each model belongs (the placement job's brief; as built: "In the town")

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
| coin-laundromat | towns of 2,000+, opens 1960-75, may run to the present (looking older) | downtown or strip-centre lot, its own four bays in front | nothing |
| foundry-nightclub | towns over ~8,000, 2010s-20s, likelier with industry (mill, mining) | its own plot (an old works) | the town's one club: Mirage (1980s), then Pulse or The Foundry |

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

## In the town (branch wf-place, 2026-11-19)

Desktop renders and JVM tests only, not yet run on the S25.

### The generator (src/town/glbSites.ts, tests glbSites.test.ts)

- **Town size**: each simulated person stands for `INHABITANTS_PER_PERSON` = 36 inhabitants (gamma: 2,844 in 1950, 8,316
  in 2025; the towns tried run 2,700-11,600). The table's thresholds read on that: the Gazette and the 1950s station from
  1,500 (peak), the diner and the laundromat from 2,000, the drive-in, the motel and the highway station from 2,500, the
  hand-made depot from 3,000, the bowling alley from 4,500, a club from 8,000 in its decade.
- **Who earns what** (`glbTenures`, all `rand(seed, 'glb', ...)`; a tenure is opened / closed / reused / pulled down):
  water tower every town (built 1920-65, never pulled down); Gazette 85% (built 1905-40; half fold 2008-20 and stand
  dark); feed mill (every farm town, 70% of small towns, 40% of others; built 1920-60, shut 1972-95, then storage, a brewery
  from 2008-18, or pulled down 5-15 years on); Main Street station 85% (opens 1950-69, stops selling gas 1972-85 at least
  four years on: 55% a repair garage with its pumps, price board and fuel sign hidden, half of those pulled down 1995-2015;
  else shut and pulled down 2-8 years on); highway gas station 80% (opens 2005-2022); diner 80% (opens 1940-65; 65% shut
  1978-2005 and pulled down within 8 years, the rest run on); bowling alley 70% (opens 1960-69, half shut 1995-2015 and
  pulled down); drive-in 65% (opens 1950-66, shut 1975-88, cleared within 4 years); motel 70% (opens 1950-69: half an
  extended stay from 1998-2008, half shut 1990-2010 and pulled down); laundromat 75% (opens 1960-75, 30% shut 1995-2020,
  standing dark). **One club**: Club Mirage when the town has 8,000 in a year of the 1980s (opens that year, shut
  1990-97, pulled down 3-10 years on); later The Foundry (2010-29: likelier in a mill or mining town, 75%, harbour 50%,
  else 25%) or Club Pulse (2020-29), when the town has 8,000 then; a Mirage still standing is pulled down the year before,
  and a Pulse takes the Mirage's plot (kept for it).
- **Where** (`glbSites`): the plan is finished first, nothing moves for a model. A plot is `glbLandmarkTiles(kind)` (1.24 m
  along the street, 2.1 m back, as the stores'), a tile off a street's edge, on either side of any street, and must keep
  off every street (a tile from the others), the river (2 tiles), the plaza, the landmarks, the town hall and chapel, the
  farmsteads (4 tiles) and country roads, the railroad (4 tiles from any track's centre line; the depot's, its model plot's
  and the elevator's ground), the 3D town's yards past the plots, the lots the town ever builds by 2025 (its 2025
  properties; a tile clear), and each other. It may take plots the town never builds on by 2025 (woodland plots at its
  edges) and a farmed field over the town's outer plots (the farms sell it the year the model opens: `fieldClear`,
  `becomes: 'landmark'`). The street must be built by the year the model opens (`developedRoads` by year from the lots'
  build years), or be the main street. The frontage rule: within 4 tiles along the same side, a model with its own car
  park in front (all but the Gazette) never stands beside a used shop plot of the old core's street wall (`stripFace`
  false), the Gazette (a street-wall building) never beside a post-war strip. Lot types and scores: downtown (Gazette,
  laundromat: nearest the plaza within 120 tiles, the main street first; the Gazette beside a core face if it can), corner
  (Mirage, Pulse: near the centre, a crossing street at the plot's end preferred), highway (gas stations, diner, motel,
  drive-in, bowling alley: the main street's outer stretches first, far from the centre, near the other highway models;
  the 1950s station nearer town), civic (water tower: off the main street, about 45 tiles from the plaza, clear of
  buildings), edge (feed mill and The Foundry: nearest the railroad's elevator site if it has one, else far out). A plot
  left by a model pulled down is taken first by a later one that fits (a highway gas station on the drive-in's cleared lot,
  The Foundry on the motel's), placed in the order the businesses open. A business that finds no plot is left out
  (`glbUnplaced`): gamma's bowling alley (its edges are taken by the motel, drive-in and feed mill).
- **The depot** (src/town/rail.ts): in a town of 3,000 and more (`premierDepot`) the depot's search also wants the model's
  whole plot clear beside the stretch (26 tiles along the track, from 3 to 12 tiles off its centre, on the depot's side; a
  field's corner allowed, the farms sell it), and `depot.premier` is that plot when it is; else the town keeps the
  code-built halt. Gamma's depot site and track did not change.

### The export (tools/town-glb.ts, town-graph.ts, town-farms.ts, export-rail.ts)

- `townGlb(seed)`: the sites over all years (the 2025 run's lots, the railroad of the town's world as rail.json lays it,
  the shipped yards). What stands in the export's year becomes a row building of kind `landmark` in its plot (named after
  every lot, landmark and extra, so no other name moves; sprite parts key = the kind, so make_buildings.py skips it as a
  store's), fronting the street its site was chosen on, listed in `graph.landmarks` (art = the kind: StoreLots leaves its
  plot open, no door guessed, lamps and benches off its front) and in `graph.glbLandmarks` (kind, name, state open / closed
  / reused, hidden nodes, opened / closed / gone / reuse, the plot's tiles and world rect, sprite, row, the site's whole
  history). Props inside a standing model's plot are dropped; the farms sell fields under it. rail.json's depot carries
  `premier` (the model's plot, world units).
- **gamma 2025** (on world-6): the Gazette (open, built 1926) on the main street's west end, the water tower (1926) on the
  stub street south of the main street's road east, the feed mill (shut 1975, Hearthside Brewing Co. since then) at the
  west edge, The Foundry (2022) on the old motel's plot (motel 1962-99, pulled down 2002) and Crossfield Fuel & Market
  (2016) on the old drive-in's (1964-76, cleared 1980) by the main street's road east, the coin laundry (1975, shut 2019,
  dark) between them, and the Union Depot at the depot (1866) by the track south-west of town. gamma 1955: the Gazette, the
  water tower, the feed mill (open); 1985: those (the feed mill shut), the motel and the laundry.

### The engine

- `domain/GlbLandmarks.kt` parses `graph.glbLandmarks`; `RailDepot.premier`; `StoreLots` includes the models' plots;
  `RailScene.onBed` keeps the forest off the model depot's plot.
- `FilamentTown(..., landmarks = LandmarkAssets(catalogue, file reader, placed))` (the app: TownSession reads assets/landmarks;
  the open preview the folder): a `TownLandmark` per placed slot (the slot left to it, as a store's), built on the workers
  nearest first with the stores, counted in `pending`; drawn each frame with the cutaway and cut in the open-world camera's
  way by its building's box (walls, roofs, glass; not the flat lot) as the cinema is (`occlude`, `fadeBetween`); its boxes
  and doors in the collision ("Go inside" where it has a door: the door's room is the interiors job's); signs, windows and
  lamps lit from dusk unless the business is shut. Far off it stays drawn to 12,000 units past its reach (no far box
  stands in for it; its shadows only within the view's range). The railroad's model depot is a `TownLandmark` of its own
  (`rail-depot`), front away from the track, its platform's edge 1.75 m from the track's centre, rebuilt with the rail's
  year; `TownRailDraw` then leaves its code-built depot out.
- Preview: `gradle :desktop:preview -Dmode=filament-open -Dshots=glb_` writes `glb_<kind>_{front_13h, quarter_13h, back_13h,
  above_13h, night}` for each placed model and the depot (`glb_depot_*`); `open.txt` lists them (triangles, solid shapes,
  doors). The open preview now draws the railroad too (-Drail=0 leaves it out).

## Animated nodes

Named nodes inside a landmark `.glb` can animate in place while the town runs without breaking static material merging.
During `LandmarkKit.build`, any node matching an animation rule (and its children) is kept out of the merged static
meshes and stored as its own small node-local renderable (`AnimatedNodePart`), pivoted at the node's own origin. At run
time, `LandmarkAnimator` updates only those renderables through Filament's `TransformManager` with zero per-frame
allocations, skipping landmarks that are off-screen or past the near-model range. Existing static-only models produce
the exact same renderables and triangle counts as before.

### Declarative table format

Rules are resolved in order: `landmarks.json` (`"animations"` on a landmark entry) > `model-info.json` (`"animations"`
array or structured `states` / `armillary_nodes` / `beacon_beam` / `lift_car` fields) > `LandmarkAnimRule.DEFAULT_TABLE`.
Node patterns use case-insensitive glob matching (`*`, `?`). A glTF node with `extras.motion = "fixed"` (such as
`armillary_outer`) always stays static.

```json
{
  "clock_stopped": "4:17",
  "animations": [
    { "node": "clock_hour*",        "kind": "clock_hour" },
    { "node": "clock_minute*",      "kind": "clock_minute" },
    { "node": "armillary_ecliptic", "kind": "slow_spin",  "axis": [-0.39715, 0.91775, 0.0], "period_game_hours": 24 },
    { "node": "beacon_beam",        "kind": "sweep_beam", "period_s": 10, "night_only": true, "direction": [0, -0.05, 1], "color": "#FFFFFF" },
    { "node": "bell_*",             "kind": "swing",      "axis": [1, 0, 0], "amplitude_deg": 30, "period_s": 2.4, "swings_on_hour": 5 },
    { "node": "lift_car",           "kind": "lift",       "stops_y_m": [6.52, 24.06, 58.04], "wait_s": 6, "speed_mps": 2.5 }
  ]
}
```

### Kinds and parameters

| kind | default pattern(s) | parameters | behaviour |
|---|---|---|---|
| `clock_hour` | `clock_hour*` | `period_game_hours` (12), `stopped_at` (`"h:mm"`) | 1 rev / 12 game hours clockwise on the dial face; holds fixed time when stopped (`clock_stopped`, `stopped_at`, `model-info` `clock_active: false`, or node `extras.active: false` + `extras.time`). |
| `clock_minute` | `clock_minute*` | `period_game_hours` (1), `stopped_at` (`"h:mm"`) | 1 rev / game hour clockwise on the dial face; shares stopped-clock overrides. |
| `slow_spin` | `armillary_ecliptic*`, `armillary_globe*` | `axis` (node-local or `extras.rotation_axis`), `period_game_hours` (24) or `period_s` | Constant rotation about `axis` (default 1 rev / game day). |
| `sweep_beam` | `beacon_beam*` | `axis` (`[0,1,0]`), `period_s` (10), `night_only` (`true`), `active` (`true`), `direction`, `color` | Turns once per `period_s` real seconds between dusk and dawn; hidden by day or when `active: false`. Empty locator nodes get a lightweight glowing searchlight cone (`casts = false`). |
| `swing` | `bell`, `bell_*` | `axis` (`[1,0,0]`), `amplitude_deg` (30), `period_s` (2.4), `phase_deg`, `swings_on_hour` (5) | Pendulum swing triggered on the hour (easing back to rest) or via `FilamentTown.ringLandmarkBells(...)` / `TownLandmark.ringBells(...)`. Multiple bells auto-stagger out of phase. |
| `lift` | `lift_car*` | `stops_y_m` (metres), `wait_s` (6), `speed_mps` (2.5) | Smooth ping-pong vertical loop along `+Y` between listed stops, waiting `wait_s` at each stop. |

### How model authors name and pivot nodes

- **Place each animated node's origin at its pivot** (dial hub for clock hands, sphere centre for armillary rings, yoke
  hinge for bells, lantern centre for `beacon_beam`). Both TRS (`translation` / `rotation` / `scale`) and 4x4 `matrix`
  glTF node transforms are supported.
- **Clock hands:** model each hand pointing up along local `+Y` (12 o'clock) with the dial's outward normal along local
  `+Z`. The engine derives the 12-o'clock basis from the node's outward `+Z` and world `+Y`, so any static pose baked
  into the `.glb` (such as `10:10`) is ignored when live and respected when stopped.
- **Materials, shadows, and glow:** animated nodes keep their material roles (`glass_*`, `glow_*`, `sign_*`, opaque body)
  and shadow casting (`beacon_beam` synthetic cones do not cast shadows).
- **Collision:** animated nodes are excluded from static collision boxes (dynamic lift-car collision is left as a TODO).

### Left

- The models have no shut or reused looks: a shut one is drawn whole and dark (the reused repair garage hides its pumps);
  the reuse names (Hearthside Brewing Co., Sundown Extended Stay) are data only, not lettered on the signs.
- The town's name is not lettered on the depot's or the water tower's boards; the Gazette's name panel is the model's.
- The engine builds the export's year only (no time travel for the models yet, as the downtown).
- Gamma's bowling alley found no plot; dense towns place fewer of the big highway models.
- The laundromat's fixtures kit and every model's interior (rooms behind the doors) are the interiors job's.
- Animated nodes: dynamic collision for moving `lift_car` parts is not implemented (`TODO` in `LandmarkKit.kt`), bell audio is not triggered with visual swings, and measurements/renders so far are desktop headless Filament (not phone captures).
- No far box: the models are drawn whole to 12,000 units (3-12k triangles each); measure on the S25.



## The planned town's plots, the courthouse and the radio station (branch wf-town-plan, 2026-11-20)

- **Placement**: glbSites no longer searches: it returns the plan's reserved plots (src/town/districts.ts, TOWN_PLAN.md
  "Where each building goes"), one plot per chain (`glbPlotOf`: the clubs, the works and The Foundry, the strip centre's
  and the courthouse's states), each fronting the street of its block face. Every model a town earns has its plot (the
  motel and Starling Lanes too). street.json's model rect is its plot as laid out on its row.
- **The county courthouse** (prototypes/county-courthouse, copied with `git show`; sources under
  tools/landmarks/county-courthouse/): four states on one shell, origin, lot (79.46 m square) and doors, the whole civic
  block of a county seat (isCountySeat: 35% of L, 15% of M, 5% of S towns). Built 1885-1905 (the 1894 state), modernised
  1951-62 (the 1962 state with its annex), neglected 1972-82 (1988: the clock stopped at 4:17, the banner), restored
  1996-2006 (2008). Catalogue: per-state info files with the eleven `door_room_*` doors marked internal (four entrances:
  door_main, door_north, door_east, door_west), `far` the distant silhouette (courthouse-far.glb, 142 triangles) the town
  draws past 3,000 units (TownLandmark.LOD) instead of the 13,800-triangle state. The clock's hands (clock_hour*,
  clock_minute*, pivoted at each dial) are turned to the game's time when the model is built (FilamentTown.clockHours,
  LandmarkKit.clockHands) and, since the animated-nodes merge, moved live as animated nodes ("Animated nodes"). The neglected state's stopped clock keeps its 4:17. Its porch landing and steps (boxes no higher
  than 1.3 m named step/stair/landing/porch/ramp) are walk-on ground, so door_main can be reached. Test seed: rho.
- **The radio station** ("Valley Radio", prototypes/radio-station; tools/landmarks/radio-station/): a 1950s-60s broadcast
  house with its mast, in a town of 2,500 and more (70%), opened 1946-60, from 1990-2006 run automated (the station's name
  and "(automated)") or the Radio Building Offices; on open ground at the post-war edge, kept 8 tiles off the river and 30
  off the rail line. Six painted nameplates (make_variants.py: Valley Radio, Prairie 1340 AM, Riverbend Radio, Hometown
  Radio, Cedar 1400 AM, Crossroads 1450; made-up names, no call letters), `variant` per town (glbSites RADIO_NAMES); three
  inner doors marked internal; its glazed entrance bay stands ~2.5 m before its front (the scale test allows it). The ON
  AIR plate is static art (bind or hide it when broadcasts are simulated).
