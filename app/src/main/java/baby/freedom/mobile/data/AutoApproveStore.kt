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
import baby.freedom.mobile.browser.AutoApproveRule
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Auto-approve rules (#112) — iOS's `AutoApproveStore`: transactions a
 * connected site may send without a sheet, each scoped to one
 * (origin, contract, function selector, chain):
 *
 *     "rule:<origin>|<contract>|<selector>|<chainId>" → {"grantedAt": 1790000000000}
 *
 * The key is [AutoApproveRule.key] (contract and selector lower-case), so
 * granting the same rule twice keeps one. Public data only — never a key.
 * A private tab never reads or writes here (it has no provider).
 *
 * Never throws for storage trouble: an unreadable file reads as `null`
 * in [allOrUnreadable] (and "no rule" in [matches], so the sheet shows),
 * a failed write reports `false`, and a corrupt file is replaced with an
 * empty one.
 */
class AutoApproveStore internal constructor(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Every rule, newest first; null while the file can't be read. */
    val allOrUnreadable: Flow<List<AutoApproveRule>?> = store.data
        .map<Preferences, Preferences?> { it }
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading auto-approve rules failed", e)
            emit(null)
        }
        .map { prefs ->
            prefs?.asMap()?.mapNotNull { (k, v) ->
                val name = k.name
                if (!name.startsWith(PREFIX)) return@mapNotNull null
                decode(name.removePrefix(PREFIX), v as? String ?: return@mapNotNull null)
            }?.sortedWith(compareByDescending<AutoApproveRule> { it.grantedAt ?: 0L }.thenBy { it.key })
        }

    /** Whether [rule]'s scope has been granted; false while the store can't be read. */
    suspend fun matches(rule: AutoApproveRule): Boolean =
        allOrUnreadable.first()?.any { it.key == rule.key } == true

    /** Grant [rule], as of now (kept as first granted if it already is); `false` if it couldn't be written. */
    suspend fun grant(rule: AutoApproveRule): Boolean {
        val now = clock()
        return write { prefs ->
            if (prefs[keyOf(rule)] == null) prefs[keyOf(rule)] = encode(now)
        }
    }

    /** Drop [rule]; `false` if the store couldn't be written. */
    suspend fun revoke(rule: AutoApproveRule): Boolean = write { it.remove(keyOf(rule)) }

    /**
     * Drop every rule of [origin] (the site was disconnected); `false` if the store couldn't be written.
     * Matched on the key's origin part, not on [decodeKey], so a rule no longer valid (one granted
     * for a function refused since, #234) goes with its site too rather than lingering unseen.
     */
    suspend fun revokeOrigin(origin: String): Boolean = write { prefs ->
        prefs.asMap().keys
            .filter { it.name.startsWith(PREFIX) && it.name.removePrefix(PREFIX).split('|').let { p -> p.size == 4 && p[0] == origin } }
            .forEach { prefs.remove(it) }
    }

    /** Drop every rule (the wallet was removed); `false` if the store couldn't be written. */
    suspend fun clear(): Boolean = write { prefs ->
        prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { prefs.remove(it) }
    }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing auto-approve rules failed", e)
        false
    }

    private fun keyOf(rule: AutoApproveRule) = stringPreferencesKey("$PREFIX${rule.key}")

    companion object {
        private const val PREFIX = "rule:"
        private const val TAG = "AutoApproveStore"

        internal fun encode(grantedAt: Long): String = JSONObject().put("grantedAt", grantedAt).toString()

        /** The rule a stored key names, or null for one that isn't a valid rule (skipped, never matched). */
        internal fun decodeKey(key: String): AutoApproveRule? {
            // The origin is `scheme://host[:port]`, which holds no `|`.
            val parts = key.split('|')
            if (parts.size != 4) return null
            val chainId = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return AutoApproveRule.of(parts[0], parts[1], parts[2], chainId)?.takeIf { it.key == key }
        }

        internal fun decode(key: String, json: String): AutoApproveRule? {
            val rule = decodeKey(key) ?: return null
            val grantedAt = try {
                JSONObject(json).optLong("grantedAt", -1).takeIf { it > 0 }
            } catch (e: Exception) {
                null
            }
            return rule.copy(grantedAt = grantedAt)
        }

        private val Context.autoApproveStore by preferencesDataStore(
            name = "freedom_dapp_auto_approve",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: AutoApproveStore? = null

        fun get(context: Context): AutoApproveStore =
            instance ?: synchronized(this) {
                instance ?: AutoApproveStore(context.applicationContext.autoApproveStore).also { instance = it }
            }
    }
}
