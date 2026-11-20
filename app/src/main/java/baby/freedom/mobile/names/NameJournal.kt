package baby.freedom.mobile.names

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import baby.freedom.mobile.wallet.FileSendJournal
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * One registration or renewal, from its first quote to its last
 * transaction — desktop's `domain-operations.json` record. A
 * registration is two transactions: [Step.COMMIT], then, once the
 * commitment is old enough, [Step.REVEAL]; a renewal is one.
 *
 * [account] is the address that pays and, for a registration, owns the
 * name: the commitment binds it, so the operation never moves to
 * another account. [accountIndex] finds it in the wallet again.
 */
data class NameOperation(
    val id: String,
    val name: String,
    val space: NameSpace,
    val intent: Intent,
    val account: String,
    val accountIndex: Int,
    /** Seconds the registration buys or the renewal adds. */
    val duration: Long,
    val step: Step,
    val state: State,
    val createdAt: Long,
    /** The registration's commitment (32 bytes, hex), null for a renewal. */
    val commitment: String? = null,
    /** The registration's secret, encrypted ([NameSecrets]); dropped once the name is registered. */
    val encryptedSecret: String? = null,
    /** The price (wei) the current step was quoted at. */
    val quotedWei: BigInteger? = null,
    /** The price quoted at commit time, kept so a dearer reveal says so. */
    val commitQuoteWei: BigInteger? = null,
    /** Block time (seconds) from which the reveal may be sent, and when the commitment runs out. */
    val readyAt: Long? = null,
    val expiresAt: Long? = null,
    /** The transaction of the current step, once the wallet sent it. */
    val transactionHash: String? = null,
    /** The commit's transaction, once mined. */
    val commitHash: String? = null,
    /**
     * A renewal's name state as prepared: it must still be this at Confirm,
     * and the renewal counts as done once the expiry (and the wrapper's)
     * is at least this plus [duration] — "at least", since another renewal
     * landing too moves it further.
     */
    val beforeExpiry: BigInteger? = null,
    val beforeWrapperExpiry: BigInteger? = null,
    val wrapped: Boolean = false,
    /** Why it's where it is, for the screen. */
    val message: String? = null,
    /** Bumped by every save: a copy read before a slow read can't overwrite a newer one. */
    val revision: Int = 0,
) {
    enum class Intent { REGISTER, RENEW }

    enum class Step { COMMIT, REVEAL, RENEW }

    enum class State {
        /** The current step can be reviewed and sent. */
        READY,

        /** Handed to the wallet; read back after a restart as [DISPATCH_UNKNOWN]. */
        DISPATCHING,

        /** Sent, waiting for its receipt. */
        PENDING,

        /** The commit is mined; the reveal waits for the commitment's minimum age. */
        WAITING_COMMITMENT,

        COMPLETED,

        /** Mined but reverted (a renewal or commit); the network fee is spent. */
        FAILED,

        /** The commitment got older than the contract accepts before the reveal. */
        EXPIRED,

        /** The wallet may have sent it: never sent again, settled from chain state only. */
        DISPATCH_UNKNOWN,

        /** A mined transaction whose effect couldn't be confirmed: never sent again. */
        VERIFICATION_NEEDED,

        /** The name, its price or its owner changed since it was prepared. */
        STALE,
        ;

        /** Nothing more will happen to it; it can be dismissed. */
        val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == EXPIRED || this == STALE

        /** Something is still on its way: the screen keeps checking. */
        val waiting: Boolean get() = this == PENDING || this == WAITING_COMMITMENT || this == DISPATCH_UNKNOWN || this == VERIFICATION_NEEDED
    }

    val registering: Boolean get() = intent == Intent.REGISTER
}

/**
 * Encrypts registration secrets under an AES-GCM key in the Android
 * Keystore. The key needs no unlock — a reveal after a restart must not
 * wait on the wallet — and a commitment's secret moves nothing on its
 * own: the commitment binds the owner, so whoever learns it can at most
 * register the name *to that owner*. Kept apart from the plain journal
 * anyway, so the commitment can't be opened before it's revealed.
 */
interface NameSecrets {
    fun seal(secret: ByteArray): String
    fun open(sealed: String): ByteArray

    /** Keystore-backed, the app's. */
    object Keystore : NameSecrets {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "freedom_name_registration_secrets"

        @Volatile
        private var cached: SecretKey? = null

        private fun key(): SecretKey = cached ?: synchronized(this) {
            cached ?: load().also { cached = it }
        }

        private fun load(): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) return existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return gen.generateKey()
        }

        override fun seal(secret: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(secret))
        }

        override fun open(sealed: String): ByteArray {
            val blob = Base64.getDecoder().decode(sealed)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            return cipher.doFinal(blob, 12, blob.size - 12)
        }
    }
}

/**
 * The operations, in one JSON file written whole to a temporary file,
 * fsynced, renamed over the old one and the directory fsynced — so a
 * secret saved before its commit is handed to the wallet is on flash
 * first. Blocks on storage: never call it on the main thread.
 *
 * In `noBackupFilesDir`: the secrets' Keystore key doesn't travel to
 * another device, so a copy there could never be revealed.
 */
interface NameJournal {
    fun load(): List<NameOperation>

    /** False when the write didn't reach flash; the caller acts as if nothing was saved. */
    fun save(operations: List<NameOperation>): Boolean

    /**
     * Deletes every journal [load] set aside — a wholly unreadable one, or a
     * copy of one with entries that didn't read back (Remove wallet: they
     * name the wallet's addresses too).
     */
    fun forgetSetAside() {}

    /**
     * Leaves nothing of the journal behind (Remove wallet): an empty one
     * saved, or — when that write can't go (a full disk) — the file
     * deleted, as is any temporary file a failed save left and every
     * copy set aside. False when something of it is still there.
     */
    fun erase(): Boolean {
        val saved = save(emptyList())
        forgetSetAside()
        return saved
    }

    /** In memory, for tests. */
    class Memory(initial: List<NameOperation> = emptyList(), var failSaves: Boolean = false) : NameJournal {
        var saved: List<NameOperation> = initial
            private set

        override fun load() = saved

        override fun save(operations: List<NameOperation>): Boolean {
            if (failSaves) return false
            saved = operations
            return true
        }

        /** With [failSaves], the entries can't be deleted either: the worst case, for tests. */
        override fun erase(): Boolean = save(emptyList())
    }

    class FileBacked(private val file: File) : NameJournal {
        override fun load(): List<NameOperation> = try {
            if (file.exists()) {
                val decoded = NameJournalCodec.decodeAll(JSONObject(file.readText()))
                // Some entries didn't read back (a newer app's state, a damaged field): a copy is
                // kept aside before the next save writes the file without them — one may hold a
                // paid commitment's secret.
                if (decoded.dropped > 0) {
                    Log.w(TAG, "${decoded.dropped} unreadable name journal entries kept aside")
                    // No copy, no partial load: the whole file is then set aside below instead
                    // (nothing lost, though its readable operations wait there too).
                    keepCopyAside()
                }
                decoded.operations
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "unreadable name journal: ${e.javaClass.simpleName}")
            // Kept aside rather than overwritten: it may hold a paid commitment's secret.
            runCatching { file.renameTo(setAside()) }
            emptyList()
        } catch (e: StackOverflowError) {
            Log.w(TAG, "unreadable name journal: too deeply nested")
            runCatching { file.renameTo(setAside()) }
            emptyList()
        }

        private fun setAside() = File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}")

        /**
         * A copy of the journal as it is, on flash before the next save can
         * write it without the entries that didn't read back; once — a copy
         * already set aside with the same bytes is enough.
         */
        private fun keepCopyAside() {
            val bytes = file.readBytes()
            val dir = file.parentFile
            val kept = dir?.listFiles { f -> f.name.startsWith("${file.name}.unreadable-") }.orEmpty()
            if (kept.any { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }) return
            val aside = setAside()
            try {
                FileOutputStream(aside).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                check(FileSendJournal.syncDirectory(dir)) { "directory not synced" }
            } catch (e: Exception) {
                aside.delete()
                throw e
            }
        }

        override fun save(operations: List<NameOperation>): Boolean = try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(NameJournalCodec.encode(operations).toString().toByteArray())
                out.fd.sync()
            }
            if (tmp.renameTo(file)) {
                FileSendJournal.syncDirectory(file.parentFile)
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't save the name journal: ${e.javaClass.simpleName}")
            false
        }

        override fun forgetSetAside() {
            file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.unreadable-") }?.forEach { it.delete() }
        }

        override fun erase(): Boolean {
            // An empty journal saved, or else none at all: deleting needs no free space.
            val saved = save(emptyList())
            if (!saved) file.delete()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            if (tmp.isFile) tmp.delete()
            forgetSetAside()
            val left = !saved && file.exists() ||
                tmp.isFile ||
                file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.unreadable-") }.orEmpty().isNotEmpty()
            runCatching { FileSendJournal.syncDirectory(file.parentFile) }
            return !left
        }

        private companion object {
            const val TAG = "NameJournal"
        }
    }
}

internal object NameJournalCodec {
    private const val VERSION = 1

    /** At most this many operations are kept; the oldest finished ones go first. */
    const val MAX = 200

    fun encode(operations: List<NameOperation>): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("operations", JSONArray().apply { operations.forEach { put(encode(it)) } })

    fun encode(o: NameOperation): JSONObject = JSONObject()
        .put("id", o.id)
        .put("name", o.name)
        .put("space", o.space.tld)
        .put("intent", o.intent.name)
        .put("account", o.account)
        .put("accountIndex", o.accountIndex)
        .put("duration", o.duration)
        .put("step", o.step.name)
        .put("state", o.state.name)
        .put("createdAt", o.createdAt)
        .put("commitment", o.commitment ?: JSONObject.NULL)
        .put("encryptedSecret", o.encryptedSecret ?: JSONObject.NULL)
        .put("quotedWei", o.quotedWei?.toString() ?: JSONObject.NULL)
        .put("commitQuoteWei", o.commitQuoteWei?.toString() ?: JSONObject.NULL)
        .put("readyAt", o.readyAt ?: JSONObject.NULL)
        .put("expiresAt", o.expiresAt ?: JSONObject.NULL)
        .put("transactionHash", o.transactionHash ?: JSONObject.NULL)
        .put("commitHash", o.commitHash ?: JSONObject.NULL)
        .put("beforeExpiry", o.beforeExpiry?.toString() ?: JSONObject.NULL)
        .put("beforeWrapperExpiry", o.beforeWrapperExpiry?.toString() ?: JSONObject.NULL)
        .put("wrapped", o.wrapped)
        .put("message", o.message ?: JSONObject.NULL)
        .put("revision", o.revision)

    /** What [decodeAll] read: every operation that came back whole, and how many didn't. */
    class Decoded(val operations: List<NameOperation>, val dropped: Int)

    /**
     * Every operation that reads back whole, each on its own (one broken
     * entry doesn't drop the others). One saved mid-dispatch comes back
     * [NameOperation.State.DISPATCH_UNKNOWN]: the app stopped after handing
     * it to the wallet, which is never permission to send it again.
     * Another version's file, or one without its list, is refused whole
     * ([IllegalStateException]) so the caller keeps it rather than saving
     * over it; [Decoded.dropped] counts the entries left out.
     */
    fun decodeAll(json: JSONObject): Decoded {
        check(json.optInt("version") == VERSION) { "name journal version ${json.opt("version")}" }
        val a = json.optJSONArray("operations") ?: error("name journal without operations")
        val operations = (0 until minOf(a.length(), MAX)).mapNotNull { i ->
            try {
                operation(a.getJSONObject(i)).let {
                    if (it.state == NameOperation.State.DISPATCHING) it.copy(state = NameOperation.State.DISPATCH_UNKNOWN) else it
                }
            } catch (e: Exception) {
                null
            }
        }
        return Decoded(operations, a.length() - operations.size)
    }

    fun decode(json: JSONObject): List<NameOperation> = decodeAll(json).operations

    private val ID = Regex("^name_[0-9a-f]{24}$")
    private val HASH = Regex("^0x[0-9a-fA-F]{64}$")

    fun operation(o: JSONObject): NameOperation {
        val id = o.getString("id")
        require(ID.matches(id))
        val parsed = NameContracts.parse(o.getString("name"))
        require(parsed.space.tld == o.getString("space") && parsed.topLevel)
        return NameOperation(
            id = id,
            name = parsed.name,
            space = parsed.space,
            intent = NameOperation.Intent.valueOf(o.getString("intent")),
            account = o.getString("account").also { NameAbi.addressBytes(it) },
            accountIndex = o.getInt("accountIndex"),
            duration = o.getLong("duration"),
            step = NameOperation.Step.valueOf(o.getString("step")),
            state = NameOperation.State.valueOf(o.getString("state")),
            createdAt = o.getLong("createdAt"),
            commitment = o.str("commitment")?.also { require(HASH.matches(it)) },
            encryptedSecret = o.str("encryptedSecret"),
            quotedWei = o.str("quotedWei")?.let(::BigInteger),
            commitQuoteWei = o.str("commitQuoteWei")?.let(::BigInteger),
            readyAt = o.long("readyAt"),
            expiresAt = o.long("expiresAt"),
            transactionHash = o.str("transactionHash")?.also { require(HASH.matches(it)) },
            commitHash = o.str("commitHash")?.also { require(HASH.matches(it)) },
            beforeExpiry = o.str("beforeExpiry")?.let(::BigInteger),
            beforeWrapperExpiry = o.str("beforeWrapperExpiry")?.let(::BigInteger),
            wrapped = o.optBoolean("wrapped", false),
            message = o.str("message"),
            revision = o.optInt("revision", 0),
        )
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)

    private fun JSONObject.long(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
}
