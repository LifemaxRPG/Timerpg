# Entertainment schedules and crowds

The functions in `src/entertainment` take plain JSON values and have no side effects. Use the town seed consistently. The day argument is the zero-based ordinal in the supplied calendar year. Convert a game-relative day using the calendar first. Hours are local decimal hours, so 13.5 is 1:30 p.m. Prices and covers are in cents, indexed from 1952.

- `cinemaProgramme(kind, year, day, seed)` returns the format, admission price in cents and programme, or null for a closed or unavailable venue. Kinds are `cinema` and `drive_in`. Each show has an hour, duration, original title and genre. On double-feature nights the listed price covers both films.
- `musicNight(kind, year, day)` returns the start and end hours, cover and act, or null. Kinds are `dance_hall`, `club` and `music_bar`.
- `seasonFixtures(year, seed)` lists Saturday baseball fixtures from April through September. `ballGame(year, day, seed)` returns the same record for a single date, or null. The `home` flag indicates whether the town team is hosting. Results are deterministic and available ahead of time to the simulation. News consumers should publish the result at or after `endHour`, and can promote fixture time and opponent before the game.
- `crowdAt(place, year, day, hour, seed)` returns an estimated headcount for the specified venue or street. Use any cinema or music kind, `ballpark`, `park_street` or `main_street`. Home-game arrivals and departures shift foot traffic for half an hour each. Away games generate no crowd at the local park.

Crowd counts are placement estimates. The caller chooses which real venues exist, assigns NPCs, and enforces capacity. Unsupported years and invalid dates return null for individual events, an empty fixture list or zero crowd.
