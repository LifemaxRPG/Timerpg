# C045 — Mail-order catalogues and deliveries

Added era-gated catalogues and ordering, postal and transit delivery estimates, pickup rules, and a durable order record the game can use as evidence.

## Tests

- `npm test -- src/mailorder src/comms src/transit src/economy` passed 40 tests in 8 files.
- `npm run typecheck` passed after restoring the existing tool fixtures required by the sparse checkout.

## Self-review

1. Processing times use three broad era defaults. The separate postal, processing and parcel legs make the estimate straightforward to tune.
2. Tested the 1925 and 2000 delivery dates against the real comms and transit modules, along with era restrictions, records and replay. Wallet, pickup and crime integration still need wiring.
3. The player would see an order slip with the name they gave and the day to collect it.

## Usage

- Model setting, token totals and plan usage are not visible.
- One run. Exact elapsed time and aggregate tool count are not visible.
- Initial delivery, zero review rounds.
