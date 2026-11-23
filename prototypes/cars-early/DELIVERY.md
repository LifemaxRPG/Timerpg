# C011 · Early Cars

Delivered the Alderwick Six touring sedan, Fenmere Club Coupe and Northcairn Terrace family sedan. Each has a finished interior, opening doors, a separate steering wheel, axle-based steering and spin pivots, lamp sockets, repaint masks, a documented badge and three distance models. The 1938 and 1956 cars have separate trunk lids.

## Verification

| Vehicle | Near | Mid | Far |
| --- | ---: | ---: | ---: |
| Alderwick Six | 2,896 | 898 | 140 |
| Fenmere Club Coupe | 2,558 | 852 | 136 |
| Northcairn Terrace | 2,820 | 1,090 | 136 |

- Checked triangle counts, physical texel density, wheelbase and track, named nodes and their parentage, door hinge math, paint and emission masks, and ground contact across all nine GLBs.
- Khronos glTF Validator reported zero errors and warnings for all nine exports. All three near Blender files opened with packed textures, and the three near GLBs rendered in standalone Filament WebGL, including silhouette checks at about 30 m. The metadata and all nine GLBs reproduced byte for byte.
- The review sheet shows the actual near exports from the front, rear, side and above, under daylight and night lighting, through the open driver's door and beside a 1.75 m reference. These are Blender Cycles desktop renders.
- The collision and performance figures are estimates for physics setup. Door interaction, drivetrain, signal controls, and Android/S25 behavior remain untested in the game.

## Self-review

1. The moving parts and silhouettes carry the era cues. The interiors use simple bench and dash geometry, with most dashboard detail drawn in the texture. I framed a close view through each driver's door to check that the controls are still legible.
2. I checked the visual poses in Blender and verified the exported hinge positions and sweep direction numerically. The in-game driver pose and sweep clearances with physics bodies still need integration testing.
3. The first things I notice are the upright dark coach with bright spokes, the coupe's long nose and rounded back, and the family sedan's white roof and small fins.

## Usage

- Model and setting: Codex. Exact variant and reasoning setting were unavailable.
- Tokens and plan usage: unavailable.
- Effort: one delivery turn, about 15 minutes from workspace readiness. Full tool-call total was unavailable.
- Rework: initial delivery, zero review rounds.
