# C032 — Letters, telegrams and the telephone

Added era mail options, postage and dispatch times, per-word telegram pricing and evidence records, phone adoption and quotes, operator hours and party-line risk. [Communications API](../../docs/COMMS.md)

## Verification

- All 31 communications and economy tests passed. TypeScript and staged whitespace checks passed.
- The 1925 overseas fixture takes nine days by ship or costs 75¢ for a same-day ten-word telegram.
- Message delivery, call gameplay and evidence handling still need game wiring. Android and the full repository suite were untested.

## Self-review

1. Postal timing uses projected distance and a transport rate. It does not track actual sailings or rail connections.
2. Tests cover era gates, collection, delivery, costs, office records, party lines and repeatable JSON.
3. A telegram reaches the professor that day, while a letter takes nine days.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, tokens and plan usage were not visible.
- Initial delivery, zero review rounds.
