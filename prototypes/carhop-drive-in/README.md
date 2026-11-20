# Milo's Drive-In

An original 1954-style American carhop burger stand, supplied in the three states requested by the owner:

- `carhop-original.glb`: cream block stand, teal enamel base, flat canopy with coral fascia, eight menu/speaker cases, tall arrow pylon, two fixed serving counters and rear prep room.
- `carhop-closed.glb`: the same shell with faded colour, worn paint/parking lines, all five windows boarded in weathered timber, dry weeds, a SHUT panel and no emission or live lamps. Exterior entrance portals deliberately remain open geometry for the engine's access policy.
- `carhop-restored.glb`: the repaired original design with genuinely open walk-up hatches and four removable picnic-table props on the side terrace. The eight historic speaker posts and canopy remain. A clean interior shell is ready for the game to furnish.

The original fits roughly 1952–75. Closed and restored are alternative later outcomes, useful in the 2000s onward. Dates do not close or reopen a business automatically: select a state from actual simulation facts. No ownership, staff, food stock, business operations or interactive speaker behavior is implemented. No vehicles are baked into the model.

## Geometry and handoff

The stand is 14.2784 × 10.2432m. The chamfered canopy is 27.936 × 9.1568m; the roadside pylon reaches approximately 8.3m. The lot is 43.456 × 34.4544m. All states use the same origin at grade in the middle of the stand's front wall, front +Z, metres/Y up in glTF. The canopy projects in front of that origin. Two exterior door anchors and one internal prep-room anchor stay identical. Interior floor and supported entrance landings are 0.0776m above grade.

The canopy has a complete membrane top, finished soffit, deep three-colour fascia, eight columns, knee braces, real steel rafters, underside ribs and small lamp lenses. Rear roof ventilation, downpipes, bins, service landing and timber fence finish the back. The lot contains marked vehicle bays, wheel stops, planting beds and a side terrace. Menus, tables, wheel stops, planting and other dressing are named removable nodes under `lot`; the canopy and pylon are structural. Columns and speaker cases leave room for a 1.8 × 4.8m reference vehicle in each documented slot; the game must still resolve vehicle routes and door clearance.

Use the actual walls, floors, stairs/kerbs and counters for architectural collision, plus `collision.boxes_gltf_y_up` from each model-info file. The box list and exported mesh are already in glTF metres/Y up/+Z front. **Do not apply the legacy Blender rotation again.** The collision mesh is hidden render-only metadata. The game needs an adapter for this schema, navigation and collision handling; no runtime integration is claimed.

The single repaintable name panel is `sign_name`, with `sign_name` material, on the per-state `sign_name.texture` file recorded in model-info: `[0,0,80,32]` in art pixels, twice that in texels. It reads MILO'S / DRIVE-IN in original bitmap lettering. Repaint/fit within that region without stretching UVs. The smaller menu, speaker grille and order-status graphics are descriptive panels, not town-name signs. No real brand or chain trade dress is used. `texture-map.json` resolves paint shared by original/restored without publishing duplicate PNGs.

`glass_windows` uses an explicit separate pane-only emission mask, black on frames/mullions and unused atlas space. The restored serving openings retain only upper transoms; closed has no glass geometry. `glow_lamps` covers only small lenses, not whole fascia/soffits. Analytic lights in the Blender scene are presentation-only; the GLBs have no lights or cameras. The closed interior detail uses a camera-mounted inspection light so its unpowered shell can be reviewed; its exterior night view stays dark. The engine supplies window-mask interpretation, lights, furniture and interactions. All non-emissive materials have slight outlines, stepped shading and deterministic wear. Shared swatches and recolours retain Timerpg-only provenance in `source-materials/provenance.json`.

## Rebuild and validate

Requires Blender 4.3+, Python 3, Pillow and NumPy. Run from the repository root:

```sh
python prototypes/carhop-drive-in/make_art.py
blender -b -t 4 --python prototypes/carhop-drive-in/build_model.py -- --state=original
blender -b -t 4 --python prototypes/carhop-drive-in/build_model.py -- --state=closed
blender -b -t 4 --python prototypes/carhop-drive-in/build_model.py -- --state=restored
python prototypes/carhop-drive-in/validate_model.py
```

Each build writes a GLB, editable packed Blender source, per-state metadata and raw review frames to `/tmp/carhop-raw`. Geometry and art are deterministic; Cycles/Blender metadata can vary by tool version. Optional `--review` lowers render resolution/samples; `--shots=day,night` selects views without changing the exported model. Remove `.blend1` backups before publication.

`finish_renders.py` applies ordinary offline Open Image Denoise light-sampling cleanup, then writes the single final PNG per view under `renders`. It does not synthesize or repaint geometry. Install `oidn` and set its module directory in `PYTHONPATH`, or retain the raw Blender frames as final frames. Finally run `package_preview.py` for `preview.html`, the combined model-info and one review sheet. Its contact-sheet font is presentation-only, not an in-game texture.

Validation reads the actual GLBs: triangle/material counts; embedded PNGs; exact 2×2 texel art blocks; nearest/repeat filtering; physical UV edge density at 0.0388m/texel; retained paving colours; grade; supported/open and identically facing doors; all collision corners; eight menu posts and unobstructed vehicle slots; state-specific picnic tables and closed-state emission. Detailed counts are in `validation.json`.

All images are **Blender desktop renders, not Filament/S25 captures**. Models, sources and previews are review assets; the game integration and furniture pass remain pending.
