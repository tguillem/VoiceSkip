cmake_minimum_required(VERSION 3.22.1)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

# Force O3 optimization for all builds (too slow otherwise)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# Set linker flags for 16 KB page size support
# This is CRITICAL for compatibility with 16 KB devices
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

include(FetchContent)

set(WHISPER_CPP_GIT_TAG 080bbbe85230f624f0b52127f1ae1218247989f9)
set(WHISPER_CPP_PATCH_FILE "${CMAKE_CURRENT_SOURCE_DIR}/whisper.patches")
set_property(
    DIRECTORY APPEND PROPERTY
    CMAKE_CONFIGURE_DEPENDS "${WHISPER_CPP_PATCH_FILE}"
)
file(SHA256 "${WHISPER_CPP_PATCH_FILE}" WHISPER_CPP_PATCH_HASH)
string(
    SHA256 WHISPER_CPP_STATE_HASH
    "${WHISPER_CPP_GIT_TAG}:${WHISPER_CPP_PATCH_HASH}"
)
string(SUBSTRING "${WHISPER_CPP_STATE_HASH}" 0 16 WHISPER_CPP_STATE_ID)
set(WHISPER_CPP_CONTENT_NAME "whisper_cpp_${WHISPER_CPP_STATE_ID}")

FetchContent_Declare(
    ${WHISPER_CPP_CONTENT_NAME}
    GIT_REPOSITORY      https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG             ${WHISPER_CPP_GIT_TAG}
    UPDATE_DISCONNECTED TRUE
    PATCH_COMMAND       git apply ${WHISPER_CPP_PATCH_FILE}
)
FetchContent_GetProperties(
    ${WHISPER_CPP_CONTENT_NAME}
    POPULATED WHISPER_CPP_POPULATED
)
if(NOT WHISPER_CPP_POPULATED)
    FetchContent_Populate(${WHISPER_CPP_CONTENT_NAME})
endif()
FetchContent_GetProperties(
    ${WHISPER_CPP_CONTENT_NAME}
    SOURCE_DIR WHISPER_LIB_DIR
)

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
    ${CMAKE_SOURCE_DIR}/gpu_probe.cpp
    ${CMAKE_SOURCE_DIR}/whisper_guard.cpp
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

# Fetch SPIR-V Headers required by ggml-vulkan.
# Upstream ggml-vulkan.cpp includes <spirv/unified1/spirv.hpp> (used by its runtime
# RoundingModeRTE SPIR-V patcher), and ggml-vulkan/CMakeLists.txt does
# `find_package(SPIRV-Headers CONFIG REQUIRED)` purely as a presence guard (the target
# is never linked). The NDK sysroot ships neither, so we vendor the headers (source
# only) and satisfy the guard with a stub config. Tag matches the Vulkan-Hpp SDK above.
FetchContent_Declare(
    spirv_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
    GIT_TAG        vulkan-sdk-1.3.275.0
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(spirv_headers)
if(NOT spirv_headers_POPULATED)
    FetchContent_Populate(spirv_headers)
endif()
# Put <spirv/unified1/spirv.hpp> on the compiler include path
include_directories(${spirv_headers_SOURCE_DIR}/include)

# Satisfy `find_package(SPIRV-Headers CONFIG REQUIRED)` on any CMake version. FetchContent
# only auto-redirects find_package on CMake >= 3.24 (this project pins 3.22.1), so we point
# SPIRV-Headers_DIR at a minimal found-config. Safe because the target is never consumed.
set(_spirv_headers_shim "${CMAKE_BINARY_DIR}/spirv-headers-shim")
file(WRITE "${_spirv_headers_shim}/SPIRV-HeadersConfig.cmake" "set(SPIRV-Headers_FOUND TRUE)\n")
set(SPIRV-Headers_DIR "${_spirv_headers_shim}" CACHE PATH "" FORCE)

set(VOICESKIP_CPU_MODULES_ACTIVE OFF)
if (${ANDROID_ABI} STREQUAL "arm64-v8a" AND NOT GGML_HOME)
    set(VOICESKIP_CPU_MODULES_ACTIVE ON)
endif()

if (GGML_HOME)
    set(GGML_CONTENT_NAME ggml)
    FetchContent_Declare(${GGML_CONTENT_NAME} SOURCE_DIR ${GGML_HOME})
else()
    set(GGML_CONTENT_NAME "ggml_${WHISPER_CPP_STATE_ID}")
    FetchContent_Declare(
        ${GGML_CONTENT_NAME}
        SOURCE_DIR ${WHISPER_LIB_DIR}/ggml
    )
endif()

set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_MIN_1_1 ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)

if (VOICESKIP_CPU_MODULES_ACTIVE)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(
        GGML_CPU_VARIANTS
        "baseline;fp16-dotprod"
        CACHE STRING "" FORCE
    )
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_VARIANTS "" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(${GGML_CONTENT_NAME})

if (VOICESKIP_CPU_MODULES_ACTIVE)
    set_target_properties(
        ggml-cpu-baseline
        ggml-cpu-fp16-dotprod
        PROPERTIES
        VERSION ""
        SOVERSION ""
    )
    target_link_options(
        ggml-cpu-baseline
        PRIVATE -Wl,-soname,libggml-cpu-baseline.so
    )
    target_link_options(
        ggml-cpu-fp16-dotprod
        PRIVATE -Wl,-soname,libggml-cpu-fp16-dotprod.so
    )
    list(
        APPEND SOURCE_FILES
        ${CMAKE_SOURCE_DIR}/cpu_backend.c
        ${CMAKE_SOURCE_DIR}/backend_loader.cpp
    )
endif()

function(voiceskip_set_arm64_baseline target_name)
    if (${ANDROID_ABI} STREQUAL "arm64-v8a" AND TARGET ${target_name})
        target_compile_options(${target_name} PRIVATE -march=armv8-a)
    endif()
endfunction()

voiceskip_set_arm64_baseline(ggml)
voiceskip_set_arm64_baseline(ggml-base)
voiceskip_set_arm64_baseline(ggml-vulkan)

if (VOICESKIP_CPU_MODULES_ACTIVE)
    voiceskip_set_arm64_baseline(ggml-cpu-baseline-feats)
    voiceskip_set_arm64_baseline(ggml-cpu-fp16-dotprod-feats)
else()
    voiceskip_set_arm64_baseline(ggml-cpu)
endif()

function(voiceskip_set_native_link_options target_name)
    if (NOT TARGET ${target_name})
        return()
    endif()

    get_target_property(target_type ${target_name} TYPE)
    if (
        target_type STREQUAL "SHARED_LIBRARY"
        OR target_type STREQUAL "MODULE_LIBRARY"
    )
        target_link_options(
            ${target_name}
            PRIVATE -Wl,-z,max-page-size=16384
        )
    endif()
endfunction()

foreach(
    native_target
    ggml
    ggml-base
    ggml-cpu
    ggml-cpu-baseline
    ggml-cpu-fp16-dotprod
    ggml-vulkan
)
    voiceskip_set_native_link_options(${native_target})
endforeach()

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    if (NOT VOICESKIP_CPU_MODULES_ACTIVE)
        target_compile_definitions(${target_name} PUBLIC
            GGML_USE_CPU
            GGML_USE_VULKAN
        )
    else()
        target_compile_definitions(
            ${target_name}
            PRIVATE VOICESKIP_CPU_MODULES
        )
    endif()
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
    endif ()
    voiceskip_set_arm64_baseline(${target_name})

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

    if (VOICESKIP_CPU_MODULES_ACTIVE)
        add_dependencies(
            ${target_name}
            ggml-cpu-baseline
            ggml-cpu-fp16-dotprod
            ggml-vulkan
        )
    endif()

    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

if (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper") # Default target
