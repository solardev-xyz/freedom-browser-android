package baby.freedom.mobile.browser

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Swarm node page's ACT public key (#541, desktop #1188). */
class ActPublicKeyTest {
    private val compressed = "02" + "ab".repeat(32)
    private val uncompressed = "04" + "cd".repeat(64)

    @Test
    fun `a compressed or uncompressed key is read as reported`() {
        assertEquals(compressed, ActPublicKey.from("""{"publicKey":"$compressed"}"""))
        assertEquals("03" + "AB".repeat(32), ActPublicKey.from("""{"publicKey":"03${"AB".repeat(32)}"}"""))
        assertEquals(uncompressed, ActPublicKey.from("""{"publicKey":"$uncompressed"}"""))
        assertEquals("0x$compressed", ActPublicKey.from("""{"publicKey":"0x$compressed"}"""))
    }

    @Test
    fun `ant's whole addresses body gives its publicKey`() {
        val body = """{"overlay":"bb98b93aeadabd8b4ae63fe552f2fff47f67d7f9c239b268cb89dece44c356cc",""" +
            """"underlay":["/ip4/127.0.0.1/tcp/46653"],"ethereum":"0x0d676e89248faf199af34e53dcbabe6d04cd2b3e",""" +
            """"publicKey":"027ed5a2b17b184a5d8fae18153a5319f4ce08b14ab5f7176975c27e4487204dd1",""" +
            """"pssPublicKey":"027ed5a2b17b184a5d8fae18153a5319f4ce08b14ab5f7176975c27e4487204dd1"}"""
        assertEquals("027ed5a2b17b184a5d8fae18153a5319f4ce08b14ab5f7176975c27e4487204dd1", ActPublicKey.from(body))
    }

    @Test
    fun `this device's node's key is taken only from the overlay the page shows`() {
        val overlay = "bb98b93aeadabd8b4ae63fe552f2fff47f67d7f9c239b268cb89dece44c356cc"
        val body = """{"overlay":"$overlay","publicKey":"$compressed"}"""
        // NodeInfo carries the overlay with 0x; /addresses without.
        assertEquals(compressed, ActPublicKey.from(body, "0x$overlay"))
        assertEquals(compressed, ActPublicKey.from(body, overlay.uppercase()))
        // Restarted under another identity since.
        assertNull(ActPublicKey.from(body, "0x" + "aa".repeat(32)))
        assertNull(ActPublicKey.from("""{"publicKey":"$compressed"}""", "0x$overlay"))
        assertNull(ActPublicKey.from("""{"overlay":"","publicKey":"$compressed"}""", "0x"))
        assertNull(ActPublicKey.from("""{"overlay":12,"publicKey":"$compressed"}""", "0x$overlay"))
    }

    @Test
    fun `anything that isn't a key reads as none`() {
        for (body in listOf(
            """{"publicKey":"${"02" + "ab".repeat(31)}"}""", // short
            """{"publicKey":"${"05" + "ab".repeat(32)}"}""", // bad prefix
            """{"publicKey":"${"04" + "ab".repeat(32)}"}""", // 04 at compressed length
            """{"publicKey":"${"02" + "zz".repeat(32)}"}""", // not hex
            """{"publicKey":" $compressed"}""",
            """{"publicKey":"$compressed\n"}""",
            """{"publicKey":12}""",
            """{"publicKey":null}""",
            """{"publicKey":["$compressed"]}""",
            """{"pssPublicKey":"$compressed"}""", // only the PSS key: not this one
            """{}""",
            """[]""",
            "not json",
            "",
            "[".repeat(100_000),
        )) {
            assertNull(body.take(80), ActPublicKey.from(body))
        }
    }

    private val running = NodeInfo(status = NodeStatus.Running, overlay = "aa".repeat(32))

    @Test
    fun `the key comes from the endpoint in use`() {
        val embedded = ActPublicKey.sourceFor("", running)!!
        assertEquals(SwarmNode.GATEWAY_URL, embedded.base)
        assertFalse(embedded.external)
        val external = ActPublicKey.sourceFor("http://192.168.1.20:1633", running)!!
        assertEquals("http://192.168.1.20:1633", external.base)
        assertTrue(external.external)
        // The external node is asked whatever this device's node is doing.
        assertEquals(external, ActPublicKey.sourceFor("http://192.168.1.20:1633", NodeInfo()))
    }

    @Test
    fun `there's nothing to ask while this device's node isn't running`() {
        for (status in NodeStatus.entries.filter { it != NodeStatus.Running }) {
            assertNull(ActPublicKey.sourceFor("", running.copy(status = status)))
        }
        assertNull(ActPublicKey.sourceFor("", running.copy(overlay = "")))
    }

    @Test
    fun `a key is shown only for the node it was read from`() {
        val a = ActPublicKey.sourceFor("", running)!!
        val read = ActPublicKey.Read(a, compressed)
        assertEquals(compressed, ActPublicKey.shown(read, a))
        // Restarted as another identity: another overlay, another node.
        val b = ActPublicKey.sourceFor("", running.copy(overlay = "bb".repeat(32)))!!
        assertNull(ActPublicKey.shown(read, b))
        // The external endpoint set, or the node stopped.
        assertNull(ActPublicKey.shown(read, ActPublicKey.sourceFor("http://nas.lan:1633", running)))
        assertNull(ActPublicKey.shown(read, null))
        assertNull(ActPublicKey.shown(null, a))
    }

    private fun served(status: String, body: String, external: Boolean = true, node: String = "x"): String? =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val acceptor = Thread {
                runCatching {
                    server.accept().use { s ->
                        s.getInputStream().read(ByteArray(4096))
                        val bytes = body.toByteArray()
                        s.getOutputStream().write(
                            "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes,
                        )
                    }
                }
            }.apply { isDaemon = true; start() }
            val source = ActPublicKey.Source("http://127.0.0.1:${server.localPort}", external, node)
            runBlocking { ActPublicKey.fetch(source) }.also { acceptor.join(5_000) }
        }

    @Test
    fun `a read asks the node's addresses`() {
        assertEquals(compressed, served("200 OK", """{"publicKey":"$compressed"}"""))
    }

    @Test
    fun `this device's node is read only for the overlay it was asked about`() {
        val body = """{"overlay":"${"aa".repeat(32)}","publicKey":"$compressed"}"""
        assertEquals(compressed, served("200 OK", body, external = false, node = "0x" + "aa".repeat(32)))
        assertNull(served("200 OK", body, external = false, node = "0x" + "bb".repeat(32)))
    }

    @Test
    fun `a node that can't be asked, or answers with no key, reads as none`() {
        assertNull(served("404 Not Found", """{"publicKey":"$compressed"}"""))
        assertNull(served("200 OK", """{"overlay":"aa"}"""))
        assertNull(served("200 OK", "{"))
        // Past the read's cap.
        assertNull(served("200 OK", """{"publicKey":"$compressed","pad":"${"x".repeat(100_000)}"}"""))
        // Nothing listening.
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertNull(runBlocking { ActPublicKey.fetch(ActPublicKey.Source("http://127.0.0.1:$port", true, "x")) })
    }
}
