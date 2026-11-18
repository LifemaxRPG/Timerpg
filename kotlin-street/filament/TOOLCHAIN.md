# Filament toolchain (desktop, headless)

The trial of moving the street's drawing to Filament needs Filament on the Linux build machine: to compile materials
and to render the scene headless (no GPU, Mesa's software OpenGL) so renders can be judged by eye, the way
`gradle :desktop:preview` works today. This is that toolchain. Everything big lives outside the repo.

One command does all of it (idempotent; finished steps are quick to repeat):

    kotlin-street/tools/build-filament.sh           # SKIP_APT=1 when the -dev packages are already installed

| What | Where |
|---|---|
| Filament v1.81.0 source (shallow clone, one line patched, see below) | `/home/user/filament-src` |
| CMake/Ninja build | `/home/user/filament-build` |
| `matc`, `libfilament-jni.so`, `filament-java.jar`, `androidx-annotation.jar`, smoke output | `/home/user/filament-dist` |
| Desktop stand-in for the Android window code | `kotlin-street/filament/jni/DesktopNativeWindow.cpp` |
| Smoke test (material + Java program) | `kotlin-street/filament/smoke/` |

Version: **v1.81.0**, the same as the Maven Central AARs (`com.google.android.filament:filament-android:1.81.0`), so
a `.filamat` compiled here loads on the phone too.

## Steps and exact flags

1. Packages (Ubuntu 24.04): `apt-get install -y libc++-18-dev libc++abi-18-dev libegl-dev libegl-mesa0 libgl-dev libgles-dev`.
   Already present: clang 18.1.3, cmake 3.28.3, ninja, OpenJDK 21, Mesa 25.2.8 (llvmpipe, LLVM 20).
2. `git clone --depth 1 --branch v1.81.0 https://github.com/google/filament /home/user/filament-src` (45 s).
3. Patch (the script applies it): in the root `CMakeLists.txt`, `if (IS_MOBILE_TARGET)` above `set(MATC_TARGET mobile)`
   becomes `if (IS_MOBILE_TARGET OR FILAMENT_SUPPORTS_EGL_ON_LINUX)`. Why: with EGL on Linux Filament compiles its
   OpenGL **ES** backend (llvmpipe gives a GLES 3.2 context), but its built-in materials were still compiled for
   desktop GL, so `Engine.create()` segfaulted in `FMaterial::getDefaultInstance()` (no default material).
4. Configure and build only what is needed:

        CC=clang CXX=clang++ cmake -S filament-src -B filament-build -G Ninja -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
            -DFILAMENT_SUPPORTS_EGL_ON_LINUX=ON -DFILAMENT_SKIP_SDL2=ON -DFILAMENT_SKIP_SAMPLES=ON \
            -DFILAMENT_SUPPORTS_VULKAN=OFF -DFILAMENT_BUILD_TESTING=OFF \
            -DFILAMENT_ENABLE_MATDBG=OFF -DFILAMENT_ENABLE_FGVIEWER=OFF
        ninja -C filament-build filament geometry filament-generatePrefilterMipmap matc

   `FILAMENT_SUPPORTS_EGL_ON_LINUX` selects `PlatformEGLHeadless` (pbuffer swap chains, no X11). OSMesa is the
   other headless option Filament has; it was not needed.
5. JNI library: Filament no longer ships desktop Java bindings, so the script compiles filament-android's own JNI
   C++ (`android/filament-android/src/main/cpp/*.cpp`, `android/common/*.cpp`) against the desktop JDK, minus
   `nativewindow/Android.cpp` (replaced by `DesktopNativeWindow.cpp`, which returns no window: only headless swap
   chains are used), plus `filament/backend/src/VirtualMachineEnv.cpp` (only built into the backend on Android, but
   the JNI callbacks need it). Flags: `-std=c++20 -O2 -fPIC -stdlib=libc++ -fvisibility=hidden`; linked with
   filament-android's version script, the static Filament libraries in a `--start-group`, static libc++/libc++abi,
   `-lEGL -lGL -ldl -lpthread -z defs -z noexecstack`. Result: `libfilament-jni.so`, 4.8 MB.
6. Java classes: filament-android's `com.google.android.filament` sources (+ `proguard/UsedByNative`) minus
   `AndroidPlatform*.java`, `javac --release 17` against `androidx.annotation:annotation-jvm:1.8.1` from the Gradle
   cache, into `filament-java.jar` (280 KB). The `android/` helper subpackage (UiHelper, TextureHelper...) is left
   out. Filament's `Platform` class already picks a desktop path when the JVM is not Android.
7. Materials: `matc -p mobile -a opengl -o x.filamat x.mat`. `-p mobile` (GLES shaders) is required for this
   headless GLES context, and is what the phone needs anyway. A `-p desktop` material fails to build on this context.

## Smoke test (done, looked at)

`filament/smoke/QuadSmoke.java` + `pixelquad.mat` (unlit, masked, one `sampler2d`): sprite b1's front (224x224) and
side (120x224) as two quads meeting at a corner, a perspective camera from the front right, `TextureSampler(NEAREST,
NEAREST)`, rendered at 2340x1080 into a headless swap chain (`SwapChainFlags.CONFIG_READABLE`) and read back with
`Renderer.readPixels`. Run as a single-file Java program:

    EGL_PLATFORM=surfaceless java -Djava.library.path=/home/user/filament-dist/lib \
        -cp /home/user/filament-dist/lib/filament-java.jar:/home/user/filament-dist/lib/androidx-annotation.jar \
        kotlin-street/filament/smoke/QuadSmoke.java pixelquad.filamat b1_front.png b1_side.png out.png   # -Dexact=1

Result, judged by eye at full size: the bait shop's front and side wall form a crisp box corner, hard pixel edges, no
blur; the sky clear colour behind. Colour check against the sprites' palette (building pixels only):

- default (post-processing on, sRGB textures, linear tone mapper, exposure 1, no dithering): 94.3% of pixels exact,
  the rest off by 1-3 levels in 255 (a few dark ones by up to 7). The colour grading LUT does that.
- `-Dexact=1` (post-processing off, plain RGBA8 textures): **0 of 554,882 pixels** differ from the palette.

Time on this 4-core server with llvmpipe: 3 frames + read-back at 2340x1080 in 114-184 ms warm, 580 ms on the first run
(shader compile). Server numbers only; nothing has run on the S25 yet.

From Kotlin: a Gradle `kotlin("jvm")` project with
`implementation(files(".../filament-java.jar", ".../androidx-annotation.jar"))`, `-Djava.library.path=.../lib` and
the environment `EGL_PLATFORM=surfaceless` built and ran a Kotlin main (clear, `readPixels`, exact pixel back),
`gradle --offline run`. For the repo's `:desktop` module the same three lines apply (not wired in yet).

## Cost

| | Time (4 cores) | Disk |
|---|---|---|
| clone | 45 s | 2.3 GB (`filament-src`, mostly third_party) |
| engine + matc build | 7 min 14 s wall, 27 min CPU (770 steps) | 159 MB (`filament-build`) |
| rebuild after the patch | 11 s | |
| JNI + jar + material + smoke | ~20 s | 19 MB (`filament-dist`) |

## What does not work, or needs care

- **No upstream desktop Java bindings** in v1.81.0 (the old `filament-java` is gone). The bindings here are rebuilt
  from the Android sources; `Stream`, `SwapChain` from a Java surface, `TextureHelper` (Bitmap) and the `android.*`
  helpers do not work on the desktop. Headless swap chains, textures, materials, renderables, cameras, lights,
  `readPixels` do.
- **The JVM does not exit** unless `engine.destroy()` is called (Filament's threads stay attached). And Filament
  aborts the process on teardown out of order (e.g. destroying a material while instances live): destroy entities,
  instances and buffers first.
- **`readPixels`' callback** runs on a later `flushAndWait()`, not inside the frame: flush until it has run.
- **Desktop GL is not available** with this setup: EGL on Linux means the GLES backend. That matches the phone, so it
  is a feature for this trial, but desktop-only features are not testable here. Vulkan was not built.
- `FEngine feature level: 1` by default (backend supports 3); ask for level 3 in `Engine.Builder` when a feature
  needs it.
- Post-processing on = not bit-exact colours (see above); fine for lit scenes, but flat pixel art that must match
  the sprite exactly wants post-processing off or a custom LUT-free path.
- NEAREST at grazing angles: the side wall, minified (more texels than screen pixels), shows the sprite's rows as
  uneven stripes. Mipmaps (with NEAREST_MIPMAP_NEAREST) or a supersampled render are the likely answers; not tried.
- Not tried yet: gltfio, filamat at runtime, shadows, IBL (cmgen not built), Android-side use of the AAR.
