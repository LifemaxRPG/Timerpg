# C017 · Rillhaven Torrel and Torrel RS

Built two generations of a compact, original mid-engine sports car. The 1968–76 Torrel has narrow tyres, chrome bumpers, rectangular lamp recesses and a wood-rim steering wheel. The 1988–96 Torrel RS adds wider arches, staggered fitment, dished wheels and a raised wing. Both have a removable targa panel, two separate seats, a front luggage opening and a rear cover over the mid-engine bay.

Each generation includes six colour choices and separate paint masks. `model-info.json` lists the interaction pivots, chassis colliders and suggested mass, weight split, per-axle fitment, speed, acceleration and gearing. Those numbers are estimates for game tuning.

## Verification

| Model | Near triangles | Mid triangles | Far triangles | Near materials |
| --- | ---: | ---: | ---: | ---: |
| Torrel | 2,808 | 1,004 | 140 | 5 |
| Torrel RS | 2,876 | 1,040 | 142 | 5 |

- All six GLBs passed pivot, geometry, texture density, paint, and lamp emission checks. Each near model passed 84 static tyre-clearance probes through the side panels.
- Khronos glTF Validator reported zero errors and zero warnings on all six GLBs. Both packed Blender files reopened, and both near models rendered in standalone Filament WebGL.
- The review sheet uses desktop Blender Cycles. It includes the required inspection angles, open doors, detached roof, cockpit, engine bay, scale figure, low hero view and staged winding road. Handling, suspension travel and S25 performance still need game testing.

## Self-review

1. Small gauges and the badge are hard to see at driving distance. I gave each cockpit a different finish and used the chrome or dark trim, wing and wheel fitment as the larger cues.
2. I inspected the closed and open renders, adjusted the side intake to sit clear of the wider RS body, and tightened the LODs. The local tests cover static geometry, dimensions, and node transforms. The grip balance and acceleration require a physics playtest.
3. The low wedge and fixed targa hoop read first. The RS wing and wider rear wheels distinguish it from the orange original at a glance.

## Usage

- Model and setting — Codex. Exact variant and reasoning setting not visible.
- Tokens and plan usage — not visible.
- Effort — one turn. Tool-call total not visible. Approximately 11 minutes from queue check to push.
- Rework — first delivery.
