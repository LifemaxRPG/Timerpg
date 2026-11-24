# C029 — Owning a small business

Added seeded listings, purchase or lease state, inventory, daily trade, payroll, losses, creditor actions and resale valuation. The 1952 five-and-dime lists at $2,400 or $60 per month. [Business API](../../docs/BUSINESS.md)

## Verification

- All 37 business, economy and calendar tests passed. TypeScript and staged whitespace checks passed.
- Player transactions, creditor actions and UI still need integration. The full repository suite and Android were untested.

## Self-review

1. Wholesale cost is an authored share of shelf price. Seeded demand and actual staffing determine weekly results, so the illustrative $310 and $285 are not fixed outputs.
2. Tests covered holiday closure, payroll pressure, theft and breakage, sequential days, debt and resale.
3. The five-and-dime comes with stock and a choice between buying and leasing. A broken window comes out of the day's profit.

## Usage

- One interactive implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
