# C009 · Briarford Police Station & Town Jail

Built a three-era civic station on one site. The original brick shell, limestone trim and rear jail carry through all three states. The radio era adds a mast, interview and booking rooms, and a squad garage. The modern station adds a glass vestibule, records shelving, CCTV mounts, a secure connection from the sally port to the cells, and a fenced yard.

All four cells have a named floor, hinge pivot, bunk anchor and window-bar node in each era. The jail transfer door and yard gate have separate collision metadata for their closed poses. The town name and both historical wanted notices have their own repaint rectangles.

## Verification

| Era | Near triangles | Mid triangles | Far triangles | Visible near materials |
| --- | ---: | ---: | ---: | ---: |
| 1900–1939 | 4,444 | 2,376 | 1,572 | 12 |
| 1940–1979 | 4,744 | 2,684 | 1,832 | 12 |
| 1980 onward | 6,080 | 3,506 | 2,520 | 12 |

- The local validator checked all nine GLBs. It measured texture density and lot height, checked masks and pivots, compared exported collision boxes with metadata, and tested the main entrance, garage entrance and all cell thresholds for fixed obstructions.
- Khronos glTF Validator reported zero errors and zero warnings across the nine GLBs. All three packed Blender sources reopened successfully.
- The modern near GLB rendered in standalone Filament WebGL. The review sheet contains Blender Cycles desktop renders of all three eras. The game scene, Galaxy S25 performance, door control, booking, CCTV and escapes still need runtime integration and testing.

## Self-review

1. The four cells are deliberately austere. Their character comes mostly from low window light, the ironwork, and the built-in bunks. I kept the floor clear and provided actor anchors so the game can stage the rooms.
2. I inspected exterior day, night, rear, above and street-height views plus the desk, cells and booking room. I corrected overlapping paving, simplified the roof pattern, and resized signs that competed with the building. Station-floor geometry and metadata passed the local tests. Actor navigation and the one-way interview glass are runtime behavior.
3. The limestone-framed brick front and the separate, low jail block are the first silhouette cues. Across eras, the radio mast, glass porch and fenced rear yard show the city adapting the same building.

## Usage

- Model and setting — Codex. Exact variant and reasoning setting were not visible.
- Tokens and plan usage — not visible.
- Effort — one turn. Tool-call total was not visible. Approximately 17 minutes from queue check to push.
- Rework — first delivery.
