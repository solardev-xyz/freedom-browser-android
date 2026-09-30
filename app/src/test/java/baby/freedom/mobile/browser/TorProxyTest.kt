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

    @Test
    fun `a proxy that connects to a onion service is Tor`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val tor = socks5(asked) { 0 }
        assertEquals(TorProxy.Probe.Tor, TorProxy.probe(tor))
        // The hostname goes to the proxy (ATYP domain), port 80, first onion only.
        assertEquals(listOf("${TorProxy.PROBE_ONIONS[0]}:80"), asked.toList())
        assertTrue(TorProxy.listening(tor))
    }

    @Test
    fun `one probe onion down is enough if the other answers`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val tor = socks5(asked) { name -> if (name == TorProxy.PROBE_ONIONS[0]) 1 else 0 }
        assertEquals(TorProxy.Probe.Tor, TorProxy.probe(tor))
        assertEquals(TorProxy.PROBE_ONIONS.map { "$it:80" }, asked.toList())
    }

    @Test
    fun `a plain SOCKS proxy that can't reach onion names isn't Tor`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val plain = socks5(asked) { 4 } // host unreachable: it tried DNS
        assertEquals(TorProxy.Probe.NoOnion(4), TorProxy.probe(plain))
        assertEquals(2, asked.size)
        // It does listen, though.
        assertTrue(TorProxy.listening(plain))
    }

    @Test
    fun `nothing listening`() = runBlocking {
        val closed = closedPort()
        assertEquals(TorProxy.Probe.NotListening, TorProxy.probe(closed))
        assertFalse(TorProxy.listening(closed))
    }

    @Test
    fun `something that isn't SOCKS5 without a password`() = runBlocking {
        val http = serve { s ->
            s.getInputStream().read(ByteArray(3))
            s.getOutputStream().write("HTTP/1.0 400 Bad Request\r\n\r\n".toByteArray())
        }
        assertEquals(TorProxy.Probe.NotSocks, TorProxy.probe(http))
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
        assertEquals(TorProxy.Probe.NoOnion(-1), TorProxy.probe(stuck, onionTimeoutMs = 300))
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L)
        assertEquals(2, asked.size)
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
            TorProxy.probe(trickle, onions = listOf(TorProxy.PROBE_ONIONS[0]), onionTimeoutMs = 600),
        )
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L)
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
