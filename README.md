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
- `src/town/network.ts`, `frontage.ts`, `plan.ts` - the **town plan**, a pure function of the seed. Pipeline:
  street network (dense but irregular web inside an organic blob outline; main street that jogs; streets that
  dead-end; a river replaces a cross street) -> water -> plaza -> landmarks -> building plots laid along street
  frontages (zoned: downtown shops, apartments/houses, small homes toward the edge, a villa hill) -> crop ->
  greenery (`greenery.ts`: sparse near buildings, thickening into forest, so the edge is organic) and street
  furniture. Grows until there is room for every shop and household.
- `src/town/properties.ts` - persistent buildings and occupancy, advanced once per simulation year. Existing households stay put despite wealth changes; surviving family can remain, vacant homes and shops are reused before new construction, and built roads never disappear. Building art is chosen when first built, not reselected every year. Physical state lives in `World.settlement`, so interventions rebuild it deterministically and viewing years out of order cannot change history.
- `src/town/layout.ts` - who/what occupies the plan in a given year. **The town grows**: streets are split at junctions and a
  block is only built once something on it is in use (plus the chain back to the main street); the rest stays woodland, so
  1950 is a compact core and the town spreads outward over the decades. Also: the town fills center-outward,
  initial housing tier follows household wealth (small / medium / house / villa / estate; poorest singles and couples share apartment buildings, see `housing.ts`), shop fronts are stacked modular floors (`facade.ts`), never-built plots remain wooded; vacant buildings remain standing, yards get props.
- `src/town/water.ts` - per-seed river (north-south, meandering, 3-6 wide) or lake, or none; plots near it are dropped, streets that meet it get bridge decks. The ground bake autotiles the shore; the player can't walk into water.
- `src/world/` - the endless **world**: `map.ts` (one town per cell of a jittered grid, seed per cell, plan origins, the road that
  joins a town's main street to its eastern neighbour), `names.ts` (town names and founding years), `wild.ts` (chunked wilderness).
- `src/sim/worker.ts` + `src/game/generator.ts` - a background worker generates each town's plan and 1950-2025 history, so walking into
  new territory never freezes the game.
- `src/game/WorldScene.ts` - streams towns in/out around the player, draws link roads and wilderness, announces towns you enter,
  walking/camera. `src/game/TownView.ts` - one town drawn anywhere in the world (loading work is time-sliced across frames).
  `src/main.ts` - HUD and the "Welcome to ..." banner. They only read sim state.
- A timeline is `seed + list of interventions`. Saves only need those two things.
- Future AI (e.g. Qwen) narrates the structured `chronicle` / `WorldDiff`; it never decides outcomes.

## Art credits

Art by **LimeZu** - [Modern Exteriors](https://limezu.itch.io/modernexteriors) and
[Modern Interiors](https://limezu.itch.io/moderninteriors) (purchased; credit required by the license).
Only the sprites we use are committed, in `public/assets/` (about 140 files). To re-extract or add
more from your zips: `npm run assets -- --exteriors <Modern_Exteriors.zip> --interiors <Modern_Interiors.zip>`
(edit `scripts/extract-assets.mjs` to choose sprites). The repo is private; keep it that way unless
you have checked the licenses allow redistribution.

## More docs

- `docs/ANDROID.md` - building the Android app
- `docs/REVIEW_GUIDE.md` - architecture and open questions, written for code reviewers

## Single-file build

`npm run build:single` writes `export/timerpg.html`: one self-contained file (code, worker and every sprite embedded) that works when
opened directly from disk or a phone's storage, no server needed. If the browser will not start the embedded worker, town generation
falls back to the main thread automatically.

## Commands

```
npm install
npm test        # simulation tests
npm run typecheck
npm run dev         # play in the browser (http://localhost:5173, ?seed=abc for another world)
npm run build       # production build
npm run demo [seed]   # kills the present-day mayor as a child and prints the consequences
```

## Roadmap

1. [x] Deterministic simulation core + interventions + diff
2. [x] Verbs: kill, invest, sabotage, introduce, rumor, gift
2m. [x] **Touch controls + Android**: on-screen joystick, pinch/button zoom, year step buttons, phone layout, tap to select; Capacitor project in `android/` (build steps in `docs/ANDROID.md`)
2l. [x] **Changing the past in-game**: click a building, pick a person/shop, choose an action (kill, smear, praise, gift, introduce, invest, sabotage) in the year the slider shows; the town's history is re-run in the background, a "what changed" panel compares it with the original, changes persist per town (localStorage) and can be undone
2k. [x] Endless world: towns stream in as you walk (background worker), have names, are joined by roads, "Welcome to ..." banner
2j. [x] Signs match the goods: business kinds are limited to ones the art can honestly show (bakery, butcher, ice cream, music, bait, clothing, dress, toy, gym, bank, law office, doctor); each kind only uses window sprites that match it (guarded by a test)
2i. [x] Classic (pre-1975) storefronts: generated in code (extract script) - flat painted sign boards with block lettering from a built-in pixel font, replacing the pack's big modern signs; 120 sprites, one per business kind
2h. [x] Era look (`src/town/era.ts`): old-fashioned street lamps and young trees in 1950, modern lamps/billboards/mature trees later
2g. [x] Color variety: the extract script generates recolored copies (wall + roof colors) of the mobile homes and terraced houses (80 + 80 + 30 sprites), since the pack only ships one gray colorway
2f. [x] Ground texture (patches, pebbles, twigs, sprouts baked into the grass) and yards: houses get front yards with picket fence + gate, side/back hedges and a dirt path
2c. [x] World generation v2: per-seed street networks, districts, plaza, forest edge, street furniture, growth from the center
2d. [x] Landmarks (post office, school, fire/police station, hospital, hotel, clock tower) with per-seed opening years (`src/town/landmarks.ts`)
2b. [ ] Secrets/knowledge, debts and feuds, land ownership, more verbs (blackmail, warn, teach), your own traveler inventory/allies
3. [x] Phaser + Vite renderer (placeholder art): layout from sim state (`src/town/layout.ts`), walkable player, year slider, original/altered timeline toggle
4. [ ] Time-travel UI + era-specific buildings and props + "what changed" screen
5. [x] Real LimeZu sprites: tiled terrain, villas (wealthy households) and small homes, shops by type, animated player. Next: trees/props, interiors, NPC sprites
6. [ ] Capacitor wrapper for Android
7. [ ] LLM narration and NPC dialogue from the chronicle

## Persistent town history

The first generation improvement preserves properties across years. Vacant buildings have subdued coloring, keep their collision footprint, and can be inspected for their recorded construction/vacancy dates and former occupants. New shop tenants change the storefront display while retaining the upper floors and roof. Time-travel interventions re-simulate occupancy alongside people and businesses. Existing seed/intervention saves need no new payload; regenerated towns use the updated placement rules.

This is a foundation for slower visible change. Deliberate moves, renovation/demolition, construction stages, neighborhood palettes and individual tree aging are still future work. Buildings currently retain their original architecture indefinitely; wealth changes alone do not trigger a move.
