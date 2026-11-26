# G020 Delivery — The Time Machine in the Playable Loop

## Summary

Wired the real G017/G018 timeline engine (`src/timeline/`) and G008 cross-era forensics (`src/crime/`) into the playable loop (`src/loop/`), exposing `jump(sim, { toYear })` and `timeMachineView(sim)` across TypeScript, `TimerpgSim`, stateless `*Json` wrappers, and `simCall`.

- **`jump(sim, { toYear })`:** Lazily or eagerly backs `LoopSim` with a `Timeline`, syncs the player's current era actions/bookings/carried items, branches/replays `presentAt(timeline, toYear)` without mutating cached worlds, rebuilds the town's living residents, active businesses, lots, Gazette stories, police force, and open cases for `toYear`, resets NPC attitudes except where `npcMemoryOfPlayer` carries branch memory for living NPCs, and returns plain-JSON changes, Gazette headlines, cross-era matches, and loose-thread facts.
- **Arson & historical consequences:** Added `act(sim, { kind: 'arson', placeId, businessId })` (and lethal-assault kill branching) so burning a store in 1952 records real witnesses/traces, logs an `arson` intervention + timeline crime, closes the business, clears/changes its lot in later years, and puts the fire in the Gazette (`headline: "<store> burns down"`).
- **What travels with the player:**
  - **Carried items (`sim.carriedItems`):** Receipts/purchases (`buy`) and stolen goods (`take`) travel back and forth across jumps keeping their original `acquiredYear`, `businessName`, `ownerName`, and `provenanceNote` (removed cleanly on `sell`, accepted `fence_sale`, and booking confiscation) and are inspected via `inspectCarriedItems()`.
  - **Evidence across time (G008/G017):** Restores collected crime traces onto both `crimeState.traces` and `policeCase.traces` and runs `reexamineColdCases(..., { year: toYear, birthYearOf })` on arrival. Prints enrolled at booking in 1990 match a 1952 crime in 1990 (38 years later, recorded on `BookingColdCaseHit.yearsToMatch`) and in 1995 (43 years later, flagging `before_birth` chronology anomaly when `playerBirthYear > 1952`), and do **not** match in 1955 (`enrolledYear 1990 > 1955`).
- **Fuel (nuclear material):** Starts at `DEFAULT_TIME_MACHINE_FUEL = 4` trips (`timeMachine.fuelRemaining = 4`, `maxFuel = 4`). Each jump spends 1 unit; refuelling is out of scope; attempting a jump at `0` fuel returns `{ ok: false, reason: 'out_of_fuel', fuelSpent: 0, fuelRemaining: 0 }` without mutating year or state.
- **Loose threads:** Surfaces `looseThreads(timeline, toYear)` as both structured `looseThreads[]` and plain-English `looseThreadFacts[]` on `JumpResult`, `timeMachineView()`, and `attitude(sim, personId)` (`looseThreadFacts` + `facts[]`) for HUD and NPC dialogue.

## Files changed

- `src/loop/types.ts` — `'time_machine'` place kind, optional `businessId`/`slot` on places, timeline/fuel fields on `LoopInit` and `LoopSim`, `'arson'` action input + `burnedBusinessId`/`burnedSlot` on `ActResult`, `year` on `CrimeMetaRecord`, `crimeYear`/`yearsToMatch` on `BookingColdCaseHit`, `looseThreadFacts` on `AttitudeResult`, and plain-JSON G020 types (`LoopCarriedItem`, `LoopJumpRecord`, `LoopTimeMachineState`, `LoopGazetteEntry`, `LoopCrossEraMatch`, `JumpInput`, `JumpResult`, `TimeMachineViewResult`).
- `src/loop/timeJump.ts` (new) — integration layer over `src/timeline/` and `src/crime/` (`DEFAULT_TIME_MACHINE_FUEL`, `ensureTimeMachine`, `ensureTimeline`, `syncSimToTimeline`, `resolveTargetBusinessForArson`, `recordArsonInTimeline`, `performTimeJump`, `buildTimeMachineView`, `looseThreadHooksForPerson`).
- `src/loop/facade.ts` — optional time-machine init in `create()`, carried-item tracking across `buy`/`take`/`sell`/`fence_sale`/confiscation, `act({ kind: 'arson' })` and lethal-assault branching, `year`/`yearsToMatch` on booking cold-case hits, `looseThreadFacts` in `attitude()`, and exported `jump()`, `timeMachineView()`, `DEFAULT_TIME_MACHINE_FUEL`.
- `src/loop/index.ts` — exports `jump`, `timeMachineView`, `DEFAULT_TIME_MACHINE_FUEL`.
- `src/loop/bridge.ts` — exposes `jump`, `timeMachineView` on `TimerpgSim` / `simCall` and stateless `jumpJson`, `timeMachineViewJson`.
- `src/loop/timeJump.test.ts` (new) — 7 end-to-end tests covering all G020 requirements plus `TimerpgSim`/`*Json`/`simCall` bridge.
- `docs/LOOP_API.md` — documents `jump()`, `timeMachineView()`, `act({ kind: 'arson' })`, nuclear fuel, carried items, cross-era evidence rules, loose threads, and QuickJS bridge methods.
- `src/timeline/`: **0 files changed.**

## Verification

- **TypeScript (`tsc -p tsconfig.json --noEmit`):** 0 errors.
- **Vitest (`vitest run --no-file-parallelism src/loop src/timeline src/court src/crime src/police src/economy`):** **19/19 test files, 137/137 tests passed** in 26.97s:
  - `src/loop/timeJump.test.ts`: **7/7 passed** (1952 store arson → 2025 changed lot + Gazette story; attitude reset vs timeline branch memory; carried receipt/stolen watch round-trip across 1952 → 1990 → 1995 → 1955 with 1990 prints matching 1952 burglary in 1990 & 1995 + `before_birth` anomaly and 0 matches in 1955; loose threads in HUD & NPC `attitude()` fact sheets; 4-trip nuclear fuel cap & clean refusal at 0 fuel; save/load round-trip after a jump continuing identically; `TimerpgSim` / `*Json` / `simCall` bridge).
  - `src/loop/loop.test.ts`: **20/20 passed**.
  - `src/loop/booking.test.ts`: **9/9 passed** (`[G014 100-day save]` total `243587B -> 137533B (-43.5%)`, `crimeState -66.5%`).
  - `src/loop/court.test.ts`: **8/8 passed**.
  - `src/loop/loop.perf.test.ts`: **1/1 passed** (`300 people, advance(sim, 24): median=2.52ms, mean=2.70ms, max=3.37ms`).
  - `src/timeline/timeline.test.ts`: **14/14 passed** (untouched; `[G018 capital]` `jump2025=174.5ms`, `cacheHit=0.020ms`).
  - `src/court/`, `src/crime/`, `src/police/`, `src/economy/`, remaining `src/loop/bridge.*.test.ts`: **78/78 passed**.
- **QuickJS-ng bundle (`esbuild src/loop/bridge.ts --bundle --platform=neutral --target=es2020 --format=iife`):** **896,422 bytes (875.4 kb)**, `0` disallowed globals (`Math.random`, `Date.now`, `process.env`, `require(`, `fs.readFile`, `Buffer.`, `window.`, `document.`), verified in an isolated bare `vm.createContext({})` sandbox executing `init` → `timeMachineView` (4 fuel) → `jump(1990)` (3 fuel) → `simCall("timeMachineView")` → `save` (176.7 kb) → `load` (year 1990).

## Usage

- Environment: sandboxed Linux container (`node v22.23.5`, `vitest 2.1.9`, `esbuild`, `tsc`).
- Branch: `gemini/G020-loop-time-jump` started off `5a01ff2` (`origin/gemini/G019-loop-court`).
- One early WIP commit pushed (`45eefbf`) followed by final commit with tests, booking cross-era hit span + fence carried-item cleanup, updated `LOOP_API.md`, and delivery notes.

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   The QuickJS-ng bundle grew from `515.5 kb` (G019) to `875.4 kb` (+360 kb) because bundling `src/timeline/` pulls in the full `src/sim/` town generator, chronicle templates, and Gazette prose tables, and a post-jump save string (holding the full `Timeline` interventions + world state for the arrived year) is ~176.7 kb in our VM smoke check. To avoid mutating or duplicating cached `presentAt()` snapshots inside `save()`, we kept `LoopSim.timeline` strictly to the plain-JSON `Timeline` record (interventions, player anchor, carried items, and crime records — never serializing the in-memory `presentAt` LRU cache) and shallow-cloned `presentAt().world` before inserting the player. The remaining weakness is that `LoopSim` initialized with custom stub `places`/`people` (rather than a `presentAt` world) maps stub store arson heuristically onto the first open same-kind `src/sim` business when `businessId` isn't explicitly supplied; callers running from a real `presentAt` town should pass the place's `businessId` (which `jump()` populates automatically on every business place).
2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked by running:** All 137 Vitest tests across `src/loop`, `src/timeline`, `src/court`, `src/crime`, `src/police`, and `src/economy`, full `tsc --noEmit`, `esbuild` neutral ES2020 IIFE build, disallowed-global scan, and live `init` → `timeMachineView` → `jump` → `simCall` → `save` → `load` execution inside a bare Node `vm` context with zero Node/browser globals.
   - **Only reasoned about:** Live QuickJS-ng execution on Android inside `kotlin-street` (no Android/JVM toolchain in this container), and the 3D scene reload/transition UX when the player steps into the time machine building in the capital.
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   They would notice that the game's core loop is now actually a time-travel game: you can torch a shop in 1952, step into the machine (watching your nuclear fuel tick down from 4 to 3), arrive in 2025 to find the lot changed and the 1952 Gazette fire still remembered, carry a receipted item back and forth with its 1952 provenance intact, get fingerprinted in 1990 and trigger a 38-year-old cold-case hit that vanishes again if you jump back to 1955 before those prints existed, and read the loose threads straight off the HUD and NPC fact sheets.

## Ideas for the team

1. **Rebuild `kotlin-street` `sim.js` asset in a dedicated integration task:** Since `bridge.ts` now exposes `jump` and `timeMachineView` (875.4 kb neutral IIFE), updating `kotlin-street`'s bundled `sim.js` and adding a minimal year-picker dialog at the capital's `time_machine` place will make G020 playable on the phone immediately.
2. **Nuclear fuel sourcing & Geiger/radiation hooks:** `timeMachine.fuelRemaining` and `maxFuel` are already plain fields on `LoopSim` and `TimeMachineViewResult`. When refuelling arrives later (hospital/university isotopes, cold-war surplus, reactor raids), adding a `refuel(sim, { units, source })` helper alongside a `crime` hook for stealing controlled nuclear material will drop cleanly onto `LoopTimeMachineState` without changing `jump()`.
3. **Optional save-size diet for `sim.world` after jumps:** Right now `save()` serializes `sim.world` for the current year (~176 kb on a full capital town) even though `sim.timeline` can deterministically replay `presentAt(sim.timeline, sim.year)` in ~150 ms. If mobile save slots ever need to stay under ~30 kb even on large towns, `save()` could optionally omit the reconstructible `world` snapshot when `sim.timeline` is present and rebuild it on `load()` via `presentAt()`.
