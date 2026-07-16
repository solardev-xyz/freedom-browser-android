/*
 * JNI shim between `baby.freedom.swarm.AntNative` and the C surface of
 * `libant_ffi.so` (solardev-xyz/ant, header vendored as `ant.h`).
 *
 * The prebuilt JNI exports inside libant_ffi.so itself
 * (`crates/ant-ffi/src/jni.rs`) are mangled for the upstream
 * download-smoke app's class and don't cover the gateway, so Freedom
 * carries this thin wrapper instead: init, start/stop the bee-shaped
 * HTTP gateway, peer count, shutdown. Errors surface as
 * RuntimeException with the message ant allocated (freed here).
 */

#include <jni.h>
#include <stdint.h>

#include "ant.h"

static void throw_runtime(JNIEnv *env, char *owned_err, const char *fallback) {
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, owned_err != NULL ? owned_err : fallback);
    }
    ant_free_string(owned_err);
}

JNIEXPORT jlong JNICALL
Java_baby_freedom_swarm_AntNative_init(JNIEnv *env, jobject thiz, jstring data_dir) {
    (void)thiz;
    const char *dir = (*env)->GetStringUTFChars(env, data_dir, NULL);
    if (dir == NULL) return 0; /* OOM — exception already pending */
    char *err = NULL;
    AntHandle *handle = ant_init(dir, &err);
    (*env)->ReleaseStringUTFChars(env, data_dir, dir);
    if (handle == NULL) {
        throw_runtime(env, err, "ant_init failed");
        return 0;
    }
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_AntNative_startGateway(JNIEnv *env, jobject thiz, jlong handle,
                                               jstring api_addr, jboolean light_mode,
                                               jstring gnosis_rpc) {
    (void)thiz;
    const char *addr = (*env)->GetStringUTFChars(env, api_addr, NULL);
    if (addr == NULL) return;
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) {
        (*env)->ReleaseStringUTFChars(env, api_addr, addr);
        return;
    }
    char *err = NULL;
    bool ok = ant_start_gateway((const AntHandle *)(uintptr_t)handle, addr,
                                light_mode != JNI_FALSE, rpc, &err);
    (*env)->ReleaseStringUTFChars(env, api_addr, addr);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    if (!ok) {
        throw_runtime(env, err, "ant_start_gateway failed");
    }
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_AntNative_stopGateway(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    ant_stop_gateway((const AntHandle *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_baby_freedom_swarm_AntNative_peerCount(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)ant_peer_count((const AntHandle *)(uintptr_t)handle);
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_AntNative_shutdown(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    ant_shutdown((AntHandle *)(uintptr_t)handle);
}
