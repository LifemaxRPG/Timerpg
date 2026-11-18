# Our sprites in the 2.5D street: what each needs to stand up in 3D

An audit of `public/assets/` (about 650 sprites, curated from LimeZu's Modern Exteriors / Interiors) against the Kotlin
street engine, which draws buildings as lit boxes (a front, a side, a roof, depth/lighting maps) and props as upright
cards. The street uses about 90 of them so far: about 35 building pieces (`classic_bank`, `condo_ground`, `middle`,
`roof`, `roofprop`, `shop_*`), one lamp, one bench, three trees, and the 48 interior pieces.

Contact sheets were made with a throwaway script from the folders below (houses, civic, shops, props).

## Done

| Sprites | Count | How |
|---|---|---|
| `houseSmall`, `houseMedium` (the mobile homes) | 160 (8 designs x 2 sizes x 10 colours) | **Done** (pitched roofs, see below). `tools/make_buildings.py` `build_house()` cuts each into the pictures of a real house: the art's front wall (rows 100-185), the near slope, ridge cap and far slope taken every other row (the artist's own 1x pixels, one screen row each), a gable end made from the wall's own rows, the hitch as a card, the porch's railing, fascia and deck boards; its shape goes to `buildings/houses.json`. The engine (`BuildingPainter.houseBuilding`, `domain/HouseGeometry.kt`) builds it: set back in its front yard, a ridge along the street, gables with rake boards, a flue, the porch or steps as lit boxes. Small and medium homes on the exported streets wear their lot's own sprite (`tools/town-streets.ts`). `gradle :desktop:preview -Dmode=houses` shows each beside its sprite; `make_buildings.py --showcase DIR` builds all 160 for it |
| `villa_1..5` | 5 | **Done** (front-facing gables, see below). `build_model()` cuts each into parts (`buildings/models.json`) and one atlas: the ground floor, the floor between the storeys, the upper floor whose front carries the gable (a true front elevation, used as drawn), the front gable's two slopes (the art's slopes from above), the terrace roof, the balcony, the small gable over the door, the posts, the deck, its railings and steps. The engine builds them (`BuildingPainter.modelBuilding`, `domain/ModelGeometry.kt`); villa-tier homes on the exported streets wear them (`tools/town-streets.ts`; none on eta's streets, two on vale's). `-Dmode=models -Dstreet=2` with the showcase |
| `landmark_clocktower_1/2`, `landmark_school_1` | 3 | **Done** in a preview row (hipped roofs, see below), not on the two streets yet: the tower a square box wearing its front on every side under a hipped roof with a flat top and lantern; the school its long front, a middle bay standing forward under a cross gable, a hipped roof with a flat ridge, two chimneys, its steps. `-Dmode=models -Dstreet=3 -Dtall=760` |
| `fence_9`, `fence_10`, `hedge_1..5` | 7 | **Done** for the houses' yards: laid end to end into one upright strip along the lot's front (a picket fence; a hedge for a formal yard; nothing for a natural one, as the game fences them), open at the gate and across a porch |

### How the pitched roof was done (and what it means for the rest)
- The art's roof is drawn from above (an 86-row slab for a ~83-pixel-deep, 9-pixel-high roof), but the street's view is
  nearly level (120 pixels of depth rise only 12 on screen), so the roof cannot be used as a picture. It is the texture
  on real sloped geometry instead: the near slope's rows taken 2:1 and the rise chosen so each is exactly one screen row
  (about 21 degrees against the art's 12: one by-eye constant, `house_rise`). The far slope faces away and is hidden by
  the ridge in the street (kept for the corner turn).
- The art never drew the gable ends (the ridge runs along the street): they are made from the wall's own row colours,
  its siding carried up into the triangle, a vent under the apex, the front's first window on it.
- Seen from the street's nearly level view a gable is almost edge on: the roof slab's end (the rake boards, standing
  out by the verge) and, behind the ridge, the soffit under it (the far slope is steeper than the view, so its
  underside faces the camera) are steep bands a pixel or two wide. Drawn as paths they came out as blended slivers with
  sky between them; they are filled one whole screen pixel per row instead (`PixelRows`), stepped like pixel art and
  never thinner than a pixel. The corner turn, which looks from higher, lays the end wall's own picture on the gable.
- The house stands back in its yard (the game's own 2 tiles), so it moves against the pavement as the street scrolls;
  its shadow, the sun's edge, the cloud shade's clip and the framing follow it.
- Glass panes are kept as drawn (curtains and blinds), with the engine's reflection laid faintly over and a lamp lit
  behind at dusk on a home's hours; nothing is cut out (the glass mask covers less than half of each pane).
- The corner turn and the crossing build them too: a box of their own width set back in its slot, a roof prism
  wearing the near and far slopes (`scene/TurnRoof.kt`), and the yard, fence, porch or steps and flue the street shows
  (`scene/TurnHouse.kt`).
- Dressing done: the yard's fence or hedge with its gate (snow along its tops, in the house's shade at dusk), the path, a
  porch light at dusk, flue smoke in the cold months.
  Still to do for these: hedges down the yards' sides, pigeons on the ridges, eave drips in rain, side-window glow.

### How the front-facing gable and the hipped roof were done
- A building of more than one volume is a list of parts drawn back to front (boxes, front gables, hipped roofs), each
  wearing a piece of one atlas: the general shape the estates and the fire station will need too.
- A front-facing gable's triangle is a true front elevation in the art, so it is used as drawn (on the front of the
  storey under it). Its slopes, seen from the nearly level street, are bands over the rakes about a tenth of the
  roof's depth tall: each is the art's slope from above laid on its quad by one projective map (`QuadMap`), shown only
  where the view sees it from above (`RoofFaces`): from far to one side the other slope is behind the ridge. The art's
  rake is a staircase of pixels and a slope's front edge a straight line, so the roof's own edge colour fills between
  them (no sky shows along the rake), and a little above the line it is the verge, the roof's edge seen edge on, which
  shows whether or not the slope does (a slope seen only as a sliver, or not at all, still has its edge); the eave's
  board along the side is drawn after the wall carrying the face.
- A hipped roof's front face is the art's face between the hips taken every other row, the rise chosen so each row is
  one screen row (as the houses' slopes); its side faces are wedges over the hips, laid on their quads. The art draws
  those in the front's way (courses across the picture), which no view of a hip's side shows: they are tiled anew with
  the front face's own tiles, each course along the depth at the height of the front's (the two meet at the hip), and
  where the view sees a side so nearly edge on that its joints would fall closer than two pixels apart, each course is
  one colour (the two fade into each other as the street scrolls). Along each hip a band of the art's crease (and the
  school's lighter cap tiles) covers the steps of both faces' pixels: no sky through the hip. A flat top (the clock
  tower's lantern stands on it).
- A picture's round glass (a clock's dial) mirrors no square of sky; its night glow fills the whole dial. A clock's
  painted hands are taken off the art and drawn at the game's hour. A window's night light is every shade of its glass
  joined to its clean glass (its darker reflections too), not its frame or mullions, a little brighter where the art's
  glass is lighter.
- A building wider than a shop (a villa is 256 pixels, the school 768) takes a wider lot and stands in its middle.
- The corner turn and the crossing put the villa's front gable on its box (`scene/TurnVilla.kt`): the same slopes laid
  by one perspective map each, the gable ends the art's own triangle. The box's front picture carries the balcony, the
  small gable over the door and the deck's railings as the street sees them from straight ahead (the wet road mirrors
  them too).
- Still to do for these: the turn's villa deck, porch and small gable as volumes (they are flat on its box's front
  there), the school's back wing, the tower's corner pinnacles, lighting maps; the cross gable's slopes are slivers in
  the street's nearly level view (the art draws them from high above), and the landmarks are taller than the street's
  frame (their roofs above it) until they stand on a street with room for them.

## Easy: already boxes (the current pipeline, `tools/make_buildings.py`)

| Sprites | Count | Why easy |
|---|---|---|
| `classic_*` shop fronts (bakery, bank, butcher, clothing, doctor, dress, gym, ice cream, law, music, toy, bait) | 72 | flat front elevations, sign band on top, glass panes; exactly what the box engine was built for |
| `condo_ground`, `middle`, `shop_Condo`, `condo_5_*` | ~40 | flat storeys that stack |
| `landmark_post`, `landmark_hotel` | 5 | flat tall fronts; the post office's top band is its flat roof seen from above (cut it off and use our roof) |
| `roofprop` billboards | 5 | flat panels on posts: a card on the roof |

## Medium: a new piece of geometry, then straightforward

| Sprites | Count | What it needs |
|---|---|---|
| ~~`houseSmall`, `houseMedium`~~ | 160 | done (see above) |
| ~~`villa_1..5`~~ | 5 | done (see above) |
| ~~`landmark_clocktower`~~ | 2 | done in a preview row (see above) |
| ~~`landmark_school`~~ | 1 | done in a preview row (see above) |
| `landmark_fire`, `landmark_hospital` | 2 | several boxes of different heights side by side (multi-volume), a garage set back; flat roofs (the hospital's helipad is a roof decal) |
| `shop_Ice_Cream_Shop` | 3 | a shop front plus a **giant cone sign standing above it**: a card above the roof |
| Ground art: `asphalt`, `sidewalk`, `dirtPath`, `grass`, `grassPatch`, `waterShore`, `waterDecor`, `flowers`, `clutter` | ~70 | drawn from above, which is right for a floor: they go onto the ground plane in perspective (decals), not upright |
| Trees, bushes, hedges, flower beds, lamps, benches, fences, bridge rails | ~45 | upright cards facing the camera (as the trees already are); hedges and fences as long cards or thin boxes along the road (the fence and hedge pieces are done for the houses' yards) |

## Hard: not boxes, or drawn in another projection

| Sprites | Count | Why hard | Approach |
|---|---|---|---|
| `houseTerraced_*` | 30 | drawn in a **different projection**: an oblique 3/4 view with a huge lean-to roof tilted at us, the right side wall showing, and garages/doors at an angle. There is no front elevation to take; the roof hides half the house | rebuild as geometry (box + steep single-pitch roof + side), using the sprite's materials (siding, roof tiles, door, windows) as textures cut from it; or repaint a front elevation per variant |
| `houseEstate` (country, Japanese, modern, one-story) | 4 | **several volumes at once**: L-shaped plans, many roof planes at different heights (the Japanese house's layered eaves), terraces, a modern house's flat roof with a deck | each estate as a small set of boxes and roof prisms modelled by hand, the sprite's pieces as textures; the most work per sprite |
| `landmark_police` | 3 | a **curved wing** (a rounded corner with a curved parapet and windows following the curve) beside a square block | the curve as a cylinder segment: a new geometry type (a box with a rounded end), its windows mapped around it |
| `fountain1_*`, `fountain2_*` | 8 | **round**: a circular basin (a cylinder in perspective) and a statue | basin as a cylinder ring drawn in the street's perspective, water as the same AGSL water we have; the statue as an upright card |
| `characters` | 8 sheets | sprites, not geometry | stay 2D cards (as now); only their shadows and lighting are 3D |

## What is left, re-checked after the pitched, front-gable and hipped roofs (2026-11-18, by eye from contact sheets)

Ranked by how much each still needs, now that the roof machinery (near-slope-as-texture, front gables, hipped roofs,
multi-part models) exists.

| Sprites | Count | What it still needs | Built on |
|---|---|---|---|
| ~~`houseTerraced_*`~~ | 30 | **Done** (2026-11-18): their front is the END of a single-pitch roof rising across it (low left, high edge running back on the right), the slab that roof from above, a lower side wing along the right. `make_buildings.py terraced()` makes the facade a true elevation (in a recess's columns the rows its top hides are put back, so every wall meets the ground on row 461), the slope from above, the wing's front and top, side walls from the upper storey's siding, steps as boxes and the planter or pot as cards (per design: `TERRACED`); the engine draws the roof as a front gable with its apex at one side (`ModelPart.apexX`, only its left slope). Terraced lots wear them (`town-streets.ts`); eta's two streets have none (vale's has one). `make_buildings.py --showcase`, `-Dmode=models -Dstreet=4` | front gable, `build_model` |
| `houseEstate` (country, Japanese, modern, one-story) | 4 | Hand-modelled each, as a list of parts: **country** a front-gable main volume, a lower side wing with its own roof, a wrap-round deck with railings and steps; **Japanese** a front gable whose lower floor has a **pent roof** (a lean-to strip along the facade, the same monopitch) and a side annex; **modern** a monopitch main block beside a **flat-roof terrace** with a pergola, parasol and railings (props on a deck), and a stair up the side; **one-story** a front gable with a **second, lower gabled garage** standing forward of it (a cross gable, as the school's) | villa models, front gables, decks |
| `landmark_police` | 3 | The **curved wing** (a quarter-cylinder with a curved parapet and windows round it) still needs a new shape; the rest is a tall square block, a lower wing, a **flat roof drawn from above** (cut off, our roof with the parapet, spotlights and the hipped skylight as props), entrance steps | new: cylinder segment |
| `landmark_fire` | 1 | Two volumes: the hose tower (left) under a **pyramid roof** (the hipped machinery) with its sign and a **porch/canopy** standing forward at the bottom; the engine bay block with a **flat roof seen from above** (cut off), two garage doors set into the front | hipped roof, models |
| `landmark_hospital` | 1 | Stepped multi-volume: a main block, a wing set back on the right (emergency), a **canopy on pillars** over the entrance standing forward; the big **flat roof from above with the helipad** becomes our flat roof with the H as a roof decal; a rooftop plant room box | models |
| `condo_9` | 1 | Drawn like the terraced houses: oblique, its **side wall showing** (pipes, a round window), flat roof from above. Rebuild as a box from the front strip and the side strip | terraced approach |
| `condo_3_44/45`, `condo_5_1..3` | 5 | Fronts are fine; the top is the **flat roof from above** (cut it, use the engine's roof with the parapet colour) and the condo_5 **entrance steps and canopy** stand forward (a deck box, as a porch) | `build_model` |
| Main Street roofs `roof_2/4/6/8/9/11` | 6 | Pitched tile roofs in the game: `roof_2/4/6` a plain pitched slab (the houses' slope along the street), `roof_8/9/11` a pitched roof **between two parapet firewalls** (stone posts at each end) — slope plus two small end boxes. `roof_1/3/5/7/10` are flat roofs with parapets: already right | houses' slope |
| `shop_*` (modern fronts) | 66 | Every one has a **sculpture sign standing on its roof** (the fish, the loaf, the meat and cleavers, the dumbbell, the cone, the music sign): an upright card on the roof line, set back a little so it parallaxes and casts a shadow, lit at night. The ice cream parlour's **striped awnings** stand forward (small sloped boxes) | prop cards |
| `landmark_post` | 4 | Front storey fine; the upper storey's **roof is drawn from above** with a hatch, ladder and vents (cut off, our flat roof, the vents as props); the **awning band** over the windows stands forward | `build_model` |
| `landmark_hotel` | 1 | Flat tall front; the **HOTEL sign on the roof** as a card, the green pilasters could stand forward a few pixels. 736 px tall: needs the taller frame the landmarks need | boxes |
| `middle_2/8/14` (and kin) | ~6 | A **planter shelf / window box** under the windows stands forward: a small box; the rest of the middle storeys are flat and right | boxes |
| `classic_*` shop fronts | 72 | Right as boxes. Polish only: the ice cream and butcher **awnings** as small sloped boxes | boxes |

Order that gets the most on screen soonest: the terraced houses (30, and they fill residential streets), the Main
Street pitched roofs and the shops' rooftop signs (on screen all the time), then the estates, then the civic buildings
(fire, hospital, post, police last for its curve).

## What this means

- **Hardest overall:** the terraced houses (another projection) and the estates (many volumes), then the police
  station's curve and the fountains' round basins. Those are better rebuilt as engine geometry wearing the sprites'
  materials (as was done for the gym's balcony) than converted from the pictures.
- **Biggest win for the least work:** the pitched roof. It unlocks 160 small and medium houses (and the villas, school
  and clocktower with the gable and hipped variants), which is what the residential streets need. **Done for the small
  and medium houses**: Orchard Lane is now a street of mobile homes. **Done for the villas** (front-facing gables) and
  **the clock towers and the school** (hipped roofs, in a preview row). Estates, terraced houses and apartments are
  still stood in by condo fronts (on eta's exported streets only one: b17, an apartment block). Main Street's own roofs
  (`roof_2/4/6`, `roof_8/9/11` are pitched in the game) could use the same machinery next.
- **Cheap volume:** ground decals and upright prop cards (about 115 sprites) mostly need placement rules, not new
  rendering.

## Estates, group A: the one-story house and the country house (2026-11-18)

Built by `tools/buildings_estA.py` (`estate_one_story`, `estate_country`), which `build_model` calls; showcase street 6
(`make_buildings.py --showcase DIR`, then `-Dmode=models -Dstreet=6 -Dtall=560`). Estate-tier lots wearing either
sprite wear it on the exported streets (`town-streets.ts`; eta's two streets have none yet).

- **One-story house** (`estate_one_story_house`): the main front gable's face is the art's elevation down to the ground,
  with what the art draws from above in it painted out: the garage's roof (and the shadow line it draws on the wall)
  is replaced by the wall's own boards, each pixel the board of the same place in the 26-column run from the nearest
  clear column at the same depth under the main rake (the art shades the wall darker just under the eave; a source far
  away left a pale ghost of the roof). The garage stands 24 forward under its own front gable, its slopes running back
  into the main roof. The porch's roof is a **lean-to** (a `hip` part with a degenerate top at the wall: the art's
  boards from above every other row, one screen row each) on a beam and two posts, over a floor; the wall behind it
  stands on the floor's front line (moved down the floor's depth as drawn) in the roof's shade. Side walls carry the
  front's boards and the gable's window.
- **Country house** (`estate_country_house`): a two-storey front-gabled main house; the upper storey (the gable's
  elevation) stands on the balcony's floor. Each main slope is the parallelogram between its back edge and its rake,
  the dormer on the right slope and the ragged notches of both back edges filled with the same column's tiles a course
  (14 rows) further down. The **wrap-round balcony** runs on the porch roof along the front and down the whole right
  side (the art draws that side's floor from above beside the roof, with a short rail across its back end: it is not a
  chimney), with railings front, side and back; under it the **wrap-round porch** deck with its railings, posts at the
  front corners, halfway back and at the back, and the left side open where a step comes up. The lower **wing** on the
  left is set back under a pitched roof whose ridge runs along the street (a `hip` part whose top spans its eaves:
  upright gable ends in the wall's courses, no snow on them); the art draws the wing a little turned, so each column of
  its slope is taken from its own back edge to its own eave (the eave runs level), and the roof runs on under the main
  roof's eave to the main wall (the art's main roof hides that end, and draws its eave over the wing's wall, which is
  given back its wall and its window's right frame). Its deck and three steps stand before its door.
- Fixes after review: the one-story wall where the garage's and porch's roofs were drawn is filled in two ways, by
  how deep under the rake the pixel lies: in the art's eave shade (36 rows) from the same place in the boards' run at
  the same depth, in the plain wall from the same column (or the nearest of the same place) whole board lengths higher,
  so no other part of the wall's shading comes with it (no pale block, no seam where the porch roof's top shadow was);
  the wall uncovered under the porch beam is the plain boards at the tint the art gives the wall in the porch's shade,
  darker towards the beam and fading into the art's own shade where the moved wall begins. Its face keeps nothing
  outside its walls (the roof's left edge the art draws there floated as a bracket). On the country house the wing's
  wall starts at its corner board (x 28; columns left of it were sky); the porch's side runs to a planked end wall
  under the balcony at its back (the art draws it in the gap beside the house's corner); the balcony's back rail is
  drawn before its side rail (parts draw in turn, and the side rail stands in front of its end).
- Roof end pieces (a `hip` part's side pictures, for a lean-to's or a pitched roof's ends) are cut to their quad
  (`end_cut`): the engine maps the whole picture by its four points, so the rest stood above the roof. The wing's
  gable end carries a verge board along its slopes (the main gable's rake colours).
- The **dormer** on the country house's right slope is geometry: a box with a level roof standing out of the slope,
  its front (the art's fascia and wall, cut along the slope's line in front of it) a card at depth 75, its right cheek
  in the upper wall's courses, its roof the art's tiles from above; the slope under it keeps the tiles it was filled
  with. The cheek and the front come before the roof in the parts so that, drawn in turn, the cheek's inner face falls
  behind them.
- Still to do: from far to one side a little sky shows between the wing's ridge and the main roof's eave (true to the
  volumes, not to the art, which draws the wing's roof up to the main roof); the one-story porch roof's ends are its
  edge colour; the dormer's top is level (the art may mean a shallow shed roof).

## The Japanese and the modern estate (`tools/buildings_estB.py`, 2026-11-18)

Both modelled by hand from their sprites as lists of parts (`ESTATES`, dispatched from `make_buildings.build_model`);
showcase street 6 (`--showcase`, `-Dmode=models -Dstreet=6 -Dtall=600`); estate-tier lots wearing either sprite are
built as themselves (`tools/town-streets.ts`; none on eta's two streets yet).

- **Japanese house** (`estate_japanese_house`): the front-gabled main house in corrugated siding (its gable and walls
  a true elevation, used as drawn); the **pent roof** of copper sheets along the ground floor's front as a lean-to
  strip (a hipped part: the art's sheets from above every other row, one screen row each), its rows in the front
  painted out with the siding above them; the two **upper windows as bays** standing 8 out of the wall on their
  lattice sills, each under its **hood** (a little lean-to of corrugated iron 10 deep from the wall out past the bay,
  the art's hood from above, painted out of the wall); **an annex on each side**, each a box under its own **lean-to
  roof running back** (the art draws those roofs from above, 150-odd rows tall, which read as towers if taken as
  walls): the corrugated iron on the left, the board roof on the right, every fourth row of the art so the pitch is
  gentle and meets the main eave; their ends the wall's siding under the roof's edge. The right annex's wall the art
  draws in lap siding at a slant (its side seen at an angle) over a brown base: its front sets the boards level and
  ends where the base does (the slanted side past it is left out), and its side wall wears the same boards and base, a
  row each, with a lattice window of the front's. The art's half-clear ground shadow, which `solid()` takes away, left
  the fronts' last rows open; each column's foot is carried down to the ground in the art's ink (`to_ground`). The
  lattice windows glow between their slats at night and mirror no square of sky; the left one runs across the left
  annex's front and the main one, a pane in each.
- **Modern house** (`estate_modern_house`, from 1985): the art is an oblique view (a thing `d` back and `y` up stands
  at row 444 - y - d), which gives every depth: the **single-pitch roof** high at its left end (only its right slope:
  the slab from above laid on it, its fascia found column by column), the upper storey 34 back on the flat roof of the
  long glazed ground storey (its glazing, doors and curtains run down to the ground: the art draws their foot higher,
  behind the frame), the **entrance 78 back under the overhang** (the wall above its door, which the fascia hides in
  the art, carries on the upper storey's band of strip windows and sills under the roof's edge, level, and lights at
  night), a box under the back half of the upper storey (behind the ground storey, its sides against the deck and the
  entrance: a true 3D renderer would otherwise see a hole there), the slanted strut and a round column holding the
  roof's corners; on the **terrace** its floor from above (the furniture, rails and column painted out with the
  floor's own tiles), a glass-edged railing, a **cantilever parasol** (mast, arm and hanger as boxes, the canopy a low
  pyramid: a hipped part whose top is a point), an armchair, a chair and a table as cards; behind it the **deck**
  behind its tiled wall and glass railing; up the left side a **stair** of 13 steps (each a box from the ground, only
  its own tread deep) with its glass railing; potted plants as cards.
- New in models.json for them (the Kotlin classic renderer draws each simply): a **`rail`** part (a handrail `h` above
  the line between two points, any direction: a stair's nosings or a terrace's edge; a glass panel under it at `ga`
  of 255, posts every `posts`); a box's **`hs`** (`l`/`r`/`lr`: those sides stand against other volumes, as an annex's
  against the house, and no view sees them: the painter, drawing back to front, would otherwise paint it over its
  neighbour); a gable with **only a right slope** (`r`, no `l`) and its apex `ax` at its left end: a single-pitch roof
  high on the left.
- Still to do: the modern house's pergola-like parasol arm is a box frame, not the art's shaded beam; the left annex
  of the Japanese house leaves out the narrow horizontally boarded screen the art shows past it; neither has
  the corner turn's own volumes (they wear their whole fronts there, as the other estates).
- Classic renderer, to check them by eye: a model box's sun wash runs across the whole house's front (a wing beside
  the main wall is lit as the wall is, with no seam between them) and, when its front carries a gable's face, on up
  into the gable instead of stopping in a hard line at the eaves.
