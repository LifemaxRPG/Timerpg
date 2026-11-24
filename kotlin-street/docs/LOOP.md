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
  (`Economy` in `src/loop/bridge.ts`: priceIn, priceIndex, stockOf, openStock, take, restock, quantityOf, wage, pawnOffer, pawnPrice, fenceOffer,
  formatMoney) and read in `LoopGame` (sim thread only; cached, never per frame): starting cash is $60 of 2025 money
  times `priceIndex(year)` (6000 cents in 2025, at least 200); every amount shown goes through `formatMoney` ("23¢",
  "$29.95"), made into text on the sim thread and handed to the UI in `LoopView` (`cashText`, `stock`, `pawnOffers`,
  `pawnOfferTexts`). Items carry their value in cents, serial numbers for valuables (the economy's `serial` flag) and
  whether they are hot (stolen, with the crime id); the cash readout is top left (orange when carrying something hot).
  The facade itself takes whole dollars (item `value`, shift `pay`, `fineAmount`, `playerCash`): Kotlin converts (cents
  rounded to dollars going in; a fine, a 2025-dollar number, is scaled by the price index into era cents coming out).
- **The shops** (shelves: each place keeps a `ShopStock` (`openStock(econKind(shopKind), year, "<town>|<place>")`, plain JSON, opened on
  first look) in `LoopGame`, saved with the loop (`stocks`, `dayFrac`) and loaded back. Buy and Take call `take(stock, id, 1)`;
  if the quantity did not drop the action fails with "Sold out." (no money, no crime). Every clock advance (hours, shifts,
  sleep, waits, the jail hours) adds to a fractional-day counter and passes the whole days to `restock(stock, days, year, seed)`
  for every opened shop, each exactly once, so each shop's delivery interval decides when shelves refill. The HUD shows
  "x2" and "sold out" (no buttons) at zero; `LoopItems.econKind` maps general/antiques(pawn)/market, bakery and butcher(market), toy and music
  (five_and_dime), clothing and dress (clothing), bait (hardware), ice cream (diner)): standing at a loop place's door or inside its room, a "🛒" chip opens its panel: **Buy** (pays the economy's price, gets
  the item; `act buy`), **Take** (shoplift: `act take`, no payment), **Sell** at the pawn shop (`pawnOffer(item, year, hot)`: hot is half the clean offer, 0 when they won't
  take it: `act sell`), **Work a shift** (the old odd-job shift, now only where no job opening exists this week, see "Calendar and employment"; up to 4 h, not past closing, paid `hours x wage(job, year).hourlyCents`, `shop_clerk` at counters and `odd_jobs` for crates,
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
- **The fence** (`economy.townFences / fenceLeads / fenceDeal / fenceRisk`, src/economy/fence.ts; code `LoopFences.kt`,
  `LoopGame`, `LoopSession.fence*`, `LoopFencePanel`):
  - *Built at init* from the town's people (saved with the loop: `fences`, `leads`, `fenceTrust`, `tips`; an older save
    builds them from the seed). The town has only warmth, so the economy's `FencePerson` is made up deterministically:
    sociability = warmth; honesty = .15 + .75 u1 + .2 (warmth - .5), 0.05 less under 30; greed = .1 + .85 u2 - .1 (warmth - .5)
    (u1, u2 from the town seed and the person's id); job = the cover their workplace suggests (bait shop: hardware clerk,
    music store: repair clerk, pawn or antiques: secondhand dealer), else the seed picks. (The export repeats 22 ids; the
    fence input has each once.) The capital gets 1 fence with about 40 leads (the seed's draw, not a rule).
  - *Leads are dialogue facts*: each lead's source person gets "You know <name> buys things, no questions asked, behind
    <shop>, after dark." in their facts (`LoopGame.attitude`, so `DialogueEngine.loopFacts`), unless their stance is
    `refuse` or `report_on_sight`. The place is the real spot, not the economy's generic text.
  - *Where and when*: 20:00-02:00 at the back of the nearest shop the cover works from (hardware: bait shop, repair: music
    store, secondhand: the pawn shop; else behind their home), two metres past the back wall, facing away from it. Drawn
    as a standing figure through `TownPlay.extras` (a 6th number, 1, means standing), labelled with their name only. The
    panel (`LoopFencePanel`) opens by itself within 3 m (checked every 0.4 s), Close keeps it shut until the player walks off.
  - *Heat* (`LoopFences.heat`): clean item 0; stolen 0.9 within 1 day of the theft (the item's day), 0.6 within a week, 0.3
    after. Not yet from the case's strength or who knows. `fenceDeal` decides: a stranger is refused above 0.7 ("too hot"),
    other categories ("not their line"); an offer is below the clean pawn offer by design.
  - *A deal* pays the cents, removes the item, saves the fence's new trust (+0.10), then rolls `fenceRisk` once with a
    stable key (town seed, fence, item id). A hit is a **tip-off**: a stolen item is entered into the pawn ledger as if
    the player sold it at the pawn shop (`act sell`, no one nearby), so the police's pawn trace can find it; otherwise
    only a note (debug panel, PlayLog `fence_deal`). A clean deal never touches the pawn ledger.
  - *Debug panel*: each fence (cover, where, hours, trust, number and names of leads), the last tip-offs.
  - **Gap for Jeff's facade**: no way to say "an informant reported this sale" (no witness on a non-pawn `sell`, no
    case for the buyer's tip, no trust or stance for the fence). The pawn-ledger `act` is the nearest hook; a clean
    sale that is tipped goes nowhere. The fence's spot behind a building is not checked for reachability (a fenced
    back yard may block it).

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

## Opening hours (2026-11-24)

The shops' hours are the economy's (`src/economy/hours.ts`, docs/ECONOMY.md), not a guess by kind: `Economy.hoursOf / isOpen /
staffed` (bridge `economy.*`) are read in `LoopGame` on the sim thread, with the shop kind mapped by `LoopItems.econKind` and
the seed `"<town>|<place>"` (the outlet variation). `LoopPlace.open/close` are now only the facade's own `openHours`
fallback; `openAt` is gone.
- **The weekday and date:** from the calendar (see "Calendar and employment" below), not a fixed Sunday; `hoursOf / isOpen / staffed`
  now get the day's ShopDate too (the economy's legacy Christmas and New Year closures), and the world calendar's holidays that close
  `shops` shut every shop. 2025 Sundays are short (10-17), and before 1965 a Sunday is shut.
- **Caching:** `isOpen` and `staffed` per place per in-game hour (cleared on a new day), `hoursOf` per place per weekday; never per
  frame. `LoopView.hours` (place id -> `PlaceHours`: open now, today's text, the closed line, people at work, closing hour) is made
  in `publish`, so the UI never calls the sim.
- **Shop panel:** shows "Open 8 a.m.-6 p.m." / "Open 24 hours" / "Closed Sundays", and when shut "Closed. Opens at 8 a.m." (or
  "tomorrow at ..", "Monday at .."). Buy, Sell and Work are refused with that line; Work ends at the economy's closing hour.
- **Taking from a closed shop is a break-in:** `take` first sends the facade's `enter` (`isOpen: false, forcedEntry: true`: a
  burglary crime of its own, severity .62, with traces), then the `take` (shoplifting). Closed means no staff, so the scene has no
  one inside and the break-in goes unseen unless the street scene (outdoors) shows someone; the line says "You break into X".
  Both crimes exist in the facade (two ids); the second act's `crimeId` is the item's.
- **Staffing:** inside a shop `LoopGame.staffedScene` replaces the scene's people with `staffed(...)` of them: the keeper, then
  staff - 1 clerks, townspeople whose workplace is the shop first, then stand-ins picked from the town's non-keepers by the place's
  id hash (real people, because the facade ignores bystanders it does not know, so an anonymous clerk could never witness). So
  the first hour and late evening have one pair of eyes; the 11-14 rush has the kind's rush staff (market 4, pawn 2).
- Tests: `LoopGameTest.openingHoursClosedShopsAndStaffing`.

## Living costs and transit (2026-11-24)

Sam's C022 (`src/economy/living.ts`, docs/ECONOMY.md) and C024 (`src/transit`, docs/TRANSIT.md) are in the loop, through the
same bridge: `Economy.lodgingOf / dailyNeeds / dueOn / arrears / dailyBudget` (`economy.*`) and a new `Transit.fare / departures /
travelTime / classesOf` (`transit.*` in `simCall`). Read in `LoopGame` on the sim thread only, cached, precomputed into `LoopView`
(`rent`, `lodgingOffer`, `lodgingDueCents`, `livingLine`, `transit`, `railFare`); the UI never calls the sim. Code:
`LoopLiving.kt` (where things are), `LoopGame` ("living costs" and "transit" sections), `LoopSession`, `LoopHud.kt`
(`LoopLodgingPanel`, `LoopStopPanel`, `LoopFade`, the rent line under the cash), `GameActivity` (chips and panels).

- **The boarding house:** the town's export has no hotel or boarding house, so `LoopLiving.lodging` takes the biggest home (front x
  depth) whose door is within 200 m of Main Street and calls it "Boarding house on <street>" (in gamma: `home:b48`, Queen Street).
  Its residents still live there; it only gets a "ROOMS" sign over the door and a chip ("Rent a room" / "Your room") within 8 m.
- **Renting:** "Rent a room" pays `dueOn(lodging, today, today)` (the deposit and the first period; `boarding_house` in 2025:
  $94.12 a week, no deposit; the player arrives with $60, so the first job comes first). The ledger (`kind`, `startDay`, `paidCents`,
  nights slept) is saved in the loop save (`rental`, `roomItems`). Rent falls due every `periodDays` (`dueOn`); nothing is taken
  by itself. `arrears` decides the rest: **due** (the day the period begins), **warning** after 1 day late, **locked out** at 7 days.
  The HUD shows the line under the cash ("Rent due today", "Rent is 2 days late ... locked out in 5 days", "Locked out of your
  room: $188.24 owed. Your things are held."), and a status change goes to the messages and PlayLog (`loop rent`).
- **Lockout:** no Sleep in the room, and the items left in it ("Leave" / "Take back" in the panel) are held until "Pay rent"
  clears the arrears (it pays everything owed, oldest first; partial payments are not offered). Sleeping in the room (to 7) is a
  normal `sleepUntil` plus a record (`nights`, `lastNight`): needs come later.
- **Debug panel:** "Most people here live on about $265.79 a week" (`dailyBudget('working', year).weeklyLivingCents`), the room's
  state, or the offer if there is no room yet.
- **Transit stops:** `LoopLiving.lines`: Main Street and the two other streets with the most shop doors beside them (in gamma
  Kiln Lane; 4, 3, 3 stops at least 200 m apart, at the busiest road-segment midpoints, on the pavement just off the road, named
  for the nearest shop). One line per street; a ride goes between stops of the same line. The mode follows the year (horsecar
  before 1888, streetcar before 1930, then the city bus; `fare` decides there is service). A "STOP" sign label shows over the spot
  and a chip ("Bus stop") within 8 m opens the panel. **No sign prop** exists in the prop kit that fits, so the marker is the HUD
  label only.
- **A ride:** the panel shows `departures(route, year, weekday, now)` (the weekday of the loop's clock, as the opening hours; the
  rest of today's, or tomorrow's first when service is over) and, per other stop, `fare(mode, year, km)` and `travelTime`
  (straight-line km between the stops). "Ride to X" pays the fare, then **one** `advance(wait + travelTime)` moves the clock
  (the wait is to the next departure, across midnight if need be; `travelTime` already counts the mode's boarding wait), and the
  player is placed at the stop facing along the line under a short fade. Too poor: nothing happens.
- **The train** (RailTrip / TicketWindow): the ticket window showed `RailNet.fare` (its own dollar table, 2025: $6 minimum or
  $0.16 a km) and nothing was ever charged. Now (changed): the window shows `transit.fare('railway', year, km, cheapest class)`
  in the era's money (`LoopSession.railFareText`, a straight line through two sampled fares on the UI thread, so at most a cent
  off), and "Board" first charges the exact `fare` on the sim thread (`LoopGame.buyRailTicket`; "can't afford" blocks boarding,
  a year with no railway fare boards free). `RailNet.fare` is left in place (it still sets `route.fare > 0`, the "has trains" flag).
  2025, 120 km, second class: $129.55 (the old table: $19.20). The class choice is not offered (cheapest only).
- **Tests** (`LoopGameTest`): `theBoardingHouseAndTheStopsAreFound`; `rentComesDueThenWarnsThenLocksOutAndTheLedgerSaves` (a $60
  player can't rent; renting debits deposit + rent; day 8 due, day 9 warning, day 16 locked out; no sleep, things held; the ledger
  saves and loads; paying clears it and frees sleep and things); `aRidePaysTheFareWaitsAndMovesThePlayer` (the fare, the clock by
  wait + travel, the destination stop, too poor, the rail ticket); `src/loop/bridge.living.test.ts` for the bridge.
- **Gaps:** no way to give the room up (the rent runs on); only the cheapest rail class; stops are straight-line distances, not road
  distances; a ride is a fade, no vehicle; the "Rent a room" place is a resident's house; no meals included in the rent are used (needs
  come later); holidays and the rent's 1952-based scale are the economy's; the stop on the pavement is not checked against
  the collision (a fence or prop may stand there); not tried on the S25.

## Calendar and employment (2026-11-24)

Sam's `src/calendar` (docs/CALENDAR.md) and `src/jobs` (docs/JOBS.md) are in the loop through the same bridge: `Calendar` (`calendar.dateOf /
dayNumberOf / holidaysOn / isPayday / isMarketDay / schoolTerm / daylight / toShopDate`) and `Jobs` (`jobs.openings / apply / shift`) in
`src/loop/bridge.ts` (`simCall` now routes `economy.`, `transit.`, `calendar.`, `jobs.` by a table). Read in `LoopGame` on the sim thread only,
cached (dates per day, openings per place per week), precomputed into `LoopView` (`dateText`, `holidayText`, `jobs`: `JobsView`/`JobView`/
`OpeningView`); the UI never calls the sim. Tests: `LoopGameTest.theDateAdvancesWeekdaysCycleAndAHolidayClosesTheShops`,
`applyingLateTwiceAbsenceAndTheft`, `aSteadyRunGivesARaiseAGoodReferenceAndTheJobSaves`; `src/loop/bridge.calendarjobs.test.ts`.

**Calendar**
- **Start date:** `LoopGame.startDate` (saved as `startDate`, set by `init(.., startDate)`), default `defaultStartDate(year)` = the first Tuesday of
  Bloommonth (month 5) of the town's year: for 2025 gamma **Tuesday 6 Bloommonth 2025**, a weekday in spring that no shop-closing holiday
  touches nearby. Day `d` is `dateOf(d - 1, startDate)`; the weekday, the "Tue 6 Bloommonth" text, the season and today's holidays come from it
  (`LoopGame.dayInfo`). `LoopTown.START_WEEKDAY` is gone; the facade's `startDayOfWeek` is day 1's weekday (`initJson(.., startWeekday)`), so its
  gossip day names agree. A save from before this (no `startDate`) takes the default, so its weekdays shift.
- **Holidays:** `holidaysOn(date, townYear)`; a holiday whose `closes` has `shops` (2025: Hearth Day, 5 Frostmonth) shuts every shop all day
  (`isOpenNow` false, no staff, no spans; the economy's `hoursOf` also gets the ShopDate for its own legacy closures). The shop panel reads
  "Closed for Hearth Day. Opens tomorrow at 8 a.m." (`PlaceHours.holiday`); banks, schools and the post office closures are not used (no such places).
  Taking from a shut shop is the same break-in as any closed shop.
- **HUD:** the date under the clock (top centre), with the holiday name after it. The economy's year stays the town's year after Frostmonth wraps.
- Not used yet: paydays, market days, school terms, daylight (the renderer's sun is its own).

**Employment** (`LoopGame`: `openingsAt`, `applyFor`, `startShift`, `quitJob`; panel `LoopShopPanel`)
- **Ask about work:** lists `openings([{id, kind: econKind}], year, day, town.seed)` for that place this week (those not taken), as "Shop clerk: 17.50/h,
  from 9 a.m. for 6 h". Every shop kind the loop has maps to a role, so openings are possible everywhere (each place/role has one about 65% of weeks).
- **Apply:** `jobs.apply` with age **25** (the game has none), `hasPapers` **true** (not tracked), the carried references, and reputation from the
  keeper's `attitude` stance: **serve 0.6, wary 0.35, refuse / report_on_sight 0.1** (no keeper 0.6). So a clean player with no reference gets a
  **trial** at a shop that wants a reference, and is hired outright at one that does not; a keeper who refuses the player turns them down.
  The taken opening is remembered (`takenOpenings`, saved) so it is not offered again that week. One job at a time.
- **The job** (`Job`, saved as `job`: the `Employment`, place, title, start hour, shift hours, first day, last worked day): **Start shift** is at that
  place from the start hour for 3 hours; more than 15 minutes late records `late` (strike; the second dismisses), the shift then runs the rest of its hours;
  a day's end with no shift worked (the shop open that day, after the job's first day) records `absent` for that day (`daysEnded`, from `advance` and
  the cell's hours); a theft (`take`) at the workplace seen by a witness who is staff (the keeper, clerks) records `caught_stealing` (one outcome per
  day: it is entered as the day after the last shift if that day is taken). Pay per shift is the job's `hourlyCents` x hours worked, then `act work_shift`
  (the coworkers learn the player) and `advance`. Warnings, trial-to-hired, raises and dismissals are lines in the HUD (`jobs.news`, under the cash) and the messages; PlayLog `loop job`.
- **References:** a dismissal adds a poor one, **Quit job** adds the job's reference so far (good after a steady run: 15 on-time shifts over 15 days:
  +10% raise, `hasRaise`); both are kept in the save (`references`: ref, shop, day) and passed to the next `apply`; the shop panel shows them.
- **The old odd-job shift** (`work`) stays only where no opening exists this week at that place and the player does not work there (else "They're hiring:
  ask about work"); it is not removed because the three `isShop` places and the small shops often have none in a given week.
- **Gaps:** no choice of shift (always the opening's start hour); a shift is one fade of the clock, not played; papers and age are constants; the keeper's
  stance is the only reputation; no pay day (wages are paid at each shift's end); the job is not in the dialogue fact sheets (a coworker does not know of a warning);
  the clock window for starting a shift is 3 hours for all roles; a 23:00 night shift is not offered in the loop (no hotel or motel); not tried on the S25.

## Needs, weather and cars (2026-11-24)

Sam's `src/needs` (docs/NEEDS.md), `src/weather` (docs/WEATHER.md) and `src/cars` (docs/CARS.md) are in the loop through the same bridge:
`Needs` (`needs.initialNeeds / tick / eat / sleep / wash / injure / treat / treatmentCost / effects`), `Weather` (`weather.weatherAt /
weatherEffects / temperatureRange`) and `Cars` (`cars.carsForSale / papersFor / policeStop / runningCosts / drive / repairCost / serviceCar`) in
`src/loop/bridge.ts`, routed by `simCall` like `economy.*`. Read in `LoopGame` on the sim thread only, cached, precomputed into `LoopView`
(`needs`, `eat`, `sky`, `cars`, `walkMultiplier`, `boardLine`); the UI never calls the sim. Code: `LoopGame` ("needs", "the sky", "cars"
sections), `LoopCars.kt` (views and tables), `LoopSession` (activity, `tickCars`, injuries), `LoopHud.kt` (`LoopCarLotPanel`,
`LoopBreakdownCard`), `GameActivity` (Auto weather chip, panels), `VehicleWorld`, `OpenWalk`, `GameState.walkScale`. Tests:
`LoopGameTest.needsTickHungryEatingRelievesAndASleepOnABenchRestoresLessThanABed`, `theSkyFollowsTheDateAndFogCutsTheWitnessRange`,
`buyingACarPapersFuelWearBreakdownsAndATow`; `src/loop/bridge.needsweathercars.test.ts`.

**Service places.** The capital's landmarks the loop now knows as shops (`LoopTown`, added after the homes so no earlier clerk changed): the gas
station (`glb:modern_gas_station`, shop kind "gas station", econ `gas_station`), the diner (`glb:carhop_drive_in`, "diner") and the
drugstore (`glb:corner_drugstore`, "pharmacy"), matched by landmark kind so other eras' (`main_street_service_station`, `junction_diner`) work.
They get the usual panel (shelves, hours, work, theft), clerks and witnesses.

**Needs**
- `Needs` JSON in the loop state, saved (`needs`, `board`). Ticked in `LoopGame.advance` with an activity from what the player was doing:
  walking/running from the walk (`LoopSession.noteMotion` each frame adds seconds to fading shares; the shares at the burst pick the activity, `idle`
  in a car), `working` for a shift, `sleeping` on a bed type for sleep, `idle` for waits and rides. The cell hours (`arrest`) are a night on `cell`
  (or `idle` under 6 h).
- **Eat:** the shop panel has "Eat a meal" (diner) or "Have a snack" (general store, market, five-and-dime, gas station): paid
  `dailyNeeds(year).dinerMealsPerDayCents / 3`, a snack 30% of that (no menu exists yet; ours). **Board:** a lodging with `includedMealsPerDay` (room and
  board: 2) shows "Take your board" in the room panel, free, up to the included number a day (`LoopGame.lodgingKind` picks the lodging; the
  boarding house has none). "Wash up" in the room (`needs.wash`).
- **Sleep:** the rented room is a `bed` (`sleepInRoom`); "Sleep rough till 7" anywhere else is a `bench` (restores less: a 9 h night from tired
  leaves 0.48 on the bench, 0.00 in the bed); the cell is `cell`.
- **Effects:** the HUD labels ("hungry", "tired", ...) under the cash; `walkingSpeedMultiplier` x the sky's walk multiplier (floor .3) goes to
  `GameState.walkScale`, which `OpenWalk.stepFoot` takes as a share of each step (the bicycle is not slowed); the social modifier becomes a note in
  every fact sheet ("You notice this person looks unwashed and exhausted.", only when the modifier is below -0.05; cached facts are rebuilt when the
  look changes). **Not used:** the social modifier in `jobs.apply`'s reputation (it would change existing results; a later step), `dozeChancePerHour`.
- **Injuries:** a hard stop of the driven car (`VehicleWorld.onImpact`, the speed lost in one frame, at most one a second): above 10 m/s a bruise, above
  16 a sprain; a fall (`OpenWalk.onFall`, landing speed): above 6 m/s a bruise, above 9 a sprain. The pharmacy panel offers "Treat your X" at
  `treatmentCost` (it then heals twice as fast). Not tried on the S25: the impact is the per-frame speed drop of the Jolt car, a real crash may need tuning.

**Weather**
- The sky is `weatherAt('temperate_coast', day - 1, hour, town seed, startDate)` (`LoopGame.skyAt`, cached per hour; same date and seed, same sky).
  `climate` is a field (gamma's: `temperate_coast`). The manual weather chips are an override: tap one and it holds until the new **Auto** chip is tapped
  (it shows the sky's name). Mapping to the renderer's `WeatherState` (`skyState` in `GameActivity`): clear = none, cloudy = a light cloud, overcast and snow =
  overcast (snow also shows the winter season while it lasts), rain = rain, storm = rain at its heaviest, fog = mist. The sound follows the same state.
- `weatherEffects`: **faceRecognitionM** limits who can witness an act (`LoopScene.limitedTo` in `LoopGame.act`: outdoor bystanders beyond it get no line
  of sight, `lastAct.sighted` counts those who still see; 35 m clear, 6 m in fog; indoors unchanged); **walkingSpeedMultiplier** is combined with the needs'
  (above); **streetActivityMultiplier** is shown in the debug panel only: the stage already thins the crowd from the weather state (`TownPlay.outdoors` from the
  rain, mist and cloud of the state it is given), so it follows the sky through the mapping; **carGripMultiplier** reaches `VehicleWorld.gripScale`, which
  lessens the power put down (x(.5 + .5 grip)) and the steering (x(.7 + .3 grip)) of the driven car. **A stand-in:** the physics fixes tyre friction when a
  car is made, so the real friction is not changed (it would need a native setter).
- Officers' sight (30 m) is not yet cut by the weather.

**Cars**
- **The lot** is at the gas station (the capital's `Crossfield Fuel & Market`): a "Used cars" chip beside the shop chip opens `LoopCarLotPanel`.
  Listings are `carsForSale(year, "<town>|lot|<month of 28 days>")` less the player's cars, asking prices rounded ($5 under $100, $10 under $1,000, $50
  under $5,000, else $100). Bodies are drawn as V1 models (`LoopCars.specFor`): sedan, coupe and wagon by model year (alderwick before 1935, fenmere before
  1950, northcairn before 1966, rowanvale after), pickup the tallmere (older ones as sedans), sports_car the torrel-1968 (torrel-1988 from 1980).
- **Buying** debits the price, makes an owned car (the module's `Car` JSON, saved as `ownedCars` with its model, colour seed, fuel and spot) with 5 gallons
  (or a smaller tank), parked at the lot by `LoopSession.tickCars` (twice a second, on the main thread; the world's changes are posted to the game loop,
  `VehicleWorld.post`). A car left on the street keeps its spot (`parkCar`), a towed one goes back to the lot.
- **Papers:** `papersFor(year)` prices for registration, licence, insurance (those the era has), bought at the lot, saved (`papers`). Nothing checks
  them yet: **police stops on papers (`policeStop`) can wait for Jeff's facade** (no stop hook in `spotted` for a driver).
- **Driving** accrues miles (`tickCars` measures the driven car's travelled distance twice a second and passes at least 0.05 mile to `driveMiles`): `cars.drive`
  with the sky's temperature and the town's seed wears the car and may break it down (deterministic for the seed: a 2022 wagon of 63% condition broke at
  about 21,600 miles in the test); fuel falls by miles / `runningCosts.mpg`; a breakdown or an empty tank sets `VehicleWorld.engineOff` (no throttle), and
  a card (`LoopBreakdownCard`) names what happened. A flat tyre or a dead battery is a roadside job: **"Fix it here"** pays `repairCost`; anything
  else (or out of fuel) is **"Call a tow"**: pays $6 in 1952 money scaled by the price index (the module has no tow price; ours), the car goes to the lot
  once the player has got out, and "Repair" at the lot pays `repairCost`. A service (`serviceCar`) at the lot restores it.
- **The flat tyre's repair** is a tenth of the module's ($1.50 in 1952 money rather than $15): `LoopGame.repairOf` (the module still says $15; its test is
  left as it is).
- **Fuel:** the pumps sell by the gallon at `gasolinePerGallonCents` (the economy's `gasoline`): "5 gal" or "Fill up", the car within 40 m of the station
  or at the lot. Tank sizes by body are ours (`LoopCars.tank`, 13-18 gallons).
- **Gaps:** the owned car is not matched to a physical key; its colour is a seed into the model's palette; no insurance rules, no repossession; breakdowns
  stop the engine but the car does not smoke or show it; the tow does not drive anywhere; all untried on the S25, and the Android parts (HUD, the car lot panel,
  the Auto chip, spawning at the lot, the fall and crash hooks) only compile (no instrumented test).
