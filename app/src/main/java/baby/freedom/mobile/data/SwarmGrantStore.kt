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

/**
 * Which sites may use the `window.swarm` provider (#120), and what they
 * may do without asking — desktop's `swarm-permissions.js`:
 *
 *     "connect:<origin>"         → connected-at, epoch ms
 *     "auto:<kind>:<origin>"     → "1" ("always allow": publish, feeds, signing)
 *
 * `<origin>` is the provider's origin key (a normalized
 * `scheme://host[:port]`, the one site permissions use). A private tab
 * never reads or writes here. Feed access itself is per wallet, in
 * [SwarmFeedStore].
 *
 * Never throws for storage trouble: an unreadable file reads as "no
 * grants" (the site asks again), a failed write reports `false`, and a
 * corrupt file is replaced with an empty one.
 */
class SwarmGrantStore internal constructor(private val store: DataStore<Preferences>) {
    /** One connected site and its "always allow" kinds ([KINDS]). */
    data class Grant(val origin: String, val connectedAt: Long, val autoApprove: Set<String>)

    /** Every connected site, most recently connected first. */
    val all: Flow<List<Grant>> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading Swarm grants failed; treating as none", e)
            emit(emptyPreferences())
        }
        .map { prefs ->
            val map = prefs.asMap().mapKeys { it.key.name }
            map.mapNotNull { (name, v) ->
                if (!name.startsWith(CONNECT)) return@mapNotNull null
                val origin = name.removePrefix(CONNECT)
                val auto = KINDS.filter { map["$AUTO$it:$origin"] == ON }.toSet()
                Grant(origin, (v as? String)?.toLongOrNull() ?: 0L, auto)
            }.sortedByDescending { it.connectedAt }
        }

    /** [origin]'s grant, or null if it isn't connected (or the store can't be read). */
    suspend fun grantFor(origin: String): Grant? = try {
        all.first().firstOrNull { it.origin == origin }
    } catch (e: Exception) {
        Log.w(TAG, "reading Swarm grant failed", e)
        null
    }

    /** Connect [origin]; `false` if it couldn't be written. */
    suspend fun connect(origin: String, now: Long = System.currentTimeMillis()): Boolean = write {
        if (it[connectKey(origin)] == null) it[connectKey(origin)] = now.toString()
    }

    /** "Always allow" [kind] for connected [origin]; `false` if it isn't connected or the write failed. */
    suspend fun setAutoApprove(origin: String, kind: String, on: Boolean): Boolean {
        require(kind in KINDS) { "unknown auto-approve kind" }
        var connected = false
        val written = write {
            connected = it[connectKey(origin)] != null
            if (connected) {
                if (on) it[autoKey(kind, origin)] = ON else it.remove(autoKey(kind, origin))
            }
        }
        return written && connected
    }

    /** Disconnect [origin], dropping its "always allow"s; `false` if the store couldn't be written. */
    suspend fun revoke(origin: String): Boolean = write {
        it.remove(connectKey(origin))
        for (kind in KINDS) it.remove(autoKey(kind, origin))
    }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing Swarm grants failed", e)
        false
    }

    private fun connectKey(origin: String) = stringPreferencesKey("$CONNECT$origin")
    private fun autoKey(kind: String, origin: String) = stringPreferencesKey("$AUTO$kind:$origin")

    companion object {
        /** The "always allow" kinds, desktop's `autoApprove` keys (messaging is #121's). */
        val KINDS = listOf("publish", "feeds", "signing")

        private const val CONNECT = "connect:"
        private const val AUTO = "auto:"
        private const val ON = "1"
        private const val TAG = "SwarmGrantStore"

        private val Context.swarmGrantStore by preferencesDataStore(
            name = "freedom_swarm_grants",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: SwarmGrantStore? = null

        fun get(context: Context): SwarmGrantStore =
            instance ?: synchronized(this) {
                instance ?: SwarmGrantStore(context.applicationContext.swarmGrantStore)
                    .also { instance = it }
            }
    }
}
