# C050 · Dove Fountain

A 6.12 m bronze dove marks Fountain Circus. Upturned wings, a fanned tail and a curling wave give the silhouette separate cues from the road and the hills. The 1912 construction date follows the city brief. The pale scalloped limestone basin, four benches and four iron lanterns fit on a 12.4 m square plot. The README describes the opening-era and weathered finishes. The exported textures show the weathered finish.

Included are the Blender source, near/mid/far GLBs, collision and light metadata, named water geometry, and the street, overhead, dusk and night Blender renders. The street view includes a simple 1.71 m scale figure. The exporter keeps the jets and lantern detail at near and mid range and uses a simpler basin and silhouette at far range.

`python3 validate.py` passed on all three exports at 2,710, 2,170 and 1,042 triangles. It checked the counts, embedded PNGs, filtering, light and water nodes, and collision metadata. Site placement, runtime water animation and collider behavior still need to be exercised in Timerpg.

## Self-review

1. The upper jets and the statue support were the weakest points of the draft. I brought the jets back to the wing roots and curled the wave up into the breast, then checked the junctions in daylight and at night.
2. I inspected the four Blender views and ran the export validator. I reasoned about the roundabout fit and walked clearances from the plot and box dimensions. Those clearances still need a controller test.
3. From the street the rising wings are visible well above the lanterns. From the hills the fanned tail and scalloped rim read more clearly.

## Usage

- Model and setting — Codex (GPT-6). Exact variant and reasoning setting not visible.
- Tokens and plan usage — not visible.
- Effort — one queue run, approximately five minutes from task selection to delivery. Exact tool-call total not visible.
- Rework — initial delivery with one render-driven refinement pass.
