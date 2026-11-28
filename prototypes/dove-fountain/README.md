# Dove Fountain

The 1912 fountain at Fountain Circus marks the approach from the Esplanade to Dove Beach’s harbour. A bronze dove lifts off a curling bronze wave. Its raised wings are legible from the road and its fanned tail reads from the hills. The rim repeats the shape of a scallop shell.

Use a 12.4 m square plot centred on the basin. Place the model on level ground with +Z toward the intended main approach. The sculpture is 6.12 m tall. The supplied palette depicts maritime weathering with green patina and lighter worn edges. For the opening era, use a warmer copper-brown bronze on the same surfaces and leave the limestone pale. The GLBs ship with the weathered palette.

Import the near, mid and far GLBs using their shared origin. Collision boxes are in Y-up glTF metres with yaw about +Y. The named basin surface and two jet strips can receive the runtime water material. The far model keeps the basin water plane and drops the jet strips. Lantern emissive materials are in the near and mid meshes. The light positions and uplight aims are in `model-info.json`.

Run `blender -b --python-exit-code 1 -P build.py` to regenerate the source, exports and four review views. Append `-- --export-only` for just the asset files. Run `python3 validate.py` to check the GLBs. The review sheet is made from Blender renders.
