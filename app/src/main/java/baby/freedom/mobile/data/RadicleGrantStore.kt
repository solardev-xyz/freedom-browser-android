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
 *     "grant:<origin>" → "connection" | "signing"
 *
 * `connection` is the first tier (the site may see node status and the
 * seeded list, and ask to seed / unseed); `signing` adds the second
 * (the user's Radicle identity, and writing issues and comments as the
 * user). `<origin>` is the provider's origin key (a normalized
 * `scheme://host[:port]`, the one site permissions use). A private tab
 * never reads or writes here.
 *
 * Never throws for storage trouble: an unreadable file reads as "no
 * grants" (the site asks again), a failed write reports `false`, and a
 * corrupt file is replaced with an empty one.
 */
class RadicleGrantStore internal constructor(private val store: DataStore<Preferences>) {
    /** One site's grant. */
    data class Grant(val origin: String, val signing: Boolean)

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
                if (tier != CONNECTION && tier != SIGNING) return@mapNotNull null
                Grant(name.removePrefix(PREFIX), tier == SIGNING)
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
        if (it[keyOf(origin)] != SIGNING) it[keyOf(origin)] = CONNECTION
    }

    /** Give connected [origin] the signing tier; `false` if it isn't connected or the write failed. */
    suspend fun grantSigning(origin: String): Boolean {
        var connected = false
        val written = write {
            connected = it[keyOf(origin)] != null
            if (connected) it[keyOf(origin)] = SIGNING
        }
        return written && connected
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
        private const val SIGNING = "signing"
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
