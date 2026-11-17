# Status (Kotlin street prototype)

## What works (verified by rendering the same code on a computer; NOT yet run on a phone)
- The habit scene's lighting/sky/shadow engine drives everything; all art is ours (LimeZu): buildings, lamps, trees,
  bench, pedestrians and the player. No dogs, bikes, cars, or the habit tab's foreground.
- Buildings are the exact ones our TypeScript game draws for each shop (`tools/export-street.ts` -> `street.json`),
  composed into boxes by `tools/make_buildings.py`: front, matching side wall, flat roof, window-glow mask, rooftop sign.
  Signs sit over the front face as one piece (only the part above the roofline goes on the roof).
- Upper-floor windows are real openings showing plain reflective glass (engine sky reflection + sun glint); the habit scene's furnished rooms/people are off (`StageTuning.interiors`) until our own 2D interiors go in. Shopfront glass keeps its painted display and gets a faint reflection through a mask (`bN_glass.png`, display panes at ~40% strength). Arched/ornate upper windows are left as sprites.
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
  plus the junction's other buildings for the turn scene. Homes are stood in by the modular condo front in the district's colour
  (front door for a family, lobby for flats), named after the household really living there.
- The turn shows the junction's real buildings; its button appears only at the junction end of each street ("Turn onto <name>" /
  "Back to Main Street"), and turning back plays the turn in reverse and leaves you at the junction end of Main Street.
- Demo town is still "eta" (Main Street: 8 shops; Orchard Lane: 6 homes). Checked on 9 seeds: all give 7-8 on Main Street, 2-6 on the cross street.

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
  snapshots; with them about 60% of each prompt is skipped. The S25 Ultra's 8 Oryon cores (6 threads) should be several
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
