# C024 — Fares and timetables

Delivered eight era-gated modes, nominal fares, railway classes, service-day departures, and journey durations. [Transit API](../../docs/TRANSIT.md)

## Verification

- All 32 transit and economy tests passed. TypeScript and staged whitespace checks passed.
- The streetcar costs 5¢, leaves every 12 minutes at midday, and runs its last car at 23:40. The 40 km third-class night train costs $1.85 and leaves at 22:15.
- Route wiring, vehicle movement and on-device gameplay were untested.

## Self-review

1. Headways and vehicle speeds are authored defaults. The route can override rail departures, and the game still decides which services exist.
2. Tests checked era gates, fares, last runs, peak versus owl frequency, rail overrides, and travel time.
3. In 1925 the player can read a nickel fare and a precise last departure.

## Usage

- One interactive implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
