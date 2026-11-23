# C014 · Birchbend Motor Lodge

Built a roadside motel that keeps its L-shaped court and street-facing check-in office across three eras. The 1935 motor court has timber carports and seven rooms. The 1955 motel adds a fenced kidney pool, ice machine, butterfly-roof office and a neon sign with independent VACANCY and NO controls. The 1985 budget motel adds a second exterior corridor, a west stair, seven upstairs rooms, a plastic backlit sign and an ice and vending alcove.

Ground-level room and office entries share positions across the eras. The near models include the named office counter, clerk and guest anchors, a door and bed anchor for each room, and state-specific sign repaint rectangles. The office and guest rooms use empty shells for game furnishings. All three eras include original art, source and desktop preview views.

## Verification

| Era | Near triangles | Mid triangles | Far triangles | Near materials |
| --- | ---: | ---: | ---: | ---: |
| 1935–1954 | 1,772 | 1,466 | 1,040 | 8 |
| 1955–1984 | 3,298 | 2,366 | 1,884 | 12 |
| 1985 onward | 4,096 | 2,896 | 1,890 | 8 |

- The local validator checked nine GLBs for texture scale and nearest sampling, pane-only masks, bounds, sign UVs and collision boxes. Ray and box probes covered 34 office and room thresholds across the three eras. It also checked the separate vacancy material nodes and the shared ground-floor room transforms.
- Khronos glTF Validator reported zero errors and warnings. All three packed Blender sources reopened. The 1955 near GLB rendered in standalone Filament WebGL.
- The review sheet shows Blender Cycles desktop renders. Check-in, machine use, the pool, sign state switching, stair traversal and mobile performance still require Timerpg integration and testing.

## Self-review

1. Upper-floor access is the least proven part. I built the actual stair and landing, provided approach coordinates and checked every upper doorway. Traversal on the player's controller still needs a playtest.
2. I examined each state at day, night, on approach, from behind and from above, plus both office views. I finished the rear elevations, closed the butterfly roof's spandrel and moved a refuse bin out of the office exit path. Room furnishing and nighttime runtime lighting were reasoned about from the exported hooks.
3. The road sign and long row of doors do the first work from the highway. The 1955 arrow and modern upper corridor distinguish the two later motels before the player steps out.

## Usage

- Model and setting — Codex. Exact variant and reasoning setting not visible.
- Tokens and plan usage — not visible.
- Effort — one turn. Tool-call total not visible. Approximately 13 minutes from queue check to push.
- Rework — first delivery.
