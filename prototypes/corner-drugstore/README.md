# Corner Drugstore — four period states

Original 1920s–30s two-storey brick corner shop, approximately 12×20m, following `docs/CHATGPT_NEXT_BATCH3.md`. A real diagonal entrance joins two display-window frontages. The vertical trade sign, cornice, roof, chimney, service awning, upper office shells and two-flight rear stair are complete geometry.

## States

| GLB suffix | Intended years | Fixed interior treatment |
|---|---|---|
| `family` | 1935–1975 | Rounded fountain counter, eight bolted stools, prep counter, soda dispenser/taps and fixed mixers; high original ceiling. |
| `retail` | 1976–2005 | Fountain and stools removed; actual dropped ceiling. Rear prescription/service counter remains. |
| `closed` | 2006 onward | Retail ceiling retained, display panes partially boarded and emissions off. Exterior thresholds remain physically open. |
| `cafe` | 2006 onward | Original fountain/counter/stools reused, original ceiling exposed, CAFE trade face and one repainted name region. |

Choose one complete GLB at a time. These are simulation-supported alternatives, not an automatic timeline. Café reuse requires the original counter to have been retained, stored or recovered according to the town’s actual history; the model creates no recovery or closure event. No residents, inventory, prices, brands or prescriptions are generated. The game supplies loose merchandise, shelves and other furnishings.

## Rebuild and inspect

Four complete GLBs and four matching editable, packed Blender files are supplied. `state-*.json` contains each state’s actual metadata; `model-info.json` combines them. All states share the original origin, door nodes/coordinates and brick/roof shell. Source Blender uses Z up/front −Y; export uses glTF metres, Y up/front +Z. The corner door’s true outward direction is diagonal (−X,+Z), and its quaternion is checked against that direction.

With Blender 4.3.2 and Python/Pillow/numpy, from repository root:

```bash
python prototypes/corner-drugstore/make_art.py
blender -b -t 6 --python prototypes/corner-drugstore/build_drugstore.py
blender -b -t 6 --python prototypes/corner-drugstore/build_drugstore.py -- --state=retail
blender -b -t 6 --python prototypes/corner-drugstore/build_drugstore.py -- --state=closed
blender -b -t 6 --python prototypes/corner-drugstore/build_drugstore.py -- --state=cafe
python prototypes/corner-drugstore/validate_model.py
```

`--review` produces half-size/eight-sample views; `--shots=none` exports source/GLB/metadata alone; `--shots=06-fountain` selects a view. Final views are 1280×960, Cycles twenty samples. Optional offline OIDN 1.4.3 (`oidn==0.2.1` wrapper) removes sampling noise through `finish_renders.py`; it does not generate geometry. Clean all eleven raw images before running `package_preview.py` for the review sheet, state comparison, gallery and local ZIP. ZIPs, caches and backups are excluded from publication.

## Import handoff

- Every art pixel is exactly 2×2 texels at 0.0388m/texel; nearest/repeat PNGs, including the angled corner panes. Shared brick/stucco/membrane/pavement cuts and provenance are retained. LimeZu swatches are Timerpg-only; preserve original credit. All geometry and supplemental pixel art are original.
- All states stay below twelve materials and the 12,000-triangle landmark budget. Each fixed stool stays under 500 triangles. `validation.json` reads actual exported positions, UVs, indices and embedded images.
- Ground floor: 0.1552m. Upper floor: 4.1904m. Original store ceiling: 3.88m; dropped ceiling: 3.0264m. Real metal treads, landings, stringers and rails form the outside upper-office access. All twenty-six tread rectangles are checked, not inferred from a source counter.
- Exterior doors are deliberately open, without panels or gate boxes. Runtime supplies entry/access behavior and must decide whether a closed business can be entered. Upper-floor and stair navigation require height-aware integration.
- Hide `collision`. Boxes under `collision.boxes_gltf_y_up` are **already glTF Y-up metres**, with matching exported corners. Fixtures start at their supporting floor; exterior posts/fixtures start at grade. The older Blender-frame parser must be adapted rather than rotating these boxes again. Architectural walls, floors, treads and rails provide their own contact. Optional `lot` visibility must also control its tagged colliders.
- `sign_name` is one town-name region `[0,0,100,16]` art pixels in the active `textures/signs-<state>.png`. The separate blade region contains only the generic DRUGS/CAFE descriptor. Repaint the name region only. Sign and glass emission masks light the intended pixels, not their entire base pictures; the closed model exports zero emissions.
- The fountain’s dispenser, taps, mixers and stools are fixed architectural fixtures. No dispensing, billing, food preparation or medication behavior is implemented. The café and family fountain groups have identical exported geometry.
- Actual business identity, ownership, hours, closure, restoration provenance and state choice remain simulation facts. These files provide visual alternatives and eligibility years only.

The images are **Blender desktop previews, not Filament or Galaxy S25 captures**. Glass/masks, glTF collision parsing, state selection, navigation, lights and game furnishing remain integration work.
