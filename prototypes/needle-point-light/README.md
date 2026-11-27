# Needle Point Light

Two complete era states of an original coastal landmark on the same headland.

| State | Model | Features |
| --- | --- | --- |
| Historic, 1892–2007 | `needle-point-historic.glb` | Whitewashed tapered tower, 96-step winding stair, red lantern cap, faceted Fresnel optic, keeper's cottage and porch |
| Modern, 2008 onward | `needle-point-modern.glb` | Splayed concrete piers, glazed stair core, twelve maintenance stair flights, LED optic, solar panels and service annex |

Both use Timerpg's 2×2 art pixels at 0.0388 m per texel, nearest/repeat PNGs and matte surfaces. The historic cottage roof and timber use the project's shared swatches. The models use 7,695 and 5,971 triangles respectively. Each state has a separate packed, editable Blender file.

## Siting and integration

Place at the front walkway origin with glTF Y up and +Z facing town. The same grade, pavement approach and lookout fit both states. The included basalt point expects a water plane at Y −5.8 m and the town path extending landward from the origin. Hide `optional_headland` under `lot` when integrating into a custom coastline. Both states contain doors, tower stair geometry and collision metadata. Navigation and collision behavior require game integration.

Each lens has a `beacon_beam` locator with a sweep axis and period in its per-state manifest. The ocean, lighting and visible beam in the Blender Cycles images are review context. The shipped GLBs contain the lens and locator. The two desktop Filament captures use the stand-alone GLBs. Runtime beacon rotation and Android performance are untested.

## Rebuild and inspect

From this folder in Timerpg, with Blender 4.3.2, Pillow and NumPy:

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for state in historic modern; do
  NEEDLE_STATE="$state" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```
