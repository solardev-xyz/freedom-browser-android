package baby.freedom.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ens.EnsRpcConfig
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name-resolution settings (#102) across the three stores they live
 * in: this file's switches, Ethereum mainnet's own RPCs in [ChainStore]
 * (your endpoints) and the encrypted API keys in [RpcKeyStore]. Writes
 * report whether they happened: one that would leave the resolver no
 * endpoint is refused, and the settings page tells the user instead of
 * closing as though it saved.
 */
class NodeSettingsEnsRpcTest {

    internal class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val settingsFile = MemoryStore()
    private val chainFile = MemoryStore()
    private val secretsFile = MemoryStore()
    private val chains = ChainStore(chainFile)
    private val keys = RpcKeyStore(secretsFile, AesGcmCipher { aes })
    private val settings = NodeSettings.forTesting(settingsFile, chains, keys)
    private fun config() = runBlocking { settings.ensRpcConfig.first() }
    private fun mainnetRpcs() = runBlocking {
        chains.chains.first().first { it.id == BuiltInChains.ETHEREUM.id }.userRpcUrls
    }

    @Test
    fun `switching off the last endpoint is refused and reported`() = runBlocking {
        val all = EnsRpcConfig.PUBLIC_ENDPOINTS
        for (url in all.dropLast(1)) {
            assertEquals(NodeSettings.EnsEdit.DONE, settings.setPublicEnsRpcEnabled(url, false))
        }
        // The last one: refused, nothing written.
        assertEquals(NodeSettings.EnsEdit.LAST_ENDPOINT, settings.setPublicEnsRpcEnabled(all.last(), false))
        assertEquals(listOf(all.last()), config().endpoints)
    }

    @Test
    fun `removing the last own endpoint or key is refused and reported`() = runBlocking {
        assertEquals(NodeSettings.AddEndpointResult.ADDED, settings.addEnsRpcEndpoint("https://my.node"))
        for (url in EnsRpcConfig.PUBLIC_ENDPOINTS) {
            assertEquals(NodeSettings.EnsEdit.DONE, settings.setPublicEnsRpcEnabled(url, false))
        }
        assertEquals(NodeSettings.EnsEdit.LAST_ENDPOINT, settings.removeEnsRpcEndpoint("https://my.node"))
        assertEquals(listOf("https://my.node"), config().customEndpoints)

        assertEquals(NodeSettings.EnsEdit.DONE, settings.setRpcApiKey("infura", "KEY"))
        assertEquals(NodeSettings.EnsEdit.DONE, settings.removeEnsRpcEndpoint("https://my.node"))
        assertEquals(NodeSettings.EnsEdit.LAST_ENDPOINT, settings.setRpcApiKey("infura", ""))
        assertEquals(mapOf("infura" to "KEY"), config().apiKeys)
    }

    @Test
    fun `add reports duplicates, including a trailing slash, and a full list`() = runBlocking {
        assertEquals(NodeSettings.AddEndpointResult.ADDED, settings.addEnsRpcEndpoint("https://my.node/rpc"))
        assertEquals(NodeSettings.AddEndpointResult.DUPLICATE, settings.addEnsRpcEndpoint("https://MY.node/rpc/"))
        assertEquals(NodeSettings.AddEndpointResult.INVALID, settings.addEnsRpcEndpoint("ftp://x"))
        // A LAN address is refused for every chain's RPCs, this list included.
        assertEquals(NodeSettings.AddEndpointResult.INVALID, settings.addEnsRpcEndpoint("http://192.168.1.5:8545"))
        for (i in 2..EnsRpcConfig.MAX_CUSTOM_ENDPOINTS) {
            assertEquals(NodeSettings.AddEndpointResult.ADDED, settings.addEnsRpcEndpoint("https://n$i.node"))
        }
        assertEquals(NodeSettings.AddEndpointResult.FULL, settings.addEnsRpcEndpoint("https://one-more.node"))
        assertEquals(EnsRpcConfig.MAX_CUSTOM_ENDPOINTS, config().customEndpoints.size)
    }

    @Test
    fun `your endpoints are Ethereum mainnet's own RPCs, both ways`() = runBlocking {
        // Added here: on the chain's list, for every mainnet read.
        settings.addEnsRpcEndpoint("https://a.node")
        assertEquals(listOf("https://a.node"), mainnetRpcs())
        // Added on the chain page: first in the resolution order here.
        assertEquals(ChainStore.RpcAddResult.ADDED, chains.addUserRpc(1, "https://b.node"))
        assertEquals(listOf("https://a.node", "https://b.node"), config().customEndpoints)
        assertEquals(listOf("https://a.node", "https://b.node"), config().endpoints.take(2))
        // Reordered here: that's the chain's order too.
        assertEquals(NodeSettings.EnsEdit.DONE, settings.moveEnsRpcEndpoint("https://b.node", -1))
        assertEquals(listOf("https://b.node", "https://a.node"), mainnetRpcs())
        // Another chain's RPCs aren't names' business.
        assertEquals(ChainStore.RpcAddResult.ADDED, chains.addUserRpc(100, "https://gnosis.node"))
        assertEquals(listOf("https://b.node", "https://a.node"), config().customEndpoints)
    }

    @Test
    fun `one of the chain page's public RPCs can be added for names, and is asked first`() = runBlocking {
        // Name resolution never asks the chain's public RPCs on its own.
        val chainPublic = BuiltInChains.ETHEREUM.rpcUrls.filter { url ->
            EnsRpcConfig.PUBLIC_ENDPOINTS.none { EnsRpcConfig.endpointKey(it) == EnsRpcConfig.endpointKey(url) }
        }
        val url = chainPublic.first()
        assertFalse(url in config().endpoints)

        assertEquals(NodeSettings.AddEndpointResult.ADDED, settings.addEnsRpcEndpoint(url))
        assertEquals(url, config().endpoints.first())
        assertEquals(listOf(url), mainnetRpcs())
        // Asked once: yours, then the rest of the chain's public RPCs.
        val eth = chains.chains.first().first { it.id == BuiltInChains.ETHEREUM.id }
        assertEquals(1, (eth.userRpcUrls + eth.rpcUrls).distinct().count { it == url })
        assertEquals(NodeSettings.AddEndpointResult.DUPLICATE, settings.addEnsRpcEndpoint(url))
        // The chain page still refuses it: it asks its public RPCs already.
        assertEquals(ChainStore.RpcAddResult.PUBLIC, chains.addUserRpc(1, chainPublic[1]))
        // Removable like any other of yours.
        assertEquals(NodeSettings.EnsEdit.DONE, settings.removeEnsRpcEndpoint(url))
        assertEquals(emptyList<String>(), mainnetRpcs())
    }

    @Test
    fun `API keys are stored encrypted, never in plain text`() = runBlocking {
        val secret = "sk_live_0123456789abcdefSECRET"
        assertEquals(NodeSettings.EnsEdit.DONE, settings.setRpcApiKey("alchemy", secret))
        assertEquals(mapOf("alchemy" to secret), config().apiKeys)
        assertTrue(config().endpoints.any { secret in it })
        // Nothing on "disk" spells it out: not the settings file, not the secrets file.
        for (file in listOf(settingsFile, secretsFile, chainFile)) {
            assertFalse(file.state.value.asMap().values.joinToString().contains(secret))
        }
        assertTrue(secretsFile.state.value.asMap().isNotEmpty())
        assertNull(settingsFile.state.value[RpcKeyStore.LEGACY_KEY])
    }

    @Test
    fun `plain-text keys and endpoints from an earlier build move on first read`() = runBlocking {
        val oldSettings = MemoryStore(
            mutablePreferencesOf(
                RpcKeyStore.LEGACY_KEY to """{"infura":"OLDKEY","drpc":"DKEY"}""",
                stringPreferencesKey("ens_rpc_custom_endpoints") to
                    """["https://mine.node","http://192.168.1.5:8545","https://mine.node","https://rpc.flashbots.net"]""",
            ),
        )
        // A key saved in the new store already wins over the old copy.
        assertTrue(keys.edit { mapOf("drpc" to "NEWER") })
        val upgraded = NodeSettings.forTesting(oldSettings, chains, keys)

        val c = upgraded.ensRpcConfig.first()
        assertEquals(mapOf("infura" to "OLDKEY", "drpc" to "NEWER"), c.apiKeys)
        // Onto mainnet's list; the LAN address it refuses, and the repeat, are
        // dropped — one of the chain's public RPCs is kept, names never ask it otherwise.
        val mine = listOf("https://mine.node", "https://rpc.flashbots.net")
        assertEquals(mine, c.customEndpoints)
        assertEquals(mine, mainnetRpcs())
        // And gone from the settings file: no plain-text key left behind.
        assertNull(oldSettings.state.value[RpcKeyStore.LEGACY_KEY])
        assertNull(oldSettings.state.value[stringPreferencesKey("ens_rpc_custom_endpoints")])
        assertFalse(secretsFile.state.value.asMap().values.joinToString().contains("OLDKEY"))

        // A later start reads the same, and moves nothing twice.
        val again = NodeSettings.forTesting(oldSettings, chains, keys)
        assertEquals(c, again.ensRpcConfig.first())
        assertEquals(mine, mainnetRpcs())
    }

    @Test
    fun `a key store that fails to save keeps the plain-text copy until it can`() = runBlocking {
        val oldSettings = MemoryStore(mutablePreferencesOf(RpcKeyStore.LEGACY_KEY to """{"infura":"OLDKEY"}"""))
        var broken = true
        var attempts = 0
        val failing = object : SecretCipher {
            val real = AesGcmCipher { aes }
            override fun encrypt(plain: ByteArray): ByteArray {
                attempts++
                return if (broken) throw IllegalStateException("keystore unavailable") else real.encrypt(plain)
            }
            override fun decrypt(blob: ByteArray): ByteArray = real.decrypt(blob)
        }
        var now = 1_000_000L
        val upgraded = NodeSettings.forTesting(oldSettings, chains, RpcKeyStore(secretsFile, failing)) { now }
        // Still used meanwhile, not silently dropped.
        assertEquals(mapOf("infura" to "OLDKEY"), upgraded.ensRpcConfig.first().apiKeys)
        assertTrue(upgraded.ensRpcConfig.first().endpoints.any { "OLDKEY" in it })
        // Not lost: still where it was, to move next time.
        assertEquals("""{"infura":"OLDKEY"}""", oldSettings.state.value[RpcKeyStore.LEGACY_KEY])
        // Every lookup doesn't repeat the failing write: it waits before trying again.
        val tried = attempts
        assertTrue(tried > 0)
        repeat(5) { upgraded.ensRpcConfig.first() }
        assertEquals(tried, attempts)
        now += 30_000
        upgraded.ensRpcConfig.first()
        assertTrue(attempts > tried)
        // Backs off further after another failure.
        val tried2 = attempts
        now += 30_000
        upgraded.ensRpcConfig.first()
        assertEquals(tried2, attempts)

        broken = false
        now += 60_000
        assertEquals(mapOf("infura" to "OLDKEY"), upgraded.ensRpcConfig.first().apiKeys)
        assertNull(oldSettings.state.value[RpcKeyStore.LEGACY_KEY])
    }

    @Test
    fun `removing a key not yet moved out of plain text removes it`() = runBlocking {
        val oldSettings = MemoryStore(mutablePreferencesOf(RpcKeyStore.LEGACY_KEY to """{"infura":"OLDKEY","drpc":"D"}"""))
        val failing = object : SecretCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw IllegalStateException("keystore unavailable")
            override fun decrypt(blob: ByteArray): ByteArray = throw IllegalStateException("keystore unavailable")
        }
        val upgraded = NodeSettings.forTesting(oldSettings, chains, RpcKeyStore(secretsFile, failing))
        assertEquals(mapOf("infura" to "OLDKEY", "drpc" to "D"), upgraded.ensRpcConfig.first().apiKeys)
        // Clearing needs no encryption, and takes the plain-text copy with it:
        // otherwise that copy would go on answering for the provider.
        assertEquals(NodeSettings.EnsEdit.DONE, upgraded.setRpcApiKey("infura", ""))
        assertEquals(mapOf("drpc" to "D"), upgraded.ensRpcConfig.first().apiKeys)
        assertEquals("""{"drpc":"D"}""", oldSettings.state.value[RpcKeyStore.LEGACY_KEY])
    }
}
