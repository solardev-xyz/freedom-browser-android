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
        assertEquals(40123, (TorRouting.desiredEndpoint(true, true, listening, null, false)?.port ?: 0))
        // Bootstrapping already listens (a connect waits for the bootstrap).
        assertEquals(40123, TorRouting.desiredEndpoint(true, true, listening.copy(status = TorStatus.Starting), null, false)?.port ?: 0)
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
                            (TorRouting.desiredEndpoint(supported, enabled, info, null, false)?.port ?: 0),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the proxy applies only to onion hosts, over socks5`() {
        val config = TorRouting.proxyConfigFor(SocksEndpoint("127.0.0.1", 40123))
        assertTrue(config.isReverseBypassEnabled)
        assertEquals(listOf("*.onion", "*.onion."), config.bypassRules)
        assertEquals(listOf("socks5://127.0.0.1:40123"), config.proxyRules.map { it.url })
        assertEquals(
            listOf("socks5://127.0.0.1:${TorRouting.REFUSE_PORT}"),
            TorRouting.proxyConfigFor(TorRouting.REFUSE).proxyRules.map { it.url },
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
        assertEquals(0, (TorRouting.routedEndpoint?.port ?: 0))
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
            assertEquals(0, (TorRouting.routedEndpoint?.port ?: 0))
            assertEquals(emptyList<String>(), applied)
            // The same state again (a later poll) tries again, and routes.
            TorRouting.onState(context, running)
            assertEquals(listOf("socks5://127.0.0.1:40123"), applied)
            assertEquals(40123, (TorRouting.routedEndpoint?.port ?: 0))
            // Nothing changed: not re-applied.
            TorRouting.onState(context, running)
            assertEquals(1, applied.size)
        } finally {
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `letting Tor go waits for the WebView to refuse onion`() {
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        val pending = mutableListOf<Pair<String, Runnable>>()
        TorRouting.setOverride = { config, _, done -> pending += config.proxyRules.single().url to done }
        fun confirm() = pending.removeAt(0).second.run()
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            confirm() // refusing, from init
            val running = TorInfo(status = TorStatus.Running, socksPort = 40123)
            TorRouting.onState(context, running)
            confirm()
            assertEquals(40123, (TorRouting.routedEndpoint?.port ?: 0))

            // Tor switched off: routing stops at once, but the unbind
            // waits until the WebView confirms the override moved.
            TorRouting.onState(context, TorInfo())
            assertEquals(0, (TorRouting.routedEndpoint?.port ?: 0))
            var released = 0
            TorRouting.afterRefusing { released++ }
            assertEquals(0, released)
            assertEquals("socks5://127.0.0.1:${TorRouting.REFUSE_PORT}", pending.single().first)
            confirm()
            assertEquals(1, released)
            // Already refusing: runs at once.
            TorRouting.afterRefusing { released++ }
            assertEquals(2, released)

            // Back on before the refusal confirms: the stale confirmation
            // doesn't release anything.
            TorRouting.onState(context, running)
            confirm()
            TorRouting.onState(context, TorInfo())
            TorRouting.afterRefusing { released++ }
            TorRouting.onState(context, running)
            confirm() // the superseded refusal
            assertEquals(2, released)
            confirm() // back on the Tor port
            assertEquals(40123, (TorRouting.routedEndpoint?.port ?: 0))
            assertEquals(2, released)
        } finally {
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `an external proxy is routed only once confirmed, and never falls back to the embedded port`() {
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        val embedded = TorInfo(status = TorStatus.Running, socksPort = 40123)
        assertEquals(orbot, TorRouting.desiredEndpoint(true, true, embedded, orbot, true))
        assertEquals(orbot, TorRouting.desiredEndpoint(true, true, TorInfo(), orbot, true))
        // Not (or no longer) confirmed: refused, even with Arti listening.
        assertEquals(null, TorRouting.desiredEndpoint(true, true, embedded, orbot, false))
        // Off, or no reverse-bypass support: refused.
        assertEquals(null, TorRouting.desiredEndpoint(true, false, embedded, orbot, true))
        assertEquals(null, TorRouting.desiredEndpoint(false, true, embedded, orbot, true))
        // IPv6 loopback, bracketed in the proxy rule.
        val v6 = SocksEndpoint("::1", 9150)
        assertEquals(
            listOf("socks5://[::1]:9150"),
            TorRouting.proxyConfigFor(v6).proxyRules.map { it.url },
        )
        assertEquals(TorRouting.CODE_PROXY_DOWN, TorRouting.refusalCode(true, true, external = true))
        assertEquals(TorRouting.CODE_OFF, TorRouting.refusalCode(true, false, external = true))
        val down = TorRouting.refusalHtml(onion, TorRouting.CODE_PROXY_DOWN, TorInfo(), orbot)
        assertTrue(down.contains("<h1>Tor proxy isn't reachable</h1>"))
        assertTrue(down.contains("127.0.0.1:9050"))
        assertTrue(down.contains("Orbot"))
    }

    @Test
    fun `switching to an external proxy follows its probe and lets the embedded port go`() {
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        val pending = mutableListOf<Pair<String, Runnable>>()
        TorRouting.setOverride = { config, _, done -> pending += config.proxyRules.single().url to done }
        fun confirm() = pending.removeAt(0).second.run()
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            confirm()
            TorRouting.onState(context, TorInfo(status = TorStatus.Running, socksPort = 40123))
            confirm()
            assertEquals(40123, TorRouting.routedEndpoint?.port)

            // Settings → Tor → External: nothing routed until the probe
            // confirms; the embedded port is let go once refused.
            TorRouting.setExternal(context, orbot, confirmed = false)
            assertFalse(TorRouting.isRouted)
            var released = 0
            TorRouting.afterRefusing { released++ }
            assertEquals("socks5://127.0.0.1:${TorRouting.REFUSE_PORT}", pending.single().first)
            confirm()
            assertEquals(1, released)
            // Arti's late state changes nothing now.
            TorRouting.onState(context, TorInfo(status = TorStatus.Running, socksPort = 40123))
            assertTrue(pending.isEmpty())

            TorRouting.setExternal(context, orbot, confirmed = true)
            assertFalse(TorRouting.isRouted) // not before the WebView confirms
            assertEquals("socks5://127.0.0.1:9050", pending.single().first)
            confirm()
            assertEquals(orbot, TorRouting.routedEndpoint)
            TorRouting.afterRefusing { released++ }
            assertEquals(2, released) // not on the embedded port

            // Orbot went away: refused at once, before the WebView moves.
            TorRouting.setExternal(context, orbot, confirmed = false)
            assertFalse(TorRouting.isRouted)
            try {
                TorRouting.openConnection(URL("http://$onion/"))
                fail("opened an onion URL with the proxy down")
            } catch (e: TorRouting.RefusedException) {
                // expected
            }
            confirm()
            assertFalse(TorRouting.isRouted)
        } finally {
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `a failed onion load re-checks the external proxy only while routed to it`() {
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        TorRouting.setOverride = { _, _, done -> done.run() }
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        var checks = 0
        val listener: () -> Unit = { checks++ }
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setOnExternalFailure(listener)
            TorRouting.setEnabled(context, true)
            // Embedded Arti routed: not the external proxy's business.
            TorRouting.onState(context, TorInfo(status = TorStatus.Running, socksPort = 40123))
            assertTrue(TorRouting.isRouted)
            TorRouting.externalFailed()
            assertEquals(0, checks)
            // External, not yet confirmed (already refused): nothing to check.
            TorRouting.setExternal(context, orbot, confirmed = false)
            TorRouting.externalFailed()
            assertEquals(0, checks)
            TorRouting.setExternal(context, orbot, confirmed = true)
            assertTrue(TorRouting.isRoutedExternal)
            TorRouting.externalFailed()
            assertEquals(1, checks)
            // An older Activity's clear doesn't drop a newer one's listener.
            TorRouting.clearOnExternalFailure {}
            TorRouting.externalFailed()
            assertEquals(2, checks)
            TorRouting.clearOnExternalFailure(listener)
            TorRouting.externalFailed()
            assertEquals(2, checks)
        } finally {
            TorRouting.clearOnExternalFailure(listener)
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `the Tor proxy refusal page re-checks the proxy, and says when Tor answers but can't get through`() {
        // R3-F1: unrouted, nothing else would check before the next
        // scheduled check; the page itself asks for one.
        var checks = 0
        val listener: () -> Unit = { checks++ }
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        try {
            TorRouting.setOnExternalFailure(listener)
            TorRouting.refusedDocument(TorRouting.CODE_OFF, mainFrame = true)
            TorRouting.refusedDocument(TorRouting.CODE_NOT_RUNNING, mainFrame = true)
            assertEquals(0, checks)
            // Not for an iframe, which any page can add in a loop (R4-M2).
            TorRouting.refusedDocument(TorRouting.CODE_PROXY_DOWN, mainFrame = false)
            assertEquals(0, checks)
            TorRouting.refusedDocument(TorRouting.CODE_PROXY_DOWN, mainFrame = true)
            assertEquals(1, checks)

            val gone = TorRouting.refusalHtml(onion, TorRouting.CODE_PROXY_DOWN, TorInfo(), orbot, unreached = false)
            assertTrue(gone.contains("no Tor client"))
            val slow = TorRouting.refusalHtml(onion, TorRouting.CODE_PROXY_DOWN, TorInfo(), orbot, unreached = true)
            assertTrue(slow.contains("<h1>Tor can't reach onion sites</h1>"))
            assertTrue(slow.contains("127.0.0.1:9050"))
            assertFalse(slow.contains("no Tor client"))
        } finally {
            TorRouting.clearOnExternalFailure(listener)
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `nothing to wait for without an override`() {
        TorRouting.resetForTest(supported = false)
        var released = false
        TorRouting.afterRefusing { released = true }
        assertTrue(released)
        TorRouting.resetForTest(supported = null)
    }

    @Test
    fun `a POST result is sent to the refusal page as a GET, not a reload`() {
        assertTrue(onionRefusalByReload("GET"))
        assertTrue(onionRefusalByReload("head"))
        assertTrue(onionRefusalByReload(null))
        assertFalse(onionRefusalByReload("POST"))
        assertFalse(onionRefusalByReload("post"))
        assertFalse(onionRefusalByReload("PUT"))
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
