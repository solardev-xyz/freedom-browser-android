package baby.freedom.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
 */
class SitePermissionStore private constructor(
    private val store: DataStore<Preferences>,
) {
    /** One stored decision. */
    data class Record(val origin: String, val permission: String, val decision: String)

    /** Every stored decision, sorted by origin then permission. */
    val all: Flow<List<Record>> = store.data.map { prefs ->
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

    suspend fun set(origin: String, permission: String, decision: String) {
        store.edit { it[keyOf(origin, permission)] = decision }
    }

    suspend fun remove(origin: String, permission: String) {
        store.edit { it.remove(keyOf(origin, permission)) }
    }

    private fun keyOf(origin: String, permission: String) =
        stringPreferencesKey("$PREFIX$origin|$permission")

    companion object {
        private const val PREFIX = "perm:"

        private val Context.sitePermissionStore by preferencesDataStore(
            name = "freedom_site_permissions",
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
