package baby.freedom.swarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
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
        /** Progress events sent after the fetch is released (i.e. after a cancel). */
        @Volatile var lateProgress: String? = null
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
        /** When set, the next [listSeededRepos] call parks here until released. */
        @Volatile var listGate: CountDownLatch? = null
        val listEntered = CountDownLatch(1)
        override fun listSeededRepos(): String {
            listGate?.let { gate ->
                listGate = null
                listEntered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            return repos
        }
        /** How long a fetch takes to register its cancel token after it's called. */
        @Volatile var registerDelayMs = 0L
        /** A fetch that doesn't wind down on cancel (only on [releaseClone]). */
        @Volatile var ignoreCancel = false
        /** Whether a fetch has registered its cancel token (libradicle's CancelToken). */
        @Volatile var cancellable = false
        /** How many upcoming [unseedRepo] calls fail. */
        @Volatile var unseedFailures = 0

        override fun cloneRepoWithProgress(rid: String, timeoutMs: Int, onProgress: (String) -> Unit): String {
            calls += "clone:$rid"
            Thread.sleep(registerDelayMs)
            cancellable = true
            // Like libradicle: the seeding policy is set before the fetch.
            val listed = RadicleNode.parseRepos(repos).orEmpty()
            if (listed.none { it.rid == rid }) repos = reposJson(listed + RadicleRepo(rid, ""))
            onProgress("""{"phase":"connecting","nid":"z6MkSeed","addr":"seed.example:8776","index":1,"total":2}""")
            releaseClone.await(5, TimeUnit.SECONDS)
            cancellable = false
            lateProgress?.let(onProgress)
            return cloneResult
        }
        override fun cancelClone(rid: String): String {
            calls += "cancel:$rid"
            // Like libradicle: nothing to cancel before the fetch registers.
            if (!cancellable) return """{"cancelled":false}"""
            if (!ignoreCancel) releaseClone.countDown()
            return """{"cancelled":true}"""
        }
        override fun unseedRepo(rid: String): String {
            calls += "unseed:$rid"
            if (unseedFailures > 0) { unseedFailures--; return """{"error":"storage busy"}""" }
            // Like libradicle: nothing to talk to once the node is shut down.
            if (!running) return """{"error":"node not started"}"""
            repos = reposJson(RadicleNode.parseRepos(repos).orEmpty().filter { it.rid != rid })
            return """{"unseeded":true}"""
        }
        private fun reposJson(list: List<RadicleRepo>) = list.joinToString(",", "[", "]") {
            if (it.name.isEmpty()) """{"rid":"${it.rid}","name":null}""" else """{"rid":"${it.rid}","name":"${it.name}"}"""
        }
        override fun shutdown(): String {
            calls += "shutdown"
            return if (running) { running = false; """{"ok":true}""" } else """{"error":"node not started"}"""
        }
    }

    private val home: File = Files.createTempDirectory("radicle-home").toFile().apply { deleteOnExit() }
    private val config = RadicleNode.Config(home = home.absolutePath, shortSocketDir = "/tmp")
    private val pendingFile get() = File(home, RadicleNode.PENDING_UNSEED_FILE)

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
    fun aFailedStartIsRetriedByTheNextStart() {
        val ops = FakeOps().apply { startResult = """{"error":"storage is locked"}""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("error", node) { it.status == RadicleStatus.Error }
        // A re-bind (or Retry) asks again; it must boot, not be swallowed.
        ops.startResult = """{"did":"did:key:z6MkTest"}"""
        node.start()
        await("running", node) { it.status == RadicleStatus.Running && it.connectedPeers == 3 }
        assertEquals(2, ops.calls.count { it == "start" })
        // While it's running, another start is still a no-op.
        node.start()
        Thread.sleep(200)
        assertEquals(2, ops.calls.count { it == "start" })
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
    }

    @Test
    fun aStopMidBootLeavesNoStaleDialToBlockTheNextBoot() {
        val gate = CountDownLatch(1)
        val ops = FakeOps().apply { listGate = gate }
        val node = RadicleNode(config, ops)
        node.start()
        // Park the boot after its generation check, before the dial/poller
        // launch, and stop it there.
        assertTrue(ops.listEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        gate.countDown()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        Thread.sleep(200)
        assertFalse("a superseded boot never dials", "connectSeeds" in ops.calls)
        node.start()
        await("running with peers", node) { it.status == RadicleStatus.Running && it.connectedPeers == 3 }
        assertEquals(1, ops.calls.count { it == "connectSeeds" })
        node.dispose()
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
        // The policy the failed fetch added is taken back, so the RID
        // doesn't linger as "Awaiting first fetch".
        await("rolled back", node) { "unseed:$rid" in ops.calls && it.seededRepos.isEmpty() }
        node.dispose()
    }

    @Test
    fun aFailedRefetchKeepsAnAlreadySeededRepo() {
        val ops = FakeOps().apply {
            repos = """[{"rid":"$rid","name":"heartwood"}]"""
            cloneResult = """{"error":"no seeds found"}"""
            releaseClone.countDown()
        }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running && it.seededRepos.isNotEmpty() }
        node.seed(rid)
        await("failed", node) { it.seed?.phase == "failed" }
        Thread.sleep(200)
        assertFalse("unseed:$rid" in ops.calls)
        assertEquals(listOf(RadicleRepo(rid, "heartwood")), node.state.value.seededRepos)
        node.dispose()
    }

    @Test
    fun unseedingAnInFlightSeedCancelsItAndDropsIt() {
        val ops = FakeOps().apply { cloneResult = """{"cancelled":true}""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("connecting", node) { it.seed?.phase == "connecting" }
        node.unseed(rid)
        await("dropped", node) { it.seededRepos.isEmpty() && it.seed?.active != true }
        assertTrue("cancel:$rid" in ops.calls)
        assertTrue("unseed:$rid" in ops.calls)
        node.dispose()
    }

    @Test
    fun unseedingAnInFlightSeedLeavesNoStaleOutcomeLine() {
        val ops = FakeOps().apply {
            cloneResult = """{"cancelled":true}"""
            // Progress still trickling in after the cancel lands.
            lateProgress = """{"phase":"fetching","nid":"z6MkLate"}"""
        }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("connecting", node) { it.seed?.phase == "connecting" }
        node.unseed(rid)
        await("rolled back", node) { ops.calls.toList().count { it == "unseed:$rid" } == 2 && it.seededRepos.isEmpty() }
        Thread.sleep(200)
        // Neither the late progress nor "cancelled" repaints the removed RID.
        assertNull(node.state.value.seed)
        node.dispose()
    }

    @Test
    fun unseedDropsARepoFromTheList() {
        val ops = FakeOps().apply { repos = """[{"rid":"$rid","name":null}]""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running && it.seededRepos.isNotEmpty() }
        node.unseed(rid)
        await("unseeded", node) { it.seededRepos.isEmpty() }
        assertEquals(listOf("unseed:$rid"), ops.calls.filter { it.startsWith("unseed:") })
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
    fun stopMidFetchRollsTheSeedBackBeforeShuttingDown() {
        val ops = FakeOps().apply { cloneResult = """{"cancelled":true}""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("connecting", node) { it.seed?.phase == "connecting" }
        assertEquals("pending rollback recorded while fetching", listOf(rid), pendingFile.readLines())
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        val calls = ops.calls.toList()
        val unseedAt = calls.indexOf("unseed:$rid")
        assertTrue("rolled back on a live node: $calls", unseedAt in 0 until calls.indexOf("shutdown"))
        assertEquals("[]", ops.repos)
        assertFalse("nothing left to replay", pendingFile.exists())
        // Back on: the RID isn't listed as "Awaiting first fetch".
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        assertTrue(node.state.value.seededRepos.isEmpty())
        node.dispose()
    }

    @Test
    fun aRollbackTheNodeMissedIsReplayedOnTheNextBoot() {
        // A previous process died mid-fetch: the policy stayed, the note too.
        pendingFile.writeText("$rid\n")
        val ops = FakeOps().apply { repos = """[{"rid":"$rid","name":null}]""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        assertTrue("unseed:$rid" in ops.calls)
        assertTrue("dropped before it's shown", node.state.value.seededRepos.isEmpty())
        assertFalse(pendingFile.exists())
        node.dispose()
    }

    @Test
    fun aSuccessfulSeedClearsItsPendingRollback() {
        val ops = FakeOps().apply { releaseClone.countDown() }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("done", node) { it.seed?.phase == "done" }
        assertFalse(pendingFile.exists())
        assertFalse("unseed:$rid" in ops.calls)
        node.dispose()
    }

    @Test
    fun aStopBeforeTheFetchBeginsNeverStartsIt() {
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        // Park the seed job in its "was it seeded?" check, then stop.
        val gate = CountDownLatch(1)
        ops.listGate = gate
        node.seed(rid)
        assertTrue(ops.listEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        gate.countDown()
        val began = System.currentTimeMillis()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        assertTrue("stop didn't wait out the fetch", System.currentTimeMillis() - began < 2_000)
        assertFalse("fetch never started: ${ops.calls}", ops.calls.any { it.startsWith("clone:") })
        assertFalse("nothing was added, so nothing to replay", pendingFile.exists())
    }

    @Test
    fun aStopBeforeTheFetchRegistersIsRetriedUntilItLands() {
        // The fetch has started but not yet registered its cancel token, so
        // the first cancel is a no-op.
        val ops = FakeOps().apply { registerDelayMs = 300; cloneResult = """{"cancelled":true}""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("fetch called", node) { "clone:$rid" in ops.calls }
        val began = System.currentTimeMillis()
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        assertTrue("stop didn't wait out the fetch", System.currentTimeMillis() - began < 2_000)
        val calls = ops.calls.toList()
        assertTrue("cancel asked again: $calls", calls.count { it == "cancel:$rid" } >= 2)
        assertTrue("rolled back before shutdown: $calls", calls.indexOf("unseed:$rid") in 0 until calls.indexOf("shutdown"))
        assertFalse(pendingFile.exists())
    }

    @Test
    fun aFetchThatOutlivesTheStopDoesntBlockSeedingOnTheNextBoot() {
        val ops = FakeOps().apply { ignoreCancel = true }
        val node = RadicleNode(config, ops, seedStopWaitMs = 200)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("connecting", node) { it.seed?.phase == "connecting" }
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        node.start()
        await("running again", node) { it.status == RadicleStatus.Running }
        // The boot replayed the stale fetch's rollback.
        assertFalse(pendingFile.exists())
        val other = "rad:z4V1sjrXqjvFdnCUbxPFqd5p4DtH5"
        node.seed(other)
        await("new seed accepted", node) { it.seed?.rid == other && "clone:$other" in ops.calls }
        // The stale fetch ending now doesn't clear the new one or touch the node.
        val unseedsBefore = ops.calls.count { it == "unseed:$rid" }
        ops.releaseClone.countDown()
        await("new seed done", node) { it.seed?.rid == other && it.seed?.phase == "done" }
        Thread.sleep(200)
        assertEquals(unseedsBefore, ops.calls.count { it == "unseed:$rid" })
        node.dispose()
    }

    @Test
    fun aReplayedRollbackThatFailsStaysPendingWhileStillSeeded() {
        pendingFile.writeText("$rid\n")
        val ops = FakeOps().apply { repos = """[{"rid":"$rid","name":null}]"""; unseedFailures = 1 }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        assertEquals("kept for the next boot", listOf(rid), pendingFile.readLines())
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        node.start()
        await("running again", node) { it.status == RadicleStatus.Running }
        assertTrue(node.state.value.seededRepos.isEmpty())
        assertFalse(pendingFile.exists())
        node.dispose()
    }

    @Test
    fun aReplayedRollbackThatFailsIsDroppedOnceNotSeeded() {
        pendingFile.writeText("$rid\n")
        val ops = FakeOps().apply { unseedFailures = 1 }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        assertTrue("unseed:$rid" in ops.calls)
        assertFalse("nothing left to take back", pendingFile.exists())
        node.dispose()
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
