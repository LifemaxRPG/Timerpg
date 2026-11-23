# Timerpg Loop Facade API (`src/loop/`)

The single plain-JSON simulation API that the phone (`kotlin-street`, via QuickJS-ng) and desktop preview call for the playable loop.

Every input and output across the boundary is **plain JSON** — no class instances, `Map`, `Set`, `Date`, functions, or Node/browser APIs. All randomness is seeded via `rand(seed, ...keys)` from `src/sim/rng.ts`.

---

## Bundling for QuickJS-ng

`src/loop/bridge.ts` bundles into a standalone ES2020 IIFE with zero external or Node dependencies:

```bash
./node_modules/.bin/esbuild src/loop/bridge.ts \
  --bundle --platform=neutral --target=es2020 --format=iife \
  --outfile=kotlin-street/app/src/main/assets/sim/sim.js
```

Loading `sim.js` attaches `globalThis.TimerpgSim` with both:
- **Stateful QuickJS methods** (`init`, `act`, `advance`, `attitude`, `policeView`, `spotted`, `arrest`, `save`, `load`) that keep the live `LoopSim` inside QuickJS memory so Kotlin only sends small JSON events and receives small JSON deltas.
- **Stateless JSON wrappers** (`createJson`, `actJson`, `advanceJson`, `attitudeJson`, `policeViewJson`, `spottedJson`, `arrestJson`) for pure functional calls.

---

## Core TypeScript API (`src/loop/index.ts`)

### 1. `create(init: LoopInit): LoopSim`

Initializes the world, social bonds, G005/G008 crime state, bijective `src/sim/` ↔ G007 plan ID map, and era-sized police force.

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
  "streetLamps": [
    { "position": { "x": 40, "y": 0 }, "radius": 22, "intensity": 0.85 }
  ],
  "people": [
    {
      "id": "p:hale_marcus",
      "first": "Marcus",
      "surname": "Hale",
      "sex": "M",
      "age": 52,
      "traits": { "ambition": 0.62, "charm": 0.78, "diligence": 0.85 },
      "homePlaceId": "place:home_03",
      "workplacePlaceId": "place:hale_store",
      "friends": [{ "personId": "p:finch_martha", "affinity": 0.72 }]
    },
    {
      "id": "p:finch_martha",
      "first": "Martha",
      "surname": "Finch",
      "sex": "F",
      "age": 44,
      "traits": { "ambition": 0.45, "charm": 0.92, "diligence": 0.88 },
      "homePlaceId": "place:home_01",
      "friends": [{ "personId": "p:hale_marcus", "affinity": 0.72 }]
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
  "officerCount": 1
}
```

---

### 2. `act(sim: LoopSim, event: LoopActionEvent): ActResult`

Records a player action and evaluates line-of-sight witnesses (`witnessesOf`), crime ledger (`recordCrime`), physical/serial traces (`leaveTraces` + `recordTraces`), work familiarity, and pawn-shop registries.

Supported `event.kind` values:
- `buy` — legitimate purchase; slight trust bump with clerk/owner.
- `sell` — legitimate sale; at a `pawn_shop`, logs seller identity & serial number into G008 `pawn_registry` and flags stolen goods so police audits can trace them back.
- `work_shift` — legitimate paid shift; employer and coworkers learn the player's name with zero police interest.
- `take` — `subkind`: `'shoplift' | 'yard' | 'house' | 'pickpocket'`.
- `take_vehicle` — vehicle theft (`car_theft`) with color, kind, and optional plate number.
- `enter` — legitimate during open hours; trespass (`isOpen: false`) or burglary (`forcedEntry: true`) when closed.

**Example input (`take` / shoplifting with owner's back turned and window witness):**
```json
{
  "kind": "take",
  "subkind": "shoplift",
  "placeId": "place:hale_store",
  "position": { "x": 40, "y": 4 },
  "light": 0.95,
  "item": { "id": "item:peaches_1", "label": "a tin of peaches", "value": 3 },
  "bystanders": [
    {
      "id": "p:hale_marcus",
      "position": { "x": 40, "y": 7 },
      "facing": { "x": 0, "y": 1 },
      "activity": "working"
    },
    {
      "id": "p:finch_martha",
      "position": { "x": 40, "y": -1 },
      "facing": { "x": 0, "y": 1 },
      "activity": "watching"
    }
  ]
}
```

**Example output (`ActResult`, omitting `sim` as returned by `TimerpgSim.act`):**
```json
{
  "kind": "take",
  "legit": false,
  "crimeId": "crime:1:1",
  "witnessedBy": [
    {
      "personId": "p:finch_martha",
      "name": "Martha Finch",
      "confidence": 0.784,
      "recognizedPlayer": false,
      "isVictim": false,
      "description": {
        "height": "average",
        "build": "slim",
        "hair": "dark",
        "clothingColor": "green"
      }
    }
  ],
  "tracesLeft": [
    { "id": "trace:crime:1:1:fp", "kind": "fingerprint", "quality": 0.71 }
  ],
  "summary": "shoplifting at Hale's General Store witnessed by 1 person(s)."
}
```

**Example input (`work_shift`):**
```json
{
  "kind": "work_shift",
  "placeId": "place:market",
  "hours": 5,
  "pay": 45,
  "taskLabel": "stocking crates"
}
```

**Example output (`work_shift`):**
```json
{
  "kind": "work_shift",
  "legit": true,
  "payEarned": 45,
  "witnessedBy": [],
  "tracesLeft": [],
  "summary": "Worked a 5-hour shift at Capital Market for $45."
}
```

**Example input (`sell` stolen serialized item at pawn shop):**
```json
{
  "kind": "sell",
  "placeId": "place:pawn_shop",
  "item": {
    "id": "item:watch_serial_1",
    "label": "a gold Hamilton pocket watch",
    "value": 70,
    "serialNumber": "HAM-448912"
  }
}
```

**Example output (`sell`):**
```json
{
  "kind": "sell",
  "legit": true,
  "pawnTraceFlagged": true,
  "witnessedBy": [],
  "tracesLeft": [],
  "summary": "Sold a gold Hamilton pocket watch at Vance's Pawn & Loan."
}
```

---

### 3. `advance(sim: LoopSim, hours: number, options?: AdvanceOptions): AdvanceResult`

Advances the clock by `hours`, stepping each crossed day through G005/G008 `advanceDay` (memory decay, word-of-mouth gossip, era-delayed reporting, trace decay, cold-case checks), collecting scene traces on open cases, matching pawn-shop leads, compiling G007 officer plans, and diffing townsfolk attitudes.

**Example input:**
```json
{ "hours": 48, "options": { "weather": "clear" } }
```

**Example output (`AdvanceResult`, omitting `sim`):**
```json
{
  "fromDay": 1,
  "toDay": 3,
  "fromHour": 10,
  "toHour": 10,
  "daysElapsed": 2,
  "policePlans": [
    {
      "officerId": "officer:jur:capital:0",
      "planId": "officer_jur_capital_0",
      "jurisdictionId": "jur:capital",
      "role": "patrol",
      "assignment": "investigate",
      "planSource": "go place:node_hale_block then watch place:node_hale_block carefully then go place:node_station",
      "routePlaceIds": ["node:hale_block", "node:station"],
      "targetPlaceId": "place:hale_store",
      "crimeId": "crime:1:1",
      "reason": "Responding to shoplifting at Hale's General Store"
    }
  ],
  "attitudeChanges": [
    {
      "personId": "p:hale_marcus",
      "name": "Marcus Hale",
      "previousStance": "serve",
      "newStance": "refuse",
      "reasons": [
        "Martha Finch told you this person managed to steal a tin of peaches from Hale's General Store on Tuesday."
      ]
    }
  ],
  "newPoliceCases": ["crime:1:1"],
  "pawnLeadsMatched": []
}
```

---

### 4. `attitude(sim: LoopSim, personId: string): AttitudeResult`

Returns how a person feels toward the player (`serve | wary | refuse | report_on_sight`), whether they know the player by name, numeric `trust` (`-1..1`), and plain-English `facts[]` ready for the dialogue model's fact sheet.

**Example call:** `attitude(sim, "p:hale_marcus")`

**Example output:**
```json
{
  "personId": "p:hale_marcus",
  "name": "Marcus Hale",
  "stance": "refuse",
  "knowsPlayerName": false,
  "trust": -0.422,
  "facts": [
    "Martha Finch told you this person managed to steal a tin of peaches from Hale's General Store on Tuesday."
  ]
}
```

---

### 5. `policeView(sim: LoopSim, jurisdictionId: string): PoliceViewResult`

Returns the merged description, decaying heat, wanted suspect IDs, compiled officer directives, and G008 `caseStrength` breakdown for every open case in `jurisdictionId`.

**Example call:** `policeView(sim, "jur:capital")`

**Example output:**
```json
{
  "jurisdictionId": "jur:capital",
  "day": 3,
  "year": 1978,
  "heat": 0.412,
  "wantedIds": [],
  "officers": [
    {
      "officerId": "officer:jur:capital:0",
      "planId": "officer_jur_capital_0",
      "jurisdictionId": "jur:capital",
      "role": "patrol",
      "assignment": "patrol",
      "planSource": "go place:node_hale_block then watch place:node_hale_block carefully then go place:node_market_square",
      "routePlaceIds": ["node:hale_block", "node:market_square"],
      "targetPlaceId": "place:hale_store",
      "crimeId": "crime:1:1",
      "reason": "Patrolling Main St (Hale Block) near recent shoplifting"
    }
  ],
  "cases": [
    {
      "crimeId": "crime:1:1",
      "kind": "shoplifting",
      "severity": 0.288,
      "placeId": "place:hale_store",
      "placeName": "Hale's General Store",
      "reportCount": 1,
      "firstHandCount": 0,
      "heat": 0.245,
      "mergedDescription": {
        "height": "average",
        "build": "slim",
        "hair": "dark",
        "clothingColor": "green"
      },
      "overallConfidence": 0.676,
      "strength": {
        "crimeId": "crime:1:1",
        "day": 3,
        "combinedScore": 0.34,
        "witnessScore": 0.34,
        "physicalScore": 0,
        "corroborationAdjustment": 0,
        "verdictTier": "circumstantial",
        "hasTimeAnomaly": false,
        "timeAnomalies": [],
        "parts": [
          {
            "source": "witness",
            "kind": "witness_testimony",
            "label": "1 witness report (confidence 68%)",
            "contribution": 0.34
          }
        ]
      }
    }
  ]
}
```

---

### 6. `spotted(sim: LoopSim, officerId: string, sighting: OfficerSightingInput): SpotResult`

Evaluates an officer seeing the player using G005 `recognitionChance`, returning the officer's decision (`ignore | watch | approach_and_question | arrest`) and, when acting, a validated G007 `planSource`.

**Example input (`sighting`):**
```json
{
  "distance": 5,
  "light": 0.95,
  "fleeing": false
}
```

**Example output:**
```json
{
  "officerId": "officer:jur:capital:0",
  "jurisdictionId": "jur:capital",
  "targetId": "player",
  "action": "arrest",
  "recognitionChance": 0.785,
  "matchedCaseId": "crime:1:2",
  "reason": "Officer positively identified named suspect for burglary.",
  "planSource": "go person:player quickly then restrain person:player with item:cuffs_officer_jur_capital_0"
}
```

---

### 7. `arrest(sim: LoopSim, input: ArrestInput): ArrestOutcomeResult`

Loop stand-in for arrest resolution (replaced later by G010 trials): assigns a `warning`, `fine`, `cell_night`, or `jail` sentence by severity and prior record, confiscates stolen items, enrolls booking prints/DNA into G008 `identityFiles` (checking cold cases via `reexamineColdCases`), and resolves the charged cases.

**Example input:**
```json
{
  "jurisdictionId": "jur:capital",
  "carriedItemIds": ["item:peaches_1"],
  "playerCash": 50,
  "surrendered": false
}
```

**Example output (`ArrestOutcomeResult`, omitting `sim`):**
```json
{
  "jurisdictionId": "jur:capital",
  "penalty": "fine",
  "fineAmount": 29,
  "jailHours": 0,
  "jailNights": 0,
  "charges": [
    {
      "crimeId": "crime:1:1",
      "kind": "shoplifting",
      "severity": 0.288,
      "caseStrength": 0.34,
      "placeName": "Hale's General Store"
    }
  ],
  "priorOffenses": 1,
  "totalCaseStrength": 0.34,
  "confiscatedItems": ["item:peaches_1"],
  "coldCasesTriggered": [],
  "summary": "Fined $29 for 1 charge(s)."
}
```

---

### 8. `save(sim: LoopSim): string` and `load(serialized: string): LoopSim`

Exact JSON serialization round-trip of the entire `LoopSim` (including `world`, `crimeState`, `idMap`, `officers`, `crimeMeta`, `stolenItems`, `pawnRecords`, `familiarity`, and `priorOffenses`).

```ts
const snapshot: string = save(sim);
const restored: LoopSim = load(snapshot);
// save(restored) === snapshot
```

---

## ID Mapping Boundary (`src/loop/ids.ts`)

`src/sim/` IDs (`p:hale_marcus`, `place:hale_store`, `node:hale_block`) are mapped bijectively to G007 plan-safe identifiers (`[a-z][a-z0-9_]*`, e.g. `p_hale_marcus`, `place_hale_store`, `node_hale_block`) in one place inside `src/loop/ids.ts`. Callers only ever pass and receive `src/sim/` IDs (with `OfficerPlanDirective.routePlaceIds` already reverse-mapped for Kotlin pathfinding, and `planSource` validated via `parsePlan` + `bind`).
