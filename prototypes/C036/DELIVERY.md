# C036 — Houses for sale

Added seeded listings and era valuation, mortgage quotes using the bank's repayment ledger, foreclosure, Harvestmonth property taxes and repair reserves. The 1952 bungalow is $7,800 with 20% down, a $42.05 monthly payment for twenty years and $96 annual tax. [Housing API](../../docs/HOUSING.md)

## Verification

- All 38 housing, banking, economy and calendar tests passed. TypeScript and staged whitespace checks passed.
- Wallet, listings UI, taxes and foreclosure still need game wiring. Android and the full repository suite were untested.

## Self-review

1. Neighbourhood desirability comes from the caller, and the valuation has no local supply-and-demand model.
2. Tests covered era, size, street, condition, payment and default schedules, taxes and deterministic listings.
3. The bungalow has a readable down payment, monthly bill and annual tax before the player buys.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
