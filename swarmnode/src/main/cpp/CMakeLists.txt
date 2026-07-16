# Builds libant_jni.so — the JNI shim in ant_jni.c — and links it
# against the prebuilt libant_ffi.so that `cargo xtask build-android-*`
# (solardev-xyz/ant) drops into src/main/jniLibs/<abi>/.
cmake_minimum_required(VERSION 3.22)
project(ant_jni C)

# Link with -lant_ffi (search path + name) rather than an IMPORTED
# target: cargo emits libant_ffi.so without a SONAME, so linking the
# absolute path would bake that path into libant_jni.so's DT_NEEDED
# and dlopen would fail on-device.
add_library(ant_jni SHARED ant_jni.c)
target_link_directories(ant_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
target_link_libraries(ant_jni ant_ffi log)
