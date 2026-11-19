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

## The open world in the game loop (branch wf-open, integration)

- `TownSession` builds the town's `TownCollision` on the load thread (crumb `town collision: N shapes, X ms`) and
  `TownPlay.start(town, collision, yards)` makes both walks at the spawn: `TownPlay.open` (an `OpenWalk`) and
  `TownPlay.street` (the `TownWalk` of the street view). `TownPlay.walk` (a `TownWalkView`) is the one played: the open
  walk while `OrbitCamera.open` (the AI panel's "View: Open world", the default), else the street walk. A switch hands the
  player over at the next `TownPlay.step`, where they stand and the way they face (crumb `view street` / `view open world`).
  Without a collision map the street view is played.
- The open world's frame (`TownPlay.stepOpen`): the walk steps against the collision with the stick relative to
  `OpenWalk.cameraYaw`, which only `TownRun.orbit` sets from the player's orbit camera. No corners, turns or crossings
  (`GameState.atCorner` stays false). `GameState.nearest` is the building on the derived row nearest the player, so the
  dialogue's writing order (`DialogueEngine.setPlayerRow(row, nearest, liveRows)`) keeps working from the derived row and
  `OpenWalk.liveRows()` (rows within 1400, nearest first). Breadcrumbs: row changes (with the camera's state) and the
  area (into / out of a yard).
- HUD and talking: the street name and neighbourhood from `OpenWalk.streetName()/hoodName()`; "Talk to" offers
  `TownPlay.talkSprite()` (the nearest person within `TalkRange.distance` from any direction, kept until past
  `TalkRange.leaveDistance` unless someone clearly nearer comes, else the door's building); "Go inside" the door the
  player stands at, facing it (`OpenWalk.door`). The mic's listener and a voice conversation's end use
  `TownPlay.nearestKeeper()` / `fromPlayer()`, which go by world distance in the open world. Speech bubbles stand over
  heads projected through the camera last drawn (the orbit camera's), as before.
- The player's figure faces `OpenWalk.hx/hz` with no forced sheet facing, so the camera shows their front, back or side.
- The screenshot JSON's town block adds `walk` (open or street), `talkTo` and an `open` block (door, yard, speed,
  blocked share, camera yaw, heading); the orbit camera's state was already in `orbit`.
- `gradle :desktop:preview -Dmode=filament-open` (or `-Dshots=walk`) also plays a scripted 30 s walk through
  `TownPlay.step` at 60 Hz: the stick pushed relative to the camera by an autopilot that steers round obstacles (a path
  over the collision grid), the camera turned and zoomed only by scripted drags and pinches. walk_XXs.png every 5 s and
  walk.txt (the path, the breadcrumbs, frames the camera turned without input, frames inside anything).

## Open world: review fixes (branch wf-open, after the review at 6236343)

- The camera never moves by itself, zoom included: no pull-in for buildings in the way (the old pull snapped in and eased
  out as buildings crossed the line while walking). Whatever stands between it and the player is cut away instead:
  `FilamentTown.occlude` casts a cone of five lines (the player's chest, feet, head and 12 either side to the eye) against
  every building's box and the yards' tall things (sheds, garages, greenhouses, carports, gazebos, play sets, swings,
  back hedges, trees: `TownFrame`'s `tallHeight`, kept as world boxes per lot); anything within 60 of the eye is cut too
  (no wall or sign filling the screen at the near plane). Cut means faded to nothing over 0.2 s by the clock (not a
  screen door at 30%, and the same at 60 and 120 Hz); a cut building keeps its shadow. A lot whose tall thing is in the
  way has its batches that throw shadows (fences, sheds, trees) cut with their shadows, its lawn and paths kept
  (`PropLayer.update(castFade)`).
  A player standing under a tree's crown or a carport counts as hidden by it. `OrbitCamera.pullIn` (off) keeps the old
  idea as an option: only when the eye itself would stand inside a building, eased in (0.15 s) and out (0.5 s).
  `occlude` allocates nothing.
- Draw distance by the player, not the zoom: the open world draws 2600 past the player (`OPEN_REACH`; the camera's far
  plane just past it, things fading out over the last 500, so nothing is cut off hard), the haze from 1200 past the
  player, the sun's shadows only 1400 past the player (Filament's `shadowFar`, set as the zoom changes), so the cascade at
  the player stays fine at every zoom. The yards' far versions start 600 past the haze's start with 80 of hysteresis.
  The zoom's far limit is 900 (was 1400) until wide views are measured on the S25.
  Build server, software GL (estimates, not S25 numbers): the widest views now 680-850 draws, 126k-220k triangles,
  260-335 shadow casters (were up to 2118 draws, 363k triangles, 812 casters at 1400); street-level views 230-850 draws.
- Gates are drawn open (each leaf swung 90 degrees into the yard on its hinge post: `MeshBuilder.turnSince`), their posts
  solid where the gap leaves a walker room (`TownCollision.gate`); the leaves lie back along the gap's edges and are not
  solid (a solid leaf made wedges a walker got caught in). The walker's radius is 5 (was 6).
- Every back yard can be walked into: the export (tools/town-yards.ts) puts a gate in the middle of the back fence of
  every lot with no side way in (the 22 shops' service yards, houses filling their lot's width): 26 back gates. The test
  now asks for 80% of each back yard's free ground reached: 92 of 92. The scripted walker aims only at path points in
  plain sight (it cut corners across thin fences).
- The world's end is a belt of woods all round (TownGround's feather: dense from 820 to 1400 past the forest's edge;
  the walk stops at 1200), not an invisible wall in open grass.
- The sky: the yaw wraps at 180 degrees (camera and goal together) and the sky slides a whole number of its patterns per
  full turn (`OrbitCamera.skyPx`: a multiple of three screen widths), so there is no jump.
- Input: in the open world the stick takes only the lower-left corner (a third of the width, at most 300 dp, the lower
  half), leaving the rest to the camera; "Behind me" sits under the street's name at the top left (not where the right
  thumb drags); the AI panel's "Drag: Turns the camera / Moves the world" sets which way a drag turns
  (`OrbitCamera.grabWorld`, prefs `renderer/grabWorld`). Back from the street view, the camera starts behind the player
  (`OrbitCamera.behindNext`).
- "Talk to" no longer falls back to a door whose person stands further than a conversation lasts (90).
- Preview: walk renders have the stage's sky; check views `chk_*` (gates, people from four sides, the widest views, low
  and close in a back yard, the world's edges) and `backyards_low_13h`, `cinema_quarter_13h`.
- Left: (cutting a lot's tall things together: fixed on wf-feedback, each tall thing is cut alone); shop backs are still bare brick and gravel; the river's banks are still stepped; no fling and no game-pad
  camera stick; nothing of this measured on the S25.

## The stores in the open world (branch wf-storeint)

- The stores (docs/STORES.md, "In the town") are drawn in their slots by `TownStore` (scene3d/TownStores.kt) as the
  cinema is by `TownCinema`, their main blocks cut away like buildings when in the camera's way, their parked cars a
  layer rebuilt on the hour.
- `TownCollision` leaves a store's plot open when built and takes what the store as built stands there later: an overlay
  of shapes by key (`setExtra`, `extraSize`; the stores' sites and their cars) and of doors (`setExtraDoors`,
  `extraDoors`, read by `TownDoors.at` / `of`), rebuilt and swapped in whole so the walk's queries need no lock and
  allocate nothing. The shapes are read off the store's own triangles (`StoreSolids`), so what blocks is what is drawn.
- gamma re-exported with the stores: 52 nodes, 112 rows, 105 buildings, 11 landmarks, the spawn on the civic avenue
  14L by the hotel; OpenWalkTest: 11551 static shapes (12 parapets: six bridges), the flood from the spawn reaches 100%
  of the walkable ground, 94 of 94 back yards, 283 of 283 door approaches. The street view's crossing camera now ends
  on the facing row's own look (it snapped where the main street's steps across the road are staggered).
- The yards' patches are 20 units wider where a lot has a gate (the open leaves swing past the lot's items).

## Open world: the owner's screenshots of 0.1.338 (branch wf-feedback)

- No painted city in free roam: the stage's `Backdrop.city` is off in the open world (HabitsStage: `!openSky`), so the far
  skyline's rows, the middle distance and its car are not drawn there; the sky, clouds, stars, sun or moon, weather and
  the horizon's glow (taller there, 150 dp) stay. The horizon is the town's own: the world's woods in the haze (real far
  LODs of other towns would go here later). The street view is unchanged. Preview: `-Dsky=stage` lays the stage's own
  sky under the open world's renders (FilamentOpenPreview `stageSky`).
- The cutaway cuts only what is in the way: every yard's tall thing (shed, garage, greenhouse, carport, gazebo, play set,
  swing, back hedge, tree) is a yard patch of its own (`YardPatch.tallId(lot, index)`, `follows` its lot: drawn while the
  lot is near, left to the lot's far version past it); `occlude` cuts that thing alone, never its lot's fences or other
  things. A cone line that starts inside a thing counts only for the chest's and head's lines and only under a crown or
  roof (trees, carports, gazebos): a hedge or shed beside or behind the player is no longer cut (the 005359 bug: the side
  lines started inside the back hedge and the whole back yard went). Cost (build server): +30-65 draws in yard views.
- Trees (`TreeKit`, `TreeCells`): oak, maple, linden, copper beech, birch, fruit (fruit or blossom), poplar, spruce
  (star-shaped drooping tiers) and pine, each grown from a seed (lumpy lobes, limbs and twigs into the crown, a flared
  trunk with roots), and round, tall and topiary bushes, in the pack's pixel pictures (one shared 512 atlas: any number
  merge into one draw). Two levels: full detail within `TownFrame.LOD_DETAIL` (800) of the eye, a simple one to
  `LOD_NEAR` (1100), the cards beyond. The forest's, the edge's, the rows' trees and the bushes stand in cells of 420
  (`TreeCells`, per chunk: one merged mesh per cell and level; the detail packed on a worker as a cell comes within
  reach, two cells uploaded a frame, let go 700 past it). Forest sprites map to species (round: maples, lindens, birches,
  oaks; tall: spruces, pines, poplars; big: oaks). The yards' trees and fruit trees are the kit's, full detail near and
  the simple level in the far yards. No seasons in Filament yet (summer colours).
  Build server, software GL (not the S25), on the town as exported with the stores: in the woods (fb_trees) 113 draws,
  297k triangles (were 117 and 362k with the old trees; zoomed in 377k, was 507k: the simple level is lighter than the
  old near trees, the detail only round the camera); a detailed tree is
  500-1160 triangles (the old 3D tree about 330), its simple level 80-170 (desktop TreeKitTest prints each yard look).
  townMemoryCheck held 184 MB of 256.

## Open world: the bicycle (branch wf-bike)

The owner (2026-11-19): "We need a basic vehicle like a bicycle, because walking around is slow." The player's bike, in
the open world only (the street view dismounts):

- Getting on and off: the HUD's round bike button just right of the stick's corner (one tap on or off, a long press
  rings the bell). `TownPlay.toggleBike()` / `OpenWalk.mount()`: the bike needs its own clear circle (radius 7) and a clear
  front wheel within 14 of where the player stands (else "no room for the bike here"), and rolls on at the walking speed.
  It is left at once by "Go inside", a door's fade, any conversation (by button or by voice) and the view switch
  (`TownPlay.dismount(why)`). Breadcrumbs: "bike on (style) at", "bike off (why)", "bike: no room at".
- Riding (`OpenWalk.stepRide`, tuning in `shim/Bike.kt` `RideTuning`, all plain vars): the stick, relative to the camera as
  walking, is where the rider wants to go and how fast. Momentum: pedalling up to the push's share of the cruise (290
  units/s, ~13.7 m/s, ~4x the walk; 90% of it in ~1 s from standing), the sprint (400) after holding a full push 0.8 s,
  coasting with the stick let go (rolling 18 + drag 0.00035 v^2: 400 -> 335 in a second), braking (420) when the push
  points more than 115 degrees from where the bike goes, then a tight turn round at <= 70. The heading turns towards the
  push no faster than `RideTuning.maxRate(v)`: the grip (520 units/s^2 sideways) over the speed, at most 3.2 rad/s, a
  2.4 rad/s pivot below 25 (radius 36 units at 145 units/s, 218 at 372; a turn round from 387 takes 1.1 s). Slower into
  sharp turns. Surfaces (`RideSurface`): grass 55% of the top speed and 60% of the pedalling with 5x the rolling loss,
  gravel 80%, boards 95%.
- Against the town: the bike's circle (radius 7: it fits the 28-wide gates, 21.8 between the posts) and a probe for the
  front wheel (radius 5, 17 ahead): a glancing hit slides the front along what it meets and the bike follows it, losing
  what was stopped and 1.2/s more while scraping, swinging along the slide; head on it stops with the tyre at the wall. A
  turn never puts the front wheel into something; nearly still with the front at a wall the rider swings the back round
  the front wheel, rolls it back a little, or (stick pulled back) walks the bike backwards (40 units/s). Held fast with
  the stick pushed for 0.6 s (a slot narrower than the bike's length), the rider lifts the front wheel round (its probe off
  until it rides free). Fences, hedges and walls are solid; gates are open. The front wheel may graze 0.5 (a few cm).
- Drawn (`scene3d/TownBike.kt`, `BikeKit`): real geometry in our style, one entity a part with a shadow twin, through the
  street's lit material: two wheels (a knurled tread band and a 32-texel spoked disc each side, clear between the spokes,
  hub and rim in chrome, whitewalls on the cruiser) that turn with the distance rolled; the frame's tubes (a lit stripe on
  top, a darker underside), seat post and saddle, chain ring and chain; the fork and bar turned about the head tube's
  line by the turn's radius; the cranks turning with the pedalling only (they stop coasting) and the pedals kept level
  with orange reflectors; a lamp on the fork that glows at night. The whole bike leans into turns (the real lean,
  atan(v x rate / g), eased 0.12 s, at most 34 degrees). One style an era, by the town's year (`BikeStyle.of`): a red and
  cream 1950s cruiser (balloon whitewalls, swept-back bars, mudguards, chain guard, wicker basket), an orange 1970s
  ten-speed (thin tyres, taped drop bars, a bottle), a blue 1980s BMX (20" wheels, high-rise bar with its crossbar pad,
  chrome fork), a sage modern hybrid (flat bar, mudguards, rear rack). The pack has no bicycles (10_Vehicles: cars,
  buses, boats, carts; City props none), so the pictures are painted in its palette. The bike is drawn 1.45x real size
  (`BikeState.SCALE`): our people are the pack's big-headed figures and a true-size bike vanished under them.
- The rider (`TownPeople.ride`): the player's card stands in the bike's leaned frame (it leans with it) across the
  camera's right. From the side, the sheet's seated frames (row 4: right-facing 0-5, left 6-11) on the saddle; from the
  front or back, the walk's frames standing on the pedals, one walk cycle a crank turn (the idle frames coasting). The app
  now decodes the sheets' rows down to y 320 (was 192) for the seated row.
- The camera stands 30% further back on the bike (`OrbitCamera.RIDE_BACK`, eased over 0.8 s: a zoom, never a turn) and
  follows the position exactly, as walking.
- Sound (app `audio/BikeSound.kt`, mixed live by SoundScape; nothing recorded): no footsteps while riding; the tyres' hiss
  growing brighter and louder with the speed on the road, a dull swish on grass (and on snow), crackles on gravel, a
  knock on boards, a faint thump each wheel turn; coasting, the freewheel's ticks (18 a wheel turn: a tick-tick slowing
  to a stop); the bell (two strikes, 2.1 and 5.3 kHz partials, 1.6 s) on a long press. The dialogue's writing ahead pauses
  while riding as while walking.
- Tests: desktop `BikeRideTest` (gamma): a scripted rider (the stick through a camera swinging round all the while, an
  autopilot over a bike-sized grid that brakes for bends and backs out when stuck, as a player would) rides to 22 goals
  (back yards through their gates, both ends of four bridges, the plaza, the cinema's car park, back to the spawn):
  ~73k units in 304 s, mean 240 units/s, vs 467 s for a runner on the same legs (0.65x the time); never inside anything,
  the front wheel never in anything but while lifted (69 of ~18300 frames), the turning limit never broken; the cruise,
  sprint, coast and turn round; the radius growing with speed; a fence slid along at 30 degrees in (192 -> 95 units/s,
  9.3 from it) and stopped head on 22 off it; a lawn at most 71 units/s after 0.5 s; on and off (no footsteps, no room
  between things a walker fits); a ride step 1.4-3 us allocating nothing (build server JVM, not the S25). Run with
  `gradle :desktop:test -DtestOnly=OpenWalkTest,BikeRideTest`.
- Preview: `gradle :desktop:preview -Dmode=filament-open -Dshots=bike` renders each era's bike from the side, three-quarter,
  behind, in front and above, leaning both ways, at night (lamp), from a street-wide view, and an 8 s ride through
  `TownPlay.step` at 60 Hz rendered each second (bike_ride_NNs).
- Not yet / to check on the S25: the feel (all the numbers are tunable live), heat with the town streaming past at
  ~4x the walking speed (the build's 4 ms a frame budget and the live rows may need to look further ahead), parked bikes
  at home, other people on bikes, a rider's own pedalling legs from the side (the sheets have no cycling frames).

## Open world: the owner's screenshots of 0.1.367 (branch wf-feedback2)

Desktop renders (`gradle :desktop:preview -Dmode=filament-open -Dshots=fb2_`: the five screenshots' spots and cameras,
the player placed where the JSON had them, 17:36 in summer), not S25 captures.

- Townsfolk (015754, "clones"): the townspeople wear their own sheets, baked from the Interiors pack's Character
  Generator by `tools/make_townsfolk.py` (python3 -I tools/make_townsfolk.py <Character_Generator dir>
  app/src/main/assets/characters/folk 120; the raw pack stays out of the repo): body and skin tone (5 natural ones),
  eyes, outfit and its colour, hairstyle and colour (grey for elders), beards and moustaches, glasses, beanies and caps,
  laid into 768 x 128 sheets of the two rows the town draws (idle, walk), 120 of them (84 adults, 24 elders, 12 kids;
  half women; 18 in clothes too modern for a town before 1990), with folk.json giving each one's sex, age band and
  `modern`. `TownFolk.pick(seed, female, age, year)` picks a person's sheet: the people to talk to by their own sex
  and age (`TownPlay.keeperTraits`, set by the app from dialogue.json beside `keepers`), passers by their seed (about
  half women, an elder when their walk is an old person's, kids only out of school hours). The player keeps their own
  sheet. Left out: pink/blue/grey skins, anime hair, cat ears, swimwear, masks, the ninja suit, novelty hats.
- How busy the pavements are (also 015754): `TownCrowd.share(hour, shops, homes, others, outdoors)` thins each live
  row's StreetLife stream by its people's day (src/town/schedule.ts: at work, at school or at home most of the day; out
  on the commute both ways, the school run, errands, evening strolls), by what the row is (shops or houses) and by
  `TownPlay.outdoors` (the stage sets it from the weather: rain keeps 75% in, mist 35%, an overcast sky 15%, snow some).
  Who is out is fixed per walker (its slot's hash against the share), so nobody pops mid-walk. King Street at 17:36:
  13 people drawn round the player (was 27), Main Street keeps most of its stream until the shops shut.
- See-through (020240, "can't see the character in the woods"): `SeeThrough` + street_lit.mat's `seeA`/`seeB`. In the
  open world the lit material discards what lies in a cone from the eye to the player's middle (radius 55 at the player,
  shrinking to the eye), stopping 8 short of them, its edge a 4x4 ordered dither in whole art pixels; never the ground
  (its layer is left out and nothing under 1.5 up is cut) and never the people (their cards have no cone). It works on
  merged meshes (TreeCells, yards, stores, buildings) where the box cutaway (`occlude`) cannot take one tree out, and
  complements it. Cost: two float4 uniforms set with each drawn instance's `look`, and a few ALU per fragment where the
  cone is on (no texture reads). `-Dsee=0` in the preview turns it off to compare.
- GPU memory (the AI chip on CPU, PSS 2.5-2.7 GB): the screenshots' breadcrumbs had the town at 1303 MB total PSS when
  built (graphics 1031 MB, 947 textures, 82.1M texels); the rest of the game's PSS is the light model, which, kept off
  the GPU for want of room, runs from its 2.46 GB file and is counted in PSS as its pages are read. What grew in the
  town: the yards' merged atlases were at least 1024 wide however little they held, and each yard's tall thing became a
  patch of its own (wf-feedback): 136 sheets of 1024 x 256, 69 of 1024 x 128, 50 of 1024 x 64. `YardKit.merge` now
  makes each sheet as narrow as its pictures allow, widened until about square: 81.1M -> 73.7M texels on the build
  server (about 40 MB of GPU memory with mipmaps), the yards' pixels the same. The biggest left are the lots' own
  atlases (115 of 512 x 512: pictures repeated lot by lot; one global yard atlas would share them). The breadcrumbs now
  list the biggest texture sizes when the town is built (`FilamentTown.biggestTextures`). The townsfolk's sheets are
  uploaded only once drawn: at most 120 x 0.5 MB.

## The downtown through the decades, and the pack's buildings in the town (branch wf-downtown, 2026-11-19)

Desktop renders and JVM tests only, NOT yet run on the S25.

- **Generator** (`src/town/downtown.ts`, pure functions of the seed and plan; tests in `downtown.test.ts`): every shop plot
  carries a Main Street block built 1885-1935 with 1-3 storeys over the shop (taller nearer the centre); a block at a corner
  may be the red-brick corner block. Two neighbouring shop plots' back lots make a tower site (`downtownSites`). From the
  town's natural population (`population.ts`): urban renewal clears back lots for surface parking 1962-1978 (towns with 120
  people by 1975); a mid-rise (7-11 storey office, or a hotel) each time the town passes 175, 190, 205, 220 people from 1958;
  a glass tower (13-19 storeys, office styles 4/2) each time it passes 235, 255, 275, 295 from 1982, which may replace an early
  mid-rise (demolished, a year of parking between). `downtownHistory` has every building with built / demolished years;
  `downtownStanding(plan, year)` what stands. Tower sites are only in the core (mean rank under 72 tiles from the centre);
  when the core has no free site a glass tower takes the oldest mid-rise's. The export leaves out a tower or car park
  whose two plots have no shop that year. The town hall (Victorian, with its clock tower, built 1880-1910, 75% of towns)
  stands at the plaza's back facing the main street, the chapel (Grace Chapel, built 1870-1915) on the nearest free woodland
  by a street within 80 tiles of the centre (about two towns in five).
- gamma: Main Street blocks and parking until 1994, then a hotel (1994, 9 storeys), a second hotel (2006), two offices (2012,
  2019; 11 storeys): tier `midrise`. quartz (321 people by 2025): four mid-rises 1977-1993, glass towers 1998, 2006, 2014, 2020
  (13-19 storeys; two of the mid-rises pulled down for towers in 2012 and 2018). orbit (peak 113) stays Main Street blocks.
- **Export** (`tools/town-downtown.ts`, called by `town-graph.ts`): the shops become facade-kit blocks (`compose_facade`
  with `ground.picture`: the business's own storefront as the ground floor, its sign a card over the floor above; the kit's
  palette from the district's shop colour), a corner bank / law office / doctor's office the octagonal corner block; 40% of
  medium mobile homes the big mobile home of the same design and colour; apartment lots' condo stand-ins (and half of the
  others) the pack's condo terraces, Condo_3_46, Condo_8 or a facade-kit condo block, by where the lot stands: by the old
  core (plot rank below CORE_RANK, `apartmentNearCore`) up to five storeys, out in the residential streets among the houses
  and mobile homes only a 2-3 storey walk-up (the short terrace, a facade-kit block of 1-2 middle floors; a pack middle floor
  is a tall storey; the game's own 4-6 storey Condo_3_45, Condo_5 and Condo_9 are always replaced there). House lots never get a pack block (town-graph.test.ts checks both); half the schools the Victorian old
  school (V5). The towers, hotels, car parks, town hall and chapel are row buildings of kind `extra` (nobody in them: no
  door, no props, not a front; `flat: true` for car parks, walked over), named after every lot and landmark, listed in
  `graph.extras` (storeys, built, demolished). A tower stands 230 units behind the building line (the shops, then a 44-unit
  service alley: the shops' lots of that run end there); its depth 320 px. An extra that would overlap anything is left out.
- **Far boxes** (`scene3d/TownFar.kt`): every building past the haze's start (+300) is drawn as a cheap box (its footprint,
  as tall as its front picture, wearing its own front and side shrunk 4x with lit windows at night, a flat roof colour),
  merged per 900-unit cell (one draw a cell, ~10 triangles a building), switched with 120 units of hysteresis and a 0.4 s
  screen-door crossover, drawn to 24000 units (the camera's far plane follows; shadows keep their own reach). The open
  world's haze takes the stage sky's horizon colour (its glow) and is thicker (0.00062: ~60% at the view's range); the trees'
  cards are drawn 3400 units past the range so the edge woods dissolve into it. Cut-out alpha comes from a mip two levels
  sharper (far crowns no longer vanish under the mask's half). `-DtownFar=0` in the open preview compares without.
- Measured (desktop, software GL, not the S25): far boxes for gamma 2025: 49 cells, ~1100 triangles in all. Draws and
  triangles as drawn, before (no far boxes, the old haze) / after (far boxes, cards into the haze): build 0.1.367's horizon
  spot (015705, GPU p50 22 ms on the S25 there) 548 / 723 draws, 141k / 158k triangles; its woods spot (020102) 176 / 320,
  112k / 131k; the skyline from across town 585 / 717, 149k / 158k; the widest view 818 / 961, 202k / 212k. Most of the
  added draws are the trees' cards past the range (one batch a chunk); the far boxes are one draw a cell.
  townMemoryCheck: held peak 197-201 MB of the 256 budget over runs (was 195).
- **Frontage by block face** (the owner's rule: never a pavement building beside one set back behind a car park): a run of
  touching shop plots (`runOf`) is one block face; its frontage is decided for the whole face by its plots' mean rank
  (`stripFace`): the old core (under 72) is a street wall at the pavement, 2-4 storey blocks shoulder to shoulder, its lots
  behind reached by the service alley, towers and renewal car parks behind; a face past it is a post-war strip: each shop
  (1-2 storeys, built 1950-1985) stands 210 units back behind its own car park (painted stalls, a kerb with a gap for the way
  in, flat: walked over; the shop's lot and yard begin behind it). Transitions fall between runs (at side streets or gaps).
  gamma 2025 has one strip face (two shops, west Main Street); quartz two.
- Left (the owner's parking request, not done yet): on-street parking (angled bays on Main Street, parallel on side
  streets, meters from the 1940s), parked cars on the downtown's and the strips' car parks by the hour (StoreCars could be
  reused), car-park lighting and planting, side or rear lots for offices and apartments beyond the towers' alleys.
- Left: the downtown is built for the export's year only (no time travel in the app, unlike the stores); towers have no
  interiors or people; car parks have no cars; the glass towers are the pack's office styles 2/4 (no curtain-wall art); the
  far boxes are boxes (pitched roofs read flat far off); the stores and cinema still fade at the range; quartz has a
  pre-existing shop overlap (b2/b3 on 3R).
## Farms round the town (branch wf-farms2)

The owner wants farms in the world, with crops. A town is ringed by farmland; as it grows, the fields nearest it are sold
and built over (a field farmed in 1955 is houses by 2025).

- Generator (`src/town/farms.ts`, tests `farms.test.ts`; one call from `layoutTown`: `layout.farms`). Pure of the seed's
  plan (`rand(seed, 'farm', ...)`), in the plan's tiles. The farmland is the map and `RING` (100) tiles round it.
  - Country roads: out from the streets' outermost ends that point out (the main street's two ends, the furthest street
    north and south; a line out must cross no street, plot, landmark or water), straight to the farmland's edge, and a
    section road round the town (`PERIM` 36 tiles past the map) linking them. In a year when the street a road continues is
    not built yet, the road runs on in along its line to the first street that is (`farmsIn`: `inner`). The river runs on
    past the map (its edge runs, straight out); the country roads cross it on bridges.
  - Farmsteads: along every country road, each side, every 46-72 tiles, a yard of 30 x 26 tiles facing the road, kept 4
    tiles from anything the plan will ever build (streets, every plot, landmarks, plaza) and from water: the farmhouse
    (one of the two) near the road with its own front lawn and fence, the barn (one of four colours) back across the yard,
    one or two silos beside it, the machine shed facing the yard, a corral, the tractor, hay, troughs, a well, crates,
    barrels, a windbreak of trees along the back and the house's side, a gravel lane from the road, the farmyard's beaten
    dirt; mirrored by a coin. A farm is a family (`household`: surname, members, `rural: true`, founded before 1950): the
    people model does not house anyone outside the town yet, so it is flagged for later. A produce farm (55%) has a
    roadside stand (a market stall) by its lane in June to September.
  - Fields: up to five round each farmstead (beside it along the road, behind it, behind those; 26-44 x 22-36 tiles, shrunk
    to fit), each fenced with its gate toward the yard: grain (corn and wheat in rotation, `['corn','wheat']` or
    `['corn','corn','wheat']`), hay, or a truck farm's vegetables (four in rotation from crops.json's Midwest crops);
    `cropIn(field, year)`. And fields over the town's outer plots: blocks of free ground (no street, landmark, plaza or water
    in them) that take in future plots, farmed by the nearest farm until the town builds there: a field is farmed in a year
    only while nothing built that year overlaps it (`fieldClear`: built streets, plots, open landmarks), and `fieldLives`
    gives each field's life (sold the year it is first built over, and as what).
  - gamma: 28 farms (all working in 2025), 57 fields with a life from 1950 (55 farmed in 1955, 50 in 2025: 7 built over, as
    houses from 1964 on, one into a yard), 4 roads out and the section road (8 pieces), 2 new bridges.
- Export (`tools/town-farms.ts`, `withFarms` after `withYards` in `export-town.ts`; test `town-farms.test.ts`): `graph.farms`
  in world units: `roads` (centre lines, asphalt 96 wide, verges 22), `farms` (yard, farmyard ground, front, family,
  household, lane, things: model, front's middle, facing, door, summer), `fields` (rect, use, rows' direction, gate,
  rotation, phase, the year's crop, `from`, `sold`, `becomes`: houses, shops, street, store, landmark, yards), `standMonths`,
  `extent`. A field the export year's yards reach into is sold that year (`yards`). `graph.water` gains the river past the
  map, `graph.bridges` the country roads' bridges; `graph.props` loses the forest standing in farmed fields, farmyards,
  lanes, on roads and in the river, and gains the windbreaks, so TownGround's feather and the world's woods (and the
  collision's wall, 1200 past the outermost data) now stand past the farmland: the world grew from about 19,000 x 10,000
  to 28,000 x 19,000 units. Shipped: assets/town/gamma/street.json (only farms, props, water and bridges changed; the
  buildings, dialogue and interiors are the same).
- Engine (`domain/TownFarmsData.kt`: parse, `CropBook` (crops.json), `FieldNow` (a field on a day), `FarmSolids` (fences with
  open gates, the models' solid parts, their places and doors); `scene3d/TownFarms.kt`, `FarmPics.kt`):
  - FilamentTown's hook is `TownExtra` (scene3d/TownExtra.kt): `addExtra { kit -> ... }`, stepped in `buildNearest` once
    the ground is in, framed after the stores, given the open-world camera's cone in `occlude` and cut in `fadeBetween`,
    destroyed with the town. `TownFarms.attach(town, data, FarmArt, collision, year, day)`; the app (TownSession,
    FilamentLayer) attaches it with today's day of the year (Season.override's in tests).
  - Roads: asphalt (the town's tile, white edge lines, a dashed yellow centre) on gravel verges; lanes of gravel; the
    farmyard's dirt.
  - Farmsteads: the farm models (assets/farm, FilamentModels) where the export stands them, each culled by its sphere; the
    farmhouse keeps its lawn and fence and is the only one with a night-glow mask; the tall ones (barn, silos, house, shed)
    are cut away in the open-world camera's way like buildings. Their parts standing below head height are solid
    (`setExtra("farms:buildings")`), the farmhouse's and barn's doors are `setExtraDoors("farms")` (no rooms yet, so no
    "Go inside"). The stand only shows in its months.
  - Fields by the day (`FieldNow`): winter (bare frozen ground, or corn's stubble), ploughed in spring, growing (sprout,
    young, growing, budding), ripe, stubble after the harvest (corn's cut stalks through the winter, wheat's brush until
    the autumn ploughing), next year's winter wheat sprouting in October and lying young under the snow; hay a meadow
    (tufts), mown in late June and mid August with round bales lying (`fm_roundbales`), brown in winter. Drawn as: tilled soil
    as real low ridges along the rows (5 high for corn, 1.5 for drilled wheat, 6 for beds; their slopes in the pack's soil
    tile, so the furrows shade by the sun), a level soil under them; the crop as crossed cards of its frame on the crests (a
    plant every 12-38 units by crop; corn 26 apart, wheat 15), small and sparse when sprouting or under snow; a canopy band
    along each row at two thirds of the plants' height in the crop's own colours with gaps (from above and past the cards'
    range a field reads as its crop, green or gold); snow (Season's) on soil and meadows; a post-and-rail fence round each,
    its five-bar gate standing open into the field. A farm's fields are one soil batch, one canopy batch and one batch of
    cards per picture, rebuilt on a worker when the day changes what they show (`day`).
  - Built a farm a job on the town's workers (at most three at once, nearest first, after the ground), uploaded at most 3 ms
    a frame (a farm's batches, then each model). Plants drawn within 2600 of a farm's edge, shadows within 1600.
- Preview: `gradle :desktop:preview -Dmode=filament-farms` (`-Dtown=DIR` for another export, e.g. 1955, `-DfarmTag=1955`,
  `-Dshots=`) writes build/preview/filament-farms/: farmstead_{front,left,right,back,close,dusk,above}, field_{spring,
  early_summer,summer,autumn,late_autumn,winter,summer_low}, fields_{summer,autumn,winter}_above, hay_{bales,meadow},
  stand_summer, edge_{road,high,above}, farmland_all, town_inner_fields, sold_field_{above,wide}, and farms.txt.
- Measured on the build server, software GL (estimates, not the S25): the farms are 28 farms, 50 fields, 372 models;
  triangles: ground 33k, soil and canopy 15k, crop cards 375k, models 467k (all of them, before culling). Open-world views
  at a farm: 240-320 draws, 104k-127k triangles; at a field's gate 94-128 draws, 103k-186k triangles; the whole farmland from
  high up (a free camera, nothing culled by range) 10.9k draws, 1.5M triangles. townMemoryCheck (cold start, phone heap),
  after the merge with wf-downtown, the build server loaded (load average 15 on 4 cores): held 213 MB at the peak (202
  without the farms) of 256; GPU estimate textures 594 MB (512 without); first view 11.6 s (10.0 s without: the bigger
  world's ground and woods; the farms themselves wait for the ground). Before the merge, an idle server: 200 MB (186),
  textures 521 MB (439), first view 5.0 s (4.0 s). The farms' first uploads (each model's atlas the first time) still
  cost frames of 50-130 ms on software GL while walking after the first view: measure on the S25.
- Tests: vitest `src/town/farms.test.ts` (determinism, farms outside everything the town will build, fields clear of
  streets, landmarks, water, roads, yards and each other, gates on the edge, crops, fields by year: never under anything
  built, fewer in 2025 than 1955, a field farmed in 1955 houses by 2025, a road run in along an unbuilt street),
  `kotlin-street/tools/town-farms.test.ts` (the export: shipped, deterministic, fields clear of lots, footprints, yards,
  landmarks, roads and the river, no forest left in fields or yards or on roads, windbreaks, bridges, lives); desktop
  `FarmWalkTest` (fences solid, all 50 gates walked through into their fields, buildings solid, doors clear, roads and
  lanes walkable, the calendar); OpenWalkTest re-pinned (16 parapets).
- The railroad (RAIL.md "Through the farmland", merged after): the track runs out through the farmland, round the farmsteads,
  over the country roads square (they are its roads out of town: the main street's old road out is gone), and splits the
  few fields it must cross; the export ships the pieces (`<id>~<n>`, each fenced with a gate): gamma 58 fields (8 cut).
- Not yet: the town's trees have no seasons, so a snowy field sits among summer trees; farm people (the families are
  flagged rural households, nobody walks there); animals; barn and farmhouse interiors (doors have no rooms); fields are
  axis-aligned rectangles (no contour ploughing); the farmyard's dirt has hard edges; the vegetable cards are busy up
  close; far farms draw every model while in range (no model LOD yet).

## Smoother frames and the owner's screenshots of 0.1.385-0.1.402 (branch wf-smooth)

Desktop renders, JVM tests and the desktop frame bench only; NOT yet run on the S25 (the S25's numbers below are the
owner's screenshots of builds without these changes).

- **What the S25 showed**: 0.1.385 at 60 Hz, Filament CPU p50 9-13 ms (p95 15-18), GPU p50 14-17 ms (p95 21-25), thermal 2,
  the light model on 6 CPU cores; 0.1.389 by a King Street tower GPU p50 46-48 ms; 0.1.402 (farms, downtowns) GPU p50
  28-41 ms (p95 32-52) in ordinary views at 120 Hz. The GPU, not the CPU, is what misses frames; a TextureView shows its
  last frame again when the GPU is late, so the main thread's frame counter (0-2% late) never saw most of the stutter.
- **Frame-spike profiler** (app `FrameSpikes`, shared `FrameWork`): every frame of the town whose interval passes 20 ms
  while frames come at the display's rate is kept with what ran in it: the game's own parts (step, build, cutaway, far
  boxes, buildings, night light, yards, greenery and its uploads, stores and farms, people, cards, Filament's own CPU),
  the uploads (tree cells, far cells, buildings, people's sheets, ground jobs, bytes), the GPU's time of the frames Filament
  last reported (each GPU frame over 16.7 ms is counted even when the main thread was on time), ART's GC counts and time
  since the last frame, the HUD's recompositions, and which AI model ran (`AiLane.current`). Every 10 s a crumb
  `frame spikes 10 s: spikes N of M frames (moving K) by <cause counts>; GPU over 16.7 ms in a of b frames (worst);
  gc n (ms); worst: <the three worst frames in full>`; the screenshot JSON's `frames.spikes` carries the last one.
- **The AI off the frames' cores**: a line written ahead of time now holds between two words while the player walks,
  rides or turns the camera (its threads sleep; it goes on from the same word once they stand 1.5 s). A line someone waits
  for, or the overnight writer, is never held. The breadcrumbs' 10 s line says how many lines were held and for how long.
- **Uploads spread**: one tree cell's detail and one far cell uploaded a frame (two cells made the greenery's worst frames
  8-28 ms on the build server; a far cell's upload was a 20 ms frame); a townsperson's sheet seen for the first time is
  read and packed on a worker (they show a frame or two later, far off) instead of in the frame. Fewer objects a frame
  (no boxed renderable instances, no list copies, no Pair per person).
- **The see-through cone only when hidden** (054003 "everything we walk by fades out", 061255 "the building starting to
  fade because I'm next to it"): `FilamentTown.occlude` says whether the player is really hidden (a building, store, the
  cinema, a farm building, a yard's tall thing across both the chest's and the head's lines, or a tree crown, a box a
  fifth of its height either side of the trunk from half way up, across either), and only then the cone opens, eased
  over 0.3 s from nothing (an iris), radius 40 (was 55). Measured shading cost of the cone always on (as before), software
  GL: +4 to +25% of the frame in the views measured.
- **The cutaway cuts less** (075528 "buildings disappear too easily"): a building, store, cinema, farm building or yard's tall
  thing is cut only when both the chest's and the head's lines pass through it (one that covers only the feet, a side,
  or one of the two stays), or when the eye stands within 60 of it.
- **The camera inside a building** (054054, Wharf Road): it stood inside the Avalon 8 multiplex, which the cinema's kit
  draws, not a slot's building, so the cutaway never knew it; the cinema is in the cutaway now (eye inside or near: cut
  whole; in the way: cut, its shadow kept). The downtown extras are slots and were already in it.
- **Haze** (054115 "texture glitching", 053921 pale ghost towers): the open world's haze starts 1600 past the player (was
  1200) and is a ground haze: Filament's height falloff 1/140, its density at the ground set so a line from the eye down
  to the far ground meets the same 0.00074 per unit on average at any camera height (about 50% at the range, was 58%),
  so towers' upper floors, crowns and the far skyline keep their colours. Its colour is the sky's glow over its lowest
  band at the glow's own alpha (at night a faint warm grey, not the orange itself).
- **Far boxes cross over cleanly**: the boxes' screen door is the inverse of their buildings' (`PropLayer.inverseFade`,
  street_lit.mat's negative `fade`): every pixel shows one or the other, never both (they z-fought: the "glitching") nor
  neither (the ghosts); 200 units of slack either side of the line, 0.3 s.
- **Trees at the distance** (075443 "trees changing at distance"): a chunk's cards and its 3D greenery cross over the same
  way over 0.35 s, with 80 units of slack either side of the 3D line, instead of swapping all at once; the trees' cards
  past the range reach 2000 further (was 3400, into thick haze anyway).
- **The line on the horizon** (075343): the stage's sky drew a 1 dp line and its own ground colour at the horizon, which
  showed in the few pixels between the far end of the drawn land and the true horizon. In the open world the stage now
  fills below the horizon with the haze's colour and draws no line; and the land goes on past the world's edge as a
  grass-coloured apron 0.6 below the ground out to the far boxes' reach (one draw, no shadow).
- **Farms on the GPU**: the crops' cards are drawn to 1100 past a farm's edge (was 2600; past it the field's canopy picture
  stands for them), fading out over the last 250; they throw shadows only within 500.
- **High rises at night** (075402): a front with 24 or more glowing windows (towers, hotels; models and sprite fronts) lights
  each window on its own draw against a share by the hour (`BuildingMeshes.towerShare`: about 55% at seven in the evening,
  35% at nine, 13% before midnight, 8% in the small hours, a building a little more or less); a model's whole lit side is
  split into its windows first.
- **The clock**: the time slider ends at 23:59 (at 24 the clock read 12:00 PM).
- **Resolution** (AI panel "Resolution": Auto, the default; Full; 85%; 75%): Auto lets Filament lower the drawing
  resolution (to 72% at most, FSR's edge-aware upscale) only while the GPU misses its 60 Hz budget, so a heavy view loses a
  little sharpness instead of frames; on a 120 Hz display Filament's frame interval is 2 (paced to 60). Untested on the
  S25: check that Auto does not soften views the GPU can draw, and set Full if it does.
- **The bike's camera** (the owner's one exception to the camera rule): riding, the camera swings round behind the rider
  with a lag (about 0.9 s), at most 75 degrees a second, not below 30 units a second (no spin when stopped), paused 3 s
  after any drag, pinch or recentre and eased back in over 1.2 s; AI panel "On the bike: Camera follows / Camera stays".
  On foot nothing turns it (BikeRideTest.theCameraFollowsTheBikeOnlyOnTheBike).
- **Desktop**: `-Dmode=filament-open -Dshots=fb3,fb4,fb5` the screenshots' spots (the farms attached as in the app;
  `-Dfarms=0` without), `-Dgpucost=name,...` a view's software-GL time with one thing changed at a time (the cone, haze,
  shadows, post, far boxes, buildings, yards, greenery, ground; llvmpipe, noisy: read the shares loosely),
  `-Dshots=bench [-DbenchSecs=20]` the frame bench (a 20 s walk then ride through downtown, Wharf Road and east Main
  Street through the game loop: CPU by part, allocations, the worst frames with their parts and uploads; bench.txt),
  `-Ddebugeye=1` what stands by the eye in each view.

## The hand-made landmark models in the town (branch wf-place, 2026-11-19)

Desktop renders and JVM tests only, NOT yet run on the S25. Details: docs/LANDMARKS_GLB.md "In the town".

- **Generator** (`src/town/glbSites.ts`, tests `glbSites.test.ts`): which of the fourteen models a town earns (its size at
  36 inhabitants a simulated person, its kind, its railroad), the years each business stands (opened, shut, reused, pulled
  down: `glbTenures`), and its plot (`glbSites`): after the plan, on ground beside any street that no lot the town builds
  by 2025 uses, clear of streets, river, plaza, landmarks, town hall, chapel, farmsteads, country roads, track and depot,
  the 3D yards and each other, by lot type (downtown, corner, highway on the main street's outer stretches, civic, edge),
  keeping the frontage rule (a car-park building never beside the old core's street wall, the Gazette never beside a
  strip). One club a town at a time (Mirage, then Pulse on its plot or The Foundry). A plot is reused once its model is
  pulled down. The railroad's model depot is rail.ts's (`depot.premier`, a town of 3,000 and more).
- **Export** (`tools/town-glb.ts`): standing models become row buildings of kind `landmark` (named after every other
  building, so no bN name moves), `graph.landmarks` (art = the kind) and `graph.glbLandmarks`; props in their plots dropped,
  fields under them sold (`becomes: 'landmark'`). gamma 2025: the Gazette, the water tower, the feed mill (a brewery), The
  Foundry, the highway gas station, the coin laundry (shut) and the Union Depot; 6 rows' buildings added (4 rows changed),
  2 fields gone under the depot's plot, 2 sold (to the motel in 1962, the laundry in 1975). street.json, rail.json and the desktop fixture town-gamma.json re-exported.
- **Engine**: `FilamentTown(landmarks = LandmarkAssets(...))` builds a `TownLandmark` per slot and one for the depot (the
  code-built depot left out then), with collision, doors, night lights (dark when shut), the cutaway and the open-world
  camera's cut; drawn whole to 12,000 units past their reach instead of a far box. The open preview draws the railroad and
  has `glb_*` shots.

- (branch wf-foundry) The Foundry is now one building through time: a works (four painted company names, a tall chimney,
  hardly any signs) opens 1900-45, shuts 1970-2005 and stands dark, then becomes the club 2010-29 on the same plot; gamma:
  Pelham Foundry & Machine Co. 1909-1980, The Foundry 2022 (LANDMARKS_GLB.md "The works before The Foundry").
