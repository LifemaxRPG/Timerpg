# Rillhaven Torrel and Torrel RS

Two original mid-engine targas for Timerpg. The Torrel uses a low cutoff nose, rectangular lamps, exposed chrome, a fixed dark targa hoop and a steep, chopped tail. The RS widens the haunches, staggers the tyres and adds a raised cutoff wing and deep-dish wheels. Both have a front luggage compartment and a separate engine bay immediately behind the two seats.

## Setup and fitment

| Suggested input | Torrel 1968–76 | Torrel RS 1988–96 |
| --- | ---: | ---: |
| Mass | 976 kg | 1,088 kg |
| Static weight, front/rear | 43/57 | 42/58 |
| Wheelbase | 2.310 m | 2.360 m |
| Track, front/rear | 1.388 / 1.430 m | 1.478 / 1.530 m |
| Effective tyre width, front/rear | 165 / 183 mm | 205 / 235 mm |
| Tyre radius, front/rear | 303 / 303 mm | 311 / 323 mm |
| Target top speed | 188 km/h | 224 km/h |
| Target 0–100 km/h | 8.9 s | 6.4 s |
| Suggested final drive | 3.91:1 | 3.82:1 |

These are estimates for initial tuning. At the target speeds, the driven rear tyres and suggested top-gear ratios imply about 5,600 rpm in either model, using nominal radius and ignoring tyre slip. The per-axle entries in `wheel_axles` supply the exact render and physics fitment. The top-level `track_m` and `wheel_radius_m` expose the front reference for a consumer that expects a single value.

Use glTF metres, +Y up, +Z forward, +X vehicle left. Origin is below the axle midpoint. The four hub nodes use local X for spin. Front wheels sit below their Y-axis steering pivots. The doors swing about local Y. The front luggage lid (`hood`) and mid-engine deck (`trunk`) open about local X. The `roof_panel` can be moved or hidden separately from the fixed hoop and windshield. Collision boxes describe the closed static body. Apply runtime transitions to their appropriate collision state if needed.

Each car has six body colours and a separate secondary paint material for the roof and sill. The paint-mask pixels are white throughout because paint regions are selected by material. Lamps, glass, cabin surfaces and the original Torrel badge use independent materials or UV regions. The named interaction nodes and lamp positions are consistent across each car's LODs. Detailed cockpit pieces and lamp faces can be dropped at distance.

## Rebuild

Run from this folder with Blender 4.3 and Python 3 with Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for era in 1968 1988; do
  CAR_MODEL="torrel-$era" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

Packed Blender source is included. The review sheet shows desktop Blender Cycles renders of the actual near GLBs. The reference figure and twisty road are staging. Driving, suspension movement, paint selection, roof removal and mobile rendering require integration and playtesting in Timerpg.
