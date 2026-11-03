# Timerpg: guide for code reviewers

A procedurally generated **time-travel town game** (TypeScript, Phaser 3, Vite, Vitest). You walk an endless
world of towns, scrub a "time machine" slider between 1950 and 2025, and change the past (kill, smear, gift,
introduce, invest, sabotage); the town's history is re-simulated and you see what you changed.
Pixel art is LimeZu's (licensed, omitted from this export); the game falls back to nothing without it, so the
sources are meant to be read, not run, from this zip. Run it with the full repo: `npm install && npm run dev`.

## The one design idea everything rests on
**The simulation is the source of truth; rendering and (future) AI only read it.**
- `src/sim/` is a headless, deterministic simulation (people, families, businesses, marriages, mayors...).
- **Keyed randomness** (`src/sim/rng.ts`): every roll is `rand(seed, year, kind, entityId)`, not a global stream, so
  changing the past only changes what it actually causes (no butterfly-chaos from unrelated dice). Please check this
  holds everywhere (a stray `Math.random()`, or a loop whose order depends on global state, would break it).
- A timeline = `seed + list of interventions`. Saves store only that.
- Future AI (LLM NPC dialogue) will narrate structured facts from the sim; it never decides outcomes.

## Layout
- `src/sim/`: `simulate.ts` (yearly rules + `runTimeline`), `social.ts`, `diff.ts` (compare two timelines), `worker.ts` (runs in a Web Worker).
- `src/town/`: the TOWN PLAN, a pure function of the seed. `network.ts` (street web), `frontage.ts` (plots along streets),
  `water.ts`, `landmarks.ts`, `greenery.ts`, `plan.ts` (orchestrates: network -> water -> plaza -> landmarks -> plots -> crop -> decoration),
  `layout.ts` (who/what occupies the plan in a given year; the town grows outward), `housing.ts`, `facade.ts` (shop fronts that match
  their goods), `era.ts` (lamps/trees/billboards/storefronts by decade), `inspect.ts` (what a click finds).
- `src/world/`: the endless world: one town per jittered grid cell, names, roads between towns, wilderness.
- `src/game/`: Phaser. `WorldScene.ts` (streaming, player, input, acting on the past), `TownView.ts` (one town drawn anywhere,
  loading work time-sliced), `generator.ts` (talks to the worker).
- `src/main.ts` + `index.html`: HUD, banner, action panel, touch joystick.
- `scripts/extract-assets.mjs`: extracts the sprites we use from the purchased packs, recolors houses, and *generates* 1950s
  storefronts in code (pixel font). `src/**/*.test.ts`: Vitest (~75 tests, mostly invariants: no overlaps, determinism, capacity).

## Where I would most like a second opinion
1. **Determinism and the "ripple" property**: any place where an intervention could cause unrelated changes, or where
   two runs with the same seed could differ (iteration order, floating point, object key order).
2. **Performance**: `layoutTown` runs on every year change; `TownView.refresh` destroys/recreates thousands of Phaser objects.
   Memory growth when walking through many towns (unloading, worker snapshots ~76 world copies per town).
3. **The generator**: `plan.ts` retries with a bigger town until capacity rules hold (`homeRoom >= 100`, 20 shops...).
   Is there a seed that can never satisfy them, or loops badly? (`planTown` throws if no plan fits.)
4. **Architecture for what comes next**: NPCs walking around, a knowledge/secrets system (who knows which facts, gossip
   spreading), LLM-voiced dialogue constrained to what an NPC knows. Does the current sim shape support that cleanly?
5. **Mobile/Android** (`capacitor.config.ts`, `docs/ANDROID.md`): untested on a real device.

## Known weaknesses (honest list)
- Streets are straight lines on a tile grid; houses are mostly recolored copies of two sprites (the pack has few small houses).
- 1950s look is faked by gating a few sprites and generating plain storefronts; there is no genuinely period art.
- Towns link only east-west by road; NPCs do not exist yet; no sound; no goals or win condition.
- Selection panel and HUD are plain DOM; no tests for the UI layer or Phaser rendering (checked by hand with screenshots).
