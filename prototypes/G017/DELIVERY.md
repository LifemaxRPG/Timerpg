# G017 Delivery — The Timeline Engine (`src/timeline/`)

Branch: `gemini/G017-timeline` (branched from `ccr-52e43ebb-xnmj2e`)

## What Was Built

Implemented the pure, deterministic, plain-JSON time-travel engine under `src/timeline/`, built directly on `src/sim/` (`runTimeline`, `worldAt`, `diffWorlds`, `describeDiff`, `rand`/`rngFor`), `src/crime/` (`CrimeKind`, `DEFAULT_SEVERITY`, `FORENSIC_ERA`, `caseHeatOnDay`, `caseStrength`, `detectTimeTravelAnomalies`, `fingerprintTierForYear`, `dnaTierForYear`), `src/police/` (`POLICE_ERA`, `forceSizeForTown`), and `src/court/` (`courtEraForYear`, `statuteForCrime`). Zero existing files outside the allowed scope were modified.

- **`src/timeline/types.ts`** — Plain-JSON interfaces for `Timeline`, `TimelineIntervention`, `PresentSnapshot`, `TimelineChangesReport`, `PlayerAnchorState`, `CarriedItem`, `NpcMemoryView`, `SelfEraseReport`, `TimelineCrimeRecord`, `CaseStatusReport`, `LooseThread`, `UnanchoredTraveller`, `TravellerTrace`, and `TimelineCacheStats`.
- **`src/timeline/interventions.ts`** — `createTimeline`, non-mutating `branch(timeline, intervention)`, `crimeEventToIntervention`, `mapToSimInterventions` (mapping all 8 `CrimeKind`s from `src/crime/types.ts` plus `arson`, `demolish`, `self_erase`, and all 6 native `src/sim` interventions), `compileSimInterventions`, and `recordTimelineCrime`.
- **`src/timeline/cache.ts`** — `presentAt(timeline, year)`, `clearTimelineCache`, `getTimelineCacheStats`, and `getSimulatedTimeline`. Caches both underlying `SimTimeline` runs (reusing the untouched baseline and any prefix sim covering `endYear >= year`) and final `PresentSnapshot`s keyed by `(seed, startYear, active interventions <= year, year)`. Builds `TimelineChangesReport` on top of `src/sim/diff.ts` (`diffWorlds` & `describeDiff`), adding `killedEarly`, `ownerChanges`, `businessesClosedEarly`, and physical building/lot reports (`neverBuilt`, `demolishedOrReplaced`, `newlyBuilt`).
- **`src/timeline/anchoring.ts`** — Rules 1–3 from `docs/IDEAS.md`:
  - Rule 1 (`addCarriedItem`, `addPlayerMemory`, `inspectCarriedItems`): carried items keep their history even when their maker or business is erased (e.g., 2025 coffee receipt from a never-born grandson).
  - Rule 2 (`recordPlayerInteraction`, `npcMemoryOfPlayer`): anchored NPCs re-form around the current timeline and remember only interactions from the active branch.
  - Rule 3 (`selfErase(timeline, year)`): wipes anchored identity facts (`birth_record`, `bank_account`, `fingerprints_on_file`, `dna_on_file`, `legal_identity`, `passport`, `job_references`, `family_ties`, plus `identityFiles` entries) and returns a structured `SelfEraseReport` listing concrete `losses` and `gains`.
- **`src/timeline/evidence.ts`** — `caseStatusAt(timeline, crimeId, year)` and `STATUTE_LIMIT_YEARS`:
  - Erasing a victim before the crime -> `status: 'never_happened'`.
  - Erasing the only witness -> witness testimony disappears (`erasedWitnesses`, `hasTestimony: false`, `status: 'no_evidence'` when no physical traces survive).
  - Fire/flood record loss (from explicit `record_fire`/`record_flood` interventions or simulated pre-1995 `flood` stories in `world.stories`) -> `status: 'records_lost'`.
  - Era-scaled cold-case aging, statutes of limitations (e.g. 1952 arson active in 1953, 73-year-old cold/expired case in 2025; murder has no statute and can reopen when AFIS/DNA arrives unless the player self-erased; pre-birth traces surface `detectTimeTravelAnomalies`).
- **`src/timeline/paradox.ts`** — `looseThreads(timeline, referenceYear?)`: never refuses paradoxes; flags causal disruptions (`professors_lab`, `time_machine`, `professors_letter`, `prevent_building`), `self_erased`, `orphan_item`, `erased_crime_memory`, `lineage_paradox`, and `chronology_anomaly` as plain facts with `journalLine` and `dialogueHook`.
- **`src/timeline/travellers.ts`** — `generateProfessorTrail(seed)` (deterministic 5–9 traces across invented continents and multiple eras, each pointing loosely to the next, never required to be found), `tracesAt(timeline, year, placeId)`, `addTraveller`, and `addTravellerTrace`. Stored outside the sim so no intervention ever removes them.
- **`src/timeline/index.ts`** — Barrel exports.
- **`src/timeline/timeline.test.ts`** — 12 Vitest tests covering every bullet in `G017.md` plus crime-kind mapping, fire/flood/statute/DNA reopening, and capital-town benchmark logging.
- **`docs/TIMELINE.md`** — Plain-JSON schemas and concrete call/response examples for every public function.

## Measured Replay Timing & Verification

### Replay Benchmark on Capital-Sized Town (`seed = 'gamma'`, 1950–2025, living pop = 231)

Measured in this Linux container (Node v22, `planTown('gamma')` pre-warmed once):
- **Raw `src/sim` `runTimeline('gamma', 1950..2025)` alone (no `src/timeline` code):** `715.2 ms` (warm) / `787.7 ms` (first run)
  - Breakdown inside `src/sim/simulate.ts`: `~232 ms` pure sim `step()`, `~220–370 ms` for 76× `advanceSettlement(world)`, `~180–356 ms` for 76× `structuredClone(world)`.
- **`presentAt(burned, 2025)` (1952 arson -> 2025 jump, cold miss on branch, cached baseline):** **`675.3–728.7 ms`** (our `src/timeline` diff + changes report layer adds **< 1 ms**; 99.9% of the time is inside `src/sim/simulate.ts`'s `runTimeline`, which G017 forbids editing).
- **`presentAt(burned, 1953)` (cold jump to 1953, 1950–1953):** **`41.7 ms`**.
- **`presentAt(burned, 1953)` after `2025` is cached (prefix sim cache hit, zero replay):** **`0.55–0.71 ms`**.
- **`presentAt(burned, 2025)` cache hit (zero replay):** **`0.023–0.033 ms`**.

Honest note on the ~200 ms target: our `src/timeline/` layer adds under 1 ms on top of `src/sim`, and cache hits are ~0.03 ms, but a full 76-year uncached replay of `'gamma'` takes ~675–730 ms on this sandbox CPU strictly because `src/sim/simulate.ts:runTimeline()` executes `advanceSettlement` and `structuredClone` on all 76 intermediate years. See **Ideas for the team** below for the tiny opt-in `src/sim` change that would drop this under 200 ms.

### Test & Typecheck Results

- `npx tsc --noEmit`: **0 errors**.
- `npx vitest run src/timeline src/sim src/crime src/police src/court`: **82 / 82 passed** across 10 test files:
  - `src/timeline/timeline.test.ts`: **12 / 12 passed**
  - `src/sim/` (`sim`, `archetype`, `knowledge`, `scenarios`, `verbs`): **32 / 32 passed**
  - `src/crime/` (`crime`, `evidence`): **19 / 19 passed**
  - `src/police/police.test.ts`: **9 / 9 passed**
  - `src/court/court.test.ts`: **10 / 10 passed**
- Runtime purity check: zero uses of `Date.now`, `Math.random`, `structuredClone`, `window`, `process`, or Node built-ins in `src/timeline/*.ts`.

## Usage

- Agent: Gemini ("Jeff") in AI Studio Build sandbox.
- Token counts / API cost: not exposed in this environment.
- Effort: single implementation pass + full typecheck, 82-test verification run, and capital replay profiling.

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   - Two honest weaknesses:
     1. A full 76-year uncached capital replay (`presentAt(burned, 2025)` on `'gamma'`) takes ~675–730 ms on this container CPU—over the ~200 ms target—because `runTimeline` in `src/sim/simulate.ts` clones the whole `World` and runs `advanceSettlement` for all 76 years, and G017 forbids editing `src/sim/`. I minimized everything under `src/timeline/`'s control: caching the baseline separately, caching full `SimTimeline` runs keyed only by active interventions `<= year` (so jumping to earlier years or adding future interventions never replays the past), and keeping our diff/report layer under 1 ms (~0.03 ms on cache hit).
     2. Mapping `arson` onto `src/sim`'s existing `sabotage` verb without editing `src/sim/` emits 12 `sabotage` hits in the arson year (`0.4^12`) so `economy()` reliably closes even wealthy stores below capital `< 5`. It produces clean `closedYear = year` behavior in the real sim, though the raw `sim.chronicle` logs 12 sabotage lines instead of one `"burned down"` line until `src/sim` gains a native `arson` / `close_business` verb.
2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked by running:** `tsc --noEmit`, all 12 new `src/timeline` tests, all 70 existing tests in `src/sim`, `src/crime`, `src/police`, and `src/court`, pure-JSON round-tripping (`JSON.parse(JSON.stringify(tl))`), and wall-clock profiling of `runTimeline` vs `presentAt` on `'gamma'`.
   - **Only reasoned about:** Phone embedded JS engine (QuickJS/V8 on Android) execution speed and memory footprint of keeping cached 76-year `SimTimeline` snapshot arrays in RAM across many simultaneous branches; a real phone session that branches 30+ times may want a small LRU cap (e.g., 16 entries) on `simCache`.
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   - The 1952-arson-to-2025 first encounter works end to end on the real town simulation: the store actually closes in 1952, its lot turns into a parking garage in the `changes` report, the carried coffee receipt survives in the player's pocket after its maker is erased, the arson file is active in 1953 and a 73-year-old cold case in 2025, `selfErase` prints a crisp losses-vs-gains breakdown, and the professor leaves a seeded trail across continents and eras that nothing can erase.

## Ideas for the team

1. **Make `runTimeline` under 200 ms on capital towns with two tiny options in `src/sim/simulate.ts`:** Our profiling showed `76 × structuredClone` (~180–356 ms) + `76 × advanceSettlement` (~220–370 ms) account for ~70% of `'gamma'`'s ~700 ms replay. Adding two optional fields to `RunOptions`—(a) `checkpoint?: World` so `src/timeline/cache.ts` can resume from the cached baseline snapshot at `earliestInterventionYear - 1` instead of replaying untouched decades, and (b) `onlyFinalSnapshot?: boolean` (skipping intermediate `structuredClone`s when only the target year is needed)—would drop a capital jump well below 200 ms without changing a single simulation rule.
2. **Native `close_business` / `arson` verb in `src/sim/types.ts`:** Adding `{ kind: 'arson'; year: number; businessId: Id }` directly to `src/sim` would let `src/sim`'s chronicle emit a single `"<Store> burned down"` event (and feed Gazette headlines) instead of `src/timeline` simulating arson via repeated `sabotage` hits.
3. **Wire `looseThreads()` directly into Qwen's NPC system prompt & the journal UI:** Every `LooseThread` already carries a short `journalLine` and `dialogueHook` ("If you show someone 'Coffee receipt', they won't recognize the name printed on it"). Passing active `dialogueHook`s into the NPC dialogue context lets townfolk react naturally when the player flashes an item from an erased timeline or asks about a lab that was never built.
