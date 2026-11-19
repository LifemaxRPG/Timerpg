# Materials catalogue: textures we can cut from the three LimeZu packs

Audit of 2026-11-19 of Modern Exteriors v42.3, Modern Interiors v41.4 and Modern Farm v1.3 (all read from the **32 px**
art; the 48 px art is an exact 1.5x upscale, see PACK_EXTERIORS.md). Purpose: textures for buildings and objects we
build ourselves as geometry (code-built stores, interiors, farms, props), in the style of SPRITES_3D.md (pixel-art
textures on simple boxes, nearest filtering).

Machine-readable: `kotlin-street/tools/materials.json` (230 entries; format below). Contact sheets (each material tiled
3x3 with its id; strips tiled 3x1) live outside git, in the scratchpad `.../scratchpad/materials/sheets/` (one PNG per
family plus `overview_*.png`); regenerate from the zips if needed. They are desktop renders of the raw art, not S25 captures.
Licence: use and edit in our game only, credits required (limezu.itch.io); the json holds coordinates, not pixels.

## Format of materials.json

`{format, note, entries:[{id, family, pack, file, rect:[x,y,w,h], tile, repeat:[w,h], palette:[#hex...], tags:[...], seam:[sx,sy], note?, kind?}]}`

- `pack`: exteriors / interiors / farm; `file`: path inside that pack's zip (a **sheet**, not a single); `rect` in px.
- `tile`: `seamless` = the rect tiles as is (3x3 checked, wrap-around score `seam` <= 1.6 per axis, or judged by eye on
  the sheet); `needs-fix` = seam, transparent corner or wrong crop: cut a sub-rect, mirror-tile, or blend.
  The score is **conservative for strongly structured patterns** (floors, wallpapers): 42 of 74 floors say needs-fix but
  most read fine tiled; look at `sheets/floor_*.png` before relying on the flag.
- `repeat`: natural period of the pattern (for brick: one bond repeat; for roofs: one tile course). Where `repeat` is
  smaller than `rect` the rect is already a whole number of periods.
- `kind` (absent = `tile`): `strip` tile along one axis only (fences, awnings, cornices: transparent or single-row art),
  `decal` place once (door, crosswalk), `autotile` whole 12x4 autotile sheet (cut cells from it).
- `palette`: the 4-6 dominant opaque colours (median cut), most frequent first.
- Flat-colour entries (`at_*`, `carpet_path_*`, `water_basic`, `dirt_farm`) are real LimeZu base colours: the art draws
  grass/dirt/water as a **flat fill plus hand-placed detail**, so the fill colour is the right "base" and detail comes from
  scattered decals (see Gaps).

## Lighting baked into the art

LimeZu light comes from the **top** (a 2.5D view from the front-above): brick and plank rows have a lighter top edge,
a dark 1 px outline (#3a3a50 is the common outline colour in all three packs) and a darker bottom edge; roofs are lit
flat from above, sloped roof pictures are darker on the lower-left plane. Floors and walls in Interiors carry a soft
top-down shade (wall caps have a white top and dark underside). The patches listed here are flat, front-on faces, so
the baked light is only the micro shading inside each brick/plank/tile; apply our own 3D lighting on top. Avoid the
slanted roof pictures of `24_Additional_Houses` and `7_Villas` as tiles: their edges are diagonal (transparent corners).

## Catalogue by family (counts in json)

Pack letter: E exteriors, I interiors, F farm. Sheet folders: E `Modern_Exteriors_32x32/ME_Theme_Sorter_32x32/`,
I `1_Interiors/32x32/Room_Bulder_subfiles_32x32/` (sic), F `32x32/`.

| Family | n | Best sources (id: sheet, rect) and notes |
|---|---:|---|
| brick | 4 | `brick_red_terrace` (E 1_Terrains, 23,806,62x48, **seamless**, period 31x48): the classic 1950s red running bond, tan mortar, #a14f44 #b99e86 #943c3f. `brick_red_school` (E 13_School 340,646): weathered red with dark flecks. `brick_brown_weathered` (E 4_Generic 448,5010): faded brown industrial. `brick_red_school_col` pilaster strip. Condo_4's red brick (4_Generic ~256,1830) is only available in small patches between windows: not cut. |
| stucco | 6 | Cream `stucco_cream_hotel/2` (E 12_Hotel 68,228 / 388,228, 88x104), teal `stucco_teal_hotel`, green `stucco_teal_shop`, mustard `stucco_yellow_shop` (E 9_Shopping), sand `stucco_beige_school`. All flat colour with faint panel lines every 32 px: needs-fix only for those lines (crop to 32 px or blend). |
| concrete | 5 | `concrete_office_light` (E 16_Office 38,2520, 132x132, seamless, 22 px panel grid): also the curtain-wall glass, flat-roof membrane. `concrete_add_flat` (E 24_Additional 52,4604, pale lilac slab roof), `concrete_post_dark`, `concrete_fire_grey`, `concrete_office_col`. |
| siding / boards | 13 | Villas plank siding in five colours (E 7_Villas x=3/323/643, y=66 and 514, 36x114, period 12 px vertical battens): orange, brown, plum, red, blue. Boardwalk planks `boards_beach_light/dark` (E 21_Beach 6,1868 and 391,684, 344x144): large, seamless horizontal planks, two wood tones. Deck `boards_garden_pale`, `boards_subway_wood`. Farm barn: `siding_barn_orange` (F 3_Props 140,4236, 112x160, vertical), `siding_barn_brown_h/h2` (F 3_Props 780,2431 and 564,4292, horizontal), `siding_barn_pale`. |
| roof (pitched) | 7 | Clay `roof_tile_red` (E 5_Floor_Modular 332,818, 200x104, period 10x13, #a14f44 #613242), slate `roof_tile_slate` (260,3250), charcoal `roof_tile_charcoal` (260,5676). Metal: `roof_metal_ribbed_h/h2` (E 24_Additional 396,3732 / 596,3852, horizontal ribs 8 px), `roof_scale_grey`, `roof_scale_grey_b` (scale shingle). All seamless except where noted in json. |
| roof-flat | 3 | `roofflat_tan_condo` (E 4_Generic 301,1675, 32x32, #c3ac90 flat tan), `roofflat_grey_condo` (grey-brown tar with seam lines, 182,1000), `roofflat_blue_office` (blue-grey membrane, 70,3990); the pale panel `concrete_office_light` also serves. Gravel is not drawn as gravel anywhere: see Gaps. |
| glass / windows | 3 | Window grids (fenestration, not single panes): `glass_mod_blue/grey/red` (E 5_Floor_Modular 663,3380 / 663,5815 / 663,951, period 18 px, panes #97aacd #899fbf). Curtain wall: `concrete_office_light`. Single windows, frames and sills are objects in the sheets (4_Generic windows ~x=250..650,y=4380..4560; 5_Floor_Modular window strips) and are better taken from the existing modular pipeline than re-cut here. |
| metal | 5 | Corrugated `metal_corrugated_hotel` (E 12_Hotel 556,1348, 288x104, vertical, blue-grey #b9b3ce #565972), `metal_shutter_shop` (E 9_Shopping 188,148, dark olive corrugated wall), `metal_shutter_gar` / `_police` roller shutters (horizontal slats 8 px), `metal_plate_add` (grey plate). Riveted steel panels exist in F 3_Props (~648,1144, 784,1184) as 32 px-wide strips with diagonal shading: not tileable, not listed. |
| stone | 4 | `stone_cobble_brown` (E 1_Terrains 627,1359, 62x64, period 31x32): cobblestone/flagstone. `stone_rock_grey` (E 1_Terrains 4,2010) and `stone_rock_beach` (E 21_Beach 708,3452): rock-face pictures (need-fix: edge profile baked in), `stone_grave_dark`. Cut-stone walls with arches (Condo_6, chapel) are building pictures, not patches. |
| asphalt | 1 | `asphalt_plain` (E 2_City_Terrains 8,136, 144x112, seamless, #534f52 #595555): dark grey with faint cracks. |
| pavement | 4 | Sidewalk slabs, four colourways (E 2_City_Terrains, 32x32 cells at x=332/1292,y=40; 364,1512; 1292,776): cream #dad4cb, light grey #b4b4b4, mid grey #919498, blue-grey #8f9aa8. All seamless; the grout cross is 1 px at the cell edges. |
| kerb | 3 | `kerb_cream/grey/blue_autotile` (E Autotiles Game_Maker sheet y=2304 / 2560 / 2944, 384x128): the sidewalk-with-kerb autotiles (47 cells); cut the straight and corner cells for kerbs on our own geometry. 6 sidewalk autotiles exist (y=2304..2944); only 3 colours listed. |
| road paint | 3 | `road_crosswalk` (E 2_City_Terrains 162,128), `road_lane_white` / `road_lane_yellow` (autotiles y=3072 / 3200: line, T, cross, corner and dash cells on asphalt). Also in 2_City_Terrains: arrows, STOP/EXIT/TURN, BUS lanes, parking bays with P, dashed circle (not listed individually). |
| grass | 3 | `at_grass_flat` (autotile y=1536 cell 32,1536: flat **#479757**), `foliage_bush` (E 17_Garden 4,364, dense hedge foliage #64b63b #49a92f #2da233), `foliage_tree_villa` (canopy, needs-fix). Grass is a flat fill: no noisy grass tile exists. |
| dirt / sand / soil | 11 | Flat fills `at_dirt_tan` #aa8f5a, `at_dirt_orange` #ca8854, `at_dirt_grey_mud` #a29177. Sand: `at_sand`, `sand_terrain` (E 1_Terrains 552,1804), `sand_farm` (wavy dune pattern #e6ae55 #e09b4e, seamless). Gravel-like `gravel_worksite` (E 8_Worksite 20,556, orange pebbly dirt). Soil (F 1_Terrains): `soil_tilled` (furrows), `soil_dark` (wet), `soil_field_big` (plowed rows), `dirt_farm`. |
| water | 4 | `water_pond` (E 1_Terrains 548,1224, teal tile grid), `water_basic` (flat #3ca3b2, Animated_Terrains Water_Tileset), `water_sea` (animated sea frame with cell waves #5bc9d2 #41b5be), `at_water_deep` (#215c81). The animated sheets have 8 frames (96 or 128 px apart): cut the others for animation. Shore/edge pieces: the Autotiles sheet (y=256 grass-water, 896 sand-water, 1792 grass-pond, 2176...). |
| fence | 7 | Strips: iron picket (E 1_Terrains 540,582), iron picket on timber or orange base (540,796 / 540,1020), `wall_brick_cap` (802,590, brick low wall with stone cap), Farm picket / 3-rail wood / steel rail (F 2_Fences 32,36 / 606,196 / 4,196). More in the sheets: chain-link (E 1_Terrains ~800,870), wood and steel rail variants, gates, corner and diagonal pieces (F 2_Fences has 236 singles). |
| awning | 3 | Striped fabric strips: blue/cream, red/cream (E 4_Generic 304,5352 and 304,5420, 224x36) and red/white (16,5318). Tile along X every 56 px (one stripe pair). |
| trim | 2 | White dentil cornice strips (E 4_Generic 32,2960 and 416,1808, 128-144x40); more cornices and window sills inside the Condo_3/4/6 pieces and the Floor_Modular sheet. |
| door | 3 | Decals only: teal pair (E 12_Hotel 72,872), blue pair (E 15_Police 680,1858), wooden with iron straps (E 13_School 64,2040). The full door sets are the 9_Shopping and 5_Floor_Modular pieces already covered in PACK_EXTERIORS.md. |
| floor (Interiors) | 74 | `fl_{col}_{row}`: Room_Builder_Floors (I Room_Builder_Floors_32x32.png), 4 columns x 18 rows of 96x64 blocks; each id is the best 32x32 cell. Cover: marble/tile grids (fl_0_00,01,17), parquet and boards (fl_0_05,06,11,13,15), herringbone/diagonal (fl_0_14,16), ornate patterns (fl_0_12, fl_1_03), terracotta checker (fl_1_09), red checker, etc. Also `floor_tatami` (I 20_Japanese), `floor_gym_checker` (I 8_Gym). Full list: the sheets. |
| wallpaper (Interiors) | 54 | `wp_{col}_{row}`: Room_Builder_Walls flat bands (x=128/480/832, 96x32): diamond prints, plain colours, stripes, brick (wp_0_08 cream brick, 16 px), tile (wp_2_x), wood panelling (wp_1_x), dark plaster. Repeat is 32 px horizontal; the vertical flag is needs-fix on 6 because the band carries a baseboard shade. 3D wall pieces with caps are in Room_Builder_3d_walls (geometry parts, not cut). |
| carpet | 8 | Room_Builder_Floor_Paths fill cells (`carpet_path_0..6`, flat colours: cream, mint, mauve, pink, yellow, brick weave, ochre) and `carpet_generic_purple` (I 1_Generic 398,686, Persian-style purple rug). The Floor_Paths sheet also holds rug borders and corners (autotile-like, 1344x384). |

Counts: floor 74, wallpaper 54, siding 13, dirt 11, carpet 8, roof 7, fence 7, stucco 6, concrete 5, metal 5, brick 4, stone 4,
pavement 4, water 4, glass 3, roof-flat 3, grass 3, awning 3, roadpaint 3, kerb 3, door 3, trim 2, asphalt 1 = 230
entries (146 seamless, 84 needs-fix, of which 69 are strips, decals, autotiles or conservatively flagged interior patterns).

## Suggested uses

- **Brick shops and flats**: `brick_red_terrace` for the 1950s main street, `brick_brown_weathered` for industrial; cap with
  `trim_cornice_*`, awnings from `awning_*`, sidewalk `sidewalk_*` in front, `asphalt_plain` for the road; the road
  can carry decals `road_crosswalk`, lane autotiles.
- **Houses and farms**: Villas siding colours for walls, `roof_tile_*` for pitched roofs, `siding_barn_*` plus `roof_tile_red`
  for barns, `soil_*` and `fence_farm_*` for fields, `boards_*` for decks and boardwalks.
- **Flat-roofed shops and offices**: `roofflat_*`, `concrete_office_light` panels, `glass_mod_*` window grids,
  `metal_corrugated_hotel` for warehouses and `metal_shutter_*` for garage doors.
- **Interiors built as geometry**: `fl_*` for floors, `wp_*` for walls, `carpet_*` for rugs.

## Gaps: not in any pack, to paint in the LimeZu palette

1. **Grass with texture** (blade clusters, mown stripes, clover): the packs only have the flat #479757 fill and decals; paint a 32x32 tile in the same 3-4 greens (#479757 plus lighter #64b63b / darker #268b49 flecks).
2. **Gravel / ballast** for flat roofs, drives and rail beds: only the orange `gravel_worksite`; paint grey pebble noise from the asphalt palette (#534f52 #595555 plus #9da3b7 flecks).
3. **Flat-roof membranes** (EPDM/black, white reflective): only tan, grey-brown and blue-grey panels; paint black tar paper and a white membrane with a 32 px seam line.
4. **Poured concrete and concrete block** (wall, with form-tie holes or block courses): only panelised pale concrete; no block bond.
5. **Stucco/render with texture** (sprayed, dashed pebble-dash): the stuccos are flat colour with panel lines.
6. **Weatherboard / clapboard** (overlapping horizontal siding) and **shingle siding**: only vertical battens and flat boards; paint horizontal lap siding in the Villa colours.
7. **Cut stone / ashlar / granite** and **marble** exteriors: only cobble and rock-face pictures; stone walls exist only inside building sprites.
8. **Plain terracotta pantiles / metal standing seam** are present, but **asphalt shingles** (the US-style three-tab roof) are not: paint from the slate palette.
9. **Window frames, sills and lintels as strips**, **door frames in wood/metal colours**: only inside whole-building art; paint plain strips.
10. **Curb as a straight tileable strip with top face**, **drain grates, manhole covers**: kerbs exist only as autotile cells.
11. **Dirt road tracks, mud, puddles, snow, leaf litter, wet asphalt**.
12. **Signage materials** (painted wood sign board, neon, enamel plates): only finished signs (HARDWARE, MUSIC STORE, BUS).
13. **Plaster/drywall interiors with wear**, **linoleum**, **commercial carpet tile**, **cork**: the Interiors set is rich in floors and wallpapers but has no scuffed/worn variants; add wear as decals.

## How to regenerate the sheets

Join the split zips into a scratch dir (`packs/join.sh <dir>`), extract the sheets named in `file`, crop `rect`, tile 3x3
nearest-neighbour. The coordinate picks were found with a patch finder (opaque 40 px windows with uniform colour
statistics per quadrant), then checked by eye on 3x3 tilings.
