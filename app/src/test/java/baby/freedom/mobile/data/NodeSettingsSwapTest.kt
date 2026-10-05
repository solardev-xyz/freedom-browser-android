package baby.freedom.mobile.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.KeyGenerator

/** "Pay peers from the chequebook" (bee's swap-enable): the app's record, since ant doesn't persist it. */
class NodeSettingsSwapTest {

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun settings(file: MemoryStore) = NodeSettings.forTesting(
        file,
        ChainStore(MemoryStore()),
        RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
    )

    @Test
    fun `on by default, as in bee, ant and desktop`() = runBlocking {
        assertEquals(true, settings(MemoryStore(emptyPreferences())).swarmSwapEnabled.first())
    }

    @Test
    fun `a choice is kept, and read back by a fresh reader of the same file`() = runBlocking {
        val file = MemoryStore(emptyPreferences())
        settings(file).setSwarmSwapEnabled(false)
        assertEquals(false, file.state.value[booleanPreferencesKey("swarm_swap_enabled")])
        // What the `:node` process reads at a boot before the UI relays.
        assertEquals(false, settings(file).swarmSwapEnabled.first())
        settings(file).setSwarmSwapEnabled(true)
        assertEquals(true, settings(file).swarmSwapEnabled.first())
    }
}
