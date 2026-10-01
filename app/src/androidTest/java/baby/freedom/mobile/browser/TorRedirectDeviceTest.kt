package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * #143 / PR #203 R1-F1 against the platform's own `HttpURLConnection`
 * (OkHttp inside, which percent-decodes and IDNA-maps the host): a
 * clearnet redirect to an onion however spelled is refused before any
 * connection, and a plain redirect chain is still followed.
 */
@RunWith(AndroidJUnit4::class)
class TorRedirectDeviceTest {
    private val name = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid"
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun aRedirectToAnEncodedOnionIsRefusedWithoutTor() {
        for (location in listOf("http://$name%2eonion/f.bin", "http://$name.on%C2%ADion/f.bin", "http://$name.onion/f.bin")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
            try {
                TorRouting.openFollowingRedirects(URL(server.url("/dl").toString())) { }
                fail("followed $location")
            } catch (_: TorRouting.RefusedException) {
            }
        }
        // The stack itself reads the encoded host as an onion name.
        assertEquals("$name.onion", okhttp3.HttpUrl.Builder().scheme("http").host("$name%2eonion").build().host)
    }

    @Test
    fun aPlainRedirectChainIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val conn = TorRouting.openFollowingRedirects(URL(server.url("/first").toString())) {
            setRequestProperty("X-Hop", it.path)
        }
        assertEquals(200, conn.responseCode)
        assertEquals("ok", conn.inputStream.bufferedReader().readText())
        conn.disconnect()
        assertEquals("/first", server.takeRequest().getHeader("X-Hop"))
        assertEquals("/next", server.takeRequest().getHeader("X-Hop"))
    }

    /**
     * #359 R4-F1: with a system proxy, the socket an https hop gets is the
     * proxy's (a CONNECT tunnel to 127.0.0.1 here), so a peer check on it
     * would refuse every cross-origin https hop. Through the proxy the
     * hop is judged by its name's lookup and followed.
     */
    @Test
    fun aCrossOriginHttpsHopThroughASystemProxyIsFollowed() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root)
            .addSubjectAlternativeName("gateway.test").addSubjectAlternativeName("other.test").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        server.useHttps(serverCerts.sslSocketFactory(), tunnelProxy = true)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END).clearHeaders())
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/ipfs/y"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END).clearHeaders())
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        withProxyAndNames("other.test" to "198.51.100.7") {
            val conn = TorRouting.openFollowingRedirects(URL("https://gateway.test/ipfs/x")) {
                (this as HttpsURLConnection).sslSocketFactory = clientCerts.sslSocketFactory()
            }
            assertEquals(200, conn.responseCode)
            assertEquals("ok", conn.inputStream.bufferedReader().readText())
            conn.disconnect()
        }
        assertEquals("CONNECT gateway.test:443 HTTP/1.1", server.takeRequest().requestLine)
        assertEquals("GET /ipfs/x HTTP/1.1", server.takeRequest().requestLine)
        assertEquals("CONNECT other.test:443 HTTP/1.1", server.takeRequest().requestLine)
        assertEquals("GET /ipfs/y HTTP/1.1", server.takeRequest().requestLine)
    }

    /**
     * #359 R4-F2: an http hop through a system proxy isn't pinned to an
     * address — the proxy resolves the name and the request line names
     * it, so a virtual-hosted gateway gets its own name.
     */
    @Test
    fun anHttpHopThroughASystemProxyKeepsItsName() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://other.test/ipfs/y"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        withProxyAndNames("other.test" to "198.51.100.7") {
            val conn = TorRouting.openFollowingRedirects(URL("http://gateway.test/ipfs/x")) { }
            assertEquals(200, conn.responseCode)
            conn.disconnect()
        }
        assertEquals("GET http://gateway.test/ipfs/x HTTP/1.1", server.takeRequest().requestLine)
        val hop = server.takeRequest()
        assertEquals("GET http://other.test/ipfs/y HTTP/1.1", hop.requestLine)
        assertEquals("other.test", hop.getHeader("Host"))
    }

    /**
     * #359 R5-F1: a hop whose Location `java.net.URI` rejects as written (a
     * `|`) still goes through the system proxy, not straight from here.
     */
    @Test
    fun aHopJavaNetUriRejectsStillGoesThroughTheProxy() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://other.test/ipfs/y|z"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        withProxyAndNames("other.test" to "198.51.100.7") {
            val conn = TorRouting.openFollowingRedirects(URL("http://gateway.test/ipfs/x")) { }
            assertEquals(200, conn.responseCode)
            conn.disconnect()
        }
        assertEquals("GET http://gateway.test/ipfs/x HTTP/1.1", server.takeRequest().requestLine)
        val hop = server.takeRequest()
        assertEquals("other.test", hop.getHeader("Host"))
        assertTrue(hop.requestLine, hop.requestLine.startsWith("GET http://other.test/ipfs/y"))
    }

    /**
     * A hop through a system proxy that can't be reached isn't retried
     * straight from here (HttpURLConnection's own fallback), where its
     * name would be looked up afresh past the hop's checks.
     */
    @Test
    fun aHopsFailedProxyIsNotRetriedDirectly() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://other.test/ipfs/y"))
        val dead = java.net.ServerSocket(0).use { it.localPort }
        withProxyAndNames("other.test" to "198.51.100.7", hopProxyPort = dead) {
            try {
                TorRouting.openFollowingRedirects(URL("http://gateway.test/ipfs/x")) { connectTimeout = 3_000 }
                fail("reached other.test")
            } catch (e: IOException) {
                // A direct retry would ask the system resolver for other.test.
                assertFalse("retried directly: $e", e is java.net.UnknownHostException)
                assertTrue("$e", e is java.net.ConnectException)
            }
        }
    }

    /**
     * #359 R4-F2: a pinned http hop whose first address can't be reached
     * falls back to the next one, as the connection's own lookup would,
     * still sending the name as Host.
     */
    @Test
    fun aPinnedHopFallsBackToTheNextAddress() {
        val local = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        val direct = MockWebServer()
        direct.start(local, 0)
        try {
            val port = direct.port
            direct.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://other.test:$port/ipfs/y"))
            direct.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            // An unrouted (documentation-prefix) v6 address first: it fails to connect.
            withNames("other.test" to "2001:db8::1", "other.test" to local.hostAddress!!) {
                val conn = TorRouting.openFollowingRedirects(URL("http://${local.hostAddress}:$port/ipfs/x")) {
                    connectTimeout = 3_000
                    readTimeout = 5_000
                }
                assertEquals(200, conn.responseCode)
                assertEquals("ok", conn.inputStream.bufferedReader().readText())
                conn.disconnect()
            }
            direct.takeRequest()
            assertEquals("other.test:$port", direct.takeRequest().getHeader("Host"))
        } finally {
            direct.shutdown()
        }
    }

    private fun withNames(vararg names: Pair<String, String>, block: () -> Unit) {
        val table = names.groupBy({ it.first }, { InetAddress.getByName(it.second) })
        val real = TorRouting.resolve
        TorRouting.resolve = { host -> table[host]?.toTypedArray() ?: throw java.net.UnknownHostException(host) }
        try {
            block()
        } finally {
            TorRouting.resolve = real
        }
    }

    private fun withProxyAndNames(
        vararg names: Pair<String, String>,
        hopProxyPort: Int? = null,
        block: () -> Unit,
    ) {
        val previous = ProxySelector.getDefault()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(server.hostName, server.port))
        val hopProxy = hopProxyPort?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress(server.hostName, it)) }
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> =
                if (hopProxy != null && uri?.host == "other.test") listOf(hopProxy) else listOf(proxy)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        try {
            withNames(*names, block = block)
        } finally {
            ProxySelector.setDefault(previous)
        }
    }
}
