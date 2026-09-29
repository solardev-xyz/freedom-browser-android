package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.toHex
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
) {
    fun isOwner(address: String) = owners.any { it.equals(address, ignoreCase = true) }
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

    /** An abandoned execution: its hash, and the account and account nonce that sent it (null when not known). */
    data class AbandonedExec(val hash: String, val from: String? = null, val nonce: BigInteger? = null)

    /** [amount] base units of [symbol] ([decimals]) to [recipient]; [token] is null for the native currency. */
    data class Payment(val recipient: String, val amount: BigInteger, val symbol: String, val decimals: Int, val token: String?)

    val collected: Int get() = signatures.size
    val ready: Boolean get() = signatures.size >= threshold

    fun hasSigned(address: String) = signatures.any { it.signer.equals(address, ignoreCase = true) }

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
            ).also {
                // Frozen params that no longer give the stored address mean the record was changed: never trust it.
                require(SafeProtocol.predictAddress(it.owners, it.threshold, it.saltNonce) == it.address)
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
                                .put("deployed", s.deployed).put("createdAt", s.createdAt),
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
        val tag = withContext(io) { vault.identityTag() } ?: throw SafeException("There’s no wallet on this phone.")
        // What's in memory is only this wallet's if it was read under this wallet's tag.
        val current = _state.value?.takeIf { loadedTag == tag } ?: withContext(io) { store.read(tag) }
            ?: throw SafeException("The Safe accounts on this phone can’t be read.")
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
        if (!SafeProtocol.validPreset(owners.size, threshold)) throw SafeException("A Safe needs 1 of 2 or 2 of 3 owners.")
        val checked = owners.map { SafeProtocol.eip55(it) }
        if (checked.map { it.lowercase() }.toSet().size != checked.size) throw SafeException("The same account is listed as an owner twice.")
        if (checked.none { o -> local.any { it.equals(o, ignoreCase = true) } }) {
            throw SafeException("At least one owner must be an account in this wallet, to sign and pay for the activation.")
        }
        val salt = SafeProtocol.newSaltNonce()
        val address = SafeProtocol.predictAddress(checked, threshold, salt)
        return update { s ->
            if (s.safes.size >= MAX_SAFES) throw SafeException("This wallet has as many Safe accounts as it can hold.")
            if (s.safes.any { o -> checked.any { it.equals(o.address, ignoreCase = true) } }) throw SafeException("A Safe can’t own another Safe.")
            val safe = SafeAccount(
                address = address,
                name = name.trim().take(MAX_NAME).ifBlank { "Safe ${s.safes.size + 1}" },
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

    /** Records that [address] is deployed on its chain — the only thing about a Safe that ever changes. */
    suspend fun markDeployed(address: String) = update { s ->
        s.copy(safes = s.safes.map { if (it.address.equals(address, ignoreCase = true)) it.copy(deployed = true) else it }) to Unit
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
            val current = s.safe(safe.address) ?: throw SafeException("This Safe is no longer on this phone.")
            if (!current.deployed) throw SafeException("Activate this Safe before sending from it.")
            if (s.pendingFor(safe.address).any { it.kind == SafePending.Kind.TX }) {
                throw SafeException("A transaction is already waiting for signatures. Execute or discard it first.")
            }
            val entry = SafePending(id, current.address, SafePending.Kind.TX, current.chainId, typedData.toString(), current.threshold, emptyList(), clock(), payment = payment)
            s.copy(pending = s.pending + entry) to entry
        }
    }

    /** A new SafeMessage for [text] from [safe], waiting for signatures (the same words again give the same one). */
    suspend fun proposeMessage(safe: SafeAccount, text: String): SafePending {
        if (text.isEmpty()) throw SafeException("Write the message to sign.")
        if (text.length > MAX_MESSAGE) throw SafeException("The message is too long.")
        val typedData = SafeProtocol.messageTypedData(safe.address, safe.chainId, text)
        val id = "0x" + SafeProtocol.hash(typedData).toHex()
        return update { s ->
            val current = s.safe(safe.address) ?: throw SafeException("This Safe is no longer on this phone.")
            // Its EIP-1271 signature is checked by the Safe's contract, which doesn't exist until it's activated.
            if (!current.deployed) throw SafeException("Activate this Safe before signing messages with it.")
            s.pending.firstOrNull { it.id == id }?.let { return@update s to it }
            if (s.pendingFor(safe.address).count { it.kind == SafePending.Kind.MESSAGE } >= MAX_MESSAGES) {
                throw SafeException("This Safe has as many messages waiting as it can hold. Discard one first.")
            }
            val entry = SafePending(id, current.address, SafePending.Kind.MESSAGE, current.chainId, typedData.toString(), current.threshold, emptyList(), clock(), text = text)
            s.copy(pending = s.pending + entry) to entry
        }
    }

    /**
     * Adds [signature] to pending [id]: it must recover to an owner of the
     * Safe who hasn't signed yet. Throws [SafeException] saying what's
     * wrong otherwise. Returns the updated entry.
     *
     * Once the entry is [SafePending.ready] its signatures are frozen and a
     * further one is dropped (the entry comes back unchanged): Execute sends
     * exactly these signatures, and [noteSend] recognises the mined
     * execution by that calldata, so an extra one added while it's out would
     * leave the executed transaction on the board.
     */
    suspend fun addSignature(id: String, signature: String): SafePending = update { s ->
        val entry = s.pending.firstOrNull { it.id == id } ?: throw SafeException("This request was discarded.")
        val safe = s.safe(entry.safe) ?: throw SafeException("This Safe is no longer on this phone.")
        val sig = SafeProtocol.normalized(signature) ?: throw SafeException("That isn’t a signature: it should be 0x followed by 130 hex digits.")
        val hash = SafeProtocol.hash(JSONObject(entry.typedData))
        check("0x" + hash.toHex() == entry.id)
        val signer = SafeProtocol.recoverSigner(hash, sig) ?: throw SafeException("That signature isn’t valid.")
        if (!safe.isOwner(signer)) {
            throw SafeException("That signature is from $signer, which isn’t an owner of this Safe — or it was made for a different request.")
        }
        if (entry.hasSigned(signer)) return@update s to entry
        if (entry.superseded) throw SafeException("This transaction can no longer be executed. Discard it.")
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
        val entry = _state.value?.pending?.firstOrNull { it.id == id } ?: throw SafeException("This request was discarded.")
        if (entry.ready) return entry
        val hash = withContext(Dispatchers.Default) { SafeProtocol.hash(JSONObject(entry.typedData)) }
        val signature = withContext(Dispatchers.Default) { MessageSigning.sign(vault, account, hash) }
        return addSignature(id, signature)
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
            )
        }
    }
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
        SafeProtocol.decodeUint(call(chainId, safe, SafeProtocol.NONCE_CALL)) ?: throw SafeException("The Safe gave no nonce.")

    suspend fun owners(chainId: Long, safe: String): List<String> =
        SafeProtocol.decodeAddresses(call(chainId, safe, SafeProtocol.OWNERS_CALL)) ?: throw SafeException("The Safe gave no owner list.")

    suspend fun balance(chainId: Long, address: String): BigInteger = rpc.balance(chainId, address).value

    /** How many of [address]'s transactions are mined: an account nonce below it can't be mined any more. */
    suspend fun minedCount(chainId: Long, address: String): BigInteger = rpc.transactionCount(chainId, address, "latest").value

    suspend fun tokenBalance(chainId: Long, token: String, holder: String): BigInteger =
        Erc20.decodeUint256(call(chainId, token, Erc20.balanceOfData(holder))) ?: throw SafeException("The token gave no balance.")

    /** What activating [safe] costs at most, paid by [executor], against what [executor] holds. */
    suspend fun activation(safe: SafeAccount, executor: String): Activation {
        val data = SafeProtocol.deploymentData(safe.owners, safe.threshold, safe.saltNonce)
        val call = JSONObject().put("from", executor).put("to", SafeProtocol.FACTORY).put("value", "0x0").put("data", "0x" + data.toHex())
        val estimate = rpc.estimateGas(safe.chainId, call).value
        val fees = GasOracle(rpc).fees(safe.chainId)
        val maxFee = WalletSender.gasLimit(estimate, hasData = true) * fees.maxPerGas
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

    private suspend fun call(chainId: Long, to: String, data: String): String =
        rpc.call(chainId, JSONObject().put("to", to).put("data", data)).value

    companion object {
        fun get(context: Context) = SafeChain(WalletRpc(ChainDataRouter.get(context.applicationContext)))

        /**
         * The account that signs and pays for a Safe's activation and
         * executions: the first owner that's one of this wallet's
         * accounts, as desktop's `pickDefaultExecutor`. Null if none is.
         */
        fun executor(safe: SafeAccount, accounts: List<WalletAccount>): WalletAccount? =
            safe.owners.firstNotNullOfOrNull { o -> accounts.firstOrNull { it.address.equals(o, ignoreCase = true) } }
    }
}
