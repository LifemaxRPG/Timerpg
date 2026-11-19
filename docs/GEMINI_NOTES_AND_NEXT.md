# Notes for Gemini: what to improve, and the next model

Read docs/CHATGPT_MODEL_GUIDE.md in the repo first; it is the contract for every model. These notes come from bringing
your four models into the game (Mirage, Pulse, The Foundry club, The Foundry factory).

## What worked

- The models are built to our pixel grid (0.0388 m per texel) and our swatches. They load cleanly, and the scripts
  rebuild them without Blender.
- The Foundry factory and the club share one shell, lot and door layout, so the game can show one building through
  time. Please keep doing this.
- The backs are finished: loading docks, HVAC, bins.

## What to improve

1. **Too many signs.** Every model is covered in lit sign boxes: the tower board, gantry headers, yard plates, "VIP",
   "MAIN HALL", "PLANT NO 4". Real small-town buildings have one main sign and perhaps one or two small ones. Ask "would
   a real owner pay for this sign?" A 1930s factory has a painted name on the brick, not neon.
2. **Names and dates must fit the building's time.** The factory still said "The Foundry", the club's later name. A
   building before conversion has its own ordinary name, such as "<surname> Iron Works". The README said it was "built
   1938" but working from 1924. Check that names, dates and the story agree; the game chooses the exact years.
3. **Lettering must fit its plate.** "DANCE CLUB", "VIP ENTRY" and "MAIN HALL" ran past their frames. Measure the text in
   art pixels against the plate before you place it.
4. **Glow belongs only on lamps, signs and lit glass.** The clubs lit up about 70 m² of dance floor, LED walls and
   truss lamps inside the shell, which costs GPU time and is invisible from the street. Large surfaces never glow. A
   glow texture holds only the glowing pixels; everything else is black.
5. **The three clubs look alike.** They have the same lot layout, perimeter fences, car park and dark-plus-neon look.
   Vary the massing and materials, not only the sign colours.
6. **Collision to full height.** Boxes stopped at 4 m on 11 m buildings. Box each thing a walker must go round from
   the ground to its top, and list every box in model-info.json.
7. **Shapes should say what they are.** The "smokestack" was the club's square tower with a sign on it. A foundry stack
   is a tall brick chimney that narrows towards the top.
8. **Keep packages lean.** Don't commit `__pycache__`, duplicate .jpg and .png review sheets, or zips of the same
   files.

## The next model: Reel Time Video (a strip-centre storefront through four decades)

Make **one building in four states**, as separate .glb files sharing one shell, lot, origin and door nodes, the same
way you did the factory and the club:

- **1984: "Reel Time Video".** A family-run video rental store, one bay of a small single-storey strip centre.
  - Hand-lettered window posters of made-up films (no real titles), a "NEW RELEASES" banner and a VHS return slot
    by the door.
  - One sign over the door: a plain box sign, lit at night.
- **1996: the same store, bigger and more successful.** It has taken the next bay.
  - A brighter backlit sign, still the same made-up name, and "2 NIGHT RENTALS" in the window.
  - A drop box by the curb.
  - No chain branding or real trade dress.
- **2011: "STORE CLOSING / EVERYTHING MUST GO".** Paper signs in the windows and half-empty shelves seen through
  the glass.
  - The sign is unlit and faded.
- **2020: reused.** A phone repair shop or a vape shop with a cheap vinyl banner over the old sign frame.
  - The old sign's bolt holes and its ghost outline still show on the fascia.

The strip itself:
- About 30–36 m wide in total with three or four bays. The neighbouring bays are plain and quiet: a laundromat-sized
  bay, a nail salon, an empty "FOR LEASE" bay. Each gets at most one simple sign, and they change only a little
  between the states.
- Flat roof with a parapet and a few rooftop units; a covered walk along the front.
- Its own parking lot of 10–14 spaces in front.
- A finished back: a service lane, metal back doors, bins and a grease trap.
- Materials from our swatches: tan brick or split-face block, a stucco fascia, a tar-and-gravel roof, asphalt.
- Interior: the clean shell only (our game furnishes it), except the shelves that are part of the store state.

Budget and format:
- Under 5,000 triangles per state; at most 12 materials.
- Everything in the guide: metres, Y up, entrance +Z, origin at the middle of the front edge, ground at y 0 or above,
  door_* nodes, full-height collision listed in model-info.json, a `lot` group.

Deliver on branch `codex/reel-time-video` under `prototypes/reel-time-video/`:
- the four .glb files and model-info.json, listing the states and the years each fits;
- the source scripts;
- one review sheet showing day, night, back and above for each state;
- a short README.

Leave out pycache, zips and duplicate images.
