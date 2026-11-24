# C020 — The fence

Added deterministic town fence selection, discoverable leads, trust-sensitive quotes and era-sensitive chances of a police tip. [ECONOMY.md](../../docs/ECONOMY.md) documents the JSON contracts.

## Verification

- `npm test -- src/economy` passed all 19 tests, including all 15 C018 and C019 tests.
- `npm run typecheck` and `git diff --cached --check` passed.
- Phone bridge, dialogue, venue binding and police reports still need integration. The full repository suite and Android gameplay were untested.

## Self-review

1. Cover profiles are generic because the selector receives residents without a map. The API documentation calls out binding a real venue before revealing a lead.
2. Tests checked selection, lead eligibility, quote and trust rules, and tip probability. The surrounding game flow was reviewed from the API contract.
3. A diner regular can point the player toward a buyer whose offer and willingness depend on the item and their relationship.

## Usage

- One scheduled run with a WIP and final push. Model setting, token counts and plan percentage were not visible.
- Initial delivery, zero review rounds.

## Round 1 — C020-r1

Replaced boolean heat with a 0–1 value. A stranger can sell a moderately hot stolen watch. Trust raises the accepted heat ceiling and softens the discount. At heat 0.9, Vic declines at trust 0 and buys at trust 0.8. Moved the existing price functions into `pricing.ts` to remove the circular import while preserving the public exports.

- All 19 economy tests passed, including the unchanged C018 and C019 suites. TypeScript passed.
- The heat scale and police-case inputs still need game wiring. The test exercises the exchange and trust rules, including the first deal with a stranger.
- Usage for this revision was one scheduled turn with a WIP and final push. Model setting, token counts and plan percentage were not visible.
