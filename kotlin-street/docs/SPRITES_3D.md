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

### The police station (`landmark_police_1..3`), and the rounded wall

Done (2026-11-18) in a preview row (`make_buildings.py --showcase`, `-Dmode=models -Dstreet=9 -Dtall=760`), not on a
street yet: no lot tier uses a landmark. Its builder is `tools/buildings_civB.py` (`police`); the three colours share
one shape and are each cut from their own art.

- **Two new part types in models.json.** `cyl`: a segment of an upright elliptic cylinder: its centre `c` (x, d), its
  semi-axes `r` (across, in depth), the angles `a` it runs between (degrees; 0 faces the street, 90 the right, 180
  the back), its heights `y`, its top and wall colours `col` (a top of -1 is not drawn), the atlas strip `ft` it wears
  (columns evenly along the angle, rows from the top down), a ring's inner semi-axes `ri` (a parapet: its top is the
  band between the curves), `card` for a thin open surface (a rail round the curve), `sg` when its windows light at
  night. `wall`: an upright face between two plan points (x[0], d[0]) and (x[1], d[1]) wearing `ft` (the tower's
  chamfered corner). The classic street draws both a column at a time, each a flat facet between two points of the
  curve, where it faces the view (a rail both sides, its far side first), each lit by which way it faces
  (`BuildingPainter.stripPart`); a rounded wall's top is the sector to its centre or the ring's band.
- **How the strips are cut.** The art lifts what stands further back, but by eye, not by one rule, and in steps of its
  doubled pixels; sampling it along a line of depth left every band a sawtooth. So:
  - **The chamfer** (a flat wall turned at an angle) is let down column by column by the art's own step (its foot: two
    rows every four columns, measured on the red colour), so its sills, base and window frames come out level; the
    rows still dashed (edges the art steps a row every two columns) take their commonest colour (`level_rows`, which
    rows judged on the red colour, since in the greys the wall and the frames share colours).
  - **The rounded wall** wears its own courses, not the art sampled round it: the front's (the shadow under the coping,
    the blue base) with the panel's colour plain between (the art's curve has no beam and no vents; its shading is the
    light on the curve, which the engine gives it), the same on the straight side and round the back, so nothing
    changes at a seam. Its louvred windows are the art's: unrolled between the lines the art draws the curve's top and
    foot on (`curve_rows`), their clean left part (the frame, a louvre and its gap) closed with the same louvres and
    the frame turned round; a pair of them round the back as well. The ring parapet wears the front parapet's band.
  - The round pillars (the tower's, the loggia's and the porch's columns) are their art unrolled onto their front
    halves (`pillar_strip`).
- **The plan.** The wing's end is the art's D from above: a quarter ellipse at the front corner (52 across, 85 back), a
  short straight side, a longer quarter round the back (115 back). The tall block is a roof slab on pillars: the
  tower's front with three round pillars, the chamfer back to the wing's line, the open loggia (its back wall 50 back,
  where the art's floor from above ends; above what the art shows its own panels carry on), the porch under it (its
  door wall 60 back, the landing, four steps, the glass partition seen edge on, the round column).
- **The flat roofs.** Each the art's floor from above with what stands on it painted out (each pixel the nearest floor
  pixel up or down its column, `floor_of`), the parapet as boxes wearing the art's band and its broad coping (the rail
  posts drawn on it painted out), the rails as cards built from the art's bar and one of its posts (`rail_strip`), the
  ring parapet and its rail following the curve. The stair houses wear their fronts as drawn under the glass pyramids
  (hipped roofs to a point, their front faces the art's). The POLICE board stands on its legs clear of the rail (in
  the art its foot is just over the coping; as an elevation the rail would cross the letters), its letters glowing at
  night as tubes; the badge a card on its cap. The four spotlights are cards cut to the lamp's own outline (the art
  draws the rail and the coping round them, which stood in the air as bars); the front ones' lenses glow, the back ones
  are turned away. A rail runs along the back parapet too, as the art draws it, meeting the curve's.
- **Night.** The wing's lit windows are its glazing and the cash machine's screen only, found on the red colour (in the
  blue-grey colour the walls and the band over the shutter pass for glass by colour; the shutter and the vents did in
  all three).
- Still to do: the stair houses' sides and the parapets' inner faces are plain courses; nothing of it in the corner turn
  (no landmark is there yet).

### The boxes' built parts: stone end walls, rake boards, balconies, ledges, steps, sign boards (2026-11-19)

The owner's phone (build 0.1.321) showed the law office's balcony as the old flat picture, and the bakery's and law
office's stone posts as two square pillars sticking up at the front corners. In the Filament town and crossroads a shop's
or a flat's box now builds what the art paints flat but stands out (`domain/HouseShell.kt` `BoxShell`, worn in
`scene3d/BuildingMeshes.kt` and `FilamentStreet.kt`; the classic's looks keep the old pictures):

- **Stone end walls** (roof_8/9/11, `PITCHED` posts 32): each end of the box a wall as thick as the art's post, following
  the roof's slope the whole depth 6 px over the tiles, its coping (the art's capstone, rows 8..72, `bN_wcap.png`)
  overhanging 2 px, and a square pier at the front and the back rising 8 px over the coping, wearing the art's pier (its
  lighter band at its head, rows 152..224, `bN_pier.png`). The wall's faces carry on the gable picture. Gamma: b0, b13,
  b20 (roof_8), b21 (roof_9).
- **Rake boards** (roof_2/4/6): the roof's edge as a board along both slopes of each gable, 2 px past it, 3 px over the
  tiles, 4 px deep, in the roof's edge colour (`_pitch`'s rake), so the gable reads finished from the side and above.
- **Balconies** (middle_2 brick, middle_8 blue, middle_14 grey: one drawing in three colours): the flat U is taken off the
  front, the wall behind filled with the storey's own wall (pixels a whole number of brick courses away, only the
  wall's own colours), the double window opened into a glass door to the floor; the engines build the slab (its edge the
  art's slab rows, its floor the art's tiles from above), a stone parapet on its three open sides (the art's cap and
  outline colours, coursed), two brackets under it (`_balc`, `bN_balp/bals/balf.png`). Gamma: b2 (Novak's Law Office on
  Main Street), b57, b58.
- **Ledges**: every middle floor's sill course (rows 118..128) and the top floor's cornice under the roof (rows 0..8) stand
  3 px out of the wall, wearing their own rows of the front; not where a storefront's sign hangs over them (`_ledge`).
- **Door steps**: the classic law and doctor's offices' two steps (rows 76..96 of the storefront) as boxes 5 and 10 px out.
- **Sign boards** above the roofline: the board has a thickness (its outline colour in layers behind it) and a grey back,
  so from behind it is not the art mirrored.
- Checks: `gradle :desktop:preview -Dmode=filament-town -Dshots=shop_ -Dshops=b2,b0` renders each box from the front, both
  three-quarters, both ends, behind, above and low.
- Still to do: the signs' lower halves (painted on the front) and the storefront cornices of the one-storey shops are
  still flat; the ledges stop at the box's corners (the side pictures carry their bands flat).

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
| ~~`landmark_police`~~ | 3 | **done** (see above); was: a **curved wing** (a rounded corner with a curved parapet and windows following the curve) beside a square block | the curve as a cylinder segment: a new geometry type (a box with a rounded end), its windows mapped around it |
| `fountain1_*`, `fountain2_*` | 8 | **round**: a circular basin (a cylinder in perspective) and a statue | basin as a cylinder ring drawn in the street's perspective, water as the same AGSL water we have; the statue as an upright card |
| `characters` | 8 sheets | sprites, not geometry | stay 2D cards (as now); only their shadows and lighting are 3D |

## What is left, re-checked after the pitched, front-gable and hipped roofs (2026-11-18, by eye from contact sheets)

Ranked by how much each still needs, now that the roof machinery (near-slope-as-texture, front gables, hipped roofs,
multi-part models) exists.

| Sprites | Count | What it still needs | Built on |
|---|---|---|---|
| ~~`houseTerraced_*`~~ | 30 | **Done** (2026-11-18): their front is the END of a single-pitch roof rising across it (low left, high edge running back on the right), the slab that roof from above, a lower side wing along the right. `make_buildings.py terraced()` makes the facade a true elevation (in a recess's columns the rows its top hides are put back, so every wall meets the ground on row 461), the slope from above, the wing's front and top, side walls from the upper storey's siding, steps as boxes and the planter or pot as cards (per design: `TERRACED`); the engine draws the roof as a front gable with its apex at one side (`ModelPart.apexX`, only its left slope). Terraced lots wear them (`town-streets.ts`); eta's two streets have none (vale's has one). `make_buildings.py --showcase`, `-Dmode=models -Dstreet=4` | front gable, `build_model` |
| `houseEstate` (country, Japanese, modern, one-story) | 4 | Hand-modelled each, as a list of parts: **country** a front-gable main volume, a lower side wing with its own roof, a wrap-round deck with railings and steps; **Japanese** a front gable whose lower floor has a **pent roof** (a lean-to strip along the facade, the same monopitch) and a side annex; **modern** a monopitch main block beside a **flat-roof terrace** with a pergola, parasol and railings (props on a deck), and a stair up the side; **one-story** a front gable with a **second, lower gabled garage** standing forward of it (a cross gable, as the school's) | villa models, front gables, decks |
| ~~`landmark_police`~~ | 3 | **Done** (2026-11-18, see above: new `cyl` and `wall` parts). Was: the **curved wing** (a quarter-cylinder with a curved parapet and windows round it) still needs a new shape; the rest is a tall square block, a lower wing, a **flat roof drawn from above** (cut off, our roof with the parapet, spotlights and the hipped skylight as props), entrance steps | new: cylinder segment |
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
showcase street 7 (`--showcase`, `-Dmode=models -Dstreet=7 -Dtall=600`); estate-tier lots wearing either sprite are
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

## Civic buildings: the fire station, the hospital, the post office (2026-11-18)

**Done** in a showcase row (`make_buildings.py --showcase DIR`, `-Dmode=models -Dstreet=8 -Dtall=900`), not on the
exported streets (landmarks are not lots there). Builders in `tools/buildings_civA.py` (`fire_station`, `hospital`,
`post_office`); `build_model` asks that module first. Each volume stands at the depth its foot row in the art gives it
(a part drawn lower stands further forward by as much) and wears its front as drawn; what the art draws from above in
a front is painted out of it and built as its own part.

- **New part types** in models.json, each with a simple drawing in `BuildingPainter`: `slope` (a sloped slab: its
  front edge's height on one depth, its back's on another, a face `th` deep wearing `ft`, its top wearing `tt` back row
  first: the post office's awning) and `decal` (a picture lying flat at a height, `p` its piece: the helipad, a road
  arrow), and `prism` (an upright prism over a plan polygon `pts`, x and d its bounds: its first face wearing `ft`,
  its top `tt` over the bounds, `hide` the faces against other parts: the hospital's chamfered corner and its coping).
  A box may say `abut: "l" | "r" | "lr"`: that side stands against a neighbouring part (a taller volume), so a
  painter drawing back to front leaves it out; volumes are split where a taller one stands beside them so every side
  is either wholly open or wholly against another. `ModelShape.ridge` counts only gables and hipped roofs (a decal is
  no roof).
- **Fire station** (`landmark_fire_1`): the hose tower 32 forward of the engine bay, its two storeys (the upper
  overhanging the lower by a few pixels, as drawn) under a hipped roof inside a rim (the art's roof from above), the
  front face its rows between the hips every other row, a flat top with a low lantern; the door's canopy as a slab (its
  top from above, its edge) 72..78 up (its face, 32 before the tower, is drawn on rows 366..372: its foot would be row
  444), two beacons standing on it as cards (78..109), the landing, the step and their cheeks. The art shows only the
  door's foot under the canopy; the door is rebuilt to its full height (8..58: its kick panels as drawn, two glazed
  panels a leaf, the upper mirrored, its jambs and head as drawn), the bay's own brick over it up to the canopy. The bay: its wall, its parapet (face and top from the art, sides and back plain), its roof
  the art's diamond tiles (40 x 20) laid over the whole top, the upper block standing set back on it with its own top
  and two vents as low boxes. The tower is split at the bay's front and at the bay's parapet height.
- **Hospital** (`landmark_hospital_1`): the main block, the middle block 96 forward and taller, the emergency wing 32
  forward and tallest, all back to one line 256 behind the main block's front (its back coping's top is on rows 41..69);
  each a flat roof (the art's grey roof laid over it) behind a 32-wide coping (its face from the art). The art's slanted
  strip at x 256..288 is a chamfered corner in plan, from the main block's front at x 256 to the middle block's at x 288
  (96 forward): a `prism` 101 long wearing the middle block's courses and two tall narrow windows (a light of the
  front's grid each, as the strip shows two), its coping a second prism along it. The art draws the strip's coping
  meeting the main block's coping at its back end; here it stays at the middle block's height (40 higher) all along.
  The entrance under the canopy (hidden in the art) is a glass front between the glass side doors the art shows. The
  canopy on its two pillars, the blue cross a card on its edge, the arrow a ground decal, the helipad a decal on the
  middle block's roof where the art draws it (54..224 back; laid round, 170 x 170, where the art squeezes it), the
  plant room (160..244 back, standing over the back coping, which is split round it) and its fan unit (202..232) on
  the main block's roof, the slab over the ambulance bays. Split: the middle block into its front part, its part behind
  that before the main block (both against the chamfer on their left), its part beside the main block and its part
  above it; the wing at the middle block's roof.
- **Post office** (`landmark_post_1..4`, one shape in four colours): the shop storey with its parapet (face and top
  from the art), its roof the art's floor; the upper block set back on it, its top the art's with the three hatches and
  two vents painted out and built as low boxes; the ladder up its front as a card drawn in the art's own colours; the
  mast a card cut from the art (its pole, its cross-bar with the orange tip and the stays under it), standing at the
  upper block's front right corner where its foot is drawn (row 76: 72 back); the awning as a `slope` (top from the art, the sign its face), the wall behind it carried on in each row's
  colour; the hanging rods painted out of the front and built as thin boxes from brackets on the wall.
- A window's night light also fills the pale pixels inside each pane joined to its glass (the art's grey streaks of
  reflection, pale stickers and blinds, which showed as dark marks in a lit pane), not the wall's own colours
  (`lit_panes`).
- Checked by eye at day, dusk, night and snow in the classic street. Still to do: the hospital entrance and the fire
  station's door above its foot are reconstructed, not drawn by the artist; side walls are each front's courses with
  its windows (none of these buildings shows a side in the art).

## The apartment blocks and the hotel (`tools/buildings_apt.py`, 2026-11-18)

**Done**: `condo_3_44`, `condo_3_45`, `condo_5_1..3`, `condo_9` and `landmark_hotel_1`, each a list of model parts
(`models.json`) built by `tools/buildings_apt.py` (dispatched from `build()` / `build_model()` in `make_buildings.py`).
Showcase street 10 (`make_buildings.py --showcase DIR`, then `-Dmode=models -Dassets=DIR -Dstreet=10 -Dtall=900`).
Apartment lots whose game sprite is one of the condo blocks wear it (`tools/town-streets.ts`; eta's b17, in the corner
turn's cross street east side, is now `condo_3_45`); the modular condo stacks stay as they were. The hotel is in the
showcase only, as the other landmarks.

- **The front is a true elevation**: `Elev` keeps the art's rows that are wall and leaves out the rows it draws from
  above (the roof, a ledge's top between storeys); every height in the model is then measured on that elevation.
- **Flat roof inside a parapet** (`flat_roof`): the art's floor from above is the body box's top (the back parapet's
  inner face, which the art paints on the floor, replaced by the floor's own rows); the parapet is four boxes the
  coping's width thick: the front wears the front's own top rows (its cornice), the back the inner face as drawn, each
  wearing its coping from above on top. The art's tops are one row per pixel of depth, so the depths come from them
  (`condo_3_*` 124, `condo_5` 86, `condo_9` 142, the hotel 200).
- **What stands out of the wall** (`relief`): a shallow box wearing its own columns of the elevation as its front, its
  top the art's rows from above where it draws them: `condo_3_*` lintels and flower-box sills (3 deep), the door frame,
  the plinth either side of the door (4), `condo_3_45`'s ledge between storeys (6, its top the art's 6 rows);
  `condo_5`'s cream frames (the band under the roof, the ledges, the posts either side of each storey, 4 deep), its
  plinth, the portal round its door (6) and a step out onto the pavement.
- **`condo_3_45`'s TV antenna**: found as what differs from `condo_3_44`'s otherwise identical roof, taken off the
  roof (that roof's pixels put back) and stood on the floor as a card, its foot where the art's mast ends.
- **`condo_9`** (drawn obliquely): one box; its front the front strip (columns 18..133), its side the side strip with
  the 32 rows its higher foot hides put back in its brickwork and its plain wall between the pipes carried back along
  the depth; the roof the art's narrowed to the front's width (the stretch over the side strip taken out); the mast a
  thin box on the left parapet (its foot, drawn in the parapet's shade, given the mast's own colours: the engine shades
  it); the Juliet balcony a slab, two end rails and a railing card open between its balusters.
- **The hotel**: the pilasters stand 4 out of the side bays and the middle bay is set back 32 (their feet are drawn 4
  and 30 rows higher). Because the engine draws parts in list order, the walls are cut so that no face can be painted
  over a wall in front of it from either side: the two blocks behind the middle bay's front line, the middle bay, the
  two walls in front of them (the bays' fronts with the pilasters' between), the pilasters (4 deep), the pilasters'
  copper running back along the roof 6 proud of it (the front's green band carried up), then the HOTEL sign: taken off
  the roof (what is not the roof's own colours, plus the ink between its topmost and lowest pixels in each column; the
  roof and its shadow under it repainted from the roof's rows below) and stood as a card on the two middle pilasters'
  copper, as far back as the art draws its foot. Its night light is in the atlas's glow picture (the letters' faces and
  bulbs); the classic renderer lights only panes, so it shows only in a renderer that reads the glow.
- Side walls: each row the front's wall colour clear of windows, the front's own windows laid along the depth
  (`side_windows` / `side_windows_lit`; `condo_5`'s teal brick shares the glass's blue-greys, so its panes are placed by
  hand with a lightness threshold each: `lit_panes`).
- Still to do: `condo_5`'s barred ground windows as grilles standing out (they are flat on the front), the side bays'
  window hoods on the hotel as relief, the hotel's sign glow in the classic renderer, and a lighting map for these fronts.

## After merging the five groups (2026-11-18)

- Showcase streets (`make_buildings.py --showcase DIR`, `-Dmode=models -Dstreet=N`): 5 one of each estate, 6 the
  one-story and country estates, 7 the Japanese and modern estates, 8 civic (fire station, hospital, post office), 9 the
  police station, 10 the apartment blocks and the hotel.
- Part types in models.json and their `ModelPart` numbers: `box` 0, `gable` 1, `hip` 2, `rail` 3, `decal` 4, `slope` 5,
  `prism` 6, `cyl` 7, `wall` 8. A box's sides against another volume are one key for everyone, `hs` (`l`, `r`, `lr`;
  bit 1 left, bit 2 right); the civic group's `abut` became `hs`.

## Final audit of every modelled building (2026-11-18, `tools/buildings_wf-sprites.py`)

Every building the data describes (showcase streets 2 to 10 and the app's street.json, whose one model is condo_3_45)
rendered from the left, middle and right of the view at day, dusk, night and snow, and each part's front, side and top
pictures laid side by side from models.json and the atlas (a throwaway script) to compare every side with its front.
Fixed, in the builders:

- **Terraced houses: the side wing's side.** It was each row of the wing's front averaged across, which smeared its
  upright corrugated panels (or boards) into horizontal streaks (in design 5 the potted plant became a green band). It
  is now one rib period of the wing's own front repeated along the depth, the plant (its own card) painted out
  (`ribbed_side` in `tools/buildings_wf-sprites.py`, a module of its own so the groups' files stay as merged;
  make_buildings imports it with importlib).
- **Japanese estate: the main house's and the left annex's sides** wore brown vertical boards: the corrugation's
  period was taken at x 140 on row 322, which is inside an upper window's bay (its sill). Now the plain corrugation
  between the bays (row 300, x 230..249), so the sides are the front's grey corrugated iron; the walls' colour likewise.
- **Hospital: the roofs** wore the plant room's doors and windows tiled over them (the floor sample began on row 110,
  inside the plant room's front, which ends on row 165). Now the floor under it only. **The emergency wing's side**
  carries the front's louvred band (a fin every 16 columns) instead of a plain dark band.
- **Post office: the side** took its courses from the dark corner posts (the four colours' sides were all one dark
  grey); now from the pilasters just inside them (the wall's own maroon, green, light grey or grey), with the band under
  the upper windows (orange in two colours) carried round.
- **condo_9: the side's plain wall** repeated a period that held the back corner's outline (a dark line every 14
  columns down the brick) and the art's stepped shading under the coping (dark teeth); the period is now clear of the
  outline and the shading rows are each their own colour.
- **condo_5_1..3: the side's ground storey** now has the front's barred window, as its upper storeys have their windows.
- **The hotel: the sides' ground storey** was the doors' green smeared along the depth (the side's courses were taken
  from the bays' columns, which on the ground storey are doors); it carries the storey above's brick down.

Checked and left as they are: the villas, clock towers, school, the one-story and country estates, the modern estate,
the fire station, the police station and condo_3_44/45 have sides, gables and roofs that match their fronts, and
nothing floats or shows sky through it in any of the three views; condo_3_44's skylight is drawn flat on the roof (a
glass panel in the floor, so it stays flat). Left to do: the Japanese estate's upper bays and long window do not light
at night; the hotel is taller than the preview's 820-pixel frame (render it with `-Dtall=1150`).

## Filament: every building (2026-11-18)

Filament now draws every modelled building, not only the shop boxes: the villas, the clock towers and the school, the
terraced houses, all four estates, the fire station, hospital and post offices, the police station with its curved wall,
the apartment blocks and the hotel. The code is `shared/src/filament/kotlin/scene3d/FilamentModels.kt`; it reads the
same models.json and atlas as the classic. To look at them: `gradle :desktop:preview -Dmode=filament-models -Dstreet=2-10`
writes each building from the front, both three-quarter sides, behind, from above, at dusk and at night, plus a contact
sheet per street (`desktop/build/preview/filament-models/street<N>_sheet.png`).

What draws well, checked by eye at 13:00 and 22:30 on every showcase street:

- Fronts, sides, roofs (pitched, hipped, single-pitch, flat) and shadows on every building; nothing floats or shows sky.
- Backs: they wear the side picture without seams; back gables show only the gable's own cladding and attic window,
  not the tops of the front's windows.
- Clock faces on every side of the towers, with hands; the clocks light at night.
- Signs and the helicopter are two one-sided cards, so they read the right way round from behind.
- Night: the windows light; the POLICE letters, the rooftop spotlights and the HOTEL sign glow in their own colours.
- Data fixes made along the way: the villa balconies are railings, the country house's wing stands on the ground and its
  dormer is closed, the terraced houses' walls have the right colour, the police garage wing has a brick back.

What is still wrong or missing:

- **The hotel is about twice the scale of everything else.** Fixing it needs a per-building scale, which the frozen
  classic would also have to follow; this is the owner's decision.
- Some backs are plain colour: the modern estate's back is flat grey with no texture (no side picture in the data).
- The lower parts of some back gables are filled with a repeated strip (one-story and country house): plausible, not
  the real cladding.
- The police station's barred cell windows on the curved wall light up at night; condo_9 lights a few extra panes; the
  terraced houses show a few lit specks.
- Small: the school's hip wedge is smeared, specks at clock-tower corners and the fire station's T-junction, a faint
  light hairline along the villas' back eave, porch lamps do not light, glass is either fully opaque or fully clear.

## Pack buildings: the modular facade kit (2026-11-19, `tools/buildings_pack.py`)

Buildings assembled from the Modern Exteriors modular kits (theme 5 facade slices, Condo_7, Condo_1/2, office towers) through the existing
pipeline: `compose_facade(spec)` writes the usual models.json boxes (a body wearing the stacked floors, a flat roof inside a parapet from
`buildings_apt.flat_roof`, sill courses as 3 px reliefs, steps and roof props as boxes and cards) and an atlas; `make_buildings.py` builds
`pk_*` sprites through `build_model`. Details, the kit format and what the trial corrected: `PACK_EXTERIORS.md` ("Built"). Filament renders:
`-Dmode=filament-models -Dassets=DIR -Dstreet=0-3` after `make_buildings.py --pack-showcase DIR` (street 3 is the scale street). Output for the
showcase lives in `app/src/main/assets/pack/` (`tools/pack-export.sh`). Not on a town street yet.

Red-brick corner blocks (Condo_4_38/39/40, Condo_6, Condo_3_46, Condo_8): same file, `compose_corner` and friends, showcase street 3 (street 4 is the scale street). The
chamfered corner is a `prism` (roof top only, faces hidden) plus three `wall` parts wearing the art's chamfer columns let down to a level foot; see
`PACK_EXTERIORS.md` ("Built ... item 4").

## Victorian civic buildings, chapel and more pack buildings (2026-11-19, `tools/buildings_civic.py`)

Built through the same pipeline (`models.json` + atlas, Filament renders `-Dmode=filament-models -Dassets=DIR -Dstreet=5-7` after
`make_buildings.py --pack-showcase DIR`): the Victorian town hall, old school, mansard block, big mansion, wings and towers, a haunted mansion, a
windmill and the Grace Chapel with a belfry and spire. Recipes: a hipped roof helper (`hip_roof`: slate face every other row, sides tiled anew, the art's
flat top or ridge), the clock tower as a square box under a hip to a flat top, a front-gabled wing as the villas' gable (slopes on quads, 300 deep) with the tower
at its back, a point-topped hip for turrets and spires. Details and limits: `PACK_EXTERIORS.md`. Not on a town street.
