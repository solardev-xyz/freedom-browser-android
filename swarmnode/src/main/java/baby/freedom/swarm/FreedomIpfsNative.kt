package baby.freedom.swarm

/**
 * Raw JNI surface over the embedded freedom-ipfs reader (the IPFS half
 * of the combined `libfreedom_mobile_ffi.so`), bridged by the shim in
 * `src/main/cpp/freedom_ipfs_jni.c`.
 *
 * Handles are `*mut FreedomIpfsNode` cast to [Long]; `0` is never a
 * valid handle. Calls are blocking — callers must stay off the main
 * thread. Unlike ant, the C ABI reports failures as `false`/`0`
 * returns rather than error strings, so no exceptions are thrown here.
 */
internal object FreedomIpfsNative {
    init {
        // Pulls in libfreedom_mobile_ffi.so transitively via DT_NEEDED.
        System.loadLibrary("freedom_jni")
    }

    /** `FREEDOM_IPFS_ROUTING_MODE_*` constants from freedom_ipfs.h. */
    const val ROUTING_AUTO = 0
    const val ROUTING_DELEGATED = 1
    const val ROUTING_LIGHT_DHT = 2
    const val ROUTING_OFFLINE = 3

    /**
     * Open (creating on first use) the persistent block cache at
     * [dataDir]. [maxCacheBytes] `0` = library default (256 MiB).
     * Returns `0` on failure.
     */
    external fun nodeNew(dataDir: String, maxCacheBytes: Long): Long

    /** Free the handle — it must not be reused. */
    external fun nodeFree(handle: Long)

    /**
     * Start the loopback HTTP gateway on [addr] (e.g. `127.0.0.1:0`)
     * with online retrieval. `NULL` router / zeroed tuning knobs in the
     * shim take the library defaults.
     */
    external fun startGatewayOnline(handle: Long, addr: String, routingMode: Int): Boolean

    external fun stopGateway(handle: Long): Boolean

    /** Full `http://127.0.0.1:<port>` base URL, or null when not running. */
    external fun gatewayUrl(handle: Long): String?

    /** freedom-ipfs release version, e.g. `0.4.3`. */
    external fun version(): String?

    /**
     * `FreedomIpfsDiagnostics` flattened to `long[11]`: blockCount,
     * totalBytes, cacheHits, httpProviderBlocks, bitswapBlocks,
     * delegatedProviderLookups, delegatedProviderResults,
     * delegatedProviderErrors, dhtProviderLookups, dhtProviderResults,
     * dhtProviderErrors.
     */
    external fun diagnostics(handle: Long): LongArray

    /** Tell the node connectivity changed (drops stale provider state). */
    external fun handleNetworkChange(handle: Long): Boolean
}
