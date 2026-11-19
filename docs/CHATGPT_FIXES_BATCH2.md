# Fixes for the second batch of models (paste to ChatGPT)

All six of your new models load in the game and look right. Please fix these against the guide (docs/CHATGPT_MODEL_GUIDE.md) and push to the same branches:

- **All of them:**
  - Leave doorways open. Don't put a closed door-panel box in them: the game places "Go inside" there.
  - The emissive texture should be only the parts that glow (glass and signs), not the whole base picture.
- **Gazette office:** box the building in the collision mesh up to its full 9.6 m height (yours stop at 4.5 m).
- **Feed mill:** centre the lot on the origin. It runs from x -11.2 to 18.6 m.
- **Starlight drive-in:** box the screen tower and its legs up to 11.5 m (yours stop at 3.3 m).
- **Water tower:**
  - Replace the single 12.7 x 12.7 x 26.2 m box with one box per leg, the fence and the gate, so a person can walk under the tank.
  - Bring the triangles down from 9,210 to under 5,000; most of them are in the legs and the catwalk.
- **Roadside motel:**
  - Lay the asphalt at y 0 or above (it is at -0.078 m).
  - Centre the lot. It runs from x -9.9 to 37.2 m.
  - 5,838 of its 9,970 triangles are in `detail_utility`: simplify them.
  - Keep the pool, but put its water surface at y 0 with the basin below. Name its material `water_pool`.
- **Railroad depot:**
  - Lay the asphalt at y 0 or above (it is at -0.078 m).
  - Put the side doors at the wall face, not set back into it.
  - Leave the tracks out (the game draws its own), but say in model-info.json where the platform edge is: its z and its height.

The two nightclubs were made by Gemini. We touched them up on our side, so they aren't on this list.
