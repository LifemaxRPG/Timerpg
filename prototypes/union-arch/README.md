# Union Arch

A limestone civic gateway conceived as a 1929 public-works dedication. The tall central vault frames a street or civic axis. Twin pedestrian arches, granite plinths, fluted pilasters and a stepped copper crown give the same formal elevation to both sides. Bronze medallions carry a stylized river-and-reed relief. Smaller sconces mark the entrances after dark.

The square measures 43 × 36 m. The monument rises 20.15 m. The broad opening is 9.5 m wide and the two smaller openings are each 2.35 m wide. All three are continuous outdoor passages at plaza level. The GLB origin is at the middle of the front lot edge, facing +Z in glTF metres. Use the wall geometry for collision, keep the three paths clear, and use the separate `lot` nodes to retain or remove the trees and benches. The east and west garden beds frame the approach. The two opposed inscription meshes use the same `sign_name` material.

The matte finish uses whole 2×2 texel blocks at 0.0388 m per texel. Pavement and bench timber use Timerpg's shared swatches. The ashlar, granite, copper, bronze and inscription are generated pixel textures. The source packs all textures. The published GLB uses 4,648 triangles and 11 materials.

## Rebuild

From this folder in a Timerpg checkout with Blender 4.3.2, NumPy and Pillow installed:

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The inspection views use Blender Cycles with desktop scene lights and scale figures. The optional Filament frame shows the exported GLB in a standalone desktop WebGL scene. Device performance, game collision and in-game nighttime lighting still need testing on Android.
