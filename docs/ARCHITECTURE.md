# How the pieces connect

A living map of how the TypeScript simulation and the Android game fit together. Claude keeps it current; read it
before shaping an API that something else will consume.

## Two kinds of TypeScript

1. **Baked ahead of time:** the world, the towns and their people. `src/world/gen/`, `src/town/`, `src/sim/` and the
   tools in `kotlin-street/tools/` (`export-town.ts`, `export-street.ts`, `export-dialogue.ts`, `bake-world.ts`...)
   write JSON and packs into `kotlin-street/app/src/main/assets/`. The phone reads that data; it never runs this code.
   The rule: deterministic from the seed (`rand(seed, ...keys)`), the source of truth for what the world *is*.
2. **Run live on the phone:** anything that reacts to the player while they play: `src/crime/`, `src/plan/`,
   `src/police/` and the facade `src/loop/`. This code is **not ported to Kotlin**. esbuild bundles `src/loop/bridge.ts`
   and everything it imports into `kotlin-street/app/src/main/assets/sim/sim.js`, and the game runs that file in an
   embedded JavaScript engine (QuickJS-ng, in the native layer beside llama.cpp and Jolt). So what passes Vitest is
   exactly what the player meets (decided 2026-11-23; kotlin-street/docs/LOOP.md).

## The live boundary

```
Kotlin (the game)                         JS bundle (src/loop -> crime, plan, police)
-----------------                         -------------------------------------------
wallet, inventory, interactions   --act-->      who saw it, the crime recorded
line of sight, light, positions   --act-->      witnesses' memories
the clock (on the hour, on sleep) --advance-->  gossip, reports, police plans
moves officers along their plans  <--changes--  plans to act out, attitudes changed
shopkeepers, NPC dialogue facts   --query-->    attitude(person), policeView(jurisdiction)
an officer sees the player        --spotted-->  ignore, watch, question, arrest
save / load                       <--> one JSON string
```

- Everything crossing the line is a JSON string: no class instances, Maps, Sets or functions.
- The bundle may not use Node or browser APIs (`fs`, `process`, `Buffer`, timers), `Date.now` or `Math.random`.
- The game calls it on one background thread, in bursts (after an action, on the hour, while idle), never per frame.
  QuickJS was 1.2-2x slower than Node's V8 on the crime core's smoke workload (measured on the x86 server, not the
  S25; kotlin-street/docs/LOOP.md, "The JavaScript bridge"); heavier code may widen that, so budget accordingly.

## Conventions across modules

- **Ids.** `src/sim/` people and places use their own ids; the plan language accepts only `[a-z][a-z0-9_]*`
  (`src/plan/plan.gbnf`). The facade maps between them (e.g. `p:0001` becomes `p_0001`) in one place, `src/loop/`,
  never ad hoc in callers.
- **Units.** The TS sim works in metres and game days/hours. The Kotlin town works in street units
  (`VehicleWorld.U`, about 21 units a metre); Kotlin converts at the boundary.
- **Time.** A day number from the start of the game, and the era's calendar year alongside it.

## What's wired today

| Module | Status in the game |
|---|---|
| `src/world/gen/`, `src/town/` | baked into assets: in the game |
| `src/sim/` (people, schedules) | baked into the dialogue and town exports: in the game |
| `src/crime/`, `src/plan/` | tested; run on the phone through the JS bridge (QuickJS-ng, SimJs.kt) |
| `src/police/` (G009) | in review or in progress; joins the bundle through `src/loop/` |
| `src/loop/` (G011) | next: the facade the game calls |
| `src/court/` (G010) | on hold until the loop plays; a fine or a night in a cell stands in |

## Who consumes what next

Every review names what will be wired first and by whom, so its types can be shaped for that consumer.
