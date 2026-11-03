# Timerpg

A procedurally generated time-travel town sim. Visit a town in the present, travel back, change
the past (kill the future mayor, invest in the bakery...), and come back to see what you changed.

## Architecture

- `src/sim/` - the headless, deterministic simulation (no rendering). **This is the game's source of truth.**
  - `rng.ts` - *keyed* randomness: every roll is derived from `(seed, year, kind, entityId)`, so an
    edit to the past only changes what it actually causes instead of reshuffling the whole town.
  - `worldgen.ts` - builds the starting town (1950) from a seed.
  - `simulate.ts` - yearly rules (death, inheritance, economy, marriage, births, immigration,
    business founding, mayor elections) and `runTimeline()` which applies time-traveler interventions.
  - `social.ts` - pairwise affinity (seeded baseline + deliberate `bonds`) and reputation; drives marriages and elections.
  - `diff.ts` - compares the same year across two timelines ("what did my actions change?").
- `src/town/` - pure layout generator: sim state -> lots/roads (stable slots, so buildings keep their place across years).
- `src/game/` + `src/main.ts` - Phaser scene and HUD; they only read sim state.
- A timeline is `seed + list of interventions`. Saves only need those two things.
- Future AI (e.g. Qwen) narrates the structured `chronicle` / `WorldDiff`; it never decides outcomes.

## Art credits

Art by **LimeZu** - [Modern Exteriors](https://limezu.itch.io/modernexteriors) and
[Modern Interiors](https://limezu.itch.io/moderninteriors) (purchased; credit required by the license).
Only the sprites we use are committed, in `public/assets/` (about 140 files). To re-extract or add
more from your zips: `npm run assets -- --exteriors <Modern_Exteriors.zip> --interiors <Modern_Interiors.zip>`
(edit `scripts/extract-assets.mjs` to choose sprites). The repo is private; keep it that way unless
you have checked the licenses allow redistribution.

## Commands

```
npm install
npm test        # simulation tests
npm run typecheck
npm run dev         # play in the browser (http://localhost:5173, ?seed=abc for another town)
npm run build       # production build
npm run demo [seed]   # kills the present-day mayor as a child and prints the consequences
```

## Roadmap

1. [x] Deterministic simulation core + interventions + diff
2. [x] Verbs: kill, invest, sabotage, introduce, rumor, gift
2b. [ ] Secrets/knowledge, debts and feuds, land ownership, more verbs (blackmail, warn, teach), your own traveler inventory/allies
3. [x] Phaser + Vite renderer (placeholder art): layout from sim state (`src/town/layout.ts`), walkable player, year slider, original/altered timeline toggle
4. [ ] Time-travel UI + era-specific buildings and props + "what changed" screen
5. [x] Real LimeZu sprites: tiled terrain, villas (wealthy households) and small homes, shops by type, animated player. Next: trees/props, interiors, NPC sprites
6. [ ] Capacitor wrapper for Android
7. [ ] LLM narration and NPC dialogue from the chronicle
