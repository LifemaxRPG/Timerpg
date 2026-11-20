# ChatGPT: next four landmarks (batch 3)

Follow docs/CHATGPT_MODEL_GUIDE.md exactly, as you did for the funeral home and radio station. Those two were excellent.
Keep doing what worked there:
- the pane-only emission mask;
- one restrained sign;
- real steps;
- clean packages.

**Three fixes for this batch:**
1. **Leave exterior doorways open.** Don't fill them with closed door panels and gate boxes. The game puts "Go inside"
   there. Interior doors may keep panels.
2. **Give collision boxes in glTF metres** (Y up, +Z front), the same frame as the doors, not Blender's.
3. **Keep the name lettering in one sign region** and give that region in model-info.json, as you did for the funeral
   home. The game repaints the name per town.

Make each model on its own branch `codex/<name>` under `prototypes/<name>/`.

## 1. Public library (Carnegie style)
- **The building:** a one-storey-plus-basement 1912 town library, about 18 x 14 m.
  - Brick with stone trim, a raised entrance up a flight of steps, and a classical portico with two columns and a
    pediment.
  - Tall round-arched reading-room windows on both sides, and a hipped roof.
  - "PUBLIC LIBRARY" carved over the door.
- **Inside:** a central desk area, two reading rooms either side, and a basement children's room reached by stairs.
- **Later state (separate .glb, same origin and doors):** a 1970s flat-roofed brick-and-glass addition at the back or side,
  joined by a short link.
- **Years:** built 1905-1920; the addition from about 1965-1985.

## 2. Corner drugstore with soda fountain
- **The building:** a two-storey brick corner building, about 12 x 20 m, of the 1920s-30s.
  - The entrance is cut across the corner.
  - Big display windows, and a projecting vertical "DRUGS" sign plus one name sign.
  - Offices or a flat upstairs, with an outside stair at the back.
- **Inside:** the soda fountain counter with stools along one wall (fixed geometry), the prescription counter at the
  back, and open floor for the game's shelves.
- **States:**
  - 1935-1975: the family drugstore with the fountain;
  - 1976-2005: the same store, with the fountain removed and a dropped ceiling;
  - 2006+: closed, or a café reusing the old fountain counter.

  Use separate .glb files with the same shell.

## 3. Small-town bank
- **The building:** a 1920s one-storey classical bank, about 14 x 18 m.
  - Stone front with two tall columns, a big arched window or tall windows, and bronze doors.
  - The name carved in the frieze ("<NAME> STATE BANK", per town).
- **Inside:** a banking hall with teller counter (fixed), a vault door at the back (fixed geometry), and side offices.
- **Later state:** a 1960s-70s drive-through canopy with two lanes on the side lot, and a night deposit box. A
  separate .glb, or `lot` nodes the game can show by year.
- **Years:** built 1900-1930; drive-through from 1965.

## 4. Brick church with steeple and parish hall
- **The building:** a red-brick Gothic Revival church, about 14 x 30 m.
  - A corner bell tower with a tall spire (about 30 m).
  - Pointed-arch windows (glass glows faintly at night), and a gabled entry with double doors up a few steps.
- **Parish hall:** a plain one-storey hall attached at the back, where church suppers happen. It needs its own door.
- **Grounds:** a sign board ("<NAME> CHURCH", plain, no denomination symbol needed), a few graves or a memorial garden
  optional as `lot` nodes, and a small car park.
- **Inside:** a nave with an aisle and a raised chancel (fixed platform). The pews are placed by the game.
- **Variant (separate .glb):** a small white wooden country church, about 9 x 18 m, with a front belfry, for farm towns.

## For each model
- Under the guide's budgets (a landmark up to about 12,000 triangles).
- model-info.json listing the states and years.
- One review sheet: day, night, back, above, person height at the entrance, and one interior view.
- A short README.
