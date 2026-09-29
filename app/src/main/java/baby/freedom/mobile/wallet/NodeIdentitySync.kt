package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
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
 *    aren't this vault's — none yet, another vault's, or unreadable —
 *    derive them from the seed and store them: [Change.Adopted]. An
 *    unlock of the same vault finds them already there and does nothing,
 *    so the nodes only restart when the identity really changes. (A
 *    wallet made before #77 gets its identities on its first unlock.)
 *  - The wallet is removed: wipe them, [Change.Dropped].
 *
 * Each change goes to the [setOnChanged] listener — the activity
 * restarts the Swarm node with it — and to [notices], which the browser
 * shows. The `:node`
 * process only ever reads the store, and only keys tagged with the
 * vault still on the device, so a crash between the vault and the store
 * being updated can't leave a node running as a removed wallet.
 */
class NodeIdentitySync internal constructor(
    private val vault: Vault,
    private val store: NodeIdentityStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Change {
        /** The nodes now use the wallet's identities; [swarmAddress] is the Swarm account. */
        data class Adopted(val swarmAddress: String) : Change

        /** The wallet is gone; the nodes are back to their own identities. */
        data object Dropped : Change
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
        } ?: return null
        runCatching { onChanged.get()?.invoke(change) }
        _notices.trySend(change)
        return change
    }

    private fun adopt(): Change? {
        val tag = vault.identityTag() ?: return null
        store.read(tag)?.let {
            it.wipe()
            return null
        }
        val identity = try {
            vault.withSeed { NodeIdentity.derive(it) }
        } catch (_: VaultLockedException) {
            // Locked again meanwhile; the next unlock tries again.
            return null
        }
        return try {
            store.write(tag, identity)
            Change.Adopted(identity.swarmAddress)
        } finally {
            identity.wipe()
        }
    }

    private fun drop(): Change? {
        if (store.isEmpty()) return null
        store.wipe()
        return Change.Dropped
    }

    companion object {
        private const val TAG = "NodeIdentitySync"

        @Volatile
        private var instance: NodeIdentitySync? = null

        fun get(context: Context): NodeIdentitySync = instance ?: synchronized(this) {
            instance ?: NodeIdentitySync(
                vault = Vault.get(context),
                store = NodeIdentityStore.get(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
    }
}
