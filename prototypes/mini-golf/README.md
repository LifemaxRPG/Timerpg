# Thimble Trails Mini Golf

A 60 × 70 m roadside course with 18 distinct greens around a creek, a footbridge and raised rock banks. The route climbs a low hill before descending at hole 11. Hole 14 has a hollow drop pipe. The ticket hut and reserved statue pad keep their original transforms across eras.

| Years | Asset prefix | Condition |
| --- | --- | --- |
| 1962–1989 | thimble-trails-1962 | Open, with colored path bulbs, a lit windmill and lighthouse lantern, and water in the creek |
| 1990–2005 | thimble-trails-1990 | Sun-faded paint and carpet, with dimmer lights in the preview |
| 2006 onward | thimble-trails-2006 | Cracked carpet, a dry creek and pond, overgrowth, a missing windmill blade, and a sagging fence with a gap |

Each era includes near, mid and far GLBs and an editable packed Blender source for the near model.

## Place the course

- Each glTF unit is one metre. +Y is up and +Z faces the road. Set the origin on the front wall midpoint. Lot bounds are in `model-info.json`.
- Leave the 9.76 × 11.72 m usable face of `statue_pad` clear. Attach the game-supplied statue to `statue_anchor`. The closed-state fence stays outside the statue pad and hut door clearances.
- Rotate `windmill_blades` around its local glTF +Z axis during open eras and leave it stationary after closure. The three-bladed closed model keeps the pivot. Near and mid models list the positions and colors of path bulbs, the windmill spotlight and the lighthouse lamp. The previews use Blender lights at those positions. The GLBs carry emissive lens faces and attachment data.
- The manifest lists tee and cup coordinates, grade along each route, hole 14’s `pipe_entry` and `pipe_exit` transforms, box collisions, and repaint rectangles for the ticket hut’s lettering and the hole plates.
- Ball physics, shot handling, terrain traversal, lighting in Filament, Android performance and gating closed courses remain integration work. The creek and spillway are static geometry.

## Rebuild

Run from this directory with Blender 4.3, Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for year in 1962 1990 2006; do
  THIMBLE_YEAR="$year" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` shows all three states from the front, rear and above, at night, at the entrance, in the ticket hut and among the holes. These images are Blender Cycles desktop renders.
