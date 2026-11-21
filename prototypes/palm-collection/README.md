# Timerpg landscape palms

This set covers 14 familiar outdoor palms for mainland U.S. coastal, subtropical, desert and warm-temperate streetscapes. Thirteen types ship here with their own detailed and distance GLBs. The coconut palm comes from the existing [Coastal Palm](https://github.com/LifemaxRPG/Timerpg/pull/30) prototype. The review sheet includes it so the 14 silhouettes can be compared. Tree heights are authored landscaping sizes.

| Shape | Types |
| --- | --- |
| Tall fans | Mexican fan, California fan, Sabal |
| Compact fans | Windmill, Mediterranean fan, saw palmetto |
| Feather palms | Coconut, date, Canary Island date, queen, royal, foxtail |
| Small or clustered feather palms | Areca, pygmy date |

The models distinguish fan blades from feathered fronds, smooth crownshafts from fibrous or diamond-scarred trunks, and low spreading clusters from single-stem street trees. The two shared matte atlases use Timerpg's 2×2 art-pixel blocks, nearest/repeat sampling and 0.0388 m per texel. Placement origins are centered at ground level. `model-info.json` records per-model dimensions and trunk-blocking hints. Runtime planting rules, distance switching and Android performance need game integration.

## Rebuild

Run in this folder with Blender 4.3.2, Pillow and NumPy.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

To include the coconut in a fresh review, set `COCONUT_GLB` to the existing prototype's near GLB. The editable packed Blender source lays out the new near meshes. The catalogue and distance comparisons are desktop Blender Cycles renders, with a person included for scale in each image. The Filament checks use actual near GLBs for a tall feather palm, a fan palm and a clustered palm.
