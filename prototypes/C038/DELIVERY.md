# C038 — Building security

Added deterministic building security profiles, entry options with skill difficulty and forcing consequences, era-gated alarm responses, and API examples in `docs/SECURITY.md`.

Runtime wiring for alarm triggers, dog encounters and consequence application belongs to the caller. This delivery covers the pure module.

## Tests

- `npm test -- src/security src/skills src/economy` passed, 36 tests across 7 files.
- `npm run typecheck` passed.

## Self-review

1. Alarm timing is the weakest part because the initial values are era defaults. The response table is explicit and tested so it can be tuned without changing the caller contract.
2. Ran automated tests for seeded repeatability, farmhouse and jeweller access, alarm eras, and forcing consequences. Integration with the live game was reasoned about only.
3. The same back door can offer a quiet skill check or a noisy entry that leaves visible consequences.

## Usage

- Model and reasoning setting, token totals and plan usage: not visible.
- Effort: one queue run. Exact wall-clock time and aggregate tool-call count not visible.
- Rework: initial delivery, zero review rounds.
