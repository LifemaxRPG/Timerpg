# Ships and passports

Routes contain two ports (`id` and `continentId`) and a distance in nautical miles. All inputs and outputs are JSON values.

- `timetable(route, year)` returns services by era, with weekdays numbered Sunday 0 through Saturday 6, local departure hours, crossing hours and fares in cents.
- `passportRequired(route, year)` applies to continental crossings from 1915. `passportOffice(year)` lists the office, fee, wait and required records. `applyForPassport(date, documents)` reports missing records or returns papers with an absolute calendar day for `readyOn`. `canBoard(route, date, passport)` checks the era and readiness.
- `unpaperedPassage(route, date, voyageId, kind, method, seed)` resolves fictional travel on a scheduled sailing and returns a hold for authorities or an illegal-landing event. Work passage is a freighter option. Keep the voyage ID and seed stable across replays.
- `dayAtSea(kind, travelClass, year, voyageDay, seed)` returns berth, meals, deck and a passenger encounter. Number voyage days from one.

The caller handles payments, ship movement, port checks and forwarding events to the crime system.
