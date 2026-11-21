# Small-Town WPA Post Office (1938 Federal Building → 1988 Modernized Branch)

A small-town American 1930s WPA-era federal post office delivered as two historical `.glb` states sharing **one lot (`26.69 m × 24.52 m`), historic 1938 masonry shell footprint (`16.76 m × 11.17 m`), origin `(0, 0, 0)` at the front-center wall edge (`+Z`), preserved interior 1930s WPA mural, and exact door nodes (`door_main`, `door_back`)**.

Built to `docs/CHATGPT_MODEL_GUIDE.md` on the strict `0.0388 m/texel` (`0.0776 m/art px`, 2×2 PNG) pixel grid with `NEAREST` + `REPEAT` sampling, 6 shared LimeZu swatches (`brick_red`, `roof_slate`, `ballast`, `roof_membrane_grey`, `asphalt_worn`, `pavement`), and 12 role-named materials (including a dedicated `mural_wpa` material for the panoramic lobby mural).

---

## States & Eras

1. **`post-office-1938.glb` — U.S. Post Office (`1938–1977`, built `1938`)**
   - Classical WPA red-brick and Indiana limestone federal architecture featuring a projecting four-pilaster limestone entrance pavilion, carved **`U.S. POST OFFICE`** entablature, and true triangular limestone pediment with carved **`1938`** date block (no neon).
   - Recessed hipped slate roof (`roof_slate`) behind a stone-coped brick parapet, crowned by a centered louvered civic cupola with brass orb finial (`12.65 m` height) and a properly tapered rear brick boiler chimney.
   - Real glazed front lobby window openings looking into the clean terrazzo-and-marble interior lobby shell featuring the full-width **`9.93 m × 2.79 m` 1930s WPA mural** (*"The Mail Unites Farm, Rail, and Town"*) mounted above a walnut-and-brass teller counter and West wall brass P.O. Box bank.
   - Broad 3-step limestone front stairs flanked by bronze globe lantern pedestals, symmetrical boxwood planters, tall West flagpole, olive-green cast-iron corner mailbox, civic bench, and rear covered mail loading dock.

2. **`post-office-1988.glb` — Modernized Historic Branch (`1978–2026`, renovated `1978`)**
   - Preserves the 1938 WPA brick-and-limestone building, cupola, flagpole, and interior 1938 WPA mural while adding authentic late-20th-century postal upgrades:
     - East concrete accessibility ramp with tubular metal handrails connecting smoothly to the front entrance landing.
     - Commercial dark-bronze aluminum-and-glass double vestibule doors and updated cream-and-navy lobby teller counter.
     - Twin royal-blue steel drive-up curbside collection boxes, a compact backlit **`POST / OFFICE`** sidewalk monument sign on a low limestone base, expanded deep rear mail-truck loading canopy with yellow safety bollards and rubber dock bumpers, and rooftop HVAC condenser units tucked behind the rear parapet.

---

## Verified Build & Validation Metrics

| Asset | Era | Triangles | Materials | WPA Mural | UV Errors | Glow Area (Black Mask) | Max Height | Collision Boxes |
| --- | --- | ---: | ---: | :---: | ---: | ---: | ---: | ---: |
| `post-office-1938.glb` | 1938–1977 (built 1938) | `2,186` / 5,000 | 12 / 12 | Yes (`256×72`) | `0` (`max 1e-6 m`) | `7.23 m²` (`94.1%` black) | `12.65 m` | 14 (full height) |
| `post-office-1988.glb` | 1978–2026 (renovated 1978) | `2,428` / 5,000 | 12 / 12 | Yes (`256×72`) | `0` (`max 1e-6 m`) | `16.77 m²` (`77.7%` black) | `12.65 m` | 18 (full height) |

- **Origin & Doors:** Origin `(0, 0, 0)` at ground level at the front-center wall edge (`+Z`); identical `door_main` `[0, 0, +1.3968]` (`+Z`, foot of front steps) and `door_back` `[0, 0, -12.5712]` (`-Z`, rear employee dock entrance) across both states.

---

## Rebuild & Verification

Run from the repository root (Python 3 + Node.js only; no Blender required):

```bash
python3 prototypes/post-office/make_art.py
python3 prototypes/post-office/build_models.py
python3 prototypes/post-office/validate_models.py
node prototypes/post-office/render_review.mjs
```

---

## Notes
- `post-office-review.png` is produced by the standalone software rasterizer (`render_review.mjs`) and is **not** an in-engine Filament phone capture.
- The interior lobby shell includes fixed architectural elements (terrazzo floor, walnut/marble wainscot walls, built-in teller counter partition, P.O. Box bank, and the framed 1930s WPA mural) while leaving floor space open for game furniture.
- All lot props (`parking_lot`, `rear_service`, `curb_props`) are parented cleanly under the `lot` node so the game can keep or drop them per town.
