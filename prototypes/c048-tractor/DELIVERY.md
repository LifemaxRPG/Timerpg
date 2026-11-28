# C048 — Wren farm tractors

Two original vehicles. The 1952 Wren Row-Crop has paired narrow front wheels, exposed engine details and an uncovered driver's seat. The 1978 Wren Utility has a wider axle, glazed cab, hinged driver door, roof work lights and a three-point hitch.

Each variant includes a full GLB, two distance models, packed Blender source and physics metadata. Paint and emission use material-selected geometry masks. Model-info supplies five repaint options, including a faded farm finish. Repainting and individual light control still need game-side wiring.

## Validation

- `python3 validate.py` checks all six GLBs for indexed triangle counts, material limits, required full-model nodes, wheel/steering hierarchy and texture sampling.
- Row-Crop has 1,772 full, 444 mid and 116 far triangles. Utility has 2,024 full, 600 mid and 116 far triangles.
- The review sheet includes both generations from front, rear, side and above, at night, with driver access visible, and alongside the C011 Northcairn and a 1.75 m reference figure. These are Blender Cycles renders. Filament, physics, playability and device performance remain untested.

## Self-review

1. The fenders and rear tread are the most simplified areas. I kept the large rear tyres and raised tread blocks readable within the vehicle budget, and used the rear and side views to check their silhouette.
2. I checked exported GLB data with the validator and inspected the rendered review views. I pulled the camera back on the utility tractor and lowered the glass opacity after the first pass to give the roof and interior more room to read.
3. The narrow paired wheels and open seat immediately distinguish the 1952 tractor from the wider, cabbed 1978 one.

## Usage

Initial delivery, zero review rounds. Model setting, token totals, plan usage and exact elapsed time are not visible.
