# The Great Meridian, revision 2: from good to unforgettable

Revision 1 worked:
- the three-part silhouette reads at 32 px;
- the far model matches;
- glow is down to 16 m²;
- the floodlight and beacon nodes, the four eras, the climb through the hollow pier, the meridian line, the made-up
  flags and the collision in game coordinates are all there.

Keep all of it.

This round is about the things people remember: the statues, the sphere, the crown, the stone itself, and the ground
the player walks on. Revise on `codex/great-meridian-monument`, with the same origin, doors, nodes and states.

## 1. The four guardians: real colossal statues

Right now they read as the words DEFENSE / LEARNING / INDUSTRY / HARVEST on panels. They must be **figures** that a
player stops to look up at.

- **Where:** each stands on a projecting stone ledge at the top of its pier's outer corner, about 44-52 m up, facing
  outward diagonally. They are silhouetted against the sky from the plaza and visible from the far end of the
  reflecting pool. **Height:** 8 m, so each figure is about 4.7 times a person.
- **Style:** 1920s Art Deco monumental sculpture. Massive, simplified, faceted planes, a strong vertical stance,
  heavy robes or work clothes falling in straight pleats, square shoulders, heads slightly bowed, faces stern and
  simple. Build each one as a stack of carved blocks, not organic curves, which suits low-poly perfectly:
  - plinth;
  - legs and robe (a tapered prism with 3-4 pleat ridges);
  - torso;
  - arms;
  - head (an 8-12 faced block with a squared jaw, brow and nose ridges);
  - the attribute.
- **Each holds one big attribute,** readable from the ground:
  - **Defense:** both hands resting on the pommel of a long sword planted point-down; a round shield against the leg.
  - **Learning:** an open book held against the chest in one arm, the other hand raised holding a small torch (the
    torch flame is a tiny glow, under 0.3 m²).
  - **Industry:** a sledgehammer over one shoulder, a gear wheel (1.5 m) at the feet, a leather apron.
  - **Harvest:** a sheaf of wheat in the crook of the arm, a sickle in the other hand, a basket at the feet.
- **Budget:** about 600-900 triangles per figure. Share the base figure mesh and swap the attributes.
- **Texture:** the same limestone as the piers, with stepped shading on every plane (3 tones: lit, mid, shadow),
  carved fold lines, dark outline pixels where planes meet, and a slightly lighter tone on top surfaces where the
  weather has bleached them. The 1976 state adds dark streaks running down from each figure.
- **Names:** carved in the ledge below each figure in 0.6 m letters: DEFENSE, LEARNING, INDUSTRY, HARVEST. Small. The
  figure does the talking.
- **Collision:** a box per figure on the ledge (not reachable, but solid).
- **Review shots:** each guardian from the plaza at person height (the upward view), and from the 24 m gallery.

## 2. The sphere: visible from everywhere

It vanishes behind the steel trusses from outside.
- **Bigger and brighter:**
  - outer ring diameter 18 m (now 15.2 m);
  - rings 0.6 m wide and 0.3 m deep, so they read as solid bands, not wires;
  - gilded bronze with a strong light-gold top face and a darker gold underside;
  - stepped shading and a one-pixel bright highlight along each ring's upper edge.
- **Fewer trusses:**
  - keep **one** riveted steel arch on each face of the vault, spanning pier to pier at the vault's top (4 arches
    total), and the hanger rods holding the sphere from the deck above;
  - remove the diagonal crossing ribs and any truss members that pass in front of the sphere seen from the four
    main axes and the four corners;
  - the vault opening should be mostly sky with the sphere in the middle.
- **The globe** at the centre: 5 m, verdigris oceans with pale bronze continents (simple made-up continent shapes).
- **The ecliptic band:** 12 pixel zodiac symbols raised on the ring. **Polar axis rod:** 0.4 m thick, with bronze
  end bearings.
- **Lighting:** add four `flood_sphere_*` nodes on the inner pier faces aimed at the sphere, so at night it is
  lit gold from below against the dark vault. It's the night view people will screenshot.
- **Review shot:** the sphere from the far end of the reflecting pool (person height) by day and night. It must be
  clearly visible and read as a sphere.

## 3. The crown: give the top presence

Above the deck it is mostly a long spire. Make the lantern a real Art Deco crown:
- **The lantern:**
  - an octagonal drum about 13 m across and 18 m tall, in three stepped tiers, each narrower than the one below
    (13 m, 10.5 m, 8 m), each tier with a strong cornice;
  - on the lowest tier, 8 tall narrow lancets (amber glass, the glowing part) between flat vertical piers with
    chevron caps;
  - at each corner of the lowest tier, a flying buttress fin sweeping down to the deck's corner posts (8 fins,
    stepped profile), which ties the crown to the tower.
- **Above the lantern,** a short gilded drum with a ring of 16 small vertical ribs (a sunburst crown worked into the
  structure, not stuck on top), then the copper spire, about 25 m, with 4 ribbed edges, verdigris with lighter
  streaks.
- **The beacon:** an open bronze cage (8 thin struts) at about 101 m, the orb inside it glowing, and the lightning
  finial above.
- **Proportions:** the crown and spire should be about a third of the total height. The silhouette reads as base,
  shaft and crown in roughly equal visual weight, not base, shaft and needle.

## 4. The stone: make the surfaces rich

The stone is one uniform beige noise everywhere, and that flattens the whole monument. Use three stones and give them
different jobs:
- **Dark granite** (grey-pink, big polished blocks, 1.2 x 0.6 m courses with fine joints) for the acropolis steps,
  the plinths, the ground storey of the arch block up to 6 m, and the statue ledges.
- **Pale limestone** (warm cream, 0.6 m courses, a subtle 2-tone block variation, a few chipped corners) for the arch
  block above 6 m and the piers.
- **Smooth ashlar bands** (a lighter tone, no joints) for the cornices, the frieze bands and the setback caps.

Detail:
- **Weathering:**
  - a gentle dark gradient in pixel steps at the base of every wall (rain splash);
  - pale tops on every horizontal surface;
  - dark drip lines under the cornices (strong in 1976, faint in 1941, absent in 1926 and after the 2000 cleaning).
- **The arches:**
  - deep coffered soffits (a grid of square recessed panels, each with a dark inner outline);
  - a carved voussoir ring of alternating light and dark stones round each arch face;
  - a projecting keystone with a carved compass star.
- **The setbacks** on the piers each get a band of carved Art Deco chevrons or a zigzag frieze, 1 m tall.
- **The friezes** ("THE GREAT MERIDIAN", "DEDICATED MCMXXVI", "MERIDIAN EAST/WEST"): incised letters (dark inside,
  with a light lower edge), centred and fitted to their bands with margins. Check each in a close-up.

## 5. Ground level: what the player walks through

- **The reflecting pool:**
  - a 0.5 m granite coping you can stand on, with water at its proper level;
  - three low fountain jets per basin (small water-white prisms, the only glow being a thin rim at night);
  - lily-pad-free, clean.
  - At the far (+Z) end, a semicircular granite exedra: a curved bench wall with the dedication text "ERECTED BY THE
    PEOPLE OF THE STATE MCMXXVI" and two bronze lamp standards.
- **The staircases:**
  - each flight of 12-15 steps with landings;
  - massive cheek walls, each ending in a bronze tripod brazier (the eternal flames, four total, at the main flight);
  - bronze handrails down the middle of the wide flights.
- **The bronze doors:**
  - two leaves each 2.2 m wide and 5 m tall, hinged at the arch's sides and **open, folded flat against the reveals
    at 90°**, so their relief faces show to the passer-by;
  - each has 6 relief panels (scenes as pixel art: a train, a plough, a ship, a factory, a school, a bridge).
- **The rotunda floor:**
  - the compass rose about 14 m across, in inlaid bronze and two marble tones, with N/E/S/W letters;
  - the meridian line running through it, crisp and continuous.
- **The dedication wall** (west hall):
  - one carved line at the top, "TO THOSE WHO BUILT, FARMED, TAUGHT AND DEFENDED", in 0.4 m letters;
  - below it, rows of small carved name blocks as texture (illegible pixel words, 30-40 rows);
  - a low bronze rail in front;
  - a carved wreath at each end.
- **The east hall:**
  - a scale model of the monument on a plinth (reuse the far model, scaled down);
  - a wall map of the made-up state, as pixel art, with the meridian line drawn through it.
- **Plaza life:**
  - lamp standards in a regular rhythm along the pool and paths;
  - 4 bronze benches per side;
  - planters at the staircase feet;
  - trash bins (2000 state);
  - a drinking fountain;
  - the `tree_*` nodes laid out in formal rows (the game plants them).
- **The cornerstone** at the north-east corner of the arch block, at eye height: "1926", with a small carved
  compass.

## 6. Night: the money shot

- Keep the floods, but add **flood colours** to model-info: warm white (3,000 K) on the stone. The beacon is a sweeping
  white beam. The lancets are amber. The eternal flames are orange.
- **The sphere floods** (point 2).
- **A night review panel** from the far end of the reflecting pool, with the floods drawn as approximated light
  pools, showing the stone glowing warm, the sphere gold in the dark vault, the lantern amber, the beacon beam and the
  flames reflected in the pool (fake the reflection in the render, not in the model).

## 7. The eras should look more different

- **1926:**
  - fresh copper on the spire, glowing orange-brown (not yet green);
  - bright clean stone;
  - bunting swags on the arch block cornice and red-white-blue fabric on the flagpoles' bases;
  - a temporary dedication stand (a timber platform with railings) at the foot of the main stairs;
  - young trees.
- **1941:**
  - the copper turned a streaky brown-green;
  - the stone toned slightly darker;
  - a bronze war-years plaque beside the main doors;
  - sandbags round the base of the main stairs and taped window crosses on the hall windows (wartime precautions).
- **1976:**
  - the copper deep green with black streaks;
  - heavy dark drip lines;
  - one guardian's attribute damaged (the Industry gear chipped);
  - scaffolding on one pier with tarps;
  - the beacon off;
  - three broken lamps;
  - a chain-link fence round the scaffolded corner;
  - a hand-painted "CLOSED FOR REPAIRS" sign on a sawhorse at the north stairs;
  - weeds in the paving joints (texture).
- **2000+:**
  - clean pale stone (cleaner than 1941);
  - restored bright copper-green with no streaks;
  - modern LED floods as small visible fixtures;
  - the accessibility ramp;
  - the visitor centre pavilion;
  - interpretive sign panels along the pool (small, 1 m, pixel text);
  - a bike rack.

## 8. A middle level of detail

This monument is seen from every distance, so add a third model, `great-meridian-mid.glb` (about 4,000-6,000
triangles), for 500-2,000 m away:
- the full silhouette with the arches, piers, statues as simple blocks, the sphere's rings as simple bands, the
  lantern tiers, the spire and the plaza as one textured slab;
- no interior, no props.

## 9. Technical limits
- **Near model:** up to 30,000 triangles (the statues and sphere may take what was freed from the trusses).
- **Materials:** at most 12 per state.
- **Glow:** under 20 m² outside the lancets.
- **Collision:** in glTF metres, full height. Update it for the new doors (open), the statues, the exedra and the
  sandbags or fence per state.
- **Node list in model-info.json:**
  - `armillary_outer`, `armillary_ecliptic`, `armillary_globe` (with their rotation axes);
  - `beacon_beam`, `lift_car`;
  - every `flood_*` (with direction and colour);
  - `tree_*`;
  - every walkable `floor_*`;
  - the doors.

## 10. The review sheet (make it judgeable)

Large panels, at least 800 px wide for the hero shots:
1. The approach from the far end of the reflecting pool at person height, by day and at night (the money shot).
2. The four corner views at street height (2000 state).
3. Each guardian, close and from the plaza.
4. The sphere from outside (day and night) and from the rotunda looking up.
5. The crown: the lantern and spire close up from the deck.
6. A stone close-up showing the three stones, weathering and the arch coffering.
7. The bronze doors open, with their relief panels.
8. The dedication wall and the east hall.
9. Each era from the same camera (day), plus the 1976 "CLOSED FOR REPAIRS" corner close-up.
10. Near, mid and far models side by side at the same size, with the black silhouettes at 128/64/32 px.

The README lists what changed and anything not done.
