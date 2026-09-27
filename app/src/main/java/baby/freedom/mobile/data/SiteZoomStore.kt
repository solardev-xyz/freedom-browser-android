package baby.freedom.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * Remembered per-site page zoom (#88).
 *
 * Backed by its own [DataStore] (`freedom_site_zoom`), one int entry per
 * site that is *not* at the default level:
 *
 *     "zoom:<site>" → percent
 *
 * where `<site>` is the key [baby.freedom.mobile.browser.zoomSiteKey]
 * derives from the page's URL. Resetting a site to the default removes
 * its entry, so the file only ever holds sites the user changed.
 *
 * Never throws for storage trouble: a file that can't be read reads as
 * empty (every site shows at the default) and a failed write is logged
 * and dropped — the level still applies for the rest of the session,
 * it just isn't remembered. A corrupt file is replaced with an empty one.
 */
class SiteZoomStore internal constructor(
    private val store: DataStore<Preferences>,
) {
    /** Every remembered level, site → percent. */
    suspend fun load(): Map<String, Int> = try {
        store.data.first().asMap().mapNotNull { (k, v) ->
            val site = k.name.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
            val level = v as? Int
            if (site.isNullOrEmpty() || level == null) null else site to level
        }.toMap()
    } catch (e: IOException) {
        Log.w(TAG, "reading site zoom failed; treating as none", e)
        emptyMap()
    }

    /** Remember [percent] for [site], or forget it when [percent] is null. */
    suspend fun set(site: String, percent: Int?) {
        try {
            store.edit {
                val key = intPreferencesKey(PREFIX + site)
                if (percent == null) it.remove(key) else it[key] = percent
            }
        } catch (e: IOException) {
            Log.w(TAG, "writing site zoom failed", e)
        }
    }

    /** Forget every remembered level. */
    suspend fun clear() {
        try {
            store.edit { it.clear() }
        } catch (e: IOException) {
            Log.w(TAG, "clearing site zoom failed", e)
        }
    }

    companion object {
        private const val PREFIX = "zoom:"
        private const val TAG = "SiteZoomStore"

        private val Context.siteZoomStore by preferencesDataStore(
            name = "freedom_site_zoom",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: SiteZoomStore? = null

        fun get(context: Context): SiteZoomStore =
            instance ?: synchronized(this) {
                instance ?: SiteZoomStore(
                    context.applicationContext.siteZoomStore,
                ).also { instance = it }
            }
    }
}
