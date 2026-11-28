# Wren farm tractors

Original 1952 row-crop and 1978 utility tractors. Each has a full, mid and far GLB and editable Blender source. The full models use embedded nearest-filtered 2×2 pixel textures at 0.0388 m per texel. The node and mesh layout follows the Timerpg vehicle convention, with glTF +Z forward and the origin between the axles at ground height.

The `body_paint` primitives form the repaint mask. The `lamp_faces` primitives form the emission mask. Both use geometry boundaries. Apply paint options from model-info while retaining the texture's tonal variation. The default paint differs between the two generations.

Rebuild with `blender -b -P build.py -- /path/to/northcairn-1956.glb`. The optional C011 comparison car is loaded after export, for scale renders only. Review PNGs are rendered in desktop Blender Cycles. The human reference is 1.75 m tall. Run `python3 validate.py` to check GLB geometry, budgets, node hierarchy and sampling.
