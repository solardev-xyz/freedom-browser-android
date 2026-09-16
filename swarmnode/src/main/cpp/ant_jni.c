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

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_agentString(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    char *agent = ant_agent_string((const AntHandle *)(uintptr_t)handle);
    if (agent == NULL) return NULL;
    jstring out = (*env)->NewStringUTF(env, agent);
    ant_free_string(agent);
    return out;
}

/*
 * Lifecycle recovery (ant.h: ant_resume / ant_suspend / ant_wake). After
 * Android freezes or the network flips, the swarm's peer sockets are
 * reaped while the peer counter still looks healthy and nothing
 * re-dials — retrievals then hang and the page shows ERR_-1 until the
 * node is restarted. `resume` re-opens live sockets to the bootnodes;
 * `suspend` / `wake` quiesce and restart background work around a
 * suspension. All three are idempotent and cheap.
 */
static void call_lifecycle(JNIEnv *env, jlong handle,
                           int (*fn)(const AntHandle *, char **),
                           const char *what) {
    char *err = NULL;
    int rc = fn((const AntHandle *)(uintptr_t)handle, &err);
    if (rc != 0) {
        throw_runtime(env, err, what);
    }
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_AntNative_resume(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    call_lifecycle(env, handle, ant_resume, "ant_resume failed");
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_AntNative_suspend(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    call_lifecycle(env, handle, ant_suspend, "ant_suspend failed");
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_AntNative_wake(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    call_lifecycle(env, handle, ant_wake, "ant_wake failed");
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_AntNative_shutdown(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    ant_shutdown((AntHandle *)(uintptr_t)handle);
}
