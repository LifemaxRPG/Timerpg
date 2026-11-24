# C018 — The economy by era

Implemented the standalone economy in `src/economy/`. It has an authored 1880–2030 price index, 230 catalogue items, nine shop kinds, eight wage schedules, and pawn and fence quotes. The JSON API and calibration ranges are in [ECONOMY.md](../../docs/ECONOMY.md). All monetary values crossing the boundary are nominal integer cents.

The 1952 encounter yields 23¢ peaches, a $29.95 table radio, $6 clean and $3 hot pawn offers, and a 75¢ hourly sweeping wage. A two-hour odd-job shift pays $1.50.

## Verification

- `npm test -- src/economy` passed 11 of 11, including sale windows across all supported years and shop kinds.
- `npm run typecheck` passed.
- An esbuild bundle targeting neutral ES2022 ran a smoke test in an isolated JavaScript context without Node or browser globals.
- I stopped the broader Vitest runs before they finished. The unchanged plan-language benchmark missed its 0.05 ms limit in the broader run and again in a targeted retry (0.102 ms per 50-actor tick). That file's other 14 tests passed on retry.
- Claude still needs to wire the economy into the phone bridge, inventory and shop UI. QuickJS and Android gameplay were untested.

## Self-review

1. A single price index is the weakest assumption. It makes the broad era changes readable, but it cannot model the rapid relative price drop of early electronics. I calibrated the first encounter and added four sample-era range checks.
2. I checked the calculations, shop inventories, pawn spreads, formatting, types and standalone bundle in tests. I reasoned about the handoff to the phone from the architecture guide.
3. In the first ten seconds of the wired encounter, the cents sign on the peaches and the price gap between a radio and a sweeping shift should date the shop.

## Usage

- Model, reasoning setting, token counts and plan usage were not visible.
- One interactive implementation run. The first WIP was pushed before the catalogue and tests. More than 25 tool calls were visible by the delivery check.
- Initial delivery, no formal review rounds.
