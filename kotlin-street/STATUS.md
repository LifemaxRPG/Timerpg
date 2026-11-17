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
- The corner turn is now the crossroads and the walk round it (see "The crossroads and the walk round the corner" below).
  The old separate turn scene (`scene/CornerTurn.kt`) is no longer used by the app; the desktop `turn`/`turnsky`/`cross`
  modes still render it, and its 180-degree street crossing is kept until it is ported.
- Side walls: three windows per floor, pilasters, grain, brick courses. Night: only real glass glows (fixed blue walls glowing).
- Engine cloud-shadow pass is off for sprite buildings (it drew pale boxes above roofs). A building with a missing PNG no longer
  drops the whole street to the fallback.
- Preview modes: `gradle :desktop:preview -Dmode=pano|weather|rooms|walk|turn|junction|seam [-Dstreet=0|1] [-Dhour=H]`.

## The crossroads and the walk round the corner (verified in the desktop preview; NOT yet run on a phone)
- One place: `domain/Crossroads.kt` lays both streets on one ground plan (the main street's fronts on Z = 0, the cross
  street's west row on X = 0 facing east, its east row across the 178-unit mouth), every building once. The stage builds
  both streets once (`StageTuning.crossroads`, set by the app from street.json) and draws whichever the player is on,
  with the other seen round the corner.
- One camera: `scene/StageCamera.kt` is a pinhole 700 street units behind its target, its eye at the height that puts
  the vanishing point on the stage's own horizon (~49 degrees across the preview, ~69 on the S25's screen). A row of
  buildings or a stretch of ground can keep the stage's cabinet look (the camera's column for its depth axis leaned to
  the stage's offset): at rest it is exactly the stage's drawing; mid-turn plain perspective.
- One look: `scene/CornerWorld.kt` draws everything with the stage's own painters. Fronts go through
  `BuildingPainter.building` (glass reflections, signs, relief maps, balconies, night windows) under the front's
  projective transform; side faces, back walls, roofs and rooftop boards each on their own plane (`sideFace`,
  `roofFace`, `roofBoard`; `planeFaces` leaves them out of the front pass); faces seen small or edge on are sampled
  smoothly (mipmapped on Android), the rest crisp. The ground (the stage's pavement, kerb and road, lamp pools, shadows)
  goes through the ground's transform in strips that stay in front of the camera; lamps, trees, benches and people stand
  upright where they are. Everything is sorted front to back by the lines that part it. At rest the street in view is
  drawn exactly as the stage always drew it.
- The gap view (walking past the mouth) is now the real side street: Orchard Lane runs to the far city under the sky
  (no flat block), both its rows are there with their glass and roofs, the corner buildings' side walls are real (no
  floating eave, no hard cut, no strip of backdrop), the mouth has the cross street's asphalt with dropped kerbs and a
  zebra on it, the side street takes the hour's light (dark at night), and no road name floats over it. Taller side
  street buildings rise above the main street's roofs, and the top fade and cloud ceiling count them.
- The walk (`shim/CornerWalk.kt`): hold the up key a tenth of a second in the mouth (within 77 units of its middle) and
  the player walks a smooth curve into the cross street at walking pace (a run when held 0.6 s); the camera turns with
  how far along they are (90 degrees, eased), the target slides from the main street's view to the cross street's own
  view, and the sky turns with it (clouds, far city, stars, the sun or moon and its glare: a quarter turn takes the sun
  out of the cross street's view; that street is lit by where the sun really is). In the frame the walk ends the stage
  hands over to the cross street's own view: the same picture. Down walks it back (it ends where it began). Letting go
  of up lets the walk carry on (`TurnTuning.autoWalk`); still held at the end, it walks on along the new street. Back:
  up at the cross street's start (within 60 units) or walking on against its end (left held). Elsewhere up and down step
  across the pavement as before. Not while inside, at a door, in a conversation or tuning the NPU.
- The block the camera swings behind mid-turn (the bank's corner and the cross street's east row) is cut away nearer than
  120 units in front of the corner; between 30 and 55 degrees of the turn the cut moves on to 120 units in front of the
  camera's target as it slides up the cross street (the same place at a quarter turn, so the cross street's own view is
  unchanged), never nearer than 30 units in front of the player. A building the cut goes through is shown open: its far
  walls from inside, a floor at every storey, and its outer walls, slabs and roof as solid bands on the cut, like an
  architect's section. That is the visible compromise (`TurnTuning.CUT`, `KEEP`); the alternatives (walls down, a closer
  camera) are the owner's call.
- The seam test (`gradle :desktop:preview -Dmode=seam [-Ddir=fwd|back|abort] [-Dat=west|mid|east] [-Dsize=phone]
  [-Dhour, -Dwx, -Dseason] [-Dseq=1]`) renders the walk frame by frame at 60 fps, as the game steps it, and measures the
  hand-overs; `tools/seam-matrix.sh` runs it over many views. Measured in the desktop preview (Skia, not the S25):
  all 11 runs of `tools/seam-matrix.sh quick` pass (west/mid/east of the
  mouth both ways at 17.6 h; 13 h and 21.5 h rain in winter on the phone's screen both ways; walking back halfway). The
  street's own view and the walk's first frame, and the walk's last frame and the new street's own view, differ by a mean
  of 0.000 (out of 255; no pixel by more than 1); one step either side of each end differs by a mean of 0.11 at most,
  against 3.9-6.9 for one step of ordinary walking; no frame of any walk jumps against its neighbours; the walk takes
  170 frames forward (127.6 units: 170.2 at walking pace) and 128 back (95.3 units). Outside the mouth the stage draws
  as before (both streets' panoramas, weather and seasons against renders from before this work: a mean of 0.18 at
  most, in the sun's glare round one building's outline on the cross street).
- Desktop: `-Dmode=junction` walks past the mouth (nine frames) at any hour, weather, season and `-Dsize=phone`
  (2340x1080 at density 2.625). Tests: `gradle :desktop:crossroadsTest` (camera at rest equals the stage's drawing within
  0.05 px, the side street's vanishing point on the horizon, the ground plan, the walk's keys, path and hand-over).
- Not done yet: the street name sign at the corner; lamp heads turned along their own street (the cross street's lamps
  are the main street's picture, seen side on); the cut buildings' rooms are empty; generated corner walls from each
  front's storeys (the side pictures are used); the 180-degree street crossing still uses the old turn scene (so
  `StageTuning.skyOnly` and `GameState.skyPan` stay for the desktop `turnsky` mode); frame time, heat and HWUI's
  perspective drawing on the S25 are unmeasured.

## Real crossroads (tools/export-street.ts v2)
- street.json now holds named streets from the town's real layout: Main Street (the buildings on the main road west of a real
  crossroads, in order) and the cross street (the buildings along the road that leaves the main street there, named from the seed),
  plus the junction's other buildings for the turn scene. Homes are stood in by the modular condo front in the district's colour
  (front door for a family, lobby for flats), named after the household really living there.
- Both streets, the side street's mouth and the cross street's other side (`junction.crossEast`) are one place in the
  game now (below); there is no turn button any more.
- Demo town is still "eta" (Main Street: 8 shops; Orchard Lane: 6 homes). Checked on 9 seeds: all give 7-8 on Main Street, 2-6 on the cross street.

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
- Collision radii and the "near the kerb" band are guesses.
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
