# C041 — Town politics

Added era- and size-dependent town offices, calendar-based elections, resident nominations, neighbourhood turnout and vote totals, and policy settings from the winning mayor's platform. In the 1952 race a burglary wave can lift support for the policing ticket and double the night patrol if it wins.

## Tests

- `npm test -- src/politics src/calendar` passed 9 tests across 2 files.
- `npm run typecheck` passed.

## Self-review

1. Candidate platforms are the weakest part. The three profiles give pressure distinct effects, but civic ideology and budgets are still simplified. The platform data is explicit and can be tuned independently of turnout and ballot counting.
2. Tested the real calendar and deterministic RNG, including date checks, crime response, voting arithmetic and policy output. Appointments, news, patrol spawning and phone behavior remain unwired.
3. The owner would first notice the burglary wave changing the race, then the winning mayor doubling the night patrol.

## Usage

- Model and reasoning setting, tokens and plan usage: not visible.
- Effort: one scheduled run. Exact wall time to push and aggregate tool-call count not visible.
- Rework: initial delivery, zero review rounds.
