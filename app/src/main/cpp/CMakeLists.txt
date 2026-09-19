# CMakeLists.txt — builds libairplay_native.so for Android.
#
# Layers:
#   static libs : playfair, llhttp, plist-2.0, OpenSSL crypto  (vendored)
#   airplay core: UxPlay lib/*.c EXCEPT dnssd.c (replaced by android_dnssd_shim.c)
#   bridge      : native_bridge.cpp + android_raop_callbacks.c + android_dnssd_shim.c
#
# Why dnssd.c is excluded: lib/dnssd.c delegates networking+TXT to a backend via
# dnssd_private_init(). The upstream mdnsd backend does the mDNS networking on
# 5353, which we don't want on Android (NsdManager owns mDNS in Kotlin). Our
# android_dnssd_shim.c provides dnssd_private_init() and the rest of the dnssd.h
# facade with TXT-record construction only — no sockets.
#
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(airplay_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

set(UXPLAY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/UxPlay)
set(UXPLAY_LIB ${UXPLAY_DIR}/lib)

# ---------------------------------------------------------------- playfair ---
add_library(playfair STATIC
    ${UXPLAY_LIB}/playfair/playfair.c
    ${UXPLAY_LIB}/playfair/omg_hax.c
    ${UXPLAY_LIB}/playfair/hand_garble.c
    ${UXPLAY_LIB}/playfair/modified_md5.c
    ${UXPLAY_LIB}/playfair/sap_hash.c
)
target_include_directories(playfair PUBLIC ${UXPLAY_LIB}/playfair)

# ------------------------------------------------------------------ llhttp ---
add_library(llhttp STATIC
    ${UXPLAY_LIB}/llhttp/api.c
    ${UXPLAY_LIB}/llhttp/http.c
    ${UXPLAY_LIB}/llhttp/llhttp.c
)
target_include_directories(llhttp PUBLIC ${UXPLAY_LIB}/llhttp)

# --------------------------------------------------------------- libplist ---
# libplist ships autotools-only (no CMake), so vendor the .c sources directly.
# HEAD is 2.7.0 (>= 2.3), so both PLIST_210 and PLIST_230 are valid downstream.
set(LIBPLIST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplist)
add_library(plist-2.0 STATIC
    ${LIBPLIST_DIR}/src/base64.c
    ${LIBPLIST_DIR}/src/bytearray.c
    ${LIBPLIST_DIR}/src/hashtable.c
    ${LIBPLIST_DIR}/src/ptrarray.c
    ${LIBPLIST_DIR}/src/time64.c
    ${LIBPLIST_DIR}/src/common.c
    ${LIBPLIST_DIR}/src/xplist.c
    ${LIBPLIST_DIR}/src/bplist.c
    ${LIBPLIST_DIR}/src/jsmn.c
    ${LIBPLIST_DIR}/src/jplist.c
    ${LIBPLIST_DIR}/src/oplist.c
    ${LIBPLIST_DIR}/src/out-default.c
    ${LIBPLIST_DIR}/src/out-plutil.c
    ${LIBPLIST_DIR}/src/out-limd.c
    ${LIBPLIST_DIR}/src/plist.c
    ${LIBPLIST_DIR}/libcnary/cnary.c
    ${LIBPLIST_DIR}/libcnary/node.c
    ${LIBPLIST_DIR}/libcnary/node_list.c
)
target_include_directories(plist-2.0 PUBLIC
    ${LIBPLIST_DIR}/include          # <plist/plist.h>
    ${LIBPLIST_DIR}/src              # internal <hashtable.h>, <ptrarray.h>
    ${LIBPLIST_DIR}/libcnary/include
)
# All Android ABIs are little-endian; bionic (API 21+) has strndup/memmem/etc.
# PACKAGE_VERSION normally comes from autotools' config.h; define it directly
# since we vendor the sources (not HAVE_CONFIG_H).
target_compile_definitions(plist-2.0 PRIVATE
    __LITTLE_ENDIAN__=1
    PACKAGE_VERSION="2.7.0"
    HAVE_STRDUP=1 HAVE_STRNDUP=1 HAVE_STRERROR=1
    HAVE_GMTIME_R=1 HAVE_LOCALTIME_R=1 HAVE_TIMEGM=1 HAVE_STRPTIME=1
    HAVE_MEMMEM=1 HAVE_TM_TM_GMTOFF=1 HAVE_TM_TM_ZONE=1
)
target_link_libraries(plist-2.0 PUBLIC m)

# --------------------------------------------------------------- OpenSSL ---
# Build OpenSSL (static) from the openssl-cmake submodule. Downloads the tarball
# once; needs perl/python3/make/patch on the host (all present).
set(BUILD_OPENSSL             ON  CACHE BOOL "" FORCE)
set(OPENSSL_BUILD_VERSION     "3.5.5" CACHE STRING "" FORCE)
set(OPENSSL_USE_STATIC_LIBS   ON  CACHE STRING "" FORCE)
set(CROSS_ANDROID             ON  CACHE BOOL "" FORCE)
set(OPENSSL_ENABLE_TESTS      OFF CACHE BOOL "" FORCE)
# armeabi-v7a workaround: openssl-cmake keys the v7-a target on ARMEABI_V7A,
# which modern NDK toolchains no longer define. arm64-v8a + x86_64 are fine.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(ARMEABI_V7A ON CACHE BOOL "" FORCE)
endif()

# Force OpenSSL to target our minSdk (24). openssl-cmake's building_env.py
# invokes the NDK clang directly without forwarding ANDROID_PLATFORM, so it
# defaults to the highest platform (android-35) and emits fortified calls
# (__sendto_chk etc.) that aren't in the API-24 libc. BuildOpenSSL.cmake builds
# FORWARD_CFLAGS from CMAKE_C_FLAGS, so defining __ANDROID_API__=24 here makes
# OpenSSL's clang use the API-24 sysroot for both compile and fortify selection.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -D__ANDROID_API__=24")
add_subdirectory(third_party/openssl-cmake)

# --------------------------------------------------------- UxPlay airplay core
# Keep ALL of lib/*.c (including dnssd.c, which is backend-agnostic: it holds
# the public dnssd_init/destroy/get_name/set_pk/etc. and delegates to a backend
# via dnssd_private_init). Our android_dnssd_shim.c IS that backend — it
# implements dnssd_private_init/register_*/get_*_txt with TXT construction only.
# We do NOT glob dns_sd/ or mdnsd/ — those are the networking backends we drop.
file(GLOB UXPLAY_CORE_SOURCES ${UXPLAY_LIB}/*.c)

add_library(airplay_core STATIC ${UXPLAY_CORE_SOURCES})
target_include_directories(airplay_core PUBLIC ${UXPLAY_LIB})
target_compile_definitions(airplay_core PUBLIC
    NOHOLD
    STANDALONE
    __STDC_CONSTANT_MACROS
    __STDC_LIMIT_MACROS
    TARGET_POSIX
    _LINUX
    PLIST_210
    PLIST_230
    OPENSSL_API_COMPAT=0x10101000L
    TARGET_RT_LITTLE_ENDIAN=1
)
target_link_libraries(airplay_core PUBLIC playfair llhttp plist-2.0 crypto log)

# -------------------------------------------------------------------- ALAC ---
# Apple Lossless decoder (software fallback for RAOP audio when no hardware ALAC).
# Apache-2.0 (macosforge/alac). Decoder-only subset (no encoder/convert-utility).
set(ALAC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/alac/codec)
add_library(alac STATIC
    ${ALAC_DIR}/ALACDecoder.cpp
    ${ALAC_DIR}/ALACBitUtilities.c
    ${ALAC_DIR}/EndianPortable.c
    ${ALAC_DIR}/ag_dec.c
    ${ALAC_DIR}/dp_dec.c
    ${ALAC_DIR}/matrix_dec.c
)
target_include_directories(alac PUBLIC ${ALAC_DIR})
target_compile_definitions(alac PUBLIC TARGET_RT_LITTLE_ENDIAN=1)

# ------------------------------------------------------- shared library (.so)
add_library(airplay_native SHARED
    native_bridge.cpp
    android_raop_callbacks.c
    android_dnssd_shim.c
    android_event_channel.c
)
target_link_libraries(airplay_native PRIVATE airplay_core alac log android)
