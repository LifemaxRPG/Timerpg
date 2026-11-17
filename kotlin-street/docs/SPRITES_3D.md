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
| `villa_1..5` | 5 | a **front-facing gable** (the triangle end faces the street), a balcony and a small gable roof over the door: a gable prism turned 90°, the balcony box we already have, a second small prism. Next: the houses' pitched-roof machinery (HouseView, the slope strips, the gable) turned 90 degrees; the villas' gable triangle is a true front elevation and can be used as drawn |
| `landmark_clocktower` | 2 | a tall tower box with a **hipped roof** (four sloped faces, a pyramid on a box) |
| `landmark_school` | 1 | long box, **hipped roof**, entrance steps and a central gable: hipped roof + steps box |
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

## What this means

- **Hardest overall:** the terraced houses (another projection) and the estates (many volumes), then the police
  station's curve and the fountains' round basins. Those are better rebuilt as engine geometry wearing the sprites'
  materials (as was done for the gym's balcony) than converted from the pictures.
- **Biggest win for the least work:** the pitched roof. It unlocks 160 small and medium houses (and the villas, school
  and clocktower with the gable and hipped variants), which is what the residential streets need. **Done for the small
  and medium houses**: Orchard Lane is now a street of mobile homes. Villas, estates, terraced houses and apartments are
  still stood in by condo fronts (on the exported streets only one: b17, an apartment block). Main Street's own roofs
  (`roof_2/4/6`, `roof_8/9/11` are pitched in the game) could use the same machinery next.
- **Cheap volume:** ground decals and upright prop cards (about 115 sprites) mostly need placement rules, not new
  rendering.
