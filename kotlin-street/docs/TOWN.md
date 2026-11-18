# The walkable town (plan)

From one crossroads (two streets of town `eta`) to the whole generated town, drawn by Filament. The town comes from the
game's own generator (src/town/layout.ts `layoutTown`, neighborhoods.ts, landmarks.ts, frontage.ts), so going back to
procedural generation later is a seed change. Probe scripts used for this plan: run `npx tsx <script> <seed> 2025` from
the repo root (copies in kotlin-street/tools/town-probes/ if kept).

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
