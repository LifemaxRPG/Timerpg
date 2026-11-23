# Thimble Trails Mini Golf

A 40 × 50 m roadside course with 18 tee and cup pairs and molded obstacle scenery. The themed holes include a windmill, striped lighthouse, volcano, a pond and footbridge, a covered bridge and a castle gate. The ticket hut includes an open street door, rear service door and fixed sales counter.

| Years | Asset prefix | Condition |
| --- | --- | --- |
| 1962–1989 | thimble-trails-1962 | Open, with warm sign lamps and saturated fiberglass paint |
| 1990–2005 | thimble-trails-1990 | Sun-faded paint and dry turf |
| 2006 onward | thimble-trails-2006 | Chain-link perimeter, CLOSED sign, boarded sash, worn greens and dark windows |

Each condition has near, mid and far GLBs and a packed Blender source for the near model.

## Place the course

- One metre per glTF unit, +Y up and +Z toward the road. The common grade origin is on the main doorway's front edge. The same door, tee, cup and statue transforms apply in every era.
- The ellipse under `statue_pad` leaves 9.76 × 11.72 m usable at its top. Attach the game-supplied statue to `statue_anchor`. The blank pad is part of this asset. The closed state fences the site without changing the hut’s door apertures.
- Rotate `windmill_blades` about its local glTF +Z axis. The shaft and tower stay fixed. Stop the windmill in the closed state. The animation setup, shot routing and ball physics remain game work.
- `model-info.json` lists hole coordinates, full-height blocking boxes, and the repaint rectangles for `sign_name` and the other key signs. The course uses low lips and real cup openings. The windmill and castle openings are sized for a putt. The two bridges have open pedestrian decks.
- Keep the grading, yard paths and enclosure under `lot` when using the supplied site layout. Android and Filament integration, bridge traversal and closure interaction gating remain to be tested in game.

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

`review.jpg` compares each condition at day and night, from the rear and above, at the entrance, among the holes and inside the ticket hut. The images are Blender Cycles desktop renders.
