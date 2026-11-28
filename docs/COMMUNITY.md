# Town community groups

The pure functions in `src/community` accept JSON records. Provide stable town and person IDs and the place kinds the town actually has. Standing and sociability use a 0–1 scale. Wealth and dues use cents. Supply residents of the town with unique IDs.

- `groupsFor(town, year)` returns era-appropriate group profiles for available venues. Dues are per meeting for weekly and monthly groups and per year for the chamber, indexed from 1952.
- `meetingsOn(town, date)` uses the game calendar. Weekly groups meet on their configured weekday. Monthly groups meet on its first occurrence each month. The chamber meets on the first Tuesday in Leafmonth. Hours are local. A congregation meets at 11 a.m. on Sunday.
- `membership(people, town, year, seed)` returns seeded rosters with officers selected by standing. The player uses the separate application check. Resident flags supply eligibility facts such as veteran status, school parenthood and business ownership. `religion: 'none'` excludes congregation membership.
- `tiesFrom(rosters)` returns symmetric affinity bonuses keyed by the simulation's pair key. When groups overlap, the strongest meeting frequency wins. Apply the bonuses as a snapshot when assessing gossip affinity. Repeated calls should replace the prior community contribution.
- `joining(roster, person, year, sponsorId)` checks eligibility, affordability, standing and sponsorship. A lodge sponsor must be a different current member. The result previews introductions, an officer reference and, before 1970, an informal police vouch when an officer can give one. The caller collects dues and persists accepted membership.

Venue placement, crowd spawning, gossip integration and applicant state are handled by the caller.
