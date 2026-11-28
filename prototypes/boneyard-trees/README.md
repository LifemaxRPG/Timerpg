# Dove Beach driftwood

Place the near GLBs at the sand surface. Units are metres and glTF is Y-up. The matching `-mid.glb` and `-far.glb` files share the asset origin. Use the sizes, suggested tidal placements and oriented collision boxes in `model-info.json`. Box rotations are glTF-space XYZW quaternions. Thin branches and scatter have no collision. The buried trunk extends below the sand surface.

The textures use nearest filtering at 0.0388 m per texel. Wood grain patterns use two-texel blocks. The low tide staining and small sand deposits are part of the meshes and materials.

Open `boneyard-source.blend` in Blender to inspect the source scene. Run `blender -b --python-exit-code 1 -P build.py` to regenerate the exports and previews. Append `-- --export-only` to regenerate the GLBs and metadata. Run `python3 validate.py` to check the exported pack.

The three lineup images cover standing trees, fallen trunks, stumps, and scatter alongside a simplified player scale figure. The sunset and group images show possible beach placements. All five are Blender renders.
