package baby.freedom.mobile.wallet

import android.app.KeyguardManager
import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Android Keystore behind the vault (#76). Which half runs
 * depends on the device: with no screen lock, the device-only key and a
 * full create → relaunch → unlock round trip; with one, that the key
 * really refuses to seal anything until BiometricPrompt has authorised
 * the cipher, and isn't tied to the current set of biometrics.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreVaultStore(context)
    private val secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    private val phrase = Mnemonic.parse(
        "legal winner thank year wave sausage worth useful legal winner thank yellow",
    )

    @Before
    fun clean() = store.wipe()

    @After
    fun tearDown() = store.wipe()

    private fun key(): SecretKey =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("freedom.wallet.vault", null) as SecretKey

    private fun keyInfo(): KeyInfo =
        SecretKeyFactory.getInstance(key().algorithm, "AndroidKeyStore").getKeySpec(key(), KeyInfo::class.java) as KeyInfo

    @Test
    fun noScreenLock_deviceOnlyVaultRoundTrips() = runBlocking {
        assumeFalse("device has a screen lock", secure)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val noPrompt = VaultAuthenticator { _, _ -> fail("prompted without a screen lock"); throw AssertionError() }
        val vault = Vault(store, scope)
        vault.create(phrase, noPrompt, imported = false)
        val info = (vault.state.value as Vault.State.Unlocked).info
        assertEquals(VaultProtection.DEVICE_ONLY, info.protection)
        assertFalse(keyInfo().isUserAuthenticationRequired)

        // What's on disk: no words, in noBackupFilesDir.
        val file = File(context.noBackupFilesDir, "wallet/vault.json")
        val onDisk = file.readText()
        for (w in phrase.words.toSet()) assertFalse(w, onDisk.contains(w))

        // "Relaunch": a new Vault over the same store starts locked.
        val relaunched = Vault(store, scope)
        assertTrue(relaunched.state.value is Vault.State.Locked)
        relaunched.unlock(noPrompt)
        assertArrayEquals(phrase.seed(), relaunched.withSeed { it.copyOf() })
        relaunched.remove()
        assertFalse(file.exists())
    }

    @Test
    fun screenLock_keyNeedsTheUserForEveryUse() {
        assumeTrue("device has no screen lock", secure)
        val sealing = store.newSealingCipher(VaultProtection.SCREEN_LOCK)
        val info = keyInfo()
        assertTrue(info.isUserAuthenticationRequired)
        assertEquals(0, info.userAuthenticationValidityDurationSeconds)
        assertFalse(info.isInvalidatedByBiometricEnrollment)
        try {
            sealing.cipher.doFinal(phrase.phrase().toByteArray())
            fail("sealed without authentication")
        } catch (_: Exception) {
            // IllegalBlockSizeException wrapping KeyStoreException "Key user not authenticated".
        }
    }
}
