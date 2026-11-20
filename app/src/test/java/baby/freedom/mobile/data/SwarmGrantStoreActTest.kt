package baby.freedom.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ACT rules (#540): one per (site, publisher), only for a connected site, gone with its connection. */
class SwarmGrantStoreActTest {
    private val dir = Files.createTempDirectory("swarm-grants").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SwarmGrantStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "freedom_swarm_grants.preferences_pb") },
    )
    private val site = "https://app.example"
    /** Ends with [site]'s text: its keys must not be read as [site]'s. */
    private val lookalike = "https://my-app.example"
    private val key = "02" + "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val otherKey = "03" + key.substring(2)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a rule needs a connection and names one publisher`() = runBlocking {
        assertFalse(store.allowAct(site, key))
        assertEquals(null, store.grantFor(site))
        store.connect(site, now = 1)
        assertTrue(store.allowAct(site, key, now = 5))
        assertTrue(store.allowAct(site, otherKey, now = 9))
        assertEquals(
            listOf(SwarmGrantStore.ActRule(otherKey, 9), SwarmGrantStore.ActRule(key, 5)),
            store.grantFor(site)!!.act,
        )
        // The other "always allow"s are untouched by it.
        assertEquals(emptySet<String>(), store.grantFor(site)!!.autoApprove)
    }

    @Test
    fun `rules are per site, by the exact origin`() = runBlocking {
        store.connect(site)
        store.connect(lookalike)
        store.connect("$site:8443")
        store.allowAct(lookalike, key)
        store.allowAct("$site:8443", key)
        assertEquals(emptyList<SwarmGrantStore.ActRule>(), store.grantFor(site)!!.act)
        assertEquals(listOf(key), store.grantFor(lookalike)!!.act.map { it.publicKey })
        // Disconnecting one doesn't take the other's.
        assertTrue(store.revoke(site))
        assertEquals(listOf(key), store.grantFor(lookalike)!!.act.map { it.publicKey })
        assertEquals(listOf(key), store.grantFor("$site:8443")!!.act.map { it.publicKey })
    }

    @Test
    fun `removing a rule, or disconnecting, takes it away for good`() = runBlocking {
        store.connect(site)
        store.allowAct(site, key)
        store.allowAct(site, otherKey)
        assertTrue(store.removeAct(site, key))
        assertEquals(listOf(otherKey), store.grantFor(site)!!.act.map { it.publicKey })
        assertTrue(store.revoke(site))
        store.connect(site)
        assertEquals("a reconnect inherits nothing", emptyList<SwarmGrantStore.ActRule>(), store.grantFor(site)!!.act)
    }
}
