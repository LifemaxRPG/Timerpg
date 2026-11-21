# Copperwake line

The **309 m MV Copperwake** is the 1996-onward cruise ship. It retains the navy hull, twin copper funnels, stern terraces, two boarding points and twelve stair runs. The revision adds raised ship lettering, colored navigation lamps, steel shading on the funnels, varied balcony doors with separate night masks, pool and sports fittings, a deck cinema, a bar, palms and promenade life rings. The visible near model uses **29,826 triangles and 12 materials**. Repeated fittings share meshes.

The **260 m SS Copperwake** is a 1952–1978 liner with a black hull, three raked copper funnels, two masts, open teak galleries and an aft pool. Its **1979-onward laid-up** state has patinated hull plating and canvas funnel covers. The three vessel states include near, mid and far GLBs. The MV distance models use 3,732 and 228 triangles. The working SS uses 4,452 and 248 triangles. The laid-up SS uses 4,560 and 248.

The near MV GLB includes its observation shell and sample stateroom. The **ten detailed room GLBs** under `interiors/` are independent scene-transition assets. The MV set includes the atrium, dining room, theatre, bridge, observation lounge, stateroom and suite. The SS set includes the grand staircase, first-class dining saloon and smoking room. Each contains fixed fittings, a named floor, door anchors and collision boxes. Atrium and theatre stairs have named endpoints. These room files have their own local origins and review views.

## Coordinates and integration

The MV keeps its amidships keel origin and +Z bow in glTF metres, with Y up. The calm-water offset is 9.35 m above the MV keel and 8.30 m above the SS keel. Use `model-info.json` and `liner-model-info.json` for boarding, mooring, captain, arrival camera and exterior collision. Collision boxes use glTF axes. The hull boxes provide broad-phase envelopes, and deck-specific navigation will need to use the deck levels and stair runs. The room manifest maps independent room-file floors, door positions and collisions. Ship movement, dock gangways, room transitions and cross-ocean gameplay still need engine integration.

## Rebuild and review

Run in this folder with Blender 4.3.2, Python, Pillow and NumPy.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
blender -b --python-exit-code 1 --python build_liners.py
blender -b --python-exit-code 1 --python build_interiors.py
python validate_model.py
python verify_revision.py
python lod_review.py
```

The three packed vessel sources and the packed room source are editable. Texture art uses 2×2 texel blocks, 0.0388 m per texel and nearest/repeat sampling. Run `render_model.py` in Blender for the review views. Use `COPPERWAKE_SHIP=ss-copperwake` or `COPPERWAKE_SHIP=ss-copperwake-laid-up` for the other eras, and select views with `COPPERWAKE_VIEWS` as a comma-delimited list. `render_rooms.py` renders the separate rooms. `package_review.py` assembles the photo sheets. Sea, wake, pier and scale figures belong to the Blender previews. The Filament images use standalone desktop WebGL. Android performance and the runtime light rig have yet to be tested.
