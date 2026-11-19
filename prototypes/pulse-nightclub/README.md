# PULSE — 2020s Modern Nightclub & Rooftop Sky Lounge (`pulse-nightclub.glb`)

Drop-in contemporary luxury nightclub and rooftop sky lounge landmark for **Timerpg (`kotlin-street`)**, built from scratch to the `docs/CHATGPT_MODEL_GUIDE.md` and `kotlin-street/docs/LANDMARKS_GLB.md` contract.

## Key Specifications

| Property | Value |
| --- | --- |
| **GLB Asset** | `pulse-nightclub.glb` (binary glTF 2.0, embedded PNG textures) |
| **Building Footprint** | `18.624 m × 17.3824 m` (`240 × 224` art pixels) |
| **Total Lot Footprint** | `27.3152 m × 29.7984 m` (`352 × 384` art pixels) |
| **Max Height** | `11.1744 m` (`144` art pixels, within `<= 12.0 m` cap) |
| **Pixel Grid** | `1 texel = 0.0388 m`, `1 art pixel = 2×2 texels = 0.0776 m` |
| **UV Scale Accuracy** | `0` edges over `1 mm` error across all visible edges |
| **Materials** | `12` (`<= 12` limit, `NEAREST` + `REPEAT` samplers) |

## Architectural Highlights (Full 360° + Rooftop Sky Lounge + Walkable Main Room)

- **Asymmetric Contemporary Front Facade (`+Z`)**:
  - Matte charcoal-black vertical aluminum louvre rainscreen (`wall_obsidian`), pale architectural travertine piers (`wall_travertine`), and brushed champagne-gold bronze fascia (`trim_architectural`).
  - Dramatic **cantilevered west upper media box** housing a flush `72×32` art-pixel exterior **kinetic LED media facade screen** (`led_media_wall`).
  - Swept cantilevered porte-cochère with a warm **architectural teak wood soffit** (`plank_horizontal`), `6` linear recessed LED downlights (`ny = -1`, automatically spawning Filament point lights), central backlit `VIP VALET` blade sign, and twin steel/bronze V-columns.
  - East corner double-height glass atrium tower (`atrium_col`), recessed frameless double glass VIP doors (`door_main` + `door_panel_main`), and floor-to-ceiling curtain-wall lobby glazing (`LOUNGE` and `VIP CLUB`).
- **Open-Air Rooftop Sky Lounge Terrace**:
  - Warm teak terrace deck (`plank_horizontal`) bordered by frameless glass safety balustrades (`rail_glass`), rooftop VIP velvet seating, and a cantilevered bronze-and-teak shade pergola over an illuminated **`SKY LOUNGE`** open-air rooftop bar with `2` overhead LED downlights.
  - Double-sided rooftop **`PULSE`** backlit architectural crown sign atop the rear acoustic sound-isolation penthouse.
- **Side Elevations & Rear Production Loading Dock (`-X`, `+X`, `-Z`)**:
  - West blacktop VIP valet arrival drive with travertine wheel stops and bronze elevation fins; East VIP side exit (`door_side` + `door_panel_side`) and screened mechanical chiller/dumpster yard (`service_yard`).
  - Rear concrete-block acoustic shell with raised production loading dock, rubber bumpers, side steps, steel roll-up freight door, bronze canopy hood with downlight, backstage artist entry (`door_back` + `door_panel_back`), and twin galvanized HVAC risers.
- **Walkable Main-Room Interior Shell**:
  - **Geometric LED Pulse Dance Floor** (`96×64` art pixels = `7.45 m × 4.97 m`) with concentric ice-cyan, ultraviolet, and warm-gold recessed architectural light rings underneath a suspended brushed-steel & champagne-bronze concert lighting rig (`8` recessed LED light bars).
  - **Elevated Festival-Scale DJ Stage & LED Video Wall**: Matte obsidian & bronze DJ console with smoked `MAIN ROOM` acoustic glass shield, floor-to-ceiling dual line-array speaker towers, and a `128×40` art-pixel (`9.93 m × 3.10 m`) kinetic **LED Video Wall** (`dj_led_wall`).
  - **Illuminated Onyx & Brushed-Bronze Cocktail Bar** (west wall) with glowing 2-tier floating bottle display (`bar_display`) and royal-plum velvet **VIP Bottle-Service Banquettes** (east wall).

## Shared LimeZu Swatches Used

Extracted directly from `kotlin-street/tools/swatches/atlas/atlas.png`:
- `wall_slate_dark` (`32×32`)
- `wall_slate_light` (`32×32`)
- `block_courses` (`48×48`)
- `plank_horizontal` (`32×32`)
- `roof_membrane_grey` (`32×32`)
- `asphalt` (`32×32`)
- `pavement` (`32×32`)
