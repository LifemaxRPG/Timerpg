#!/usr/bin/env bash
# Builds the whole town's assets (tools/town-config.ts, or the seed and year given) into kotlin-street/build-town/<seed>/:
# street.json v2 (export-town.ts: the graph, lots and yards) and buildings/ (make_buildings.py, every core). The app still
# ships the v1 street in app/src/main/assets; this folder is what T10 will ship once the town is wired in. Not in git.
# Usage: kotlin-street/tools/build-town.sh [seed] [year]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; root="$here/../.."
seed="${1:-$(cd "$root" && npx tsx -e "import { TOWN } from './kotlin-street/tools/town-config'; console.log(TOWN.seed)")}"
out="$here/../build-town/$seed"; mkdir -p "$out"
(cd "$root" && npx tsx kotlin-street/tools/export-town.ts "$seed" ${2:-} > "$out/street.json.tmp") && mv "$out/street.json.tmp" "$out/street.json"
time python3 "$here/make_buildings.py" "$out/street.json" "$out/buildings" > "$out/buildings.log"
echo "built $(ls "$out/buildings" | wc -l) files for $(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))['shops']))" "$out/street.json") buildings in $out"
# the yards from above, for checking by eye
python3 "$here/yards-debug.py" "$out/street.json" "$out/yards.png" 0.25
