# Alder

Alder is an 8.6 m masthead sloop inspired by compact fiberglass cruisers of the late 1970s. It has muted green topsides, a cream deck and sails, and teak cockpit trim. The round-bilge hull continues below the waterline to a ballasted fin and rudder. Above deck, the working jib and cambered mainsail have a forestay, backstay, spreaders, shrouds and running sheets. The recessed cockpit has a tiller, seats and sheet winches. A boarding ladder, cleats, anchor roller, forehatch and closed companionway finish the deck.

## Model and placement

The hull is 8.60 m long and 2.94 m wide, with a 1.43 m design draft. The exported asset includes the ladder and rig fittings beyond those reference dimensions. The glTF origin is amidships at keel elevation. Y points up and the bow faces +Z. Place the keel 1.43 m below the intended water surface. `model-info.json` lists boarding, helm, cleat and waterline anchors. The rudder and tiller share a pivot at the rudder stock. The mast, mainsail and jib have their own groups. Both sails show one fixed trim.

The GLB uses 3,597 triangles and eleven matte materials. Original textures use 2×2 art pixels, nearest/repeat filtering and 0.0388 m per texel. The geometry check covers the port and starboard boarding markers, the open cockpit, the rudder pivot and physical texture scale. The companionway is modeled closed. Buoyancy, steering, animated sail trim and boarding still need engine work and an on-device test.

## Build

Run from this folder in a Timerpg checkout. Texture generation and validation require Pillow and NumPy. The packed model source uses Blender 4.3.2.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The water and wake appear in the Blender Cycles previews. The standalone Filament image shows the exported model.
