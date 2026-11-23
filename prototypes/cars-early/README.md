# Timerpg Early Cars

Three original American passenger cars for the driving prototype.

| Asset | Era | Model details |
| --- | --- | --- |
| Alderwick Six | 1927–1930 | Tall four-door coachwork, split windscreen, separate fenders and running boards, bright wheel spokes and a rear-mounted spare |
| Fenmere Club Coupe | 1937–1940 | Long crowned bonnet, swept roof, pontoon fenders, vertical grille and a rounded trunk |
| Northcairn Terrace | 1955–1957 | Two-tone four-door sedan, wraparound glass, chrome side trim, whitewall tyres and small fins |

Each car ships as near, mid and far GLBs. The near model also has a packed, editable Blender source.

## Game handoff

- glTF units are metres. +Y is up, +Z forward and +X vehicle-left. The origin is the ground point midway between axles. Axle, wheel, seat, exit, door and lamp data are in `model-info.json`.
- Turn `steer_fl` and `steer_fr` around local +Y and spin their child wheels around local +X. Rear wheels also spin around local +X. Doors hinge about local +Y. The hood and the later trunks hinge about local +X. The Alderwick carries its spare outside the coachwork and has no lid for a built-in trunk.
- Each lamp anchor owns a lens using `glow_lamps`. The emissive atlas covers lens faces. The game can drive lens intensity per mesh instance or bind a separate material instance at each lamp. The oldest coach has head, tail and brake lenses. The 1938 coupe adds indicators, and the 1956 sedan also adds reverse lamps.
- Paint masks accompany the matching stamped-color textures. Bind the masks to `paint_primary` and `paint_secondary`, and read the palette and stock trim color from the manifest. The badge has its own mesh and documented atlas position for swapping the emblem.
- The manifest includes estimated mass and era top speed, wheel radius, wheelbase, track, and two coarse chassis boxes. The far models retain anchor nodes for identity and use closed, low-detail geometry.
- Driving, entry and exit interaction, collision tuning, lamp controls, and S25 performance remain game integration work. The person in the scale render is a 1.75 m preview mannequin.

## Rebuild

From this directory with Blender 4.3, Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for car in alderwick-1928 fenmere-1938 northcairn-1956; do
  CAR_MODEL="$car" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` contains eight Blender Cycles desktop views per car, including the night, open-door, interior and scale shots.
