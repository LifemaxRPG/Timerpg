# Valley Radio

Original 1950s–60s small-town broadcast house: brick studio wing, angular glazed entrance bay, canopy, illuminated nameplate and double-sided RADIO blade, service awning and a freestanding lattice mast. Designed to fit towns from 1950 onward. Valley Radio is a fictional preview label; station existence, ownership and actual broadcasts remain simulation facts.

## Deliverables and rebuild

`radio-station.glb` is the clean building/mast shell with optional `lot`, named doors and hidden `collision` boxes. `radio-station.blend` is editable and has every texture packed. Deterministic source scripts, retained swatch cuts, metadata, validation, six raw/clean actual views and `preview.html` accompany it.

From repository root, with Blender 4.3.2 and Python/Pillow/numpy:

```bash
python prototypes/radio-station/make_art.py
blender -b -t 6 --python prototypes/radio-station/build_radio.py
python prototypes/radio-station/validate_model.py
```

Use `-- --review` for half-size eight-sample views; `-- --shots=01-front-day,03-rear` selects views; `-- --shots=none` rebuilds the GLB, packed source and metadata alone. Final renders use 1280×960, Cycles twenty samples. Optional offline OIDN 1.4.3 (`oidn==0.2.1` wrapper) removes sampling noise without generating geometry. Put OIDN on `PYTHONPATH`, clean the six raw PNGs with `finish_renders.py`, then run `package_preview.py` for the contact sheet, gallery and local ZIP. ZIP/cache/backup files are excluded from publication.

## Art and handoff

- 4,544 visible triangles, twelve role-named materials. Optional props stay under 500 triangles each (complete mast fence: 396). Shared LimeZu brick/stucco/membrane/paving/asphalt cuts are retained in `source-materials` with provenance. They are Timerpg-only licensed assets: preserve original LimeZu credit. Supplemental pixel art and all geometry are original.
- Every art pixel is exactly 2×2 texels, at 0.0388m/texel. PNGs use nearest/repeat sampling. Sloped lattice members and angled glass retain physical UV density; the red mast paint atlas is contained within its own region.
- GLB metres, Y up, public entrance +Z. Origin is grade at the middle of the main brick frontage; the entrance bay projects forward. Metadata gives actual structural bounds. Source Blender coordinates are Z up, front −Y; box metadata uses those source metres, door coordinates/normals use glTF metres.
- Lobby, studio and control room have floors at 0.1552m and ceilings at 3.88m. They are clean shells with fixed decorative wall lining and an observation ledge. No console, microphone, chairs, loose recording equipment or measured acoustic treatment is supplied. The real partition aperture contains two alpha-glazed panes.
- Six door anchors: public entrance, rear service, studio exterior exit and three internal doors. Closed panel groups and matching `gate_node` boxes must move/hide together. Door interactions, animation and navigation remain game integration work.
- Hide `collision`. Use the actual architectural walls/floors and the 24 listed full-height box blockers. `lot` contains parking stops, bench, bins and mast-yard fence; matching lot colliders must follow visibility. The mast is structural, with a conservative footprint blocker that remains when optional fencing is hidden. Its ladder is visual only, without climb interaction.
- Three parking bays have 3.104m spacing and approximately 4.81m marked depth. Vehicle spawning/routing is not implemented. The cable conduit crosses the walking route overhead, with its riser inside the blocked mast footprint.
- `sign_name` uses `[0,0,94,18]` art pixels in `textures/signs.png`. Replace only this nameplate region; the same material atlas contains the RADIO blade faces, microphone emblem and ON AIR plate. ON AIR is static placeholder artwork: bind or hide it from actual runtime broadcast state; do not infer a broadcast event from the asset.
- Alpha glass, its emission mask, nearest filtering, signs and door semantics require importer integration. The control-room pane has no emissive fill. Preview illumination comes from Blender-only lights; no operating radio transmitter, coverage, safety compliance, mast climbing or business simulation is claimed.

These are **Blender desktop previews, not Filament or Samsung Galaxy S25 captures**. Game integration and phone performance measurements remain pending.
