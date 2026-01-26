cmake_minimum_required(VERSION 3.22.1)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

# Force O3 optimization for all builds (too slow otherwise)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# Set linker flags for 16 KB page size support
# This is CRITICAL for compatibility with 16 KB devices
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

include(FetchContent)

# Fetch whisper.cpp (source only, don't process its CMakeLists.txt)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        f53dc74843e97f19f94a79241357f74ad5b691a6
    PATCH_COMMAND  git apply ${CMAKE_CURRENT_SOURCE_DIR}/whisper.patches
)
FetchContent_GetProperties(whisper_cpp)
if(NOT whisper_cpp_POPULATED)
    FetchContent_Populate(whisper_cpp)
endif()
set(WHISPER_LIB_DIR ${whisper_cpp_SOURCE_DIR})

# Get whisper.cpp version
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

message(STATUS " Whisper version: ${WHISPER_VERSION}")
message(STATUS " Using 16 KB page size linker flags")

# Path to external GGML, otherwise uses the copy in whisper.cpp.
option(GGML_HOME "whisper: Path to external GGML source" OFF)

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
    ${CMAKE_SOURCE_DIR}/stream.c
    )

find_library(LOG_LIB log)

# Fetch Vulkan-Hpp C++ bindings matching NDK r28b Vulkan version
# NDK r28b has VK_HEADER_VERSION 275 (Vulkan 1.3.275)
# Disable samples, tests, and extra dependencies to speed up build
set(VULKAN_HPP_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(VULKAN_HPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(VULKAN_HPP_RUN_GENERATOR OFF CACHE BOOL "" FORCE)
set(VULKAN_HPP_SAMPLES_BUILD OFF CACHE BOOL "" FORCE)
set(VULKAN_HPP_TESTS_BUILD OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    vulkan_hpp
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Hpp.git
    GIT_TAG        v1.3.275
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(vulkan_hpp)

# Set up include directories globally (before building GGML)
include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-vulkan)
# Add Vulkan-Hpp C++ bindings include directory
FetchContent_GetProperties(vulkan_hpp)
if(NOT vulkan_hpp_POPULATED)
    FetchContent_Populate(vulkan_hpp)
endif()
include_directories(${vulkan_hpp_SOURCE_DIR})

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC
        GGML_USE_CPU
        GGML_USE_VULKAN
    )
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_dotprod")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16+dotprod)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)

        # Explicitly add 16 KB page size for this target
        target_link_options(${target_name} PRIVATE -Wl,-z,max-page-size=16384)
    else ()
        target_link_options(${target_name} PRIVATE -Wl,-z,max-page-size=16384)
    endif ()

    if (GGML_HOME)
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
    else()
        FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    endif()

    # Enable Vulkan backend in GGML
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN_MIN_1_1 ON CACHE BOOL "" FORCE)

    FetchContent_MakeAvailable(ggml)

    # Apply 16 KB page size to GGML library as well
    if(TARGET ggml)
        target_link_options(ggml PRIVATE -Wl,-z,max-page-size=16384)
    endif()

    target_link_libraries(${target_name} ${LOG_LIB} android ggml)


endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_dotprod")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper") # Default target

