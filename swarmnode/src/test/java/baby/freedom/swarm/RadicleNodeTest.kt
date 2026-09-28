package baby.freedom.swarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RadicleNodeTest {
    /** Records libradicle calls; [start] and [cloneRepoWithProgress] block until released. */
    private class FakeOps : RadicleNode.Ops {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var releaseStart = CountDownLatch(0)
        val startEntered = CountDownLatch(1)
        @Volatile var startResult = """{"did":"did:key:z6MkTest"}"""
        @Volatile var peers = 3
        @Volatile var repos = "[]"
        val releaseClone = CountDownLatch(1)
        @Volatile var cloneResult = """{"ok":true}"""
        @Volatile var movedSocket: String? = null
        @Volatile var running = false

        override fun setSocketPath(path: String) { movedSocket = path }
        override fun start(home: String, alias: String): String {
            calls += "start"
            startEntered.countDown()
            releaseStart.await(5, TimeUnit.SECONDS)
            if (startResult.contains("did")) running = true
            return startResult
        }
        override fun connectSeeds(timeoutMs: Int): String {
            calls += "connectSeeds"
            return """{"connected":$peers,"target":4}"""
        }
        override fun identity() =
            """{"did":"did:key:z6MkTest","nid":"z6MkTest","alias":"freedom-android"}"""
        override fun status() = """{"connectedPeers":$peers}"""
        override fun listSeededRepos() = repos
        override fun cloneRepoWithProgress(rid: String, timeoutMs: Int, onProgress: (String) -> Unit): String {
            calls += "clone:$rid"
            onProgress("""{"phase":"connecting","nid":"z6MkSeed","addr":"seed.example:8776","index":1,"total":2}""")
            releaseClone.await(5, TimeUnit.SECONDS)
            return cloneResult
        }
        override fun cancelClone(rid: String): String {
            calls += "cancel:$rid"
            releaseClone.countDown()
            return """{"cancelled":true}"""
        }
        override fun shutdown(): String {
            calls += "shutdown"
            return if (running) { running = false; """{"ok":true}""" } else """{"error":"node not started"}"""
        }
    }

    private val config = RadicleNode.Config(home = "/data/user/0/app/files/radicle", shortSocketDir = "/tmp")

    private fun await(what: String, node: RadicleNode, cond: (RadicleInfo) -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond(node.state.value) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("$what; state=${node.state.value}", cond(node.state.value))
    }

    private val rid = "rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5"

    @Test
    fun startShowsIdentityAndPeers() {
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        await("running with peers", node) { it.status == RadicleStatus.Running && it.connectedPeers == 3 }
        val info = node.state.value
        assertEquals("did:key:z6MkTest", info.did)
        assertEquals("z6MkTest", info.nid)
        assertEquals("freedom-android", info.alias)
        assertTrue("seed book dialled", "connectSeeds" in ops.calls)
        // The default socket path fits, so RAD_SOCKET is left alone.
        assertNull(ops.movedSocket)
        node.dispose()
    }

    @Test
    fun startFailureIsReportedAndStopClearsIt() {
        val ops = FakeOps().apply { startResult = """{"error":"storage is locked"}""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("error", node) { it.status == RadicleStatus.Error }
        assertEquals("storage is locked", node.state.value.errorMessage)
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped }
        // Nothing booted, so nothing to shut down, and no stale error.
        assertFalse("shutdown" in ops.calls)
        assertNull(node.state.value.errorMessage)
    }

    @Test
    fun stopDuringStartShutsTheBootedNodeDown() {
        val ops = FakeOps().apply { releaseStart = CountDownLatch(1) }
        val node = RadicleNode(config, ops)
        node.start()
        assertTrue(ops.startEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        ops.releaseStart.countDown()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        Thread.sleep(200)
        assertEquals(RadicleStatus.Stopped, node.state.value.status)
        assertFalse("never dialled for a superseded start", "connectSeeds" in ops.calls)
    }

    @Test
    fun onOffOnEndsRunningWithOneBoot() {
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        node.stop()
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        Thread.sleep(200)
        assertEquals(RadicleStatus.Running, node.state.value.status)
        assertTrue(ops.running)
        node.dispose()
    }

    @Test
    fun seedReportsProgressThenDoneAndRefreshesRepos() {
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed("  rad://z3gqcJUoA1n9HaHKufZs5FCSGazv5 ")
        await("connecting", node) { it.seed?.phase == "connecting" }
        assertEquals(rid, node.state.value.seed?.rid)
        assertEquals("seed.example:8776 (1/2)", node.state.value.seed?.detail)
        // A second seed while one is in flight is ignored.
        node.seed("rad:z4V1sjrXqjvFdnCUbxPFqd5p4DtH5")
        ops.repos = """[{"rid":"$rid","name":"heartwood","description":"x"}]"""
        ops.releaseClone.countDown()
        await("done", node) { it.seed?.phase == "done" && it.seededRepos.isNotEmpty() }
        assertFalse(node.state.value.seed!!.active)
        assertEquals(listOf(RadicleRepo(rid, "heartwood")), node.state.value.seededRepos)
        assertEquals(1, ops.calls.count { it.startsWith("clone:") })
        node.dispose()
    }

    @Test
    fun seedFailureCarriesTheReason() {
        val ops = FakeOps().apply {
            cloneResult = """{"error":"no seeds found"}"""
            releaseClone.countDown()
        }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("failed", node) { it.seed?.phase == "failed" }
        assertEquals("no seeds found", node.state.value.seed?.detail)
        node.dispose()
    }

    @Test
    fun invalidRidNeverReachesTheNode() {
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed("https://example.com")
        await("rejected", node) { it.seed?.phase == "failed" }
        assertEquals("Not a valid repository ID", node.state.value.seed?.detail)
        assertFalse(ops.calls.any { it.startsWith("clone:") })
        node.dispose()
    }

    @Test
    fun stopCancelsAnInFlightSeed() {
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("connecting", node) { it.seed?.phase == "connecting" }
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped }
        assertTrue("cancel:$rid" in ops.calls)
        // The stopped node's page doesn't show the old fetch's outcome.
        Thread.sleep(200)
        assertNull(node.state.value.seed)
    }

    @Test
    fun longHomeMovesTheControlSocket() {
        val ops = FakeOps()
        val longHome = "/data/user/0/" + "x".repeat(90) + "/files/radicle"
        val node = RadicleNode(RadicleNode.Config(home = longHome, shortSocketDir = "/cache"), ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        assertEquals("/cache/rad.sock", ops.movedSocket)
        node.dispose()
    }

    @Test
    fun normalizesRids() {
        val bare = "z3gqcJUoA1n9HaHKufZs5FCSGazv5"
        assertEquals("rad:$bare", RadicleNode.normalizeRid(bare))
        assertEquals("rad:$bare", RadicleNode.normalizeRid("rad:$bare"))
        assertEquals("rad:$bare", RadicleNode.normalizeRid("rad://$bare"))
        assertEquals("rad:$bare", RadicleNode.normalizeRid(" rad:$bare\n"))
        // Base58: no 0/O/I/l, no case folding, must start with the multibase z.
        assertNull(RadicleNode.normalizeRid("rad:${bare.replace('3', '0')}"))
        assertNull(RadicleNode.normalizeRid("rad:Z${bare.drop(1)}"))
        assertNull(RadicleNode.normalizeRid("rad:"))
        assertNull(RadicleNode.normalizeRid("rad:z123"))
    }

    @Test
    fun parsesSeededRepos() {
        val repos = RadicleNode.parseRepos(
            """[{"rid":"rad:za","name":"heartwood","description":"d"},{"rid":"rad:zb","name":null},{"name":"no rid"}]""",
        )
        assertEquals(listOf(RadicleRepo("rad:za", "heartwood"), RadicleRepo("rad:zb", "")), repos)
        // The error shape isn't a list: keep what's shown.
        assertNull(RadicleNode.parseRepos("""{"error":"node not started"}"""))
    }

    @Test
    fun progressDetails() {
        fun detail(json: String) = RadicleNode.progressDetail(JSONObject(json))
        assertEquals("2 candidate seeds", detail("""{"phase":"resolving","candidates":2}"""))
        assertEquals("from z6MkA", detail("""{"phase":"fetching","nid":"z6MkA","index":1,"total":1}"""))
        assertEquals("timed out", detail("""{"phase":"peer-failed","reason":"timed out"}"""))
        assertEquals("", detail("""{"phase":"done"}"""))
    }
}
