#!/bin/sh
# The Hexagon SDK for building the app's NPU backend (llama.cpp's ggml-hexagon): taken from Qualcomm's public Snapdragon
# toolchain image (ghcr.io/snapdragon-toolchain/arm64-android, the one llama.cpp's Snapdragon docs build with) without
# Docker: its SDK layer is streamed from the registry and only /opt/hexagon is unpacked (~3 GB). The SDK is Qualcomm's,
# under its own license. Then adds hexagon.sdk to local.properties.
#   tools/get-hexagon.sh [DIR]
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
dir="${1:-$here/third_party/hexagon}"
repo=snapdragon-toolchain/arm64-android; tag=v0.8
tok=$(curl -s "https://ghcr.io/token?scope=repository:$repo:pull" | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
idx=$(curl -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.oci.image.index.v1+json" "https://ghcr.io/v2/$repo/manifests/$tag" \
  | python3 -c "import json,sys; print([m['digest'] for m in json.load(sys.stdin)['manifests'] if m.get('platform',{}).get('architecture')=='amd64'][0])")
# the SDK's layer: the biggest one under opt/hexagon (the image's layers in order, largest first, probed by name)
for d in $(curl -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.oci.image.manifest.v1+json" "https://ghcr.io/v2/$repo/manifests/$idx" \
    | python3 -c "import json,sys; print(' '.join(l['digest'] for l in sorted(json.load(sys.stdin)['layers'], key=lambda l: -l['size'])))"); do
  if curl -sL -H "Authorization: Bearer $tok" "https://ghcr.io/v2/$repo/blobs/$d" | tar tz 2>/dev/null | head -5 | grep -q "opt/hexagon/"; then
    mkdir -p "$dir"
    curl -sL -H "Authorization: Bearer $tok" "https://ghcr.io/v2/$repo/blobs/$d" | tar xz -C "$dir" --strip-components=2 \
      --exclude='opt/hexagon/*/libs/itrace' --exclude='opt/hexagon/*/tools/debug' --exclude='root'
    sdk="$(ls -d "$dir"/*/ | head -1)"
    grep -q '^hexagon.sdk=' "$here/local.properties" 2>/dev/null || echo "hexagon.sdk=${sdk%/}" >> "$here/local.properties"
    echo "Hexagon SDK: ${sdk%/}"
    exit 0
  fi
done
echo "no Hexagon SDK layer found in $repo:$tag" >&2; exit 1
