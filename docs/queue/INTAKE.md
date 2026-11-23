# Intake list: accepted models waiting to go into the game

Claude's intake job brings these into the game through our loader (never merging contributor branches) and places
them in the capital city. It runs after the town plan merges (`wf-town-plan`), because it edits the same ledger.

| ID | Branch | What | Where in the capital |
|---|---|---|---|
| pre | codex/public-library | Carnegie library, 1912 + 1970s wing | civic, near the square |
| pre | codex/corner-drugstore | Corner drugstore with soda fountain, 4 states | downtown corner |
| pre | codex/small-town-bank | Town State Bank, + drive-through | downtown |
| pre | codex/brick-church | Brick Gothic church + white country church | old town / farm towns |
| pre | codex/lincoln-high-school | Lincoln High campus, 4 states (revised) | edge of old town |
| pre | codex/carhop-drive-in | Milo's Drive-In, 3 states | highway strip |
| pre | codex/fire-station | Fire station 1924 + 1988 | civic, one block off the square |
| pre | codex/post-office | WPA post office 1938 + 1988 | civic, facing the square |
| pre | codex/highway-rest-area | Pine Bend rest area | highway outside the capital |
| pre | codex/lantern-pine-lodge | Log lodge | woods at the edge |
| pre | codex/the-eyrie | The Eyrie wings monument | a reserved landmark plot |
| pre | codex/union-arch | Union Arch | end of Main Street / park entrance |
| pre | codex/night-keeper | The Night Keeper giant | highway roadside attraction |
| pre | codex/beach-blvd-rex | The Beach Blvd Rex (approved exception; prototypes/ only, not its live-file edits) | roadside on the edge of town (later: a coastal hub) |
| pre | codex/great-meridian-monument | The Great Meridian (r2; guardians still blocky) | the capital's landmark plot |
| pre | codex/county-courthouse | Courthouse, 4 states (already imported by the town plan) | county seats |

## New from the queue (waiting for the next intake)

| ID | Branch | What | Where in the capital |
|---|---|---|---|
| C001 | codex/C001-five-and-dime | Marlow Five and Dime, 4 states (1925 dime, 1970 variety, 2000 Dollar Cove, vacant) | downtown Main Street, mid-block in a street wall |
| C002 | codex/C002-grain-elevator | Lark Bend grain elevator: 1925 timber, 1950 + concrete bins, 1990 co-op; rail siding reference in model-info | rail side, by the depot siding (also farm towns) |

Coastal and port set: these wait for the coastal hub and the ocean, so they aren't placed in the capital yet:
- codex/copperwake (the ships);
- codex/port-meridian;
- codex/breakwater-harbor;
- codex/needle-point-light;
- codex/alder-sloop;
- codex/vesper-yacht;
- codex/coastal-palm;
- codex/palm-collection;
- codex/beach-prop-pack;
- codex/surf-shops.

## Status (2026-11-22, branch wf-intake)

Every table row except the courthouse (imported by the town plan) is in the game and placed in the capital: kotlin-street/docs/LANDMARKS_GLB.md "The intake models",
kotlin-street/docs/TOWN_PLAN.md "The intake models in the capital". The coastal and port set still waits.
