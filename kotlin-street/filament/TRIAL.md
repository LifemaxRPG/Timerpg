# The Filament trial: what it showed

The question: can Google's Filament (a real 3D engine, with a depth buffer, real shadows and a true camera) draw the
street in place of the hand-drawn Compose Canvas, and keep the crisp pixel-art look? Short answer: **yes, it looks
right, but it is not ready to replace the classic yet. Nothing has run on the S25 so far.** That run is the next step.

## What was built

- **Filament 1.81 on the build machine** (`TOOLCHAIN.md`): built headless on Mesa's software OpenGL ES, with the
  material compiler and desktop Java bindings, so renders can be judged by eye just as `gradle :desktop:preview`
  renders are today. The phone uses the same version from Maven Central, so the compiled materials load on both.
- **The street as real geometry** (`shared/src/filament/kotlin/scene3d/FilamentStreet.kt`, about 1,100 lines, plus
  three materials in `filament/materials/`). It covers each shop's front, sign, side and back walls, flat and pitched
  roofs with their gables and end walls, and the rooftop billboards. It also covers the mobile homes (walls, gables,
  roof slopes, lawn, fence), and the ground as one painted tile per street (slab joints, kerbs, lane dashes, the zebra
  and dropped kerbs at the mouth). Lamps, trees, benches and people stand as upright cards. Everything wears the
  existing sprites. No new art was made.
- **Light**: a sun with 4096-pixel cascaded shadow maps (the moon throws no shadows), a sky light, and fog for the far
  blocks. Dusk and night come from the same hour as the classic: glass shows the sky's colour, shop displays glow and
  lantern glass is lit.
- **The same picture as the classic**: everything goes to the screen through CornerWorld's own matrices
  (`LookMatrix.kt`), so at rest the street stands where the classic draws it, and mid-turn it swings the same way.
  `LookMatrixTest` checks this. Light and shadow still use each thing's true 3D position.
- **On the phone**: a renderer switch, a frame-time readout, and a crash guard (see below). The stage keeps running
  everything else (the walk, the camera, people, the sky). It hands each frame's crossroads to `Stage3D`, and Filament
  draws over the stage's sky in a TextureView.

## Side by side (classic on top, Filament below, 2340x1080 each)

| Image | What it shows |
|---|---|
| `filament/images/compare_13h_mouth.png` | Main Street at the side street's mouth, 13:00 |
| `filament/images/compare_19.3h_west.png` | Further west at dusk, 19:20 |
| `filament/images/compare_22.5h_mouth.png` | The mouth at night, 22:30 |
| `filament/images/compare_turn_050.png` | Halfway round the corner |
| `filament/images/crisp_rest_2x.png` | A shop's roof and windows at rest, enlarged 2x (classic left, Filament right) |
| `filament/images/crisp_turn_2x.png` | The corner building mid-turn, enlarged 2x (classic left, Filament right) |

The full set (three hours at two places, and the turn at 0/25/50/75/100%) is written by
`gradle :desktop:preview -Dmode=filament` into `desktop/build/preview/filament/`.

## How crisp

- **At rest, Filament is crisper than the classic.** Every sprite pixel is a hard square (nearest sampling). The
  classic resamples its sprites a little soft, so its window frames and roof tiles are slightly blurred. Compare
  `crisp_rest_2x.png`.
- **Colours**: with post-processing off, the smoke test matched the sprites' palette exactly (0 of 554,882 pixels
  differ). With post-processing on (needed for the lit scene), 94% of pixels are exact and the rest are 1-3 levels off
  in 255. You cannot see the difference.
- **Mid-turn**: walls seen edge-on keep crisp texels. Because there is no anti-aliasing yet, their slanted edges are
  hard stair-steps where the classic's are softened. Mipmaps stop the moire on far, edge-on walls but blur them
  slightly. MSAA or rendering at a higher resolution is the likely fix, and the S25 can afford either.

## Better with Filament

- A real depth buffer: nothing is sorted by hand. Things cross and overlap correctly through the turn, and nothing
  pops.
- Real shadows from the actual buildings, lamps, trees and people. They fall onto the pavement and road and move with
  the sun. The shadows are a little longer and straighter than the classic's painted, splayed ones.
- The dusk lamps' lantern glow and the shop displays read well. The light falls off with the hour the way the
  classic's does.
- The engine opens up what the classic cannot do cheaply: a free camera, real point lights, bloom, and (on the GPU)
  much more on screen. This is the AAA headroom.

## Worse, or missing so far

- **Upper windows**: the classic's glass reflects the actual clouds and the far city. Filament's glass is a flatter
  sky-coloured gradient with a city silhouette. The classic looks richer here.
- **Night**: no lit rooms in the upper windows (the classic shows about seven in ten lit) and no lamp pools on the
  ground. A low sun's glare is drawn under the street instead of over it.
- **Dusk**: shop doors read too bright and white, and the warm backlit edge that the classic paints along the
  pavement is missing.
- **Detail on the homes**: no porches, steps, hitches or chimneys/flues. In the turn the mobile homes look plainer.
  The far cross street's ground has no building shadows on it.
- **Not drawn at all**: weather, seasons and snow, the wet mirror (puddle reflections), cars, birds and air sprites,
  the far-city backdrop as geometry, interiors, villas, terraced houses and estates (they are not on the two trial
  streets).

## Frame times (desktop software GL, NOT the phone)

Measured on a 4-core server with no GPU (Mesa llvmpipe renders on the CPU). Use these only to compare the two
renderers with each other. They say nothing about the S25.

| | Classic (Skia on the CPU) | Filament (llvmpipe) |
|---|---|---|
| Day, per frame | 205-335 ms | 100-200 ms |
| Dusk, per frame | 810-850 ms | 115-260 ms |
| Night, per frame | 320-355 ms | about 100 ms |
| First frame of a street (shader compile) | none | 215-1020 ms |
| Engine start / building a street | none | 35 ms / 90-200 ms |

The median Filament frame is 116 ms over 13 frames. On the phone, the classic runs on the GPU through Android's own
Canvas, and Filament runs on the Adreno 830. **The real numbers can only come from the S25.**

## How to measure on the S25

1. Build and install: `gradle :app:assembleDebug` gives `app/build/outputs/apk/debug/app-debug.apk`.
2. In the game, tap the **"AI" chip** (top left), then the **Renderer** row: **Classic** / **Filament trial**. The
   choice is kept between launches.
3. The same panel shows Filament's CPU time per frame (median and p95), its GPU time (where the driver reports it),
   its start and street-build times, the frame line, and the phone's thermal status and headroom. For the
   comparison, read these with each renderer for a few minutes on Main Street, at dusk, and walking round the corner.
4. If Filament fails to start or stops, the game goes back to the classic street and says why. If it crashes in
   native code before drawing 30 frames, the next launch starts on the classic.

## What the trial did not cover

- Any run on a real phone: speed, GPU time, heat, battery, memory, the TextureView's composition cost, touch while it
  draws.
- The Vulkan backend (only OpenGL ES was used), the planned "Exact / Rich" post-processing switch, and a benchmark
  walk.
- Lamp point lights. Things are drawn through the classic's leaned matrices, so Filament's own light clustering does
  not match the screen.
- The build setup: Filament's library asks for compileSdk 37, but this project is on AGP 8.7 / SDK 34. The library's
  metadata check is skipped for now and its own dependencies are left out.
- Everything listed under "not drawn at all" above.

## Estimate for the full move (refined)

Focused weeks of work, by area, keeping the classic as the fallback until the end:

| Area | Weeks |
|---|---|
| S25 bring-up: measure, upgrade AGP/SDK for the library, SurfaceView vs TextureView, try Vulkan | 1 |
| Street parity: lit rooms, lamp pools as real lights, glare, porches/steps/flues, richer glass, dusk fixes | 2-3 |
| Remaining building kinds (villas and hipped roofs, terraced houses, estates, the clock tower, the gym's balcony) | 2 |
| Weather, seasons, snow, the wet mirror, cars, birds, the backdrop | 3-4 |
| People and animation as cards (sheets, facing, shadows), street life | 1 |
| A true camera for the turn (dropping the leaned-matrix bridge), which unlocks point lights and screen-space effects | 2-3 |
| Interiors | 2 |
| Pixel stability and post-processing (anti-aliasing, own colour grading, bloom), quality settings | 1-2 |
| Tests, preview tooling, retiring or keeping the classic | 1 |
| **Total** | **about 15-19 weeks** |

**Risks retired**: building Filament and its materials; keeping pixels crisp (exact palette is possible); putting
the 3D street exactly where the classic draws it, at rest and through the turn; real shadows from sprite cards; depth
ordering in place of hand sorting; a desktop render loop for judging by eye; a safe switch with a crash guard in the
app.

**Still open**: S25 frame time and heat (completely unmeasured); the library vs this project's SDK level; how much
the TextureView costs; whether to keep the cabinet look (the leaned matrices limit lights and screen effects) or move
to a true camera, which is a look decision for the owner; edge aliasing mid-turn; the size of the port (about 34,000
lines of hand-drawn scene code, most of it art detail that must be rebuilt as geometry or materials).

## Recommendation

**Continue, but gate it on one measurement first.** Install the APK, flip the Renderer switch on the S25, and read the
panel on Main Street, at dusk and through the turn. If Filament's CPU and GPU times fit comfortably within a 120 Hz
frame (about 8 ms) and the phone stays cool for a few minutes, move over area by area, starting with street parity and
the true camera. Keep the classic as a setting until Filament matches or beats it everywhere. If the phone runs it
badly, stop here: the trial cost little, and the classic stays.
