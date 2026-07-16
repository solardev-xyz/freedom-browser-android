package baby.freedom.swarm

/**
 * Raw JNI surface over the embedded ant light-node (`libant_ffi.so`),
 * bridged by the shim in `src/main/cpp/ant_jni.c`.
 *
 * Handles are `*mut AntHandle` cast to [Long]; `0` is never a valid
 * handle. Every call is blocking — callers must stay off the main
 * thread. Failures surface as [RuntimeException] carrying the message
 * ant reported.
 */
internal object AntNative {
    init {
        // Pulls in libant_ffi.so transitively via DT_NEEDED.
        System.loadLibrary("ant_jni")
    }

    /** Boot the node against Swarm mainnet; returns a non-zero handle. */
    external fun init(dataDir: String): Long

    /**
     * Start the in-process bee-shaped HTTP gateway on [apiAddr]
     * (e.g. `127.0.0.1:1633`). [lightMode] false = ultra-light
     * (read-only) mode; [gnosisRpc] backs the on-chain endpoints,
     * `""` disables them.
     */
    external fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String)

    external fun stopGateway(handle: Long)

    /** Connected BZZ peer count; -1 on a stale handle. */
    external fun peerCount(handle: Long): Int

    /** Tear the node down and free the handle — it must not be reused. */
    external fun shutdown(handle: Long)
}
