/*
 * JNI shim between `baby.freedom.swarm.MyotisNative` and the plain C ABI
 * of the Myotis Ethereum light client (`myotis_*`, linked into
 * `libfreedom_mobile_ffi.so` via freedom-mobile-ffi; header vendored as
 * `myotis_engine.h` from biafra23/myotis v0.1.12, engine ABI 32).
 *
 * Same split as `ant_jni.c` / `freedom_ipfs_jni.c`: the Rust library owns
 * the C ABI, this file only marshals JNI types. Bridged: the node lifecycle
 * (init, create, create-with-checkpoint for stale-anchor recovery,
 * start/stop, pause/resume, status, logs) and the verified reads the app
 * consumes: eth_call for name resolution (#101), and the account, code,
 * call, receipt and block reads the chain-data router asks (#329). Errors are the engine's own sentinels (negative handle
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

/*
 * The chain-data router's verified reads (#329). Each pins its string
 * arguments (hex and tags: plain ASCII), calls the engine and hands its
 * JSON back as UTF-8 bytes. Blocking — the engine's own ~90 s budget;
 * the caller bounds its wait. NULL only when a string couldn't be
 * pinned (OOM, exception pending).
 */
#define MAX_PINNED 5

typedef struct {
    JNIEnv *env;
    int n;
    jstring src[MAX_PINNED];
    const char *utf[MAX_PINNED];
} pinned_t;

/* Pin every non-NULL jstring in `in` into `out` (a NULL one stays NULL); false on failure, nothing left pinned. */
static int pin_all(JNIEnv *env, pinned_t *p, const jstring *in, int n) {
    p->env = env;
    p->n = 0;
    for (int i = 0; i < n; i++) {
        p->src[i] = in[i];
        p->utf[i] = NULL;
        if (in[i] != NULL) {
            p->utf[i] = (*env)->GetStringUTFChars(env, in[i], NULL);
            if (p->utf[i] == NULL) {
                for (int j = 0; j < i; j++) {
                    if (p->utf[j] != NULL) (*env)->ReleaseStringUTFChars(env, p->src[j], p->utf[j]);
                }
                return 0;
            }
        }
        p->n = i + 1;
    }
    return 1;
}

static void unpin_all(pinned_t *p) {
    for (int i = 0; i < p->n; i++) {
        if (p->utf[i] != NULL) (*p->env)->ReleaseStringUTFChars(p->env, p->src[i], p->utf[i]);
    }
}

/* myotis_request_account_json: nonce and balance at `block` (NULL/"" = the verified head). */
JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_requestAccount(JNIEnv *env, jobject thiz, jlong handle,
                                                    jstring address, jstring block) {
    (void)thiz;
    jstring in[2] = {address, block};
    pinned_t p;
    if (!pin_all(env, &p, in, 2)) return NULL;
    char *out = myotis_request_account_json((int64_t)handle, p.utf[0] ? p.utf[0] : "", p.utf[1]);
    unpin_all(&p);
    return take_bytes(env, out);
}

/* myotis_get_code_json. */
JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_getCode(JNIEnv *env, jobject thiz, jlong handle,
                                             jstring address, jstring block) {
    (void)thiz;
    jstring in[2] = {address, block};
    pinned_t p;
    if (!pin_all(env, &p, in, 2)) return NULL;
    char *out = myotis_get_code_json((int64_t)handle, p.utf[0] ? p.utf[0] : "", p.utf[1]);
    unpin_all(&p);
    return take_bytes(env, out);
}

/*
 * myotis_eth_call_json with a caller: `from` ("" = anonymous) and `value`
 * (wei, decimal). `to` must be non-NULL: the engine refuses a NULL one,
 * and an empty one is contract creation.
 */
JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_ethCallFrom(JNIEnv *env, jobject thiz, jlong handle,
                                                 jstring from, jstring to, jstring data,
                                                 jstring value, jstring block) {
    (void)thiz;
    jstring in[5] = {from, to, data, value, block};
    pinned_t p;
    if (!pin_all(env, &p, in, 5)) return NULL;
    if (p.utf[1] == NULL) {
        unpin_all(&p);
        return NULL;
    }
    char *out = myotis_eth_call_json((int64_t)handle, p.utf[0] ? p.utf[0] : "", p.utf[1],
                                     p.utf[2] ? p.utf[2] : "0x", p.utf[3] ? p.utf[3] : "0",
                                     p.utf[4]);
    unpin_all(&p);
    return take_bytes(env, out);
}

/* myotis_get_transaction_receipt_json: a receipt, "null" (not seen in the scanned window) or {"error"}. */
JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_transactionReceipt(JNIEnv *env, jobject thiz, jlong handle,
                                                        jstring tx_hash) {
    (void)thiz;
    jstring in[1] = {tx_hash};
    pinned_t p;
    if (!pin_all(env, &p, in, 1)) return NULL;
    char *out = myotis_get_transaction_receipt_json((int64_t)handle, p.utf[0] ? p.utf[0] : "");
    unpin_all(&p);
    return take_bytes(env, out);
}

/* myotis_get_block_by_number_json: a block, "null" or {"error"}. */
JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_MyotisNative_blockByNumber(JNIEnv *env, jobject thiz, jlong handle,
                                                   jstring tag, jboolean full_transactions) {
    (void)thiz;
    jstring in[1] = {tag};
    pinned_t p;
    if (!pin_all(env, &p, in, 1)) return NULL;
    char *out = myotis_get_block_by_number_json((int64_t)handle, p.utf[0] ? p.utf[0] : "latest",
                                                full_transactions == JNI_TRUE);
    unpin_all(&p);
    return take_bytes(env, out);
}
