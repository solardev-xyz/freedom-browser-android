package baby.freedom.mobile.wallet

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/** Block Store can't be used here: no Google Play services, or it didn't answer. */
open class BackupUnavailableException(message: String) : Exception(message)

/**
 * Play services didn't answer in time. Unlike a refusal this says nothing
 * about the call itself: a `Task` can't be cancelled, so a write may still
 * land after this was thrown (#244 R1-M2).
 */
class BackupNoAnswerException(message: String = "Google Play services didn’t answer") : BackupUnavailableException(message)

/**
 * Block Store can't end-to-end encrypt a cloud backup on this phone (no
 * screen lock, or no Google account), so nothing is stored.
 */
class BackupNotEncryptedException : Exception("Google can’t end-to-end encrypt the backup on this phone")

/**
 * The Block Store entry isn't one this app wrote. Deliberately carries no
 * cause: a JSON parser's message quotes the text it choked on, which here
 * would be the recovery phrase.
 */
class BackupUnreadableException : Exception("the Google backup can’t be read")

/** A restore found no Block Store entry (deleted meanwhile). */
class BackupMissingException : Exception("there’s no Google backup on this phone")

/** Restoring needs a screen lock: the restored wallet's key must require the user. */
class RestoreNeedsScreenLockException : Exception("set a screen lock first")

/**
 * The few Block Store calls [PhraseBackup] makes, so the logic above them
 * can be tested without Play services. [GmsBlockStore] is the real one.
 * Every call throws [BackupUnavailableException] when Block Store can't
 * be reached.
 */
interface BlockStorePort {
    /** Whether a cloud backup would be end-to-end encrypted (a screen lock and a Google account). */
    suspend fun endToEndEncryptionAvailable(): Boolean

    suspend fun store(key: String, bytes: ByteArray, backupToCloud: Boolean)

    /** The entry under [key], or null when there's none. */
    suspend fun retrieve(key: String): ByteArray?

    suspend fun delete(key: String)
}

/**
 * Whether the entry was last written *by this install*, and for the cloud
 * or not: the entry's own `cloud` field is only what whichever phone wrote
 * it asked for, and Block Store doesn't say what an entry restored onto
 * this phone (device-to-device or from the cloud) is actually flagged
 * with (#244 R1-M1). Null when this install hasn't written it, or a write
 * was left unfinished. Kept out of every Android backup
 * ([Context.getNoBackupFilesDir]): a copy on a new phone would claim a
 * write that phone never made.
 */
interface WrittenHere {
    suspend fun get(): Boolean?
    suspend fun set(cloud: Boolean?)

    class InMemory : WrittenHere {
        @Volatile private var value: Boolean? = null
        override suspend fun get() = value
        override suspend fun set(cloud: Boolean?) {
            value = cloud
        }
    }

    /**
     * One small file, read once and then kept in memory: a file that can't
     * be written (#244 R2-M4) costs one rewrite per app start, not one on
     * every reconcile, and [writeFailed] says so. A file that can't be read
     * reads as "not written here", which costs one rewrite.
     */
    class InFile(private val file: File) : WrittenHere {
        @Volatile private var loaded = false
        @Volatile private var value: Boolean? = null

        /** Whether the last [set] didn't reach the file (the value still holds in memory). */
        @Volatile var writeFailed = false
            private set

        override suspend fun get(): Boolean? {
            if (!loaded) {
                value = withContext(Dispatchers.IO) {
                    runCatching { if (file.exists()) file.readText().trim() else null }.getOrNull()?.let {
                        when (it) {
                            "cloud" -> true
                            "device" -> false
                            else -> null
                        }
                    }
                }
                loaded = true
            }
            return value
        }

        override suspend fun set(cloud: Boolean?) {
            value = cloud
            loaded = true
            writeFailed = !withContext(Dispatchers.IO) {
                runCatching {
                    if (cloud == null) {
                        !file.exists() || file.delete()
                    } else {
                        val tmp = File(file.path + ".tmp")
                        tmp.writeText(if (cloud) "cloud" else "device")
                        // Never leave a stale value behind for the next start to trust.
                        tmp.renameTo(file) || run { file.delete(); false }
                    }
                }.getOrDefault(false)
            }
        }
    }
}

/**
 * Opt-in, end-to-end encrypted backup of the recovery phrase through
 * Google Block Store (#231; decision 4 on #75 made v1 device-only and
 * left this for later).
 *
 * One Block Store entry, [KEY], holds the phrase. It is only ever written
 * with `setShouldBackupToCloud(true)` once Block Store reports end-to-end
 * encryption available: Google then encrypts it on the phone with a key
 * guarded by the screen lock, so what reaches Google is ciphertext it
 * can't open, and restoring it on another phone needs this phone's PIN,
 * pattern or password during that phone's setup. Block Store does *not*
 * refuse a cloud backup when encryption is unavailable — it uploads it
 * without — so [reconcile] rewrites the entry as device-only (no cloud
 * copy; Block Store deletes the one already up there at its next sync)
 * whenever encryption has gone away, e.g. the screen lock was removed,
 * and back to cloud once it returns. The payload records which it was
 * written as and [WrittenHere] whether this install wrote it so, so
 * [reconcile] only rewrites on a change — and always once for an entry
 * that came from another phone, whose flag on this one nothing reports.
 *
 * [reconcile] follows the *entry*, not the wallet: an entry left behind
 * with the wallet removed (its backup kept), or one a new phone received
 * from the old one, is kept end-to-end encrypted the same way. It runs
 * whenever the app comes to the foreground, when the wallet page opens,
 * and — while an entry exists — from [PhraseBackupJob] about once an hour
 * in the background. That narrows, but can't close, the window between
 * the screen lock being removed and the next reconcile: Android sends no
 * broadcast for it, so a Block Store backup landing inside that window
 * (at most about an hour while the app isn't running, longer only if the
 * phone restarted meanwhile and Freedom hasn't been opened since) would
 * still go up without end-to-end encryption.
 *
 * Every write and delete of the entry, and [reconcile]'s read-then-write,
 * runs under one lock, so a reconcile can't write back an entry a
 * concurrent delete (Turn off, Remove wallet) just removed, nor restore
 * an older phrase over one a concurrent [store] just wrote.
 *
 * On the phone Block Store keeps the entry in Google Play services' own
 * storage, which is what lets a reinstall on the same phone restore it.
 *
 * Nothing here logs; the phrase lives in byte arrays that are zeroed
 * after use, apart from the unavoidable [String] inside [Mnemonic].
 */
class PhraseBackup(
    private val blockStore: BlockStorePort,
    /** Where the entry's account address is derived (PBKDF2): off the main thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val writtenHere: WrittenHere = WrittenHere.InMemory(),
) {
    /** Serializes every change to the entry; see the class KDoc. */
    private val writes = Mutex()

    /**
     * What the entry is, as of the last [reconcile], [store] or [delete];
     * null until one has run, or when the last [reconcile] couldn't (Block
     * Store didn't answer, or the entry isn't readable). What the wallet
     * page goes by to say where Google backup holds the phrase, and
     * whether a kept entry is this wallet's.
     */
    data class Known(
        val status: Status,
        /**
         * The checksummed address of account 0 of the entry's phrase
         * (public, like every address the wallet shows), to tell whether
         * the entry is the wallet on this phone; null with no entry.
         */
        val address: String?,
    )

    private val _known = MutableStateFlow<Known?>(null)
    val known: StateFlow<Known?> = _known.asStateFlow()

    enum class Availability {
        /** No Google Play services, or Block Store refused the call. */
        UNSUPPORTED,

        /**
         * Play services is here but didn't answer in time (or is still busy
         * with an earlier call): not the same as not having it (#244 R2-M1).
         */
        NO_ANSWER,

        /** Block Store is here, but can't end-to-end encrypt: no screen lock or no Google account. */
        NOT_ENCRYPTED,

        /** A backup would be end-to-end encrypted. */
        READY,
    }

    /** Where the entry stands after [reconcile]. */
    enum class Status {
        /** No entry. */
        NONE,

        /**
         * Written for backup to the cloud, end-to-end encrypted. Block Store
         * uploads it with the phone's own Google backup, which may be off
         * and which no app can ask about: not proof it left the phone
         * (#244 R5-F1).
         */
        CLOUD,

        /** Kept on this phone only until end-to-end encryption is available again. */
        PAUSED,
    }

    suspend fun availability(): Availability = try {
        if (blockStore.endToEndEncryptionAvailable()) Availability.READY else Availability.NOT_ENCRYPTED
    } catch (_: BackupNoAnswerException) {
        Availability.NO_ANSWER
    } catch (_: BackupUnavailableException) {
        Availability.UNSUPPORTED
    }

    /**
     * Puts [mnemonic] in Block Store for backup to the cloud. Throws
     * [BackupNotEncryptedException] (and stores nothing) unless Block Store
     * says that backup will be end-to-end encrypted.
     */
    suspend fun store(mnemonic: Mnemonic) = writes.withLock { storeHeld(mnemonic) }

    private suspend fun storeHeld(mnemonic: Mnemonic) {
        if (!blockStore.endToEndEncryptionAvailable()) throw BackupNotEncryptedException()
        val address = addressOf(mnemonic)
        // Whose the entry was before: a write Play services doesn't answer may still land.
        val before = entryAddress()
        try {
            write(mnemonic.phrase(), cloud = true)
        } catch (e: BackupNoAnswerException) {
            // The caller is told it failed, so it mustn't turn up later as a cloud backup
            // nobody asked for (#244 R1-M2). [GmsBlockStore] sends nothing more until that
            // write has finished, so this reads what it did; if it still hasn't, this fails
            // too and the entry's state stays unknown.
            _known.value = null
            if (before == null || !before.equals(address, ignoreCase = true)) {
                withContext(NonCancellable) { runCatching { deleteIfOfHeld(address) } }
            }
            throw e
        }
        _known.value = Known(Status.CLOUD, address)
    }

    /** Account 0's address of the entry's phrase; null with no entry or an unreadable one. */
    private suspend fun entryAddress(): String? {
        val bytes = blockStore.retrieve(KEY) ?: return null
        return try {
            addressOf(Mnemonic.parse(decode(bytes).phrase))
        } catch (_: BackupUnreadableException) {
            null
        } catch (_: Mnemonic.ParseException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun deleteHeld() {
        try {
            blockStore.delete(KEY)
        } catch (e: BackupNoAnswerException) {
            // Like a store that got no answer, the delete may still land (#244 R2-M2): the
            // entry is neither known to be there nor gone, and if it stays, this install's
            // last write of it can't be vouched for either.
            _known.value = null
            writtenHere.set(null)
            throw e
        }
        writtenHere.set(null)
        _known.value = Known(Status.NONE, null)
    }

    /** What [Held.deleteIfOf] did. */
    enum class DeleteIfOf {
        /** The entry was that wallet's, and is deleted. */
        DELETED,

        /** There was no entry. */
        NO_ENTRY,

        /** The entry is another wallet's: kept. */
        OTHER_WALLET,

        /** Whose the entry is couldn't be told (no address given, or it can't be read): kept. */
        UNKNOWN,
    }

    /**
     * Deletes the entry only if its phrase is the wallet whose account-0
     * address is [address], read afresh under the lock: for a delete asked
     * while whose the entry is wasn't known (Remove wallet with Play
     * services slow to answer), which must not take another wallet's kept
     * backup with it (#244 R5-F2).
     */
    private suspend fun deleteIfOfHeld(address: String?): DeleteIfOf {
        val bytes = blockStore.retrieve(KEY) ?: run {
            writtenHere.set(null)
            _known.value = Known(Status.NONE, null)
            return DeleteIfOf.NO_ENTRY
        }
        val entryAddress = try {
            addressOf(Mnemonic.parse(decode(bytes).phrase))
        } catch (_: BackupUnreadableException) {
            null
        } catch (_: Mnemonic.ParseException) {
            null
        } finally {
            bytes.fill(0)
        }
        if (address == null || entryAddress == null) return DeleteIfOf.UNKNOWN
        if (!entryAddress.equals(address, ignoreCase = true)) return DeleteIfOf.OTHER_WALLET
        deleteHeld()
        return DeleteIfOf.DELETED
    }

    /** [store] and [delete] for code already holding the entry lock, inside [exclusive]. */
    inner class Held internal constructor() {
        suspend fun store(mnemonic: Mnemonic) = storeHeld(mnemonic)
        suspend fun delete() = deleteHeld()

        /** See [deleteIfOfHeld]. */
        suspend fun deleteIfOf(address: String?): DeleteIfOf = deleteIfOfHeld(address)
    }

    /**
     * Runs [block] holding the entry lock, for a caller that also takes
     * another lock around its writes ([Vault]'s): this one is always taken
     * first. Each Block Store call takes at most [GmsBlockStore.TIMEOUT_MS]
     * (20 s), waiting for an earlier one included, so with Play services
     * unresponsive a reconcile (three calls) holds it for up to a minute, a
     * Turn on that gets no answer (five, taking a late write back) for up to
     * about 100 s, and Remove wallet's [Held.deleteIfOf] (two) for up to
     * 40 s (#244 R2-M3). Whoever waits for it then must not be holding the
     * vault's lock meanwhile, or unlocking queues behind it (#244 R4-F4).
     * Inside, write through the [Held] receiver: the lock isn't reentrant.
     */
    suspend fun <T> exclusive(block: suspend Held.() -> T): T = writes.withLock { Held().block() }

    /** The backed-up phrase, or null when Block Store holds none. */
    suspend fun read(): Mnemonic? {
        val bytes = blockStore.retrieve(KEY) ?: return null
        return try {
            Mnemonic.parse(decode(bytes).phrase)
        } catch (_: Mnemonic.ParseException) {
            throw BackupUnreadableException()
        } finally {
            bytes.fill(0)
        }
    }

    /** Whether Block Store holds an entry (readable or not); null when it can't be asked. */
    suspend fun exists(): Boolean? = try {
        blockStore.retrieve(KEY)?.let { it.fill(0); true } ?: false
    } catch (_: BackupUnavailableException) {
        null
    }

    /** Deletes the entry, here and (at Block Store's next sync) from the cloud. */
    suspend fun delete() = writes.withLock { deleteHeld() }

    /**
     * Keeps the entry's cloud copy end-to-end encrypted: rewrites it as
     * device-only while Block Store can't encrypt, and as cloud-backed once
     * it can again. Needs no authentication (Block Store hands its own
     * entry back to the app freely); the phrase is only held for the rewrite.
     */
    suspend fun reconcile(): Status = writes.withLock {
        // Whether this run has started rewriting the entry: from then on, what [known]
        // said before may be wrong (a cloud entry now device-only).
        var rewriting = false
        try {
            val bytes = blockStore.retrieve(KEY)
            if (bytes == null) {
                writtenHere.set(null)
                _known.value = Known(Status.NONE, null)
                return@withLock Status.NONE
            }
            try {
                val entry = decode(bytes)
                val encrypted = blockStore.endToEndEncryptionAvailable()
                // The payload's flag is what the phone that wrote it asked for; only a write
                // this install made says what the entry is flagged with here (#244 R1-M1).
                if (entry.cloud != encrypted || writtenHere.get() != encrypted) {
                    rewriting = true
                    write(entry.phrase, cloud = encrypted)
                }
                val status = if (encrypted) Status.CLOUD else Status.PAUSED
                val address = try {
                    addressOf(Mnemonic.parse(entry.phrase))
                } catch (_: Mnemonic.ParseException) {
                    null
                }
                _known.value = Known(status, address)
                status
            } finally {
                bytes.fill(0)
            }
        } catch (e: CancellationException) {
            // The caller went away (the wallet page's effect restarts on every lock and
            // unlock, #244 R3-F3): that says nothing about the entry, so what was known
            // stands — unless this run began rewriting it, and then it's no longer known.
            if (rewriting) _known.value = null
            throw e
        } catch (e: Throwable) {
            // Not known any more: nothing may go on claiming the phrase is in Google.
            _known.value = null
            throw e
        }
    }

    /** Account 0's address of [mnemonic] ([EthAccounts]); the seed is zeroed after. */
    private suspend fun addressOf(mnemonic: Mnemonic): String = withContext(compute) {
        val seed = mnemonic.seed()
        try {
            EthAccounts.address(seed, 0)
        } finally {
            seed.fill(0)
        }
    }

    /** [reconcile] for a background caller nobody awaits: never throws; null when it didn't run. */
    suspend fun reconcileQuietly(): Status? = try {
        reconcile()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** Call under [writes]. */
    private suspend fun write(phrase: String, cloud: Boolean) {
        val bytes = encode(Entry(phrase, cloud))
        // Unknown until the write is answered: one left unfinished is written again next time.
        writtenHere.set(null)
        try {
            blockStore.store(KEY, bytes, backupToCloud = cloud)
        } finally {
            bytes.fill(0)
        }
        writtenHere.set(cloud)
    }

    /** The entry as stored: the phrase, and whether it was written for the cloud. */
    internal class Entry(val phrase: String, val cloud: Boolean)

    companion object {
        /** The one Block Store key this app uses. */
        const val KEY = "baby.freedom.mobile.wallet.phrase"
        const val VERSION = 1

        internal fun encode(entry: Entry): ByteArray = JSONObject()
            .put("version", VERSION)
            .put("phrase", entry.phrase)
            .put("cloud", entry.cloud)
            .toString()
            .toByteArray(Charsets.UTF_8)

        /** Throws [BackupUnreadableException] (never quoting [bytes]) for anything else. */
        internal fun decode(bytes: ByteArray): Entry = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            if (o.getInt("version") != VERSION) throw BackupUnreadableException()
            Entry(o.getString("phrase"), o.getBoolean("cloud"))
        } catch (e: BackupUnreadableException) {
            throw e
        } catch (_: Throwable) {
            throw BackupUnreadableException()
        }

        @Volatile
        private var instance: PhraseBackup? = null

        fun get(context: Context): PhraseBackup = instance ?: synchronized(this) {
            instance ?: PhraseBackup(
                GmsBlockStore(context),
                writtenHere = WrittenHere.InFile(File(context.applicationContext.noBackupFilesDir, "phrase-backup-written")),
            ).also { instance = it }
        }
    }
}

/**
 * [BlockStorePort] over Play services' `BlockstoreClient`. Any failure —
 * no Play services, an API error, no answer within [TIMEOUT_MS] — is a
 * [BackupUnavailableException], whose message is Play services' status,
 * never data.
 */
class GmsBlockStore(context: Context) : BlockStorePort {
    private val app = context.applicationContext
    private val client by lazy { Blockstore.getClient(app) }

    override suspend fun endToEndEncryptionAvailable(): Boolean =
        call { client.isEndToEndEncryptionAvailable() } == true

    override suspend fun store(key: String, bytes: ByteArray, backupToCloud: Boolean) {
        val data = StoreBytesData.Builder()
            .setKey(key)
            .setBytes(bytes)
            .setShouldBackupToCloud(backupToCloud)
            .build()
        call { client.storeBytes(data) }
    }

    override suspend fun retrieve(key: String): ByteArray? {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(key)).build()
        return call { client.retrieveBytes(request) }?.blockstoreDataMap?.get(key)?.bytes
    }

    override suspend fun delete(key: String) {
        val request = DeleteBytesRequest.Builder().setKeys(listOf(key)).build()
        call { client.deleteBytes(request) }
    }

    private val order = TaskOrder(ABANDON_MS)

    private suspend fun <T> call(start: () -> Task<T>): T? = try {
        // Asked before every call, never cached: Play services can be installed, enabled
        // or updated while the app runs. Without it the client isn't even created — a
        // call made then has Play services' own library post a heads-up "Freedom won't
        // work unless you enable Google Play services" notification, on every foreground
        // reconcile (#244 R4-F1), though the app works fine without it.
        if (!playServicesUsable(app)) throw BackupUnavailableException("Google Play services isn’t available")
        // One deadline for waiting on an earlier call and for this one's own answer, so
        // no call takes longer than [TIMEOUT_MS] in all (#244 R2-M3).
        withTimeout(TIMEOUT_MS) { order.submit(start).await() }
    } catch (e: TimeoutCancellationException) {
        throw BackupNoAnswerException()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw BackupUnavailableException(
            (e as? com.google.android.gms.common.api.ApiException)?.let { "Google Play services: ${it.statusCode}" }
                ?: "Google Play services isn’t available",
        )
    }

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener({ it.run() }) { t ->
            when {
                t.isSuccessful -> cont.resume(t.result)
                t.isCanceled -> cont.resumeWithException(BackupUnavailableException("Google Play services cancelled the call"))
                else -> cont.resumeWithException(t.exception ?: BackupUnavailableException("Google Play services failed"))
            }
        }
    }

    internal companion object {
        /** The longest any one call takes, waiting for an earlier one included. */
        const val TIMEOUT_MS = 20_000L

        /** When a call Play services still hasn't answered stops holding later ones back ([TaskOrder]). */
        const val ABANDON_MS = 120_000L

        /** Only answers; unlike a failed API call it shows the user nothing. */
        private fun playServicesUsable(context: Context): Boolean = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }
}

/**
 * Keeps Block Store calls in the order they were made. A timed-out or
 * cancelled wait doesn't cancel the Play services `Task` behind it, so a
 * store can still land after its caller gave up — and after the entry lock
 * was released, over a delete made next (#244 R1-M2). So no call is sent
 * while an earlier one is still running: [submit] waits for that one
 * first, and the caller bounds that wait with its own deadline (cancelled
 * there, nothing is sent).
 *
 * A Task still running [abandonMs] after it was sent is given up on
 * (#244 R2-M1): Play services fails a call whose connection dies, so one
 * that old has been lost, and waiting on for it would refuse every later
 * call until the process dies — among them the reconcile that rewrites
 * the entry device-only once end-to-end encryption is gone. The accepted
 * cost: a write that does land later still than that isn't ordered.
 */
internal class TaskOrder(
    private val abandonMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Mutex()
    private var last: Task<*>? = null
    private var lastSentAt = 0L

    suspend fun <T> submit(start: () -> Task<T>): Task<T> = lock.withLock {
        val previous = last
        if (previous != null && !previous.isComplete) {
            val abandonIn = abandonMs - (now() - lastSentAt)
            if (abandonIn > 0) withTimeoutOrNull(abandonIn) { previous.settled() }
        }
        start().also {
            last = it
            lastSentAt = now()
        }
    }

    private suspend fun Task<*>.settled() = suspendCancellableCoroutine { cont ->
        addOnCompleteListener({ it.run() }) { cont.resume(Unit) }
    }
}
