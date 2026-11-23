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

## Numbers

These were measured on the 4-core x86 build server, not on the S25.

### Speed and handling

All five cars reach their top speed with the throttle held:

| Car | Top speed | Era target | 0-60 km/h | Braking from 50 km/h | Full-lock radius |
| --- | ---: | ---: | ---: | --- | ---: |
| 1928 | 97 km/h | 103 km/h | 10.6 s | 15.6 m, 2.4 s | 5.0 m |
| 1938 | 125 km/h | 132 km/h | 7.8 s | 13.5 m | 5.0 m |
| 1956 | 148 km/h | 153 km/h | 6.0 s | 12.6 m | 5.6 m |
| 1970 coupe | 189 km/h | 192 km/h | 4.5 s | 11.9 m | 8.0 m |
| 1972 pickup | 147 km/h | 153 km/h | 6.6 s | 12.8 m | 6.2 m |

The turning radii were measured at 19-27 km/h.

### Step time

| Case | Step time |
| --- | ---: |
| One car on one box (`physics_host_test`) | 0.015 ms |
| Five cars in the capital, 750-1750 streamed statics | 0.04-0.06 ms |

Streaming the town's shapes when the player gets in (about 750 bodies added at once) takes the time the test prints
("streaming: first ... ms"). Later restreams add and remove only the edge of the range.

**S25 estimate:** the Oryon cores are at least as fast per thread as this server's, so a step should be well under
0.1 ms: about 0.5% of an 8.3 ms frame at 120 Hz. This has not been measured on the phone yet.

### Size

`libtimerpg_physics.so` is about 2.3 MB on x86-64 (stripped, without symbols). The five cars' assets are 2.2 MB.

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

## Open problems

- **The near terrain in the game:** the physics and the streaming handle heightfield ground (tested on a slope), but the
  app does not yet give the car a `ground` function. Inside the town everything is flat, and the walk's edge wall still
  keeps the car on the town's ground. Driving out needs:
  - the orbit camera's height to follow the car (it looks at a fixed eye height now);
  - a rule for getting out off the town's ground (the walk has no terrain heights);
  - the terrain's heights read from `WorldNear`'s `WorldTerrain` in the town's frame.
- **Kerbs:** the town's kerbs are only painted on its flat ground, so there is nothing to climb yet. The cylinder-cast
  wheels will climb real kerb geometry once the ground has it.
- **Props are immovable:** a car stops dead against a hydrant or a mailbox. Knock-over props, as dynamic Jolt bodies,
  are the next physics step.
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
- **Physics beyond cars:** knock-over props, doors, loose objects; later, walking on Jolt's character controller.
- **Measurements on the S25:** step time, the cost of the streaming hitch, and the heat of a long drive.
