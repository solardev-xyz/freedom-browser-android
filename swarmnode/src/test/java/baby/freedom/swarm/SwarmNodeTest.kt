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
        override fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String) {
            calls += "gateway:$handle"
            gatewayModes += if (lightMode) "light:$gnosisRpc" else "ultra-light:$gnosisRpc"
        }
        override fun agentString(handle: Long) = "ant-test"
        override fun peerCount(handle: Long) = 0
        override fun stopGateway(handle: Long) {
            calls += "stopGateway:$handle"
            stopEntered.countDown()
            releaseStop.await(5, TimeUnit.SECONDS)
        }
        override fun shutdown(handle: Long) {
            calls += "shutdown:$handle"
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

    private fun lightNode(ops: FakeOps): SwarmNode {
        ops.releaseSeed.countDown()
        ops.releaseInit.countDown()
        ops.ethAddress = "0x" + TestTx.OWNER.uppercase()
        val node = SwarmNode(config.copy(mode = { SwarmNode.Mode.light("https://rpc.example/key123") }), ops)
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
}
