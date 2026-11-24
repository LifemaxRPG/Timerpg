# C042 — Life on the road

Added seeded roadside placement and encounters, optional hitchhiker clues tied to public news, weather-sensitive drive estimates and suggested meal and sleep stops. A passerby in a matching coat can make a radio bulletin matter to the current journey.

## Tests

- `npm test -- src/road src/weather src/needs src/economy` passed 44 tests across 8 files.
- `npm run typecheck` passed.

## Self-review

1. Stop density is the weakest part because it uses fixed era and road-kind spacing. The tuning is centralized, and the tests check how remoteness affects counts.
2. Tested era gates, fog and travel time, needs forecasts, seeded results and the news clue. NPC spawning, radio playback and road placement in the game remain unwired.
3. A lift to the port starts as an ordinary roadside encounter. A public bulletin about the same coat can make the player reconsider it.

## Usage

- Model and reasoning setting, token counts and plan usage: not visible.
- Effort: one scheduled run. Exact wall time to push and aggregate tool-call count not visible.
- Rework: initial delivery, zero review rounds.
