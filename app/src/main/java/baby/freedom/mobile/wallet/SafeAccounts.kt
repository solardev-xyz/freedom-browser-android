package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.chains.rpc.undisputed
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.ledger.Ledger
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Safe multisig account (#141), desktop's `safe` wallet record: the
 * frozen init params — [owners], [threshold], [saltNonce] — its
 * counterfactual [address] was predicted from, and whether it's been
 * activated (deployed) yet. The init params never change once stored:
 * they are what makes the address recomputable, so the same Safe can be
 * deployed later on another chain and claim what was sent there.
 *
 * Once active, the Safe's owners and threshold can change on chain (an
 * `addOwnerWithThreshold`, `removeOwner`, `swapOwner` or `changeThreshold`
 * it executed, #341). What the chain last said, when it differs from the
 * init params, is kept beside them in [currentOwners] / [currentThreshold];
 * [ownersNow] / [thresholdNow] are what signatures are counted against.
 *
 * Owners are addresses: this wallet's own accounts, and accounts on other
 * devices (desktop Freedom, another phone) that co-sign by signing the
 * shared request. Public data only.
 */
data class SafeAccount(
    val address: String,
    val name: String,
    val owners: List<String>,
    val threshold: Int,
    val saltNonce: String,
    /** The chain it lives on; v1 only [SafeProtocol.CHAIN_ID]. */
    val chainId: Long,
    val deployed: Boolean,
    val createdAt: Long,
    /** The owners the Safe's contract last reported ([SafeChain.policy]), in its order; null while they are still [owners]. */
    val currentOwners: List<String>? = null,
    /** The threshold the Safe's contract last reported; null while it is still [threshold]. */
    val currentThreshold: Int? = null,
) {
    /** Who owns the Safe now, as far as this phone knows: the chain's last word, or the init params. */
    val ownersNow: List<String> get() = currentOwners ?: owners

    /** How many owners must sign now, as far as this phone knows. */
    val thresholdNow: Int get() = currentThreshold ?: threshold

    /** Whether [address] is one of the Safe's owners now ([ownersNow]). */
    fun isOwner(address: String) = ownersNow.any { it.equals(address, ignoreCase = true) }

    /**
     * This Safe with [owners] / [threshold] read from its contract as what
     * it has now; the init params stay as they are. Back to the init params
     * (nulls) when the chain matches them again.
     */
    fun withOnChain(owners: List<String>, threshold: Int): SafeAccount {
        val same = threshold == this.threshold && sameOwners(owners, this.owners)
        return copy(currentOwners = owners.takeUnless { same }, currentThreshold = threshold.takeUnless { same })
    }

    companion object {
        /** Whether [a] and [b] name the same owners, in any order and case. */
        fun sameOwners(a: List<String>, b: List<String>): Boolean =
            a.size == b.size && a.map { it.lowercase() }.toSet() == b.map { it.lowercase() }.toSet()
    }
}

/**
 * What [SafeAccounts.applyOnChain] changed: the threshold before and now,
 * whether the owner list changed, and how many collected signatures were
 * taken off pending items because their signer is no longer an owner.
 */
data class SafePolicyChange(val thresholdBefore: Int, val thresholdNow: Int, val ownersChanged: Boolean, val droppedSignatures: Int) {
    val thresholdChanged: Boolean get() = thresholdBefore != thresholdNow
}

/**
 * A SafeTx or SafeMessage waiting for its owners' signatures (desktop's
 * pending-store entry / message session): the exact [typedData] they
 * sign, its hash ([id]), and the signatures collected so far — just
 * bytes, useless without the Safe and safe to keep on disk.
 */
data class SafePending(
    /** The SafeTx / SafeMessage hash, `0x` + 64 hex digits. */
    val id: String,
    val safe: String,
    val kind: Kind,
    val chainId: Long,
    val typedData: String,
    val threshold: Int,
    val signatures: List<SafeProtocol.OwnerSignature>,
    val createdAt: Long,
    /** For a transaction: what it pays, as the proposer described it. */
    val payment: Payment? = null,
    /** For a message: the words signed. */
    val text: String? = null,
    /** The last `execTransaction` sent for it, while it's waiting to be mined. */
    val execHash: String? = null,
    /** The account that sent [execHash] and the account nonce it used, when known. */
    val execFrom: String? = null,
    val execNonce: BigInteger? = null,
    /** The Safe's nonce moved past this transaction without it: it can never execute. */
    val superseded: Boolean = false,
    /**
     * Every `execTransaction` the sender stopped following (Stop tracking)
     * before it was mined, oldest first: until its account nonce is used
     * by another send, any of them can still land and pay out, whatever
     * happens to this entry. All are kept, not just the latest — an
     * earlier one can be the one mined ([MAX_ABANDONED] at most).
     */
    val abandonedExecs: List<AbandonedExec> = emptyList(),
) {
    enum class Kind { TX, MESSAGE }

    /**
     * [text] as the pending page and its title show it: what would draw as
     * nothing or rearrange the words written as a visible escape, as the
     * co-sign page shows the same message ([SafeProtocol.Request.Message.shownText]).
     */
    val shownText: String? get() = text?.let(Eip712::visible)

    /** An abandoned execution: its hash, and the account and account nonce that sent it (null when not known). */
    data class AbandonedExec(val hash: String, val from: String? = null, val nonce: BigInteger? = null)

    /** [amount] base units of [symbol] ([decimals]) to [recipient]; [token] is null for the native currency. */
    data class Payment(val recipient: String, val amount: BigInteger, val symbol: String, val decimals: Int, val token: String?)

    val collected: Int get() = signatures.size
    val ready: Boolean get() = signatures.size >= threshold

    fun hasSigned(address: String) = signatures.any { it.signer.equals(address, ignoreCase = true) }

    /** How many of the signatures are from [owners] — the only ones the Safe counts towards its threshold. */
    fun countedBy(owners: List<String>): Int = signatures.count { s -> owners.any { it.equals(s.signer, ignoreCase = true) } }

    /** The request as other owners' devices take it ([SafeProtocol.parseRequest]). */
    fun shareText(): String = SafeProtocol.shareText(JSONObject(typedData), text)

    /** The SafeTx itself (a [Kind.TX] only). */
    fun safeTx(): SafeProtocol.SafeTx = (SafeProtocol.parseRequest(typedData) as SafeProtocol.Request.Tx).tx

    /** A message's EIP-1271 signature once [ready]: the owners' signatures, sorted and packed. */
    fun combinedSignature(): String = "0x" + SafeProtocol.signatureBytes(signatures).toHex()

    companion object {
        /** Abandoned executions kept per entry (the oldest go first); each needs a fresh Execute and Stop tracking. */
        const val MAX_ABANDONED = 16
    }
}

/** Everything about the wallet's Safes on this phone. */
data class SafeState(val safes: List<SafeAccount>, val pending: List<SafePending>) {
    fun safe(address: String) = safes.firstOrNull { it.address.equals(address, ignoreCase = true) }

    fun pendingFor(address: String) = pending.filter { it.safe.equals(address, ignoreCase = true) }

    companion object {
        val EMPTY = SafeState(emptyList(), emptyList())
    }
}

/** A Safe step that can't be taken, with what to tell the user. */
class SafeException(message: String) : Exception(message)

/**
 * The Safes on disk (`noBackupFilesDir/wallet/safes.json`), tied to the
 * vault they belong to ([VaultRecord.identityTag]) like the account list:
 * a file left over from a removed or replaced wallet reads as none.
 */
class SafeStore internal constructor(private val file: File) {
    /** The state saved for the vault tagged [vaultTag]; empty when there's none, null when the file can't be read. */
    fun read(vaultTag: String): SafeState? = runCatching {
        if (!file.exists()) return SafeState.EMPTY
        val o = JSONObject(file.readText())
        if (o.optString("vault") != vaultTag) return SafeState.EMPTY
        val safes = o.getJSONArray("safes").objects().map { s ->
            SafeAccount(
                address = s.getString("address"),
                name = s.getString("name"),
                owners = s.getJSONArray("owners").let { a -> (0 until a.length()).map { a.getString(it) } },
                threshold = s.getInt("threshold"),
                saltNonce = s.getString("saltNonce"),
                chainId = s.getLong("chainId"),
                deployed = s.getBoolean("deployed"),
                createdAt = s.getLong("createdAt"),
                currentOwners = s.optJSONArray("currentOwners")?.let { a -> (0 until a.length()).map { SafeProtocol.eip55(a.getString(it)) } },
                currentThreshold = if (s.has("currentThreshold") && !s.isNull("currentThreshold")) s.getInt("currentThreshold") else null,
            ).also {
                // Frozen params that no longer give the stored address mean the record was changed: never trust it.
                require(SafeProtocol.predictAddress(it.owners, it.threshold, it.saltNonce) == it.address)
                // What the chain said is kept whole or not at all, and is a policy a Safe can have.
                require((it.currentOwners == null) == (it.currentThreshold == null))
                require(SafePolicy.valid(it.ownersNow, it.thresholdNow))
            }
        }
        val pending = o.getJSONArray("pending").objects().map { p ->
            SafePending(
                id = p.getString("id"),
                safe = p.getString("safe"),
                kind = SafePending.Kind.valueOf(p.getString("kind")),
                chainId = p.getLong("chainId"),
                typedData = p.getString("typedData"),
                threshold = p.getInt("threshold"),
                signatures = p.getJSONArray("signatures").objects().map { SafeProtocol.OwnerSignature(it.getString("signer"), it.getString("data")) },
                createdAt = p.getLong("createdAt"),
                payment = p.optJSONObject("payment")?.let { m ->
                    SafePending.Payment(
                        m.getString("recipient"),
                        BigInteger(m.getString("amount")),
                        m.getString("symbol"),
                        m.getInt("decimals"),
                        m.optString("token").takeIf { it.isNotEmpty() },
                    )
                },
                text = p.optString("text").takeIf { p.has("text") && !p.isNull("text") },
                execHash = p.optString("execHash").takeIf { p.has("execHash") && !p.isNull("execHash") },
                superseded = p.optBoolean("superseded"),
                execFrom = p.optString("execFrom").takeIf { p.has("execFrom") && !p.isNull("execFrom") },
                execNonce = p.optString("execNonce").takeIf { p.has("execNonce") && !p.isNull("execNonce") }?.let(::BigInteger),
                abandonedExecs = p.optJSONArray("abandonedExecs")?.objects()?.map { a ->
                    SafePending.AbandonedExec(
                        a.getString("hash"),
                        a.optString("from").takeIf { a.has("from") && !a.isNull("from") },
                        a.optString("nonce").takeIf { a.has("nonce") && !a.isNull("nonce") }?.let(::BigInteger),
                    )
                }
                    // A record from before the list: its one abandoned execution, sender unknown.
                    ?: listOfNotNull(p.optString("abandonedExec").takeIf { p.has("abandonedExec") && !p.isNull("abandonedExec") }?.let { SafePending.AbandonedExec(it) }),
            )
        }
        SafeState(safes, pending)
    }.getOrNull()

    fun write(vaultTag: String, state: SafeState) {
        val o = JSONObject()
            .put("vault", vaultTag)
            .put(
                "safes",
                JSONArray().apply {
                    state.safes.forEach { s ->
                        put(
                            JSONObject().put("address", s.address).put("name", s.name).put("owners", JSONArray(s.owners))
                                .put("threshold", s.threshold).put("saltNonce", s.saltNonce).put("chainId", s.chainId)
                                .put("deployed", s.deployed).put("createdAt", s.createdAt)
                                .put("currentOwners", s.currentOwners?.let { JSONArray(it) } ?: JSONObject.NULL)
                                .put("currentThreshold", s.currentThreshold ?: JSONObject.NULL),
                        )
                    }
                },
            )
            .put(
                "pending",
                JSONArray().apply {
                    state.pending.forEach { p ->
                        put(
                            JSONObject().put("id", p.id).put("safe", p.safe).put("kind", p.kind.name).put("chainId", p.chainId)
                                .put("typedData", p.typedData).put("threshold", p.threshold).put("createdAt", p.createdAt)
                                .put(
                                    "signatures",
                                    JSONArray().apply { p.signatures.forEach { put(JSONObject().put("signer", it.signer).put("data", it.data)) } },
                                )
                                .put(
                                    "payment",
                                    p.payment?.let { m ->
                                        JSONObject().put("recipient", m.recipient).put("amount", m.amount.toString()).put("symbol", m.symbol)
                                            .put("decimals", m.decimals).put("token", m.token ?: "")
                                    } ?: JSONObject.NULL,
                                )
                                .put("text", p.text ?: JSONObject.NULL)
                                .put("execHash", p.execHash ?: JSONObject.NULL)
                                .put("superseded", p.superseded)
                                .put("execFrom", p.execFrom ?: JSONObject.NULL)
                                .put("execNonce", p.execNonce?.toString() ?: JSONObject.NULL)
                                .put(
                                    "abandonedExecs",
                                    JSONArray().apply {
                                        p.abandonedExecs.forEach {
                                            put(
                                                JSONObject().put("hash", it.hash).put("from", it.from ?: JSONObject.NULL)
                                                    .put("nonce", it.nonce?.toString() ?: JSONObject.NULL),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("couldn't save the Safe accounts")
        }
    }

    fun wipe() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    companion object {
        fun get(context: Context) = SafeStore(File(context.applicationContext.noBackupFilesDir, "wallet/safes.json"))
    }
}

/**
 * The wallet's Safe accounts (#141), after desktop's `safe-service.js`,
 * `safe-transactions.js` and `safe-messages.js`, kept in step with the
 * [Vault] like [WalletAccounts]: read while the wallet is locked or open
 * (it's public data), wiped with the wallet.
 *
 * A user-paced task board, not a pipeline: a Safe is created for free
 * (its address predicted, its params frozen), activated when an owner
 * account here pays for it, and each SafeTx / SafeMessage collects owner
 * signatures one at a time — from this wallet's accounts, or pasted or
 * scanned from another device — until the threshold is met. One pending
 * SafeTx per Safe, as on desktop: a single slot sidesteps nonce ordering.
 * Every change is on disk before it's shown.
 *
 * The activation and `execTransaction` go out through the wallet's own
 * send flow ([WalletSender]), marked with a [SafeCallLabel]; [noteSend]
 * follows them, so an activation that got mined marks the Safe active and
 * an execution that got mined clears its transaction, whether or not the
 * Safe's page is open.
 */
class SafeAccounts internal constructor(
    private val vault: Vault,
    private val store: SafeStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Signs typed data as a Ledger account (#142): on the device, which shows it. */
    private val ledgerSign: suspend (WalletAccount, Eip712.TypedData, ByteArray) -> String = { _, _, _ -> throw SafeException(Strings.get(R.string.safe_error_ledger_key)) },
) {
    private val _state = MutableStateFlow<SafeState?>(null)

    /** The Safes and what's pending for them; null when there's no wallet, or the file can't be read. */
    val state: StateFlow<SafeState?> = _state.asStateFlow()

    private val mutex = Mutex()
    private var started = false

    /** The vault identity tag [state] was read under (guarded by [mutex]). */
    private var loadedTag: String? = null

    /**
     * Follows the vault (and, given [sends], the wallet's sends) from now on. Idempotent.
     * [sends] must deliver every status, none conflated away ([WalletSender.changes]): a
     * Confirmed skipped between a Pending and Done's null would read as Stop tracking.
     */
    fun start(sends: Flow<SendStatus?>? = null) {
        synchronized(this) {
            if (started) return
            started = true
        }
        if (sends != null) {
            scope.launch {
                var last: SendStatus? = null
                sends.collect { s ->
                    val prev = last
                    last = s
                    lastSend = s
                    // The sender moved off a send (Stop tracking, or another send took its place):
                    // an execution it was following is no longer going out.
                    if (prev?.hash != null && prev.hash != s?.hash) runCatching { noteDropped(prev) }
                    s?.let { runCatching { noteSend(it) } }
                }
            }
        }
        scope.launch { vault.state.collect { reconcile(it) } }
    }

    /**
     * The sender's latest status, replayed once [state] is read: at process
     * start the journal can bring back a mined activation or execution
     * before safes.json is, and [noteSend] needs the state to apply it to.
     */
    @Volatile
    private var lastSend: SendStatus? = null

    internal suspend fun reconcile(state: Vault.State) {
        try {
            val loaded = mutex.withLock {
                var loaded = false
                when (state) {
                    // The tag is checked every time, not only when nothing is loaded: a
                    // conflated state flow can skip the Empty between one wallet and the next.
                    is Vault.State.Locked, is Vault.State.Unlocked -> {
                        val tag = withContext(io) { vault.identityTag() }
                        if (_state.value == null || tag != loadedTag) {
                            _state.value = tag?.let { withContext(io) { store.read(it) } }
                            loadedTag = tag
                            loaded = _state.value != null
                        }
                    }
                    Vault.State.Empty -> {
                        _state.value = null
                        loadedTag = null
                        withContext(io) { store.wipe() }
                    }
                    Vault.State.Unreadable -> {
                        _state.value = null
                        loadedTag = null
                    }
                }
                loaded
            }
            // A send the sender published before this state was read (see [lastSend]).
            if (loaded) lastSend?.let { noteSend(it) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Safe sync failed: ${t.javaClass.simpleName}")
        }
    }

    /** Applies [change] to the state, saves it, then shows it; returns what [change] returned. */
    private suspend fun <T> update(change: (SafeState) -> Pair<SafeState, T>): T = mutex.withLock {
        val tag = withContext(io) { vault.identityTag() } ?: throw SafeException(Strings.get(R.string.safe_error_no_wallet))
        // What's in memory is only this wallet's if it was read under this wallet's tag.
        val current = _state.value?.takeIf { loadedTag == tag } ?: withContext(io) { store.read(tag) }
            ?: throw SafeException(Strings.get(R.string.safe_error_unreadable))
        val (next, result) = change(current)
        if (next != current || loadedTag != tag) {
            if (next != current) withContext(io) { store.write(tag, next) }
            _state.value = next
            loadedTag = tag
        }
        result
    }

    /**
     * A new Safe: a fresh salt, the address predicted from [owners] and
     * [threshold], stored frozen. Costs nothing and needs no network; it
     * can take funds at once and is activated later. At least one owner
     * must be one of this wallet's accounts ([local]) — something here has
     * to be able to sign and pay for its activation.
     */
    suspend fun create(name: String, owners: List<String>, threshold: Int, local: Collection<String>): SafeAccount {
        if (!SafeProtocol.validPreset(owners.size, threshold)) throw SafeException(Strings.get(R.string.safe_error_preset))
        val checked = owners.map { SafeProtocol.eip55(it) }
        if (checked.map { it.lowercase() }.toSet().size != checked.size) throw SafeException(Strings.get(R.string.safe_error_owner_twice))
        if (checked.none { o -> local.any { it.equals(o, ignoreCase = true) } }) {
            throw SafeException(Strings.get(R.string.safe_error_no_local_owner))
        }
        val salt = SafeProtocol.newSaltNonce()
        val address = SafeProtocol.predictAddress(checked, threshold, salt)
        return update { s ->
            if (s.safes.size >= MAX_SAFES) throw SafeException(Strings.get(R.string.safe_error_too_many_safes))
            if (s.safes.any { o -> checked.any { it.equals(o.address, ignoreCase = true) } }) throw SafeException(Strings.get(R.string.safe_error_safe_owns_safe))
            val safe = SafeAccount(
                address = address,
                name = name.trim().take(MAX_NAME).ifBlank { Strings.get(R.string.safe_default_name, s.safes.size + 1) },
                owners = checked,
                threshold = threshold,
                saltNonce = salt,
                chainId = SafeProtocol.CHAIN_ID,
                deployed = false,
                createdAt = clock(),
            )
            s.copy(safes = s.safes + safe) to safe
        }
    }

    /** Records that [address] is deployed on its chain. */
    suspend fun markDeployed(address: String) = update { s ->
        s.copy(safes = s.safes.map { if (it.address.equals(address, ignoreCase = true)) it.copy(deployed = true) else it }) to Unit
    }

    /**
     * Brings [address]'s record in line with its contract: [owners] and
     * [threshold] as just read from the chain ([SafeChain.policy], a
     * confirmed read). The init params stay; [SafeAccount.currentOwners] /
     * [SafeAccount.currentThreshold] take the new values. Every pending item
     * of the Safe takes the new threshold, and loses the signatures of
     * signers who are no longer owners — they can't count on chain — unless
     * an execution of it is going out, whose calldata (and how [noteSend]
     * recognises it) is those signatures. Returns what changed, or null if
     * nothing did. Throws [SafeException] if [owners] / [threshold] are no
     * Safe's policy.
     */
    suspend fun applyOnChain(address: String, owners: List<String>, threshold: Int): SafePolicyChange? {
        if (!SafePolicy.valid(owners, threshold)) throw SafeException(Strings.get(R.string.safe_error_no_owner_list))
        val checked = owners.map { SafeProtocol.eip55(it) }
        return update { s ->
            val current = s.safe(address) ?: throw SafeException(Strings.get(R.string.safe_error_gone))
            val ownersChanged = !SafeAccount.sameOwners(current.ownersNow, checked)
            val thresholdBefore = current.thresholdNow
            val safe = current.withOnChain(checked, threshold)
            var dropped = 0
            val pending = s.pending.map { p ->
                if (!p.safe.equals(current.address, ignoreCase = true)) return@map p
                val kept = if (p.execHash != null) p.signatures else p.signatures.filter { sig -> checked.any { it.equals(sig.signer, ignoreCase = true) } }
                dropped += p.signatures.size - kept.size
                p.copy(threshold = threshold, signatures = kept)
            }
            val next = s.copy(safes = s.safes.map { if (it.address.equals(current.address, ignoreCase = true)) safe else it }, pending = pending)
            next to SafePolicyChange(thresholdBefore, threshold, ownersChanged, dropped)
                .takeIf { it.thresholdChanged || ownersChanged || dropped > 0 }
        }
    }

    /** Takes [address] off this phone, with what was pending for it. The Safe and its funds stay on chain. */
    suspend fun remove(address: String) = update { s ->
        s.copy(
            safes = s.safes.filterNot { it.address.equals(address, ignoreCase = true) },
            pending = s.pending.filterNot { it.safe.equals(address, ignoreCase = true) },
        ) to Unit
    }

    /**
     * A new SafeTx [tx] from [safe] (active, with no other transaction
     * pending) waiting for signatures; [payment] is what it pays, for the
     * board. The caller read [tx]'s nonce from the Safe.
     */
    suspend fun proposeTx(safe: SafeAccount, tx: SafeProtocol.SafeTx, payment: SafePending.Payment): SafePending {
        val typedData = SafeProtocol.safeTxTypedData(safe.address, safe.chainId, tx)
        val id = "0x" + SafeProtocol.hash(typedData).toHex()
        return update { s ->
            val current = s.safe(safe.address) ?: throw SafeException(Strings.get(R.string.safe_error_gone))
            if (!current.deployed) throw SafeException(Strings.get(R.string.safe_error_activate_to_send))
            if (s.pendingFor(safe.address).any { it.kind == SafePending.Kind.TX }) {
                throw SafeException(Strings.get(R.string.safe_error_tx_waiting))
            }
            val entry = SafePending(id, current.address, SafePending.Kind.TX, current.chainId, typedData.toString(), current.thresholdNow, emptyList(), clock(), payment = payment)
            s.copy(pending = s.pending + entry) to entry
        }
    }

    /** A new SafeMessage for [text] from [safe], waiting for signatures (the same words again give the same one). */
    suspend fun proposeMessage(safe: SafeAccount, text: String): SafePending {
        if (text.isEmpty()) throw SafeException(Strings.get(R.string.safe_error_message_empty))
        if (text.length > MAX_MESSAGE) throw SafeException(Strings.get(R.string.safe_error_message_too_long))
        val typedData = SafeProtocol.messageTypedData(safe.address, safe.chainId, text)
        val id = "0x" + SafeProtocol.hash(typedData).toHex()
        return update { s ->
            val current = s.safe(safe.address) ?: throw SafeException(Strings.get(R.string.safe_error_gone))
            // Its EIP-1271 signature is checked by the Safe's contract, which doesn't exist until it's activated.
            if (!current.deployed) throw SafeException(Strings.get(R.string.safe_error_activate_to_sign))
            s.pending.firstOrNull { it.id == id }?.let { return@update s to it }
            if (s.pendingFor(safe.address).count { it.kind == SafePending.Kind.MESSAGE } >= MAX_MESSAGES) {
                throw SafeException(Strings.get(R.string.safe_error_too_many_messages))
            }
            val entry = SafePending(id, current.address, SafePending.Kind.MESSAGE, current.chainId, typedData.toString(), current.thresholdNow, emptyList(), clock(), text = text)
            s.copy(pending = s.pending + entry) to entry
        }
    }

    /**
     * Adds [signature] to pending [id]: it must recover to an owner of the
     * Safe ([SafeAccount.ownersNow]: the chain's last word) who hasn't signed yet. Throws [SafeException] saying what's
     * wrong otherwise. Returns the updated entry.
     *
     * Once the entry is [SafePending.ready] its signatures are frozen and a
     * further one is dropped (the entry comes back unchanged): Execute sends
     * exactly these signatures, and [noteSend] recognises the mined
     * execution by that calldata, so an extra one added while it's out would
     * leave the executed transaction on the board.
     */
    suspend fun addSignature(id: String, signature: String): SafePending = update { s ->
        val entry = s.pending.firstOrNull { it.id == id } ?: throw SafeException(Strings.get(R.string.safe_error_discarded))
        val safe = s.safe(entry.safe) ?: throw SafeException(Strings.get(R.string.safe_error_gone))
        val sig = SafeProtocol.normalized(signature) ?: throw SafeException(Strings.get(R.string.safe_error_not_a_signature))
        val hash = SafeProtocol.hash(JSONObject(entry.typedData))
        check("0x" + hash.toHex() == entry.id)
        val signer = SafeProtocol.recoverSigner(hash, sig) ?: throw SafeException(Strings.get(R.string.safe_error_invalid_signature))
        if (!safe.isOwner(signer)) {
            throw SafeException(Strings.get(R.string.safe_error_not_an_owner, signer))
        }
        if (entry.hasSigned(signer)) return@update s to entry
        if (entry.superseded) throw SafeException(Strings.get(R.string.safe_error_superseded))
        if (entry.ready) return@update s to entry
        val next = entry.copy(signatures = entry.signatures + SafeProtocol.OwnerSignature(signer, sig))
        s.copy(pending = s.pending.map { if (it.id == id) next else it }) to next
    }

    /**
     * Signs pending [id] with [account] (one of the Safe's owners in this
     * wallet), on this phone, and adds the signature — unless it's already
     * [SafePending.ready], when it's left as it is (see [addSignature]). Throws
     * [VaultLockedException] if the wallet isn't open.
     */
    suspend fun signWith(id: String, account: WalletAccount): SafePending {
        val entry = _state.value?.pending?.firstOrNull { it.id == id } ?: throw SafeException(Strings.get(R.string.safe_error_discarded))
        if (entry.ready) return entry
        val signature = ownerSignature(account, entry.typedData)
        return addSignature(id, signature)
    }

    /**
     * [account]'s signature over the Safe typed data [typedData] (a SafeTx or
     * SafeMessage): from the vault's seed, or on its Ledger (#142), which
     * shows the typed data and is confirmed there. Throws [VaultLockedException]
     * for a seed account if the wallet isn't open.
     */
    suspend fun ownerSignature(account: WalletAccount, typedData: String): String {
        val data = withContext(Dispatchers.Default) { Eip712.parseStrict(typedData) }
        val digest = withContext(Dispatchers.Default) { Eip712.digest(data) }
        return if (account.isLedger) ledgerSign(account, data, digest)
        else withContext(Dispatchers.Default) { MessageSigning.sign(vault, account, digest) }
    }

    /** Takes pending [id] off the board (its signatures are thrown away). */
    suspend fun discard(id: String) = update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) to Unit }

    /** The Safe's nonce is past pending [id]'s: it can never execute now. */
    suspend fun markSuperseded(id: String) = update { s ->
        s.copy(pending = s.pending.map { if (it.id == id) it.copy(superseded = true, execHash = null, execFrom = null, execNonce = null) else it }) to Unit
    }

    /**
     * The `execTransaction` [hash] for pending [id] is no longer being followed (a later one's
     * hash is kept). [abandoned]: it wasn't settled, only given up on (Stop tracking), so it
     * may still be mined — added to [SafePending.abandonedExecs] for Discard to warn about and
     * the nonce guard to check. [from] / [nonce]: who sent it with which account nonce, when
     * known better than the entry's own record of it.
     */
    suspend fun clearExecution(id: String, hash: String, abandoned: Boolean = false, from: String? = null, nonce: BigInteger? = null) = update { s ->
        s.copy(
            pending = s.pending.map {
                if (it.id == id && it.execHash == hash) {
                    val kept = if (abandoned) {
                        val a = SafePending.AbandonedExec(hash, from ?: it.execFrom, nonce ?: it.execNonce)
                        (it.abandonedExecs.filterNot { e -> e.hash == hash } + a).takeLast(SafePending.MAX_ABANDONED)
                    } else {
                        it.abandonedExecs
                    }
                    it.copy(execHash = null, execFrom = null, execNonce = null, abandonedExecs = kept)
                } else {
                    it
                }
            },
        ) to Unit
    }

    /** [hash] is the `execTransaction` now going out for pending [id] (null: none is), sent by [from] with account nonce [nonce]. */
    suspend fun noteExecution(id: String, hash: String?, from: String? = null, nonce: BigInteger? = null) = update { s ->
        s.copy(
            pending = s.pending.map {
                if (it.id == id) it.copy(execHash = hash, execFrom = from.takeIf { hash != null }, execNonce = nonce.takeIf { hash != null }) else it
            },
        ) to Unit
    }

    /**
     * Follows one of the wallet's sends: a Safe's activation that got
     * mined marks it active; its `execTransaction` going out is noted on
     * the pending transaction, and once mined, clears it — it's done, and
     * the history keeps the record. A reverted one leaves it to try again
     * (the Safe's nonce only moves when an execution succeeds).
     */
    internal suspend fun noteSend(status: SendStatus) {
        val label = status.quote.request.dapp?.safe ?: return
        val state = _state.value ?: return
        val safe = state.safe(label.address) ?: return
        if (label.activates) {
            if (status.stage is SendStatus.Stage.Confirmed && !safe.deployed) markDeployed(safe.address)
            return
        }
        val entry = state.pendingFor(safe.address).firstOrNull { it.kind == SafePending.Kind.TX } ?: return
        val hash = status.hash ?: return
        // Only the execution of this very transaction: its data names the SafeTx and its signatures.
        val expected = runCatching { SafeProtocol.execTransactionData(entry.safeTx(), entry.signatures) }.getOrNull() ?: return
        if (!status.quote.tx.data.contentEquals(expected)) return
        when (status.stage) {
            is SendStatus.Stage.Confirmed -> discard(entry.id)
            is SendStatus.Stage.Reverted -> clearExecution(entry.id, hash)
            is SendStatus.Stage.Failed -> if (!status.mayHaveGone) clearExecution(entry.id, hash)
            else -> if (entry.execHash != hash) noteExecution(entry.id, hash, status.quote.request.from.address, status.quote.tx.nonce)
        }
    }

    /**
     * The sender stopped following [status] (Stop tracking, or a new send
     * took its place) without seeing it mined: if it was the pending
     * transaction's execution, none is going out any more, so the entry
     * can be discarded or executed again. Should the abandoned one land
     * after all, the Safe's nonce guard finds the entry can't execute.
     */
    internal suspend fun noteDropped(status: SendStatus) {
        if (status.stage is SendStatus.Stage.Confirmed) return
        val label = status.quote.request.dapp?.safe ?: return
        if (label.activates) return
        val hash = status.hash ?: return
        val state = _state.value ?: return
        val entry = state.pendingFor(label.address).firstOrNull { it.kind == SafePending.Kind.TX && it.execHash == hash } ?: return
        clearExecution(entry.id, hash, abandoned = true, from = status.quote.request.from.address, nonce = status.quote.tx.nonce)
    }

    companion object {
        private const val TAG = "SafeAccounts"
        const val MAX_SAFES = 20
        const val MAX_NAME = 64
        const val MAX_MESSAGES = 10
        const val MAX_MESSAGE = 4_000

        @Volatile
        private var instance: SafeAccounts? = null

        fun get(context: Context): SafeAccounts = instance ?: synchronized(this) {
            instance ?: create(context).also {
                instance = it
                it.start(WalletSender.get(context).changes)
            }
        }

        /** From the application context only: this lives as long as the process. */
        @VisibleForTesting
        internal fun create(context: Context): SafeAccounts {
            val app = context.applicationContext
            return SafeAccounts(
                vault = Vault.get(app),
                store = SafeStore.get(app),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ledgerSign = { account, data, digest -> Ledger.get(app).signTypedData(account, data, digest) },
            )
        }
    }
}

/** Which owner lists and thresholds a Safe can have. */
object SafePolicy {
    /** At least one owner, none twice, and a threshold between 1 and their number. */
    fun valid(owners: List<String>, threshold: Int): Boolean =
        owners.isNotEmpty() && owners.map { it.lowercase() }.toSet().size == owners.size && threshold in 1..owners.size
}

/**
 * Where a Safe stands on its chain, read through the chain-data router
 * (#108) like every wallet read: deployed or not, its nonce and owners,
 * and what activating it would cost the owner account that pays.
 */
class SafeChain(private val rpc: WalletRpc) {
    /** Activation's price for [executor]: "needs funds" is a state to show, not an error (desktop's `getSafeStatus`). */
    data class Activation(val executor: String, val maxFee: BigInteger, val balance: BigInteger) {
        val needsFunds: Boolean get() = balance < maxFee

        /** How much more [executor] needs, when it [needsFunds]. */
        val shortfall: BigInteger get() = (maxFee - balance).max(BigInteger.ZERO)
    }

    suspend fun deployed(chainId: Long, address: String): Boolean = rpc.code(chainId, address).value.length > 2

    suspend fun nonce(chainId: Long, safe: String): BigInteger =
        SafeProtocol.decodeUint(call(chainId, safe, SafeProtocol.NONCE_CALL)) ?: throw SafeException(Strings.get(R.string.safe_error_no_nonce))

    suspend fun owners(chainId: Long, safe: String): List<String> =
        SafeProtocol.decodeAddresses(call(chainId, safe, SafeProtocol.OWNERS_CALL)) ?: throw SafeException(Strings.get(R.string.safe_error_no_owner_list))

    suspend fun balance(chainId: Long, address: String): BigInteger = rpc.balance(chainId, address).value

    /**
     * [safe]'s state at one block: its nonce, owners and modules (null when
     * the list is longer than one page or unreadable in shape), and with
     * [withBalance] its native balance. What a self-call is checked against
     * has to come from one block: the router sends each read to whichever
     * node it picks, so separate "latest" reads can pair a stale owner list
     * with a current nonce (#255 R2-F1). Every read here names the block
     * [eth_blockNumber] gave; a node that doesn't have it fails the read,
     * and the caller shows no check at all.
     */
    suspend fun snapshot(chainId: Long, safe: String, withBalance: Boolean = false): Snapshot {
        val block = rpc.blockNumber(chainId).value
        val tag = "0x" + block.toString(16)
        return Snapshot(
            block = block,
            nonce = SafeProtocol.decodeUint(call(chainId, safe, SafeProtocol.NONCE_CALL, tag)) ?: throw SafeException(Strings.get(R.string.safe_error_no_nonce)),
            owners = SafeProtocol.decodeAddresses(call(chainId, safe, SafeProtocol.OWNERS_CALL, tag)) ?: throw SafeException(Strings.get(R.string.safe_error_no_owner_list)),
            modules = SafeProtocol.decodeModules(call(chainId, safe, SafeProtocol.MODULES_CALL, tag)),
            balance = if (withBalance) rpc.balance(chainId, safe, tag).value else null,
        )
    }

    /** A Safe as it stood at [block] ([snapshot]). */
    data class Snapshot(val block: Long, val nonce: BigInteger, val owners: List<String>, val modules: List<String>?, val balance: BigInteger?)

    /**
     * An active Safe's owners, threshold and nonce as its contract reports
     * them at one block (#341), like [snapshot]: a threshold from one node
     * and owners from another a block behind could disagree. [confirmed]
     * when every read was verified, or came undisputed from an RPC the user
     * added ([ChainTrust.undisputed]) — what Execute and a message's
     * signature are judged against, and the only reading the record is
     * refreshed from. Throws when a read fails or isn't a Safe's answer.
     */
    suspend fun policy(chainId: Long, safe: String): Policy {
        val block = rpc.blockNumber(chainId)
        val tag = "0x" + block.value.toString(16)
        val nonce = rpc.call(chainId, JSONObject().put("to", safe).put("data", SafeProtocol.NONCE_CALL), tag)
        val owners = rpc.call(chainId, JSONObject().put("to", safe).put("data", SafeProtocol.OWNERS_CALL), tag)
        val threshold = rpc.call(chainId, JSONObject().put("to", safe).put("data", SafeProtocol.THRESHOLD_CALL), tag)
        val ownerList = SafeProtocol.decodeAddresses(owners.value) ?: throw SafeException(Strings.get(R.string.safe_error_no_owner_list))
        val needed = SafeProtocol.decodeUint(threshold.value)?.takeIf { it.bitLength() < 31 }?.toInt()
            ?.takeIf { SafePolicy.valid(ownerList, it) } ?: throw SafeException(Strings.get(R.string.safe_error_no_owner_list))
        return Policy(
            block = block.value,
            nonce = SafeProtocol.decodeUint(nonce.value) ?: throw SafeException(Strings.get(R.string.safe_error_no_nonce)),
            owners = ownerList,
            threshold = needed,
            confirmed = listOf(nonce.trust, owners.trust, threshold.trust).all { it.undisputed },
        )
    }

    /** A Safe's owners, threshold and nonce at [block] ([policy]). */
    data class Policy(val block: Long, val nonce: BigInteger, val owners: List<String>, val threshold: Int, val confirmed: Boolean)

    /**
     * Whether [guard] passes a v1.4.1 Safe's `setGuard` check (GS300):
     * it has code and answers `supportsInterface(Guard)` with true. A call
     * the node says reverts is a no, as it is for the Safe; any other
     * failure throws, and means "not known". The guard's author controls
     * both reads (the code can be deployed later, and the answer can depend
     * on the caller or change), so a false is never proof the Safe would
     * refuse it when the transaction actually executes.
     */
    suspend fun guardSupported(chainId: Long, guard: String): Boolean {
        if (rpc.code(chainId, guard).value.length <= 2) return false
        val answer = try {
            call(chainId, guard, SafeProtocol.SUPPORTS_GUARD_CALL)
        } catch (e: ChainRpcException.Rpc) {
            if (e.data != null || e.code == ChainRpcException.EXECUTION_REVERTED) return false
            throw e
        }
        return SafeProtocol.decodeBool(answer) ?: false
    }

    /** How many of [address]'s transactions are mined: an account nonce below it can't be mined any more. */
    suspend fun minedCount(chainId: Long, address: String): BigInteger = rpc.transactionCount(chainId, address, "latest").value

    suspend fun tokenBalance(chainId: Long, token: String, holder: String): BigInteger =
        Erc20.decodeUint256(call(chainId, token, Erc20.balanceOfData(holder))) ?: throw SafeException(Strings.get(R.string.safe_error_no_token_balance))

    /**
     * What activating [safe] costs at most, paid by [executor], against
     * what [executor] holds: its gas at the fee cap plus, on an OP Stack
     * rollup, the L1 data fee [WalletSender.prepare] reserves on top
     * ([WalletSender.l1Fee]) — or the card calls ready an executor that
     * Activate then refuses for the network fee.
     */
    suspend fun activation(safe: SafeAccount, executor: String): Activation {
        val data = SafeProtocol.deploymentData(safe.owners, safe.threshold, safe.saltNonce)
        val call = JSONObject().put("from", executor).put("to", SafeProtocol.FACTORY).put("value", "0x0").put("data", "0x" + data.toHex())
        val estimate = rpc.estimateGas(safe.chainId, call).value
        val fees = GasOracle(rpc).fees(safe.chainId)
        val gasLimit = WalletSender.gasLimit(estimate, hasData = true)
        val l1Fee = if (safe.chainId !in WalletSender.OP_STACK_CHAINS) BigInteger.ZERO else {
            // The transaction prepare() will price: only its nonce's width can differ, well inside the fee's doubling.
            val nonce = rpc.transactionCount(safe.chainId, executor, "pending").value
            WalletSender.l1Fee(rpc, EthTransaction(safe.chainId, nonce, gasLimit, SafeProtocol.FACTORY, BigInteger.ZERO, data, fees))
        }
        val maxFee = gasLimit * fees.maxPerGas + l1Fee
        return Activation(executor, maxFee, rpc.balance(safe.chainId, executor).value)
    }

    /** The receipt's status for [hash]: true mined and succeeded, false mined and reverted, null not (yet) mined. */
    suspend fun succeeded(chainId: Long, hash: String): Boolean? {
        val receipt = rpc.receipt(chainId, hash).value ?: return null
        return when (receipt.optString("status")) {
            "0x1" -> true
            "0x0" -> false
            else -> null
        }
    }

    private suspend fun call(chainId: Long, to: String, data: String, block: String = "latest"): String =
        rpc.call(chainId, JSONObject().put("to", to).put("data", data), block).value

    companion object {
        fun get(context: Context) = SafeChain(WalletRpc(ChainDataRouter.get(context.applicationContext)))

        /**
         * The account that signs and pays for a Safe's activation and
         * executions: the first owner that's one of this wallet's
         * accounts, as desktop's `pickDefaultExecutor`. Null if none is.
         */
        fun executor(safe: SafeAccount, accounts: List<WalletAccount>): WalletAccount? =
            safe.ownersNow.firstNotNullOfOrNull { o -> accounts.firstOrNull { it.address.equals(o, ignoreCase = true) } }
    }
}
