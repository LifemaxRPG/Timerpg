# C021 — Opening hours by era

Added deterministic shop hours, holiday closures, and staffing estimates in the economy module. The integration examples are in [ECONOMY.md](../../docs/ECONOMY.md).

## Verification

- `npm test -- src/economy` passed all 23 tests, including the 19 existing economy tests.
- TypeScript and staged whitespace checks passed.
- The game still needs to supply local time and wire the API into shop interactions. Android and the full repository suite were untested.

## Self-review

1. Regional laws and individual staffing rosters are approximated with era defaults. The guide makes that boundary explicit.
2. Tests exercised closures, overnight fuel, seeded hours, holiday exceptions and rush staffing. Phone behavior was reviewed against the JSON API contract.
3. The 1925 general store closes on Sunday, and the 1985 highway station has one clerk on duty at 3 a.m.

## Usage

- One scheduled run with a WIP and final push. Model setting, tokens and plan usage were not visible.
- Initial delivery, zero review rounds.
