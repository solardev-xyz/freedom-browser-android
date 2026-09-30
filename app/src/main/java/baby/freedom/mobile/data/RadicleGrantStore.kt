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
 * Which sites may use the `window.radicle` provider (#124), and how far:
 * desktop's `radicle-permissions.js`, iOS's `RadiclePermissionStore`.
 *
 *     "grant:<origin>" → "connection" | "signing:<did>"
 *
 * `connection` is the first tier (the site may see node status and the
 * seeded list, and ask to seed / unseed); `signing` adds the second
 * (the user's Radicle identity, and writing issues and comments as the
 * user) — for the one identity `<did>` the user allowed (#328): the node
 * runs as the wallet's or as the device's own, and a site allowed to act
 * as one of them asks again before it learns or writes as the other. A
 * bare `signing` (from before #328) names no identity, so it counts as
 * `connection`. `<origin>` is the provider's origin key (a normalized
 * `scheme://host[:port]`, the one site permissions use). A private tab
 * never reads or writes here.
 *
 * Never throws for storage trouble: an unreadable file reads as "no
 * grants" (the site asks again), a failed write reports `false`, and a
 * corrupt file is replaced with an empty one.
 */
class RadicleGrantStore internal constructor(private val store: DataStore<Preferences>) {
    /** One site's grant: [signingAs] is the DID it may sign as, or null for the connection tier only. */
    data class Grant(val origin: String, val signingAs: String? = null) {
        /** It may sign as some identity (maybe not the node's current one). */
        val signing: Boolean get() = signingAs != null

        /** It may sign as [did], the identity the node runs as now. */
        fun signsAs(did: String): Boolean = did.isNotEmpty() && signingAs == did
    }

    /** Every grant, by origin. */
    val all: Flow<List<Grant>> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading Radicle grants failed; treating as none", e)
            emit(emptyPreferences())
        }
        .map { prefs ->
            prefs.asMap().mapNotNull { (k, v) ->
                val name = k.name
                if (!name.startsWith(PREFIX)) return@mapNotNull null
                val tier = v as? String ?: return@mapNotNull null
                val signingAs = when {
                    tier == CONNECTION || tier == SIGNING -> null
                    tier.startsWith(SIGNING_AS) && tier.length > SIGNING_AS.length -> tier.removePrefix(SIGNING_AS)
                    else -> return@mapNotNull null
                }
                Grant(name.removePrefix(PREFIX), signingAs)
            }.sortedBy { it.origin }
        }

    /** [origin]'s grant, or null if it has none (or the store can't be read). */
    suspend fun grantFor(origin: String): Grant? = try {
        all.first().firstOrNull { it.origin == origin }
    } catch (e: Exception) {
        Log.w(TAG, "reading Radicle grant failed", e)
        null
    }

    /** Connect [origin] (keeping a signing grant it already has); `false` if it couldn't be written. */
    suspend fun connect(origin: String): Boolean = write {
        if (it[keyOf(origin)]?.startsWith(SIGNING_AS) != true) it[keyOf(origin)] = CONNECTION
    }

    /**
     * Give connected [origin] the signing tier for the identity [did] (and
     * only it); `false` if it isn't connected, [did] is empty, or the write
     * failed.
     */
    suspend fun grantSigning(origin: String, did: String): Boolean {
        if (did.isEmpty()) return false
        var connected = false
        val written = write {
            connected = it[keyOf(origin)] != null
            if (connected) it[keyOf(origin)] = SIGNING_AS + did
        }
        return written && connected
    }

    /**
     * Take every site back to the connection tier: the user's Radicle
     * identity changed (#328 — the node now runs as the wallet's, or as
     * its own again), and a site allowed to know and write as the old one
     * must ask before it learns or writes as the new one. `false` if the
     * store couldn't be written. (Each grant names its identity anyway, so
     * one this misses still isn't honored for another; this also clears
     * the old identity's grants for good.)
     */
    suspend fun dropSigning(): Boolean = write { prefs ->
        prefs.asMap().forEach { (k, v) ->
            if (k.name.startsWith(PREFIX) && v is String && (v == SIGNING || v.startsWith(SIGNING_AS))) {
                prefs[stringPreferencesKey(k.name)] = CONNECTION
            }
        }
    }

    /** Drop [origin]'s grant, both tiers; `false` if the store couldn't be written. */
    suspend fun revoke(origin: String): Boolean = write { it.remove(keyOf(origin)) }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing Radicle grants failed", e)
        false
    }

    private fun keyOf(origin: String) = stringPreferencesKey("$PREFIX$origin")

    companion object {
        private const val PREFIX = "grant:"
        private const val CONNECTION = "connection"
        /** Before #328: signing for no identity in particular, read as [CONNECTION]. */
        private const val SIGNING = "signing"
        private const val SIGNING_AS = "signing:"
        private const val TAG = "RadicleGrantStore"

        private val Context.radicleGrantStore by preferencesDataStore(
            name = "freedom_radicle_grants",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: RadicleGrantStore? = null

        fun get(context: Context): RadicleGrantStore =
            instance ?: synchronized(this) {
                instance ?: RadicleGrantStore(context.applicationContext.radicleGrantStore)
                    .also { instance = it }
            }
    }
}
