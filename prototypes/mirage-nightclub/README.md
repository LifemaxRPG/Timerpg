# Club Mirage — 1980s Synthwave Nightclub (`mirage-nightclub.glb`)

Drop-in 1980s synthwave / Art-Deco revival nightclub landmark for **Timerpg (`kotlin-street`)**, built from scratch to the `docs/CHATGPT_MODEL_GUIDE.md` and `kotlin-street/docs/LANDMARKS_GLB.md` specification.

## Key Specifications

| Property | Value |
| --- | --- |
| **GLB Asset** | `mirage-nightclub.glb` (`301 KB`, binary glTF 2.0, embedded PNG textures) |
| **Building Footprint** | `18.624 m × 17.3824 m` (`240 × 224` art pixels) |
| **Total Lot Footprint** | `27.3152 m × 29.7984 m` (`352 × 384` art pixels) |
| **Max Height** | `11.3296 m` (`146` art pixels, within `<= 12.0 m` cap) |
| **Pixel Grid** | `1 texel = 0.0388 m`, `1 art pixel = 2×2 texels = 0.0776 m` |
| **UV Scale Accuracy** | `0` edges over `1 mm` error (`max_error_m = 0.0000012 m` across `8,772` edges) |
| **Visible Triangles** | `2,926` (`<= 12,000` budget) |
| **Collision Triangles** | `516` (`43` axis-aligned collision boxes in `model-info.json`) |
| **Materials** | `12` (`<= 12` limit, `NEAREST` + `REPEAT` samplers) |

## Architectural Highlights (Full 360° + Walkable Interior Shell)

- **Front Boulevard Facade (`+Z`)**:
  - Twin stepped 1980s Art-Deco corner towers (`wall_midnight` + `wall_slate`) with vertical neon speed-fins and full-height illuminated glass-block light columns (`tower_col`).
  - Swept cantilevered neon marquee canopy with central stepped `VIP ENTRY` proscenium, twin `DANCE CLUB` wing signs, `6` downward-facing soffit lamps (`glow_signage` with `ny = -1`, automatically spawning real Filament point lights via `LandmarkKit`), and twin V-strut chrome/neon canopy columns.
  - Central stepped magenta/cyan ziggurat portal framing `door_main` (`+Z`) and `door_panel_main`, flanked by large illuminated glass-block bay windows (`LIVE DJ` and `COCKTAILS`).
  - Double-sided rooftop `CLUB MIRAGE` neon sign crowned by a 1980s synthwave sunburst emblem.
- **Side Elevations (`-X` West Parking Lane & `+X` East Service Walk)**:
  - West VIP parking lane with concrete wheel stops, stall dividers, glass-block lounge windows (`MIRAGE` and `VIP CLUB`), and vertical neon accent pilasters.
  - East side VIP/emergency exit doorway (`door_side` facing `+X` with gated `door_panel_side`), concrete walkway, and fenced mechanical chiller/dumpster enclosure (`service_yard`).
- **Rear Touring Band Loading Dock (`-Z`)**:
  - Raised brick and concrete loading dock platform with `4` rubber truck bumpers, side access stairs, overhead steel roll-up acoustic freight door, canopy hood with downlight, performer stage door (`door_back` facing `-Z` with gated `door_panel_back`), rear `DANCE CLUB` neon badge, twin galvanized HVAC risers, and rear rooftop `CLUB MIRAGE` penthouse sign.
- **Interior 1980s Dance Hall Shell**:
  - **Illuminated Disco / Synthwave Checkerboard Dance Floor** (`96×64` art pixels = `7.45 m × 4.97 m`) centered in the main hall underneath a suspended chrome concert lighting truss rig (`8` downward-facing ceiling/truss spotlights).
  - **Elevated DJ Stage & Sound Booth** (`z in [-218, -168]`): Raised neon-trimmed stage platform, built-in magenta/chrome DJ synthesizer console with tinted `SOUND BOOTH` acoustic glass shield, floor-to-ceiling dual concert bass-reflex speaker stacks, and a `128×40` art-pixel (`9.93 m × 3.10 m`) `CLUB MIRAGE` synthwave sunset & laser-grid stage backdrop.
  - **Built-in Neon Cocktail Bar & VIP Banquettes**: Full-length magenta & brushed-chrome cocktail bar counter along the west wall with an illuminated 2-tier neon bottle display (`back_bar`), plus built-in magenta/cyan VIP banquette lounge booths along the east wall.

## Shared LimeZu Swatches Used

Extracted directly from `kotlin-street/tools/swatches/atlas/atlas.png`:
- `wall_slate_dark` (`32×32`)
- `block_courses` (`48×48`)
- `brick_red` (`60×48`)
- `roof_membrane_grey` (`32×32`)
- `ballast` (`32×32`)
- `asphalt_worn` (`32×32`)
- `pavement` (`32×32`)

## Reproducible Build Pipeline

```bash
python3 prototypes/mirage-nightclub/make_art.py
python3 prototypes/mirage-nightclub/build_model.py
python3 prototypes/mirage-nightclub/validate_model.py
node prototypes/mirage-nightclub/render_views.mjs
python3 prototypes/mirage-nightclub/package_preview.py
```
