package baby.freedom.mobile.wallet

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A cipher ready to seal a new vault, and where its key ended up. */
class SealingCipher(val cipher: Cipher, val strongBox: Boolean)

/** The vault's key is gone for good (the screen lock was removed, or the key was deleted). */
class VaultKeyLostException(cause: Throwable? = null) :
    IllegalStateException("the wallet's Keystore key is no longer usable", cause)

/**
 * Opens the vault's cipher with [init], reporting a key that is gone for
 * good as [VaultKeyLostException], however this phone's Keystore says so:
 * no key at all, [UnrecoverableKeyException] from [loadKey] (some Keystore
 * versions answer an invalidated key that way), or an [InvalidKeyException]
 * from [init] — [KeyPermanentlyInvalidatedException] on most phones. A
 * plain [InvalidKeyException] only counts if [keyStillWorks] agrees the
 * key is dead, so a one-off refusal doesn't tell the user to remove their
 * wallet; [UserNotAuthenticatedException] never does.
 */
internal fun openCipherOrKeyLost(
    loadKey: () -> SecretKey?,
    init: (SecretKey) -> Cipher,
    keyStillWorks: (SecretKey) -> Boolean,
): Cipher {
    val key = try {
        loadKey()
    } catch (e: UnrecoverableKeyException) {
        throw VaultKeyLostException(e)
    } ?: throw VaultKeyLostException()
    return try {
        init(key)
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw VaultKeyLostException(e)
    } catch (e: UserNotAuthenticatedException) {
        throw e
    } catch (e: InvalidKeyException) {
        if (keyStillWorks(key)) throw e
        throw VaultKeyLostException(e)
    }
}

/**
 * Whether a key is still usable, judged by [start] opening a cipher with
 * it: a key Android has invalidated fails there. The cipher is then
 * finished with an empty `doFinal` only so the Keystore operation isn't
 * left open; its outcome says nothing about the key, since an
 * auth-per-use (screen-lock) key always refuses to finish without
 * BiometricPrompt — a failed finish aborts the operation just the same.
 */
internal fun probeKey(start: () -> Cipher): Boolean {
    val cipher = try {
        start()
    } catch (_: Exception) {
        return false
    }
    runCatching { cipher.doFinal() }
    return true
}

/**
 * Where [Vault] keeps its sealed phrase and gets its ciphers from. The
 * Keystore implementation is [KeystoreVaultStore]; tests use a software
 * key.
 */
interface VaultStore {
    /** Whether the phone has a PIN, pattern or password (so a key can require the user). */
    fun deviceSecure(): Boolean

    fun read(): VaultRecord?

    /** True when a vault file exists, readable or not. */
    fun exists(): Boolean

    fun write(record: VaultRecord)

    /** Replaces the key with a fresh one guarded as [protection], and returns a cipher to seal with it. */
    fun newSealingCipher(protection: VaultProtection): SealingCipher

    /** A cipher to open [record]; throws [VaultKeyLostException] if its key can't be used any more. */
    fun openingCipher(record: VaultRecord): Cipher

    /** Deletes the file and the key. */
    fun wipe()
}

/**
 * The vault on Android (#76): an AES-256-GCM key in the Android Keystore
 * seals the recovery phrase, and the sealed bytes live in
 * `noBackupFilesDir` — outside Auto Backup and device-to-device transfer,
 * so the wallet is device-only as decided for v1 (the key couldn't travel
 * anyway).
 *
 * The key, as the maintainer's decisions for #75/#76 ask:
 *  - hardware-backed, in StrongBox when the phone has one (falling back
 *    to the TEE when StrongBox refuses the key);
 *  - usable only after the user authenticates, for each use
 *    (`setUserAuthenticationParameters(0, …)`), with a strong biometric
 *    or the device credential — so BiometricPrompt must unlock this very
 *    cipher, not just "someone unlocked the phone recently";
 *  - not invalidated when a new fingerprint or face is enrolled
 *    (`setInvalidatedByBiometricEnrollment(false)`). Removing the screen
 *    lock does still destroy it (Android does that to every
 *    authentication-bound key); [openingCipher] reports that as
 *    [VaultKeyLostException] and the wallet page explains what's left:
 *    re-import the recovery phrase, if the user was ever shown it.
 *
 * On a phone with no screen lock the key is made without the
 * authentication requirement ([VaultProtection.DEVICE_ONLY]), which is
 * the only key Android will make there.
 */
class KeystoreVaultStore(context: Context) : VaultStore {
    private val app = context.applicationContext
    private val file = File(app.noBackupFilesDir, "wallet/vault.json")
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val hasStrongBox = app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    override fun deviceSecure(): Boolean = keyguard?.isDeviceSecure == true

    override fun exists(): Boolean = file.exists()

    override fun read(): VaultRecord? =
        if (!file.exists()) null else runCatching { VaultRecord.decode(file.readText()) }.getOrNull()

    override fun write(record: VaultRecord) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(record.encode())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("couldn't write the wallet file")
        }
    }

    override fun newSealingCipher(protection: VaultProtection): SealingCipher {
        deleteKey()
        var strongBox = false
        val key = if (hasStrongBox) {
            try {
                generate(protection, strongBox = true).also { strongBox = true }
            } catch (_: StrongBoxUnavailableException) {
                generate(protection, strongBox = false)
            }
        } else {
            generate(protection, strongBox = false)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Keystore picks the IV (randomized encryption is required).
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return SealingCipher(cipher, strongBox)
    }

    override fun openingCipher(record: VaultRecord): Cipher = openCipherOrKeyLost(
        loadKey = ::key,
        init = { key ->
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, record.iv))
            }
        },
        keyStillWorks = ::keyStillWorks,
    )

    /**
     * Whether [key] is still alive: opening a cipher to *encrypt* needs no
     * authentication even for an authentication-bound key, so it tells a
     * dead key apart from one that merely refused this decryption. Only
     * the init is the answer — see [probeKey].
     */
    internal fun keyStillWorks(key: SecretKey): Boolean = probeKey {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun wipe() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
        deleteKey()
    }

    private fun generate(protection: VaultProtection, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                if (protection == VaultProtection.SCREEN_LOCK) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "freedom.wallet.vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
