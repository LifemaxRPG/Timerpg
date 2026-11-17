# Status (Kotlin street prototype)

## What works (verified by rendering the same code on a computer; NOT yet run on a phone)
- The habit scene's lighting/sky/shadow engine drives everything; all art is ours (LimeZu): buildings, lamps, trees,
  bench, pedestrians and the player. No dogs, bikes, cars, or the habit tab's foreground.
- Buildings are the exact ones our TypeScript game draws for each shop (`tools/export-street.ts` -> `street.json`),
  composed into boxes by `tools/make_buildings.py`: front, matching side wall, flat roof, window-glow mask, rooftop sign.
  Signs sit over the front face as one piece (only the part above the roofline goes on the roof).
- Upper-floor windows are real openings: furnished rooms with people/lamps/TV behind (engine presence logic), engine
  reflections and sun glints over. Shop windows and arched/ornate windows are left as sprites.
- Walking: hold ◀ ▶; ▲ ▼ step in/out across the pavement (back/front views); camera follows. Time slider; season
  (spring/summer/autumn/winter) and weather (clear/overcast/rain/mist) buttons; trees are tinted by season.
- `gradle :desktop:preview -Dmode=pano|weather|rooms|walk` renders PNGs without a phone (see desktop/build/preview).

## Known rough edges / not done
- Untested on a real device (touch, speed, memory). The Android-only code is the image helper, asset loading, and controls.
- Pixel art is scaled by the engine's smooth zoom, so pixel sizes are slightly uneven in places.
- Only about a third of buildings have clean window floors (others are single-storey or have ornate/arched windows).
- Windows on side faces, people in shop windows, corner turns, doors/interiors, NPC schedules and dialogue are not
  connected to this renderer yet. The street is a fixed snapshot (re-run the two tools to change town: `export-street.ts <seed> <year>`).
- Demo town is "eta" (has more window floors); "alpha" works too.

## Ideas for next
- Feed the street from live game logic (port the TS logic to Kotlin, or export on demand).
- Residents appear in their own windows at the times their schedules put them home.
- Walkable pavement collisions with props; entering shops; the camera-rotating corner turn.

## Pixel-perfect scaling
The camera zoom is snapped so each sprite pixel is a whole, even number of screen pixels, the camera translate is rounded to whole pixels, and props and characters are drawn at 1x or 1.5x the building pixel scale. Pixel art stays crisp while moving. Toggle with `StageTuning.pixelPerfect`.

## Corner turn preview
`gradle :desktop:preview -Dmode=turn` renders `desktop/build/preview/turn_NN.png` (code: `desktop/src/main/kotlin/Turn.kt`). It is a standalone scene, not wired into the app yet: our building art on boxes around a crossroads, and a camera that yaws 90 degrees while travelling from the junction down the cross street. Each face is drawn as vertical sprite strips; buildings that end up between the camera and the player fade out; distance haze. `export/corner-turn-preview.gif` and `export/corner-turn-sheet.png` show it.
