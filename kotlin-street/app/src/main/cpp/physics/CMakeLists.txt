# The game's physics (docs/VEHICLES.md): Jolt Physics and our wrapper (physics_core) with its JNI layer (physics_jni),
# one shared library, libtimerpg_physics.so.
#
# Android: added by the app's CMakeLists.txt (add_subdirectory), arm64-v8a with NEON (Jolt picks NEON on __aarch64__).
# Desktop: built on its own (this file as the project: gradle :desktop:physicsHost, or tools/physics-host-test.sh), a Linux
# x86-64 JNI library for the preview and the tests, and physics_host_test (the wrapper without the JVM).
#
# Where Jolt is: -DJOLT_DIR=... (Gradle passes jolt.dir from local.properties), else kotlin-street/third_party/JoltPhysics
# (tools/get-jolt.sh fetches the pinned release, v5.5.0).
cmake_minimum_required(VERSION 3.22)
if (CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(timerpg_physics CXX)
    set(CMAKE_CXX_STANDARD 17)
    set(CMAKE_CXX_STANDARD_REQUIRED ON)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    if (NOT CMAKE_BUILD_TYPE)
        set(CMAKE_BUILD_TYPE Release)
    endif()
endif()

if (NOT JOLT_DIR)
    set(JOLT_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/JoltPhysics")
endif()
get_filename_component(JOLT_DIR "${JOLT_DIR}" ABSOLUTE)
if (NOT EXISTS "${JOLT_DIR}/Jolt/Jolt.h" OR NOT EXISTS "${JOLT_DIR}/Build/CMakeLists.txt")
    message(FATAL_ERROR
        "Jolt Physics source not found at ${JOLT_DIR}.\n"
        "Run kotlin-street/tools/get-jolt.sh to fetch it into kotlin-street/third_party/JoltPhysics, "
        "or set jolt.dir=/path/to/JoltPhysics in kotlin-street/local.properties.")
endif()
message(STATUS "Jolt Physics: ${JOLT_DIR}")

macro(jolt_force name value)
    set(${name} ${value} CACHE BOOL "" FORCE)
endmacro()
# The library alone: no samples, tests, viewer, installs, debug renderer or profiler; no LTO (a static library linked
# into our shared one), no -Werror (another compiler's new warning must not break the app's build).
jolt_force(TARGET_UNIT_TESTS OFF)
jolt_force(TARGET_HELLO_WORLD OFF)
jolt_force(TARGET_PERFORMANCE_TEST OFF)
jolt_force(TARGET_SAMPLES OFF)
jolt_force(TARGET_VIEWER OFF)
jolt_force(ENABLE_INSTALL OFF)
jolt_force(ENABLE_ALL_WARNINGS OFF)
jolt_force(INTERPROCEDURAL_OPTIMIZATION OFF)
jolt_force(GENERATE_DEBUG_SYMBOLS OFF)
jolt_force(DEBUG_RENDERER_IN_DEBUG_AND_RELEASE OFF)
jolt_force(PROFILER_IN_DEBUG_AND_RELEASE OFF)
jolt_force(ENABLE_OBJECT_STREAM OFF)
jolt_force(USE_ASSERTS OFF)
jolt_force(FLOATING_POINT_EXCEPTIONS_ENABLED OFF)
# (a static Jolt in our one library; a plain variable, so the llama.cpp build's own cache setting is left alone)
set(BUILD_SHARED_LIBS OFF)
# The same simulation on the phone and the desktop (Jolt's cross-platform determinism: no fused multiply-add, precise
# floating point; it costs a few percent of the step, which is a fraction of a millisecond here).
jolt_force(CROSS_PLATFORM_DETERMINISTIC ON)
jolt_force(USE_AVX512 OFF)
# (Jolt's own Release flags: -O3 even in a debug APK, the physics must keep its step)
jolt_force(OVERRIDE_CXX_FLAGS ON)

add_subdirectory("${JOLT_DIR}/Build" jolt EXCLUDE_FROM_ALL)

add_library(physics_core STATIC physics_core.cpp)
target_include_directories(physics_core PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(physics_core PUBLIC Jolt)
# (as Jolt: no RTTI or exceptions, so our subclasses of its interfaces link against it; precise maths as it)
target_compile_options(physics_core PRIVATE -O3 -fno-rtti -fno-exceptions -ffp-contract=off)
set_target_properties(physics_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(timerpg_physics SHARED physics_jni.cpp)
target_link_libraries(timerpg_physics PRIVATE physics_core)
target_compile_options(timerpg_physics PRIVATE -O2 -fno-rtti -fno-exceptions)
set_target_properties(timerpg_physics PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
if (ANDROID)
    target_link_libraries(timerpg_physics PRIVATE log)
    target_link_options(timerpg_physics PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    find_package(JNI REQUIRED)
    target_include_directories(timerpg_physics PRIVATE ${JNI_INCLUDE_DIRS})
    find_package(Threads REQUIRED)
    target_link_libraries(timerpg_physics PRIVATE Threads::Threads)
    add_executable(physics_host_test physics_host_test.cpp)
    target_link_libraries(physics_host_test PRIVATE physics_core Threads::Threads)
    target_compile_options(physics_host_test PRIVATE -fno-rtti -fno-exceptions)
endif()
