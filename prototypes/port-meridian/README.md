# Port Meridian

A 1996-onward passenger terminal designed for the 309 m MV Copperwake. Port Meridian has a 386 m straight quay, boarding bridge, harbor clock, three patinated-copper roofs, separate arrivals and departures entrances, and a baggage service yard. The two-level terminal shell has fixed check-in counters, two switchback stairs, and a lift core reserved for integration. Optional dressing includes parked shuttle coaches, baggage containers and the deployed boarding leaf.

The stand-alone port uses 11,952 triangles and 12 matte materials. The timber uses Timerpg's shared plank swatch. Original masonry, glazing, metal, pixel lettering and paint use 2×2 texel art pixels, nearest/repeat filtering and 0.0388 m per texel.

## Placement and boarding

- Use glTF metres with grade at Y 0, mean water at Y −2.40, +X seaward and +Z along the vessel's bow. The berth calls for 12.5 m of dredged water depth supplied by the world.
- Place the MV keel origin at `[22.4, -11.75, 0]` with zero yaw. The port has 38 m of quay margin aft and 39 m forward with that pose.
- The fixed link runs from the departures floor to the shore hinge. The deployable leaf narrows to a 1.24 m deck at its exposed tip, shifted longitudinally around an existing life-ring post on Copperwake. The handoff continues along the ship deck to the original `gangway_port` position. Both positions and the full approach are in `model-info.json`.
- Use the authored floor and stair surfaces for deck navigation. The manifest lists fixed wall and obstruction boxes and approach, mooring, stair and gate anchors. Hide the leaf when the berth is empty. It depicts one static configuration at mean water.

This asset supplies geometry and connection points. Bathymetry, tides, route planning, berthing, bridge movement, lift operation, ship interiors and cross-ocean travel are game integration work. The 1950s ship state has its own boarding geometry and pose.

## Rebuild and review

Run from this folder with Blender 4.3.2, Python, Pillow and NumPy.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py --copperwake /path/to/copperwake.glb
MERIDIAN_COPPERWAKE=/path/to/copperwake.glb blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

Run the validator without `--copperwake` for port-only checks. The reviewed ship, shoreline, water and mooring lines are Blender Cycles context and are separate from the port GLB. `renders/filament.png` is an isolated desktop Chromium Filament render. Android performance is untested.
