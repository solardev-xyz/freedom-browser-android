package baby.freedom.swarm

import android.util.Log
import java.io.File

/**
 * Raw JNI surface over corpus.core's Colibri stateless verifier (#100):
 * the `c4_*` unified-RPC state machine in `libc4.so`, bridged by
 * `src/main/cpp/colibri_jni.c` into `libfreedom_colibri.so`.
 *
 * One call's life: [createRpcCtx] → [execute] until its status is no
 * longer `pending`, answering each request it lists with [setResponse]
 * or [setError] in between → [freeRpcCtx]. The core does no I/O itself.
 * A request handle is a pointer into its context: valid only until that
 * context is freed.
 *
 * Every call is serialized on one lock — the core keeps process-wide
 * caches and isn't thread-safe — and runs on the caller's thread, so
 * stay off the main thread. Strings cross as UTF-8 bytes.
 *
 * [available] is false in a build without `libc4.so` (a checkout that
 * never ran `scripts/build-colibri.sh`), or when the library won't load;
 * callers then skip the Colibri tier.
 */
object ColibriNative {
    private const val TAG = "ColibriNative"

    /** `c4_create_rpc_ctx` prover modes (colibri.h): the proof comes from a remote prover. */
    const val PROVER_MODE_REMOTE = 1

    /** `C4_PROVER_FLAG_ZK_PROOF`: sync-committee transitions arrive as a ZK proof. */
    const val PROVER_FLAG_ZK_PROOF = 1 shl 7

    /**
     * `VERIFY_FLAG_PAP`: Pragmatic Adaptive Privacy — the call's own
     * parameters go to the RPCs (as they would anyway), the prover only
     * sees which storage the call touches.
     */
    const val VERIFY_FLAG_PAP = 1 shl 1

    private const val STATE_VERSION_MARKER = "freedom-colibri-storage-version"

    private val lock = Any()

    @Volatile
    private var initialized: Boolean? = null

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("freedom_colibri")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Colibri not in this build: ${e.message}")
            false
        }
    }

    /** Whether the verifier is in this build and was set up by [init]. */
    val available: Boolean get() = initialized == true

    /**
     * Load the library and keep the verifier's state (the sync committee
     * it has checked, a few KB) in [statesDir]. Idempotent; the first
     * call decides. False when Colibri can't be used in this process.
     *
     * The state is tied to the library's version: an older verifier's
     * files can fail a newer one's light-client catch-up and silently
     * cost every proof (iOS hit this going from 1.1.26 to 1.1.30), so a
     * version change starts from an empty directory — one fresh
     * checkpoint bootstrap, seconds.
     */
    fun init(statesDir: File): Boolean = synchronized(lock) {
        initialized?.let { return it }
        val ok = loaded && runCatching {
            val version = nVersion().toString()
            val marker = File(statesDir, STATE_VERSION_MARKER)
            if (runCatching { marker.readText() }.getOrNull() != version) {
                statesDir.deleteRecursively()
                statesDir.mkdirs()
                marker.writeText(version)
            }
            nInit(statesDir.absolutePath.toByteArray())
        }.getOrElse {
            Log.w(TAG, "Colibri init failed: $it")
            false
        }
        initialized = ok
        ok
    }

    /** The core's version as it reports it to provers (`major·65536 + minor·256 + patch`). */
    fun version(): Int = synchronized(lock) { nVersion() }

    /** A context for one call of [method] with [params] (a JSON array); 0 if the core refused it. */
    fun createRpcCtx(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long =
        synchronized(lock) {
            nCreate(method.toByteArray(), params.toByteArray(), chainId, proverFlags, verifyFlags, proverMode)
        }

    /** Reject a proof for `latest` from a block older than [unixSeconds]. */
    fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = synchronized(lock) { nSetMinLatestBlockTs(ctx, unixSeconds) }

    /** Advance [ctx]; its status JSON (`pending` with `requests`, `success`, `revert`, `error`). */
    fun execute(ctx: Long): String? = synchronized(lock) { nExecute(ctx)?.toString(Charsets.UTF_8) }

    /** Answer request [req] with [data], fetched from server [nodeIndex] of its list. */
    fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) = synchronized(lock) { nSetResponse(req, data, nodeIndex) }

    /** Fail request [req]. */
    fun setError(req: Long, error: String, nodeIndex: Int) =
        synchronized(lock) { nSetError(req, error.toByteArray(), nodeIndex) }

    fun freeRpcCtx(ctx: Long) = synchronized(lock) { nFree(ctx) }

    private external fun nInit(statesDir: ByteArray): Boolean
    private external fun nVersion(): Int
    private external fun nCreate(
        method: ByteArray,
        params: ByteArray,
        chainId: Long,
        proverFlags: Int,
        verifyFlags: Int,
        proverMode: Int,
    ): Long
    private external fun nSetMinLatestBlockTs(ctx: Long, ts: Long)
    private external fun nExecute(ctx: Long): ByteArray?
    private external fun nSetResponse(req: Long, data: ByteArray, nodeIndex: Int)
    private external fun nSetError(req: Long, error: ByteArray, nodeIndex: Int)
    private external fun nFree(ctx: Long)
}
