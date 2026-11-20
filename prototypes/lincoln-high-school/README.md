# Lincoln High School — a campus through four eras

Four complete GLBs share one campus lot, origin and original main-portal position. Original geometry and supplemental pixel art, with retained Timerpg/LimeZu swatches. The requested Lincoln name is a fictional preview binding, not a recreation of a real school.

| State | Visual changes |
| --- | --- |
| 1925–1954 | Three-storey brick school, projecting raised entrance tower with a static school clock, paired tall sash windows, stone trim, carved name, 1925 datestone, walks, flagpole and a dirt baseball diamond with timber backstop. |
| 1955–1974 | Low ribbon-window classroom wing, cafeteria connection, bow-roof gym, west-side bus loop, ticket booth, football field, wooden bleachers and pole-mounted floodlights. |
| 1975–1999 | **Lincoln Consolidated** road monument replaces the active name binding; some original panes shrink inside infill frames. Three portable classroom shells with ramps, a 40-bay student car park, steel grandstand and press box. |
| 2000 onward | Portables removed; new glass-and-brick entrance vestibule, accessible side ramp, artificial turf, five-lane oval running track and a plain non-emissive scoreboard. |

Pick **one complete state**, according to actual town history. These years describe visual alternatives and do not create construction, pupils, renaming, staff, vehicles or sports events. The original 1925 block remains in every state. The revision adds a connected paving network, twelve rooted three-dimensional trees, front benches, period parking and complete field enclosures. Earlier states have a staff pull-in; the student lot replaces it in 1975. The ticket booth is a clean shell with a fixed counter and an open rear entrance. Its TICKETS label is a generic fixture label, separate from the one active school-name panel. The tower clock is static preview art, not a simulated clock.

The bus loop sits on the west side, clear of the school entrances. Its approximate 15 m outside turning radii and 4 m lane are geometric references, not a tested bus-routing system. Pedestrian junctions are exported as one non-overlapping paving surface. Tree collision uses full-height trunk columns, leaving room beneath the raised canopies; foliage and buildings remain actual geometry. No traffic, ticket sales or tree growth is implemented.

## Campus and interiors

The campus is **232.8 × 159.856 m**, enlarged from the repository's compact suggestion so the school, baseball infield and near-regulation football envelope can coexist with a later approximately 400 m track. The original school is 40.352 × 16.1408 m. Metres, glTF Y up, +Z toward the street; origin at grade in the middle of the campus front edge. Ground is at or above zero in every state. Hall floor is 0.6208 m.

The ground floor has an entrance hall, empty fixed trophy case, a long locker corridor, open classroom/office shells and a real dog-leg stair. Upper floors have exterior shells and slabs but are closed at the first upper landing; upper classroom navigation is not implemented. No loose desks, chairs, trophies or classroom stock are included. The gym has court lines, wall-mounted basketball backboards and hoops, fixed folding bleachers and a low stage. These are architectural fixtures.

The 1975 portable classrooms have raised floors and geometric 1:12 ramps. The 2000 entrance has a 1:12 side ramp. These are model dimensions, not an accessibility certification or tested navigation system. The original `door_main` remains at the same coordinates; it becomes the original **interior** portal in 2000, behind the new open `door_security`. `door_access` marks the side entrance. Every exterior doorway is left open without a closed panel or gate box.

Base paths are approximately 90 ft, and the football envelope includes both end zones at approximately 120 yd × 53⅓ yd. The compact baseball outfield is not a regulation facility. Track lane one is approximately 400 m; there are five lanes. Sport surfaces use constant palette vertex colours multiplied by a repeating pixel texture; the colours are not baked lighting. All markings are real thin geometry with physical UV density, not a flattened photograph of a field.

## Name and art bindings

Exactly one active `sign_name` node is present per state. Before consolidation, the name is on the tower frieze; from 1975 it moves to the roadside monument and the old frieze becomes blank stone. In `textures/signs.png`:

- Historic name: **[0, 0, 128, 12] art pixels**.
- Consolidated name: **[0, 24, 144, 18] art pixels**.

Double these coordinates for texture texels. Repaint the active region per town. The small datestone and plain scoreboard are separate regions in `textures/panels.png`; lettering and shapes use the original 5×7 pixel alphabet, without external typefaces. There are no real logos or mascots.

The raised entrance tower reaches approximately 20.87 m, while the original three-storey block and stone floor bands remain. Each classroom bay now contains two tall sash panes separated by a structural masonry pier; the 1975 infill bays retain paired smaller panes.

Every art pixel is exactly 2×2 texels; one texel covers 0.0388 m. All PNGs use nearest/repeat sampling. Twelve materials per state. Repeated window and locker panels share exported mesh data. Each `.blend` is editable and contains all thirteen packed texture images. No normal maps, photo textures, baked GI or glowing sport surfaces. The glass emission mask leaves mullions, the empty trophy case and fence mesh black. Non-window glow is limited to actual lamp faces/lenses and remains below 15 m².

`source-materials/` retains source atlas rectangles, pack filenames and credit. Cut textures live only in `textures/`, avoiding duplicate PNG copies. Shared LimeZu art is licensed for Timerpg only; retain original credit. Supplemental art and all geometry are original.

## Collision and runtime handoff

`model-info.json` includes all four state records, feature flags, door anchors, room bounds, material counts, triangles per part and collision boxes. Architectural walls, floors and stairs are the wall/floor collision source (`use_the_walls`). Supplemental full-height boxes cover the grandstand, press box, flagpole, field lights, fence segments, backstop, bins, sign and closed upper landing. Fence gates remain gaps.

**`boxes_gltf_y_up` is already in glTF metres.** The older `LandmarkKit.collisionTags` reader needs an adapter for this key; do not rotate it again from Blender coordinates. Each box gives its support floor, group and attachment. Model visibility and collision must follow the selected state together. The collision companion is hidden render geometry, not scenery.

`school-far.glb` is a very cheap **1955 silhouette only**, including the bow-roof gym, wing, field and grandstand. Its count is in `far-info.json`; it is below 400 triangles. It shares the campus origin but has no interiors, doors or collision. Automatic LOD selection, other-era far variants, school/security logic, state selection, furnishing, glass-mask lighting, navigation and sports/vehicle interactions remain game integration work. Nothing here changes the simulation or app runtime.

## Rebuild

Use Blender 4.3.2 and Python with `requirements.txt`. From the repository root:

```sh
python prototypes/lincoln-high-school/make_art.py
blender -b -t 4 --python prototypes/lincoln-high-school/build_school.py -- --state=1925
blender -b -t 4 --python prototypes/lincoln-high-school/build_school.py -- --state=1955
blender -b -t 4 --python prototypes/lincoln-high-school/build_school.py -- --state=1975
blender -b -t 4 --python prototypes/lincoln-high-school/build_school.py -- --state=2000
python prototypes/lincoln-high-school/validate_model.py
python prototypes/lincoln-high-school/finish_renders.py
python prototypes/lincoln-high-school/package_preview.py
```

`--shots=none` exports only. `--shots=day,above` selects views, and `--review` renders drafts. Full views are 1024×768 at 12 Cycles samples. Raw frames go to `/tmp/lincoln-school-raw/`; `finish_renders.py` uses standard OIDN to remove sampling noise and writes the single delivery PNG for each view. It does not generate or replace model detail. Raw duplicates, zips, caches and Blender backups are excluded from publication.

Validation reads actual exported buffers/images: triangle/material budgets, texture blocks and samplers, physical UV edge lengths, no geometry below grade, shared panel meshes, clear exterior apertures, exact collision-box corners, state features, shared origin/main portal and measured emissive triangle area. It also checks the far budget, the 96 paired sash panes, twelve tree groups, period ticket-booth presence and non-overlapping exported path triangles. See `validation.json` for actual measurements.

`preview.html` links all models, sources and **24 actual Blender desktop views**: day/night/rear/above in every era, plus the original entrance, locker corridor, gym, view from the bleachers, portables, new security entrance, ticket booth and bus loop. The review sheet combines them. These are **not Filament/S25 captures**; game integration and phone measurements remain pending.
