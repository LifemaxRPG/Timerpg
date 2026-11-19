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
`GameState.moveX/moveLane` and calls `step(dt)` each frame (since T10 the app plays it through `TownPlay`; the old eta
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
- T10 (wf-t10): the app plays gamma (see STATUS.md "The walkable town in the app"): assets/town/gamma/ (street.json v2,
  buildings, dialogue.json v2 and interiors.json from `export-dialogue.ts gamma 2025 --all --street build-town/gamma/street.json`
  and `export-interiors.ts gamma 2025 --all`), assets/town/art/ (the pack pictures TownArt asks for, listed by the preview
  in art-paths.txt); `TownPlay` (shim) is the game's town walk, people, talk range and events; `TownRun` (scene3d) the
  build-and-draw path the app and the preview share; `TownPeople` the people's cards. DialogueEngine orders writing ahead
  by rows. The AI panel switches back to the eta crossroads.
- T10 review fixes (wf-t10): buildings are made ready on the `town-build` workers (`BuildingMeshes.prepare` /
  `FilamentModels.prepare`: art decoded, geometry and vertices packed, pictures read and packed, the windows' glass
  filled), the engine's thread only `finish`es them (buffers, textures, materials, renderables): the slowest building on
  the engine's thread went from 156 ms to about 20 ms on the build server; the windows' night light runs in a 2 ms budget a
  frame. Frames come every display refresh only while something moves or the town is still building
  (`TownPlay.renderBusy`), otherwise at the stage's 30 Hz clock; the stage does none of the crossroads' work under the town
  (`Frame3D.fillTown`). Crash guard: a `townBuilding` mark from the scene's start until the whole town is built (a death
  in it starts the next launch on the Filament crossroads); the "starting" mark counts frames only after the first view; a
  town exception falls back to the Filament crossroads, not the classic. The engine's arenas are sized for the town
  (`TownEngine.config`). The player is frozen and the dialogue's writing ahead paused while the first view is built; the
  player's world position is kept in prefs (back there at the next start); the source pictures are let go after upload
  (weak caches); memory (graphics, native, total PSS) goes to breadcrumbs and the snapshot at first view and when the
  whole town is built. Preview: `-Dshots=cold` builds a fresh scene through TownRun from the spawn and reports time to first
  view and frame work (cold_first_view.png).
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
- Review of wf-town2 (T6, yards, T7): parallel make_buildings re-checked byte-identical to `--jobs=1` on gamma (849
  files) and to the shipped eta buildings (bar the lit/shade maps, which another tool makes). An independent top-down
  check of gamma's yards (every item against every building, carriageway, water, plaza, landmark, other parcels and its
  own layer) found no overlaps. Fixed: 14 driveways dropped their kerb where one of the row's street lamps stands; a
  driveway now slides out along its side strip clear of the row's furniture (14 units), or gives that side up (one did:
  pads 41 -> 40). TownWalk's footprints (a rigid box on the front's middle, square to the segment there) are the same
  rule the yards use for `house`; T8 must stand the buildings the same way so what is drawn is what blocks the walk.
  The Kotlin fixture desktop/src/test/resources/town-gamma.json is the graph without yards (town-graph.test.ts keeps
  it); T8's tests will want a fixture with yards.

## T4 and T8: the whole town in Filament (branch wf-town3d)

- `domain/TownYards.kt`: graph.yards and graph.lanes parsed (`TownYards`, `TownYard`, `TownYardItem`, `TownLane`), and
  `TownPlace`: where a building of a row stands (the walk's rigid box on its front's middle: origin on the building line
  at the middle, local z into the lot, local -x the art's left-to-right seen from the street, a proper rotation, so no
  mirrored art). Tested (desktop TownPlaceTest): every building's box is TownWalk's footprint, the frame's determinant
  is +1, and the yards' `house` rect is the same box (on build-town/gamma when it is there).
- `scene3d/BuildingMeshes.kt` (T4): the three kinds of building (box with sign, pitched roof, gables and billboard;
  mobile home; FilamentModels' villas, blocks and landmarks) built once in that local frame, night windows included,
  placed by one transform. A lot with a yard drops the house's and the model's own front lawn and fence (not the
  landmarks'). FilamentStreet (the eta crossroads) is untouched and still builds in world space.
- `scene3d/TownGround.kt`: grass over every tile that is not water (one seamless 2048-texel picture from the pack's grass
  tiles: their tufts and stones scattered over a base broken into five shades by wrapped noise, dithered) and fields on
  to the horizon; roads as strips per segment (sidewalk and asphalt tiles, kerbs, the centre's dashes) cut back 89 at
  each end; a patch at every node and corner painted by which sides a road leaves (cross, tee, bend, dead end's head;
  rounded kerbs); zebras over every arm of a crossing or tee; the plaza paved; the river 9 units down with stone banks;
  bridges' decks' edges and parapets; the edge's trees and bushes as crossed cards, flower bushes as the props' planter,
  flowers flat; a 3D fountain; the rows' lamps (3D, lanterns lit at night), benches and trees; the street props by
  PropRules along every row (on a row whose +x runs against the art's right, along and turn are flipped so signs keep
  their handedness).
- `scene3d/TownYardHook.kt`: the yard kit's hook, `FilamentTown.yardBuilder: YardBuilder` (`build(town, yards, sink)`,
  the sink takes meshes and street props). `PlaceholderYards` lays items as flat colours and objects as boxes meanwhile.
- `scene3d/FilamentTown.kt` (T8): `buildGround()` once, then `buildNearest(x, z, budgetMs)` each frame (nearest the
  player first) until `pending` is 0; `frame(pose, hour)` lights the hour, culls each building by a sphere against the
  view's four sides (its shadow twin kept within 300 of the view), and in the street view fades out (screen door) what
  stands nearer than 110 short of the building line looked at (the row across the road). `TownFrame.street(walk, w, h)`
  is the true camera from TownWalk's camera (CameraRig's eye and back, backing off through a turn, the building line's
  foot at 82% of the screen); `TownFrame.aerial(...)` a free camera. `stats` counts what was drawn.
- Preview: `gradle :desktop:preview -Dmode=filament-town` (-Dtown=DIR, -DassetsRoot=DIR, -Dshots=a,b, -Dw=) writes
  build/preview/filament-town/: aerial_13h, aerial_dusk, aerial_top, civic_13h/dusk, street_{spawn,main,heights,
  woodside,turn}_{13h,night}, behind_{spawn,main,heights,woodside}, and town.txt (build times, draws per view). On the
  4-core build server (software GL, not the S25): ground and everything static 1.3-1.9 s; 98 buildings in ~62 batches of
  4 ms, 1.4 s in all (the first box ~200 ms: first use, its art's decoding included); whole town in view 252 renderables,
  ~1020 draws; street views 180-250 renderables, 380-1000 draws.
- Not yet: the near side's lamps, trees and props are not cut; street signs crowd some corners (one per row meeting there);
  forest trees are cards of the street's scale (1.25-1.6x); the landmarks' grounds and corner lots' second side are not
  drawn; Stage3D/GameActivity were wired in T10 (below).

### The yard kit in the town (wf-town3d)

- `FilamentTown.yardBuilder` is now `YardKitBuilder` (scene3d/YardLayer.kt): every lot's yard and every lane's and verge's
  items are baked by `YardKit.bake(items, perKind = false)` a lot (or a lane) at a time: one batch for what throws a
  shadow, one for what does not, one per ground tile (at most 12 a lot; 94 patches, 712 batches for gamma's 92 yards and
  2 lanes with items). `-DtownYards=placeholder` brings back the flat placeholders to check the layout alone.
- The hook's sink takes a `YardPatch` with each mesh (the lot's ground circle and its building's sprite) and returns the
  batch's material instance; `sink.animate { seconds -> }` steps the water's tile (`YardLayer.shimmer`), run each frame by
  `frame(pose, hour, seconds)`. The town draws each patch in its own PropLayer: culled by its circle (its shadow twins
  within 150 of the view), and in the street view faded with its own house (the same cut, from the building's middle),
  so the near side's front hedges, fences and lawns go with the row across the road; a cut lot throws no shadow (a
  fence's shadow without its fence read wrong). Tested (TownYardsKitTest, in crossroadsTest): a patch for every lot and
  lane, at most 14 batches each, every vertex inside its circle, every lot's building in the town.
- Draws and triangles (4-core server, software GL, not the S25): street views 245-1130 draws, 230k-1.2M triangles;
  whole-town aerial 1735 draws, 1.7M triangles (the yards are 1.59M triangles in all: side fences 429k, greenhouses 278k,
  flower beds 189k, post-and-rail 168k lead). The per-lot atlases hold 29M texels in all (pictures repeated lot by lot).
- Preview: `yard_{pool,woodside,garden}_{street_13h,street_night,above,top,back,back_dusk}` (the Heights pool b69, a
  Woodside lot b45, a garden b79), and town.txt lists the yards' triangles by kind.
- Left: near-pavement props and the edge's tree cards are still not cut; the mobile homes show their front picture from
  behind too; the side fences, greenhouses and flower beds are heavy in triangles; the atlases could be shared across lots.

### Review fixes (wf-town3d, after the review at a98bbba)

- Buildings: a box shop's back wall carries its side picture on at its own texel size (two copies joined at their
  best-matching column, so no window is cut), not stretched 1.87x; a pitched roof with stone end posts stands its gables
  inside the posts (the bank in eta too); the buildings', models' and props' pictures are sampled with 16x anisotropy
  (side walls no longer smear); a mobile home's back is plain siding (each row the front's commonest colour) with two
  thirds of its windows, not its front picture.
- Pictures are shared by content (StreetTextures: a picture never updated is one texture however many batches wear it;
  every layer of the town shares the town's): 651 textures for 2866 asks. The per-lot yard atlases are still per lot
  (67.8M texels in all): one global yard atlas is left.
- Culling: renderables carry their boxes and Filament culls them (`culling(true)`; the look's depth now reaches 1 at the
  camera's own near, so Filament's camera and the drawn one agree). The street view has a range (`TownPose.range`, the
  haze's start + 3600): buildings, yards and chunks fade out (screen door) over the last 500 and are not drawn past it;
  the haze there is total (max opacity 1). Free cameras have no range. The haze starts 1400 past the building line
  looked at (was 700) and is thinner (.00045), so the next blocks read solid.
- Chunks: the rows' furniture and street props in stretches of 800 along each row side, the edge's greenery in 2000
  squares, each culled by its sphere and range, and in the street view cut with its row (judged at the row's building
  line, as its buildings are): the near pavement's lamps, mailboxes and planters go with the row across the road.
- Trees: far, a crossed card plus a level crown card (the sprite's own leaves on a disc: no more plus signs from above);
  near (a chunk within 1100 of the eye), the yard kit's 3D trees (round broadleaves for the round sprites, poplars for the
  tall ones), made the first time a chunk comes near (in `buildNearest`'s spare budget, or `prepare(pose)`). The forest's
  trees are shaken off the map's grid, and past its straight outer edge trees thin out into the fields in clumps.
- Night: the 12 lanterns nearest the point looked at are Filament point lights (warm, reach 260; the farthest four dim
  so a switch does not pop); the lantern glass glows warm, not blown white.
- Building off the engine's thread: `startGround()` makes the ground, greenery, furniture, props and yards on workers
  (every core but one; the yards baked a few lots per core at a time) as packed batches (`PropLayer.Packing`,
  `StreetTextures.prepare`); `uploadGround(budgetMs)` uploads them a frame at a time (`buildGround()` still does both,
  waiting). The buildings' art is decoded on the workers, nearest first, before `buildNearest` builds them. Measured on
  the 4-core build server (software GL, not the S25): workers 2.4 s, the engine's thread 138 ms of uploads in 4 ms steps
  (longest 25 ms); buildings 934 ms in 53 batches, the school (b93, a FilamentModels model) still 114 ms in one go: the
  models' build needs a prepare/upload split of its own (left for after wf-fbuild's last commits are merged).
- Street name signs: one per crossing (signs within 260 of one kept are left out). Landmarks get a paved path from the
  pavement to the door and an apron along the front (their side and back grounds are still lawn).
- Preview: free cameras back off out of any building (yard_woodside_back was inside one); new shots landmark_b92/b96/b97
  and heights_backyards_above; town.txt reports the ground's worker/upload split, textures, chunks and lamps lit.
- Left from the review: the yards' triangles (fences, greenhouses, flower beds: LODs, plank fences as alpha quads), the
  per-MI `look` (a material change shared with eta), the vertex format, the river's outline and banks, bridges'
  structure, lawn edges against the town grass, shop back lots (gravel, no gate), side windows' glass mask, the school's
  back seams, repeated shop art, the yard kit's minor issues, people for scale. Draws went up in the aerials (3052, was
  1735: chunks are per kind) while street views draw 834-1448 with fewer triangles (0.6-0.96M, was 0.65-1.7M).

## Open world: walking and collision (Task A, as built on wf-open)

The owner's decision (2026-11-19): the camera is the player's (one finger orbits, pinch zooms), it never turns on its own,
and the player walks anywhere. The walk and what blocks it are engine-agnostic (shared/src/main):

- `domain/TownCollision.kt`: the town's solid shapes, built once (`TownCollision.build(town, yards, models, cinema)` or
  `fromStreetJson(streetJson, modelsJson)`): oriented boxes and circles in flat arrays under a 64-unit grid. What blocks:
  box buildings and mobile homes as their slot's rigid box (TownWalk's footprint, the yards' `house`); modelled buildings
  (villas, flats, landmarks) as their parts standing on the ground below head height (44 px) and above a step or porch deck
  (14 px), placed exactly as BuildingMeshes/FilamentModels place them (a porch's posts, the fire station's front block);
  the cinema (`CinemaSiteShape`, CinemaKit's plot numbers: the main block, the multiplex's atrium, canopy posts, drum and
  stair tower, the site's walls or car park rails); yard fences (front, side, back, post and rail), hedges and low walls,
  with gates and side gates as the gaps the export leaves; yard objects as their rectangles (sheds, garages, pools, play
  sets, tables, raised beds...), trees by their trunks, bushes as small circles, carports and gazebos by their posts,
  washing lines by their posts; the rows' lamps, benches and trees where TownGround stands them; the street props by
  PropRules (one name sign a crossing); the forest's trees (trunks, shaken off the grid as drawn) and bushes; the plaza's
  fountain, benches and planters; the river less the bridges, and the bridges' parapets; a wall 1200 past the town's
  data. Walkable: streets, pavements, lawns, paths, drives, patios, decks, flower beds, sandpits, the plaza, lanes, grass.
  Queries allocate nothing: `blocked(x, z, r)`, `clearance`, `nearest`, `resolve` (push out), `move(x, z, dx, dz, r, out)`
  (half-radius sub-steps, push-out sliding, corner fallback per axis; a walker that starts clear never ends inside
  anything), `nearestClear` (never spawn inside).
- `domain/TownDoors.kt`: every building's front door (on its front wall at the yard's front path, else the front's middle,
  the cinema's at its entrance), walked out of the collision to where a walker stands clear; `at(x, z, hx, hz)` is the door
  within 44 of the player, on its outside and roughly faced (within ~70 degrees; any facing within 14); the people to talk
  to stand where TownPlay stands them, and `nearestKeeper(x, z, keepers, range)` finds the nearest within range from any
  direction.
- `shim/OpenWalk.kt` (a `TownWalkView`): the player's world point and facing are the truth. Each frame the camera sets
  `cameraYaw` (0 north, clockwise, as `TownCamera.yaw`) and the app calls `step(dt)` (the stick's `GameState.moveX` right,
  `moveLane` down): the push turned from the camera's facing into the world, eased by `Locomotion` (MoveTuning's walk 72
  and run 150, now the same in every direction), moved with `TownCollision.move` (radius 6), the velocity cut to the slide,
  the facing turned towards the walk (0.06 s), the legs advanced by what was really walked (GameState gait, footfalls,
  walking, running, travel; `GameState.facing` 0 and `dir` the side the camera sees, for the figure's view). Derived and
  kept: `row`, `x`, `lane` (the nearest building line, a line the player stands behind counting 1.6x, 12 units of
  hysteresis; GameState.x/lane follow), `door` ("Go inside"), `front` (the door's building), `yard` (the parcel stood in),
  `streetName()`, `hoodName()`, `nearestKeeper(keepers)`, `fromKeeper(sprite)`, `liveRows()` (rows within 1400, kept
  until the player moves 120), `camera` (looking along `cameraYaw` at the player), `transit` always null. `placeAt`,
  `spawn`, `takeOver(streetWalk)` stand the player (on the nearest clear spot); the street view's TownWalk takes over with
  its own `placeAt(wx, wz)`.
- Tests (desktop OpenWalkTest, on the shipped gamma assets): 7803 shapes (177 building pieces, 460 fences, 54 hedges, 35
  low walls, 1254 yard objects, 4625 trees, 1085 props, 105 water pieces, 8 parapets); every gate open and every fence
  solid; a 6-unit flood fill from the spawn reaches 98.8% of the clear ground: every row's pavement, every bridge, every
  door and person to talk to, the cinema's car park, and 67 of 92 back yards (every one with a side gate; the 25 shut are
  the shops' fenced back lots b0-b26 and b49, b50, b80, which have no gate in the data); a scripted walker (the stick
  turned through a camera swinging round all the while) walks to 11 back yards through their gates, a back lane, both
  sides of all four bridges, the plaza, the cinema's car park, the forest and back to the spawn (~12 minutes of play,
  ~100k units) and is never inside anything, never jumps; pushed at 45 degrees into a fence the walker slides along it
  at ~94% of the push's along-the-fence speed (149 units in 1.5 s from standing), 6.0 from it; doors found from in front at three angles (277 of 277), not when facing away or from behind; the
  people to talk to from 8 sides. Build server JVM (not the S25): build 160-410 ms, `move` 100-280 ns, `blocked` 17-45 ns,
  a whole frame's `step` 1.1-4 us, allocating nothing. Pictures: desktop/build/open-walk/*.png (top-down map, reached
  ground, the walker's trail).
- Not yet: parked cars, dumpsters and bins on the cinema's site; the landmarks' own front fences (models.json `fence`);
  the trees thinned out past the forest's edge (TownGround's feather); people and cars as moving obstacles; the shops'
  back lots have no gate.

## The open world's camera and drawing (branch wf-open, task B)

The owner's decision (CLAUDE.md): the camera is the player's. `scene/OrbitCamera.kt` (shared, no platform) is its state:
it orbits the player's chest (feet + `EYE` 30 up) at `yaw` (0 north, clockwise, as the walk's heading), `pitch` 10-60
degrees down and `dist` 140-1400; only the player's input moves it (`drag`, `pinch`, `recentre`), eased over ~70 ms
(`step`), never turned by itself; it follows the player's position exactly. `worldMove(moveX, moveLane)` turns the
stick's push into a ground direction relative to the camera (the open walk takes `yawRad` as its `cameraYaw`). `open`
is the AI panel's "View: Open world / Street" (prefs `renderer/view`, open by default); the street view is untouched.

- Drawing (`TownRun.orbit` -> `TownFrame.orbit`): a true perspective, 50 degrees tall, centred lens. Buildings between
  the camera and the player: the camera comes in in front of the first one if that leaves it at least 40% of its
  distance (and 130) off; otherwise it stays and the buildings between fade to a screen door (`FilamentTown.occlude`,
  `fadeBetween`, eased .12 a frame to 30%), unless the camera itself would stand inside one (then it comes in to that
  wall). Pulled in at once, eased back out over .35 s. A scene starting 300+ from where the camera last followed puts it
  behind the player.
- Draw distance and LOD: the street view's range and haze, measured from the eye (radial): everything past
  `dist + 1400 + 3600` is not drawn, the haze total there. Each yard has a far version (`YardKit.bake(far = true)`:
  small things left out, fences as one slab of their own size in their average colour, meadows without tufts) drawn
  past `dist + 1300` from the eye in place of the near one (`YardPatch.far`, `-DtownLod=0` bakes none). The yard kit's
  one-colour pictures are now one quad however long the face (`PixImg.uniform` in `tq`): the yards went from 1.59M to
  568k triangles with the same pixels (street views differ in 3-43 pixels of 2.5M: shadow-map edges); far versions 237k.
- Light and sky: in the open world the sun stands still in the world (`OrbitCamera.sunAzimuth`: east at six, south at
  noon, west at six; the moon from six in the evening), as high as the street's key light. The stage's sky turns with the
  camera (`backdrop.yaw = -lens * yaw`), its horizon where the camera puts it (`OrbitCamera.horizonY`), the sun or moon
  drawn where the light comes from (`OrbitCamera.orbAt`; off the screen when behind the camera).
- People: cards upright and square to the camera (as before); each shows the front, back or side of its sheet by the
  angle between the way it faces and the camera's look, the player's own too (the street view's up/down facing override
  is the street view's).
- Input (app `OrbitGestures.kt`): chained outside the stick's handler on its layer, so the stick takes its touches first
  and buttons theirs; one finger turns (200 degrees a screen width, 90 of pitch a screen height), two pinch, a double
  tap or the "Behind me" chip recentres. Screenshots and crash snapshots carry `view` and `orbit` (yaw, pitch, distance,
  goal, pulled, the point followed).
- Preview: `gradle :desktop:preview -Dmode=filament-open` (`-Dshots=`, `-Dw=`, `-DtownLod=0`) renders
  build/preview/filament-open/: Main Street (behind, fronts, three-quarter, high, close, through the block, dusk west),
  the civic avenue, a Heights backyard (b69: the back, along the backs, above, night), on a bridge, the cinema, Woodside;
  open.txt gives each view's draws and triangles three ways (a free camera as before: no draw distance, all yards near;
  the draw distance alone; as drawn). The player is placed by a position and a heading (`TownWalk.placeAt`).
- Measured on the 4-core build server, software GL (not the S25): open views draw 460-1610 draws and 120k-256k
  triangles as drawn (the same views free as before: 480-2850 draws, 140k-620k triangles; the Heights backyard 595k ->
  229k). townMemoryCheck: held 194 MB at the peak of 256 (the far yards are baked too).
- Left: the occluder fade is a screen door and reads busy when a whole building fades right in front of the camera; the
  far version's slab fences are solid where a picket fence is see-through; bridges still look like a road over water from
  above; no LOD yet for buildings, chunks' props or the edge's trees beyond the range; the yards' far bake doubles the
  yards' worker time on the build server (1.7 s -> ~5 s with other builds running: measure on the S25).
