# Crime System Core (`src/crime/`)

Grounded, deterministic simulation of how crimes ripple through a living world: **witnessing -> memory -> word of mouth -> police reporting -> jurisdiction knowledge & recognition**.

Everything in `src/crime/` is a pure function of `(seed, inputs)` using `src/sim/rng.ts` (`rand` / `rngFor`), with zero `Math.random()` calls and plain-JSON serializable state (`CrimeState`) suitable for save files and time-travel replay.

---

## 1. Crime Events (`src/crime/types.ts`)

Eight crime kinds with default severities (`0..1`) and scene conspicuousness:

| Kind | Default Severity | Conspicuousness |
| --- | ---: | ---: |
| `vandalism` | 0.22 | 0.65 |
| `shoplifting` | 0.28 | 0.40 |
| `theft` | 0.44 | 0.48 |
| `car_theft` | 0.58 | 0.72 |
| `burglary` | 0.64 | 0.58 |
| `assault` | 0.72 | 0.88 |
| `robbery` | 0.80 | 0.90 |
| `murder` | 1.00 | 0.94 |

Each `CrimeEvent` records `position`, `placeId` (and optional `townId`), timestamp `{ year, day, hour }`, `culpritId`, ground-truth `culpritAppearance` (`height`, `build`, `hair`, `clothingColor`, optional `vehicle`), optional `victimId` / `victimConscious`, and `stolenOrDamaged`.

---

## 2. Witnessing (`src/crime/witness.ts`)

`witnessesOf(event, bystanders, options)` evaluates every living, conscious bystander (excluding the culprit):

- **Lighting (`ambientLightAt`):** Solar daylight curve (`1.0` from 08:00–17:00, smooth dawn/dusk ramps, `0.06` at night 21:00–05:00) boosted locally by caller-supplied `streetLamps` (quadratic falloff inside lamp radius).
- **Effective range:** Scales from `~11 m` in pitch dark (`light = 0.06`) up to `maxDistance = 55 m` in full daylight.
- **Line of sight:** Caller-supplied `canSee(from, to, bystanderId)` gates non-victim witnesses.
- **Age & attention:** Visual acuity peaks at ages 12–55 (`1.0`), tapering below 12 and above 55 (`0.52` by age 95). Attention defaults from `person.traits.diligence` (`0.45..0.80`) unless overridden.
- **Conscious victim rule:** If `event.victimId` is conscious (default `true` except `murder`), the victim is **always** a witness regardless of lighting or `canSee`, with confidence floored by ambient light and eyesight.
- **Perceived description (`observeDescription`):** Each attribute (`height`, `build`, `hair`, `clothingColor`, `vehicle`) is independently observed or omitted, and may be mistaken (adjacent band on ordered scales like height/build) with error probability `0.42 * (1 - c) * (1 - 0.4c) + 0.02`.
- **Personal ID:** A witness who personally knows the culprit (kin, `affinity > 0.25`, or `bystander.knowsCulprit`) attaches `identifiedCulpritId` when confidence clears `0.42` (victim) or `0.54` (bystander).

---

## 3. Memory & Refresh (`src/crime/memory.ts`)

Every sighting and gossip account decays daily with a severity-driven half-life:

$$\text{halfLifeDays}(s) = 3.5 \times 35^{s^{1.5}}$$

- **First-hand bystander half-lives:** vandalism `5.1 d`, shoplifting `5.9 d` (forgotten below `0.06` floor within ~3–4 weeks), theft `9.9 d`, burglary `21.6 d`, robbery `44.5 d`, murder `122.5 d`.
- **Modifiers:** Second-hand hearsay fades faster (`× 0.62`); victims remember longer (`× 1.45`); each refresh extends half-life by `+35%` (up to 5 refreshes).
- **Traumatic floor:** First-hand witnesses of murder (`severity >= 0.95`) never decay below `max(0.18, 0.24 * initialConfidence)`.
- **Detail shedding:** As confidence drops through `[0.54, 0.42, 0.32, 0.22, 0.12]`, details drop in least-striking-first order: `clothingColor -> build -> vehicle -> hair -> height`.
- **Refreshing (`refreshSighting` / `refreshWitnessMemory`):** Re-seeing the culprit (`+0.20`) or a police interview (`+0.12`) restores confidence up to `initialConfidence` and resets the decay clock.

---

## 4. Word of Mouth (`src/crime/gossip.ts`)

Every simulated day, active knowers (`hops < 4`, `confidence >= 0.16`) may tell their regular circle:
- **Contacts:** Spouse (`closeness 1.0`), parents/children/siblings (`0.74..0.86`), up to 2 coworkers at the same workplace (`workplaceOf`), bonded friends (`world.bonds`), and up to 4 local town friends selected deterministically and weighted by `affinity` from `src/sim/social.ts`.
- **Spread probability:** Scales with teller `charm`, `severity`, `recency` (half-life `2.5 + 7.5 * severity` days), `hopAtten = 0.58^hops`, relation multiplier, and `closeness`.
- **Second-hand degradation (`retellDescription`):** Retold accounts always have strictly lower confidence (`× (0.64 + 0.10 * diligence)`) and drop or distort details with probability `0.36 * (1 - 0.85 * newConf)`.
- **Town containment:** Non-kin, non-travel links never cross towns (`townOf`). Word crosses towns **only** through cross-town kin (spouse/parents/children/siblings) or people traveling today (`travelTownsOf`).
- **Ledger:** `state.ledger[crimeId][personId]` tracks `source` (`first_hand` / `second_hand`), `hops`, `heardFrom`, `dayLearned`, `confidence`, `description`, and `reported`.

---

## 5. Reporting & Historical Era Latency (`src/crime/reporting.ts`)

Within an active decision window (`6 days` first-hand, `5 days` 1-hop, `3 days` deeper hearsay), knowers decide whether to report:
- **Severity & source:** Scales steeply with `severity^1.35`; victims report eagerly; second-hand rumors report far less readily (`× 0.28`, or `× 0.56` if heard from direct kin, `× 0.09` at 2+ hops).
- **Shielding & loyalty:** Victims' kin/friends get a reporting boost. Culprit's spouse (`94%` shield), kin (`84%` shield), and close friends (`affinity > 0.15`, up to `~75%` shield) suppress reporting when they know/suspect who did it; rivals report more eagerly.
- **Reputation & refusal (`refusesPolice`):** Deterministic per-person refusal trait modulated by `reputation` and `diligence` (~14% average, higher for low-reputation citizens; extreme crimes `severity >= 0.90` cut refusal by 65%, leaving only a small silent core).
- **Era latency (`reportLatencyForEra`):**
  - **Pre-1900 (e.g. 1880, no phones):** Must walk/ride to station (`delayDays >= 1`, `12–72 h` depending on station distance & urgency). Queued in `state.pendingReports` until `arrivalDay <= day`.
  - **1900–1934:** Mix of shop/party-line phones and walking (`1–30 h`, `0–1 d`).
  - **1935–1999 (e.g. 1990):** Home & shop telephones (`0.15–3.0 h`, arrives same day).
  - **2000+:** Mobile phones (`0.05–0.75 h`, arrives same day).

---

## 6. Police Knowledge, Heat & Recognition (`src/crime/police.ts`)

- **Jurisdiction isolation:** Caller supplies `jurisdictionOf(placeId)` (town police, county sheriff, state). Reports go to the crime's jurisdiction (or the reporter's local/visited jurisdiction when word crosses towns via kin or travel), with optional `escalateJurisdictionOf(jur, crime)` for higher-tier escalation.
- **Weighted description merge (`mergeDescriptions`):** Each report gets weight `confidence * (firstHand ? 1.0 : 0.42) * (isVictim ? 1.25 : 1.0)`. Per-field consensus picks the highest-weighted value and records per-field agreement confidence.
- **Heat decay:** Case base heat `(14 + 72 * severity^1.15) * (0.45 + 0.55 * overallConf) * (named ? 1.15 : 1.0)` decays with half-life `7 + 95 * severity^2` days (~14 d shoplifting, ~68 d robbery, ~102 d murder). Total jurisdiction heat caps at `100`.
- **Queries:**
  - `policeKnowledge(state, jurisdictionId, day)` -> `{ heat, cases, wantedIds }`
  - `isWanted(state, culpritId, jurisdictionId, day)` / `isNamedSuspect(...)`
  - `recognitionChance(state, world, npcId, culprit, day, options)` -> combines personal first-hand memory, second-hand gossip, and jurisdiction police/poster awareness against the culprit's **current** appearance (changing clothes or ditching a vehicle lowers recognition while permanent traits still carry weight).

---

## 7. Integration Hooks for Claude

`src/crime/` does not edit `src/sim/` or any rendering/dialogue file. To wire it into the main game loop:
1. Store `crime: CrimeState` alongside world save state (`createCrimeState(world.seed, startDay)`).
2. When the player or an NPC commits a crime, call `witnessesOf(event, nearbyBystanders, { seed, canSee, streetLamps, world, victim })` and `state = recordCrime(state, event, sightings)`.
3. Once per simulated day, call `state = advanceDay(state, world, day, ctx)` providing `jurisdictionOf`, `townOf`, `workplaceOf`, `travelTownsOf`, and `escalateJurisdictionOf`.
4. Read `policeKnowledge`, `isWanted`, `knowersOf`, and `recognitionChance` from AI dialogue, patrol spotting, Gazette crime stories, and arrest checks; call `refreshWitnessMemory` when an NPC spots a suspect again or is interviewed.

---

## 8. Physical Evidence, Era Forensics, Cold Cases & Case Strength (`src/crime/forensics.ts`, `src/crime/evidence.ts`)

Extends the witness/police core with physical and digital crime traces whose investigative value changes smoothly across historical eras, while staying 100% backward-compatible with existing `CrimeState` saves (`traces` and `identityFiles` are optional plain-JSON maps).

### 8.1 Trace Types & Scene Decay (`leaveTraces`, `recordTraces`, `decayTraceOneDay`, `destroyTraces` / `cleanScene`)

When a crime occurs, call `leaveTraces(event, options)` and persist the result with `recordTraces(state, event.id, traces)`:

- **`fingerprint`** — left on touched surfaces/items unless `gloved: true`.
- **`footprint`** — left around the scene (sharper outdoors in `rain`/`snow`, carries optional `shoeProfile`).
- **`dna`** — rare blood/biological trace (higher chance in `murder`/`assault`, or forced via `bloodSpilled: true`).
- **`dropped_item`** — optional personal item left behind (`droppedItem: { label, ownerId?, serialNumber? }`).
- **`stolen_serial`** — generated for `event.stolenOrDamaged` when `itemHasSerialInEra(classifySerialItem(item), year)` is true.
- **`vehicle_plate`** — generated when `culpritAppearance.vehicle` is present; `plateNumber` is populated **only** once `year >= FORENSIC_ERA.plateRegistrationYear` (1905).
- **`camera_footage`** — generated for caller-supplied `cameras` with line of sight & radius; capability and captured fields depend on the era (`bw` drops clothing colour; `color` adds it; `hd` improves quality).

Uncollected traces decay every simulated day inside `advanceDay` using `ctx.weatherOf(placeId, day)` (outdoor footprints and blood wash away sharply in `rain`/`storm`, and uncollected `camera_footage` overwrites automatically once `retentionDays` elapses). Any actor can call `destroyTraces` / `cleanScene(state, crimeId, { method, actorId, day, thoroughness? })` (`'clean' | 'wash' | 'wipe' | 'burn' | 'overwrite' | 'dispose'`) to destroy or degrade uncollected traces. Traces already collected into a police evidence locker (`collected: true`) are immune to weather decay and scene cleaning.

### 8.2 Era Forensics Table (`FORENSIC_ERA`)

All historical milestones live in a single tunable table in `src/crime/forensics.ts` (overridable via `ctx.forensicEra`):

| Domain | Milestone | Default Year | Behaviour |
|---|---|---:|---|
| Fingerprints | `fingerprintLocalYear` | 1910 | Manual match against local station card file |
| Fingerprints | `fingerprintNationalYear` | 1924 | Manual bureau search of `scope: 'national'` cards across jurisdictions |
| Fingerprints | `fingerprintAutomatedYear` | 1980 | Automated AFIS search across all jurisdictions & local files |
| DNA | `dnaAnalysisYear` | 1987 | Manual suspect comparison (same-jurisdiction enrolled DNA sample) |
| DNA | `dnaDatabaseYear` | 1995 | Indexed national DNA database (cross-jurisdiction cold hits) |
| Security cameras | `cameraBwYear` | 1970 | B&W CCTV (no clothing/vehicle colour) |
| Security cameras | `cameraColorYear` | 1990 | Colour CCTV |
| Security cameras | `cameraHdYear` | 2010 | High-definition digital CCTV |
| License plates | `plateRegistrationYear` | 1905 | Local vehicle plate registration |
| License plates | `plateDatabaseYear` | 1972 | Computerized cross-jurisdiction plate database |
| Serial numbers | `serialFirearmWatchYear` | 1900 | Firearms & watches |
| Serial numbers | `serialApplianceBikeYear` | 1930 | Bicycles, typewriters & major appliances |
| Serial numbers | `serialElectronicsYear` | 1970 | Consumer electronics, TVs, cameras & phones |

### 8.3 Linking, Identity Files & Cold Cases (`enrollOnFile`, `collectCrimeTraces`, `matchTrace`, `reexamineColdCases`)

- A trace never links to a person by magic: the person must have an accessible `IdentityFileRecord` in `state.identityFiles` (`reason: 'arrest' | 'license' | 'database' | 'military' | 'vehicle_registration' | 'pawn_registry'`, `scope: 'local' | 'national'`, `enrolledYear <= year`).
- `collectCrimeTraces(state, world, crimeId, jurisdictionId, day, options)` moves surviving scene traces into `PoliceCase.traces` and immediately runs `matchTrace` for the current era.
- Unmatched collected traces stay in `PoliceCase.traces` indefinitely. When `advanceDay` runs (or `reexamineColdCases` is called explicitly), unmatched collected traces are re-checked in O(unmatched traces) time: a 1965 fingerprint or blood sample collected with no match automatically solves the cold case when the culprit is booked in 1982 (AFIS) or entered into a DNA database in 1995 (`coldCase: true`, `yearsToMatch`), re-heating the case so `policeKnowledge`, `isNamedSuspect`, and `isWanted` update immediately.

### 8.4 Time-Travel Anomalies (`detectTimeTravelAnomalies`, `findTimeTravelAnomalies`)

When a forensic match (or an enrolled identity record) links a trace from `traceYear` to a person whose `birthYear` is later (`traceYear < birthYear`) or whose `firstEraArrivalYear` / `ctx.eraArrivalYearOf(personId)` is later (`traceYear < arrivalYear`), the match is flagged with `impossibleTimeline: true`, `beforeBirth`, `beforeEraArrival`, and structured `TimeTravelAnomaly[]` entries (`kind`, `traceYear`, `referenceYear`, `yearsImpossible`, human-readable `summary`). The module exposes the flags on both `EvidenceMatch`, `PoliceCase.timeAnomalies`, `caseStrength(...).timeAnomalies`, and `findTimeTravelAnomalies(state, world)` without forcing any specific game reaction.

### 8.5 Explainable Case Strength (`caseStrength(policeCase, day)`)

`caseStrength(policeCase, day)` combines pure witness consensus (`mergeDescriptions(policeCase.reports)`) and physical/digital forensic matches into a single `0..1` prosecution score (`combinedScore`, `witnessScore`, `physicalScore`, `corroborationAdjustment`, `verdictTier`: `'none' | 'circumstantial' | 'probable_cause' | 'strong' | 'overwhelming'`, `leadSuspectId`, `hasTimeAnomaly`, and itemized `parts[]` with labels, sources, years, cold-case flags, and corroboration/conflict notes so a prosecutor or UI can explain every component).
