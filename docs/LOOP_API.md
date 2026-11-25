# Timerpg Loop Facade API (`src/loop/`)

The single plain-JSON simulation API that the phone (`kotlin-street`, via QuickJS-ng) and desktop preview call for the playable loop.

Every input and output across the boundary is **plain JSON** — no class instances, `Map`, `Set`, `Date`, functions, or Node/browser APIs. All randomness is seeded via `rand(seed, ...keys)` from `src/sim/rng.ts`.

Policing is powered directly by the real G009 police engine (`src/police/`: `createPoliceForce`, `stepPoliceTick`, `spotting.ts`, and `plans.ts`) over G005/G008 crime/evidence (`src/crime/`) and G007 plans (`src/plan/`).

---

## Boundary Conventions

### Clock Ownership
There is one clear owner for every simulated hour so callers never double-advance the clock:
- **`advance(sim, hours)`** advances time between player actions (walking, waiting, sleeping).
- **`act(sim, { kind: 'work_shift', ... })`** **owns the shift's clock**: it records wages and familiarity, then runs `advance(sim, hours)` internally and returns the updated `day`, `hour`, `daysElapsed`, `policePlans`, `attitudeChanges`, `newPoliceCases`, and `pawnLeadsMatched`. Callers must **not** call `advance()` again for the shift.
- **`arrest(sim, input)`** **owns the cell/jail clock**: when `jailHours > 0`, it runs the skipped hours through `advance()` (so gossip, police reports, trace/heat decay, and police plans progress during time served) and returns `day`, `hour`, `daysElapsed`, `policePlans`, `attitudeChanges`, and `newPoliceCases`.
- All other `act()` kinds (`buy`, `sell`, `take`, `take_vehicle`, `enter`) are instantaneous and do not advance the clock.

### Money Units (Integer Cents & Era Scaling)
Every monetary field crossing the facade boundary is **integer cents** in era money, matching `src/economy/` (`docs/ECONOMY.md`):
- Inputs: `item.value`, `vehicle.value`, `work_shift.pay`, `arrest.playerCash`.
- Outputs: `ActResult.payEarned`, `ArrestOutcomeResult.fineAmount`.
- Default `work_shift` pay (when `pay` is omitted) uses `wage('shop_clerk', year).hourlyCents * hours` from `src/economy/eras.ts`.
- Arrest fines scale by `priceIndex(year)` from `src/economy/eras.ts` (2025 = `1.0`, 1978 = `0.207`, 1952 = `0.085`), so fines match the era's wallet.

### Patrol Beats
Officer patrol beats are constructed by **graph distance (nearby corners)** in `src/police/force.ts` (`buildBeats`), using BFS hop distances rather than node ID ordering. Any street-junction numbering scheme produces compact, local beats.

### ID Mapping Boundary (`src/loop/ids.ts`)
`src/sim/` IDs (`p:hale_marcus`, `place:hale_store`, `node:hale_block`) are mapped bijectively to G007 plan-safe identifiers (`[a-z][a-z0-9_]*`, e.g. `p_hale_marcus`, `place_hale_store`, `node_hale_block`) in one place inside `src/loop/ids.ts`. Callers only ever pass and receive `src/sim/` IDs (`OfficerPlanDirective.routePlaceIds` is reverse-mapped back to sim-domain IDs).

---

## Bundling for QuickJS-ng

`src/loop/bridge.ts` bundles into a standalone ES2020 IIFE with zero external or Node dependencies:

```bash
./node_modules/.bin/esbuild src/loop/bridge.ts \
  --bundle --platform=neutral --target=es2020 --format=iife \
  --outfile=kotlin-street/app/src/main/assets/sim/sim.js
```

Loading `sim.js` attaches `globalThis.TimerpgSim` (unchanged method names: `init`, `act`, `advance`, `attitude`, `policeView`, `spotted`, `arrest`, `save`, `load`), stateless `*Json` wrappers, and `globalThis.simCall`.

---

## Core TypeScript API (`src/loop/index.ts`)

### 1. `create(init: LoopInit): LoopSim`

Initializes the world, social bonds, G005/G008 crime state, bijective `src/sim/` ↔ G007 plan ID map, and the real G009 `PoliceState` via `createPoliceForce()`.

Key optional `LoopInit` / `LoopPlaceInit` fields:
- `playerName?: string` — player's name (learned by employers, coworkers, and pawn clerks).
- `playerDescription?: string` — optional plain-English description (e.g. `"a slim stranger in a green coat"`) used in hearsay facts when the speaker doesn't know the player by name.
- `stopPolicy?: Partial<StopPolicyTable>` — optional threshold overrides for street stops & arrests (defaults to `STOP_POLICY`).
- `places[i].staff?: string[]` — person IDs of clerks/coworkers who work at the place. Staff automatically witness crimes when the shop is open (unless explicitly overridden in `event.bystanders`) and learn the player's name + gain trust after a `work_shift`.

**Example input (`LoopInit`):**
```json
{
  "seed": "first-encounter-1978",
  "year": 1978,
  "startDay": 1,
  "startHour": 10,
  "startDayOfWeek": 1,
  "playerId": "player",
  "playerName": "Alex Mercer",
  "playerDescription": "a slim stranger in a green coat",
  "playerAppearance": {
    "height": "average",
    "build": "slim",
    "hair": "dark",
    "clothingColor": "green"
  },
  "places": [
    {
      "id": "place:hale_store",
      "name": "Hale's General Store",
      "kind": "general_store",
      "position": { "x": 40, "y": 4 },
      "ownerId": "p:hale_marcus",
      "staff": ["p:hale_helen"],
      "jurisdictionId": "jur:capital",
      "townId": "capital",
      "openHours": [7, 19],
      "nodeId": "node:hale_block"
    },
    {
      "id": "place:police_station",
      "name": "Capital Precinct",
      "kind": "police_station",
      "position": { "x": 85, "y": 42 },
      "jurisdictionId": "jur:capital",
      "nodeId": "node:station"
    }
  ],
  "streetGraph": {
    "nodes": [
      { "id": "node:hale_block", "position": { "x": 40, "y": 0 }, "kind": "street", "jurisdictionId": "jur:capital", "streetLight": 0.75 },
      { "id": "node:station", "position": { "x": 85, "y": 42 }, "kind": "station", "jurisdictionId": "jur:capital", "streetLight": 0.85 }
    ],
    "edges": [
      { "from": "node:hale_block", "to": "node:station", "distance": 62 }
    ]
  },
  "people": [
    {
      "id": "p:hale_marcus",
      "first": "Marcus",
      "surname": "Hale",
      "sex": "M",
      "age": 52,
      "homePlaceId": "place:home_03",
      "workplacePlaceId": "place:hale_store"
    }
  ]
}
```

**QuickJS bridge response (`TimerpgSim.init(initJson)`):**
```json
{
  "ok": true,
  "day": 1,
  "hour": 10,
  "year": 1978,
  "peopleCount": 50,
  "officerCount": 2
}
```

---

### 2. `act(sim: LoopSim, event: LoopActionEvent): ActResult`

Records a player action, evaluates line-of-sight witnesses (`witnessesOf`, automatically including on-duty `place.staff` while the place is open unless overridden in `event.bystanders`), records crimes & traces (`leaveTraces` + `recordTraces`), updates work familiarity, and logs pawn-shop registries.

Supported `event.kind` values:
- `buy` — legitimate purchase; slight trust bump with clerk/owner.
- `sell` — returns `legit: true, hot: false` for clean items; returns **`legit: false, hot: true, pawnTraceFlagged: true`** when the item is stolen (and enrolls seller + serial number into G008 `pawn_registry`).
- `work_shift` — legitimate paid shift in integer cents (defaults to era `shop_clerk` wage × hours); employer, `place.staff`, and coworkers learn the player's name; **advances `hours` through `advance()` internally** and returns `day`, `hour`, `daysElapsed`, `policePlans`, `attitudeChanges`, `newPoliceCases`, and `pawnLeadsMatched`.
- `take` — `subkind`: `'shoplift' | 'yard' | 'house' | 'pickpocket'`; `item.value` in integer cents.
- `take_vehicle` — vehicle theft (`car_theft`); `vehicle.value` in integer cents.
- `enter` — legitimate during open hours; trespass (`isOpen: false`) or burglary (`forcedEntry: true`) when closed.

**Example input (`work_shift`):**
```json
{
  "kind": "work_shift",
  "placeId": "place:market",
  "hours": 4,
  "taskLabel": "stocking crates"
}
```

**Example output (`work_shift`, omitting `sim`):**
```json
{
  "kind": "work_shift",
  "legit": true,
  "payEarned": 1260,
  "witnessedBy": [],
  "tracesLeft": [],
  "day": 1,
  "hour": 14,
  "daysElapsed": 0,
  "policePlans": [],
  "attitudeChanges": [],
  "newPoliceCases": [],
  "pawnLeadsMatched": [],
  "summary": "Worked a 4-hour shift at Capital Market for $12.60."
}
```

**Example output (`sell` stolen serialized item at pawn shop, omitting `sim`):**
```json
{
  "kind": "sell",
  "legit": false,
  "hot": true,
  "pawnTraceFlagged": true,
  "witnessedBy": [],
  "tracesLeft": [],
  "summary": "Sold stolen a gold Hamilton pocket watch at Vance's Pawn & Loan (flagged hot)."
}
```

---

### 3. `advance(sim: LoopSim, hours: number, options?: AdvanceOptions): AdvanceResult`

Advances the clock by `hours`, stepping crossed days through G005/G008 `advanceDay`, collecting scene traces on open cases, matching pawn-shop leads, stepping the real G009 police engine (`stepPoliceTick`), and returning new `policePlans`, `attitudeChanges`, `newPoliceCases`, and `pawnLeadsMatched`.

---

### 4. `attitude(sim: LoopSim, personId: string): AttitudeResult`

Returns how a person feels toward the player (`serve | wary | refuse | report_on_sight`), whether they know the player by name, numeric `trust` (`-1..1`), and plain-English `facts[]` written directly for speaking to the player (`"you"` / `"I"` — never `"this person"`).

**Example output (`attitude(sim, "p:hale_marcus")`):**
```json
{
  "personId": "p:hale_marcus",
  "name": "Marcus Hale",
  "stance": "refuse",
  "knowsPlayerName": false,
  "trust": -0.422,
  "facts": [
    "Martha Finch told me you managed to steal a tin of peaches from Hale's General Store on Tuesday."
  ]
}
```

---

### 5. `policeView(sim: LoopSim, jurisdictionId: string): PoliceViewResult`

Returns the merged description, decaying heat, wanted suspect IDs, live G009 officer directives (`planSource` + sim-domain `routePlaceIds`), and G008 `caseStrength` breakdown for every open case in `jurisdictionId`.

---

### 6. `spotted(sim: LoopSim, officerId: string, sighting: OfficerSightingInput): SpotResult`

Evaluates an officer seeing the player via G005 `recognitionChance` and G009 `src/police/spotting.ts`, governed by the single tunable `STOP_POLICY` table (overridable via `LoopInit.stopPolicy`):

| `STOP_POLICY` field | Default | Meaning |
| --- | --- | --- |
| `watchRecognition` | `0.14` | Minimum recognition to passively watch |
| `questionRecognition` | `0.26` | Minimum recognition to stop & question (unidentified) |
| `namedQuestionRecognition` | `0.20` | Minimum recognition to stop & question (named suspect) |
| `directArrestRecognition` | `0.72` | Minimum recognition for immediate arrest on sight |
| `directArrestStrength` | `0.48` | Minimum case strength for immediate arrest (unidentified) |
| `namedDirectArrestRecognition` | `0.52` | Minimum recognition for immediate arrest (named suspect) |
| `namedDirectArrestStrength` | `0.55` | Minimum case strength for immediate arrest (named suspect) |
| `stopArrestStrength` | `0.45` | Minimum case strength to arrest after a stop; below this, **released** |
| `fleeingBonus` | `0.22` | Recognition boost when the target is fleeing |
| `papersCheckHeat` | `25` | Jurisdiction heat threshold for asking papers (`year >= 1915`) |
| `papersCheckSeverity` | `0.35` | Case severity threshold for asking papers (`year >= 1915`) |

When `action === 'approach_and_question'`, `SpotResult.stopOutcome` reports the real street stop:
```json
{
  "officerId": "officer_jur_capital_0",
  "jurisdictionId": "jur:capital",
  "targetId": "player",
  "action": "approach_and_question",
  "recognitionChance": 0.31,
  "matchedCaseId": "crime:1:1",
  "reason": "Asked questions, but case strength (0.34) is below arrest threshold (0.45); released.",
  "stopOutcome": {
    "checkedPapers": false,
    "questioned": true,
    "outcome": "released",
    "caseStrength": 0.34,
    "thresholdUsed": 0.45,
    "reason": "Asked questions, but case strength (0.34) is below arrest threshold (0.45); released."
  },
  "planSource": "go person:player then ask person:player politely"
}
```

---

### 7. `arrest(sim: LoopSim, input: ArrestInput): ArrestOutcomeResult`

Loop stand-in for arrest resolution (replaced later by G010 trials):
- If initiated from a street stop (`fromStop: true` or `why: 'approach_and_question'`) and `!forceArrest`, cases below `sim.stopPolicy.stopArrestStrength` release immediately with `penalty: 'warning'`, `fineAmount: 0`, `jailHours: 0`, `releasedAfterStop: true`, and zero booking/confiscation/priors.
- Otherwise imposes era-scaled integer-cent fines (`priceIndex(year)`) or cell/jail time, confiscates stolen items, enrolls booking prints/DNA into G008 `identityFiles` (`reexamineColdCases`), appends real G009 `ArrestRecord` entries to `sim.policeState.arrests`, and runs any `jailHours > 0` through `advance()`.

**Example output (`ArrestOutcomeResult`, omitting `sim`):**
```json
{
  "jurisdictionId": "jur:capital",
  "penalty": "fine",
  "fineAmount": 608,
  "jailHours": 0,
  "jailNights": 0,
  "day": 3,
  "hour": 10,
  "daysElapsed": 0,
  "charges": [
    {
      "crimeId": "crime:1:1",
      "kind": "shoplifting",
      "severity": 0.282,
      "caseStrength": 0.34,
      "placeName": "Hale's General Store"
    }
  ],
  "priorOffenses": 1,
  "totalCaseStrength": 0.34,
  "confiscatedItems": ["item:peaches_1"],
  "coldCasesTriggered": [],
  "policePlans": [],
  "attitudeChanges": [],
  "newPoliceCases": [],
  "summary": "Fined $6.08 for 1 charge(s)."
}
```

---

### 8. `save(sim: LoopSim): string` and `load(serialized: string): LoopSim`

Exact JSON serialization round-trip of the entire `LoopSim` (including `world`, `crimeState`, `policeState`, `idMap`, `officers`, `crimeMeta`, `stolenItems`, `pawnRecords`, `familiarity`, `stopPolicy`, and `priorOffenses`).
