# C022 — The cost of living

Added era lodging and daily needs quotes, a JSON rent ledger with warning and lockout states, and daily NPC budget estimates for three income brackets. The 1952 room costs $8 a week and three diner meals cost $2. [Economy API](../../docs/ECONOMY.md)

## Verification

- Economy Vitest suite passed 28 of 28 tests, including all 23 existing tests.
- TypeScript and staged whitespace checks passed.
- Wallet, calendar, NPC dialogue and belongings custody still need game integration. Android and the full repository suite were untested.

## Self-review

1. The largest simplification is fixed rent periods and a shared price index for nonfuel expenses. The lease contract and its 30-day flat period are documented.
2. Tests checked the authored 1952 prices, era availability, deposits, arrears transitions and budget serialization. The game integration was reviewed against the JSON contract.
3. Saturday's rent and a day of diner meals leave the player with $1.

## Usage

- One implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
