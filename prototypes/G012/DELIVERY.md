# G012 Delivery — One Police, and the Fixes the Game's Wiring Found

- **Task ID:** `G012`
- **Branch:** `gemini/G012-loop-police`
- **Base commit:** `990a5cfe685ea8d0fb8e38348f45069139c8bd77` (`ccr-52e43ebb-xnmj2e`)

## What Was Built

1. **One Police (`src/police/` replacing `src/loop/policeAdapter.ts`):**
   - Deleted the duplicate stand-in adapter (`src/loop/policeAdapter.ts`) and replaced it with a thin bridge (`src/loop/policeBridge.ts`) calling real G009 policing (`createPoliceForce`, `stepPoliceTick`, `getActiveWantedCases`, `selectBestMatchingCase`, `decideSpotAction`, `buildPatrolPlanSource`, `buildQuestionPlanSource`, `buildArrestPlanSource`, `compileOfficerPlan`).
   - `LoopSim` now holds the authoritative plain-JSON `policeState: PoliceState` (with `sim.officers` projected from `policeState.officers` and reverse-mapped to sim-domain IDs exclusively through `src/loop/ids.ts`).
   - Booking in `arrest()` appends real G009 `ArrestRecord` entries to `sim.policeState.arrests`.
   - Every public `src/loop/` function signature and every `TimerpgSim` method name in `src/loop/bridge.ts` stayed unchanged.

2. **All 8 wiring fixes from the Android capital integration:**
   - **`arrest()` clock:** Runs any `jailHours > 0` through `advance()`, running gossip, police reports, trace/heat decay, and police plans during time served, and returns `day`, `hour`, `daysElapsed`, `policePlans`, `attitudeChanges`, and `newPoliceCases`.
   - **`work_shift` clock ownership:** `act(sim, { kind: 'work_shift', ... })` owns the shift's clock — advances `hours` through `advance()` internally and returns `day`, `hour`, `daysElapsed`, and world deltas (`policePlans`, `attitudeChanges`, `newPoliceCases`, `pawnLeadsMatched`). Documented in `docs/LOOP_API.md`.
   - **Graph-distance patrol beats:** Fixed `buildBeats()` in `src/police/force.ts` to pick anchors via graph-distance farthest-point sampling and grow beats strictly over nearby graph-connected corners (BFS hop distances), so beats stay local under any node-ID numbering.
   - **`LoopPlaceInit.staff: string[]`:** Added optional `staff` to `LoopPlaceInit` / `LoopPlaceRecord`. Staff automatically witness crimes in open shops (unless explicitly overridden in `event.bystanders`) and learn the player's name + gain trust after a `work_shift`.
   - **Hot item sales (`sell`):** Selling a stolen item now returns `legit: false`, `hot: true`, and `pawnTraceFlagged: true`; selling a clean item returns `legit: true`, `hot: false`.
   - **Dialogue facts POV (`attitude`):** Added optional `playerDescription` to `LoopInit` and rewrote all `attitude().facts` to address the player directly as `"you"` (`"I saw you ..."`, `"Martha Finch told me you ..."`, `"Local police are actively looking for you."`, zero `"this person"` anywhere).
   - **Integer cents & era-scaled fines:** Switched all monetary boundary fields (`item.value`, `vehicle.value`, `shift.pay`, `payEarned`, `fineAmount`, `playerCash`) to integer cents matching `src/economy/`. Fines scale with `priceIndex(year)` and default shift pay uses `wage('shop_clerk', year)` from `src/economy/eras.ts`.
   - **Real street stop (`spotted` -> `approach_and_question`):** Added a single tunable `STOP_POLICY` table (`StopPolicyTable`, overridable via `LoopInit.stopPolicy`) and `SpotResult.stopOutcome` (`checkedPapers`, `questioned`, `outcome: 'released' | 'arrested'`, `caseStrength`, `thresholdUsed`). Thin cases below `stopArrestStrength` check papers (era-gated >= 1915), ask questions, and **release** instead of locking the player in a cell; routing `fromStop: true` / `why: 'approach_and_question'` into `arrest()` also honors thin-case release (`releasedAfterStop: true`).

## Listed Change in `src/police/`

- **`src/police/force.ts` (`buildBeats` + private `bfsHopDistances` helper):** Replaced station-BFS index slicing / dead-end pool wraparound with graph-distance farthest-point anchor selection and local BFS hop-distance beat expansion so every officer's beat stays on nearby corners regardless of node-ID numbering. All 9 tests in `src/police/police.test.ts` pass unmodified.

## Changed G011 Test Expectations

In `src/loop/loop.test.ts` and `src/loop/loop.perf.test.ts`, the original 11 G011 tests run unmodified in structure on the real G009 police, with only two intentional expectation updates required by the G012 API fixes:
1. **Integer cents:** Updated dollar test fixtures (`value: 2..900`, `pay: 36..45`, `playerCash: 50`) to integer cents (`value: 50..90000`, `pay: 1260..1575`, `playerCash: 5000`), matching the integer-cent boundary switch. (With `playerCash: 50` interpreted as 50¢ in 1978, the player could not pay the 1978 fine and was correctly routed to `cell_night`.)
2. **Hot pawn sale:** In the serialized pawn-trace test, updated `expect(sell.legit).toBe(true)` to `expect(sell.legit).toBe(false); expect(sell.hot).toBe(true); expect(sell.pawnTraceFlagged).toBe(true);`.

## Performance — 300-Person `advance(sim, 24)` Before vs After

Measured in Node (`vitest run src/loop/loop.perf.test.ts`, 5 timed iterations after 1 JIT warm-up):

| Build | Median | Mean | Max | Samples (ms) |
| --- | --- | --- | --- | --- |
| **Before** (G011 `policeAdapter.ts`) | **3.17 ms** | 3.33 ms | 4.41 ms | `[4.41, 3.35, 3.17, 3.05, 2.69]` |
| **After** (G012 real `src/police/`) | **3.33 ms** | 3.29 ms | 3.64 ms | `[3.33, 3.64, 3.02, 3.51, 2.96]` |

The real G009 police engine + G007 plan compilation adds ~0.16 ms to median `advance(sim, 24)` for 300 people (well within container jitter and >10× faster than the 50 ms budget).

## Verification

- `npx tsc --noEmit`: 0 errors.
- `npx vitest run src/loop src/police`: **7/7 test files, 39/39 tests passed** (11 G011 tests + 9 new G012 tests + 9 `src/police` tests + 9 `bridge.*` tests + 1 perf test).
- QuickJS-ng bundle check (`esbuild src/loop/bridge.ts --bundle --platform=neutral --target=es2020 --format=iife`): succeeded in 35 ms (366.3 KB), zero `Math.random`, `Date.now`, `process.env`, or `require(` calls.

## Usage

```ts
import { STOP_POLICY, act, advance, arrest, attitude, create, policeView, spotted } from './src/loop';

let sim = create({
  ...townInit,
  playerName: 'Sam Rivera',
  playerDescription: 'a slim stranger in a green coat',
  stopPolicy: { stopArrestStrength: 0.45 }, // optional override over STOP_POLICY
});

// Work a 4-hour shift (advances clock internally, pays era integer cents, acquaints place.staff)
const shift = act(sim, { kind: 'work_shift', placeId: 'place:market', hours: 4 });
sim = shift.sim; // shift.day, shift.hour, shift.payEarned (cents)

// Shoplift -> advance -> street stop checks papers/questions and releases on a thin case
const spot = spotted(sim, Object.keys(sim.officers)[0]!, { distance: 4, light: 0.95 });
// spot.action === 'approach_and_question'
// spot.stopOutcome?.outcome === 'released' | 'arrested'

// Arrest runs jailHours through advance() and returns era-scaled fineAmount (cents) + new day/hour
const booked = arrest(sim, { jurisdictionId: 'jur:capital', playerCash: 5000 });
// booked.day, booked.hour, booked.fineAmount (cents), booked.attitudeChanges
```

## Self-review

1. **Does it hold up when poked at the edges?**
   - Yes: tested empty/thin stops vs strong named stops, pre-1915 vs post-1915 papers checks, closed vs open shop staff witnessing, shifts crossing midnight, multi-day jail sentences surfacing new gossip/cases during time served, randomly scrambled 5×5 grid junction IDs, and `save()`/`load()` backwards compatibility for snapshots missing `policeState` or `staff`.
   - One edge worth noting for Kotlin wiring: because `work_shift` now advances the clock internally and returns `day`/`hour` + deltas, `LoopGame.kt` will need to remove its follow-up `advance(hours, "work")` call and its dollar-to-cents conversions (`dollars(pay)` / `eraCents(fineAmount)`) when picking up the new `sim.js` bundle.
2. **Where could this break or disappoint when real town data hits it?**
   - In a 2-officer small town (day 06–14, swing 14–22), an arrest or shift ending at 23:00 leaves both officers `off_duty` until 06:00 unless jurisdiction heat reaches 75+ (real G009 shift behavior). `directivesFromPoliceState` still emits valid station-watch G007 plans for off-duty officers so Kotlin never receives an undefined `planSource`, though streets will genuinely be quieter late at night.
   - `LoopPlanWorld` in `policeBridge.ts` is an abstract step adapter for `advance()` bursts (no live pedestrian array), so officer spatial spotting during free roam remains driven by Kotlin calling `spotted(officerId, sighting)` as the player walks past officers in 3D.
3. **What did I trim or defer, and why?**
   - Left `arrest()` sentencing as the loop's stand-in until G010 court trials (`src/court/`) are wired in (per G012 scope), and did not touch `kotlin-street/` (strictly out of allowed files).

## Ideas for the team

- **Unify `LoopGame.kt` drop-in cleanup:** When re-bundling `sim.js` into `kotlin-street`, Claude can delete: (a) the Hilbert junction renumbering workaround for beats, (b) `LoopGame.toPlayer()` `"this person"` string replacement, (c) the duplicate `advance(hours, "work")` call after `work_shift`, (d) the manual cell-clock math after `arrest()`, and (e) the dollar↔cent conversion wrappers around `pay`, `value`, `playerCash`, and `fineAmount`.
- **Street-stop dialogue hook:** `SpotResult.stopOutcome` (`checkedPapers`, `questioned`, `outcome`, `reason`) gives the Android HUD everything it needs to play a short on-street stop beat ("Officer checks your papers, asks where you were Tuesday, and waves you on") without teleporting the player to the police station on a weak case.
- **Persistent officer positions across `advance()`:** In a future follow-up (or G018 checkpoints), persisting each officer's last street node on `PoliceState` instead of restarting from `beatNodeIds[0]` on each burst would let officers resume mid-block smoothly across short `advance(0.25)` ticks.
