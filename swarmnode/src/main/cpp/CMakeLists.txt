# Builds libfreedom_jni.so — the JNI shims for both embedded nodes
# (ant_jni.c for Swarm, freedom_ipfs_jni.c for IPFS) — and links it
# against the prebuilt combined libfreedom_mobile_ffi.so that
# `scripts/build-android.sh` (solardev-xyz/freedom-mobile-ffi) drops
# into src/main/jniLibs/<abi>/.
cmake_minimum_required(VERSION 3.22)
project(freedom_jni C)

# Link with -lfreedom_mobile_ffi (search path + name) rather than an
# IMPORTED target: the build script stamps a SONAME these days, but
# link-by-name keeps us safe against ever regressing to a cargo-bare
# .so whose DT_NEEDED would otherwise capture the build-host path.
add_library(freedom_jni SHARED ant_jni.c freedom_ipfs_jni.c)
target_link_directories(freedom_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
target_link_libraries(freedom_jni freedom_mobile_ffi log)
