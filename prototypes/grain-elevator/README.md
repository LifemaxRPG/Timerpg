# Lark Bend Grain Elevator

An original Plains rail-side grain elevator built in three eras. Each era comes as a near, mid and far GLB, with an editable packed Blender file for the near model.

| Years | Model prefix | Site |
| --- | --- | --- |
| 1925–1949 | lark-bend-1925 | Timber elevator, receiving canopy and timber rail spout |
| 1950–1989 | lark-bend-1950 | Timber and concrete plants side by side, six 35.72 m bins, transfer leg and raised gallery |
| 1990 onward | lark-bend-1990 | Concrete plant and co-op lettering, with the timber building removed |

The scale office and truck scale are common to all three. The office has a clear front and yard doorway, open sashes with framed glazing, and a fixed records counter. The bin interiors, machinery and rooftop inspection route are visual industrial structures. Player access is scoped to the office, yard and covered receiving apron.

## Placement

- Put the origin at grade, in the middle of the office front. GLBs use metres, +Y up and +Z toward the street. Reuse exactly the same origin in each era. Allow up to 71 × 29.1 m for the paved lot and service edge, plus the rail clearance behind it. The headhouse vent rises to 44.71 m.
- Use the local-space AABBs in `model-info.json` with the asset transform. `lot` groups the yard, scale, drive, fence and optional exterior dressing. Preserve the office apron walk-up when supplying replacement paving.
- The game supplies the track. The siding centerline spans local X −27 to +47 m at Z −28.5 m. Both spout tips are directly over that line. Rails, cars, switch routing, spout animation, truck scale logic, runtime LOD switching and Filament integration are outside this delivery.
- Repaint the atlas rectangles in `model-info.json` and `textures/sign-regions.json`. The concrete `sign_name` panel maps three equal-width facets, left to right, and uses alpha to leave the concrete visible through the faded letters. `sign_trade` carries either GRAIN or CO-OP. The timber plant has its own `sign_wood` panel.

## Rebuild

Run from the prototype directory with Blender 4.3, Python, NumPy and Pillow.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for state in 1925 1950 1990; do
  LARK_STATE="$state" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` shows each exported near model in day, night, rear, above, eye-level, interior and painted-name views. The images are Blender Cycles desktop renders.
