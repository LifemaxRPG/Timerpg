# Police in Action (`src/police/`)

Pure, deterministic police operations built directly on top of the crime core (`src/crime/`, G005/G008) and the NPC plan language (`src/plan/`, G007). Every officer's duty — patrolling beats, answering reported crimes, interviewing witnesses, searching haunts, staking out suspects, checking papers, calling backup, giving chase, cuffing, and transporting prisoners to the station — executes as compiled, bound G007 plans stepped through `PlanWorld`.

---

## 1. Force Sizing, Shifts, Beats & Era Table (`src/police/force.ts`)

All historical policing milestones live in one tunable table, `POLICE_ERA`:

| Field | Default | Meaning |
|---|---:|---|
| `patrolCarYear` | `1928` | Motorized patrol cars enter service (`foot` only before) |
| `radioYear` | `1930` | Patrol cars carry two-way radio (instant backup dispatch) |
| `handheldRadioYear` | `1965` | Foot officers also carry portable handheld radios |
| `papersCheckStartYear` | `1915` | Routine ID / licence / papers checks during stops |
| `detectiveMinPopulation` | `350` | Minimum town population to staff a dedicated detective |
| `earlyOfficersPerThousand` | `1.15` | Headcount per 1,000 residents before 1930 |
| `midOfficersPerThousand` | `1.70` | Headcount per 1,000 residents (1930–1959) |
| `modernOfficersPerThousand` | `2.30` | Headcount per 1,000 residents (1960+) |

- **`createPoliceForce({ seed, year, towns, graph, era? })`** produces a plain-JSON `PoliceState` across one or more jurisdictions:
  - Sizes headcount via `forceSizeForTown(population, year)`.
  - Partitions each jurisdiction's caller-supplied `StreetGraph` into connected patrol beats (`buildBeats`) radiating from `stationNodeId`.
  - Assigns 8-hour shifts (`day` 06:00–14:00, `swing` 14:00–22:00, `night` 22:00–06:00), transport (`foot` vs `car`), radio capability (`officerHasRadioInEra`), cuffs (`cuffsItemId`), and a `detective` for serious cases (`severity >= 0.70`).
- **Heat-driven effort:** High jurisdiction heat (`policeKnowledge(crimeState, jurId, day).heat`) weaves hot crime locations into patrol beats, triggers all-hands shift overrides at extreme heat (`>= 75`), lowers spotting scrutiny thresholds, and assigns detectives/patrols to proactive `search_suspect` and `stakeout` plans at last-seen nodes and known haunts.

---

## 2. Officer Behaviours as G007 Plans (`src/police/plans.ts`)

Every officer assignment compiles through `parsePlan` → `bind` → `createPlanState` (`compileOfficerPlan`) and ticks through `step(planState, planWorld, officer.id, dt)`:

| Assignment | Example Compiled G007 Plan |
|---|---|
| `patrol` (foot) | `go place:main_west then go place:bank_a then go place:market_sq` |
| `patrol` (car, 1928+) | `drive vehicle:patrol_car_town_a_0 until at(place:bank_a) then ...` |
| `respond_crime` | `go place:bank_a quickly then ask person:witness_1 politely then ask person:witness_2 politely` |
| `search_suspect` | `go place:bank_a quickly then watch place:bank_a then go place:tavern_a carefully then watch place:tavern_a` |
| `stakeout` | `go place:oak_homes quietly then hide while watch place:oak_homes until time(10:30)` |
| `question` / papers | `go person:suspect_1 quickly then warn person:suspect_1 politely then ask person:suspect_1 politely` |
| `call_backup` (1930+ radio) | `signal place:bank_a then follow person:suspect_1 quickly` |
| `call_backup` (pre-1930) | `signal place:bank_a then go place:station_a quickly then call place:station_a` |
| `chase` (foot / car) | `go person:suspect_1 quickly then warn person:suspect_1 rudely then restrain person:suspect_1 with item:cuffs_officer_town_a_0 quickly` |
| `arrest` | `warn person:suspect_1 rudely then restrain person:suspect_1 with item:cuffs_officer_town_a_0 quickly` |
| `transport` | `go place:main_west carefully then go place:station_a carefully` |

When an officer's `ask person:<witness>` step completes during `respond_crime`, `stepPoliceTick` calls G005 `refreshWitnessMemory(crimeState, crimeId, witnessId, day, 'police_interview')` and records an `InterviewRecord`.

---

## 3. Spotting, Lookalikes & Disguises (`src/police/spotting.ts`)

- **Spatial index (`SpatialPersonIndex`):** Uniform grid with numeric cell keys and active-officer bounding-box pruning keeps spotting sweeps fast in 2,000-person towns.
- **Authoritative G005 recognition:** Officers evaluate `recognitionChance` against the jurisdiction's active wanted cases using distance, local street lighting (`effectiveLightAtNode` + `ambientLightAt`), and the person's current `CulpritAppearance`. Changing clothes, ditching a vehicle, or moving at night measurably lowers spotting rates.
- **Lookalikes:** Innocent citizens who closely resemble an unnamed wanted description (`appearanceMatchScore >= 0.42`) can be stopped and questioned by mistake. Once cleared with no contraband, the officer emits `StopOutcome { reason: 'lookalike_cleared', isLookalike: true }`, records a cooldown in `officer.clearedUntilSec`, and releases them.
- **Era & severity actions (`decideSpotAction`):** Routine cases trigger `question` or `ask_papers` (`year >= 1915`); fleeing suspects or high-severity matches trigger `call_backup` (instant radio dispatch from 1930+, delayed station dispatch pre-1930) and `pursue`.

---

## 4. Chase, Cross-Jurisdiction Escape & Arrest (`src/police/engine.ts`)

- **Pursuit & jurisdiction borders:** Pursuing officers chase via foot or patrol car `PlanWorld` movement. If a fleeing suspect crosses into another jurisdiction (`targetObs.jurisdictionId !== officer.jurisdictionId`), local pursuit halts at the border and emits an `EscapeRecord`. Heat in the new jurisdiction stays `0` unless the caller's `escalateJurisdictionOf` hook escalates the case (mirroring G005 escalation).
- **Arrest & custody outcomes (for G010 Trials & Jail):** Catching or cornering a suspect executes hands-up (`warn`), cuffing (`restrain ... with item:cuffs`), inventory search (`ctx.getPersonInventory` + `ctx.isContraband`), a `caseStrength` snapshot from G008, and prisoner transport back to `stationNodeId` (`transportedToStation: true`), emitting a plain-JSON `ArrestRecord`.

---

## 5. Stub Town & Integration (`src/police/stubTown.ts`)

`createStubTownGraph()` and `StubTownPlanWorld` provide a self-contained two-jurisdiction town (`town_a` connected via `east_gate` to `town_b`) implementing `PlanWorld` for headless simulation and tests.

To wire `src/police/` into the main game loop:
1. Build the force once per town/era with `createPoliceForce({ seed, year, towns, graph })` and persist `PoliceState` as plain JSON alongside `CrimeState`.
2. Each simulation tick (`dt` seconds), call `const { state, crimeState, events } = stepPoliceTick(policeState, ctx)` supplying `planWorld`, `world`, `crimeState`, `graph`, nearby/observed people, inventory lookup, haunts, and escalation hooks.
3. Feed `events.arrests` into jail booking and G010 Trials, `events.stops` / `events.escapes` into player feedback & dialogue, and updated `crimeState` back into the daily crime loop.
