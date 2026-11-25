# G014 Delivery — Booking, Civilians Who Call the Police, Small Saves, and Fence Informants

- **Task ID:** `G014`
- **Branch:** `gemini/G014-booking`
- **Base commit:** `bddfe1c6204d95a7b813a4d43c74f43dcd4bbcea` (`ccr-52e43ebb-xnmj2e`)

## What Was Built

1. **Booking pipeline (`src/loop/facade.ts`, `src/loop/types.ts`):**
   - Added `bookSuspectAtStation()` invoked both on `arrest()` when `transportedToStation: true` and automatically during `advance()` when a G009 police officer completes station transport (`transportedToStation: true`, surfaced in `AdvanceResult.bookingEvents`).
   - Enrolls fingerprints and era-gated DNA into G008 `crimeState.identityFiles` via `enrollOnFile` using `FORENSIC_ERA` (`fingerprintLocalYear = 1910`, `fingerprintNationalYear = 1924`, `dnaAnalysisYear = 1987`). Pre-1910 arrests still book at the station without prints or DNA.
   - Runs G008 `reexamineColdCases()` immediately at booking and continues re-examining on subsequent ticks/days, so both already-reported unsolved cases and previously unreported cases crack automatically once reported.
   - Checks unresolved pawn-ledger links (`pawnRecords` referencing stolen items) and emits plain-English `bookingFindings: string[]` plus structured `booking?: BookingReport` (`printsEnrolled`, `dnaEnrolled`, `scope`, `coldCaseHits`, `pawnLinks`, `findings`) on `arrest()` (and `bookingEvents?: BookingReport[]` on `advance()`).

2. **Civilians call it in (`civilianSpotted`, `TimerpgSim.civilianSpotted`, `civilianSpottedJson`):**
   - Added `civilianSpotted(sim, personId, sighting?)` (plus QuickJS bridge `TimerpgSim.civilianSpotted(personId, sightingJson)`, `civilianSpottedJson`, and `simCall` dispatch).
   - Evaluates `attitude(sim, personId)` first: if `stance !== 'report_on_sight'` or line-of-sight / distance / visibility fails, returns `reported: false` without mutating state.
   - When a hostile witness sees the player, queues G005 `PendingReport` entries for their known unresolved crimes involving the player using `reportLatencyForEra(year, stationDistanceKm, roll)` (recording exact sub-day `arrivalTotalHours` in `pendingReportMeta` and `latestSightingPlaceByCrime`).
   - Updated `advance()` to deliver due timed reports across both multi-day and sub-day (`daysElapsed === 0`) ticks while keeping not-yet-due timed reports shielded from premature `advanceDay` delivery, and to clear stale `respondedCases` / re-arm idling officers so a fresh sighting dispatches an officer to the newly sighted location via `policeBridge.ts`'s `lastSeenNodeOf`.

3. **Small saves (`pruneResolvedState` at `arrest()` and `save()`):**
   - Implemented `pruneResolvedState(sim)` and wired it into both `arrest()` and `save()`.
   - Strips resolved-crime bloat from `crimeState` (`crimes`, `ledger`, `toldPairs`, `traces`, `pendingReports`, resolved `jurisdictions[*].cases`), `policeState` (resolved `respondedCases`, expired `backupAlerts`), `pawnRecords`, disposed `stolenItems`, stale `pendingReportMeta`, and `latestSightingPlaceByCrime`, plus non-empty `toldPairs` left behind by completely forgotten unresolved crimes.
   - Preserves compact 3-field `resolvedCrimeMemories` (`{ s, h?, v? }`) so `attitude()` continues to report past resolved crimes identically, while leaving `identityFiles`, `priorOffenses`, and all active unresolved crimes completely intact.

4. **A fence who informs (`act({ kind: 'fence_sale', ... })` and `tipOff`):**
   - Integrated Sam's `src/economy/fence.ts` (`fenceDeal`, `fenceRisk`, `Fence`) into `act({ kind: 'fence_sale', ... })` and exposed standalone `tipOff(sim, input)` (plus `TimerpgSim.tipOff`, `tipOffJson`, and `simCall`).
   - Clean fence deals track per-fence deal history in `familiarity[fenceId]` (`fenceDeals`, `fenceRefusals`, `informedPolice`), build trust and `friendly` stance without triggering pawn-shop serial registries, and respect `fenceDeal()`'s `too_hot` heat refusal (`wary` stance).
   - When `fenceRisk()` fires on a hot deal (or `tipOff()` is called directly), the fence's tip flows through the same era-delayed `reportLatencyForEra` reporting and officer-dispatch pipeline as `civilianSpotted`, flags any referenced item as traced to the player, and shifts the fence's `attitude()` to reflect the betrayal.

## Files Touched

- `src/loop/types.ts` — Added `BookingColdCaseHit`, `BookingReport`, `CivilianSightingInput`, `CivilianSpotResult`, `TipOffInput`, `TipOffResult`, `ReportMeans`, `ResolvedCrimeMemory`, `PendingReportMeta`; extended `PlayerFamiliarity`, `LoopSim`, `LoopActionEvent` (`fence_sale`), `ActResult`, `AdvanceResult`, and `ArrestOutcomeResult`.
- `src/loop/policeBridge.ts` — Wired optional `latestSightingPlaceByCrime` into `stepLoopPolice`'s `lastSeenNodeOf` lookup.
- `src/loop/facade.ts` — Implemented booking pipeline, sub-day timed report delivery, `civilianSpotted`, `tipOff`, `fence_sale`, `pruneResolvedState`, `resolvedCrimeMemories` support in `attitude()`, and automatic station booking in `advance()`.
- `src/loop/index.ts` — Exported `civilianSpotted`, `tipOff`, and `pruneResolvedState`.
- `src/loop/bridge.ts` — Added `civilianSpottedJson`, `tipOffJson`, `TimerpgSim.civilianSpotted`, and `TimerpgSim.tipOff` (auto-dispatched by `simCall`).
- `src/loop/booking.test.ts` — New 9-test suite covering all four G014 deliverables, era comparisons, QuickJS bridge calls, and the 100-day save benchmark.
- `docs/LOOP_API.md` — Documented `fence_sale`, booking outputs (`booking`, `bookingFindings`, `bookingEvents`), `civilianSpotted`, `tipOff`, and compact saves / `pruneResolvedState`.
- `prototypes/G014/DELIVERY.md` — This delivery note.
- **`src/crime/` and `src/police/`:** **0 changes needed** (`reportLatencyForEra`, `enrollOnFile`, `reexamineColdCases`, and `FORENSIC_ERA` were already exported).

## Measurements

### 100-Day Save Size (Before vs After Pruning)

Measured in `src/loop/booking.test.ts` on a 111-day simulation with 8 resolved crimes and 2 active unresolved crimes (`day=111`, 100+ days of gossip and police activity):

| Metric | Before Pruning (`JSON.stringify` raw state) | After `pruneResolvedState` / `save()` | Delta |
| --- | ---: | ---: | ---: |
| **Total save JSON** | **231,985 B** | **109,654 B** | **-122,331 B (-52.7%)** |
| **`crimeState` JSON** | **181,808 B** | **59,492 B** | **-122,316 B (-67.3%)** |

Verified that `load(save(sim))` preserves identical `attitude()` outputs on both resolved and active witnesses, identical `policeView()` active cases, identical `identityFiles`, and identical 10-day continuation (`advance`, `attitude`, `policeView`) vs the unpruned simulation.

### Era Reporting Latency (`civilianSpotted`, 1925 vs 1990)

Measured in `src/loop/booking.test.ts` across 15 deterministic seeds (`n=15`) when a shopkeeper spots the player two days after a theft:

- **1925:** `avg = 5.66 h`
- **1990:** `avg = 1.29 h`
- **1925 > 1990 in 14 / 15 seeds**

## Verification

- `npx tsc --noEmit`: **0 errors**.
- `npx vitest run src/loop src/crime src/police src/court`: **11/11 test files, 80/80 tests passed** (including all **9/9** new tests in `src/loop/booking.test.ts`, **20/20** in `src/loop/loop.test.ts`, **1/1** in `src/loop/loop.perf.test.ts`, **9/9** in `src/loop/bridge.*.test.ts`, **10/10** in `src/crime/crime.test.ts`, **9/9** in `src/crime/evidence.test.ts`, **9/9** in `src/police/police.test.ts`, and **13/13** in `src/court/court.test.ts`).
- `npx vitest run --no-file-parallelism src/loop src/crime src/police src/court src/economy src/plan`: **16/17 test files, 122/123 tests passed** (all **16/16** files / **108/108** tests in `src/loop`, `src/crime`, `src/police`, `src/court`, and `src/economy` passed — including `src/economy/fence.test.ts` **4/4** and `src/loop/loop.perf.test.ts` median **3.19 ms** — while untouched `src/plan/plan.test.ts` hit container CPU jitter on its 0.05 ms micro-benchmark: measured 0.0604 ms; `src/plan/` was not modified).
- QuickJS-ng bundle check (`esbuild src/loop/bridge.ts --bundle --platform=neutral --target=es2020 --format=iife`): **414.5 KB** in 55 ms, **0** disallowed globals (`Math.random`, `Date.now`, `process.env`, `require(`).

## Usage

```ts
import {
  TimerpgSim,
  act,
  advance,
  arrest,
  civilianSpotted,
  create,
  pruneResolvedState,
  save,
  tipOff,
} from './src/loop';

// 1. Booking on arrest (or automatically in advance() on transportedToStation)
const arr = arrest(sim, {
  officerId: 'off:1',
  why: 'window_break',
  fineAmount: 5000,
  jailHours: 24,
});
console.log(arr.bookingFindings);
// ["Fingerprints taken at booking and filed with national records.",
//  "Fingerprints matched an unsolved burglary at Meridian Watch & Loan.",
//  "Pawn ledger at Meridian Watch & Loan linked gold pocket watch (serial PW-78-0042) to you."]

// 2. Civilian spots the player and calls it in with era delay
const spot = civilianSpotted(sim, 'npc:baker', {
  placeId: 'place:market',
  distanceMeters: 8,
  lineOfSight: true,
});
// Advance hours until spot.arrivalDay / spot.arrivalHour to ingest report & dispatch officer
const adv = advance(spot.sim, 6);

// 3. Fence sale (can build trust, refuse when too_hot, or trigger an informant tip)
const sale = act(sim, {
  kind: 'fence_sale',
  placeId: 'place:backroom',
  fenceId: 'npc:fence_1',
  itemId: 'item:watch_1',
  fence: { id: 'npc:fence_1', name: 'Sal', buyRate: 0.4, maxHeat: 0.75, informant: true, specialties: ['jewelry'] },
});
// Or trigger an informant tip directly (also exposed on TimerpgSim.tipOff / simCall)
const tip = tipOff(sale.sim, { informantId: 'npc:fence_1', itemId: 'item:watch_1' });

// 4. Compact save (prunes resolved crimes automatically)
const compactJson = save(tip.sim);
```

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   - Sub-day timed report delivery in `advance()` had to coexist with G005's day-granularity `advanceDay()`. Originally `advance()` only ran `advanceDay()` when `daysElapsed > 0`, which collapsed 1925 (5.66 h) and 1990 (1.29 h) calls into the same next-day boundary if stepped hour-by-hour, while letting `advanceDay()` ingest timed reports early if `arrivalDay === day` before `arrivalHour`. I added explicit `pendingReportMeta[reportId].arrivalTotalHours` tracking plus a `deliverDueTimedReports()` helper that strips not-yet-due timed reports during `advanceDay()` and delivers them at their exact hour on both intra-day and multi-day `advance()` calls, then verified the 1925 vs 1990 arrival curve across 15 seeds.
   - A second honest limitation on save size: `resolvedCrimeMemories` still keeps a small 3-field witness entry (`{ s, h?, v? }`) per resolved crime so `attitude()` stays lossless, and `sim.world` itself is serialized verbatim (~50 KB of static town people/places/roads). Cutting `crimeState` by 67.3% halves a 100-day save today, but a multi-decade save with hundreds of resolved crimes will eventually benefit from capping/aging `resolvedCrimeMemories` and reconstructing static world data from seed.

2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked by running:** `tsc --noEmit` (0 errors); 11 core Vitest suites (`src/loop`, `src/crime`, `src/police`, `src/court`: 80/80 passed) plus all 5 `src/economy` suites (28/28 passed; 108/108 across all 16 `loop`/`crime`/`police`/`court`/`economy` suites); 9 new G014 integration tests covering 1895/1978/1995 booking & cold-case cracking (both immediate and next-tick), G009 `transportedToStation` auto-booking, 15-seed 1925 vs 1990 `civilianSpotted` latency + officer dispatch, 111-day save size reduction + lossless round-trip & 10-day continuation, clean vs `too_hot` vs informant `fence_sale` + `tipOff`, and `TimerpgSim` / `simCall` JSON bridge paths; and an `esbuild` QuickJS-ng neutral bundle check (414.5 KB, 0 disallowed globals).
   - **Only reasoned about (not run here):** Live Android / QuickJS-ng JNI execution inside `kotlin-street/`, and the unrelated slow town-plat/GLB suites (`src/town/glbSites.test.ts`, `src/town/plat.test.ts`), which do not import `src/loop/`. Note also that container CPU jitter occasionally tripped untouched micro-benchmarks (`src/crime/crime.test.ts` 5 ms target ranged 3.51–6.75 ms across runs; `src/plan/plan.test.ts` 0.05 ms target measured 0.0604 ms serially and 0.109 ms in parallel; neither `src/crime/` nor `src/plan/` was modified).

3. **If the owner looked at this for 10 seconds, what would they notice first?**
   - The player-facing booking report ("Fingerprints taken at booking and filed with national records. Fingerprints matched an unsolved burglary at ... Pawn ledger at ... linked gold pocket watch to you.") and the concrete 100-day save shrinkage (232 KB down to 110 KB, -52.7% total / -67.3% `crimeState`) — both directly visible in the test output and ready for the Android UI to print verbatim.

## Ideas for the team

1. **Seed-reconstructed saves (`world` delta saves):** In our 111-day save benchmark, `crimeState` dropped from 182 KB to 59 KB, leaving static `world` data (people, social graph, street graph, places) as nearly half of the remaining 110 KB. Storing only `worldSeed` + dynamic diffs (added NPCs, changed jobs/trust, player cash/inventory) could shrink a 100-day save from ~110 KB down to ~25–35 KB for fast Android checkpointing.
2. **Sting operations from burned fences:** Right now an informant fence files a tip with `tipOff()` that sends an officer to the last known spot. A natural follow-up is a "scheduled buy" sting: an informant fence tells the player "bring the rest back tomorrow at 10 p.m." and secretly tips off a detective to stake out the back room via G009's existing `stakeout` plan.

## Usage

- **Model and setting:** Gemini (AI Studio Build agent; exact model tier/reasoning setting not visible to the agent).
- **Tokens:** not visible.
- **Effort:** 1 implementation pass across 2 compacted sessions (~18 tool calls in implementation + verification/delivery), ~25 minutes wall-clock excluding compaction wait.
- **Plan usage:** not visible.
- **Rework:** First delivery (round 1, not a revision).
