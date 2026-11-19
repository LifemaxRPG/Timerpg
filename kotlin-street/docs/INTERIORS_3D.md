# 3D interiors from the Modern Interiors sprites (trial)

The question: can LimeZu's Modern Interiors pack, drawn flat in its 3/4 top-down way, furnish real 3D rooms inside our 3D
buildings, in the town's look (sharp nearest-filtered pixel art on simple lit geometry), instead of flat 2D interiors?
**Short answer: yes. A small-town diner was built from 25 of the pack's pictures with no new art, and it reads as the pack
come to life from every side.** These are desktop renders only (Filament on the build machine's software GL). Nothing has
run on the S25, and nothing is wired into the game yet.

- Code: `shared/src/filament/kotlin/scene3d/RoomKit.kt` (pieces, walls, floor, ceiling: no engine calls, it makes
  `PropMesh`es that `PropLayer` uploads), `desktop/src/filament/kotlin/FilamentRoomPreview.kt` (the diner and its renders).
- Pictures: `tools/cut_interiors.py <Modern_Interiors_v41.4.zip>` cuts the 25 PNGs into `app/src/main/assets/room3d/diner/`
  (108 KB). It reads the zip that `packs/join.sh` rebuilds in a scratch folder. The raw pack never goes into the tree.
- Renders: `gradle :desktop:preview -Dmode=room` writes `build/preview/room/`: `diner_<view>_<day|night>.png` for seven
  views (eye level, the high game camera, the opposite side, a booth close-up, a corner, the counter, straight down),
  `pieces_<name>.png` and `pieces_sheet.png` (each piece beside its sprite at 4 screen pixels a texel). `-Dhour=13` or `22`
  renders one hour only.
- Test: `gradle :desktop:crossroadsTest --tests RoomKitTest` checks that every piece is built from the shipped pictures,
  stands on the floor at its footprint's height, and keeps its texture coordinates inside its own pictures.

## Scale

The town's art is the **32 px** scale, not 48: a tile is 32 px = 26.29 street units (`TEXEL`, docs/TOWN.md), the character
sheets are 32 x 64 a frame (1792 px wide), and a person stands 44 px (about 1.7 m, so a metre is about 26 px). The pack's
32x32 folders match that exactly. **One art pixel is one texel everywhere.** Furniture is as big as the art draws it, at
the buildings' and people's own density.

Where the art is too short for the person beside it, a plain row of the picture is repeated; a texel is never stretched.
Examples: a counter's pink panel row raised to a 1 m counter, a booth's seat front raised to seat height, a fridge's plain
top carried to a fridge's depth. The pack draws a few things much too big for 3D: a plate of food is 28 px (over a metre)
across. Those come from the pack's own **16 px** drawing of the same item, which is the artist's pixels at twice the
density. Nothing is resampled.

## How each kind is built

The pack's 3/4 view draws the top of a thing in the rows nearer the picture's top (its depth about 1:1) and its front in
the rows under them (its height about 1:1). Every piece below is that split, written down per design as a few numbers.

### 1. Box furniture (the top on a box's top, the front on its front)

`RoomKit.Block` cuts columns x0..x1, top rows t0..t1 and front rows f0..f1, and sets a depth and height. Each one only
repeats a plain row to reach them. The sides and back are filled from the picture's own material, so no face is blank
and nothing is mirrored:

- **Sides:** each row takes the front's column just inside the outline, with the outline's colour along both upright
  edges. A cabinet's side gets its own wood, panel lines and plinth.
- **Back:** each row takes its commonest colour (the material without handles or drawers), outlined.
- **Hem option:** a hem (a tablecloth) puts the front's middle columns on all four sides.

| Piece | Sprite | Cut |
|---|---|---|
| counter | Ice Cream 100 | top slab (rows 8..28, edge 28..33) over a cabinet set 2 px back, its pink panels raised to 27 px (1 m) |
| ice cream display | Ice Cream 57 | glass top from above (glows at night), striped front raised to counter height |
| fridge | Kitchen 158 | 32 x 26 x 52 px, the plain top row repeated to depth |
| stove | Kitchen 149 | burners and pan from above. The pan's handle over the edge is a one-texel board |
| kitchen counter | Kitchen 121 | wood top carried to 24 px deep, doors raised to 27 px |
| "jukebox" | Basement 218 | a vending/arcade machine front, its coloured lights and screen glowing at night (the pack has no jukebox) |
| side table | Living Room 65 | as drawn |

### 2. Few-part shapes (a handful of boxes, rings and legs wearing the picture's parts and colours)

| Piece | Sprite | Parts |
|---|---|---|
| booth | Generic sheet (192,344) red sofa | back block (rail from above, backrest face) and seat block (cushion from above, front raised to 12 px) |
| tablecloth table | Kitchen 336 | the cloth's top and its hem on all four sides (a `Block` with `wrapSides`), four dark legs below it |
| round table | Basement 2 | the oval top laid flat, cut round by the art's own outline (masked), a 24-facet rim in the art's band colours, four legs in the art's leg colours |
| bar stool | Basement 105 | seat block (top and front from the art), four legs |
| chair | Kitchen 280 | the art is a side view: its slats turned into the seat seen from above, the post's colours row by row on a back board, four legs |
| table lamp, pendant | Living Room 75 | the shade's front columns, mirrored round a frustum (square texels: rows as tall and columns as many as the shade's height and round), glowing at night; foot and stem, or a cord |

### 3. Billboards and flat things

Plants (Living Room 13, 14, 16), the coffee machine (Kitchen 178) and people stand as cards turned to the camera, like
the town's people. They are lit and cast shadows. When the camera looks steeply down they lean back, up to 60 degrees, so
a top view never sees them edge on. Under the game's usual 3/4 camera they stay upright. Plates of food (Kitchen 385 and
387, 16 px) lie flat on the tables and counter.

### 4. The room

- **Floor:** the floor art's 96 x 64 block (Room Builder floors, the grey checker) laid in its own tiles.
- **Walls:** the wall art's middle tile (cream over green wainscot), 8 texels thick:
  - **Face:** the art's 50 face rows with its plain cream row carried up to 78 px (3 m).
  - **Top and outside:** the cap is the art's white top band. The outside is each row's commonest colour.
  - **Pictures:** pictures, the door (Condominium 85) and the window frames (Generic sheet) are painted into the wall
    picture, so there is no z-fighting.
  - **Openings:** the window's glass is cut out, so the room sees out and the sun comes in. Each opening is lined (sill,
    head, jambs). The kitchen partition has a real pass with a shelf and a door opening.
- **Ceiling:** it always throws its shadow (an invisible shadow caster), so daylight comes in only through the windows
  and falls in patches on the booths and floor. It is drawn only when the camera is inside the room under it (the eye
  view). From above it is left off.
- **Sims-style cut-away:** every wall whose outside faces the camera is swapped for a 12 px stub (the skirting and a
  little more). It still throws its full shadow, so the light does not change as the camera turns.
- **Night:**
  - Five pendants and the kitchen tube are spot lights with their own shadows. The table lamp and the machine are point
    lights.
  - Lamp shades, the machine's lights and the ice cream display glow through the street material's glow mask.
  - The sun is off, and a dim blue sky light comes in.

The diner is 42 pieces: 12 box, 20 parts, 5 cards and 5 flat, plus two people for scale. It draws in 52 batches and 1,524
triangles, so it is very light.

## What looked wrong and was fixed (judged on the renders)

- **Mirrored pictures.** The street's world is mirrored against Filament's right hand: looking along +z, +x is on the right.
  A piece facing (fx, fz) must run along (-fz, fx) for its picture to read the right way round. The first renders
  had every front mirrored, which the side-by-side caught.
- **Blurry texels in close-ups.** On the desktop's software GL, 16x anisotropic filtering blurs a *magnified* texture
  despite `MagFilter.NEAREST`. `PropLayer` now takes an `anisotropy` argument; it defaults to 16, so the town is unchanged.
  The room preview passes 1. The phone's GPU should not do this, but **close-ups in other desktop previews are probably
  softer than on the phone.**
- **Lamps lighting only the middle of the picture, and blocky light pools.** Filament finds a pixel's lights by its depth
  and screen cell (froxels), from its *own* camera. That camera has to agree with the `look` matrix in three ways:
  - **The same near**, 2: the look's depth is `n / w`.
  - **A far plane of a million**, as the look's depth is infinite.
  - **A projection with its x turned round** (the mirrored world).
  - **The light range:** `setDynamicLightingOptions(10, 3000)` is also needed, because Filament's default light range
    is 5..100 of its units, a few feet of ours.
- **Missing indoor shadows.** With the camera's far at a million, the sun's shadow cascades spread over a million units.
  They need `shadowFar` (1600) to span the room. **The town has no point or spot lights yet. When it gets them (shop
  windows, street lamps' pools), the same camera rules apply there.**
- **Smaller fixes:**
  - A kitchen counter's front was raised by repeating its outline row. It now repeats a panel row.
  - The lamp's shade had squashed texels and outline seams. It is now cut to its own height and round.
  - The lamp caps' pictures were smaller than the caps and read the atlas beside them. The test now checks texture
    coordinates.
  - Plates were over a metre wide. They now come from the 16 px drawing.

## Honest judgement

- **Good:**
  - Box furniture is close to free and looks right from every side. The fridge, counter, display, stove, cabinets and
    machine are the pack's own pictures standing up, crisp, lit and shadowed, and their filled sides and backs pass as
    the same object.
  - The booths, tables, stools and chairs read well from the high game camera and at eye level.
  - Night is the strongest look: the pendants' pools on the tables and the glowing shades.
- **So-so:**
  - Cards (plants, people) are fine from the 3/4 camera, but in a top-down view they lean and look like stand-ups.
  - The round table's top keeps the art's front rim drawn on it (a lip on the near edge).
  - The pendant shades look like jelly domes from above.
  - Modular counter pieces show the art's outline at each join (as the pack does).
- **Not right yet:**
  - The pack has no jukebox (a vending machine stands in).
  - Daylight patches are small at this sun angle, and the sun is fixed in the preview, not the game's.
  - The windows look out on a plain sky.
  - People's cards are standing figures only (nobody sits).

## Coverage of the pack (an estimate, not a measurement)

The 32 px shadowless singles are 5,330 pictures across 24 themes. Many are colour variants of one design: beds come in
about 10 colours, sofas and chairs in 4 to 8. A quick script sorted them by how much of their bounds they fill and their
size:

| Group | Share (estimate) | Roughly | Notes |
|---|---|---|---|
| 1. Box furniture | 40-45 % | 2,100-2,400 | beds, counters and counter segments, cabinets, shelves, bookcases, dressers, desks, appliances, crates, displays. A spec is a few numbers (columns, top rows, front rows, a repeat row) per *design*; colour variants share it. Rugs, mats and tablecloth tops in this group are flat floor decals, not boxes |
| 2. Few-part shapes | 15-20 % | 800-1,000 | chairs, stools, tables, lamps, toilets, sinks, bathtubs, racks, instruments. Each family needs a small builder (chair, table, lamp ... as in RoomKit), then a spec per design |
| 3. Cards and flat things | 25-30 % | 1,300-1,600 | plants, clutter, food, dishes, clothes, toys, small decor: no geometry work, only a choice of card or flat and what it stands on |
| Hand-built or special | ~10 % | ~500 | drawn at a diagonal (some arcade machines, the basement's angled pieces), multi-tile modular sets that must be assembled (sofa segments, bunk beds, counter runs), wall-mounted things (shelves, clocks, pictures, TVs on walls: wall decals or thin boxes on a wall), animated objects (fireplaces, screens) |

Rough effort, an estimate:

- **Box furniture:** about 5 minutes a design by hand. Most of the split (where the top ends and the front begins) could
  be found by a tool from the outline row, so it could be largely automatic.
- **Few-part families:** a day or two each for about 10 families.
- **Cards:** essentially free.
- **The whole pack:** a few weeks of focused work.
- **Furnishing the town's 92 interiors with a few dozen designs each:** a week or two.

## Next steps

1. Wire one room into the game: "Go inside" opens it at the building's door, inside the town's scene. TownRun and
   FilamentTown are other agents' work, so that is a hand-off.
2. Specs as data: a `room3d/pieces.json` per design (kind, rows, heights, repeat rows), written by the cutter with the
   top/front split guessed from the art, and checked in a contact sheet beside each sprite like `pieces_sheet.png`.
3. More few-part builders (sofa sets from modular segments, beds with pillows as boxes, toilets and sinks, bunk beds).
   Sitting poses for people.
4. Interior layouts from `interiors.json` (the 92 rooms the game already describes), using the pack's room builder walls
   and floors per building type.
5. Measure on the S25: a room is about 50 draws and a few thousand triangles, plus 5-8 shadowed spot lights, the costly
   part. Give the lights a quality setting (shadowed pendants versus unshadowed) only if the phone needs it.
6. Fade walls (screen-door, as the street's cut) instead of the hard swap to a stub, and cut furniture that blocks the
   player from the camera.
