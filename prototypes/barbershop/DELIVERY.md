# C005 · Barbershop and beauty parlour

Built Mercer Barber and Lilac Beauty as two original, narrow Main Street buildings. Both come in 1940–1979, 1980–2009 and 2010-onward styles. The shop fronts share a placement origin and open street and alley doors across all six states.

Mercer has three fixed chairs, framed mirrors and an independent barber-pole drum on its glTF +Y rotation axis. Lilac has three hood-dryer stations in the two older styles, then becomes Lilac Nail Room with three manicure stations. Eighteen GLBs, six packed Blender sources, repaintable name plates, pane masks, colliders and a single review sheet are included.

## Verification

- The prototype verifier passed all 18 GLBs for shared anchors, door apertures, equipment counts, pole pivot, collider transforms, visible triangle counts, nearest sampling, 2×2 art pixels, 0.0388 m per texel and pane-only emission. The most detailed asset has 1,678 visible triangles. Every model has 12 materials or fewer.
- Khronos glTF Validator reported zero errors and warnings across all 18 files. All six sources opened with packed textures.
- Inspected day, night, rear, overhead, entrance and interior views. The previews are Blender Cycles desktop renders.

The game still needs to drive the pole, door interactions, shop services and Android integration. Mirror imagery is static.

## Self-review

1. The plain side walls carry little detail at an isolated camera angle. I kept the brick continuous for neighboring storefronts and concentrated the silhouette and trim changes on the street face. The roof and rear remain finished for exposed end lots.
2. I looked at all six storefronts at street height and checked their interiors and roof silhouettes. I redrew the small nail salon window lettering after its N read like an H in the first review. I ran the binary validation and opened the packed sources. I reasoned about placement and the pole’s animation hookup. No Filament or Android runtime checks ran here.
3. The owner will likely notice the striped pole and the color changes from the oxblood original to the green revival, or the shift from Lilac's round hood dryers to its manicure counters.

## Usage

- Model and setting: Codex GPT-6 family. Exact variant and reasoning setting not visible.
- Tokens and plan usage: not visible.
- Effort: one turn, about eight minutes and about 25 direct tool calls through delivery.
- Rework: initial delivery, zero review rounds.
