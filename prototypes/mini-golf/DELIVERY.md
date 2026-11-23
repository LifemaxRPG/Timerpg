# C007 · Thimble Trails Mini Golf

Built a 40 × 50 m roadside course with 18 numbered greens and real cup openings, a walk-in ticket hut and a clear entrance pad for a statue. Its signature holes include a windmill with a separate rotor, a lighthouse, a volcano, a pond footbridge, a roofed covered bridge and a castle tunnel. The 1990 state fades the paint and turf. The 2006 state adds a chain-link perimeter, boarded sash, worn grass and dark windows.

Nine GLBs, three packed Blender files, reproducible artwork and build scripts, sign rectangles, collision data and a single review sheet are included. All states share the same survey points.

## Verification

- All nine GLBs passed checks for 18 physical cup rings, clear statue space, shared door and rotor transforms, box colliders, grade, distance variants, pane and sign emission masks, nearest sampling, and physical 2×2 art-pixel scale. The biggest export has 4,160 visible triangles and 12 materials.
- Khronos glTF Validator reported zero errors and warnings. All three Blender sources opened with packed textures.
- Inspected the day, night, rear, aerial, entrance, course and ticket-hut Blender Cycles views. The previews are desktop renders.

Animation, ball behavior, statue placement and phone integration remain game work.

## Self-review

1. The narrow parcel keeps the greens on shared rows, which limits the variety visible from overhead. I varied the taper, direction and curb shapes between holes and used different landmark silhouettes along the route.
2. I inspected the exterior and interior render angles and ran geometry and file checks. The first images caught overlaps at path joins, which I removed. Playability with the game's ball, movement and gates is still untested.
3. The original course reads first through the striped lighthouse and the windmill. The closed state introduces a perimeter fence, dark windows and a worn fascia while the course outlines still show through.

## Usage

- Model and setting: Codex GPT-6 family. Exact variant and reasoning setting not visible.
- Tokens and plan usage: not visible.
- Effort: one turn, about ten minutes and about 27 direct tool calls through delivery.
- Rework: initial delivery, zero review rounds.
