# The on-device language model: llama.cpp plus our small engine (llm_core) and its JNI layer (llm_jni).
#
# Android (the app's externalNativeBuild, arm64-v8a only): llama.cpp as shared libraries, with the CPU kernels built as
# several variants (baseline armv8.0, armv8.2 + dotprod + fp16, armv8.6 + i8mm) of which llm_jni.cpp loads the best one
# the phone supports. So it is fast on modern phones and still runs on any arm64 phone.
#
# Host (tools/llm-host-test.sh): llama.cpp static, tuned for this machine, plus the llm_host_test program.
#
# Where llama.cpp is: -DLLAMA_DIR=... (Gradle passes llama.dir from local.properties), else kotlin-street/third_party/llama.cpp.
cmake_minimum_required(VERSION 3.22)
project(timerpg_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT LLAMA_DIR)
    set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
endif()
get_filename_component(LLAMA_DIR "${LLAMA_DIR}" ABSOLUTE)
if (NOT EXISTS "${LLAMA_DIR}/include/llama.h" OR NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp source not found at ${LLAMA_DIR}.\n"
        "Run kotlin-street/tools/get-llama.sh to fetch it into kotlin-street/third_party/llama.cpp, "
        "or set llama.dir=/path/to/llama.cpp in kotlin-street/local.properties.")
endif()
message(STATUS "llama.cpp: ${LLAMA_DIR}")

# The model must be fast even in debug APKs: always optimise llama.cpp (Gradle also passes CMAKE_BUILD_TYPE=Release).
if (NOT CMAKE_BUILD_TYPE STREQUAL "Release")
    message(STATUS "CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}: building llama.cpp with Release flags anyway")
    set(CMAKE_BUILD_TYPE Release)
endif()

macro(force name value)
    set(${name} ${value} CACHE BOOL "" FORCE)
endmacro()

# Only the library: no common utils (and so no curl/openssl/httplib), server, tools, examples, tests or web UI.
force(LLAMA_BUILD_COMMON   OFF)
force(LLAMA_BUILD_TESTS    OFF)
force(LLAMA_BUILD_TOOLS    OFF)
force(LLAMA_BUILD_EXAMPLES OFF)
force(LLAMA_BUILD_SERVER   OFF)
force(LLAMA_BUILD_APP      OFF)
force(LLAMA_BUILD_UI       OFF)
force(LLAMA_BUILD_MTMD     OFF)
force(LLAMA_OPENSSL        OFF)
force(LLAMA_SUBPROCESS     OFF)
force(LLAMA_ALL_WARNINGS   OFF)
force(LLAMA_TOOLS_INSTALL  OFF)
force(GGML_BUILD_TESTS     OFF)
force(GGML_BUILD_EXAMPLES  OFF)
force(GGML_ALL_WARNINGS    OFF)
force(GGML_CCACHE          OFF)
# CPU only: no GPU or accelerator backends.
foreach (b CUDA HIP MUSA METAL VULKAN OPENCL SYCL WEBGPU HEXAGON RPC BLAS KOMPUTE CANN ZDNN ZENDNN OPENVINO VIRTGPU ET)
    force(GGML_${b} OFF)
endforeach()
# ggml's own thread pool (no OpenMP runtime to ship); no llamafile sgemm (not used by our Q4_K model, saves size);
# no KleidiAI (it is downloaded at configure time and mostly speeds up Q4_0).
force(GGML_OPENMP       OFF)
force(GGML_LLAMAFILE    OFF)
force(GGML_CPU_KLEIDIAI OFF)
force(GGML_CPU_REPACK   ON)

if (ANDROID)
    if (NOT ANDROID_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "The language model is built for arm64-v8a only (got ${ANDROID_ABI})")
    endif()
    force(BUILD_SHARED_LIBS     ON)
    force(GGML_NATIVE           OFF)
    force(GGML_BACKEND_DL       ON)
    force(GGML_CPU_ALL_VARIANTS ON)
elseif (LLM_HOST_DL)
    # Host test of the Android-style loading: shared libraries, CPU kernels as separate variant libraries that
    # llm_host_test --cpu-lib loads by name (build the variant target too, e.g. ggml-cpu-haswell).
    force(BUILD_SHARED_LIBS     ON)
    force(GGML_NATIVE           OFF)
    force(GGML_BACKEND_DL       ON)
    force(GGML_CPU_ALL_VARIANTS ON)
else()
    # The host test: one static program, optimised for this machine.
    force(BUILD_SHARED_LIBS     OFF)
    force(GGML_NATIVE           ON)
    force(GGML_BACKEND_DL       OFF)
    force(GGML_CPU_ALL_VARIANTS OFF)
endif()

# EXCLUDE_FROM_ALL: only what our targets need gets built.
add_subdirectory("${LLAMA_DIR}" llama.cpp EXCLUDE_FROM_ALL)

add_library(llm_core STATIC llm_core.cpp)
target_include_directories(llm_core PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(llm_core PUBLIC llama)
set_target_properties(llm_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if (ANDROID)
    # The CPU kernel variants llm_jni.cpp may load (its list must match). The others ggml defines (SVE, SME) are not
    # built: on phones they are not faster than NEON + i8mm, and armv8.6_1 runs on every phone that has them.
    set(TIMERPG_CPU_VARIANTS ggml-cpu-android_armv8.6_1 ggml-cpu-android_armv8.2_2 ggml-cpu-android_armv8.0_1)
    foreach (v ${TIMERPG_CPU_VARIANTS})
        if (NOT TARGET ${v})
            message(FATAL_ERROR "llama.cpp at ${LLAMA_DIR} has no CPU variant ${v}: use the version tools/get-llama.sh fetches")
        endif()
    endforeach()

    add_library(timerpg_llm SHARED llm_jni.cpp)
    target_link_libraries(timerpg_llm PRIVATE llm_core log)
    # Building the JNI library builds the kernels it loads at run time.
    add_dependencies(timerpg_llm ${TIMERPG_CPU_VARIANTS})
    # Smaller: drop unused code and do not export anything but the JNI entry points.
    set_target_properties(timerpg_llm PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
    target_link_options(timerpg_llm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    add_executable(llm_host_test host_test.cpp)
    target_link_libraries(llm_host_test PRIVATE llm_core)
    find_package(Threads REQUIRED)
    target_link_libraries(llm_host_test PRIVATE Threads::Threads)
endif()
