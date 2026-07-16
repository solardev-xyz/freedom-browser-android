/*
 * JNI shim between `baby.freedom.swarm.FreedomIpfsNative` and the C
 * surface of `libfreedom_ipfs_mobile.so` (solardev-xyz/freedom-ipfs,
 * header vendored as `freedom_ipfs.h`).
 *
 * Same split as `ant_jni.c`: the Rust library owns the C ABI, this
 * file only marshals JNI types. Only the HTTP loopback-gateway surface
 * is bridged — the browser consumes `/ipfs/…` and `/ipns/…` over
 * `http://127.0.0.1:<port>` exactly as it did with the Kubo node, so
 * the native per-request gateway API is not needed.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "freedom_ipfs.h"

JNIEXPORT jlong JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_nodeNew(JNIEnv *env, jobject thiz,
                                                  jstring data_dir,
                                                  jlong max_cache_bytes) {
    (void)thiz;
    const char *dir = (*env)->GetStringUTFChars(env, data_dir, NULL);
    if (dir == NULL) return 0; /* OOM — exception already pending */
    FreedomIpfsNode *node =
        freedom_ipfs_node_new_with_data_dir(dir, (uint64_t)max_cache_bytes);
    (*env)->ReleaseStringUTFChars(env, data_dir, dir);
    return (jlong)(uintptr_t)node;
}

JNIEXPORT void JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_nodeFree(JNIEnv *env, jobject thiz,
                                                   jlong handle) {
    (void)env;
    (void)thiz;
    freedom_ipfs_node_free((FreedomIpfsNode *)(uintptr_t)handle);
}

JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_startGatewayOnline(
    JNIEnv *env, jobject thiz, jlong handle, jstring addr, jint routing_mode) {
    (void)thiz;
    const char *addr_c = (*env)->GetStringUTFChars(env, addr, NULL);
    if (addr_c == NULL) return JNI_FALSE;
    /* NULL router and zeroed tuning knobs take the library defaults
     * (delegated router list, concurrency, DHT budgets, queue timeout),
     * matching the Swift wrapper's startOnlineGateway defaults. */
    bool ok = freedom_ipfs_node_start_gateway_online_with_config_v3(
        (FreedomIpfsNode *)(uintptr_t)handle, addr_c, NULL,
        (uint32_t)routing_mode, 0, 0, 0, 0);
    (*env)->ReleaseStringUTFChars(env, addr, addr_c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_stopGateway(JNIEnv *env, jobject thiz,
                                                      jlong handle) {
    (void)env;
    (void)thiz;
    return freedom_ipfs_node_stop_gateway((FreedomIpfsNode *)(uintptr_t)handle)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_gatewayUrl(JNIEnv *env, jobject thiz,
                                                     jlong handle) {
    (void)thiz;
    char *url =
        freedom_ipfs_node_gateway_url((FreedomIpfsNode *)(uintptr_t)handle);
    if (url == NULL) return NULL;
    jstring out = (*env)->NewStringUTF(env, url);
    freedom_ipfs_string_free(url);
    return out;
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_version(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char *version = freedom_ipfs_version();
    if (version == NULL) return NULL;
    jstring out = (*env)->NewStringUTF(env, version);
    freedom_ipfs_string_free(version);
    return out;
}

/*
 * FreedomIpfsDiagnostics flattened into a long[11] in declaration
 * order: block_count, total_bytes, cache_hits, http_provider_blocks,
 * bitswap_blocks, delegated_provider_lookups,
 * delegated_provider_results, delegated_provider_errors,
 * dht_provider_lookups, dht_provider_results, dht_provider_errors.
 */
JNIEXPORT jlongArray JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_diagnostics(JNIEnv *env, jobject thiz,
                                                      jlong handle) {
    (void)thiz;
    FreedomIpfsDiagnostics d =
        freedom_ipfs_node_diagnostics((FreedomIpfsNode *)(uintptr_t)handle);
    jlong values[11] = {
        (jlong)d.block_count,
        (jlong)d.total_bytes,
        (jlong)d.cache_hits,
        (jlong)d.http_provider_blocks,
        (jlong)d.bitswap_blocks,
        (jlong)d.delegated_provider_lookups,
        (jlong)d.delegated_provider_results,
        (jlong)d.delegated_provider_errors,
        (jlong)d.dht_provider_lookups,
        (jlong)d.dht_provider_results,
        (jlong)d.dht_provider_errors,
    };
    jlongArray out = (*env)->NewLongArray(env, 11);
    if (out == NULL) return NULL;
    (*env)->SetLongArrayRegion(env, out, 0, 11, values);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_baby_freedom_swarm_FreedomIpfsNative_handleNetworkChange(JNIEnv *env,
                                                              jobject thiz,
                                                              jlong handle) {
    (void)env;
    (void)thiz;
    return freedom_ipfs_node_handle_network_change(
               (FreedomIpfsNode *)(uintptr_t)handle)
               ? JNI_TRUE
               : JNI_FALSE;
}
