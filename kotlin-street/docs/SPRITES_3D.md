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
