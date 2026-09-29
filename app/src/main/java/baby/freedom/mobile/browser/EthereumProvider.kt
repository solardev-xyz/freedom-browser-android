package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.ChainInput
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.NodeIdentity
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.VaultLockedException
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What a site asks the user through `window.ethereum` (#110); every one names the site. */
sealed interface EthAsk {
    /** The provider origin key of the page asking, shown on every approval. */
    val origin: String

    /** `eth_requestAccounts`: share one of the wallet's accounts with the site, on [chain]. */
    data class Connect(override val origin: String, val chain: Chain) : EthAsk

    /** `personal_sign`: [text] when the message is readable text, else null and [hex] is shown. */
    data class SignMessage(
        override val origin: String,
        val account: WalletAccount,
        val text: String?,
        val hex: String,
    ) : EthAsk

    /** `eth_signTypedData_v4`: the domain's name and contract, the struct's type and its fields as JSON. */
    data class SignTypedData(
        override val origin: String,
        val account: WalletAccount,
        val chain: Chain,
        /** The signature names [chain]; false when it isn't tied to any chain (no `chainId` in its domain). */
        val chainBound: Boolean,
        val domainName: String?,
        val verifyingContract: String?,
        val primaryType: String,
        val messageJson: String,
    ) : EthAsk

    /**
     * `eth_sendTransaction`, priced ([quote]); [repriced] when an earlier
     * quote went stale and this is its fresh price. [autoApprove] is the
     * rule (#112) the user may turn on with it — null when the call can't
     * have one ([AutoApproveRule.eligible]); [ruled] when that rule is
     * already on and the sheet shows only because the wallet is locked or
     * the send replaces one the user stopped tracking.
     */
    data class SendTransaction(
        override val origin: String,
        val quote: SendQuote,
        val repriced: Boolean,
        val autoApprove: AutoApproveRule? = null,
        val ruled: Boolean = false,
    ) : EthAsk

    /** `wallet_switchEthereumChain` (or `wallet_addEthereumChain` for a chain the wallet already has). */
    data class SwitchChain(override val origin: String, val from: Chain, val to: Chain) : EthAsk

    /** `wallet_addEthereumChain` for a chain the wallet doesn't have: add [chain] and switch the site to it. */
    data class AddChain(override val origin: String, val chain: Chain) : EthAsk
}

/** The user's answer to an [EthAsk]. */
sealed interface EthAnswer {
    data object Rejected : EthAnswer

    /**
     * No sheet was shown: the user rejected an earlier one from this tab,
     * and its pages may not ask again until the user navigates it.
     */
    data object Paused : EthAnswer

    /**
     * [account]: the one the user picked to share, for [EthAsk.Connect].
     * [alwaysApprove]: for [EthAsk.SendTransaction], also turn its
     * [EthAsk.SendTransaction.autoApprove] rule on.
     */
    data class Approved(val account: WalletAccount? = null, val alwaysApprove: Boolean = false) : EthAnswer
}

/**
 * The `window.ethereum` provider (#110): the authority behind every
 * EIP-1193 request a page makes — desktop's `dapp-provider.js`, iOS's
 * `EthereumBridge` / `RPCRouter`. The page-side object and its channel
 * are [EthereumProviders]'; the approval sheets are [EthereumApprovalSheet].
 *
 *  - Anyone: `eth_chainId`, `net_version`, `eth_accounts` (empty until
 *    connected), the chain reads ([ChainDataRouter.READ_METHODS], through
 *    the chain-data router as the page's reads), and the chain switch
 *    requests, which ask the user.
 *  - `eth_requestAccounts` asks once and remembers the site with the
 *    account the user chose ([Grants]).
 *  - Connected sites only: `personal_sign`, `eth_signTypedData_v4` and
 *    `eth_sendTransaction`, each asked every time, from the account the
 *    site was given and no other — except a transaction an auto-approve
 *    rule the user turned on covers ([AutoApprove], #112), which goes out
 *    without a sheet while the wallet is unlocked.
 *
 * Each site is on a chain of its own: Gnosis until it switches (desktop's
 * default), an onchain app's own chain always. A switch moves that site
 * only. `eth_sign`, `eth_signTransaction` and `eth_sendRawTransaction`
 * are refused (4200), as on desktop and iOS.
 *
 * [origin] is always the platform's word for the requesting top-level
 * document ([providerOriginKey]), never something the page says.
 * Parameters are checked before any sheet shows, so the user is never
 * asked about a request that would fail anyway.
 */
class EthereumProvider(
    private val grants: Grants,
    private val wallet: Wallet,
    private val chains: suspend () -> List<Chain>,
    private val reads: suspend (chainId: Long, method: String, params: JSONArray, origin: String) -> Any?,
    private val sends: Sends,
    private val autoApprove: AutoApprove,
    /** Where typed data is parsed and hashed: never the main thread, which a page's payload could otherwise hold up. */
    private val compute: CoroutineContext = Dispatchers.Default,
) {
    /**
     * Connected sites ([baby.freedom.mobile.data.DappGrantStore]) and the
     * chain list. [grantFor] and [all] throw [GrantsUnreadable] while the
     * store can't be read — not "no site is connected" (#215 R6-M1).
     */
    interface Grants {
        suspend fun grantFor(origin: String): Grant?
        suspend fun grant(origin: String, account: String, chainId: Long): Boolean
        suspend fun setChain(origin: String, chainId: Long): Boolean
        suspend fun revoke(origin: String): Boolean

        /** Every connected site, by origin. */
        suspend fun all(): Map<String, Grant>

        /** Disconnect every site (the wallet was removed); true if it's written. */
        suspend fun clear(): Boolean

        /** Add a custom chain (Settings → Chains); true if it's there now. */
        suspend fun addChain(chain: Chain): Boolean
    }

    data class Grant(val account: String, val chainId: Long)

    /** [Grants] can't be read right now; which sites are connected, and on which chain, is unknown. */
    class GrantsUnreadable : Exception("connected sites unreadable")

    /**
     * Auto-approve rules (#112, [baby.freedom.mobile.data.AutoApproveStore]).
     * [matches] is false while the store can't be read: the sheet shows.
     */
    interface AutoApprove {
        suspend fun matches(rule: AutoApproveRule): Boolean
        suspend fun grant(rule: AutoApproveRule): Boolean

        /** Drop every rule of [origin]; true if it's written. */
        suspend fun revokeOrigin(origin: String): Boolean

        /** Drop every rule (the wallet was removed); true if it's written. */
        suspend fun clear(): Boolean
    }

    /** The wallet: its accounts (public), activity, and signing a digest. */
    interface Wallet {
        /** The wallet's accounts, or null when there's no wallet (or it has never been opened). */
        suspend fun accounts(): List<WalletAccount>?

        /** Whether the wallet is open right now, so a send can be signed without asking to unlock. */
        fun unlocked(): Boolean

        /** A connected site is using the wallet: the idle lock waits (maintainer decision 7). */
        fun noteActivity()

        /** [MessageSigning.sign]; throws [VaultLockedException] if the wallet isn't open. */
        fun sign(account: WalletAccount, digest: ByteArray): String
    }

    /** The send flow ([baby.freedom.mobile.wallet.WalletSender]). */
    interface Sends {
        /** Throws [SendException] with what to tell the user. */
        suspend fun prepare(request: SendRequest): SendQuote

        /** Sign and broadcast [quote]; returns once it's out (its hash) or didn't go. */
        suspend fun submit(quote: SendQuote): Submitted

        /**
         * Whether [submit] would refuse any quote right now as
         * [Submitted.Busy] (another send is going out or unresolved) — so
         * the user isn't asked to confirm one that can't go.
         */
        fun busy(): Boolean
    }

    sealed interface Submitted {
        data class Sent(val hash: String) : Submitted
        data object Stale : Submitted
        data object Busy : Submitted
        data class Failed(val message: String, val hash: String?) : Submitted
    }

    /** A request's answer: a result for the page (JSON-able, [JSONObject.NULL] for null), or an error. */
    sealed interface Reply {
        data class Ok(val value: Any) : Reply

        data class Err(val code: Int, val message: String, val data: Any? = null) : Reply {
            fun toJson(): JSONObject = JSONObject().put("code", code).put("message", message).apply {
                if (data != null) put("data", data)
            }
        }
    }

    /** Receives the provider's events for pages on an origin: `accountsChanged`, `chainChanged`, `connect`. */
    fun interface Events {
        fun emit(origin: String, event: String, data: Any)
    }

    @Volatile
    var events: Events = Events { _, _, _ -> }

    /** Chains picked by sites that aren't connected: for this process only, never written down. */
    private val sessionChains = HashMap<String, Long>()

    /**
     * Answer one request from a page on [origin]. [ask] puts an approval
     * sheet up on the page's tab and returns the user's answer
     * ([EthAnswer.Rejected] too for a sheet that couldn't be shown).
     */
    suspend fun request(origin: String, method: String, params: JSONArray, ask: suspend (EthAsk) -> EthAnswer): Reply = try {
        dispatch(origin, method, params, ask)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BadParams) {
        Reply.Err(INVALID_PARAMS, e.message ?: "Invalid params")
    } catch (e: ChainUnavailable) {
        Reply.Err(CHAIN_DISCONNECTED, "Chain ${e.id} isn't set up in this wallet (Settings → Chains)")
    } catch (e: GrantsUnreadable) {
        // Not "unconnected, on Gnosis": answering that would flip a connected site's accounts
        // and chain with no event, and back once the store reads again (#215 R6-M1).
        Reply.Err(INTERNAL, "Couldn't read the wallet's connected sites; try again")
    }

    private suspend fun dispatch(origin: String, method: String, params: JSONArray, ask: suspend (EthAsk) -> EthAnswer): Reply {
        val connected = connectedAccount(origin)
        if (connected != null) wallet.noteActivity()
        return when (method) {
            "eth_chainId" -> Reply.Ok(chainFor(origin).hexId)
            "net_version" -> Reply.Ok(chainFor(origin).id.toString())
            "eth_accounts" -> Reply.Ok(JSONArray().apply { connected?.let { put(it.address) } })
            "eth_coinbase" -> Reply.Ok(connected?.address ?: JSONObject.NULL)
            "eth_requestAccounts" -> connect(origin, connected, ask)
            "wallet_requestPermissions" -> when (val r = connect(origin, connected, ask)) {
                is Reply.Ok -> Reply.Ok(permissions(origin, connectedAccount(origin)))
                else -> r
            }
            "wallet_getPermissions" -> Reply.Ok(permissions(origin, connected))
            "wallet_revokePermissions" -> if (disconnect(origin)) Reply.Ok(JSONObject.NULL) else Reply.Err(INTERNAL, "Couldn't save the change")
            "personal_sign" -> personalSign(origin, connected ?: return notConnected(), params, ask)
            "eth_signTypedData_v4" -> signTypedData(origin, connected ?: return notConnected(), params, ask)
            "eth_sendTransaction" -> sendTransaction(origin, connected ?: return notConnected(), params, ask)
            "wallet_switchEthereumChain" -> switchChain(origin, params, ask)
            "wallet_addEthereumChain" -> addChain(origin, params, ask)
            "eth_sign", "eth_signTransaction", "eth_sendRawTransaction", "eth_signTypedData", "eth_signTypedData_v1",
            "eth_signTypedData_v3",
            -> Reply.Err(UNSUPPORTED, "Method not supported: $method")
            in ChainDataRouter.READ_METHODS -> read(origin, method, params)
            else -> Reply.Err(UNSUPPORTED, "Method not supported: $method")
        }
    }

    /**
     * Disconnect [origin] (`wallet_revokePermissions`, or Disconnect on the
     * wallet page): its pages see no accounts, and its auto-approve rules
     * (#112) are dropped, so connecting again later starts with none. The
     * rules go first: if they can't be written the site stays connected
     * and the caller can try again, never a disconnected site with rules
     * left over. The site stays on the chain
     * it was on for the rest of this session — its pages were told that
     * chain and get no `chainChanged` — as a site that never connected
     * keeps the one it switched to. False if it couldn't be written.
     *
     * Runs to the end even if the caller is cancelled (a page closed while
     * the write is in flight): the store commits the revoke regardless, and
     * a revoke that commits must also tell the site's pages. Holds
     * [siteLinks], so a rule turned on from a sheet that was open meanwhile
     * can't be written after the site's rules were dropped.
     */
    suspend fun disconnect(origin: String): Boolean = withContext(NonCancellable) {
        siteLinks.withLock {
            val grant = try {
                grants.grantFor(origin)
            } catch (e: GrantsUnreadable) {
                return@withLock false
            } ?: return@withLock autoApprove.revokeOrigin(origin)
            if (!autoApprove.revokeOrigin(origin)) return@withLock false
            if (!grants.revoke(origin)) return@withLock false
            synchronized(sessionChains) { sessionChains[origin] = grant.chainId }
            events.emit(origin, "accountsChanged", JSONArray())
            true
        }
    }

    /**
     * The wallet was removed: every site is disconnected and every
     * auto-approve rule dropped, so importing the
     * same phrase later doesn't quietly reconnect them, and their open
     * pages see no accounts. Each keeps its chain for the session, as
     * [disconnect]. False if either store couldn't be written — but each is
     * still cleared as far as it can be: a rule store that can't be written
     * doesn't keep every site connected past the wallet's removal (and a
     * rule left behind is dropped when its site next connects, [connect]).
     * Not cancellable, as [disconnect].
     */
    suspend fun disconnectAll(): Boolean = withContext(NonCancellable) {
        siteLinks.withLock {
            // Unreadable: clear them all the same; only which pages to tell is unknown.
            val all = try {
                grants.all()
            } catch (e: GrantsUnreadable) {
                emptyMap()
            }
            val rulesCleared = autoApprove.clear()
            if (!grants.clear()) return@withLock false
            synchronized(sessionChains) { all.forEach { (origin, g) -> sessionChains[origin] = g.chainId } }
            all.keys.forEach { events.emit(it, "accountsChanged", JSONArray()) }
            rulesCleared
        }
    }

    /**
     * Held while a site's connection or its auto-approve rules change
     * ([connect], [disconnect], [disconnectAll], [grantRule]), so a rule is
     * only ever written for a site that is connected, with the account it
     * was turned on for, at the moment it's written.
     */
    private val siteLinks = Mutex()

    /**
     * Turn [rule] on for [origin]'s send from [account] — if the site is
     * still connected with that account now that the sheet is closed. The
     * page may have disconnected itself (`wallet_revokePermissions`), or the
     * user it, while the sheet was up; a rule written then would outlive
     * the disconnect and cover the site's next connection (R1-F1).
     */
    private suspend fun grantRule(origin: String, account: WalletAccount, rule: AutoApproveRule) {
        siteLinks.withLock {
            val now = try {
                connectedAccount(origin)
            } catch (e: GrantsUnreadable) {
                null
            }
            if (now != null && now.address.equals(account.address, ignoreCase = true)) autoApprove.grant(rule)
        }
    }

    // ---- Chains ----

    /**
     * The chain [origin] is on: an onchain app's own; the connected site's;
     * the one it switched to; else Gnosis.
     *
     * A site whose chain was removed in Settings → Chains is moved to Gnosis
     * — written down, and its pages told with `chainChanged` — rather than
     * quietly answered and routed for Gnosis while they still think they're
     * on the old chain (#215 R3-F2). An onchain app pinned to a removed
     * chain can't move: its requests are refused with 4901 instead. So is
     * any site whose chain can't be looked up because the list couldn't be
     * read.
     */
    internal suspend fun chainFor(origin: String): Chain {
        val list = runCatching { chains() }.getOrNull()
        val pinned = pinnedChain(origin)
        val id = pinned ?: storedChain(origin) ?: DEFAULT_CHAIN_ID
        (list ?: BuiltInChains.ALL).firstOrNull { it.id == id }?.let { return it }
        if (id == DEFAULT_CHAIN_ID) return BuiltInChains.GNOSIS
        if (list == null || pinned != null) throw ChainUnavailable(id)
        return moveOffRemoved(origin, id)
    }

    /** The chain [origin] was last put on (connected, or switched for this session); null if none. */
    private suspend fun storedChain(origin: String): Long? =
        grants.grantFor(origin)?.chainId ?: synchronized(sessionChains) { sessionChains[origin] }

    /** Serializes moving sites off removed chains, so each is moved and told once. */
    private val chainMoves = Mutex()

    /**
     * Move [origin] off [removed] to Gnosis, and tell its pages. The chain
     * list is read again under the lock, not taken from the caller: one read
     * before a `wallet_addEthereumChain` finished would otherwise undo the
     * chain it just added and switched the site to (#215 R5-M2).
     */
    private suspend fun moveOffRemoved(origin: String, removed: Long): Chain {
        val moved = chainMoves.withLock {
            // Another request, or the sweep, may have moved it meanwhile.
            if ((storedChain(origin) ?: DEFAULT_CHAIN_ID) != removed) return@withLock null
            val list = runCatching { chains() }.getOrNull() ?: throw ChainUnavailable(removed)
            // Added (back) since the caller looked: nothing to move.
            if (list.any { it.id == removed }) return@withLock null
            val fallback = list.firstOrNull { it.id == DEFAULT_CHAIN_ID } ?: BuiltInChains.GNOSIS
            if (!setChainFor(origin, fallback.id)) throw ChainUnavailable(removed)
            events.emit(origin, "chainChanged", fallback.hexId)
            fallback
        }
        return moved ?: chainFor(origin)
    }

    /**
     * The chain list is now [list] (Settings → Chains changed it): every
     * site on a chain that's gone is moved to Gnosis and its pages told,
     * as [chainFor] would on the site's next request.
     */
    suspend fun chainsChanged(list: List<Chain>) {
        val ids = list.mapTo(HashSet()) { it.id }
        val origins = runCatching { grants.all() }.getOrNull().orEmpty().filterValues { it.chainId !in ids }.keys +
            synchronized(sessionChains) { sessionChains.filterValues { it !in ids }.keys.toList() }
        for (origin in origins) {
            if (pinnedChain(origin) != null) continue
            // A grant store that can't be read moves nobody (#215 R6-M1).
            val id = runCatching { storedChain(origin) }.getOrNull() ?: continue
            if (id !in ids) runCatching { moveOffRemoved(origin, id) }
        }
    }

    /** [chainFor] can't name a chain the site's pages can be answered for. */
    private class ChainUnavailable(val id: Long) : Exception()

    private fun pinnedChain(origin: String): Long? = OnchainAppRef.parseVirtual(origin)?.first?.chainId

    private suspend fun setChainFor(origin: String, chainId: Long): Boolean {
        if (grants.grantFor(origin) != null) return grants.setChain(origin, chainId)
        synchronized(sessionChains) { sessionChains[origin] = chainId }
        return true
    }

    /**
     * The chain [origin] is switching away from: [chainFor], except that a
     * site on a custom chain can still leave it for a built-in one while
     * the chain list can't be read (#215 R6-M3) — its current chain is then
     * named by ID alone, as nothing more about it can be looked up.
     */
    private suspend fun switchingFrom(origin: String): Chain = try {
        chainFor(origin)
    } catch (e: ChainUnavailable) {
        if (pinnedChain(origin) != null || runCatching { chains() }.getOrNull() != null) throw e
        Chain(id = e.id, name = "Custom network", symbol = "", rpcUrls = emptyList())
    }

    private suspend fun switchChain(origin: String, params: JSONArray, ask: suspend (EthAsk) -> EthAnswer): Reply {
        val id = chainIdParam(params)
        val current = switchingFrom(origin)
        if (current.id == id) return Reply.Ok(JSONObject.NULL)
        pinnedChain(origin)?.let { return pinnedRefusal(current) }
        val list = runCatching { chains() }.getOrNull()
        val target = (list ?: BuiltInChains.ALL).firstOrNull { it.id == id }
            // Unreadable list: the chain may well be set up, so 4902 ("add it first") would be a lie.
            ?: return if (list == null) chainListUnreadable() else Reply.Err(UNRECOGNIZED_CHAIN, "Unrecognized chain ID ${hex(id)}. Try adding the chain using wallet_addEthereumChain first.")
        return switchTo(origin, current, target, ask)
    }

    private suspend fun switchTo(origin: String, current: Chain, target: Chain, ask: suspend (EthAsk) -> EthAnswer): Reply {
        ask(EthAsk.SwitchChain(origin, current, target)).let { if (it !is EthAnswer.Approved) return refused(it) }
        if (!setChainFor(origin, target.id)) return Reply.Err(INTERNAL, "Couldn't save the change")
        events.emit(origin, "chainChanged", target.hexId)
        return Reply.Ok(JSONObject.NULL)
    }

    private suspend fun addChain(origin: String, params: JSONArray, ask: suspend (EthAsk) -> EthAnswer): Reply {
        val p = params.opt(0) as? JSONObject ?: throw BadParams("Expected [{chainId, chainName, nativeCurrency, rpcUrls}]")
        val id = chainIdOf(p.opt("chainId"))
        val current = switchingFrom(origin)
        if (current.id == id) return Reply.Ok(JSONObject.NULL)
        pinnedChain(origin)?.let { return pinnedRefusal(current) }
        // A chain the wallet has keeps its own settings: this only switches to it.
        val list = runCatching { chains() }.getOrNull()
        (list ?: BuiltInChains.ALL).firstOrNull { it.id == id }?.let { return switchTo(origin, current, it, ask) }
        // Unreadable list: whether the wallet already has this chain is unknown, and if it
        // does, approving an Add sheet showing the site's name and RPCs would keep the stored
        // ones instead (#215 R5-M1). Refuse rather than show a sheet that may not be true.
        if (list == null) return chainListUnreadable()
        val chain = chainFromParams(id, p, allowLoopback = RpcUrls.isLoopbackUrl(origin))
        ask(EthAsk.AddChain(origin, chain)).let { if (it !is EthAnswer.Approved) return refused(it) }
        if (!grants.addChain(chain)) return Reply.Err(INTERNAL, "Couldn't add the chain")
        if (!setChainFor(origin, chain.id)) return Reply.Err(INTERNAL, "Couldn't save the change")
        events.emit(origin, "chainChanged", chain.hexId)
        return Reply.Ok(JSONObject.NULL)
    }

    private fun chainListUnreadable() = Reply.Err(INTERNAL, "Couldn't read the wallet's chain list; try again")

    private fun pinnedRefusal(current: Chain) =
        Reply.Err(UNSUPPORTED, "This onchain app is pinned to ${current.name} (chain ${current.id}); it can't switch chains.")

    // ---- Accounts ----

    /** [origin]'s account, if it's connected with one this wallet still has. */
    private suspend fun connectedAccount(origin: String): WalletAccount? {
        val grant = grants.grantFor(origin) ?: return null
        return wallet.accounts()?.firstOrNull { it.address.equals(grant.account, ignoreCase = true) }
    }

    private suspend fun connect(origin: String, connected: WalletAccount?, ask: suspend (EthAsk) -> EthAnswer): Reply {
        if (connected != null) return Reply.Ok(JSONArray().put(connected.address))
        val chain = chainFor(origin)
        val answer = ask(EthAsk.Connect(origin, chain))
        if (answer !is EthAnswer.Approved) return refused(answer)
        val picked = answer.account ?: return rejected()
        // Only an account this wallet has, whatever the sheet handed back.
        val account = wallet.accounts()?.firstOrNull { it.address.equals(picked.address, ignoreCase = true) } ?: return rejected()
        // A new connection starts with no rules: any the site's last one left (a grant for an
        // account this wallet no longer lists, or a rule store the wallet's removal couldn't
        // clear) were turned on for another account's sends, not this one's (R1-M1).
        val saved = withContext(NonCancellable) {
            siteLinks.withLock { autoApprove.revokeOrigin(origin) && grants.grant(origin, account.address, chain.id) }
        }
        if (!saved) return Reply.Err(INTERNAL, "Couldn't save the connection")
        synchronized(sessionChains) { sessionChains.remove(origin) }
        wallet.noteActivity()
        events.emit(origin, "accountsChanged", JSONArray().put(account.address))
        events.emit(origin, "connect", JSONObject().put("chainId", chain.hexId))
        return Reply.Ok(JSONArray().put(account.address))
    }

    /** EIP-2255's view of the one permission there is: `eth_accounts`. */
    private fun permissions(origin: String, account: WalletAccount?): JSONArray = JSONArray().apply {
        if (account == null) return@apply
        put(
            JSONObject()
                .put("parentCapability", "eth_accounts")
                .put("invoker", origin)
                .put(
                    "caveats",
                    JSONArray().put(JSONObject().put("type", "restrictReturnedAccounts").put("value", JSONArray().put(account.address))),
                ),
        )
    }

    /** [raw] names [account] (any case), or the site asked to act as another one. */
    private fun requireSameAccount(raw: String?, account: WalletAccount) {
        if (raw == null || !ADDRESS.matches(raw.trim()) || !raw.trim().equals(account.address, ignoreCase = true)) {
            throw BadParams("The address doesn't match the account connected to this site")
        }
    }

    // ---- Signing ----

    private suspend fun personalSign(origin: String, account: WalletAccount, params: JSONArray, ask: suspend (EthAsk) -> EthAnswer): Reply {
        val p0 = params.opt(0) as? String
        val p1 = params.opt(1) as? String
        if (p0 == null || p1 == null) throw BadParams("Expected [message, address]")
        // MetaMask's order is [message, address]; some sites send [address, message].
        val (message, address) = when {
            ADDRESS.matches(p1.trim()) -> p0 to p1
            ADDRESS.matches(p0.trim()) -> p1 to p0
            else -> throw BadParams("Expected [message, address]")
        }
        requireSameAccount(address, account)
        val bytes = Eip712.hex(message) ?: message.toByteArray(Charsets.UTF_8)
        // Plain text too: one with a bidi override or an invisible character is shown as hex.
        val text = readableUtf8(bytes)
        val answer = ask(EthAsk.SignMessage(origin, account, text, "0x" + bytes.hexString()))
        if (answer !is EthAnswer.Approved) return refused(answer)
        return signed { wallet.sign(account, MessageSigning.personalDigest(bytes)) }
    }

    private suspend fun signTypedData(origin: String, account: WalletAccount, params: JSONArray, ask: suspend (EthAsk) -> EthAnswer): Reply {
        if (params.length() != 2) throw BadParams("Expected [address, typedData]")
        requireSameAccount(params.opt(0) as? String, account)
        // Off the main thread: the payload is the page's, and so is how long it takes to hash.
        val (data, digest, shown) = withContext(compute) {
            try {
                val data = Eip712.parse(params.opt(1))
                val digest = Eip712.digest(data)
                val shown = Eip712.signedMessage(data).let { m -> runCatching { m.toString(2) }.getOrElse { m.toString() } }
                Triple(data, digest, shown)
            } catch (e: Eip712.Invalid) {
                throw BadParams("Invalid typed data: ${e.message}")
            }
        }
        val chain = chainFor(origin)
        // Only a chainId the domain separator covers says which chain this is for; an undeclared one isn't signed.
        val chainBound = Eip712.chainBound(data)
        if (chainBound && data.chainId != BigInteger.valueOf(chain.id)) {
            throw BadParams("The typed data is for chain ${data.domain.opt("chainId")}, but this site is on ${chain.name} (chain ${chain.id})")
        }
        val ask0 = EthAsk.SignTypedData(
            origin = origin,
            account = account,
            chain = chain,
            chainBound = chainBound,
            // Only what the domain separator covers: an undeclared key isn't signed.
            domainName = Eip712.signedDomainString(data, "name"),
            verifyingContract = Eip712.signedDomainString(data, "verifyingContract"),
            primaryType = data.primaryType,
            // Only what the signature covers: a key the types don't declare isn't signed.
            messageJson = shown,
        )
        ask(ask0).let { if (it !is EthAnswer.Approved) return refused(it) }
        return signed { wallet.sign(account, digest) }
    }

    private fun signed(sign: () -> String): Reply = try {
        Reply.Ok(sign())
    } catch (e: VaultLockedException) {
        Reply.Err(UNAUTHORIZED, "The wallet locked before signing. Nothing was signed.")
    } catch (e: Exception) {
        Reply.Err(INTERNAL, "Couldn't sign. Nothing was signed.")
    }

    // ---- Transactions ----

    private suspend fun sendTransaction(origin: String, account: WalletAccount, params: JSONArray, ask: suspend (EthAsk) -> EthAnswer): Reply {
        val tx = params.opt(0) as? JSONObject ?: throw BadParams("Expected [transaction]")
        (tx.opt("from") as? String)?.let { requireSameAccount(it, account) }
        val toRaw = (tx.opt("to") as? String)?.trim()
        if (toRaw.isNullOrEmpty()) throw BadParams("Deploying a contract isn't supported: the transaction needs a \"to\" address")
        if (!ADDRESS.matches(toRaw)) throw BadParams("\"to\" isn't an address")
        val to = checksummed(toRaw) ?: throw BadParams("\"to\" has a typo: its capital letters don't match its checksum")
        val value = quantityParam(tx.opt("value"), "value") ?: BigInteger.ZERO
        val dataRaw = (tx.opt("data") ?: tx.opt("input"))?.takeIf { it != JSONObject.NULL }
        val data = when (dataRaw) {
            null -> ByteArray(0)
            is String -> if (dataRaw.isEmpty()) ByteArray(0) else Eip712.hex(dataRaw) ?: throw BadParams("\"data\" isn't hex")
            else -> throw BadParams("\"data\" isn't hex")
        }
        val gas = quantityParam(tx.opt("gas") ?: tx.opt("gasLimit"), "gas")?.takeIf { it.signum() > 0 }
        val chain = chainFor(origin)
        quantityParam(tx.opt("chainId"), "chainId")?.let {
            if (it != BigInteger.valueOf(chain.id)) {
                throw BadParams("The transaction is for chain $it, but this site is on ${chain.name} (chain ${chain.id}). Switch first.")
            }
        }
        val request = SendRequest(chain, TokenRegistry.native(chain), account, to, value, DappCall(origin, data, gas))
        var quote = when (val q = prepare(request)) {
            is SendQuote -> q
            else -> return q as Reply
        }
        // The one rule that could cover this call (#112): this site, this contract, this function, this chain.
        val rule = AutoApproveRule.eligible(origin, to, value, data, chain.id)
        var repriced = false
        repeat(MAX_REPRICES) {
            // Before the sheet, not after the user confirmed one that can't go.
            if (sends.busy()) return busy()
            val ruled = rule != null && autoApprove.matches(rule)
            // No sheet only with the wallet open (else the sheet's button asks to unlock), and never
            // for a send that takes the place of one the user stopped tracking: that warning is theirs to read.
            if (!ruled || !wallet.unlocked() || quote.replaces != null) {
                val answer = ask(EthAsk.SendTransaction(origin, quote, repriced, rule, ruled))
                if (answer !is EthAnswer.Approved) return refused(answer)
                // Turned on with the sheet's approval. A rule that couldn't be saved doesn't stop this send.
                if (answer.alwaysApprove && rule != null && !ruled) grantRule(origin, account, rule)
            }
            when (val s = sends.submit(quote)) {
                is Submitted.Sent -> return Reply.Ok(s.hash)
                Submitted.Busy -> return busy()
                is Submitted.Failed -> return Reply.Err(INTERNAL, s.message, s.hash?.let { JSONObject().put("hash", it) })
                // Priced too long ago to trust its fee: price it again and let the user look.
                Submitted.Stale -> {
                    quote = when (val q = prepare(request)) {
                        is SendQuote -> q
                        else -> return q as Reply
                    }
                    repriced = true
                }
            }
        }
        return Reply.Err(INTERNAL, "The network fee kept changing before it could be sent. Nothing was sent.")
    }

    private fun busy() = Reply.Err(
        INTERNAL,
        "Another transaction from this wallet is still going out or waiting on the wallet page. Nothing was sent.",
    )

    /** The priced [request], or the [Reply] saying why it can't be sent (a [SendException]'s words). */
    private suspend fun prepare(request: SendRequest): Any = try {
        sends.prepare(request)
    } catch (e: SendException) {
        Reply.Err(INTERNAL, e.message ?: "Couldn't prepare the transaction")
    }

    // ---- Reads ----

    private suspend fun read(origin: String, method: String, params: JSONArray): Reply {
        val chain = chainFor(origin)
        return try {
            Reply.Ok(reads(chain.id, method, params, origin) ?: JSONObject.NULL)
        } catch (e: ChainRpcException.Rpc) {
            Reply.Err(e.code, e.rpcMessage, e.data)
        } catch (e: ChainRpcException.UnknownChain) {
            Reply.Err(CHAIN_DISCONNECTED, "${chain.name} isn't set up in Settings → Chains")
        } catch (e: ChainRpcException.AllSourcesFailed) {
            e.nodeError?.let { Reply.Err(it.code, it.rpcMessage, it.data) }
                ?: Reply.Err(RESOURCE_UNAVAILABLE, "No RPC answered for ${chain.name}")
        } catch (e: ChainRpcException) {
            Reply.Err(INTERNAL, "The RPC's answer made no sense")
        }
    }

    // ---- Helpers ----

    private class BadParams(message: String) : Exception(message)

    private fun rejected() = Reply.Err(USER_REJECTED, "User rejected the request.")

    /** 4001 either way; a paused tab's says why no sheet came up, for the site to show. */
    private fun refused(answer: EthAnswer) = if (answer == EthAnswer.Paused) {
        Reply.Err(USER_REJECTED, "The user rejected an earlier request from this page; reload it to ask again.")
    } else {
        rejected()
    }

    private fun notConnected() = Reply.Err(UNAUTHORIZED, "Not connected. Call eth_requestAccounts first.")

    private fun chainIdParam(params: JSONArray): Long {
        val p = params.opt(0) as? JSONObject ?: throw BadParams("Expected [{chainId}]")
        return chainIdOf(p.opt("chainId"))
    }

    private fun chainIdOf(raw: Any?): Long {
        val s = raw as? String ?: throw BadParams("chainId must be a 0x-prefixed hex string")
        if (!s.startsWith("0x")) throw BadParams("chainId must be a 0x-prefixed hex string")
        return ChainInput.parseId(s) ?: throw BadParams("chainId isn't a valid chain ID")
    }

    /**
     * The chain [p] describes. An `http://` loopback RPC (a node on the
     * device) only when the page asking is itself on loopback
     * ([allowLoopback]): a remote site mustn't point the app's own
     * JSON-RPC client at ports on the device.
     */
    private fun chainFromParams(id: Long, p: JSONObject, allowLoopback: Boolean): Chain {
        val name = p.opt("chainName") as? String ?: throw BadParams("chainName is missing")
        val currency = p.opt("nativeCurrency") as? JSONObject ?: throw BadParams("nativeCurrency is missing")
        val symbol = currency.opt("symbol") as? String ?: throw BadParams("nativeCurrency.symbol is missing")
        val decimals = (currency.opt("decimals") as? Number)?.toInt() ?: throw BadParams("nativeCurrency.decimals is missing")
        val rpcs = (p.opt("rpcUrls") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
            .take(Chain.MAX_RPC_URLS * 4)
            .mapNotNull(RpcUrls::normalize)
            .filter { allowLoopback || !RpcUrls.isLoopbackUrl(it) }
            .distinct()
            .take(Chain.MAX_RPC_URLS)
        if (rpcs.isEmpty()) throw BadParams("rpcUrls has no usable https RPC")
        val explorer = (p.opt("blockExplorerUrls") as? JSONArray)?.let { a ->
            (0 until a.length()).mapNotNull { a.opt(it) as? String }.firstNotNullOfOrNull(ChainInput::normalizeExplorer)
        }.orEmpty()
        return ChainInput.build(
            id = id.toString(),
            name = name,
            symbol = symbol,
            decimals = decimals.toString(),
            explorer = explorer,
            rpcUrls = rpcs,
            currencyName = currency.opt("name") as? String,
        ) ?: throw BadParams("The chain's name, symbol or decimals aren't valid")
    }

    private fun quantityParam(raw: Any?, name: String): BigInteger? {
        if (raw == null || raw == JSONObject.NULL) return null
        val v = try {
            Eip712.integer(raw, name)
        } catch (e: Eip712.Invalid) {
            throw BadParams("\"$name\" isn't a number")
        }
        if (v.signum() < 0) throw BadParams("\"$name\" is negative")
        return v
    }

    companion object {
        /** EIP-1193 / EIP-1474 / EIP-3326 codes. */
        const val USER_REJECTED = 4001
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED = 4200
        const val CHAIN_DISCONNECTED = 4901
        const val UNRECOGNIZED_CHAIN = 4902
        const val RESOURCE_UNAVAILABLE = -32002
        const val INVALID_PARAMS = -32602
        const val INTERNAL = -32603

        /** Desktop's default: Gnosis Chain. */
        val DEFAULT_CHAIN_ID = BuiltInChains.GNOSIS.id

        /** How many times a send is priced again after the user took too long to confirm. */
        private const val MAX_REPRICES = 3

        private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

        private fun hex(id: Long) = "0x" + id.toString(16)

        /** EIP-55 form of [address]; null when it's mixed case with a wrong checksum. */
        internal fun checksummed(address: String): String? {
            val digits = address.substring(2)
            val sum = NodeIdentity.checksum(ByteArray(20) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
            val mixed = digits.any { it in 'a'..'f' } && digits.any { it in 'A'..'F' }
            return if (mixed && sum != address) null else sum
        }

        /**
         * [bytes] as text if it's UTF-8 with no control characters but tab
         * and newlines and no format characters (Unicode `Cf`: bidi
         * overrides and isolates, zero-width spaces and joiners, the BOM),
         * which would make the sheet show something other than, or in
         * another order than, what's signed; else null (shown as hex).
         */
        internal fun readableUtf8(bytes: ByteArray): String? {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val s = try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                return null
            }
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val control = Character.isISOControl(cp) && cp != '\n'.code && cp != '\t'.code && cp != '\r'.code
                if (control || Character.getType(cp) == Character.FORMAT.toInt()) return null
                i += Character.charCount(cp)
            }
            return s
        }

        private fun ByteArray.hexString(): String = hexOf(this)
    }
}
