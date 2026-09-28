package baby.freedom.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * Sites the user asked for as desktop sites (#180).
 *
 * Backed by its own [DataStore] (`freedom_desktop_site`), one entry per
 * site that has "Desktop site" on:
 *
 *     "desktop:<site>" → true
 *
 * where `<site>` is the key [baby.freedom.mobile.browser.desktopSiteKey]
 * derives from the page's URL. Turning a site back off removes its
 * entry, so the file only ever holds sites the user switched on.
 *
 * Never throws for storage trouble, like [SiteZoomStore]: a file that
 * can't be read reads as empty (every site is a mobile site) and a
 * failed write is logged and dropped — the choice still applies for the
 * rest of the session, it just isn't remembered. A corrupt file is
 * replaced with an empty one.
 */
class SiteDesktopStore internal constructor(
    private val store: DataStore<Preferences>,
) {
    /** Every site remembered as a desktop site. */
    suspend fun load(): Set<String> = try {
        store.data.first().asMap().mapNotNull { (k, v) ->
            val site = k.name.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
            if (site.isNullOrEmpty() || v != true) null else site
        }.toSet()
    } catch (e: IOException) {
        Log.w(TAG, "reading desktop sites failed; treating as none", e)
        emptySet()
    }

    /** Remember [site] as a desktop site when [desktop], or forget it. */
    suspend fun set(site: String, desktop: Boolean) {
        try {
            store.edit {
                val key = booleanPreferencesKey(PREFIX + site)
                if (desktop) it[key] = true else it.remove(key)
            }
        } catch (e: IOException) {
            Log.w(TAG, "writing desktop site failed", e)
        }
    }

    /** Forget every desktop site. */
    suspend fun clear() {
        try {
            store.edit { it.clear() }
        } catch (e: IOException) {
            Log.w(TAG, "clearing desktop sites failed", e)
        }
    }

    companion object {
        private const val PREFIX = "desktop:"
        private const val TAG = "SiteDesktopStore"

        private val Context.siteDesktopStore by preferencesDataStore(
            name = "freedom_desktop_site",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: SiteDesktopStore? = null

        fun get(context: Context): SiteDesktopStore =
            instance ?: synchronized(this) {
                instance ?: SiteDesktopStore(
                    context.applicationContext.siteDesktopStore,
                ).also { instance = it }
            }
    }
}
