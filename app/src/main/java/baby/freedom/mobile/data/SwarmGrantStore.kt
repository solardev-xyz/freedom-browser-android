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
 *     "messaging:<origin>"       → granted-at, epoch ms (the messaging tier, #121)
 *     "auto:<kind>:<origin>"     → "1" ("always allow": publish, feeds, signing, messaging)
 *     "act:<key>:<origin>"       → granted-at, epoch ms ("always allow" opening ACT
 *                                  shares from one publisher, #540; `<key>` is its
 *                                  compressed key, 66 lower-case hex)
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
    /** One connected site, whether it holds the messaging tier, and its "always allow" kinds ([KINDS]). */
    data class Grant(
        val origin: String,
        val connectedAt: Long,
        val autoApprove: Set<String>,
        val messaging: Boolean = false,
        /** The publishers it may open ACT shares from without asking (#540), most recently allowed first. */
        val act: List<ActRule> = emptyList(),
    )

    /** "Always allow" opening what one publisher shares through ACT: [publicKey] is compressed, 66 lower-case hex. */
    data class ActRule(val publicKey: String, val grantedAt: Long)

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
                val act = map.mapNotNull { (key, at) -> actRule(key, origin, at) }.sortedByDescending { it.grantedAt }
                Grant(origin, (v as? String)?.toLongOrNull() ?: 0L, auto, map["$MESSAGING$origin"] != null, act)
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

    /** Grant connected [origin] the messaging tier; `false` if it isn't connected or the write failed. */
    suspend fun grantMessaging(origin: String, now: Long = System.currentTimeMillis()): Boolean {
        var connected = false
        val written = write {
            connected = it[connectKey(origin)] != null
            if (connected && it[messagingKey(origin)] == null) it[messagingKey(origin)] = now.toString()
        }
        return written && connected
    }

    /** Take [origin]'s messaging tier (and its "always allow" for messages) away; `false` if the write failed. */
    suspend fun revokeMessaging(origin: String): Boolean = write {
        it.remove(messagingKey(origin))
        it.remove(autoKey("messaging", origin))
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

    /**
     * Let connected [origin] open ACT shares from the publisher whose
     * compressed key is [publicKey] without asking (#540); `false` if it
     * isn't connected or the write failed.
     */
    suspend fun allowAct(origin: String, publicKey: String, now: Long = System.currentTimeMillis()): Boolean {
        require(ACT_KEY.matches(publicKey)) { "not a compressed public key" }
        var connected = false
        val written = write {
            connected = it[connectKey(origin)] != null
            if (connected) it[actKey(publicKey, origin)] = now.toString()
        }
        return written && connected
    }

    /** Take [origin]'s ACT rule for [publicKey] away; `false` if the write failed. */
    suspend fun removeAct(origin: String, publicKey: String): Boolean = write { it.remove(actKey(publicKey, origin)) }

    /** Disconnect [origin], dropping its messaging tier, "always allow"s and ACT rules; `false` if the store couldn't be written. */
    suspend fun revoke(origin: String): Boolean = write {
        it.remove(connectKey(origin))
        it.remove(messagingKey(origin))
        for (kind in KINDS) it.remove(autoKey(kind, origin))
        for (key in it.asMap().keys.toList()) {
            if (actRule(key.name, origin, "") != null) it.remove(key)
        }
    }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing Swarm grants failed", e)
        false
    }

    private fun connectKey(origin: String) = stringPreferencesKey("$CONNECT$origin")
    private fun messagingKey(origin: String) = stringPreferencesKey("$MESSAGING$origin")
    private fun autoKey(kind: String, origin: String) = stringPreferencesKey("$AUTO$kind:$origin")
    private fun actKey(publicKey: String, origin: String) = stringPreferencesKey("$ACT$publicKey:$origin")

    /** [name]'s ACT rule if it's one of [origin]'s: the key is fixed-length, so the origin after it is exact. */
    private fun actRule(name: String, origin: String, value: Any?): ActRule? {
        if (!name.startsWith(ACT) || name.length != ACT.length + 67 + origin.length || !name.endsWith(origin)) return null
        val key = name.substring(ACT.length, ACT.length + 66)
        if (!ACT_KEY.matches(key) || name[ACT.length + 66] != ':') return null
        return ActRule(key, (value as? String)?.toLongOrNull() ?: 0L)
    }

    companion object {
        /** The "always allow" kinds, desktop's `autoApprove` keys. */
        val KINDS = listOf("publish", "feeds", "signing", "messaging")

        private const val CONNECT = "connect:"
        private const val MESSAGING = "messaging:"
        private const val AUTO = "auto:"
        private const val ACT = "act:"
        private val ACT_KEY = Regex("^0[23][0-9a-f]{64}$")
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
