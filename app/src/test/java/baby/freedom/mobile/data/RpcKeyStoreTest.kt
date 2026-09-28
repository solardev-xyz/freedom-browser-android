package baby.freedom.mobile.data

import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyed providers' API keys at rest (#102): AES-GCM, round-trip, and failure. */
class RpcKeyStoreTest {

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `keys round-trip and are removed`() = runBlocking {
        val key = aesKey()
        val file = NodeSettingsEnsRpcTest.MemoryStore()
        val store = RpcKeyStore(file, AesGcmCipher { key })
        assertEquals(emptyMap<String, String>(), store.apiKeys.first())

        assertTrue(store.edit { it + ("alchemy" to "A-KEY") + ("infura" to "I-KEY") })
        assertEquals(mapOf("alchemy" to "A-KEY", "infura" to "I-KEY"), store.apiKeys.first())
        // Another instance over the same file and Keystore key reads them back.
        assertEquals(
            mapOf("alchemy" to "A-KEY", "infura" to "I-KEY"),
            RpcKeyStore(file, AesGcmCipher { key }).apiKeys.first(),
        )
        // Unknown providers are dropped, as for the plain-text form.
        assertTrue(store.edit { it + ("nope" to "X") - "infura" })
        assertEquals(mapOf("alchemy" to "A-KEY"), store.apiKeys.first())
        assertTrue(store.edit { it - "alchemy" })
        assertEquals(emptyMap<String, String>(), store.apiKeys.first())
        assertTrue(file.state.value.asMap().isEmpty())
    }

    @Test
    fun `the stored blob is ciphertext with a fresh IV each write`() = runBlocking {
        val key = aesKey()
        val file = NodeSettingsEnsRpcTest.MemoryStore()
        val store = RpcKeyStore(file, AesGcmCipher { key })
        store.edit { mapOf("drpc" to "SECRET-DRPC-KEY") }
        val first = file.state.value.asMap().values.single() as String
        assertFalse(first.contains("SECRET-DRPC-KEY"))
        assertFalse(first.contains("drpc"))
        store.edit { mapOf("drpc" to "SECRET-DRPC-KEY") }
        val second = file.state.value.asMap().values.single() as String
        assertNotEquals(first, second)
    }

    @Test
    fun `a blob that won't decrypt reads as no keys, and the next save replaces it`() = runBlocking {
        val file = NodeSettingsEnsRpcTest.MemoryStore()
        val lost = aesKey()
        RpcKeyStore(file, AesGcmCipher { lost }).edit { mapOf("infura" to "OLD") }
        // The Keystore key is gone (a new one in its place): the tag fails.
        val other = aesKey()
        val store = RpcKeyStore(file, AesGcmCipher { other })
        assertEquals(emptyMap<String, String>(), store.apiKeys.first())
        assertTrue(store.edit { it + ("alchemy" to "NEW") })
        assertEquals(mapOf("alchemy" to "NEW"), store.apiKeys.first())
    }

    @Test
    fun `a tampered blob fails authentication`() {
        val key = aesKey()
        val cipher = AesGcmCipher { key }
        val blob = cipher.encrypt("hello".toByteArray())
        assertEquals("hello", String(cipher.decrypt(blob)))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(blob) }.isFailure)
    }
}
