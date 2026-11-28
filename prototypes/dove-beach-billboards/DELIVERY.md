# C051 · Visit Dove Beach

Three campaigns span the railway-poster, motor-age postcard and modern highway eras. Each has three illustrated faces and four countdown signs. The lighthouse and family-drive faces also come in sun-faded, chipped versions. All 23 faces use the dove-over-three-waves crest.

The 256 × 88 PNGs match the existing 10 × 3.44 m billboard panel at unit scale. The native-size review sheet includes every face. The roadside sheet includes four Blender renders on reconstructed billboard supports.

`python3 validate.py` passed. It checks dimensions, campaign and distance coverage, the exact two-by-two art-pixel grid, and both weathered variants. Game atlas integration and in-game legibility at driving speed remain untested.

## Self-review

1. Small scene captions lose contrast first as distance increases. I used solid dark caption bands and kept the town name and mileage at larger scales.
2. I inspected the native-size sheet and roadside renders and ran the texture validator. The roadside views approximate the existing billboard dimensions. I did not test the atlas or signs in a driving build.
3. The large Dove Beach lettering and the lighthouse, family sedan and surf silhouettes are the first cues.

## Usage

- Model and setting — Codex. Exact variant and reasoning setting not visible.
- Tokens and plan usage — not visible.
- Effort — one initial delivery across resumed queue triggers. First WIP through final preview took about four minutes. Exact total elapsed time and tool-call count not visible.
- Rework — initial delivery with a visual refinement pass. No formal review rounds.
