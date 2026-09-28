package baby.freedom.mobile.wallet

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertSame
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
}
