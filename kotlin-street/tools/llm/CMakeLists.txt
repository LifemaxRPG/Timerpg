# Offline model-file tools (kotlin-street/docs/MODEL_FILES.md), built on a computer against a llama.cpp source tree:
#   cmake -S kotlin-street/tools/llm -B /dev/shm/tl/tools -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR=/path/to/llama.cpp
#   cmake --build /dev/shm/tl/tools -j4
# vocab_reorder: the phone-side tool (vocab_reorder.cpp/.h need only ggml's gguf API, ggml-base).
# vocab_test: server-side checks (links libllama).
# quantize_ours: the phone-side calibrated quantization (quantize_ours.cpp/.h: llama_model_quantize with our imatrix).
# llama-quantize / llama-imatrix / llama-perplexity: llama.cpp's own tools from the same tree.
cmake_minimum_required(VERSION 3.18)
project(timerpg_llm_tools CXX C)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT LLAMA_DIR)
  message(FATAL_ERROR "set -DLLAMA_DIR to a llama.cpp source tree (the app's: b1b770d)")
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} llama.cpp EXCLUDE_FROM_ALL)

add_library(vocab_reorder_lib STATIC vocab_reorder.cpp)
target_include_directories(vocab_reorder_lib PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(vocab_reorder_lib PUBLIC ggml-base)

add_executable(vocab_reorder vocab_reorder_main.cpp)
target_link_libraries(vocab_reorder PRIVATE vocab_reorder_lib)

add_executable(vocab_test vocab_test.cpp)
target_link_libraries(vocab_test PRIVATE vocab_reorder_lib llama)

add_library(quantize_ours_lib STATIC quantize_ours.cpp)
target_include_directories(quantize_ours_lib PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(quantize_ours_lib PUBLIC llama ggml-base)

add_executable(quantize_ours quantize_ours_main.cpp)
target_link_libraries(quantize_ours PRIVATE quantize_ours_lib)

add_custom_target(llama_tools ALL)
add_dependencies(llama_tools llama-quantize llama-imatrix llama-perplexity)
