package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.ledger.LedgerException
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone as desktop Freedom's signer (#113): desktop shows a QR code
 * for each job — adding the phone's account, then every signature or
 * transaction — the user scans it on the phone ([start]), and the
 * browser's JSON-RPC requests arrive here over the OpenLV session
 * ([OpenLvEngine]). Each one that signs, or shares an account, waits for
 * the user on its own sheet ([approval]) and nothing is answered for
 * them; the rest are answered at once.
 *
 * After iOS's `OpenLVWalletSession`, and like it deliberately not a dApp
 * permission: the session is one the user started seconds ago by
 * scanning, lasts one job, and keeps no grant — the shared account and
 * the chain it switched to are forgotten with it. Desktop checks every
 * answer again anyway: a signature must recover to the account it
 * added, and a transaction hash's sender must be it.
 *
 * What's answered, and how:
 *  - `eth_chainId`, `eth_accounts`: at once (the session's chain,
 *    Ethereum until desktop switches it; the account shared in this
 *    session, if any);
 *  - `wallet_switchEthereumChain`: at once, to a chain set up on the
 *    phone (4902 otherwise). It only picks the chain the next
 *    transaction's sheet is priced on, and names, so it asks nothing
 *    itself; `wallet_addEthereumChain` is refused — chains are added in
 *    Settings → Chains;
 *  - `eth_requestAccounts`: a sheet to pick the account to share;
 *  - `personal_sign`, `eth_signTypedData_v4`: a sheet showing exactly
 *    what's signed, for an account of this wallet ([MessageSigning],
 *    [Eip712]);
 *  - `eth_sendTransaction`: priced by the wallet's own send flow
 *    ([WalletSender.prepare] — its nonce, fees and gas estimate, as
 *    desktop asks), reviewed on a sheet, then signed and broadcast by
 *    [WalletSender] like any send of the wallet's, which then follows it
 *    on the wallet page. The answer is the hash once a node took it.
 *
 * One sheet at a time; a request arriving while one is up is refused
 * (-32002). A new scan replaces the session, and a closed or failed one
 * takes its open sheet with it (answered as rejected): desktop has
 * stopped waiting for it.
 *
 * Keys come from [keys] for one signature and are zeroed after; nothing
 * of a request is logged beyond its method.
 */
class OpenLvSession internal constructor(
    private val engine: OpenLvEngine,
    private val keys: Keys,
    private val chains: suspend () -> List<Chain>,
    private val sender: WalletSender,
    private val scope: CoroutineScope,
) : OpenLvEngine.Listener {
    /** The wallet's side: its accounts (public) and, for one signature at a time, a key. */
    interface Keys {
        /** The wallet's accounts, or null when there's no wallet (or its list isn't known yet). */
        fun accounts(): WalletAccountList?

        /** Runs [block] with [account]'s private key, zeroed after. Throws [VaultLockedException] if the wallet is locked. */
        fun <T> withKey(account: WalletAccount, block: (ByteArray) -> T): T

        /**
         * `personal_sign` of [message] as [account]: with its key
         * ([withKey]), or on its Ledger (#142), which shows the message
         * and throws [LedgerException] for a rejection, a disconnect…
         */
        suspend fun signPersonal(account: WalletAccount, message: ByteArray): String =
            withContext(Dispatchers.Default) { withKey(account) { key -> MessageSigning.sign(key, account.address, MessageSigning.personalDigest(message)) } }

        /** `eth_signTypedData_v4` of [data], whose EIP-712 digest is [digest]: as [signPersonal]. */
        suspend fun signTypedData(account: WalletAccount, data: Eip712.TypedData, digest: ByteArray): String =
            withContext(Dispatchers.Default) { withKey(account) { key -> MessageSigning.sign(key, account.address, digest) } }

        /**
         * Signs [account]'s transactions: with its key ([withKey]), or on
         * its Ledger, which asks [fresh] once it's ready to show the
         * transaction ([WalletSender.signerFor]).
         */
        fun transactionSigner(account: WalletAccount, fresh: () -> Boolean): suspend (EthTransaction) -> EthTransaction.Signed =
            { t -> withKey(account) { key -> t.sign(key, account.address) } }

        /** Wallet activity: keeps an open wallet from idling out, as dApp use does on desktop. */
        fun noteActivity()
    }

    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status
        data object Connected : Status
        data object Disconnected : Status
        data class Failed(val message: String) : Status
    }

    /** What a sheet asks the user. */
    sealed interface Request {
        /** `eth_requestAccounts`: which account desktop may add. */
        data class Connect(val accounts: List<WalletAccount>, val suggested: WalletAccount) : Request

        /** `personal_sign`: [text] when [message] reads as text ([MessageSigning.readableText]), else show its hex. */
        class PersonalSign(val account: WalletAccount, val message: ByteArray, val text: String?) : Request

        /**
         * `eth_signTypedData_v4`. [chainId] is the chain its signature is
         * bound to (null: none), [chain] that chain if the phone knows it.
         */
        class TypedData(
            val account: WalletAccount,
            val primaryType: String,
            val domain: List<Eip712.Line>,
            val message: List<Eip712.Line>,
            val chainId: BigInteger?,
            val chain: Chain?,
        ) : Request

        /** `eth_sendTransaction`, priced; [notice] says why it's shown again, if it is. */
        data class SendTransaction(val quote: SendQuote, val notice: String? = null) : Request
    }

    sealed interface Decision {
        data object Reject : Decision

        /** [account] is the one picked on a [Request.Connect] sheet; ignored otherwise. */
        data class Approve(val account: WalletAccount? = null) : Decision
    }

    /** One open sheet. [decide] answers it (once; a later answer is ignored). */
    class Approval internal constructor(val request: Request, internal val answer: CompletableDeferred<Decision>) {
        fun decide(decision: Decision) {
            answer.complete(decision)
        }
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _approval = MutableStateFlow<Approval?>(null)

    /** The sheet to show, if any. */
    val approval: StateFlow<Approval?> = _approval.asStateFlow()

    // Only touched on [scope]'s thread (the main thread in the app).
    private var sid = 0
    private var chainId = TokenRegistry.ETHEREUM
    private var sharedAccount: WalletAccount? = null
    private var sheetSid = -1
    private var currentUri: String? = null

    init {
        engine.listener = this
    }

    val active: Boolean get() = status.value == Status.Connecting || status.value == Status.Connected

    /**
     * Joins the session in [uri] (an `openlv://` URI, [ScannedCode.Pairing]).
     * Any session before it is left, and its open sheet answered as
     * rejected: desktop makes a new code for every job, so the old one
     * is over the moment a new one is scanned. The code of the session
     * still going is ignored — the camera sees it again and again — unless
     * [again]. Main thread.
     */
    fun start(uri: String, again: Boolean = false) {
        if (!again && uri == currentUri && active) return
        currentUri = uri
        endSession()
        _status.value = Status.Connecting
        engine.start(sid, uri)
        Log.i(TAG, "joining a session")
    }

    /** Leaves the session (Disconnect). Main thread. */
    fun stop() {
        endSession()
        engine.stop()
        _status.value = Status.Idle
    }

    private fun endSession() {
        sid++
        sharedAccount = null
        chainId = TokenRegistry.ETHEREUM
        _approval.value?.decide(Decision.Reject)
        _approval.value = null
    }

    /**
     * The Ledger account [address] was taken off the wallet (#220 R1-M2):
     * if desktop was given it, it isn't any more — `eth_accounts` answers
     * none, and adding the account again later doesn't hand it back
     * without a new Connect sheet, as for a site
     * ([baby.freedom.mobile.browser.EthereumProvider.accountRemoved]).
     * Main thread.
     */
    fun accountRemoved(address: String) {
        if (sharedAccount?.address.equals(address, ignoreCase = true)) {
            sharedAccount = null
            Log.i(TAG, "shared account removed from the wallet")
        }
    }

    /**
     * Drops [sharedAccount] if the wallet's list no longer has it — a
     * backstop for [accountRemoved]. A list not read yet (null) says nothing.
     */
    private fun forgetRemovedAccount() {
        val shared = sharedAccount ?: return
        val list = keys.accounts() ?: return
        if (list.accounts.none { it.address.equals(shared.address, ignoreCase = true) }) accountRemoved(shared.address)
    }

    override fun onLink(sid: Int, link: OpenLvLink) {
        scope.launch {
            if (sid != this@OpenLvSession.sid) return@launch
            _status.value = when (link) {
                OpenLvLink.Connecting -> Status.Connecting
                OpenLvLink.Connected -> Status.Connected
                OpenLvLink.Disconnected -> Status.Disconnected
                is OpenLvLink.Failed -> Status.Failed(link.message)
            }
            if (link is OpenLvLink.Disconnected || link is OpenLvLink.Failed) {
                Log.i(TAG, "session ended${if (link is OpenLvLink.Failed) " (failed)" else ""}")
                endSession()
            }
        }
    }

    override fun onRequest(sid: Int, id: Int, method: String, params: JSONArray) {
        scope.launch {
            if (sid != this@OpenLvSession.sid) return@launch
            Log.i(TAG, "request $method")
            val response = try {
                handle(sid, method, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$method failed: ${e.javaClass.simpleName}")
                OpenLvResponse.Error(INTERNAL, "The phone couldn’t answer this request.")
            }
            engine.respond(sid, id, response)
        }
    }

    /** One request's answer. Internal so tests drive it without an engine. */
    internal suspend fun handle(sid: Int, method: String, params: JSONArray): OpenLvResponse {
        keys.noteActivity()
        forgetRemovedAccount()
        return when (method) {
            "eth_chainId" -> OpenLvResponse.Result("0x" + chainId.toString(16))
            "eth_accounts" -> OpenLvResponse.Result(JSONArray().apply { sharedAccount?.let { put(it.address) } })
            "eth_requestAccounts" -> oneSheet(sid) { requestAccounts(sid) }
            "personal_sign" -> oneSheet(sid) { personalSign(sid, params) }
            "eth_signTypedData_v4" -> oneSheet(sid) { signTypedData(sid, params) }
            "eth_sendTransaction" -> oneSheet(sid) { sendTransaction(sid, params) }
            "wallet_switchEthereumChain" -> switchChain(params)
            "wallet_addEthereumChain" -> OpenLvResponse.Error(
                UNSUPPORTED,
                "The phone can’t add chains this way. Add the chain in Freedom’s Settings → Chains on the phone, then try again.",
            )
            else -> OpenLvResponse.Error(UNSUPPORTED, "The phone doesn’t support $method.")
        }
    }

    /**
     * One request that may need a sheet at a time per session — from its
     * arrival (pricing a transaction comes first) to its answer. A
     * replaced session's leftover never holds up the new one's: it can't
     * show a sheet any more ([ask]).
     */
    private suspend fun oneSheet(sid: Int, block: suspend () -> OpenLvResponse): OpenLvResponse {
        if (sheetSid == sid) return OpenLvResponse.Error(BUSY, "Another request is waiting on the phone.")
        sheetSid = sid
        return try {
            block()
        } finally {
            if (sheetSid == sid) sheetSid = -1
        }
    }

    /** Shows [request] and waits for the user; rejected if the session is over, or ends first. */
    private suspend fun ask(sid: Int, request: Request): Decision {
        if (sid != this.sid) return Decision.Reject
        val approval = Approval(request, CompletableDeferred())
        _approval.value = approval
        return try {
            approval.answer.await()
        } finally {
            if (_approval.value === approval) _approval.value = null
        }
    }

    private suspend fun requestAccounts(sid: Int): OpenLvResponse {
        sharedAccount?.let { return OpenLvResponse.Result(JSONArray().put(it.address)) }
        val list = keys.accounts() ?: return NO_WALLET
        return when (val d = ask(sid, Request.Connect(list.accounts, list.active))) {
            Decision.Reject -> REJECTED
            is Decision.Approve -> {
                val account = d.account?.takeIf { a -> list.accounts.any { it.address == a.address } } ?: list.active
                if (sid == this.sid) sharedAccount = account
                OpenLvResponse.Result(JSONArray().put(account.address))
            }
        }
    }

    private suspend fun personalSign(sid: Int, params: JSONArray): OpenLvResponse {
        val raw = params.opt(0) as? String ?: return invalid("Expected [message, address].")
        val account = accountFor(params.opt(1)) ?: return notThisWallet(params.opt(1))
        val message = if (HEX.matches(raw)) raw.hexToBytes() else raw.toByteArray(Charsets.UTF_8)
        if (message.size > MAX_MESSAGE) return invalid("The message is too long.")
        return when (ask(sid, Request.PersonalSign(account, message, MessageSigning.readableText(message)))) {
            Decision.Reject -> REJECTED
            is Decision.Approve -> signed { keys.signPersonal(account, message) }
        }
    }

    private suspend fun signTypedData(sid: Int, params: JSONArray): OpenLvResponse {
        val account = accountFor(params.opt(0)) ?: return notThisWallet(params.opt(0))
        // Off the main thread: the payload is the peer's, up to Eip712.MAX_JSON of it.
        val (typed, digest, lines) = try {
            withContext(Dispatchers.Default) {
                val td = Eip712.parseStrict(params.opt(1))
                Triple(td, Eip712.digest(td), Eip712.lines(td))
            }
        } catch (e: Eip712.Invalid) {
            return invalid(e.message ?: "The typed data can’t be read.")
        }
        val domainChain = typed.chainId
        val chain = domainChain?.takeIf { it.bitLength() < 63 }?.toLong()?.let { id -> chains().firstOrNull { it.id == id } }
        val request = Request.TypedData(account, typed.primaryType, lines.first, lines.second, domainChain, chain)
        return when (ask(sid, request)) {
            Decision.Reject -> REJECTED
            is Decision.Approve -> signed { keys.signTypedData(account, typed, digest) }
        }
    }

    private suspend fun sendTransaction(sid: Int, params: JSONArray): OpenLvResponse {
        val tx = params.opt(0) as? JSONObject ?: return invalid("Expected [transaction].")
        val account = accountFor(tx.opt("from")) ?: return notThisWallet(tx.opt("from"))
        val asked = if (tx.has("chainId")) quantity(tx.opt("chainId"))?.takeIf { it.bitLength() < 63 }?.toLong() ?: return invalid("Not a chain ID.") else chainId
        if (asked != chainId) {
            return invalid("The transaction is for chain $asked, but this session is on chain $chainId. Switch first.")
        }
        val chain = chains().firstOrNull { it.id == asked } ?: return OpenLvResponse.Error(UNKNOWN_CHAIN, "Chain $asked isn’t set up on the phone.")
        val to = (tx.opt("to") as? String)?.takeIf { EthTransaction.ADDRESS.matches(it) }
            ?: return invalid(if (tx.has("to")) "Not an address to send to." else "Deploying a contract isn’t supported.")
        val value = if (tx.has("value")) quantity(tx.opt("value"))?.takeIf { it.signum() >= 0 && it.bitLength() <= 256 } ?: return invalid("Not a value.") else BigInteger.ZERO
        val data = when (val d = tx.opt("data") ?: tx.opt("input")) {
            null, JSONObject.NULL -> "0x"
            // Bounded before anything reads it: the sheet shows all of it, and more isn't a call anyone reviews.
            is String -> if (d.length > 2 + 2 * MAX_CALL_DATA) {
                return invalid("The call data is over ${MAX_CALL_DATA / 1024} KB: more than the phone will show you to approve.")
            } else {
                d.lowercase().takeIf { HEX.matches(it) } ?: return invalid("The data isn’t hex.")
            }
            else -> return invalid("The data isn’t hex.")
        }
        val request = SendRequest(
            chain = chain,
            token = TokenRegistry.native(chain),
            from = account,
            to = NodeIdentity.checksum(to.hexToBytes()),
            amount = value,
            dapp = DappCall(origin = null, data = data.hexToBytes(), gasLimit = null),
        )
        var notice: String? = null
        while (true) {
            val quote = try {
                sender.prepare(request)
            } catch (e: SendException) {
                return OpenLvResponse.Error(INTERNAL, e.message ?: "The phone couldn’t price this transaction.")
            }
            when (ask(sid, Request.SendTransaction(quote, notice))) {
                Decision.Reject -> return REJECTED
                is Decision.Approve -> Unit
            }
            val sign = keys.transactionSigner(account) { !sender.isStale(quote) }
            return when (val b = sender.submitAndAwaitBroadcast(quote, sign)) {
                is WalletSender.Broadcast.Sent -> OpenLvResponse.Result(b.hash)
                is WalletSender.Broadcast.Failed -> OpenLvResponse.Error(
                    INTERNAL,
                    if (b.mayHaveGone) "${b.message} It may still go out: see the wallet on the phone." else b.message,
                )
                WalletSender.Broadcast.Busy -> OpenLvResponse.Error(
                    BUSY,
                    "Another send from the phone’s wallet isn’t settled yet. Settle it in the wallet on the phone, then try again.",
                )
                WalletSender.Broadcast.Rejected -> REJECTED_ON_LEDGER
                is WalletSender.Broadcast.Stale -> {
                    notice = if (b.droppedSigned) {
                        // Approved on the Ledger, but its review there outlasted SIGNED_TTL_MS (#220 R1-M1).
                        "The Ledger approval came over three minutes after the fees were worked out, so it wasn’t " +
                            "sent and they’ve been priced again. Check them and confirm again."
                    } else {
                        "The fees were over a minute old, so they’ve been priced again. Check them and confirm again."
                    }
                    continue
                }
            }
        }
    }

    private suspend fun switchChain(params: JSONArray): OpenLvResponse {
        val id = (params.opt(0) as? JSONObject)?.opt("chainId")?.let(::quantity)?.takeIf { it.signum() > 0 && it.bitLength() < 63 }?.toLong()
            ?: return invalid("Expected [{chainId}].")
        if (chains().none { it.id == id }) {
            return OpenLvResponse.Error(UNKNOWN_CHAIN, "Chain $id isn’t set up on the phone. Add it in Freedom’s Settings → Chains there.")
        }
        chainId = id
        return OpenLvResponse.Result(JSONObject.NULL)
    }

    private suspend fun signed(sign: suspend () -> String): OpenLvResponse = try {
        OpenLvResponse.Result(sign())
    } catch (e: VaultLockedException) {
        OpenLvResponse.Error(UNAUTHORIZED, "The wallet on the phone locked before it signed. Try again.")
    } catch (e: LedgerException) {
        when (e.kind) {
            // Refused on the device, or the user cancelled waiting for it: a rejection, as for a site.
            LedgerException.Kind.REJECTED, LedgerException.Kind.CANCELLED -> REJECTED_ON_LEDGER
            else -> OpenLvResponse.Error(INTERNAL, "Ledger: ${e.message}")
        }
    }

    /** The wallet's account at [address] (any case), or null. */
    private fun accountFor(address: Any?): WalletAccount? {
        val a = (address as? String)?.trim() ?: return null
        return keys.accounts()?.accounts?.firstOrNull { it.address.equals(a, ignoreCase = true) }
    }

    private fun notThisWallet(address: Any?): OpenLvResponse =
        if (keys.accounts() == null) NO_WALLET
        else OpenLvResponse.Error(UNAUTHORIZED, "${address ?: "That account"} isn’t an account of the wallet on this phone.")

    private fun invalid(message: String) = OpenLvResponse.Error(INVALID_PARAMS, message)

    private fun quantity(v: Any?): BigInteger? = when (v) {
        is Int, is Long -> BigInteger.valueOf((v as Number).toLong())
        is String -> when {
            QUANTITY.matches(v) -> BigInteger(v.substring(2), 16)
            v.isNotEmpty() && v.length <= 78 && v.all { it in '0'..'9' } -> BigInteger(v)
            else -> null
        }
        else -> null
    }

    internal companion object {
        private const val TAG = "OpenLv"

        // EIP-1193 / JSON-RPC codes desktop maps (src/main/wallet/remote/errors.js).
        const val REJECTED_CODE = 4001
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED = 4200
        const val UNKNOWN_CHAIN = 4902
        const val INVALID_PARAMS = -32602
        const val INTERNAL = -32603
        const val BUSY = -32002

        val REJECTED = OpenLvResponse.Error(REJECTED_CODE, "Rejected on the phone.")
        val REJECTED_ON_LEDGER = OpenLvResponse.Error(REJECTED_CODE, "Rejected on the Ledger.")
        val NO_WALLET = OpenLvResponse.Error(UNAUTHORIZED, "There’s no wallet on the phone, or it hasn’t been opened yet.")

        /** A `personal_sign` message longer than this isn't one a sheet can show. */
        const val MAX_MESSAGE = 64 * 1024

        /**
         * `eth_sendTransaction` call data longer than this, in bytes, is
         * refused. Real contract calls are a few hundred bytes to a few KB;
         * a node won't relay a transaction over 128 KB anyway.
         */
        const val MAX_CALL_DATA = 64 * 1024

        private val HEX = Regex("^0x([0-9a-fA-F]{2})*$")
        private val QUANTITY = Regex("^0x[0-9a-fA-F]{1,64}$")

        @Volatile
        private var instance: OpenLvSession? = null

        fun get(context: Context): OpenLvSession = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /**
         * [accountRemoved] on the session, if one was ever made (no
         * session, nothing shared: none is made just for this). Main thread.
         */
        fun accountRemovedFromWallet(address: String) {
            instance?.accountRemoved(address)
        }

        private fun create(app: Context): OpenLvSession {
            val vault = Vault.get(app)
            val accounts = WalletAccounts.get(app)
            val chainStore = ChainStore.get(app)
            return OpenLvSession(
                engine = WebViewOpenLvEngine(app),
                keys = VaultKeys(vault, accounts, Ledger.get(app)),
                chains = { chainStore.chains.first() },
                sender = WalletSender.get(app),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}

/**
 * [OpenLvSession.Keys] from the device's wallet: the accounts
 * [WalletAccounts] lists, keys derived from the [Vault]'s seed — or, for
 * a Ledger's account (#142), signed on that Ledger.
 */
internal class VaultKeys(private val vault: Vault, private val accounts: WalletAccounts, private val ledger: Ledger) : OpenLvSession.Keys {
    override fun accounts(): WalletAccountList? = accounts.accounts.value

    override fun <T> withKey(account: WalletAccount, block: (ByteArray) -> T): T {
        val key = vault.withSeed { seed -> HdKeys.secp256k1(seed, account.path) }
        return try {
            block(key)
        } finally {
            key.fill(0)
        }
    }

    // A Ledger's account (#142) signs on its Ledger, never with a key from the seed.
    override suspend fun signPersonal(account: WalletAccount, message: ByteArray): String =
        if (account.isLedger) ledger.signPersonal(account, message) else super.signPersonal(account, message)

    override suspend fun signTypedData(account: WalletAccount, data: Eip712.TypedData, digest: ByteArray): String =
        if (account.isLedger) ledger.signTypedData(account, data, digest) else super.signTypedData(account, data, digest)

    override fun transactionSigner(account: WalletAccount, fresh: () -> Boolean): suspend (EthTransaction) -> EthTransaction.Signed =
        if (account.isLedger) { t -> ledger.signTransaction(account, t, fresh) } else super.transactionSigner(account, fresh)

    override fun noteActivity() = vault.noteActivity()
}
