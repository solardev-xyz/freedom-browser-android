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
        val starts = java.util.concurrent.atomic.AtomicInteger()
        override fun start(home: String, alias: String): String {
            calls += "start"
            starts.incrementAndGet()
            startEntered.countDown()
            releaseStart.await(5, TimeUnit.SECONDS)
            if (startResult.contains("did")) running = true
            return startResult
        }
        /** The key each [startWithKey] got, as it was when the call began. */
        val keysGiven: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** The arrays [startWithKey] got, to check they're zeroed afterwards. */
        val keyArrays: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        override fun startWithKey(home: String, alias: String, secretKey: ByteArray): String {
            keysGiven += secretKey.joinToString("") { "%02x".format(it) }
            keyArrays += secretKey
            calls += "startWithKey"
            starts.incrementAndGet()
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
        /**
         * The fetch reaches a peer transfer, during which (like libradicle)
         * a cancel is recorded but not acted on until the fetch is released.
         */
        @Volatile var transferring = false
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
            if (transferring) onProgress("""{"phase":"fetching","nid":"z6MkSeed"}""")
            releaseClone.await(5, TimeUnit.SECONDS)
            cancellable = false
            lateProgress?.let(onProgress)
            return cloneResult
        }
        override fun cancelClone(rid: String): String {
            calls += "cancel:$rid"
            // Like libradicle: nothing to cancel before the fetch registers.
            if (!cancellable) return """{"cancelled":false}"""
            if (!ignoreCancel && !transferring) releaseClone.countDown()
            return """{"cancelled":true}"""
        }
        /** When set, the next [unseedRepo] call parks here until released. */
        @Volatile var unseedGate: CountDownLatch? = null
        val unseedEntered = CountDownLatch(1)
        override fun unseedRepo(rid: String): String {
            calls += "unseed:$rid"
            unseedGate?.let { gate ->
                unseedGate = null
                unseedEntered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            if (unseedFailures > 0) { unseedFailures--; return """{"error":"storage busy"}""" }
            // Like libradicle: nothing to talk to once the node is shut down.
            if (!running) return """{"error":"node not started"}"""
            repos = reposJson(RadicleNode.parseRepos(repos).orEmpty().filter { it.rid != rid })
            return """{"unseeded":true}"""
        }
        private fun reposJson(list: List<RadicleRepo>) = list.joinToString(",", "[", "]") {
            if (it.name.isEmpty()) """{"rid":"${it.rid}","name":null}""" else """{"rid":"${it.rid}","name":"${it.name}"}"""
        }
        /** Run (on the lifecycle thread) as [shutdown] returns. */
        @Volatile var afterShutdown: (() -> Unit)? = null
        override fun shutdown(): String {
            calls += "shutdown"
            afterShutdown?.invoke()
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
    fun browserCallsNeedARunningNodeAndStayOnTheList() {
        val ops = object : RadicleNode.Ops by FakeOps() {
            override fun call(method: String, args: org.json.JSONObject) = """{"ok":"$method"}"""
        }
        val node = RadicleNode(config, ops)
        val stopped = org.json.JSONObject(node.call("issues", org.json.JSONObject()))
        assertEquals("node-stopped", stopped.getString("reason"))
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        assertEquals("""{"ok":"issues"}""", node.call("issues", org.json.JSONObject()))
        // Seeding, importing and the lifecycle never go through this path.
        for (m in listOf("cloneRepo", "unseedRepo", "importRepo", "shutdown", "start")) {
            assertEquals("unsupported", org.json.JSONObject(node.call(m, org.json.JSONObject())).getString("reason"))
        }
        node.dispose()
    }

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
    fun aHostIdentityBootsWithItsKeyZeroedAfterAndShowsAsTheWallets() {
        val ops = FakeOps().apply { startResult = """{"did":"did:key:z6MkWallet"}""" }
        val key = ByteArray(32) { 7 }
        val node = RadicleNode(config.copy(identity = { RadicleNode.HostIdentity(key.copyOf(), "did:key:z6MkWallet") }), ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running && it.connectedPeers == 3 }
        assertEquals(listOf("startWithKey"), ops.calls.filter { it.startsWith("start") })
        assertEquals(listOf("07".repeat(32)), ops.keysGiven)
        // The copy handed over is zeroed once the call returns.
        assertTrue(ops.keyArrays.single().all { it == 0.toByte() })
        assertTrue(node.state.value.walletIdentity)
        node.dispose()
    }

    @Test
    fun withoutAHostIdentityTheProfilesOwnKeyBoots() {
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running && it.connectedPeers == 3 }
        assertEquals(listOf("start"), ops.calls.filter { it.startsWith("start") })
        assertFalse(node.state.value.walletIdentity)
        node.dispose()
    }

    @Test
    fun reloadIdentityRestartsOnlyANodeUpAsAnotherIdentity() {
        val ops = FakeOps()
        val host = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val node = RadicleNode(
            config.copy(identity = { host.get()?.let { RadicleNode.HostIdentity(ByteArray(32) { 1 }, it) } }),
            ops,
        )
        // Not started: nothing to restart (the next boot reads it anyway).
        node.reloadIdentity()
        Thread.sleep(100)
        assertEquals(0, ops.starts.get())

        node.start()
        await("running", node) { it.status == RadicleStatus.Running && it.connectedPeers == 3 }
        // Same identity (the device's own): left alone.
        node.reloadIdentity()
        Thread.sleep(200)
        assertEquals(listOf("start"), ops.calls.filter { it.startsWith("start") })

        // A wallet appears: shut down, then up as the wallet's.
        host.set("did:key:z6MkWallet")
        node.reloadIdentity()
        await("restarted as the wallet's", node) {
            it.status == RadicleStatus.Running && it.walletIdentity && ops.calls.count { c -> c == "startWithKey" } == 1
        }
        val order = ops.calls.filter { it == "start" || it == "startWithKey" || it == "shutdown" }
        assertEquals(listOf("start", "shutdown", "startWithKey"), order)
        // Asked again for the same identity: no second restart.
        node.reloadIdentity()
        Thread.sleep(200)
        assertEquals(2, ops.starts.get())

        // The wallet is removed: back to the profile's own key.
        host.set(null)
        node.reloadIdentity()
        await("back to its own", node) { it.status == RadicleStatus.Running && !it.walletIdentity && ops.starts.get() == 3 }
        assertEquals("start", ops.calls.last { it == "start" || it == "startWithKey" })
        node.dispose()
    }

    @Test
    fun reloadIdentityLeavesAStoppedNodeStopped() {
        val ops = FakeOps()
        val host = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val node = RadicleNode(
            config.copy(identity = { host.get()?.let { RadicleNode.HostIdentity(ByteArray(32) { 1 }, it) } }),
            ops,
        )
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        host.set("did:key:z6MkWallet")
        node.reloadIdentity()
        Thread.sleep(200)
        assertEquals(RadicleStatus.Stopped, node.state.value.status)
        assertEquals(1, ops.starts.get())
        // Turned on again later, it boots as the wallet's.
        node.start()
        await("running as the wallet's", node) { it.status == RadicleStatus.Running && it.walletIdentity }
        node.dispose()
    }

    @Test
    fun anUnreadableIdentityFailsTheBootInsteadOfRunningAsTheOwnKey() {
        val ops = FakeOps().apply { startResult = """{"did":"did:key:z6MkWallet"}""" }
        val readable = java.util.concurrent.atomic.AtomicBoolean(false)
        val node = RadicleNode(
            config.copy(identity = {
                check(readable.get()) { "keystore unavailable" }
                RadicleNode.HostIdentity(ByteArray(32) { 1 }, "did:key:z6MkWallet")
            }),
            ops,
        )
        node.start()
        await("error", node) { it.status == RadicleStatus.Error }
        assertEquals(0, ops.starts.get())
        // Once it can be read (the next unlock re-seals it), a reload boots as the wallet's.
        readable.set(true)
        node.reloadIdentity()
        await("running as the wallet's", node) { it.status == RadicleStatus.Running && it.walletIdentity }
        assertEquals(listOf("startWithKey"), ops.calls.filter { it.startsWith("start") })
        // Unreadable again at a later bind: the node stays as it is.
        readable.set(false)
        node.reloadIdentity()
        Thread.sleep(200)
        assertEquals(RadicleStatus.Running, node.state.value.status)
        assertFalse("shutdown" in ops.calls)
        node.dispose()
    }

    @Test
    fun aStopDuringAnIdentityReloadKeepsTheNodeOff() {
        val ops = FakeOps()
        val host = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val reading = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)
        val release = CountDownLatch(1)
        val node = RadicleNode(
            config.copy(identity = {
                reading.getAndSet(null)?.let { it.countDown(); release.await(5, TimeUnit.SECONDS) }
                host.get()?.let { RadicleNode.HostIdentity(ByteArray(32) { 1 }, it) }
            }),
            ops,
        )
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        host.set("did:key:z6MkWallet")
        val entered = CountDownLatch(1)
        reading.set(entered)
        node.reloadIdentity()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // The user turns Radicle off while the reload is deciding.
        node.stop()
        release.countDown()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        Thread.sleep(300)
        assertEquals(RadicleStatus.Stopped, node.state.value.status)
        assertEquals(1, ops.starts.get())

        // Many reloads racing a stop from another thread: whatever the
        // interleaving, the node ends up off.
        repeat(20) { i ->
            node.start()
            await("running $i", node) { it.status == RadicleStatus.Running }
            host.set(if (i % 2 == 0) null else "did:key:z6MkWallet")
            val stopper = Thread { node.stop() }
            node.reloadIdentity()
            stopper.start()
            stopper.join()
            await("stopped $i", node) { it.status == RadicleStatus.Stopped }
            Thread.sleep(50)
            assertEquals("round $i", RadicleStatus.Stopped, node.state.value.status)
        }
        node.dispose()
    }

    @Test
    fun movingToTheWalletIdentityWaitsForAFirstFetchInsteadOfUnseedingIt() {
        val ops = FakeOps()
        val host = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val node = RadicleNode(
            config.copy(identity = { host.get()?.let { RadicleNode.HostIdentity(ByteArray(32) { 1 }, it) } }),
            ops,
        )
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("fetching", node) { it.seed?.phase == "connecting" }
        host.set("did:key:z6MkWallet")
        node.reloadIdentity()
        Thread.sleep(300)
        // The fetch runs on: no cancel, no shutdown, no rollback.
        assertFalse(ops.calls.any { it.startsWith("cancel:") || it == "shutdown" || it.startsWith("unseed:") })
        ops.releaseClone.countDown()
        await("restarted as the wallet's", node) { it.status == RadicleStatus.Running && it.walletIdentity }
        assertEquals(listOf("start", "shutdown", "startWithKey"), ops.calls.filter { it.startsWith("start") || it == "shutdown" })
        assertFalse(ops.calls.any { it.startsWith("unseed:") })
        assertTrue(RadicleNode.parseRepos(ops.repos)!!.any { it.rid == rid })
        node.dispose()
    }

    @Test
    fun goingBackToTheOwnKeyDoesntWaitForAFetch() {
        val ops = FakeOps()
        val host = java.util.concurrent.atomic.AtomicReference<String?>("did:key:z6MkWallet")
        val node = RadicleNode(
            config.copy(identity = { host.get()?.let { RadicleNode.HostIdentity(ByteArray(32) { 1 }, it) } }),
            ops,
        )
        node.start()
        await("running", node) { it.status == RadicleStatus.Running && it.walletIdentity }
        node.seed(rid)
        await("fetching", node) { it.seed?.phase == "connecting" }
        // The wallet is removed: restart now (the stop cancels the fetch).
        host.set(null)
        node.reloadIdentity()
        await("back to its own", node) { it.status == RadicleStatus.Running && !it.walletIdentity }
        assertTrue(ops.calls.any { it.startsWith("cancel:") })
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
    fun anOnLandingAsTheStopFinishesKeepsStarting() {
        // Off -> on right as the stop job wraps up: its Stopped must never
        // overwrite the new start's Starting (#197 R3-F3). Racy by nature,
        // so hit the window from another thread many times over.
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        repeat(300) { i ->
            val go = CountDownLatch(1)
            ops.afterShutdown = { go.countDown() }
            val starter = Thread {
                go.await(5, TimeUnit.SECONDS)
                node.start()
            }.apply { start() }
            // The new boot parks inside ops.start, so nothing it publishes
            // can mask what the stop job left behind.
            ops.releaseStart = CountDownLatch(1)
            val boots = ops.starts.get()
            node.stop()
            starter.join(5_000)
            val until = System.currentTimeMillis() + 5_000
            while (ops.starts.get() == boots && System.currentTimeMillis() < until) Thread.sleep(1)
            assertEquals("round $i", RadicleStatus.Starting, node.state.value.status)
            ops.afterShutdown = null
            ops.releaseStart.countDown()
            await("running again", node) { it.status == RadicleStatus.Running }
        }
        node.dispose()
    }

    @Test
    fun aStopLandingAsTheBootFinishesNeverShowsRunningAgain() {
        // On -> off right as the boot writes Running: once stop() has
        // published Stopping, the boot must not put Running back over it
        // (#197 R4-F2). Racy by nature, so hit the window many times over.
        val ops = FakeOps()
        val node = RadicleNode(config, ops)
        repeat(300) { i ->
            val gate = CountDownLatch(1)
            ops.listGate = gate
            node.start()
            // The boot's refreshRepos parks just before the Running write.
            val until = System.currentTimeMillis() + 5_000
            while (ops.listGate != null && System.currentTimeMillis() < until) Thread.sleep(1)
            val stopper = Thread { gate.countDown(); node.stop() }.apply { start() }
            stopper.join(5_000)
            val shown = mutableListOf<RadicleStatus>()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val s = node.state.value.status
                if (shown.lastOrNull() != s) shown += s
                if (s == RadicleStatus.Stopped) break
            }
            assertFalse("round $i: $shown", RadicleStatus.Running in shown)
            assertEquals("round $i: $shown", RadicleStatus.Stopped, shown.last())
        }
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
    fun aStopMidTransferRollsBackWithoutWaitingOutThePeer() {
        // libradicle only checks the cancel token between peers, so a fetch
        // already transferring runs on; the stop mustn't sit out its wait.
        val ops = FakeOps().apply { transferring = true }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("fetching", node) { it.seed?.phase == "fetching" }
        val began = System.currentTimeMillis()
        node.stop()
        await("stopped", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
        assertTrue("stop didn't wait out the transfer", System.currentTimeMillis() - began < 2_000)
        val calls = ops.calls.toList()
        assertTrue("cancel asked: $calls", "cancel:$rid" in calls)
        assertTrue("rolled back before shutdown: $calls", calls.indexOf("unseed:$rid") in 0 until calls.indexOf("shutdown"))
        assertFalse("nothing left to replay", pendingFile.exists())
        ops.releaseClone.countDown()
        Thread.sleep(200)
        assertEquals("the fetch's own end doesn't unseed again", 1, ops.calls.count { it == "unseed:$rid" })
        node.start()
        await("running again", node) { it.status == RadicleStatus.Running }
        assertTrue(node.state.value.seededRepos.isEmpty())
        node.dispose()
    }

    @Test
    fun aSameRidSeedWaitsForAStaleFetchOfIt() {
        // libradicle's cancel tokens are keyed by RID in one global map, and a
        // fetch removes its RID's entry when it ends: a new fetch of the same
        // RID alongside a stale one would lose its token to it.
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
        node.seed(rid)
        await("refused while the stale fetch runs", node) {
            it.seed?.rid == rid && it.seed?.phase == "failed" && it.seed?.detail == RadicleNode.STALE_FETCH_DETAIL
        }
        assertEquals(1, ops.calls.count { it == "clone:$rid" })
        ops.releaseClone.countDown()
        val until = System.currentTimeMillis() + 5_000
        while (ops.calls.count { it == "clone:$rid" } < 2 && System.currentTimeMillis() < until) {
            node.seed(rid)
            Thread.sleep(20)
        }
        await("accepted once the stale fetch ended", node) { it.seed?.rid == rid && it.seed?.phase == "done" }
        node.dispose()
    }

    @Test
    fun anUnseededFetchStillTransferringDoesntBlockTheNextSeed() {
        // Stop seeding on a fetch mid-transfer: libradicle confirms the cancel
        // but runs on until that peer is done. The line is gone and Seed is
        // offered again, so a new seed must go through (another RID) or say
        // why not (the same RID), not be dropped without a word.
        val ops = FakeOps().apply { transferring = true; cloneResult = """{"cancelled":true}""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("fetching", node) { it.seed?.phase == "fetching" }
        node.unseed(rid)
        await("line dropped", node) { it.seed == null && "unseed:$rid" in ops.calls }
        node.seed(rid)
        await("same RID refused with a reason", node) {
            it.seed?.rid == rid && it.seed?.phase == "failed" && it.seed?.detail == RadicleNode.STALE_FETCH_DETAIL
        }
        val other = "rad:z2SzCC9zYnP17QRPZUhrP2RTEwZHj"
        node.seed(other)
        await("other RID fetching", node) { it.seed?.rid == other && "clone:$other" in ops.calls }
        ops.transferring = false
        ops.releaseClone.countDown()
        await("other RID settled", node) { it.seed?.rid == other && it.seed?.active == false }
        // The unseeded fetch's own end doesn't repaint a line over the new one.
        Thread.sleep(200)
        assertEquals(other, node.state.value.seed?.rid)
        assertFalse(node.state.value.seededRepos.any { it.rid == rid })
        node.dispose()
    }

    @Test
    fun aSameRidRetryWaitsForTheUnseededFetchsRollback() {
        // The unseeded fetch has ended but its rollback hasn't landed yet: a
        // retry of the same RID in that gap must wait, or the rollback takes
        // back the retry's policy and the RID silently stops being seeded.
        val ops = FakeOps().apply { transferring = true; cloneResult = """{"cancelled":true}""" }
        val node = RadicleNode(config, ops)
        node.start()
        await("running", node) { it.status == RadicleStatus.Running }
        node.seed(rid)
        await("fetching", node) { it.seed?.phase == "fetching" }
        node.unseed(rid)
        await("line dropped", node) { it.seed == null && "unseed:$rid" in ops.calls }
        val rollback = CountDownLatch(1)
        ops.unseedGate = rollback
        ops.transferring = false
        ops.releaseClone.countDown()
        assertTrue("rollback reached", ops.unseedEntered.await(5, TimeUnit.SECONDS))
        ops.cloneResult = """{"ok":true}"""
        node.seed(rid)
        await("retry refused while the rollback is pending", node) {
            it.seed?.rid == rid && it.seed?.detail == RadicleNode.STALE_FETCH_DETAIL
        }
        assertEquals(1, ops.calls.count { it == "clone:$rid" })
        rollback.countDown()
        val until = System.currentTimeMillis() + 5_000
        while (ops.calls.count { it == "clone:$rid" } < 2 && System.currentTimeMillis() < until) {
            node.seed(rid)
            Thread.sleep(20)
        }
        await("retry done and still seeded", node) {
            it.seed?.rid == rid && it.seed?.phase == "done" && it.seededRepos.any { r -> r.rid == rid }
        }
        Thread.sleep(200)
        assertTrue(node.state.value.seededRepos.any { it.rid == rid })
        assertEquals("done", node.state.value.seed?.phase)
        assertFalse(pendingFile.exists() && rid in pendingFile.readText())
        node.dispose()
    }

    @Test
    fun aSeedRacingAStopIsAlwaysCancelled() {
        // A seed landing at the same moment as a stop is either refused or
        // cancelled by it, never left to run out the stop's whole wait.
        repeat(40) { i ->
            val ops = FakeOps()
            val node = RadicleNode(config, ops)
            node.start()
            await("running #$i", node) { it.status == RadicleStatus.Running }
            val go = CountDownLatch(1)
            val seeder = Thread { go.await(); node.seed(rid) }.apply { start() }
            val stopper = Thread { go.await(); node.stop() }.apply { start() }
            val began = System.currentTimeMillis()
            go.countDown()
            seeder.join(); stopper.join()
            await("stopped #$i", node) { it.status == RadicleStatus.Stopped && "shutdown" in ops.calls }
            assertTrue("#$i stop waited out the fetch: ${ops.calls}", System.currentTimeMillis() - began < 2_000)
            ops.releaseClone.countDown()
            pendingFile.delete()
        }
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
        fun line(json: String) = RadicleNode.progressLine("rad:za", JSONObject(json))
        assertEquals("2 candidate seeds", line("""{"phase":"resolving","candidates":2}""").detail)
        assertEquals("1 candidate seed", line("""{"phase":"resolving","candidates":1}""").shown)
        assertEquals("from z6MkA", line("""{"phase":"fetching","nid":"z6MkA","index":1,"total":1}""").detail)
        assertEquals("a (1/3)", line("""{"phase":"connecting","addr":"a","index":1,"total":3}""").shown)
        assertEquals("timed out", line("""{"phase":"peer-failed","reason":"timed out"}""").detail)
        assertEquals("", line("""{"phase":"done"}""").detail)
    }

    /** Pages get [RadicleSeed.detail] (#280): English whatever the app language; the node page gets [RadicleSeed.shown]. */
    @Test
    fun seedDetailStaysEnglishForPages() {
        val english = ResourceXmlSwarmStrings()
        SwarmStrings.useForTest(object : SwarmStringSource {
            override fun string(id: Int, vararg args: Any?) = "[de] " + english.string(id, *args)
            override fun plural(id: Int, count: Int, vararg args: Any?) = "[de] " + english.plural(id, count, *args)
            override fun english(id: Int, vararg args: Any?) = english.string(id, *args)
            override fun englishPlural(id: Int, count: Int, vararg args: Any?) = english.plural(id, count, *args)
        })
        try {
            val resolving = RadicleNode.progressLine("rad:za", JSONObject("""{"phase":"resolving","candidates":3}"""))
            assertEquals("3 candidate seeds", resolving.detail)
            assertEquals("[de] 3 candidate seeds", resolving.shown)
            val fetching = RadicleNode.progressLine("rad:za", JSONObject("""{"phase":"fetching","nid":"z6MkA"}"""))
            assertEquals("from z6MkA", fetching.detail)
            assertEquals("[de] from z6MkA", fetching.shown)
            val stale = RadicleSeed.ofKey("rad:za", "failed", RadicleSeed.DETAIL_STALE_FETCH, active = false)
            assertEquals(RadicleNode.STALE_FETCH_DETAIL, stale.detail)
            assertTrue(!stale.detail.startsWith("[de]") && stale.shown.startsWith("[de]"))
            for (key in listOf(RadicleSeed.DETAIL_INVALID_RID, RadicleSeed.DETAIL_UNREADABLE_FETCH)) {
                val seed = RadicleSeed.ofKey("rad:za", "failed", key, active = false)
                assertTrue(seed.detail.isNotEmpty() && !seed.detail.startsWith("[de]"))
                assertEquals("[de] " + seed.detail, seed.shown)
            }
            // The native library's own words are the same on both sides.
            val failed = RadicleNode.progressLine("rad:za", JSONObject("""{"phase":"failed","reason":"no seeds found"}"""))
            assertEquals("no seeds found", failed.detail)
            assertEquals("no seeds found", failed.shown)
        } finally {
            SwarmStrings.useForTest(null)
        }
    }
}
