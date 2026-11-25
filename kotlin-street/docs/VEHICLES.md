# Vehicles

The player can get into a car and drive it round the capital. This is phase **V1**: Jolt Physics in the game, five of
ChatGPT's cars parked by era, getting in and out, and driving on the phone. CLAUDE.md says why we use Jolt rather than
switching engines: our world is procedural and code-driven, so the one thing another engine would give us is physics,
and Jolt brings that into the native layer we already have for llama.cpp.

## What is built (V1)

### Jolt in the native layer

- **Source:** Jolt Physics **v5.5.0**, commit `23dadd0e603f1b321142d4c74df07fce85064989` (December 2025), fetched as
  source by `tools/get-jolt.sh` into `kotlin-street/third_party/JoltPhysics`, which git ignores. To use another copy, set
  `jolt.dir=...` in `local.properties`. The build stops with a clear message if Jolt is missing.
- **Build:** `app/src/main/cpp/physics/CMakeLists.txt` builds a static Jolt plus our wrapper into one shared library,
  `libtimerpg_physics.so`:
  - **Phone:** the app's `CMakeLists.txt` adds the physics with `add_subdirectory`, for arm64-v8a. Jolt uses NEON on
    `__aarch64__` without any extra flags.
  - **Desktop:** the same file is a standalone CMake project, built as a Linux x86-64 JNI library by
    `gradle :desktop:physicsHost` into `desktop/build/physics`. The preview and the tests load it through
    `-Dtimerpg.physics.lib`.
  - **Host test:** `tools/physics-host-test.sh` builds the wrapper without the JVM (`physics_host_test.cpp`), following
    the `*_host_test.cpp` pattern of the language model.
- **Determinism:** Jolt's `CROSS_PLATFORM_DETERMINISTIC` is on. It turns off fused multiply-add and uses precise
  floating point, so the phone and the desktop run the same simulation. It costs a few percent of a step that already
  takes a fraction of a millisecond.
- **The wrapper:** `physics_core.h/.cpp` has no JNI in it. It provides:
  - a world (single-threaded job system, fixed step);
  - static colliders: boxes, upright cylinders and heightfields;
  - wheeled vehicles: Jolt's `WheeledVehicleController` built from the model-info numbers, with a compound of the
    delivery's chassis boxes, the centre of mass lowered, cylinder-cast wheels (so tyres roll up edges instead of a ray
    catching on them), anti-roll bars, an automatic gearbox and linear-cast motion (so fast cars do not pass through thin
    fences);
  - driver input (forward, right, brake, handbrake);
  - the body pose and each wheel's spin, steer, suspension and contact.
- **JNI:** `physics_jni.cpp` reads and writes arrays in place, so a frame allocates nothing on either side.

### Kotlin

- **`shared/.../physics/Physics.kt`:** the `Physics` interface, `JoltPhysics` (the JNI implementation for phone and
  desktop), and the index tables `VehicleDesc` and `VehicleState`, which must match `physics_core.h`. The library
  checks that its layout matches when it loads.
- **`vehicle/VehicleSpec.kt`:** reads a car's `model-info.json` (nodes, lamps, chassis boxes, palette, LODs, door angles)
  and decides its era with `VehicleEra`:
  - before the model's first year it does not exist;
  - up to 20 years after its last model year it is a working car;
  - after that it is a collector's classic.
- **`Drivetrain`:** the deliveries only give mass and an estimated top speed, so the game supplies the rest by era:
  - torque and the rev range;
  - a 3-speed gearbox (4-speed for the 1970 coupe);
  - brakes, steering lock, suspension, tyre grip, drag and the height of the centre of mass.

  The final drive is calculated so the engine reaches its rev limit in top gear exactly at the delivery's top speed.
  The car therefore really runs up to its era's top speed and no faster. All of these are estimates, not measurements.
- **`vehicle/VehicleSpots.kt`:** where cars can park:
  - at the kerb of every road, on the right of the traffic (the cars are American), away from junctions and from
    anything solid;
  - in driveways and parking pads, nose towards the house.
- **`vehicle/VehicleWorld.kt`:** runs the cars in the game.
  - **Parking:** parks the cars that fit the year. The 1956 sedan, if its era fits, goes on the spawn's street a few
    metres from the player. The others alternate between driveways and kerbs nearest the spawn, at least 18 m apart.
    Each car's colour comes from its delivery's palette, picked by the town's seed. While parked, a car's footprint is
    in the walk's collision, so the player walks round it.
    - Where they land in the capital in 2025 (measured by the test): the sedan at the Main Street kerb 2.3 m from the
      spawn (`street:60:0:4:-1`), the 1972 pickup 19.6 m and the 1928 car 20.0 m along Main Street, the 1970 coupe and
      the 1938 coupe on parking pads 26 and 37 m away.
    - The owner could not find them because the game starts where the player last stood (`TownSession.restore`), on
      the owner's phone about 1.3 km from the spawn. Now, once the town is loaded, the first car (the sedan) is moved to
      the clear kerb beside a player who starts more than 60 m from it (`requestFirstNearPlayer`); at the spawn it stays
      on Main Street.
  - **The test button 🚗** (behind the 📷/🔖 switch, right of the bike button): each tap spawns the next of the five
    cars, whatever the year, at the nearest kerb within 80 m whose whole footprint plus 0.3 m is clear of every solid
    shape and the other cars (`VehicleSpots.clear`, an exact box and circle test) and not on the player, facing along
    the kerb, replacing the one spawned last; the car's name shows for a moment. The spawn runs on the game loop's next
    step (`requestSpawn`); the car list is copy-on-write, as the renderer reads it on its own thread.
  - **Getting in and out:** within 1.7 m of `exit_left` or `exit_right` the HUD shows **Enter**. The driver's door
    swings open, the player is hidden in the seat and the door shuts. **Exit** works below 1.2 m/s: the handbrake goes
    on, the door opens, the player stands at `exit_left` (the walk finds the nearest clear spot) and the door shuts.
  - **The drive:**
    - The left stick steers. Steering is eased and reduced at speed, as a driver would.
    - The HUD's **Gas** and **Brake**, or the stick's up and down on a keyboard, drive the car. Holding brake (or down)
      at a stop reverses; pressing gas while rolling backwards brakes first.
    - **Hand** holds the handbrake.
    - Brake, reverse and turn-signal states follow the controls.
  - **Collision streaming:** the town's solid shapes (`TownCollision.forEachNear`: buildings 8 m tall, fences 1.3 m,
    hedges, walls, yard objects, tree trunks as cylinders, props, the river's edge, parapets) are added as static
    bodies within 110 m of the driven car. They are updated after it moves 25 m or when the town adds shapes (a store is
    built), and dropped beyond that range. Parked cars are not static shapes: each one is its own Jolt vehicle, asleep
    on its springs. The town's flat ground is one big box.
  - **Terrain:** heightfield chunks (64 m, 33 samples) are made round the car from a `ground` function where one is
    given. The edge walls are left out of the physics only then.
  - **The step:** a fixed 60 Hz step inside the frame budget, at most 4 steps a frame. The pose drawn is interpolated
    between the last two steps, which matters on the S25's 120 Hz display.
- **Hooks (kept small):**
  - `OpenWalk` can be carried by a car (`carry`, `release`; its `step` does nothing while carried).
  - `TownPlay` holds `cars`, steps them with the walk, hides the player's figure while carried and turns off the bike
    while in a car.
  - `TownRun`'s orbit camera uses the bike's follow for the car: it follows behind by default; touching the camera gives
    full manual control for 3 s, then the follow eases back in over 1.2 s. The "Camera follows" setting covers both.
    In a car the camera stands half a bike-step further back.
  - The app creates the cars in `TownSession`, attaches `TownCars` in `FilamentLayer`, and adds `DriveHud` in
    `GameActivity`, which also hides the bike button while in a car.

### Drawing (Filament)

`scene3d/VehicleKit.kt` reads a car's `.glb` with the landmarks' `GlbFile` reader, into the same lit material, so the
cars are lit and shadowed like the town: nearest-filtered pixel art on simple geometry.

- **Moving parts:** the model is split into parts that move, each baked in its own node's frame:
  - wheels (under their steering pivots);
  - doors, hood, trunk and tailgate;
  - the steering wheel;
  - each lamp's lens, so every lamp can light on its own.

  Everything else is one static body, merged by material.
- **Paint:** the `paint_primary` picture is recoloured to the car's colour, keeping its own shading.
- **Glass:** made a dark tinted solid. Our material does not blend; see "What's next".

`scene3d/TownCars.kt` is a `TownExtra`:

- It builds each car on the town's workers and uploads it into a layer of its own.
- Every frame it places the body from the physics pose and each part from its node chain:
  - wheels spin and rise with the suspension;
  - the front pivots steer;
  - doors swing by the delivery's preview angles;
  - the steering wheel turns.
- **Lights:**
  - **Headlights:** on after dusk on the driven car, both the lens glow and two real Filament spot lights on the road.
  - **Tail lights:** on with the headlights.
  - **Brake lights:** when braking, and dim with the tail lights.
  - **Reverse lights:** when reversing.
  - **Turn signals:** blink at 1.5 Hz while steering hard at low speed.
- **Distance models:** past the delivery's suggested distances, the mid model (38 m) and then the far model (95 m)
  take over, as static models.

## Assets

The cars were copied with `git show`, never merged, into `app/src/main/assets/vehicles/<key>/`: the near, mid and far
GLBs, the paint masks, and a `model-info.json` holding that car's entry from its delivery's file (with the file's
format, origin and texel header).

| Car | Key | Years | Mass | Top speed (est.) | From |
| --- | --- | --- | ---: | ---: | --- |
| Alderwick Six | alderwick-1928 | 1927-1930 | 1280 kg | 103 km/h | C011 `origin/codex/C011-cars-early` 3ed5f14 |
| Fenmere Club Coupe | fenmere-1938 | 1937-1940 | 1465 kg | 132 km/h | C011 |
| Northcairn Terrace | northcairn-1956 | 1955-1957 | 1685 kg | 153 km/h | C011 |
| Rowanvale Sprint (coupe) | rowanvale-1970 | 1968-1974 | see info | 192 km/h | C012 `origin/codex/C012-cars-late` c17bf35 |
| Tallmere 72 Pickup | tallmere-1972 | 1968-1976 | 1780 kg | 153 km/h | C012 |

The C012 Stoneweir Wayfarer is left out while it is in revision. The Pinelatch City 3 is not in V1.

The capital (gamma) is set in 2025, so all five cars appear there as classics. In 1950 only the 1928 and 1938 cars
appear, as working cars; in 1920 there are none.

## Parked in the lots (2026-11-25)

The cars in the stores' car parks were boxes until 2026-11-25. So were the movie theater's lot and a general store's
truck on its drive. They are now our real cars (`LotCars`). The owner had asked to "replace all fake cars in our models
with real cars".

- Places: the lots keep their own logic for where cars stand. `StoreCars.spots` and `StoreTraffic` decide by the hour
  and the day which bays fill. `CinemaGrounds` lays out the theater's rows. `StoreGrounds` puts the owner's truck on
  the drive.
- Models: each place gets a model of the town's year (`LotCars.pick`, weighted by `VehicleEra`). A model in its working
  years weighs 1. A classic weighs less the longer ago its working life ended, and nothing more than 35 years past that
  shows up. The race build is never parked. In 2025 the late-80s Torrel is the commonest; in 1958 only the 1938 and
  1956 cars and a rare 1928.
- Colours: one of the delivery's preset colours, seeded.
- Static: each car is drawn from its mid or far .glb, whole. No physics, and its lamps stay unlit.
- Sharing: a model in a colour is built once and shared. A lot's cars are packed into one batch per model, colour and
  material.
- Level of detail by distance: the far models are always up. The mid models are made only when the player comes within
  ~90 m of the lot's edge, shown within ~45 m, and let go past ~140 m (`TownStore.CAR_MID`, `MID_LEAD`, `MID_DROP`).
  The theater's few cars keep both models up.
- Collision: the walk's and the cars' solid shapes still come from the old boxes at the same places.
- Fallback: without `LotCars.files` (set by `TownCars.attach`; the open preview sets it, `-DlotCars=0` to compare)
  the boxes are drawn as before.

The cost, measured on the build server (`LotCarsTest`): gamma's superstore on its busiest hour, Saturday 3 pm, parks
159 cars.

| Models | Triangles | Batches | Size |
| --- | ---: | ---: | ---: |
| Mid | 160,026 | 120 | 10.7 MB |
| Far | 22,110 | 73 | 1.4 MB |
| Old boxes (before) | 9,540 | 1 | |

The frame cost on the S25 near a full lot is not yet measured.

## Numbers

These were measured on the 4-core x86 build server, not on the S25.

### Speed and handling

All five cars reach their top speed with the throttle held:

| Car | Top speed | Era target | 0-60 km/h | Braking from 50 km/h | Full-lock radius |
| --- | ---: | ---: | ---: | --- | ---: |
| 1928 | 97 km/h | 103 km/h | 9.5 s | 15.6 m, 2.4 s | 5.0 m |
| 1938 | 125 km/h | 132 km/h | 6.9 s | 13.5 m | 5.0 m |
| 1956 | 148 km/h | 153 km/h | 5.2 s | 12.6 m | 5.6 m |
| 1970 coupe | 189 km/h | 192 km/h | 3.3 s | 11.9 m | 8.0 m |
| 1972 pickup | 147 km/h | 153 km/h | 5.0 s | 12.8 m | 6.2 m |
| Torrel 1968 | 175 km/h | 188 km/h | 5.1 s | | 4.7 m |
| Torrel 1988 | 216 km/h | 224 km/h | 4.3 s | | 5.0 m |
| Torrel 1988 RS (race build, C046) | 273 km/h | 278 km/h | | 100-0: 35.0 m | |

The race Torrel, its downforce and the car button's list are described in [CARS.md](CARS.md).

### The gearbox (2026-11-25)

The 0-60 times above dropped by 0.5-1.2 s on 2026-11-25. Jolt's automatic does not shift up while a driven wheel slips
more than 10%. Flat out, every car spun its tyres in the low gears, so the engine sat on its limiter for 0.2-2.1 s
before each shift (`ShiftPointTest`, before: the 1956 sedan held 4,400 rpm for 2.1 s in second). That is what the owner
heard as the engine "topped out before it shifts".

The upshifts are now the physics' own (`stepGearbox` in physics_core.cpp; Jolt still makes the downshifts and the first
gear from a stop). In a forward gear, with the driven wheels on the ground and Jolt's box idle, the car shifts up past
`SHIFT_UP_RPM` (72% of the governed speed, coasting) or up to `SHIFT_UP_FULL_RPM` (92%, flat out), scaled by the
throttle. The clutch stays in, and the throttle is cut and eased back in over `SHIFT_TIME`: 0.4 s before 1950, 0.3 s
to 1979, 0.2 s after, 0.15 s for the race build. Flat out, every car now shifts at 92% and spends no time at its
limiter before a shift. Top speeds are unchanged.

The Torrels (976 and 1,088 kg two-seaters) once had the 1970 coupe's 560 Nm drivetrain: on a quarter throttle the
one-tonne car spun up to 46 km/h and slid wide at full lock, a 23 m circle. They now have their own: a small
high-revving engine (200 and 260 Nm, 6,800-7,000 rpm, four and five gears), a 31° lock and a low centre of mass. Their
driver's door is `door_l` (a two-door's), found by `VehicleSpec.driverDoor`.

The turning radii were measured at 19-27 km/h.

### Step time

| Case | Step time |
| --- | ---: |
| One car on one box (`physics_host_test`) | 0.015 ms |
| Five cars in the capital, 750-1750 streamed statics | 0.04-0.06 ms (0.11 ms on one busy run) |

Streaming the town's shapes when the player gets in (about 750 bodies added at once) took **9.7 ms** on this server.
That is a one-frame hitch as the player gets in, while the door is opening. Later restreams add and remove only the
edge of the range. It is the first thing to spread over frames, or to move to a worker, if the S25 shows it.

**S25 estimate:** the Oryon cores are at least as fast per thread as this server's, so a step should be well under
0.1 ms: about 0.5% of an 8.3 ms frame at 120 Hz. This has not been measured on the phone yet.

### Size

`libtimerpg_physics.so` is 1.19 MB in the arm64 APK and 2.3 MB on x86-64. The five cars' assets are 2.2 MB.

## Tests

`gradle :desktop:crossroadsTest --tests VehiclePhysicsTest` loads the same JNI library and checks:

- all five cars parse (wheels, steering pivots, a driver's door, seat and exit, chassis boxes, lamps, palette) and get
  the right era;
- each car runs up to between 90% and 104% of its era's top speed;
- each stops from 50 km/h in under 45 m;
- full lock gives a radius between 3.5 and 10 m, and right is right;
- a car stops against a wall without passing into it, and stays upright;
- reverse works;
- the wheels turn by distance divided by radius (within 5%) while coasting;
- a car climbs a 6% heightfield slope and stays on the ground;
- in the capital:
  - the cars park by year (2025: all five as classics, the sedan near the spawn; 1950: two working cars; 1920: none),
    and each parked car blocks the walker and stays put on its springs;
  - getting in, driving and getting out works: the door opens and shuts, the walk is carried, shapes are streamed,
    the player cannot get out at speed, stands at the exit afterwards, and the car blocks the walker again;
  - the real `OpenWalk` is carried and stood on standable ground;
  - a car driven into the town's buildings never ends up inside one;
- kerbside spots lie on the road.

Previews: `gradle :desktop:preview -Dmode=filament-open -Dcars=1 [-DcarYear=1956] [-Dw=1280]` renders each parked car,
getting in with the door open, the sedan driving down Main Street from the follow camera, and the sedan at night with
its headlamps and brake lamps. They are written to `desktop/build/preview/filament-cars/`. These are desktop renders
(Mesa software GL), not S25 captures.

## The ground beyond the town, and the safety net (2026-11-23)

The owner drove through trees into the open field past the town and the car sped up to ~300 mph and was lost: the app
built `VehicleWorld` with no `ground` and no `flat`, so the only floor was a box under the collision map's bounds and
past it the car free-fell forever (Jolt has no drag), which read as runaway acceleration.

- **Real ground.** `TownSession` passes `VehicleWorld.start(..., ground, flat)`. `flat` is `TownGround.boundsOf(town)`, the
  same rectangle the renderer leaves to the town's grass (the flat box's top is y 0, the town's ground). `ground` is
  `TownPlay.groundFor(site)`: the terrain's `WorldTerrain.heightLocal` (the very function `WorldFar`/`WorldNear` build
  the drawn ground from), in physics metres converted through the hub frame (street units / `unitsPerMetre`, plus the
  plan origin's offset from the frame's). The terrain is made on a worker, so `WorldFar.whenTerrain` hands it to
  `TownPlay.terrain` when ready and calls `VehicleWorld.groundChanged()`; until then the lambda returns null (the town's
  edge stays a wall and the flat box the only floor), then the shapes and chunks are streamed again with the terrain.
  `streamGround` no longer remembers a chunk the terrain could not give, so it is tried again at the next restream, and
  it runs without a collision map too. Beyond the box the drawn terrain is 5 cm under the town's ground for 150 m, then
  rises (WorldFarPlan.UNDER_M, FLAT_M): the car drops 5 cm leaving the flat box, as the picture does.
- **Safety net (always on).** After each batch of physics steps `VehicleWorld.watch` looks at every car: one that is
  more than 6 m below the floor under it (the flat 0, or the terrain), faster than 85 m/s, or has a NaN state is put back
  (`rescue`): teleported (`Physics.place`) to a pose it held on the ground (>= 3 wheels in contact, below 70 m/s, one
  recorded every 0.5 s, the 8 latest kept; the rescue takes the one 1 s back, since the newest may be the slip), upright,
  at rest, facing as it did, steering reset; with no pose yet it goes to where it was parked. Logged through
  `VehicleWorld.onEvent` (the app writes it to the Crumbs and the PlayLog as `car/rescue`): "car: rescued from a fall of
  6 m below the ground at ..., put back at ...".
- **A way out.** `VehicleWorld.resetDriven()` does the same on demand (at the next step). The drive HUD has a ⟲ button
  beside Exit (the 🚗 test button is hidden while driving), with a note "Car put back on the road".
- Tests (`VehiclePhysicsTest`): a car driven off a flat box with no terrain is rescued once and ends on the box near its
  last good spot; with terrain past the edge it stays on it (within 2 cm of the function) at a sane speed; a NaN state is
  rescued. Not measured on the S25; the heights were checked in desktop physics tests against the same function, not
  against a render.

## Destruction (2026-11-23)

The owner: "when you hit a fence with your car, it shouldn't stop the car if it's like a wooden fence... we need to be
able to do some destruction with our cars because it's annoying getting stuck on every little thing we run into."

**What gives, and how** (`vehicle/Wreckage.kt`, `WreckMaterial.of`, by the shape's `SolidKind` and its
`TownCollision.detail`: a yard item's `kind/variant/zone`, `lamp`, `streetBench`, `plazaBench`, a `PropKind` name):

| Give | What | Breaks at (car mass x speed into it) |
| --- | --- | --- |
| **Break** into panels | picket (front) fences, board and lap fences, chain-link, post-and-rail, garden gates, the farms' field fences; hedges (a gap torn through them); raised vegetable beds (a stretch at a time) | 2,200-3,800 N s (wooden fences 2,700-3,000): the 976 kg Torrel goes through a picket or board fence from about 3 m/s (7 mph), the 1,685 kg sedan from 1.7-1.8 m/s; the heaviest car (1,780 kg) nudging at 1.4 m/s is stopped |
| **Break** whole | mailboxes, bins, chairs, tables, benches, grills, birdbaths, planters, doghouses, pallets, compost bins, trampolines; street benches, wheelie and litter bins, news boxes, the mail collection box, utility cabinets, cones | 150 N s (a cone) to 7,000 N s (a utility cabinet) |
| **Bend** (snap at the foot, fall over) | street lamps, sign posts, parking meters, fire alarm posts, bike racks, young trees in guards, fruit trees, washing-line posts, hydrants | 2,600 N s (a sign) to 14,000 N s (a hydrant: about 8 m/s) |
| **Soft** (drive through, slowed, it springs back) | bushes (the yards' and the woods') | always: a drag of 1.6/s while inside |
| **Solid** | buildings, low brick and stone walls, bollards, big trees (yard, street and forest), the river's banks, bridge parapets, the world's edge, planters on the plaza, the fountain, store sites' and the cinema's fences (not drawable broken yet) | never |

Below its impulse a breakable or bendable thing is as solid as a wall: a slow nudge stops the car, as in real life. A car's
approach speed counts at least half its speed over the ground, whichever way it meets the thing: a car catching a fence's
end with its corner, or scraping along it, tears it rather than stopping dead.

Every fence and hedge in the capital, by `DestructionTest.everyFenceInTheCapitalIsClassed` (gamma's yards and lanes, the
farms' 490 field fence runs, the store lots, the cinema): picket fences (frontFence v0-2) 2,800 N s; board fences
(sideFence v0-2, backFence v1) and lap fences (backFence v0) 3,000; chain-link (backFence v2) 3,800; post-and-rail
(postRail v0-2) 2,700; garden gates and driveway gates (gate v0-2, sideGate v0-2 and v5-7) 2,700; the farms' fields'
post-and-rail fences (`FarmSolids.DETAIL`, "farm fence") 2,700; hedges 2,600; raised beds 2,200. Only the cinema's three
rails stay solid. (Up to 0.1.715 the farms' fences had no detail and stopped a car at any speed: the S25 report 163153.)

**Physics** (`app/src/main/cpp/physics`):

- A contact listener (`Breaker` in `physics_core.cpp`) sees every contact of a dynamic body with a static that has a
  *give* (`setGive(handle, impulse, soft)`; the handle is in the body's user data). Above the impulse (the dynamic
  body's mass times its approach speed along the contact normal) the contact is made a **sensor** (no response, also
  for the cars' linear-cast CCD), the static is marked broken (every later contact with it is a sensor too) and a break
  is recorded in a fixed buffer of 64 (handle, contact point, the body's velocity, speed, mass, vehicle). A soft static
  is always a sensor and the body is slowed after the step. Nothing allocates per step.
- New layers: `DEBRIS` (loose pieces: they meet the statics, the cars' chassis and each other) and `WHEEL` (the wheels'
  ground casts: the statics and the cars, not the debris, so a wheel never climbs a fallen board and launches the car;
  the chassis pushes pieces, and a board lying flat passes under it).
- **Debris never rides along with a car** (`Breaker::debrisOnCar`; the S25 report 163021: a driveway's pair of white
  gates, spawned across the low Torrel's body, rode with it for a kilometre): a piece meeting a car is a sensor (the car
  passes through it) when it lies lower than 0.3 m over the car's ground (the car rides over it), when the two overlap
  deeper than 0.1 m (a piece inside the body, which pushing out would wedge), or once they have touched for 40 steps on
  end (0.67 s: it is being pushed along or dragged), until they have been apart 3 steps; their friction is a car's paint
  (0.15), so nothing is dragged along by it.
- New calls (JNI kept small): `step` returns how many breaks wait; `setGive`, `takeBreaks` (one array), `addDebris` (one
  array of 18 floats: a dynamic box, its mass, velocity and spin; linear-cast, damped so it settles), `bodyPoses` (all
  live pieces in one call a step) and `addVehicleVelocity`.

**What happens at a break** (`Wreckage.onBreaks`, after the step that saw it):

- The static is taken out of the physics, and loose pieces are put in its place with real masses: a fence's stretch
  within the car's width (wider for a glancing hit, at most 9 m) round where the car's middle crosses the fence's line
  (not round the contact, which may be its corner) becomes its panels, snapped to the fence's own bays (1.9-2.4 m, 9-17
  kg a metre), each as thin as its boards (7 cm: lying flat it is under any car's sills); a hedge's gap becomes clumps; a
  bin or a bench is one piece; a gate its leaf, as thin; a lamp post is one tall piece with its foot pushed on and its
  top tipping away from the car. No piece is put down inside the car that broke it: it is moved out of the car's body
  (where it will be after the step, 6 cm clear) the shortest way, up to 1.2 m; one across the whole car is left to the
  physics, which lets it through.
- **The farms' fences** (`Wreckage.breakRun`): a field's run is split like a yard's post-and-rail (bays of 46 units, half
  the posts' spacing), the run taken out of the farms' set of shapes in the walk's map (`TownCollision.removeExtraBox`)
  and what is left either side put back, still breakable; the broken stretch goes into `Wreckage.cuts`, and `TownFarms`
  builds that farm's fences again (their own batch) without it, a post at each broken end.
- **Momentum exchange:** pieces in the car's path get the car's velocity (x1.06-1.18, a little lift and a tumble), those
  at the edge of the gap less; the car loses the momentum they took (`addVehicleVelocity`) plus what breaking cost (4-18%
  of its speed into the thing; never more than half its speed). The pieces then go on colliding with the car normally.
- **The walk's map:** the shape is taken out of `TownCollision` (`remove(i)`, queries skip it; or a remnant's extra
  dropped) and what is left of a fence either side goes back in as shorter fences (`setExtra("broken:N")`, still
  breakable). A piece that settles standing up (taller than 0.5 m) blocks the walker (`debris:N`); one lying flat does
  not. Broken stays broken for the session.
- **Cap:** at most 200 pieces live in the physics (the oldest is frozen: out of the physics, drawn where it lies);
  asleep pieces farther than the streaming range from the car are frozen too (the terrain under them may go); at most
  600 are kept drawn.
- **Events:** each break is a `DamageEvent` (what, kind, detail, owner: the yard's building or the row, where, when,
  which car, how fast) in `Wreckage.events`, and a line "damage: a picket fence (b163) at (x, z) by northcairn-1956 at
  8.4 m/s, t 12.0 s" through `VehicleWorld.onEvent` (the app's crumbs and play log), ready for the crime system.

**Drawing** (`scene3d/TownWreck.kt`, attached with `TownCars`):

- The yard is baked again without what broke (`FilamentTown.replaceYard` on the workers, then `pollYards` swaps the
  lot's patches: near, far and its tall things', their water's shimmer with them), the fence's remnants as shorter
  fences of the same kind.
- A street lamp, bench or prop is taken out of its chunk's batch (`FilamentTown.hideProp`: the batch is packed again
  without it; the batches keep what they were packed from), and a lamp's lantern light goes out.
- Each piece is drawn with its original's own pixel-art mesh, the yard kit's fence of one panel's length, its bin, its
  bench, the town's lamp, at the physics' pose each frame: M = T(p) R(q q0⁻¹) T(0, -hy, 0), the mesh made as it stood.
  A piece whose original cannot be found is a plain wooden box of its size.

**Tests** (`DestructionTest`, the same JNI library): the materials; at 15 m/s through a picket fence it breaks (2
panels), the car keeps 91% of its speed, one damage event, the gap is open to the walker and the ends still block, the
yard's fence is two shorter ones, the pieces sleep within 1.8 s and lie on the ground near the gap; at 1.5 m/s the fence
stops the car (no break); a building stops it at 16 m/s; a lamp post at 12 m/s snaps and lies flat, the walker is free
there, the renderer is told; with the cap lowered to 3, five bins leave 3 live and 5 drawn, the oldest frozen.
Measured on the build server, not the S25.

The S25 reports of 0.1.715 (`DestructionTest`): `debrisNeverRidesAlongWithTheCar` (the Torrel through a driveway's pair
of gates, a garden gate, board and picket fences, square and at 30-55 degrees at 10.4 m/s, stopped, then on at 5 mph over
where the pieces lie for 16 s: none moves with it), `aLooseBoardIsNeverCarriedAlong` (a board standing at the nose,
leaning on the bumper, lying half under the sills, beside it, and a 3.6 m gate pair put across the body: the Torrel and
the sedan drive 22 m on at 5 mph; a gate across the cabin was carried the whole way before the fix), `aBoardFencesPanels
FallWhenHit` (at 10.6 m/s every panel ends lying), `aFarmFenceBreaksAt11Mph` (4.9 m/s: through, the gap open to the
walker, the rest standing, one cut for the farms), `woodenFencesGiveFrom8Mph` (picket, board and post-and-rail at 3.6
m/s with the Torrel; the 1,780 kg Tallmere nudging a board fence at 1.4 m/s is stopped), `everyFenceInTheCapitalIsClassed`.

**Preview:** `gradle :desktop:preview -Dmode=filament-open -Dcars=smash [-Dw=1200]` drives the sedan through a picket
fence and into a street lamp in the capital (before, during, after each), then the Torrel through a farm field's fence at
11 mph and through a driveway's pair of gates (lined up on a post) at 10 m/s and on at 5 mph (`-Dcars=smash-fixes`: those
only), written to `desktop/build/preview/filament-smash/` with `smash.txt`. Desktop renders (Mesa software GL), not S25
captures.

**Not yet:** leaning without breaking (below the impulse a lamp is simply solid); the hedges' clumps are short hedge
blocks, not torn foliage; store and cinema fences stay solid; a break's sound and a camera thump; pieces are not saved
between sessions (a new game has the town whole again); the parked cars are not pushed by debris differently from any
body; walls and buildings take no damage.

## Open problems

- **The near terrain in the game:** the physics and the streaming handle heightfield ground (tested on a slope), but the
  app does not yet give the car a `ground` function. Inside the town everything is flat, and the walk's edge wall still
  keeps the car on the town's ground. Driving out needs:
  - the orbit camera's height to follow the car (it looks at a fixed eye height now);
  - a rule for getting out off the town's ground (the walk has no terrain heights);
  - the terrain's heights read from `WorldNear`'s `WorldTerrain` in the town's frame.
- **Kerbs:** the town's kerbs are only painted on its flat ground, so there is nothing to climb yet. The cylinder-cast
  wheels will climb real kerb geometry once the ground has it.
- **Glass is opaque** (dark tinted). Real transparency, with the cabin seen through the windows, needs a blended
  variant of our material.
- **Getting in has no animation:** the player vanishes as the door opens. A seated pose for the sheet (or a 3D driver)
  is still to come.
- **The camera** jumps about a metre to the car's middle as the player gets in.
- **"Talk to" chips** may still show while driving past someone.
- **No sound** for the cars yet: an engine by rpm, tyres and doors.
- **Tuning has not been tried on the phone:** the feel of the steering, the pedals and the camera needs the owner's
  hands on the S25.

## What's next

- **Traffic:** cars driven by the simulation along the road graph (lanes, junctions, rules of the road by era), sleeping
  far from the player and promoted to Jolt vehicles near them.
- **More vehicles:** the rest of ChatGPT's cars (C012's compact; the Stoneweir Wayfarer once revised), trucks and buses
  by era.
- **Motorcycles:** Jolt has a `MotorcycleController` with lean.
- **Damage:** dents by impact (vertex offsets on the body), broken lamps, a smoking engine, wrecks.
- **Horses and carts** for the early eras: a different controller on the same physics.
- **Fuel:** a tank, consumption by rpm and load, gas stations by era (the landmark gas stations already price fuel by
  year).
- **Crime hooks** (the owner's direction: grounded crime with consequences through people):
  - stealing a car (breaking in, hot-wiring by era, the owner noticing and reporting it);
  - plates and registrations by era (a description the police can look for);
  - witnesses remembering a car and its colour;
  - speeding and reckless driving seen by police;
  - hiding a car, changing its plates or paint.
- **Physics beyond cars:** doors, loose objects the player can push; later, walking on Jolt's character controller.
- **Measurements on the S25:** step time, the cost of the streaming hitch, and the heat of a long drive.
