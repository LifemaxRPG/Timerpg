# Route numbers and signs

The made-up national scheme of Highways v2 (src/world/gen/highways.ts; WORLD_GEN.md "Highways that evolve: as built").
Every landmass numbers its own roads (each is its own country's network), so a number is unique per landmass and
system, not on the planet. Numbers are data by year on every road segment (`number`: [year, designation] steps).

## The three systems

| System | Designation | From | What it numbers |
|---|---|---|---|
| **Pennant routes** (the expressways) | `P` + number, e.g. P25, P239 | 1956 | limited-access trunks, their loops and spurs |
| **National routes** (the highways) | `N` + number, e.g. N96; business routes `N96B` | 1927 (the national numbering) | the old highways, chained into through routes |
| **County roads** | `C` + number, e.g. C12 | the year the road was paved | county roads, and highways handed to the counties |

## The rules

- **Direction.** A trunk that runs mostly north-south has an **odd** number, east-west an **even** one (by the span of
  the places it links).
- **Order across the landmass.** North-south numbers rise **west to east**, east-west numbers rise **north to south**, by
  position: a trunk's number is its place across the landmass spread over 1-97 (Pennant) or 1-197 (national), then
  nudged up so each is above the one before. A driver can tell roughly where a route lies from its number.
- **Loops** (beltways): an **even hundred** over the lowest trunk that meets the ring: a ring met by P39 is P239 (then
  P439, P639... if taken).
- **Spurs** (the radials from a beltway in to the town's edge): an **odd hundred** over the trunk that uses them: P139,
  P339... (past P939, into four digits keeping the hundreds' parity: P1139).
- **Business routes**: when a highway's town is bypassed, the old way through Main Street becomes `N<n>B`; from the
  1970s an old highway an expressway replaced may become `N<n>B` along its whole length, keep its number, or be handed
  to the county and take a `C` number.
- **Exit numbers** are kilometres: on a trunk from its west (east-west) or north (north-south) end, on a loop from its
  first anchor, on a spur from its inner end. Two exits in the same kilometre take letters (12, 12A).
- **Kilometre markers** follow the same count (a marker every kilometre: the distance along the route, a function of the
  route's segments; not stored).

## The shields (original designs; no real-world trade dress)

- **Pennant route**: a hanging banner: a flat top bar in **signal orange** (#E07A2E), below it a **deep teal** field
  (#1F6F6B) with straight sides and a **swallowtail notch** cut into the bottom edge, a thin **cream** inner border and
  the number in cream (#F3E9C8) in a heavy rounded sans. Loops add a small open ring glyph on the orange bar; spurs a
  short arrow on it.
- **National route**: a **milestone**: a white upright tablet with a **round-arched top** and a straight bottom, a black
  border and black numerals, a small `N` above the number. Business routes add a black band across the foot reading
  `BUS`.
- **County road**: a small **mustard-yellow square** (#D8B23A) with black numerals and a **dark green band** (#2F5A3A)
  along its foot carrying `C`.
- **Exit tabs**: a cream tab on top of a teal guide sign, `EXIT 55`. Guide signs: teal with cream lettering (the Pennant
  colours); national route guide signs white with black lettering.

The contact sheet's maps draw the Pennant shield (teal, orange bar, notch) and the milestone (white, arched).
