# Marlow Five and Dime

A 10 × 28 m, two-story Main Street storefront with brick party walls and a walkable ground-floor shop. Use the same placement for every state.

| State | Model prefix | Storefront and interior |
| --- | --- | --- |
| 1925–1969 | marlow-1925 | Marlow Dime, oxblood trim, wood ceiling and soda counter |
| 1970–1999 | marlow-1970 | Marlow Variety, fluorescent strips and dropped ceiling |
| 2000 onward | marlow-2000 | Dollar Cove, sale posters and updated storefront |
| Vacant | marlow-closed | For-lease panels, dark windows and cleared sales floor |

Each state has a near GLB and `-mid` and `-far` LODs. The packed Blender files are editable near models. `model-info.json` lists the lot, entrance and rear service anchors, and collision boxes per state and LOD in glTF coordinates and meters. Keep the entrance and service-door apertures clear when installing door props. Use the sign atlas rectangles in `textures/sign-regions.json` for repaints and preserve nearest sampling plus the separate emission masks. The vacant state leaves door locking to the game.

## Rebuild

Run from this directory with Blender and Python with Pillow installed.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for state in 1925 1970 2000 closed; do
  MARLOW_STATE="$state" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` compares day, night, rear, above, entrance and interior views, with close-ups of the storefront, receiving and soda counter signs. The images are Blender Cycles desktop renders.
