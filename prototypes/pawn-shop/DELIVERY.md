# C010 · Latch & Lyre Pawn Shop

Built one brick side-street shop in three eras on a shared origin. The original state has three brass pawnbroker balls, painted LOANS glass, a jewellery case and a brass clerk cage. The middle state adds neon lettering, window bars, a buzzer and tagged storage cartons. The modern state stays open and adds LED lettering, security glass, a transfer counter, camera nodes and game-console stock. The original alley advertisement fades across the three models.

The clerk and customer anchors mark both sides of the `counter`. The near models include the safe, store and alley doors, separate fixed and removable window collisions, and named display shelves. Decorative stock is grouped for replacement by the inventory system. Each owner sign has its own repaint rectangle.

## Verification

| Era | Near triangles | Mid triangles | Far triangles | Visible near materials |
| --- | ---: | ---: | ---: | ---: |
| 1920–1959 | 3,530 | 1,698 | 762 | 12 |
| 1960–1999 | 2,794 | 1,410 | 678 | 12 |
| 2000 onward | 3,154 | 1,578 | 664 | 12 |

- The local validator checked nine GLBs for texture density, nearest filtering, pane-only emission, the owner-sign UVs and collision boxes. It also checked that the three doors share transforms across eras and that visible geometry and colliders clear each threshold.
- Khronos glTF Validator found zero errors and warnings. All three packed Blender sources reopened successfully.
- The modern near model rendered in standalone Filament WebGL. The review sheet shows Blender Cycles desktop renders. The Timerpg scene, phone performance, inventory, lock, buzzer and camera behavior need integration and runtime testing.

## Self-review

1. Individual stock items simplify quickly at street distance. I added a waist-high island for the period display so the instruments, televisions and consoles can also be read from inside. The stock remains removable for gameplay.
2. I checked all exterior angles and both interior views, plus the exported metrics and structure. I widened the exterior framing, checked sightlines past the counter, and moved the rear camera housing clear of the wall. I did not run the player's interaction path in Timerpg.
3. The original brass balls and LOANS windows are the first cues. Neon and then LED take their place while the brick shell and old wall advertisement remain recognizable.

## Usage

- Model and setting — Codex. Exact variant and reasoning setting not visible.
- Tokens and plan usage — not visible.
- Effort — one turn. Tool-call total not visible. Approximately 12 minutes from queue check to push.
- Rework — first delivery.
