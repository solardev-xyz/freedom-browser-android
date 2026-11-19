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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Which sites may use the `window.radicle` provider (#124), and how far:
 * desktop's `radicle-permissions.js`, iOS's `RadiclePermissionStore`.
 *
 *     "grant:<origin>" → "connection" | "signing:<did>" | "writes:<did>"
 *     "autowrites:<origin>" → when its rule last wrote without asking
 *
 * `connection` is the first tier (the site may see node status and the
 * seeded list, and ask to seed / unseed); `signing` adds the second
 * (the user's Radicle identity, and writing issues and comments as the
 * user) — for the one identity `<did>` the user allowed (#328): the node
 * runs as the wallet's or as the device's own, and a site allowed to act
 * as one of them asks again before it learns or writes as the other. A
 * bare `signing` (from before #328) names no identity, so it counts as
 * `connection` — one that could sign as the device's own identity, the
 * only one the node ran as then, so a prompt for the wallet's says the
 * two can be linked. `<origin>` is the provider's origin key (a normalized
 * `scheme://host[:port]`, the one site permissions use). A private tab
 * never reads or writes here.
 *
 * `writes:<did>` is `signing:<did>` plus the site's "always allow writes"
 * rule (#544, desktop's `autoApprove.signing`): its issues, comments and
 * state changes go out without a prompt each, at most
 * [MAX_AUTO_WRITES_PER_DAY] in any 24 hours ([countAutoWrite], whose
 * times are kept so a restart doesn't hand the site a fresh day). The
 * rule lives in the grant's own value, so anything that changes or drops
 * the grant — [revoke], [dropSigning], a [grantSigning] for another
 * identity — drops the rule with it.
 *
 * Never throws for storage trouble: an unreadable file reads as "no
 * grants" (the site asks again), a failed write reports `false`, and a
 * corrupt file is replaced with an empty one.
 */
class RadicleGrantStore internal constructor(private val store: DataStore<Preferences>) {
    /**
     * One site's grant: [signingAs] is the DID it may sign as, or null for
     * the connection tier only. [signedAs] is the DID a connection-tier
     * site could sign as before [dropSigning] took that back (the Radicle
     * identity changed), so its next signing prompt can say the identity
     * is a different one; null if it never could. `""` for a site allowed
     * to sign before #328, when grants named no DID: it could act as the
     * device's own identity (the only one the node ran as then), whose DID
     * wasn't recorded.
     */
    data class Grant(
        val origin: String,
        val signingAs: String? = null,
        val signedAs: String? = null,
        /** Its writes as [signingAs] go out without a prompt each (#544). */
        val autoWrites: Boolean = false,
    ) {
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
                parse(name.removePrefix(PREFIX), v as? String ?: return@mapNotNull null)
            }.sortedBy { it.origin }
        }

    private fun parse(origin: String, tier: String): Grant? = when {
        tier == CONNECTION -> Grant(origin)
        // Before #328 the node only ever ran as the device's own identity.
        tier == SIGNING || tier == SIGNED_DEVICE -> Grant(origin, signedAs = "")
        tier.startsWith(SIGNING_AS) && tier.length > SIGNING_AS.length ->
            Grant(origin, signingAs = tier.removePrefix(SIGNING_AS))
        tier.startsWith(WRITES_AS) && tier.length > WRITES_AS.length ->
            Grant(origin, signingAs = tier.removePrefix(WRITES_AS), autoWrites = true)
        tier.startsWith(SIGNED_AS) && tier.length > SIGNED_AS.length ->
            Grant(origin, signedAs = tier.removePrefix(SIGNED_AS))
        else -> null
    }

    /** [origin]'s grant, or null if it has none (or the store can't be read). */
    suspend fun grantFor(origin: String): Grant? = try {
        all.first().firstOrNull { it.origin == origin }
    } catch (e: Exception) {
        Log.w(TAG, "reading Radicle grant failed", e)
        null
    }

    /** Connect [origin] (keeping a grant it already has); `false` if it couldn't be written. */
    suspend fun connect(origin: String): Boolean = write {
        val tier = it[keyOf(origin)]
        if (tier == null || parse(origin, tier) == null) it[keyOf(origin)] = CONNECTION
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
            val tier = it[keyOf(origin)]
            connected = tier != null
            // Already allowed as [did]: keep its rule. Any other identity
            // starts without one.
            if (connected && tier != WRITES_AS + did) it[keyOf(origin)] = SIGNING_AS + did
        }
        return written && connected
    }

    /**
     * Turn on [origin]'s "always allow writes" rule — only while it may
     * sign as [did], checked in the same edit [revoke] makes, so a site
     * disconnected (or moved to another identity) while its prompt was up
     * doesn't get one. `false` if it may not, or the write failed.
     */
    suspend fun allowAutoWrites(origin: String, did: String): Boolean {
        if (did.isEmpty()) return false
        var signing = false
        val written = write {
            val tier = it[keyOf(origin)]
            signing = tier == SIGNING_AS + did || tier == WRITES_AS + did
            if (signing) it[keyOf(origin)] = WRITES_AS + did
        }
        return written && signing
    }

    /**
     * Turn [origin]'s rule off, keeping its grant; `false` if the store
     * couldn't be written. Not cancellable: the user may leave the page
     * the moment they tap Ask each time.
     */
    suspend fun stopAutoWrites(origin: String): Boolean = withContext(NonCancellable) {
        write {
            val tier = it[keyOf(origin)]
            if (tier != null && tier.startsWith(WRITES_AS)) it[keyOf(origin)] = SIGNING_AS + tier.removePrefix(WRITES_AS)
        }
    }

    /**
     * Count one write [origin]'s rule answers at [now] (wall clock, ms), if
     * fewer than [MAX_AUTO_WRITES_PER_DAY] were counted in the 24 hours
     * before; `false` (the write needs a prompt) otherwise, or if the count
     * couldn't be saved. A time that isn't provably more than a day old —
     * one ahead of [now], after the clock was set back — still counts.
     */
    suspend fun countAutoWrite(origin: String, now: Long): Boolean {
        var counted = false
        val written = write {
            val key = autoWritesKey(origin)
            val recent = autoWriteTimes(it[key]).filterNot { t -> now - t >= DAY_MS }
            counted = recent.size < MAX_AUTO_WRITES_PER_DAY
            val kept = if (counted) recent + now else recent
            if (kept.isEmpty()) it.remove(key) else it[key] = kept.joinToString(",")
        }
        return written && counted
    }

    /**
     * Take every site back to the connection tier: the user's Radicle
     * identity changed (#328 — the node now runs as the wallet's, or as
     * its own again), and a site allowed to know and write as the old one
     * must ask before it learns or writes as the new one. `false` if the
     * store couldn't be written. (Each grant names its identity anyway, so
     * one this misses still isn't honored for another; this also clears
     * the old identity's grants for good.) Each site keeps the DID it
     * could sign as ([Grant.signedAs]), for its next prompt to name.
     */
    suspend fun dropSigning(): Boolean = write { prefs ->
        prefs.asMap().forEach { (k, v) ->
            if (!k.name.startsWith(PREFIX) || v !is String) return@forEach
            when {
                v == SIGNING -> prefs[stringPreferencesKey(k.name)] = SIGNED_DEVICE
                v.startsWith(SIGNING_AS) -> prefs[stringPreferencesKey(k.name)] = SIGNED_AS + v.removePrefix(SIGNING_AS)
                v.startsWith(WRITES_AS) -> prefs[stringPreferencesKey(k.name)] = SIGNED_AS + v.removePrefix(WRITES_AS)
            }
        }
    }

    /** Drop [origin]'s grant, both tiers, its rule and its count; `false` if the store couldn't be written. */
    suspend fun revoke(origin: String): Boolean = write {
        it.remove(keyOf(origin))
        it.remove(autoWritesKey(origin))
    }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing Radicle grants failed", e)
        false
    }

    private fun keyOf(origin: String) = stringPreferencesKey("$PREFIX$origin")

    private fun autoWritesKey(origin: String) = stringPreferencesKey("$AUTO_WRITES$origin")

    private fun autoWriteTimes(value: String?): List<Long> =
        value?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    companion object {
        private const val PREFIX = "grant:"
        private const val CONNECTION = "connection"
        /**
         * Before #328: signing for no identity in particular — the device's
         * own, the only one there was. Read as the connection tier with
         * [Grant.signedAs] `""`.
         */
        private const val SIGNING = "signing"
        /** [dropSigning]'s answer to [SIGNING]: connection tier, once allowed to sign as the device's own identity. */
        private const val SIGNED_DEVICE = "signed"
        private const val SIGNING_AS = "signing:"
        /** Connection tier, once allowed to sign as the DID that follows ([Grant.signedAs]). */
        private const val SIGNED_AS = "signed:"
        /** [SIGNING_AS] with the "always allow writes" rule on ([Grant.autoWrites]). */
        private const val WRITES_AS = "writes:"
        private const val AUTO_WRITES = "autowrites:"

        /** Desktop's `DAILY_MAX` (`auto-write-limiter.js`). */
        const val MAX_AUTO_WRITES_PER_DAY = 50
        const val DAY_MS = 24 * 60 * 60 * 1000L
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
