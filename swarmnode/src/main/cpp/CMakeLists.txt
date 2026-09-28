# Builds libfreedom_jni.so — the JNI shims for the embedded nodes
# (ant_jni.c for Swarm, freedom_ipfs_jni.c for IPFS, myotis_jni.c for
# the Myotis Ethereum light client) — and links it
# against the prebuilt combined libfreedom_mobile_ffi.so that
# `scripts/build-android.sh` (solardev-xyz/freedom-mobile-ffi) drops
# into src/main/jniLibs/<abi>/.
cmake_minimum_required(VERSION 3.22)
project(freedom_jni C)

# Link with -lfreedom_mobile_ffi (search path + name) rather than an
# IMPORTED target: the build script stamps a SONAME these days, but
# link-by-name keeps us safe against ever regressing to a cargo-bare
# .so whose DT_NEEDED would otherwise capture the build-host path.
add_library(freedom_jni SHARED ant_jni.c freedom_ipfs_jni.c myotis_jni.c)
# 16 KB page-size compatibility. NDK r27 links with 4 KB LOAD alignment by
# default; a device booted with 16 KB pages then refuses to dlopen this
# library ("not page-aligned"), the :node process never comes up, and
# every bzz:// / ipfs:// navigation fails while plain https keeps working.
# libfreedom_mobile_ffi.so is already built 16 KB-aligned by cargo-ndk.
target_link_options(freedom_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_directories(freedom_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
target_link_libraries(freedom_jni freedom_mobile_ffi log)

# libfreedom_colibri.so — the JNI shim over corpus.core's Colibri verifier
# (colibri_jni.c, #100), linked against the prebuilt libc4.so that
# scripts/build-colibri.sh drops into src/main/jniLibs/<abi>/. Its own
# library, not part of freedom_jni: the browser process loads it for name
# resolution, and must not pull libfreedom_mobile_ffi.so in with it.
#
# Optional: a checkout without libc4.so still builds (with a warning) and
# the app starts name resolution at the RPC quorum instead. Release builds
# can't ship that way — release.yml builds libc4.so and checks each APK
# carries the shim.
set(C4_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libc4.so)
if(EXISTS ${C4_LIB})
    add_library(freedom_colibri SHARED colibri_jni.c)
    target_link_options(freedom_colibri PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_directories(freedom_colibri PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
    target_link_libraries(freedom_colibri c4)
else()
    message(WARNING "libc4.so missing for ${ANDROID_ABI}: building without Colibri (see scripts/build-colibri.sh)")
endif()
