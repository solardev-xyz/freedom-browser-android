package baby.freedom.mobile.wallet

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultStoreTest {
    private val key: SecretKey = SecretKeySpec(ByteArray(32), "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun open(
        loadKey: () -> SecretKey? = { key },
        init: (SecretKey) -> Cipher = { cipher },
        keyStillWorks: Boolean = true,
    ) = openCipherOrKeyLost(loadKey, init) { keyStillWorks }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun `the vault file is written whole through a synced temp file, then its directory synced (#229)`() {
        val dir = java.nio.file.Files.createTempDirectory("vault").toFile()
        try {
            val file = java.io.File(dir, "wallet/vault.json")
            val synced = mutableListOf<java.io.File?>()
            writeDurably(file, "first") { synced += it; true }
            writeDurably(file, "second") { synced += it; true }
            org.junit.Assert.assertEquals("second", file.readText())
            assertFalse(java.io.File(file.parentFile, "vault.json.tmp").exists())
            org.junit.Assert.assertEquals(listOf(file.parentFile, file.parentFile), synced)
            // A directory that won't sync leaves the file in place rather than failing the write.
            writeDurably(file, "third") { false }
            org.junit.Assert.assertEquals("third", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a working key opens the cipher`() {
        assertSame(cipher, open())
    }

    @Test
    fun `every way a Keystore reports a dead key is a lost key`() {
        assertThrows<VaultKeyLostException> { open(loadKey = { null }) }
        // Some Keystore versions fail the lookup itself for an invalidated key.
        assertThrows<VaultKeyLostException> { open(loadKey = { throw UnrecoverableKeyException("gone") }) }
        assertThrows<VaultKeyLostException> { open(init = { throw KeyPermanentlyInvalidatedException() }) }
        // A plain InvalidKeyException from a key that can't even encrypt any more.
        assertThrows<VaultKeyLostException> {
            open(init = { throw InvalidKeyException("Keystore operation failed") }, keyStillWorks = false)
        }
    }

    @Test
    fun `a refusal from a key that still works isn't reported as lost`() {
        val refused = InvalidKeyException("once")
        assertSame(refused, assertThrows<InvalidKeyException> { open(init = { throw refused }, keyStillWorks = true) })
        val unauthenticated = UserNotAuthenticatedException()
        assertSame(
            unauthenticated,
            assertThrows<UserNotAuthenticatedException> { open(init = { throw unauthenticated }, keyStillWorks = false) },
        )
    }

    @Test
    fun `the key probe goes by the init, not by whether the cipher would finish`() {
        assertFalse(probeKey { throw InvalidKeyException("invalidated") })
        assertFalse(probeKey { throw KeyPermanentlyInvalidatedException() })
        // An auth-per-use key opens an encrypt cipher but refuses to finish
        // it without BiometricPrompt: that key is alive.
        val refusesToFinish = Cipher.getInstance("AES/GCM/NoPadding") // never initialised: doFinal throws
        assertTrue(probeKey { refusesToFinish })
        assertTrue(probeKey { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) } })
    }
}
