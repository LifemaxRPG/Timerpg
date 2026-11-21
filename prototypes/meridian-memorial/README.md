# The Meridian Memorial

A national public-works memorial imagined as a 1941 commission. Two 232 m masonry pylons frame a 66.9 m copper-clad steel meridian ring. The ring bears on a granite gallery and meets stone saddles on the inside faces of both pylons. The towers rise from battered granite footings and narrow to copper caps. Recessed bronze bands, engaged limestone ribs and amber gallery slits mark the vertical scale.

The 176 × 236 m square puts the same axis at walking height. Paired reflecting basins lead to an open-air memorial floor with a compass inlay and dedication walls. Visitors can approach from either end. Four ramps meet the raised platform. Trees and path lamps are removable `lot` groups. The upper gallery and towers are exterior geometry.

The exported GLB has 8,600 triangles and 12 materials. Textures use nearest/repeat sampling, with 2 × 2 texel art pixels at 0.0388 m per texel. The paving and timber come from Timerpg swatches. Stone, patinated copper and lettering use original pixel-art textures. The editable Blender source packs all twelve images.

## Build and review

Run these commands from this folder in a Timerpg checkout. Texture generation and validation require Python, Pillow and NumPy. Blender 4.3.2 generates the model and desktop renders.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

For the optional city context shot, set `MERIDIAN_REFERENCE_ROOT` to the directory holding the separate Gazette, library, bank and hotel Timerpg worktrees. The city view stages those existing GLBs at native scale. They and the preview figures stay in the review scene. The main views are Blender Cycles renders. The Filament frame uses the actual exported GLB in standalone desktop WebGL.

Town placement, Android lighting, collision and navigation need a game integration pass. The large lot is intended for a city plaza or axial park. Keep both pool-side paths and all four ramps open and treat the basins as obstructions. The two towers and the upper gallery have no interior gameplay.
