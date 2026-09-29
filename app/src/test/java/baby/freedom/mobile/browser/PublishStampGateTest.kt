package baby.freedom.mobile.browser

import baby.freedom.mobile.node.INodeService
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun <T> assertNotNull(value: T?): T {
        assertTrue(value != null)
        return value!!
    }
}
