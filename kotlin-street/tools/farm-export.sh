#!/usr/bin/env bash
# Rebuilds the farm models (tools/buildings_farm.py) into app/src/main/assets/farm/ (street.json + buildings/), next to the crop
# atlas (tools/make_farm_crops.py). Needs the Modern Farm 32x32 art (FARM_ART, see buildings_farm.py).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; tmp="$(mktemp -d)"
python3 "$here/make_buildings.py" --jobs=1 --farm-showcase "$tmp"
out="$here/../app/src/main/assets/farm"; mkdir -p "$out/buildings"; rm -rf "$out/buildings"/*; rm -f "$out/street.json"
cp "$tmp/street.json" "$out/street.json"
cp "$tmp"/buildings/models.json "$tmp"/buildings/windows.json "$out/buildings/"
cp "$tmp"/buildings/fm_*.png "$out/buildings/"
rm -rf "$tmp"; echo "wrote $out"
