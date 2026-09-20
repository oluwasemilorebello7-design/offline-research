cmake_minimum_required(VERSION 3.22.1)
project(offline_research LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- llama.cpp (git submodule: see README step 1) -------------------------------------------
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)   # no network code in the binary at all
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# ---- JNI bridge -----------------------------------------------------------------------------
add_library(offline_llm SHARED llama_jni.cpp)
target_include_directories(offline_llm PRIVATE llama.cpp/include llama.cpp/ggml/include)
target_link_libraries(offline_llm PRIVATE llama android log)
