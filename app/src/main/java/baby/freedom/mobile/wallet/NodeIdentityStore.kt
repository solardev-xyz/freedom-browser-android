package baby.freedom.mobile.wallet

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import baby.freedom.swarm.RadicleNode
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Which vault a set of derived node keys belongs to: SHA-256 over the
 * vault file's IV and ciphertext. Public (it's a hash of ciphertext),
 * stable for a vault's lifetime — marking it backed up rewrites the file
 * but not the sealed bytes — and different for every vault ever created,
 * since each gets a fresh key and IV. Lets the `:node` process tell,
 * without the seed, whether the keys on disk still belong to the wallet
 * on the device.
 */
fun VaultRecord.identityTag(): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(iv)
    md.update(ciphertext)
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The node keys derived from the wallet (#77), kept so the nodes can
 * start with them while the wallet is locked — which is always, after a
 * relaunch: the seed only ever lives in memory ([Vault]), but ant has
 * to boot as the same Swarm account every time.
 *
 * Only the derived 32-byte keys are kept — Swarm, IPFS and (#328)
 * Radicle; never the phrase or the seed — sealed with AES-256-GCM under their own Android Keystore key
 * ([KeystoreNodeKeys]: hardware-backed, StrongBox when there is one). That
 * key needs no user authentication, since the node boots in the
 * background; what it protects against is the file being read off the
 * device — it lives in `noBackupFilesDir`, so no backup or
 * device-to-device transfer carries it either. The file names the
 * vault it was derived from ([VaultRecord.identityTag], bound into the
 * GCM tag as associated data), so keys left over from a removed or
 * replaced wallet are never used.
 *
 * Version 1 files (#77) hold the Swarm and IPFS keys only; they still
 * boot Swarm, and [NodeIdentitySync] rewrites them as version 2, with the
 * Radicle key, at the wallet's next unlock.
 */
class NodeIdentityStore internal constructor(
    private val file: File,
    private val keys: Keys,
) {
    /** Where the sealing key comes from: the Keystore on a device, a plain key in tests. */
    internal interface Keys {
        /** The key, creating it when [create] and there is none. Null if there is none. */
        fun key(create: Boolean): SecretKey?

        fun delete()
    }

    /** True when there's no node identity file at all. */
    fun isEmpty(): Boolean = !file.exists()

    /** The vault tag the stored keys were derived from, or null if there are none (or they can't be read). */
    fun storedTag(): String? = readFile()?.optString("vault")?.takeIf { it.isNotEmpty() }

    /** True when the file on disk (readable or not once opened) holds a Radicle key: a version-2 file (#328). */
    fun storedHasRadicle(): Boolean = readFile()?.optInt("version") == VERSION

    /**
     * The stored identity if it was derived from the vault tagged [vaultTag];
     * null if there's none, it belongs to another vault, or it can't be
     * opened. The caller owns the result and must [NodeIdentity.wipe] it.
     */
    fun read(vaultTag: String): NodeIdentity? {
        val o = readFile() ?: return null
        if (o.optString("vault") != vaultTag) return null
        return runCatching {
            val key = keys.key(create = false) ?: return null
            val iv = Base64.getDecoder().decode(o.getString("iv"))
            val sealed = Base64.getDecoder().decode(o.getString("sealed"))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(vaultTag.toByteArray())
            val plain = cipher.doFinal(sealed)
            try {
                val radicle = when (o.optInt("version") to plain.size) {
                    1 to 64 -> null
                    VERSION to 96 -> plain.copyOfRange(64, 96)
                    else -> return null
                }
                NodeIdentity(plain.copyOfRange(0, 32), plain.copyOfRange(32, 64), radicle)
            } finally {
                plain.fill(0)
            }
        }.getOrNull()
    }

    /** Seals [identity]'s keys (all three) as belonging to the vault tagged [vaultTag], replacing whatever was there. */
    fun write(vaultTag: String, identity: NodeIdentity) {
        val radicle = requireNotNull(identity.radicleKey) { "no Radicle key" }
        val key = keys.key(create = true) ?: error("no node identity key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider picks the IV (the Keystore insists on it).
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(vaultTag.toByteArray())
        val plain = identity.swarmKey + identity.ipfsKey + radicle
        val sealed = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val text = JSONObject()
            .put("version", VERSION)
            .put("vault", vaultTag)
            .put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("sealed", Base64.getEncoder().encodeToString(sealed))
            .toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("couldn't write the node identity file")
        }
    }

    /** Deletes the file and its key: the nodes go back to their own device identities. */
    fun wipe() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
        keys.delete()
    }

    private fun readFile(): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.optInt("version") in 1..VERSION }
    }

    /**
     * What the `:node` process boots ant with (#77): the identity document
     * for the wallet on this device ([NodeIdentity.antIdentityJson], which
     * the caller zeroes), and its Swarm address — or null when there's no
     * wallet, or no keys derived from this one yet. Never throws.
     */
    class Boot(val antIdentity: ByteArray, val swarmAddress: String)

    fun boot(vault: VaultStore): Boot? = runCatching {
        val tag = vault.read()?.identityTag() ?: return null
        val identity = read(tag) ?: return null
        try {
            Boot(identity.antIdentityJson(), identity.swarmAddress)
        } finally {
            identity.wipe()
        }
    }.getOrNull()

    /**
     * What the `:node` process boots the Radicle node as (#328): the
     * wallet's Radicle identity (its secret seed, which the caller wipes,
     * and DID) — or null when there's no wallet, no keys derived from this
     * one yet, or only version-1 keys without a Radicle one.
     *
     * Throws [IllegalStateException] when it can't tell: the vault file is
     * there but unreadable, or the keys on disk are this vault's but can't
     * be opened (a Keystore or I/O failure). The node must not take that
     * for "no wallet" and quietly run as the device's own key.
     */
    fun radicle(vault: VaultStore): RadicleNode.HostIdentity? {
        val record = runCatching { vault.read() }.getOrNull()
        if (record == null) {
            check(!runCatching { vault.exists() }.getOrDefault(true)) { "the wallet can't be read" }
            return null
        }
        val tag = record.identityTag()
        // Keys from another (removed) vault, or none yet: this wallet has no
        // Radicle identity on disk.
        if (storedTag() != tag) {
            check(isEmpty() || readFile() != null) { "the node identity file can't be read" }
            return null
        }
        val identity = read(tag) ?: error("the wallet's node keys can't be opened")
        try {
            val did = identity.radicleDid ?: return null
            return RadicleNode.HostIdentity(identity.radicleSecret() ?: return null, did)
        } finally {
            identity.wipe()
        }
    }

    companion object {
        private const val VERSION = 2
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** The store on this device. Safe to use from any of the app's processes. */
        fun get(context: Context): NodeIdentityStore {
            val app = context.applicationContext
            return NodeIdentityStore(File(app.noBackupFilesDir, "wallet/node-identity.json"), KeystoreNodeKeys(app))
        }
    }
}

/**
 * The Keystore key sealing [NodeIdentityStore]: AES-256-GCM, StrongBox
 * when the phone has it (the TEE otherwise), usable without the user so
 * the `:node` process can start ant on its own. Its alias is its own, so
 * it never touches the vault's key or the RPC API key store's.
 */
internal class KeystoreNodeKeys(context: Context) : NodeIdentityStore.Keys {
    private val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    override fun key(create: Boolean): SecretKey? = synchronized(KeystoreNodeKeys::class.java) {
        (keyStore().getKey(ALIAS, null) as? SecretKey) ?: if (create) generate() else null
    }

    override fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun generate(): SecretKey = if (hasStrongBox) {
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    } else {
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "freedom.wallet.node-identity"
    }
}
