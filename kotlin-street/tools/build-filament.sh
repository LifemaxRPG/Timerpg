#!/usr/bin/env bash
# Builds Filament for the Linux desktop trial, outside the repo: the engine with an OpenGL backend that renders
# headless through EGL (Mesa llvmpipe works, no GPU needed), matc, and filament-android's Java/JNI bindings rebuilt for
# the desktop JVM. Then compiles and runs the smoke test (filament/smoke). See filament/TOOLCHAIN.md.
#
#   tools/build-filament.sh            # everything (idempotent: finished steps are quick to repeat)
#   SKIP_APT=1 tools/build-filament.sh # when the -dev packages are already there
set -euo pipefail

VERSION=v1.81.0
SRC=${FILAMENT_SRC:-/home/user/filament-src}
BUILD=${FILAMENT_BUILD:-/home/user/filament-build}
DIST=${FILAMENT_DIST:-/home/user/filament-dist}
HERE=$(cd "$(dirname "$0")/.." && pwd)        # kotlin-street
JOBS=${JOBS:-$(nproc)}

if [ -z "${SKIP_APT:-}" ]; then
    # libc++ (Filament links it statically on Linux), EGL headers and Mesa's EGL driver (surfaceless llvmpipe)
    DEBIAN_FRONTEND=noninteractive apt-get install -y libc++-18-dev libc++abi-18-dev libegl-dev libegl-mesa0 \
        libgl-dev libgles-dev
fi

[ -d "$SRC" ] || git clone --depth 1 --branch "$VERSION" https://github.com/google/filament "$SRC"

# 0. No handle debug tags (the S25's freeze of 0.1.805, docs/TOWN.md "The freeze on the Meridian's steps"): 1.81.0 keeps a
# tag for every handle in a robin_map keyed by its id, whose age bits (27-28) sit above the table's mask; a pool slot
# reused over its ages puts several keys in one bucket, and once a few thousand small handles have been reused that way
# one insert's probe passes 8192 and the map rehashes, doubling, without end. The tags only name handles in error
# messages. The phone's AAR gets the same change as a binary patch (app/build.gradle.kts, patchFilamentAar).
HA="$SRC/filament/backend/src/HandleAllocator.cpp"
if ! grep -q "timerpg: no handle debug tags" "$HA"; then
    python3 - "$HA" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
for fn in ("writePoolHandleTag", "writeHeapHandleTag"):
    head = "void DebugTag::%s(HandleBase::HandleId key, ImmutableCString&& tag) noexcept {\n" % fn
    assert s.count(head) == 1, fn
    s = s.replace(head, head + "    return; // timerpg: no handle debug tags (the tag map's endless rehash, build-filament.sh)\n")
open(p, "w").write(s)
PY
fi

# 1. The engine and matc. EGL_ON_LINUX selects PlatformEGLHeadless (pbuffer swap chains, no X11).
mkdir -p "$BUILD"
if [ ! -f "$BUILD/build.ninja" ]; then
    CC=clang CXX=clang++ cmake -S "$SRC" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DFILAMENT_SUPPORTS_EGL_ON_LINUX=ON -DFILAMENT_SKIP_SDL2=ON -DFILAMENT_SKIP_SAMPLES=ON \
        -DFILAMENT_SUPPORTS_VULKAN=OFF -DFILAMENT_BUILD_TESTING=OFF \
        -DFILAMENT_ENABLE_MATDBG=OFF -DFILAMENT_ENABLE_FGVIEWER=OFF
fi
ninja -C "$BUILD" -j"$JOBS" filament geometry filament-generatePrefilterMipmap matc

mkdir -p "$DIST/bin" "$DIST/lib" "$DIST/obj"
cp "$BUILD/tools/matc/matc" "$DIST/bin/"

# 2. The JNI library: filament-android's C++ bindings, minus the Android window code, against the desktop JDK.
JDK=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
A="$SRC/android"
CXXFLAGS=(-std=c++20 -O2 -fPIC -stdlib=libc++ -fvisibility=hidden -DFILAMENT_SUPPORTS_VULKAN=0
    -I"$A" -I"$JDK/include" -I"$JDK/include/linux"
    -I"$SRC/filament/include" -I"$SRC/filament/backend/include" -I"$SRC/libs/utils/include"
    -I"$SRC/libs/math/include" -I"$SRC/libs/geometry/include" -I"$SRC/libs/filabridge/include"
    -I"$SRC/libs/ibl/include" -I"$SRC/third_party/robin-map" -I"$SRC/third_party/perfetto")
objs=()
# VirtualMachineEnv is only compiled into the backend on Android; the JNI callbacks need it here too.
for f in "$A"/filament-android/src/main/cpp/*.cpp "$A"/common/*.cpp "$SRC/filament/backend/src/VirtualMachineEnv.cpp" \
         "$HERE/filament/jni/DesktopNativeWindow.cpp"; do
    o="$DIST/obj/$(basename "${f%.cpp}").o"
    if [ ! -f "$o" ] || [ "$f" -nt "$o" ]; then echo "  c++ $(basename "$f")"; clang++ "${CXXFLAGS[@]}" -c "$f" -o "$o" & fi
    objs+=("$o")
    while [ "$(jobs -r | wc -l)" -ge "$JOBS" ]; do wait -n; done
done
wait
lib() { find "$BUILD" -name "lib$1.a" | head -1; }
# Order matters for static libraries: users first, then what they use.
clang++ -shared -stdlib=libc++ -o "$DIST/lib/libfilament-jni.so" "${objs[@]}" \
    -Wl,--version-script="$A/filament-android/libfilament-jni.map" \
    -Wl,--start-group \
    "$(lib filament-generatePrefilterMipmap)" "$(lib filament)" "$(lib backend)" "$(lib filaflat)" \
    "$(lib filabridge)" "$(lib ibl-lite)" "$(lib geometry)" "$(lib utils)" "$(lib zstd)" \
    $(for l in abseil perfetto bluegl shaders smol-v mikktspace meshoptimizer; do p=$(lib $l); [ -n "$p" ] && echo "$p"; done) \
    -Wl,--end-group \
    -static-libgcc /usr/lib/llvm-18/lib/libc++.a /usr/lib/llvm-18/lib/libc++abi.a \
    -lEGL -lGL -ldl -lpthread -Wl,-z,defs -Wl,-z,noexecstack

# 3. The Java side: filament-android's classes, minus the Android-only platform and helpers.
ANN=$(find ~/.gradle/caches -name 'annotation-jvm-1.8.1.jar' | head -1)
[ -n "$ANN" ] || { echo "need androidx.annotation:annotation-jvm (any 1.x jar) in the Gradle cache"; exit 1; }
cp "$ANN" "$DIST/lib/androidx-annotation.jar"
J="$A/filament-android/src/main/java/com/google/android/filament"
rm -rf "$DIST/classes"; mkdir -p "$DIST/classes"
{ find "$J" -maxdepth 1 -name '*.java' ! -name 'AndroidPlatform*.java'; find "$J/proguard" -name '*.java'; } > "$DIST/sources.txt"
javac --release 17 -nowarn -cp "$ANN" -d "$DIST/classes" @"$DIST/sources.txt"
jar --create --file "$DIST/lib/filament-java.jar" -C "$DIST/classes" .

# 4. The smoke test: a building's front and side, NEAREST-sampled, rendered headless to a PNG.
# mobile = OpenGL ES shaders: the EGL headless context is GLES 3.2, and the same .filamat runs on the phone
"$DIST/bin/matc" -p mobile -a opengl -o "$DIST/pixelquad.filamat" "$HERE/filament/smoke/pixelquad.mat"
B="$HERE/app/src/main/assets/buildings"
OUT=${SMOKE_OUT:-$DIST/smoke.png}
EGL_PLATFORM=surfaceless java -Djava.library.path="$DIST/lib" -cp "$DIST/lib/filament-java.jar:$ANN" \
    "$HERE/filament/smoke/QuadSmoke.java" "$DIST/pixelquad.filamat" "$B/b1_front.png" "$B/b1_side.png" "$OUT"
