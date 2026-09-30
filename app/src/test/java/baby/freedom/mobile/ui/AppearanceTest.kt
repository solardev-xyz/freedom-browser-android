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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Appearance (#269): the stored choice, and what it paints. */
class AppearanceTest {
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun settings(file: MemoryStore = MemoryStore()) = NodeSettings.forTesting(
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
}
