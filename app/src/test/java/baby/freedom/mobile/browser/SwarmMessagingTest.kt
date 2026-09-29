package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.wallet.PublisherKeys
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * window.swarm messaging (#121): the GSOC room derivation against
 * desktop's, the subscription registry, and the node socket against a
 * WebSocket server standing in for ant's gateway.
 */
class SwarmMessagingTest {
    // -------------------------------------------------------------------
    // GSOC derivation
    // -------------------------------------------------------------------

    /** bee-js 12.2 `gsocMine` with desktop's derivation (the vectors iOS pins too). */
    private val vectors = listOf(
        listOf(
            "room:doc-42",
            "5aaa9322a34420597442f65b3f3ef3e5aba895e844f77e53cf92f20d709b26c5",
            "0000000000000000000000000000000000000000000000000000000000001118",
            "457d444476f6de5d990d9465662d55462efd4be2ef34303bf922cedc7d89b1a9",
        ),
        listOf(
            "swarm-kit:chat-bus/v1:lobby",
            "78fd71b2243adaa64da725e768b231206e244df9f478212bcfb5c7583a3fe56a",
            "00000000000000000000000000000000000000000000000000000000000039f3",
            "74b6f12ccb5adc7e7f4d3ea6aa189aaa08977fb2dff8bd6562cab4ebd75a72c4",
        ),
        listOf(
            "t",
            "cac1bb71f0a97c8ac94ca9546b43178a9ad254c7b757ac07433aa6df35cd8089",
            "000000000000000000000000000000000000000000000000000000000000200d",
            "8df0bf3a90c2366465f60ed6ce24451f697d6c611517476ba02ea7cdaf0dab56",
        ),
    )

    @Test
    fun `a topic's room is the one desktop and iOS derive`() {
        for ((topic, identifier, key, address) in vectors) {
            val d = SwarmGsoc.derive(topic)
            assertEquals(topic, identifier, d.identifier.swarmHex())
            assertEquals(topic, key, d.privateKey.swarmHex())
            assertEquals(topic, address, d.address)
        }
    }

    @Test
    fun `the mined key meets the placement rule and signs at the room's address`() {
        val d = SwarmGsoc.derive("agreement-check")
        val target = Keccak256.digest("freedom-gsoc-v1:agreement-check".toByteArray())
        assertTrue(SwarmGsoc.proximity(d.address.hexToBytesOrNull()!!, target) >= 12)
        val soc = SwarmChunks.sign(d.identifier, SwarmChunks.cac("hello".toByteArray()), d.privateKey)
        assertEquals(d.address, soc.address.swarmHex())
        assertEquals(PublisherKeys.address(d.privateKey).lowercase(), "0x" + soc.owner.swarmHex())
        // Cached: the same object again.
        assertTrue(SwarmGsoc.derive("agreement-check") === d)
    }

    @Test
    fun `proximity counts shared leading bits`() {
        val zeros = ByteArray(32)
        assertEquals(256, SwarmGsoc.proximity(zeros, zeros))
        assertEquals(255, SwarmGsoc.proximity(zeros, ByteArray(32).also { it[31] = 1 }))
        assertEquals(0, SwarmGsoc.proximity(zeros, ByteArray(32).also { it[0] = 0x80.toByte() }))
        assertEquals(11, SwarmGsoc.proximity(zeros, ByteArray(32).also { it[1] = 0x10 }))
    }

    // -------------------------------------------------------------------
    // The registry
    // -------------------------------------------------------------------

    private class Socket(val kind: String, val key: String, val onMessage: (ByteArray) -> Unit) : SwarmSubscriptions.Socket {
        override val established = CompletableDeferred<Unit>()
        var cancelled = false
        override fun cancel() {
            cancelled = true
            if (!established.isCompleted) established.completeExceptionally(SwarmSubscriptions.Failure("cancelled", "Subscription cancelled"))
        }
    }

    private class Doc(override val tab: Long, override val document: Int) : SwarmSubscriptions.Subscriber {
        var alive = true
        val got = CopyOnWriteArrayList<JSONObject>()
        override fun live() = alive
        override fun deliver(message: JSONObject) {
            got += message
        }
    }

    private val sockets = mutableListOf<Socket>()
    private fun registry(max: Int = 32, timeoutMs: Long = 30_000) =
        SwarmSubscriptions({ kind, key, onMessage -> Socket(kind, key, onMessage).also { sockets += it } }, max, timeoutMs)

    @Test
    fun `subscriptions to one key share one socket, which closes with the last`() = runBlocking {
        val r = registry()
        val a = Doc(1, 1)
        val b = Doc(2, 1)
        val first = async { r.subscribe("https://a.example", "gsoc", "k", a) }
        val second = async { r.subscribe("https://b.example", "gsoc", "k", b) }
        yield()
        assertEquals(1, sockets.size)
        sockets[0].established.complete(Unit)
        val idA = first.await()
        val idB = second.await()
        sockets[0].onMessage(byteArrayOf(1))
        assertEquals(idA, a.got.single().getString("subscription"))
        assertEquals(idB, b.got.single().getString("subscription"))
        assertFalse(r.unsubscribe("https://b.example", idA))
        assertTrue(r.unsubscribe("https://a.example", idA))
        assertFalse(sockets[0].cancelled)
        assertTrue(r.unsubscribe("https://b.example", idB))
        assertTrue(sockets[0].cancelled)
        assertEquals(0, r.sockets())
    }

    @Test
    fun `a subscription still coming up gets no messages`() = runBlocking {
        val r = registry()
        val doc = Doc(1, 1)
        val job = async { r.subscribe("https://a.example", "pss", "k", doc) }
        yield()
        // The page has no id for it yet.
        sockets.single().onMessage(byteArrayOf(1))
        assertEquals(0, doc.got.size)
        sockets.single().established.complete(Unit)
        val id = job.await()
        sockets.single().onMessage(byteArrayOf(2))
        assertEquals(id, doc.got.single().getString("subscription"))
        assertEquals("Ag==", doc.got.single().getJSONObject("result").getString("data"))
    }

    @Test
    fun `a document that goes while its subscription comes up gets subscription_cancelled, and gives its slot back`() = runBlocking {
        val r = registry()
        val doc = Doc(1, 1)
        val job = async { runCatching { r.subscribe("https://a.example", "pss", "k", doc) } }
        yield()
        r.cancelWhere { it.tab == 1L }
        val failure = job.await().exceptionOrNull() as SwarmSubscriptions.Failure
        assertEquals("cancelled", failure.reason)
        assertTrue(sockets.single().cancelled)
        assertEquals(0, r.count("https://a.example"))

        // Navigated away (no longer live) just as it came up.
        val gone = Doc(1, 2)
        val job2 = async { runCatching { r.subscribe("https://a.example", "pss", "k", gone) } }
        yield()
        gone.alive = false
        sockets.last().established.complete(Unit)
        assertEquals("subscription_cancelled", (job2.await().exceptionOrNull() as SwarmSubscriptions.Failure).reason)
        assertEquals(0, r.count("https://a.example"))
        assertEquals(0, r.sockets())
    }

    @Test
    fun `a socket that never comes up times out and releases everything`() = runBlocking {
        val r = registry(timeoutMs = 50)
        val failure = runCatching { r.subscribe("https://a.example", "gsoc", "k", Doc(1, 1)) }.exceptionOrNull() as SwarmSubscriptions.Failure
        assertEquals("establish_timeout", failure.reason)
        assertTrue(sockets.single().cancelled)
        assertEquals(0, r.count("https://a.example"))
    }

    @Test
    fun `the node refusing a pipeline is node_subscription_limit`() = runBlocking {
        val r = registry()
        val job = async { runCatching { r.subscribe("https://a.example", "gsoc", "k", Doc(1, 1)) } }
        yield()
        sockets.single().established.completeExceptionally(SwarmSubscriptions.Failure("node_subscription_limit", "full"))
        assertEquals("node_subscription_limit", (job.await().exceptionOrNull() as SwarmSubscriptions.Failure).reason)
        assertEquals(0, r.sockets())
    }

    @Test
    fun `a site's cap counts its own subscriptions only`() = runBlocking {
        val r = registry(max = 2)
        val jobs = List(2) { i -> async { r.subscribe("https://a.example", "pss", "k$i", Doc(1, 1)) } }
        yield()
        sockets.forEach { it.established.complete(Unit) }
        jobs.forEach { it.await() }
        val failure = runCatching { r.subscribe("https://a.example", "pss", "k9", Doc(1, 1)) }.exceptionOrNull() as SwarmSubscriptions.Failure
        assertEquals("too_many_subscriptions", failure.reason)
        val other = async { r.subscribe("https://b.example", "pss", "k0", Doc(2, 1)) }
        other.await() // joins k0's socket, already up
        assertEquals(2, r.sockets())
    }

    @Test
    fun `navigation and disconnect close only the subscriptions they concern`() = runBlocking {
        val r = registry()
        val old = Doc(1, 1)
        val otherTab = Doc(2, 1)
        val jobs = listOf(
            async { r.subscribe("https://a.example", "pss", "x", old) },
            async { r.subscribe("https://a.example", "pss", "y", otherTab) },
            async { r.subscribe("https://b.example", "pss", "z", Doc(3, 1)) },
        )
        yield()
        sockets.forEach { it.established.complete(Unit) }
        jobs.forEach { it.await() }
        // Tab 1 starts document 2.
        r.cancelWhere { it.tab == 1L && it.document != 2 }
        assertEquals(listOf(true, false, false), sockets.map { it.cancelled })
        r.cancelByOrigin("https://a.example")
        assertEquals(listOf(true, true, false), sockets.map { it.cancelled })
        assertEquals(1, r.count("https://b.example"))
    }

    // -------------------------------------------------------------------
    // The node socket
    // -------------------------------------------------------------------

    private val server = MockWebServer()

    @After
    fun stop() {
        runCatching { server.shutdown() }
    }

    /** A gateway that, per upgrade, runs [each] on the server side of the socket. */
    private fun gateway(each: (Int, WebSocket) -> Unit) {
        var n = 0
        repeat(8) {
            val i = n++
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = each(i, webSocket)
                }),
            )
        }
        server.start()
    }

    private fun socket(onMessage: (ByteArray) -> Unit) = NodeSubscriptionSocket(
        NodeSubscriptionSocket.CLIENT, server.url("/gsoc/subscribe/abc").toString(), onMessage,
        graceMs = 200, baseDelayMs = 100, maxDelayMs = 400,
    ).start()

    @Test
    fun `a socket open past the grace period is up and delivers binary frames`() = runBlocking {
        gateway { _, ws -> Thread { Thread.sleep(400); ws.send(byteArrayOf(7, 8).toByteString()) }.start() }
        val got = CopyOnWriteArrayList<List<Byte>>()
        val s = socket { got += it.toList() }
        withTimeout(5_000) { s.established.await() }
        val until = System.currentTimeMillis() + 5_000
        while (got.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(listOf(listOf<Byte>(7, 8)), got.toList())
        assertEquals("/gsoc/subscribe/abc", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        s.cancel()
    }

    @Test
    fun `a 1013 close before it's up is the node's pipelines being full`() = runBlocking {
        gateway { _, ws -> ws.close(1013, "lurker slots full") }
        val s = socket {}
        try {
            withTimeout(5_000) { s.established.await() }
            fail("expected a refusal")
        } catch (e: SwarmSubscriptions.Failure) {
            assertEquals("node_subscription_limit", e.reason)
            assertEquals("lurker slots full", e.message)
        }
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a socket that drops once up reconnects and keeps delivering`() = runBlocking {
        gateway { i, ws ->
            Thread {
                Thread.sleep(300)
                if (i == 0) ws.close(1001, "restart") else ws.send(byteArrayOf(i.toByte()).toByteString())
            }.start()
        }
        val got = CopyOnWriteArrayList<Byte>()
        val s = socket { got += it[0] }
        withTimeout(5_000) { s.established.await() }
        val until = System.currentTimeMillis() + 5_000
        while (got.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(listOf<Byte>(1), got.toList())
        assertTrue(server.requestCount >= 2)
        s.cancel()
        val count = server.requestCount
        Thread.sleep(600)
        assertEquals(count, server.requestCount)
    }

    @Test
    fun `cancelling before it's up fails the wait`() = runBlocking {
        gateway { _, _ -> }
        val s = socket {}
        s.cancel()
        assertEquals("cancelled", (runCatching { s.established.await() }.exceptionOrNull() as SwarmSubscriptions.Failure).reason)
    }
}
