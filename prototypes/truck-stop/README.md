# Cedar Relay Truck Stop

A highway truck stop on a 127 × 144 m parcel with two placeable era states. Both have six pull-through diesel lanes, a three-lane car canopy, a diner and market with fixed booths and a counter, two shower bays, a tall highway sign, 30 marked truck bays and a separate drive-over scale.

| Years | Asset prefix | Roadside identity |
| --- | --- | --- |
| 1960–1989 | cedar-relay-1960 | Independent diner with an arrow pylon and warm neon-style letters |
| 1990 onward | cedar-relay-1990 | Relay Travel Center with teal fascia, a raised entry canopy and a twin-post pylon |

Each era includes near, mid and far GLBs and an editable packed Blender source for the near model.

## Place the stop

- glTF metres, +Y up and +Z toward the highway. The shared grade origin is at the center of the diner and market entrance. All three door positions and facings persist across eras. The shower wing has its own east door. The delivery door opens onto the rear apron.
- The center of every fuel lane is in `model-info.json` with its clear width. Diesel lanes provide 4.33 m horizontal clearance and at least 6.25 m to the lowest fascia. The parking rows are back-to-back, leaving separate aisles for the nose-in and backing approaches. The scale is offset to the east of the truck lot.
- The manifest records scale entry, platform and exit, collision bounds and sign rectangles. Repaint the named `sign_name`, `sign_name_rear` and `sign_store` panels in the sign atlas with the separate letter mask. Lettering on the active roadside signs can emit light. Window masks illuminate only the panes.
- Keep the optional `lot` paving and markings together if using the supplied layout, or recreate the lane markings from the manifest. The highway itself goes into the surrounding map.
- Vehicles, swept turns, refuelling, scale operation, shower controls, runtime lighting and device testing remain for integration. The near model previews use no vehicle props.

## Rebuild

Run from this directory with Blender 4.3 and Python with NumPy and Pillow.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for year in 1960 1990; do
  RELAY_YEAR="$year" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` contains day, night, rear, aerial, entrance, diesel lane, highway pylon and walk-in diner views for both eras. These images are Blender Cycles desktop renders.
