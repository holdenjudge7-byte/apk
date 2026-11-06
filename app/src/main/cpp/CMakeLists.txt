cmake_minimum_required(VERSION 3.10.2)
project("orsted_chess_overlay")

# C++14 is required by Stockfish 11; C++17 is NOT needed
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Stockfish 11 source files ─────────────────────────────────────────────────
# The GitHub Actions workflow downloads SF11 and stages the files here.
# main.cpp has its own int main() — exclude it; JNI is our entry point.

file(GLOB SF_CORE "${CMAKE_SOURCE_DIR}/stockfish/*.cpp")
list(FILTER SF_CORE EXCLUDE REGEX ".*/main\\.cpp$")

file(GLOB SF_SYZYGY "${CMAKE_SOURCE_DIR}/stockfish/syzygy/*.cpp")

set(SF_SRC ${SF_CORE} ${SF_SYZYGY})

# ── Shared library ────────────────────────────────────────────────────────────
add_library(
    orsted_engine
    SHARED
    native-lib.cpp
    ${SF_SRC}
)

target_include_directories(orsted_engine PRIVATE
    ${CMAKE_SOURCE_DIR}/stockfish
    ${CMAKE_SOURCE_DIR}/stockfish/syzygy
)

# SF11 compile flags
# -DIS_64BIT only if 64-bit ABI; CMake sets ANDROID_ABI automatically
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(orsted_engine PRIVATE IS_64BIT)
endif()

target_compile_definitions(orsted_engine PRIVATE
    USE_PTHREADS
    # No NNUE — SF11 uses classical evaluation only
)

target_compile_options(orsted_engine PRIVATE
    -O2
    -Wall
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-sign-compare
)

find_library(log-lib log)
target_link_libraries(orsted_engine ${log-lib})
