# C012 · Later vehicle models

Delivered the Tallmere 72 Pickup, Rowanvale Sprint, Pinelatch City 3 and Stoneweir Wayfarer. The pickup has a real open bed and dropping tailgate. The coupe has removable hood and deck stripes. Both liftbacks carry rear glazing on the hinge. The compact has dark plastic bumpers, and the crossover adds a contoured front valance and LED-style lamps.

## Verification

| Vehicle | Near triangles | Mid triangles | Far triangles |
| --- | ---: | ---: | ---: |
| Tallmere 72 Pickup | 2,300 | 878 | 134 |
| Rowanvale Sprint | 2,362 | 884 | 120 |
| Pinelatch City 3 | 2,172 | 846 | 120 |
| Stoneweir Wayfarer | 2,540 | 870 | 120 |

- The prototype validator checked all 12 GLBs for physical UV scale, 2×2 art pixels, material and LOD budgets, paint and emission masks, axle and hinge transforms, door sweep, the clear pickup bed, moving liftback glazing and separate stripe nodes. Every near model uses at most six materials.
- Khronos glTF Validator reported zero errors and warnings. The four near Blender sources opened with packed images. The near GLBs also rendered in standalone Filament WebGL at roughly 30 m. The metadata and all 12 GLBs reproduced byte for byte.
- The review sheet uses Blender Cycles desktop renders. It shows the views around each vehicle, headlights at night, driver and cargo openings, and a 1.75 m preview mannequin.
- Simulator acceleration, suspension, vehicle interaction, lighting controls and S25 performance remain untested in the game. The manifest's mass and speed figures are estimates.

## Self-review

1. The hatchback uses a simple block interior at close range. I gave it separate buckets, a textured gauge panel and a cargo floor so the car still reads through its windows and open doors.
2. I checked the exported poses visually and tested hinge and mask behavior from the files. I did not run the game's physics or driver animations.
3. Each roofline is different at a glance. The pickup's low open bed, the coupe's long striped hood, the hatch's short shell, and the crossover's roof rails and sloped fascia separate the four silhouettes.

## Usage

- Model and setting: Codex. Exact variant and reasoning setting were unavailable.
- Tokens and plan usage: unavailable.
- Effort: one delivery turn, about 11 minutes from workspace readiness. Full tool-call total was unavailable.
- Rework: initial delivery, zero review rounds.
