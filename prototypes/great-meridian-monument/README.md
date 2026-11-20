# The Great Meridian Monument — Revised Multi-Era Package (1926 – 2000+)

A 105.5 m Art Deco / Neo-Classical civic national monument built strictly to the game's pixel-art contract (`0.0388 m/texel`, 2×2 art-pixel textures, `NEAREST` + `REPEAT`, `<= 12` materials, `<= 30,000` triangles near, `< 400` triangles far, `< 20 m²` total emissive area, `0` UV stretch errors, glTF Y-up `+Z` front collision).

## What Changed in This Revision

1. **Cleaner 3-Part Iconic Silhouette (105.5 m)**
   - **Base (`0–24 m`)**: Monolithic `34 × 34 m` square Arch Block pierced by four 17 m triumphal arches and crowned with a continuous walkable `24 m` Cornice Gallery. Dropped the side peristyle colonnade wings, corner stair turrets, and flanking obelisks so the monument reads immediately as one cohesive landmark at `64 px` and `32 px` silhouette height.
   - **Shaft (`24–58 m`)**: Four tall, slender setback corner piers (`34 m` and `46 m` setbacks) with deep vertical Art Deco fluting, one narrow amber window slot per outer face, and four 7 m sculpted Art Deco Guardians (`DEFENSE`, `LEARNING`, `INDUSTRY`, `HARVEST`) framing the open riveted-steel cathedral vault and suspended `15.2 m` Celestial Armillary Sphere.
   - **Crown (`58–105.5 m`)**: Square `58 m` Sky Observation Deck with open bronze railing, brass viewing telescopes, and four engraved corner brass direction plates (`TO CAPITOL`, `TO RIVER`, `TO MOUNTAINS`, `TO THE SEA`), topped by **one** stepped octagonal lantern (`58–74 m`) housing the beacon machinery chamber, a slender tapered copper needle spire (`74–101 m`), and the `105.5 m` aviation beacon & finial. Dropped the 16-column sky colonnade, starburst crown, and flying-buttress fins.
2. **Four Historical States Sharing One Shell, Origin & Door Layout**
   - `great-meridian-1926.glb` (`1926–1940`, Dedication): Clean pale limestone, fresh reddish-copper spire, pleated red/white/blue cornice bunting swags, gas-style globe lamps, young plaza trees (`tree_01..08`).
   - `great-meridian-1941.glb` (`1941–1975`, Mature): Slightly darkened limestone, green verdigris spire, Art Deco electric globe lamps, `1941–1945` bronze War Years plaque beside the main South bronze doors, mature plaza trees.
   - `great-meridian-1976.glb` (`1976–1999`, Worn): Rainwater-streaked limestone under cornices, 3 broken/unlit plaza lamps, East sky-deck telescope missing (empty mounting plate), aviation beacon OFF, timber-plank and steel-tube restoration scaffolding covering the SE pier.
   - `great-meridian-2000.glb` (`2000+`, Restored): Cleaned pale limestone, beacon relit, visible modern floodlight fixtures mounted at `flood_01..12`, East accessibility switchback ramp (`floor_access_ramp`), and a one-storey glass-and-stone Visitor Centre pavilion at the SW plaza corner (`door_visitor_center`, `floor_visitor_center`).
3. **Far LOD (`great-meridian-far.glb`, 282 Triangles)**
   - Built directly from the revised 3-part silhouette (`< 400` triangle cap) with matching square arch block, 4 setback piers, golden armillary diamond, square deck, octagonal lantern, verdigris needle, and glowing beacon orb.
4. **Tightened Emissive Budget & Night Lighting Hookups**
   - Cut emissive surface area from `54.3 m²` down to `15.22–15.76 m²` (`12.70 m²` in `1976` with beacon off and 3 lamps broken) — strictly restricted to the beacon orb, 8 narrow lantern lancet cores, 8 narrow pier window slots, 16 globe lamp sides, 4 eternal flames, 3 reflecting pool fountain jets, and the razor-thin ecliptic star line on a pure-black (`#000000`) emissive mask.
   - Added 12 directional spotlight nodes (`flood_01..flood_12`) and rotating `beacon_beam` (`10 s` rotation period).
5. **Storytelling, Climb & Grounding Fixes**
   - Hollow **North-West Pier** with an open-cage wrought-iron elevator shaft, movable `lift_car` cab, and winding staircase (`floor_stair_landings`) linking the Rotunda (`6.5 m`), Cornice Gallery (`24.1 m`), and Sky Observation Deck (`58.0 m`).
   - Walkable **Octagonal Lantern Machinery Chamber** (`floor_lantern_chamber`, `61.8 m`) reached by a bronze ladder from the `58 m` deck, containing interlocking gilded-bronze drive gears and a brass-framed Fresnel lens.
   - **West Memorial Hall Dedication Wall** (`TO THOSE WHO / BUILT FARMED / TAUGHT AND / DEFENDED` + anonymous carved name blocks), two 4 m tall open embossed bronze doors folded back against the South triumphal arch reveals, inlaid bronze **Meridian Line** running continuously across the plaza and Compass Rose Rotunda, and made-up royal-blue-and-gold compass-star civic flags.
   - **Reflecting Pool** water placed cleanly at `y = 0.0` with sunken granite basin below and plaza paving at `y = +0.155 m` (`>= 0`).
   - All collision boxes in `model-info.json` exported in **glTF metres (`Y-up`, `+Z` front)** matching the door node frame.

## Key glTF Nodes

| Category | Node Names | Notes |
| --- | --- | --- |
| **3-Node Armillary** | `armillary_sphere` (parent), `armillary_outer`, `armillary_ecliptic`, `armillary_globe` | Outer meridian/equator rings fixed; `armillary_ecliptic` (`23.4°` zodiac band) & `armillary_globe` (inclined polar axis) rotate independently once per game day. |
| **Beacon & Floodlights** | `beacon_beam`, `flood_01`..`flood_12` | `beacon_beam` sits at `(0, 102.28, -59.98)` with `10.0 s` rotation period (`active: false` in `1976`); `flood_01..12` carry normalized spotlight aim vectors in `extras.direction` (and visible housings in `2000+`). |
| **Elevator** | `lift_car` | Movable bronze-and-glass elevator cab inside hollow NW pier (`stops_y_m: [6.52, 24.06, 58.04]`). |
| **Plaza Trees** | `tree_01`..`tree_08` | Young saplings in `1926`, mature canopies in `1941+`. |
| **Doors / Portals** | `door_main`, `door_rotunda_south`, `door_north`, `door_east`, `door_west`, `door_hall_west`, `door_hall_east`, `door_observatory_lift`, `door_cornice_gallery`, `door_sky_deck`, `door_lantern_chamber`, `door_visitor_center` (`2000+`) | All in glTF Y-up metres (`+Z` front). |
| **Walkable Floors** | `floor_plaza`, `floor_stairs_pz`, `floor_stairs_mz`, `floor_stairs_px`, `floor_stairs_mx`, `floor_rotunda_plaza`, `floor_hall_west`, `floor_hall_east`, `floor_stair_landings`, `floor_cornice_gallery`, `floor_observation_deck`, `floor_lantern_chamber`, `floor_access_ramp` (`2000+`), `floor_visitor_center` (`2000+`) | Explicit walkable deck meshes at every climb level. |
| **Collision & Lot** | `structure`, `lot`, `collision` | `collision` mesh (`extras.collision_only: true`) matches `collision.boxes_gltf_y_up` in `model-info.json`. |

## Rebuild & Validate (No Blender Required)

```bash
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/great-meridian-monument/make_art.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/great-meridian-monument/build_models.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/great-meridian-monument/validate_models.py
node prototypes/great-meridian-monument/render_review.mjs
```

> **Note on `great-meridian-review.png`**: All review sheet panels are generated by our headless Node.js software glTF rasterizer (`render_review.mjs`), not phone or Filament engine captures.
