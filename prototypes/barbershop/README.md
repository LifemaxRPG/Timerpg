# Mercer and Lilac

Two 6 × 18 m Main Street shops with finished brick party walls, alley service doors and walk-in interiors. Each style has a near GLB, `-mid.glb`, `-far.glb` and an editable packed Blender file for the near model.

| Years | Mercer | Lilac |
| --- | --- | --- |
| 1940–1979 | Oxblood barbershop, painted fascia and three pedestal chairs with mirrors | Sage beauty parlour with three chair-mounted hood dryers |
| 1980–2009 | Mercer Hair Co. with a new sign, broad canopy and tiled floor | Lilac Styling with a mauve reface and retained dryers |
| 2010 onward | A dark-green barbershop revival with larger panes and the original rotating pole | Lilac Nail Room with three manicure stations replacing the dryers |

## Integration

- Unit is one metre. +Y is up and +Z faces the street. All six models share a grade origin at the center of the street wall and identical `door_main` and `door_back` transforms. The sidewalk, rear pad and waste box are grouped under `lot`.
- `model-info.json` lists box colliders, sign plate rectangles and era and distance variants. Repaint `sign_name` through the shared PNG atlas. Lettering leaves a full pixel margin for the border, and the pane mask leaves the frame pixels dark.
- Mercer’s separate `barber_pole` drum rotates around its local glTF +Y axis. The collars and wall brackets are part of the stationary shell. The same pivot is provided at every LOD. A period of 3.5 seconds is suggested. Engine animation and mirror reflections are integration work. The mirrors use static pixel art.
- The rear staff partition has an open passage. Equipment is fixed geometry with conservative collision boxes. Doors, hairstyle interactions and staffed business behavior are game systems.

## Rebuild

Run from this directory with Blender 4.3 and Python with NumPy and Pillow.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for state in barber-1940 barber-1980 barber-2010 salon-1940 salon-1980 salon-2010; do
  SHOP_STATE="$state" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` shows the six states from the front by day and night, from the back and above, at the entrance and inside. These are Blender Cycles desktop renders.
