# Breakwater Harbor

A modern marina with two armored breakwaters, six floating sailboat berths, a harbormaster's office, a public trailer ramp and a fixed visitor quay for boats up to 44 m. The dry-stack warehouse has a tall roll-up opening, two racks of three bays and three tiers, and a clear central aisle. The prototype includes 15 removable display runabouts, a parked marine forklift, power pedestals, cleats, rescue rings and quay ladders.

The harbor uses Timerpg's shared plank swatch for the docks. The painted siding, rock, asphalt and lettering use nearest-filtered 2×2 art pixels at 0.0388 m per texel. The GLB contains 11,872 triangles and 12 materials, including optional dressing.

## Placement

Keep land grade at glTF Y 0, water at Y −0.85 m and seaward approach facing +Z. Take berth positions, draft clearance targets, dock connection points and collision boxes from `model-info.json`. The floating dock's walking surface is Y −0.48 m. The 44 × 8.3 m visitor berth has an open seaward approach and a 4 m recommended dredged depth. The six 12 × 4.4 m sailboat berths each call for 2.5 m of water. The quay frontage, launch ramp and gangway are authored as visible geometry. The game supplies bathymetry, tides and navigation.

The open warehouse layout is suited to small powered boats. The playable sailboats and Vesper use the exterior marina and are separate assets. `optional_rack_boats` and `optional_marina_forklift` sit under `lot` and can be omitted. The lift is a static model. Docking, moving floats, launching, working lifts and Android testing await game integration.

## Rebuild and review

From this folder, with Blender 4.3.2, Pillow and NumPy:

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The Blender Cycles review uses desktop-only water, terrain and lights. Set `HARBOR_SLOOP` and `HARBOR_YACHT` to paths for the Alder and Vesper GLBs to reproduce the moored vessel context. The rack layout view lifts the roof for inspection. `renders/filament.png` is a separate desktop Chromium Filament render of the actual GLB.
