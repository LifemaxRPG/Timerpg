# C044 — Church, clubs and community

Added eight community groups, calendar meetings, indexed dues, seeded memberships and officers, gossip affinity bonuses, and player joining checks with member references.

## Tests

- `npm test -- src/community src/calendar src/economy` passed 37 tests across 7 files.
- `npm run typecheck` passed.

## Self-review

1. A town currently gets one group per eligible venue kind. The profiles keep the place, cadence, dues and eligibility rules explicit so they can be tuned once the town supplies actual venues.
2. Tested calendar dates, veterans' post availability, affinity weights, lodge sponsorship and deterministic rosters. Live crowd spawning and gossip propagation still need integration.
3. The lodge expects a real member to introduce the player before it lets them join.

## Usage

- Model setting, token totals and plan usage are not visible.
- Initial delivery with zero review rounds. Aggregate wall time and tool count across interrupted runs are not visible.
