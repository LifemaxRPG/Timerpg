# G010 Delivery — Trials (Court, Jury, Verdict) (`src/court/`)

Branch: `gemini/G010-trials` (branched from `ccr-52e43ebb-xnmj2e`, HEAD `f9bfaa1`)

## Queue Selection Note

Both open tasks (`G009` and `G011`) were already delivered on `origin/gemini/G009-police-action` and `origin/gemini/G011-loop-facade`. Per `docs/queue/README.md` rule 6 ("if nothing is open or revise, take a hold task"), I took `G010` whose hold note was specifically `"after G011"` — rather than `G003`/`G004`, which require a JVM/Gradle toolchain (`:desktop:test` / Filament test) not installed in this sandbox (`node` + `npm` only).

## Summary

Implemented the pure, deterministic courtroom & jury trial simulation under `src/court/`, wired directly to G005 (`witnessesOf`, `recordCrime`, `ingestPoliceReport`, gossip ledger, mistaken descriptions) and G008 (`caseStrength`, `witness_physical_conflict`, `FORENSIC_ERA`, `leaveTraces`, `reexamineColdCases`), plus full documentation (`docs/COURT.md`) and 10 deterministic test scenarios (`src/court/court.test.ts`).

### What Was Built
- **`src/court/types.ts`** — Plain-JSON interfaces for era laws, charges, bail, plea offers, defense lawyers, voir-dire facts, seated/struck jurors, ordered trial events, juror votes, sentences, trial interference crimes, and post-conviction appeals/exoneration.
- **`src/court/law.ts`** — Four historical court eras (`frontier_circuit`, `interwar`, `mid_century`, `modern`) synchronized directly with `FORENSIC_ERA`, era-scaled statutes & fines/bail, `chargesFromCase`, `evaluateBail`, `makePleaOffer` (scaling discount inversely with `caseStrength`), and 4-tier `hireDefenseLawyer`.
- **`src/court/jury.ts`** — `selectJury`: draws 12 adult jurors from `world.people`, builds transparent `VoirDireFact[]` (kinship, victim/defendant affinity, G005 gossip knowledge, reputation, traits), executes for-cause strikes (striking kin, friends of victim/defendant, and fixed gossip bias) and defense/prosecution peremptory strikes.
- **`src/court/trial.ts`** — `inspectWitnessErrors`, `runTrial`, `runRetrial`, `commitTrialInterference` (`bribe_juror`, `threaten_juror`, `bribe_witness`, `threaten_witness`, `perjury` routed through G005/G008 as real new crimes), and `fileAppeal` (era-gated DNA exoneration, recantation, new witness, procedural retrial).
- **`src/court/index.ts`** — Public barrel exports.
- **`src/court/court.test.ts`** — 10 deterministic test scenarios covering every requirement in `docs/queue/tasks/G010.md`.
- **`docs/COURT.md`** — Full design & API reference.

No files outside `src/court/`, `docs/COURT.md`, and `prototypes/G010/DELIVERY.md` were touched.

## Verification & Measured Results

- **Typecheck (`npm run typecheck`):** 0 errors.
- **Court tests (`npx vitest run src/court`):** **10 / 10 passed** (217 ms):
  1. Strong-evidence robbery -> unanimous `guilty` conviction with sentence strictly inside era statute range.
  2. Single mistaken eyewitness against exculpatory DNA -> cross-examination exposes all 4 description mistakes + G008 `witness_physical_conflict` -> unanimous `not_guilty` acquittal.
  3. **Expensive lawyer acquittal odds over 60 seeds** (contested 1978 burglary with 2 flawed eyewitnesses + partial print):
     - `public_defender`: **acquittal = 0.0%, hung = 0.0%, guilty = 100.0%**
     - `local_attorney`: **acquittal = 0.0%, hung = 73.3%, guilty = 26.7%**
     - `retained_counsel`: **acquittal = 98.3%, hung = 1.7%, guilty = 0.0%**
     - `elite_defense`: **acquittal = 100.0%, hung = 0.0%, guilty = 0.0%**
  4. Jury selection strikes victim's spouse (for cause, `victim_kin`) and all 3 close friends of the victim (`victim_friend`) with full `VoirDireFact[]`.
  5. Hung jury (`verdict === 'hung'`, `retrialRecommended === true`) -> `runRetrial` increments `attempt` to `2` and draws a fresh 12-person jury composition.
  6. 1925 vs 1995 trial on identical case with DNA + camera evidence: 1925 excludes `dna` and `camera_footage` (`not_guilty`), 1995 admits both (`guilty`, `+0.4+` higher perceived guilt).
  7. Bribed juror flips an otherwise unanimous `guilty` robbery into `hung` (bribed seat votes `not_guilty`, `tampered: 'bribed'`) while recording a real new crime and live police case in `CrimeState`.
  8. Exoneration years later: 1978 wrongful conviction -> 1982 DNA appeal denied (pre-DNA era) -> 1998 G008 `reexamineColdCases` DNA hit on the real culprit exonerates the defendant, vacates remaining jail days, restores reputation, and awards compensation.
  9. Pre-trial plea scaling (weak vs strong case), bail denial on murder, and byte-for-byte JSON determinism.
  10. Perjury caught in court and witness intimidation create real `CrimeEvent` records and police cases in `CrimeState`.
- **Regression suite (`npx vitest run src/crime src/plan`):** **34 / 34 passed** (0 regressions).
- **Phone / QuickJS hygiene:** Zero occurrences of `Math.random`, `Date.now`, `performance`, timers, DOM globals, or Node built-ins in `src/court/`.

## Self-Review

1. **What's the worst case or edge case I haven't tested?**
   - Multi-count indictments (bundling 3+ separate `PoliceCase`s into a single joint trial with concurrent vs consecutive sentencing and mixed verdicts per count — e.g. guilty on theft, not guilty on robbery). Right now `chargesFromCase` produces one count per `PoliceCase` cleanly; a multi-case indictment wrapper would be a natural follow-up when prison/court UI needs consolidated trials.
2. **Does anything here look awkward or half-finished?**
   - In `commitTrialInterference`, `bribe_juror`, `bribe_witness`, and `perjury` map onto existing G005 `CrimeKind: 'theft'` (and threats onto `'assault'`) with descriptive `stolenOrDamaged.item` labels (`"juror bribery"`, `"perjury under oath"`) so we didn't have to modify `src/crime/types.ts`. When `src/crime/` next evolves, adding dedicated `'bribery'` / `'perjury'` / `'intimidation'` entries to `CrimeKind` would make police reports and rap sheets read even cleaner.
3. **Is there a simpler design I passed over?**
   - I could have skipped the 3-round deliberation loop and just counted initial juror threshold votes directly. Keeping the 3-round loop with stubbornness-gated persuasion takes ~25 lines, emits `deliberation_round` events Qwen can narrate (e.g., 9-3 -> 11-1 -> hung), and makes single-juror bribery feel authentically dramatic.

## Ideas for the Team

- **Replacing `src/loop`'s stand-in `arrest()` with `src/court/`:** Once G010 and G011 are both merged, `src/loop/facade.ts` can keep its fast fine/cell path for petty misdemeanors (`severity < 0.35`) and route felonies / contested arrests into `src/court/runTrial` (with pre-trial bail/plea choices exposed through `bridge.ts`).
- **Pre-generating trial lines while awaiting arraignment:** As noted in `docs/IDEAS.md`, `era.arraignmentDays` gives a natural in-world window where Qwen on the S25 can pre-generate opening/witness/cross-examination dialogue from `TrialOutcome.events[]` (which is already deterministic and plain JSON!) so the courtroom scene plays smoothly without NPU stalls.
- **Time-travel exoneration & "alibi before birth":** Because `TrialOutcome` already carries `impossibleTimeline` from G008 `EvidenceMatch`, a future time-travel trial twist can let defense counsel challenge a 1955 fingerprint belonging to a defendant born in 1995 right inside `evidence_challenge`.

## Usage

- Model / agent: Gemini ("Jeff") in AI Studio Build sandbox.
- Token counts / API cost: not visible in this environment.
- Effort: 1 run (~8 minutes wall-clock), 0 revisions, all 10 `src/court` tests + 34 `src/crime`/`src/plan` tests green.
