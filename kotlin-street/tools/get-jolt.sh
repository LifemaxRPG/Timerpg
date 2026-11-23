#!/bin/sh
# Fetches Jolt Physics (the game's physics: the cars, docs/VEHICLES.md) at the release the game is built and tested with,
# into kotlin-street/third_party/JoltPhysics (git-ignored). Or point the build elsewhere with `jolt.dir=...` in
# local.properties. Pinned: v5.5.0, commit 23dadd0e603f1b321142d4c74df07fce85064989.
set -e
cd "$(dirname "$0")/.."
TAG=v5.5.0
COMMIT=23dadd0e603f1b321142d4c74df07fce85064989
[ -d third_party/JoltPhysics ] || git clone --depth 1 --branch "$TAG" https://github.com/jrouwe/JoltPhysics.git third_party/JoltPhysics
got=$(git -C third_party/JoltPhysics rev-parse HEAD)
[ "$got" = "$COMMIT" ] || { echo "third_party/JoltPhysics is at $got, not $TAG ($COMMIT)" >&2; exit 1; }
echo "Jolt Physics $TAG ($COMMIT) in third_party/JoltPhysics"
