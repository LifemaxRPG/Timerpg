# G013 Delivery — Fairer Lawyers & Crime Kinds for Court Crimes

Branch: `gemini/G013-court-tuning`

## 1. What Was Built

### Fairer Lawyers (`src/court/law.ts`, `src/court/trial.ts`)
Previously, on the contested case in `src/court/court.test.ts`, defense budget decided the verdict outright (`public_defender` = `0.0%` acquitted / `100.0%` guilty; `elite_defense` = `100.0%` acquitted / `0.0%` guilty).

Changes made:
- **Realistic counsel tiers (`src/court/law.ts`):** Raised `public_defender` baseline skills (funded: `prep 0.42`, `crossSkill 0.46`, `persuasion 0.42`; pre-1950 unpaid: `0.34`/`0.38`) and tightened the tier spread up to `elite_defense` (`0.88`/`0.90`/`0.86`), keeping strict monotonic ordering across all four tiers and peremptory strikes (`3 / 4 / 5 / 6`).
- **Physical-evidence resistance (`src/court/trial.ts`):** Added `physicalDamp = 1 / (1 + 0.75 * incriminatingPhysicalWeight)` on defense case presentation, closing arguments, and juror rhetoric tilt so expensive counsel shines most on witness-only and contested cases while hard physical forensics resist pure courtroom rhetoric.
- **Smooth cross-examination & courtroom variance (`src/court/trial.ts`):** Replaced the hard `crossSkill >= 0.70` auto-catch cliff with a smooth probability curve (`0.62 + 0.34 * crossSkill`), rebalanced witness vs. fingerprint weights (`first_hand` `0.46`, `fingerprint` `0.37`), added a small no-physical-evidence juror reasonable-doubt buffer, and added bounded, zero-mean deterministic trial-day variance (`trialPoiseNoise` coupled across tiers per seed + small `counselDayNoise`) so verdicts reflect real courtroom variance instead of a razor-sharp threshold step.

### 60-Seed Verdict Spreads (1978, `src/court/court.test.ts`)

**Before G013 (G010 contested baseline):**
| Tier | Acquitted | Hung | Guilty |
| --- | ---: | ---: | ---: |
| `public_defender` | 0.0% | 0.0% | 100.0% |
| `local_attorney` | 0.0% | 73.3% | 26.7% |
| `retained_counsel` | 98.3% | 1.7% | 0.0% |
| `elite_defense` | 100.0% | 0.0% | 0.0% |

**After G013 — Weak case (two eyewitnesses, one description mistake, no physical evidence):**
| Tier | Acquitted | Hung | Guilty |
| --- | ---: | ---: | ---: |
| `public_defender` | 65.0% | 30.0% | 5.0% |
| `local_attorney` | 80.0% | 18.3% | 1.7% |
| `retained_counsel` | 90.0% | 10.0% | 0.0% |
| `elite_defense` | 95.0% | 5.0% | 0.0% |

*(First-encounter check: on the weak case with only a `public_defender`, 14 of the 60 seeds end in a 2-round deliberation followed by a unanimous `not_guilty` verdict.)*

**After G013 — Contested case (exact G010 test: two eyewitnesses with 1 mistake each + partial `0.62` fingerprint):**
| Tier | Acquitted | Hung | Guilty |
| --- | ---: | ---: | ---: |
| `public_defender` | 8.3% | 33.3% | 58.3% |
| `local_attorney` | 16.7% | 46.7% | 36.7% |
| `retained_counsel` | 33.3% | 53.3% | 13.3% |
| `elite_defense` | 58.3% | 40.0% | 1.7% |

**After G013 — Strong case with physical evidence (two solid eyewitnesses + strong `0.86` fingerprint):**
| Tier | Acquitted | Hung | Guilty |
| --- | ---: | ---: | ---: |
| `public_defender` | 0.0% | 3.3% | 96.7% |
| `local_attorney` | 0.0% | 3.3% | 96.7% |
| `retained_counsel` | 0.0% | 6.7% | 93.3% |
| `elite_defense` | 0.0% | 10.0% | 90.0% |

### Dedicated Crime Kinds for Court Crimes
Added `'bribery'`, `'perjury'`, and `'intimidation'` to `CrimeKind` and replaced the `'theft'` / `'assault'` stand-ins in `commitTrialInterference` (`src/court/trial.ts`):
- `bribe_juror` / `bribe_witness` -> `'bribery'` (`DEFAULT_SEVERITY = 0.64`, `CRIME_CONSPICUOUSNESS = 0.30`).
- `perjury` -> `'perjury'` (`DEFAULT_SEVERITY = 0.56`, `CRIME_CONSPICUOUSNESS = 0.38`), and caught-in-court perjury now reliably opens a `'perjury'` police case even when `targetId` is a synthetic court-officer ID not present in `world.people`.
- `threaten_juror` / `threaten_witness` -> `'intimidation'` (`DEFAULT_SEVERITY = 0.70`, `CRIME_CONSPICUOUSNESS = 0.56`).
- Added dedicated felony statutes for `'bribery'`, `'perjury'`, and `'intimidation'` in `BASE_STATUTES` (`src/court/law.ts`).

### Listed Changes Outside `src/court/`
1. **`src/crime/types.ts`** *(allowed, listed)*: Added `'perjury' | 'bribery' | 'intimidation'` to `CrimeKind`, `DEFAULT_SEVERITY`, and `CRIME_CONSPICUOUSNESS`.
2. **`src/crime/reporting.ts`** *(allowed, listed)*: Added `CRIME_REPORTING_MODIFIERS` table **only** for `'bribery'`, `'perjury'`, and `'intimidation'` (leaving all 8 original street/property crime kinds untouched). Targets of intimidation/bribery skip the `>= 0.90` violent/property victim floor and scale by personal `diligence`, while third-party witnesses still report reliably (slightly boosted for intimidation) and perjury hearsay reports rarely.
3. **`src/timeline/evidence.ts` and `src/timeline/interventions.ts`** *(minimal TS-exhaustiveness update)*: Because G017 merged `TimelineCrimeKind = CrimeKind | 'arson'` with exhaustive `Record<TimelineCrimeKind, ...>` tables (`STATUTE_LIMIT_YEARS`, `BASE_ACTIVE_YEARS`) and an exhaustive `switch (crimeKind)` in `mapCrimeKindToSim`, adding the three new `CrimeKind` literals requires matching entries in those two files for `tsc --noEmit` to compile cleanly. Added only the three `'bribery'`, `'perjury'`, `'intimidation'` entries — no other timeline logic touched.

### Updated Existing G010 Test Expectation
- In `src/court/court.test.ts` (`records perjury caught in court and witness intimidation as real crimes in CrimeState`), updated the old stand-in assertion `expect(threatRes.crimeState.crimes[threatRes.crimeId]!.kind).toBe('assault')` to `'intimidation'`, and added matching `kind` assertions (`'bribery'`, `'perjury'`, `'intimidation'`) on both `crimes` and `jurisdictions['jur:capital'].cases`.

## 2. Verification

Commands run in `/tmp/timerpg-work`:
- `npx tsc --noEmit` -> **0 errors**
- `npx vitest run src/court src/crime src/timeline src/police src/loop` -> **11/11 test files, 74/74 tests passed**
  - `src/court/court.test.ts`: 13/13 passed (all 10 G010 tests + 3 new G013 tests)
  - `src/crime/crime.test.ts`: 10/10 passed
  - `src/crime/evidence.test.ts`: 9/9 passed
  - `src/police/police.test.ts`: 9/9 passed
  - `src/loop/*.test.ts`: 21/21 passed
  - `src/timeline/timeline.test.ts`: 12/12 passed

## 3. Usage

- **Model and setting:** Gemini (AI Studio Build coding engine, standard run).
- **Tokens:** Not exposed by tool telemetry in this environment.
- **Duration:** ~20 minutes wall-clock including repo setup, 60-seed calibration probes, tests, docs, and verification.

## 4. Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   Adding `'bribery'`, `'perjury'`, and `'intimidation'` to `CrimeKind` required adding three exhaustive entries in `src/timeline/evidence.ts` and `src/timeline/interventions.ts` (outside the task's original file list, because G017 merged `TimelineCrimeKind = CrimeKind | 'arson'` after G013 was written). I kept those edits strictly to the 3 new literals with zero behavioral changes to existing crimes, verified `tsc --noEmit` and all 12 `src/timeline` tests pass, and listed them explicitly above. Secondarily, because `CRIME_CONSPICUOUSNESS.bribery` is now covert (`0.30`), a successful bribe with only one hallway bystander can go unseen — which is realistic, so the test suite explicitly covers both a refused juror bribe (`g013-court-crimes`) and a bailiff-witnessed accepted bribe (`bribe-juror-case`).
2. **What did you check by looking or running, and what did you only reason about?**
   - **Checked by running:** `npx tsc --noEmit`, all 74 tests across `src/court`, `src/crime`, `src/timeline`, `src/police`, and `src/loop`, and multiple 60-seed calibration runs across weak, contested, and strong physical-evidence cases (including 2-round public-defender acquittals).
   - **Only reasoned about:** How `bribery`/`perjury`/`intimidation` cases behave inside a full multi-day `advanceDay` gossip loop over 200+ townspeople (we unit-tested `reportingProbability` directly for the three new modifiers, not a full 30-day gossip curve for court crimes).
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   The contested 60-seed spread moving from a broken `0% / 100%` coin-buy (`public_defender` always losing, `elite_defense` always winning) to a realistic four-step gradient (`8.3% -> 16.7% -> 33.3% -> 58.3%` acquitted), flanked by a weak case where a public defender wins `65%` of the time (often after 2 deliberation rounds) and a strong physical-evidence case where even elite counsel loses `90%` of the time — plus tampering crimes showing up as real `bribery`, `perjury`, and `intimidation` cases instead of `theft`/`assault`.

## 5. Ideas for the Team

- **Expose `courtroom_poise` as a voiceable `TrialEvent`:** Right now the bounded trial-day execution variance (`trialPoiseNoise`) shifts `perceivedGuiltScore` without emitting a narrative event; emitting an optional short courtroom-moment event (e.g., `"Key prosecution witness hesitates under questioning"` or `"Prosecutor lays out a crisp timeline"`) would give the on-device LLM concrete flavor for *why* a borderline trial swung one way on a given day.
- **Wire `bribery` / `perjury` / `intimidation` into `src/loop/facade.ts` `formatCrimePhrase`:** `src/loop/facade.ts` currently falls through to its `default:` branch for unknown `CrimeKind` values. Adding explicit player-facing phrases (e.g., `"juror bribery"`, `"perjury under oath"`, `"witness intimidation"`) is a 3-line polish whenever `src/loop/` is next touched.
- **Prosecution tier symmetry:** Right now only defense counsel has a tier (`public_defender`..`elite_defense`), while the prosecutor is fixed. Adding a lightweight prosecutor skill factor (e.g., small-town county attorney vs. special prosecutor on high-severity/repeat felonies) would make capital cases and high-profile retrials feel distinct across eras without adding UI complexity.
