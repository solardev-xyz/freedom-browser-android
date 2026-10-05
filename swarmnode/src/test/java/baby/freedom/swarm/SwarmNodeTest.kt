package baby.freedom.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SwarmNodeTest {
    /** Records native calls; [seed] and [init] block until released. */
    private class FakeOps : SwarmNode.NodeOps {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val seedEntered = CountDownLatch(1)
        val releaseSeed = CountDownLatch(1)
        val initEntered = CountDownLatch(1)
        val releaseInit = CountDownLatch(1)
        val shutDown = CountDownLatch(1)
        @Volatile var nextHandle = 1L
        /** [stopGateway] blocks until this opens; open by default. */
        @Volatile var releaseStop = CountDownLatch(0)
        val stopEntered = CountDownLatch(1)

        override fun seed(antDir: File) {
            calls += "seed"
            seedEntered.countDown()
            releaseSeed.await(5, TimeUnit.SECONDS)
        }
        override fun init(dataDir: String): Long {
            val h = nextHandle++
            calls += "init:$h"
            initEntered.countDown()
            releaseInit.await(5, TimeUnit.SECONDS)
            return h
        }
        /** What [initWithIdentity] was handed, copied before the node zeroes it; and the array itself. */
        val identities: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val identityArrays: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        override fun initWithIdentity(dataDir: String, identity: ByteArray): Long {
            identities += String(identity)
            identityArrays += identity
            val h = nextHandle++
            calls += "initWithIdentity:$h"
            initEntered.countDown()
            releaseInit.await(5, TimeUnit.SECONDS)
            return h
        }
        /** The account [accountInfo] reports; `0xabc<handle>` by default. */
        @Volatile var ethAddress: String? = null
        override fun accountInfo(handle: Long) =
            """{"eth_address":"${ethAddress ?: "0xabc$handle"}","overlay":"ff$handle","peer_id":"16Uiu2","agent":"ant-test"}"""
        /** Each [startGateway]'s mode, as `light:<rpc>` or `ultra-light`. */
        val gatewayModes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** How many more [startGateway] calls succeed before the rest throw; unlimited by default. */
        @Volatile var gatewayStartsLeft = Int.MAX_VALUE
        /** Each [startGateway]'s CORS allow-list. */
        val gatewayCors: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        override fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String, corsOrigins: List<String>) {
            calls += "gateway:$handle"
            gatewayStarted += handle
            gatewayCors += corsOrigins
            if (gatewayStartsLeft-- <= 0) throw RuntimeException("bind failed")
            gatewayModes += if (lightMode) "light:$gnosisRpc" else "ultra-light:$gnosisRpc"
        }
        override fun agentString(handle: Long) = "ant-test"
        /** What [peerCount] does before answering 0; nothing by default. */
        @Volatile var onPeerCount: () -> Unit = {}
        override fun peerCount(handle: Long): Int {
            onPeerCount()
            return 0
        }
        val resumeEntered = CountDownLatch(1)
        /** [resume] blocks until this opens; open by default. */
        @Volatile var releaseResume = CountDownLatch(0)
        override fun resume(handle: Long) {
            calls += "resume:$handle"
            resumeEntered.countDown()
            releaseResume.await(5, TimeUnit.SECONDS)
            calls += "resumed:$handle"
        }
        override fun wake(handle: Long) {
            calls += "wake:$handle"
        }
        override fun suspend(handle: Long) {
            calls += "suspend:$handle"
        }
        /** What a chain read got from [AntChainTransport] while [stopGateway]/[shutdown] ran. */
        val readsWhileStopping: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private fun probeChainRead() {
            readsWhileStopping += String(AntChainTransport.serve("{}".toByteArray()), Charsets.UTF_8)
        }
        override fun stopGateway(handle: Long) {
            calls += "stopGateway:$handle"
            probeChainRead()
            stopEntered.countDown()
            releaseStop.await(5, TimeUnit.SECONDS)
        }
        override fun shutdown(handle: Long) {
            calls += "shutdown:$handle"
            probeChainRead()
            shutDown.countDown()
        }

        /** What [storageStatus] answers: the connected batch. */
        @Volatile var storageStatusJson = """{"enabled":false}"""
        /** Run inside each spend, in place of ant's transactions. */
        @Volatile var onSpend: (String) -> String = { it }
        override fun storageStatus(handle: Long) = storageStatusJson
        override fun storageQuote(handle: Long, gnosisRpc: String, depth: Int, days: Long): String {
            calls += "quote:$handle:$gnosisRpc:$depth:$days"
            return """{"depth":$depth}"""
        }
        override fun storageTopupQuote(handle: Long, gnosisRpc: String, days: Long) = "{}"
        override fun storageValidity(handle: Long, gnosisRpc: String) = "{}"
        override fun storageBuyXdai(handle: Long, gnosisRpc: String, depth: Int, amountPerChunk: String, immutable: Boolean): String {
            calls += "buy:$handle:$depth:$amountPerChunk:$immutable"
            return onSpend("buy")
        }
        override fun storageTopupXdai(handle: Long, gnosisRpc: String, amountPerChunk: String): String {
            calls += "topup:$handle:$amountPerChunk"
            return onSpend("topup")
        }
        override fun storageConnectBatch(handle: Long, gnosisRpc: String, batchId: String): String {
            calls += "connect:$handle:$batchId"
            return onSpend("connect")
        }
        /** Run inside a discover, in place of ant's scan (and the chequebook deploy it may try). */
        @Volatile var onDiscover: () -> Unit = {}
        override fun storageDiscover(handle: Long, gnosisRpc: String): String {
            calls += "discover:$handle:$gnosisRpc"
            onDiscover()
            return """{"registered":[]}"""
        }

        /** Handles whose gateway has started. */
        val gatewayStarted: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
        /**
         * Each [setSwapEnabled], as `handle:enabled`, with `+gw` when that
         * handle's gateway had already started. Apart from [calls], whose
         * exact sequence other tests pin.
         */
        val swapCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** Whether [setSwapEnabled] throws, as ant's -2 does. */
        @Volatile var swapFails = false
        @Volatile var swapStatusJson = """{"supported":true,"swap_switch":true,"swap_enabled":true,"paying":false,"chequebook":null,"persisted":false}"""
        /** What [confirmChequeLiability] returns; each call recorded as `handle:chequebook`. */
        @Volatile var confirmResult = 0
        val confirms: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun setSwapEnabled(handle: Long, enabled: Boolean) {
            swapCalls += "$handle:$enabled" + if (handle in gatewayStarted) "+gw" else ""
            if (swapFails) throw RuntimeException("node loop gone")
        }
        override fun swapStatus(handle: Long) = swapStatusJson
        override fun confirmChequeLiability(handle: Long, chequebook: String): Int {
            confirms += "$handle:$chequebook"
            return confirmResult
        }

        /** The gateway's chequebook, 40 hex (all zeros for none), and the account's xBZZ in PLUR. */
        @Volatile var chequebookHex = "0".repeat(40)
        @Volatile var walletPlur = "0"
        /** What the chequebook holds, PLUR. */
        @Volatile var chequebookBalancePlur = "0"
        /** What a `POST /chequebook/deposit` does, in place of ant's transaction. */
        @Volatile var onDeposit: (String) -> SwarmNode.GatewayAnswer? =
            { SwarmNode.GatewayAnswer(201, "{\"transactionHash\":\"0x${"ee".repeat(32)}\"}") }
        override fun gateway(method: String, path: String, timeoutMs: Int): SwarmNode.GatewayAnswer? {
            calls += "gateway:$method $path"
            return when {
                method == "GET" && path == "/chequebook/address" ->
                    SwarmNode.GatewayAnswer(200, "{\"chequebookAddress\":\"0x$chequebookHex\"}")
                method == "GET" && path == "/chequebook/balance" ->
                    SwarmNode.GatewayAnswer(200, "{\"totalBalance\":\"$chequebookBalancePlur\",\"availableBalance\":\"0\"}")
                method == "GET" && path == "/wallet" ->
                    SwarmNode.GatewayAnswer(200, "{\"bzzBalance\":\"$walletPlur\",\"nativeTokenBalance\":\"1\"}")
                method == "POST" && path.startsWith("/chequebook/deposit?amount=") ->
                    onDeposit(path.substringAfter("amount="))
                else -> SwarmNode.GatewayAnswer(404, "")
            }
        }
    }

    private val config = SwarmNode.Config(dataDir = "/nonexistent")

    private fun awaitStatus(node: SwarmNode, status: NodeStatus) {
        val until = System.currentTimeMillis() + 5_000
        while (node.state.value.status != status && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(status, node.state.value.status)
    }

    @Test
    fun startsNormally() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals("ant-test", node.state.value.clientVersion)
        node.dispose()
    }

    @Test
    fun aStopWaitsForAResumeStillInsideAntBeforeShuttingItDown() {
        // ant_resume re-dials the bootnodes: a stop landing meanwhile must
        // not shut the node down (free its handle) under it.
        val ops = FakeOps().apply {
            releaseSeed.countDown()
            releaseInit.countDown()
            releaseResume = CountDownLatch(1)
        }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        node.resume()
        assertTrue(ops.resumeEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        Thread.sleep(300)
        assertTrue(ops.calls.toString(), ops.calls.none { it.startsWith("stopGateway:") || it.startsWith("shutdown:") })
        ops.releaseResume.countDown()
        assertTrue(ops.shutDown.await(5, TimeUnit.SECONDS))
        val calls = ops.calls.toList()
        assertTrue(calls.toString(), calls.indexOf("resumed:1") < calls.indexOf("stopGateway:1"))
        node.dispose()
    }

    @Test
    fun aStopWaitsForAPeerCountStillInsideAnt() {
        val counting = CountDownLatch(1)
        val releaseCount = CountDownLatch(1)
        val ops = FakeOps().apply {
            releaseSeed.countDown()
            releaseInit.countDown()
        }
        val node = SwarmNode(config, ops)
        ops.onPeerCount = {
            ops.calls += "peerCount"
            counting.countDown()
            releaseCount.await(5, TimeUnit.SECONDS)
            ops.calls += "counted"
        }
        node.start()
        assertTrue(counting.await(5, TimeUnit.SECONDS))
        node.stop()
        Thread.sleep(300)
        assertTrue(ops.calls.toString(), ops.calls.none { it.startsWith("shutdown:") })
        releaseCount.countDown()
        assertTrue(ops.shutDown.await(5, TimeUnit.SECONDS))
        val calls = ops.calls.toList()
        assertTrue(calls.toString(), calls.indexOf("counted") < calls.indexOf("stopGateway:1"))
        node.dispose()
    }

    @Test
    fun aLifecycleCallAfterStopNeverReachesAnt() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        node.stop()
        node.resume()
        node.suspend()
        node.onNetworkChanged()
        assertTrue(ops.shutDown.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertTrue(ops.calls.toString(), ops.calls.none { it.startsWith("resume") || it.startsWith("suspend") || it.startsWith("wake") })
        node.dispose()
    }

    @Test
    fun stopDuringSeedingNeverInitsAndStaysStopped() {
        val ops = FakeOps().apply { releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        assertTrue(ops.seedEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        ops.releaseSeed.countDown()
        Thread.sleep(300)
        assertEquals(NodeStatus.Stopped, node.state.value.status)
        assertEquals(listOf("seed"), ops.calls.toList())
        node.dispose()
    }

    @Test
    fun aStopRefusesChainReadsWhileTheGatewayStopsAndShutsDown() {
        // #300 R2-M1: a read coming in during the stop would hold ant's
        // stopGateway/shutdown for the reader's whole deadline.
        AntChainTransport.install({ """{"result":"0x1"}""" })
        try {
            val ops = FakeOps()
            val node = lightNode(ops)
            node.stop()
            assertTrue(ops.shutDown.await(5, TimeUnit.SECONDS))
            assertEquals(2, ops.readsWhileStopping.size)
            assertTrue(ops.readsWhileStopping.toString(), ops.readsWhileStopping.all { "the Swarm node is stopping" in it })
            // Served again once the stop is over.
            assertEquals("""{"result":"0x1"}""", String(AntChainTransport.serve("{}".toByteArray()), Charsets.UTF_8))
            node.dispose()
        } finally {
            AntChainTransport.install({ """{"result":"0x0"}""" })
        }
    }

    @Test
    fun aFailedGatewayStartShutsTheNodeDownWithChainReadsRefused() {
        // #300 R3-M2: the shutdown after a failed startGateway mustn't wait
        // behind a chain read ant began, as [stop]'s doesn't.
        AntChainTransport.install({ """{"result":"0x1"}""" })
        try {
            val ops = FakeOps().apply {
                releaseSeed.countDown()
                releaseInit.countDown()
                gatewayStartsLeft = 0
            }
            val node = SwarmNode(config, ops)
            node.start()
            awaitStatus(node, NodeStatus.Error)
            assertEquals(listOf("shutdown:1"), ops.calls.filter { it.startsWith("shutdown") })
            assertEquals(1, ops.readsWhileStopping.size)
            assertTrue(ops.readsWhileStopping.toString(), "the Swarm node is stopping" in ops.readsWhileStopping.single())
            // Served again once the shutdown is over.
            assertEquals("""{"result":"0x1"}""", String(AntChainTransport.serve("{}".toByteArray()), Charsets.UTF_8))
            node.dispose()
        } finally {
            AntChainTransport.install({ """{"result":"0x0"}""" })
        }
    }

    @Test
    fun stopDuringInitShutsTheNewNodeDownAndStaysStopped() {
        val ops = FakeOps().apply { releaseSeed.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        assertTrue(ops.initEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        ops.releaseInit.countDown()
        assertTrue(ops.shutDown.await(5, TimeUnit.SECONDS))
        assertEquals(NodeStatus.Stopped, node.state.value.status)
        assertEquals(listOf("seed", "init:1", "gateway:1", "stopGateway:1", "shutdown:1"), ops.calls.toList())
        node.dispose()
    }

    @Test
    fun restartDuringSeedingRunsExactlyOneNode() {
        val ops = FakeOps().apply { releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        assertTrue(ops.seedEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        node.start()
        ops.releaseSeed.countDown()
        awaitStatus(node, NodeStatus.Running)
        Thread.sleep(300)
        assertEquals(1, ops.calls.count { it.startsWith("init:") })
        assertTrue(ops.calls.none { it.startsWith("shutdown:") })
        node.dispose()
    }

    @Test
    fun restartAfterStopWaitsForTheOldNodeToShutDown() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        ops.releaseStop = CountDownLatch(1)
        node.stop()
        node.start()
        assertTrue(ops.stopEntered.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        // The old node still holds the gateway port: the new launch waits.
        assertTrue(ops.calls.none { it == "init:2" })
        ops.releaseStop.countDown()
        awaitStatus(node, NodeStatus.Running)
        val calls = ops.calls.toList()
        assertTrue(calls.indexOf("shutdown:1") in 0 until calls.indexOf("init:2"))
        node.dispose()
    }

    @Test
    fun withoutAWalletIdentityAntUsesItsOwn() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf("seed", "init:1", "gateway:1"), ops.calls.toList())
        assertEquals("0xabc1", node.state.value.accountAddress)
        assertEquals("ff1", node.state.value.overlay)
        assertFalse(node.state.value.walletIdentity)
        node.dispose()
    }

    @Test
    fun aWalletIdentityIsHandedToAntAndZeroedAfter() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config.copy(identity = { """{"signing_key":"aa"}""".toByteArray() }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf("seed", "initWithIdentity:1", "gateway:1"), ops.calls.toList())
        assertEquals(listOf("""{"signing_key":"aa"}"""), ops.identities.toList())
        assertTrue(ops.identityArrays.single().all { it.toInt() == 0 })
        assertTrue(node.state.value.walletIdentity)
        assertEquals("0xabc1", node.state.value.accountAddress)
        node.dispose()
    }

    @Test
    fun restartReadsTheIdentityAgain() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val current = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val node = SwarmNode(config.copy(identity = { current.get()?.toByteArray() }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertFalse(node.state.value.walletIdentity)
        current.set("wallet")
        node.restart()
        val until = System.currentTimeMillis() + 5_000
        while (!node.state.value.walletIdentity && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(NodeStatus.Running, node.state.value.status)
        assertTrue(node.state.value.walletIdentity)
        val calls = ops.calls.toList()
        assertTrue(calls.indexOf("shutdown:1") in 0 until calls.indexOf("initWithIdentity:2"))
        assertEquals("0xabc2", node.state.value.accountAddress)
        current.set(null)
        node.restart()
        val until2 = System.currentTimeMillis() + 5_000
        while (node.state.value.walletIdentity && System.currentTimeMillis() < until2) Thread.sleep(10)
        awaitStatus(node, NodeStatus.Running)
        assertFalse(node.state.value.walletIdentity)
        assertEquals("init:3", ops.calls.last { it.startsWith("init") })
        node.dispose()
    }

    @Test
    fun bootsUltraLightWithNoChainByDefault() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf("ultra-light:"), ops.gatewayModes.toList())
        assertFalse(node.state.value.lightMode)
        node.dispose()
    }

    @Test
    fun startsTheGatewayAllowingNoCorsOrigins() {
        // Not `null` (#284): a CORS fetch carries it after any cross-origin
        // redirect, so allowing it let any page read /wallet and /addresses.
        assertEquals(emptyList<String>(), SwarmNode.GATEWAY_CORS_ORIGINS)
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf(emptyList<String>()), ops.gatewayCors.toList())
        node.dispose()
    }

    @Test
    fun lightModeHandsTheGatewayItsRpcAndARestartReadsTheModeAgain() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val mode = java.util.concurrent.atomic.AtomicReference(SwarmNode.Mode.light("https://rpc.example"))
        val node = SwarmNode(config.copy(mode = { mode.get() }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf("light:https://rpc.example"), ops.gatewayModes.toList())
        assertTrue(node.state.value.lightMode)

        mode.set(SwarmNode.Mode.ULTRA_LIGHT)
        node.restart()
        val until = System.currentTimeMillis() + 5_000
        while (ops.gatewayModes.size < 2 && System.currentTimeMillis() < until) Thread.sleep(10)
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf("light:https://rpc.example", "ultra-light:"), ops.gatewayModes.toList())
        assertFalse(node.state.value.lightMode)
        node.stop()
        assertFalse(node.state.value.lightMode)
        node.dispose()
    }

    @Test
    fun aModeThatThrowsFailsTheStartAndStillZeroesTheIdentity() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val identity = """{"signing_key":"aa"}""".toByteArray()
        val node = SwarmNode(
            config.copy(identity = { identity }, mode = { throw IllegalStateException("no settings") }),
            ops,
        )
        node.start()
        awaitStatus(node, NodeStatus.Error)
        assertTrue(identity.all { it.toInt() == 0 })
        assertEquals(listOf("seed"), ops.calls.toList())
        node.dispose()
    }

    @Test
    fun lightModeNeedsAnRpc() {
        assertEquals(SwarmNode.Mode.ULTRA_LIGHT, SwarmNode.Mode.light("  "))
        assertFalse(SwarmNode.Mode.light("").light)
        val light = SwarmNode.Mode.light(" https://rpc.example ")
        assertTrue(light.light)
        assertEquals("https://rpc.example", light.gnosisRpc)
        // Its string form never carries the endpoint, which may hold a key.
        assertEquals("light", light.toString())
    }

    private fun lightNode(
        ops: FakeOps,
        dataDir: String = config.dataDir,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        bootId: () -> String? = { null },
    ): SwarmNode {
        ops.releaseSeed.countDown()
        ops.releaseInit.countDown()
        ops.ethAddress = "0x" + TestTx.OWNER.uppercase()
        val node = SwarmNode(
            config.copy(dataDir = dataDir, mode = { SwarmNode.Mode.light("https://rpc.example/key123") }),
            ops,
            clock,
            bootId,
        )
        node.start()
        awaitStatus(node, NodeStatus.Running)
        return node
    }

    @Test
    fun storageCallsNeedARunningLightNode() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        assertThrows(IllegalStateException::class.java) { node.storageQuote(17, 2) }
        node.start()
        awaitStatus(node, NodeStatus.Running)
        // Ultra-light: no chain, nothing to buy with.
        assertThrows(IllegalStateException::class.java) { node.storageQuote(17, 2) }
        node.dispose()

        val light = lightNode(FakeOps())
        assertEquals("""{"depth":17}""", light.storageQuote(17, 2))
        light.dispose()
    }

    @Test
    fun findingOwnedStampsNeedsALightNodeAndLetsNothingOut() {
        val ultraLight = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ultraLight)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertThrows(IllegalStateException::class.java) { node.discoverStamps() }
        node.dispose()

        val ops = FakeOps()
        val light = lightNode(ops)
        // ant may try to deploy a chequebook while it registers a batch it
        // found: no permit is open, so that broadcast is refused.
        val deploy = TestTx.request(TestTx.createBatch(TestTx.OWNER, java.math.BigInteger.TEN, 17, false))
        var admitted: Boolean? = null
        ops.onDiscover = { admitted = SpendGuard.admit(deploy) }
        assertEquals("""{"registered":[]}""", light.discoverStamps())
        assertEquals(false, admitted)
        assertTrue(ops.calls.any { it.startsWith("discover:1:") })
        light.dispose()
    }

    @Test
    fun findingOwnedStampsThatAdoptsAChequebookLeavesTheGatewayRunningAndItSeesTheChequebook() {
        val ops = FakeOps()
        val node = lightNode(ops)
        // The discover adopted the account's own chequebook and (ant 0.5.52+)
        // pointed the running gateway at it: no restart, and the chequebook
        // page's read — and a deposit — find it on the same gateway.
        ops.onDiscover = { ops.chequebookHex = chequebook }
        node.discoverStamps()
        assertNoGatewayRestart(ops)
        assertGatewaySeesChequebook(ops, node)
        node.dispose()
    }

    @Test
    fun aBuyRunsInsideAPermitForExactlyWhatWasConfirmed() {
        val ops = FakeOps()
        val node = lightNode(ops)
        val amount = java.math.BigInteger("4325218560")
        val confirmed = TestTx.request(TestTx.createBatch(TestTx.OWNER, amount, 17, false))
        val other = TestTx.request(TestTx.createBatch(TestTx.OWNER, amount, 18, false))
        val seen = mutableListOf<Boolean>()
        ops.onSpend = {
            seen += SpendGuard.admit(other)
            seen += SpendGuard.admit(confirmed)
            """{"enabled":true}"""
        }
        assertFalse(SpendGuard.admit(confirmed))
        node.buyStamp(17, amount, immutable = false, maxSwapWei = java.math.BigInteger.TEN.pow(17))
        assertEquals(listOf(false, true), seen)
        // Closed again once the buy returns.
        assertFalse(SpendGuard.admit(confirmed))
        assertTrue("buy:1:17:4325218560:false" in ops.calls)
        node.dispose()
    }

    @Test
    fun extendingNeedsTheConnectedBatchAndPaysForItsDepth() {
        val ops = FakeOps()
        val node = lightNode(ops)
        val id = "ab".repeat(32)
        val amount = java.math.BigInteger("2162609281")
        ops.storageStatusJson = """{"enabled":true,"batch_id":"0x${"cd".repeat(32)}","batch_depth":17}"""
        assertThrows(IllegalStateException::class.java) {
            node.extendStamp(id, amount, java.math.BigInteger.ONE)
        }
        assertFalse(ops.calls.any { it.startsWith("topup") })

        ops.storageStatusJson = """{"enabled":true,"batch_id":"0x${id.uppercase()}","batch_depth":18}"""
        val verdicts = mutableListOf<Boolean>()
        ops.onSpend = {
            verdicts += SpendGuard.admit(TestTx.request(TestTx.approve(amount.shiftLeft(17))))
            verdicts += SpendGuard.admit(TestTx.request(TestTx.approve(amount.shiftLeft(18))))
            verdicts += SpendGuard.admit(TestTx.request(TestTx.topUp("cd".repeat(32), amount)))
            verdicts += SpendGuard.admit(TestTx.request(TestTx.topUp(id, amount)))
            "{}"
        }
        node.extendStamp("0x$id", amount, java.math.BigInteger.ONE)
        assertEquals(listOf(false, true, false, true), verdicts)
        node.dispose()
    }

    @Test
    fun aStopWaitsForAStorageCallStillInsideTheNode() {
        val ops = FakeOps()
        val node = lightNode(ops)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        ops.onSpend = {
            inside.countDown()
            release.await(5, TimeUnit.SECONDS)
            "{}"
        }
        val buyer = Thread { runCatching { node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE) } }
        buyer.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        node.stop()
        Thread.sleep(200)
        assertFalse("shut down under a running buy", ops.calls.any { it.startsWith("shutdown") })
        release.countDown()
        buyer.join(5_000)
        assertTrue(ops.shutDown.await(5, TimeUnit.SECONDS))
        node.dispose()
    }

    @Test
    fun errorsNeverCarryTheRpc() {
        val ops = FakeOps()
        val node = lightNode(ops)
        ops.onSpend = { throw RuntimeException("rpc: error sending request for url (https://rpc.example/key123/)") }
        val e = assertThrows(RuntimeException::class.java) {
            node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE)
        }
        assertFalse(e.message!!.contains("key123"))
        assertTrue(e.message!!.contains("the Gnosis RPC"))
        node.dispose()
    }

    private val chequebook = "37".repeat(20)
    private val milliBzz = java.math.BigInteger.TEN.pow(13)

    @Test
    fun aDepositRunsInsideAPermitForExactlyTheConfirmedChequebookAndAmount() {
        val ops = FakeOps()
        val node = lightNode(ops)
        ops.chequebookHex = chequebook
        ops.walletPlur = "34393882720917"
        val confirmed = TestTx.request(TestTx.transfer(chequebook, milliBzz))
        val elsewhere = TestTx.request(TestTx.transfer("cc".repeat(20), milliBzz))
        val seen = mutableListOf<Boolean>()
        ops.onDeposit = { amount ->
            seen += SpendGuard.admit(elsewhere)
            seen += SpendGuard.admit(confirmed)
            assertEquals(milliBzz.toString(), amount)
            SwarmNode.GatewayAnswer(201, """{"transactionHash":"0xabc"}""")
        }
        assertFalse(SpendGuard.admit(confirmed))
        assertEquals("""{"transactionHash":"0xabc"}""", node.depositChequebook("0x" + chequebook.uppercase(), milliBzz))
        assertEquals(listOf(false, true), seen)
        // Closed again once the deposit returns.
        assertFalse(SpendGuard.admit(confirmed))
        node.dispose()
    }

    @Test
    fun aDepositRefusesAnotherChequebookNoneOrMoreXbzzThanTheNodeHolds() {
        val ops = FakeOps()
        val node = lightNode(ops)
        ops.walletPlur = milliBzz.toString()
        fun refused(cb: String, amount: java.math.BigInteger): String {
            val e = assertThrows(IllegalStateException::class.java) { node.depositChequebook(cb, amount) }
            assertFalse(ops.calls.any { it.startsWith("gateway:POST") })
            return e.message!!
        }
        // No chequebook yet (the gateway's zero address).
        assertEquals("the node has no chequebook yet", refused(chequebook, milliBzz))
        // The gateway's chequebook isn't the one the user confirmed.
        ops.chequebookHex = "cc".repeat(20)
        assertEquals("the node's chequebook isn't the one you confirmed", refused(chequebook, milliBzz))
        // More than the account holds: no swap for a deposit.
        ops.chequebookHex = chequebook
        assertEquals("the node holds only 0.001 xBZZ", refused(chequebook, milliBzz.add(java.math.BigInteger.ONE)))
        assertThrows(IllegalArgumentException::class.java) { node.depositChequebook(chequebook, java.math.BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { node.depositChequebook("0x1234", milliBzz) }
        node.dispose()
    }

    @Test
    fun aFailedDepositSaysWhyWithoutTheRpc() {
        val ops = FakeOps()
        val node = lightNode(ops)
        ops.chequebookHex = chequebook
        ops.walletPlur = milliBzz.toString()
        ops.onDeposit = {
            SwarmNode.GatewayAnswer(502, """{"code":502,"message":"chain tx: deposit transfer: https://rpc.example/key123 refused"}""")
        }
        val e = assertThrows(RuntimeException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertTrue(e.message!!, e.message!!.contains("(chain tx: deposit transfer: the Gnosis RPC"))
        assertFalse(e.message!!.contains("key123"))
        node.dispose()
    }

    @Test
    fun aDepositNeedsARunningLightNode() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        assertThrows(IllegalStateException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertThrows(IllegalStateException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertFalse(ops.calls.any { it.startsWith("gateway:GET") || it.startsWith("gateway:POST") })
        node.dispose()
    }

    @Test
    fun connectingABatchLetsOutOnlyTheChequebookSetupAndLeavesTheGatewayRunning() {
        // #115: the wallet bought the batch; ant connects it, and a first connect
        // sets up the chequebook — nothing else may get out meanwhile.
        val ops = FakeOps()
        val node = lightNode(ops)
        val id = "ab".repeat(32)
        val verdicts = mutableListOf<Boolean>()
        ops.onSpend = {
            verdicts += SpendGuard.admit(TestTx.request(TestTx.createBatch(TestTx.OWNER, java.math.BigInteger.TEN, 17, false)))
            verdicts += SpendGuard.admit(TestTx.request(TestTx.swap(java.math.BigInteger.ONE)))
            verdicts += SpendGuard.admit(TestTx.request(TestTx.deployChequebook()))
            verdicts += SpendGuard.admit(TestTx.request(TestTx.transfer("cc".repeat(20), milliBzz)))
            // ant (0.5.52+) points the running gateway at the chequebook it set up.
            ops.chequebookHex = chequebook
            """{"enabled":true}"""
        }
        assertThrows(IllegalArgumentException::class.java) { node.connectBatch("0x1234") }
        node.connectBatch("0x" + id.uppercase())
        assertEquals(listOf(false, false, true, true), verdicts)
        assertTrue("connect:1:0x$id" in ops.calls)
        assertFalse(SpendGuard.admit(TestTx.request(TestTx.deployChequebook())))
        // The chequebook it set up is on the running gateway: no restart.
        assertNoGatewayRestart(ops)
        assertGatewaySeesChequebook(ops, node)
        node.dispose()
    }

    @Test
    fun aBuyThatSetsUpTheChequebookLeavesTheGatewayRunningAndItSeesTheChequebook() {
        val ops = FakeOps()
        val node = lightNode(ops)
        // The first buy deploys the chequebook and (ant 0.5.52+) points the
        // running gateway at it: no stop and start of the gateway, and
        // settlement's reads — the chequebook page's, a deposit's — see it.
        ops.onSpend = { ops.chequebookHex = chequebook; it }
        node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE)
        assertNoGatewayRestart(ops)
        assertGatewaySeesChequebook(ops, node)

        // A buy that sets up none (or one the gateway doesn't report)
        // doesn't restart the gateway either: nothing reads ant's
        // settlement to second-guess it any more.
        ops.chequebookHex = "0".repeat(40)
        ops.onSpend = { it }
        node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE)
        assertNoGatewayRestart(ops)
        node.dispose()
    }

    /** The node's gateway was started once, at boot, and never stopped or started again. */
    private fun assertNoGatewayRestart(ops: FakeOps) {
        assertEquals(ops.calls.toString(), 0, ops.calls.count { it.startsWith("stopGateway:") })
        assertEquals(ops.calls.toString(), listOf("gateway:1"), ops.calls.filter { it.startsWith("gateway:") && !it.startsWith("gateway:GET") && !it.startsWith("gateway:POST") })
        assertEquals(1, ops.gatewayModes.size)
    }

    /** A deposit into [chequebook] — which reads the gateway's chequebook — goes ahead on the running node. */
    private fun assertGatewaySeesChequebook(ops: FakeOps, node: SwarmNode) {
        assertEquals(NodeStatus.Running, node.state.value.status)
        ops.walletPlur = milliBzz.toString()
        assertTrue(node.depositChequebook(chequebook, milliBzz).contains("transactionHash"))
        assertTrue(ops.calls.contains("gateway:POST /chequebook/deposit?amount=$milliBzz"))
    }

    @Test
    fun aDepositThatEndsWithoutAnAnswerBlocksAnotherUntilTheChequebookShowsIt() {
        val ops = FakeOps()
        var now = 1_000_000L
        val node = lightNode(ops, clock = { now })
        ops.chequebookHex = chequebook
        ops.walletPlur = milliBzz.multiply(java.math.BigInteger.TEN).toString()
        ops.chequebookBalancePlur = milliBzz.toString()
        // The POST read times out (no answer): the transfer may be out already.
        ops.onDeposit = { null }
        // Its own type, which `:node` flags to the app (#313 R1-M1): never told by the words.
        val first = assertThrows(DepositMaybeSentException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertTrue(first.message!!, first.message!!.startsWith(SwarmNode.DEPOSIT_MAYBE_SENT))
        fun posts() = ops.calls.count { it.startsWith("gateway:POST") }
        assertEquals(1, posts())

        // A retry while the chequebook still shows the old balance: refused
        // before any transaction, though /wallet still reads the xBZZ as there.
        now += 60_000
        val held = assertThrows(IllegalStateException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertTrue(held.message!!, held.message!!.startsWith("an earlier deposit may still be on its way"))
        assertEquals(1, posts())

        // The earlier deposit lands: a new one goes ahead.
        ops.chequebookBalancePlur = milliBzz.shiftLeft(1).toString()
        assertThrows(RuntimeException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertEquals(2, posts())
        // …which again ended unanswered; past the hold, one goes ahead anyway.
        now += 15 * 60_000L
        val third = assertThrows(DepositMaybeSentException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertTrue(third.message!!.startsWith(SwarmNode.DEPOSIT_MAYBE_SENT))
        assertEquals(3, posts())
        node.dispose()
    }

    @Test
    fun theHoldAfterAnUnansweredDepositOutlivesTheNodeProcess() {
        val dir = java.nio.file.Files.createTempDirectory("swarmnode-hold").toFile()
        try {
            var now = 1_000_000L
            fun funded(ops: FakeOps) = ops.apply {
                chequebookHex = chequebook
                walletPlur = milliBzz.multiply(java.math.BigInteger.TEN).toString()
                chequebookBalancePlur = milliBzz.toString()
            }
            val ops = funded(FakeOps())
            val node = lightNode(ops, dir.path, clock = { now })
            ops.onDeposit = { SwarmNode.GatewayAnswer(504, """{"code":504,"message":"timed out"}""") }
            assertThrows(RuntimeException::class.java) { node.depositChequebook(chequebook, milliBzz) }
            // Turning the node off kills the :node process: nothing in memory survives.
            node.dispose()

            // A fresh node on the same data dir still holds the retry, with no POST.
            val ops2 = funded(FakeOps())
            val again = lightNode(ops2, dir.path, clock = { now + 60_000 })
            val held = assertThrows(IllegalStateException::class.java) { again.depositChequebook(chequebook, milliBzz) }
            assertTrue(held.message!!, held.message!!.startsWith("an earlier deposit may still be on its way"))
            assertEquals(0, ops2.calls.count { it.startsWith("gateway:POST") })
            again.dispose()

            // Once the balance shows it, the hold lifts, on disk too.
            val ops3 = funded(FakeOps()).apply { chequebookBalancePlur = milliBzz.shiftLeft(1).toString() }
            val third = lightNode(ops3, dir.path, clock = { now + 120_000 })
            third.depositChequebook(chequebook, milliBzz)
            assertEquals(1, ops3.calls.count { it.startsWith("gateway:POST") })
            third.dispose()
            assertFalse(File(dir, "unconfirmed-deposit.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theHoldAfterAnUnansweredDepositOutlivesAReboot() {
        val dir = java.nio.file.Files.createTempDirectory("swarmnode-hold-reboot").toFile()
        try {
            fun funded(ops: FakeOps) = ops.apply {
                chequebookHex = chequebook
                walletPlur = milliBzz.multiply(java.math.BigInteger.TEN).toString()
                chequebookBalancePlur = milliBzz.toString()
            }
            // Unanswered at 2 h of uptime.
            val ops = funded(FakeOps())
            val node = lightNode(ops, dir.path, clock = { 2 * 3_600_000L })
            ops.onDeposit = { null }
            assertThrows(RuntimeException::class.java) { node.depositChequebook(chequebook, milliBzz) }
            node.dispose()

            // The phone reboots: the clock restarts at 0. Five minutes into
            // the new boot the transfer may still be pending: still held.
            val ops2 = funded(FakeOps())
            val again = lightNode(ops2, dir.path, clock = { 5 * 60_000L })
            val held = assertThrows(IllegalStateException::class.java) { again.depositChequebook(chequebook, milliBzz) }
            assertTrue(held.message!!, held.message!!.startsWith("an earlier deposit may still be on its way"))
            assertEquals(0, ops2.calls.count { it.startsWith("gateway:POST") })
            again.dispose()

            // A full hold into the new boot, it lifts.
            val ops3 = funded(FakeOps())
            val third = lightNode(ops3, dir.path, clock = { 15 * 60_000L })
            third.depositChequebook(chequebook, milliBzz)
            assertEquals(1, ops3.calls.count { it.startsWith("gateway:POST") })
            third.dispose()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aHoldFromAnEarlierBootDoesNotComeBackOnceUptimePassesIt() {
        val dir = java.nio.file.Files.createTempDirectory("swarmnode-hold-boot-id").toFile()
        try {
            fun funded(ops: FakeOps) = ops.apply {
                chequebookHex = chequebook
                walletPlur = milliBzz.multiply(java.math.BigInteger.TEN).toString()
                chequebookBalancePlur = milliBzz.toString()
            }
            // Unanswered at 2 h of uptime, boot "a".
            val ops = funded(FakeOps())
            val node = lightNode(ops, dir.path, clock = { 2 * 3_600_000L }, bootId = { "a" })
            ops.onDeposit = { null }
            assertThrows(RuntimeException::class.java) { node.depositChequebook(chequebook, milliBzz) }
            node.dispose()

            // Rebooted (boot "b"), and never checked until 2 h 05 min into
            // it: the uptime is past the hold's reading, but it's another
            // boot, so the full 2 h 05 min counts and the hold is gone.
            val ops2 = funded(FakeOps())
            val again = lightNode(ops2, dir.path, clock = { 2 * 3_600_000L + 5 * 60_000L }, bootId = { "b" })
            again.depositChequebook(chequebook, milliBzz)
            assertEquals(1, ops2.calls.count { it.startsWith("gateway:POST") })
            again.dispose()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aHoldFromThisBootStillCountsFromItsReading() {
        val dir = java.nio.file.Files.createTempDirectory("swarmnode-hold-same-boot").toFile()
        try {
            fun funded(ops: FakeOps) = ops.apply {
                chequebookHex = chequebook
                walletPlur = milliBzz.multiply(java.math.BigInteger.TEN).toString()
                chequebookBalancePlur = milliBzz.toString()
            }
            val ops = funded(FakeOps())
            val node = lightNode(ops, dir.path, clock = { 2 * 3_600_000L }, bootId = { "a" })
            ops.onDeposit = { null }
            assertThrows(RuntimeException::class.java) { node.depositChequebook(chequebook, milliBzz) }
            node.dispose()

            // Same boot, node toggled off and on, 5 min later: still held.
            val ops2 = funded(FakeOps())
            val again = lightNode(ops2, dir.path, clock = { 2 * 3_600_000L + 5 * 60_000L }, bootId = { "a" })
            assertThrows(IllegalStateException::class.java) { again.depositChequebook(chequebook, milliBzz) }
            assertEquals(0, ops2.calls.count { it.startsWith("gateway:POST") })
            again.dispose()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun holdElapsedIsNeverMoreThanTheRealTime() {
        assertEquals(60_000L, SwarmNode.holdElapsedMs(1_060_000L, 1_000_000L))
        assertEquals(60_000L, SwarmNode.holdElapsedMs(1_060_000L, 1_000_000L, sameBoot = true))
        // Rebooted: only the time since this boot is sure.
        assertEquals(30_000L, SwarmNode.holdElapsedMs(30_000L, 7_200_000L))
        assertEquals(30_000L, SwarmNode.holdElapsedMs(30_000L, 7_200_000L, sameBoot = false))
        // Another boot whose uptime has passed the hold's reading.
        assertEquals(7_500_000L, SwarmNode.holdElapsedMs(7_500_000L, 7_200_000L, sameBoot = false))
    }

    @Test
    fun aBuyThatFailsBeforeAntSetUpAChequebookLeavesTheGatewayAlone() {
        val ops = FakeOps()
        val node = lightNode(ops)
        // Refused before anything went on-chain (no xDAI).
        ops.onSpend = { throw RuntimeException("insufficient xDAI") }
        assertThrows(RuntimeException::class.java) {
            node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE)
        }
        assertEquals(listOf("light:https://rpc.example/key123"), ops.gatewayModes)
        assertFalse(ops.calls.contains("stopGateway:1"))
        // Nor does one refused because another payment is running.
        val otherPayment = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.CountDownLatch(1)
        ops.onSpend = { running.countDown(); otherPayment.await(5, TimeUnit.SECONDS); it }
        val first = Thread { runCatching { node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE) } }
        first.start()
        assertTrue(running.await(5, TimeUnit.SECONDS))
        val refused = assertThrows(IllegalStateException::class.java) {
            node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE)
        }
        assertEquals("another payment is already running", refused.message)
        assertEquals(listOf("light:https://rpc.example/key123"), ops.gatewayModes)
        otherPayment.countDown()
        first.join(5_000)
        assertFalse(ops.calls.contains("stopGateway:1"))
        assertEquals(NodeStatus.Running, node.state.value.status)
        node.dispose()
    }

    @Test
    fun aGatewayTimeoutOrRpcErrorMayHaveSentTheDepositARevertDidnt() {
        fun answer(code: Int, message: String) = SwarmNode.GatewayAnswer(code, """{"code":$code,"message":"$message"}""")
        assertTrue(SwarmNode.depositMaybeSent(null, null))
        assertTrue(SwarmNode.depositMaybeSent(answer(504, "chain transaction timed out"), "chain transaction timed out"))
        assertTrue(SwarmNode.depositMaybeSent(answer(502, "x"), "chain tx: deposit transfer: rpc: timeout"))
        assertTrue(SwarmNode.depositMaybeSent(answer(502, "x"), "chain tx: deposit transfer: transaction never mined: 0xab (timeout 60s)"))
        assertFalse(SwarmNode.depositMaybeSent(answer(502, "x"), "chain tx: deposit transfer: transaction reverted: 0x0"))
        assertFalse(SwarmNode.depositMaybeSent(answer(400, "x"), "amount must be a decimal integer"))
        assertFalse(SwarmNode.depositMaybeSent(answer(501, "x"), "on-chain writes require a configured wallet key"))
        assertFalse(SwarmNode.depositMaybeSent(answer(503, "x"), "chain initializing"))
        assertFalse(SwarmNode.depositMaybeSent(SwarmNode.GatewayAnswer(201, "{}"), null))

        // A 504 through the node: said so, and the next deposit is held.
        val ops = FakeOps()
        val node = lightNode(ops)
        ops.chequebookHex = chequebook
        ops.walletPlur = milliBzz.shiftLeft(2).toString()
        ops.onDeposit = { answer(504, "chain transaction timed out") }
        val e = assertThrows(RuntimeException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertTrue(e.message!!.contains("chain transaction timed out"))
        assertThrows(IllegalStateException::class.java) { node.depositChequebook(chequebook, milliBzz) }
        assertEquals(1, ops.calls.count { it.startsWith("gateway:POST") })
        node.dispose()

        // A revert isn't held: nothing moved.
        val ops2 = FakeOps()
        val node2 = lightNode(ops2)
        ops2.chequebookHex = chequebook
        ops2.walletPlur = milliBzz.shiftLeft(2).toString()
        ops2.onDeposit = { answer(502, "chain tx: deposit transfer: transaction reverted: 0x0") }
        repeat(2) {
            val r = assertThrows(RuntimeException::class.java) { node2.depositChequebook(chequebook, milliBzz) }
            assertTrue(r.message!!.startsWith("chain tx: deposit transfer: transaction reverted"))
        }
        assertEquals(2, ops2.calls.count { it.startsWith("gateway:POST") })
        node2.dispose()
    }

    @Test
    fun aBuyWhoseBatchRevertsSetsUpNoChequebookAndLeavesTheGatewayRunning() {
        val ops = FakeOps()
        val node = lightNode(ops)
        // ant (v0.5.56) sets up the chequebook only after createBatch and
        // register_batch succeed, so a reverted batch leaves none behind.
        val before = ops.chequebookHex
        ops.onSpend = { throw RuntimeException("createBatch reverted") }
        assertThrows(RuntimeException::class.java) {
            node.buyStamp(17, java.math.BigInteger.TEN, false, java.math.BigInteger.ONE)
        }
        assertEquals(before, ops.chequebookHex)
        assertNoGatewayRestart(ops)
        assertEquals(NodeStatus.Running, node.state.value.status)
        node.dispose()
    }

    private fun awaitSwapCalls(ops: FakeOps, n: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (ops.swapCalls.size < n && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(ops.swapCalls.toString(), n, ops.swapCalls.size)
    }

    @Test
    fun theSavedPayPeersSettingIsAppliedAtEveryInitBeforeTheGatewayStarts() {
        // ant doesn't persist swap-enable: a node the user switched off
        // must be set off again after each init, before the gateway's
        // chain init lets it start paying.
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config.copy(swapEnabled = { false }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        awaitSwapCalls(ops, 2)
        assertEquals(listOf("1:false", "1:false+gw"), ops.swapCalls.toList())
        ops.nextHandle = 2
        node.restart()
        awaitStatus(node, NodeStatus.Running)
        awaitSwapCalls(ops, 4)
        assertEquals(listOf("2:false", "2:false+gw"), ops.swapCalls.drop(2))
        node.dispose()
    }

    @Test
    fun theSwitchFlipsTheRunningNodeLiveAndOutlivesARestart() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config.copy(swapEnabled = { true }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        awaitSwapCalls(ops, 2)
        node.setSwapEnabled(false)
        awaitSwapCalls(ops, 3)
        assertEquals("1:false+gw", ops.swapCalls[2])
        // The setting store (still on here) isn't re-read over the user's
        // latest choice, which the app also saved.
        ops.nextHandle = 2
        node.restart()
        awaitStatus(node, NodeStatus.Running)
        awaitSwapCalls(ops, 5)
        assertEquals(listOf("2:false", "2:false+gw"), ops.swapCalls.drop(3))
        node.dispose()
    }

    @Test
    fun aSwitchFlippedWhileStoppedReachesNoNodeUntilTheNextStart() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.setSwapEnabled(false)
        Thread.sleep(200)
        assertTrue(ops.swapCalls.toString(), ops.swapCalls.isEmpty())
        node.start()
        awaitStatus(node, NodeStatus.Running)
        awaitSwapCalls(ops, 2)
        assertTrue(ops.swapCalls.toString(), ops.swapCalls.all { it.contains(":false") })
        node.dispose()
    }

    @Test
    fun aSwitchFlippedDuringStartupIsAppliedOnceTheNodeIsUp() {
        // The first apply (before the gateway) read the old value; the
        // toggle found no published handle; the apply after publishing
        // must catch it up.
        val ops = FakeOps()
        val node = SwarmNode(config.copy(swapEnabled = { true }), ops)
        node.start()
        ops.releaseSeed.countDown()
        assertTrue(ops.initEntered.await(5, TimeUnit.SECONDS))
        node.setSwapEnabled(false)
        ops.releaseInit.countDown()
        awaitStatus(node, NodeStatus.Running)
        val until = System.currentTimeMillis() + 5_000
        while (ops.swapCalls.lastOrNull() != "1:false+gw" && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(ops.swapCalls.toString(), "1:false+gw", ops.swapCalls.last())
        node.dispose()
    }

    @Test
    fun aFailedSwitchDoesNotFailTheStart() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown(); swapFails = true }
        val node = SwarmNode(config.copy(swapEnabled = { false }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        node.dispose()
    }

    @Test
    fun swapStatusIsAntsAnswerAndNeedsARunningNode() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        assertThrows(IllegalStateException::class.java) { node.swapStatus() }
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(ops.swapStatusJson, node.swapStatus())
        node.dispose()
    }

    @Test
    fun confirmingALostLedgerIsOnlyForTheChequebookTheNodeRuns() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown(); chequebookHex = "ab".repeat(20) }
        val node = SwarmNode(config.copy(mode = { SwarmNode.Mode.light("https://rpc.example") }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertThrows(IllegalArgumentException::class.java) { node.confirmChequeLiability("0x1234") }
        assertThrows(IllegalStateException::class.java) { node.confirmChequeLiability("0x" + "cd".repeat(20)) }
        assertTrue(ops.confirms.isEmpty())
        assertTrue(node.confirmChequeLiability("0x" + "AB".repeat(20)))
        assertEquals(listOf("1:0x" + "ab".repeat(20)), ops.confirms.toList())
        ops.confirmResult = 1
        assertFalse(node.confirmChequeLiability("0x" + "ab".repeat(20)))
        node.dispose()
    }
}
