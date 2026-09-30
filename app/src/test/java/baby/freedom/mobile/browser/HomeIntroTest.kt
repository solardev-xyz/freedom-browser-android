package baby.freedom.mobile.browser

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import baby.freedom.mobile.data.AesGcmCipher
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import baby.freedom.mobile.data.RpcKeyStore
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The home page's first-run introduction and Swarm warm-up line (#278). */
class HomeIntroTest {
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun settings(file: MemoryStore) = NodeSettings.forTesting(
        file,
        ChainStore(MemoryStore()),
        RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
    )

    private val key = booleanPreferencesKey("intro_dismissed")

    @Test
    fun `a fresh install shows the introduction until it is dismissed, then never again`() = runBlocking {
        val file = MemoryStore()
        assertNull("undecided, so not shown yet", settings(file).introDismissed.first())
        settings(file).settleIntro { false }
        assertEquals(false, settings(file).introDismissed.first())
        settings(file).dismissIntro()
        assertEquals(true, file.state.value[key])
        // A later launch (a new instance over the same file) keeps it dismissed.
        assertEquals(true, settings(file).introDismissed.first())
    }

    @Test
    fun `an install already in use isn't greeted as a first launch`() = runBlocking {
        val file = MemoryStore()
        settings(file).settleIntro { true }
        assertEquals(true, settings(file).introDismissed.first())
    }

    @Test
    fun `an install with a changed setting isn't greeted as a first launch`() = runBlocking {
        // Upgrading with history cleared and no bookmarks, but a setting the
        // user once changed (only their own choices write this store).
        val file = MemoryStore(mutablePreferencesOf(booleanPreferencesKey("run_node_enabled") to false))
        var asked = false
        settings(file).settleIntro { asked = true; false }
        assertFalse("the setting alone settles it", asked)
        assertEquals(true, settings(file).introDismissed.first())
    }

    @Test
    fun `once decided, later starts don't decide again`() = runBlocking {
        // Shown on the first start; the pages visited since don't hide it.
        val file = MemoryStore()
        settings(file).settleIntro { false }
        var asked = false
        settings(file).settleIntro { asked = true; true }
        assertFalse(asked)
        assertEquals(false, settings(file).introDismissed.first())
        // And a dismissal stays one.
        val dismissed = MemoryStore(mutablePreferencesOf(key to true))
        settings(dismissed).settleIntro { false }
        assertEquals(true, settings(dismissed).introDismissed.first())
    }

    @Test
    fun `warm-up shows until the node has peers`() {
        fun w(status: NodeStatus, peers: Long = 0) =
            swarmWarmUp(NodeInfo(status = status, connectedPeers = peers), runNodeEnabled = true, external = false)
        assertEquals(SwarmWarmUp.Starting, w(NodeStatus.Stopped))
        assertEquals(SwarmWarmUp.Starting, w(NodeStatus.Starting))
        assertEquals(SwarmWarmUp.Connecting, w(NodeStatus.Running))
        assertNull(w(NodeStatus.Running, peers = 1))
        assertNull(w(NodeStatus.Running, peers = 42))
        assertEquals(SwarmWarmUp.Failed, w(NodeStatus.Error))
    }

    @Test
    fun `no warm-up before the node setting has been read`() {
        // A node-off user's cold start: the status reads Stopped and the
        // setting isn't known yet, which must not flash "Starting…".
        for (status in NodeStatus.entries) {
            assertNull(swarmWarmUp(NodeInfo(status = status), runNodeEnabled = null, external = false))
        }
    }

    @Test
    fun `no warm-up when the node is switched off or an external endpoint stands in`() {
        for (status in NodeStatus.entries) {
            val info = NodeInfo(status = status)
            assertNull(swarmWarmUp(info, runNodeEnabled = false, external = false))
            assertNull(swarmWarmUp(info, runNodeEnabled = true, external = true))
        }
    }

    @Test
    fun `explore lists iOS's curated site`() {
        assertEquals(listOf("app.swarmit.eth"), EXPLORE_CURATED.map { it.address })
    }
}
