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

    /** A SOCKS5 server answering each CONNECT's hostname with [reply]'s code; records the hostnames. */
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
    fun `a canary with no answer in time is inconclusive, not proof of either`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val slow = socks5(asked) { name -> if (name == TorProxy.CANARY_ONION) null else 0 }
        assertEquals(TorProxy.Probe.Tor, TorProxy.probe(slow, canaryTimeoutMs = 300))
        assertEquals(TorProxy.Probe.Tor, TorProxy.recheck(slow, canaryTimeoutMs = 300))
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
        val stuck = socks5(asked) { null }
        val t0 = System.nanoTime()
        assertEquals(
            TorProxy.Probe.NoOnion(-1),
            TorProxy.probe(stuck, onionTimeoutMs = 300, canaryTimeoutMs = 300),
        )
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L)
        assertEquals(3, asked.size)
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
        // Canary and onion, each ended at its own 600 ms.
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
