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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
 *     "<origin>|<permission>"        → "allow" | "deny"
 *     "<origin>|<permission>|<top>"  → "allow" | "deny"
 *
 * where `<origin>` is a normalized `scheme://host[:port]` (see
 * [baby.freedom.mobile.browser.permissionOriginKey]; never contains
 * `|`) and `<permission>` the storage key shared with the desktop
 * browser (`camera`, `microphone`, `geolocation`). Values are kept as
 * plain strings so this layer stays free of browser types.
 *
 * Decisions are double-keyed (#363) by the requesting origin and the
 * top-level site it asked from ([Record.top]). A site's own decision
 * (`top == origin`) keeps the plain two-part key — so every decision
 * stored before #363 reads, unchanged, as the pair (origin, origin):
 * the migration needs no rewrite, and the key stays the one the desktop
 * browser uses. A frame embedded in another site gets the three-part
 * key with that site's origin last, which an older build reads as an
 * unknown permission and ignores rather than applying it to the frame's
 * origin everywhere.
 *
 * Never throws for storage trouble: a file that can't be read reads as
 * empty (the site is simply asked again) and a failed write reports
 * `false`, so a broken preferences file can't crash a permission
 * request or leave it unanswered. A corrupt file is replaced with an
 * empty one rather than failing every read.
 */
class SitePermissionStore internal constructor(
    private val store: DataStore<Preferences>,
    /**
     * First back-off before re-reading after a failed read; doubles per
     * consecutive failure up to 32×, and resets after a successful read.
     */
    private val readRetryMs: Long = 1_000,
    /** How the back-off waits; replaced in tests to record the delays. */
    private val backOff: suspend (Long) -> Unit = { delay(it) },
) {
    /** One stored decision; [top] is the site it was made on ([origin] for the site's own). */
    data class Record(val origin: String, val permission: String, val decision: String, val top: String = origin)

    /**
     * Every stored decision, sorted by top-level site, the site's own
     * first, then by origin and permission.
     *
     * A failed read emits "none" and then re-subscribes (with back-off)
     * instead of completing: DataStore's `data` ends at the first error,
     * and a long-lived collector (Settings) would otherwise stay stuck
     * on the empty list through every later successful write.
     *
     * The back-off counts *consecutive* failures, per collector: any
     * successful read resets it, so failures hours apart each start
     * again from [readRetryMs] (`retryWhen`'s own `attempt` counts every
     * retry over the flow's lifetime and would stay pinned at 32×).
     */
    val all: Flow<List<Record>> = flow {
        var failures = 0
        emitAll(
            store.data
                .onEach { failures = 0 }
                .retryWhen { e, _ ->
                    if (e !is IOException) return@retryWhen false
                    Log.w(TAG, "reading site permissions failed; treating as none", e)
                    emit(emptyPreferences())
                    backOff(readRetryMs shl failures.coerceAtMost(5))
                    failures++
                    true
                },
        )
    }.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) ->
            val name = k.name
            if (!name.startsWith(PREFIX)) return@mapNotNull null
            val parts = name.removePrefix(PREFIX).split('|')
            if (parts.size !in 2..3 || parts.any { it.isEmpty() }) return@mapNotNull null
            val decision = v as? String ?: return@mapNotNull null
            Record(parts[0], parts[1], decision, top = parts.getOrElse(2) { parts[0] })
        }.sortedWith(compareBy({ it.top }, { it.origin != it.top }, { it.origin }, { it.permission }))
    }

    /**
     * Stored decisions for [origin] on the top-level site [top],
     * permission key → decision. Only that exact pair: a frame's request
     * never reads its origin's own decisions, nor the reverse.
     */
    suspend fun decisionsFor(origin: String, top: String = origin): Map<String, String> =
        all.first().filter { it.origin == origin && it.top == top }.associate { it.permission to it.decision }

    /** Store a decision for [origin] on [top]; `false` if it couldn't be written. */
    suspend fun set(origin: String, permission: String, decision: String, top: String = origin): Boolean =
        write { it[keyOf(origin, permission, top)] = decision }

    /** Forget [origin]'s decision on [top]; `false` if the store couldn't be written. */
    suspend fun remove(origin: String, permission: String, top: String = origin): Boolean =
        write { it.remove(keyOf(origin, permission, top)) }

    private suspend fun write(
        change: (MutablePreferences) -> Unit,
    ): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing site permissions failed", e)
        false
    }

    private fun keyOf(origin: String, permission: String, top: String) =
        stringPreferencesKey(storageKey(origin, permission, top))

    companion object {
        private const val PREFIX = "perm:"

        /**
         * The preferences key for [origin]'s decision about [permission]
         * on [top]: the plain `<origin>|<permission>` one for a site's own
         * decision (as stored before #363), `<origin>|<permission>|<top>`
         * for a frame embedded in another site.
         */
        internal fun storageKey(origin: String, permission: String, top: String): String =
            if (top == origin) "$PREFIX$origin|$permission" else "$PREFIX$origin|$permission|$top"
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
