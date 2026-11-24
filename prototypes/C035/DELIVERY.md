# C035 — Skills and learning a trade

Added eleven skills, diminishing-return progression, era-priced training, job thresholds and deterministic attempt outcomes. Three correspondence sessions and five garage shifts clear the mechanic-helper requirement. [Skills API](../../docs/SKILLS.md)

## Verification

- All 31 skills and economy tests passed. TypeScript and staged whitespace checks passed.
- The jobs UI, training interactions and action consequences still need game wiring. Android and the full repository suite were untested.

## Self-review

1. One progression curve applies across skills, with learning rates differentiated by method. Deeper trade-specific progression would require playtest data.
2. Tests checked the garage path, diminishing returns, era gates, price scaling, success odds and noise on a botch.
3. The player can see the mechanic-helper requirement and meet it through a course and garage work.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
