# Next model for Gemini: the county courthouse square (a hard one)

Read docs/CHATGPT_MODEL_GUIDE.md and docs/GEMINI_NOTES_AND_NEXT.md first. This brief is meant to stretch you: curved
and repeated geometry, a real multi-storey interior, a whole town square, and four states through 130 years, all inside
a phone's budget.

## What to build: the Harlan County Courthouse and its square

A county seat's courthouse, the heart of a small American town: a three-storey Romanesque Revival building of 1894 standing
in the middle of a full town block (the square), with streets on all four sides. It has **no back**: it faces all four
streets, with an entrance on each side (the main one, under the clock tower, faces +Z).

### The building (1894)

- **Plan:** a cross-shaped plan about 30 x 30 m: a central block with four projecting entrance pavilions, one per side.
- **Walls:** a rough rusticated stone base storey, red pressed brick above, with stone bands at each floor line.
- **Windows:** round-arched windows (real arches in the geometry, not painted on), and paired arched windows on the
  third floor.
- **Roof:** steep hipped roofs with dormers on each wing.
- **The tower:** a central clock tower rising to about 38 m:
  - a square shaft;
  - a four-faced clock at about 26 m (each face a separate `glow_clock_*` material with a pixel dial; the hands
    are separate nodes `clock_hour` and `clock_minute`, pivoted at the dial's centre, so the game can turn them);
  - an open belfry with arched openings and a bell (node `bell`);
  - a copper-green pyramidal spire topped by a finial.
- **Main entrance:** a broad flight of stone steps (real steps, not a ramp texture) up to an arched porch on two
  columns. Side entrances have shorter steps.
- **Accessibility ramp:** none in 1894 (see the states).

### The square around it (the lot: the whole block, about 80 x 80 m, origin at the middle of the +Z edge)

- Diagonal and cross paths from each corner and each side to the entrances.
- Lawn and big shade trees, as positions only: list `tree_*` nodes and the game plants its own trees.
- **A Civil War soldier memorial:** a column on a stepped base with a pixel-art figure on top. **A bandstand:** an
  octagonal pavilion with a conical roof and railings.
- A flagpole, iron benches, lamp posts (each lamp a `glow_` material), and a granite low wall with gaps at the paths.
- The streets themselves are drawn by the game. Your lot stops at the curb, but include the sidewalk.

### The interior (a real shell, the hardest part)

The game will let the player walk in, so build a clean interior shell on all three floors:
- **Ground floor:**
  - a central hall under the tower, rising through both upper floors to a skylight;
  - a grand staircase (real stepped geometry, with a landing) up to a gallery on floors 2 and 3;
  - doors to offices along corridors in each wing (`door_room_*` nodes at each doorway, facing into the room).
- **Floor 2:** the courtroom, double-height through floors 2 and 3 in the +Z wing, with a raised judge's bench and the
  bar rail as geometry. The rest of the furniture is placed by the game.
- **Floor 3:** a gallery round the central hall, and small offices.
- Floors at the right levels (about 4.5 m per storey).
- Interior walls carry plaster, wainscot and floor swatches.
- No furniture beyond the bench and the rail.
- Name each room's floor mesh `floor_<room>` so the game knows the rooms.

### Four states, one shared base (separate .glb files, same origin, same doors, same lot)

1. **1894-1950: as built.** Gas-era lamp posts. The bandstand is new. Hitching rails on the +Z street side.
2. **1951-1975: "modernised".** The county saves money:
   - the original arched windows on the ground floor are partly bricked in with smaller aluminium windows;
   - a flat-roofed concrete annex (2 storeys, about 15 x 20 m) is attached to the -Z wing;
   - the bandstand is gone (a concrete pad remains), and electric cobra-head lamps replace the gas lamps.
3. **1976-1999: neglect and protest.**
   - The clock is stopped: hands stuck at 4:17, clock glow off.
   - The annex is stained, and a few windows are boarded.
   - A "SAVE OUR COURTHOUSE" banner hangs on the tower, and the copper spire is darker.
4. **2000 onward: restored.**
   - The windows are reopened and the annex removed (patched brick shows where it stood).
   - A new bandstand is built to the old design (slightly cleaner).
   - The clock works again, with warm floodlights on the tower at night (`glow_` only on the lamp heads and lit
     glass, never on whole walls).
   - An accessibility ramp at a side entrance, and a bronze plaque by the door: "Restored 2004".

### Distant view (new)

Also deliver `courthouse-far.glb`: under 400 triangles, the same silhouette (tower, spire, roofs, wings) in the same
pixel textures, for when the player is far away. The tower is the town's landmark and will be seen from everywhere.

## Budgets and rules (as the guide, with these limits)

- Near model: at most 15,000 triangles per state, all included: building, interior shell and square.
  - Use repetition smartly (one arched window built once and instanced as nodes).
  - Keep curves to 8-12 sides.
- At most 12 materials per state, plus up to 4 `glow_clock_*` for the dials. Pack textures into atlases.
- Collision: full-height boxes for the walls, steps as a few stepped boxes, and the memorial, bandstand, benches and
  flagpole. List them all in model-info.json.
- Interior collision too: interior walls, the stair as stepped boxes, the gallery rail.
- Everything on the 2 x 2 texel art-pixel grid at 0.0388 m per texel, with outlines, highlights and stepped shading.
- No flat single-colour walls. Stone, brick, copper and slate come from our swatches where the atlas has them.
- No real place names. "Harlan County" is fine (made up for us). No brands.
- Keep the lettering small and on plates: "HARLAN COUNTY COURTHOUSE" carved in the stone over the main arch,
  "1894" in a datestone, and the banner and plaque in their states. Nothing else.

## Deliver

On branch `codex/county-courthouse` under `prototypes/county-courthouse/`:
- the four state .glb files and `courthouse-far.glb`;
- `model-info.json`: the states and their years, footprints, heights, the node list (doors, rooms, clock hands,
  bell, trees), collision boxes and triangles per part;
- the source scripts (rebuildable without Blender, as you did before) and your validation script with its output;
- **one review sheet that we can actually judge.** For each state: the day three-quarter, night, the opposite side
  and above. Plus close-ups at person height:
  - the main steps and porch;
  - the clock face;
  - the stair hall looking up to the skylight;
  - the courtroom;
  - the memorial and bandstand.

  Make the views big enough to read the lettering.
- a short README listing anything unfinished.

No zips, pycache or duplicate images.
