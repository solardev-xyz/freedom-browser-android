package baby.freedom.mobile.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Block Store client behind Google backup (#231), on a device
 * with Play services: an entry goes in, comes back byte for byte, and is
 * gone after delete. Uses its own key, never [PhraseBackup.KEY], so it
 * can't touch a real backup on the device. Skipped without Play services.
 */
@RunWith(AndroidJUnit4::class)
class GmsBlockStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val blockStore = GmsBlockStore(context)
    private val key = "baby.freedom.mobile.wallet.devicetest"

    @After
    fun cleanUp() = runBlocking {
        runCatching { blockStore.delete(key) }
        Unit
    }

    private fun assumePlayServices() = runBlocking {
        val here = try {
            blockStore.endToEndEncryptionAvailable()
            true
        } catch (_: BackupUnavailableException) {
            false
        }
        assumeTrue("no Play services / Block Store on this device", here)
    }

    @Test
    fun storeRetrieveDeleteRoundTrip() = runBlocking {
        assumePlayServices()
        // Device-only (no cloud): the test must never upload anything.
        val bytes = PhraseBackup.encode(PhraseBackup.Entry("legal winner thank year", cloud = false))
        blockStore.store(key, bytes, backupToCloud = false)
        assertArrayEquals(bytes, blockStore.retrieve(key))
        blockStore.delete(key)
        assertNull(blockStore.retrieve(key))
    }

    @Test
    fun availabilityMatchesTheClient() = runBlocking {
        assumePlayServices()
        val backup = PhraseBackup(blockStore)
        val expected = if (blockStore.endToEndEncryptionAvailable()) {
            PhraseBackup.Availability.READY
        } else {
            PhraseBackup.Availability.NOT_ENCRYPTED
        }
        assertEquals(expected, backup.availability())
    }
}
