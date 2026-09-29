package baby.freedom.swarm

/**
 * Raw JNI surface over the embedded ant light-node (the Swarm half of
 * the combined `libfreedom_mobile_ffi.so`), bridged by the shim in
 * `src/main/cpp/ant_jni.c`.
 *
 * Handles are `*mut AntHandle` cast to [Long]; `0` is never a valid
 * handle. Every call is blocking — callers must stay off the main
 * thread. Failures surface as [RuntimeException] carrying the message
 * ant reported.
 */
internal object AntNative {
    init {
        // Pulls in libfreedom_mobile_ffi.so transitively via DT_NEEDED.
        System.loadLibrary("freedom_jni")
    }

    /** Boot the node against Swarm mainnet; returns a non-zero handle. */
    external fun init(dataDir: String): Long

    /**
     * Like [init], but as the account in [identity] (#77): the UTF-8
     * identity document `ant_init_with_identity` takes
     * (`{"signing_key","overlay_nonce"}`). ant neither reads nor writes
     * `identity.json` then; the shim zeroes its native copy, and the
     * caller zeroes [identity].
     */
    external fun initWithIdentity(dataDir: String, identity: ByteArray): Long

    /** `ant_account_info`: `{"eth_address","overlay","peer_id","agent"}`, null on a stale handle. */
    external fun accountInfo(handle: Long): String?

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

    /**
     * The running node's agent string (e.g. `ant-ffi/0.5.42`) — the
     * version of the embedded library actually loaded, independent of
     * the APK's version metadata. Null on a stale handle.
     */
    external fun agentString(handle: Long): String?

    /**
     * Postage stamps (#116), ant's storage calls; each returns ant's JSON
     * and throws with its message. `ant_storage_status`: the connected
     * batch (`batch_id`, `batch_depth`, …; `enabled=false` for none).
     */
    external fun storageStatus(handle: Long): String

    /**
     * `ant_storage_settlement_status`: whether this account has a
     * chequebook set up on this device (`enabled`, `chequebook`) — read
     * from ant's persisted association, no chain call.
     */
    external fun settlementStatus(handle: Long): String

    /** `ant_storage_quote`: what a [depth]-deep batch lasting [days] costs. No transaction. */
    external fun storageQuote(handle: Long, gnosisRpc: String, depth: Int, days: Long): String

    /** `ant_storage_topup_quote`: what extending the connected batch by [days] costs. No transaction. */
    external fun storageTopupQuote(handle: Long, gnosisRpc: String, days: Long): String

    /** `ant_storage_validity`: the connected batch's remaining lifetime. */
    external fun storageValidity(handle: Long, gnosisRpc: String): String

    /**
     * `ant_storage_buy_xdai`: SPENDS. Only through [SpendGuard.during]
     * — the shim's chain transport refuses every broadcast otherwise.
     */
    external fun storageBuyXdai(handle: Long, gnosisRpc: String, depth: Int, amountPerChunk: String, immutable: Boolean): String

    /** `ant_storage_topup_xdai` on the connected batch: SPENDS, likewise only through [SpendGuard.during]. */
    external fun storageTopupXdai(handle: Long, gnosisRpc: String, amountPerChunk: String): String

    /**
     * `ant_storage_discover`: registers every still-funded batch this
     * account owns on Gnosis (#118). Sends nothing: the shim puts the
     * broadcast gate back first, so the chequebook deploy ant may try
     * along the way is refused.
     */
    external fun storageDiscover(handle: Long, gnosisRpc: String): String

    /** Tear the node down and free the handle — it must not be reused. */
    external fun shutdown(handle: Long)

    /**
     * Re-open live sockets to the bootnodes after an OS suspension or a
     * network change (`ant_resume`). The swarm doesn't notice reaped
     * peer sockets on its own — the peer count stays healthy while
     * every retrieval hangs — so the host has to prompt it. Cheap and
     * idempotent; throws only if the node loop is already gone.
     */
    external fun resume(handle: Long)

    /** Quiesce background work before a suspension (`ant_suspend`). Blocks ≤ ~5 s. */
    external fun suspend(handle: Long)

    /** Undo [suspend] on foreground / network restored (`ant_wake`). */
    external fun wake(handle: Long)
}
