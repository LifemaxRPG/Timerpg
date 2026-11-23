# Timerpg Later Vehicles

Four original vehicles from the late 1960s through the 2010s.

| Vehicle | Era | Construction |
| --- | --- | --- |
| Tallmere 72 Pickup | 1968–1976 | Single cab, bench seat, ribbed fleetside bed, and bottom-hinged tailgate |
| Rowanvale Sprint | 1968–1974 | Long hood, short deck, bucket seats, four round headlamps, and removable twin stripes |
| Pinelatch City 3 | 1990–1997 | Three-door compact hatchback, dark plastic bumpers, and a liftgate with moving rear glass |
| Stoneweir Wayfarer | 2012–2018 | Raked crossover with black roof and pillars, a rising beltline, rounded quarters, and a hatch-wide rear light bar |

Every vehicle has near, mid and far GLBs and a packed Blender source for the near model.

## Game handoff

- Units are metres. +Y is up, +Z forward and +X vehicle-left. The origin is on the ground midway between axles. `model-info.json` lists pivots, their parenting, seat and exit anchors, chassis boxes, wheel fitment and era performance estimates.
- Steering pivots rotate about local +Y, and wheels spin about local +X. Side doors hinge about local +Y. Hood, pickup `tailgate`, coupe `trunk`, and the hatch and crossover liftgates hinge about local +X. The liftgates use the `trunk` node name.
- The pickup has an open bed even with the tailgate shut. The coupe's `stripe_hood` and `stripe_trunk` nodes can be hidden to remove the livery in near and mid models. Their painted surfaces move with the lids.
- Paint masks accompany the primary and secondary paint textures. Use the palette and stock trim in the manifest. The badge is a separate near-model mesh with a documented atlas center. The lamp lenses use a dedicated emission mask. The game can bind lamp material instances to control individual lights.
- The Wayfarer uses its secondary paint mask for the two-tone roof. The pillars and lower cladding use dark trim. The front daytime running strips are `light_drl_l` and `light_drl_r`. `light_tail_bar` follows the liftgate and joins the body-mounted corner lamps with the gate shut.
- The far models retain anchor nodes and closed, reduced geometry. Driving, entry and exit interaction, lamp controls and Android performance remain game integration work.

## Rebuild

With Blender 4.3, Pillow and NumPy, run from this directory.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for car in tallmere-1972 rowanvale-1970 pinelatch-1993 stoneweir-2015; do
  CAR_MODEL="$car" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` contains Blender Cycles desktop renders of each vehicle, including open cargo, driver-door, and 1.75 m scale views.
