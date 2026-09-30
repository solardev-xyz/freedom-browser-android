package baby.freedom.mobile.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import baby.freedom.swarm.MyotisNetwork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.KeyGenerator

/** The light client's start-at-launch choice per chain (#274), and what the single #72 switch becomes. */
class NodeSettingsMyotisTest {

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun settings(file: MemoryStore) = NodeSettings.forTesting(
        file,
        ChainStore(MemoryStore()),
        RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
    )

    private fun atLaunch(settings: NodeSettings) = runBlocking { settings.myotisStartOnLaunch.first() }

    private val legacy = booleanPreferencesKey("myotis_enabled")

    @Test
    fun `off by default`() {
        assertEquals(emptySet<MyotisNetwork>(), atLaunch(settings(MemoryStore(emptyPreferences()))))
    }

    @Test
    fun `the old single switch on starts both chains at launch`() {
        val file = MemoryStore(mutablePreferencesOf(legacy to true))
        assertEquals(setOf(MyotisNetwork.Mainnet, MyotisNetwork.Gnosis), atLaunch(settings(file)))
    }

    @Test
    fun `the old single switch off starts neither`() {
        val file = MemoryStore(mutablePreferencesOf(legacy to false))
        assertEquals(emptySet<MyotisNetwork>(), atLaunch(settings(file)))
    }

    @Test
    fun `each chain's own choice overrides the old switch for that chain only`() = runBlocking {
        val file = MemoryStore(mutablePreferencesOf(legacy to true))
        val settings = settings(file)
        settings.setMyotisStartOnLaunch(MyotisNetwork.Mainnet, false)
        assertEquals(setOf(MyotisNetwork.Gnosis), atLaunch(settings))
        settings.setMyotisStartOnLaunch(MyotisNetwork.Gnosis, false)
        assertEquals(emptySet<MyotisNetwork>(), atLaunch(settings))
        settings.setMyotisStartOnLaunch(MyotisNetwork.Mainnet, true)
        assertEquals(setOf(MyotisNetwork.Mainnet), atLaunch(settings))
        // Kept under a key of its own per chain.
        assertEquals(true, file.state.value[booleanPreferencesKey("myotis_mainnet_start_on_launch")])
        assertEquals(false, file.state.value[booleanPreferencesKey("myotis_gnosis_start_on_launch")])
    }
}
