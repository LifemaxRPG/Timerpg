# Vesper

A 41.94 m modern motor yacht with pearl-white topsides, a warm white superstructure, tinted glazing and a stepped teak stern. The design has a 7.92 m beam and a 2.10 m design draft. At the stern, a swim ladder rises to the beach platform, with two stair runs continuing to the aft dining terrace. A stair on the starboard side reaches the pilothouse terrace, and another reaches the hardtop flybridge. The salon and pilothouse have modeled interiors and clear aft portals. The foredeck has two sunpads. Below the waterline are twin shafts, propellers and rudders.

The GLB has 6,926 triangles and eleven matte materials. Teak uses Timerpg's shared timber swatch. Original paint, glass and upholstery use 2×2 art pixels, nearest/repeat filtering and 0.0388 m per texel. Seats and sunpads are named groups under `lot`.

## Placement and rebuild

The origin is amidships at keel level. The model uses glTF metres, up Y and bow +Z. Set the keel 2.10 m below the water surface. `model-info.json` records boarding, mooring and helm anchors, and the four stair runs. Geometry validation checks all 50 treads, both room openings and level deck samples. Below-deck cabins are represented by exterior windows and hull volume. The model awaits engine integration for boat movement, moving-deck collision, boarding and Android testing.

Run from this folder in a Timerpg checkout with Blender 4.3.2, Pillow and NumPy.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The sea, wake, lights and scale figures belong to the Blender Cycles review. The separate desktop Filament panel renders the exported GLB.
