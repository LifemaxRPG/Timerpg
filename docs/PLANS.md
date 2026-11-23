# The Timerpg Plan Language (`src/plan/`)

Companions and key NPCs act through short, strict plans produced by the on-device model (Qwen on the NPU) and executed step by step by the engine. The player speaks plainly; the model compiles the request into a plan; the engine guarantees nothing impossible can ever run.

## Three layers of safety

1. **Syntax (`src/plan/plan.gbnf` + `src/plan/parser.ts`)** — Constrained decoding via llama.cpp GBNF guarantees every model output parses cleanly with zero extra tokens, unknown verbs, or malformed clauses. Both the GBNF file and TypeScript parser accept and reject the exact same grammar.
2. **Name & inventory binding (`src/plan/bind.ts`)** — `bind(plan, facts)` checks every `person:`, `place:`, `item:`, `vehicle:`, `nearest(<kind>)`, `heard(<signal>)`, and `done(n)` against the actor's facts. Unknown ids and unheld items (unless acquired in an earlier `then` step) are rejected with clear messages listing known/held ids so the model can fix them immediately.
3. **World execution & trait gate (`src/plan/executor.ts`)** — `step(planState, world, actor, dt)` enforces preconditions every tick (locked doors, missing/wrong keys, line of sight, reach, funds) and runs a trait gate (`gentle`, `honest`, `lawful`) before sensitive verbs execute. Failed or refused steps return a structured `PlanFailure` for replanning or in-character refusal.

---

## Syntax

```ebnf
plan       ::= step (ws ("then" | "while") ws step)*
step       ::= verb (ws target)? (ws "with" ws item-ref)? (ws manner)? (ws "until" ws cond)? (ws "if" ws cond)?

target     ::= "person:" id | "place:" id | "item:" id | "vehicle:" id | "player" | "nearest(" kind ")" | "here"
item-ref   ::= "item:" id
manner     ::= "quietly" | "quickly" | "carefully" | "politely" | "rudely" | "as(" role ")"

cond       ::= ("not" ws)? cond-atom
cond-atom  ::= "sees(" target ")"
             | "at(" (subject "," ws?)? place-target ")"
             | "time(" hh:mm ")"
             | "heard(" signal ")"
             | "has(" item-ref ")"
             | "alone"
             | "done(" step-number ")"
```

- **Identifiers (`id`, `kind`, `signal`):** lowercase `[a-z][a-z0-9_]*`.
- **Roles (`as(role)`):** lowercase words separated by single spaces, e.g. `as(delivery man)`, `as(inspector)`.
- **Clock (`time(hh:mm)`):** strict 24-hour `00:00`–`23:59`.
- **Step reference (`done(n)`):** 1-based step index (`1..steps.length`).
- **Combinators:**
  - `then` runs stages sequentially (`A` completes before `B` starts).
  - `while` binds tighter than `then`, grouping adjacent `while`-linked steps into one concurrent stage. Continuous verbs (`wait`, `watch`, `follow`, `distract`, `hide`, `talk`) without their own `until` stay active for as long as any `until`-bounded or finite driver step in the same `while` stage is running, then stop cleanly together.
- **Guards (`if`):** Evaluated once when a step activates; if false, the step is marked `skipped` cleanly.

---

## The 30 verbs

| Verb | Target / `with` | Preconditions | Effects | Trait gate |
|---|---|---|---|---|
| `go` | `target` (or `here`) | Target has location; path not blocked by closed/locked door. | Moves actor toward target (`quickly` 1.5×, `quietly` 0.72×, `carefully` 0.75×). | — |
| `follow` | `person:` / `player` / `vehicle:` | Target exists and route is open. | Tracks target at safe follow distance (`quietly` sets hidden + wider stealth gap); records visited places in `state.observedPlaces`. | — |
| `wait` | none | None. | Stays put until `until` is satisfied (or completes after one tick if no `until`). | — |
| `watch` | `target` (or `here`) | None. | Observes target; records visible target places into `state.observedPlaces`. | — |
| `hide` | optional cover `place:` | Cover reachable (if given). | Moves to cover if specified and sets actor hidden. | — |
| `talk` | `person:` / `player` | Target exists, reachable within talk range, and visible. | Engages target in conversation (keeps guards occupied). | — |
| `ask` | `person:` / `player` | Target reachable and visible. | Logs/issues an inquiry to target. | — |
| `tell` | `person:` / `player` | Target reachable and visible. | Delivers `state.observedPlaces` report (if any) to target. | — |
| `lie` | `person:` / `player` (optional `as(role)`) | Target reachable and visible. | Deceives target; `as(role)` sets actor's disguise role. | Refused if `honest >= 0.80`. |
| `warn` | `person:` / `player` | Target reachable and visible. | Delivers warning to target. | — |
| `distract` | `person:` / `player` | Target reachable and visible. | Pins target's attention (pauses guard patrol while sustained). | — |
| `signal` | optional `person:` / `place:` | None. | Emits a named signal (or `"signal"`) detectable by `heard(...)`. | — |
| `take` | `item:` | Item exists, reachable, and unowned (or already actor's). | Moves item into actor's inventory. | — |
| `give` | recipient + `with item:` (or `item:`) | Actor holds item; recipient reachable. | Transfers item from actor to recipient. | — |
| `put` | `place:` + `with item:` (or `item:`) | Actor holds item; destination reachable. | Places item at destination place. | — |
| `use` | `target` + `with item:` (or `item:`) | Actor holds item. | Invokes `world.useItem(...)`. | — |
| `open` | `place:` (door/gate) | Door exists, reachable, and **not locked**. | Opens door (`blocked_locked` failure if locked). | — |
| `close` | `place:` (door/gate) | Door exists and reachable. | Closes door. | — |
| `lock` | `place:` (optional `with item:`) | Door reachable; actor holds required key if keyed. | Closes and locks door (`missing_key` / `wrong_key` on failure). | — |
| `unlock` | `place:` (optional `with item:`) | Door reachable; actor holds matching key or `lockpick`. | Unlocks door (`missing_key` / `wrong_key` on failure). | — |
| `carry` | `item:` | Item exists, reachable, and unowned. | Picks up and carries item. | — |
| `drive` | `vehicle:` (optional `until at(place)`) | Vehicle exists & reachable; path to destination not blocked. | Boards vehicle and drives toward `until at(...)` place (or `player` if omitted), moving driver and vehicle together. | — |
| `buy` | `item:` | Item exists; actor has `money >= price`. | Deducts price and transfers item (`insufficient_funds` if short). | — |
| `sell` | `item:` | Actor holds item. | Transfers item and credits money. | — |
| `steal` | `item:` | Item exists & reachable; fails with `caught` if spotted unhidden. | Steals owned item into actor's inventory. | Refused if `lawful >= 0.75` or `honest >= 0.85`. |
| `attack` | `person:` (optional `with item:`) | Target reachable and visible. | Executes attack against target. | Refused if `gentle >= 0.70`. |
| `restrain` | `person:` | Target reachable and visible. | Restrains target. | Refused if `gentle >= 0.85`. |
| `flee` | optional threat target | Threat resolvable (defaults to `nearest(guard)`). | Runs rapidly away from threat until safe distance or `until`. | — |
| `call` | optional `person:` / `place:` | Target callable. | Places call (delivering observed report if present). | — |
| `write` | optional `place:` / `item:` | Target writable. | Leaves a written note (including observed report if present). | — |

---

## Player speech → compiled plan examples

| What the player says | Compiled plan |
|---|---|
| *"Keep the guard talking while I go round the back."* | `distract person:guard_1 politely while watch person:guard_1 until at(player, place:back_alley)` |
| *"When I signal, unlock the gate and bring the van."* | `wait until heard(signal) then go place:back_gate then unlock place:back_gate with item:gate_key then open place:back_gate then drive vehicle:van until at(place:back_gate)` |
| *"Follow him quietly and tell me where he goes."* | `follow person:guard_2 quietly until at(person:guard_2, place:back_gate) then go player quietly then tell player` |
| *"Go grab the ledger from storage."* (fails on locked door) | `go place:storage_door then open place:storage_door then go place:storage then take item:ledger` → fails: `blocked: place:storage_door locked` |
| *Replan after failure:* *"Find the storage key in the office first, then get the ledger."* | `go place:office then take item:storage_key then go place:storage_door then unlock place:storage_door with item:storage_key then open place:storage_door then go place:storage then take item:ledger` |
| *"Bluff the guard as a delivery man until I slip past."* | `lie person:guard_1 as(delivery man) until at(player, place:courtyard)` *(refused if actor is honest ≥ 0.80)* |
| *"If you already have the key, open the gate; otherwise wait until 10 PM."* | `open place:back_gate if has(item:gate_key) then wait until time(22:00) if not has(item:gate_key)` |
| *"Knock out the guard!"* (on a gentle companion) | `attack person:guard_1` → refused: `refused: actor is too gentle to attack (gentle=0.90)` |
