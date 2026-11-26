# G019 Delivery: Your court in the playable loop

## What was built

1. **From arrest to court (`src/loop/facade.ts`, `src/loop/courtBridge.ts`):**
   - Kept street-stop release (`STOP_POLICY` / `releasedAfterStop`) and petty first-offense misdemeanor summary fines (`severity < 0.35`, non-felony, 0 prior convictions, `!demandTrial`) intact.
   - All chargeable felonies (`burglary`, `car_theft`, `robbery`, `assault`, `murder`, `bribery`, `intimidation`, `perjury`, etc.), repeat offenses, serious misdemeanors (`severity >= 0.35`), and `demandTrial: true` arrests now return `penalty: 'charged'` with a real `LoopCourtCase`:
     - Real G010 statutory charges (`chargesFromCase`),
     - Era-scaled integer-cent bail (`evaluateBail` converted cleanly to cents, or held without bail with `bail.granted: false`, `custody: 'remanded_no_bail'` for murder / extreme flight risk),
     - A scheduled first court date in game time (`currentDay + era.arraignmentDays` at 9:00),
     - Pre-computed `pleaOffer` (capped below even a lenient judge's trial sentence so a guilty plea is guaranteed lighter than a guilty trial verdict on the same case),
     - All four era-scaled counsel options (`public_defender`, `local_attorney`, `retained_counsel`, `elite_defense`), pre-selected jury panel (`selectJury`), and prosecution `witnessIds`.
   - `activeCrimeStateForPolice(sim)` suppresses crimes currently on a `pending_trial` docket (unless `warrant_for_fta`) so an out-on-bail defendant is not immediately re-arrested for the same already-booked charge while awaiting court.
   - `act()` also accepts `kind: 'take'` with `subkind: 'robbery'`, `kind: 'assault'` (`weapon`, `lethal` -> `murder`), and `kind: 'court_interference'`.

2. **Plain-JSON player choices (`src/loop/facade.ts`, `src/loop/courtBridge.ts`, `src/loop/index.ts`, `src/loop/bridge.ts`):**
   - `bailDecision(sim, { courtCaseId?, choice: 'pay_bail' | 'stay_in_cell', playerCash? })`
   - `chooseCounsel(sim, { courtCaseId?, tier?, playerCash? })` — explicit tier or automatic best affordable tier from `playerCash`, re-running `selectJury` with the new counsel's peremptory strikes (while preserving jury composition if juror tampering already occurred)
   - `plead(sim, { courtCaseId?, plea: 'guilty' | 'not_guilty', serveSentence? })` — guilty plea records conviction, resolves crimes, and serves `jailHours` through `advance()`
   - `courtInterfere(sim, { courtCaseId?, kind, targetId?, placeId?, bribeAmount?, bystanders?, caughtInCourt? })` (also via `act({ kind: 'court_interference', ... })`) — routes through G010 `commitTrialInterference` to open real G013 `bribery`, `intimidation`, and `perjury` crimes and live police cases
   - `courtView(sim)` — returns `{ pending, resolved, activeWarrants }`
   - Every new call is exposed on `src/loop/index.ts`, `globalThis.TimerpgSim`, stateless `*Json` wrappers, and `globalThis.simCall`, and documented in `docs/LOOP_API.md`.

3. **Trials on their court date & missed-date warrants (`src/loop/facade.ts`, `src/loop/courtBridge.ts`):**
   - `advance(sim, hours, options?)` detects the earliest due `pending_trial` court date within the step, advances to the exact court date/hour, and runs the trial via G010/G013 `runTrial()`.
   - Returns plain-English `facts[]`, deliberation rounds, vote split, verdict (`guilty | not_guilty | hung`), judge/sentence (cents + jail days) or retrial date in `AdvanceResult.courtEvents`.
   - Guilty trial verdicts serve `jailHours` through `advance()` (unless `options.skipSentenceAdvance: true`) and prune resolved crimes/jury pools on `save()`.
   - Missing the court date (`out_on_bail` with `options.attendCourt === false` or `options.playerPlaceId !== courtPlaceId`) forfeits bail, creates an active `LoopWarrantRecord`, re-exposes the underlying case with a high-confidence Failure-to-Appear report, and steps police immediately so officers dispatch on the warrant. In-custody defendants are brought to court automatically. Re-arrest executes active warrants and feeds `priorEscape: true` into bail evaluation.

## Files changed

- `src/loop/types.ts` — court docket, warrant, trial event, and choice input/output types; `'courthouse'` place kind; `'charged'` arrest penalty; `AdvanceOptions` / `AdvanceResult.courtEvents`; `robbery`, `assault`, `court_interference` action inputs.
- `src/loop/courtBridge.ts` (new) — integration layer over `src/court/` (`chargesFromCase`, `evaluateBail`, `makePleaOffer`, `hireDefenseLawyer`, `lawyerFeeForTier`, `selectJury`, `commitTrialInterference`, `runTrial`, `courtEraForYear`), cent↔dollar era conversions, DA prosecution witness selection (`selectProsecutionReports`), warrant issuance, and resolved-case pruning.
- `src/loop/facade.ts` — court routing in `arrest()`, court-date splitting and trial/warrant execution in `advance()`, `bailDecision`, `chooseCounsel`, `plead`, `courtInterfere`, `courtView`, `assault`/`robbery`/`court_interference` in `act()`, and `pruneResolvedCourtCases` in `pruneResolvedState()`.
- `src/loop/index.ts` — exports new court functions and types.
- `src/loop/bridge.ts` — adds stateful, stateless `*Json`, and `simCall` wrappers for all 5 new court calls.
- `src/loop/court.test.ts` (new) — 8 end-to-end tests covering every G019 requirement plus QuickJS bridge and in-custody attendance.
- `src/loop/loop.test.ts` — updated 3 legacy stand-in felony arrest assertions (car theft repeat offense, burglary jail-clock advance, strong burglary stop-to-arrest) to assert `penalty: 'charged'` + `courtCase` (and plead guilty where jail-clock progression / conviction count is tested).
- `src/loop/booking.test.ts` — updated the 100-day save-pruning loop to plead guilty on charged burglary cycles so those cycles still resolve and exercise resolved-crime pruning.
- `docs/LOOP_API.md` — updated clock ownership, money units, QuickJS bundle method list, `advance()`, `arrest()`, and added sections 11–15 for the new court calls.
- `src/court/`: **0 files changed.**

## Verification

- **TypeScript (`tsc --noEmit`):** 0 errors.
- **Vitest (`vitest run --no-file-parallelism src/loop src/court src/crime src/police src/economy`):** **17/17 test files, 116/116 tests passed** in 12.00s:
  - `src/loop/court.test.ts`: **8/8 passed** (thin-case release / solid bail / worst held without bail; bail & budget-aware counsel & `courtView`; guilty plea strictly lighter than guilty verdict on the same case; trial on date serving jail sentence + missed date warrant & police dispatch + re-arrest warrant execution; bribing a juror opens a `bribery` case + witness `intimidation` + `perjury`; save between arrest and trial round-trips losslessly and continues bit-for-bit identically; `TimerpgSim` / `*Json` / `simCall` bridge; in-custody defendant brought to court automatically).
  - `src/loop/loop.test.ts`: **20/20 passed**.
  - `src/loop/booking.test.ts`: **9/9 passed** (`[G014 100-day save]` total `242642B -> 136588B (-43.7%)`, `crimeState -66.5%`).
  - `src/loop/loop.perf.test.ts`: **1/1 passed** (`300 people, advance(sim, 24): median=2.99ms, mean=3.55ms, max=5.07ms`).
  - `src/court/court.test.ts`: **13/13 passed** (untouched).
  - `src/crime/`, `src/police/`, `src/economy/`, remaining `src/loop/bridge.*.test.ts`: **65/65 passed**.
- **QuickJS-ng bundle (`esbuild src/loop/bridge.ts --bundle --platform=neutral --target=es2020 --format=iife`):** **527,847 bytes (515.5 kb)**, `0` disallowed globals (`Math.random`, `Date.now`, `process.env`, `require(`), verified in a clean Node `vm` sandbox exposing all 5 new court methods + `*Json` wrappers + `simCall`.

## Usage

- Environment: sandboxed Linux container (`node v22.23.5`, `vitest 2.1.9`, `esbuild`, `tsc`).
- Branch: `gemini/G019-loop-court` off `599ddbf` (`ccr-52e43ebb-xnmj2e`).
- One early WIP commit pushed (`0115601`) followed by final commit with tests, docs, and delivery notes.

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   During live end-to-end testing in `src/loop`, we caught a real cross-module interaction bug: across the 18-day arraignment window between arrest and trial, town gossip generates 15–20 second-hand hearsay callers in `policeCase.reports`. Passing all 20 raw reports directly into `src/court/trial.ts` caused `runTrial`'s per-witness cross-examination penalty (capped at `marginalWitness + 0.08` while diminishing returns pushed later witnesses' `marginalWitness` near `0.001`) to subtract over `1.0` from `perceivedGuiltScore` — turning an airtight 3-eyewitness + fingerprint + recovered-item burglary into a `0-12` acquittal (`score = 0.043`). We fixed this in `src/loop/courtBridge.ts` (`selectProsecutionReports`) so the DA calls the strongest first-hand eyewitnesses (up to 4, falling back to at most 2 second-hand witnesses only when fewer than 2 eyewitnesses exist, plus any witness explicitly targeted by tampering) without touching `src/court/`. The remaining weakness is that `LoopSim` itself does not own the player's wallet balance (consistent with `arrest.playerCash`), so callers in `kotlin-street` must still deduct `bailPaidCents`, `chooseCounsel.feeCents`, `plead.fineAmount`, and `courtEvents[*].sentence.fineAmount` from the player's wallet on their side.

2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked by running:** All 116 Vitest tests across `src/loop`, `src/court`, `src/crime`, `src/police`, and `src/economy`, full `tsc --noEmit`, live trial score/event traces across the 18-day arraignment window, and the neutral ES2020 IIFE bundle loaded and inspected inside an isolated JS VM sandbox.
   - **Only reasoned about:** Live QuickJS-ng execution on Android inside `kotlin-street` (no Android/JVM build environment in this sandbox), and real UI flow for walking into the courthouse building on `courtDateDay` (`AdvanceOptions.playerPlaceId` is wired and unit-tested, but the Kotlin HUD/door trigger is out of scope for G019).

3. **If the owner looked at this for 10 seconds, what would they notice first?**
   They would notice that getting arrested for a serious crime no longer vanishes in a one-line stand-in penalty: you are booked, charged with a real statute and era-scaled bail (or remanded without bail for murder), given a real court date two weeks out, able to hire counsel within your budget, take a genuinely lighter plea deal, try bribing a seated juror (and immediately catch a live `bribery` police case), or skip court and have police actively hunt you on a bench warrant.

## Ideas for the team

1. **Hearsay cap inside `src/court/trial.ts` (follow-up hardening):** Our `selectProsecutionReports` filter in `src/loop/courtBridge.ts` solves the 17-gossiper cross-exam collapse cleanly for the playable loop, and keeps `src/court/` untouched here, but any future caller that passes a long-running `PoliceCase` straight to `runTrial()` could hit the same diminishing-returns vs flat-`-0.08` cross-examination asymmetry. Adding a `maxSecondHandWitnesses` cap (or scaling `credibilityDrop` proportionally to `marginalWitness`) inside `src/court/trial.ts` would harden the core engine for every caller.
2. **Bail refund on acquittal / completion & bondsman 10% option:** Right now `bailDecision` tracks `bailPaidCents` and `bailForfeitedCents` on FTA. Adding a `bailRefundedCents` field on `LoopTrialEventSummary` when a defendant shows up for court (or a 1920s–1990s bail bondsman option at 10% non-refundable fee + bounty-hunter risk on FTA) would make making bail feel meaningfully different from paying a fine.
3. **Courtroom hearing beats for the NPU dialogue layer:** `runTrial()` already emits rich phase-by-phase `events[]` (opening, each witness testimony, specific memory mistakes exposed on cross-examination, forensic exhibits, deliberation rounds). Exposing an optional compact `trialHighlights[]` on `LoopTrialEventSummary` would let the phone's NPU narrator voice the actual cross-examination moment ("Your lawyer caught Clara Bell claiming your coat was blue when it was green") without bloating saves.
