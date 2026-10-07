package baby.freedom.mobile.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import baby.freedom.mobile.browser.AdblockCategory
import baby.freedom.mobile.browser.AdblockLocaleDefaults
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A settings file that no longer parses: every setting reads as its
 * default and the next write replaces the file, rather than the read
 * throwing into ad blocking's startup collectors and crashing the app on
 * every launch.
 */
class NodeSettingsCorruptionTest {

    private val dir = Files.createTempDirectory("node-settings").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun corruptFile(): File =
        File(dir, "freedom_node_settings.preferences_pb").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xff.toByte()) + "garbage-not-a-proto".toByteArray())
        }

    private fun settingsOn(file: File) = NodeSettings.forTesting(
        PreferenceDataStoreFactory.create(corruptionHandler = NodeSettings.corruptionHandler, scope = scope) { file },
        ChainStore(MemoryStore()),
        RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
    )

    @Test
    fun `the file really is unreadable without a handler`() = runBlocking {
        val bare = PreferenceDataStoreFactory.create(scope = scope) { corruptFile() }
        try {
            bare.data.first()
            fail("a corrupt file read")
        } catch (e: CorruptionException) {
            // What every collector of NodeSettings got before.
        }
    }

    @Test
    fun `a corrupt file reads as the defaults`() = runBlocking {
        val settings = settingsOn(corruptFile())
        assertEquals(
            AdblockCategory.entries.filter { it.enabledByDefault }.toSet(),
            settings.adblockCategories.first(),
        )
        assertEquals(emptyList<String>(), settings.adblockAllowlist.first())
        assertEquals(true, settings.adblockAutoUpdate.first())
        assertEquals(true, settings.checkForUpdates.first())
    }

    /** #405 R1-M3: a language change while the app runs re-emits the categories the engine and Settings collect. */
    @Test
    fun `a language change re-emits the ad-blocking categories`() = runBlocking {
        val settings = NodeSettings.forTesting(
            MemoryStore(),
            ChainStore(MemoryStore()),
            RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
        )
        try {
            AdblockLocaleDefaults.refresh(listOf(Locale.GERMANY))
            val seen = Channel<Set<AdblockCategory>>(Channel.UNLIMITED)
            val job = scope.launch { settings.adblockCategories.collect { seen.send(it) } }
            assertTrue(AdblockCategory.GERMAN in withTimeout(5_000) { seen.receive() })
            AdblockLocaleDefaults.refresh(listOf(Locale.US))
            assertFalse(AdblockCategory.GERMAN in withTimeout(5_000) { seen.receive() })
            job.cancel()
        } finally {
            AdblockLocaleDefaults.refresh()
        }
    }

    @Test
    fun `the next write replaces it`() = runBlocking {
        val file = corruptFile()
        val settings = settingsOn(file)
        assertTrue(settings.addAdblockAllowlistHost("example.com"))
        assertEquals(listOf("example.com"), settings.adblockAllowlist.first())
        // The file on disk parses again, with no handler to fall back on.
        val copy = file.copyTo(File(dir, "copy.preferences_pb"))
        val reread = PreferenceDataStoreFactory.create(scope = scope) { copy }
        assertEquals(1, reread.data.first().asMap().size)
    }
}
