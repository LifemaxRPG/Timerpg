# Small-Town Fire Station (1924 Brick Firehouse → 1988 Modern Station)

A small-town American municipal fire station delivered as two historical `.glb` states sharing **one lot (`27.94 m × 25.45 m`), origin `(0, 0, 0)` at the front-center wall/apron edge (`+Z`), street apron, parking layout, and exact door nodes (`door_main`, `door_back`)**.

Built to `docs/CHATGPT_MODEL_GUIDE.md` on the strict `0.0388 m/texel` (`0.0776 m/art px`, 2×2 PNG) pixel grid with `NEAREST` + `REPEAT` sampling, 7 shared LimeZu swatches (`brick_red`, `block_courses`, `roof_slate`, `ballast`, `roof_membrane_grey`, `asphalt_worn`, `pavement`), and 12 role-named materials.

---

## States & Eras

1. **`fire-station-1924.glb` — Engine Co. No. 2 (`1924–1983`, built `1924`)**
   - Two-storey red-brick two-bay firehouse (`17.38 m × 12.42 m`) with stone plinth, belt course, quoins, and stepped pediment.
   - Tall NW brick **hose-drying tower** (`17.23 m` to brass finial) with recessed louvered drying vents on all four faces and a pyramidal slate roof.
   - Recessed double crimson timber carriage doors with upper transoms, oak main door, clean interior apparatus bay shell with a built-in brass fire pole, carved limestone **`ENGINE CO. NO. 2`** tablet + **`1924`** date stone (no neon), glowing red entrance globe lamps, street fire-alarm pull box, cast-iron hydrant, and rear timber hose-washing rack.

2. **`fire-station-1988.glb` — Fire Station No. 2 (`1984–2026`, rebuilt `1985`)**
   - Modern split-face concrete-block municipal station on the exact same lot, apron, and door layout, with distinct split-level massing: wide two-bay ground block (`5.94 m`) + setback second-storey crew & dispatch wing (`8.96 m`) with continuous ribbon windows.
   - Oversized modern red-and-white sectional roll-up apparatus doors with vision-glass bands, cantilevered dark-metal lobby canopy, single modest backlit **`STATION NO. 2`** fascia sign, subtle red bay warning beacons, rooftop HVAC units, galvanized VHF radio mast (`15.68 m`), yellow safety bollards, and rear diesel backup generator.

---

## Verified Build & Validation Metrics

| Asset | Era | Triangles | Materials | UV Errors | Glow Area (Black Mask) | Max Height | Collision Boxes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `fire-station-1924.glb` | 1924–1983 (built 1924) | `2,174` / 5,000 | 12 / 12 | `0` (`max 1e-6 m`) | `2.10 m²` (`94.1%` black) | `17.23 m` | 11 (full height) |
| `fire-station-1988.glb` | 1984–2026 (rebuilt 1985) | `1,594` / 5,000 | 12 / 12 | `0` (`max 2e-6 m`) | `11.47 m²` (`69.5%` black) | `15.68 m` | 15 (full height) |

- **Origin & Doors:** Origin `(0, 0, 0)` at ground level at the front-center wall edge (`+Z`); identical `door_main` `[0, 0, +0.1552]` (`+Z`) and `door_back` `[0, 0, -12.5712]` (`-Z`) across both states.

---

## Rebuild & Verification

Run from the repository root (Python 3 + Node.js only; no Blender required):

```bash
python3 prototypes/fire-station/make_art.py
python3 prototypes/fire-station/build_models.py
python3 prototypes/fire-station/validate_models.py
node prototypes/fire-station/render_review.mjs
```

---

## Notes
- `fire-station-review.png` is produced by the standalone software rasterizer (`render_review.mjs`) and is **not** an in-engine Filament phone capture.
- All interior spaces are clean architectural shells (concrete floor, tiled wainscot/plaster walls, ceiling, plus the fixed 1924 brass fire pole) so the game can furnish them with LimeZu furniture.
- All lot props (`parking_lot`, `rear_service`, `curb_props`) are parented cleanly under the `lot` node so the game can keep or drop them per town.
