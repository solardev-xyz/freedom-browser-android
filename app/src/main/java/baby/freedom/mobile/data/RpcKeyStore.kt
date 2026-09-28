package baby.freedom.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import baby.freedom.mobile.ens.EnsRpcConfig
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Encrypts and decrypts a small secret. [encrypt] returns one opaque
 * blob (IV and ciphertext together); [decrypt] throws on a blob it
 * can't authenticate.
 */
internal interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM under the key [key] hands out. Output is the 12-byte IV
 * followed by the ciphertext and its 128-bit tag. In the app the key
 * lives in the Android Keystore ([KeystoreKey]) and never leaves it;
 * unit tests pass an in-memory key.
 */
internal class AesGcmCipher(private val key: () -> SecretKey) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider picks a fresh random IV (the Keystore insists on it).
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected IV length" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The AES key [RpcKeyStore] encrypts with, generated in the Android
 * Keystore on first use under [ALIAS]. Only the alias is known outside
 * the Keystore: no key material is ever in the app's files, and none is
 * in a backup or device transfer (Keystore keys aren't carried over).
 */
internal object KeystoreKey {
    private const val PROVIDER = "AndroidKeyStore"
    /** Distinct from the wallet vault's `KeystoreVaultStore.KEY_ALIAS`. */
    internal const val ALIAS = "freedom_rpc_api_keys"

    @Volatile
    private var cached: SecretKey? = null

    fun get(): SecretKey = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    private fun load(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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
}

/**
 * The keyed RPC providers' API keys (#102: Alchemy, Infura, DRPC),
 * encrypted at rest.
 *
 * Its own DataStore file (`freedom_rpc_secrets`), holding one entry: the
 * `provider id → key` map as JSON, AES-GCM encrypted by [cipher] (a
 * Keystore-backed key in the app, [KeystoreKey]) and Base64-encoded.
 * The file is excluded from cloud backup and device transfer
 * (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`): the
 * Keystore key doesn't travel with it, so a copy elsewhere could never
 * be read, and a key shouldn't leave the device anyway.
 *
 * Nothing here logs a key, and a failure's exception is logged by class
 * only (a crypto provider's message can quote input). A blob that won't
 * decrypt (the Keystore key was wiped — e.g. by a lock-screen reset on
 * some devices — or the file was damaged) reads as "no keys": the user
 * enters them again, and the next save replaces it.
 *
 * Before this store existed the keys sat in plain text in the settings
 * file ([LEGACY_KEY]); [NodeSettings] moves any such value here on first
 * read and only then deletes it there.
 */
class RpcKeyStore internal constructor(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    /** Provider id ([EnsRpcConfig.KEYED_PROVIDERS]) → API key. */
    val apiKeys: Flow<Map<String, String>> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading RPC keys failed (${e.javaClass.simpleName}); none in use")
            emit(emptyPreferences())
        }
        .map { decode(it[BLOB]) }

    /**
     * Replace the stored keys with [change] applied to them. `false`
     * when the write failed (nothing changed); a `null` from [change]
     * means "leave them as they are" and counts as done.
     */
    internal suspend fun edit(change: (Map<String, String>) -> Map<String, String>?): Boolean = try {
        store.edit { prefs ->
            val next = change(decode(prefs[BLOB])) ?: return@edit
            if (next.isEmpty()) prefs.remove(BLOB) else prefs[BLOB] = encode(next)
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "writing RPC keys failed (${e.javaClass.simpleName})")
        false
    }

    private fun encode(keys: Map<String, String>): String {
        val plain = EnsRpcConfig.encodeKeys(keys).toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(cipher.encrypt(plain))
    }

    /** The last blob [decode] opened and what was in it: the resolver reads the keys for every lookup. */
    @Volatile
    private var opened: Pair<String, Map<String, String>>? = null

    private fun decode(blob: String?): Map<String, String> {
        if (blob.isNullOrEmpty()) return emptyMap()
        opened?.let { (b, keys) -> if (b == blob) return keys }
        return try {
            val plain = cipher.decrypt(Base64.getDecoder().decode(blob))
            EnsRpcConfig.decodeKeys(String(plain, Charsets.UTF_8)).also { opened = blob to it }
        } catch (e: Exception) {
            Log.w(TAG, "RPC keys can't be decrypted (${e.javaClass.simpleName}); none in use")
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "RpcKeyStore"
        private val BLOB = stringPreferencesKey("api_keys_v1")

        /** Where the keys were kept, in plain text, before this store (`NodeSettings`' file). */
        internal val LEGACY_KEY = stringPreferencesKey("ens_rpc_api_keys")

        private val Context.rpcSecrets by preferencesDataStore(
            name = FILE_NAME,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        /** The DataStore file's name, excluded from backup in `res/xml`. */
        const val FILE_NAME = "freedom_rpc_secrets"

        @Volatile
        private var instance: RpcKeyStore? = null

        fun get(context: Context): RpcKeyStore =
            instance ?: synchronized(this) {
                instance ?: RpcKeyStore(
                    context.applicationContext.rpcSecrets,
                    AesGcmCipher(KeystoreKey::get),
                ).also { instance = it }
            }
    }
}
