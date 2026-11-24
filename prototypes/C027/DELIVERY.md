# C027 — Seasonal weather

Added four regional climates, keyed weather fronts, evening fog, and modifiers for witnesses, walking, street activity and grip. [Weather API](../../docs/WEATHER.md)

## Verification

- All eight weather and calendar tests passed. TypeScript and staged whitespace checks passed.
- Verified Mistmonth fog at 18:00 for the documented seed, with 75 m visibility and a 6 m face recognition distance.
- Rendering, regional placement, and gameplay wiring remain pending. Android and the full repository suite were untested.

## Self-review

1. Fronts are local to a seed and do not travel across adjacent regions. Fog is a climate proxy until the game passes a waterside locale.
2. Tests covered cold-month snow, visibility, fronts, seasonal ranges, and JSON determinism.
3. Evening fog brings the face recognition limit down to six metres.

## Usage

- One interactive implementation run with a WIP and final push. Model setting, tokens and plan usage were not visible.
- Initial delivery, zero review rounds.
