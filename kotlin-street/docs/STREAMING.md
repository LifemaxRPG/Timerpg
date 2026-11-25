# Streaming and the far view (2026-11-25)

The capital is no longer held whole. What is resident in full is what is near the camera and the player (and where the
player will be in a few seconds); everything else exists only in a far form that costs a few MB for the whole town. Past
the town the world's far view (WorldFar, 172 km) and now a horizon ring carry the eye to the edge of sight. The chain,
near to far, is GTA's: full detail, a coarse stand-in per cell, a coarser one per district, lights as dots at night, and a
painted horizon.

Out of town the world itself streams round the player (the far view re-planned, the near terrain, the cars' ground,
the capital kept on the skyline): WORLD_STREAM.md.

Background and sources: OPTIMIZATION_RESEARCH.md sections 2 (render distance and open worlds) and 6; the budget it
serves: MEMORY_AUDIT.md ("The memory budget guard"). Every number below is **desktop** (the build server, Mesa llvmpipe,
software GL) unless it says otherwise. Filament's own count on the desktop matches the phone's to within ~2%
(MEMORY_AUDIT.md), so the MB figures are a good stand-in for what Filament holds on the S25; **the times are not the
phone's** and nothing here is measured on the S25 yet.

---

## 1. The chain

| distance from the eye (street units, ~21 a metre) | what is drawn | code |
|---|---|---|
| near (to the haze line + 400, ~dist + 2000) | buildings in full, yards' near versions, chunks' 3D greenery, real trees | `FilamentTown`, `TownStream` |
| to 5200 | far cells: each 900 x 900 cell's buildings merged into boxes wearing pictures shrunk from their own | `TownFar` (cells) |
| 5200 to 24000 | districts: 3 x 3 cells as one mesh, pictures shrunk 4x more | `TownFar` (districts, "slod") |
| night, past the detail line | lit windows and street lamps as fixed-size pixel dots with a stepped halo, one draw | `FarLights`, `far_lights.mat` |
| far trees | camera-facing quads wearing one of 8 baked views of the tree's own 3D mesh | `TreeImpostors` |
| the world to 172 km | terrain levels, rivers, roads, rail, other towns as boxes | `WorldFar` (WORLD_GEN W4/W5) |
| 167 to 640 km | the horizon ring: a baked panorama drawn by the sky | `HorizonPanorama`, `sky.mat` section 7 |

Switches between levels cross over with the inverse screen door (no blend, no pop) over `TownFar.SWITCH_SECONDS` (0.3 s),
with slack either side of each line so a camera on the line does not flicker.

### Step 1: cell streaming (`TownStream`)

Residency is measured from up to four points: the eye, the player, and both moved ahead by the player's eased velocity
times `LEAD_SECONDS` (4 s). With no fast travel all movement is predictable, so a car at 600 u/s (~100 km/h) loads
2400 units ahead along its heading (Zelda TotK's prediction, Spider-Man's speed-adapted streaming).

- Something comes in when it is within its tier's need plus `MARGIN` (300, plus half the lead) of any point, and goes once
  it is `HYSTERESIS` (500) past that from all of them: no load and unload on a line.
- **Buildings**: needed out to the haze line + `FAR_PAST_HAZE` + the far cell's slack. A far cell keeps its boxes until
  every building of it that is wanted is in (no holes); then the boxes cross over to the buildings. Textures are
  refcounted per streamed building (`StreetTextures.owned/release`): a picture shared by several buildings stays until
  the last of them goes. Streamed buffers get their own arena pages (`BufferArena.lane`) so freeing one does not leave
  holes in the town's long-lived pages.
- **Yards**: a lot's near version is let go past its switch to the far version (which stays, baked once for the town),
  and baked again near-only on the workers when it is wanted back, swapped in within `YARD_UPLOAD_MS` a frame.
- **Chunks' 3D greenery** is let go far off.
- Each tier has a pool (`TownBudget.streamBuildingsMb` 260, `streamYardsMb` 220): over it, that tier's margin shrinks until
  it is back under.
- Letting go is spread over frames (`EVICT_MS` 2 ms a frame beyond the first item).

`-Dstream=0` holds the whole town as before (the previews and `GpuBudgetTest`, which measures the whole capital).

### Step 2: the coarser far level (districts)

Past `SLOD_FROM` (5200 from the eye) each 3 x 3 block of far cells is one mesh whose pictures are shrunk 4x more than the
cells' (`SHRINK`): a handful of draws for the whole skyline across town. `-Dslod=0` to compare.

### Step 3: distant lights (`FarLights`)

At night a lit window past the detail line is not a window but a dot of light, and a street lamp past the few real point
lights the same (GTA V's night city). Every light is a quad made a fixed number of screen pixels big in the vertex stage:
a 2 x 2 art-pixel core with a stepped halo, nearest, added over the scene in one draw, fading in with the dark on the
windows' own curve, in a thinner haze than the walls' (a city's lights read through the evening air from across town).
`-DfarLights=0` to compare.

### Step 4: the horizon ring (`HorizonPanorama`, `sky.mat`)

The world past the far view's reach as one texture, drawn by the sky where nothing nearer is (Source's 3D skybox idea,
baked instead of drawn):

- **Bake** (CPU, a town worker): a 4096 x 64 RGBA8 panorama from the eye, azimuth round the full circle by elevation from
  -0.004 rad up, square texels of 0.088 deg. Each column is marched outward from 167 to 640 km over the skeleton's
  heights (step 0.4% of the distance, at least 600 m); the nearest ground that rises over everything before it fills
  the rows between the old and new top, so a near ridge hides what is behind it. A texel holds the biome's colour (snow
  over the snow line, three stepped tones by how the slope faces the eye) and in alpha its distance. Distant towns in
  sight are marked as lit texels. Baked again when the eye has moved 1.5 km or climbed 150 m (the parallax at 172 km is
  0.3 deg a km).
- **Draw**: no draw of its own. `sky.mat` section 7 reads one texel (texelFetch, nearest) in a 64-row band over the
  horizon, lights the ground by the day (warm at dusk, a little moonlight at night) and hazes it toward the sky's own
  colour at that pixel by the air along the way, with the same law as the far view's fog (`WorldAir`: extinction thinning
  with height over the aerosols' scale height, from the eye's height). A town's lights carry farther than its walls.
- **What it looks like**: on the game's clear day (`WorldAir.CLEAR`, 90 km visibility) the ranges 200+ km off are hazed
  to almost nothing, which is what the air would do; on a very clear day (250 km) they show as a pale stepped range on
  the horizon, rose at dusk, dark against the night sky; at night distant towns are a row of warm dots on the horizon.
  It matters most from height (the planes, Flight.kt) and after the weather (phase W10) clears the air.
- Flat planet, as the far view is: no curvature drop.
- `-Dhorizon=0` leaves it out; `-DworldVis=<m>` sets the air in the previews; the world preview's `world_ring`,
  `world_ring_dusk` and `world_ring_night` shots aim at the tallest range, and `horizon_panorama.png` dumps the bake.

### Step 5: tree impostors (`TreeImpostors`)

Each tree look is drawn once from 8 directions round it by a small software rasterizer from its own 3D mesh, a pixel a
texel of the kit's atlas, faces shaded a little by their normals; far off each tree is one upright quad facing the
camera wearing the nearest view (no blend between views: the stepped, sprite-like look, Fortnite's octahedral impostors
kept to the horizontal ring a street camera sees). They replace the pack's crossed cards, so far trees are the same trees
as near ones. `-Dimpostors=0` to compare.

---

## 2. Measured (desktop) vs estimated

### Resident GPU along the route (`GpuRouteTest`)

A fixed route through the capital (gamma), played at 60 Hz paced to the wall clock so the workers get the time they
would get on the phone: down Main Street on foot (60 u/s), then a loop round the town's middle half by car (300 u/s, the
far side at 600), 62,568 street units, 11,484 frames. Filament's whole count every 15 frames.

| | streaming on | streaming off (`ROUTE_STREAM=0`, whole town) |
|---|---:|---:|
| after the build at the origin | 538.2 MB | 1060.0 MB |
| peak along the route | **705.5 MB** (drive-s, 600 u/s) | **1204.7 MB** |
| buildings resident near the peak | ~98 of 283 | 283 |
| CPU ms a frame, median | 1.89 | 2.03 |
| CPU ms a frame, p99 | 18.70 | 5.70 |
| CPU ms a frame, worst | 170.45 | 94.95 |

(Measured 2026-11-25. The whole town grows past its build figure along the route because the drive brings the tree
cells into detail: trees 168.6 MB at the end with streaming off, 31.5 MB with it on.) The radius budget
`TownBudget.ROUTE_MB` is 800 MB; the test fails over it.

Since MEMORY_AUDIT.md's step 4 (the night masks as window pages, 20-byte vertices, the yards' shared atlas; the same
day) the route peaks at **475-478 MB** with streaming on (704.2 with step 4 off: `ROUTE_STEP4=0`), 378.6 MB after the
build at the origin, and `ROUTE_MB` is 495.

The downtown preview shots (`-Dshots=downtown`) hold 583-626 MB with streaming, against 1057.9 MB for the whole town; the
near views are the same pictures (the contact sheet below), the far views gain the lights at night.

The CPU times are llvmpipe's (the frame call includes the software GL's own work on this 4-core server), so read them only
against each other: streaming costs a p99 hitch of ~19 ms here (a block's buildings or yards coming in, the worst single
streaming step 48.9 ms). On the S25 the same work is spread over 8 fast cores and the frame is drawn by the GPU, so the
hitch should be a fraction of this; **to be measured** there.

### The horizon ring

- Bake: 68-149 ms on a worker (desktop; `HorizonPanoramaTest` 96 ms), ~8,000 ground texels and ~300 lit ones from the
  capital's middle 400 m up.
- GPU: one 1 MB texture (4096 x 64 RGBA8) and one texelFetch per sky pixel in the 64-row band; no draw, no triangles.
- Phone cost: an estimate (a few microseconds of fragment work over the band); not measured.

### Estimates, not measured anywhere

- The streaming's MB on the phone: Filament's count matches to ~2%, so the route's 705 MB is expected within ~15 MB on the
  S25; the driver's own memory (Gfx dev) is not in it (MEMORY_AUDIT.md G9).
- The far-level and impostor savings in draws and triangles on the Adreno 830.

---

## 3. What needs the S25

1. **The route on the phone**: walk Main Street and drive the loop (the play log's `mem` line every 5 s): the GPU total
   should stay near the desktop's 540-710 MB, and `free` / the NPU headroom should rise by roughly what the whole town
   used to hold beyond that (MEMORY_AUDIT.md section 3 says how much of a cut reaches Android's "free").
2. **Hitches**: frame times while a block comes in at 100 km/h (Perfetto, the engine thread): the desktop's p99 of 19 ms is
   llvmpipe's; the phone's must be under its frame budget. If not: lower `EVICT_MS` / `YARD_UPLOAD_MS`, or raise the lead.
3. **Pop-in at speed**: whether a 4 s lead is enough on the bicycle and in a car (look for a far box turning into its
   building inside the haze line, which should never be seen).
4. **The lights at night** and **the impostors** by eye on the phone's screen (pixel size at 3120 x 1440).
5. **The horizon ring**: from a high place on a clear day and at night; its bake time on a worker (expected well under the
   desktop's 100 ms).

## 4. Tests

- `gradle :desktop:crossroadsTest --tests GpuRouteTest` (streaming on, fails over `TownBudget.ROUTE_MB`); with
  `ROUTE_STREAM=0` in the environment the same route with the whole town held, to compare (an input of the task, so the
  switch reruns it). Writes `desktop/build/gpu-route/route.txt`.
- `--tests GpuBudgetTest` (the whole capital's parts against their budgets), `--tests MeridianStepsTest`,
  `--tests HorizonPanoramaTest`.
- `gradle :desktop:test --tests OpenWalkTest`.

## 5. The contact sheet (2026-11-25)

Before (whole town held) and after (streaming, the far level, the lights, the impostors and the ring) from the downtown
preview, and the ring off and on in 250 km air: the near views match (no differences above noise in the day shots, a
few hundred pixels at dusk and night, all in the far part of the picture: the light dots and the impostors); at night
the far buildings show their lights. Desktop renders, not S25 captures. (Scratchpad of the session that made them:
`stream/contact_final.png`.)
