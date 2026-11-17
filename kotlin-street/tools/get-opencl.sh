#!/bin/sh
# The OpenCL headers and a link stub for building the app's Adreno GPU backend (llama.cpp's ggml-opencl): Khronos'
# headers, and their ICD loader built for Android arm64 only to link against (the APK does not carry it: the phone's own
# libOpenCL.so is used at run time). Then adds opencl.headers / opencl.lib to local.properties.
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
dir="${1:-$here/third_party}"
ndk="${ANDROID_NDK:-$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/ndk/* | sort -V | tail -1)}"
mkdir -p "$dir"
[ -d "$dir/OpenCL-Headers" ] || git clone -q --depth 1 https://github.com/KhronosGroup/OpenCL-Headers "$dir/OpenCL-Headers"
[ -d "$dir/OpenCL-ICD-Loader" ] || git clone -q --depth 1 https://github.com/KhronosGroup/OpenCL-ICD-Loader "$dir/OpenCL-ICD-Loader"
cmake -S "$dir/OpenCL-ICD-Loader" -B "$dir/OpenCL-ICD-Loader/build-android" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DOPENCL_ICD_LOADER_HEADERS_DIR="$dir/OpenCL-Headers" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=24 -DANDROID_STL=c++_shared -DBUILD_TESTING=OFF >/dev/null
cmake --build "$dir/OpenCL-ICD-Loader/build-android" --target OpenCL
grep -q '^opencl.headers=' "$here/local.properties" 2>/dev/null || echo "opencl.headers=$dir/OpenCL-Headers" >> "$here/local.properties"
grep -q '^opencl.lib=' "$here/local.properties" 2>/dev/null || echo "opencl.lib=$dir/OpenCL-ICD-Loader/build-android/libOpenCL.so" >> "$here/local.properties"
echo "OpenCL ready: $dir"
