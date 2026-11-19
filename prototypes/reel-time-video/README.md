# Reel Time Video — 4-Bay Strip Centre Across Four Decades

Single-storey small-town strip centre (`32.28 m × 11.17 m` building on a `36.01 m × 28.56 m` lot with a 12-space parking lot in front and finished rear service lane) delivered in four historical states sharing **one building shell, lot, origin, and door node layout** (`door_main` `+Z`, `door_back` `-Z`).

## States & Years (Built 1981)
- **`reel-time-video-1984.glb` (`1982–1992`)**: Family-run **Reel Time Video** in Bay 2 with one plain warm-lit box sign, `NEW RELEASES` window banner, hand-lettered fictional film posters (`STAR DRIFT`, `NIGHT CAB`), wall VHS return slot beside the door, and stocked rental shelves visible inside; Bay 3 vacant (`FOR LEASE`).
- **`reel-time-video-1996.glb` (`1993–2006`)**: Expanded into Bay 2 & Bay 3 with a wider brighter backlit `REEL TIME VIDEO` sign, `2 NIGHT RENTALS` window banner, extra double-bay rental aisles, and a curbside VHS return drop box.
- **`reel-time-video-2011.glb` (`2007–2016`)**: Liquidation state with the fascia sign **unlit and sun-faded**, yellow/red `STORE CLOSING` and `EVERYTHING MUST GO` paper posters in the windows, taped-shut curb drop box, and half-empty bare shelves visible through the glass.
- **`reel-time-video-2020.glb` (`2017–2030`)**: Re-divided and reused: Bay 2 houses a budget phone repair shop (`CELL FIX`) with a cheap unlit vinyl banner strapped over the old sign frame, leaving the old sign's ghost outline and rusted bolt holes exposed across the stucco fascia; Bay 3 returns to `FOR LEASE`.

Neighbours **Suds Laundry** (Bay 1) and **Ruby Nails** (Bay 4) remain quiet with one modest sign each across all four states.

## Technical Notes
- Built to `docs/CHATGPT_MODEL_GUIDE.md` (`0.0388 m/texel`, 2×2 art-pixel grid, 6 LimeZu swatches from `atlas.png`, 11 materials per state, `< 5,000` visible triangles per state, full-height collision boxes listed in `model-info.json`).
- Emissive glow masks (`glow_mask_*.png`) are pure black everywhere except lit fascia sign faces and the four small recessed walkway soffit bulbs.
- `reel-time-video-review.png` shows software-rendered views (day, night, back, and above for all four states), not phone/Filament captures.

## Rebuild
```bash
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/reel-time-video/make_art.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/reel-time-video/build_models.py
PYTHONDONTWRITEBYTECODE=1 python3 prototypes/reel-time-video/validate_models.py
node prototypes/reel-time-video/render_review.mjs
```
