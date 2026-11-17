# Status (Kotlin street prototype)

## What works (verified by rendering the same code on a computer; NOT yet run on a phone)
- The habit scene's lighting/sky/shadow engine drives everything; all art is ours (LimeZu): buildings, lamps, trees,
  bench, pedestrians and the player. No dogs, bikes, cars, or the habit tab's foreground.
- Buildings are the exact ones our TypeScript game draws for each shop (`tools/export-street.ts` -> `street.json`),
  composed into boxes by `tools/make_buildings.py`: front, matching side wall, flat roof, window-glow mask, rooftop sign.
  Signs sit over the front face as one piece (only the part above the roofline goes on the roof).
- Upper-floor windows are real openings showing plain reflective glass (engine sky reflection + sun glint); the habit scene's furnished rooms/people in the windows are off (`StageTuning.interiors`); the buildings themselves can now be entered (see Interiors below). Shopfront glass keeps its painted display and gets a faint reflection through a mask (`bN_glass.png`, display panes at ~40% strength). Arched/ornate upper windows are left as sprites.
  reflections and sun glints over. Shopfront glass keeps its painted display and gets a faint reflection through a mask (`bN_glass.png`, display panes at ~40% strength). Arched/ornate upper windows are left as sprites.
- Walking: hold ◀ ▶; ▲ ▼ step in/out across the pavement (back/front views); camera follows. Time slider; season
  (spring/summer/autumn/winter) and weather (clear/overcast/rain/mist) buttons; trees are tinted by season.
- `gradle :desktop:preview -Dmode=pano|weather|rooms|walk` renders PNGs without a phone (see desktop/build/preview).

## Added since (all verified by headless renders only)
- Pixel-perfect scaling (zoom snapped to whole pixels; `StageTuning.pixelPerfect`), props/characters at 1x/1.5x pixel scale.
- Shop name and owner shown at the bottom while standing at a shop. Collisions: walk round lamps/trees/benches at the kerb (radii are guesses).
- Corner turn (`shared/.../scene/CornerTurn.kt`; the "Turn the corner" button appears within ~110 units of either end of the street): the camera yaws 90 degrees round a crossroads built
  from our buildings (player walking, time-of-day light, lit windows and lamp glow at night), then fades to the other street
  (the town's shops are split into two). It follows time of day, the weather setting (overcast greying, rain streaks, mist haze) and the season (winter: snowy roofs/pavement and flakes; autumn: warmer pavement). It is a separate scene: it does not use the engine's sun/weather/reflections yet, and
  the junction is not a real place in the world map.
- Side walls: three windows per floor, pilasters, grain, brick courses. Night: only real glass glows (fixed blue walls glowing).
- Engine cloud-shadow pass is off for sprite buildings (it drew pale boxes above roofs). A building with a missing PNG no longer
  drops the whole street to the fallback.
- Preview modes: `gradle :desktop:preview -Dmode=pano|weather|rooms|walk|turn [-Dstreet=0|1] [-Dhour=H]`.

## Real crossroads (tools/export-street.ts v2)
- street.json now holds named streets from the town's real layout: Main Street (the buildings on the main road west of a real
  crossroads, in order) and the cross street (the buildings along the road that leaves the main street there, named from the seed),
  plus the junction's other buildings for the turn scene. Homes are named after the household really living there; small
  and medium homes are now the game's own mobile homes (see Pitched-roof houses), villas the game's own villas (see
  Villas and landmarks), the rest are stood in by the modular condo front in the district's colour.
- The turn shows the junction's real buildings; its button appears only at the junction end of each street ("Turn onto <name>" /
  "Back to Main Street"), and turning back plays the turn in reverse and leaves you at the junction end of Main Street.
- Demo town is still "eta" (Main Street: 8 shops; Orchard Lane: 6 homes). Checked on 9 seeds: all give 7-8 on Main Street, 2-6 on the cross street.

## Pitched-roof houses: the homes are mobile homes now (verified in the desktop preview only; NOT yet seen on a phone)
- A small or medium home on the exported streets wears the very mobile home the game draws on its lot (`houseSmall`,
  `houseMedium`, in its district's colours; `tools/town-streets.ts`), built by the engine as a real house instead of the
  condo stand-in: Orchard Lane (b8-b13) and the junction's b16. No mobile home repeats either of the two before it in a
  row (b10 and b12 are re-rolled to another of their size and palette, as shops already are). Apartments, villas,
  estates and terraced homes keep the condo front (b17).
- `tools/make_buildings.py` `build_house()` cuts the sprite into pictures (front wall, near slope / ridge / far slope
  taken every other row, gable end, hitch, porch parts, the yard's ground and its fence or hedge, where snow lies on that
  fence) and writes the shape to
  `buildings/houses.json`; `--showcase DIR` builds all 160 sprites. `BuildingPainter.houseBuilding` builds it: back in its
  2-tile front yard (it moves against the pavement as the street scrolls), a ridge along the street (the slope one screen
  row per row of the art, about 21 degrees), a gable end on the shown side under the roof slab's end (its rake boards
  standing out by the verge, and behind the ridge the soffit under them in shade: thin steep bands, filled one whole
  screen pixel per row, `domain/HouseGeometry.kt` `PixelRows`, so they step like the art's own lines), the flue behind the ridge,
  the hitch, the porch or steps as lit boxes, the yard (grass or bare earth, the path to the gate, the game's fence or a
  hedge for a formal yard). The gable end is the front's plain siding carried round the corner (the columns that are
  siding all the way down, so no door, frame or blind colours it). The art's ground is told from the house by the grey
  base design (the green variants paint the hitch and the wheels green). The porch is a platform: its deck's front over
  the dark space under it, the railing open between its balusters. Light: the slope catches a high sun and is in shade
  at golden hour, the gable by its side, the verge's shadow, the eaves' shade, the house's shade over its yard, its fence
  or hedge and (shorter) the pavement. Snow on the roof, the ridge cap and the rake boards, a lip and icicles, a white
  yard, along the fence's and the hedge's tops. A house's wall stands on the screen's own pixels (its place along the
  street counted in, not only whole pixels from its middle). Windows keep their drawn curtains and blinds with the sky mirrored faintly over them,
  rain on the glass, and a lamp lit behind at dusk on a home's hours. The wet road mirrors a house where it stands (its
  own width, back in its yard, its roof over it, mirrored about its own foot); a car's headlights light only its front
  wall, and the lamp posts' shadows they throw land on that wall where it stands back; a tap finds its yard (porch,
  steps), its front or its roof, each leaning with the view as far as it stands back, not the sky beside or above it.
- The corner turn and the street crossing slot a house at its own width, back in its slot, with its pitched roof as a
  prism (`scene/TurnRoof.kt`: both slopes wear the art's slopes through one perspective matrix each, the gables the end
  wall's own picture above the wall, its trim and vent, under a band of the rake's colour), and what the street shows
  round it, so nothing pops in or out as the view turns (`scene/TurnHouse.kt`): its yard's ground, its fence or hedge
  (snow on it in winter), its porch or steps (the railing and the deck's front wearing the art) and its flue as lit
  boxes (the flue between the slopes, so the ridge hides its foot from the front). The turn's tints are kept in a table
  of plain ints, so looking one up makes no garbage. A porch light beside each door comes on at dusk; the
  flues smoke in the cold months (the turn has neither yet).
- Not done yet: Main Street's own pitched roofs (`roof_2/4/6`, `roof_8/9/11`, the same machinery at 120 deep; the
  villas and the hipped roofs are done, below); hedges down the yards' sides, pigeons on the ridges, eave drips in rain. The street's view is nearly level, so a porch's deck boards hide behind its railing (only
  between the balusters do they show); the crossing, from higher, shows them. The turn's houses stand 40 back in its
  120-deep slots against 64 in the street (their porches shortened to fit), and its roofs have no ridge cap.
- Checks: `gradle :desktop:preview -Dmode=houses -Dstreet=1` (each house beside its sprite at the left, middle and right
  of the view, and the lane at day, dusk, night and in snow; `-Dassets=DIR -Dstreet=0` for the showcase);
  `gradle :desktop:test -DtestOnly=HouseGeometryTest`; `npx vitest run kotlin-street/tools/town-streets.test.ts`.
- `make_buildings.py` no longer deletes the lighting maps (`_lit`, `_shade`) of fronts it did not change: the depth
  model that makes them is not kept on disk. The houses have none (their relief is real geometry).
- Draw cost: about 60-90 draw calls per house against about 40 per shop (an estimate; nothing measured on the S25).

## Villas and landmarks: front-facing gables and hipped roofs (verified in the desktop preview only; NOT yet seen on a phone)
- A villa-tier home on the exported streets wears its own villa (`villa_1..5`, `tools/town-streets.ts`; a villa
  repeating one of the two homes before it becomes another of its district's villas when there is one). eta has none
  on its streets (its assets are unchanged); vale has two (one on Oak Street, one at the junction).
- `tools/make_buildings.py` `build_model()` cuts a villa into parts (`buildings/models.json`), all its art in one atlas
  (`bN_atlas.png`, where it glows at night in `bN_glow.png`): the ground floor (its porch posts taken off the wall, the
  siding carried across), the floor between the storeys, the upper floor whose front carries the gable (a true front
  elevation, used as drawn: the round window, the rake boards, the balcony's glass door), a FRONT-FACING gable (its two
  slopes are the art's slopes from above, laid back from the rake), the terrace roof over the ground floor (its boards,
  with the small gable and the balcony painted out), the balcony (rails and a card open between the balusters), the
  small gable over the door (its face as drawn, its slopes), the posts, the raised deck, its railings (open between the
  balusters) and steps. Side walls are each row of the front's own siding with one of its windows. Its yard (grass, the
  path, the game's fence or a hedge) is the houses' helper.
- The clock towers (`landmark_clocktower_1/2`, only the time differs) are a square tower wearing its front on every
  side (a clock on each face) under a HIPPED roof: the front face the art's rows between the hips, every other one (the
  artist's own pixels, one screen row each; the rise is chosen so, about 31 degrees; its last row the tiles' ends along
  the eave, which on the wall's top stayed red under the snow), the side faces laid on their quads and tiled anew with
  the front face's own tiles, each course along the depth at the height of the front's (`hip_side`: the art draws them
  in the front's way, courses across, which sampled into hatching or, averaged, streaks), with a second piece whose
  courses are each one colour for a view that sees them nearly edge on (`along_mean`; the engine fades between the two
  by how far apart the joints fall on the screen); a band of the art's crease along each hip (no sky through it), a
  flat top with its lantern, the box's top round it the stone coping the art shows from above. The brick piers' rows
  are widened to the box's edge (they stood two pixels in: a slit of sky down each corner). The dial's painted hands are
  taken off (`clock_of`) and the engine draws them at the game's hour on every face (`ClockHands`: pixel art in the
  dial's doubled cells, ink down the middle, brown round it, gold tips and hub; a face seen from the left turns them the
  other way, so they still go clockwise); at night the whole dial glows (its face's colour inside its ellipse) with the
  hands dark on it. Each clock has its own small picture of its hands, made blank and mutable (`ImageBitmap(n, n)`) and
  painted again only when the minute changes, with no allocation (on Android a picture made from pixels is immutable:
  writing it again would throw; the desktop preview now fails the same way). The school (`landmark_school_1`) is its long front, a middle bay standing 12 forward (as wide as the
  two doors' arches, which reach past the cross gable's eaves: its edge no longer cuts an arch) with the cross gable's
  face over it, a ledge beside the gable, a hipped roof (about 43 degrees, a flat ridge, its hips the art's light cap
  tiles over a crease), the cross gable's slopes back to the valley (its verge along a slope the view does not see), two
  chimneys (a brick stack under a wider slate cap, the cap's top the art's from above; smoking in the
  cold months), its entrance steps between stone cheeks with the art's balusters (from the cheek's outline in: the
  art's half-clear shadow beside it had come out pure black); its side walls are the front's wall
  colour clear of its windows, with an upper and an arched window of its own. They are not on the two streets:
  `-Dmode=models -Dstreet=3` shows them in a preview row.
- The engine (`domain/ModelGeometry.kt`, `BuildingPainter.modelBuilding`) builds them back to front: boxes wearing the
  art's fronts (a front may reach above its box), sides and tops; front gables, each slope laid on its quad by one
  projective map where the view sees it from above (`RoofFaces`), lit by the side it faces, snow on it with a lip along
  the rake; under each slope's straight front edge the roof's own edge colour (the art's row above the rake ink) fills
  down past the face's stepped rake, so no staircase of sky shows between the gable and its slopes, and two pixels
  above it is the verge, which shows whether or not the slope does (the small gable over the door, whose ridge now
  meets where its face's checkered rake boards do, at 45 degrees, no longer shows a dotted outline on the side the view
  does not see; snow on it, and over its apex). The eave's board and
  its shade along the shown side are drawn after the wall that carries the gable's face (`ModelShape.eaveOn`: that
  wall's side would paint over them). Hipped roofs: side wedges (half the slopes' sunny wash: the full one turned their
  dark red tiles pink), flat top, front face with its eave on the screen's pixels, lit as the houses' slopes are; snow on
  each face's own pixels, along the hips and with a lip hanging over the eave, nearly opaque on these roofs (it had
  taken the roof's colour: pink on a red roof). Windows mirror the sky and gather rain (not a round dial, which would
  show a square) and light on the building's hours (a home's, a shop's, the clock's dial all night): every shade of a
  pane's glass joined to its clean glass, up to its frame and mullions (the school's oval window had lit in blobs with
  dark wedges, the villas' with ragged tops), a little brighter where the art's glass is lighter; the villa's lamp by
  the door. A building wider than a shop takes a wider lot and stands
  in its middle (`BuildingLayout.wide`); shops and the junction are laid out as before. Its shade on the pavement, the
  wet road's mirror (culled by its own width), the sun's glare at its edges, headlights and taps follow it where it
  stands back (as a house's). At the mouth of the side street it shows no side face (`StageTuning.cornerIds`, as the
  shops and houses). Only a box whose side has windows gets the night's light pass on its side (`sg`; not a railing's).
- The corner turn and the crossing build a villa's box of two storeys with its front gable (`scene/TurnVilla.kt`): a
  prism on the box, its ridge from the middle of the front to the middle of the back, each slope its art from above
  laid on its quad by one perspective map from the art's own four points (as the street lays it), each gable end the
  upper storey's own triangle (the round window, the rake boards) over the roof's edge colour; lit by the side it faces,
  white in winter. So a villa keeps its shape as the view comes round the corner. Its box's front picture
  (`bN_front.png`, which the wet road mirrors too) carries the balcony's railing, the small gable over the door and the
  deck's railings as the street sees them from straight ahead (they had been left out: the turn showed the storeys
  bare); they are flat on it there, not built as volumes. A landmark's front gable is never taken for its roof there.
- Not done yet: the villas' storeys are as low as the art draws them (40 pixels: a shade lower than the mobile homes'
  wall); no lighting maps for them; the school's back wing and the clock tower's corner pinnacles above the eave are not
  modelled; the cross gable's rake ink stays dark in snow (the art's boards, under the lip of snow); the cross gable's
  slopes are slivers in the street's nearly level view (the art draws them from high above), and the bay's side return
  is as thin as its 12 pixels of depth; the turn's villa deck, porch and small gable are flat; in the street's frame
  (780 by 360) the landmarks' roofs are above the top (they are not on a street yet).
- Checks: `python3 tools/make_buildings.py --showcase DIR`, then `gradle :desktop:preview -Dmode=models -Dassets=DIR
  -Dstreet=2` (villas) or `-Dstreet=3 -Dtall=760` (landmarks): each beside its sprite at the left, middle and right of
  the view, and the row at day, dusk, night and in snow; `-Dmode=turn|cross -Dassets=` now read another asset folder too.
  `gradle :desktop:test -DtestOnly=ModelGeometryTest,HouseGeometryTest`; `npx vitest run kotlin-street/tools/town-streets.test.ts`.
- Draw cost: a villa is about 20 parts (an estimate of 120-160 draw calls), the school about 12, a clock's hands one
  more image per face (repainted only when the minute changes), a hipped roof's side face one or two pieces (two while
  it fades between its tiled and its flush picture); nothing measured on the S25. Each projective piece
  (a slope, a side wall) is a `withTransform`, which on Android allocates a matrix per call (Compose's
  `AndroidCanvas.concat`), as the turn's houses and roofs already do: more of them now, still unmeasured.

## Talking to people out loud (verified on a computer; NOT yet run on a phone)
- In a conversation, hold "Hold to talk", say anything, let go: `voice/PlayerVoice.kt` records 16 kHz mono (at most 20 s),
  trims the silence and `llm/NativeAsr.kt` writes it down with **Qwen3-ASR 1.7B** (Q8_0, 2.2 GB + 356 MB encoder; llama.cpp's
  mtmd library in the same native library, `cpp/asr_core.cpp`), with the town's and everyone's names as hints. The
  NPC's topic nearest the words is chosen (`DialogueEngine.topicFor`) and Qwen answers the player's exact words in that
  topic's fact sheet (`withQuestion`), checked by `validate()` as always, then spoken in the NPC's voice.
- Writing ahead pauses while the player speaks and while the words are written down (the CPU is theirs).
- Measured on the 4-core x86 server (`asr_host_test`, our Kokoro clips): every clip word for word, names included ("Ray
  Vance", "Holloway's Bakery"); ~0.4x real time (a 5 s question in ~2 s) after a one-time load. The S25: not measured yet.
- The AI panel's download fetches the speech model with the rest (about 8.9 GB in all). The microphone permission is
  asked the first time the button is held.

## GPU rain (verified in the desktop preview with Skia's runtime shaders; NOT yet seen on a phone)
- `scene/RainFx.kt`: shaders the platform runs (`StageFx`: Android 13+ RuntimeShader/AGSL via `AndroidFx`, the preview
  Skia RuntimeEffect/SkSL via `SkiaFx`; the same source). `GLASS`: beads on every window pane and the shop glass (a bright
  face, a dark lip) and drops running down with trails; `GROUND`: puddles that hold the sky in a wide, flat pattern
  that grows with how wet the street is, and the rain's rings across the wet ground (thicker in the puddles). Pixel-crisp
  on the sprites' grid, anchored to the street. Older phones keep the drawn rain only.
- `SHEEN`: at night on the wet ground each street lamp's light lies in a long, broken warm streak (up to eight lamps in
  view). `FOG`: mist as banks drifting slowly along the street, thickest low down, over a lighter even veil; it takes the
  light there is (pale by day, dark blue-grey at night).

## Depth and light on the building fronts (verified in the desktop preview; NOT yet seen on a phone)
- `tools/make_lighting.py` (run after `make_buildings.py`, on a computer): **Depth Anything V2 Small** (Apache-2.0, ONNX)
  estimates each front's depth; the scene-wide slope it reads into a picture is taken out, and the depth is snapped to
  the art (each patch of one colour takes its median depth), so light and shade fall along the pixel art's own lines.
  From its normals: `bN_lit.png` / `bN_shade.png` (R, G, B: brighter / darker than a flat wall with the sun from the
  left, the right, above; shade's A: recesses). The shopfront glass is left out (the display is not the wall).
- `BuildingPainter.relief`: by the sun's side and how direct it is, the maps are laid over the front (strength inside the
  colour filter: a paint's alpha is applied before its filter); recesses stay a little dark at night. Windows read as
  set into the wall, ledges and sills throw a thin shadow, signs and the gym's dumbbells have volume.

## People remember (verified on a computer; NOT yet run on a phone)
- Every exchange said out loud is remembered by that person (`dialogue/NpcMemory.kt`, `MemoryService.kt`; saved in
  `filesDir/memories.json`, 200 per person): the player's words and the answer, with a vector from **Qwen3-Embedding-0.6B**
  (native, `cpp/embed_core.cpp`), made once when stored. When the player speaks, the memories that stand out for those
  words (2 standard deviations above the person's mean likeness; a floor with few memories) and the last exchange (10
  minutes) go into the fact sheet, and someone who remembers the player no longer calls them a stranger.
- Which memories go in: the embedder's closest four, judged by **Qwen3-Reranker-4B** (reads what is said and a memory
  together, answers yes or no; at most two yes), plus the last exchange; when nothing comes close the judge is not asked.
  Measured (`tools/memory-eval/cases.json` with `eval_memory`: 30 memories with look-alikes, 20 references, 10 unrelated
  lines): embedder alone 20 of 20 found but something recalled for 8 of the 10 unrelated; with Qwen3-Reranker-0.6B 18 of
  20 and 10 of 10; with the 4B 20 of 20 and 10 of 10, ~4 s per line on the 4-core server (judging 4 memories; the S25:
  not measured, estimated under 1.5 s). Qwen3.5-9B uses them: "Any news about my dog?" -> "No sightings yet. I told you,
  keep your eyes open too."; "Clara's wedding is coming up soon." -> "I hope the preparations go smoothly for her."
- RAM: every model runs in one lane (`llm/AiLane.kt`: dialogue, speech, memory, voices take turns), weights are mapped
  from their files (an idle model's pages can be dropped), the speech and memory models load when someone to talk to is
  near and are let go after a minute idle, the voice engines after 30 s. The AI panel shows the phone's free RAM, the
  game's own and what the lane is running, to measure it on the S25.

## Dialogue and the native dialogue AI (verified on a computer; NOT yet run on a phone)
- `tools/export-dialogue.ts` -> `assets/dialogue.json`: the people at each building of both streets (shop owners; a home's
  head and one other adult), with the game's template lines and the model's prompts for each topic (see README).
- "Talk to <name>" at a building opens a dialogue panel (topics, the reply streaming in, a marker for AI vs built-in line);
  walking away or turning the corner closes it. The "AI" chip (top left) opens the model and speed panel.
- `dialogue/DialogueEngine.kt`: one low-priority worker owns the model; lines are written ahead nearest-first (paused in
  the corner turn and in the background), questions jump the queue, replies pass the `validate()` port or the template
  stays; results cached in `filesDir/dialogue-cache.json`. Model file picked once with the system picker (GGUF header
  checked, copied with progress).
- The engine is `NativeLlm` (`llm/Backends.kt`): llama.cpp built into the APK (arm64-v8a; CPU kernels armv8.0 /
  armv8.2+dotprod+fp16 / armv8.6+i8mm, best one picked at run time), KV prefix reuse, old sampler settings, streaming,
  cancel, errors instead of crashes (missing/invalid model, no native library, low memory).
- The model is **Qwen3.5-9B** (Q4_K_M, 5.7 GB; Apache-2.0), the strongest Qwen that fits a phone (Qwen3.6/3.8 are 27B
  and up). The AI panel downloads it (and both voice engines) from Hugging Face with resume, or a file can be picked.
  Qwen3.5 is a hybrid model (Gated DeltaNet + attention): its recurrent state cannot be cut back, so the engine saves
  snapshots of the state at each line end of the fact sheet (`Engine::take_snapshot`, LRU of 8) and starts the next
  prompt from the longest one it begins with (the shared rules for anyone, the person's lines for the same person).
- Measured on a computer (4-core x86 cloud machine, NOT the S25), `tools/llm-dialogue-check.sh`, all 75 prompts:
  Qwen3.5-9B: 73 of 75 (97%) pass `validate()` (Qwen3-0.6B: 67-68 of 75, ~89%), replies in character ("Get off my
  porch before I call the sheriff."); ~33-44 tok/s prompt processing, ~2.5-5 tok/s generation, ~12 s per line before the
  snapshots; with them 58% of prompt tokens come from a snapshot and a line takes ~6.4 s (first token ~2 s). The S25 Ultra's 8 Oryon cores (6 threads) should be several
  times faster: an estimate until measured there. Kotlin and TypeScript `validate()` agree on all 75.
- `validate()` (both copies) also rejects a name the prompt never mentions (a capitalized word mid-sentence), the fact
  sheet's way of speaking read back ("A curt, measured response: ..."), and the "<town>, <year>" tag.
- Guesses to check on a device: the free-memory thresholds for loading, the 45 s reply timeout, two tries per line, the
  thread count (6 on the S25 Ultra by its cpu_capacity, 2 if only clocks can be read), the panel sizes on small
  landscape screens.

## Known rough edges / not done
- Untested on a real device (touch, speed, memory, the turn's draw cost). Android-only code: image helper, asset loading, controls.
- Collision radii and the "near the kerb" band are guesses. The turn's far buildings show the striped side art at grazing angles.
- Storefront glass lights as a flat warm panel at night; sign letters that are pale blue also light up (mask treats them as glass).
- People in shop windows, doors/interiors, NPC schedules and dialogue are not connected to this renderer. The street is a fixed
  snapshot (re-run `tools/export-street.ts <seed> <year>` and `tools/make_buildings.py` to change town).

## Ideas for next
- Feed the street from live game logic (port the TS logic to Kotlin, or export on demand); a real junction between streets.
- Residents appear in their own windows at the times their schedules put them home. Entering shops.


## Interiors (Wave Function Collapse)

- "Go inside" at a building opens its room: `src/town/interiors.ts` plans the walls and room divisions (keyed to the
  property), `src/town/furnish.ts` furnishes each area by Wave Function Collapse (`src/town/wfc.ts`): cells in a
  superposition of "empty" and every placement that could cover them, lowest-entropy cell collapsed by seeded chance,
  overlaps/blocked walkways/counts propagated, neighbour affinities in the weights (kitchen runs, chairs only under
  tables), required pieces first, the finished room checked for reachability and re-solved from another branch of the
  seed if it fails. Homes follow the household (a bed per couple/single, green beds and toys for children, a piano or
  guitar for a musical resident, desk and books for a studious one); shop stock grows with capital. The large public
  buildings keep their drawn layouts.
- `tools/export-interiors.ts eta 2025 > app/src/main/assets/interiors.json`; sprites in `app/src/main/assets/interior/`.
- Kotlin: `shared/.../scene/Interior.kt` draws the room fitted to the screen, top-down 3/4: our furniture sprites with
  contact shadows, walls/windows/skirting as geometry, daylight shafts by the hour, a night tint with warm lamp pools; the
  player walks with the same pads (feet collide with footprints), the shop keeper or resident stands by their counter or
  sofa and can be talked to when near; walking out of the door or "Leave" returns to the street.
  Desktop preview: `gradle :desktop:preview -Dmode=inside -Drooms=b0,b5`.
- Not yet: outdoor sound is not muffled inside; upstairs/large civic buildings are not on the two streets.
