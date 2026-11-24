# C028 — Owning a car

Added seeded new and used listings, ownership papers, running costs, and a drive and repair state machine. In the 1952 fixture the used 1946 sedan costs $450 and registration costs $12. [Cars API](../../docs/CARS.md)

## Verification

- All 32 car and economy tests passed. TypeScript and staged whitespace checks passed.
- The test compared breakdown counts over 1,000 miles across 40 seeds and verified mileage stops when a breakdown occurs.
- Vehicle, wallet, garage and police wiring remain pending. Android and the full repository suite were untested.

## Self-review

1. Legal thresholds and efficiency curves are shared game defaults. The module models eras and body types without per-model or local jurisdiction data.
2. Tests covered market pricing, insurance, fuel costs, breakdown rates, deterministic mileage and repairs.
3. The used sedan has a much lower sticker price, but its age and service history matter on the road.

## Usage

- One interactive implementation run with a WIP and final push. Model setting, tokens and plan usage were not visible.
- Initial delivery, zero review rounds.
