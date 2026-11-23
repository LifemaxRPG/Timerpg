# Vale Lantern Fuel

Three eras of an original roadside station on one shared origin, facing +Z. All versions keep `door_main`, `door_back`, `cashier_counter`, `cashier_clerk`, and `cashier_guest` in the same positions.

| Era | Forecourt and shop |
| --- | --- |
| 1925–1944 | Cottage with shingled canopy, two transparent gravity globes, visible amber fuel levels, an oil rack and a freestanding air post |
| 1945–1974 | Enamel station, two open service bays with lifts and stacked tyres, two pump islands, high pole-sign lantern and a pay phone |
| 1975 onward | Wide folded canopy, six self-service pumps on three concrete islands, LED price board, ice chest and digital air machine |

`model-info.json` lists colliders in glTF metres and an interaction row per pump. `pump_<n>_nozzle` marks the physical handle, and `pump_<n>_use` marks the adjacent standing point. The two middle-era service bays have clear thresholds at `door_service_<n>`, separately named overhead leaves at `service_bay_<n>`, and work anchors inside. The leaves are exported in the open pose.

For each GLB use that state's name and price rects. Front and back share each rect, so one repaint reaches both faces. The owner name and fuel price use separate textures and mesh groups. The per-state `price` object follows the existing landmark catalogue's whole-texture replacement format. Both price faces sample the full dedicated texture. Vintage sample prices are cents per gallon with one decimal. The modern sample is currency per gallon with two decimals. Runtime formatting and mask repainting can use the documented rectangles. Refuelling logic, per-town prices, lifts, bay-door animation and checkout are game integration work.

## Rebuild

Run from this folder with Blender 4.3 and Python 3 with Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_station.py
python validate_station.py
for era in 1925 1945 1975; do
  STATION_ERA="$era" blender -b --python-exit-code 1 --python render_station.py
done
python package_review.py
```

Editable packed Blender files and the 21 desktop Blender renders are included. Preview lights and road context are staged separately from the models. The source uses Timerpg's shared asphalt, pavement and slate swatches plus original textures on the same 2 × 2 art-pixel grid.
