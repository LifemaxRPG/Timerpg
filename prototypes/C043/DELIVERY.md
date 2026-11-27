# C043 — Ships and passports

Added era-specific sailings and fares, passport applications and clearance, seeded passage outcomes, and day-at-sea profiles. The example 1952 liner sails Tuesdays and Fridays and charges $85 for the five-day crossing in third class.

## Tests

- `npm test -- src/ships src/calendar src/economy` passed 37 tests across 7 files.
- `npm run typecheck` passed.

## Self-review

1. Fares are fixed by service and class before inflation. The service table documents those assumptions, and distance affects crossing time.
2. Tested scheduling, indexed fares, era gates, application waits and seeded outcomes. Live boarding, travel and crime integration remain unwired.
3. The player would first notice that a modern passport fails at the 1952 gangway.

## Usage

- Model setting, token totals and plan usage are not visible.
- Resumed the existing worktree. Aggregate time and tool count are not visible.
- Initial delivery with zero review rounds.
