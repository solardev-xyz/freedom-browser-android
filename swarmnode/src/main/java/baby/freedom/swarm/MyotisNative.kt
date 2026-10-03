package baby.freedom.swarm

/**
 * Raw JNI surface over the embedded Myotis Ethereum light client (the
 * `myotis_*` C ABI inside `libfreedom_mobile_ffi.so`), bridged by the
 * shim in `src/main/cpp/myotis_jni.c`. The node lifecycle (and
 * checkpoint recovery's create) is bridged, plus the verified reads the
 * app asks: [ethCall] (name resolution, #101) and the chain-data router's
 * account, code, call, receipt and block reads (#329); see [MyotisNode]
 * for the wrapper the app uses.
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

    /**
     * Like [create], but bootstrapping from the caller's beacon block
     * [root] (32-byte hex) at header [slot] instead of the embedded
     * checkpoint — stale-anchor recovery (#195). The engine doesn't
     * authenticate the root; it pins the bootstrap to it and verifies
     * forward from it. The first call on a directory records the anchor
     * (`sync-anchor[-net].json`); the same root+slot later resumes it,
     * anything else is [ANCHOR_MISMATCH]. Same sentinels as [create].
     */
    external fun createWithCheckpoint(network: String, dataDir: String, root: String, slot: Long): Long

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

    /**
     * `myotis_eth_call_json`, anonymous and with zero value: a call of
     * [data] (0x-hex calldata) on [to] at [block] (`latest` = the
     * verified head), executed locally against proven state. The engine's
     * JSON as UTF-8 bytes — `{"status":"ok","resultHex"}`,
     * `{"status":"revert","dataHex"}`, `{"status":"unavailable","reason"}`
     * or `{"error"}`, each with `blockNumber` and `verified` (finalized).
     * Blocks for up to the engine's ~90 s budget.
     */
    external fun ethCall(handle: Long, to: String, data: String, block: String): ByteArray?

    /**
     * `myotis_request_account_json`: [address]'s account proven at [block]
     * (`latest` = the verified head) — `nonce` (-1 when the account
     * doesn't exist), `balanceWei` (decimal, null when it doesn't),
     * `blockNumber`, and `failReason` set when the proof couldn't be
     * anchored to the beacon chain; `{"error"}` when it can't read now.
     */
    external fun requestAccount(handle: Long, address: String, block: String): ByteArray?

    /** `myotis_get_code_json`: `codeHex`, `exists`, `blockNumber`, `failReason`, as [requestAccount]. */
    external fun getCode(handle: Long, address: String, block: String): ByteArray?

    /**
     * `myotis_eth_call_json` with a caller: [from] (`""` = anonymous),
     * [value] in wei as a decimal string. Same answers as [ethCall].
     */
    external fun ethCallFrom(handle: Long, from: String, to: String, data: String, value: String, block: String): ByteArray?

    /**
     * `myotis_get_transaction_receipt_json`: the receipt, the literal
     * `null` when the transaction wasn't seen in the window the engine
     * scanned (not proof it doesn't exist), or `{"error"}`.
     */
    external fun transactionReceipt(handle: Long, txHash: String): ByteArray?

    /** `myotis_get_block_by_number_json`: the block at [tag] (`latest`, `0x…`), `null`, or `{"error"}`. */
    external fun blockByNumber(handle: Long, tag: String, fullTransactions: Boolean): ByteArray?
}
