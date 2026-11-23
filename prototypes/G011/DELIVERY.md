# G011 Delivery — The Loop Facade (`src/loop/`)

Branch: `gemini/G011-loop-facade` (branched from `ccr-52e43ebb-xnmj2e`, HEAD `f9bfaa1`)

## Summary

Built the plain-JSON playable-loop facade under `src/loop/`, the QuickJS-ng bridge (`src/loop/bridge.ts`), full JSON API documentation (`docs/LOOP_API.md`), a 50-person stub capital with families/friendships/street graph, and deterministic functional + 300-person performance test suites.

### Key Design Decisions
- **Plain JSON across the boundary:** Every input and output of `create`, `act`, `advance`, `attitude`, `policeView`, `spotted`, `arrest`, `save`, and `load` is pure JSON (zero `Map`, `Set`, class instances, `Date`, functions, `Math.random`, `Date.now`, or Node/browser APIs).
- **Single-place ID mapping (`src/loop/ids.ts`):** Bijectively maps arbitrary `src/sim/` IDs (e.g. `p:hale_marcus`, `place:hale_store`, `node:hale_block`) to G007 plan-language IDs (`[a-z][a-z0-9_]*`) and reverse-maps `routePlaceIds` back to sim IDs for Kotlin pathfinding.
- **Line-of-sight & back-turned stealth (`act`):** Evaluates bystander `hasLineOfSight`, `facing` / `facingRad` dot product, and `activity` (`watching`, `working`, `distracted`, `sleeping`). When a store owner's back is turned (or they are absent), `victimConscious` is set to `false` for `witnessesOf` so stealth shoplifting is not magically spotted by the owner, while keeping `victimId` intact so gossip and witness warnings still trigger victim reactions and reporting.
- **Pawn shop trace-back (`act` + `advance`):** Selling a stolen item at a `pawn_shop` records a `PawnRecord`, enrolls the seller and serial number into G008 `identityFiles` (`reason: 'pawn_registry'`), and connects stolen goods back to the player via G008 `collectCrimeTraces` / `reexamineColdCases` and pawn-ledger cross-checks.
- **QuickJS-ng bridge (`src/loop/bridge.ts`):** Exposes `globalThis.TimerpgSim` with stateful methods (`init`, `act`, `advance`, `attitude`, `policeView`, `spotted`, `arrest`, `save`, `load`) that keep the live `LoopSim` inside QuickJS memory so Kotlin only transfers small JSON events and deltas (plus stateless `*Json` wrappers when pure functional calls are preferred).
- **Note on G009 (`src/police/`):** Because `gemini/G009-police-action` is awaiting review and not yet merged into `ccr-52e43ebb-xnmj2e` (and branches must not be merged), `src/loop/policeAdapter.ts` provides a clean, self-contained G009-compatible adapter that compiles and validates real G007 plans through `src/plan/` (`parsePlan` + `bind`). Once G009 merges, `policeAdapter.ts` can delegate directly to `src/police/` without changing any public `src/loop/` signature or test.

## Files Added

No existing files outside `src/loop/` were modified (`src/crime/`, `src/plan/`, `src/sim/`, and `docs/queue/` are untouched).

- `src/loop/types.ts` — Plain-JSON input/output/state interfaces for the full loop API
- `src/loop/ids.ts` — Deterministic bijective `src/sim/` ↔ G007 plan ID mapper
- `src/loop/policeAdapter.ts` — Era-sized police force & compiled, bound G007 officer plans
- `src/loop/facade.ts` — `create`, `act`, `advance`, `attitude`, `policeView`, `spotted`, `arrest`, `save`, `load`
- `src/loop/index.ts` — Public barrel exports
- `src/loop/bridge.ts` — QuickJS-ng JSON-string bridge (`globalThis.TimerpgSim`)
- `src/loop/stubCapital.ts` — 50-person (and scalable 300-person) stub capital with Hale's General Store, Market, Pawn Shop, Police Station, Bakery, 10 homes, street graph, and lamps
- `src/loop/loop.test.ts` — 10 end-to-end deterministic tests (first-encounter scenario, unseen theft, legit work shift, pawn trace-back, open/closed enter + car theft + fine/jail arrest, G008 cold-case print match on booking, heat cooldown & jurisdiction isolation, save/load round-trip, determinism, QuickJS bridge)
- `src/loop/loop.perf.test.ts` — Standalone 300-person `advance(sim, 24)` timing suite
- `docs/LOOP_API.md` — Complete API reference with JSON request/response examples for every call
- `prototypes/G011/DELIVERY.md` — This delivery report

## Verification & Measured Results

- **Typecheck (`npm run typecheck`):** 0 errors.
- **Loop functional tests (`npx vitest run src/loop/loop.test.ts`):** 10 / 10 passed (96 ms).
- **300-person performance (`npx vitest run src/loop/loop.perf.test.ts`):**
  - 300 people with active witnessed shoplifting + house burglary + pawned serialized watch, `advance(sim, 24)` across 5 post-warmup samples in Node:
  - **`median = 2.38 ms`**, **`mean = 2.47 ms`**, **`max = 3.06 ms`** (samples: `[3.06, 2.39, 2.19, 2.36, 2.38]`) — **~21× under the 50 ms budget** in Node (leaving comfortable margin for QuickJS-ng on S25).
- **Regression suite (`npx vitest run src/crime src/plan`):** 34 / 34 passed (`plan.test.ts` 15, `evidence.test.ts` 9, `crime.test.ts` 10).
- **QuickJS-ng bundle & bare VM sandbox check:**
  - Bundled `src/loop/bridge.ts` with `./node_modules/.bin/esbuild src/loop/bridge.ts --bundle --platform=neutral --target=es2020 --format=iife` -> **183.5 KB** in 37 ms.
  - Executed the IIFE bundle inside a bare `node:vm` context with zero Node/browser globals (`process`, `fs`, `Buffer`, timers, `performance`, `window`, `document` all absent) running `init -> act -> advance(48) -> attitude -> policeView -> save -> load` cleanly.
- **API hygiene check:** Zero occurrences of `Math.random`, `Date.now`, `performance`, timers, DOM globals, or Node built-in imports in `src/loop/` runtime files.

## Self-Review

1. **What's the worst case or edge case I haven't tested?**
   - Multi-jurisdiction cross-border chases where the player commits a high-severity crime (`severity >= 0.75`) in `jur:capital`, escalates to `jur:county`, flees across a border node, and is spotted by a county officer, as well as very long multi-month saves (100+ days with dozens of open/resolved crimes) measuring save JSON growth on mobile storage.
2. **Does anything here look awkward or half-finished?**
   - `src/loop/policeAdapter.ts` duplicates a compact subset of G009's era sizing and G007 plan compilation because `gemini/G009-police-action` is not yet merged on `ccr-52e43ebb-xnmj2e`. Once G009 merges, swapping `policeAdapter.ts` to call `src/police/` directly removes that duplication cleanly.
   - `warnUnawareVictims` inside `facade.ts` bridges the gap between G005's ambient town gossip and a customer directly telling a shopkeeper they just got robbed; once G005/G009 are wired together, it could live as a clean named helper in `src/crime/` if other callers want the same behavior.
3. **Is there a simpler design I passed over?**
   - I considered having `TimerpgSim` be purely stateless (passing full `simJson` back and forth across JNI on every call), which would have saved ~50 lines in `bridge.ts`. Keeping `activeSim` in QuickJS memory with small delta returns avoids serializing 300 people's state across JNI on every player step or dialogue open — worth the small wrapper for S25 battery/latency, while still keeping the pure stateless `*Json` functions exported for testing.

## Ideas for the Team

- **Incremental Qwen fact-sheet delta:** `attitude(sim, personId)` already returns short plain-English `facts[]` ready for Qwen's prompt. On Kotlin, caching the last `attitude` per NPC and only refreshing on `attitudeChanges` (or on dialogue start) avoids re-querying unchanged NPCs.
- **Observing non-crime sightings (`observe`):** `LOOP.md` mentions a possible future `observe(sightings)` hook for non-crime street sightings (e.g., an NPC seeing the wanted player walk past a window and refreshing memory or calling police). Right now `spotted()` covers officers; adding a civilian `civilianSpotted(sim, personId, sighting)` helper would take ~25 lines and make `report_on_sight` NPCs actively call in sightings during free-roam.
- **Prune resolved `toldPairs` on `save()`:** After dozens of resolved crimes, `crimeState.toldPairs[resolvedCrimeId]` is no longer needed for gameplay; pruning resolved `toldPairs` on `arrest()` would keep mobile save files tiny over long campaigns.

## Usage

- Model / agent: Gemini ("Jeff") in AI Studio Build sandbox.
- Token counts / API cost: not visible in this environment.
- Runtime wall-clock work: ~1 session turn (including compaction resume, full implementation, typecheck, 45 passing tests across `src/loop` + `src/crime` + `src/plan`, 300-person perf benchmark, and QuickJS esbuild/VM verification).
