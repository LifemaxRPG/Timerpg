# Timerpg Court & Trials System (`src/court/`)

Pure, deterministic TypeScript jury-trial simulation built directly on `src/crime/` (G005 witnesses, memory, gossip & descriptions + G008 physical traces, era forensics & `caseStrength`). Every input and output is plain JSON with zero `Math.random`, `Date.now`, or Node/browser APIs so it runs identically on desktop and inside QuickJS-ng on the phone.

Game logic owns **every fact, strike, vote, verdict, sentence, and appeal**; the on-device language model (Qwen) voices the structured `TrialEvent[]` stream and voir-dire facts later without inventing outcomes.

---

## 1. Era Law & Charges (`src/court/law.ts`)

Four historical court eras (`COURT_ERA_PROFILES`) keep fines, bail, lawyer retainers, funded public defenders, arraignment waits, and forensic admissibility synchronized with `FORENSIC_ERA` from `src/crime/forensics.ts`:

| Era | Years | Money Scale | Funded Public Defender | Admissible Forensics |
| --- | --- | --- | --- | --- |
| `frontier_circuit` | `< 1915` | `0.12×` | No (unpaid appointed counsel) | Serials, dropped items, footprints |
| `interwar` | `1915–1949` | `0.30×` | No | + Local/national fingerprints, vehicle plates |
| `mid_century` | `1950–1985` | `1.00×` | Yes | + Automated prints (AFIS 1980+), B&W cameras (1970+) |
| `modern` | `1986+` | `3.00×` | Yes | + Forensic DNA (1987+), DNA database (1995+), color/HD cameras |

- `statuteForCrime(kind, year)` returns the era-scaled `StatuteRule` (`felony`, `minJailDays..maxJailDays`, `minFine..maxFine`, `baseBail`, `allowNoBail`, `admissibility`).
- `chargesFromCase(policeCase, year, day)` turns a G005/G008 `PoliceCase` into formal `Charge[]` carrying live `caseStrength(policeCase, day)`.

---

## 2. Pre-Trial: Bail, Plea Offers & Defense Counsel (`src/court/law.ts`)

- **`evaluateBail(...)`**: Computes `flightRisk`, `granted`, `amount`, and `canAfford` from severity, felony status, prior convictions, wealth, local ties, and reputation. Capital charges (`murder`) or severe high-flight-risk charges are remanded without bail.
- **`makePleaOffer(...)`**: Scales sentence discounts inversely with `caseStrength`:
  - Weak case (`< 0.42`): ~55–70% discount and misdemeanor reduction on non-capital felonies.
  - Strong case (`>= 0.70`): firm offer (~15–25% discount).
- **`hireDefenseLawyer({ seed, caseId, year, budget?, tier? })`**: Maps budget or explicit tier to `public_defender`, `local_attorney`, `retained_counsel`, or `elite_defense`, scaling `preparation`, `crossSkill`, `persuasion`, and `peremptoryStrikes`.

---

## 3. Jury Selection & Voir Dire (`src/court/jury.ts`)

`selectJury(params)` draws 12 adult jurors (`age >= 18`, alive) from `world.people`, excluding the defendant, victim, judge, prosecutor, defense counsel, and first-hand witnesses/reporters.

For every venire candidate, `selectJury` exposes transparent `VoirDireFact[]` for the courtroom UI:
- Direct kinship or friendship (`world.bonds` / `affinity`) with the victim or defendant.
- Pre-trial gossip knowledge from `crimeState.ledger[crimeId][personId]` (`heardFrom`, `confidence`).
- Perception of the defendant's public `reputation` and notable traits (`diligence`, `charm`).

Strikes are recorded in `jurySelection.struck[]`:
1. **For-cause strikes:** direct kin of victim or defendant, close friends of victim (`victimAffinity >= 0.30` or explicit bond `>= 0.20`) or defendant (`>= 0.35`), and fixed pre-trial opinions from high-confidence gossip (`>= 0.60`).
2. **Peremptory strikes:** defense strikes up to `lawyer.peremptoryStrikes` most pro-prosecution jurors; prosecution strikes up to `prosecutionStrikes` most pro-defense jurors.

---

## 4. Trial Sequence, Deliberation & Retrials (`src/court/trial.ts`)

`runTrial(options)` produces an ordered `TrialEvent[]` sequence:
1. `opening_prosecution` / `opening_defense`
2. `prosecution_witness` + `cross_examination`: `inspectWitnessErrors(report, actualAppearance)` compares each G005 `PoliceReport.description` field (`height`, `build`, `hair`, `clothingColor`, `vehicle`) against ground truth so skilled defense counsel exposes mistaken details on the stand.
3. `prosecution_evidence` + `evidence_challenge`: filters `EvidenceMatch[]` by `era.admissibility` (logging excluded era evidence in `ignoredEraEvidenceKinds`), weights incriminating vs exculpatory matches, and surfaces G008 `witness_physical_conflict` directly in court.
4. `defense_case` + optional `defendant_testimony` (takes `defendantTestimonyScore` in `0..1` supplied by the spoken-defense evaluator).
5. `closing_arguments`
6. **3-round deliberation (`deliberation_round`):** Each seated juror evaluates `perceivedGuiltScore` through their own `traits` (`diligence`, `ambition`, `charm`), `initialBias`, deterministic individual judgment, and any active jury tampering (`bribed` / `threatened`). Unanimous `12-0` guilty -> `'guilty'`; unanimous `0-12` -> `'not_guilty'`; split vote -> `'hung'` with `retrialRecommended: true`.
7. **`sentence` (when `'guilty'`):** Judge picks `jailDays` and `fineAmount` strictly inside the era statute's range based on severity, prior record, and judge traits.

`runRetrial(previousOutcome, options)` increments `attempt` and draws a fresh 12-person jury venire.

---

## 5. Crimes Around the Trial (`commitTrialInterference`)

Supports `bribe_juror`, `threaten_juror`, `bribe_witness`, `threaten_witness`, and `perjury`.

Every attempt routes through G005/G008 (`witnessesOf`, `recordCrime`, `leaveTraces`, `recordTraces`):
- Success depends on bribe size (era-scaled) vs the target's `diligence`, `ambition`, and `reputation` (or `caughtInCourt` for perjury).
- Always creates a real `CrimeEvent` in `crimeState.crimes[crimeId]` with physical traces, and immediately opens a police case in `crimeState.jurisdictions[jurisdictionId]` when refused, caught in court, or witnessed by a bystander.
- On success, returns an `ActiveTrialTampering` token passed into `runTrial({ tampering })` — e.g., a bribed juror votes `'not_guilty'` as a holdout, flipping a unanimous conviction to a hung jury while leaving an active bribery case behind.

---

## 6. Appeals & Exoneration (`fileAppeal`)

Supports `new_dna_match`, `forensic_retest`, `new_witness`, `witness_recantation`, and `procedural_error`:
- DNA appeals are era-gated (`year >= FORENSIC_ERA.dnaAnalysisYear`).
- When a later DNA match (e.g., via G008 `reexamineColdCases` years later) identifies a different suspect (`suspectId !== defendantId`) or excludes the defendant, `fileAppeal` returns `outcome: 'exonerated'`, vacates remaining `jailDays`, restores reputation, awards era-scaled wrongful-imprisonment compensation, and marks `updatedConviction.exonerated = true`.
