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
