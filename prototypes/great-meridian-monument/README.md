# The Great Meridian Monument (Revision 2)

A 106.2 m × 120.0 m national civic landmark spanning four historical eras (**1926**, **1941**, **1976**, **2000+**) with three levels of detail (**Near**, **Mid**, **Far**), built on the strict `0.0388 m/texel` (`0.0776 m/art px`, 2×2 PNG) pixel grid using `NEAREST` + `REPEAT` sampling and 12 materials per state.

---

## What Changed in Revision 2

1. **Four Colossal 8.2 m Sculpted Art Deco Guardian Statues (44.5–52.8 m):**
   - Sculpted human 1920s Art Deco figures (`~950–1,180` tris each) with anatomically tapered legs/stances, pinched waists, V-tapered broad shoulders, sculpted forearms/hands, chiseled 12-faced heads with brow/nose/cheekbone planes, and distinct headgear standing on cantilevered 45°-angled dark-granite corner ledges (`±16.30 m`) silhouetted cleanly against open sky from the reflecting pool and plaza:
     - **SW — `DEFENSE`:** parted greaved warrior stance, battle-kilt, sweeping back cloak, crested Art Deco helm, hands gripping the pommel of a planted diamond-section broadsword + 45°-angled embossed round shield at side.
     - **SE — `LEARNING`:** contrapposto draped robe with forward bent knee, laurel-crowned head, 3D open V-book cradled at chest + sky-soaring raised right arm holding a glowing bronze torch (`< 0.14 m²`).
     - **NW — `INDUSTRY`:** wide trousered worker stance, riveted saddle-brown leather apron with crossed suspenders, peaked industrial cap, heavy sledgehammer over shoulder + 1.5 m spoked gear wheel at the feet (**chipped upper-right quadrant in 1976**).
     - **NE — `HARVEST`:** contrapposto draped robe, wheat-wreath crown, multi-bundle flaring wheat sheaf cradled in arm, raised 5-segment crescent sickle silhouetted against the sky + octagonal woven harvest basket at feet.
   - Smooth chiseled 3-tone ivory limestone, warm sculpted limestone shadow recesses, bleached horizontal tops, dark weather streaks in 1976, `0.6 m` incised ledge names (`DEFENSE`, `LEARNING`, `INDUSTRY`, `HARVEST`), and solid per-figure collision boxes.
2. **18.0 m Celestial Armillary Sphere in an Open Sky Vault:**
   - Scaled up from `15.2 m` to **`18.0 m`** outer diameter with solid `0.62 m × 0.31 m` gilded bands, bright top highlight pixels, `5.0 m` verdigris-ocean / pale-bronze-continent Earth globe (`armillary_globe`), `0.4 m` polar axis rod with bronze end bearings, and 12 raised 3D zodiac plaques on `armillary_ecliptic`.
   - Removed all diagonal crossing truss ribs that previously obscured the sphere; kept **4 clean riveted top vault arches** (`y = 47–57 m`, above the sphere) plus vertical suspension rods from the 58 m deck so the vault opens straight to the sky.
   - Added four dedicated warm-gold sphere uplights (`flood_sphere_01..04`, `#FFD88A`, 2,800 K) on the inner pier faces aimed directly at the sphere.
3. **Three-Tier Art Deco Crown (58–106.2 m, ~1/3 Total Height):**
   - Rebuilt the upper tower into a three-tier stepped octagonal lantern (`13.0 m` → `10.5 m` → `8.0 m` across, `18 m` tall) with strong smooth-ashlar cornices, 8 flat vertical piers with chevron caps, 8 tall amber lancet windows, and **8 stepped flying buttress fins** sweeping down to the 58 m deck corners.
   - Added an integrated 16-rib gilded sunburst drum (`76–79 m`), 4-ribbed tapered copper spire (`79–101.2 m`), open 8-strut bronze beacon cage (`101.2–103.7 m`) with glowing white orb (extinguished in 1976), and lightning finial (`106.2 m`).
4. **Three Distinct Stones, Weathering & Arch Craft:**
   - Split stone into **dark grey-pink granite** (`stone_granite`, `1.2 × 0.6 m` courses — acropolis, plinths, 0–6 m lower arch block, statue ledges, pool coping, exedra), **warm pale limestone** (`stone_limestone`, `0.6 m` courses with 2-tone block variation, chipped corners, rain-splash base darkening, bleached tops, and era-graded cornice drip lines), and **smooth pale ashlar** (`stone_ashlar` — cornices, entablature friezes, setback caps).
   - Added deep **coffered arch soffits**, alternating light/dark **carved voussoir rings**, projecting **compass-star keystones** seated cleanly below the entablature, `1.0 m` **carved Art Deco chevron friezes** at both pier setbacks (`34 m` and `46 m`), and unobstructed incised friezes (`THE GREAT MERIDIAN`, `DEDICATED MCMXXVI`, `MERIDIAN EAST`, `MERIDIAN WEST`).
5. **Ground-Level Plaza, Exedra, Doors & Memorial Halls:**
   - **Reflecting Pool:** walkable `0.5 m` granite coping, clean lily-pad-free water at `y = 0.0`, 3 basins × 3 low water-white prism fountain jets (thin glowing rim at night), and a **semicircular granite exedra** at the far `+Z` end inscribed `ERECTED BY THE PEOPLE OF THE STATE MCMXXVI` with two bronze lamps.
   - **Grand Staircases:** 12–14 steps with mid-landings, central bronze handrails, massive cheek walls, and 4 eternal-flame tripod braziers at the main South flight.
   - **Open 5.0 m Bronze Doors:** two `2.2 m × 5.0 m` leaves folded flat at `90°` against the South arch reveals displaying 6 pixel-art relief panels (train, plough, ship, factory, school, bridge).
   - **Rotunda Floor:** `14.0 m` inlaid bronze-and-marble Compass Rose with `N/E/S/W` letters bisected by the continuous Meridian Line.
   - **West Memorial Hall:** carved dedication wall (`TO THOSE WHO BUILT, FARMED, TAUGHT AND DEFENDED`), 35 rows of carved name blocks, twin carved wreaths, and a low bronze museum rail.
   - **East Memorial Hall:** 1:35 scale monument model on a plinth + framed pixel-art State Wall Map with the Meridian Line running through it.
   - **Plaza Life & Cornerstone:** regular rhythm of lamp standards, 4 bronze benches per side, staircase planters, drinking fountain, formal `tree_01..08` rows, NE eye-height `"1926"` cornerstone with carved compass, and 2000+ trash bins, interpretive panels & bike rack.
6. **Distinct Four Historical Eras:**
   - **1926 (`great-meridian-1926.glb`):** bright fresh orange-brown copper spire, clean bright limestone, red-white-blue cornice bunting & flagpole-base bunting, timber dedication stand at South stairs, gas lamps, young trees.
   - **1941 (`great-meridian-1941.glb`):** streaky brown-green copper, slightly darkened limestone with faint drips, 1941–1945 bronze war plaque by main doors, stacked olive-tan sandbag emplacements at South stairs, taped X-crosses on hall windows.
   - **1976 (`great-meridian-1976.glb`):** deep green black-streaked copper, heavy dark cornice drips & guardian streaks, chipped Industry gear, tarped SE pier scaffolding, ground-level SE chain-link fence, hand-painted `"CLOSED FOR REPAIRS"` North-stair sawhorse, beacon off, three broken lamps, weeds in paving joints.
   - **2000+ (`great-meridian-2000.glb`):** cleaned pale limestone, restored bright verdigris copper, visible LED flood fixtures, East accessibility ramp, SW Visitor Centre pavilion, interpretive panels, trash bins, bike rack.
7. **New Middle LOD (`great-meridian-mid.glb`) + Updated Far LOD (`great-meridian-far.glb`):**
   - Added `great-meridian-mid.glb` (`5,042` tris, target `4,000–6,000`) for `500–2,000 m` viewing: full Revision 2 silhouette including arches, piers, cantilevered human guardian silhouettes, banded sphere, 3-tier crown, flying buttresses, spire, exedra, and textured plaza slab (no interior or small props).
   - Updated `great-meridian-far.glb` (`330` tris, `< 400`) to match the 3-tier crown, open vault, and glowing beacon orb at `2,000+ m`.

---

## Verified Build & Validation Metrics

| Asset | Distance / Years | Triangles | Materials | UV Errors | Glow Area | Collision Boxes |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `great-meridian-1926.glb` | Near (1926–1940) | `28,203` / 30,000 | 12 / 12 | `0` (`max 0.022 m`) | `19.65 m²` | 113 |
| `great-meridian-1941.glb` | Near (1941–1975) | `27,833` / 30,000 | 12 / 12 | `0` (`max 0.022 m`) | `19.08 m²` | 118 |
| `great-meridian-1976.glb` | Near (1976–1999) | `28,297` / 30,000 | 12 / 12 | `0` (`max 0.022 m`) | `16.64 m²` | 116 |
| `great-meridian-2000.glb` | Near (2000+) | `29,017` / 30,000 | 12 / 12 | `0` (`max 0.022 m`) | `19.08 m²` | 115 |
| `great-meridian-mid.glb` | Mid (`500–2,000 m`) | `5,042` (4k–6k) | 12 / 12 | `0` (`max 0.008 m`) | beacon | — |
| `great-meridian-far.glb` | Far (`2,000+ m`) | `330` (< 400) | 12 / 12 | `0` (`max 0.001 m`) | beacon | — |

- **Coordinate Frame:** glTF metres, Y-up, `+Z` front, origin `(0, 0, 0)` at the midpoint of the `+Z` front curb.
- **Ground & Water:** Reflecting pool water sits at `y = 0.0 m`; plaza ground sits at `y = +0.155 m`; total finial height `106.16 m`.
- **Emissive Mask:** `glow_monument_mask.png` is `65.6%` pure black; non-glowing surfaces never glow; 1976 (`16.64 m²`) is dimmer than 2000+ (`19.08 m²`).

---

## Node Reference (see `model-info.json` for exact coordinates & vectors)

- **Animated / Dynamic Nodes:**
  - `armillary_outer` — fixed outer rings (`18.0 m` diameter)
  - `armillary_ecliptic` — rotates 1 rev / game day around tilted axis `[-0.39715, 0.91775, 0.0]` (`23.4°`)
  - `armillary_globe` — rotates 1 rev / game day around `[-0.39715, 0.91775, 0.0]` (`5.0 m` globe)
  - `beacon_beam` — sweeping white (`#FFFFFF`) aviation beam at `y = 102.59 m` (10 s period; inactive in 1976)
  - `lift_car` — open-cage bronze elevator in hollow NW pier (`y = 6.52 m` → `24.06 m` → `58.04 m`)
- **Lighting Nodes (`flood_*`, with direction & color in `model-info.json`):**
  - `flood_01`–`flood_12` — warm white 3,000 K (`#FFE4B5`) architectural stone floods on piers, arches, and upper crown
  - `flood_sphere_01`–`flood_sphere_04` — warm gold 2,800 K (`#FFD88A`) uplights on inner pier faces aimed at the 18 m sphere
  - Lantern lancets: amber (`#FFB347`); eternal flames: orange (`#FF7A28`)
- **Planting Nodes:** `tree_01`–`tree_08` (formal avenue rows; game spawns era-appropriate trees)
- **Walkable Floor Meshes (`floor_*`):** `floor_plaza`, `floor_stairs_pz`, `floor_stairs_mz`, `floor_stairs_px`, `floor_stairs_mx`, `floor_rotunda_plaza`, `floor_hall_west`, `floor_hall_east`, `floor_stair_landings`, `floor_cornice_gallery`, `floor_observation_deck`, `floor_lantern_chamber`, plus `floor_access_ramp` & `floor_visitor_center` in 2000+.
- **Doors (`door_*`):** `door_main`, `door_rotunda_south`, `door_north`, `door_east`, `door_west`, `door_hall_west`, `door_hall_east`, `door_observatory_lift`, `door_cornice_gallery`, `door_sky_deck`, `door_lantern_chamber`, plus `door_visitor_center` in 2000+.
- **Collision:** Every state `.glb` includes full-height glTF Y-up `collision` boxes (covering piers up to 58 m, 3-tier crown, open doors, 8 m guardians, exedra, plus era-specific sandbags/stand/scaffolding/fence/ramp/visitor centre).

---

## Rebuild & Verification Commands

Run from the repository root (no Blender required; Python 3 + Pillow and Node.js only):

```bash
python3 prototypes/great-meridian-monument/make_art.py
python3 prototypes/great-meridian-monument/build_models.py
python3 prototypes/great-meridian-monument/validate_models.py
node prototypes/great-meridian-monument/render_review.mjs
```

---

## Notes & Minor Adaptations

- **Software Review Renders:** `great-meridian-review.png` (`2560 × 5822`, hero panels `832–1256 px` wide covering all 10 requested views) is produced by the standalone software rasterizer (`render_review.mjs`). In the Night Money Shot and night sphere panel, the 3,000 K stone floodlight pools, golden sphere uplighting, beacon beam cone, eternal flame halos, and reflecting pool mirror reflection are **renderer-side approximations** meant to preview the `flood_*` / `beacon_beam` lighting setup — none of those lighting/reflection effects are baked into the `.glb` geometry.
- **West Hall Dedication Heading Wrapping:** `"TO THOSE WHO BUILT, FARMED, TAUGHT AND DEFENDED"` is set across two centered `0.4 m` carved lines (`"TO THOSE WHO BUILT, FARMED,"` / `"TAUGHT AND DEFENDED"`) so the text preserves full `0.4 m` legibility between the two carved wreaths on the `8.7 m` interior West Hall wall.
- **Per-State Collision Export:** `model-info.json` lists the `2000+` state's 115 collision boxes at top level and reports per-state counts (`113` / `118` / `116` / `115`); each era `.glb` carries its own exact state-specific `collision` mesh boxes in glTF Y-up metres.
