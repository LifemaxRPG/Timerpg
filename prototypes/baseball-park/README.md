# Alder Bend Ballpark

A small-town diamond with a covered wooden grandstand, three seating bays for about 600 spectators, foul-line bleachers, dugouts, a ticket booth, a concession stand and a gravel lot. The outfield fence carries four original local ads and a repaintable scoreboard.

| Years | Asset prefix | Changes |
| --- | --- | --- |
| 1935–1949 | alder-bend-1935 | Original wood grandstand and bleachers with a hand-numbered scoreboard |
| 1950–1959 | alder-bend-1950 | Same wood park with four floodlight towers |
| 1960–1999 | alder-bend-1960 | Steel foul-line bleachers and the lit field |
| 2000 onward | alder-bend-2000 | Aluminium seating, mown turf and an updated scoreboard |

Use the unsuffixed GLB for the near model and the `-mid` and `-far` files as distance variants. Packed Blender files contain the corresponding near assets. The covered grandstand has 10 rows with three 20-seat bench bays per row, assuming a 48 cm seat pitch.

## Place the park

- glTF metres, +Y up and +Z toward the street. Place the shared origin at grade at the center of the entry gate. The lot occupies roughly 145 × 156 m, with an outfield wall 118 m from home at the center. The bases are 90 feet apart. The wood park roof is 9.38 m high and the light towers reach 17.37 m.
- The main gate and both booths have open apertures. The concession and ticket interiors include fixed counters, and the field is accessible around both ends of the stand and through the open ends of the backstop. Keep the gate apron when replacing the optional `lot` paving.
- `model-info.json` includes three door anchors, box colliders, base and mound locations, and the atlas rectangles for signs and ads, including `sign_name` and `sign_scoreboard`. The scoreboard face has sample values. Live digits, crowd seating, tickets and baseball rules are future game work.
- Spectators can be staged by the game. The bench banks use conservative rectangular collision envelopes. Precise bleacher and grandstand traversal, gameplay, Filament lighting and Android performance remain for integration. The models include emissive lamp faces from 1950. The actual field illumination in the review comes from Blender preview lights.

## Rebuild

From this folder, with Blender 4.3 and Python with NumPy and Pillow.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for year in 1935 1950 1960 2000; do
  ALDER_YEAR="$year" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` includes day, night, rear, overhead, gate-height, field-height and concessions interior views of all four states. These are Blender Cycles desktop renders.
