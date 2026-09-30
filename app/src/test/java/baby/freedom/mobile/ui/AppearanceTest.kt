package baby.freedom.mobile.ui

import android.app.UiModeManager
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import baby.freedom.mobile.data.AesGcmCipher
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import baby.freedom.mobile.data.RpcKeyStore
import javax.crypto.KeyGenerator
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Appearance (#269): the stored choice, and what it paints. */
class AppearanceTest {
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun settings(file: DataStore<Preferences> = MemoryStore()) = NodeSettings.forTesting(
        file,
        ChainStore(MemoryStore()),
        RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
    )

    @Test
    fun `system default until a choice is made, then the choice`() = runBlocking {
        val file = MemoryStore()
        val settings = settings(file)
        assertEquals(Appearance.System, settings.appearance.first())
        for (choice in listOf(Appearance.Dark, Appearance.Light, Appearance.System)) {
            settings.setAppearance(choice)
            assertEquals(choice, settings.appearance.first())
            assertEquals(choice.key, file.state.value[stringPreferencesKey("appearance")])
        }
    }

    @Test
    fun `an unknown stored value reads as system default`() = runBlocking {
        val file = MemoryStore(mutablePreferencesOf(stringPreferencesKey("appearance") to "sepia"))
        assertEquals(Appearance.System, settings(file).appearance.first())
    }

    @Test
    fun `forced choices ignore the system, system default follows it`() {
        for (systemDark in listOf(false, true)) {
            assertFalse(Appearance.Light.isDark(systemDark))
            assertTrue(Appearance.Dark.isDark(systemDark))
            assertEquals(systemDark, Appearance.System.isDark(systemDark))
        }
    }

    @Test
    fun `each choice maps to its app night mode`() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, Appearance.System.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, Appearance.Light.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_YES, Appearance.Dark.nightMode)
    }

    @Test
    fun `keys round-trip`() {
        for (choice in Appearance.entries) assertEquals(choice, Appearance.fromKey(choice.key))
        assertEquals(Appearance.System, Appearance.fromKey(null))
    }

    /** A store whose reads fail (as DataStore's `data` does, ending the flow) [failures] times first. */
    private class FlakyStore(private val file: MemoryStore, failures: Int) : DataStore<Preferences> {
        val left = AtomicInteger(failures)
        override val data: Flow<Preferences> = flow {
            if (left.getAndDecrement() > 0) throw IOException("disk hiccup")
            emitAll(file.data)
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
            file.updateData(transform)
    }

    @Test
    fun `a read error doesn't end the flow, so a later choice still arrives`() = runTest {
        val file = MemoryStore()
        val settings = settings(FlakyStore(file, failures = 2))
        val seen = mutableListOf<Appearance>()
        val reader = launch { settings.appearance.take(2).toList(seen) }
        runCurrent()
        assertTrue("nothing read while the store is failing", seen.isEmpty())
        advanceTimeBy(NodeSettings.APPEARANCE_RETRY_FIRST_MS * 3 + 1)
        runCurrent()
        assertEquals(listOf(Appearance.System), seen)
        settings.setAppearance(Appearance.Dark)
        runCurrent()
        assertEquals(listOf(Appearance.System, Appearance.Dark), seen)
        reader.join()
    }
}
