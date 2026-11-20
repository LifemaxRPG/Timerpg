# The Great Meridian Monument (Dedicated 1926)

An original world-wonder-scale national monument (`105.54 m` tall on a `120.0 m × 120.0 m` ceremonial acropolis and reflecting pool plaza) built to the `0.0388 m/texel` pixel-art architectural contract (`docs/CHATGPT_MODEL_GUIDE.md`).

## Architectural Concept
- **Style & Era**: 1926 Beaux-Arts & Art Deco civic engineering monument — combining monumental dressed-limestone triumphal arches, exposed riveted-steel open-truss cathedral vaults, patinated verdigris copper, and gilded bronze.
- **Grounds & Approach (`lot`)**:
  - `120 m × 120 m` marble-paved ceremonial promenade with manicured corner park lawns and low perimeter granite walls.
  - `14.9 m × 29.0 m` Grand Reflecting Pool on the `+Z` front approach axis with three gilded-bronze fountain basins, flanked by 12 waving civic flagpoles (`14 m` tall), 16 Art Deco pedestal globe lamps, and 12 limestone-and-timber benches.
  - Twin sweeping curved-faceted **Peristyle Colonnade Wings** (west and east embracing arcs of fluted `10 m` limestone columns crowned with classical bronze urns).
- **Ceremonial Acropolis & Walkable Sanctuary (`y = 6.52 m`)**:
  - Three-tier granite and limestone acropolis plinth with four sweeping monumental staircases and four corner **Eternal Flame** bronze tripod braziers.
  - Open-air **Grand Compass Rose Rotunda** (`floor_rotunda_plaza`) featuring an inlaid bronze-and-slate compass medallion directly beneath the central crossing, flanked by walkable West and East Memorial Halls (`floor_hall_west`, `floor_hall_east`) and Observatory Lift Lobbies (`floor_stairwell_nw`, `floor_stairwell_ne`).
- **Four Cathedral Pylons & Triumphal Crossing**:
  - Four battered, stepped limestone pylons with fluted pilasters, deep-reveal arched cathedral windows (leaded dark-blue-and-amber glass recessed `0.43 m` behind stone jambs), Art Deco sunburst medallions, verdigris obelisk pinnacles, and corner turrets.
  - Four `17.1 m` clear-span coffered limestone barrel-vaulted arches topped by flush chiseled stone friezes (`THE GREAT MERIDIAN / DEDICATED MCMXXVI`, `MERIDIAN EAST`, `MERIDIAN WEST`) and an eye-height dedication plaque and `1926` cornerstone.
- **Open-Truss Steel Vault & 15.2 m Celestial Armillary Sphere (`armillary_sphere`)**:
  - Four soaring Eiffel-style X-braced riveted-steel cathedral arches and two diagonal crossing vault ribs framing a sky-filled upper chamber (`y = 31.4 m .. 57.1 m`).
  - Suspended at `y = 40.97 m` inside the open vault is a **`15.2 m` gilded-bronze and verdigris-copper Celestial Armillary Sphere** exported as its own centered `armillary_sphere` node (so the game can optionally rotate it slowly in place).
- **58.2 m Walkable Sky Observation Crown & 105.5 m Astral Beacon**:
  - Walkable 360-degree observation terrace (`floor_observation_deck` at `y = 58.20 m`) with an open wrought-iron & gilded-bronze perimeter railing, inner viewing oculus looking straight down onto the Armillary Sphere, 16-column circular colonnade, and 4 brass viewing telescopes.
  - Above the deck rise the **Octagonal Art Deco Cathedral Sky Lantern** (8 stepped flying buttress fins and 8 tall amber leaded lancets), an **8-Point Gilded Starburst Crown**, a ribbed tapered **Verdigris-Copper Needle Spire**, and an open bronze cage holding the radiant **Astral Beacon Orb** (`y = 101.8 m`) topped by a lightning finial reaching **`105.54 m`**.

## Files Included
- `great-meridian-monument.glb` — Full near-field monument (`24,084` instanced visible triangles, `12` named role materials, `0` UV stretch errors, `0.000444 m` max UV edge error, `88` full-height collision boxes).
- `great-meridian-monument-far.glb` — Matching distant landmark silhouette LOD (`390` triangles).
- `model-info.json` — Footprint, exact heights, named doors, walkable floor nodes, special nodes, and full-height Blender Z-up collision boxes.
- `validation.json` — Automated validator output confirming 2×2 art-pixel textures, NEAREST + REPEAT samplers, `<= 12` materials, `0` UV errors, pure-black emissive mask (`76.3%` black), and ground placement `min_y >= 0`.
- `great-meridian-review.png` — 14-panel `2620 × 2080` review sheet (day/night/back/above overviews, street-height silhouettes, far LOD, architectural close-ups, and walkable views). **Note: these are software renders produced by `render_review.mjs`, not phone or Filament engine captures.**
- Source scripts & textures: `pixel_font.py`, `make_art.py`, `build_models.py`, `validate_models.py`, `render_review.mjs`, `art-regions.json`, and `textures/*.png`.

## Rebuild Without Blender
```bash
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/great-meridian-monument/make_art.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/great-meridian-monument/build_models.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/great-meridian-monument/validate_models.py
node prototypes/great-meridian-monument/render_review.mjs
```
