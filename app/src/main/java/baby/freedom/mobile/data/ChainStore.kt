package baby.freedom.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.ChainInput
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.ens.EnsRpcConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's custom chains (#107), next to the [BuiltInChains] that
 * ship with the app.
 *
 * Backed by its own [DataStore] (`freedom_chains`), one JSON string per
 * custom chain:
 *
 *     "chain:<chainId>" → {"id", "name", "symbol", "currencyName",
 *                          "decimals", "explorerUrl", "rpcUrls",
 *                          "isTestnet", "addedAt"}
 *
 * and the user's own RPCs for any chain, built-in or custom (#108):
 *
 *     "rpcs:<chainId>" → ["https://…", …]
 *
 * Built-ins are never stored, so they can't be removed or shadowed: a
 * chain ID that's built in can't be added, and a stored entry for one
 * (from a later build that made it built in) is ignored. Every entry is
 * re-validated through [ChainInput.build] on read, so a hand-edited or
 * half-written one is skipped rather than handed to a caller.
 *
 * Never throws for storage trouble, like [SitePermissionStore]: a file
 * that can't be read lists only the built-ins in [chains] and `null` in
 * [chainsOrUnreadable] (and re-reads with back-off), a failed write
 * reports [AddResult.FAILED] / [RemoveResult.FAILED], and a corrupt file
 * is replaced with an empty one.
 */
class ChainStore internal constructor(
    private val store: DataStore<Preferences>,
    private val readRetryMs: Long = 1_000,
    private val backOff: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class AddResult { ADDED, BUILT_IN, DUPLICATE, FAILED }

    /**
     * [NOT_FOUND] (a built-in, or a chain already gone) is not an error —
     * only [FAILED], a write that didn't land, leaves the chain listed.
     */
    enum class RemoveResult { REMOVED, NOT_FOUND, FAILED }

    /**
     * [addUserRpc]'s answer. [PUBLIC]: already one of the chain's public
     * RPCs; [NAME_RESOLUTION_PUBLIC]: one of name resolution's own
     * built-in public endpoints ([isNameResolutionPublic]); [NO_CHAIN]: no
     * such chain (removed meanwhile).
     */
    enum class RpcAddResult { ADDED, INVALID, DUPLICATE, PUBLIC, NAME_RESOLUTION_PUBLIC, FULL, NO_CHAIN, FAILED }

    /**
     * Every chain: the built-ins, then custom chains in the order they were
     * added — or `null` while the file can't be read (and is re-read with
     * back-off). A reader that acts on a chain being *gone* must use this
     * rather than [chains]: a read error isn't the user removing every
     * custom chain (#215 R4-F1).
     */
    val chainsOrUnreadable: Flow<List<Chain>?> = flow {
        var failures = 0
        emitAll(
            store.data
                .map<Preferences, Preferences?> { it }
                .onEach { failures = 0 }
                .retryWhen { e, _ ->
                    if (e !is IOException) return@retryWhen false
                    Log.w(TAG, "reading chains failed; listing built-ins only", e)
                    emit(null)
                    backOff(readRetryMs shl failures.coerceAtMost(5))
                    failures++
                    true
                },
        )
    }.map { prefs ->
        prefs?.let {
            (BuiltInChains.ALL + customChains(it)).map { chain ->
                userRpcs(it, chain).takeIf { rpcs -> rpcs.isNotEmpty() }?.let { rpcs -> chain.copy(userRpcUrls = rpcs) } ?: chain
            }
        }
    }

    /** [chainsOrUnreadable], listing only the built-ins while the file can't be read. */
    val chains: Flow<List<Chain>> = chainsOrUnreadable.map { it ?: BuiltInChains.ALL }

    /**
     * Add [raw] to chain [id]'s own RPCs ([Chain.userRpcUrls]), after
     * [RpcUrls.normalize] — checked against the stored list inside the
     * same write, so two quick adds can't overflow it or add one twice.
     * Every comparison is by [EnsRpcConfig.endpointKey]
     * (`https://ETH.drpc.org/` is `https://eth.drpc.org`).
     *
     * One of the chain's public RPCs is refused ([RpcAddResult.PUBLIC])
     * unless [allowPublic]: the chain page already asks those, but name
     * resolution doesn't (it has its own public list), so adding one
     * from there makes it yours — asked first, here too — stored as the
     * chain lists it, so every reader sees the one URL. For Ethereum
     * mainnet, whose own RPCs are name resolution's "your endpoints",
     * one of name resolution's built-in public endpoints is always
     * refused ([RpcAddResult.NAME_RESOLUTION_PUBLIC]), whichever page
     * it's added from: the resolver already asks it under its own
     * switch, and taking it as yours would label a third party's lone
     * answer as your RPC's.
     */
    suspend fun addUserRpc(id: Long, raw: String, allowPublic: Boolean = false): RpcAddResult {
        val url = RpcUrls.normalize(raw) ?: return RpcAddResult.INVALID
        var result = RpcAddResult.ADDED
        return try {
            store.edit { prefs ->
                val chain = BuiltInChains.ALL.firstOrNull { it.id == id }
                    ?: prefs[keyOf(id)]?.let(::decode)?.first?.takeIf { it.id == id && !BuiltInChains.isBuiltIn(id) }
                val current = chain?.let { userRpcs(prefs, it) }.orEmpty()
                val key = EnsRpcConfig.endpointKey(url)
                val listed = chain?.rpcUrls?.firstOrNull { EnsRpcConfig.endpointKey(it) == key }
                result = when {
                    chain == null -> RpcAddResult.NO_CHAIN
                    current.any { EnsRpcConfig.endpointKey(it) == key } -> RpcAddResult.DUPLICATE
                    !allowPublic && listed != null -> RpcAddResult.PUBLIC
                    isNameResolutionPublic(id, url) -> RpcAddResult.NAME_RESOLUTION_PUBLIC
                    current.size >= Chain.MAX_USER_RPC_URLS -> RpcAddResult.FULL
                    else -> {
                        prefs[rpcsKeyOf(id)] = JSONArray(current + (listed ?: url)).toString()
                        RpcAddResult.ADDED
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "writing RPC failed", e)
            RpcAddResult.FAILED
        }
    }

    /** Drop [url] from chain [id]'s own RPCs. `false` only when the write failed. */
    suspend fun removeUserRpc(id: Long, url: String): Boolean = try {
        store.edit { prefs ->
            val left = decodeRpcs(prefs[rpcsKeyOf(id)]) - url
            if (left.isEmpty()) prefs.remove(rpcsKeyOf(id)) else prefs[rpcsKeyOf(id)] = JSONArray(left).toString()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "removing RPC failed", e)
        false
    }

    /**
     * Move [url] one place up (`by = -1`) or down (`+1`) chain [id]'s own
     * RPCs — their order is the order they're asked in, and for Ethereum
     * mainnet the name-resolution order too (#102). `false` only when the
     * write failed; a move off either end changes nothing.
     */
    suspend fun moveUserRpc(id: Long, url: String, by: Int): Boolean = try {
        store.edit { prefs ->
            val list = decodeRpcs(prefs[rpcsKeyOf(id)]).toMutableList()
            val from = list.indexOf(url)
            val to = from + by
            if (from < 0 || to !in list.indices) return@edit
            list.add(to, list.removeAt(from))
            prefs[rpcsKeyOf(id)] = JSONArray(list).toString()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "moving RPC failed", e)
        false
    }

    /**
     * Append [urls] to built-in chain [id]'s own RPCs in one write — for
     * moving a list kept elsewhere in, not for user input: each is
     * [RpcUrls.normalize]d, and one that's invalid, already there
     * ([EnsRpcConfig.endpointKey]), one of name resolution's own public
     * endpoints ([isNameResolutionPublic]) or past
     * [Chain.MAX_USER_RPC_URLS] is skipped. One of the chain's other
     * public RPCs is taken, as the chain lists it, as [addUserRpc] with
     * `allowPublic` does: the list being moved in chose it on purpose.
     * Returns the URLs it skipped, or `null` when the write failed.
     */
    internal suspend fun importUserRpcs(id: Long, urls: List<String>): List<String>? {
        val chain = BuiltInChains.ALL.firstOrNull { it.id == id } ?: return urls
        val skipped = ArrayList<String>()
        return try {
            store.edit { prefs ->
                skipped.clear()
                val list = decodeRpcs(prefs[rpcsKeyOf(id)]).toMutableList()
                for (raw in urls) {
                    val url = RpcUrls.normalize(raw)
                    val key = url?.let(EnsRpcConfig::endpointKey)
                    if (url == null || list.any { EnsRpcConfig.endpointKey(it) == key } ||
                        isNameResolutionPublic(id, url) || list.size >= Chain.MAX_USER_RPC_URLS
                    ) {
                        skipped += raw
                    } else {
                        list += chain.rpcUrls.firstOrNull { EnsRpcConfig.endpointKey(it) == key } ?: url
                    }
                }
                if (list.isNotEmpty()) prefs[rpcsKeyOf(id)] = JSONArray(list).toString()
            }
            skipped
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "importing RPCs failed", e)
            null
        }
    }

    /**
     * Add [chain] as a custom chain, unless its ID is built in or
     * already added — checked inside the same write, so two quick adds
     * can't both land.
     */
    suspend fun add(chain: Chain): AddResult {
        if (BuiltInChains.isBuiltIn(chain.id)) return AddResult.BUILT_IN
        var result = AddResult.ADDED
        return try {
            store.edit { prefs ->
                val key = keyOf(chain.id)
                if (prefs[key]?.let(::decode) != null) {
                    result = AddResult.DUPLICATE
                } else {
                    prefs[key] = encode(chain.copy(builtIn = false), clock())
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "writing chain failed", e)
            AddResult.FAILED
        }
    }

    /** Remove the custom chain [id]; a built-in is never removed. */
    suspend fun remove(id: Long): RemoveResult {
        if (BuiltInChains.isBuiltIn(id)) return RemoveResult.NOT_FOUND
        var removed = false
        return try {
            store.edit { prefs ->
                removed = prefs.contains(keyOf(id))
                prefs.remove(keyOf(id))
                prefs.remove(rpcsKeyOf(id))
            }
            if (removed) RemoveResult.REMOVED else RemoveResult.NOT_FOUND
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "removing chain failed", e)
            RemoveResult.FAILED
        }
    }

    private fun customChains(prefs: Preferences): List<Chain> =
        prefs.asMap().mapNotNull { (k, v) ->
            if (!k.name.startsWith(PREFIX)) return@mapNotNull null
            val decoded = (v as? String)?.let(::decode) ?: return@mapNotNull null
            decoded.takeIf { (chain, _) ->
                "$PREFIX${chain.id}" == k.name && !BuiltInChains.isBuiltIn(chain.id)
            }
        }.sortedWith(compareBy({ it.second }, { it.first.id })).map { it.first }

    /**
     * [chain]'s own RPCs as stored, each re-validated and capped — a
     * hand-edited or stale entry is dropped rather than routed to. One
     * that's also a public RPC stays: it was added as yours on purpose
     * ([addUserRpc]'s `allowPublic`), which puts it first.
     */
    private fun userRpcs(prefs: Preferences, chain: Chain): List<String> =
        decodeRpcs(prefs[rpcsKeyOf(chain.id)])
            .filter { RpcUrls.normalize(it) == it }
            .distinct()
            .take(Chain.MAX_USER_RPC_URLS)

    companion object {
        private const val PREFIX = "chain:"
        private const val RPCS_PREFIX = "rpcs:"
        private const val TAG = "ChainStore"

        /**
         * Whether [url] may never be one of chain [id]'s own RPCs: on
         * Ethereum mainnet, whose own RPCs are name resolution's "your
         * endpoints", any URL on the host of one of name resolution's
         * built-in public endpoints ([EnsRpcConfig.publicEndpointHost]).
         */
        fun isNameResolutionPublic(id: Long, url: String): Boolean =
            id == BuiltInChains.ETHEREUM.id && EnsRpcConfig.publicEndpointHost(url) != null

        private fun keyOf(id: Long) = stringPreferencesKey("$PREFIX$id")
        private fun rpcsKeyOf(id: Long) = stringPreferencesKey("$RPCS_PREFIX$id")

        private fun decodeRpcs(json: String?): List<String> = try {
            json?.let { JSONArray(it) }?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        internal fun encode(chain: Chain, addedAt: Long): String = JSONObject()
            .put("id", chain.id)
            .put("name", chain.name)
            .put("symbol", chain.symbol)
            .put("currencyName", chain.currencyName)
            .put("decimals", chain.decimals)
            .put("explorerUrl", chain.explorerUrl ?: JSONObject.NULL)
            .put("rpcUrls", JSONArray(chain.rpcUrls))
            .put("isTestnet", chain.isTestnet)
            .put("addedAt", addedAt)
            .toString()

        /** `(chain, addedAt)`, or `null` for anything [ChainInput.build] wouldn't accept as a stored chain. */
        internal fun decode(json: String): Pair<Chain, Long>? = try {
            val o = JSONObject(json)
            val rpcs = o.optJSONArray("rpcUrls")
                ?.let { a -> (0 until a.length()).map { a.optString(it) } }
                .orEmpty()
            ChainInput.build(
                id = o.opt("id")?.toString().orEmpty(),
                name = o.optString("name"),
                symbol = o.optString("symbol"),
                decimals = o.opt("decimals")?.toString().orEmpty(),
                explorer = if (o.isNull("explorerUrl")) "" else o.optString("explorerUrl"),
                rpcUrls = rpcs,
                currencyName = o.optString("currencyName").takeIf { it.isNotBlank() },
                isTestnet = o.optBoolean("isTestnet", false),
                stored = true,
            )?.let { it to o.optLong("addedAt", 0) }
        } catch (_: Exception) {
            null
        }

        private val Context.chainStore by preferencesDataStore(
            name = "freedom_chains",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: ChainStore? = null

        fun get(context: Context): ChainStore =
            instance ?: synchronized(this) {
                instance ?: ChainStore(context.applicationContext.chainStore).also { instance = it }
            }
    }
}
