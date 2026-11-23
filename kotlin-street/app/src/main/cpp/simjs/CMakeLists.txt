# The game's simulation in JavaScript (docs/LOOP.md, "The JavaScript bridge"): QuickJS-ng and our JNI wrapper
# (sim_js.cpp), one shared library, libtimerpg_simjs.so, that runs assets/sim/sim.js (the TypeScript crime, plan and
# police code bundled by tools/bundle-sim.mjs).
#
# Android: added by the app's CMakeLists.txt (add_subdirectory), arm64-v8a.
# Desktop: built on its own (this file as the project: gradle :desktop:simjsHost), a Linux x86-64 JNI library for the
# preview and the tests, which load it by -Dtimerpg.simjs.lib (as the physics).
#
# Where QuickJS-ng is: -DQUICKJS_DIR=... (Gradle passes quickjs.dir from local.properties), else
# kotlin-street/third_party/quickjs-ng (tools/get-quickjs.sh fetches the pinned release, v0.18.0).
cmake_minimum_required(VERSION 3.22)
if (CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(timerpg_simjs C CXX)
    set(CMAKE_CXX_STANDARD 17)
    set(CMAKE_CXX_STANDARD_REQUIRED ON)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    if (NOT CMAKE_BUILD_TYPE)
        set(CMAKE_BUILD_TYPE Release)
    endif()
endif()

if (NOT QUICKJS_DIR)
    set(QUICKJS_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/quickjs-ng")
endif()
get_filename_component(QUICKJS_DIR "${QUICKJS_DIR}" ABSOLUTE)
if (NOT EXISTS "${QUICKJS_DIR}/quickjs.h" OR NOT EXISTS "${QUICKJS_DIR}/quickjs.c")
    message(FATAL_ERROR
        "QuickJS-ng source not found at ${QUICKJS_DIR}.\n"
        "Run kotlin-street/tools/get-quickjs.sh to fetch it into kotlin-street/third_party/quickjs-ng, "
        "or set quickjs.dir=/path/to/quickjs-ng in kotlin-street/local.properties.")
endif()
message(STATUS "QuickJS-ng: ${QUICKJS_DIR}")

# The engine itself: its four sources compiled here, as its own CMakeLists.txt does (the same defines and flags), but
# without its command-line tools, tests or libc module (the bundle needs no files, timers or OS access). Static, inside
# our one library; -O2 even in a debug APK.
add_library(quickjs STATIC
    "${QUICKJS_DIR}/dtoa.c"
    "${QUICKJS_DIR}/libregexp.c"
    "${QUICKJS_DIR}/libunicode.c"
    "${QUICKJS_DIR}/quickjs.c")
target_include_directories(quickjs PUBLIC "${QUICKJS_DIR}")
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE QUICKJS_NG_BUILD)
set_target_properties(quickjs PROPERTIES C_STANDARD 11 C_EXTENSIONS ON C_VISIBILITY_PRESET hidden POSITION_INDEPENDENT_CODE ON)
target_compile_options(quickjs PRIVATE -O2 -funsigned-char -w)
find_package(Threads REQUIRED)
target_link_libraries(quickjs PUBLIC m Threads::Threads ${CMAKE_DL_LIBS})

add_library(timerpg_simjs SHARED sim_js.cpp)
target_link_libraries(timerpg_simjs PRIVATE quickjs)
target_compile_options(timerpg_simjs PRIVATE -O2)
set_target_properties(timerpg_simjs PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
if (ANDROID)
    target_link_libraries(timerpg_simjs PRIVATE log)
    target_link_options(timerpg_simjs PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    find_package(JNI REQUIRED)
    target_include_directories(timerpg_simjs PRIVATE ${JNI_INCLUDE_DIRS})
endif()
