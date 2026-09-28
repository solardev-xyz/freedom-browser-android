/*
 * JNI shim between `baby.freedom.swarm.TorNative` and the embedded Arti
 * (Tor) client's C ABI (`freedom_tor_*`, freedom-mobile-ffi feature `tor`,
 * linked into `libfreedom_mobile_ffi.so`; header vendored as
 * `freedom_tor.h`). Same split as the other shims: the Rust library owns
 * the behaviour, this file only marshals JNI types.
 */

#include <jni.h>
#include <stdint.h>
#include <string.h>

#include "freedom_tor.h"

/* An owned `freedom_tor_*` string as UTF-8 bytes (see myotis_jni.c for
 * why not a jstring), freed here. */
static jbyteArray take_bytes(JNIEnv *env, char *owned) {
    if (owned == NULL) return NULL;
    size_t len = strlen(owned);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)owned);
    }
    freedom_tor_string_free(owned);
    return out;
}

/*
 * Start the client; returns null on success (the port is in the status
 * JSON) or the failure message as UTF-8 bytes.
 */
JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_TorNative_start(JNIEnv *env, jobject thiz,
                                        jstring state_dir, jstring cache_dir) {
    (void)thiz;
    const char *state = (*env)->GetStringUTFChars(env, state_dir, NULL);
    if (state == NULL) return NULL; /* OOM — exception already pending */
    const char *cache = (*env)->GetStringUTFChars(env, cache_dir, NULL);
    if (cache == NULL) {
        (*env)->ReleaseStringUTFChars(env, state_dir, state);
        return NULL;
    }
    char *err = NULL;
    uint16_t port = freedom_tor_start(state, cache, 0, &err);
    (*env)->ReleaseStringUTFChars(env, cache_dir, cache);
    (*env)->ReleaseStringUTFChars(env, state_dir, state);
    if (port != 0) return NULL;
    if (err == NULL) {
        static const char unknown[] = "freedom_tor_start failed";
        jbyteArray out = (*env)->NewByteArray(env, (jsize)(sizeof unknown - 1));
        if (out != NULL) {
            (*env)->SetByteArrayRegion(env, out, 0, (jsize)(sizeof unknown - 1),
                                       (const jbyte *)unknown);
        }
        return out;
    }
    return take_bytes(env, err);
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_TorNative_stop(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    freedom_tor_stop();
}

JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_TorNative_statusJson(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return take_bytes(env, freedom_tor_status_json());
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_TorNative_version(JNIEnv *env, jobject thiz) {
    (void)thiz;
    /* ASCII ("0.46.0"), so NewStringUTF is safe. */
    return (*env)->NewStringUTF(env, freedom_tor_version());
}
