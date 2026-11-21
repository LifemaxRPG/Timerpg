# MV Copperwake

An original modern cruise liner with a navy hull, copper twin funnels, white tiered decks and teak walkways. The ship is 309 m long and 39.26 m wide across the rails. From the keel to the funnel outlets it measures 54.50 m. The waterline sits 9.35 m above the keel.

Both outboard promenades have gangway openings and run beneath the launch boats. Paired stern stairs connect the weather deck and six passenger-deck landings. The upper deck contains a swimming pool, two spas, a games court, shaded café tables and a walk-in observation lounge. A modeled stateroom with two openings sits on the forward weather deck. The model includes a continuous underside, stern propulsion pods, lifeboat mounts, railings and a wraparound wheelhouse.

The GLB uses 12 matte materials and **19,524 visible triangles**. This ship exceeds the ordinary landmark budget by design. The cabin façades repeat a physically scaled wrap of balcony bays so individual stateroom rows do not require individual meshes. All twelve images use nearest/repeat filtering and whole 2×2 art pixels at 0.0388 m per texel. Teak derives from Timerpg's shared timber swatch.

## Integration handoff

The origin is amidships at the keel. The bow points towards glTF +Z. Place the keel 9.35 m below the surface in calm water. `model-info.json` records both boarding points, room portals, a pool-deck anchor, and the twelve stair runs. The lounge and sample stateroom are modeled spaces. The other cabin blocks are closed exterior volumes. Dock approach, gangway deployment, deck collision, ocean routes and continent transitions need game integration.

## Rebuild and review

Run in this folder in a Timerpg checkout. Texture and validation scripts use Pillow and NumPy. The packed source and render script use Blender 4.3.2.

```sh
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
blender -b --python-exit-code 1 --python render_model.py
python package_review.py
```

The Blender reviews add desktop preview lighting, sea, wake and scale figures outside the asset. The separate Filament view renders the GLB in standalone desktop WebGL. The validator checks dimensions, physical UV scale, embedded texture blocks, both gangway cuts, the room floors and all 216 stair treads. Android performance and gameplay are untested.
