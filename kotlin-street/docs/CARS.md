# Cars the player can choose

The cars themselves, their physics and how they are drawn are in [VEHICLES.md](VEHICLES.md). This page covers the car
button's list and the race Torrel (C046).

## Choosing the car

The open world has a round car button just right of the bicycle (on foot only). A tap opens a small list in the HUD's
style (`HudCarPicker` in `shared/src/main/kotlin/hud/GameHud.kt`). The list holds every car in
`VehicleSpec.CATALOGUE`, in this order: Alderwick 1928, Fenmere 1938, Northcairn 1956, Rowanvale 1970, Tallmere 1972,
Torrel 1968, Torrel 1988, Torrel 1988 RS. At its top is "↻ Same again: <last car>".

- Picking a car spawns it at the nearest clear kerb beside the player, replacing the car spawned before
  (`VehicleWorld.requestSpawn(key)`). The pick is remembered in the `cars` preferences (`last`) between games.
- A long press on the button spawns the same car again with no list. With no earlier pick, that is the first car.
- The Debug tab's "Test car" row has the same choice ("Choose a car…", "Same again: …").
- The list logic is `CarPicker` (`shared/src/main/kotlin/vehicle/CarPicker.kt`), unit-tested in `RaceTorrelTest`.
  It drops a remembered key the catalogue no longer has. A world that loaded fewer cars lists only those.

`VehicleWorld.start` loads the whole catalogue. Towns park only the road cars: a spec with a `race` setup is never
parked, so the race Torrel appears only when the player spawns it.

## The race Torrel (`torrel-1988-rs`, C046)

This is the 1988 Torrel after a made-up racing shop (Halvane Speedshop) got hold of it. It keeps the road car's body,
lamps and cabin, lowered 50 mm, and adds bolt-on race parts:

- a big two-element rear wing on tall swept uprights, its main plane about 6 cm above the roof line, with riveted black
  endplates;
- riveted bolt-on arches over a wider track (front 1.60 m, rear 1.66 m, up from 1.48 and 1.53);
- wider, lower tyres (front 245 mm, r 0.300 m; rear 285 mm, r 0.312 m) on ten-spoke race wheels with a deep dish and
  a centre-lock nut;
- a deeper front air dam with three mesh intakes, a black splitter and two canards a side;
- a stripped cabin: both road seats are gone, replaced by a race bucket with a red harness, a roll cage (main hoop with
  a diagonal and a harness bar, a front hoop up the screen pillars, roof rails, an X door bar on the driver's side) and
  a fire extinguisher on the bare passenger floor;
- bonnet pins, an alloy fuel filler on the bonnet, red tow hooks front and rear, a centre twin exhaust (the road car's
  corner pipes are gone) and diffuser fins.

### How it is made

`tools/make_torrel_rs.py` builds it from the road Torrel's three GLBs (`python3 -I tools/make_torrel_rs.py`; it needs
numpy, Pillow and pygltflib). The output is the same every run. The script:

- drops the road wing, the seats and the exhaust pipes from the road GLBs;
- lowers the body and moves the hubs;
- adds the parts as simple low-poly geometry.

New parts get their own 6th material, `race_kit`: a packed atlas where every face has its own pixel-art picture at the
Torrel's 0.0388 m texel, in colours sampled from the Torrel's own textures. Body-coloured parts (the wing's planes, the
arches, the air dam) use `paint_primary`, so the game repaints them with the car.

The script also writes the mid and far models from the road car's own, changed the same way, and `model-info.json`.

### Liveries

There are two liveries of our own, with no real makers, sponsors or racing colours. Both are decals: the car's own
paint skin clipped to each region and laid 7-11 mm proud. The decals sit under nodes named `livery_<id>_<part>`, on
the part they move with (bonnet, doors, deck lid, wing).

- **a, Halvane stripes** (body `#ece4cc`): twin navy stripes with red pinstripes over the bonnet, roof, deck and wing,
  white "27" roundels on the doors, and a navy sun strip reading HALVANE.
- **b, Ostrand flash** (body `#2c3a46`): an amber side flash under an orange band, an orange nose with a yellow "7"
  roundel, "7" roundels on the doors, and an amber sun strip reading OSTRAND.

`VehicleKit.build` draws a livery's nodes when the car's colour is that livery's body colour
(`VehicleSpec.liveryFor`), and none for the four plain colours (yellow, red, racing green, orange). Both livery colours
are in the presets, so a spawn picks either livery or plain paint.

### Model numbers

| | Road Torrel | Race Torrel |
| --- | ---: | ---: |
| Near triangles | 2,876 | 3,628 drawn (3,869 with both liveries) |
| Mid triangles | 1,040 | 1,138 drawn (31% of near) |
| Far triangles | 142 | 146 |
| Materials | 5 | 6 (`race_kit` added) |

The near model is over C011's 3,000-triangle budget by about a fifth. That is the cage, the race wheels and the
rivets. It is kept: on the S25 a few hundred triangles more on one car do not matter, and the parts are what make it
read as a race car.

### Handling (model-info.json `race`; `VehicleSpec.Race`)

The road cars' drivetrains are estimates by era (`Drivetrain.of`). The race build carries its own setup in
`model-info.json`, used as it is.

| | Road Torrel | Race Torrel |
| --- | ---: | ---: |
| Mass | 1,088 kg | 968 kg |
| Weight front/rear | 42/58 | 41/59 |
| Engine | 260 Nm, 800-7,000 rpm | 330 Nm, 900-8,200 rpm |
| Gears | 5 (3.3 … 1.0) | 6 (3.0, 2.1, 1.6, 1.3, 1.08, 0.92), final drive 3.63 |
| Tyre grip (share of Jolt's radial) | 1.05 | 1.34 |
| Springs, damping | 2.0 Hz, 0.52 | 2.9 Hz, 0.62 |
| Suspension travel | 0.05-0.22 m | 0.035-0.15 m |
| Centre of mass | 0.42 m | 0.36 m |
| Ride height (body underside) | 0.154 m | 0.104 m (splitter 0.09 m) |
| Brakes a wheel | 1,700 Nm | 2,400 Nm |
| Downforce | none | 650 N at 100 km/h, 2,600 N at 200 km/h, 40% front (38% before 2026-11-25) |
| Lateral grip (per axle) | 1.05 both | 1.52 front, 1.48 rear; front tail flat to 12° (2026-11-25) |
| Top speed (model-info) | 224 km/h | 278 km/h (+24%) |
| 0-100 km/h (model-info estimate) | 6.4 s | 4.9 s |

Downforce is a real force in the physics. `VehicleDesc.DOWNFORCE` (N per (m/s)²) and `DOWNFORCE_FRONT` were added to
`physics_core`. Each step, before Jolt updates, `k·v²` is applied along the body's down axis, split between the two
axles. The tyres' grip grows with their load, so the car grips harder the faster it goes. Every road car's value is 0
and its description is otherwise exactly what it was (`RaceTorrelTest.theRoadCarsAreUnchanged`).

These figures were measured on the 4-core x86 build server, not on the S25 (`RaceTorrelPhysicsTest`, flat ground,
throttle held):

| | Road Torrel | Race Torrel |
| --- | ---: | ---: |
| Top speed after 60 s | 211 km/h | 273 km/h |
| 0-100 km/h (the game's automatic) | 8.0 s | 5.4 s |
| Lateral grip, half lock at 90 km/h | 9.9 m/s² | 13.0 m/s² (11.6 before the balance work) |
| Lateral grip, half lock at 150 km/h | 10.4 m/s² | 14.8 m/s² (13.2) |
| 100-0 km/h | 40.5 m | 35.0 m |
| Suspension at 236 km/h vs at rest | | 0.107 m vs 0.119 m (pressed down by the wing) |

The race Torrel stays upright through every test, including full lock at 120 km/h. At 236 km/h the body bobs 1 mm.
Dropped from 30 cm, it lands without hopping and settles. The 0-100 times are slower than the model-info estimates:
the tyres spin at launch (before 2026-11-25 the automatic also waited on the limiter while they spun: [VEHICLES.md](VEHICLES.md), "The gearbox"). The gap between the two cars is what counts.

### Cornering balance (2026-11-25, the owner: "it still understeers")

`CorneringBalanceTest` holds the car at 60, 100 and 150 km/h on a skid pad and winds the steering on in 14 steps up to
what the game's full thumb gives at that speed (the steering's speed fade included). Build-server numbers:

| | 60 km/h | 100 km/h | 150 km/h |
| --- | ---: | ---: | ---: |
| Understeer gradient K, before / after | -0.04 / -0.05 deg/g | +0.02 / +0.03 | +0.09 / +0.05 |
| Most lateral g, before / after | 1.23 / 1.33 | 1.33 / 1.42 | 1.38 / 1.49 |
| At the full thumb, before / after | 1.09 / 1.23 | 1.17 / 1.33 | 1.33 / 1.44 |
| Lift off at the limit: body slide, before / after | 3.1° / 3.2° | 2.0° / 4.7° | 2.6° / 9.9° (road car 6.5°) |

The car was neutral in the linear range all along. The understeer was at the limit, for two reasons:
- Jolt's lateral grip curve peaks at 3° of slip and falls to 83% of the peak by 20°. The game's full thumb gives 2-3
  times the lock the front can use at every speed, so the front tyres were driven past their peak and washed out (1.09 g
  at full thumb against 1.23 g possible at 60 km/h). The race fronts now hold their peak to 12° (`front_grip_tail`,
  `VehicleDesc.FRONT_LAT_TAIL`); the rears keep Jolt's curve, so the rear lets go first, gently.
- Jolt combines a tyre's friction with the ground's as `sqrt(tyre x ground)`, so `tyre_grip` 1.34 only bought 13% over
  the road car, not 28%. Lateral grip is now set per axle (`lateral_grip` [front, rear], `VehicleDesc.FRICTION_LAT_REAR`)
  with that square root in mind. Longitudinal grip is unchanged.

More front downforce (44%) and a slower steering fade for this car were tried. Both made lifting off at 150 km/h snap
(29° and 18° of slide), so the downforce went to 40% and the steering fade stays the road cars' (`steer_fade_kmh` exists
for later). Jolt's vehicle models no camber or toe. Its limited-slip differential (ratio 1.4) does not act on a skid pad
(inner/outer wheel ratio ~1.09), and its anti-roll bars do not change the balance, because Jolt's grip is linear in
load. Road cars are unchanged (`RaceTorrelTest.theRoadCarsAreUnchanged`, `VehiclePhysicsTest`).

### Still to check on the S25

- How it feels with the thumb now that the front holds at full lock (owner's verdict on "a little faster").
- Its drawing cost next to the road cars.
- Whether the decals' 7 mm offset holds without flicker at a distance on the Adreno's depth buffer.
