/*
 * JNI shim between `baby.freedom.swarm.AntNative` and ant's C surface,
 * linked into `libfreedom_mobile_ffi.so` (freedom-hq/ant via
 * solardev-xyz/freedom-mobile-ffi; header vendored as `ant.h`).
 *
 * The prebuilt JNI exports inside ant-ffi itself
 * (`crates/ant-ffi/src/jni.rs`) are mangled for the upstream
 * download-smoke app's class and don't cover the gateway, so Freedom
 * carries this thin wrapper instead: init, start/stop the bee-shaped
 * HTTP gateway, peer count, postage stamps (#116), shutdown. Errors surface as
 * RuntimeException with the message ant allocated (freed here).
 */

#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ant.h"
#include "freedom_mobile.h"

/* Zero secret bytes in a way the compiler can't drop as a dead store. */
static void wipe(void *p, size_t n) {
    volatile unsigned char *v = (volatile unsigned char *)p;
    while (n--) *v++ = 0;
}

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
    /* Before ant_init claims the process's tracing subscriber for its
     * logs alone: this one also carries freedom-ipfs's progress recorder
     * (see freedom_mobile.h; #156). */
    freedom_mobile_init_logging();
    char *err = NULL;
    AntHandle *handle = ant_init(dir, &err);
    (*env)->ReleaseStringUTFChars(env, data_dir, dir);
    if (handle == NULL) {
        throw_runtime(env, err, "ant_init failed");
        return 0;
    }
    return (jlong)(uintptr_t)handle;
}

/*
 * Like init, but ant runs as the account in `identity` (#77): the UTF-8
 * identity document for ant_init_with_identity, handed over as a byte[]
 * so neither side ever holds the key in an immutable String. It is
 * copied straight into a native buffer (GetByteArrayRegion — no pinned
 * or JVM-side copy), NUL-terminated, and zeroed before it is freed.
 */
JNIEXPORT jlong JNICALL
Java_baby_freedom_swarm_AntNative_initWithIdentity(JNIEnv *env, jobject thiz, jstring data_dir,
                                                   jbyteArray identity) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, identity);
    char *doc = malloc((size_t)len + 1);
    if (doc == NULL) {
        throw_runtime(env, NULL, "out of memory");
        return 0;
    }
    (*env)->GetByteArrayRegion(env, identity, 0, len, (jbyte *)doc);
    doc[len] = '\0';
    const char *dir = (*env)->GetStringUTFChars(env, data_dir, NULL);
    AntHandle *handle = NULL;
    char *err = NULL;
    if (dir != NULL) {
        freedom_mobile_init_logging();
        handle = ant_init_with_identity(dir, NULL, doc, &err);
    }
    wipe(doc, (size_t)len + 1);
    free(doc);
    if (dir == NULL) return 0; /* OOM — exception already pending */
    (*env)->ReleaseStringUTFChars(env, data_dir, dir);
    if (handle == NULL) {
        throw_runtime(env, err, "ant_init_with_identity failed");
        return 0;
    }
    return (jlong)(uintptr_t)handle;
}

/* ant_account_info: {"eth_address","overlay","peer_id","agent"}, or null. */
JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_accountInfo(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    char *err = NULL;
    char *info = ant_account_info((const AntHandle *)(uintptr_t)handle, &err);
    ant_free_string(err);
    if (info == NULL) return NULL;
    jstring out = (*env)->NewStringUTF(env, info);
    ant_free_string(info);
    return out;
}

/*
 * The chain transport every Freedom node runs with (#114, #116): ant may
 * read the chain freely, but a broadcast goes out only if the app's own
 * SpendGuard (SpendGuard.kt) admits it. In light mode the gateway signs
 * and sends a transaction for POST /stamps, PATCH /stamps/topup|dilute,
 * POST /chequebook/deposit and friends with no prompt and no auth, and
 * the gateway sits on 127.0.0.1:1633 where every app on the device (and
 * any page in any browser, via a no-cors POST) can reach it. Gating the
 * broadcast itself is the one place that covers all of those callers at
 * once, however the request got to the gateway — and the app's own
 * storage calls (ant_storage_buy_xdai, ant_storage_topup_xdai) go
 * through the same transport, so they pass only while SpendGuard holds a
 * permit for the one spend the user confirmed, and only with that
 * spend's own transactions.
 *
 * A read is answered by the app (#273): AntChainTransport.serve, which
 * the :node process backs with the chain-data router's bridge
 * (AntChainBridge.kt), so ant reads Gnosis the way the wallet does —
 * through the router's sources, not one RPC on its word. Its answer goes
 * back to ant as is, and it is never NULL or a -32000 (ant's two
 * can't-serve signals): a read the router can't answer reaches ant as an
 * error, never as a silent fallback to the configured gnosis_rpc URL.
 * An admitted broadcast returns NULL, as before: ant falls back to
 * gnosis_rpc and it goes out on the RPC the call was given. A refused broadcast
 * (`eth_send*`) gets a JSON-RPC error that isn't -32000, which ant
 * passes through to its caller as a genuine answer instead of retrying
 * it on gnosis_rpc (see ant_set_chain_transport). ant builds the request
 * body itself, so a plain substring match on the method can't be dodged
 * by a page; SpendGuard parses what it admits strictly.
 */
static const char *const BROADCAST_METHOD = "\"eth_send";

/* SpendGuard.admit(String): boolean, resolved in JNI_OnLoad (ant's
 * threads can't FindClass an app class). NULL = refuse everything. */
static JavaVM *g_vm = NULL;
static jclass g_guard_class = NULL;
static jmethodID g_guard_admit = NULL;

/* AntChainTransport.serve(byte[]): byte[], likewise. NULL = every read
 * gets an error. */
static jclass g_reads_class = NULL;
static jmethodID g_reads_serve = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_VERSION_1_6;
    g_vm = vm;
    jclass cls = (*env)->FindClass(env, "baby/freedom/swarm/SpendGuard");
    if (cls == NULL) {
        /* No gate to ask: every broadcast is refused. */
        (*env)->ExceptionClear(env);
        return JNI_VERSION_1_6;
    }
    jmethodID admit = (*env)->GetStaticMethodID(env, cls, "admit", "(Ljava/lang/String;)Z");
    if (admit == NULL) {
        (*env)->ExceptionClear(env);
    } else {
        g_guard_class = (jclass)(*env)->NewGlobalRef(env, cls);
        g_guard_admit = admit;
    }
    (*env)->DeleteLocalRef(env, cls);

    jclass reads = (*env)->FindClass(env, "baby/freedom/swarm/AntChainTransport");
    if (reads == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_VERSION_1_6;
    }
    jmethodID serve = (*env)->GetStaticMethodID(env, reads, "serve", "([B)[B");
    if (serve == NULL) {
        (*env)->ExceptionClear(env);
    } else {
        g_reads_class = (jclass)(*env)->NewGlobalRef(env, reads);
        g_reads_serve = serve;
    }
    (*env)->DeleteLocalRef(env, reads);
    return JNI_VERSION_1_6;
}

/* A JNIEnv for this thread, attaching it if it isn't; *attached says so. */
static JNIEnv *thread_env(int *attached) {
    *attached = 0;
    if (g_vm == NULL) return NULL;
    JNIEnv *env = NULL;
    jint st = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
        *attached = 1;
    } else if (st != JNI_OK) {
        return NULL;
    }
    return env;
}

/* Does SpendGuard let `request_json` out? Refuses on anything unexpected. */
static int guard_admits(const char *request_json) {
    if (g_guard_class == NULL || g_guard_admit == NULL) return 0;
    int attached = 0;
    JNIEnv *env = thread_env(&attached);
    if (env == NULL) return 0;
    jboolean ok = JNI_FALSE;
    /* ant's request bodies are ASCII JSON, so modified UTF-8 is exact. */
    jstring s = (*env)->NewStringUTF(env, request_json);
    if (s != NULL) {
        ok = (*env)->CallStaticBooleanMethod(env, g_guard_class, g_guard_admit, s);
        (*env)->DeleteLocalRef(env, s);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }
    /* ant's pool threads come and go; never leave one attached. */
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return ok == JNI_TRUE;
}

/*
 * A JSON-RPC error answering `request_json`, echoing its id when it's a
 * plain number or string (null otherwise); `message` must need no JSON
 * escaping. Out of memory aborts: NULL would fall back to gnosis_rpc —
 * for a broadcast, spend — and there's nothing else to answer with.
 */
static char *error_reply(const char *request_json, int code, const char *message) {
    char id[64] = "null";
    const char *p = strstr(request_json, "\"id\"");
    if (p != NULL) {
        p += 4;
        while (*p == ' ' || *p == ':') p++;
        size_t n = strcspn(p, ",} \t\r\n");
        if (n > 0 && n < sizeof(id) && memchr(p, '\\', n) == NULL) {
            memcpy(id, p, n);
            id[n] = '\0';
        }
    }
    static const char *const fmt =
        "{\"jsonrpc\":\"2.0\",\"id\":%s,\"error\":{\"code\":%d,\"message\":\"%s\"}}";
    size_t len = strlen(fmt) + strlen(id) + strlen(message) + 16;
    char *out = malloc(len);
    if (out == NULL) abort();
    snprintf(out, len, fmt, id, code, message);
    return out;
}

static char *guard_broadcasts(const char *request_json) {
    if (guard_admits(request_json)) return NULL;
    return error_reply(request_json, -32003,
                       "Freedom only lets the Swarm node send transactions you confirmed in the app");
}

/*
 * A read, answered by AntChainTransport.serve: its UTF-8 answer copied
 * into a malloc'd, NUL-terminated buffer. NULL if the JVM side couldn't
 * be reached or threw (serve itself never does).
 */
static char *app_reads(const char *request_json) {
    if (g_reads_class == NULL || g_reads_serve == NULL) return NULL;
    int attached = 0;
    JNIEnv *env = thread_env(&attached);
    if (env == NULL) return NULL;
    char *out = NULL;
    jsize len = (jsize)strlen(request_json);
    jbyteArray req = (*env)->NewByteArray(env, len);
    if (req != NULL) {
        (*env)->SetByteArrayRegion(env, req, 0, len, (const jbyte *)request_json);
        jbyteArray res = (jbyteArray)(*env)->CallStaticObjectMethod(env, g_reads_class, g_reads_serve, req);
        if (!(*env)->ExceptionCheck(env) && res != NULL) {
            jsize n = (*env)->GetArrayLength(env, res);
            out = malloc((size_t)n + 1);
            if (out == NULL) abort();
            (*env)->GetByteArrayRegion(env, res, 0, n, (jbyte *)out);
            out[n] = '\0';
        }
        if (res != NULL) (*env)->DeleteLocalRef(env, res);
        (*env)->DeleteLocalRef(env, req);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        free(out);
        out = NULL;
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return out;
}

/* The chain transport: broadcasts through SpendGuard, reads through the app. */
static char *chain_transport(const char *request_json, void *host_ctx) {
    (void)host_ctx;
    if (request_json == NULL) return error_reply("", -32600, "Empty request");
    if (strstr(request_json, BROADCAST_METHOD) != NULL) return guard_broadcasts(request_json);
    char *out = app_reads(request_json);
    /* Never NULL for a read: that would send it to gnosis_rpc alone. */
    return out != NULL ? out : error_reply(request_json, -32002, "Chain request failed");
}

/*
 * The handle chain_transport was last installed on (0: none). Nothing
 * but install_guard replaces a handle's transport, so once installed it
 * stays: installing it again before every storage call would only wait
 * on ant's write lock, i.e. for every read already inside the callback
 * (up to AntChainBridge's 60 s deadline) — and hold every other read up
 * behind it. Cleared before ant_shutdown, since a later handle may get
 * the same address.
 */
static _Atomic uintptr_t g_installed_on = 0;

/* Install the transport (the broadcast gate, the app's reads) on `handle`,
 * unless it already is; 0 on success (or a build with no chain to
 * broadcast on). */
static int install_guard(jlong handle) {
    uintptr_t h = (uintptr_t)handle;
    if (h != 0 && atomic_load(&g_installed_on) == h) return 0;
    int tr = ant_set_chain_transport((AntHandle *)h, chain_transport, NULL);
    if (tr == ANT_CHAIN_TRANSPORT_OK) atomic_store(&g_installed_on, h);
    return tr == ANT_CHAIN_TRANSPORT_OK || tr == ANT_CHAIN_TRANSPORT_UNSUPPORTED ? 0 : -1;
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
    /* Before the gateway starts: it captures its chain wiring there. A
     * build without chain support has no chain to broadcast on. */
    if (install_guard(handle) != 0) {
        (*env)->ReleaseStringUTFChars(env, api_addr, addr);
        (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
        throw_runtime(env, NULL, "ant_set_chain_transport failed");
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
 * Postage stamps (#116): ant's storage calls, each a JSON string or a
 * RuntimeException with ant's message. The two that spend
 * (storageBuyXdai, storageTopupXdai) put the broadcast gate back first,
 * so they can never run on a handle without it; SwarmNode wraps them in
 * SpendGuard.during, which is what lets their transactions out. All of
 * them block — the spends until their transactions confirm.
 */
static jstring json_or_throw(JNIEnv *env, char *json, char *err, const char *what) {
    if (json == NULL) {
        throw_runtime(env, err, what);
        return NULL;
    }
    ant_free_string(err);
    jstring out = (*env)->NewStringUTF(env, json);
    ant_free_string(json);
    return out;
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageStatus(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    char *err = NULL;
    char *json = ant_storage_status((const AntHandle *)(uintptr_t)handle, &err);
    return json_or_throw(env, json, err, "ant_storage_status failed");
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_settlementStatus(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    char *err = NULL;
    char *json = ant_storage_settlement_status((const AntHandle *)(uintptr_t)handle, &err);
    return json_or_throw(env, json, err, "ant_storage_settlement_status failed");
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageQuote(JNIEnv *env, jobject thiz, jlong handle,
                                               jstring gnosis_rpc, jint depth, jlong days) {
    (void)thiz;
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) return NULL;
    char *err = NULL;
    char *json = ant_storage_quote((const AntHandle *)(uintptr_t)handle, rpc, (uint8_t)depth,
                                   (uint64_t)days, &err);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    return json_or_throw(env, json, err, "ant_storage_quote failed");
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageTopupQuote(JNIEnv *env, jobject thiz, jlong handle,
                                                    jstring gnosis_rpc, jlong days) {
    (void)thiz;
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) return NULL;
    char *err = NULL;
    char *json = ant_storage_topup_quote((const AntHandle *)(uintptr_t)handle, rpc,
                                         (uint64_t)days, &err);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    return json_or_throw(env, json, err, "ant_storage_topup_quote failed");
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageValidity(JNIEnv *env, jobject thiz, jlong handle,
                                                  jstring gnosis_rpc) {
    (void)thiz;
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) return NULL;
    char *err = NULL;
    char *json = ant_storage_validity((const AntHandle *)(uintptr_t)handle, rpc, &err);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    return json_or_throw(env, json, err, "ant_storage_validity failed");
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageBuyXdai(JNIEnv *env, jobject thiz, jlong handle,
                                                 jstring gnosis_rpc, jint depth,
                                                 jstring amount_per_chunk, jboolean immutable) {
    (void)thiz;
    if (install_guard(handle) != 0) {
        throw_runtime(env, NULL, "ant_set_chain_transport failed");
        return NULL;
    }
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) return NULL;
    const char *amount = (*env)->GetStringUTFChars(env, amount_per_chunk, NULL);
    if (amount == NULL) {
        (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
        return NULL;
    }
    char *err = NULL;
    char *json = ant_storage_buy_xdai((const AntHandle *)(uintptr_t)handle, rpc, (uint8_t)depth,
                                      amount, immutable != JNI_FALSE ? 1 : 0, &err);
    (*env)->ReleaseStringUTFChars(env, amount_per_chunk, amount);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    return json_or_throw(env, json, err, "ant_storage_buy_xdai failed");
}

JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageTopupXdai(JNIEnv *env, jobject thiz, jlong handle,
                                                   jstring gnosis_rpc, jstring amount_per_chunk) {
    (void)thiz;
    if (install_guard(handle) != 0) {
        throw_runtime(env, NULL, "ant_set_chain_transport failed");
        return NULL;
    }
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) return NULL;
    const char *amount = (*env)->GetStringUTFChars(env, amount_per_chunk, NULL);
    if (amount == NULL) {
        (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
        return NULL;
    }
    char *err = NULL;
    char *json = ant_storage_topup_xdai((const AntHandle *)(uintptr_t)handle, rpc, amount, &err);
    (*env)->ReleaseStringUTFChars(env, amount_per_chunk, amount);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    return json_or_throw(env, json, err, "ant_storage_topup_xdai failed");
}

/*
 * ant_storage_connect_batch (#115): registers a batch the node's account
 * already owns on Gnosis — one the wallet bought for it through
 * SwarmNodeFunder. Buys nothing itself, but on a first connect ant sets
 * up the chequebook (deploy + settlement deposit), which broadcasts: so
 * it puts the gate back first, like the spends, and SwarmNode runs it
 * inside SpendGuard.during.
 */
JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageConnectBatch(JNIEnv *env, jobject thiz, jlong handle,
                                                      jstring gnosis_rpc, jstring batch_id) {
    (void)thiz;
    if (install_guard(handle) != 0) {
        throw_runtime(env, NULL, "ant_set_chain_transport failed");
        return NULL;
    }
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) return NULL;
    const char *batch = (*env)->GetStringUTFChars(env, batch_id, NULL);
    if (batch == NULL) {
        (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
        return NULL;
    }
    char *err = NULL;
    char *json = ant_storage_connect_batch((const AntHandle *)(uintptr_t)handle, rpc, batch, &err);
    (*env)->ReleaseStringUTFChars(env, batch_id, batch);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    return json_or_throw(env, json, err, "ant_storage_connect_batch failed");
}

/*
 * Finds the batches this account already owns on Gnosis (#118): a log
 * scan, then each still-funded one is registered with the node, so a
 * wallet's stamps come back after a reinstall or on another device. It
 * sends nothing on purpose, but ant also tries to set up settlement
 * when it registers one (a chequebook deploy when none is found), so the
 * broadcast gate goes back first: with no permit open, that deploy is
 * refused like any other broadcast.
 */
JNIEXPORT jstring JNICALL
Java_baby_freedom_swarm_AntNative_storageDiscover(JNIEnv *env, jobject thiz, jlong handle,
                                                  jstring gnosis_rpc) {
    (void)thiz;
    if (install_guard(handle) != 0) {
        throw_runtime(env, NULL, "ant_set_chain_transport failed");
        return NULL;
    }
    const char *rpc = (*env)->GetStringUTFChars(env, gnosis_rpc, NULL);
    if (rpc == NULL) return NULL;
    char *err = NULL;
    char *json = ant_storage_discover((const AntHandle *)(uintptr_t)handle, rpc, &err);
    (*env)->ReleaseStringUTFChars(env, gnosis_rpc, rpc);
    return json_or_throw(env, json, err, "ant_storage_discover failed");
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
    uintptr_t h = (uintptr_t)handle;
    atomic_compare_exchange_strong(&g_installed_on, &h, (uintptr_t)0);
    ant_shutdown((AntHandle *)(uintptr_t)handle);
}
