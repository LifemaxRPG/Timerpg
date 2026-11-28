# G025 Delivery — Real addresses for every building

- **Task:** `G025` (`docs/queue/tasks/G025.md`)
- **Branch:** `gemini/G025-addresses` (branched from `ccr-52e43ebb-xnmj2e`)
- **Contributor:** Gemini ("Jeff")

## What was built

A deterministic, plain-JSON TypeScript addressing system under `src/address/` covering the capital (`gamma` / Mapleridge), all procedural and baked-recipe towns (`TownPlan` v2 and v1), and Dove Beach (`src/town/city2/`). **Zero files in `src/town/` were modified** — all required geometry, parcels, landmarks, farms, stores, and Dove Beach road curves were read through existing exports.

### Files added

- `src/address/types.ts` — Plain-JSON data structures (`Address`, `StreetRecord`, `BuildingEntry`, `TownAddressBook`, `FindMatch`, `NearestResult`, `CityDirectory`, `AddressBridgeRequest`/`Response`).
- `src/address/postal.ts` — Deterministic town & district postal codes with era cutovers (`1963` for 5-digit codes, `1983` for ZIP+4; `null` before 1963).
- `src/address/streets.ts` — Collinear road grouping, directional `N.`/`S.`/`E.`/`W.` splitting across town origin, 100-block breaks, unique original street naming (trees, sim founders, outward-numbered grid cross-streets, regional/archetype names, `"Old ... Road"` country lanes, earliest-platted-district era suffixes), recorded historical renames with years/reasons, and Dove Beach's 13 curved roads + 18 Seagrove grid streets (with 4 historical renames grounded in `src/town/city2/roads.ts`).
- `src/address/numbering.ts` — Coordinate-based house numbering: odd on left of rising direction, even on right, strictly rising away from town origin, gaps for empty/unbuilt lots, `"1/2"` and letter suffixes for split/co-located parcels, apartment units (`Apt 1A..` / `Apt 101..302`), strip-center suites (`Suite A..C`), upstairs `"1/2"` downtown shop flats, and surface-only snapping on Dove Beach so underground `Headland Tunnel` only claims tunnel portals.
- `src/address/lookup.ts` — `addressOf(book, buildingId, year, opts)`, `allAddresses(book, year)`, `find(book, query, year, opts)` (exact, abbreviations, ordinals like `1st St` / `2nd St`, Damerau-Levenshtein typo tolerance, relational `"the diner on Main"`, historical street names, postal codes, and sim resident/business overlay), plus `nearestAddress(book, pos, year)` returning nearest standing building and interpolated street address.
- `src/address/directory.ts` — Era city & telephone directory generator (`buildDirectory`, `formatDirectory`) wired to `World` residents, workers, and businesses, with era telephone formats (`none` <1915, 3-digit 1915–35, named exchange like `SPring 4-5179` 1936–61, 7-digit 1962–94, `(555)` 10-digit 1995+).
- `src/address/bridge.ts` — Town builders (`buildCapitalAddresses`, `buildTownAddresses`, `buildTownAddressesFromRecipe`, `buildDoveBeachAddresses`), JSON serialization (`serializeAddressBook`, `parseAddressBook`), and QuickJS-ng plain-JSON bridge (`handleAddressRequest`, `handleAddressJson`, `createAddressBridge`).
- `src/address/index.ts` — Public barrel exports.
- `src/address/address.test.ts` — 13 comprehensive tests covering all towns, Dove Beach, baked recipes, v1 plans, invariants across 8 seeds, era transitions, renames, fuzzy/relational queries, directories, and JSON bridge round-trips.

## Real output samples

### Capital (`gamma` — Mapleridge: 62 streets, 417 buildings, 6 historical renames, prefix `659`)
- `glb:post_office`: `101 W. Dunmore Street, Mapleridge` (1952) → `101 W. Dunmore Street, Mapleridge 65961` (1975) → `+4: 65961-3510` (2025)
- `landmark:cinema`: `100 W. Main Street, Mapleridge` (1952) → `100 W. Main Street, Mapleridge 65961` (1975)
- `glb:carhop_drive_in`: `931 W. Main Street, Mapleridge 65964` (`find(cap, "the diner on Main", 2025)` → #1, score 100)
- Split upstairs flat: `169 1/2 E. Main Street, Mapleridge 65961`
- Apartment unit: `214 E. Vance Road, Apt 1A, Mapleridge 65961`
- Recorded renames: `N./S. Livery Lane` → `N./S. Depot Street` (1888), `W./E. Old Tannery Lane` → `W./E. Thorne Avenue` (1949), `N./S. Old Boundary Road` → `N./S. Bypass Road` (1964)
- 1952 Directory excerpt: `Town Hall & Square — 101 W. Main Street [Tel: SPring 4-5179]`, `Brennan's Bakery — 168 W. Main Street [Tel: SPring 8-7815] (Prop: Vernon Brennan; Staff: ...)`

### Generated town (`alpha` — Salt Landing, harbor: 46 streets, 309 buildings, prefix `955`)
- Outward-numbered grid streets, harbor/regional naming, unique postal prefix (`955`), farmsteads on `"Old Pasture Road"`.

### Dove Beach (`city2`: 31 streets, 19 landmarks, 4 historical renames)
- `fountain`: `100 Marine Carriage Drive, Dove Beach` (1920) → `100 The Esplanade, Dove Beach 26711` (1985)
- `customhouse`: `155 Steep Street, Dove Beach 26711`
- `observatory`: `2316 Crown Road, Dove Beach 26713`
- `fort`: `1050 Neck Hill, Dove Beach 26711`
- `lighthouse`: `2980 Head Road, Dove Beach 26715`
- `cottages`: `215 Sixth Avenue, Dove Beach 26712`

## Verification

- `npx tsc --noEmit`: **exit 0** (zero errors)
- `npx vitest run --no-file-parallelism src/address/address.test.ts`: **1 file, 13/13 passed** (18.32s)
- Existing `src/town/` suite (all 20 files, verified in 4 batches):
  - `city2`, `downtown`, `era`, `housing`, `neighborhoods`: **5 files, 44/44 passed**
  - `farms`, `films`, `landmarks`, `plat`, `stores`, `water`: **6 files, 64/64 passed**
  - `glbLandmarks`, `glbSites`, `inspect`, `interiors`, `rail`: **5 files, 42/42 passed**
  - `largeBuildings`, `layout`, `properties`, `schedule`: **4 files, 29/29 passed**
  - **Total existing `src/town/`: 20/20 files, 179/179 passed**
- QuickJS-ng purity audit: zero references to `fs`, `path`, `process`, `Buffer`, `window`, `document`, `Date.now`, or `Math.random` anywhere in `src/address/`.

## Usage

- **Model and setting:** Gemini (agentic coding session, standard build mode; exact reasoning tier not visible)
- **Tokens:** not visible
- **Effort:** single delivery session across one context compaction (two WIP pushes + final push)
- **Plan usage:** not visible
- **Rework:** Round 1 (initial delivery, no prior review rounds)

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   - Three real gaps to call out honestly: (a) `src/address/` is not yet wired into `street.json`, the Kotlin sign painter, or the phone UI because those files are outside G025's allowed scope; (b) `buildTownAddressesFromRecipe()` calls `useRecipe()` so `planTown()` reads the recipe, and because `src/town/recipe.ts` has no per-seed unregister helper (only `clearRecipes()`), passing an unregistered recipe leaves it registered rather than wiping unrelated cached recipes; and (c) Dove Beach's `src/town/city2/site.ts` currently defines only its 19 landmarks (no lot generator yet). To make integration easy, I exposed `extraBuildings` on `buildDoveBeachAddresses()` (tested with co-located split buildings and Esplanade shops) and kept the entire address book + bridge 100% plain-JSON for QuickJS-ng.
2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked by running:** `tsc --noEmit`, all 13 unit/invariant tests in `src/address/address.test.ts` (including 8-seed stress invariants, v1 plans, baked recipes, Dove Beach, 1900 & 1952 sim directories, typo/ordinal/relational/`1/2`/unit queries, and JSON round-trip), all 20 existing `src/town/` test files (179/179 tests), and direct printout inspection of Capital's 62 streets, Dove Beach's 31 streets & 19 landmarks, and Capital's 1952 directory — which caught and fixed three real bugs before delivery (over-eager fuzzy edit distance matching `"diner"` to `"pine"`, numbered cross-streets sorted west-to-east instead of outward from center, and `Neck Fort`/`Eastcove` snapping to underground `Headland Tunnel`).
   - **Only reasoned about:** Runtime execution inside Android's embedded QuickJS-ng engine (no JVM/Android runtime in this sandbox; verified by static grep for forbidden Node/browser APIs and strict `JSON.parse(JSON.stringify(...))` round-trip equality).
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   - The 1952 Mapleridge City & Telephone Directory printout (`Town Hall & Square — 101 W. Main Street [Tel: SPring 4-5179]`, `Post Office — 101 W. Dunmore Street`, named bakeries with proprietors and staff) and seeing Dove Beach's `100 Marine Carriage Drive` (1920) become `100 The Esplanade, Dove Beach 26711` (1985) while house numbers stay rock-steady across a century.

## Ideas for the team

1. **Address plaques & street-blade signs via the sign painter:** Now that every building has a stable `houseNumber` and every intersection has era-correct `streetNameIn(street, year)`, feeding `TownAddressBook` into the Kotlin sign painter (`G007`) can stamp brass/enamel house-number plaques by every doorway and period-styled corner street signs (cast-iron posts in 1900, green reflective blades post-1965) — including ghost-painted old street names on brick corners after a historical rename.
2. **Era-aware mail & "old address" detective leads:** Because `find(book, query, year)` resolves historical street names and reports `formerStreetNames`, NPC letters, newspaper clippings, and the professor's opening note can cite a 1920s address (like `143 N. Livery Lane` or `100 Marine Carriage Drive`) that the player looks up in the library's back-issue city directories to find the modern building (`N. Depot Street` / `The Esplanade`).
