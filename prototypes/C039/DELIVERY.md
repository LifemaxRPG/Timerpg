# C039 — A night out

Added era-based indoor cinema and drive-in programmes, live-music schedules and covers, seeded baseball fixtures and scores, and crowd estimates for venues and nearby streets. The 1952 Friday cinema programme starts at 7 and 9 for 50¢. The Saturday home game draws people toward the park starting at 1:30 p.m.

## Tests

- `npm test -- src/entertainment src/calendar src/economy src/news` passed all 40 tests in 8 files.
- `npm run typecheck` passed.

## Self-review

1. Crowd counts are the weakest part: they use fixed venue turnout rather than town population or capacity. The API documents them as placement estimates and tests the traffic shift across arrival, game and departure.
2. Ran tests on the real calendar, economy and seeded RNG code paths. Live NPC placement, radio integration and phone behavior remain unwired and untested here.
3. The player would notice the crowd heading toward the ballpark half an hour before the first pitch.

## Usage

- Model and reasoning setting, token counts and plan usage: not visible.
- Effort: one scheduled run. Exact wall time to push and aggregate tool-call count not visible.
- Rework: initial delivery, zero review rounds.
