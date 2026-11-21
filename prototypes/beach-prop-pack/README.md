# Beach Day Prop Pack

Twenty-four independent low-poly props for Timerpg's coast, beach resorts and lakeside lots. The scene render groups the props at their actual dimensions.

| Set | Props |
| --- | --- |
| Shade | Coral and teal striped umbrellas and towels |
| Surf | Stringer longboard, swallowtail fish, sunset shortboard and bodyboard |
| Play | Beach ball, volleyball, volleyball net, pail, shovel, rake, star mold, sandcastle and flying disc |
| Comfort | Picnic cooler, canvas tote, folding chair, sun lounger, swim ring, air mattress and flip-flops |

Every GLB has its own footprint-centered origin, metre scale and ground at glTF Y 0. The surfboards are displayed fin-up on sand. `model-info.json` records era, pose, size, optional collision boxes and triangle counts. The net's collision covers its two posts, and umbrellas use a separate pole box. The remaining small loose objects are placed as decoration unless given a box in the manifest.

Each prop uses one compact embedded nearest/repeat texture, original two-by-two pixel art at 0.0388 m per texel, and 76–472 triangles. Wood uses Timerpg's shared horizontal plank swatch. `beach-prop-pack.blend` includes all 24 editable meshes with packed images. The sand and water are Blender review context. Desktop Filament sampled the exported props. In-game placement, pickup, buoyancy, sports and Android performance await integration.

## Rebuild

From this folder in a Timerpg checkout, with Blender 4.3.2, Pillow and NumPy:

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_pack.py
python validate_pack.py
for view in day catalog toys surf; do
  BEACH_VIEW="$view" blender -b --python-exit-code 1 --python render_pack.py
done
python package_review.py
```
