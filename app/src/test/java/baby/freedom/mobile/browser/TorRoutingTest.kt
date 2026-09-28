package baby.freedom.mobile.browser

import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URL

class TorRoutingTest {
    private val onion = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"

    @Test
    fun `onion hosts are recognised the way the Tor side recognises them`() {
        assertTrue(isOnionHost(onion))
        assertTrue(isOnionHost("www.$onion"))
        assertTrue(isOnionHost("EXAMPLE.ONION"))
        assertTrue(isOnionHost("example.onion."))
        assertFalse(isOnionHost("onion"))
        assertFalse(isOnionHost(".onion"))
        assertFalse(isOnionHost("a..onion"))
        assertFalse(isOnionHost("example.onion.com"))
        assertFalse(isOnionHost("notonion"))
        assertFalse(isOnionHost("example.com"))
        assertFalse(isOnionHost("127.0.0.1"))
        assertFalse(isOnionHost(null))
    }

    @Test
    fun `onion is routed only with support, the setting on, and a listening client`() {
        val listening = TorInfo(status = TorStatus.Running, socksPort = 40123)
        assertEquals(40123, TorRouting.desiredPort(true, true, listening))
        // Bootstrapping already listens (a connect waits for the bootstrap).
        assertEquals(40123, TorRouting.desiredPort(true, true, listening.copy(status = TorStatus.Starting)))
        // Every other combination refuses.
        for (supported in listOf(true, false)) {
            for (enabled in listOf(true, false)) {
                for (status in TorStatus.entries) {
                    for (port in listOf(0, 40123, 70000)) {
                        val info = TorInfo(status = status, socksPort = port)
                        val routed = supported && enabled && port == 40123 &&
                            (status == TorStatus.Running || status == TorStatus.Starting)
                        assertEquals(
                            "supported=$supported enabled=$enabled $status port=$port",
                            if (routed) 40123 else 0,
                            TorRouting.desiredPort(supported, enabled, info),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the proxy applies only to onion hosts, over socks5`() {
        val config = TorRouting.proxyConfigFor(40123)
        assertTrue(config.isReverseBypassEnabled)
        assertEquals(listOf("*.onion", "*.onion."), config.bypassRules)
        assertEquals(listOf("socks5://127.0.0.1:40123"), config.proxyRules.map { it.url })
        assertEquals(
            listOf("socks5://127.0.0.1:${TorRouting.REFUSE_PORT}"),
            TorRouting.proxyConfigFor(TorRouting.REFUSE_PORT).proxyRules.map { it.url },
        )
    }

    @Test
    fun `the refusal says why`() {
        assertEquals(TorRouting.CODE_UNSUPPORTED, TorRouting.refusalCode(false, true))
        assertEquals(TorRouting.CODE_OFF, TorRouting.refusalCode(true, false))
        assertEquals(TorRouting.CODE_OFF, TorRouting.refusalCode(null, false))
        assertEquals(TorRouting.CODE_NOT_RUNNING, TorRouting.refusalCode(true, true))

        val off = TorRouting.refusalHtml(onion, TorRouting.CODE_OFF, TorInfo())
        assertTrue(off.contains("<h1>Tor is off</h1>"))
        assertTrue(off.contains(onion))
        val stopped = TorRouting.refusalHtml(onion, TorRouting.CODE_NOT_RUNNING, TorInfo())
        assertTrue(stopped.contains("<h1>Tor isn't running</h1>"))
        assertTrue(stopped.contains("Nodes page"))
        // A start failure is shown, escaped.
        val failed = TorRouting.refusalHtml(
            onion, TorRouting.CODE_NOT_RUNNING,
            TorInfo(status = TorStatus.Error, errorMessage = "bind <denied>"),
        )
        assertTrue(failed.contains("couldn't start"))
        assertTrue(failed.contains("bind &lt;denied&gt;"))
        assertTrue(TorRouting.refusalHtml(onion, TorRouting.CODE_UNSUPPORTED, TorInfo()).contains("newer WebView"))
        // Nothing to run or fetch.
        assertTrue(off.contains("default-src 'none'"))
        assertFalse(off.contains("<script"))
    }

    @Test
    fun `a native onion fetch without Tor is refused before anything resolves`() {
        // No Tor port is routed in a unit test.
        assertEquals(0, TorRouting.port)
        for (url in listOf("http://$onion/", "https://www.$onion/a.png", "http://example.onion.:8080/")) {
            try {
                TorRouting.openConnection(URL(url))
                fail("opened $url")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("Tor"))
            }
        }
        // Anything else is opened as before (openConnection doesn't connect).
        TorRouting.openConnection(URL("https://example.com/")).let {
            assertEquals("example.com", it.url.host)
        }
    }

    @Test
    fun `an onion host is recognised however a URL spells it`() {
        val name = onion.removeSuffix(".onion")
        for (host in listOf(
            onion, "$onion.", "$onion..", "${name}%2eonion", "${name}%2Eonion", "${name.uppercase()}%2EONION",
            "${name}%2eonion%2e", "%77ww.${name}.onion", "${name}\u3002onion", "${name}\uff0eonion",
            "${name}\uff61onion", "${name}.on\u00adion", "${name}.o\u200dnion", "${name}.\uff4f\uff4e\uff49\uff4f\uff4e",
            "${name}%e3%80%82onion",
        )) {
            assertTrue(host, hostMayBeOnion(host))
        }
        for (host in listOf(
            "example.com", "onion.example.com", "example.onion.com", "b\u00fccher.de", "xn--bcher-kva.de",
            "b%c3%bccher.de", "127.0.0.1", "[::1]", "[fe80::1%25en0]", "", null,
        )) {
            assertFalse(host.toString(), hostMayBeOnion(host))
        }
    }

    @Test
    fun `the host is also read the way the HTTP stack parses the authority`() {
        assertEquals("example.com", okHttpHost("https://example.com/a"))
        assertEquals("example.com", okHttpHost("https://user:pw@example.com:8443/a"))
        assertEquals("[::1]", okHttpHost("http://[::1]:80/"))
        // java.net.URL reads evil.com here; OkHttp ends the authority at the backslash.
        assertEquals("$onion", okHttpHost("http://$onion\\@evil.example/"))
        assertTrue(fetchMayReachOnion(URL("http://$onion\\@evil.example/")))
        assertFalse(fetchMayReachOnion(URL("https://example.com/a.onion")))
        assertFalse(fetchMayReachOnion(URL("https://example.com/?u=http://$onion/")))
    }

    @Test
    fun `a redirect to an onion spelled any way is refused without Tor`() {
        val name = onion.removeSuffix(".onion")
        val page = URL("https://example.com/download")
        for (location in listOf(
            "http://${name}%2eonion/f.bin", "http://${name}\u3002onion/f.bin", "//$onion/f.bin",
            "http://${name}.on%C2%ADion/f.bin",
        )) {
            try {
                TorRouting.openConnection(URL(page, location))
                fail("opened $location")
            } catch (_: TorRouting.RefusedException) {
            }
        }
    }

    @Test
    fun `redirects are followed the way HttpURLConnection follows them`() {
        val from = URL("https://example.com/a/b")
        TorRouting.redirectFor(from, 302, "GET", "/c")!!.let {
            assertEquals("https://example.com/c", it.url.toString())
            assertFalse(it.toGet)
        }
        assertEquals("https://other.example/x", TorRouting.redirectFor(from, 301, "HEAD", "https://other.example/x")!!.url.toString())
        // A POST is re-sent as a GET after 300-303, not followed after 307/308.
        assertTrue(TorRouting.redirectFor(from, 303, "POST", "/c")!!.toGet)
        assertEquals(null, TorRouting.redirectFor(from, 307, "POST", "/c"))
        assertEquals(null, TorRouting.redirectFor(from, 308, "POST", "/c"))
        assertFalse(TorRouting.redirectFor(from, 307, "GET", "/c")!!.toGet)
        // Not a redirect, no Location, or another scheme: handed back as is.
        assertEquals(null, TorRouting.redirectFor(from, 304, "GET", "/c"))
        assertEquals(null, TorRouting.redirectFor(from, 302, "GET", null))
        assertEquals(null, TorRouting.redirectFor(from, 302, "GET", "http://example.com/c"))
        assertEquals(null, TorRouting.redirectFor(from, 302, "GET", "ftp://example.com/c"))
    }

    @Test
    fun `a followed redirect chain goes through the Tor check hop by hop`() {
        val name = onion.removeSuffix(".onion")
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += "${ex.requestMethod} ${ex.requestURI} ${ex.requestHeaders.getFirst("X-Hop")} $body".trim()
            when (ex.requestURI.path) {
                "/onion" -> ex.responseHeaders.add("Location", "http://${name}%2eonion/f.bin")
                "/hop" -> ex.responseHeaders.add("Location", "/done")
                "/post" -> ex.responseHeaders.add("Location", "/done")
            }
            val code = if (ex.requestURI.path == "/done") 200 else 302
            val out = "ok".toByteArray()
            ex.sendResponseHeaders(code, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            try {
                TorRouting.openFollowingRedirects(URL("$base/onion")) { setRequestProperty("X-Hop", it.path) }
                fail("followed a redirect to an onion without Tor")
            } catch (_: TorRouting.RefusedException) {
            }
            assertEquals(listOf("GET /onion /onion"), requests.toList())

            requests.clear()
            val conn = TorRouting.openFollowingRedirects(URL("$base/hop")) { setRequestProperty("X-Hop", it.path) }
            assertEquals(200, conn.responseCode)
            assertEquals("$base/done", conn.url.toString())
            conn.disconnect()
            // configure ran on each hop with that hop's URL.
            assertEquals(listOf("GET /hop /hop", "GET /done /done"), requests.toList())

            requests.clear()
            val post = TorRouting.openFollowingRedirects(URL("$base/post"), "{}".toByteArray()) {
                requestMethod = "POST"
                setRequestProperty("X-Hop", it.path)
            }
            assertEquals(200, post.responseCode)
            post.disconnect()
            assertEquals(listOf("POST /post /post {}", "GET /done /done"), requests.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an override that fails is retried on the next state update`() {
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        var fail = false
        val applied = mutableListOf<String>()
        TorRouting.setOverride = { config, _, done ->
            if (fail) {
                fail = false
                throw IllegalStateException("WebView busy")
            }
            applied += config.proxyRules.single().url
            done.run()
        }
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            assertEquals(listOf("socks5://127.0.0.1:${TorRouting.REFUSE_PORT}"), applied)
            applied.clear()
            fail = true
            val running = TorInfo(status = TorStatus.Running, socksPort = 40123)
            // The move to the Tor port throws: still refused.
            TorRouting.onState(context, running)
            assertEquals(0, TorRouting.port)
            assertEquals(emptyList<String>(), applied)
            // The same state again (a later poll) tries again, and routes.
            TorRouting.onState(context, running)
            assertEquals(listOf("socks5://127.0.0.1:40123"), applied)
            assertEquals(40123, TorRouting.port)
            // Nothing changed: not re-applied.
            TorRouting.onState(context, running)
            assertEquals(1, applied.size)
        } finally {
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `a typed onion address opens over http`() {
        val ddg = SearchEngines.DEFAULT.template
        assertEquals("http://$onion", UrlParser.toUrl(onion, ddg))
        assertEquals("http://$onion/path?q=1", UrlParser.toUrl("$onion/path?q=1", ddg))
        assertEquals("http://$onion:8080/x", UrlParser.toUrl("$onion:8080/x", ddg))
        assertEquals("https://$onion/", UrlParser.toUrl("https://$onion/", ddg))
        assertEquals("https://example.com", UrlParser.toUrl("example.com", ddg))
        assertEquals("https://onion.example.com", UrlParser.toUrl("onion.example.com", ddg))
    }
}
