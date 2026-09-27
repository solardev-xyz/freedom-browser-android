package baby.freedom.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import baby.freedom.mobile.ens.EnsRpcConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name-resolution writes (#102) report whether they happened: a
 * write that would leave the resolver no endpoint is refused, and the
 * settings page tells the user instead of closing as though it saved.
 */
class NodeSettingsEnsRpcTest {

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val settings = NodeSettings.forTesting(MemoryStore())
    private fun config() = runBlocking { settings.ensRpcConfig.first() }

    @Test
    fun `switching off the last endpoint is refused and reported`() = runBlocking {
        val all = EnsRpcConfig.PUBLIC_ENDPOINTS
        for (url in all.dropLast(1)) assertTrue(settings.setPublicEnsRpcEnabled(url, false))
        // The last one: refused, nothing written.
        assertFalse(settings.setPublicEnsRpcEnabled(all.last(), false))
        assertEquals(listOf(all.last()), config().endpoints)
    }

    @Test
    fun `removing the last own endpoint or key is refused and reported`() = runBlocking {
        assertEquals(NodeSettings.AddEndpointResult.ADDED, settings.addEnsRpcEndpoint("https://my.node"))
        for (url in EnsRpcConfig.PUBLIC_ENDPOINTS) assertTrue(settings.setPublicEnsRpcEnabled(url, false))
        assertFalse(settings.removeEnsRpcEndpoint("https://my.node"))
        assertEquals(listOf("https://my.node"), config().customEndpoints)

        assertTrue(settings.setRpcApiKey("infura", "KEY"))
        assertTrue(settings.removeEnsRpcEndpoint("https://my.node"))
        assertFalse(settings.setRpcApiKey("infura", ""))
        assertEquals(mapOf("infura" to "KEY"), config().apiKeys)
    }

    @Test
    fun `add reports duplicates, including a trailing slash, and a full list`() = runBlocking {
        assertEquals(NodeSettings.AddEndpointResult.ADDED, settings.addEnsRpcEndpoint("https://my.node/rpc"))
        assertEquals(NodeSettings.AddEndpointResult.DUPLICATE, settings.addEnsRpcEndpoint("https://MY.node/rpc/"))
        assertEquals(NodeSettings.AddEndpointResult.INVALID, settings.addEnsRpcEndpoint("ftp://x"))
        for (i in 2..EnsRpcConfig.MAX_CUSTOM_ENDPOINTS) {
            assertEquals(NodeSettings.AddEndpointResult.ADDED, settings.addEnsRpcEndpoint("https://n$i.node"))
        }
        assertEquals(NodeSettings.AddEndpointResult.FULL, settings.addEnsRpcEndpoint("https://one-more.node"))
        assertEquals(EnsRpcConfig.MAX_CUSTOM_ENDPOINTS, config().customEndpoints.size)
    }
}
