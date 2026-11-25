#!/usr/bin/env bash
# Compiles the Filament street's materials (filament/materials/*.mat) into app/src/main/assets/filament/*.filamat,
# for OpenGL ES and Vulkan, mobile shaders (what the phone runs and what the headless desktop context needs).
# The outputs are committed, so building the app needs no matc. MATC=/path/to/matc to use another.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
matc="${MATC:-/home/user/filament-dist/bin/matc}"
out="$here/app/src/main/assets/filament"
mkdir -p "$out"
build() {
    "$matc" -p mobile -a opengl -a vulkan -o "$out/$1.filamat" "$2"
    echo "$1.filamat $(stat -c %s "$out/$1.filamat") bytes"
}
for src in "$here"/filament/materials/*.mat; do
    build "$(basename "$src" .mat)" "$src"
done
# The opaque twins of the masked town materials (docs/NOVEL_OPTIMIZATIONS.md idea 1): the same source with
# `blending : opaque`, for the batches whose pictures have no cut-out texels under their texture coordinates (Cutouts.kt).
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
for name in street_lit shadow_proxy; do
    sed -e 's/^\( *name : [A-Za-z]*\),/\1Opaque,/' -e 's/blending : masked/blending : opaque/' -e '/maskThreshold/d' \
        "$here/filament/materials/$name.mat" > "$tmp/${name}_opaque.mat"
    grep -q 'blending : opaque' "$tmp/${name}_opaque.mat"
    build "${name}_opaque" "$tmp/${name}_opaque.mat"
done
