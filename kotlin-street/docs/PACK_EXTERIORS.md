# The rest of LimeZu Modern Exteriors: what we have not used yet, and how to build it

An audit (2026-11-19) of the full pack (`kotlin-street/packs/Modern_Exteriors_v42.3.zip.part*`, rebuilt with `join.sh`)
against what the game already uses. It continues `SPRITES_3D.md` (which audits the ~650 curated sprites) and uses its
vocabulary: **easy box** (flat elevations on `build_model` boxes), **roof machinery** (pitched slope, front gable,
hipped roof, flat roof cut from above, `cyl`/`wall` parts), **new geometry**. Pipeline: `tools/make_buildings.py`
(`build_model`, `build_house`) writes `buildings/models.json` + an atlas, Filament builds it
(`domain/ModelGeometry.kt`, `scene3d/BuildingMeshes.kt`).

Licence: use and edit in our game only; credits required (limezu.itch.io). No raw pack files are in this document's
commit; the contact sheets below live outside git (scratch dir, regenerate from the zip when needed).

## How "already have" was decided (exact, not by eye)

`scripts/extract-assets.mjs` names every source file we use (from the **32x32** twin of each theme sheet), so the used
set is exact. The 48x48 sorter has the same file names and layout as the 32x32 one (6 202 vs 6 201 singles in the zip;
the pack also ships 16x16). Our whole pipeline (models.json pixel units, `house_rise`, atlas rects) is 32 px. **Checked: the 48 px art is an exact nearest-neighbour 1.5x upscale of the 32 px art** (Villa_1: 0 of 269 568 pixels differ), so it adds no detail, only memory. Keep cutting from 32 px; the 48 px names and layout are the same, so either sorter can be read.

Used so far: **277 of 6 222** theme singles (4.5%), plus the 72 `classic_*` fronts derived from theme-5 shop fronts.

| Theme (48x48 sorter) | Singles | Used | What the theme is |
|---|---:|---:|---|
| 1 Terrains and Fences | 462 | 64 | grass, water shore, fences, walls, mounds, dirt |
| 2 City Terrains | 351 | 7 | asphalt (27), sidewalk/curb autotiles (324) |
| 3 City Props | 716 | 28 | traffic lights, kiosks, trash, water towers, billboards, trucks, EV chargers |
| 4 Generic Building | 180 | 6 | Condo 1-9, Hardware Store, shop tents |
| 5 Floor Modular Building | 343 | 106 | the modular ground/middle/roof system (full 7-wide and 48-wide slices) |
| 6 Garage Sales | 95 | 0 | narrow run-down row houses, shutters, tables, graffiti |
| 7 Villas | 194 | 5 | 5 villas, toy house, tree house, yard props |
| 8 Worksite | 71 | 0 | building skeleton, excavator, cones, fences, toilets |
| 9 Shopping Center and Markets | 57 | 0 | 30 market fronts, a mall, doors, signboards |
| 10 Vehicles | 273 | 0 | 26 cars, 6 buses, ambulance, camper, food trucks/carts, gas station |
| 11 Camping | 693 | 16 | mobile homes (24: 16 used), tents, piers, 348 tree variants |
| 12 Hotel and Hospital | 41 | 2 | hotel, hospital, their modular strips |
| 13 School | 125 | 3 | school, clock tower, courts, yard toys, playground tubes |
| 14 Swimming Pool | 179 | 0 | pool autotile (88), sunbeds, umbrellas, lifeguard chair, toys |
| 15 Police Station | 37 | 3 | station, cars, barriers, helicopter, booth |
| 16 Office | 116 | 0 | modular office towers (left/middle/right), balconies, diagonals, roof props |
| 17 Garden | 570 | 23 | bushes, fountains (used), palace, greenhouse, statues, planters, sheds |
| 18 Fire Station | 42 | 1 | station, 12 fire-truck views, hydrant, fire pole tubes |
| 19 Graveyard | 217 | 0 | gothic chapel, gates, crosses, tombstones, coffins, pumpkins |
| 20 Subway and Train Station | 581 | 0 | tunnels, rails, trains (5 liveries, ext/int), subway stair structures |
| 21 Beach | 316 | 0 | stages, lighthouse, bars, volleyball, sand, shells |
| 22 Post Office | 44 | 4 | office building, mail trucks, mailboxes, packages |
| 23 Military Base | 305 | 0 | dormitories, hangar, bunker, tanks, helicopter, radio tower |
| 24 Additional Houses | 214 | 9 | terraced (used), country/Japanese/modern/one-story (used), **Victorian x7, Haunted, Post-apocalyptic** |

Contact sheets (outside git, in the scratchpad dir `.../scratchpad/packaudit_sheets/`): `00_TOP10_unused_buildings.png`,
`03_city_props_big`, `04_generic_building`, `04_condo_1_2_5_modular_narrow`, `04_condo_3_pieces`,
`04_condo_4_red_brick_pieces`, `05_modular_middle_floors`, `05_modular_shop_ground`, `05_modular_condo_ground`,
`05_modular_roofs`, `05_modular_assembled_demo`, `06_garage_sales`, `07_villas`, `08_worksite`, `09_shopping_markets`,
`10_vehicles`, `11_camping`, `12_hotel_hospital`, `13_school`, `14_pool`, `15_police`, `16_office`,
`16_office_pieces_and_assembly`, `17_garden`, `18_fire`, `19_graveyard`, `20_train`, `22_post`, `23_military`,
`24_additional_houses`. All are desktop contact sheets of the raw art, not S25 captures.

## Per theme: unused buildings, 3D plan, what the town needs them for

Plans use: **E** easy box, **R** existing roof machinery, **N** new geometry. Counts are 48 px singles.

### 4 Generic Building (180; 6 used: Condo_3_44/45, Condo_5_1-3, Condo_9)
Unused whole buildings (7) plus modular piece sets:
- **Condo_4_38 / 4_39 / 4_40** (3; 768x864 and an octagonal tower 288x768): red-brick four-storey **corner block**, a
  chamfered corner (one mirrored of the other), stone arch door, white cornice, flat tan roof from above. Plan **E+**: two
  boxes meeting at a `wall` part (the chamfer, as the police tower) and a flat roof cut from above (our roof with the
  cornice colour); 4_40 is the same with an octagon (`cyl` with 8 facets, or `wall` x8). Need: the downtown corner on
  every junction of a bigger town (1950-2020s: brick is timeless); apartments over shops. **37 more Condo_4 pieces**
  (corner joins, windows, doors, balconies, roof strips, planters) make it modular: see the modular section.
- **Condo_6** (+ 14 parts: top/bottom windows x3 colours, roof air units, trapdoor, 624x768 example): grey-stone four-storey
  with arched windows and a garage shutter, a stepped lower roof with an AC unit. Plan **E** (two boxes, two flat roofs).
  Need: 1950s-70s mixed-use block, bank/office feel.
- **Condo_3_46** (336x864, five storeys; 3_44/45 are the 1- and 2-storey siblings we use): **E**, the same recipe as
  `condo_3_44/45` (flat roof from above cut off). Need: taller downtown building in the same family as the ones we have.
  Also **Condo_3 pieces 1-43**: 11-storey side columns (528 tall, 1-4), roof strips (5, 9), doors (10-12), windows (13-43).
- **Condo_8** (240x768; Balcony_Railing x3, Fire_Escape_Ladder x3, Flyers x4, roof window): five-wide apartment tower
  whose balconies stack on its front, with a fire escape. Plan **E+**: box plus balconies as lit boxes (the law office's
  balcony recipe `_balc`), the ladder as a thin card. Need: tenement/apartment of the 1950s-80s.
- **Condo_7 modular tower** (Modular_Bottom 144x240, Middle 144x96, Top 144x336, Window x5, Roof_Air_Unit, three examples):
  a **3-tile-wide tower of any height** (stack N middles). Plan **E** and it is the simplest modular piece in the pack.
- **Condo_1, Condo_2** (2 colours x 10 pieces: 144-, 96-, 48-wide columns, 576 tall (4 floors + roof) and 480 tall
  (3 floors); Garage_Sales Building_1/_2 are the same system in yellow and grey): narrow **row-house / terrace
  system**: any width = 3a + 2b + c tiles, two heights. Plan **E**.
- **Hardware_Store** (384x432; Air_Unit x2, Roof_Sign 384x240 (the giant hammer), Tent x2): a shop with a striped awning
  and a roof sign. Plan **E**: front box, awning as a sloped box (the ice-cream parlour awning recipe), the hammer a
  roof card like the other roof props. Need: a store variant a small town surely has.
- **Shop_Tent** x4 (192x96, 240x96 striped awnings): awnings for any of our shop fronts. Small sloped boxes. **E**.

### 5 Floor Modular Building (343; 106 used: Roof 1-11, Condo ground 1-12, Middle 1-18, Roof_Props 12-16, 60 shop fronts)
The core system; see the modular section. Unused: **all 48-wide `_Modular` slices (30 middle + 24 condo ground + 36 shop
ground + 33 roof + 6 each for 7 shop kinds = 165)**, shop fronts we skipped by design (the even "shutters-down" and
"CLOSED" variants: 6+6+... about 70; useful as night/closed states and for 1980s-2010s dead-mall decay), **Ground_Floor_Gun_Store x3**
(336x192; optional, era/region dependent), **ATM** (96x144), Grate x6, Ice_Cream_Shop_Props x3, **Roof_Props 1-11** (11
rooftop units: AC, vents, tanks; cards). All **E**.

### 6 Garage Sales (95)
Building 1/2 (2 x 10 narrow-column pieces: see Condo_1/2), Shutter x12, Table x4, Box x11, Cloth x4, Graffiti x17, Sign x13,
Antenna x3, Air_Conditioner x3, Roof_Window x3. Need: a run-down block and **garage-sale days** (tables, boxes, cloth on lawns) as
a weekly event, graffiti for the 1980s. **E** (cards and the row-house system).

### 7 Villas (194; 5 used)
Unused: **Toy_House x8** (192x240 playhouses: front-gable, **R**, small), **Tree_House** (432x528: base + house + ladders;
**N**, a platform in a tree: boxes + ladder card), Lemonade_Stand x2 (**E**, a kid's stand: great small-town detail),
Villa_Props x25, Villa_Yard_Props x21, Yard_Toy_Rocking x8, Sand_Box x4 (+22 modular tiles), Trampoline x3, Basketball_Net x4,
Stomp x2, Yard_Flowers x13, Picnic x21, Armchair, Chimney x5, Solar_Panel x5, Doormat x2, Ladder x2, Roof/Round windows x10.
Backyard kit (the owner asked for play sets): all cards or small boxes. **E**, high value for yards.

### 8 Worksite (71)
**Building_Skeleton x2** (336x384/480: a frame of floors with rebar: **N-light**, boxes + posts, for a building under construction in
the growth events), Excavator x4 (cards), Light_Tower x2, Portable_Toilet x4, Scissor_Lifter x2, Fence x16 (48x96 strips), Cone,
Sign, Stacked_Material x7, Tool_Box x3. Need: construction when a town builds a new block between decades. **E**.

### 9 Shopping Center and Markets (57; none used)
**Market_Big x6** (336x576, 4 storeys over a MARKET front), **Market_Medium x12** (336x432), **Market_Small x12** (240x384, STORE sign), all with a
flat green/tan/pink roof from above: **30 store fronts** with striped awnings, in several colours: the extra STORE/MARKET variants
for our code-built stores. **Mall** (864x576; 6 Mall_Doors 192x144, 3 Signboards, 8 Props, 3 Shopping_Carts, Air-conditioner x6): a big
flat-roof box with a long glazed front, for the edge-of-town shopping centre (1960s onward). Plan **E**: front box, flat
roof from the art cut off (our roof with the edge colour), awning as sloped boxes, signboard as a roof card.

### 10 Vehicles (273; none used)
Each vehicle has Up/Down/Left/Right views (a view per heading, so no rotation needed): **Car x26 designs** (x4 views = 104), Bus x6
colours, Ambulance x2, Camper x4, Boat x3 designs, Gas_Station x2 (576x528 / 816x528, a canopy over pumps + a shop; props:
pumps, price boards, digits x10), Ice_Cream_Truck x4, Tacos_Truck x4, Hot_Dog_Cart x4, Pizza_Tuk_Tuk x4, Street_Food_Cart x6,
Bus_Stop x4 (+ sign x4), Public_Phone x2, Helicopter + pad. Plan: vehicles are **upright cards** per heading (as the trees are); moving ones
later with **Jolt** (CLAUDE.md). Gas station **N-light**: a canopy on posts (a flat slab on four boxes, the police board's
legs recipe) over the pumps and a one-storey shop box. Era: cars need a decade tag (the pack's cars are modern; the 1950s need a
recolour or fewer designs; mark in the data). Bus stop, ice-cream truck, taco truck: street life for any decade.

### 11 Camping (693; 16 used: Mobile_House_Small/Medium 1-8)
**Mobile_House_Big x8** (480x288, 480x384 with a deck): the third size of the mobile home; the same sprite family and the same
`build_house()` pipeline we use for Small/Medium (pitched roof cut, porch, hitch): **E, almost free**, and 8 designs x 10 colours = 80
more trailer-park homes. Also Tent x6, Campfire, Barbecue, Benched_Table x4, Chair x16, Table x8, Lantern, Pier x29 (+ barrels,
bridge, crates), Fishing_Spot, Sign x8, Signboard x2, Tree x348 (the town has 9 trees; this is the forest/variety),
Tree_Dead x28, Tree_Wall_Modular x9 (a forest wall). Cards. Need: trailer park, lakeside, forest edge variety, the backyard barbecue.

### 12 Hotel and Hospital (41; 2 used)
Hotel_Modular x5 (816 wide floor strips) and Hospital_Modular x4 (the hospital in pieces: the helipad block, emergency wing): the
hotel could be built to any height; the hospital's wings placed freely. Luggage_Trolley, Stretcher x23, Signboards. Low value now. **E**.

### 13 School (125; 3 used)
Unused: Basketball_Court x8 (624x624..768: **ground decals**), Soccer_Court x2 (576x816), Stands x3, Stadium_Light x4, Flag x2,
School_Yard_Toy x19, **School_Yard_Tube_Modular x57** (the playground tube set), Corridor_Modular x5, Entrance_Ramp x3, Railing, balls, nets, Bench x5.
Need: the school grounds (TOWN.md lists "school yard" as not yet done), and a park/playing field. Decals plus cards, **E**.

### 14 Swimming Pool (179; none)
**Pool x88** (autotile: edge, corner, step, deep water tiles), Sunbed x15, Umbrella x8, Lifeguard_Chair x4, Starting_Block x12, Toy x17, Toy_Floating x17,
Buoy, Palm x3, Lifesaver_Holder, Wooden_Tiny_Table x6. The pool is **ground decals** (flat water, with our AGSL water) and the rest cards.
Need: backyard pools for rich homes and a public pool (owner request in CLAUDE.md). Easy, high value. **E**.

### 15 Police Station (37; 3 used) and 18 Fire Station (42; 1 used) and 22 Post Office (44; 4 used)
Vehicles and props only: Police_Car x4, Parking_Booth x2, Police_Box x2, Barrier/Automatic_Barrier x5, Siren, **Police_Station_Small x2**
(336x624, a small rural station: **R**, a second police building for a small town!), Helicopter. Fire: **Fire_Truck x12 views** (down/up/left/right + ladder
extended), Hydrant + hose, Fire_Pole tubes x9, flame sprites x12 (effects). Post: **Mailboxes** (red/blue/black/grey, front and side,
the US blue box), Truck x5 views (+open empty/full), Packages x10, Letter. All cards. **E**. Need: the civic buildings come alive.

### 16 Office (116; none): modular office towers
Four styles (1-4: slate, grey, light blue...), each in **Left (96 wide) / Middle (144, repeatable) / Right (96)** in two heights (144 = one
floor, 288 = two floors), Window modules (fillers 96/144 x 96 for the floor between), Window_With_Shutter x4, **Balcony** sets (Left/Middle/
Right x2 styles x 4 colours), **Diagonal corners** (Bottom/Middle/Roof Diagonal Left/Right x 4: a 45-degree chamfer), Roof_Left/Middle/Right (240 tall,
a flat roof from above), Roof_Stairs, Roof props (air ducts x3, dish, solar panel, cabin), Building_Sign x2 (336x48), two
whole examples (Example_1 "LIME CORP", Example_2 "G-NERIC CORP", 576x912 and 912x864). Plan **E** with the facade kit.
Verified by assembling Left + Middle x2 + Right, two double floors and a ground piece (sheet `05_modular_assembled_demo`):
it stacks and tiles seamlessly. Need: bigger towns, an office district from the 1970s, banks and insurance.

### 17 Garden (570; 23 used) and 3 City Props (716; 28 used)
**City props** are the cheapest big win: Traffic_Light x16 + New_ lights (diagonals, red/orange/green: 40+), Phone_Booth x4, Parking_Meter,
Dumpster x6, Trash_Can (~40 colour/open/full variants), Hydrant x3, Mailbox, Electric_Pole x4, Electric_Box, **Water_Tower x3** (the
small-town icon: 240x336, **N-light**, a tank on legs: cylinder + 4 posts), Kiosk x4 (Coffee_Cup, Ice_Cream giant cone, Mushroom, Infopoint:
street vendors/info stand; each 1 box + a sign), Billboard x8 (5 as roof props already), Container_House, Junk_Shack, Power_House
(336x384), Wind_Turbine (288x480; 2010s), EV_Charging_Station x2 (2020s), Drone x5 (2020s), Solar_Panel, Car_Wreck x13, Barrel x15, Cone x8,
Danger_Sign x16, Traffic_Barrier x9, Pedestrian_Barrier, Trash trucks (green/orange x4 views), Pigeon x2, Hanging_Clothes x7 (clotheslines for
backyards), Manhole x8, Brake_Marks x9. Era gating: phone booth up to ~2005, EV charger/drone/solar 2015+, wind turbine 2010+.
**Garden**: **Palace x2 (1200x1632 examples + 6 entrance variants)**: a huge cream-and-gold hotel/mansion for the richest lot or a
grand civic building (**N-heavy**, many hipped volumes: low priority), **Large_Greenhouse** (384x384, layered: a garden centre; **N-light**,
glass boxes), Angel_Statue x4, Wood_Storage sheds x9 (backyard sheds! 3 colours with door and roof), Bird_House x6, Vases/Flower beds (~150),
Big_Bench x4, Watering_Can x4, Grass_Wall/Arch/Gate, Big_Stairs, Flowers_Bench. All cards/E; sheds and benches are on the backyard
checklist in CLAUDE.md.

### 19 Graveyard (217; none)
**Grace_Chapel x2** (240x432, with and without gate: a gothic chapel, tall front gable with a rose window and brown steep roof: **R** front gable + a
narrow nave; the church of the town, with a cemetery), Big/Medium/Small/High Gate x4 (+red orb x4), Cross x16 (black/brown/grey), Tombstone
x~12, Coffin x~60 (open graves/funeral scenes), Dirt_Ditch, Grave_Cage, Candle_Holder, Pumpkins x~30 and Pumpkin trees (**a Halloween
event**), dead trees. All cards or decals except the chapel. Need: church, cemetery, Halloween.

### 20 Subway and Train Station (581; none)
**There is no station building in the pack.** It has Stairs_Structure x2 (288x288 subway entrances), Tunnel cornice/side/shadow pieces (~120), Rail
horizontal/vertical/crossing/shadow (brown, grey, broken), Trains in 5 liveries (blue, green, orange, white, grey) each in Exterior/Interior/Example
and Left/Middle/Right pieces (432-480 x 192 long cards; the front views 144x432), Gallery, Graffiti train (864x192), signs, machines (coffee, drink),
baggage, benches. Plan: a rail line as ground decals + a train as a long upright card sliding along it (**E**), and a station building made from a
brick civic block (Victorian_House_5 or the Condo_4 corner) + platform props, labelled honestly as our composition. Low priority for a small town,
high for the 1950s (depot) or a commuter line.

### 21 Beach (316), 23 Military Base (305)
Beach: Lighthouse (336x768, with variants: a landmark for a coastal town), stage x4 (528x528, 336x528), bamboo bar, volleyball, sand mountains, shells,
loudspeakers, DJ set, ship bar (336x240). Military: Dormitory x4 (528x384-576), Hangar (528x624 + modular), Bunker (384x576), Tanks (2 colours x 20 views),
Helicopter (8 views), Radio_Tower (384x1008), Watch_Tower, Tents x4, External_Wall x4. Outside a small American town unless a base/coast is added;
lighthouse and radio tower are the only two worth considering (**N-light**). Lowest priority.

### 24 Additional Houses (214; 9 used)
Unused: **Victorian_House 1-7** (7 pieces: see below), **Haunted_House x2** (432x432-ish, gothic mansion; **R+N**, towers with pointed roofs;
Halloween/abandoned house), Post_Apocalyptic_House x2 + generator/wind-mill/solar (props; wind mills on a wood tower), Terraced_House_3
(the taller one, skipped), Terraced Side_View_Base/Roof x10 (side views of the terraced roofs), Modular_1-6 (terraced fronts without the roof),
Fence_1/2 sets x~60 (fence autotiles with corners and a gate), Modern_House props (glass-railing modular, deck chairs, umbrellas, tables).
**Victorian** (the best old-town buildings): V4 (clock tower at the left, a long brick civic block with a cornice and a flat roof behind a parapet:
**town hall / old school**), V5 (a long brick block with a rounded-window tower at the left and a hipped roof: **old school, depot, library**),
V1 (a corner mansion with a roof of cross gables and a tower: large house, **boarding house**), V6 (a brick block with a mansard front), V2/V3
(narrow wings with a tower), V7 (tower only), plus a side entrance. Plan **R**: hipped roofs with the school's machinery (`landmark_school`) and the clock tower
(`landmark_clocktower`); the towers are the clock tower's recipe. Need: town hall, old school, library, the pre-1950 centre of the town.

### 1 Terrains and Fences (462; 64 used), 2 City Terrains (351; 7 used)
Ground decals: Grass autotiles 87+22 fenced, Grass_Water x91 (more shore variants), Deep_Water x22, Mound x9, Fence x54 (+30 props), Wall x58
(+12 props), Dirt props x30, Sidewalk x324 (curb corners, crossings), Asphalt x27. The sidewalk/asphalt autotiles give **proper curbs,
crosswalks and road markings**: high visual value for streets, not a "building" task. Ground decals, **E**.

## Ranked build order (most visible value first)

| # | What | Count | Plan | Why first |
|--:|---|---:|---|---|
| 0 | **Modular facade kit** (themes 5 + 16 + Condo_7/Condo_1/2): downtown blocks and apartments of any width and height | 165 slices + 116 office + 20 + 15 | tool + data format below, no new engine code | one tool unlocks every block, every height, many colours |
| 1 | **Markets, Mall, Hardware store, tents** (theme 9 + Hardware_Store + Shop_Tent) | 30 + 1 + 1 + 4 (+22 parts) | **E**, flat roofs cut, awnings as sloped boxes | 30 store variants next to our code-built stores; the mall for the edge of town |
| 2 | **Mobile_House_Big** (theme 11) | 8 designs (x10 colours = 80) | **E**, the same `build_house()` | near free: more trailer-park homes |
| 3 | **Red-brick corner blocks Condo_4_38/39/40 + Condo_6, 3_46, 8** | 7 + 37 pieces | **E+** (`wall` chamfer) | the iconic downtown corner and apartment variety |
| 4 | **City props era set** (traffic lights, phone booths, water towers, kiosks, dumpsters, EV chargers, mailboxes) | ~120 chosen of 716 | cards; water tower N-light | streets stop looking empty; decade dressing |
| 5 | **Victorian civic buildings** (V4 town hall, V5 old school/depot, V1 boarding house, V2/3/6/7) | 7 | **R** (hipped roofs, clock tower, school machinery) | the old centre, the town hall the game needs |
| 6 | **Backyard and school-grounds kit**: pool (88 tiles), sunbeds, villa yard props, toy house, sheds, courts, yard toys, barbecue, clothes | ~450 | decals + cards, **E** | owner's "backyards complete" instruction |
| 7 | **Church and cemetery** (Grace_Chapel, gates, crosses, tombstones; Halloween pumpkins) | 2 + 215 | **R** (front gable) + cards | a town needs a church; an October event |
| 8 | **Office towers**, dressing (roof props, balconies, diagonals, signs) | 69 families | the kit (0) plus cards | bigger towns |
| 9 | **Vehicles**: parked and moving cars (26), buses, ambulance, food trucks, gas station | 273 | cards per heading; gas station **N-light** | life on the street; Jolt later |
| 10 | **Civic vehicles and props**: fire truck, police car, post truck, mailboxes, hydrant, small police station | ~80 | cards, **R** for the station | the landmarks come alive |
| 11 | **Worksite** (building skeleton, excavator, fences) | 71 | **E**, skeleton **N-light** | growth events between decades |
| 12 | **Rail** (tracks, a train card, subway stairs) and a composed station | 581 | decals + long card | commuter line, 1950s depot |
| 13 | Hotel/hospital modular strips, Haunted House, Post-apocalyptic, Garage sales | ~150 | **E/R** | variety, events |
| 14 | Palace, greenhouse, lighthouse, radio tower, military, beach | ~900 | **N** | out of scope for a small American town unless a coast or a base is added |

`00_TOP10_unused_buildings.png` is the ten best-looking unused buildings (not the build order): Condo_4_38, Victorian_House_4, Market_Big_3, Condo_6,
Office_Example_2, Hardware_Store, Mall_1, Gas_Station_1, Grace_Chapel_With_Gate, Victorian_House_1.

Era table (1950-2020s), for the generator: 1950-70s: Victorian civic, brick corner blocks, markets, phone booths, trailer parks, depot;
1960-80s: mall, offices, graffiti, gas station; 1990s-2000s: ATM, offices, billboards; 2010s-20s: EV chargers, drones, solar, wind turbine, dead malls (the
"CLOSED" shop variants). Cars need a per-decade pick (unverified: the pack's cars are modern).

## The modular building system (themes 5 and 16, plus Condo_7, Condo_1/2, Condo_4, Garage_Sales)

### What is in the pack (verified by assembling the pieces, sheets `05_modular_*` and `16_office_pieces_and_assembly`)
Tile = 48 px (32 px in the 32x32 set).

**Theme 5, the facade kit.** Each building is three layers stacked in screen space: roof (drawn from above, deep), N middle floors, ground floor.
- **Full pieces, 7 tiles (336 px) wide**: Ground floors (3 tiles tall = 144: Shop x36, Condo x12; taller where a sign or the
  goods hang above: Music 192, Bakery/Butchery/Gym/Ice cream 240, Bait 288), **Middle_Floor x18** (4 tiles = 192, three palettes of 6: brick, blue, grey), **Roof x11**
  (336x288 or 336x336, flat roofs from above: teal, stone-edged, and the pitched variants we already use). All of these we already use.
- **48-wide `_Modular` slices**: a facade is a row of **2-tile motifs**, each motif two slices (left half, right half), because a window or balcony
  straddles the slice boundary. Middle: **5 motifs** (single window, U-shaped balcony, window with a sign, two small windows stacked, tall narrow window)
  **x 2 halves x 3 palettes = 30**. Condo ground: **4 motifs** (small windows, ATM, standpipe and meter, tall window) x 2 x 3 = 24. Shop ground:
  **6 motifs** (door, display window, shutter, plant, goods, ...) x 2 x 3 = 36; each shop kind (Bait, Bakery, Butchery, Gun, Gym, Ice cream, Music)
  has **one signature motif** (6 slices = 2 halves x 3 palettes). Roof: **33 slices** in colour sets: roof columns (48x288/336: the tile
  or flat roof's depth, 2 per colour, so a roof is N columns across), and **corner and end pieces** (48x96 and 48x48, left/right) at the top and
  bottom edges of the roof rim.
- **1-floor versions**: ground floor directly under a roof (what the game does for one-storey shops). Heights: 1 floor = ground + roof; any number of
  middle floors in between; mixed palettes only by design (the three palettes share the same slice grid: brick, blue and grey slices are interchangeable by motif).
- **What the slices do not carry**: the full pieces have a 3 px dark pilaster at each edge; the modular slices have **no edge frame** (checked
  numerically: no slice equals any tile of a full piece). A modular facade therefore needs end caps: generate a 3 px pilaster strip per palette in
  the tool (colour from the full piece's edge: the existing `extract-assets.mjs` already rebuilds walls by tiling a clean 8 px strip, same technique).
  Also no plain-wall slice: odd widths need a **1-tile filler** cut from a clean wall area of a slice (brick courses repeat). Both are one-off tool jobs.
  The roof's corner/end slices (14-33) are expected to carry the rim; the exact mapping of which slice is which corner is **unverified** and needs a
  one-hour trial render with Filament before committing.

**Theme 16, Office.** Four styles (1-4). Per style: **Left (2 tiles), Middle (3 tiles, repeatable), Right (2 tiles)**, each in a **288 (two floors)**
and a **144 (one floor, or the ground floor with the lobby/shutter)** height (`..._2` suffix is the 144 version; style 3 has only a 288 middle, style 4 only a 144 middle,
so use per-style tables); Window modules for plain floors; roof **Roof_Left / Roof_Middle_Modular / Roof_Right** (240 tall, 2 / 3 / 2 tiles: the same
column rule); Roof_Stairs (+Left); balconies (Left 1, Middle 2, Right 1 tiles, 4 colours, with Balcony_Window fillers); **diagonal corner set** (Bottom, Middle,
Roof; Left and Right; 48 wide: a 45-degree chamfer). Width of a tower = 2 + 3k + 2 tiles (7, 10, 13, ...). Verified assembled: L + M + M + R, two double floors
and a ground piece stacks and tiles exactly (`05_modular_assembled_demo`, panel C).

**Other modular sets** (same idea, different looks): Condo_7 (a 3-wide tower: Bottom 144x240, N x Middle 144x96, Top 144x336, Windows x5),
Condo_1/2 and Garage_Sales Building_1/2 (columns 144/96/48/48 wide, 576 or 480 tall), Condo_4 (a corner block in 37 pieces incl. chamfer join pieces and roof strips),
Condo_3 (columns and roof strips), Hotel_Modular x5 (816-wide floors), Terraced_House_Modular x6, Greenhouse layers, School corridor, Hangar.

### How the pieces combine (rules for the generator)
1. Choose a **kit** (palette + ground kind): `brick/blue/grey` x `shop|condo|<kind>` for theme 5, `office-1..4` for theme 16.
2. Width `W` in tiles. Theme 5: a ground floor is either one full 7-wide piece (W = 7) or a row of 2-tile motifs (+ 1-tile filler for odd W) between two edge caps.
   Office: W = 2 + 3k + 2.
3. Floors `F >= 0`: every floor is a row of motifs; each floor may pick its pattern (window, balcony, ...) from the kit's motif list; the top floor gets the cornice (the full pieces'
   top rows already carry it; in the slices it is the top band); the ground floor sits under the lowest middle floor.
4. Roof: a roof with `W` columns wide, and a depth (the roof piece's height: 288/336 = 6-7 tiles; for a shallower or deeper building repeat/cut the roof's middle rows, which are
   uniform gravel). Roof props (11 + 5 cards) on top; the sign above the roofline as a card.
5. Corner lots: Condo_4's chamfer (`wall` part) or Office diagonals (a 45-degree corner piece set), mirrored for the other corner.
6. The 3D build is unchanged: the tool **bakes the front picture** (atlas strip) of each floor and writes models.json parts exactly as for the current `middle`
   boxes (a box per floor with its `ft` rect, ledges, balconies as `_balc`, the roof as the existing flat/pitched machinery). So the engine, the corner turn and the
   lighting maps need no change; identical (kit, W, pattern) floors share one atlas rect.

### Proposed data format

`kits.json` (generated once from the pack by a tool, checked in without pixels beyond the atlas that make_buildings.py already writes):

```json
{
  "tile": 48,
  "kits": {
    "brick": {
      "palette": "brick",
      "edge":   { "cap": "brick_edge.png", "w_px": 3 },
      "filler": { "id": "wall1", "tiles": 1, "from": "Middle_Floor_Modular_1", "rect": [0, 0, 48, 192] },
      "middle": { "h_tiles": 4,
        "motifs": [
          { "id": "win",   "tiles": 2, "slices": ["Middle_Floor_Modular_1", "Middle_Floor_Modular_2"], "weight": 5 },
          { "id": "balc",  "tiles": 2, "slices": ["Middle_Floor_Modular_3", "Middle_Floor_Modular_4"], "weight": 1, "balcony": true },
          { "id": "sign",  "tiles": 2, "slices": ["Middle_Floor_Modular_5", "Middle_Floor_Modular_6"], "weight": 2 }
        ],
        "full": ["Middle_Floor_1", "Middle_Floor_2", "Middle_Floor_3"] },
      "ground": {
        "shop":  { "h_tiles": 3, "full": ["Ground_Floor_Shop_1"], "motifs": [ { "id": "door", "tiles": 2, "slices": ["Ground_Floor_Shop_Modular_1", "Ground_Floor_Shop_Modular_2"] } ] },
        "condo": { "h_tiles": 3, "full": ["Ground_Floor_Condo_1"], "motifs": [ { "id": "atm", "tiles": 2, "slices": ["Ground_Floor_Condo_Modular_3", "Ground_Floor_Condo_Modular_4"] } ] }
      },
      "roof": {
        "flat":   { "columns": ["Roof_Modular_1", "Roof_Modular_2"], "corners": { "tl": "?", "tr": "?", "bl": "?", "br": "?" }, "depth_tiles": 6 },
        "pitched":{ "columns": ["Roof_Modular_3"], "gable": true }
      },
      "props": ["Roof_Props_1", "Roof_Props_2"]
    },
    "office-1": {
      "left":  { "up2": "Building_1_Left", "up1": "Building_1_Left_2" },
      "mid":   { "up2": "Building_1_Middle_Modular", "up1": "Building_1_Middle_Modular_2", "tiles": 3 },
      "right": { "up2": "Building_1_Right", "up1": "Building_1_Right_2" },
      "window": "Building_1_Window_Modular",
      "roof": { "left": "Roof_Left", "mid": "Roof_Middle_Modular", "right": "Roof_Right", "stairs": "Roof_Stairs" },
      "diagonal": { "left": ["Bottom_Diagonal_Left_Building_1", "Diagonal_Middle_Modular_Left_Building_1", "Roof_Diagonal_Left_Building_1"] }
    }
  }
}
```

A building spec as the town generator emits it (`tools/town-buildings.ts` would add `modular` beside today's `sprite`):

```json
{
  "id": "m-downtown-17",
  "kit": "brick",
  "widthTiles": 10,
  "ground": { "kind": "shop", "bays": ["door", "shopwin", "shopwin", "door"] },
  "floors": [ ["win", "win", "balc", "win", "win"], ["win", "sign", "win", "win", "win"] ],
  "roof": { "style": "flat", "props": ["Roof_Props_3"], "sign": "BANK" },
  "corner": null,
  "era": [1950, 2029],
  "seed": 12345
}
```

Rules: `widthTiles` = sum of `tiles` over a row's motifs + fillers; every floor row must equal `widthTiles`; `floors` length = storeys (0 = the ground floor under the roof);
a floor given as `{ "count": 3, "pattern": "win,win,balc", "seed": 7 }` is expanded by a seeded rule (`rand(seed, ...)` as everywhere in `src/`); `corner`
selects the chamfer set. The tool output per unique spec is one atlas strip per floor row plus the models.json parts, as today, so the generator in
`src/town/buildings.ts` only picks kit, width, floors, roof, and props.

### Work to do
1. A **one-hour trial**: assemble a 10-wide brick facade from motifs with edge caps and filler in `make_buildings.py`, render it in
   `gradle :desktop:preview -Dmode=filament-town`, decide the roof corner mapping (unverified above).
2. `tools/kits.py`: writes `kits.json` and the pilaster/filler tiles from the 48 px (or 32 px) pack.
3. `compose_facade(spec)` in `make_buildings.py` (bake + models.json), then the generator hook.
4. Office, Condo_7, Condo_1/2 follow with only a new kit entry each.

## Built (2026-11-19): the modular facade kit, item 1 of the build order

`tools/kits.py` (writes `tools/kits/kits.json` and 28 generated caps and fillers), `tools/buildings_pack.py` (`compose_facade(spec)`),
hooked into `make_buildings.py` (`pk_*` sprite keys; `--pack-showcase DIR`), `tools/pack-export.sh` (writes
`app/src/main/assets/pack/`). Not wired into the town generator. Preview: `python3 tools/make_buildings.py --pack-showcase DIR`, then
`gradle :desktop:preview -Dmode=filament-models -Dassets=DIR -Dstreet=0-3`. The kit works from the 32 px art
(`PACK_ART=.../ME_Theme_Sorter_32x32`, from `packs/join.sh`; the pack is never copied into the repo).

What the trial showed, against the audit's guesses:
- **Middle floors and condo grounds: as audited.** Motif m of palette p is the pair of slices (10p+2m+1, +2) (condo ground 8p+2m+1); every
  motif is 2 tiles. Slices carry no edge, so every row is capped with 3 px pilasters cut from the full floor of the same kind
  (`<palette>_<mid|condo|shop>_capl/capr.png`) and an odd width takes a 1-tile wall filler (`..._filler.png`). The brick courses are
  not periodic, so the filler is two clean strips laid end to end, every other one mirrored: no seam shows.
- **Condo doors and windows come from the full floors**, cut at tile edges (`door_glass`, `door_canopy`, `door_arch`, `door_wood`, `win2`,
  `bigwin3`; the cut's pilaster replaced by the wall beside it); the condo slices have no door.
- **Shop grounds: the audit was wrong.** The 36 `Ground_Floor_Shop_Modular` slices are single tiles (the left tile of a display window, a
  door, each open or shuttered), not 2-tile motifs, and no middle tiles of a display exist. The kit takes shops from the 7-wide full
  floors, cut into a 5-tile display (`dispK`) and a 2-tile door (`doorK`), so a shop ground is 7, 14, ... tiles, or a display alone (5), a
  door alone (2..4), plus fillers. Shuttered variants (`cdispK`) are in the data, not yet picked by the generator.
- **The U balcony motif is a flat decal on a bare wall** (it belongs next to a window in the full floor), so it has weight 0 in the
  generator; it needs real geometry (`_balc`) before use.
- **Roof corners: unverified guess was wrong.** Trial (`final/item1_roof_corner_trial.png`): `Roof_Modular_14/15` are the back-left/right
  end blocks of a parapet, `17/16` the left/right rim stubs (each ends in its outline and does not repeat into a rim), there are no front
  corners: the slices cannot frame a roof. The columns do carry the back cap and inner face, the floor and the front cap (rows 2-12,
  12-26, 44-164, 166-180), so the tool takes those and builds the side rims as parapet boxes wearing a stub's coping
  (`apt.flat_roof`). Three colours (teal, gold, stone); the 224-tall grey roof set is not used.
- **Condo_7** (a 3-tile tower, N floors, window sprites laid over the middles, steps as boxes, the air unit a card), **Condo_1/2** (a row of 96/64/32 px
  columns, tall 384 or short 320; the right end closed with the mirrored pilaster; the roof's party-wall rims show between columns,
  as in a terrace) and **office styles 1 and 2** (ground Left_2 + k x Middle_2 + Right_2, n blocks of two floors, width 4+3k tiles; styles 3
  and 4 lack a ground or a middle piece) are kit entries in the same tool. The office roof's inner face is 36 rows tall: the tool uses its lowest 14.
- The side wall is each floor's wall rows with windows (`side_segment`; the ground floor's from a clean wall tile, since the shop display would colour it
  dark), without corner boards (the back wears the side picture repeated, and boards showed as seams). Sill courses stand 3 px out.
- Scale checked beside `condo_3_45`, `condo_5_1` and `villa_1` (`final/item1_scale_street_*.png`): a tile is 32 px in both.
- Still to do: balconies as geometry, roof props (the 11 + 5 cards) and signs, shuttered shops, office styles 3 and 4, the diagonal corner pieces, a
  per-floor window-light randomisation, the generator hook (`tools/town-buildings.ts` emitting `modular` specs; the spec format is
  `compose_facade`'s).

## Honest limits of this audit
- Done by reading the file names and sizes of all 6 222 singles and looking at contact sheets of the buildings, the modular sets and every important theme;
  props themes (garden 570, camping 693, beach, military) were inspected by name and size, not sprite by sprite.
- Mapping of which roof slice is which corner, the exact meaning of each shop motif, and the pack cars' era are **unverified**.
- No S25 measurement is involved: triangle and texture costs of any of this on the phone are estimates until built.
- The Haunted House is a Halloween/abandoned-house asset; the Gun Store front is optional.
