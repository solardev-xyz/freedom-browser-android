package baby.freedom.swarm

/**
 * Raw JNI surface over the embedded Myotis Ethereum light client (the
 * `myotis_*` C ABI inside `libfreedom_mobile_ffi.so`), bridged by the
 * shim in `src/main/cpp/myotis_jni.c`. Only the node lifecycle is
 * bridged; see [MyotisNode] for the wrapper the app uses.
 *
 * Handles are the engine's own `int64` ids (>= 1). Failures are the
 * engine's sentinels, not exceptions: a negative id from [create],
 * `false` from [start]/[pause]/[resume], `"{}"` from [statusJson] for an
 * unknown handle. Every call may block — [stop] in particular has no
 * wall-clock bound — so callers stay off the main thread and serialize
 * start/pause/resume/stop per handle (the engine requires it).
 */
internal object MyotisNative {
    init {
        // Pulls in libfreedom_mobile_ffi.so transitively via DT_NEEDED.
        System.loadLibrary("freedom_jni")
    }

    /** `myotis_create` sentinels (myotis_engine.h). */
    const val CREATE_FAILED = -1L
    const val UNSUPPORTED_NETWORK = -2L
    const val ANCHOR_MISMATCH = -3L

    /**
     * ABI handshake: installs the engine's log ring (idempotent) and
     * returns the engine's ABI version. Nothing else may be called if it
     * differs from [headerAbiVersion].
     */
    external fun init(): Int

    /** `MYOTIS_ABI_VERSION` of the vendored header the shim was built with. */
    external fun headerAbiVersion(): Int

    /**
     * Allocate a not-yet-started handle for [network] (`mainnet`,
     * `gnosis`) persisting into [dataDir]. >= 1 on success, else one of
     * [CREATE_FAILED], [UNSUPPORTED_NETWORK], [ANCHOR_MISMATCH].
     */
    external fun create(network: String, dataDir: String): Long

    /** Start the sync loop. False for an unknown / already running handle. */
    external fun start(handle: Long): Boolean

    /** Drain registered work and shut the handle down. Synchronous, unbounded. */
    external fun stop(handle: Long)

    /** Running → Paused: tear down networking, keep warm state. */
    external fun pause(handle: Long): Boolean

    /** Paused → Running warm restart. False if not paused or the rebuild failed. */
    external fun resume(handle: Long): Boolean

    /** eth/69 served-block window, clamped by the engine to [1, 4096]. */
    external fun setServedBlockWindow(handle: Long, blocks: Int): Boolean

    /** `myotis_status_json` as UTF-8 bytes (camelCase keys; `{}` for an unknown handle). */
    external fun statusJson(handle: Long): ByteArray?

    /** Up to [max] buffered engine tracing lines, newline-joined, as UTF-8 bytes. */
    external fun drainLogs(max: Int): ByteArray?
}
