#!/usr/bin/env bash
# Builds the four name variants of the works (make_art.py VARIANTS) into the app's assets:
# foundry-factory-<name>.glb and one model-info.json (the geometry is the same; only the painted name differs).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; out="$here/../../../app/src/main/assets/landmarks/foundry-factory"
mkdir -p "$out"
for i in 0 1 2 3; do
  name=$(cd "$here" && python3 -c "import os; os.environ['FACTORY_VARIANT']='$i'; import make_art; print(make_art.VARIANT[0])")
  (cd "$here" && FACTORY_VARIANT=$i python3 make_art.py > /dev/null && FACTORY_OUT="foundry-factory-$name.glb" python3 build_model.py > /dev/null)
  mv "$here/foundry-factory-$name.glb" "$out/"
done
mv "$here/model-info.json" "$out/model-info.json"
rm -rf "$here/textures" "$here/source-materials" "$here/__pycache__"
ls -la "$out"
