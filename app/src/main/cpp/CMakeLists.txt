
cmake_minimum_required(VERSION 3.22.1)
project(omni_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(omni_llama SHARED native-lib.cpp)

target_include_directories(omni_llama PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(omni_llama
    llama
    ggml
    android
    log
)
