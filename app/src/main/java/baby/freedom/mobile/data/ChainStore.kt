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
import java.io.IOException
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
 * Built-ins are never stored, so they can't be removed or shadowed: a
 * chain ID that's built in can't be added, and a stored entry for one
 * (from a later build that made it built in) is ignored. Every entry is
 * re-validated through [ChainInput.build] on read, so a hand-edited or
 * half-written one is skipped rather than handed to a caller.
 *
 * Never throws for storage trouble, like [SitePermissionStore]: a file
 * that can't be read lists only the built-ins (and re-reads with
 * back-off), a failed write reports [AddResult.FAILED] / `false`, and a
 * corrupt file is replaced with an empty one.
 */
class ChainStore internal constructor(
    private val store: DataStore<Preferences>,
    private val readRetryMs: Long = 1_000,
    private val backOff: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class AddResult { ADDED, BUILT_IN, DUPLICATE, FAILED }

    /** Every chain: the built-ins, then custom chains in the order they were added. */
    val chains: Flow<List<Chain>> = flow {
        var failures = 0
        emitAll(
            store.data
                .onEach { failures = 0 }
                .retryWhen { e, _ ->
                    if (e !is IOException) return@retryWhen false
                    Log.w(TAG, "reading chains failed; listing built-ins only", e)
                    emit(emptyPreferences())
                    backOff(readRetryMs shl failures.coerceAtMost(5))
                    failures++
                    true
                },
        )
    }.map { prefs -> BuiltInChains.ALL + customChains(prefs) }

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
        } catch (e: IOException) {
            Log.w(TAG, "writing chain failed", e)
            AddResult.FAILED
        }
    }

    /** Remove the custom chain [id]; a built-in is never removed. `false` if nothing was. */
    suspend fun remove(id: Long): Boolean {
        if (BuiltInChains.isBuiltIn(id)) return false
        var removed = false
        return try {
            store.edit { prefs ->
                removed = prefs.contains(keyOf(id))
                prefs.remove(keyOf(id))
            }
            removed
        } catch (e: IOException) {
            Log.w(TAG, "removing chain failed", e)
            false
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

    companion object {
        private const val PREFIX = "chain:"
        private const val TAG = "ChainStore"

        private fun keyOf(id: Long) = stringPreferencesKey("$PREFIX$id")

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

        /** `(chain, addedAt)`, or `null` for anything [ChainInput.build] wouldn't accept. */
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
