# C037 — Games of chance

Added four keyed games, abstract poker tiers, rigging detection, era stakes, sporting fixtures, betting and numbers settlement, venue legality and debt escalation. [Gambling API](../../docs/GAMBLING.md)

## Verification

- All 33 gambling and economy tests passed. TypeScript and staged whitespace checks passed.
- Sampled 10,000 keyed dice rounds per fairness mode and tested odds, payout, sport and numbers settlement, legality, and debt milestones.
- Wallet, venues, paper and radio wiring remain pending. Android and the full suite were untested.

## Self-review

1. Poker rolls authored outcome tiers, so the game cannot show or exchange specific cards with this module.
2. Tests covered long-run returns, crooked odds, a $5 stake, deterministic fixtures, the runner payout, legality and debt.
3. A strong hand can return $22 gross from a $5 buy-in in 1952.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
