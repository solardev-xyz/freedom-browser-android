package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the device's own HttpURLConnection: [GatewayHttp] — which manifest
 * discovery uses for the external Swarm endpoint — sends an onion
 * endpoint's name to the routed Tor SOCKS proxy and never resolves it
 * here, and refuses it with no route (#356).
 */
@RunWith(AndroidJUnit4::class)
class GatewayHttpOnionDeviceTest {
    @After
    fun unroute() = setRouted(null)

    @Test
    fun `an onion endpoint is refused with no Tor routed`() {
        setRouted(null)
        try {
            GatewayHttp.requestAt("http://$ONION:1633", "GET", "/bzz/abc/freedom-manifest.json", emptyMap(), null, 5_000)
            fail("an onion endpoint was opened with no Tor routed")
        } catch (_: TorRouting.RefusedException) {
        }
    }

    @Test
    fun `an onion endpoint goes to the routed Tor proxy by name`() {
        ServerSocket(0, 1, LOOPBACK).use { proxy ->
            val asked = AtomicReference<String>()
            val requestLine = AtomicReference<String>()
            val server = Thread {
                runCatching {
                    proxy.accept().use { s ->
                        val input = DataInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        // Greeting: VER, NMETHODS, METHODS — answer "no authentication".
                        check(input.readUnsignedByte() == 5)
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        out.write(byteArrayOf(5, 0)); out.flush()
                        // CONNECT: VER CMD RSV ATYP ADDR PORT.
                        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                        input.readUnsignedByte()
                        val atyp = input.readUnsignedByte()
                        val target = when (atyp) {
                            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                            1 -> "ipv4:" + ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                            else -> "atyp$atyp"
                        }
                        asked.set("$target:${input.readUnsignedShort()}")
                        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                        // Now the tunnelled HTTP request.
                        val reader = input.bufferedReader()
                        requestLine.set(reader.readLine())
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }
            setRouted(SocksEndpoint("127.0.0.1", proxy.localPort))
            val answer = GatewayHttp.requestAt(
                "http://$ONION:1633", "GET", "/bzz/abc/freedom-manifest.json", emptyMap(), null, 5_000,
            )
            server.join(5_000)
            assertEquals(200, answer.status)
            assertEquals("ok", String(answer.body))
            // The name itself (SOCKS5 ATYP 3), not an address looked up here.
            assertEquals("$ONION:1633", asked.get())
            assertTrue(requestLine.get(), requestLine.get().startsWith("GET /bzz/abc/freedom-manifest.json "))
        }
    }

    private fun setRouted(endpoint: SocksEndpoint?) {
        TorRouting::class.java.getDeclaredField("routed").apply { isAccessible = true }.set(TorRouting, endpoint)
    }

    private companion object {
        const val ONION = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
