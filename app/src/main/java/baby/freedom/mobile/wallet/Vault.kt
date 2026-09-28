package baby.freedom.mobile.wallet

import android.content.Context
import android.os.SystemClock
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The wallet is locked (or there is none): unlock it before asking for the seed. */
class VaultLockedException : IllegalStateException("the wallet is locked")

/** The vault file or its ciphertext can't be read back; only Remove wallet gets out of this. */
class VaultUnreadableException(cause: Throwable? = null) :
    IllegalStateException("the wallet file can't be read", cause)

/**
 * When an unlocked vault locks itself again (#76), as the maintainer
 * decided: after [IDLE_MS] (15 min) with no wallet activity — desktop's
 * auto-lock, kept alive by dApp use through [Vault.noteActivity] — or
 * [BACKGROUND_GRACE_MS] (1 min) after the app left the foreground, so a
 * quick app switch doesn't cost a re-authentication but a phone left
 * behind does. Times are `elapsedRealtime`, which keeps counting through
 * deep sleep and can't be moved by changing the clock.
 */
internal class AutoLockPolicy(
    private val idleMs: Long = IDLE_MS,
    private val graceMs: Long = BACKGROUND_GRACE_MS,
) {
    private var lastActivity = 0L
    private var backgroundedAt: Long? = null

    fun unlocked(now: Long, inForeground: Boolean) {
        lastActivity = now
        backgroundedAt = if (inForeground) null else now
    }

    fun activity(now: Long) {
        lastActivity = maxOf(lastActivity, now)
    }

    fun backgrounded(now: Long) {
        if (backgroundedAt == null) backgroundedAt = now
    }

    fun foregrounded() {
        backgroundedAt = null
    }

    /** The moment the vault must lock, if nothing else happens first. */
    fun deadline(): Long {
        val idle = lastActivity + idleMs
        val grace = backgroundedAt?.let { it + graceMs } ?: Long.MAX_VALUE
        return minOf(idle, grace)
    }

    fun expired(now: Long) = now >= deadline()

    companion object {
        const val IDLE_MS = 15 * 60 * 1000L
        const val BACKGROUND_GRACE_MS = 60 * 1000L
    }
}

/**
 * The one wallet on this device (#75, #76): the BIP-39 recovery phrase,
 * sealed by a Keystore key ([KeystoreVaultStore]) and opened through
 * BiometricPrompt ([VaultAuthenticator]).
 *
 * While unlocked only the 64-byte BIP-39 seed is held, in memory — never
 * the words, and never on disk — so a killed process always comes back
 * [State.Locked]. [lock] zeroes it; [AutoLockPolicy] decides when that
 * happens on its own.
 *
 * What later work builds on:
 *  - [withSeed] — the seed for deriving keys (node identities, #77; the
 *    wallet's accounts), which also counts as activity for the idle lock;
 *  - [revealMnemonic] and [markBackedUp] — "Show recovery phrase" (#78),
 *    which always costs a fresh authentication, and clears the backup
 *    reminder once the phrase has been seen;
 *  - [requireUnlocked] — the lazy entry point: whatever first needs an
 *    identity (a wallet action, publishing, a dApp) calls it, and the
 *    browser opens the wallet page to create, import or unlock one. The
 *    user can always say Not now, and browsing never needs a wallet.
 */
class Vault internal constructor(
    private val store: VaultStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Non-secret facts about the stored vault, for the wallet page. */
    data class Info(
        val protection: VaultProtection,
        val strongBox: Boolean,
        /** False until the user has seen the phrase (#78): the backup reminder shows till then. */
        val backedUp: Boolean,
    )

    sealed interface State {
        data object Empty : State
        data class Locked(val info: Info) : State
        data class Unlocked(val info: Info) : State

        /** A vault file is there but isn't one this app can read. */
        data object Unreadable : State
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Guards [seed], [policy] and [inForeground]. */
    private val lock = Any()
    private var seed: ByteArray? = null
    private val policy = AutoLockPolicy()
    private var inForeground = true
    private var lockJob: Job? = null

    /** Create, import, unlock, reveal and remove run one at a time. */
    private val ops = Mutex()

    private fun initialState(): State {
        val record = store.read()
        return when {
            record != null -> State.Locked(record.info())
            store.exists() -> State.Unreadable
            else -> State.Empty
        }
    }

    /** Whether [create] can make an authentication-bound key; false shows the no-screen-lock warning. */
    fun deviceSecure(): Boolean = store.deviceSecure()

    /**
     * Seals [mnemonic] as the device's one wallet and leaves it unlocked.
     * A phrase created here has not been backed up yet; an [imported]
     * one evidently has, so it gets no backup reminder.
     */
    suspend fun create(mnemonic: Mnemonic, auth: VaultAuthenticator, imported: Boolean) = ops.withLock {
        check(_state.value == State.Empty) { "a wallet already exists" }
        val protection = if (store.deviceSecure()) VaultProtection.SCREEN_LOCK else VaultProtection.DEVICE_ONLY
        try {
            val sealing = withContext(io) { store.newSealingCipher(protection) }
            val cipher = if (protection == VaultProtection.SCREEN_LOCK) {
                auth.authenticate(sealing.cipher, if (imported) VaultAuthPurpose.IMPORT else VaultAuthPurpose.CREATE)
            } else {
                sealing.cipher
            }
            val iv = cipher.iv
            val plain = mnemonic.phrase().toByteArray(Charsets.UTF_8)
            val sealed = try {
                cipher.doFinal(plain)
            } finally {
                plain.fill(0)
            }
            val record = VaultRecord(protection, sealing.strongBox, iv, sealed, backedUp = imported)
            val derived = withContext(compute) { mnemonic.seed() }
            withContext(io) { store.write(record) }
            open(derived, record)
        } catch (t: Throwable) {
            // Nothing half-made stays behind: no key without a file, no file without a key.
            withContext(NonCancellable + io) { store.wipe() }
            throw t
        }
    }

    /** Asks the user (unless the vault is [VaultProtection.DEVICE_ONLY]) and opens the vault. */
    suspend fun unlock(auth: VaultAuthenticator) = ops.withLock {
        if (_state.value is State.Unlocked) return@withLock
        val record = storedRecord()
        val mnemonic = openMnemonic(record, auth, VaultAuthPurpose.UNLOCK)
        val derived = withContext(compute) { mnemonic.seed() }
        open(derived, record)
    }

    /**
     * The recovery phrase itself, for "Show recovery phrase" (#78). Always
     * asks the user again, locked or not — the words are never kept.
     * Show it only on a `FLAG_SECURE` screen ([baby.freedom.mobile.browser.SecureWindow]).
     */
    suspend fun revealMnemonic(auth: VaultAuthenticator): Mnemonic = ops.withLock {
        openMnemonic(storedRecord(), auth, VaultAuthPurpose.REVEAL)
    }

    /** The user has seen the phrase (#78): the backup reminder goes. */
    suspend fun markBackedUp() = ops.withLock {
        val record = storedRecord()
        if (record.backedUp) return@withLock
        val updated = record.withBackedUp(true)
        withContext(io) { store.write(updated) }
        // Under the seed lock, and keyed on the seed rather than the state
        // read before it: an auto-lock landing meanwhile must not be undone.
        synchronized(lock) {
            _state.value = if (seed != null) State.Unlocked(updated.info()) else State.Locked(updated.info())
        }
    }

    /** Forgets the seed. The vault stays on the device. */
    fun lock() {
        synchronized(lock) {
            seed?.fill(0)
            seed = null
            lockJob?.cancel()
            lockJob = null
            val s = _state.value
            if (s is State.Unlocked) _state.value = State.Locked(s.info)
        }
    }

    /**
     * Remove wallet: deletes the sealed phrase and its key. There is no
     * undo; only the recovery phrase brings the wallet back.
     */
    suspend fun remove() = ops.withLock {
        lock()
        // Empty is set inside the non-cancellable block: once the file and
        // key are gone the state must say so, even if the caller's scope
        // (the Wallet page) was cancelled meanwhile and the resume throws.
        withContext(NonCancellable + io) {
            store.wipe()
            _state.value = State.Empty
        }
    }

    /**
     * Runs [block] with the 64-byte BIP-39 seed (a copy, zeroed after) and
     * counts it as wallet activity. Throws [VaultLockedException] if the
     * vault isn't unlocked — or has just run out its auto-lock time. Keep
     * whatever [block] derives scoped to the one operation.
     */
    fun <T> withSeed(block: (ByteArray) -> T): T {
        val copy = synchronized(lock) {
            lockIfExpiredLocked()
            val s = seed ?: throw VaultLockedException()
            policy.activity(clock())
            reschedule()
            s.copyOf()
        }
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    /** dApp or wallet activity: keeps an unlocked vault from idling out, as on desktop. */
    fun noteActivity() {
        synchronized(lock) {
            if (seed == null) return
            lockIfExpiredLocked()
            if (seed == null) return
            policy.activity(clock())
            reschedule()
        }
    }

    /** The app left the foreground: the [AutoLockPolicy.BACKGROUND_GRACE_MS] clock starts. */
    fun onAppBackground() {
        synchronized(lock) {
            inForeground = false
            if (seed == null) return
            policy.backgrounded(clock())
            reschedule()
        }
    }

    /** Back in front: lock if the grace (or the idle time) ran out while away, else carry on. */
    fun onAppForeground() {
        synchronized(lock) {
            inForeground = true
            if (seed == null) return
            lockIfExpiredLocked()
            policy.foregrounded()
            if (seed != null) reschedule()
        }
    }

    /**
     * Whether the seed is available right now: false if the vault is locked
     * or its auto-lock deadline has passed (the timer can run late in deep
     * sleep), in which case it locks now. What [withSeed] would find.
     */
    fun unlockedNow(): Boolean = synchronized(lock) {
        lockIfExpiredLocked()
        seed != null
    }

    /** Locks now if the auto-lock deadline has passed (the timer can run late in deep sleep). */
    fun lockIfExpired() {
        synchronized(lock) { lockIfExpiredLocked() }
    }

    private fun lockIfExpiredLocked() {
        if (seed != null && policy.expired(clock())) lock()
    }

    private fun reschedule() {
        lockJob?.cancel()
        val wait = (policy.deadline() - clock()).coerceAtLeast(0)
        lockJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                if (policy.expired(clock())) lock() else reschedule()
            }
        }
    }

    private fun open(derived: ByteArray, record: VaultRecord) {
        synchronized(lock) {
            seed?.fill(0)
            seed = derived
            policy.unlocked(clock(), inForeground)
            _state.value = State.Unlocked(record.info())
            reschedule()
        }
    }

    private fun storedRecord(): VaultRecord = when (_state.value) {
        State.Empty -> throw IllegalStateException("there is no wallet")
        State.Unreadable -> throw VaultUnreadableException()
        else -> store.read() ?: throw VaultUnreadableException()
    }

    private suspend fun openMnemonic(record: VaultRecord, auth: VaultAuthenticator, purpose: VaultAuthPurpose): Mnemonic {
        val cipher: Cipher = withContext(io) { store.openingCipher(record) }
        val authed = if (record.protection == VaultProtection.SCREEN_LOCK) auth.authenticate(cipher, purpose) else cipher
        val plain = try {
            authed.doFinal(record.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw VaultUnreadableException(e)
        }
        return try {
            Mnemonic.parse(String(plain, Charsets.UTF_8))
        } catch (e: Mnemonic.ParseException) {
            throw VaultUnreadableException(e)
        } finally {
            plain.fill(0)
        }
    }

    // ---- Lazy setup (maintainer decision 1) ----

    /**
     * The features waiting for an unlocked wallet, while the wallet page
     * is up for them. [reasons] says who is asking and why, one line per
     * distinct caller, in the page's banner. Every concurrent
     * [requireUnlocked] shares one answer; a caller that joins with a new
     * reason gets a fresh [SetupRequest] over the same answer, so the
     * banner names it too.
     */
    class SetupRequest internal constructor(
        val reasons: List<String>,
        internal val waiting: Waiting,
    ) {
        /** The reasons as the banner shows them. */
        val reason: String get() = reasons.joinToString("\n")

        /** The wallet page is done: [unlocked] if the user created, imported or unlocked one. */
        fun finish(unlocked: Boolean) {
            waiting.result.complete(unlocked)
        }
    }

    /** One shared answer, and how many [requireUnlocked] calls still await it (guarded by the vault's setup lock). */
    internal class Waiting {
        val result = CompletableDeferred<Boolean>()
        var callers = 0
    }

    private val setupLock = Any()
    private val _setupRequest = MutableStateFlow<SetupRequest?>(null)

    /** The pending [requireUnlocked] calls the browser should open the wallet page for, if any. */
    val setupRequest: StateFlow<SetupRequest?> = _setupRequest.asStateFlow()

    /**
     * Returns true once the vault is unlocked — at once if it already is,
     * otherwise after the user creates, imports or unlocks one on the
     * wallet page opened for [reason]. False if they chose Not now.
     * Callers then carry on without an identity.
     *
     * Concurrent callers share the page and its answer. One of them being
     * cancelled doesn't close the page on the others: it stays up until
     * it's answered or the last caller still waiting goes away.
     */
    suspend fun requireUnlocked(reason: String): Boolean {
        if (unlockedNow()) return true
        val waiting = synchronized(setupLock) {
            val current = _setupRequest.value?.takeIf { !it.waiting.result.isCompleted }
            val next = when {
                current == null -> SetupRequest(listOf(reason), Waiting())
                reason in current.reasons -> current
                else -> SetupRequest(current.reasons + reason, current.waiting)
            }
            next.waiting.callers++
            _setupRequest.value = next
            next.waiting
        }
        return try {
            waiting.result.await()
        } finally {
            synchronized(setupLock) {
                waiting.callers--
                val current = _setupRequest.value
                if (current?.waiting === waiting && (waiting.callers == 0 || waiting.result.isCompleted)) {
                    _setupRequest.value = null
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: Vault? = null

        fun get(context: Context): Vault = instance ?: synchronized(this) {
            instance ?: Vault(
                store = KeystoreVaultStore(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ).also { instance = it }
        }
    }
}

private fun VaultRecord.info() = Vault.Info(protection, strongBox, backedUp)
