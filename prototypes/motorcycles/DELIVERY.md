# C013 · Motorcycles across the century

Delivered three original motorcycles. The Brackenton has an exposed V-twin, valances and a sprung saddle. The Marrowfield has a round headlamp, upright bars, twin shocks and a tank stripe. The Kelveran has a full fairing, swept windscreen, clip-ons and an angled painted slash. Each model has a folding kickstand, two seat anchors, separate lamps and repaint masks.

## Verification

| Motorcycle | Near triangles | Mid triangles | Far triangles |
| --- | ---: | ---: | ---: |
| Brackenton Quarry Twin | 1,606 | 626 | 108 |
| Marrowfield Standard 550 | 1,710 | 598 | 108 |
| Kelveran Slipstream | 1,414 | 528 | 96 |

- Checked all nine GLBs for material and triangle budgets, physical pixel and UV scale, paint and lamp masks, steering rake, axis parenting, wheel ground contact and deployed kickstand clearance. Visual review caught a displaced sport-bike front mudguard. I corrected the placement and added a longitudinal bounds check. The metadata and all nine GLBs reproduced byte for byte.
- Khronos glTF Validator reported zero errors and warnings. The near Blender files opened with packed PNGs. The three near GLBs rendered in standalone Filament WebGL at about 30 m.
- The review sheet uses actual GLB renders in Blender Cycles. It includes both ends, side and top, the headlamp at night, the rider controls, stand deployment and a 1.75 m reference.
- Riding mechanics, passenger animation, dynamic steering and lean clearance, circuit controls and S25 performance remain untested in the game. Mass and speed are estimates.

## Self-review

1. The cruiser’s small linkage and cable details simplify quickly at mid distance. I kept its valances, saddle, round lamp and tall handlebar profile as the main visual identifiers.
2. I inspected the actual near GLBs from both ends and beside the scale figure. The scripted checks confirmed wheel alignment and the kickstand's parked contact at the documented angle. Rider fit and handling still depend on the game’s animation and physics.
3. The maroon valances, gold tank stripe and blue fairing distinguish the three eras at a glance.

## Usage

- Model and setting: Codex. Exact variant and reasoning setting were unavailable.
- Tokens and plan usage: unavailable.
- Effort: work resumed after an interruption. About 2 hours 35 minutes elapsed from first queue check to delivery, including the interruption. Full tool-call total was unavailable.
- Rework: initial delivery, zero review rounds.
