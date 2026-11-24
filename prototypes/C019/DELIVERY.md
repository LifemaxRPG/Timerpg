# C019 — Category prices and persistent shelves

Added relative retail curves and deterministic, JSON-saveable shop stock. The original `stockOf` API remains available for initial snapshots. The bridge-facing calls and a complete radio purchase and restock example are in [ECONOMY.md](../../docs/ECONOMY.md). The phone bridge and UI still need wiring.

## Verification

- `npm test -- src/economy` passed all 15 tests, including all 11 unchanged C018 tests.
- `npm run typecheck` and `git diff --check` passed.
- The phone and full repository test suite were outside this verification.

## Self-review

1. Deliveries reset shelves to capacity, so unsold stock and its previous price are replaced. The API guide documents that simplification.
2. Tests covered the 1952 radio, calculator and meal prices, daily versus weekly delivery, JSON persistence, and deterministic day advances. The bridge handoff was reviewed against the existing JSON contract.
3. The player comes back the next day to an empty radio shelf and can buy the replacement a week later for $30.48.

## Usage

- One implementation turn and a WIP push before final delivery. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
