# Small-town bank — two complete period states

An original one-storey classical bank: ashlar stone street front, two tall columns, carved name frieze and a real faceted arched transom. Matching ashlar stone sides and rear, staff entry, roof equipment and service apron finish the whole building. Inside are a high banking hall, four fixed teller wickets, two unfurnished offices and a fixed circular vault door with hinges, locking bars and a three-spoke wheel.

| File | Visual state | Visible triangles | Materials |
| --- | --- | ---: | ---: |
| `small-town-bank-1920s.glb` | Original bank, design period 1900–1930 | 3,119 | 12 |
| `small-town-bank-drive-through.glb` | Same bank with a 1965+ two-lane side canopy | 3,749 | 12 |

Choose **one complete GLB**. The later version preserves the original building, vault, name and exterior door anchors. Its canopy has numbered lanes, concrete islands, two document terminals, supported pneumatic conduit and a wall-mounted night deposit fixture. Eligibility in 1965 does not cause construction: actual town history and sufficient side land must select the state.

## Import contract

Metres; glTF Y up and +Z toward the street. Main footprint 13.968 × 18.0032 m. Grade is zero; hall floor is 0.3104 m. Both exterior thresholds are open, framed in bronze/stone, without closed door leaves or gate blockers. The closed **interior** `vault_door` is fixed architecture, not an entrance or working lock. Side offices are shells, without loose furniture or stock. Teller counters and fixtures are fixed.

`model-info.json` combines both state records. It gives `door_main`, `door_staff`, `lot`, `collision`, the fixed vault group and later-state lane references. Collision companions are hidden render geometry. **`boxes_gltf_y_up` is already in exported glTF coordinates**; do not apply the legacy Blender rotation. The current `LandmarkKit.collisionTags` reader requires an adapter for this key. The architecture supplies walls/floors; boxes supplement columns, teller line, vault and fixtures. Interior blockers start at the hall floor. Exterior fixtures start at grade.

The lane centre references are clear of the supplemental collision boxes for a 1.8624 m wide × 1.8 m high reference vehicle. Lowest checked overhead clearance above lane pavement is 3.9964 m. These are geometric checks, not a tested vehicle-routing system. Lane direction is glTF −Z. Terminal islands and canopy supports must remain collision obstacles.

## Repaint the town name

One `sign_name` node/material and one region in `textures/signs.png`: **[0, 0, 128, 18] art pixels**, or [0, 0, 256, 36] texture texels. Replace `TOWN STATE BANK` with the generated bank name within this region. Preserve its UV rectangle, stone surround and 2×2 texel art grid. The carved lettering is non-emissive. Lane numbers and deposit/envelope symbols are separate utility art, not extra name bindings.

## Art and source

0.0388 m per texture texel; every art pixel is exactly 2×2 texels. PNGs use nearest/repeat sampling, with no normal maps or baked lighting. Twelve materials, thirteen packed source images, including the pane-only emission mask. Both `.blend` files are editable and packed. Geometry and supplemental pixel art are original. Shared LimeZu swatch cuts and their provenance are retained in `source-materials/`; retain their original credit and Timerpg-only usage restriction.

## Rebuild and verify

Use Blender 4.3.2 and Python with `requirements.txt` installed. Run from the repository root:

```sh
python prototypes/small-town-bank/make_art.py
blender -b -t 6 --python prototypes/small-town-bank/build_bank.py
blender -b -t 6 --python prototypes/small-town-bank/build_bank.py -- --later
python prototypes/small-town-bank/validate_model.py
```

For export-only use `-- --shots=none` (or `-- --later --shots=none`). `--review` produces half-resolution drafts. Full renders are 1280×960, 20 samples. Clean the nine raw PNGs with `finish_renders.py` using the optional OIDN dependency, then run `package_preview.py`. OIDN removes sampling noise from actual renders; it does not generate scenes. `preview.html` links every view, GLB and source.

`validate_model.py` reads the exported GLB buffers and textures. It checks triangle/material budgets, UV density, 2×2 art blocks, sampler modes, architectural footprints, arched glass, teller panes, door thresholds, fixed vault coverage, paving overlaps, lane reference corridors, overhead clearance and matching original geometry across both states. See `validation.json` for actual results.

## Runtime handoff

No account balances, money, security, transactions, deposit collection, tube transport, residents or bank ownership are implemented or invented. State selection, navigation, glTF collision parsing, lights, glass emission-mask handling and any vehicle or banking interactions remain game integration work. The bank model does not alter simulation code.

All nine previews are actual **Blender desktop renders**, including day, blue hour, rear, above, person height, banking hall, vault, later exterior and driver eye. They are **not Filament/S25 captures**. Keep that distinction when evaluating in-game lighting and performance.
