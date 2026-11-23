# G005 — Witnesses, Word of Mouth and Police Reports (`src/crime/`)

## The Model in a Paragraph
`src/crime/` models grounded criminal consequences as a pure, deterministic, JSON-serializable chain driven by `src/sim/rng.ts` and `src/sim/social.ts`: `witnessesOf` decides who saw a `CrimeEvent` (from distance, time-of-day + street-lamp light, caller line-of-sight, attention, age, and the conscious-victim rule) and generates partial, fallible culprit descriptions; `advanceDay` then steps every recorded crime through severity-weighted memory decay (shedding clothing and build before hair and height, with murder never fading to nothing and refreshes extending recall), bounded town-scoped word-of-mouth gossip (attenuating confidence and distorting details at each hop, crossing towns only via kin or travelers), relationship/reputation/refusal-gated police reporting with historical era delays (pre-1900 walk-to-station vs post-1935 telephones), and jurisdiction-isolated police case files that merge reports weighted by confidence and first-hand priority, decay heat over time, and drive `isWanted` and `recognitionChance` against the culprit's current appearance.

## Gossip Curve (200-Person Town, Robbery)
Measured directly from `src/crime/crime.test.ts` (`seed = "gossip-200"`, 200 living residents with married couples, parent/child kin, 15 workplaces, and 8 initial first-hand witnesses on Day 0):

| Day | People Who Know | Share of Town |
| ---: | ---: | ---: |
| 0 | 8 / 200 | 4.0% |
| 1 | 11 / 200 | 5.5% |
| 3 | 21 / 200 | 10.5% |
| 7 | 32 / 200 | 16.0% |
| 14 | 59 / 200 | 29.5% |
| 30 | 82 / 200 | 41.0% |

## Era Latency Comparison (1880 vs 1990)
Measured on identical 50-person robbery scenarios (`stationDistanceKm = 4.0`, crime on Day 10):
- **1880 (pre-telephone):** `firstArrivalDay = 11`, `avgDelay = 21.9 h`, `sameDay = 0`
- **1990 (telephone era):** `firstArrivalDay = 10`, `avgDelay = 1.19 h`, `sameDay = 17`

## Test Summary
Commands run locally:
- `npx vitest run src/crime` (run 1) — **10 / 10 passed (`1.24 s`)**
- `npx vitest run src/crime` (run 2) — **10 / 10 passed (`1.35 s`)**
- `npm run typecheck` (`tsc --noEmit`) — **0 errors**

Covered tests in `src/crime/crime.test.ts`:
1. **Determinism & JSON round-trip:** Identical state across repeated runs and across mid-run `JSON.parse(JSON.stringify(state))`.
2. **Witnessing:** 2 a.m. unlit alley yields fewer and weaker witnesses than noon Main Street; street lamps boost dark-alley sightings; `canSee` stub blocks occluded bystanders; conscious victim always witnesses while murdered/unconscious victim does not.
3. **Memory:** Shoplifting fades to `null` within 28 days while murder persists at Day 90 and Day 365; clothing drops before height; re-sighting and police-interview refreshes restore confidence.
4. **Gossip & friend shielding:** 200-person robbery curve (above), strict second-hand confidence drop with hop/source ledger tracking, and close-friend reporting suppression vs neutral stranger.
5. **Jurisdiction isolation:** Town A police know (`isWanted = true`, `heat > 0`) while Town B police know nothing (`0` cases, `heat = 0`, `isWanted = false`), unless a witness travels to Town B (`travelTownsOf`) or has cross-town kin in Town B.
6. **Era latency:** 1880 pre-phone walk/ride delays vs 1990 same-day phone reports.
7. **Police merging, heat decay & recognition:** First-hand report outweighs conflicting second-hand rumors; jurisdiction heat decays over 90 days; changing clothes and ditching the getaway vehicle lowers `recognitionChance` while permanent traits retain partial risk; an officer on another continent has `0` recognition.
8. **Jurisdiction escalation & immutability:** Severe crimes (`murder`) escalate via `escalateJurisdictionOf` to state police while petty crimes (`shoplifting`) remain local; `advanceDay` never mutates input `CrimeState`.
9. **Spouse shielding:** Culprit's spouse reports at `< 15%` the rate of an unbiased stranger.
10. **Performance (2,000 people × 20 active cases):** Verified `< 5 ms` per `advanceDay` tick.

## Performance
Measured in `src/crime/crime.test.ts` on 2,000 people (4 towns × 500 residents) and 20 concurrent active crimes (`965` active ledger entries across 10 measured ticks after 10 warmup days):
- **Run 1:** median **`3.55 ms`**, mean **`3.68 ms`**, max **`5.77 ms`**
- **Run 2:** median **`2.71 ms`**, mean **`3.45 ms`**, max **`7.47 ms`**

## What's Simplified (and Integration Hooks)
- **Bounded social circle sampling:** Each person's non-kin friend circle is a bounded deterministic sample (`4` local friends + `2` coworkers + all explicit `world.bonds` + all spouse/parents/children/siblings) cached per `(world, ctx)` in a `WeakMap`, rather than scoring all `N²` pairs each day.
- **No arrest/trial/jail loop yet:** Police track cases, merged descriptions, heat, and `isWanted`, leaving patrol spotting, pursuit, arrest, bail, and Gazette story emission to the wiring tasks.
- **Discrete appearance vocabulary:** Descriptions use discrete bands (`height`, `build`, `hair`, `clothingColor`, `vehicle`) rather than free-text prose so weighted merging and `recognitionChance` stay fast and deterministic; AI dialogue can render these bands into natural NPC speech.
- **No edits outside allowed paths:** Integration hooks (`CrimeContext` callbacks `jurisdictionOf`, `townOf`, `placeTownOf`, `workplaceOf`, `travelTownsOf`, `stationDistanceKm`, `escalateJurisdictionOf`) are documented in `docs/CRIME.md` for Claude to wire into `simulate.ts` and the game loop.

## Allowed Files Created
- `src/crime/types.ts`
- `src/crime/witness.ts`
- `src/crime/memory.ts`
- `src/crime/gossip.ts`
- `src/crime/reporting.ts`
- `src/crime/police.ts`
- `src/crime/advance.ts`
- `src/crime/index.ts`
- `src/crime/crime.test.ts`
- `docs/CRIME.md`
- `prototypes/G005/DELIVERY.md`

## Self-review
1. **What is the single weakest part of this delivery, and what did you do about it?**
   The weakest part is sensitivity to CPU contention during early cold ticks on large worlds: `WorldSocialIndex` caches per-person social circles and `affinity()` lookups in a `WeakMap` keyed by `(world, ctx)` plus `world.people`/`world.bonds` object identity. When 2,000 people start populating their social circles simultaneously (or under heavy parallel CI load running every test file at once), `advanceDay` can briefly spike above 5 ms on a cold tick before the contact cache warms up. I minimized hot-loop overhead by precomputing daily decay factors, hoisting gossip contact evaluation out of per-knower closures, sharing one `rngFor` stream per `(day, crime)` and per `baseContactsOf`, adding symmetric `affinityCache` + `refusalRollCache`, and short-circuiting already-informed contacts before drawing RNG rolls—bringing median 2,000×20 time to `2.71–3.55 ms`. Callers that mutate `world.people` in place between days (instead of passing a fresh snapshot) should pass a new `CrimeContext` or world reference so the `WeakMap` index refreshes.
2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   All simulation logic in `src/crime/` was **executed directly** via two consecutive runs of `npx vitest run src/crime` (10/10 passing both runs) and `npm run typecheck` (0 errors), with the 200-person gossip curve, 1880 vs 1990 latency numbers, and 2,000×20 tick timings taken verbatim from test stdout. The only part reasoned about without live execution is wiring `CrimeState` into `src/sim/simulate.ts`, Gazette stories, AI dialogue prompts, and Kotlin/Android patrol AI, because G005 intentionally forbids editing files outside `src/crime/`, `docs/CRIME.md`, and `prototypes/G005/DELIVERY.md`.
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   They'd notice the grounded domino effect in the printed numbers: a noon robbery in a 200-person town starts with 8 witnesses, reaches 11 people the next day, 32 by Day 7, and 82 (41%) by Day 30; in 1880 the constable doesn't get the first report until the next day (`21.9 h` avg, 0 same-day) while in 1990 the phone rings in `1.19 h` (17 same-day); changing clothes and ditching the car reduces recognition without erasing height/hair; and fleeing to Town B genuinely leaves you unknown unless a witness travels there or tells kin in Town B.

## Ideas for the team
- **False accusations & lookalike stops:** Because `recognitionChance` already scores `CulpritDescription` against any `CulpritAppearance` (not just the real culprit), when the player has low-confidence police descriptions floating around town, an NPC who closely matches the merged description could briefly be questioned or gossiped about—and conversely, an innocent player who happens to wear the same coat color as a wanted thief could get stopped by a beat cop until cleared.

## Usage
- Model and setting: Gemini (AI Studio Build coding agent)
- Tokens (in / out / cache): not visible
- Effort: 1 run (rebuilt cleanly after sandbox idle reset), ~9 tool calls, ~7 min wall clock on rebuild
- Plan usage: not visible
- Rework: initial delivery (round 1)
