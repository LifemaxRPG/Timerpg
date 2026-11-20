# Monument brief: The Carillon of the Hundred Years (designed by Claude)

Read docs/CHATGPT_MODEL_GUIDE.md first. It is the contract for every model: the pixel grid at 0.0388 m per texel,
our swatches, glTF metres, Y up, entrance +Z, role-named materials, doors, collision. This brief describes one
landmark. It should be the second great monument of the world, as different as possible from the Great Meridian (a
heavy stone tower with a hanging sphere).

## The idea

Timerpg is a game about time. This monument is a monument **to time itself**, and it changes as the game's decades
pass.

In 1938, in the middle of the Depression, the people of a river city built a **carillon tower**, a bell tower with
dozens of tuned bells played from a keyboard. It stands on a hill above the river, with a **cascade of water stairs**
running down to the riverbank. Under the tower they sealed a **time capsule**, to be opened in 1988. Along the cascade
runs the **Wall of Decades**: a long terraced wall with one carved relief panel per decade, and **a new panel is added
every ten years**, so the wall grows as time passes. A player who time-travels will see the wall stop at different
points, and can be there on the day the capsule is opened.

The mood is the opposite of the Meridian's: slender, vertical, light and musical. Streamline Moderne and Art Deco,
with bells you can see, water you can hear, and a story that keeps moving.

## The site (the lot): about 90 m wide (x) by 140 m deep (z), on a slope

- **Origin and slope:** the origin is at the middle of the +Z edge, the riverbank promenade at y 0. The site climbs
  **18 m** from the river (+Z, low) to the hilltop (-Z, high), where the tower stands about 110 m back from the
  origin. Build the ground terraces as part of the model. The game will place it where the town's ground meets the
  river.
- **From bottom to top:**
  1. **Riverbank promenade** (y 0): a 90 m wide granite walk with a balustrade along the water, lamp standards, and a
     round **lower basin** 16 m across where the cascade ends, with a central bronze sculpture: three leaping fish in
     a spiral, about 4 m.
  2. **The Water Stair:** a grand central stair about 12 m wide climbing the hill in **five flights with landings**.
     Down its centre runs a **cascade**, a channel 4 m wide of stepped water weirs, one at each step-group. Water
     surfaces use a `water_*` material, and each weir's falling sheet is a thin vertical water-white strip.
     Pedestrian stairs run either side of the cascade.
  3. **The Wall of Decades** lines the east side of the Water Stair: a terraced retaining wall stepping up with the
     stairs, built of rough granite with a smooth limestone band at eye height holding the relief panels (see below).
  4. **The west side:** terraced lawns and planting beds with `tree_*` nodes (formal rows of lindens), stone benches
     facing the river, and a small open-air **music lawn** with a curved stone seat wall facing the tower: people sit
     there on summer evenings to hear the bells.
  5. **The upper terrace** (y 18): a broad paved forecourt around the tower's base, a granite balustrade on the river
     side with a view over the city, four tall bronze flagpoles with made-up city flags, and the **time capsule vault
     entrance** (below).

## The tower: 72 m, slender, Streamline Moderne

- **Plan:** square, 11 x 11 m at the base, rising in **three long setbacks** (at 22 m, 44 m and 58 m), each narrowing
  the shaft by about 1.2 m per side. Total height 72 m to the top of the finial.
- **The shaft:** pale cream limestone, with **deep vertical channels** (fluting) running the full height of each face,
  0.6 m wide and 0.4 m deep, five per face. They catch light and shadow and make the tower feel even taller. At each
  setback, a band of carved Art Deco chevrons.
- **The entrance** at the base, on the +Z (river) face: a tall narrow portal (3 m wide, 9 m tall), stepped-recessed in
  four orders, with **aluminium-and-bronze doors** (open) and a carved stone panel above: "THE CARILLON OF THE
  HUNDRED YEARS / MCMXXXVIII".
- **The clock:** at about 40 m, a square clock face 5 m across on each of the four faces. A **24-hour dial**, unusual
  and fitting for a monument to time, with Roman numerals I to XXIV, gilded, and an outer ring of **ten small year
  markers**. Separate nodes `clock_hour_<face>` and `clock_minute_<face>`, pivoted at the dial centres, so the game can
  set the time.
- **The bell chamber** (about 50-62 m): **the signature.** The top section is **open on all four sides**, with tall
  narrow openings between slim piers. Inside, visible from below and from far away, hang **the bells**:
  - 23 bells in a stepped frame, from a big 2.2 m bourdon bell at the bottom to small 0.4 m bells at the top;
  - bronze with verdigris streaks, each bell a simple 10-12 sided bell shape;
  - a steel bell frame painted dark green.

  The bells read as a glittering pyramid of bronze inside an open stone lantern. Separate nodes for the 4 biggest
  bells (`bell_0` to `bell_3`) so the game can swing them.
- **The crown** (62-72 m): a stepped Art Deco cap of four tiers, each with a band of fluted aluminium (bright grey
  metal), then a slim **aluminium needle** with a ring-shaped beacon of light near its top. The ring glows warm white
  at night, and the game can pulse it when the bells play.
- **The playing cabin** (about 47 m, below the bells): a small glassed room where the carillonneur sits at the
  baton keyboard. It has a few windows lit at night. Its interior holds a wooden keyboard console (fixed geometry)
  with pedals and a bench.

## Inside the tower

- **Ground floor:** a small square entrance hall with a terrazzo floor (a compass of the hours inlaid, a 24-pointed
  star) and a winding staircase round an open central well up to the playing cabin. Use real stepped geometry, with
  landings every 6 m and slot windows looking out over the river.
- **A memorial plaque** in the hall: "BUILT BY THE HANDS OF THIS CITY IN A HARD TIME", with a bronze relief of
  workers hauling a bell on ropes.
- **The playing cabin** (described above), with a short ladder up into the bell chamber. The bell chamber has a steel
  grating floor round the frame, so a player can stand among the bells and look out over the city through the
  openings.

## The time capsule vault

- **The entrance:** at the foot of the tower on the -Z (hill) side, a low granite portal leads down a short flight of
  steps to a **small vaulted crypt** (about 6 x 8 m, 4 m high).
- **The crypt:** in the centre stands the **capsule**, a bronze cylinder 1.2 m tall on a stone pedestal behind a low
  bronze rail. On the wall, a bronze plaque: "SEALED MAY 21 1938 / TO BE OPENED MAY 21 1988 / WE WHO BUILT THIS
  GREET YOU WHO COME AFTER". The ceiling is a shallow vault with a single lamp.
- **The vault door:** a heavy round bronze door, like a bank vault's, set in the portal. It is **closed and sealed
  (with wax seals) before 1988, and open after.** Separate node `capsule_door`.

## The Wall of Decades (it grows)

- **The panels:** along the east wall of the Water Stair, a row of **ten relief panel frames**, each 3 m wide and 2 m
  tall, one per decade: 1930s, 1940s, ... up to 2020s, plus spare empty frames for the future.
- **What each panel shows:** a decade's life in carved Art Deco relief, made as pixel art on the stone, in low relief
  (a texture plus a slight inset):
  - **1930s:** workers raising the tower, bells on a wagon;
  - **1940s:** a soldier's homecoming at a train;
  - **1950s:** a family and a new car outside a little house;
  - **1960s:** a rocket rising and children watching;
  - **1970s:** a festival on the riverbank;
  - **1980s:** the capsule being opened, a crowd;
  - **1990s:** a computer in a classroom;
  - **2000s:** a candlelight vigil (respectful, simple);
  - **2010s:** a flood and neighbours sandbagging;
  - **2020s:** two people waving through a window.
- **Panels appear only in the states that have reached their decade** (see the states). An uncarved frame is plain
  smooth stone with a small carved date ("1990") waiting.

## Four states (separate .glb files: same origin, same doors, same site)

1. **1938-1945, dedication:**
   - Brand new pale stone and bright bronze bells.
   - The Wall has only the 1930s panel; the rest are blank frames.
   - The capsule door is sealed.
   - Bunting on the upper balustrade, and a timber dedication stand on the riverbank promenade.
   - Young trees.
2. **1946-1987, maturing:**
   - The bells darken to brown bronze with green streaks.
   - The Wall has panels up to the 1970s (make this state show 1940s-1970s carved; the game will pick the state by
     year).
   - A small bronze plaque at the tower foot: "THE BELLS RANG AT WAR'S END".
   - The capsule is still sealed.
3. **1988-1999, the opening:**
   - The capsule vault door is open, and the capsule is displayed in a glass case in the crypt with a few objects
     beside it (a newspaper, a pair of children's shoes, a letter: simple pixel props).
   - The Wall has panels up to the 1980s (the opening panel).
   - A small "CAPSULE II, SEALED 1988, OPEN 2038" plaque by the door.
   - The cascade water is slightly greener and the stone shows weathering streaks.
4. **2000+, restored:**
   - Clean stone, and the bells re-bronzed with a lighter patina.
   - LED floods as small fixtures.
   - The Wall has panels up to the 2020s.
   - An accessibility path zig-zags up the west lawns, and there are interpretive signs along the promenade.

## Light at night

- **Glow (emissive), only these:**
  - the needle's ring beacon;
  - the playing cabin's windows;
  - the clock dials (soft);
  - the lamp heads;
  - the lower basin's fish sculpture rim (a thin strip).

  Under 15 m² in total. Supply an emission mask: black everywhere else.
- **Floodlight nodes** (`flood_<n>`, with direction and colour in model-info.json):
  - warm white up the tower's fluted faces;
  - a **cool blue-white light inside the bell chamber** shining up through the bells, so at night the bells glitter
    against the dark;
  - soft light along each cascade weir, so the falling water glows.
- **The night money shot:** from the riverbank promenade looking up the Water Stair, the cascade glowing in steps, the
  tower floodlit, the bells shining in the open lantern, the beacon ring on the needle, and the clock dials lit.

## Budgets and rules

- **Triangles:** near model up to 30,000 per state (a once-per-world monument). Instance everything repeated (bells,
  lamps, balustrade posts, steps, trees as nodes).
- **Materials:** at most 12 per state: atlas the bells, bronze, aluminium and signs together.
- **The look:** every surface on the 2 x 2 texel art-pixel grid with our pixel-art finish: outlines, top highlights,
  stepped shading and wear. Take the stone, granite, paving and water from our swatches.
- **Collision:** in glTF metres (Y up, +Z front), the same frame as the doors. Full height for the tower, the
  balustrades and railings, the bell frame, the basin rim, the walls and the stairs as stepped boxes. Every box
  is listed in model-info.json.
- **Walkable floor nodes:**
  - the promenade;
  - each stair flight and landing;
  - the terraces and the music lawn;
  - the upper terrace;
  - the tower hall, the stair landings, the playing cabin and the bell chamber grating;
  - the crypt.
- **Doors** (as `door_*` nodes), with their doorways left **open**: the tower entrance, the crypt portal, the
  playing-cabin door and the bell-chamber hatch.
- **Lettering:** cut into the stone or on bronze plaques. No other signs.
- **Names:** none real (no real cities, people, flags or brands). Leave the city name out: the game names it.
- **Distance models:**
  - `carillon-mid.glb` (4,000-6,000 triangles): the full silhouette with the bells as a simple stepped cone, no
    interior;
  - `carillon-far.glb` (under 400 triangles): the shaft, setbacks, open lantern, crown, needle and beacon.

  Check the black silhouette at 128, 64 and 32 px: it must read as the same tower at every size.

## Deliver

On a branch `codex/carillon-hundred-years` under `prototypes/carillon-hundred-years/`:
- the four state .glb files, the mid and far models, model-info.json (states and years, every node, floods, collision
  boxes, triangles per part), the source scripts (rebuildable without Blender if you can) and your validation output;
- **one review sheet with large hero panels:**
  - the day approach from the riverbank looking up the Water Stair at person height;
  - the night money shot from the same spot;
  - the four corner views;
  - the bells close up from the bell chamber grating;
  - the clock face;
  - the playing cabin;
  - the time capsule crypt sealed (1938) and open (1988);
  - the Wall of Decades close up in each state;
  - the cascade weirs;
  - the near, mid and far models side by side with their black silhouettes;
- a short README listing anything unfinished.

No zips, caches or duplicate images. Say clearly that the renders are not game captures.
