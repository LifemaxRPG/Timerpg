# Willow House Funeral Home

Original small-town American landmark: a converted early twentieth-century house with a formal entrance portico, a covered side arrival lane, vaulted chapel wing and discreet rear service bay. Its architecture fits a 1950s onward town. Willow House is a fictional preview label, not a generated resident or simulation fact.

## Deliverables

- `funeral-home.glb`: complete clean architectural shell with fixed reception counter, exterior fixtures, named door anchors, optional `lot` and hidden box-only `collision` companion.
- `funeral-home.blend`: editable, packed source, saved at the daytime front camera. Geometry is procedurally authored, then grouped by logical assembly/material. Presentation terrain, cameras and lights are excluded from GLB.
- Deterministic `make_art.py`, `build_funeral.py`, helper and original pixel-font scripts; `model-info.json`, `art-regions.json`, `validation.json`.
- Six actual day/night/rear/above/person-height/chapel views in `renders`, their raw originals, contact sheet and `preview.html`.

No loose interior furniture, pews, altar, bodies, coffins, people or vehicles are included. The attic is a closed, non-occupiable roof void. Reception and chapel are open shells; the service bay has a lower floor with actual connecting steps. No religious affiliation or operating funeral service is assumed.

## Rebuild

From the repository root, using Blender 4.3.2 and Python 3 with Pillow/numpy:

```bash
python prototypes/funeral-home/make_art.py
blender -b -t 4 --python prototypes/funeral-home/build_funeral.py
python prototypes/funeral-home/validate_model.py
```

`-- --review` renders the six views at half resolution/eight samples. `-- --shots=01-front-day,03-rear` selects views; `-- --shots=none` rebuilds the model/source/metadata without rendering. Final views are 1280×960, Cycles twenty samples. Optional offline OIDN 1.4.3 cleanup through the Python `oidn==0.2.1` wrapper removes light-sampling noise only; it does not generate or replace geometry. Supply OIDN on `PYTHONPATH`, then run:

```bash
python prototypes/funeral-home/finish_renders.py prototypes/funeral-home/renders/01-front-day.png
python prototypes/funeral-home/package_preview.py
```

Clean all six raw images before packaging. The packaging script also makes a local ZIP; the ZIP is excluded from publication.

## Art and import contract

- Metres; glTF Y up; main public entrance faces +Z; origin is grade at the middle of the main house's front edge. Source Blender uses Z up, front −Y. Metadata lists box centres/sizes in source Blender metres and door positions/normals in glTF metres.
- One art pixel is exactly 2×2 texels; 0.0388m/texel, 0.0776m/art pixel. Physical UVs preserve this density, including slopes. All PNGs use nearest/repeat sampling; no normal maps or photographic textures.
- Twelve role-named materials; 4,062 visible triangles, below the 12,000 landmark budget. Optional props are individually under 500 triangles. Validation reads the actual GLB rather than trusting a source counter.
- Seven door anchors: five exterior, two internal. Each has its own closed panel group and matching `gate_node` box. Main/reception/chapel floors are 0.4656m; service floor 0.1552m. The internal bay connection drops in two 0.1552m risers. Exterior steps are actual geometry. Door opening, animation, elevation-aware navigation, vehicle routing and accessibility handling are not implemented by this asset.
- `collision` must be hidden, as its extras state. Use the architectural walls, floors and steps plus the listed 27 full-height prop/post/closed-door boxes. Move/hide the gate box with its door panel; hide matching lot colliders when disabling `lot`. Boxes are conservatively blocking, not a physics implementation.
- `sign_name` and `sign_name_back` are separate meshes with the same static atlas region `[0,0,48,24]` art pixels in `textures/signs.png`. Update both faces consistently. It is an externally lit, non-neon sign.
- Opaque privacy glazing is intentional. `glass_home` also carries door art; its separate `pane-emission.png` mask emits only the glass areas, leaving wooden doors dark. Preserve the emissive texture on import; the generic game window-role lighting must respect this mask so wooden door regions do not glow. Displayed day/night strengths and actual light pools are staged in Blender; game lighting needs integration.
- Arrival lane, rooms and service bay are spatial proposals only. Ownership, residents, deaths, business existence and all historical facts remain the simulation's responsibility.

## Shared material credit

`source-materials` retains exact shared atlas crops and provenance for LimeZu `wall_stucco_red` (retinted cream), `brick_red`, `roof_slate` and `pavement`. Those swatches are for Timerpg game use only and require retaining the original LimeZu credit/licence. Supplemental clapboard, trim, wood, lawn, metal, privacy panes, lamps and signage are original pixel art. All geometry is original.

These previews are **Blender desktop renders, not Filament or Galaxy S25 captures**. No runtime performance or completed game integration is claimed.
