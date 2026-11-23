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

C001-C006, C009 and C010 are in (see "Done" below), except the C006 truck stop, catalogued but not placed (it needs a roadside plot outside the plan). Waiting:

| ID | Branch | What | Where in the capital |
|---|---|---|---|
| C007 | codex/C007-mini-golf | Thimble Trails Mini Golf, 3 eras, winding course with creek, pipe hole, golf-ball finale; statue pad for the Rex | roadside on the edge of town with the Beach Blvd Rex on its pad (needs a roadside plot like C006) |

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

## Done: the fifth intake (2026-11-23, branch ccr-52e43ebb-xnmj2e)

C009 and C010 are in the game and placed in the capital (kotlin-street/docs/LANDMARKS_GLB.md "The fifth intake", kotlin-street/docs/TOWN_PLAN.md "The fifth intake in the capital"). Files copied with
`git show origin/codex/<branch>:prototypes/<name>/...`, no merges. Both belong to the first playable loop (kotlin-street/docs/LOOP.md): reachable on foot from Main Street and the general store, their doors on a street.
Their named nodes (cell doors, bunks, window bars, front desk, booking and fingerprint spots; counter anchors, safe, display zones) are kept in each state's flat `model-info-<kind>.json` under `named`.

| ID | Branch | Where it went in the capital |
|---|---|---|
| C009 | codex/C009-police-station | `glb:police_station`: the civic district, where the sprite police station stood (it replaces it, as the post office and fire station replaced theirs): the civic block's south face behind the plaza, beside the post office, 22 x 15 tiles; brick station and jail 1900, dispatch / booking / garage 1940, glass entry and sally port 1980 |
| C010 | codex/C010-pawn-shop | `glb:pawn_shop`: a street-wall shop on the cross street just east of the general store (a side street off Main Street, seven tiles from it), 10 x 9 tiles, door on the cross street; brass balls and LOANS 1920, neon and bars 1960, LED WE BUY GOLD 2000 |

## Done: the third intake (2026-11-23, branch worktree-agent-a25e28c96190038b4)

C001, C002 and C003 are in the game and placed in the capital (kotlin-street/docs/LANDMARKS_GLB.md "The third intake",
kotlin-street/docs/TOWN_PLAN.md "The third intake in the capital"). Files copied with `git show origin/codex/<branch>:prototypes/<name>/...`, no merges.

| ID | Branch | Where it went in the capital |
|---|---|---|
| C001 | codex/C001-five-and-dime | `glb:five_and_dime`: Main Street's core, mid-block in the street wall (north side), 9 x 15 tiles; dime store 1925, variety 1970, Dollar Cove 2000, for lease from the 2010s |
| C002 | codex/C002-grain-elevator | `glb:grain_elevator`: the rail column's far end (two blocks made one, 82 tiles), 16 x 58 tiles facing the town side, its own siding along its back; timber 1925, concrete bins 1950, co-op 1990 |
| C003 | codex/C003-baseball-park | `glb:baseball_park`: a third south block of the grounds column behind the school, 117 x 74 tiles, its gravel lot on the street; wooden park 1935, lights 1950, steel bleachers 1960, renovation 2000; gravel re-skinned with the town's yard gravel |

## Done: the fourth intake (2026-11-23, branch worktree-agent-aa3869f31d3684250)

C004, C005 and C006 are in the game (kotlin-street/docs/LANDMARKS_GLB.md "The fourth intake", kotlin-street/docs/TOWN_PLAN.md "The fourth intake in the capital"). Files copied with
`git show origin/codex/<branch>:prototypes/<name>/...`, no merges. The capital's plan did not grow (965 x 382 tiles, `GAMMA_PINS` and `src/world/` untouched).

| ID | Branch | Where it went |
|---|---|---|
| C004 | codex/C004-elementary-school | `glb:westmeadow`: Westmeadow Elementary (1955, 1990+) in the capital's late subdivision, east end of a 152-tile block (69 x 32 tiles); `glb:mill_branch`: the Mill Branch one-room school (in use to the 1950s, abandoned after) at the grounds' far west edge beside the ballpark (18 x 15 tiles). Catalogued for farm towns too (not placed there: `INTAKE_FOR_OTHERS` is off) |
| C005 | codex/C005-barbershop | `glb:barber` (Mercer Barber 1940, Hair Co. 1980, Barber Co. 2010) in the gap beside the five and dime, in the street wall, party walls touching; `glb:salon` (Lilac Beauty / Styling / Nail Room) across Main Street beside the Gazette (the gap holds one 5-tile shop, not two). The `barber_pole` node spins (LandmarkAnimation `DEFAULT_TABLE`, 3.5 s a turn) |
| C006 | codex/C006-truck-stop | **catalogued, not placed**: Cedar Relay (1960) and Relay Travel Center (1990) are 127 x 144 m (106 x 69 tiles). No outside-the-plan placement exists in the current system (every glb plot is a plan block; `src/world/` is another job's), and the plan's east edge holds a 47-tile worker column and a 44-tile rail column: nothing of 106 x 69 tiles is free without growing the plan or removing the depot or the houses. For the next hub or a roadside-plot system |

## Vehicles (wait for the driving system, not placed as landmarks)

- C011 `codex/C011-cars-early`: Alderwick Six (1927-1930), Fenmere Club Coupe (1937-1940), Northcairn Terrace (1955-1957). Wheels, steering, doors, lights, repaint masks, mid and far models.
- C013 `codex/C013-motorcycles`: Brackenton Quarry Twin (1941-1949), Marrowfield Standard 550 (1971-1978), Kelveran Slipstream (2002-2008). Kickstand, two seats, lamps, repaint masks, mid and far models.
- C012 `codex/C012-cars-late`: Tallmere 72 Pickup (1968-1976), Rowanvale Sprint (1968-1974), Pinelatch City 3 (1990-1997), Stoneweir Wayfarer crossover (2012-2018, revised). The Wayfarer is the one not yet in the game.
- C017 `codex/C017-sports-car`: Rillhaven Torrel (1968-1976) and Torrel RS (1988-1996), mid-engine targa sports cars, the owner's request. Handling numbers in model-info.
