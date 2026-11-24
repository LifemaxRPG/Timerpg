# Town politics

The functions in `src/politics` use plain JSON. Keep the town seed and unique resident and neighbourhood IDs stable across replays.

- `officesFor(size, year)` lists mayor, individual council seats and the local law office, with term length and selection method. Election years count from 1952. Elections fall on the first Tuesday in Leafmonth. From 1960 the mayor's term grows from two to four years, larger councils gain two seats, and small towns switch from an elected sheriff to an appointed police chief. Other towns already appoint the chief.
- `candidatesFor(residents, officeId, year, seed)` ranks eligible adults aged 25 or older with standing of at least 0.25. The score uses standing, ambition, diligence, a civil-service job bonus and a seeded tiebreaking component. Up to three ranked nominees receive platforms focused on policing, lower taxes or road improvements.
- `runElection(size, officeId, date, candidates, neighbourhoods, seed)` returns null outside the scheduled date, for duplicate candidate or district IDs, or with no candidates. Each district supplies an eligible voter count and normalized crime, price and unemployment pressure from zero to one. The result includes turnout, whole-ballot counts per candidate and neighbourhood, and the town tally. Vote ties sort by candidate ID. A race with no ballots has no winner.
- `policyFor(platform, mood)` returns the staffing, night patrol, shop hours, tax and road settings. Mayoral results include these settings automatically, using crime pressure weighted by eligible voters. A winning expansion platform increases police headcount by half and doubles night patrol when weighted crime reaches 0.65. Other races return a winner without townwide policy.

The caller supplies demographics and pressure, tracks officeholders, handles appointments, publishes stories and applies the policy settings. Election results can be replayed without changing state.
