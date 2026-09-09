# Navic native DSP module — Phase 1 (Bass Boost proof-of-concept).
#
# Adapted from timschneeb/RootlessJamesDSP's app/src/main/cpp/CMakeLists.txt.
# Differences from the reference:
#   - libcrashlytics-connector and libjdspimptoolbox are NOT built: neither is
#     needed for Bass Boost, and JamesDspWrapper.cpp doesn't call into either
#     of them directly. A minimal local Log.h replaces the LOGx macros that
#     libcrashlytics-connector's Log.h provided, without the Crashlytics hook.
#   - The wrapper library build (previously a separate
#     libjamesdsp-wrapper/CMakeLists.txt pulled in via include()) is inlined
#     below for simplicity, with the crashlytics/jdspimptoolbox link targets
#     and include dirs removed.

cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")

project(jamesdsp LANGUAGES C)
project(jamesdsp-wrapper LANGUAGES CXX)

set(JDSP_CORE_DIR ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp)

# --- libjamesdsp: the core DSP engine (vendored via git submodule at
# androidApp/src/main/cpp/libjamesdsp, pointing at timschneeb/JamesDSPManager
# branch "extensions" — the fork RootlessJamesDSP actually builds against).
# The core compiles as one unit; Bass Boost is just one of the effect entry
# points it exposes, there's no supported way to build "just bass boost".
file(GLOB_RECURSE LIBJAMESDSP_SOURCE_FILES CONFIGURE_DEPENDS
        ${JDSP_CORE_DIR}/*.c
        ${JDSP_CORE_DIR}/*.h)

add_library(jamesdsp SHARED ${LIBJAMESDSP_SOURCE_FILES} EELStdOutExtension.c EELStdOutExtension.h)
target_compile_options(jamesdsp PRIVATE -O2 -std=gnu11
        # libjamesdsp (vendored, upstream C source) forward-declares "struct dspsys" inside a
        # function-pointer field (see Crossfeed.process / Convolver1D.process in jdsp_header.h)
        # before the real struct is defined. Under C's prototype-scope rules that forward
        # declaration is a distinct type from the later "JamesDSPLib" typedef of the real struct,
        # even though they're byte-identical in memory. Older Clang/GCC only warned about this;
        # NDK 27's Clang (~18) promotes -Wincompatible-function-pointer-types to a hard error by
        # default, so this vendored source no longer builds unmodified without downgrading it back.
        # This vendored C source also relies on other legacy-C patterns (implicit "int" on
        # "register i, j;"-style declarations, implicit function declarations, mismatched-signedness
        # int assignments) that GCC/old Clang only warned about but Clang 15+ (shipped in NDK 27)
        # promotes to hard errors by default, matching C23. Downgrade those back to warnings too;
        # none of them reflect an actual behavioral difference on this target.
        -Wno-error=incompatible-function-pointer-types
        -Wno-error=implicit-int
        -Wno-error=implicit-function-declaration
        -Wno-error=int-conversion)
target_include_directories(jamesdsp PUBLIC ${JDSP_CORE_DIR})

find_library(log-lib log)
target_link_libraries(jamesdsp ${log-lib})

# --- jamesdsp-wrapper: JNI glue exposing JamesDspWrapper.kt's `external fun`s.
# Ported from RootlessJamesDSP's libjamesdsp-wrapper/*.cpp,*.h with only the
# JNI export names changed (me.timschneeberger.rootlessjamesdsp.interop ->
# paige.navic.shared.dsp) and the Crashlytics hook removed.
file(GLOB_RECURSE JAMESDSP_WRAPPER_SOURCE_FILES CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp-wrapper/*.cpp
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp-wrapper/*.h)

add_library(jamesdsp-wrapper SHARED ${JAMESDSP_WRAPPER_SOURCE_FILES})

target_include_directories(jamesdsp-wrapper PUBLIC
        ${JDSP_CORE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(jamesdsp-wrapper jamesdsp ${log-lib})
