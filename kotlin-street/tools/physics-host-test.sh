#!/bin/sh
# Builds the physics (Jolt and our wrapper) for this computer and runs physics_host_test: a 1956 sedan on flat ground
# runs up to speed, brakes, turns, and the step time is printed. Out of tree, in build/physics-host.
set -e
cd "$(dirname "$0")/.."
cmake -S app/src/main/cpp/physics -B build/physics-host -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
ninja -C build/physics-host physics_host_test timerpg_physics
./build/physics-host/physics_host_test
