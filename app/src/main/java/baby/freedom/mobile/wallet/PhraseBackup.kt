package baby.freedom.mobile.wallet

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Block Store can't be used here: no Google Play services, or it didn't answer. */
class BackupUnavailableException(message: String) : Exception(message)

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
 * written as, so [reconcile] only rewrites on a change.
 *
 * On the phone Block Store keeps the entry in Google Play services' own
 * storage, which is what lets a reinstall on the same phone restore it.
 *
 * Nothing here logs; the phrase lives in byte arrays that are zeroed
 * after use, apart from the unavoidable [String] inside [Mnemonic].
 */
class PhraseBackup(private val blockStore: BlockStorePort) {
    enum class Availability {
        /** No Google Play services (or Block Store didn't answer). */
        UNSUPPORTED,

        /** Block Store is here, but can't end-to-end encrypt: no screen lock or no Google account. */
        NOT_ENCRYPTED,

        /** A backup would be end-to-end encrypted. */
        READY,
    }

    /** Where the entry stands after [reconcile]. */
    enum class Status {
        /** No entry. */
        NONE,

        /** Backed up to the cloud, end-to-end encrypted. */
        CLOUD,

        /** Kept on this phone only until end-to-end encryption is available again. */
        PAUSED,
    }

    suspend fun availability(): Availability = try {
        if (blockStore.endToEndEncryptionAvailable()) Availability.READY else Availability.NOT_ENCRYPTED
    } catch (_: BackupUnavailableException) {
        Availability.UNSUPPORTED
    }

    /**
     * Puts [mnemonic] in Block Store for backup to the cloud. Throws
     * [BackupNotEncryptedException] (and stores nothing) unless Block Store
     * says that backup will be end-to-end encrypted.
     */
    suspend fun store(mnemonic: Mnemonic) {
        if (!blockStore.endToEndEncryptionAvailable()) throw BackupNotEncryptedException()
        write(mnemonic.phrase(), cloud = true)
    }

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
    suspend fun delete() = blockStore.delete(KEY)

    /**
     * Keeps the entry's cloud copy end-to-end encrypted: rewrites it as
     * device-only while Block Store can't encrypt, and as cloud-backed once
     * it can again. Needs no authentication (Block Store hands its own
     * entry back to the app freely); the phrase is only held for the rewrite.
     */
    suspend fun reconcile(): Status {
        val bytes = blockStore.retrieve(KEY) ?: return Status.NONE
        try {
            val entry = decode(bytes)
            val encrypted = blockStore.endToEndEncryptionAvailable()
            if (entry.cloud != encrypted) write(entry.phrase, cloud = encrypted)
            return if (encrypted) Status.CLOUD else Status.PAUSED
        } finally {
            bytes.fill(0)
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

    private suspend fun write(phrase: String, cloud: Boolean) {
        val bytes = encode(Entry(phrase, cloud))
        try {
            blockStore.store(KEY, bytes, backupToCloud = cloud)
        } finally {
            bytes.fill(0)
        }
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
            instance ?: PhraseBackup(GmsBlockStore(context)).also { instance = it }
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
    private val client by lazy { Blockstore.getClient(context.applicationContext) }

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

    private suspend fun <T> call(start: () -> Task<T>): T? = try {
        withTimeout(TIMEOUT_MS) { start().await() }
    } catch (e: TimeoutCancellationException) {
        throw BackupUnavailableException("Google Play services didn’t answer")
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

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
