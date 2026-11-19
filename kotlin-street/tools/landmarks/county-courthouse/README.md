# Harlan County Courthouse & Square (1894–Present)

Three-storey Romanesque Revival county courthouse (`29.80 m × 29.80 m` cross-shaped plan, `38.02 m` clock tower) standing at the centre of a full town-square block (`79.46 m × 79.46 m` lot, origin at the midpoint of the `+Z` front curb edge). Delivered in four historical states sharing **one shell, origin, lot, and exterior/interior door layout**, plus a distant silhouette LOD (`courthouse-far.glb`).

## States & Distant LOD
- **`courthouse-1894.glb` (`1894–1950`, As Built)**: Rusticated stone base, LimeZu red pressed brick upper storeys, real 3D arched windows (paired on Floor 3), steep hipped slate roofs with four wing dormers, broad 6-step main entrance with 10-sided columns & stone arch (`HARLAN COUNTY COURTHOUSE`, `1894` datestone), 4-faced lit pixel clock tower (`10:10`), open arched belfry with 10-sided bronze `bell`, copper-green spire, Civil War soldier memorial, octagonal bandstand, flagpole, gas-era lamp posts, iron benches, granite low wall, and `+Z` hitching rails.
- **`courthouse-1962.glb` (`1951–1975`, Modernised)**: Ground-floor stone arches partly bricked in with smaller 1950s aluminium windows, flat-roofed 2-storey poured-concrete annex attached to the `-Z` wing (`15.5 m × 19.9 m`), bandstand replaced by a concrete pad, and electric cobra-head lamps.
- **`courthouse-1988.glb` (`1976–1999`, Neglect & Protest)**: Clock stopped at **`4:17`** with clock glow OFF, water-stained annex, plywood-boarded windows, `"SAVE OUR COURTHOUSE"` banner on the tower shaft, and a darker oxidized copper spire.
- **`courthouse-2008.glb` (`2000+`, Restored)**: Arched windows reopened, annex removed leaving a patched-brick scar on the `-Z` facade, new octagonal bandstand rebuilt, working backlit clock + four compact warm tower-base floodlight heads, East side accessibility ramp, restored acorn-globe lamp posts, and a `"RESTORED 2004"` bronze plaque by `door_main`.
- **`courthouse-far.glb`**: Distant town-landmark silhouette (`< 400` triangles) using the exact same pixel textures, proportions, and origin.

## Walkable 3-Storey Interior & Named Nodes
- **Ground Floor (`y = 1.09 m`)**: Open 3-storey marble-floored `floor_rotunda` rising to an overhead glass skylight, real stepped grand staircase with mid-landing and split return flights, entry corridors (`floor_entry_*`), and four offices (`floor_office_clerk/assessor/treasurer/recorder` with `door_room_*` nodes facing into each room).
- **Floor 2 (`y = 5.59 m`)**: Wrap-around `floor_gallery_2` with railings, **double-height `floor_courtroom`** spanning Floors 2 & 3 in the `+Z` wing (with raised judge's bench and oak bar rail only; remaining furniture placed by the game), plus `floor_chambers`, `floor_jury`, and `floor_law_library`.
- **Floor 3 (`y = 10.09 m`)**: Upper `floor_gallery_3` overlooking the rotunda & skylight plus `floor_records`, `floor_archives`, and `floor_commission`.
- **Interactive / Placement Nodes**: `door_main`, `door_north`, `door_east`, `door_west`, 11 `door_room_*` nodes, `clock_hour` & `clock_minute` (plus `_north`/`_east`/`_west` pivoted at dial centers), `bell`, and 16 `tree_01`..`tree_16` lawn positions.

## Technical Notes & Known Limits
- Built to `docs/CHATGPT_MODEL_GUIDE.md`, `docs/GEMINI_NEXT_COURTHOUSE.md`, and `docs/GEMINI_COURTHOUSE_REVISION.md`: `0.0388 m/texel` (strict 2×2 art-pixel textures), 12 base materials + 4 `glow_clock_*` materials, `13,042–13,880` instanced visible triangles per near state (`< 15,000`), `142` triangles for `courthouse-far.glb` (`< 400`), `0` UV errors, **`< 15 m²` non-window glow** (`12.48 m²` in 1894, `9.78 m²` in 1962, `1.04 m²` in 1988, `12.64 m²` in 2008), pure-black emissive masks outside lit lenses/dials, and full-height exterior + interior + stepped-stair/ramp/prop collision listed in `model-info.json`.
- `courthouse-review.png` is a software-rendered review sheet (not phone/Filament captures) showing Day, Night, Opposite (`-Z`), and Above views for all four states, a row of four street-height corner views (SW, SE, NE, NW), and six large person-height close-ups (main entrance, circular clock & belfry, 1988 stopped clock & banner, stair hall -> skylight, Civil War soldier memorial, and double-height courtroom).
- Note: The 1962/1988 concrete annex is an exterior-shell volume abutting the `-Z` wing (not furnished inside), and interior window reveals are modelled on the exterior wall faces while interior room shells carry continuous plaster/wainscot walls.

## Revision Changes
- **Cross-Plan Massing & Silhouette**: Rebuilt the shell into a bold cross plan (`18.0 m` central block with four `9.6 m` entrance pavilions projecting `5.9 m` on each side), gave every pavilion its own steep gabled roof meeting the central hipped roof in crisp 45° valleys plus eight gabled roof dormers, added a clearly visible square stone-trimmed tower base rising above the ridge, deepened all arched window reveals (`~0.34 m`) with projecting keystones and stone corner quoins, and updated `courthouse-far.glb` to match.
- **Civil War Soldier Memorial**: Replaced the dark obelisk with a three-tier stepped granite base, short stout granite pedestal with a plain carved `"1861 - 1865"` panel (no names), and an articulated `~1.84 m` standing Civil War infantryman (greatcoat, belt, kepi cap with visor, and Springfield musket with bayonet) in pixel-shaded weathered bronze-green.
- **Main Entrance**: Resized the 2008 `"RESTORED 2004"` bronze plaque to `~0.62 × 0.47 m` at eye height beside `door_main`, turned `"1894"` into a single dressed stone flush in the wall (`~1.01 × 0.47 m`), kept `door_main` completely open into the marble vestibule with zero closing panels, and carved `"HARLAN COUNTY / COURTHOUSE"` flush into the stone entablature band without a sign-plate frame.
- **Glow Budget (`< 15 m²`)**: Replaced the oversized square clock faces with four circular `1.71 m`-diameter (`r = 11` art px) dials set inside non-glowing sculpted stone bezels (`8.74 m²` total) and tightened lamp heads to `~0.28 m`, cutting total non-window glow from `57.91 m²` down to `12.48 m²` (1894), `9.78 m²` (1962), `1.04 m²` (1988), and `12.64 m²` (2008). Enforced `< 15 m²` directly in `validate_models.py`.
- **Clean Stair Hall**: Removed the four freestanding corner piers and crossing skylight beams, added cream-plaster undersides to the Floor 2 and Floor 3 galleries, unified all three storeys in the same oak wainscot and cream plaster as the courtroom, and framed the open central well under one clear overhead skylight.
- **Review Sheet**: Added a row of four street-height corner views (SW, SE, NE, NW) and dedicated close-ups of the main entrance, Civil War memorial, and stair hall.

## Rebuild
```bash
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/county-courthouse/make_art.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/county-courthouse/build_models.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/county-courthouse/validate_models.py
node prototypes/county-courthouse/render_review.mjs
```
