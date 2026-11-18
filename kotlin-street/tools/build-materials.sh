#!/usr/bin/env bash
# Compiles the Filament street's materials (filament/materials/*.mat) into app/src/main/assets/filament/*.filamat,
# for OpenGL ES and Vulkan, mobile shaders (what the phone runs and what the headless desktop context needs).
# The outputs are committed, so building the app needs no matc. MATC=/path/to/matc to use another.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
matc="${MATC:-/home/user/filament-dist/bin/matc}"
out="$here/app/src/main/assets/filament"
mkdir -p "$out"
for src in "$here"/filament/materials/*.mat; do
    name="$(basename "$src" .mat)"
    "$matc" -p mobile -a opengl -a vulkan -o "$out/$name.filamat" "$src"
    echo "$name.filamat $(stat -c %s "$out/$name.filamat") bytes"
done
