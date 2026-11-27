# Coastal Palm

A 9.9 m feather palm with a curved, ring-scarred trunk, a shallow root flare, and a crown with three growth tiers. Individual leaflets fold and droop along the arch of each frond. New growth is a warmer olive. Mature outer fronds are darker, with a small skirt of older bronze growth and three young coconuts near the trunk.

The near GLB has 1,190 triangles. The 168-triangle distance GLB keeps the same base and crown outline with simplified fronds. Both use two matte materials, nearest/repeat sampling, and whole 2×2 art-pixel blocks at 0.0388 m per texel. The palette follows Timerpg's coastal greens and warm bark shadows.

## Placement and rebuild

Both files have their origin at the trunk base, in glTF metres with Y up. `model-info.json` lists the exported bounds and a ground-level trunk blocker. The prototype is ready for an integration pass into the procedural tree system. Automatic placement, distance switching, and Android performance still need testing.

Run the following from this folder in a Timerpg checkout with Blender 4.3.2, Pillow and NumPy.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The review sheet uses Blender Cycles model renders. The ground, curb and person are scale cues in the preview scene. The separate Filament panel loads the exported near GLB in desktop WebGL.
