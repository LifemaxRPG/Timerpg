# C015 · Vale Lantern Fuel

Built three eras of an original filling station. The shingled cottage has two translucent gravity pumps, a visible fuel level, oil cans and an air post. The enamel station adds two islands, a high price pole, a pay phone and two open service bays with lift fixtures and tyres. The later station has a wide folded canopy, six numbered dispensers, an LED price board, an ice chest and an air machine.

All states share the origin, office entrances and cashier anchors. The pump handles and adjacent standing points are named. The middle-era bay leaves are separate nodes in the open position. The owner name and prices have separate artwork. Each state's price metadata uses the existing whole-texture replacement format, with the same dedicated texture on both faces.

## Verification

| Era | Near triangles | Mid triangles | Far triangles | Visible near materials |
| --- | ---: | ---: | ---: | ---: |
| 1925–1944 | 1,696 | 1,148 | 1,024 | 12 |
| 1945–1974 | 1,608 | 1,304 | 1,104 | 11 |
| 1975 onward | 2,290 | 1,642 | 1,354 | 11 |

- Local validation checked all nine GLBs for physical UV scale, nearest sampling, pane-only emission, sign and price UVs, collision boxes and eight clear office and service-shop thresholds. It also checked all ten refuelling standing points against collision.
- Khronos glTF Validator found zero errors and warnings. The packed Blender sources reopened. The 1925 near model rendered in standalone Filament WebGL.
- The review sheet contains the 21 requested Blender Cycles desktop renders. Refuelling, checkout, service-bay animation, runtime price lettering and phone performance need an in-game pass.

## Self-review

1. The glass reservoirs and hose details read best once the player reaches the forecourt. I kept their caps and liquid levels distinct so the two gravity pumps still have a recognizable silhouette under the cottage canopy.
2. I checked the models from the road, in the office and around the lot. I moved the entrance camera closer to the pumps, tied the columns into the sloped canopy beam, and separated the price texture for the existing replacement path. I verified geometry and metadata, then left driving, standing interactions and runtime pricing to a playtest.
3. The pumps and elevated price board are the first cues. The shingled cottage gives way to the garage wing and then to the broader roof and numbered self-serve islands.

## Usage

- Model and setting — Codex. Exact variant and reasoning setting not visible.
- Tokens and plan usage — not visible.
- Effort — one turn. Tool-call total not visible. Approximately 11 minutes from queue check to push.
- Rework — first delivery.
