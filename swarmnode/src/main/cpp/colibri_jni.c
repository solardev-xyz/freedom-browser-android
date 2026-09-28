/*
 * JNI shim between `baby.freedom.swarm.ColibriNative` and the C API of
 * corpus.core's Colibri stateless verifier (`c4_*`, `libc4.so`, built by
 * scripts/build-colibri.sh at release.yml's COLIBRI_REF; header vendored
 * as `colibri.h` from the same tag) — #100.
 *
 * Only the unified RPC state machine is bridged: create a context for one
 * JSON-RPC call, step it, hand it the responses to the HTTP requests it
 * asks for, free it. The C core does no I/O; the app does every request
 * (EnsColibri.kt). Built into its own libfreedom_colibri.so, apart from
 * libfreedom_jni.so, so the browser process can load it without pulling
 * in the embedded nodes' library.
 *
 * Strings cross as raw UTF-8 byte arrays both ways, not jstrings: the
 * status JSON can carry a server's error text, and NewStringUTF expects
 * *modified* UTF-8 (same reason as myotis_jni.c's take_bytes).
 *
 * Not thread-safe on its own: the core keeps process-wide caches, so the
 * Kotlin side serializes every call (ColibriNative's lock).
 */

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "colibri.h"

/*
 * The core's storage plugin (src/util/plugin.h, not part of the public
 * header). The build keeps its in-memory default; nInit swaps in the
 * file-backed one, filtered, so the verified sync-committee state survives
 * restarts and nothing else is written.
 */
typedef struct {
    bool (*get)(char *key, void *buffer);
    void (*set)(char *key, bytes_t value);
    void (*del)(char *key);
    uint32_t max_sync_states;
} c4_storage_plugin_t;

extern void c4_get_file_storage_plugin(c4_storage_plugin_t *plugin);
extern void c4_set_storage_config(c4_storage_plugin_t *plugin);

/* A NUL-terminated copy of [bytes], or NULL (OOM, exception pending). */
static char *c_string(JNIEnv *env, jbyteArray bytes) {
    if (bytes == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, bytes);
    char *out = malloc((size_t)len + 1);
    if (out == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, len, (jbyte *)out);
    out[len] = '\0';
    return out;
}

/* [owned] as a byte array, freed. */
static jbyteArray take_bytes(JNIEnv *env, char *owned) {
    if (owned == NULL) return NULL;
    size_t len = strlen(owned);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)owned);
    }
    free(owned);
    return out;
}

/*
 * What may reach the disk: the verifier's own consensus state and nothing
 * about what it was asked. The core also stores, through the same plugin,
 * a per-contract account cache (`call_<chain>_<address>`: every storage
 * slot a proven eth_call read, i.e. each resolved name's registry slot and
 * its contenthash) and contract bytecode (`code_<hash>`) — a browsing
 * trail no "clear site data" or private tab would reach. Those, and any
 * key a later core adds, stay unstored: the core then simply fetches and
 * proves the data again. Keep in step with ColibriNative.PERSISTED_PREFIXES.
 */
static const char *const persisted_prefixes[] = {
    "states_", /* the sync-committee checkpoint per chain */
    "sync_",   /* verified sync-committee periods */
    "rdelay_", /* retry back-off per server category */
};

static c4_storage_plugin_t file_backend;

static bool persisted(const char *key) {
    if (key == NULL) return false;
    for (size_t i = 0; i < sizeof(persisted_prefixes) / sizeof(persisted_prefixes[0]); i++) {
        if (strncmp(key, persisted_prefixes[i], strlen(persisted_prefixes[i])) == 0) return true;
    }
    return false;
}

static bool kept_get(char *key, void *buffer) {
    return persisted(key) && file_backend.get(key, buffer);
}

static void kept_set(char *key, bytes_t value) {
    if (persisted(key)) file_backend.set(key, value);
}

static void kept_del(char *key) {
    if (persisted(key)) file_backend.del(key);
}

/*
 * Point the file storage at [states_dir] and make it the active backend,
 * filtered to [persisted_prefixes].
 * Must run before the first context: the core reads C4_STATES_DIR once.
 */
JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_ColibriNative_nInit(JNIEnv *env, jobject thiz, jbyteArray states_dir) {
    (void)thiz;
    char *dir = c_string(env, states_dir);
    if (dir == NULL) return JNI_FALSE;
    int rc = setenv("C4_STATES_DIR", dir, 1);
    free(dir);
    if (rc != 0) return JNI_FALSE;
    memset(&file_backend, 0, sizeof(file_backend));
    c4_get_file_storage_plugin(&file_backend);
    if (file_backend.get == NULL || file_backend.set == NULL || file_backend.del == NULL) return JNI_FALSE;
    c4_storage_plugin_t plugin = file_backend;
    plugin.get = kept_get;
    plugin.set = kept_set;
    plugin.del = kept_del;
    c4_set_storage_config(&plugin);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_baby_freedom_swarm_ColibriNative_nVersion(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jint)c4_get_current_version_number();
}

JNIEXPORT jlong JNICALL
Java_baby_freedom_swarm_ColibriNative_nCreate(JNIEnv *env, jobject thiz,
                                                   jbyteArray method, jbyteArray params,
                                                   jlong chain_id, jint prover_flags,
                                                   jint verify_flags, jint prover_mode) {
    (void)thiz;
    char *m = c_string(env, method);
    char *p = c_string(env, params);
    void *ctx = NULL;
    if (m != NULL && p != NULL) {
        ctx = c4_create_rpc_ctx(m, p, (uint64_t)chain_id, (uint32_t)prover_flags,
                                (uint32_t)verify_flags, (int)prover_mode);
    }
    free(m);
    free(p);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_ColibriNative_nSetMinLatestBlockTs(JNIEnv *env, jobject thiz,
                                                          jlong ctx, jlong ts) {
    (void)env;
    (void)thiz;
    if (ctx == 0) return;
    c4_rpc_set_min_latest_block_ts((void *)(intptr_t)ctx, (uint64_t)ts);
}

JNIEXPORT jbyteArray JNICALL
Java_baby_freedom_swarm_ColibriNative_nExecute(JNIEnv *env, jobject thiz, jlong ctx) {
    (void)thiz;
    if (ctx == 0) return NULL;
    return take_bytes(env, c4_rpc_execute_json_status((void *)(intptr_t)ctx));
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_ColibriNative_nSetResponse(JNIEnv *env, jobject thiz,
                                                  jlong req, jbyteArray data, jint node_index) {
    (void)thiz;
    if (req == 0 || data == NULL) return;
    jsize len = (*env)->GetArrayLength(env, data);
    uint8_t *copy = malloc(len > 0 ? (size_t)len : 1);
    if (copy == NULL) return;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)copy);
    bytes_t bytes = {.len = (uint32_t)len, .data = copy};
    /* The core copies the response into the request it belongs to. */
    c4_req_set_response((void *)(intptr_t)req, bytes, (uint16_t)node_index);
    free(copy);
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_ColibriNative_nSetError(JNIEnv *env, jobject thiz,
                                               jlong req, jbyteArray error, jint node_index) {
    (void)thiz;
    if (req == 0) return;
    char *e = c_string(env, error);
    if (e == NULL) return;
    c4_req_set_error((void *)(intptr_t)req, e, (uint16_t)node_index);
    free(e);
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_ColibriNative_nFree(JNIEnv *env, jobject thiz, jlong ctx) {
    (void)env;
    (void)thiz;
    if (ctx == 0) return;
    c4_free_rpc_ctx((void *)(intptr_t)ctx);
}
