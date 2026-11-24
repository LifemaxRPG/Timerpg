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

## Wired into the game (2026-11-23)

Every step below runs through Jeff's facade (`src/loop`, bundled as `assets/sim/sim.js`) in QuickJS; Kotlin only
supplies the physical scene and keeps the wallet. Code: `shared/src/main/kotlin/loop/` (platform-free, tested on the
desktop) and `app/src/main/kotlin/LoopSession.kt` (the game's side), `LoopHud.kt` (placeholder UI).

**Wired:**
- **The sim starts with the town** (`LoopSession.start`, on its own `SimJsThread`): `LoopTown.build` makes `init` from
  gamma's own `street.json` and `dialogue.json`: the 183 people (ids, ages, sexes, warmth as charm, homes, workplaces;
  spouses and children parsed from their family facts; coworkers, housemates and next-door neighbours as friends), 63
  places (the general store's site, the open market-kind store, the pawn shop `glb:pawn_shop`, the police station
  `glb:police_station`, the 11 small shops that have an owner, the homes), the street graph (the town's 96 junctions and
  132 roads, plus the station's door), 1,113 street lamps, the era (2025) and the game's clock. Positions go across in
  metres (street units / `VehicleWorld.U`). A save comes back instead when there is one: `files/loop/<seed>.json`
  (the facade's `save()`, the wallet and the inventory), written with the town's own save (`TownSession.save`, on
  pause) and after each action.
- **The clock in bursts:** `advance` on each whole in-game hour crossed (the time slider forward, a train's arrival),
  and by the hours of a shift, "Wait 1 h", "Sleep till 7" and a night in the cell; before each action the sim is
  brought to the minute. Never per frame; all on the sim's thread. A clock set back (the debug slider) is not followed.
- **Wallet and inventory:** the wallet is **integer cents** of the town's year. Money comes from the era economy
  (`src/economy`, docs/ECONOMY.md), exposed through the same bundle as `simCall({"method":"economy.<name>","args":[...]})`
  (`Economy` in `src/loop/bridge.ts`: priceIn, priceIndex, stockOf, wage, pawnOffer, pawnPrice, fenceOffer,
  formatMoney) and read in `LoopGame` (sim thread only; cached, never per frame): starting cash is $60 of 2025 money
  times `priceIndex(year)` (6000 cents in 2025, at least 200); every amount shown goes through `formatMoney` ("23¢",
  "$29.95"), made into text on the sim thread and handed to the UI in `LoopView` (`cashText`, `stock`, `pawnOffers`,
  `pawnOfferTexts`). Items carry their value in cents, serial numbers for valuables (the economy's `serial` flag) and
  whether they are hot (stolen, with the crime id); the cash readout is top left (orange when carrying something hot).
  The facade itself takes whole dollars (item `value`, shift `pay`, `fineAmount`, `playerCash`): Kotlin converts (cents
  rounded to dollars going in; a fine, a 2025-dollar number, is scaled by the price index into era cents coming out).
- **The shops** (shelves: `stockOf(econKind(shopKind), year, "<town>|<place>|d<day>")`, so a shop's stock is fixed within a day and
  changes the next; `LoopItems.econKind` maps general/antiques(pawn)/market, bakery and butcher(market), toy and music
  (five_and_dime), clothing and dress (clothing), bait (hardware), ice cream (diner)): standing at a loop place's door or inside its room, a "🛒" chip opens its panel: **Buy** (pays the economy's price, gets
  the item; `act buy`), **Take** (shoplift: `act take`, no payment), **Sell** at the pawn shop (`pawnOffer(item, year, hot)`: hot is half the clean offer, 0 when they won't
  take it: `act sell`), **Work a shift** (up to 4 h, not past closing, paid `hours x wage(job, year).hourlyCents`, `shop_clerk` at counters and `odd_jobs` for crates,
  back rooms and cases (`LoopItems.job`), at the end: `act work_shift`,
  then `advance`). Each sends the scene: inside a room, the keeper at their counter facing the way in (so the player
  behind them is not seen) and up to two more staff; outdoors, the people the game draws within 40 m (the keepers at
  their doors and the passers), their positions, facing, standing or walking, and line of sight by a ray against the
  walk's collision (buildings, walls and hedges block; fences and props don't); the room's light, or outdoors the
  facade's own light from the hour and the lamps.
- **Consequences:** `attitude(keeper)` before serving: `refuse` (and `report_on_sight`) blocks buying, selling and
  working with a line saying why ("Mildred won't serve you. (Mildred saw you steal a brass pocket watch ... on
  Monday)"), `wary` is shown beside the line. Every person's `facts` go into their dialogue fact sheet
  (`DialogueEngine.loopFacts`, inserted before the example answer in every prompt; someone who knows something answers
  live rather than from a line written ahead). The facts are cached per person and looked up again when `act` names
  them (witnesses, the clerk, the employer and coworkers), when `advance` reports their attitude changed (Jeff's idea),
  and, on a new day, for everyone cached (gossip can add to what a wary person knows without changing their stance).
- **Police:** the officers' plans (`policeView`'s officers) are walked along the town's roads on the pavement (Dijkstra
  over the roads between the plan's route nodes), there and back at 1.5 m/s in real time, drawn as townspeople
  (`TownPlay.extras`) with a "POLICE" label over their heads. Once a second an officer within 30 m with a clear line of
  sight calls `spotted` (at most every 20 s each): `watch` is shown; `approach_and_question` or `arrest` is the arrest
  scene: `arrest` with the cash and what the player carries; the fine is taken, stolen things confiscated, the hours
  held passed; a card says what happened and closing it stands the player at the station's door with the clock moved.
- **Debug:** the "Loop" chip under the location shows cash, inventory (hot, serials), the last act's witnesses (who and
  how confident, recognised, the victim), heat and open cases (reports, strength tier), each officer's assignment and
  reason, the last advance's attitude changes, new cases and pawn leads, the last sighting and arrest, and each facade
  call's time. Every loop event goes to PlayLog (`event("loop", name, detail JSON)`: init, load, act, attitude,
  advance, spotted, arrest), and `loop.start` / `loop.save` as spans.

**Placeholder (to replace):**
- The landmark stores have no owner in the export: a townsperson who "works from home" is made each one's clerk (the
  antiques dealer's, Broadmeadow's three, the pawn shop's); they stand at that shop's door as well as at home, and
  their dialogue still says they work from home.
- The street's passers are anonymous: each is taken for one of the town's people by its seed (`LoopTown.passerId`), so
  a witness has a memory and friends; the same person can be in two places at once.
- The officers only walk their routes: the plans' other steps (watch, ask, the stakeout's wait, a car) are not acted
  out; they use a townsperson's look, not a uniform. A stop (`approach_and_question`) goes straight to the arrest.
- The player's appearance is fixed (average, brown hair, navy) and their name is the facade's default, "Alex Mercer".
- Odd jobs are one shift kind per shop; the stock is a few items per shop kind (`LoopItems.STOCK`).
- Opening hours by kind; traits only from warmth; in 2025 gamma's general store is an antiques dealer and its market a
  chapel, so the "market" is the open superstore, Broadmeadow, a kilometre west of downtown.
- Without the dialogue model the consequences are not voiced (the templates don't use the facts); the shopkeeper's
  refusal, the debug panel and the police are the visible consequences then.

**Mismatches with the facade (for Jeff):**
1. `arrest` moves the facade's clock by the hours held but runs no day of gossip, reports or decay for the days it
   crosses, and its answer has no `day`/`hour`: the game works the clock out itself.
2. `TimerpgSim.advance` and `act` keep the clock separate from the action: a shift's hours need a separate `advance`
   (fine, but `work_shift` could say so, or take the hours itself).
3. Patrol beats are runs of consecutive node ids in sort order (`buildInitialOfficers`): with the town's real graph
   that scattered them over the town, so the game numbers the junctions along a Hilbert curve to keep a beat to
   neighbouring corners. A beat built by graph distance from the station would not need that.
4. No `ownerId` for the landmark stores in the town data, so the game makes clerks (above); `LoopPlaceInit` has no
   field for more staff than the owner (the coworkers come from `workplacePlaceId`, which works).
5. Names and ids: dialogue ids such as `gen:1:c2>1984` work (the id map sanitises them); facts say "this person" for
   the player, which the game rewords for the HUD ("Mildred saw you steal ...").
6. `sell` of a stolen item is `legit: true` in the answer (the trace is `pawnTraceFlagged`); the HUD does not tell the
   player, the debug panel does.
7. `spotted` gives `approach_and_question` for a recognised description even with a weak case; the stand-in arrest then
   gives a night in the cell. Harshness is the owner's call ("Open, for the owner").

**Tests** (`gradle :desktop:crossroadsTest --tests LoopGameTest`, the real gamma data, QuickJS through SimJs):
init from the town (people, places, keepers, spouses, metres); buy at the general store's successor, a theft the keeper
sees face on, 48 hours, the keeper refuses, the stolen watch sold at the pawn shop (flagged in the ledger), a shift;
the save loaded into a fresh engine gives the same sim JSON back and the keeper still refuses; an unseen theft behind
the keeper's back (no witnesses), a seen one, an officer's sighting and the arrest (stolen things taken, the clock on);
an officer's route on the roads; line of sight through a building and across the street.

**Timing** (this 4-core x86 server, QuickJS, not the S25): `init` for the real town 43-80 ms (195 KB of JSON);
`act` 0.1-0.9 ms; `advance` 24 h 2.5-3.5 ms in a quiet game, 22-29 ms mean (43 ms max) a day after 20 witnessed thefts;
`attitude` 0.03-0.1 ms; `policeView` under 1 ms; `load` 22-30 ms; the save 260-485 KB; the JS heap 1.5-1.8 MB.

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
