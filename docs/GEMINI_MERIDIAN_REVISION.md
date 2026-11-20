# The Great Meridian: revision notes for Gemini

This is the most ambitious model anyone has made for Timerpg, and the right instincts are all there:
- the 15.2 m armillary sphere hanging in an open steel vault;
- the compass-rose rotunda;
- a walkable observation deck at 58 m;
- the reflecting-pool approach;
- carved dedications;
- a separate far model;
- a clean, rebuildable package.

Now make it **iconic**: the thing a player sees on the horizon from a train window and recognises instantly, the way
people know the Washington Monument or the Gateway Arch from a mile away. That comes from **one unforgettable
silhouette and a few huge moves**, not from more parts. Revise on the same branch (`codex/great-meridian-monument`).

**Keep:**
- the name and the 1926 dedication;
- the plaza size (120 x 120 m) and the reflecting pool on +Z;
- the armillary sphere node;
- the rotunda, memorial halls, observation deck, telescopes and the eternal flames;
- the origin and door nodes.

## 1. The silhouette: one tower, three clear parts

Right now pylons, turrets, trusses, a lantern, a starburst crown and a needle spire are stacked like a wedding cake. A
1926 American monument is bold and disciplined: Beaux-Arts proportion with Art Deco verticality. Think of a
stone shaft that rises in clean setbacks, like the great memorial towers and state capitol towers of the 1920s, then
one dramatic crown.

Massing, from the ground up:

### The base: the acropolis and the arch block (0-24 m)
- Keep the three-tier granite acropolis and the four grand staircases.
- On it, **one square block about 34 x 34 m** pierced by the four 17 m triumphal arches, so it reads as a single solid
  mass with arches cut through it. Don't build four separate pylons with gaps between them.
- Deep reveals: the arches recessed at least 1.5 m, with coffered soffits.
- Its top is a strong cornice line at about 24 m, with the carved friezes ("THE GREAT MERIDIAN / DEDICATED MCMXXVI",
  "MERIDIAN EAST", "MERIDIAN WEST") in that band.

### The shaft: the open vault (24-58 m)
- From the arch block rise **four tall corner piers** (about 6 x 6 m each), with stepped Art Deco setbacks at 34 m and
  46 m and deep vertical fluting or ribs running their whole height. These are the monument's "legs".
- Between the piers, **the vault is open on all four sides.** The riveted-steel truss arches span between the piers and
  the **armillary sphere hangs in the middle**, visible from every direction against the sky. This is the signature:
  a stone tower with a hole through its heart and a golden sphere floating inside it.
- Remove the turrets, pinnacles and extra windows from the piers. Each pier face carries just one tall narrow window
  slot (amber glass) and one sculpted panel near the top, described below.

### The crown (58-105 m)
- The observation deck sits on top of the four piers, bridging them: a square deck with a bronze railing and the
  oculus looking down on the sphere.
- Above it, **one** element only: a stepped octagonal lantern (about 12 m wide, rising 16 m), then a slender
  verdigris-copper spire to about 101 m, then the beacon cage and finial at 105 m.
- Drop the starburst crown, the 16-column circular colonnade and the corner obelisks. They blur the outline. The
  lantern's eight tall amber lancets are enough.

**The test:** draw the outline as a solid black shape. It must be recognisable at 64 px tall: a square base with an
arch, four legs with a sphere between them, a deck, a lantern and a needle. If a part doesn't change that black
shape, it is texture detail and not geometry.

## 2. Sculpture that tells a story (the "badass" part)

Give the monument meaning, in our pixel style, kept low-poly:
- **Four guardian figures,** one at the top of each corner pier just below the deck: colossal stylised Art Deco
  figures about 7 m tall, carved in the stone, standing with hands resting on a sword or a book or a wheel or a sheaf
  of wheat. They stand for **Defense, Learning, Industry, Harvest**, and their names are carved under them. Build them
  as simple faceted blocks (Art Deco sculpture is blocky already); the pixel texture does the rest.
- **The meridian line:** a bronze strip inlaid in the plaza paving, running dead straight from the reflecting pool,
  through the main arch, across the compass rose, out through the back arch and on to the plaza's edge. That's why
  it's called the Meridian. At solar noon the sphere's shadow should fall on it; the game can place the sun.
- **Dedication wall:** inside the west memorial hall, a long carved stone wall: "TO THOSE WHO BUILT, FARMED, TAUGHT
  AND DEFENDED", with rows of small carved name blocks as a texture, using no real names.
- **Bronze doors:** the main arch has two huge bronze doors (4 m tall), shown **open**, folded back against the
  reveals, with relief panels.

## 3. The armillary sphere: make it the star
- **Rings:** three to five concentric rings in gilded bronze (the equator, the ecliptic tilted 23.4°, two meridian
  rings), with a verdigris globe of the earth at the centre.
- **Axis:** the rings sit on an inclined polar axis rod running from a bronze bearing hung from the truss above.
- **Engraving:** a band of zodiac or star symbols in pixel art along the ecliptic ring.
- **Node structure:** separate nodes the game can turn independently: `armillary_outer` (fixed), `armillary_ecliptic`
  (turns slowly once a game day), `armillary_globe` (turns once a day on the polar axis).
- At night it is lit **from below** by the game's lights. Don't make it glow; at most a thin glowing line on the
  ecliptic's star band.

## 4. Light and night (a budget that still looks spectacular)
- **Emissive surfaces, and only these:**
  - the beacon orb;
  - the eight lantern lancets;
  - the narrow pier window slots;
  - the globe lamp heads;
  - the eternal flames;
  - the reflecting pool's fountain jets (a thin strip).

  Total under 20 m² (now 54 m²).
- Floodlighting the stone is the game's job. Add empty nodes `flood_<n>` at the base of each pier and along the plaza,
  each with a direction in model-info.json, and the game puts spotlights there. A floodlit stone tower against a dark
  sky is far more striking than glowing walls.
- **The beacon:** a rotating beam effect. Give a node `beacon_beam` with the beam's direction, and the game sweeps it.
  A real 1920s aviation beacon turned once every 10 seconds.

## 5. The climb: an experience, not just floors
- **Ground:** you walk up the staircases, through the bronze doors and into the rotunda. Look up through the open vault
  at the sphere hanging 35 m above, framed by the truss arches. That's the money shot; make sure nothing blocks it.
- **Inside the north-west pier:** an elevator shaft (an open cage, with the car as a separate node `lift_car` the game
  can move) **and** a stair winding round it, with small slot windows looking out over the town as you climb. Mark
  landings at 24 m (the cornice gallery, a narrow walkway round the top of the arch block outside, with a railing) and
  58 m (the deck).
- **The deck:** the telescopes stay, at four points facing the four directions. A brass direction plate in each corner
  of the railing, engraved "TO THE CAPITOL", "TO THE RIVER", "TO THE MOUNTAINS", "TO THE SEA" (the game will rename
  them).
- **Inside the lantern above the deck:** a small chamber reached by a ladder, holding the beacon's machinery: gears and
  a big lens around the orb. It can be a closed room seen through the lancets.

## 6. Through time (separate .glb states, same origin, doors and plaza)
1. **1926-1940, dedication:**
   - The stone is clean and pale.
   - Bunting is draped from the cornice (red, white and blue fabric swags as simple geometry).
   - Gas-style globe lamps.
   - The plaza is new, with young trees as `tree_*` nodes.
2. **1941-1975, mature:**
   - The bunting is gone, the stone has darkened slightly, and the copper on the spire is fully green.
   - Electric lamps.
   - A small bronze plaque for the war years is added beside the main doors.
3. **1976-1999, worn:**
   - Water stains streak down from the cornices.
   - A few lamps are broken and one of the telescopes is gone.
   - The beacon is off.
   - Scaffolding (timber and steel tube) covers one pier, as if restoration has started and stalled.
4. **2000+, restored:**
   - The stone is cleaned.
   - The beacon is relit.
   - The new floodlights are visible as fixtures.
   - An accessibility ramp at one staircase.
   - A small visitor centre pavilion (glass and stone, one storey) at a plaza corner.

## 7. The far model: it matters most for this building

The far model will be seen from kilometres away on every town's skyline in its region. Build it from the new
silhouette:
- the arch block, with the arch openings cut through;
- the four piers with their setbacks;
- the sphere as a simple ring or disc between them;
- the deck slab, the octagonal lantern and the spire.

Use the same textures and keep it under 400 triangles. Put the beacon orb on it as a glow material. Check it at 64 px
and 32 px tall against the near model's outline: they must read as the same building.

## 8. Technical
- **Budget:** up to 30,000 triangles for the near model. This is a once-per-world wonder, so it gets double a normal
  landmark. Instance everything repeated (flags, lamps, benches, columns, rivets as texture).
- **Materials:** at most 12. Pack the sculptures, bronze and gilding into atlases.
- **Collision, in glTF metres:** Y up, +Z front, the same frame as the doors (not Blender's Z-up). Full height:
  - the piers, the arch block and the staircases as stepped boxes;
  - the railings at the deck and the cornice gallery;
  - the elevator shaft and stair;
  - the lantern;
  - the plaza props.
- **Walkable floor nodes:**
  - the plaza;
  - each staircase;
  - the rotunda;
  - the halls;
  - the stair landings;
  - the cornice gallery;
  - the deck;
  - the lantern chamber.
- No real flags: the flags on the plaza poles should be a made-up state or town flag (e.g. a blue field with a gold
  compass star), not a national flag.
- The plaza ground at y 0 or above, and the reflecting pool's water at y 0 with the basin below
  (`water_pool` material).

## Deliver
- The four state .glb files, `great-meridian-far.glb`, model-info.json, and your validation output.
- **One review sheet with large views:**
  - the silhouette test (black outline at three sizes);
  - each state by day and night (with floodlight nodes shown as markers);
  - the approach from the far end of the reflecting pool at person height;
  - looking up from the rotunda at the sphere;
  - the four guardians close up;
  - the elevator and stair inside the pier;
  - the cornice gallery;
  - the deck with a telescope;
  - the lantern chamber;
  - the far model beside the near model at the same size.
- **README:** what changed, and the node list for the sphere rings, beam, lift and floods.
