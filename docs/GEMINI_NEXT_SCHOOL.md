# Gemini: next model, the town high school through time (a hard one)

Read docs/CHATGPT_MODEL_GUIDE.md, docs/GEMINI_NOTES_AND_NEXT.md and docs/GEMINI_COURTHOUSE_REVISION.md first. The courthouse
revision was a big step up. Bring its standard (clear massing, restrained lettering, glow under budget, readable
interior) to this.

## What to build: Lincoln Consolidated High School and its campus

A small-town high school that grows over a century: one campus lot about 120 x 90 m, origin at the middle of the
front (+Z) edge on the main street.

**States:** separate .glb files with the same origin, the same main door node and the same lot.

1. **1925-1954: the original building.**
   - A three-storey brick block, about 40 x 16 m, with a central entrance tower, stone trim and tall classroom
     windows in rows.
   - "LINCOLN HIGH SCHOOL" carved over the door, and "1925" in a datestone.
   - The lot: a flagpole, front lawn walks, a dirt baseball diamond with a wooden backstop behind.
2. **1955-1974: post-war growth.** Everything in state 1, plus:
   - a one-storey modern classroom wing of flat-roofed brick and ribbon windows;
   - a gymnasium with a barrel or bowstring roof;
   - a cafeteria link;
   - a football field with wooden bleachers and lights on poles;
   - a bus loop in front.
3. **1975-1999: consolidation.**
   - The building is renamed on a new sign: "LINCOLN CONSOLIDATED" on a low brick monument sign by the road.
   - Two or three portable classrooms (white boxes with ramps) behind.
   - A car park for students.
   - Steel bleachers replace the wooden ones, and a press box.
   - Some original windows are filled with smaller ones (the energy crisis).
4. **2000+: renovated.**
   - A new glass-and-brick secure entrance vestibule at the front, and the portables gone.
   - An artificial-turf field with a running track.
   - A LED-free plain scoreboard, an accessibility ramp, and the original 1925 block kept and cleaned.

**Interior:** a clean shell of the 1925 block's ground floor:
- an entrance hall with a trophy case;
- one long corridor with lockers as wall geometry;
- the classroom doors (`door_room_*` nodes);
- the office;
- the stair to the upper floors (stairs as real geometry; the upper floors can be blocked off for now).

**Also, in state 2 onward:**
- the gym interior: a court floor with lines, folding bleachers as fixed geometry, and a stage at one end;
- the far model `school-far.glb` with the campus silhouette, under 400 triangles per state (or one that fits state 2).

## Rules

- At most 15,000 triangles per state, campus included.
  - Instance repeated windows, lockers and bleacher seats.
  - Curves use 8-12 sides.
- At most 12 materials per state. Glow only on lamp heads, the field lights' heads, the scoreboard face and lit window
  glass, under 15 m² outside window glass. Supply an emission mask.
- **Exterior doorways open**, with no closed panels.
- Collision in glTF metres, full-height:
  - walls, bleachers, backstop, portables, flagpole and light poles;
  - the field fences, with gaps at their gates.
- Lettering cut into stone or on the one monument sign; nothing else.
- No real school names, mascots or logos. The team name can be painted small on the scoreboard: "LINCOLN".

## Deliver

On `codex/lincoln-high-school` under `prototypes/lincoln-high-school/`:
- the four state .glb files and the far model;
- model-info.json (states, years, doors, rooms, collision boxes, triangles per part);
- the source scripts and your validation output;
- one review sheet:
  - per state: day, night, opposite side, above;
  - close-ups: the main entrance, the corridor, the gym, the football field from the bleachers, the portables;
- a short README.

No zips, pycache or duplicate images.
