package baby.freedom.mobile.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.KeyGenerator

/** The Swarm cache size: the app's record, since ant doesn't persist its cap. */
class NodeSettingsSwarmCacheTest {

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = longPreferencesKey("swarm_cache_capacity_bytes")
    private val mib = 1024L * 1024L

    private fun settings(file: MemoryStore) = NodeSettings.forTesting(
        file,
        ChainStore(MemoryStore()),
        RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
    )

    @Test
    fun `the sizes are 256 MB to 4 GB, in MiB as ant counts them`() {
        assertEquals(
            listOf(256 * mib, 512 * mib, 1024 * mib, 2048 * mib, 4096 * mib),
            SwarmCacheSize.entries.map { it.bytes },
        )
        // All inside the 64 MiB–16 GiB ant clamps to, so none is changed under the user.
        assert(SwarmCacheSize.entries.all { it.bytes in 64 * mib..16 * 1024 * mib })
    }

    @Test
    fun `512 MB by default, ant's own default`() = runBlocking {
        val s = settings(MemoryStore(emptyPreferences()))
        assertEquals(SwarmCacheSize.MB_512, s.swarmCacheSize.first())
        assertEquals(512 * mib, s.swarmCacheCapacityBytes.first())
    }

    @Test
    fun `a choice is stored as bytes and read back by a fresh reader of the same file`() = runBlocking {
        val file = MemoryStore(emptyPreferences())
        settings(file).setSwarmCacheSize(SwarmCacheSize.GB_2)
        assertEquals(2048 * mib, file.state.value[key])
        // What the `:node` process reads at a boot before the UI relays.
        assertEquals(2048 * mib, settings(file).swarmCacheCapacityBytes.first())
        settings(file).setSwarmCacheSize(SwarmCacheSize.MB_256)
        assertEquals(SwarmCacheSize.MB_256, settings(file).swarmCacheSize.first())
    }

    @Test
    fun `a stored value that is none of the sizes reads as the default, not as an arbitrary cap`() = runBlocking {
        for (odd in listOf(0L, -1L, 3 * mib, 100 * 1024 * mib)) {
            val file = MemoryStore(mutablePreferencesOf(key to odd))
            assertEquals("$odd", SwarmCacheSize.MB_512, settings(file).swarmCacheSize.first())
        }
    }

    @Test
    fun `fromBytes maps each size's bytes back to it`() {
        for (size in SwarmCacheSize.entries) assertEquals(size, SwarmCacheSize.fromBytes(size.bytes))
        assertEquals(SwarmCacheSize.DEFAULT, SwarmCacheSize.fromBytes(null))
    }
}
