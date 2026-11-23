#!/bin/sh
# Fetches QuickJS-ng (the embedded JavaScript engine that runs the game's TypeScript simulation, bundled as
# assets/sim/sim.js: docs/LOOP.md, "The JavaScript bridge") at the release the game is built and tested with, into
# kotlin-street/third_party/quickjs-ng (git-ignored). Or point the build elsewhere with `quickjs.dir=...` in
# local.properties. Pinned: v0.18.0, commit d7215b0ad3fc2c562e8317fd9bca058fd4863804.
set -e
cd "$(dirname "$0")/.."
TAG=v0.18.0
COMMIT=d7215b0ad3fc2c562e8317fd9bca058fd4863804
[ -d third_party/quickjs-ng ] || git clone --depth 1 --branch "$TAG" https://github.com/quickjs-ng/quickjs.git third_party/quickjs-ng
got=$(git -C third_party/quickjs-ng rev-parse HEAD)
[ "$got" = "$COMMIT" ] || { echo "third_party/quickjs-ng is at $got, not $TAG ($COMMIT)" >&2; exit 1; }
echo "QuickJS-ng $TAG ($COMMIT) in third_party/quickjs-ng"
