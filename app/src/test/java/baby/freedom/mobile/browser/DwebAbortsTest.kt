package baby.freedom.mobile.browser

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * [DwebAborts]: the page's word that it gave up on a dweb request stops
 * the interceptor's fetch for it — before WebView even asks for it, while
 * it waits on the gateway, or between its retries — and only that fetch.
 */
class DwebAbortsTest {
    /** A ledger on a clock the test moves. */
    private class Clocked {
        var now = 1_000L
        val aborts = DwebAborts(now = { now }, ttlMs = 1_000)
        fun begin(url: String = SEG, at: Long = now) = run { now = at; aborts.begin(url) }
        /** An entry for a request started between [lo] and [hi]. */
        fun done(url: String = SEG, lo: Long, hi: Long = lo) = aborts.pageDone(url, lo.toDouble(), hi.toDouble())
    }

    @Test
    fun `a request the page gave up on before its call is answered at once`() {
        val c = Clocked()
        c.done(lo = 990, hi = 995)
        assertTrue(c.begin(at = 1_010).abandoned)
        // Only the one: the next request for the same URL is fetched.
        assertFalse(c.begin().abandoned)
    }

    @Test
    fun `a call in flight is told when the page gives up on it`() {
        val c = Clocked()
        val ticket = c.begin(at = 1_000)
        val cut = CountDownLatch(1)
        ticket.onAbandon { cut.countDown() }
        assertFalse(ticket.abandoned)
        c.done(lo = 990, hi = 995) // started before its call began, for certain
        assertTrue(ticket.abandoned)
        assertEquals(0L, cut.count)
        assertEquals(0, c.aborts.working(SEG))
    }

    @Test
    fun `a call that is abandoned already runs its cut at once`() {
        val c = Clocked()
        c.done(lo = 990)
        val ticket = c.begin(at = 1_000)
        var ran = false
        ticket.onAbandon { ran = true }
        assertTrue(ran)
    }

    /** R1-F1: an aborted duplicate of a URL never cuts the older call still wanted. */
    @Test
    fun `an aborted duplicate leaves the earlier, wanted call alone`() {
        val c = Clocked()
        val wanted = c.begin(at = 1_000)
        // 300 ms later the page starts the same URL again and aborts it after 8 ms;
        // its entry comes before WebView has begun its call.
        c.now = 1_310
        c.done(lo = 1_299, hi = 1_302)
        assertFalse(wanted.abandoned)
        // The duplicate's own call is the one answered at once.
        assertTrue(c.begin(at = 1_320).abandoned)
        assertFalse(wanted.abandoned)
        // Its call had begun already: that one is cut, still not the wanted one.
        val again = c.begin(at = 1_400)
        c.now = 1_420
        c.done(lo = 1_390, hi = 1_395)
        assertTrue(again.abandoned)
        assertFalse(wanted.abandoned)
    }

    @Test
    fun `a call that may have begun before the entry's request is left alone`() {
        val c = Clocked()
        val ticket = c.begin(at = 1_000)
        c.done(lo = 990, hi = 1_005) // the call could be this request's, or one before it
        assertFalse(ticket.abandoned)
        // Nor is anything kept for a later call.
        assertFalse(c.begin(at = 1_010).abandoned)
    }

    @Test
    fun `with no lower bound yet an entry only acts where nothing could be another's`() {
        val c = Clocked()
        val ticket = c.begin(at = 1_000)
        c.aborts.pageDone(SEG, Double.NEGATIVE_INFINITY, 1_005.0)
        assertFalse(ticket.abandoned)
        c.aborts.finished(ticket)
        c.aborts.pageDone("$SEG/2", Double.NEGATIVE_INFINITY, 1_005.0)
        assertTrue(c.begin("$SEG/2", at = 1_010).abandoned)
    }

    @Test
    fun `an answered request's entry is matched to its answer, not to a later call`() {
        val c = Clocked()
        val first = c.begin(at = 1_000)
        c.aborts.finished(first) // answered; its entry comes once the page has read it
        val second = c.begin(at = 1_050)
        c.done(lo = 990, hi = 995) // the first request's entry
        assertFalse(second.abandoned)
        c.done(lo = 1_040, hi = 1_045) // now the second's: the page gave up on it
        assertTrue(second.abandoned)
    }

    @Test
    fun `an entry for an answered request leaves nothing behind for the next call`() {
        val c = Clocked()
        c.aborts.finished(c.begin(at = 1_000))
        c.done(lo = 990)
        assertFalse(c.begin(at = 1_100).abandoned)
    }

    @Test
    fun `of two calls in flight for one URL the one begun after the entry's start is cut`() {
        val c = Clocked()
        val first = c.begin(at = 1_000)
        val second = c.begin(at = 1_100)
        c.done(lo = 1_050, hi = 1_060)
        assertFalse(first.abandoned)
        assertTrue(second.abandoned)
        c.aborts.finished(first)
        c.aborts.finished(second)
        c.done(lo = 990) // the first, read by the page
        assertFalse(c.begin(at = 1_200).abandoned)
    }

    @Test
    fun `only the URL the page gave up on is stopped`() {
        val c = Clocked()
        val other = c.begin("$SEG/2", at = 1_000)
        c.done(lo = 990)
        assertFalse(other.abandoned)
        assertTrue(c.begin(at = 1_001).abandoned)
    }

    /** R1-M1: a record dropped to keep the ledger small can't leave its entry to cut another call. */
    @Test
    fun `an entry whose answered call was forgotten does nothing`() {
        val c = Clocked()
        c.aborts.finished(c.begin(at = 1_000)) // a large body: its entry lands much later
        // More than the ledger keeps of other URLs, each answered with no entry yet.
        for (i in 0 until 600) c.aborts.finished(c.begin("$SEG/x$i", at = 1_100L + i))
        val refetch = c.begin(at = 2_000)
        c.now = 2_010
        c.done(lo = 990, hi = 995) // the large body's entry
        assertFalse(refetch.abandoned)
        assertFalse(c.begin(at = 2_020).abandoned)
    }

    /** R2-F2: a stray entry costs at most the one call it's spent on; it isn't passed on. */
    @Test
    fun `a request answered at once by a stray entry doesn't pass it on`() {
        val c = Clocked()
        // An entry no call of ours made (a cache hit the page couldn't tell apart).
        c.done(lo = 900, hi = 902)
        // The next real request for the URL takes it: answered at once.
        c.now = 1_000
        val live = c.begin(at = 1_000)
        assertTrue(live.abandoned)
        c.aborts.finished(live)
        // That request's own entry: it started before its call began.
        c.now = 1_010
        c.done(lo = 995, hi = 997)
        // Retries within the TTL are fetched again, not answered at once in turn.
        for (at in listOf(1_100L, 1_300L, 1_600L)) {
            val retry = c.begin(at = at)
            assertFalse(retry.abandoned)
            c.aborts.finished(retry)
            c.now = at + 10
            c.done(lo = at - 5, hi = at - 3)
        }
    }

    /** R2-F1: every tracked answer lets a cross-origin page see a later cache hit on it as one. */
    @Test
    fun `timing is allowed on every tracked answer, once`() {
        assertEquals(mapOf("Timing-Allow-Origin" to "*"), DwebAborts.timingAllowed(null))
        assertEquals(
            mapOf("Content-Type" to "video/mp2t", "Timing-Allow-Origin" to "*"),
            DwebAborts.timingAllowed(mapOf("Content-Type" to "video/mp2t", "timing-allow-origin" to "https://a.example")),
        )
    }

    @Test
    fun `an entry whose call never comes is forgotten`() {
        val c = Clocked()
        c.done(lo = 990)
        assertFalse(c.begin(at = 2_000).abandoned)
    }

    @Test
    fun `an entry from a clock that went back is forgotten too`() {
        val c = Clocked()
        c.now = 5_000
        c.done(lo = 4_990)
        assertFalse(c.begin(at = 4_000).abandoned)
    }

    @Test
    fun `a request and its entry match without the fragment`() {
        val c = Clocked()
        c.done("$SEG#t=10", lo = 990)
        assertTrue(c.begin(at = 1_000).abandoned)
    }

    @Test
    fun `sleep ends early when the page gives up`() {
        val aborts = DwebAborts()
        val ticket = aborts.begin(SEG)
        val started = ticket.beganAt
        Thread { Thread.sleep(100); aborts.pageDone(SEG, started - 10.0, started - 5.0) }.start()
        val t0 = System.nanoTime()
        assertTrue(ticket.sleep(5_000))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertFalse(aborts.begin(SEG).sleep(50))
    }

    @Test
    fun `only subresources on the virtual origins are tracked`() {
        assertTrue(DwebAborts.tracks(SEG, mainFrame = false))
        assertTrue(DwebAborts.tracks("https://abc.ipfs.freedom.baby/a.js", mainFrame = false))
        assertFalse(DwebAborts.tracks(SEG, mainFrame = true))
        assertFalse(DwebAborts.tracks("https://example.com/seg/1", mainFrame = false))
        assertFalse(DwebAborts.tracks("http://abc.bzz.freedom.baby/seg/1", mainFrame = false))
        assertFalse(DwebAborts.tracks("bzz://abc/seg/1", mainFrame = false))
    }

    @Test
    fun `a report keeps only dweb subresource entries, on our clock`() {
        val report = listOf(
            "500.5 9000 9010", "100 $SEG", "100 https://example.com/x", "", "x".repeat(10), "nan $SEG",
            "120.25 https://abc.ens.freedom.baby/a",
        ).joinToString("\n")
        val parsed = parseDoneReport(report, receivedAt = 9_600)!!
        assertEquals(listOf(SEG, "https://abc.ens.freedom.baby/a"), parsed.entries.map { it.url })
        // Upper bound: the tighter of the page's (9010) and this message's (9600 + 1 - 500.5).
        assertEquals(9_100.0, parsed.entries[0].startedLo, 0.0)
        assertEquals(9_110.0, parsed.entries[0].startedHi, 0.0)
        assertEquals(9_200.5, parseDoneReport(report.replace("9010", "9200"), receivedAt = 9_600)!!.entries[0].startedHi, 0.0)
        assertEquals("9600 500.5", parsed.reply)
    }

    @Test
    fun `a report with no bounds yet, crossed bounds or a bad header is handled safely`() {
        val first = parseDoneReport("10 - -\n5 $SEG", receivedAt = 1_000)!!
        assertEquals(Double.NEGATIVE_INFINITY, first.entries[0].startedLo, 0.0)
        assertEquals(996.0, first.entries[0].startedHi, 0.0)
        assertEquals("1000 10", first.reply)
        assertEquals(0, parseDoneReport("10 - -", receivedAt = 1_000)!!.entries.size)
        assertEquals(null, parseDoneReport("10 2000 1500\n5 $SEG", receivedAt = 3_000))
        assertEquals(null, parseDoneReport("garbage\n5 $SEG", receivedAt = 3_000))
        assertEquals(null, parseDoneReport("", receivedAt = 3_000))
    }

    /** As seen on the emulator: both clocks' rounding can put the bounds a fraction of a millisecond apart the wrong way. */
    @Test
    fun `bounds crossed by rounding alone still act`() {
        val c = Clocked()
        val ticket = c.begin(at = 1_000)
        c.aborts.pageDone(SEG, 995.4, 995.1)
        assertTrue(ticket.abandoned)
        val parsed = parseDoneReport("10 990.3 990\n5 $SEG", receivedAt = 2_000)!!
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `the page script is built for the channel and the dweb suffixes`() {
        val js = dwebAbortsJs("abcdefghij")
        assertTrue(js.contains("'abcdefghij'"))
        for (suffix in VirtualOrigin.SUFFIXES) assertTrue(suffix, js.contains("'.$suffix/'"))
        assertTrue(runCatching { dwebAbortsJs("Bad-Name") }.isFailure)
    }

    /** The script run against a stand-in for the page's globals. */
    private class Page(deliveryType: Boolean = true) {
        val cx: Context = Context.enter().apply { optimizationLevel = -1; languageVersion = Context.VERSION_ES6 }
        val scope: Scriptable = cx.initStandardObjects()
        fun eval(js: String): Any? = cx.evaluateString(scope, js, "t", 1, null)
        init {
            eval(
                """
                var window = this, sent = [], observer = null, clockNow = 50, touched = 0;
                var port = window.abcdefghij = { postMessage: function (m) { sent.push(m); } };
                function Performance() {}
                Performance.prototype.now = function () { return clockNow; };
                var performance = new Performance();
                function PerformanceEntry(o) { this._o = o; }
                ['name', 'startTime'].forEach(function (k) {
                  Object.defineProperty(PerformanceEntry.prototype, k, { get: function () { return this._o[k]; } });
                });
                function PerformanceResourceTiming(o) { PerformanceEntry.call(this, o); }
                PerformanceResourceTiming.prototype = Object.create(PerformanceEntry.prototype);
                ['workerStart'${if (deliveryType) ", 'deliveryType'" else ""}].forEach(function (k) {
                  Object.defineProperty(PerformanceResourceTiming.prototype, k, { get: function () { return this._o[k]; } });
                });
                function PerformanceObserverEntryList(es) { this._es = es; }
                PerformanceObserverEntryList.prototype.getEntries = function () { return this._es.slice(); };
                function PerformanceObserver(cb) { this.cb = cb; }
                PerformanceObserver.prototype.observe = function (o) { observer = this; };
                function MessageEvent(d) { this._d = d; }
                Object.defineProperty(MessageEvent.prototype, 'data', { get: function () { return this._d; } });
                function entries(list) {
                  observer.cb(new PerformanceObserverEntryList(list.map(function (o) { return new PerformanceResourceTiming(o); })));
                }
                """.trimIndent(),
            )
        }
        fun close() = Context.exit()
        fun sent(): List<String> = (0 until Context.toNumber(eval("sent.length")).toInt()).map { Context.toString(eval("sent[$it]")) }
    }

    @Test
    fun `the page script reports its entries' start times, bracketed by the replies`() {
        val page = Page()
        try {
            page.eval(dwebAbortsJs("abcdefghij"))
            assertEquals(false, page.eval("'abcdefghij' in window"))
            // The empty report at document start, and our reply to it.
            assertEquals(listOf("50 - -"), page.sent())
            val reply = parseDoneReport(page.sent()[0], receivedAt = 10_052)!!.reply
            // The platform's own shape, a plain object, then a MessageEvent: both are read.
            page.eval("clockNow = 60; port.onmessage({ data: '$reply' });")
            page.eval("clockNow = 61; port.onmessage(new MessageEvent('$reply'));")
            // A page that hooks array indexes and object getters afterwards sees nothing.
            page.eval(
                """
                Object.defineProperty(Array.prototype, '0', { set: function (v) { touched++; }, get: function () { touched++; }, configurable: true });
                Object.defineProperty(Object.prototype, 'deliveryType', { get: function () { touched++; }, configurable: true });
                PerformanceEntry.prototype.toString = function () { touched++; return ''; };
                clockNow = 70;
                entries([
                  { name: '$SEG', startTime: 61.5, workerStart: 0, deliveryType: '' },
                  { name: 'https://abc.bzz.freedom.baby/sw', startTime: 62, workerStart: 1, deliveryType: '' },
                  { name: 'https://abc.bzz.freedom.baby/cached', startTime: 63, workerStart: 0, deliveryType: 'cache' },
                  { name: 'https://example.com/x', startTime: 64, workerStart: 0, deliveryType: '' }
                ]);
                """.trimIndent(),
            )
            assertEquals(0, Context.toNumber(page.eval("touched")).toInt())
            // Bounds: lower 10052 - 60, upper 10052 + 1 - 50 (our clock is whole milliseconds).
            assertEquals(listOf("70 9992 10003", "61.5 $SEG"), page.sent()[1].split('\n'))
            val parsed = parseDoneReport(page.sent()[1], receivedAt = 10_075)!!
            assertEquals(listOf(SEG), parsed.entries.map { it.url })
            assertEquals(10_053.5, parsed.entries[0].startedLo, 0.0)
            assertEquals(10_064.5, parsed.entries[0].startedHi, 0.0)
        } finally {
            page.close()
        }
    }

    /** R1-M2: before Chromium 109 a memory-cache hit can't be told from a request: nothing is reported. */
    @Test
    fun `the page script does nothing where WebView has no deliveryType`() {
        val page = Page(deliveryType = false)
        try {
            page.eval(dwebAbortsJs("abcdefghij"))
            assertEquals(false, page.eval("'abcdefghij' in window"))
            assertEquals(null, page.eval("observer"))
            assertEquals(emptyList<String>(), page.sent())
        } finally {
            page.close()
        }
    }

    @Test
    fun `abandoning a header wait cuts its connection and refuses the next hop`() {
        val cuts = AtomicInteger(0)
        val deadline = HeaderDeadline(5_000, patience = PatientWaits(0))
        deadline.connected(FakeConnection { cuts.incrementAndGet() })
        deadline.abandon()
        val deadlineMs = System.nanoTime() / 1_000_000 + 2_000
        while (cuts.get() == 0 && System.nanoTime() / 1_000_000 < deadlineMs) Thread.sleep(10)
        assertEquals(1, cuts.get())
        assertTrue(deadline.expired)
        assertTrue(runCatching { deadline.connected(FakeConnection {}) }.isFailure)
    }

    @Test
    fun `a fetch waiting on the gateway's headers stops when the page gives up`() {
        ServerSocket(0, 50, LOOPBACK).use { server ->
            val accepted = Collections.synchronizedList(mutableListOf<Socket>())
            val hungUp = CountDownLatch(1)
            Thread {
                runCatching {
                    val s = server.accept()
                    accepted += s
                    // Never answers; waits for the client to go.
                    if (s.getInputStream().let { i -> while (i.read() >= 0) Unit; true }) hungUp.countDown()
                }.onFailure { hungUp.countDown() }
            }.apply { isDaemon = true; start() }
            val url = "http://127.0.0.1:${server.localPort}/bzz/ab/seg/1"
            val aborts = DwebAborts()
            val ticket = aborts.begin(SEG)
            val begun = ticket.beganAt.toDouble()
            Thread { Thread.sleep(300); aborts.pageDone(SEG, begun - 10, begun - 5) }.start()
            val started = System.nanoTime()
            fetchWithRetry(request(), url, SEG, false, gatewayFetchPolicy(mainFrame = false, media = false), abandon = ticket)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("the gateway was never asked", accepted.isNotEmpty())
            assertTrue("took $ms ms: the 10 s header wait ran on", ms < 3_000)
            assertTrue("returned after $ms ms, before the page gave up", ms >= 250)
            assertTrue("the gateway connection stayed open", hungUp.await(3, TimeUnit.SECONDS))
            accepted.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `a fetch retrying a transient answer stops when the page gives up`() {
        ServerSocket(0, 50, LOOPBACK).use { server ->
            val asked = AtomicInteger(0)
            Thread {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { s ->
                            asked.incrementAndGet()
                            s.getOutputStream().write("HTTP/1.1 503 Busy\r\nContent-Type: text/plain\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val url = "http://127.0.0.1:${server.localPort}/bzz/ab/seg/1"
            val aborts = DwebAborts()
            val ticket = aborts.begin(SEG)
            val begun = ticket.beganAt.toDouble()
            // Past the first answers, inside the back-off that follows them.
            Thread { Thread.sleep(1_000); aborts.pageDone(SEG, begun - 10, begun - 5) }.start()
            val started = System.nanoTime()
            fetchWithRetry(request(), url, SEG, false, gatewayFetchPolicy(mainFrame = false, media = false), abandon = ticket)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $ms ms: the ~17 s back-off ran on", ms < 3_000)
            assertTrue("returned after $ms ms, before the page gave up", ms >= 900)
            val after = asked.get()
            // 0, 250 and 500 ms back-off: three or four answers by 1 s.
            assertTrue("the gateway was asked $after times", after in 3..4)
            Thread.sleep(600)
            assertEquals("asked again after the page gave up", after, asked.get())
        }
    }

    /** Task 3: a body WebView closes early cuts the gateway, through every wrapper the media path puts round it. */
    @Test
    fun `closing a sliced, seek-proofed media body drops the gateway connection`() {
        for (range in listOf<String?>(null, "bytes=100-", "bytes=-10")) {
            val disconnected = CountDownLatch(1)
            val body = DisconnectOnCloseInputStream(
                ByteArrayInputStream(ByteArray(1000)),
                FakeConnection { disconnected.countDown() },
                length = 1000,
            )
            val stream = webViewSeekProof(SlicedInputStream(body, 100, 500), range)
            stream.read()
            stream.close()
            assertTrue("range $range: not disconnected", disconnected.await(2, TimeUnit.SECONDS))
        }
    }

    private fun request() = object : android.webkit.WebResourceRequest {
        override fun getUrl(): android.net.Uri? = null
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private class FakeConnection(private val onDisconnect: () -> Unit) :
        HttpURLConnection(URL("http://127.0.0.1/")) {
        override fun disconnect() = onDisconnect()
        override fun usingProxy() = false
        override fun connect() {}
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        const val SEG = "https://abc.bzz.freedom.baby/seg/1"
    }
}
