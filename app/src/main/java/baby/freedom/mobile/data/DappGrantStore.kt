package baby.freedom.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Which sites are connected to the wallet through `window.ethereum`
 * (#110) — desktop's `dapp-permissions.js`, iOS's `PermissionStore`:
 *
 *     "grant:<origin>" → {"account": "0x…", "chainId": 100}
 *
 * [Grant.account] is the one address the user chose to share with the
 * site; [Grant.chainId] the chain the site is on (each site keeps its
 * own, so one site switching chains never moves another). `<origin>` is
 * the provider's origin key (a normalized `scheme://host[:port]`, the
 * one site permissions and `window.radicle` use). Public data only —
 * never a key. A private tab never reads or writes here.
 *
 * Never throws for storage trouble: an unreadable file reads as "no
 * grants" (the site asks again), a failed write reports `false`, and a
 * corrupt file is replaced with an empty one.
 */
class DappGrantStore internal constructor(private val store: DataStore<Preferences>) {
    data class Grant(val origin: String, val account: String, val chainId: Long)

    /** Every grant, by origin. */
    val all: Flow<List<Grant>> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading dApp grants failed; treating as none", e)
            emit(emptyPreferences())
        }
        .map { prefs ->
            prefs.asMap().mapNotNull { (k, v) ->
                val name = k.name
                if (!name.startsWith(PREFIX)) return@mapNotNull null
                decode(name.removePrefix(PREFIX), v as? String ?: return@mapNotNull null)
            }.sortedBy { it.origin }
        }

    /** [origin]'s grant, or null if it has none (or the store can't be read). */
    suspend fun grantFor(origin: String): Grant? = try {
        all.first().firstOrNull { it.origin == origin }
    } catch (e: Exception) {
        Log.w(TAG, "reading dApp grant failed", e)
        null
    }

    /** Connect [origin] with [account] on [chainId]; `false` if it couldn't be written. */
    suspend fun grant(origin: String, account: String, chainId: Long): Boolean =
        write { it[keyOf(origin)] = encode(account, chainId) }

    /** Move connected [origin] to [chainId]; `false` if it isn't connected or the write failed. */
    suspend fun setChain(origin: String, chainId: Long): Boolean {
        var connected = false
        val written = write { prefs ->
            val current = prefs[keyOf(origin)]?.let { decode(origin, it) }
            connected = current != null
            if (current != null) prefs[keyOf(origin)] = encode(current.account, chainId)
        }
        return written && connected
    }

    /** Disconnect [origin]; `false` if the store couldn't be written. */
    suspend fun revoke(origin: String): Boolean = write { it.remove(keyOf(origin)) }

    /** Disconnect every site (the wallet was removed); `false` if the store couldn't be written. */
    suspend fun clear(): Boolean = write { prefs ->
        prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { prefs.remove(it) }
    }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing dApp grants failed", e)
        false
    }

    private fun keyOf(origin: String) = stringPreferencesKey("$PREFIX$origin")

    companion object {
        private const val PREFIX = "grant:"
        private const val TAG = "DappGrantStore"
        private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

        internal fun encode(account: String, chainId: Long): String =
            JSONObject().put("account", account).put("chainId", chainId).toString()

        internal fun decode(origin: String, json: String): Grant? = try {
            val o = JSONObject(json)
            val account = o.getString("account")
            val chainId = o.getLong("chainId")
            if (ADDRESS.matches(account) && chainId > 0) Grant(origin, account, chainId) else null
        } catch (e: Exception) {
            null
        }

        private val Context.dappGrantStore by preferencesDataStore(
            name = "freedom_dapp_grants",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: DappGrantStore? = null

        fun get(context: Context): DappGrantStore =
            instance ?: synchronized(this) {
                instance ?: DappGrantStore(context.applicationContext.dappGrantStore).also { instance = it }
            }
    }
}
