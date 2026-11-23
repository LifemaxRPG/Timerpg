# Briarford Police Station & Town Jail

One original brick civic building and four-cell jail in three site states. The parcel and main brick shell share one origin. Each era keeps the public entrance facing glTF +Z. The modern door anchor moves out to the face of the new vestibule.

| State | Street and operations |
| --- | --- |
| 1900–39 | Limestone arch, wanted cabinet, hitching rail, sergeant's counter, chief's office and four barred cells behind the station |
| 1940–79 | Enamel entry hood, radio mast, booking and interview rooms, squad garage and parking bays |
| 1980 onward | Glass public vestibule, records room, CCTV, steel holding doors, secure transfer corridor, sally port and fenced rear lot |

Start with `model-info.json`. Each state lists its near, mid and far GLBs, named doors, collision boxes, anchors, four cell floors and hinge pivots. Use the near model for interior and interactive placement. The mid and far models shed room detail and omit physical collision meshes. Town names can be repainted at `sign_name` using the per-state rectangle. The original wanted notices have separate UV rectangles.

Fixed barriers use the listed boxes. The heavy jail door, cell doors and modern yard gate have separate closed positions in metadata so the game can remove or move those barriers when it opens them. The window bars, exterior vents and roof hatch are separate hooks. The model includes bunk and chair anchors for actors. Door animation, locks, CCTV, one-way glass visibility and escape behavior require game integration.

## Rebuild and review

Run from this folder with Blender 4.3 and Python 3 with Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for era in 1900 1940 1980; do
  STATION_ERA="$era" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

The three editable `.blend` files contain packed textures. The review sheet shows actual Blender Cycles renders of the near GLBs. Inputs include Timerpg's shared swatches for brick and asphalt, with additional finishes painted on the same nearest-filtered 2×2 texel grid.
