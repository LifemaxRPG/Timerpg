# G008 Delivery — Evidence & Forensics by Era

## Summary

Extended the `src/crime/` core with deterministic, plain-JSON physical and digital evidence, era-gated forensic science (`FORENSIC_ERA`), long-horizon cold-case matching, time-travel timeline anomaly flags, and an explainable `caseStrength(policeCase, day)` prosecution scorer — while preserving 100% backward compatibility with all existing G005 APIs, save states, and the `< 5 ms` `advanceDay` performance budget.

## Files Added / Changed

- **New:** `src/crime/forensics.ts` — Single tunable `FORENSIC_ERA` table, capability tier helpers (`fingerprintTierForYear`, `dnaTierForYear`, `cameraTierForYear`, `plateTierForYear`, `classifySerialItem`, `itemHasSerialInEra`), and `detectTimeTravelAnomalies`.
- **New:** `src/crime/evidence.ts` — `leaveTraces`, `recordTraces`, `traceDailyRetention`, `decayTraceOneDay`, `destroyTraces` / `cleanScene`, `enrollOnFile`, `matchTrace`, `collectCrimeTraces`, `reexamineColdCases`, `findTimeTravelAnomalies`, and `caseStrength`.
- **New:** `src/crime/evidence.test.ts` — 9 tests covering era gates, all 7 trace kinds, 17-year print & 30-year DNA cold cases, prints-before-birth & pre-era-arrival anomalies, gloves/weather/tape-overwrite/scene-cleaning, `caseStrength` corroboration & conflict, determinism/JSON round-trip, and 2,000-person × 20-case performance with traces & files.
- **Updated (backward-compatible):**
  - `src/crime/types.ts` — Added evidence, identity-file, match, anomaly, and case-strength types plus optional `traces?`, `evidenceMatches?`, `timeAnomalies?` on `PoliceCase`, optional `traces?`, `identityFiles?` on `CrimeState`, and optional `weatherOf?`, `forensicEra?`, `eraArrivalYearOf?`, `birthYearOf?` on `CrimeContext`.
  - `src/crime/police.ts` — Preserves collected `traces`/`evidenceMatches`/`timeAnomalies` across witness report ingestion and uses `max(lastReportedDay, latestEvidenceMatchDay)` in `caseHeatOnDay` so solving a decades-old cold case immediately re-heats police awareness & wanted status.
  - `src/crime/advance.ts` — Decays uncollected scene traces with weather and re-examines unmatched collected traces on `advanceDay`, with zero overhead when `traces`/`identityFiles` are absent.
  - `src/crime/index.ts` — Re-exports `./forensics` and `./evidence`.
  - `docs/CRIME.md` — Added Section 8 documenting traces, `FORENSIC_ERA`, cold cases, anomalies, and `caseStrength`.

## Verification (Real Measured Results)

- **Typecheck:** `npm run typecheck` (`tsc --noEmit`) — **0 errors**.
- **Tests:** `npx vitest run src/crime` — **19 passed / 19 total** (all 10 existing G005 tests in `src/crime/crime.test.ts` + all 9 new G008 tests in `src/crime/evidence.test.ts`).
- **Determinism & JSON:** Verified zero `Math.random` / `Date.now` calls in `src/crime/`, identical `JSON.stringify` output across runs and across mid-run `JSON.parse(JSON.stringify(state))`.
- **Performance (`advanceDay` for 2,000 people & 20 active cases, budget < 5 ms):**
  - `src/crime/crime.test.ts` (`PERF_2000x20`): **3.09 ms median** isolated / **3.19 ms median** parallel (`activeLedgerEntries=965`).
  - `src/crime/evidence.test.ts` (`PERF_G008_2000x20`, with 93 physical traces, weather decay & 200 enrolled files): **2.65 ms median** isolated (`mean=2.52ms, max=4.57ms`) / **3.81 ms median** parallel.
  - Isolated `reexamineColdCases` overhead on 20 cases / 200 enrolled files: **0.026 ms** per tick.

## Usage Example

```ts
import {
  advanceDay, caseStrength, cleanScene, collectCrimeTraces,
  createCrimeState, enrollOnFile, findTimeTravelAnomalies,
  leaveTraces, recordCrime, recordTraces, witnessesOf,
} from '../src/crime';

let state = createCrimeState(world.seed, day);
const sightings = witnessesOf(crime, nearby, { seed: world.seed, world });
state = recordCrime(state, crime, sightings);

// 1. Leave physical/digital traces (respects gloves, weather, era plates/serials/cameras)
const traces = leaveTraces(crime, {
  seed: world.seed,
  gloved: false,
  weather: 'rain',
  cameras: nearbyCameras,
  vehiclePlate: 'CA-1965',
});
state = recordTraces(state, crime.id, traces);

// Optional: culprit attempts to wipe uncollected traces before police arrive
// state = cleanScene(state, crime.id, { method: 'clean', actorId: culpritId, day });

// 2. Police collect surviving traces into the evidence locker (stops weather decay)
state = collectCrimeTraces(state, world, crime.id, 'town_a', day + 1, { year: 1965 });

// 3. Years later, suspect is booked -> advanceDay automatically cracks cold cases
state = enrollOnFile(state, {
  id: 'booking-1982',
  personId: crime.culpritId,
  jurisdictionId: 'town_b',
  scope: 'local',
  reason: 'arrest',
  enrolledYear: 1982,
  enrolledDay: day + 6200,
  hasPrints: true,
  hasDna: false,
});
state = advanceDay(state, worldIn1982, day + 6201, { year: 1982 });

// 4. Inspect prosecution strength & any impossible time-travel matches
const c = state.jurisdictions.town_a!.cases[crime.id]!;
const strength = caseStrength(c, day + 6201);
const anomalies = findTimeTravelAnomalies(state, worldIn1982);
```

## Self-review (per `docs/queue/TEAM.md`)

1. **What could still go wrong or what assumptions did I make?**
   - `matchTrace` links traces directly to `trace.sourcePersonId` when that person has an accessible `IdentityFileRecord`; it does not simulate false-positive partial-print or lookalike-camera misidentifications (camera footage currently requires either high quality/HD + local record or corroborating traits rather than running a full facial-recognition false-match distribution across 2,000 citizens).
   - `reexamineColdCases` runs on every `advanceDay` when `state.identityFiles` is present; right now that costs ~0.026 ms for 20 cases, which is negligible, though a world accumulating thousands of centuries-old unsolved cold cases should eventually archive or throttle re-examination to only trigger when `identityFiles` changes or the calendar year crosses a `FORENSIC_ERA` threshold.
   - `caseHeatOnDay` re-heats a solved cold case from `matchedDay`; if the game later adds case closure/conviction (`status: 'closed'`), `policeKnowledge` should skip closed cases so convicted crimes stop generating patrol heat.

2. **Did I cut any corners?**
   - No shortcuts on core requirements: all 7 trace kinds, the single `FORENSIC_ERA` table, local vs. national vs. automated tiers, weather & retention decay, scene destruction immunity for collected traces, multi-decade cold cases, before-birth & before-arrival time-travel flags, and pure-witness vs. forensic `caseStrength` corroboration/conflict scoring are implemented and tested.
   - One intentional scope boundary: we left time-travel anomaly reactions (e.g. newspaper headlines, secret agency interest, detective confusion) to caller code as requested in `G008.md`.

3. **What was harder than expected, and what did I learn?**
   - Subtle interaction in `caseStrength`: `applyEvidenceMatchesToCase` promotes a strong physical match onto `policeCase.merged.namedSuspectId` so existing G005 queries (`isNamedSuspect`, `isWanted`) work seamlessly on zero-witness cases — which initially risked masking witness-vs-DNA contradictions inside `caseStrength`. Computing pure witness consensus directly from `mergeDescriptions(policeCase.reports)` inside `caseStrength` cleanly solved both needs without duplicating state on `PoliceCase`.
   - Ensuring `national_manual` (1924–1979) strictly requires `scope: 'national'` for cross-jurisdiction fingerprint cards while `automated` AFIS (1980+) unlocks cross-jurisdiction access to local cards caught a real era-gating bug during testing.

## Ideas for the Team

- **Cold-case Gazette stories & detective dialogue:** When `EvidenceMatch.coldCase === true` fires on `advanceDay` (especially with `yearsToMatch >= 10` or `impossibleTimeline === true`), the Gazette (`G004`) and NPC dialogue could generate a dramatic front-page cold-case breakthrough or "Impossible Fingerprint" conspiracy beat.
- **Forensic lab backlog / turnaround delay by era:** Right now `collectCrimeTraces` matches immediately on collection day; adding an optional `labDelayDays` (e.g., 21 days for mail-in 1930s FBI cards, 14 days for 1988 RFLP DNA, 1 day for 2015 AFIS) would give culprits a realistic window to flee or destroy other evidence before the lab call comes back.
- **Witness-vs-evidence alibi & wrongful-arrest arc:** Because `caseStrength` now explicitly emits `witness_physical_conflict` when an eyewitness names one person and DNA/prints match another, a future court/trial module can turn mistaken-identity arrests into exoneration storylines decades later when DNA arrives.

## Run Metrics

- Branch: `gemini/G008-evidence` (branched from `ccr-52e43ebb-xnmj2e` @ `78d4395`)
- Verification commands run: `npm run typecheck`, `npx vitest run src/crime` (19/19 passing across 3 consecutive runs)
- Task scope: G008 only (no edits to `docs/queue/` or non-crime modules)
