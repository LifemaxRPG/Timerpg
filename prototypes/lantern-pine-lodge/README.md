# Lantern Pine Lodge

A compact 1930s woodland lodge on a 34 × 29 m clearing. Interlocking log courses frame an open great room with exposed collar ties, deep window casings and a fieldstone fireplace. A porch with amber lanterns and a bench faces a short gravel path. A rear stoop and covered woodshed sit behind the lodge. Seventeen removable pines frame the clearing, and a small picnic table sits off the side path.

The parcel model uses 9,191 visible triangles and twelve matte materials. All images use whole 2×2 texel art pixels, 0.0388 m per texel, and nearest/repeat sampling. The cedar roof and plank textures use Timerpg swatches. Cedar is toned down for the woodland palette. Log grain, stone, lantern glass and the sign use original pixel textures.

## Placement

The GLB uses metres, Y up, and a +Z-facing front. Its origin sits at ground level at the center of the south parcel edge. Front and rear entries, the footpath, the hearth and the woodshed approach have anchors. The porch roof slopes away from the front wall and sits at least 40 mm below the main eave. Folded flashing covers the wall junction. Roof clearance, door openings and floor support are checked against the exported geometry. The metadata lists tagged collision boxes. Scenic trees, rocks and furniture live under `lot`.

## Build

Run from this folder in a Timerpg checkout. Texture generation and validation need Pillow and NumPy. The packed model source uses Blender 4.3.2.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The review images use Blender Cycles. The additional pines beyond the parcel and the lighting rig belong to the render scene. The desktop Filament preview loads the GLB with collision-only meshes hidden. In-game placement, stairs and night lighting need Android testing.
