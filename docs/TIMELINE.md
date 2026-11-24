# Timeline Engine (`src/timeline/`)

The playable time-travel layer built on top of `src/sim/` (seeded history + `diffWorlds`), `src/crime/`, `src/police/`, and `src/court/`.

All inputs and outputs are **plain JSON** (no `Map`, `Set`, `Date`, class instances, Node APIs, browser APIs, `Date.now`, or `Math.random`). All randomness uses `rand` / `rngFor` from `src/sim/rng.ts`.

---

## Core Time-Travel Rules (Unanchored Travellers)

1. **Rule 1 — Travellers and carried items are unanchored.** Once a player jumps, their memories and carried inventory (`timeline.player.inventory`, `timeline.player.memories`) survive every timeline edit with their original provenance intact, even if an item's maker or shop never existed in the new timeline.
2. **Rule 2 — Everything else is anchored.** The town, its people, businesses, buildings, and memories re-form around the seed plus interventions. An NPC remembers the player only from the current timeline branch.
3. **Rule 3 — Self-erasure is playable.** `selfErase(timeline, year)` removes the player's anchored self and identity records (birth record, bank account, fingerprints/DNA on file, legal identity, job references, family recognition). The player keeps existing as an unanchored ghost with explicit losses and gains.
4. **Paradoxes are loose threads, not errors.** Interventions that remove the cause of the player's own trip (e.g., preventing the professor's lab from ever being built) are always allowed and surfaced as plain facts via `looseThreads(timeline)`.
5. **Unanchored travellers live outside the sim.** The professor and any other travellers keep `(year, place, trace)` marks in `timeline.travellers`; no timeline intervention can remove them.

---

## API Reference & Plain-JSON Examples

### 1. `createTimeline(options)`

Creates a fresh plain-JSON `Timeline` ledger and deterministically generates the professor's trail (5–9 traces across eras and continents) from `seed`.

**Input:**
```json
{
  "seed": "gamma",
  "startYear": 1950,
  "placeId": "capital",
  "player": {
    "playerId": "player",
    "originYear": 2025,
    "birthYear": 1995,
    "homePlaceId": "capital"
  }
}
```

**Output (shape):**
```json
{
  "version": 1,
  "seed": "gamma",
  "startYear": 1950,
  "placeId": "capital",
  "interventions": [],
  "player": {
    "playerId": "player",
    "originYear": 2025,
    "birthYear": 1995,
    "homePlaceId": "capital",
    "selfErased": false,
    "records": {
      "birthRecord": true,
      "bankAccount": true,
      "fingerprintsOnFile": true,
      "dnaOnFile": false,
      "legalIdentity": true,
      "passportEligible": true,
      "jobReferences": ["Capital Municipal Clerk", "Former Workshop Supervisor"],
      "knownFamilyIds": []
    },
    "inventory": [],
    "memories": [],
    "interactions": []
  },
  "crimes": {},
  "identityFiles": {},
  "travellers": [
    {
      "id": "professor",
      "name": "Professor Hale",
      "role": "professor",
      "unanchoredSinceYear": 1923,
      "traces": ["...5 to 9 TravellerTrace entries..."]
    }
  ]
}
```

---

### 2. `branch(timeline, intervention)`

Returns a new `Timeline` with `intervention` appended. Never mutates the original `timeline`.

Supported `kind` values:
- Native `src/sim` wrappers: `kill`, `invest`, `sabotage`, `introduce`, `rumor`, `gift`
- Player crime acts: `crime` (with `crimeKind` imported from `src/crime/types.ts`: `vandalism`, `shoplifting`, `theft`, `car_theft`, `burglary`, `assault`, `robbery`, `murder`)
- Timeline/world acts: `arson`, `demolish`, `prevent_building`, `self_erase`, `record_fire`, `record_flood`

**Call:**
```ts
const burned = branch(timeline, {
  year: 1952,
  placeId: 'capital',
  kind: 'arson',
  businessId: 'b:gen:0',
  replacementLotLabel: 'Parking garage',
  summary: 'Burned down the hardware store in 1952',
});
```

You can also convert a `CrimeEvent` from `src/crime` directly via `crimeEventToIntervention(crimeEvent, overrides?)`, and compile all interventions up to a year with `compileSimInterventions(timeline, maxYear?)`.

---

### 3. `presentAt(timeline, year)`

Replays `src/sim` for `(seed, startYear, active interventions <= year)` and returns the replayed `world` plus a structured `changes` report built on `src/sim/diff.ts` (`diffWorlds` & `describeDiff`).

Results and underlying sim runs are cached by `(seed, startYear, active interventions, year)` so repeated jumps cost zero replays. Use `getTimelineCacheStats()` to inspect cache hits/misses and `clearTimelineCache()` to reset the cache.

**Call:**
```ts
const snap = presentAt(burned, 2025);
```

**Output (shape):**
```json
{
  "seed": "gamma",
  "year": 2025,
  "placeId": "capital",
  "world": { "...src/sim World snapshot at 2025..." },
  "fromCache": false,
  "changes": {
    "year": 2025,
    "hasChanges": true,
    "population": { "before": 231, "after": 229 },
    "erasedPeople": [{ "id": "gen:2:c0>1978", "name": "Arthur Keller", "birthYear": 1978 }],
    "killedEarly": [],
    "newPeople": [],
    "businessesNeverOpened": [],
    "businessesNeverClosed": [],
    "businessesClosedEarly": [
      {
        "id": "b:gen:0",
        "name": "Keller's Bakery",
        "kind": "Bakery",
        "foundedYear": 1941,
        "closedYear": 1952,
        "reason": "Burned down in 1952 arson"
      }
    ],
    "businessesNew": [],
    "ownerChanges": [],
    "mayorChange": { "changed": false, "beforeName": "Helen Vance", "afterName": "Helen Vance" },
    "richestChange": { "changed": false, "beforeName": "Walter Ellis", "afterName": "Walter Ellis" },
    "buildings": {
      "neverBuilt": [],
      "demolishedOrReplaced": [
        {
          "slot": "shop:22,18",
          "beforeLabel": "Keller's Bakery",
          "afterLabel": "Parking garage",
          "kind": "shop",
          "cause": "Burned down in 1952 arson",
          "year": 1952,
          "businessId": "b:gen:0"
        }
      ],
      "newlyBuilt": []
    },
    "worldDiff": { "...raw src/sim WorldDiff..." },
    "summary": [
      "Population in 2025: 231 -> 229",
      "Closed down: Keller's Bakery",
      "Lot shop:22,18 (Keller's Bakery): Burned down in 1952 arson -> now Parking garage"
    ]
  }
}
```

---

### 4. Anchoring Helpers (`addCarriedItem`, `addPlayerMemory`, `recordPlayerInteraction`, `inspectCarriedItems`, `npcMemoryOfPlayer`, `selfErase`)

#### `addCarriedItem(timeline, item)` & `inspectCarriedItems(timeline, year)`
```ts
const withReceipt = addCarriedItem(timeline, {
  id: 'item:coffee-receipt',
  label: 'Coffee receipt',
  acquiredYear: 2025,
  acquiredPlaceId: 'capital',
  makerId: 'gen:2:c0>1978',
  makerName: 'Arthur Keller',
  provenanceNote: 'Bought coffee from the owner\'s grandson in 2025',
});

const items = inspectCarriedItems(alteredTimeline, 2025);
```
**Output:**
```json
[
  {
    "item": {
      "id": "item:coffee-receipt",
      "label": "Coffee receipt",
      "acquiredYear": 2025,
      "acquiredPlaceId": "capital",
      "makerId": "gen:2:c0>1978",
      "makerName": "Arthur Keller",
      "provenanceNote": "Bought coffee from the owner's grandson in 2025"
    },
    "survives": true,
    "makerExists": false,
    "businessExists": true,
    "anachronisticProvenance": true,
    "note": "Survives in inventory (unanchored), though its maker Arthur Keller never existed in this timeline."
  }
]
```

#### `npcMemoryOfPlayer(timeline, npcId, year)`
```ts
const view = npcMemoryOfPlayer(timeline, 'gen:4:h', 2025);
```
**Output:**
```json
{
  "npcId": "gen:4:h",
  "npcName": "Harold Ellis",
  "year": 2025,
  "existsInTimeline": true,
  "aliveInYear": true,
  "remembersPlayer": false,
  "knowsAsFamily": false,
  "interactions": [],
  "summary": "Harold Ellis does not remember the player in this timeline."
}
```

#### `selfErase(timeline, year, placeId?)`
```ts
const { timeline: ghostTimeline, report } = selfErase(timeline, 1953, 'capital');
```
**Output (`report`):**
```json
{
  "year": 1953,
  "placeId": "capital",
  "playerId": "player",
  "alreadyErased": false,
  "erasedRecords": [
    "birth_record",
    "legal_identity",
    "bank_account",
    "passport",
    "job_references",
    "family_ties",
    "fingerprints_on_file"
  ],
  "losses": [
    { "code": "no_birth_record", "label": "No birth record", "detail": "No municipal registry shows you were ever born." },
    { "code": "no_legal_identity", "label": "No legal identity", "detail": "Cannot pass official background checks, open legitimate leases, or claim state benefits." },
    { "code": "no_bank_account", "label": "Bank account vanished", "detail": "Anchored bank accounts, credit history, and balances under your name cease to exist." },
    { "code": "no_legal_passport", "label": "No legal passport path", "detail": "Cannot obtain a legal passport; crossing oceans requires forged papers or illegal passage." },
    { "code": "no_references", "label": "Lost job & personal references", "detail": "2 reference(s) no longer remember employing or vouching for you." },
    { "code": "no_family_recognition", "label": "Family no longer knows you", "detail": "1 family member(s) have no memory of you." }
  ],
  "gains": [
    { "code": "no_prints_on_file", "label": "No fingerprints on file", "detail": "Police fingerprint registries and AFIS databases contain no matching prints for you." },
    { "code": "no_dna_on_file", "label": "No DNA profile on file", "detail": "Forensic DNA databases cannot link crime-scene samples to a civil identity." },
    { "code": "no_paper_trail", "label": "Unregistered ghost", "detail": "No tax roll, civil registry, or known home address lets investigators trace you on paper." }
  ],
  "summary": [
    "Erased anchored self in 1953 at capital.",
    "Lost: No birth record; No legal identity; Bank account vanished; No legal passport path; Lost job & personal references; Family no longer knows you.",
    "Gained: No fingerprints on file; No DNA profile on file; Unregistered ghost."
  ]
}
```

---

### 5. `recordTimelineCrime(timeline, input, options?)` & `caseStatusAt(timeline, crimeId, year)`

Tracks crime evidence across eras using `src/crime` (`FORENSIC_ERA`, `caseHeatOnDay`, `caseStrength`, `detectTimeTravelAnomalies`), `src/police` (`POLICE_ERA`, `forceSizeForTown`), and `src/court` (`courtEraForYear`, `statuteForCrime`).

- **Victim erased before the crime** -> `status: "never_happened"`, `crimeOccurred: false`, `activelyLooking: false`.
- **Witness erased before the crime** -> witness testimony disappears (`erasedWitnesses`); if no testimony or physical traces survive -> `status: "no_evidence"`, `activelyLooking: false`.
- **Fire / flood record loss** -> explicit `record_fire`/`record_flood` interventions or simulated pre-1995 flood stories destroy paper files (`status: "records_lost"`, `recordsIntact: false`).
- **Era cold cases & statutes of limitations** -> e.g. a 1952 arson is `active` in 1953 and a 73-year-old `cold` case (with `statuteExpired: true`) in 2025; murder has no statute (`null`) and can reopen when modern AFIS/DNA databases arrive unless the player self-erased.

**Call:**
```ts
const withArson = recordTimelineCrime(
  timeline,
  {
    id: 'crime:arson-1952',
    kind: 'arson',
    year: 1952,
    placeId: 'capital',
    culpritId: 'player',
    businessId: 'b:gen:0',
    reported: true,
    witnesses: [{ personId: 'gen:3:h', confidence: 0.85 }],
  },
  { alsoBranch: true, replacementLotLabel: 'Parking garage' },
);

const in1953 = caseStatusAt(withArson, 'crime:arson-1952', 1953);
const in2025 = caseStatusAt(withArson, 'crime:arson-1952', 2025);
```

**Output (`in2025`):**
```json
{
  "crimeId": "crime:arson-1952",
  "crimeKind": "arson",
  "crimeYear": 1952,
  "queryYear": 2025,
  "eraId": "modern",
  "status": "cold",
  "activelyLooking": false,
  "crimeOccurred": true,
  "isColdCase": true,
  "statuteExpired": true,
  "statuteLimitYears": 10,
  "ageYears": 73,
  "recordsIntact": true,
  "hasTestimony": true,
  "activeWitnesses": [],
  "erasedWitnesses": [],
  "deceasedWitnesses": ["gen:3:h"],
  "hasPhysicalEvidence": false,
  "survivingTraces": 0,
  "playerIdentifiableOnFile": true,
  "canReopenWithModernForensics": false,
  "forensicAnomalies": [],
  "reasons": [
    "1 witness(es) from 1952 have since passed away.",
    "73-year-old arson file from 1952 is a cold case (Modern Court Era (1986+)).",
    "Statute of limitations for arson (10 years) expired in 1962."
  ]
}
```

---

### 6. `looseThreads(timeline, referenceYear?)`

Lists every paradox on `timeline` as plain facts for dialogue and the player's journal. Paradoxes are never refused (`allowed: true`).

**Call:**
```ts
const threads = looseThreads(noLabTimeline, 2025);
```

**Output:**
```json
[
  {
    "id": "thread:cause:iv:0:1952:prevent_building",
    "kind": "cause_removed",
    "year": 1952,
    "placeId": "capital",
    "allowed": true,
    "fact": "Professor's lab was never built in 1952, yet you travelled from it — allowed because he built it as an unanchored man.",
    "journalLine": "Loose thread (1952): Professor's lab does not exist in this history, though you remember stepping out of it.",
    "dialogueHook": "Nobody in town remembers Professor's lab ever standing there.",
    "relatedIds": ["iv:0:1952:prevent_building"]
  }
]
```

---

### 7. Unanchored Travellers (`generateProfessorTrail`, `tracesAt`, `addTraveller`, `addTravellerTrace`)

Travellers live outside the sim on `timeline.travellers`. Interventions never remove their traces.

**Call:**
```ts
const trail = generateProfessorTrail('gamma');
const found = tracesAt(timeline, trail.traces[0].year, trail.traces[0].placeId);
```

**Output (`found`):**
```json
[
  {
    "id": "trace:professor:gamma:0",
    "travellerId": "professor",
    "sequenceIndex": 0,
    "year": 1923,
    "eraLabel": "Interwar",
    "continent": "Vespera",
    "placeId": "capital",
    "placeName": "Capital",
    "kind": "early_patent",
    "summary": "A 1923 patent ledger in Capital records a solid-state signal amplifier sketched thirty years ahead of its time.",
    "clueToNext": "Marginal notes hint at heading toward Solis Basin around the 1930s — look for boardinghouse register.",
    "nextHint": {
      "continent": "Solis Basin",
      "decadeHint": "the 1930s",
      "themeHint": "boardinghouse register"
    }
  }
]
```

Pass `{ exactYearOnly: true }` to `tracesAt` to match only traces left in that exact year, or `{ travellerId: 'professor' }` to filter to one traveller.
