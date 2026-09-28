/*
 * JNI shim between `baby.freedom.swarm.MyotisNative` and the plain C ABI
 * of the Myotis Ethereum light client (`myotis_*`, linked into
 * `libfreedom_mobile_ffi.so` via freedom-mobile-ffi; header vendored as
 * `myotis_engine.h` from biafra23/myotis v0.1.12, engine ABI 32).
 *
 * Same split as `ant_jni.c` / `freedom_ipfs_jni.c`: the Rust library owns
 * the C ABI, this file only marshals JNI types. Bridged: the node lifecycle
 * (init, create, create-with-checkpoint for stale-anchor recovery,
 * start/stop, pause/resume, status, logs) and one verified read, eth_call,
 * for name resolution (#101); the other verified reads come with their
 * first consumer. Errors are the engine's own sentinels (negative handle
 * ids, `false`, `"{}"`, `{"error": …}`), not exceptions.
 */

#include <jni.h>
#include <stdint.h>
#include <string.h>

#include "myotis_engine.h"

/*
 * A `myotis_*` string result handed to Kotlin as raw UTF-8 bytes and
 * released with `myotis_string_free`. Not a jstring: NewStringUTF
 * expects *modified* UTF-8, and the engine's JSON and tracing lines are
 * plain UTF-8 that may carry 4-byte sequences (a peer's client name, a
 * log field), which abort under CheckJNI. Same reason as the IPFS
 * progress snapshot in freedom_ipfs_jni.c.
 */
static jbyteArray take_bytes(JNIEnv *env, char *owned) {
    if (owned == NULL) return NULL;
    size_t len = strlen(owned);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)owned);
    }
    myotis_string_free(owned);
    return out;
}

JNIEXPORT jint JNICALL
Java_baby_freedom_swarm_MyotisNative_init(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jint)myotis_init();
}

JNIEXPORT jint JNICALL
Java_baby_freedom_swarm_MyotisNative_headerAbiVersion(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jint)MYOTIS_ABI_VERSION;
}

JNIEXPORT jlong JNICALL
Java_baby_freedom_swarm_MyotisNative_create(JNIEnv *env, jobject thiz,
                                            jstring network, jstring data_dir) {
    (void)thiz;
    const char *net = (*env)->GetStringUTFChars(env, network, NULL);
    if (net == NULL) return -1; /* OOM — exception already pending */
    const char *dir = (*env)->GetStringUTFChars(env, data_dir, NULL);
    if (dir == NULL) {
        (*env)->ReleaseStringUTFChars(env, network, net);
        return -1;
    }
    int64_t handle = myotis_create(net, dir);
    (*env)->ReleaseStringUTFChars(env, data_dir, dir);
    (*env)->ReleaseStringUTFChars(env, network, net);
    return (jlong)handle;
}

JNIEXPORT jlong JNICALL
Java_baby_freedom_swarm_MyotisNative_createWithCheckpoint(JNIEnv *env, jobject thiz,
                                                          jstring network, jstring data_dir,
                                                          jstring root, jlong slot) {
    (void)thiz;
    if (slot < 1) return -1; /* the engine refuses slot 0 too; never pass a negative as uint64 */
    const char *net = (*env)->GetStringUTFChars(env, network, NULL);
    if (net == NULL) return -1;
    const char *dir = (*env)->GetStringUTFChars(env, data_dir, NULL);
    if (dir == NULL) {
        (*env)->ReleaseStringUTFChars(env, network, net);
        return -1;
    }
    const char *hex = (*env)->GetStringUTFChars(env, root, NULL);
    if (hex == NULL) {
        (*env)->ReleaseStringUTFChars(env, data_dir, dir);
        (*env)->ReleaseStringUTFChars(env, network, net);
        return -1;
    }
    int64_t handle = myotis_create_with_checkpoint(net, dir, hex, (uint64_t)slot);
    (*env)->ReleaseStringUTFChars(env, root, hex);
    (*env)->ReleaseStringUTFChars(env, data_dir, dir);
    (*env)->ReleaseStringUTFChars(env, network, net);
    return (jlong)handle;
}

JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_MyotisNative_start(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return myotis_start((int64_t)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_MyotisNative_stop(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    myotis_stop((int64_t)handle);
}

JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_MyotisNative_pause(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return myotis_pause((int64_t)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_MyotisNative_resume(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return myotis_resume((int64_t)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_MyotisNative_setServedBlockWindow(JNIEnv *env, jobject thiz,
                                                          jlong handle, jint blocks) {
    (void)env;
    (void)thiz;
    return myotis_set_served_block_window((int64_t)handle, (int32_t)blocks) ? JNI_TRUE
                                                                            : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_statusJson(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    return take_bytes(env, myotis_status_json((int64_t)handle));
}

JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_drainLogs(JNIEnv *env, jobject thiz, jint max) {
    (void)thiz;
    return take_bytes(env, myotis_drain_logs((int32_t)max));
}

/*
 * myotis_eth_call_json: anonymous (empty `from`), zero value. Blocking —
 * the engine's own ~90 s budget; the caller bounds its wait. NULL only
 * when a string couldn't be pinned (OOM, exception pending).
 */
JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_ethCall(JNIEnv *env, jobject thiz, jlong handle,
                                             jstring to, jstring data, jstring block) {
    (void)thiz;
    const char *to_c = (*env)->GetStringUTFChars(env, to, NULL);
    if (to_c == NULL) return NULL;
    const char *data_c = (*env)->GetStringUTFChars(env, data, NULL);
    if (data_c == NULL) {
        (*env)->ReleaseStringUTFChars(env, to, to_c);
        return NULL;
    }
    const char *block_c = (*env)->GetStringUTFChars(env, block, NULL);
    if (block_c == NULL) {
        (*env)->ReleaseStringUTFChars(env, data, data_c);
        (*env)->ReleaseStringUTFChars(env, to, to_c);
        return NULL;
    }
    char *out = myotis_eth_call_json((int64_t)handle, "", to_c, data_c, "0", block_c);
    (*env)->ReleaseStringUTFChars(env, block, block_c);
    (*env)->ReleaseStringUTFChars(env, data, data_c);
    (*env)->ReleaseStringUTFChars(env, to, to_c);
    return take_bytes(env, out);
}
