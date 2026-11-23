# Birchbend Motor Lodge

An original L-shaped roadside site. The low court wings, parking area and street-facing office keep one origin and a shared ground-floor layout as the motel changes.

| State | What you see |
| --- | --- |
| 1935–1954 | Timber motor court with deep carports, seven rooms and a tall enamel road sign |
| 1955–1984 | Neon arrow, separately controlled VACANCY and NO panels, a butterfly-roof office, ice machine and fenced kidney-shaped pool |
| 1985 onward | Two-storey exterior corridors, a west stair and landing, fourteen rooms, a backlit sign, and covered ice and vending machines |

The near GLBs have clear exterior entries and finished room shells. Furnish each guest room using its bed anchor. Each `room_<n>` has the associated `room_<n>_door` and `room_<n>_bed` nodes. Rooms 1–7 remain stable across eras and 8–14 are upstairs. The office keeps `door_main`, `door_back`, `counter`, `counter_clerk` and `counter_guest`. `model-info.json` lists collision boxes, sign rectangles and all anchor coordinates in glTF metres. The modern stair has tread geometry and approach metadata. The distant GLBs simplify the stair and interior geometry.

Both sides of the roadside sign share a repaint rect per era. Select the state-specific `signs` entry when taking that era into the game. The pool has its own group and a gate anchor. For the middle era keep VACANCY lit and switch the separate NO group and material on when full. The exported default is vacant. The model gives the game placement hooks for the covered machines and leaves check-in, sign control, stairs and swimming to runtime integration.

## Rebuilding

Run here with Blender 4.3 and Python 3 with Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_motel.py
python validate_motel.py
for era in 1935 1955 1985; do
  MOTEL_ERA="$era" blender -b --python-exit-code 1 --python render_motel.py
done
python package_review.py
```

Packed editable Blender files are included. The review sheet shows actual desktop Blender Cycles renders, with separate preview lights and road context. Surfaces use Timerpg swatches and original additions on the same 2 × 2 art-pixel grid.
