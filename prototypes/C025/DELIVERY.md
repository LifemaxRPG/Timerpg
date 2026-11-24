# C025 — The calendar

Delivered deterministic dates, nine fictional holidays, paydays, markets, school terms, seasonal daylight and a shop-date adapter. [Calendar API](../../docs/CALENDAR.md)

## Verification

- All 32 calendar and economy tests passed. TypeScript and staged whitespace checks passed.
- The test fixture puts Friday payday on Harvestmonth 14, with Lantern Festival the following day.
- On-device integration is untested. The existing shop-hours defaults still need wiring to fictional closures and alternating paydays.

## Self-review

1. Daylight is a temperate seasonal approximation. It does not model latitude or day-to-day shifts.
2. Tests covered leap-century round-trips, weekday cycling, holiday rules, pay rhythms, school terms, daylight and the shop-date adapter.
3. The player can read a Friday payday and see why the bank will close for the Lantern Festival tomorrow.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
