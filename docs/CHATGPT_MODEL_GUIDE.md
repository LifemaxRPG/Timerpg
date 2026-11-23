# Making 3D models for Timerpg (guide for ChatGPT)

You make **landmark buildings and props** for Timerpg, a time-travel life sim for Android set in generated small American
towns from the 1940s to the 2020s. The game is drawn by Filament on a phone (Samsung S25 Ultra). Its look is
**"a cross between SNES and PlayStation 2"**: pixel-art textures from LimeZu's packs on simple, real 3D geometry, with
sharp nearest-filtered pixels, lit and shadowed, and windows glowing at night. Your models must sit next to those
buildings and read as the same world. This guide is the contract; a model that breaks a **must** rule gets sent back.

## 1. The look

- **Pixel art on simple shapes.** Boxes, slabs, prisms and low-sided cylinders (8-16 sides) carry the detail in their
  textures. Do not model what a texture can draw (bricks, panel seams, window mullions, lettering).
- **The pixel grid (must).** The town art is 16 px art shown at 32 px: **one art pixel = 2 x 2 texels**, and
  **one texel = 0.0388 m** (one 32 px tile = 1.24 m; a person is 44 texels, about 1.7 m). So one art pixel is
  **7.8 cm**. Every texture you paint is drawn in whole art pixels (2 x 2 texel blocks) and mapped so that one texel
  covers 0.0388 m on the surface, never stretched or squeezed. A 12 m wall is 309 texels wide.
- **Pixel-art finish (must):** a dark outline one art pixel wide where a shape ends against another (sign edges, window
  frames, trim); a one-art-pixel highlight on top edges; shading in 2-4 flat steps with dithering, not gradients;
  small wear (a few chipped bricks, a stain under a window, cracks in asphalt). Flat single-colour surfaces are the main
  thing to avoid: the Junction Diner's brick, panels and roof were nearly flat and had to be re-skinned.
- **Use our materials (strongly preferred).** The repo has a library of seamless swatches cut from the LimeZu art:
  `kotlin-street/tools/swatches/atlas/atlas.png` with `atlas.json` (each entry's rect in the atlas) and the catalogue
  `kotlin-street/docs/MATERIALS.md` / `kotlin-street/tools/materials.json`. Cut the swatches you need out of the atlas
  by their rects into separate repeating textures (brick, siding, shingles, roofs, asphalt, pavement, floors,
  wallpapers ...). Paint your own only for what the library lacks, in the same palette (take colours from the swatches).
  These swatches are licensed for our game only: use them in Timerpg models, never publish them elsewhere.
- **Colours:** the LimeZu palette is warm and slightly muted with strong dark outlines. Take colours from the swatches
  and the sprite art in the repo (`public/assets/`) rather than inventing new ones.
- **No smooth high-poly models, no photo textures, no blurry filtering, no baked global illumination.** The engine
  lights and shadows the model itself.

## 2. Content rules (must)

- **Complete from every side.** Real backs, sides and roofs: the player walks round everything and turns the camera
  freely. A back with a service door, bins, pipes and a fence is part of the job (the Junction Diner got this right).
- **Original designs only.** No real brands, logos, chain names, trade dress, real places or people. Made-up names
  that sound ordinary (Junction Diner, Haskell's General Store).
- **Era.** Say which years the design fits (built, typical decades) and, if useful, give variants by decade (a 1950s
  sign and a 1980s sign; a closed/boarded state; a reuse).
- **Small-town America,** ordinary scale: it should look like it belongs on a real Main Street or roadside.
- Signs and lettering are pixel letters on the art-pixel grid, readable, no fonts from outside the art.
- **Naming:** set `"naming": "owner"` in model-info.json for standard buildings that repeat across towns (shops, strip
  malls, diners, barbers, gas stations, motels); they get the owner's name painted by the game. Unique buildings
  (landmarks, monuments, one-of-a-kind places) use `"naming": "fixed"` and keep the name you paint.
- **Business names are painted by the game per town and owner** (for `owner` models). For every sign that shows the business's name, list it in
  `model-info.json` under `signs`: `id`, `texture`, `rect` [x, y, w, h] in texels, `lines` (1-2), `maxChars`,
  `face` (era style), `ink`, `paper`, `glow`. Paint a sample name on it for your renders (the game paints over the
  whole rect). Words that aren't a name (DINER, TICKETS, PAWN, CLOSED) stay baked in the art. Format: docs/SIGNS.md.

## 3. Technical format (must)

- **glTF 2.0 binary (`.glb`)**, metres, Y up, the **front entrance facing +Z**, the origin at ground level in the
  **middle of the building's front edge**. Give the footprint (width x depth) and heights in `model-info.json`.
- **Textures:** PNG, nearest filtering, repeat wrapping, power-of-two sizes where you can (they may be packed into an
  atlas). No normal maps needed.
- **Materials, named by role** (the game treats them by name):
  - `glass_*` window glass (the game lights it at night), `glow_*` signs and lamps that shine at night (emissive),
    `sign_*` sign faces, `roof_*`, `wall_*`, `ground_*` (lot paving, painted lines), anything else as you like.
  - At most about 12 materials per model; merge meshes by material.
- **Budgets:** an ordinary building 1,500-5,000 triangles, a landmark up to ~12,000, a prop under 500. No hidden
  geometry inside walls.
- **Doors:** an empty named node `door_main` (and `door_back`, ...) at each entrance, at ground level just outside,
  facing out. The game puts "Go inside" there.
- **Collision:** an extra mesh named `collision` (boxes only, a few dozen at most) covering what the player must not
  walk through, or say "use the walls" in the info file. Box each thing a walker must go round **from the ground to
  its top** (a pump, a sign's post, a bin), not just its foot or plinth, and list every box in `model-info.json`
  (centre, size, group, attachment): the game reads the boxes from there (boxes that touch at their corners cannot be
  told apart in the mesh).
- **Ground at zero:** the lot's paving and asphalt stand at y 0 or above (a kerb step up from the asphalt is fine;
  asphalt laid below 0 sinks under the town's own ground). The game lifts a model whose forecourt is below 0.
- **Interior:** a clean shell (floor, walls, ceiling, counters that are part of the building) with no furniture: the
  game furnishes rooms from its own LimeZu furniture pieces. Put the interior floor at the entrance's level.
- **Lot dressing** (benches, bins, parking lines, planters) as separate nodes under `lot`, so the game can keep or drop
  them per town.

## 4. What to deliver

On a branch `codex/<model-name>` under `prototypes/<model-name>/`:
- `<name>.glb`, `model-info.json` (name, era, footprint and heights in metres, triangles, materials, door nodes, the
  swatches used),
- the source (Blender file or script) so it can be rebuilt,
- renders: front three-quarter by day, blue hour or night (lit windows and signs), the back, from above, and one at
  person height; state that they are Blender renders, not game captures,
- a short README with what it is and anything unfinished.

## 5. Checklist before you push

- Every texture on the 2 x 2 texel art-pixel grid at 0.0388 m per texel; nothing stretched.
- Outlines, highlights, stepped shading and wear on every visible surface; no flat single-colour walls or roofs.
- Our swatches used where the library has the material.
- Back, sides and roof finished; nothing floating; no sky through gaps.
- No brands or real names. Era stated.
- `.glb` in metres, Y up, entrance +Z, origin at the front edge's middle; materials named by role; doors, collision and
  `lot` nodes present; triangle budget kept.
- Lot ground at y 0 or above; every collision box full height and listed in `model-info.json`.

How the game loads these files (scale, roles, lighting, collision, doors, the price panel): `kotlin-street/docs/LANDMARKS_GLB.md`.

## 6. Delivery is only done when it is on GitHub (must)

- Push your branch, then prove it: run `git ls-remote origin <your-branch>` and paste the line it prints in your
  final message. No line = not delivered.
- If the push fails (no credentials, an expired token, a network error), say so plainly and ask the owner for access.
  Never report a model as delivered from your local copy alone.
- Never commit a token, password or key to the repository, and never write one into a file in it.
