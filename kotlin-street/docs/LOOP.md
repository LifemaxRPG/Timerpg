# The first playable loop

The owner's decision (2026-11-23), on Sam's proposal, with Claude's agreement: before the planet fills with towns,
prove the game is fun in **a few blocks of the capital**. Planet-wide towns (TOWNS_EVERYWHERE.md) and the organic
street upgrade wait until this loop is playable on the S25 and the owner has played it.

## The loop, as the player lives it

1. **Arrive** in the capital with a little era cash in the wallet.
2. **Earn money**, either way:
   - legit: an odd job at a shop (a shift of sweeping or stocking or deliveries, paid at closing), or selling
     something you own at the pawn shop;
   - not legit: shoplift from a store, lift a wallet, take something from a yard or an unlocked house, take a car.
3. **Get noticed.** Anyone who could see you remembers what they saw (Jeff's witness core, G005): who, when, a
   description. Overnight, word spreads by traits and relationships (gossip); someone who would report it does,
   with the era's delay (G005 reporting).
4. **The town reacts**, and you can trace it to what you did:
   - the shopkeeper you robbed won't serve you, or watches you;
   - people mention it when you talk to them (the facts go into the Qwen fact sheets);
   - the police know: an officer patrols near the store, asks around, may recognise you and stop you (G009);
   - caught: arrested, fined or jailed for a night (the full trial, G010, comes later);
   - leave for a while and it cools down; leave the jurisdiction and you're not known there.
5. **Come back later** (sleep, or a day away) and the consequences are still there.

**Done means:** the owner plays it on the S25 for half an hour, earns money both ways, commits a crime someone saw,
and later meets a consequence they can trace back to it.

## How it is built

- **One source of truth for the rules.** The crime core, evidence, plans and police are TypeScript (`src/crime/`,
  `src/plan/`, `src/police/`). The phone runs that same code, bundled by esbuild into `assets/sim/sim.js`, in an
  embedded JavaScript engine (QuickJS-ng, in the native layer beside llama.cpp and Jolt). No Kotlin port to drift
  out of step. The desktop preview runs the same bundle the same way.
- **The game-facing API** (`src/loop/`, the facade the phone calls, JSON in and out):
  - `init(town, era, seed)`: the people, their homes, relationships, the jurisdictions;
  - `act(event)`: the player did something (bought, sold, worked a shift, took an item, was seen at a place);
  - `observe(sightings)`: who saw the player and how well (the renderer supplies line of sight, distance, light);
  - `advance(hours)`: gossip, reports, police plans, cooling off;
  - `query(...)`: what a person knows and thinks of the player (for dialogue and for shopkeepers), what the police
    know, active police plans to act out.
- **Kotlin owns what's physical:** the wallet and inventory, the interactions (buy, sell, take, work), line of sight,
  moving the officers through the streets, the arrest scene, saving.
- **Heat and battery:** the sim runs on its own thread in bursts (after an action, on the hour, while idle); nothing
  per frame.

## The JavaScript bridge

How the TypeScript reaches the phone (built 2026-11-23):

- **Engine:** QuickJS-ng v0.18.0, fetched by `tools/get-quickjs.sh` into `third_party/quickjs-ng` (git-ignored, commit
  verified; or `quickjs.dir=` in local.properties, as `jolt.dir`). Its four C sources are compiled by
  `app/src/main/cpp/simjs/CMakeLists.txt` into one library, `libtimerpg_simjs.so`, with our JNI wrapper `sim_js.cpp`:
  one runtime and one context per instance, a memory limit and a stack limit, evaluate a script, call a global
  function with one string and get a string back. Text crosses as real UTF-8 bytes. A JS error (or running out of
  memory) arrives in Kotlin as `SimJsException` with the message and the JS stack.
- **Android:** the app's CMakeLists adds `simjs/` beside `physics/`; the APK ships it for arm64-v8a.
  **Desktop:** `gradle :desktop:simjsHost` builds the same CMake project for this computer into
  `desktop/build/simjs` (the way the physics is built), and the tests load it by `-Dtimerpg.simjs.lib`.
- **Kotlin:** `shared/src/main/kotlin/simjs/SimJs.kt`: `SimJs` (`load(bundle)`, `call(fn, json)`, `close()`,
  `memoryUsed`) and `SimJsThread`, which keeps one `SimJs` on a thread of its own and returns futures, so the game
  never calls it from the render thread.
- **Bundle:** `node kotlin-street/tools/bundle-sim.mjs` (the repo's esbuild) bundles `src/loop/bridge.ts` and all it
  imports into `app/src/main/assets/sim/sim.js`, an IIFE that sets the API on `globalThis`. It bundles for the
  `neutral` platform, so an import of a Node built-in fails, and checks the output for Node globals (`require`,
  `process`, `Buffer`). Re-run it after changing anything under `src/crime`, `src/plan`, `src/police` or `src/loop`;
  the bundle is committed, as the APK ships it.
- **The API for now is temporary:** until the facade (`src/loop/index.ts`, G011) arrives, `bridge.ts` exposes a smoke
  API over the crime core (`simCreate`, `simRecordCrime`, `simAdvance`, `simKnowers`, JSON in and out, the state
  held in the JS context). G011 replaces it with init / act / observe / advance / query.
- **Test:** `SimJsTest` (`gradle :desktop:crossroadsTest --tests SimJsTest`) has `tools/sim-smoke.mjs` run a fixed
  sequence of calls on the bundle in Node (a robbery with a victim and five bystanders, then three days; then a
  300-person town for timing), replays the same calls in QuickJS through `SimJs`, and expects the same answers. It
  also checks that errors carry their stack, that UTF-8 survives, and that the memory limit holds.
- **Not bit-identical to Node:** 40 of 42 answers match byte for byte; two differ in the last digit of a float,
  because QuickJS takes `Math.exp`/`pow`/`log` from the C library and V8 from its own port. The test allows 1e-12. The
  phone's C library (bionic) may differ from the desktop's (glibc) the same way, so a saved game is replayed from its
  state, not re-simulated from its inputs, and a chance should never sit exactly on a threshold. If exact agreement
  across devices is ever needed, the fix is to give QuickJS one portable libm (e.g. musl's or fdlibm) on both.
- **Timing** (this 4-core x86 server, not the S25): one `advanceDay` of the smoke API (one robbery, 20 bystanders,
  word spreading to about 120 people) took 0.9-1.2 ms on average in QuickJS against 0.5-0.9 ms in Node, for a 300-person
  town; 1.8 ms against 1.0 ms for 3,000 people; the JS heap held 0.9 MB and 3.4 MB. This workload is light. Measure
  again with the real facade (many crimes, police plans) and on the S25.

## The blocks

The capital's civic centre and Main Street, with:
- a general store and a market (shoplifting, buying);
- the pawn shop (C010) on a side street, the police station (C009) near the courthouse: both placed in this intake;
- homes with yards on the next street (taking from yards, a house left unlocked);
- the drivable cars (C011–C013, C017), for taking a car.

## Who does what

- **Claude:** the JavaScript bridge and its tests, the Kotlin wallet/inventory/interactions and line of sight, the
  arrest scene, wiring it together, playtests and the APK.
- **Jeff:** G009 police in action, then G011, the `src/loop/` facade over his crime, evidence, plan and police code.
- **Sam:** the models the blocks still need (the gas station, C015, for the drive in; interior props for the shops).

## Open, for the owner when it's playable

- How harsh the first consequences should be (a fine and a ban from the shop, or a night in a cell).
- What the odd jobs should be.
