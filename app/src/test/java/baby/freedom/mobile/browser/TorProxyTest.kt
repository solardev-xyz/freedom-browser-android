package baby.freedom.mobile.browser

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorProxyTest {
    private fun ok(raw: String) = TorProxy.parse(raw).endpoint?.authority
    private fun no(raw: String) = TorProxy.parse(raw).rejection

    @Test
    fun `loopback host-port endpoints are accepted and normalized`() {
        assertEquals("127.0.0.1:9050", ok("127.0.0.1:9050"))
        assertEquals("127.0.0.1:9050", ok("  127.0.0.1:9050  "))
        assertEquals("127.0.0.1:9150", ok("localhost:9150"))
        assertEquals("127.0.0.1:9150", ok("LOCALHOST:9150"))
        assertEquals("127.0.0.1:9050", ok("localhost.:9050"))
        assertEquals("127.1.2.3:9050", ok("127.1.2.3:9050"))
        assertEquals("[::1]:9050", ok("[::1]:9050"))
        assertEquals("127.0.0.1:9050", ok("socks5://127.0.0.1:9050"))
        assertEquals("127.0.0.1:9050", ok("SOCKS5H://127.0.0.1:9050/"))
        assertEquals("127.0.0.1:1", ok("127.0.0.1:1"))
        assertEquals("127.0.0.1:65535", ok("127.0.0.1:65535"))
        assertEquals(TorProxy.DEFAULT, TorProxy.stored("127.0.0.1:9050"))
        assertEquals(TorProxy.DEFAULT, TorProxy.stored("garbage"))
    }

    @Test
    fun `anything off the device is refused`() {
        for (raw in listOf(
            "192.168.1.10:9050", "10.0.2.2:9050", "0.0.0.0:9050", "8.8.8.8:9050",
            "example.com:9050", "tor.localhost:9050", "127.tracker.example:9050",
            "127.0.0.1.nip.io:9050", "[::2]:9050", "[0:0:0:0:0:0:0:1]:9050",
            "[::ffff:127.0.0.1]:9050", "127.0.0.01:9050", "0177.0.0.1:9050",
            "0x7f.0.0.1:9050", "127.1:9050", "2130706433:9050", "127.0.0.256:9050",
        )) {
            assertEquals(raw, TorProxy.Rejection.NOT_LOOPBACK, no(raw))
        }
    }

    @Test
    fun `malformed endpoints are refused with a reason`() {
        assertEquals(TorProxy.Rejection.EMPTY, no(""))
        assertEquals(TorProxy.Rejection.EMPTY, no("   "))
        assertEquals(TorProxy.Rejection.FORMAT, no("127.0.0.1"))
        assertEquals(TorProxy.Rejection.FORMAT, no("::1:9050"))
        assertEquals(TorProxy.Rejection.FORMAT, no("[::1]"))
        assertEquals(TorProxy.Rejection.FORMAT, no("user@127.0.0.1:9050"))
        assertEquals(TorProxy.Rejection.FORMAT, no("socks5://u:p@127.0.0.1:9050"))
        assertEquals(TorProxy.Rejection.FORMAT, no("127.0.0.1:9050/path"))
        assertEquals(TorProxy.Rejection.FORMAT, no("127.0.0.1:9050?x"))
        assertEquals(TorProxy.Rejection.FORMAT, no("127.0.0.1:9050#x"))
        assertEquals(TorProxy.Rejection.FORMAT, no("127.0.0.1 :9050"))
        assertEquals(TorProxy.Rejection.SCHEME, no("http://127.0.0.1:9050"))
        assertEquals(TorProxy.Rejection.SCHEME, no("socks4://127.0.0.1:9050"))
        assertEquals(TorProxy.Rejection.SCHEME, no("socks://127.0.0.1:9050"))
        assertEquals(TorProxy.Rejection.PORT, no("127.0.0.1:"))
        assertEquals(TorProxy.Rejection.PORT, no("127.0.0.1:0"))
        assertEquals(TorProxy.Rejection.PORT, no("127.0.0.1:65536"))
        assertEquals(TorProxy.Rejection.PORT, no("127.0.0.1:123456"))
        assertEquals(TorProxy.Rejection.PORT, no("127.0.0.1:90a0"))
        assertEquals(TorProxy.Rejection.PORT, no("127.0.0.1:-1"))
        assertEquals(TorProxy.Rejection.PORT, no("[::1]:x"))
        // Exactly one of the two.
        for (raw in listOf("", "127.0.0.1:9050", "8.8.8.8:1")) {
            val p = TorProxy.parse(raw)
            assertTrue(raw, (p.endpoint == null) != (p.rejection == null))
        }
    }

    // --- Probe, against fake listeners on 127.0.0.1 ----------------------

    private val servers = Collections.synchronizedList(mutableListOf<ServerSocket>())

    @After
    fun closeServers() {
        servers.forEach { runCatching { it.close() } }
    }

    /** A listener on 127.0.0.1 running [handle] for each connection. */
    private fun serve(handle: (Socket) -> Unit): SocksEndpoint {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { s.use(handle) } }
            }
        }
        return SocksEndpoint("127.0.0.1", server.localPort)
    }

    /**
     * A SOCKS5 server answering each CONNECT's hostname with [reply]'s code
     * (`null`: never, negative: closed without one); records the hostnames.
     */
    private fun socks5(asked: MutableList<String>, reply: (String) -> Int?): SocksEndpoint = serve { s ->
        val input = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        assertEquals(5, input.read())
        val methods = ByteArray(input.read()).also { input.readFully(it) }
        assertTrue(0.toByte() in methods)
        out.write(byteArrayOf(5, 0))
        val head = ByteArray(4).also { input.readFully(it) }
        if (head[3].toInt() != 3) return@serve
        val name = String(ByteArray(input.read()).also { input.readFully(it) }, Charsets.US_ASCII)
        val port = input.readUnsignedShort()
        asked += "$name:$port"
        val code = reply(name) ?: run { Thread.sleep(10_000); return@serve }
        if (code < 0) return@serve // closed without a reply
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun closedPort(): SocksEndpoint {
        val s = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = s.localPort
        s.close()
        return SocksEndpoint("127.0.0.1", port)
    }

    /** A Tor-like SOCKS5 server: [CANARY_ONION] refused (1, as Tor does), others answered by [reply]. */
    private fun tor(asked: MutableList<String>, reply: (String) -> Int? = { 0 }): SocksEndpoint =
        socks5(asked) { name -> if (name == TorProxy.CANARY_ONION) 1 else reply(name) }

    private val canary = "${TorProxy.CANARY_ONION}:80"

    @Test
    fun `a proxy that refuses the impossible onion and connects a real one is Tor`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val tor = tor(asked)
        assertEquals(TorProxy.Probe.Tor, TorProxy.probe(tor))
        // The hostname goes to the proxy (ATYP domain), port 80: the canary,
        // then the first onion only.
        assertEquals(listOf(canary, "${TorProxy.PROBE_ONIONS[0]}:80"), asked.toList())
        assertTrue(TorProxy.listening(tor))
        assertEquals(TorProxy.Probe.Tor, TorProxy.recheck(tor))
    }

    @Test
    fun `a proxy that answers success before dialing isn't Tor, and gets no real onion name`() = runBlocking {
        // shadowsocks' ss-local, v2ray/xray and clash socks inbounds reply 0
        // to any CONNECT before dialing (R1-F1).
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val early = socks5(asked) { 0 }
        assertEquals(TorProxy.Probe.NotTor, TorProxy.probe(early))
        assertEquals(listOf(canary), asked.toList())
        // And a confirmed proxy's port taken over by one is caught on recheck.
        assertEquals(TorProxy.Probe.NotTor, TorProxy.recheck(early))
        assertTrue(TorProxy.describe(TorProxy.Probe.NotTor, early).contains("isn't Tor"))
    }

    @Test
    fun `the canary is a well-formed v3 onion name no service can have`() {
        fun decode(label: String): ByteArray {
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            var bits = 0L
            var n = 0
            val out = java.io.ByteArrayOutputStream()
            for (c in label) {
                bits = (bits shl 5) or alphabet.indexOf(c).also { assertTrue(it >= 0) }.toLong()
                n += 5
                if (n >= 8) {
                    n -= 8
                    out.write(((bits shr n) and 0xff).toInt())
                }
            }
            return out.toByteArray()
        }
        fun valid(onion: String): Boolean {
            val label = onion.removeSuffix(".onion")
            assertEquals(56, label.length)
            val b = decode(label)
            assertEquals(35, b.size)
            val sha3 = java.security.MessageDigest.getInstance("SHA3-256")
            sha3.update(".onion checksum".toByteArray())
            sha3.update(b, 0, 32)
            sha3.update(b[34])
            val sum = sha3.digest()
            return b[34].toInt() == 3 && sum[0] == b[32] && sum[1] == b[33]
        }
        TorProxy.PROBE_ONIONS.forEach { assertTrue(it, valid(it)) }
        assertFalse(valid(TorProxy.CANARY_ONION))
    }

    @Test
    fun `one probe onion down is enough if the other answers`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val tor = tor(asked) { name -> if (name == TorProxy.PROBE_ONIONS[0]) 1 else 0 }
        assertEquals(TorProxy.Probe.Tor, TorProxy.probe(tor))
        assertEquals(listOf(canary) + TorProxy.PROBE_ONIONS.map { "$it:80" }, asked.toList())
    }

    @Test
    fun `a plain SOCKS proxy that can't reach onion names isn't Tor`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val plain = socks5(asked) { 4 } // host unreachable: it tried DNS
        assertEquals(TorProxy.Probe.NoOnion(4), TorProxy.probe(plain))
        assertEquals(3, asked.size)
        // It does listen, though.
        assertTrue(TorProxy.listening(plain))
    }

    @Test
    fun `a canary with no refusal isn't a pass, and no real onion name is sent after it`() = runBlocking {
        // Tor refuses the canary at once; no reply, a late one or a closed
        // connection isn't Tor (R2-F2).
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val silent = socks5(asked) { name -> if (name == TorProxy.CANARY_ONION) null else 0 }
        assertEquals(TorProxy.Probe.NoOnion(-1), TorProxy.probe(silent, canaryTimeoutMs = 300))
        assertEquals(TorProxy.Probe.NoOnion(-1), TorProxy.recheck(silent, canaryTimeoutMs = 300))
        assertEquals(listOf(canary, canary), asked.toList())

        // A proxy that dials first and answers "connected" only after a slow
        // upstream (shadowsocks over a slow link): past the canary deadline,
        // so it never gets a real onion name to "connect" too.
        asked.clear()
        val late = socks5(asked) { Thread.sleep(1_000); 0 }
        assertEquals(TorProxy.Probe.NoOnion(-1), TorProxy.probe(late, canaryTimeoutMs = 300))
        assertEquals(listOf(canary), asked.toList())

        // Closed without a reply.
        asked.clear()
        val eof = socks5(asked) { name -> if (name == TorProxy.CANARY_ONION) -1 else 0 }
        assertEquals(TorProxy.Probe.NoOnion(-1), TorProxy.probe(eof))
        assertEquals(TorProxy.Probe.NoOnion(-1), TorProxy.recheck(eof))
        assertEquals(listOf(canary, canary), asked.toList())
    }

    @Test
    fun `a plain SOCKS proxy passes the canary alone, so only the full probe tells it from Tor`() = runBlocking {
        // Why a confirmed proxy is re-probed in full, not just canaried: a
        // plain SOCKS5 proxy (ssh -D, …) that took Tor's port refuses the
        // impossible onion as Tor does, and fails only the real one (R2-F1).
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val plain = socks5(asked) { 4 }
        assertEquals(TorProxy.Probe.Tor, TorProxy.recheck(plain))
        assertEquals(TorProxy.Probe.NoOnion(4), TorProxy.probe(plain))
    }

    @Test
    fun `re-checks back off for a proxy that isn't Tor, not for one that's absent`() {
        assertEquals(TorProxy.RECHECK_MS, TorProxy.nextCheckMs(TorProxy.Probe.Tor, TorProxy.RETRY_MS))
        assertEquals(TorProxy.RETRY_MS, TorProxy.nextCheckMs(TorProxy.Probe.NotListening, 80_000))
        assertFalse(TorProxy.backsOff(TorProxy.Probe.NotListening))
        assertFalse(TorProxy.backsOff(TorProxy.Probe.Tor))
        var wait = TorProxy.RETRY_MS
        val waits = List(8) { wait = TorProxy.nextCheckMs(TorProxy.Probe.NoOnion(4), wait); wait }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L), waits)
        listOf(TorProxy.Probe.NotSocks, TorProxy.Probe.NotTor, TorProxy.Probe.NoOnion(-1)).forEach {
            assertTrue(TorProxy.backsOff(it))
        }
    }

    @Test
    fun `a confirmed Tor that can't reach an onion once stays routed for one quick check`() {
        // R3-F1: the canary still refused as Tor does, the onions slow on a
        // flaky link — not "gone" on the first miss.
        val tor = TorProxy.Probe.Tor
        val slow = TorProxy.Probe.NoOnion(-1)
        var next = TorProxy.afterCheck(TorProxy.Watch(), tor, tor, nowMs = 1_000)
        assertTrue(next.watch.confirmed)
        assertEquals(TorProxy.RECHECK_MS, next.waitMs)
        next = TorProxy.afterCheck(next.watch, tor, slow, nowMs = 21_000)
        assertTrue(next.watch.confirmed)
        assertTrue(next.watch.unreached)
        assertEquals(TorProxy.RETRY_MS, next.waitMs)
        // Passing again forgets the miss.
        val back = TorProxy.afterCheck(next.watch, tor, tor, nowMs = 26_000)
        assertEquals(TorProxy.Watch(confirmed = true, confirmedAtMs = 26_000), back.watch)
        // A second miss in a row: refused, and checked every RECHECK_MS
        // (no back-off) while Tor still answers, within the window.
        next = TorProxy.afterCheck(next.watch, tor, TorProxy.Probe.NoOnion(0xF2), nowMs = 26_000)
        assertFalse(next.watch.confirmed)
        assertTrue(next.watch.unreached)
        assertEquals(TorProxy.RECHECK_MS, next.waitMs)
        repeat(20) { i ->
            next = TorProxy.afterCheck(next.watch, tor, slow, nowMs = 46_000L + i * 20_000)
            assertEquals(TorProxy.RECHECK_MS, next.waitMs)
            assertFalse(next.watch.confirmed)
        }
        // Past the window, the usual back-off.
        next = TorProxy.afterCheck(next.watch, tor, slow, nowMs = 21_000 + TorProxy.FAST_RETRY_WINDOW_MS)
        assertEquals(10_000L, next.waitMs)
        next = TorProxy.afterCheck(next.watch, tor, slow, nowMs = 31_000 + TorProxy.FAST_RETRY_WINDOW_MS)
        assertEquals(20_000L, next.waitMs)
        // And Tor getting through again routes it.
        next = TorProxy.afterCheck(next.watch, tor, tor, nowMs = 51_000 + TorProxy.FAST_RETRY_WINDOW_MS)
        assertTrue(next.watch.confirmed)
        assertFalse(next.watch.unreached)
    }

    @Test
    fun `a confirmed proxy that stops refusing the canary gets no grace`() {
        // The listener isn't the Tor that was confirmed (R2-F1): refused on
        // the first such check.
        val confirmed = TorProxy.afterCheck(TorProxy.Watch(), TorProxy.Probe.Tor, TorProxy.Probe.Tor, 0).watch
        listOf(
            TorProxy.Probe.NotListening,
            TorProxy.Probe.NotSocks,
            TorProxy.Probe.NotTor,
            TorProxy.Probe.NoOnion(-1), // the canary itself got no refusal
        ).forEach { canary ->
            val next = TorProxy.afterCheck(confirmed, canary, canary, 20_000)
            assertFalse(canary.toString(), next.watch.confirmed)
            assertFalse(canary.toString(), next.watch.unreached)
            assertEquals(TorProxy.nextCheckMs(canary, TorProxy.RETRY_MS), next.waitMs)
        }
        // Nor a proxy never confirmed that can't reach an onion: backs off.
        val never = TorProxy.afterCheck(TorProxy.Watch(), TorProxy.Probe.Tor, TorProxy.Probe.NoOnion(4), 0)
        assertFalse(never.watch.confirmed)
        assertEquals(10_000L, never.waitMs)
    }

    @Test
    fun `a plain SOCKS5 error to the probe onions is no Tor that can't get through`() {
        // R4-M1: a plain SOCKS5 proxy that took the port refuses the canary
        // and the probe onions at once with a plain error (it can't look the
        // names up) — refused on the first check, no grace, no "Tor answers"
        // copy, no fast re-checks: backs off like any proxy that isn't Tor.
        val tor = TorProxy.Probe.Tor
        val confirmed = TorProxy.afterCheck(TorProxy.Watch(), tor, tor, nowMs = 0).watch
        (1..8).forEach { code ->
            val plain = TorProxy.Probe.NoOnion(code)
            assertFalse(TorProxy.unreachedByTor(plain))
            val next = TorProxy.afterCheck(confirmed, tor, plain, nowMs = 20_000)
            assertFalse("$code", next.watch.confirmed)
            assertFalse("$code", next.watch.unreached)
            assertEquals(10_000L, next.waitMs)
            assertEquals(20_000L, TorProxy.afterCheck(next.watch, tor, plain, nowMs = 30_000).waitMs)
        }
        // A timeout, or Tor's own onion-service errors (ExtendedErrors), are.
        assertTrue(TorProxy.unreachedByTor(TorProxy.Probe.NoOnion(-1)))
        (0xF0..0xF7).forEach { assertTrue(TorProxy.unreachedByTor(TorProxy.Probe.NoOnion(it))) }
        assertTrue(TorProxy.afterCheck(confirmed, tor, TorProxy.Probe.NoOnion(0xF0), 20_000).watch.confirmed)
    }

    @Test
    fun `a page's nudge doesn't cut a back-off short, the user's does`() {
        // R4-M2: a page adding onion iframes (or reloading the refusal page)
        // in a loop must not have a proxy that isn't Tor probed every
        // RETRY_MS.
        val plain = TorProxy.Probe.NoOnion(4)
        var watch = TorProxy.afterCheck(TorProxy.Watch(), TorProxy.Probe.Tor, plain, 0).watch
        assertTrue(watch.backoffMs > TorProxy.RETRY_MS)
        assertNull(TorProxy.afterNudge(watch, byUser = false))
        assertEquals(TorProxy.RETRY_MS, TorProxy.afterNudge(watch, byUser = true)!!.backoffMs)
        // Not backing off (nothing listening; Tor that can't get through in
        // the window): a page's nudge checks sooner, and changes nothing.
        watch = TorProxy.afterCheck(TorProxy.Watch(), TorProxy.Probe.NotListening, TorProxy.Probe.NotListening, 0).watch
        assertEquals(watch, TorProxy.afterNudge(watch, byUser = false))
        val tor = TorProxy.Probe.Tor
        watch = TorProxy.afterCheck(TorProxy.Watch(), tor, tor, 0).watch
        watch = TorProxy.afterCheck(watch, tor, TorProxy.Probe.NoOnion(-1), 20_000).watch
        watch = TorProxy.afterCheck(watch, tor, TorProxy.Probe.NoOnion(-1), 25_000).watch
        assertFalse(watch.confirmed)
        assertEquals(watch, TorProxy.afterNudge(watch, byUser = false))
    }

    @Test
    fun `the canary is sent once per check`() = runBlocking {
        // R3-M1: recheck, then reachOnion — not recheck and then probe.
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val tor = socks5(asked) { if (it == TorProxy.CANARY_ONION) 1 else 0 }
        assertEquals(TorProxy.Probe.Tor, TorProxy.recheck(tor))
        assertEquals(TorProxy.Probe.Tor, TorProxy.reachOnion(tor))
        assertEquals(listOf("${TorProxy.CANARY_ONION}:80", "${TorProxy.PROBE_ONIONS[0]}:80"), asked.toList())
    }

    @Test
    fun `nothing listening`() = runBlocking {
        val closed = closedPort()
        assertEquals(TorProxy.Probe.NotListening, TorProxy.probe(closed))
        assertEquals(TorProxy.Probe.NotListening, TorProxy.recheck(closed))
        assertFalse(TorProxy.listening(closed))
    }

    @Test
    fun `something that isn't SOCKS5 without a password`() = runBlocking {
        val http = serve { s ->
            s.getInputStream().read(ByteArray(3))
            s.getOutputStream().write("HTTP/1.0 400 Bad Request\r\n\r\n".toByteArray())
        }
        assertEquals(TorProxy.Probe.NotSocks, TorProxy.probe(http))
        assertEquals(TorProxy.Probe.NotSocks, TorProxy.recheck(http))
        assertFalse(TorProxy.listening(http))
        val authOnly = serve { s ->
            s.getInputStream().read(ByteArray(3))
            s.getOutputStream().write(byteArrayOf(5, 0xff.toByte()))
        }
        assertEquals(TorProxy.Probe.NotSocks, TorProxy.probe(authOnly))
    }

    @Test
    fun `a listener that never answers is given up on at its deadline`() = runBlocking {
        val silent = serve { Thread.sleep(10_000) }
        val start = System.nanoTime()
        assertEquals(TorProxy.Probe.NotSocks, TorProxy.probe(silent, handshakeTimeoutMs = 300))
        assertTrue(System.nanoTime() - start < 2_000_000_000L)

        // Greets, then holds the CONNECT forever (a Tor still bootstrapping
        // that never gets there): each onion bounded by its own deadline.
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val stuck = tor(asked) { null }
        val t0 = System.nanoTime()
        assertEquals(
            TorProxy.Probe.NoOnion(-1),
            TorProxy.probe(stuck, onionTimeoutMs = 300, canaryTimeoutMs = 300),
        )
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L)
        assertEquals(listOf(canary) + TorProxy.PROBE_ONIONS.map { "$it:80" }, asked.toList())
    }

    @Test
    fun `a byte trickled per read doesn't stretch the deadline`() = runBlocking {
        // Greets, then sends the CONNECT reply one byte at a time, each
        // just inside a per-read timeout: only the overall deadline ends it.
        val trickle = serve { s ->
            val input = s.getInputStream()
            input.read(ByteArray(3))
            s.getOutputStream().write(byteArrayOf(5, 0))
            input.read(ByteArray(300))
            Thread.sleep(250)
            s.getOutputStream().write(byteArrayOf(5))
            Thread.sleep(10_000)
        }
        val t0 = System.nanoTime()
        assertEquals(
            TorProxy.Probe.NoOnion(-1),
            TorProxy.probe(
                trickle,
                onions = listOf(TorProxy.PROBE_ONIONS[0]),
                onionTimeoutMs = 600,
                canaryTimeoutMs = 600,
            ),
        )
        // The canary, ended at its 600 ms; no onion after a canary with no refusal.
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L)
    }

    @Test
    fun `each verdict reads as what to do`() {
        val e = TorProxy.DEFAULT
        assertTrue(TorProxy.describe(TorProxy.Probe.Tor, e).contains("reached a .onion site"))
        assertTrue(TorProxy.describe(TorProxy.Probe.NotListening, e).contains("Start Orbot"))
        assertTrue(TorProxy.describe(TorProxy.Probe.NotSocks, e).contains("isn't a SOCKS5 proxy"))
        assertTrue(TorProxy.describe(TorProxy.Probe.NoOnion(4), e).contains("SOCKS error 4"))
        assertTrue(TorProxy.describe(TorProxy.Probe.NoOnion(-1), e).contains("in time"))
        assertNull(TorProxy.parse("127.0.0.1:9050").rejection)
    }
}
