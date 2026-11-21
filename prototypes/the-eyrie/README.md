# The Eyrie

An outdoor national monument imagined as a 1938 sculptural commission. Two rising bronze wings emerge from their own battered granite abutments. The east wing sits farther back and rises higher. Long bronze primaries sweep out from each spine, with shorter overlapping feathers and raised quills visible on both faces. The gap between the wings stays open to the sky.

The plaza is 198 × 160 m. The tallest tip reaches 113.46 m. Granite bands in the paving lead up two 20-step approaches to a shared open-air terrace. Two inscription panels face opposite approaches. Lawn trees and path lanterns are separate removable lot nodes.

The asset uses 8,392 visible triangles and 12 matte materials. Each PNG uses nearest/repeat sampling and whole 2 × 2 art-pixel blocks at 0.0388 m per texel. The paving and timber use Timerpg swatches. Bronze, stone and the dedication panels use original pixel textures. The editable Blender source packs all twelve referenced textures.

## Rebuild and review

Run in this folder in a Timerpg checkout. Texture and validation scripts use Pillow and NumPy. The source and renders use Blender 4.3.2.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

Set `EYRIE_REFERENCE_ROOT` to a directory holding the Gazette, library, bank and hotel Timerpg worktrees to reproduce the native-scale context shot. Those buildings, the preview figures and the desktop lighting rig belong to the render scene. The main previews use Blender Cycles. The optional desktop Filament frame uses the exported GLB. Android placement, stair navigation, collision and lighting need an in-game test.
