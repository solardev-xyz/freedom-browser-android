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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * Remembered per-site permission decisions (#81) — the "remember this
 * decision" half of the site-permission prompt. Session-only answers
 * never reach this store; they live in
 * [baby.freedom.mobile.browser.PermissionSession].
 *
 * Backed by its own [DataStore] (`freedom_site_permissions`), one
 * string entry per decision:
 *
 *     "<origin>|<permission>" → "allow" | "deny"
 *
 * where `<origin>` is a normalized `scheme://host[:port]` (see
 * [baby.freedom.mobile.browser.permissionOriginKey]; never contains
 * `|`) and `<permission>` the storage key shared with the desktop
 * browser (`camera`, `microphone`, `geolocation`). Values are kept as
 * plain strings so this layer stays free of browser types.
 *
 * Never throws for storage trouble: a file that can't be read reads as
 * empty (the site is simply asked again) and a failed write reports
 * `false`, so a broken preferences file can't crash a permission
 * request or leave it unanswered. A corrupt file is replaced with an
 * empty one rather than failing every read.
 */
class SitePermissionStore internal constructor(
    private val store: DataStore<Preferences>,
    /** First back-off before re-reading after a failed read; doubles up to 32×. */
    private val readRetryMs: Long = 1_000,
) {
    /** One stored decision. */
    data class Record(val origin: String, val permission: String, val decision: String)

    /**
     * Every stored decision, sorted by origin then permission.
     *
     * A failed read emits "none" and then re-subscribes (with back-off)
     * instead of completing: DataStore's `data` ends at the first error,
     * and a long-lived collector (Settings) would otherwise stay stuck
     * on the empty list through every later successful write.
     */
    val all: Flow<List<Record>> = store.data.retryWhen { e, attempt ->
        if (e !is IOException) return@retryWhen false
        Log.w(TAG, "reading site permissions failed; treating as none", e)
        emit(emptyPreferences())
        delay(readRetryMs shl attempt.coerceAtMost(5).toInt())
        true
    }.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) ->
            val name = k.name
            if (!name.startsWith(PREFIX)) return@mapNotNull null
            val body = name.removePrefix(PREFIX)
            val sep = body.lastIndexOf('|')
            if (sep <= 0 || sep == body.lastIndex) return@mapNotNull null
            Record(body.substring(0, sep), body.substring(sep + 1), v as? String ?: return@mapNotNull null)
        }.sortedWith(compareBy({ it.origin }, { it.permission }))
    }

    /** Stored decisions for [origin], permission key → decision. */
    suspend fun decisionsFor(origin: String): Map<String, String> =
        all.first().filter { it.origin == origin }.associate { it.permission to it.decision }

    /** Store a decision; `false` if it couldn't be written. */
    suspend fun set(origin: String, permission: String, decision: String): Boolean =
        write { it[keyOf(origin, permission)] = decision }

    /** Forget a decision; `false` if the store couldn't be written. */
    suspend fun remove(origin: String, permission: String): Boolean =
        write { it.remove(keyOf(origin, permission)) }

    private suspend fun write(
        change: (MutablePreferences) -> Unit,
    ): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing site permissions failed", e)
        false
    }

    private fun keyOf(origin: String, permission: String) =
        stringPreferencesKey("$PREFIX$origin|$permission")

    companion object {
        private const val PREFIX = "perm:"
        private const val TAG = "SitePermissionStore"

        private val Context.sitePermissionStore by preferencesDataStore(
            name = "freedom_site_permissions",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: SitePermissionStore? = null

        fun get(context: Context): SitePermissionStore =
            instance ?: synchronized(this) {
                instance ?: SitePermissionStore(
                    context.applicationContext.sitePermissionStore,
                ).also { instance = it }
            }
    }
}
