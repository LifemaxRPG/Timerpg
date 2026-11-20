# Town churches — brick Gothic and white clapboard alternatives

Two original small-town American churches, delivered as **alternative buildings**, not an automatic renovation chain.

| Model | Architecture | Nave | Top height |
| --- | --- | --- | --- |
| `brick-church.glb` | Red-brick Gothic Revival church, corner tower, octagonal spire, deep pointed windows, stepped buttresses, projecting entry and rear parish hall | 13.968 × 29.9536 m | 30.1088 m |
| `country-church.glb` | Small white wooden church, round-arched windows, front belfry with a fixed bronze bell, modest porch and rear vestry | 9.0016 × 18.0032 m | 14.5888 m |

The designs suggest construction in 1880–1925 and 1870–1930 respectively, with continued use through the game's 1950–2020s years. Select the building and its history from actual town facts. These assets create no congregation, residents, services, named graves, construction events or religious affiliation.

The brick church has a real rear parish-hall shell with side and back outside entrances, a connecting opening beside the chancel, and a fixed serving counter. The smaller country church has a rear vestry with its own back entrance. Both include a clean nave, visible timber roof ribs, an aisle floor finish and a raised chancel with real steps. **Pews, chairs, tables and other loose furnishings are supplied by the game.** The upper tower/belfry is architectural detail only, without an access stair. The country bell is fixed geometry; no ringing or animation is implemented.

## Format and import

Metres; glTF Y up; +Z faces the street. Origin is at grade in the middle of the projecting entry porch's front edge. Main nave floors are 0.6208 m (brick) and 0.4656 m (country). The variants have their own footprints and entrance positions; choose one complete model, rather than overlaying them.

`model-info.json` combines the variant records. The individual records list materials, triangles by major part, small-prop counts, window profile areas/part bindings, name-panel region, room floors, door anchors and collision boxes. Every exterior threshold is **open**, without a closed door leaf. `door_main` faces the street, `door_back` faces the rear, and the brick variant also has `door_hall` at the parish-hall side entrance. Actual step/landing geometry supports all anchors at their stated floor heights.

`collision` is hidden render geometry containing closed boxes. **`boxes_gltf_y_up` is already in glTF metres**: do not apply the older Blender rotation. The current legacy collision reader needs an adapter for this key. Walls, roofs, floors and steps supply architectural collision (`use_the_walls`). Supplementary boxes cover buttresses, the chancel, fixed counter, sign, planters, bench, bins, downpipes and full-height tree-trunk columns. Interior blockers begin at their room floor. Raised tree canopies leave walking space below; no whole-canopy ground blocker is implied.

The complete rear, roof and service side are modeled. Lot groups include grade-level paths, eight/four marked parking references, a small planted garden, a bench, two 3D trees, the name board and service bins. Paths form one non-overlapping paving surface at their junctions. These are geometric references, not an implemented navigation, vehicle-routing or parking system. No graves or memorial names are invented.

## Pixel art and town name

One art pixel is exactly **2×2 texels**; one texel covers **0.0388 m**. PNGs use nearest/repeat sampling. Brick, slate, plaster and retinted paving use shared Timerpg/LimeZu swatches; original clapboard, stone trim, bronze, timber, glass, grass and sign art complement them. Retain the original shared-art credit and Timerpg-only licence. `source-materials/provenance.json` records source pack filenames and atlas rectangles; duplicate source PNG copies are omitted.

The glass has real pointed or round geometry. Its separate emission mask keeps outlines, mullions and unused atlas space black. Glow belongs only to small lamp faces/lenses; name boards, walls and roofs do not emit. Paving uses constant palette vertex colours with an ordinary repeating pixel texture, not baked lighting. The exporter explicitly retains active `COLOR_0` values.

Each model uses twelve materials. Its editable `.blend` packs all **thirteen images used by that variant**. The shared texture directory has fourteen PNGs because brick and white siding are alternatives. All geometry and supplemental art are original, with no real brands, congregation names or denomination symbols.

Exactly one `sign_name` node uses `textures/signs.png`, rectangle **[0, 0, 32, 20] art pixels** (double these values for texels). The modest roadside board reads `TOWN / CHURCH` in a hand-drawn 3×5 pixel alphabet. Repaint it per town, fitting or abbreviating longer names inside the region; preserve the UV rectangle and physical pixel density. The sign is not emissive.

## Rebuild and validation

Use Blender 4.3.2 and Python with `requirements.txt` installed. From the repository root:

```sh
python prototypes/brick-church/make_art.py
blender -b -t 4 --python prototypes/brick-church/build_church.py -- --variant=brick
blender -b -t 4 --python prototypes/brick-church/build_church.py -- --variant=country
python prototypes/brick-church/validate_model.py
python prototypes/brick-church/finish_renders.py
python prototypes/brick-church/package_preview.py
```

`--shots=none` exports the model/metadata/source without rendering; `--shots=day,nave` selects views. `--review` generates half-resolution drafts. Full renders are 1024×768 at 16 Cycles samples. Raw frames go to `/tmp/church-raw`; standard OIDN cleanup writes the single delivered PNG per view. It removes sampling noise from actual Blender renders and does not generate geometry or replace details. Raw duplicates, ZIPs, caches and Blender backups are excluded from publication.

`validate_model.py` reads the exported buffers and images. It checks actual triangles/material budgets, exact 2×2 PNG blocks, samplers, physical UV edge lengths, retained paving colours, no below-grade geometry, true arch areas and valid part bindings, open/facing entrances with actual floor support, exact glTF collision-box corners, small props and non-overlapping path triangles. See `validation.json` for the measured results.

The fourteen views show day, night, rear, above, person-height entry and nave for both designs, plus the parish hall and country belfry. `preview.html` links the sources, models and full-size views; `church-review.jpg` combines them. They are **Blender desktop renders, not Filament/S25 captures**. Per-town selection, furnishing, collision parsing, analytic lights, pane-mask handling, navigation and interactions remain game integration work; no app or simulation code changes are included.
