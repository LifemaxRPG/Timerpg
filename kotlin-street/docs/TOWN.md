# The walkable town (plan)

From one crossroads (two streets of town `eta`) to the whole generated town, drawn by Filament. The town comes from the
game's own generator (src/town/layout.ts `layoutTown`, neighborhoods.ts, landmarks.ts, frontage.ts), so going back to
procedural generation later is a seed change. Probe scripts used for this plan: run `npx tsx <script> <seed> 2025` from
the repo root (copies in kotlin-street/tools/town-probes/ if kept).

## Camera and movement (the owner's decision)

The game stays a side-scrolling street view (the camera follows along the street; the player moves in all directions
on the pavement). An open-world camera is a possible later option, so the walking code (T7) keeps the player's
position in world coordinates (wx, wz, heading) as the source of truth, with "which row, how far along it" derived for
the street view; nothing should assume the player can only exist on a row.

## Town and seed

- Fixed town for now: `TOWN = { seed: 'gamma', year: 2025 }` in `kotlin-street/tools/town-config.ts`, used by every
  exporter. `gamma` is a "Commuter suburb" like `eta`: 43 roads, 92 lots, 337 people, a river, all 5 neighbourhoods
  (9-34 lots each), 6 landmarks, 23 junctions (15 four-way, 5 T, 3 bends), 23 dead ends (mostly the forest edge).
- Civic centre: the road at x136 (roads 33/34/35) running north from the plaza on the main street (plaza x119-135):
  school (west side), fire station and clock tower (east side), hotel at the north end (road 9); south of the main
  street the post office (road 14) and police (road 18). Spawn the player on this civic avenue.
- `eta` keeps working through the v1 path (`town-streets.ts`, street.json v1 fields), so the desktop previews, the frozen
  classic renderer and existing tests (town-streets.test.ts, seam-matrix.sh) keep passing.

## Geometry: tiles to street units

- 1 tile = 32 px = 26.29 street units (BuildingSpec.W 184 per 224 px).
- Game roads are 2 tiles wide (fronts 4 tiles = ~105 units apart); the 3D street wants 178 (`Crossroads.MOUTH`).
  Widening road lines across the map splits lots, so instead: scale all POSITIONS by k ~= 1.69 (front to road centre
  2 tiles -> 89 units = half of 178), keep each building at its art size, and move each run of buildings along one road
  side with gaps <= 1 tile as one rigid group by its front-centre point (landmarks likewise). Boxes apart stay apart when
  centres scale by k >= 1, provided each 3D footprint fits its plot (CHECK with models.json depths: school, hospital,
  estates). A group's end lands >= 2k tiles from a crossing road's centre, so corners stay clear. Gaps between groups
  become lawns, trees, driveways and alleys.

## street.json v2 (a superset of v1)

Keep `shops`, v1 `streets` and `junction` (computed for the spawn junction). Add `version: 2` and:

```
town: { seed, year, k, unitPerTile, spawn:{row, x, lane},
  neighbourhoods:[{id,name,palette,yard,shopColour}],
  nodes:[{id, x,z, kind:'cross'|'tee'|'bend'|'end', arms:[{road, heading, mouthHalf}]}],
  roads:[{id, name, main, a:node, b:node, pts:[[x,z]...], width}],
  rows:[{id, road, side:'L'|'R', from:node, to:node, origin:[x,z], along:[dx,dz], normal:[nx,nz], length,
         buildings:[{sprite, x0, x1, setback, depth, group, neighbourhood, kind:'shop'|'home'|'landmark', lot}],
         furniture:[{kind:'lamp'|'bench'|'tree', x}],
         ends:{ start:{straight?:row, turnIn?:row, cross?:row}, end:{...} }}],
  landmarks:[{id, kind, sprite, row}], plaza:{rect, props}, water:{polys}, bridges:[...], props:[...],
  lots:[{id, row, rect (scaled world), kind, neighbourhood, yard}] }
```

- As built (T2, tools/town-graph.ts, tools/export-town.ts): the graph sits under `graph`, not `town` (v1 already uses
  `town` for the seed string, which GameActivity reads). World axes: x east, z south (the map's y), y up; positions are
  tiles x 44.5 (k 1.6929 x 26.286). Rows also carry `pts` (the building line as a polyline: bends), furniture `look`,
  and `ends.*.mouth` (a road on the building side or across the way on: groups keep 89 units clear). Nodes' arm
  `heading` is a unit [dx, dz]. Buildings' `setback` is from the building line to the front wall (yard included),
  `depth` the building's own, both in street units from tools/make_buildings.py's models (checked against the showcase of
  every sprite). Lot `rect`s move with their building along the row (rigid) and scale in depth. Landmarks get bN sprites
  after every lot (shops entries of kind `Landmark`). Yard props inside lots are dropped (the 3D models bring their own).
  The Kotlin fixture is kotlin-street/desktop/src/test/resources/town-gamma.json (kept current by town-graph.test.ts).
- A ROW is one side of one road between two decision points, in its own side-scroller frame: x to the right as seen
  from the pavement, buildings at +normal. Bends (2-arm nodes, the main street's steps) are polyline corners inside a
  row, not decision points. Transitions at each row end are precomputed: `straight` (cross the side street on the zebra
  to the same side's next row), `turnIn` (round the corner building, a 90 degree turn: today's up-key turn), `cross`
  (to the facing row across the road).
- Lot rects (scaled) are exported for yards, driveways and gardens (owner request: neighbourhoods that feel real).
- As built (T3, shared/.../domain/TownMap.kt, JunctionFrame.kt): `Town.parse(streetJson)` reads the `graph` section (null
  for a v1 file). `Row.pointAt(x, lane)` is exact along each segment (lanes `Crossroads.LANE` out from the building line
  towards the road); within 40 units of a bend the offset swings through the corner's mitre, so walks are continuous.
  `Row.layout(mirrored)` spans the row node to node (0..length) from the exported x0/x1; mirrored, x' = length - x.
  `Crossroads.at(town, node, fromRow, toRow)` takes a turn-in: at fromRow's end a right turn (plan as is), at its start a
  left turn (both rows' layouts mirrored, so CornerWalk still walks +X). The plan is the real geometry (Crossroads got
  optional `westEdge`, `zOff`, `frame` parameters; defaults unchanged): main building line Z = 0, cross building line
  X = 0, the node at (89, -89); the cross street's layout starts at the corner (89). `Crossroads.frame` (JunctionFrame)
  maps plan to world and each street's layout x to its row x. No east row (`east = null`) and `boxes` keep the old
  sprite-sized boxes: the town's real footprints come from the rows. Tests: desktop TownMapTest (walks CornerWalk round
  a right and a mirrored left turn and checks it keeps out of every real footprint).

## Walking the town (T7, as built)

`shim/TownWalk.kt`: `TownWalk(town)` is the one player walking the graph; the app or a test sets the keys on
`GameState.moveX/moveLane` and calls `step(dt)` each frame (not wired into GameActivity yet: the app still runs eta's
crossroads through CornerWalk as before).

- The truth is the world point (`wx`, `wz`) and heading (`hx`, `hz`, `heading`: 0 north, clockwise). The street view's
  `row`, `x` and `lane` are derived and kept (`placeAt(wx, wz)` finds the row whose pavement that is: `Row.locate` inverts
  `Row.pointAt`, bends and past-the-ends included). `GameState.x/lane` mirror them so the HUD and legs work as today.
- On a row: `GameState.step` (lanes 3..14, the furniture's `avoid`), then the real buildings: each a rigid box on its
  front's middle (setback, width, depth; `footprints(row)`), a step that would end inside one (2 units' margin) is not
  taken. `front` is the building whose front covers x; `door(row, b)` its front wall's middle.
- Decisions at a row's ends: STRAIGHT is walking on past the node line (the rows on one line, the lane carries over, no
  transit, no camera turn); a dead end or an end with nothing straight on stops at the node line less 20 as a street's
  end does. TURN_IN is a new press of the up key in the mouth (within `Crossroads.MOUTH_ZONE` before the node line):
  `CornerWalk.begin` on `Crossroads.at` (kept per corner), mirrored for a left turn at the row's start; after a left turn
  `GameState.walkOnDir = -1` so the walk on goes away from the corner. CROSS is a new press of the down key at the kerb
  (lane 14): a straight walk over the road to the facing row's kerb at the projected x, the camera swinging half round
  (the way the player faced), walked on by itself (`TurnTuning.autoWalk`) or back with up; arrived with down still held it
  walks on in. Crossing is allowed at the zebras (within `ZEBRA` 180 of either node) and mid-block while `midBlock`
  (default true until there is traffic). After any transit the keys must be let go before the next.
- For the renderer (T8) `TownWalkView`: `row`, `x`, `lane`, `wx/wz`, heading; `camera: TownCamera(tx, tz, fx, fz)` (the
  point on the building line it looks at and its looking direction; `rx/rz` its right, `yaw` 0 north clockwise; at rest
  square to the row, swung smoothly through the main street's steps by `Row.lookAt`; through a turn the plan's camera
  mapped to the world, through a crossing eased half round); `transit: TownTransit?` (kind, from, to, node, the corner's
  `Crossroads` and `JunctionFrame`, `progress`, `ease`); `liveRows()` (the row, its neighbours one transition off, a
  transit's target's too, nearest first); `front`; `rowChanges` (bumps when the row changes). The look-ahead
  (`CameraLead`), eye height and framing stay the renderer's.
- Tests (desktop TownWalkTest on the gamma fixture, a scripted bot): all 472 transitions taken there and back, every one of
  the 96 rows reached from the spawn in one continuous walk (190 transitions, about 18 minutes of play), the 70 corners'
  right and left turns mirror images (path length, frames, arrival 109 from the node, the camera a quarter each way),
  every row walked end to end in lanes 4 and 8 both ways without sticking; every frame: world position continuous (largest
  step 3.5 units, a run round a bend's mitre), camera continuous, never inside a building.
- Gamma's 6L has one building (b18) standing over a bend of the main street's step: kept as a rigid box square to the
  row at its middle (in the corner's outside); the export could keep groups off bends.

## Phases (separate files so agents can run in parallel)

| # | Task | Files |
|---|---|---|
| T1 | Shared building list refactor + test that v1 output is byte-identical | tools/town-buildings.ts (entry/vary/homeFacade out of town-streets.ts), tools/town-streets.ts |
| T2 | Graph export: rows, nodes, transitions, rigid-group placement, landmarks (landmarkArtKey helper in src/town/landmarks.ts, same pickVariant as src/game/TownView.ts:317), props, lots, v2 json. Tests: deterministic; every lot in exactly one row; no footprint overlaps after scaling (models.json depths); transitions both ways; every row reachable from spawn | tools/town-graph.ts, tools/export-town.ts, tools/town-config.ts, tools/town-graph.test.ts |
| T3 | Kotlin TownMap parse + desktop tests on a fixture | shared/.../domain/TownMap.kt (Town, Row, Node, Placement, Transition, Row.pointAt, Row.layout(): StreetLayout), domain/JunctionFrame.kt |
| T4 | Filament mesh building in building-local space (no visual change) | scene3d/BuildingMeshes.kt, FilamentStreet.kt (after the camera and buildings branches merge) |
| T5 | Dialogue and interiors over all buildings (people looked up by sprite) | tools/export-dialogue.ts, tools/export-interiors.ts, app dialogue/DialogueData.kt |
| T6 | Parallel asset build (multiprocessing.Pool in make_buildings.py), regenerate for gamma | tools/make_buildings.py |
| T7 | Walking the graph: rows, decisions, Crossroads.at adapter (CornerWalk reused in a local JunctionFrame, mirrored for left turns) | shim/TownWalk.kt, small additions to GameState/CornerWalk |
| T8 | Whole-town Filament scene (built once, nearest-first in ~4 ms batches; street life only near the player) + TownFrame | scene3d/FilamentTown.kt, scene3d/TownGround.kt, scene/Stage3D.kt (after T3, T4, camera) |
| T9 | Plaza, water and bridges, forest edge, street signs, yards/driveways/grass, street props | new files under scene3d/ and tools/ |
| T10 | App wiring: load, spawn on the civic avenue, HUD street + neighbourhood | GameActivity.kt |

Risks: camera-branch conflicts (agree a CameraPose interface); deep models overflowing plots; scale (k 1.7 makes gamma
~11,000 units across: a run or quick travel later, then cars); seed switch (keep eta via v1); mirrored turns (test
both hands); load time (decode textures in the background, build nearest-first).

## Done so far (branch wf-town)

- T1, T2, T3, T5, T7 done (T7 on branch wf-town2: shim/TownWalk.kt). v1 (eta, street.json v1, tools/town-streets.ts) is byte-identical: street, dialogue and interiors
  exports for eta, vale, alpha and gamma match the pre-split recordings, and eta matches the shipped assets
  (town-buildings.test.ts, town-graph.test.ts).
- Gamma 2025: 47 nodes, 48 roads, 96 rows (40 with buildings), 92 lots + 6 landmarks each on exactly one row, no
  footprint/lot overlaps, nothing in a street or the river, every row reachable from the spawn (8L, Civic Avenue).
- One building list: `townLots()` lives in tools/town-buildings.ts and both tools/town-graph.ts and tools/town-whole.ts
  name from it (v1 names first, then layout order, then the landmarks b92..b97). town-whole.test.ts exports the real
  graph and runs `export-dialogue --all --street` on it: every NPC gets its row and index. Landmarks on rows are skipped
  by the dialogue's sprite check (nobody lives there yet).
- Not yet: eta, alpha and vale have placement conflicts in the graph (nudges into streets, overlaps): fix before any seed
  switch. Landmarks have no NPCs or interiors. The app still ships v1; gamma's street.json v2, dialogue and interiors
  must ship together (DialogueEngine refuses a town/year mismatch). DialogueEngine still orders generation by v1
  street/index (off-street NPCs come last) until T10 orders it by rows.
- T6 (wf-town2): tools/make_buildings.py builds the shops in a process pool (`--jobs=N`, default every core); the
  workers return what each shop added to WINDOWS/HOUSES/MODELS and the main process merges them in shop order, so the
  output is byte-identical to `--jobs=1` (hashes of every file match on eta, the showcase and gamma). On the 4-core
  build server (shared with other agents): gamma 98 buildings 5.3 s serial, 2.0 s parallel; showcase 9.2 s -> 5.3 s
  (CPU-bound and limited by the shared server). `tools/build-town.sh [seed]` writes the whole town's assets to
  `kotlin-street/build-town/<seed>/` (git-ignored: street.json v2 with yards, buildings/, yards*.png); T10 ships them.
- Yards (wf-town2, tools/town-yards.ts, tests town-yards.test.ts): street.json v2 `graph.yards` (one per lot: parcel,
  house, front and back rects, household summary, items {kind, rect, face, variant, layer, on?, zone}) and
  `graph.lanes` (back lanes reaching a street, and verges); `graph.props` loses the forest props now inside a parcel or
  lane. Parcels are lots widened along the row into the free ground between groups (shared half and half, at most 200
  units a side). Driveways run from a dropped kerb (across the 38-unit pavement, meeting the carriageway) beside the
  house to a pad, carport or garage behind it (estate_one_story_house: in front of its own garage door). Layers: ground
  (lawn, meadow, gravel) under surfaces (paths, driveway, patio, deck, pads) under objects and edges (fences, hedges,
  gates); an object on a surface names it in `on`. The building models still draw their own front lawn and fence across
  their width: a renderer that draws the yards should drop those. Flush-fronted apartment stand-ins (setback 0) have
  the pavement for a front yard. Not yet: shop fronts, landmark grounds (school yard, police parking), corner lots'
  second street side; houses on the dead-end row 45 stand partly past its end (a graph placement issue).
