# Dove Beach: the second hand-made city (phase 1: design and road prototype)

Status: phase 1 is done (2026-11-25). It covers:
- the design below;
- the road network as data and code (`src/town/city2/`);
- the top-down maps (`tools/city2-map.py`);
- a Filament preview of the roads on their ground (`-Dmode=filament-city2`);
- the drive check (`City2DriveTest`), where the race Torrel drives the loop on Jolt.

Nothing in the game uses it yet. The build plan from phase 2 on is at the end.

**The name.** The owner named the city **Dove Beach** on 2026-11-25. The road outline of map_v1 reads as a dove diving
towards the viewer, wings spread, which the owner saw first. The same day the owner chose a dove theme for the city's
lore and look. Display names say Dove Beach. Module paths keep `city2` so as not to churn the code.

**The owner's brief** (2026-11-25): "the entire design designed around it being fun to drive in ... a fun little quirk of
this city that nobody will know it was designed that way, but it will be perfectly designed as a racing, driving city."
Later the same day the owner added:
- curvy and hilly roads, with crests, dips, climbs and descents with views, and no launches;
- beachy vibes after Tybee Island, Georgia, as inspiration only (CLAUDE.md: no real places, names or landmarks copied);
- the dove outline kept as a design constraint.

## The secret

Dove Beach is a believable small beach town with a fishing harbour, hills behind the beach and a history going back to
the 1700s. Its roads were all built for ordinary reasons:
- a seafront promenade;
- the old turnpike to the interior;
- a carriage road up to the observatory;
- a 1936 scenic drive built as a Depression public work;
- an old town high street;
- the quay.

These roads happen to link into a 13.8 km loop with every kind of corner, and every other road feeds into it. No sign,
map label, NPC line or loading text calls it a driving city. Players find out by driving it.

## The feel (inspired by a small Georgia barrier-island beach town; every name and design here is original)

- **Small-town scale and pace.** About 9,000 people at the peak, three times that in a summer week. Golf carts and
  bicycles in Seagrove, and nothing taller than four storeys except the lighthouse.
- **The beach.** The Long Strand, 2.7 km of sand on the bay's west shore. Dunes run behind it with sea oats and boardwalk
  crossings every 200 m, plus beach showers and lifeguard stands. The **Strand Pier** (1909) is a 480 m timber fishing pier
  with an open white pavilion at its end, rebuilt after storms. The **Esplanade** runs behind the dunes.
- **The beach strip** on the Esplanade (the Strand): a small, colourful run of ice cream parlours, surf and tackle shops,
  seafood shacks, two old motor courts and a bait shop.
- **Seagrove**, the 1905 grid behind the dunes: raised beach cottages on stilts in pastel colours, with screened porches,
  outside stairs and tin roofs. It has live oaks with moss, palms, and sand lanes.
- **Heron Marsh** behind the beach to the west: salt marsh and tidal creeks with long sunsets over the cordgrass. The
  **Marsh Causeway** crosses it on a low embankment and is the main road in, with a drawbridge over Heron Creek. Shrimp
  boats tie up at the **Creek Dock**, which has a seafood shack on stilts.
- **The harbour** inside its two breakwater moles: shrimp boats, the fish market and the Custom House. **Eastcove** beyond
  the Head has the marina, the shrimp dock and a fish camp. The 1972 container port of draft v1 is gone.
- **The Head**: the **Head Light** (1851, a black-and-white banded lighthouse) on Lighthouse Point, and **Neck Fort**
  (1790) with its concrete gun batteries (1898-1945) along the cliff, a museum since 1960.
- **The hills behind the beach** (the Crown, 245 m, and the Ridge) give the elevation, the views and the drivers' roads.
  The beach, the pier, the dunes and the marsh give the vibe.
- **The dove motif**, never spelled out:
  - the city crest is a dove over three waves;
  - the legend says the first settlers followed doves to the bay;
  - the **Dove Fountain** (a bronze dove with spread wings over a shell basin) stands at Fountain Circus;
  - Old Town roofs have dovecotes;
  - the Headland Tunnel's portals carry a dove mural.

## The dove outline (the owner's constraint)

Read from the owner's sketch and overlay (dove_overlay.jpg), the dove dives towards the viewer:

| part | roads and places |
|---|---|
| **tail**, fanned at the top | the Ridge loop: the Crown Road's stacked hairpins and the Ridge Drive's bends are the tail-feather tips; the Crown Observatory sits on the tail |
| **body** | down the gorge from Crown Foot past Sallow Mills to the harbour: **Mill Road** (added in v2 for this) |
| **head** | the Old Harbour; the **Custom House**'s round clock window is the dove's eye on the crest |
| **beak** | the two breakwater moles (1868) meeting in a point south of the harbour mouth (added in v2) |
| **left wing** | Fellside Road and the Esplanade, tip at Strand End |
| **right wing** | the Head peninsula: Neck Hill and the bay shore down to Lighthouse Point, back up Head Road to Neck Cross; tip at Lighthouse Point |

`city2.test.ts` ("keeps the dove") pins the loop to its outline points:
- Strand End;
- the Esplanade's Pavilion Bend;
- Harbour Road;
- Neck Hill and Neck Cross;
- Fellside Road and its viaduct;
- the Crown Road's hairpins;
- the Ridge Drive's bends.

Any road change that moves one of them more than 60 m fails the test. Head Road, Mill Road and the moles are kept by
their data. map_v2 draws them in a paler glow as "the rest of the dove outline".

## Where it is on the planet

Dove Beach takes the **second launch port's slot** (`launch/port2` in src/world/gen/hubs.ts). That slot is a port on the
continent nearest the capital's, "so the first sea route exists". It is therefore always on another continent than the
capital's (WORLD_GEN.md 3). Its hand-made pad replaces the generic 3 km one.

**Site rules** for the hub placer, on top of the port's own rules:
- a sheltered bay about 3 km across, open to the south;
- a headland on one side;
- a beach on the other with low ground behind it for the marsh;
- hills of 200-260 m within 2 km of the shore;
- a river coming down a gorge to the bay's head;
- a warm temperate humid climate (Cfa: palms and live oaks; WORLD_GEN.md 2.3).

The pad is about 8.5 x 6 km plus a 2 km growth margin.

**Pins** (metres from the Custom House: x east, z south):

| pin | where | what meets it |
|---|---|---|
| highway west | (-4400, 1700), out west | the Marsh Causeway from the mainland and the coast towns (the main road in) |
| highway north | (-1560, -3300), out north | the Pass Road up the Sallow to the pass (11 km) and the interior |
| highway east | (2700, 300), out east | Eastcove's road along the coast |
| rail north | (-1750, -3600), out north | the main line down the Cleft to Harbour Station (1886) |
| waterfront | (-190, 700) harbour mouth; (2500, 300) Eastcove | the coastal steamers (1880s-1950s), the car ferry to the capital's continent (1962 on), liners' tenders |

**How you get there** (CLAUDE.md, "No fast travel"):
- **By road**: from the capital, an expressway and highway drive to this continent's coast (the ferry crossing below),
  then the Coast Highway and the last 1.7 km across the marsh on the Causeway.
- **By sea**: the car ferry from the capital's continent docks in the Old Harbour (1962 on). Before that, the coastal
  steamers came in from the liner port.
- **By rail**: the main line comes down the Cleft to Harbour Station.

## Terrain (src/town/city2/site.ts)

The site is authored as features and turned into heights by one pure function, `groundHeight(x, z)`, with seeded noise
on top (seed `city2`):
- **the coast**, which includes:
  - the Bell, the bay, 2.9 km across;
  - the Long Strand's scalloped beach;
  - the harbour;
  - the Head, a peninsula 1 km wide and 2.7 km long, with true cliffs and coves on its seaward side (`CLIFF_SEGMENTS`:
    the land keeps its height to 25 m from the water);
- **the Sallow** and its bed heights, carving a gorge (the Cleft) 110 m wide and up to 80 m deep;
- **hills as anisotropic bumps**:
  - the Crown, a whaleback whose west face is square to the switchbacks;
  - the Ridge's four knolls, with a saddle;
  - the Neck, the Head and Lighthouse Point;
  - the Western Fells and the Uplands;
- **Heron Marsh**: flat at half a metre, its creeks cut 1.5 m deep, rising into the land over 200 m at its edge;
- **the moles**: stone walls 18 m wide, 4 m above the sea;
- a gentle rise inland from the coastal plain, and rolling noise that grows inland (the crests and dips the roads ride).

The roads are laid on this land (below), and **ground.ts** benches them into it. The carriageway sits at the road's height
and crossfall, with a 1.5 m shoulder and cut and fill slopes at 1:1.5 back to the land. Bridges, viaducts and tunnels
leave the land as it is. map_v2 and the Filament preview both draw this benched ground.

## Districts

| district | from | what |
|---|---|---|
| The Head | 1790 | Neck Fort and its batteries, the naval yard (1939-1994, now a park), the Head Light, cliff-top parkland |
| Old Harbour | 1780 | the fishing harbour inside the moles: shrimp boats, fish market, seafood shacks, the Custom House |
| Old Town | 1800 | steep lanes and stairs up the Crown's south skirt, pastel houses with dovecotes, churches, the funicular foot |
| Heron Marsh | always | salt marsh and creeks, the Causeway, the Creek Dock |
| Sallow Mills | 1860 | rice and timber mills and the brewery in the gorge mouth; lofts, a taproom and studios from the 1990s |
| Crown Heights | 1894 | villas round the switchbacks, the Observatory on the summit, the funicular's top station |
| Seagrove | 1905 | the grid behind the dunes: raised cottages on stilts (generated from the seed, `newTownGrid`) |
| The Strand | 1905 | the beach strip: the Esplanade, dunes and boardwalks, the pier, the shops and shacks, motor courts |
| The Ridge | 1936 | Ridge Park, big houses, the radio mast, the Belvedere overlook |
| Fellside | 1948 | post-war cottages on the Fells' lower slopes, the old motor court and the bait shop |
| Eastcove | 1952 | the marina and the shrimp dock, boatyards, a fish camp |
| Uplands | 1978 | later houses in the hills along the Pass Road, the school, a small shopping strip |

The town is planned for walking and the crime loop as much as for cars:
- Old Town and Seagrove are walkable grids and lanes.
- The harbour and the Strand are the busy public places, so there are plenty of witnesses.
- Police jurisdiction is the town (a small department, with county deputies on the Causeway and the Pass Road).
- The marsh, the Head's park, the gorge and the Ridge Park are the quiet places.
- Roads out are few: the Causeway, the Pass Road and the tunnel to Eastcove, plus the ferry. Fleeing the town means
  choosing one, and roadblocks are plausible.

## History by era

- **1700s-1780s**: a fishing hamlet on the Sallow's mouth. In the legend, the first boat followed doves into the bay.
  The salt marsh is cut for hay.
- **1790-1850s**: Neck Fort is built over the harbour. Then come the Custom House (1838), the stone Sallow Bridge (1840),
  the Head Light (1851), and the turnpike up the Cleft to the pass (1832, the Pass Road and Fellside Road's line).
- **1860s-1890s**: rice and timber mills come to the gorge mouth (Mill Road, 1861), then the breakwater moles (1868) and
  the brewery (1871). The railway comes down the Cleft to Harbour Station (1886). The shell road across the marsh follows
  (1887), then the carriage road with four hairpins up to the Crown Observatory (1894) and the funicular (1899).
- **1900s-1920s**: the resort is born:
  - the Seagrove grid and its raised cottages (1905);
  - the Esplanade and the Strand (1905), trams down the Esplanade (1912-1954);
  - the pier and pavilion (1909) and the Dove Fountain (1912);
  - the Head Road cut along the cliffs (1911);
  - the Heron Creek swing bridge (1923).
- **1930s-1940s**: Ridge Drive (1936) is built as a Depression public work, with stone walls and the Belvedere. The
  Fellside Viaduct (1931) replaces the old crossing in the gorge. The naval yard on the Head (1939) brings the wartime
  tunnel under the Neck (1942), closed to the public. The batteries are manned.
- **1950s-1960s**: the car comes:
  - the Esplanade becomes a dual carriageway with palms (1958);
  - the motor courts and the radio mast (1952) arrive;
  - Eastcove's shrimp dock opens (1952), then the car ferry (1962) and the bascule bridge on the Causeway (1964);
  - the trams go.
- **1970s-1980s**: the mills close and the town gets shabby. When the naval yard closes (1984, then a park 1994), its
  tunnel opens to traffic: the short way to Eastcove.
- **1990s-2010s**: the revival: lofts in the mills (1996), the taproom, boutique motels, surf shops. The Uplands are
  built.
- **2020s-2050s**: the tram comes back on the Esplanade (2034, two lanes for cars). The Ridge Drive closes to cars at
  night (2045). Electric golf carts take over Seagrove. The marsh is protected.

## The roads (src/town/city2/roads.ts)

Every road is laid out the way an engineer lays one out (align.ts):
- **plan**: a chain of vertices, each a point, a corner (tangents filleted with radius R) or a circle the road wraps (a
  hairpin), joined by common tangents. This gives exact tangents and arcs, and ends where they were authored.
- **profile**: by default from the land (`fitProfile`). It follows the natural ground smoothed over two vertical curves.
  Its ends sit at its junctions' heights, it is held to its grade limit (4% round a hairpin, so the turn is nearly level)
  and smoothed by its vertical curves. Over a bridge or through a tunnel it runs straight from end to end. The flat
  seafront, the quay with its humpbacked bridge and the Causeway keep authored heights.
- **crossfall**: banked for the design speed (half the lateral demand, up to the road's emax, run in over 40 m).

Corner speeds are the race Torrel's estimate from its measured grip (CARS.md: 11-13 m/s^2) plus the bank, capped at its
273 km/h top speed. Grades are the steepest over 10 m. The "Torrel drove" column is the robot's slowest speed through
the corner in the Jolt check below, deliberately conservative.

### The loop (13.8 km: the left wing, the tail and the right wing's inner edge)

| # | road | length | corners (R: radius) | grade | crests, dips | estimate | Torrel drove | what makes it fun |
|---|---|---|---|---|---|---|---|---|
| 1 | **The Esplanade** (boulevard, 1905) | 2.67 km | sweepers R 450-900 linked left-right; **Pavilion Bend** R 300 | flat | none | 230 km/h at Pavilion Bend, flat out elsewhere | 199-238 | a seafront blast: long sweepers that tighten round the pier head; dunes, palms, the pier flashing past |
| 2 | **Fellside Road** (turnpike 1832, rebuilt 1931) | 4.12 km | **the Fell Esses**: nine corners, R 160-400, alternating | climbs 5 to 118 m, up to 5.5% | a dip at the gorge rim, the rise to the viaduct (crest R 751, sag R 988) | 168-268 km/h | 147-206 | rising esses on the hillside that link into each other, then straight onto the viaduct, 770 m long and up to 85 m over the river |
| 3 | **Crown Road** (carriage road 1894) | 2.20 km | **four stacked hairpins** R 16-18 (170 deg each), a last right R 35 | 118 to 255 m, 8.3% legs, level turns | crest and sag R 210 (in the slow turns) | 49-52 km/h hairpins; 120-130 km/h on the legs | 44-47 at the hairpins | brake hard, turn in, power up a 350 m leg, again four times; the view grows each turn |
| 4 | **Ridge Drive** (scenic drive 1936) | 2.60 km | esses R 150-250, then **the Belvedere** R 120 (80 deg) over the bay | 255 down to 115 m, up to 9%, rolling over its knolls | crests R 555, sags R 532 | 143-212 km/h | 131-187 | along the crest over knolls, light over the tops, the bay opening at the Belvedere's long bend |
| 5 | **Neck Hill** (high street 1802) | 1.22 km | two linked lefts R 110 and R 90, a right R 60 | 115 down to 6 m, 11% | sag R 358 at the bottom | 95-134 km/h | 92-123 | the steep old town descent, braking downhill into linked corners |
| 6 | **Harbour Road** (the quay) | 0.98 km | square corners round the Custom House R 35-40; the humpbacked Sallow Bridge | flat but for the hump, 4.2% | the hump: crest R 612 | 71-76 km/h | 80-86 | cobbles, a tight square, and the old bridge's hump (gentle enough not to launch: see the drive) |

The junctions (Fountain Circus, a roundabout of R 30; Strand End, R 24; Crown Foot, Crown Top, Neck Cross, Quay East)
have no fillet in the data. The drive check rounds them: a 15 m kerb radius, or the roundabout's own.

### The other roads

| road | length | what |
|---|---|---|
| **Head Road** (1911) | 6.26 km | the right wing: cliff-top esses R 110-220 above the coves, the **lighthouse loop** (R 28, 129 deg) round Lighthouse Point, back along the bay side; up to 8.3%; closed through the naval yard 1939-1994 |
| **Mill Road** (1861) | 1.34 km | the body: nearly straight down the gorge's east bank from Crown Foot to the harbour, 9% at its steepest |
| **Marsh Causeway** (1887) | 1.73 km | the way in: long sweepers R 1,200-1,500 on a low embankment across the marsh, the drawbridge over Heron Creek |
| **Pass Road** (1832) | 2.52 km in the prototype, 11 km to the pass | up the Cleft's east side under the rock walls, R 160-300, 6.4%; one sharp crest (R 145) to soften in phase 2 |
| **Headland Tunnel** (1942/1984) | 2.16 km | under the Neck in one long curve (R 1,500); 870 m of it in the tunnel |
| **Steep Street** (1790) | 1.02 km | the old town's cobbled climb: five corners of R 15-25 between walls, 12% |
| **Crown Lane** (1810) | 0.65 km | along the Crown's skirt from the old town to the switchbacks' foot |
| **Seagrove's grid** | 18 streets | generated from the seed square to the beach's chord: seven streets along the sea every 130-170 m, avenues across every 160-210 m |

Across every road but the tunnel, 15-48% of the length is curved (R under 1,500 m), and the test asks for 15% or more.
That is the owner's mix (WORLD_GEN.md 5): straight where the land is flat and open (Seagrove, the Causeway, Mill Road),
curving where it is not.

### The roads through the eras

Every road carries its eras (`eras`: surface, lanes, note), so a time traveller drives a different city:

| years | change |
|---|---|
| before 1887 | the turnpike (dirt) up the Cleft, cobbled Harbour Road and Neck Hill, Steep Street; no Esplanade, no Causeway (the town is reached by sea or by the turnpike) |
| 1887 | the shell road across the marsh |
| 1894 | the Crown Road, gravel with four hairpins (cobbled setts on the hairpins from 1921) |
| 1905-1924 | the Esplanade: a gravel carriage drive, then macadam with trams down the middle (1912-1954) |
| 1911 | Head Road cut along the cliffs (gravel; asphalt 1934) |
| 1923 | the Causeway asphalted, the Heron Creek swing bridge |
| 1931 | the Fellside Viaduct; Fellside Road asphalted (macadam from 1910) |
| 1936 | Ridge Drive opens, asphalt with stone walls |
| 1939-1994 | Head Road closed through the naval yard |
| 1952 | the Crown Road asphalted |
| 1955-1958 | Harbour Road asphalted, four lanes; the Esplanade a dual carriageway with palms |
| 1964 | the bascule bridge on the Causeway |
| 1984 | the Headland Tunnel opens to traffic (a naval tunnel since 1942) |
| 1996 | Mill Road gets trees and a riverside walk |
| 2034 | the Esplanade down to two lanes and the tram |
| 2045 | Ridge Drive closed to cars at night |

The Bayway, the elevated 1966 expressway of draft v1, is gone. An elevated expressway does not read right in a small
beach town.

## Landmarks (model tasks)

| landmark | from | who | the task |
|---|---|---|---|
| Crown Observatory | 1894 | Sam | domed stone observatory and keeper's house on the summit (the dove's tail); the dome turns at night |
| Crown Funicular | 1899 | Jeff | a two-car funicular from Old Town to the summit: track, stations, the ride as a real ride |
| Custom House | 1838 | Sam | the harbour's stone custom house, a round clock window facing the bay (the dove's eye on the crest) |
| Strand Pier and Pavilion | 1909 | Sam | a 480 m timber fishing pier on piles with an open white pavilion at its end |
| Dove Fountain | 1912 | Sam | a bronze dove with spread wings over a shell basin; palms round Fountain Circus |
| Head Light | 1851 | Sam | a black-and-white banded lighthouse and keepers' cottages on Lighthouse Point |
| Neck Fort | 1790 | Sam | an old coastal fort on the Neck, concrete gun batteries along the cliff |
| Cleft Viaduct (rail) | 1886 | Claude | a curved stone arch viaduct over the Sallow (engine geometry: arches by code) |
| Fellside Viaduct | 1931 | Claude | the concrete road viaduct, 770 m long, up to 85 m over the river (with the road mesh) |
| Headland Tunnel portals | 1942 | Sam | wartime concrete portals with blast walls; a dove mural from 1984 |
| The Belvedere | 1936 | Sam | a stone overlook with a balustrade and coin telescopes at the Ridge Drive's big bend |
| Ridge Mast | 1952 | Sam | the radio and TV mast with red lights |
| Eastcove shrimp dock | 1952 | Sam | shrimp boats with outriggers and nets, an ice house, the marina's floating docks |
| Harbour Station | 1886 | Sam | the terminus: a train shed and booking hall by the quay |
| Sallow Brewery | 1871 | Sam | brick brewery with a chimney on the river |
| Heron Creek drawbridge | 1923 | Claude | the Causeway's bascule bridge and its tender's house; it lifts for boats |
| Creek Dock | 1890 | Sam | a timber dock with shrimp boats and a seafood shack on stilts |
| Dune crossings | 1930 | Sam | a kit: dunes with sea oats, boardwalk crossings with rails, showers, lifeguard stands |
| Raised beach cottages | 1905 | Sam | a kit of pastel cottages on stilts with screened porches, outside stairs, tin roofs |
| The moles | 1868 | Claude | the two breakwaters (the beak): stone, a walk along the top, a light at each head |

## The prototype

- `src/town/city2/align.ts`: the road layout: belt paths, profiles, banking, and the measures (corners, corner speeds,
  grades, vertical radii, curved share).
- `src/town/city2/site.ts`: the land, districts, landmarks, coast, river, rail, marsh, dunes, pier and moles.
- `src/town/city2/roads.ts`: the roads and junctions as data, the profile from the land, the structures, and the
  Seagrove grid from the seed.
- `src/town/city2/ground.ts`: the ground with its roads benched in.
- `src/town/city2/city2.test.ts`: 10 tests:
  - every road meets its junctions exactly, at their heights;
  - grade limits;
  - the four hairpins;
  - the closed loop of six roads;
  - the dove outline;
  - every kind of corner;
  - no cut or fill over 45 m outside a structure;
  - the viaduct high and the tunnel deep;
  - the ground benched under the roads;
  - determinism.
- `kotlin-street/tools/city2-dump.ts <dir> [--loop <json>]`: writes:
  - `city2.json` (everything sampled, with each road's measures);
  - `ground.f32` (10 m grid);
  - `render.json` (2 m samples for the preview);
  - the drive check's loop: `desktop/src/crossroadsTest/resources/city2/loop.json` plus `ground20.bin`.
- `kotlin-street/tools/city2-map.py <dir> <png> [title]`: the top-down map.
- `desktop/src/filament/kotlin/FilamentCity2Preview.kt`, run with
  `gradle :desktop:preview -Dmode=filament-city2 -Dcity2=<dir>`:
  - draws the ground in pixel-art tiles (grass, hill grass, sand, marsh, rock where steep), each road as a ribbon at its
    height and crossfall with skirts, piers under the bridges and viaduct, the moles, the pier and pavilion, and the sea;
  - uses the town's lit material, with seven shots;
  - draws no buildings, trees or cars yet.

Cut and fill as laid out, the largest outside structures:
- Fellside Road: up to 45 m of fill on the viaduct's west approach.
- Head Road: 37 m where the cliff road crosses a cove's head.
- Crown Road: about 25 m of cut on the legs.
- Crown Lane: 27 m of cut through the Crown's spur.

Each is a retaining wall or embankment in phase 3. The test holds all of them under 45 m.

## The drive (City2DriveTest, Jolt on the desktop build server, not the S25)

The race Torrel (C046) is driven round the loop on the prototype's real profile, using whatever tune `model-info.json`
holds in this tree. Another job is retuning its understeer at the same time.

How the test runs:
- **The ground** is heightfield chunks streamed round the car, as VehicleWorld does beyond the town. On the carriageway
  it is the road's height and crossfall; outside it, cut and fill slopes back to the land.
- **The driver** is a robot:
  - pure pursuit on the centre line;
  - a speed plan from the line's curvature (lateral 10 m/s^2 plus the bank's share, braking at 8.5 m/s^2);
  - no limit at crests, so a crest that throws the car would show.
- **It reports** the lap time, every corner's slowest speed, any moment with all four wheels off the ground, every hit
  on the suspension's bump stop at speed, the worst distance from the centre line, resets and roll.

Results on the final profile (2026-11-25):

| | |
|---|---|
| loop | 13.80 km; first lap from a standing start **5:54.2** (354.2 s), average **140 km/h** |
| by road | Esplanade 55.7 s (173 km/h average) · Fellside Road 88.5 s (168) · Crown Road 83.7 s (95) · Ridge Drive 58.4 s (160) · Neck Hill 37.4 s (118) · Harbour Road 30.5 s (117) |
| slowest corners driven / grip estimate | hairpins 44-47 / 49-52 km/h · Crown Top's right (R 35) 71 / 73 · Neck Hill R 60 92 / 95 · Harbour Road's square 80-86 / 71-76 · the Belvedere (R 120) 130 / 143 · Fell Esses R 160-220 147-172 / 168-199 · Pavilion Bend (R 300) 199 / 230 · the Esplanade's fastest sweeper 238 km/h |
| airborne (all four wheels off) | **none** |
| suspension on its bump stop above 54 km/h | **none** |
| worst distance from the centre line | 2.5 m (Neck Cross, a 75 deg junction; the carriageway's half is 6 m) |
| lost the line, resets | none; it never rolled past 0.99 upright |

The robot drives 5-25% under the grip estimate in the fast corners and close to it in the slow ones. A person on the
S25 will be quicker; the gap between estimate and robot is the margin a good driver can find. On the two square corners
of Harbour Road it drove faster than the estimate, because the junction rounding and the full width of the quay let it
take a wider line than the centre line's radius.

**Launches found and fixed on the way** (each was measured in an earlier run, fixed in the data, and is gone in the
final run):
- **The Sallow Bridge hump**, first authored as a humpback with a 20 m vertical curve, threw the car for **0.77 s at
  164 km/h**. The deck now sits 2.5 m over the quay, with a 50 m curve (crest R 612). It no longer lifts at any speed the
  car reaches there.
- **Crown Top** threw the car for **0.75 s at 101 km/h**, where the Crown Road arrived climbing at 8% and the hillier
  Ridge Drive left falling at 9%. Then 0.18 s, at 104 km/h, with 2.5% junction zones. Every road is now level for 30 m
  each side of a junction (0.5%), with 2.5% out to 60 m. A test holds every loop junction's change of grade under 6%.
- **A bump at every chunk border** came from the heightfield padding below, not the data.

The crests that remain are the drivers' crests: Ridge Drive's knolls (crests R 555-670, light over the tops at 150-190
km/h, about a third to a half of g off the springs) and Fellside Road's rise to the viaduct (crest R 751). None of them
lifted the robot's wheels.

Two things found on the way:
- **A heightfield bug in the game.** Jolt wants a heightfield's sample count to be a multiple of its block size (4).
  physics_core pads VehicleWorld's 33-sample chunks to 36 by repeating the far edge. That makes a flat 6 m overhang into
  the next chunk at the edge's height, which made the first drive jump at every chunk border where the ground sloped
  across it. The test uses 32 samples on 62 m chunks. **The game's VehicleWorld still uses 33 on 64 m: fix it there**
  (32 + 1 = 33 is not a multiple of 4: use 32 samples on 62 m, or 37 on 72 m). This is a one-line job for whoever owns
  vehicle physics next.
- **The junctions need fillets in the data**, not only in the drive. The first drive cut the corners onto the land at
  every junction and left the ground there. Phase 3's road meshes need junction patches.

## What the engine lacks (honest list)

| need | today | effort |
|---|---|---|
| **Terrain under a town** | the town is flat: TownGround draws every road at one height (`LIFT_ROAD`) and the world terrain is flattened under the town's grass (`townGrass`) | **XL**: a city ground (ground.ts baked, a heightfield for the pad drawn like WorldNear's chunks), buildings on pads at their lot heights, terraced yards, retaining walls |
| **Curved, sloped, banked road meshes** | the town's roads are axis-aligned rectangles (plan.ts `Road`, TownGround ribbons); world roads are only painted into the terrain material | **L**: ribbons from the alignment with kerbs, sidewalks, markings, junction patches and roundabouts; the preview's ribbons are the start |
| **Bridges, viaducts, overpasses in physics** | physics_core has boxes, cylinders and heightfields; a heightfield has one height a point | **M**: a static triangle-mesh shape (Jolt `MeshShape`) in physics_core and JNI for decks, streamed like the town's shapes |
| **Tunnels** | no holes in the terrain or the heightfield | **M**: heightfield holes (Jolt's no-collision samples) plus a terrain hole when drawing, the tunnel's mesh and portals, lights inside |
| **Heightfield chunks** | 33 samples padded to 36 (above) | **S** |
| **The street graph on curved streets** | town-graph.ts nodes and rows assume axis-aligned roads; frontage.ts lays lots along straight faces | **L**: a graph of curved segments, lots along curved frontages, NPC walking and routing |
| **Traffic and police cars that drive** | parked cars only | **L** (its own system; the drive check's robot is a seed for it) |
| **Elevation for walking** | OpenWalk moves on flat ground | **M**: feet on the city ground, stairs (Old Town), ramps |
| **Maps** | the minimap draws the town's rectangles | **S-M**: draw the alignments and the marsh, dunes and pier (city2-map.py shows the look) |
| **Camera in the hills** | the orbit camera knows no terrain | **S**: keep it above the ground and out of the hillside |
| **Beach and marsh** | the ocean is Jeff's; no beach or marsh ground, no dunes | **M**: ground pictures and kits (dunes, sea oats, boardwalks, cordgrass) |
| **Streaming a bigger city** | the capital is 2.5 x 1.2 km; Dove Beach's pad is 8.5 x 6 km | **M**: measure, then stream districts (STREAMING.md) |

## The build plan (phase 2 onward)

The phases are sequenced so that no two jobs edit the same files. Effort: S under a day, M one to two days, L three to
five days, XL more than that (agent days).

| phase | job | who | size | files (new unless noted) | after |
|---|---|---|---|---|---|
| 2a | the owner's review of map_v2 and the renders; any change to the outline or names | owner, Claude | S | src/town/city2/* | now |
| 2b | **the bake**: `tools/city2-bake.ts` writes `assets/town/dove-beach/` (ground grid, alignments 1 m, junction fillets, structures, eras) and the Kotlin reader `domain/CityPlan.kt`, with a cross-language test like WorldSkeletonTest | Claude (Opus design), Sonnet | M | tools/city2-bake.ts, shared/.../domain/CityPlan.kt, a crossroadsTest | 2a |
| 3a | **the city ground**: drawing the pad's heightfield in the terrain style (WorldNear's chunks over a city source), cut and fill faces, the marsh and the beach | Claude (Opus) | L | shared/.../scene3d/CityGround.kt; a small hook in FilamentTown | 2b |
| 3b | **the road meshes**: ribbons, kerbs, sidewalks, markings, junction patches, roundabouts, bridges and viaduct piers, tunnel portals, the moles and the pier | Sonnet on a spec from Claude | L | shared/.../scene3d/CityRoads.kt | 3a (shares nothing) |
| 4 | **physics**: a mesh shape in physics_core and JNI, streamed road decks, heightfield holes, the 33-sample fix | Sonnet | M | app/src/main/cpp/physics/*, Physics.kt, VehicleWorld.kt | after the current vehicle tuning job lands |
| 5a | **the plan**: plots along curved frontages, district fills (cottages, the strip, the harbour), the reservation ledger by era, the generator as authoring tool | Claude (Opus) | L | src/town/city2/plan.ts, lots.ts | 2b |
| 5b | **the walk and the street graph** on curved streets and slopes (OpenWalk on the city ground, stairs) | Claude, Sonnet | L | shared/.../scene/CityWalk.kt, a curved town-graph | 5a, 3a |
| 6 | **landmarks**: Sam's models (the observatory, Head Light, pier pavilion, Dove Fountain, Custom House, cottages kit, dune kit); Claude's engine geometry (viaducts, moles, drawbridge, tunnel) | Sam, Claude | L (many items) | LANDMARKS_GLB.md tasks; scene3d kits | 3b |
| 7 | **on the planet**: a Dove Beach HubSpec with the pins above in hubs.ts, the site search (bay, headland, hills, marsh, Cfa), towns-handmade.json's line, the ferry route | Claude | M | src/world/gen/hubs.ts, kotlin-street/tools/towns-handmade.json | after the current world jobs |
| 8 | **life**: people, households, schedules, the police department, beach crowds by season, dialogue facts (the doves' legend), the conversation log | Claude, Jeff | L | src/town/city2/population.ts and the existing sim hooks | 5a |
| 9 | **the S25**: drive it with the thumb, measure frame time and heat on the hills and the Esplanade, tune steering fade for the hairpins | owner, Claude | M | none | 3b, 4 |

## Open questions for the owner

- **The loop's lap time** was measured by a careful robot. Would you like a timed challenge anywhere, or keep it entirely
  secret? It is currently secret: no in-game text.
- **The Sallow Bridge hump** is now gentle (crest R 612, no lift). Do you want one deliberate small hop somewhere (a
  humpback bridge that lifts the car a little at speed), or none at all?
- **The tram** returning in 2034 narrows the Esplanade to two lanes in the future eras. Keep it?

## Phase 5a — Lots on curved streets and the era ledger (G023)

Dove Beach's buildable plan now lives beside the roads in `src/town/city2/` without altering a single road alignment, grade, or dove outline point (`align.ts`, `site.ts`, `roads.ts`, `ground.ts`, and `city2.test.ts` are untouched).

### Modules

- **`src/town/city2/lots.ts`** — Lays oriented frontage lots along the 13 curved, sloped roads of `roads.ts` and Seagrove's 1905 `newTownGrid` streets (`generateLots(seed)`), plus reserved footprints for all 19 landmarks in `LANDMARKS` (`reserveLandmarkPlots()`).
  - Each lot carries its road reference (`roadId`, `roadS`, `side`), district (`districtId`), `LotKind` (reusing `src/town/kinds.ts`), front and rear yard setbacks (`frontYard`, `backYard`), leveled inner building pad (`padW`, `padD`, `padCorners`, `y`), ground grade, and 4-corner world polygon (`corners`).
  - Every candidate is rejected if any corner, midpoint, or center falls on any curved carriageway or junction clearance zone, any Seagrove street, Heron Marsh (`0` lots), the Long Strand beach or dunes, the Sallow River (`< 26 m`), the railway (`< 13 m`), ground steeper than `MAX_BUILDABLE_GRADE` (`0.30`), any exclusive landmark footprint, or any existing lot (exact 2D SAT polygon intersection with `1.5 m` separation).
  - Canonical seed (`"city2"`) places **1,662 frontage lots** across all 11 buildable districts (`harbour: 23`, `oldtown: 101`, `crown: 47`, `mills: 10`, `strand: 124`, `fellside: 164`, `ridge: 55`, `eastcove: 31`, `head: 68`, `uplands: 4`, `newtown: 1,035`, `marsh: 0`) plus **19 landmark plots**.

- **`src/town/city2/ledger.ts`** — Builds the deterministic per-lot and per-landmark history from the 1700s to the 2050s (`buildDoveBeachPlan(seed)` and `plan.standingIn(year)`).
  - Follows the town's chronology: 1700s fishing hamlet (`4` harbour shacks in 1760), Neck Fort (1790), Old Town (1800), Custom House (1838), Head Light (1851), Sallow Mills (1861) & Brewery (1871), railway & Harbour Station (1886), Crown Observatory (1894) & Funicular (1899), Seagrove & Esplanade/Strand (1905), Strand Pier (1909), Dove Fountain (1912), Heron Creek drawbridge (1923), Ridge Drive (1936), Headland Naval Yard (1939–1984, cleared into headland parkland in 1994), Fellside (1948), Eastcove marina & shrimp dock and Strand motor courts (1952), shuttered mills (1970s), Uplands (1978), adaptive-reuse mill lofts/taprooms & boutique motels (1990s), and the modern town through 2050 (including the tram's 2034 return).
  - Regular buildings stay within `1–4` storeys in every era; only **Head Light** exceeds 4 storeys.

- **`src/town/city2/mapSvg.ts`** — Pure TypeScript top-down cartographic SVG map renderer (`renderDoveBeachSvg(plan, year)`) showing coastline, ocean, Long Strand dunes, Heron Marsh, Sallow River & creeks, railway (1886+), moles & pier, era-filtered roads (highlighting the 5-road dove loop), Seagrove's grid (1905+), era-colored lots & pads, and active landmark plots. Generated snapshots for **1900** (127 buildings, 9 landmarks), **1960** (1,130 buildings, 19 landmarks), and **2025** (1,626 buildings, 19 landmarks) live in `prototypes/G023/`.
