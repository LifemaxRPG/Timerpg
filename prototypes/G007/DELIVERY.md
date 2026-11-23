# G007 — The Plan Language (`gemini/G007-plan-language`)

## What was built

Pure, deterministic, JSON-serializable TypeScript under `src/plan/`:

1. **`src/plan/plan.gbnf`** — Standard llama.cpp GBNF grammar for constrained NPU/Qwen decoding: 30 verbs, `then`/`while` combinators, 7 target forms (`person:`, `place:`, `item:`, `vehicle:`, `player`, `nearest(<kind>)`, `here`), `with item:<id>`, 6 manners including multi-word `as(<role>)`, and 7 conditions (`sees`, `at` with optional subject, strict 24h `time(hh:mm)`, `heard`, `has`, `alone`, `done(n)`) with optional `not`.
2. **`src/plan/types.ts`** — Plain-JSON AST, `PlanFacts`, `BindError`/`BoundPlan`, `ActorTraits`, `PlanWorld`, `PlanState`, `PlanFailure`, and `StepResult`.
3. **`src/plan/parser.ts`** — Strict recursive-descent parser kept in exact 1:1 agreement with `plan.gbnf`; pre-groups `while`-linked steps into concurrent stages separated by `then` and pre-parses `time(hh:mm)` into seconds-of-day.
4. **`src/plan/gbnf.ts`** — General llama.cpp GBNF compiler and memoized full-string matcher that parses `src/plan/plan.gbnf` directly off disk so tests verify true grammar/parser parity (not a regex approximation).
5. **`src/plan/bind.ts`** — `bind(plan, facts)` checks every `person:`, `place:`, `item:`, `vehicle:`, optional `nearest(<kind>)`/`heard(<signal>)`, `done(n)` bounds, and held-item requirements (projecting `take`/`buy`/`steal`/`carry` acquisitions and `give`/`put`/`sell` relinquishments across `then` stages). Returns model-readable error messages listing known/held ids.
6. **`src/plan/executor.ts`** — `createPlanState`, `step(planState, world, actor, dt, out?)`, `evaluateCondition`, and `checkTraitGate`:
   - Implements preconditions and effects for all 30 verbs with structured `PlanFailure` codes (`blocked_locked`, `blocked_closed`, `missing_key`, `wrong_key`, `insufficient_funds`, `caught`, `target_not_visible`, `trait_refused`, etc.).
   - Supports sustained concurrent `while` stages (`distract ... while watch ... until ...`), `if` skip-guards, observation recording (`follow`/`watch` → `tell`), vehicle driving, and trait gating (`gentle`, `honest`, `lawful`).
   - Mutates plain-JSON `PlanState` in-place (plus optional reusable `StepResult` `out`) for sub-microsecond per-actor stepping.
7. **`src/plan/stubWorld.ts`** — Deterministic `StubWorld` + `createCompoundStubWorld()` fixture with connected rooms/yards, locked doors & keys, two patrolling guards, a van, player, and companion.
8. **`src/plan/plan.test.ts`** — Comprehensive Vitest suite covering GBNF/parser parity, binding, all 5 required scenarios, determinism & JSON save/load, verb/condition/error coverage, and 50-actor performance.
9. **`docs/PLANS.md`** — Syntax reference, all 30 verbs with preconditions/effects/trait gates, and player-speech-to-plan examples.

---

## Test summary

Executed locally on Node v22.23.2:

- **`npx vitest run src/plan`** — **15 / 15 tests passed** (159 ms):
  - All 30 verbs covered; **90 samples** (46 valid + 44 invalid) verified for **100% exact agreement** between `parsePlan()` and `src/plan/plan.gbnf` via `compileGbnf`/`matchesGbnf`.
  - `bind()` catches unknown `person:`, `place:`, `item:`, `vehicle:`, `nearest(kind)`, `heard(signal)`, out-of-range `done(n)`, unheld `with item:` uses, and dropped-then-reused items while allowing `take ... then unlock ... with ...`.
  - All 5 scenarios pass end-to-end:
    1. *Keep guard talking while player goes round the back* (guard pinned at `front_gate`, plan finishes when player reaches `back_alley`).
    2. *When player signals, unlock back gate and bring the van* (waits for `heard(signal)`, unlocks & opens `back_gate`, drives `vehicle:van` to `back_gate`).
    3. *Follow guard quietly and report back* (tails patrolling `guard_2` hidden, records `courtyard -> office -> courtyard -> back_gate`, delivers report via `tell player`).
    4. *Locked door failure + replan* (fails with `blocked: place:storage_door locked`, replans via `office` → `storage_key` → `unlock` → `open` → `take item:ledger` and succeeds).
    5. *Trait refusals* (`gentle` refuses `attack`, `honest` refuses `lie`, `lawful` refuses `steal`; non-gentle actor succeeds).
  - Determinism & JSON save/load mid-plan: serializing `PlanState` at tick 15 via `JSON.stringify`/`JSON.parse` produces byte-identical final `PlanState`, actor positions, and world log.
  - Extra coverage for direct locked-door `go` blocking, `wrong_key`, `if` guards, `time()`, `done()`, `nearest()`, `not`, `buy`/`sell`, `steal`, `as(delivery man)`, `call`, `write`, `restrain`, `flee`.
- **`npm run typecheck`** (`tsc --noEmit`) — **0 errors**.
- **Measured 50-actor performance benchmark** (200 warmup ticks, then 5,000 ticks × 50 actors = 250,000 actor-steps via `performance.now()`):
  - **0.0196 ms per 50-actor tick** (~391 ns/actor-step) — more than 2.5× under the 0.05 ms budget, with 606.5 KB heap delta across 250,000 actor-steps (~2.4 B/step including V8 overhead).
  - Verified 3 consecutive `npx vitest run src/plan` runs pass 15/15 (182–221 ms).

---

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   `StubWorld` uses a simple room-graph + straight-line movement model rather than full navmesh/wall collision pathfinding, so multi-room steering in the live 3D town will rely on Claude's `PlanWorld.moveActorToward` implementation. Also, `src/plan/gbnf.ts` validates `plan.gbnf` in TypeScript rather than running the native `llama.cpp` C++ binary directly (which isn't built in this container). To keep both gaps tight, `PlanWorld` delegates movement/door blocking cleanly through `moveActorToward(..., destPlaceId)` and `plan.gbnf` uses only the minimal core llama.cpp GBNF subset (`::=`, `"lit"`, `[a-z]`, `|`, `(...)`, `*`, `+`, `?`, `#` comments) with 90 parity samples tested directly against the file.
2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - *Checked by running:* all 15 Vitest tests (`npx vitest run src/plan`), TypeScript strict check (`npm run typecheck`), and the 250,000 actor-step benchmark. Running the real scenarios immediately caught and fixed three real bugs in `executor.ts`: (a) `drive vehicle:van until at(place:back_gate)` short-circuiting when the driver was already at `back_gate` before boarding the van; (b) `follow` exiting on `until` before recording the target's final room in `observedPlaces`; and (c) continuous verbs (`distract`) in a `while` stage finishing after 1 tick instead of staying sustained until the sibling `until` step completed.
   - *Only reasoned about:* loading `src/plan/plan.gbnf` inside native `llama.cpp` / `@wllama/wllama` on Android NPU and how Qwen behaves on edge-case prompt phrasings.
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   The player-speech-to-plan table in `docs/PLANS.md` and the five scenarios in `plan.test.ts`—plain English instructions like *"keep the guard talking while I go round the back"* and *"when I signal, unlock the gate and bring the van"* turn into readable one-line plans that actually execute, fail cleanly on a locked door, replan with a key, or get refused by a gentle companion.

---

## Ideas for the team

- **Plan steps as grounded crime & witness triggers:** When Claude wires `PlanWorld` into the live town and `src/crime/`, each executed plan step already knows its exact `verb`, `manner` (`quietly` vs `rudely`), `as(role)` disguise, and `target`. Passing `manner` and `role` straight into `recordCrime()` (from G005) means a companion who `steal item:ledger quietly` lowers crime conspicuousness while someone who `lie person:guard_1 as(delivery man)` changes what witnesses remember in their police description ("a delivery man walked out the back")—so disguises, distractions, and witness gossip all interlock automatically with zero special-case scripting.

---

## Usage

```ts
import { readFileSync } from 'node:fs';
import { bind, createPlanState, parsePlan, step, type PlanWorld, type StepResult } from './src/plan';

// 1. Hand `src/plan/plan.gbnf` to llama.cpp / Qwen as the decoding grammar.
const gbnf = readFileSync('src/plan/plan.gbnf', 'utf8');

// 2. Parse & bind the model's plan string against the actor's facts.
const parsed = parsePlan(modelOutput);
if (!parsed.ok) {
  // Syntax error (should not happen when GBNF decoding is active)
  throw new Error(parsed.error);
}

const bound = bind(parsed.plan, actorFacts);
if (!bound.ok) {
  // Feed `bound.errors.map(e => e.reason)` back to the model to repair unknown ids / missing items.
  const repairPrompt = bound.errors.map((e) => e.reason).join('\n');
  // ...ask model to fix and re-bind...
} else {
  const planState = createPlanState(bound.bound, world.timeSec);
  const scratch: StepResult = { status: 'running', stageIndex: 0 };

  // 3. Tick each frame/sim step (plain JSON `planState` can be saved/loaded at any tick).
  const res = step(planState, world, actorId, dt, scratch);
  if (res.status === 'failed') {
    // Replan with `res.failure.message` (e.g. "blocked: place:storage_door locked")
  } else if (res.status === 'refused') {
    // Voice the refusal in character using `res.failure.message` ("refused: actor is too gentle...")
  }
}
```

## Usage (model & run metrics)

- **Model and setting:** Gemini (via Google AI Studio Build)
- **Tokens:** not visible
- **Effort:** ~10 tool calls, ~12 minutes wall-clock from start to push
- **Plan usage:** not visible
- **Rework:** initial delivery (round 1)
