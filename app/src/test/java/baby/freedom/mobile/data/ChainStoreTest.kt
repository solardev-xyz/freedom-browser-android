package baby.freedom.mobile.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import baby.freedom.mobile.browser.Icu4jUts46
import baby.freedom.mobile.browser.WhatwgHost
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChainStoreTest {
    @Before
    fun icu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(data.value).also { data.value = it }
    }

    private class BrokenStore(error: IOException) : DataStore<Preferences> {
        private val e = error
        override val data: Flow<Preferences> = flow { throw e }
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw e
    }

    private val polygon = Chain(
        id = 137, name = "Polygon", symbol = "POL", explorerUrl = "https://polygonscan.com",
        rpcUrls = listOf("https://polygon.drpc.org", "https://polygon-rpc.com"),
    )
    private val amoy = Chain(
        id = 80002, name = "Polygon Amoy", symbol = "POL", rpcUrls = listOf("https://rpc-amoy.polygon.technology"),
        isTestnet = true,
    )

    @Test
    fun listsBuiltInsFirst() = runBlocking {
        val store = ChainStore(MemoryStore())
        assertEquals(listOf(1L, 100L, 8453L), store.chains.first().map { it.id })
        assertTrue(store.chains.first().all { it.builtIn })
    }

    @Test
    fun addAndRemoveACustomChain() = runBlocking {
        var now = 1000L
        val store = ChainStore(MemoryStore(), clock = { now })
        assertEquals(ChainStore.AddResult.ADDED, store.add(amoy))
        now = 2000
        assertEquals(ChainStore.AddResult.ADDED, store.add(polygon))
        val listed = store.chains.first()
        assertEquals(listOf(1L, 100L, 8453L, 80002L, 137L), listed.map { it.id })
        assertEquals(polygon, listed.last())
        assertEquals(amoy, listed[3])

        assertEquals(ChainStore.RemoveResult.REMOVED, store.remove(80002))
        assertEquals(ChainStore.RemoveResult.NOT_FOUND, store.remove(80002))
        assertEquals(listOf(1L, 100L, 8453L, 137L), store.chains.first().map { it.id })
    }

    @Test
    fun builtInsCanNeitherBeAddedNorRemoved() = runBlocking {
        val store = ChainStore(MemoryStore())
        assertEquals(ChainStore.AddResult.BUILT_IN, store.add(polygon.copy(id = 8453)))
        assertEquals(ChainStore.RemoveResult.NOT_FOUND, store.remove(1))
        assertEquals(BuiltInChains.ALL, store.chains.first())
    }

    @Test
    fun aDuplicateIdIsRefused() = runBlocking {
        val store = ChainStore(MemoryStore())
        assertEquals(ChainStore.AddResult.ADDED, store.add(polygon))
        assertEquals(ChainStore.AddResult.DUPLICATE, store.add(polygon.copy(name = "Other")))
        assertEquals("Polygon", store.chains.first().last().name)
    }

    @Test
    fun aStoredEntryForABuiltInOrABadEntryIsIgnored() = runBlocking {
        val mem = MemoryStore()
        mem.edit {
            it[stringPreferencesKey("chain:8453")] = ChainStore.encode(polygon.copy(id = 8453), 1)
            it[stringPreferencesKey("chain:137")] = ChainStore.encode(polygon.copy(rpcUrls = listOf("http://10.0.0.1")), 1)
            it[stringPreferencesKey("chain:5")] = "{not json"
            it[stringPreferencesKey("chain:6")] = ChainStore.encode(amoy, 1) // key/id mismatch
        }
        val store = ChainStore(mem)
        assertEquals(BuiltInChains.ALL, store.chains.first())
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val (chain, at) = ChainStore.decode(ChainStore.encode(polygon, 42))!!
        assertEquals(polygon, chain)
        assertEquals(42L, at)
        assertEquals(amoy, ChainStore.decode(ChainStore.encode(amoy, 0))!!.first)
    }

    @Test
    fun storageTroubleNeverThrows() = runBlocking {
        val store = ChainStore(BrokenStore(CorruptionException("bad")), backOff = {})
        assertEquals(BuiltInChains.ALL, store.chains.first())
        assertEquals(ChainStore.AddResult.FAILED, store.add(polygon))
        assertEquals(ChainStore.RemoveResult.FAILED, store.remove(137))
    }

    // ---- the user's own RPCs (#108) ----

    @Test
    fun ownRpcsAttachToBuiltInAndCustomChains() = runBlocking {
        val store = ChainStore(MemoryStore())
        val mine = "https://my-node.example/eth"
        assertEquals(ChainStore.RpcAddResult.ADDED, store.addUserRpc(1, "  $mine "))
        assertEquals(ChainStore.RpcAddResult.DUPLICATE, store.addUserRpc(1, mine))
        assertEquals(ChainStore.RpcAddResult.PUBLIC, store.addUserRpc(1, BuiltInChains.ETHEREUM.rpcUrls[0]))
        // Name resolution may add one as yours: it doesn't ask the chain's public RPCs.
        val promoted = "https://rpc.flashbots.net"
        assertTrue(promoted in BuiltInChains.ETHEREUM.rpcUrls)
        assertEquals(ChainStore.RpcAddResult.ADDED, store.addUserRpc(1, promoted, allowPublic = true))
        assertEquals(listOf(mine, promoted), store.chains.first().first { it.id == 1L }.userRpcUrls)
        assertTrue(store.removeUserRpc(1, promoted))
        assertEquals(ChainStore.RpcAddResult.INVALID, store.addUserRpc(1, "http://192.168.1.10:8545"))
        assertEquals(ChainStore.RpcAddResult.NO_CHAIN, store.addUserRpc(137, mine))
        val eth = store.chains.first().first { it.id == 1L }
        assertEquals(listOf(mine), eth.userRpcUrls)
        assertEquals("the public list is untouched", BuiltInChains.ETHEREUM.rpcUrls, eth.rpcUrls)

        assertEquals(ChainStore.AddResult.ADDED, store.add(polygon))
        assertEquals(ChainStore.RpcAddResult.ADDED, store.addUserRpc(137, "http://127.0.0.1:8545"))
        assertEquals(listOf("http://127.0.0.1:8545"), store.chains.first().last().userRpcUrls)

        assertTrue(store.removeUserRpc(1, mine))
        assertEquals(emptyList<String>(), store.chains.first().first { it.id == 1L }.userRpcUrls)
    }

    @Test
    fun ownRpcsAreCappedAndGoWithTheirChain() = runBlocking {
        val store = ChainStore(MemoryStore())
        store.add(polygon)
        for (i in 1..Chain.MAX_USER_RPC_URLS) {
            assertEquals(ChainStore.RpcAddResult.ADDED, store.addUserRpc(137, "https://n$i.example"))
        }
        assertEquals(ChainStore.RpcAddResult.FULL, store.addUserRpc(137, "https://one-more.example"))
        store.remove(137)
        store.add(polygon)
        assertEquals("a re-added chain starts without the old ones", emptyList<String>(), store.chains.first().last().userRpcUrls)
    }

    @Test
    fun aBadStoredOwnRpcIsDropped() = runBlocking {
        val mem = MemoryStore()
        mem.edit {
            it[stringPreferencesKey("rpcs:1")] =
                """["https://ok.example","http://10.0.0.1","https://ethereum.publicnode.com",7,"https://ok.example"]"""
            it[stringPreferencesKey("rpcs:100")] = "{not json"
        }
        val chains = ChainStore(mem).chains.first()
        // A public RPC stored as yours stays (added from name resolution, which doesn't ask the chain's).
        assertEquals(
            listOf("https://ok.example", "https://ethereum.publicnode.com"),
            chains.first { it.id == 1L }.userRpcUrls,
        )
        assertEquals(emptyList<String>(), chains.first { it.id == 100L }.userRpcUrls)
    }

    @Test
    fun ownRpcStorageTroubleNeverThrows() = runBlocking {
        val store = ChainStore(BrokenStore(CorruptionException("bad")), backOff = {})
        assertEquals(ChainStore.RpcAddResult.FAILED, store.addUserRpc(1, "https://ok.example"))
        assertFalse(store.removeUserRpc(1, "https://ok.example"))
    }
}
