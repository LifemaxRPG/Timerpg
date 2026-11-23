# Latch & Lyre Pawn Shop

A narrow brick side-street shop with an open threshold, a service door in the alley, an office safe and a stockroom behind the clerk.

| Era | Fittings |
| --- | --- |
| 1920–1959 | Brass pawnbroker balls, sign-painted LOANS windows, an enclosed jewellery case, a brass clerk grille and instrument, tool and valve-radio displays |
| 1960–1999 | Neon-style PAWN and GUNS - GOLD - TV signage, a buzzer and burglar bars, television cabinets and tagged storage cartons |
| 2000 onward | LED WE BUY GOLD panel, security glass and transfer tray, three camera nodes, electronics and game consoles |

`model-info.json` lists each model and its metadata. `door_main`, `door_back` and `door_store` share positions and facing across the eras. The two glazing panels and the later grille nodes have separate collider attachments. Business-name repainting uses only each era's `sign_name` region in `textures/signs.png`.

The near model ships with decorative stock under `display_stock`. Replace that group with spawned inventory and use the named display and surface anchors in metadata. The shelves, jewellery case and central island are fixed architecture. The safe, counter, clerk and customer spots, buzzer and cameras are interaction hooks. Their behaviors, including security glass and nighttime lighting control, require game integration.

Build from this folder with Blender 4.3 and Python 3 with Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for era in 1920 1960 2000; do
  PAWN_ERA="$era" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

The packed Blender files retain editable objects and textures. The review sheet shows Blender Cycles renders of the shipped near GLBs. Brick, floor planks and paving use Timerpg's in-repository swatches. Written signs, glass, finish variations and the three stages of the alley advertisement use the same 2 × 2 art-pixel grid.
