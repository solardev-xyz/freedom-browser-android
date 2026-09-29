package baby.freedom.mobile.browser

import baby.freedom.mobile.node.INodeService
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stamp buy or search may end with `:node` restarting the gateway (a
 * new chequebook), which would cut off a publish's open `POST /bzz`: the
 * two never start over each other (#222 R1-F1).
 */
class PublishStampGateTest {
    private fun record(id: String) = PublishRecord(id, PublishKind.Folder, "site", PublishStatus.Uploading, 1_000)

    private val quote = StampQuote(
        depth = 17, days = 30, amountPerChunk = BigInteger.TEN, totalCostBzz = "1", depositBzz = null,
        accountBzz = "0", neededBzz = "1", xdaiRequired = BigInteger.ONE, xdaiRequiredDisplay = "1",
        accountXdai = "1", xdaiToSend = "0", sufficientFunds = true,
    )

    @After
    fun tearDown() {
        StampClient.service = null
        Publisher.finish("gone", emptyList())
    }

    @Test
    fun `which stamp work may restart the gateway`() {
        val idle = StampClient.Discovery.Idle
        val searching = StampClient.Discovery.Running
        fun running(kind: StampClient.Kind) = StampClient.Spend.Running(kind, null)
        assertFalse(StampClient.mayRestartGateway(StampClient.Spend.Idle, idle))
        assertTrue(StampClient.mayRestartGateway(running(StampClient.Kind.Buy), idle))
        assertTrue(StampClient.mayRestartGateway(StampClient.Spend.Idle, searching))
        // An extend or a deposit never sets up a chequebook: no reload.
        assertFalse(StampClient.mayRestartGateway(running(StampClient.Kind.Extend), idle))
        assertFalse(StampClient.mayRestartGateway(running(StampClient.Kind.Deposit), idle))
        assertFalse(StampClient.mayRestartGateway(StampClient.Spend.Done(StampClient.Kind.Buy, null), idle))

        val uploading = Publisher.State.Running("1", "site", PublishKind.Folder)
        assertTrue(StampClient.canRestartGateway(StampClient.Spend.Idle, idle, Publisher.State.Idle))
        assertTrue(StampClient.canRestartGateway(StampClient.Spend.Idle, idle, Publisher.State.Finished("1")))
        assertFalse(StampClient.canRestartGateway(StampClient.Spend.Idle, idle, uploading))
        assertFalse(StampClient.canRestartGateway(running(StampClient.Kind.Deposit), idle, Publisher.State.Idle))
    }

    @Test
    fun `a buy or a search doesn't start while a publish uploads`() {
        val r = assertNotNull(Publisher.claim("site", PublishKind.Folder) { record("p1") })
        assertEquals(Publisher.State.Running("p1", "site", PublishKind.Folder), Publisher.state.value)
        // A second publish doesn't either.
        assertNull(Publisher.claim("other", PublishKind.File) { record("p2") })

        assertFalse(StampClient.buy(quote))
        assertFalse(StampClient.discover("0x" + "aa".repeat(20)))
        assertEquals(StampClient.Spend.Idle, StampClient.spend.value)
        assertEquals(StampClient.Discovery.Idle, StampClient.discovery.value)

        Publisher.finish(r.id, emptyList())
        assertEquals(Publisher.State.Idle, Publisher.state.value)
    }

    @Test
    fun `a publish doesn't start while a search runs, and does once it ends`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The node holds the search until released; then answers nothing.
        StampClient.service = Proxy.newProxyInstance(
            INodeService::class.java.classLoader,
            arrayOf(INodeService::class.java),
        ) { _, method, _ ->
            if (method.name == "stampCall") {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            null
        } as INodeService

        assertTrue(StampClient.discover("0x" + "aa".repeat(20)))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(StampClient.Discovery.Running, StampClient.discovery.value)
        var made = false
        assertNull(Publisher.claim("site", PublishKind.Folder) { made = true; record("p3") })
        assertFalse("no history record for a publish that didn't start", made)
        assertEquals(Publisher.State.Idle, Publisher.state.value)

        release.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (StampClient.discovery.value is StampClient.Discovery.Running && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(StampClient.discovery.value is StampClient.Discovery.Running)
        val r = assertNotNull(Publisher.claim("site", PublishKind.Folder) { record("p4") })
        Publisher.finish(r.id, emptyList())
    }

    @Test
    fun `a buy the page stopped waiting for stays running until the node ends it`() {
        val timedOut = StampClient.Answer.Failed(StampClient.TIMED_OUT)
        var waited = 0
        val buy = StampClient.spendOutcome(StampClient.Kind.Buy, null, timedOut) { waited++ }
        assertEquals("a timed-out buy waits for :node before it ends", 1, waited)
        assertEquals(StampClient.Spend.Failed(StampClient.Kind.Buy, null, StampClient.BUY_OVERRAN), buy)

        // An extend or a deposit never reloads the gateway: no wait.
        val extend = StampClient.spendOutcome(StampClient.Kind.Extend, "ab", timedOut) { waited++ }
        assertEquals(StampClient.Spend.Failed(StampClient.Kind.Extend, "ab", StampClient.stillSendingMessage(StampClient.Kind.Extend)), extend)
        StampClient.spendOutcome(StampClient.Kind.Deposit, null, timedOut) { waited++ }
        // Nor a buy that answered, either way.
        assertEquals(StampClient.Spend.Done(StampClient.Kind.Buy, null), StampClient.spendOutcome(StampClient.Kind.Buy, null, StampClient.Answer.Ok(JSONObject())) { waited++ })
        assertEquals(
            StampClient.Spend.Failed(StampClient.Kind.Buy, null, "not enough xDAI"),
            StampClient.spendOutcome(StampClient.Kind.Buy, null, StampClient.Answer.Failed("not enough xDAI")) { waited++ },
        )
        assertEquals(1, waited)
    }

    @Test
    fun `a publish sends at once when the node runs no stamp work`() {
        var asks = 0
        var waiting = false
        StampClient.awaitGatewayQuiet(
            ask = { asks++; StampClient.Answer.Ok(JSONObject().put("running", false)) },
            pause = { throw AssertionError("no wait") },
            onWaiting = { waiting = true },
        )
        assertEquals(1, asks)
        assertFalse(waiting)
        // Nor when :node has gone (the upload then fails on its own), or is
        // an older one that doesn't know the call.
        StampClient.awaitGatewayQuiet(ask = { StampClient.Answer.Failed("The Swarm node isn't running") }, pause = { throw AssertionError() })
        StampClient.awaitGatewayQuiet(ask = { StampClient.Answer.Failed("unknown stamp call") }, pause = { throw AssertionError() })
    }

    @Test
    fun `a publish waits out a buy or search the node runs that this process doesn't know of`() {
        // This process's own state says nothing runs (a UI process started
        // again while :node buys, or a buy it stopped waiting for)…
        assertEquals(StampClient.Spend.Idle, StampClient.spend.value)
        val r = assertNotNull(Publisher.claim("site", PublishKind.Folder) { record("p5") })
        // …but :node does: busy, then unbound for a moment, then done.
        val answers = ArrayDeque(
            listOf(
                StampClient.Answer.Ok(JSONObject().put("running", true)),
                StampClient.Answer.Failed(StampClient.TIMED_OUT),
                StampClient.Answer.Failed("The Swarm node isn't running", unbound = true),
                StampClient.Answer.Ok(JSONObject().put("running", true)),
                StampClient.Answer.Ok(JSONObject().put("running", false)),
            ),
        )
        var pauses = 0
        StampClient.awaitGatewayQuiet(
            ask = { answers.removeFirst() },
            pause = { pauses++ },
            onWaiting = { Publisher.waitingForNode(r.id, true) },
        )
        assertTrue("asked until :node said it ended", answers.isEmpty())
        assertEquals(4, pauses)
        assertEquals(Publisher.State.Running("p5", "site", PublishKind.Folder, waitingForNode = true), Publisher.state.value)
        assertTrue(runningText(Publisher.state.value as Publisher.State.Running).startsWith("Waiting for the node"))
        Publisher.waitingForNode(r.id, false)
        assertEquals(Publisher.State.Running("p5", "site", PublishKind.Folder), Publisher.state.value)
        // Another publish's id doesn't touch it.
        Publisher.waitingForNode("other", true)
        assertEquals(Publisher.State.Running("p5", "site", PublishKind.Folder), Publisher.state.value)
        Publisher.finish(r.id, emptyList())
        Publisher.waitingForNode(r.id, true)
        assertEquals(Publisher.State.Idle, Publisher.state.value)
    }

    @Test
    fun `a publish whose node was switched off doesn't wait for it forever`() {
        // Unbound from the start (the node switched off in Settings while
        // it staged): a few asks for a rebind, no "waiting for the node"
        // card, then it goes ahead and the upload fails on its own.
        val unbound = StampClient.Answer.Failed("The Swarm node isn't running", unbound = true)
        var asks = 0
        var pauses = 0
        var waiting = false
        StampClient.awaitGatewayQuiet(
            ask = { asks++; check(asks < 100) { "never gave up" }; unbound },
            pause = { pauses++ },
            onWaiting = { waiting = true },
        )
        assertEquals(StampClient.MAX_UNBOUND_ASKS + 1, asks)
        assertEquals(StampClient.MAX_UNBOUND_ASKS, pauses)
        assertFalse(waiting)

        // Busy first, then switched off: it stops waiting just the same.
        asks = 0
        StampClient.awaitGatewayQuiet(
            ask = { asks++; check(asks < 100) { "never gave up" }; if (asks == 1) StampClient.Answer.Ok(JSONObject().put("running", true)) else unbound },
            pause = {},
            onWaiting = { waiting = true },
        )
        assertEquals(StampClient.MAX_UNBOUND_ASKS + 2, asks)
        assertTrue(waiting)

        // A rebind in between (an Activity recreated) starts the count over.
        val answers = ArrayDeque(
            List(StampClient.MAX_UNBOUND_ASKS) { unbound } +
                StampClient.Answer.Ok(JSONObject().put("running", true)) +
                List(StampClient.MAX_UNBOUND_ASKS) { unbound } +
                StampClient.Answer.Ok(JSONObject().put("running", false)),
        )
        StampClient.awaitGatewayQuiet(ask = { answers.removeFirst() }, pause = {})
        assertTrue("waited through both short unbound spells", answers.isEmpty())
    }

    private fun <T> assertNotNull(value: T?): T {
        assertTrue(value != null)
        return value!!
    }
}
