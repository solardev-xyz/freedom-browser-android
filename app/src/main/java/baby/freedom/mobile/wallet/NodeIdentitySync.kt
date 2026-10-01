package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
import baby.freedom.mobile.data.RadicleGrantStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the node identities on disk ([NodeIdentityStore]) in step with
 * the wallet (#77, maintainer decision 10): once a phrase is created or
 * imported the nodes switch to the identities derived from it, and
 * Remove wallet takes them back to their own.
 *
 *  - The wallet opens (create, import, or any unlock) and the stored keys
 *    aren't this vault's — none yet, or another vault's —
 *    derive them from the seed and store them: [Change.Adopted]. (This
 *    vault's keys that can't be opened are derived and sealed again,
 *    [Change.Resealed]: the same identities, so no grants are taken back
 *    and there's no notice — but `:node` is told, since it may have
 *    failed the same read when it booted.) An
 *    unlock of the same vault finds them already there and does nothing,
 *    so the nodes only restart when the identity really changes. (A
 *    wallet made before #77 gets its identities on its first unlock, and
 *    one whose keys were stored before #328 gets its Radicle key then —
 *    its Swarm account stays as it was.)
 *  - The wallet is removed: wipe them, [Change.Dropped].
 *
 * [Change.Adopted] and [Change.Dropped] are a new Radicle identity, so
 * before either is written [beforeRadicleChange] takes sites' Radicle
 * signing grants back (`RadicleGrantStore.dropSigning`), best effort. A
 * site that could read and write as the old identity asks again before
 * it gets the new one either way — each grant names the DID it was given
 * for — and its prompt says the identity changed. [Change.Resealed] is
 * the same identities, so it takes nothing back.
 *
 * Every change goes to the [setOnChanged] listener — the activity has
 * `:node` reload its identities, restarting the Swarm and Radicle nodes
 * that are up as another one — and every change but [Change.Resealed]
 * to [notices], which the browser shows. The `:node`
 * process only ever reads the store, and only keys tagged with the
 * vault still on the device, so a crash between the vault and the store
 * being updated can't leave a node running as a removed wallet.
 */
class NodeIdentitySync internal constructor(
    private val vault: Vault,
    private val store: NodeIdentityStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Run before the Radicle identity changes (see the class comment).
     * Best effort: a failure is logged and the change goes ahead, for an
     * adoption as for a removal. Each grant names the DID it was given
     * for, so one this couldn't take back still isn't honored for the new
     * identity — and a grant-store write failing mustn't keep the Swarm
     * node from adopting the wallet's account (even with Radicle off).
     */
    private val beforeRadicleChange: suspend () -> Unit = {},
) {
    sealed interface Change {
        /**
         * The nodes now use the wallet's identities: [swarmAddress] is the
         * Swarm account, [radicleDid] the Radicle one. [swarmChanged] is
         * false when only the Radicle identity is new (keys stored before
         * #328 for this same wallet), so the Swarm node stays as it is.
         */
        data class Adopted(val swarmAddress: String, val radicleDid: String, val swarmChanged: Boolean = true) : Change

        /**
         * This vault's keys were on disk but couldn't be opened, and are
         * sealed again: the very same identities, so no grants were taken
         * back and there's nothing to tell the user. But `:node` may have
         * hit the same unreadable file when it booted — Radicle and Swarm
         * then both fail their boot rather than run as the device's own
         * key (#357) — so the listener still has it reload; it restarts
         * only a node that's up as another identity, or whose boot failed.
         */
        data object Resealed : Change

        /** The wallet is gone; the nodes are back to their own identities. */
        data object Dropped : Change

        /**
         * The wallet unlocked and its keys were already in place: nothing
         * changed, and nothing is returned or noticed. But `:node`'s Swarm
         * launch may have failed on a read that has since cleared (#357),
         * and no other change would come to retry it — so the listener is
         * still told, and has it reload; that restarts only a node that's
         * up as another identity, or whose boot failed.
         */
        data object Unchanged : Change
    }

    /**
     * Told about every [Change] once it's on disk (on a background
     * thread). Owned by whoever set it last — the current activity — and
     * cleared by it in [clearOnChanged]: this object lives as long as the
     * process, so a listener left behind would keep a finished activity
     * (its views, its WebViews) reachable.
     */
    private val onChanged = AtomicReference<((Change) -> Unit)?>(null)

    /** Makes [listener] the one told about each [Change], replacing any earlier one. */
    fun setOnChanged(listener: (Change) -> Unit) {
        onChanged.set(listener)
    }

    /** Drops [listener] if it's still the current one (a newer owner's stays). */
    fun clearOnChanged(listener: (Change) -> Unit) {
        onChanged.compareAndSet(listener, null)
    }

    private val _notices = Channel<Change>(Channel.BUFFERED)

    /** The changes, for the browser's notice; each is delivered once. */
    val notices: Flow<Change> = _notices.receiveAsFlow()

    private val mutex = Mutex()
    private var started = false

    /** Follows the wallet from now on. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { vault.state.collect { reconcile(it) } }
    }

    /** Brings the store in line with [state]; the change made, if any. Never throws. */
    internal suspend fun reconcile(state: Vault.State): Change? {
        val change = try {
            withContext(io) {
                mutex.withLock {
                    when (state) {
                        is Vault.State.Unlocked -> adopt()
                        Vault.State.Empty -> drop()
                        else -> null
                    }
                }
            }
        } catch (t: Throwable) {
            // Never the keys: only what went wrong.
            Log.w(TAG, "node identity sync failed: ${t.javaClass.simpleName}")
            null
        } ?: run {
            if (state is Vault.State.Unlocked) runCatching { onChanged.get()?.invoke(Change.Unchanged) }
            return null
        }
        runCatching { onChanged.get()?.invoke(change) }
        // Nothing to tell about the same identities sealed again.
        if (change != Change.Resealed) _notices.trySend(change)
        return change
    }

    private suspend fun adopt(): Change? {
        val tag = vault.identityTag() ?: return null
        val stored = store.read(tag)
        // This vault's keys are on disk but couldn't be opened (a Keystore or
        // file hiccup, or a corrupt file): they were derived from this same
        // seed, and derivation is deterministic, so deriving them again gives
        // the very same identities. Sealed again below (healing a corrupt
        // file), but it's no identity change: no grants taken back, no
        // notice — unless the file was version 1, whose Radicle identity
        // really is new. `:node` is still told ([Change.Resealed]): its
        // boot read the same file, and may have failed on it.
        val sameVault = stored == null && store.storedTag() == tag
        val sameRadicle = sameVault && store.storedHasRadicle()
        // Only keys that could be read count as Swarm already adopted: if
        // they couldn't be, `:node`'s Swarm boot may have failed on them
        // (#357) and restarts onto the wallet's account now.
        val hadSwarm = stored != null
        if (stored != null) {
            val complete = stored.radicleKey != null
            stored.wipe()
            if (complete) return null
        }
        val identity = try {
            vault.withSeed { NodeIdentity.derive(it) }
        } catch (_: VaultLockedException) {
            // Locked again meanwhile; the next unlock tries again.
            return null
        }
        return try {
            if (!sameRadicle) takeBackRadicleGrants()
            store.write(tag, identity)
            if (sameRadicle) Change.Resealed else Change.Adopted(identity.swarmAddress, identity.radicleDid.orEmpty(), swarmChanged = !hadSwarm)
        } finally {
            identity.wipe()
        }
    }

    private suspend fun drop(): Change? {
        if (store.isEmpty()) return null
        takeBackRadicleGrants()
        store.wipe()
        return Change.Dropped
    }

    private suspend fun takeBackRadicleGrants() {
        try {
            beforeRadicleChange()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "taking back Radicle signing grants failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "NodeIdentitySync"

        @Volatile
        private var instance: NodeIdentitySync? = null

        fun get(context: Context): NodeIdentitySync = instance ?: synchronized(this) {
            // The lambda below outlives the caller: never let it hold an activity.
            val app = context.applicationContext
            instance ?: NodeIdentitySync(
                vault = Vault.get(context),
                store = NodeIdentityStore.get(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                beforeRadicleChange = {
                    check(RadicleGrantStore.get(app).dropSigning()) { "couldn't take back Radicle signing grants" }
                },
            ).also { instance = it }
        }
    }
}
