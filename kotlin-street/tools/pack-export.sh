#!/usr/bin/env bash
# Rebuilds the pack buildings (tools/buildings_pack.py showcase specs) into app/src/main/assets/pack/ (street.json + buildings/):
# needs the pack's 32x32 art (PACK_ART=.../ME_Theme_Sorter_32x32, see tools/kits.py). The scale street (the game's own
# buildings beside ours) is a preview-only street and is dropped here.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; tmp="$(mktemp -d)"
python3 "$here/make_buildings.py" --pack-showcase "$tmp"
out="$here/../app/src/main/assets/pack"; rm -rf "$out"; mkdir -p "$out/buildings"
python3 - "$tmp" "$out" <<'P'
import json, sys, glob, os, shutil
tmp, out = sys.argv[1:]
st = json.load(open(tmp + '/street.json'))
st['shops'] = [s for s in st['shops'] if s['sprite'].startswith('pk_')]; st['streets'] = st['streets'][:4] + st['streets'][5:]
json.dump(st, open(out + '/street.json', 'w'), indent=1)
m = json.load(open(tmp + '/buildings/models.json')); json.dump({k: v for k, v in m.items() if k.startswith('pk_')}, open(out + '/buildings/models.json', 'w'), sort_keys=True)
for f in glob.glob(tmp + '/buildings/pk_*.png'): shutil.copy(f, out + '/buildings/')
shutil.copy(tmp + '/buildings/windows.json', out + '/buildings/')
P
rm -rf "$tmp"; echo "wrote $out"
