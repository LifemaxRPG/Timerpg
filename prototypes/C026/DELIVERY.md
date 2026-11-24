# C026 — Hunger, sleep and health

Added JSON needs state, activity-based progression, food and rest relief, effects, illness, and minor injuries with era-priced treatment. [Needs API](../../docs/NEEDS.md)

## Verification

- All 33 needs and economy tests passed. TypeScript and staged whitespace checks passed.
- Gameplay wiring, including the menu price for breakfast and incident-to-injury mapping, remains pending. Android and the full repository suite were untested.

## Self-review

1. The health system uses a single illness flag and timers for minor injuries. It supports gradual consequences and recovery without a separate disease simulation.
2. Tests covered a healthy day, hunger thresholds, rest quality, treatment, price scaling and save determinism.
3. After skipping sleep and meals, walking slows and breakfast helps. A sprain costs $3 to treat in 1952.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
