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

C001, C002 and C003 went in on 2026-11-23 (see "Done" below). Waiting:

| ID | Branch | What | Where in the capital |
|---|---|---|---|
| C004 | codex/C004-elementary-school | Mill Branch one-room school (in use, abandoned) + Westmeadow Elementary 1955 and 1990+ | Westmeadow in a residential district of the capital; Mill Branch for farm towns (one abandoned at the capital's rural edge) |
| C005 | codex/C005-barbershop | Mercer Barber and Lilac Beauty, narrow Main Street shops, 3 eras each; `barber_pole` node spins | Main Street street walls (fill the empty lot beside the five-and-dime) |
| C006 | codex/C006-truck-stop | Cedar Relay truck stop 1960-1989 and Relay Travel Center 1990+: diesel and car canopies, diner, 30 truck bays, scale, lube bay / truck wash | the highway outside the capital (east approach), not in town |
| C007 | codex/C007-mini-golf | Thimble Trails Mini Golf, 3 eras, winding course with creek, pipe hole, golf-ball finale; statue pad for the Rex | roadside on the edge of town with the Beach Blvd Rex on its pad (later: a coastal hub) |

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

## Done: the third intake (2026-11-23, branch worktree-agent-a25e28c96190038b4)

C001, C002 and C003 are in the game and placed in the capital (kotlin-street/docs/LANDMARKS_GLB.md "The third intake",
kotlin-street/docs/TOWN_PLAN.md "The third intake in the capital"). Files copied with `git show origin/codex/<branch>:prototypes/<name>/...`, no merges.

| ID | Branch | Where it went in the capital |
|---|---|---|
| C001 | codex/C001-five-and-dime | `glb:five_and_dime`: Main Street's core, mid-block in the street wall (north side), 9 x 15 tiles; dime store 1925, variety 1970, Dollar Cove 2000, for lease from the 2010s |
| C002 | codex/C002-grain-elevator | `glb:grain_elevator`: the rail column's far end (two blocks made one, 82 tiles), 16 x 58 tiles facing the town side, its own siding along its back; timber 1925, concrete bins 1950, co-op 1990 |
| C003 | codex/C003-baseball-park | `glb:baseball_park`: a third south block of the grounds column behind the school, 117 x 74 tiles, its gravel lot on the street; wooden park 1935, lights 1950, steel bleachers 1960, renovation 2000; gravel re-skinned with the town's yard gravel |

## Vehicles (wait for the driving system, not placed as landmarks)

- C011 `codex/C011-cars-early`: Alderwick Six (1927-1930), Fenmere Club Coupe (1937-1940), Northcairn Terrace (1955-1957). Wheels, steering, doors, lights, repaint masks, mid and far models.
