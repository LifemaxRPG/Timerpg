# C030 — Lodging and food places

Delivered era-specific food menus, lodging offers and economy-backed opening hours. The 1952 diner fixture prices coffee at 10¢, the blue plate at 65¢ and pie at 20¢. A Station Hotel room is $2.50 a night with a shared bath. [Places API](../../docs/PLACES.md)

## Verification

- All 37 places, economy and needs tests passed. TypeScript and staged whitespace checks passed.
- Menu UI, checkout and room occupancy still need game wiring. Android and the full repository suite were untested.

## Self-review

1. Each lodging venue returns one room class with seeded capacity. Per-room inventory is left to the caller.
2. Tests cover era gates, hours, price and lodging anchors, seeded menus and the needs API.
3. The diner specials and the Station Hotel's shared bath make the era legible before the player pays.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
