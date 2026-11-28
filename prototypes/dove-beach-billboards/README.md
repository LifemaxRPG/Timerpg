# Visit Dove Beach

Three original roadside campaigns depict the Head Light, Dove Fountain, the pier, the coast road, the Strand, surfing and the Old Harbour. Every face carries the town crest, a dove over three waves.

| Years | Campaign | Look |
| --- | --- | --- |
| 1920–1939 | By Rail | Cream lettering on deep green, with limited-palette railway-poster pictures |
| 1950–1969 | The Coast Road | Cream postcard stock, warm red lettering and coastal scenes including a family sedan |
| 1990–2029 | Take the Exit | Navy and mint blocks, high-contrast lettering and brighter water |

Each era has three illustrated faces and four countdown faces for 120, 45 and 12 miles and the next exit. The lighthouse and family-drive posters also have alternate faces with fading and peeled edges. The year ranges describe the campaigns. Placement in the gaps between campaigns is left to the game's era selection.

## Existing billboard fit

Each RGB PNG is exactly 256 × 88 and uses a 128 × 44 art-pixel grid doubled with nearest sampling. The existing billboard in `WorldPropKit.kt` has a 10 × 3.44 m front quad at unit scale, for 0.0390625 m per horizontal texel and about 0.039091 m per vertical texel. Those values follow the existing face and are close to the project’s 0.0388 m nominal scale.

Use the whole PNG on the current front quad or pack it into the current atlas with equivalent UVs and nearest sampling. Countdown art fits the same face. The two weathered records identify their original in `model-info.json`.

Run `python3 paint.py` with Pillow to regenerate the 23 faces, metadata and native-size sheet. Run `python3 validate.py` to check the pack. The roadside sheet shows Blender renders using the panel and support dimensions reconstructed from the Kotlin code. Run `blender -b --python-exit-code 1 -P render.py` to recreate its photos.

Atlas integration and runtime assignment of road signs remain for the game.
