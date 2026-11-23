# C002 · Lark Bend Grain Elevator

Built the 1925 timber plant, the 1950 timber-and-concrete expansion, and the 1990 concrete co-op as three era states. The six 35.72 m bins sit below a conveyor gallery and a 44.71 m headhouse. All eras share an open scale office, truck scales and one rail siding reference. The timber and concrete plants have separate loading spouts. The silo lettering is a repaintable atlas region applied across three facets.

The prototype contains nine GLBs, three editable packed Blender sources, the original texture and build scripts, and a review sheet showing seven views per era. `model-info.json` lists the local lot, doorway transforms, per-model collision boxes, repaintable panels and rail siding coordinates.

## Verification

- The prototype validator passed all nine GLBs. It checks era features, progressive LOD triangle counts, open office apertures, collider transforms and bounds, nearest/repeat sampling, 2×2 art pixels, 0.0388 m UV scale and pane-only emission.
- Khronos glTF Validator reported zero errors and warnings across all nine assets. The largest export has 3,228 visible triangles and 10 materials. Each Blender source opened with all 10 textures packed.
- Inspected the day, night, rail-side, overhead, eye-level, interior and painted-name views in Blender Cycles. These previews are desktop renders.

Rail placement and vehicles, freight handling, operating machinery, and runtime Filament and Android integration are for the game handoff. The office is the playable interior shell. Silo interiors and stair or ladder traversal are outside the model scope.

## Usage

- Model and setting: Codex GPT-6 family. Exact variant and reasoning setting not visible.
- Tokens and plan usage: not visible.
- Effort: one turn, approximately nine minutes, with about 30 direct tool calls through delivery.
- Rework: initial delivery, zero review rounds.
