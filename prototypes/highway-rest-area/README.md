# Pine Bend Rest Area

A late-1970s public highway rest area on an 82 × 66 m parcel. A buff-brick restroom pavilion with a slate-blue metal roof sits between two rows of car parking and a wooded picnic loop. The roofed breezeway holds three vending machines and leads to two restroom shells with privacy baffles. The picnic loop has six tables and two open shelters. The east truck apron has four 4.25 × 25 m pull-through bays, a separate exit throat, and a pedestrian connection to the pavilion.

The visible GLB uses 7,228 triangles and 11 matte materials. Pixel textures use 2×2 texel blocks, nearest/repeat filtering and 0.0388 m per texel. Asphalt, pavement, brick, metal roofing and timber use Timerpg swatches. Brick and roof use a recolored palette. Restroom plumbing kits and the site furniture are independently grouped under `lot`.

## Placement

The origin is centered at the south edge at grade. The front faces glTF +Z. `model-info.json` includes separate vehicle entry and exit markers, two restroom door nodes, the picnic approach and the vending area. Collision boxes are tagged in the GLB and listed with their attachments. Vending and roadway markers are integration points. The model has no traffic or vending logic.

## Rebuild and review

Use Blender 4.3.2 and Python with Pillow and NumPy in this folder.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The Blender Cycles previews use desktop lights and scale vehicles and figures outside the export. The Filament preview renders the actual GLB with its collision-only meshes hidden. Android placement, traffic routing, navigation and in-game lighting still need testing.
