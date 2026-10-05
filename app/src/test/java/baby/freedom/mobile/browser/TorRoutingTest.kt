package baby.freedom.mobile.browser

import baby.freedom.swarm.HeldText
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
            TorInfo(status = TorStatus.Error, error = HeldText.raw("bind <denied>")),
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
    fun `a redirect onto this device is refused, whatever spells the host`() = withResolver(
        "gateway.example" to "203.0.113.5", "other.example" to "198.51.100.7", "127.tracker.example" to "198.51.100.8",
    ) {
        val gateway = URL("http://gateway.example/ipfs/bafyroot/")
        fun refused(location: String, method: String = "GET") =
            TorRouting.hopRefused(gateway, URL(gateway, location), method)
        // The node's own API, on any host that may be the device.
        for (location in listOf(
            "http://127.0.0.1:1633/addresses", "http://127.0.0.1:1633/wallet", "http://localhost:1633/stamps",
            "http://127.1:1633/addresses", "http://2130706433:1633/addresses", "http://0x7f000001:1633/peers",
            "http://127.0.0.%31:1633/addresses", "http://[::1]:1633/addresses", "http://[::ffff:127.0.0.1]:1633/balances",
            "http://0.0.0.0:1633/addresses", "http://127.0.0.1.nip.io:1633/addresses", "http://localhost.:1633/wallet",
        )) {
            assertTrue(location, refused(location))
        }
        // Any other loopback service on another origin, even the content surface.
        for (location in listOf(
            "http://127.0.0.1:1633/bzz/abc/", "http://127.0.0.1:45123/ipfs/bafyother/", "http://localhost/",
            "http://app.localhost:8080/", "http://[::1]:9050/", "http://0177.0.0.1:631/", "http://0:8080/",
        )) {
            assertTrue(location, refused(location))
        }
        // A public name that resolves to the device, on any port (R1-F1):
        // the HTTP stack would dial loopback just the same.
        for (location in listOf(
            "http://localtest.me:8080/secret", "http://127.0.0.1.nip.io:45123/", "https://loop6.example/",
            "http://mapped.example:631/", "http://mixed.example/",
        )) {
            assertTrue(location, refused(location))
        }
        // One that doesn't resolve at all isn't followed either — it could
        // resolve to loopback next — but as a failed lookup, worth a retry,
        // not as a redirect onto this device (R6-F1).
        try {
            refused("http://unresolvable.example:9000/")
            fail("judged a hop whose name didn't resolve")
        } catch (e: TorRouting.RedirectUnresolvedException) {
            assertTrue(e is java.net.UnknownHostException)
            assertFalse((e as java.io.IOException) is java.net.ConnectException)
        }
        // Elsewhere, or on the same origin: followed as before.
        for (location in listOf(
            "/ipfs/bafyroot/index.html", "http://gateway.example/ipfs/bafyother/",
            "http://other.example/ipfs/bafyroot/", "http://192.168.1.20:8080/ipfs/bafyroot/",
            "http://127.tracker.example/x",
        )) {
            assertFalse(location, refused(location))
        }
        // A loopback gateway may redirect within itself, but not onto the node's API.
        val local = URL("http://127.0.0.1:45123/ipfs/bafyroot")
        assertFalse(TorRouting.hopRefused(local, URL(local, "/ipfs/bafyroot/"), "GET"))
        assertTrue(TorRouting.hopRefused(local, URL("http://127.0.0.1:1633/addresses"), "GET"))
        val node = URL("http://127.0.0.1:1633/bzz/abc")
        assertFalse(TorRouting.hopRefused(node, URL(node, "/bzz/abc/"), "GET"))
        assertTrue(TorRouting.hopRefused(node, URL(node, "/addresses"), "GET"))
    }

    @Test
    fun `a local Kubo's subdomain redirect stays with the gateway and is followed`() = withResolver {
        val looked = mutableListOf<String>()
        val real = TorRouting.resolve
        TorRouting.resolve = { looked += it; real(it) }
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        val kubo = URL("http://localhost:8080/ipfs/$cid/")
        // Kubo's default for localhost: 301 to the CID's subdomain, same port (#359 R1-F1).
        for (location in listOf(
            "http://$cid.ipfs.localhost:8080/", "http://$cid.IPFS.LOCALHOST:8080/index.html",
            "http://k51qzi5uqu5dlvj2baxnqndepeb86cbk3ng7n3i46uzyxzyqj2xjonzllnv0v8.ipns.localhost:8080/",
        )) {
            assertFalse(location, TorRouting.hopRefused(kubo, URL(kubo, location), "GET"))
            assertTrue(location, TorRouting.sameLoopbackServer(kubo, URL(kubo, location)))
        }
        // And back from a subdomain to the bare name.
        val sub = URL("http://$cid.ipfs.localhost:8080/")
        assertFalse(TorRouting.hopRefused(sub, URL("http://localhost:8080/ipfs/$cid/"), "GET"))
        // Judged as written: nothing was looked up.
        assertEquals(emptyList<String>(), looked)
        // Still refused: the node's API on any localhost name, another port, another
        // scheme, a literal (a different listener may hold it), or from a literal start.
        for ((start, location) in listOf(
            kubo to "http://$cid.ipfs.localhost:1633/addresses",
            kubo to "http://$cid.ipfs.localhost:9000/",
            kubo to "https://$cid.ipfs.localhost:8080/",
            kubo to "http://127.0.0.1:8080/",
            kubo to "http://[::1]:8080/",
            kubo to "http://localtest.me:8080/",
            URL("http://127.0.0.1:8080/ipfs/$cid/") to "http://$cid.ipfs.localhost:8080/",
            URL("http://gateway.example:8080/ipfs/$cid/") to "http://$cid.ipfs.localhost:8080/",
        )) {
            assertTrue("$start -> $location", TorRouting.hopRefused(start, URL(start, location), "GET"))
        }
        val node = URL("http://localhost:1633/bzz/abc/")
        assertTrue(TorRouting.hopRefused(node, URL("http://x.localhost:1633/addresses"), "GET"))
    }

    @Test
    fun `a local Kubo's subdomain hop is dialed on this device, never by a network lookup of its name`() {
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/") { ex ->
            paths += ex.requestURI.path
            val code = if (ex.requestURI.path.startsWith("/ipfs/")) 301 else 200
            if (code == 301) ex.responseHeaders.add("Location", "http://$cid.ipfs.localhost:$port/")
            val out = "ok".toByteArray()
            ex.sendResponseHeaders(code, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        // Android sends `<x>.localhost` to the network's DNS, which may answer
        // anything (#359 R2-F1): here a public address. Only `localhost`
        // itself (the hosts file) may decide where the hop goes.
        val looked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val real = TorRouting.resolve
        TorRouting.resolve = { host ->
            looked += host
            when (host) {
                "localhost" -> arrayOf(java.net.InetAddress.getByName("127.0.0.1"))
                else -> arrayOf(java.net.InetAddress.getByName("198.51.100.7"))
            }
        }
        try {
            val conn = TorRouting.openFollowingRedirects(URL("http://localhost:$port/ipfs/$cid/")) {}
            assertEquals(200, conn.responseCode)
            conn.disconnect()
            // Both hops reached the gateway on 127.0.0.1 (the JVM's
            // HttpURLConnection drops a set `Host`; Android's sends it, so
            // there the gateway sees the subdomain: [Pin.hostHeader] below).
            assertEquals(listOf("/ipfs/$cid/", "/"), paths.toList())
            assertEquals(listOf("localhost"), looked.toList())

            val pin = TorRouting.pinLocalhost(URL("http://$cid.ipfs.localhost:$port/"))!!
            assertEquals(listOf("http://127.0.0.1:$port/"), pin.urls.map { it.toString() })
            assertEquals("$cid.ipfs.localhost:$port", pin.hostHeader)
            // https keeps its name (the certificate needs it) and has its peer checked instead.
            assertEquals(null, TorRouting.pinLocalhost(URL("https://$cid.ipfs.localhost:$port/")))

            // A `localhost` that isn't this device isn't dialed.
            TorRouting.resolve = { arrayOf(java.net.InetAddress.getByName("198.51.100.7")) }
            try {
                TorRouting.pinLocalhost(URL("http://$cid.ipfs.localhost:$port/"))
                fail("pinned a localhost hop off this device")
            } catch (_: TorRouting.RedirectRefusedException) {
            }
        } finally {
            TorRouting.resolve = real
            server.stop(0)
        }
    }

    @Test
    fun `a hop within a redirected-to origin is resolved again, so a rebinding name is refused`() {
        val gateway = URL("http://gateway.example/ipfs/bafyroot/")
        val first = URL("http://rb.evil.example:8711/a")
        val second = URL(first, "/b")
        // First hop: the name answers publicly, so it's followed.
        withResolver("gateway.example" to "203.0.113.5", "rb.evil.example" to "198.51.100.7") {
            assertFalse(TorRouting.hopRefused(gateway, first, "GET"))
        }
        // The server waits out the resolver cache and rebinds before its
        // same-origin `302 /b`: that hop is looked up again and refused.
        withResolver("gateway.example" to "203.0.113.5", "rb.evil.example" to "127.0.0.1") {
            assertTrue(TorRouting.hopRefused(gateway, second, "GET"))
            assertTrue(TorRouting.hopRefused(gateway, second, "HEAD"))
        }
        // A hop back onto the origin the caller asked for stays its own business.
        withResolver("gateway.example" to "127.0.0.1") {
            assertFalse(TorRouting.hopRefused(gateway, URL(gateway, "/ipfs/bafyroot/index.html"), "GET"))
        }
    }

    @Test
    fun `an http hop off the caller's origin is dialed by the address it was judged on`() = withResolver(
        "rb.evil.example" to "198.51.100.7", "loop.example" to "127.0.0.1", "v6.example" to "2001:db8::7",
    ) {
        val pin = TorRouting.pin(URL("http://rb.evil.example:8711/b?x=1"))!!
        assertEquals(listOf("http://198.51.100.7:8711/b?x=1"), pin.urls.map { it.toString() })
        assertEquals("rb.evil.example:8711", pin.hostHeader)
        assertEquals("rb.evil.example", TorRouting.pin(URL("http://RB.evil.example/"))!!.hostHeader)
        assertEquals(listOf("http://[2001:db8:0:0:0:0:0:7]/"), TorRouting.pin(URL("http://v6.example/"))!!.urls.map { it.toString() })
        // Resolving to the device: refused at the dial too.
        for (hop in listOf("http://loop.example:8711/b", "http://localtest.me/")) {
            try {
                TorRouting.pin(URL(hop))
                fail("pinned $hop")
            } catch (_: TorRouting.RedirectRefusedException) {
            }
        }
        // Not resolving at all: not dialed, as a failed lookup (R6-F1).
        try {
            TorRouting.pin(URL("http://unresolvable.example/"))
            fail("pinned a name that didn't resolve")
        } catch (_: TorRouting.RedirectUnresolvedException) {
        }
        // Nothing to pin: https (its connected peer is checked), onion, literals.
        assertEquals(null, TorRouting.pin(URL("https://rb.evil.example/")))
        assertEquals(null, TorRouting.pin(URL("http://${"a".repeat(56)}.onion/")))
        assertEquals(null, TorRouting.pin(URL("http://192.168.1.20:8080/")))
        assertEquals(null, TorRouting.pin(URL("http://[2001:db8::1]/")))
    }

    @Test
    fun `a pinned hop keeps every address its name resolved to, in order, for fallback`() = withResolver {
        val real = TorRouting.resolve
        TorRouting.resolve = { host ->
            if (host == "multi.example") {
                arrayOf("2001:db8::7", "198.51.100.7", "198.51.100.8", "198.51.100.7").map { java.net.InetAddress.getByName(it) }.toTypedArray()
            } else {
                real(host)
            }
        }
        val pin = TorRouting.pin(URL("http://multi.example:8080/p"))!!
        assertEquals(
            listOf("http://[2001:db8:0:0:0:0:0:7]:8080/p", "http://198.51.100.7:8080/p", "http://198.51.100.8:8080/p"),
            pin.urls.map { it.toString() },
        )
        assertEquals("multi.example:8080", pin.hostHeader)
        // Any one of them on this device refuses the whole hop.
        try {
            TorRouting.pin(URL("http://mixed.example/"))
            fail("pinned a name with a loopback address")
        } catch (_: TorRouting.RedirectRefusedException) {
        }
    }

    @Test
    fun `a hop is pinned or peer-checked only when the app dials it itself`() {
        val real = TorRouting.proxiesFor
        val http = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("127.0.0.1", 8080))
        val socks = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved("127.0.0.1", 1080))
        try {
            TorRouting.proxiesFor = { listOf(java.net.Proxy.NO_PROXY) }
            assertEquals(java.net.Proxy.NO_PROXY, TorRouting.hopRoute(URL("https://gw.example/ipfs/x")))
            assertEquals(java.net.Proxy.NO_PROXY, TorRouting.hopRoute(URL("http://gw.example/ipfs/x")))
            // Onion goes through Tor's SOCKS proxy: its socket's peer is unknown (R4-F1).
            assertEquals(null, TorRouting.hopRoute(URL("https://${"a".repeat(56)}.onion/x")))
            assertEquals(null, TorRouting.hopRoute(URL("http://${"a".repeat(56)}%2eonion/x")))
            // A system proxy (a Wi-Fi proxy on 127.0.0.1, or SOCKS) owns the socket and the
            // lookup; it's the only route, with no direct fallback past the checks.
            TorRouting.proxiesFor = { listOf(http, java.net.Proxy.NO_PROXY) }
            assertEquals(http, TorRouting.hopRoute(URL("https://gw.example/ipfs/x")))
            assertEquals(http, TorRouting.hopRoute(URL("http://gw.example/ipfs/x")))
            TorRouting.proxiesFor = { listOf(java.net.Proxy.NO_PROXY, socks) }
            assertEquals(socks, TorRouting.hopRoute(URL("https://gw.example/ipfs/x")))
            // A Location java.net.URI rejects as written still goes through the proxy (R5-F1).
            val asked = mutableListOf<java.net.URI>()
            TorRouting.proxiesFor = { asked += it; listOf(http) }
            for (odd in listOf("http://other.test/ipfs/y|z", "http://other.test/a b?q={x}^\"", "https://other.test:8443/p#a|b")) {
                assertEquals(odd, http, TorRouting.hopRoute(URL(odd)))
            }
            assertEquals(listOf("other.test"), asked.map { it.host }.distinct())
            assertEquals(listOf(-1, -1, 8443), asked.map { it.port })
            // A selector that fails is refused, never dialed directly past a proxy it may name.
            TorRouting.proxiesFor = { throw IllegalArgumentException("bad uri") }
            try {
                TorRouting.hopRoute(URL("https://gw.example/ipfs/x"))
                fail("dialed a hop the proxy selector failed on")
            } catch (e: TorRouting.RedirectRouteException) {
                // Not reported as a redirect onto this device, nor as an unreachable server (#359 R1-F4).
                assertFalse((e as java.io.IOException) is java.net.ConnectException)
                assertFalse((e as java.io.IOException) is java.net.UnknownHostException)
            }
            // One that answers nothing is read as direct, so the checks still run.
            TorRouting.proxiesFor = { emptyList() }
            assertEquals(java.net.Proxy.NO_PROXY, TorRouting.hopRoute(URL("https://gw.example/ipfs/x")))
        } finally {
            TorRouting.proxiesFor = real
        }
    }

    @Test
    fun `an onion or IP-literal hop is never looked up`() = withResolver {
        val looked = mutableListOf<String>()
        val real = TorRouting.resolve
        TorRouting.resolve = { looked += it; real(it) }
        val gateway = URL("http://gateway.example/ipfs/bafyroot/")
        assertFalse(TorRouting.resolvesToLoopback(URL(gateway, "http://${"a".repeat(56)}.onion/")))
        assertFalse(TorRouting.resolvesToLoopback(URL(gateway, "http://192.168.1.20:8080/")))
        assertFalse(TorRouting.resolvesToLoopback(URL(gateway, "http://[2001:db8::1]/")))
        assertEquals(emptyList<String>(), looked)
    }

    @Test
    fun `a hop is judged on the host the connection dials, not only the WHATWG one`() = withResolver(
        // UTS-46 keeps and punycodes U+1806; IDNA2003 (what HttpURLConnection maps with) deletes it.
        "127.0.0.xn--1-f3j.8.8.8.8.nip.io" to "8.8.8.8",
        "127.0.0.1.8.8.8.8.nip.io" to "127.0.0.1",
        "strasse.example" to "127.0.0.1",
        "xn--strae-oqa.example" to "198.51.100.9",
        "public.example" to "198.51.100.9",
        "gross.example" to "198.51.100.10",
    ) {
        val was = WhatwgHost.uts46
        WhatwgHost.uts46 = Icu4jUts46
        try {
            dialedHostChecks()
        } finally {
            WhatwgHost.uts46 = was
        }
    }

    private fun dialedHostChecks() {
        val gateway = URL("http://gateway.example/ipfs/bafyroot/")
        val todo = URL("http://127.0.0.1%E1%A0%86.8.8.8.8.nip.io:8712/secret.html")
        assertTrue("127.0.0.1.8.8.8.8.nip.io" in TorRouting.dialedHosts(todo))
        assertTrue(TorRouting.resolvesToLoopback(todo))
        assertTrue(TorRouting.hopRefused(gateway, todo, "GET"))
        // ß: IDNA2003 maps it to ss, UTS-46 nontransitional keeps it.
        assertTrue(TorRouting.resolvesToLoopback(URL("http://stra%C3%9Fe.example/")))
        // A literal the connection would read out of a name is judged as one,
        // and refuses the hop although the WHATWG reading doesn't resolve.
        assertTrue(TorRouting.resolvesToLoopback(URL("http://127.0.0.1%E1%A0%86/")))
        assertFalse(TorRouting.resolvesToLoopback(URL("http://public.example/")))
        // Only the dialed reading has DNS (`gross.example`); the WHATWG one
        // (`xn--gro-7ka.example`), which nothing dials, doesn't resolve: the hop
        // is followed, not left unresolved (#359 R1-F2).
        assertFalse(TorRouting.resolvesToLoopback(URL("http://gro%C3%9F.example/")))
        assertFalse(TorRouting.hopRefused(gateway, URL("http://gro%C3%9F.example/"), "GET"))
        // A dialed reading that doesn't resolve still leaves the hop unresolved.
        try {
            TorRouting.resolvesToLoopback(URL("http://unresolvable.example/"))
            fail("judged a hop whose dialed name didn't resolve")
        } catch (_: TorRouting.RedirectUnresolvedException) {
        }
    }

    /**
     * PR #409 R4-M1: the name a connection would look up inside its own
     * `connect()` is looked up first, before the watcher's connect clock
     * starts — so a slow resolver isn't timed as a slow connect — and only
     * when the hop is dialed from here: through a proxy the name is the
     * proxy's, and an IP literal has nothing to look up.
     */
    @Test
    fun `a watched hop's name is looked up before its connect clock starts, only when dialed from here`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.sendResponseHeaders(200, 2)
            ex.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        val port = server.address.port
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watcher = object : TorRouting.HopWatcher {
            override fun connecting(conn: java.net.HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
                events += (if (routes == 1) "connecting" else "connecting $routes") + if (fallback) " fallback" else ""
            }
            override fun connected(conn: java.net.HttpURLConnection) { events += "connected" }
            override fun answered(conn: java.net.HttpURLConnection) { events += "answered" }
        }
        val realResolve = TorRouting.resolve
        val realProxies = TorRouting.proxiesFor
        // localhost pinned to 127.0.0.1: a dual-stack host (the CI runner)
        // also resolves it to ::1, which would make every route count 2.
        TorRouting.resolve = { host ->
            events += "lookup $host"
            if (host == "localhost") arrayOf(java.net.InetAddress.getByName("127.0.0.1")) else realResolve(host)
        }
        fun fetch(url: String) {
            events.clear()
            TorRouting.openFollowingRedirects(URL(url), hops = watcher) { connectTimeout = 2_000; readTimeout = 2_000 }
                .disconnect()
        }
        try {
            TorRouting.proxiesFor = { listOf(java.net.Proxy.NO_PROXY) }
            fetch("http://localhost:$port/a")
            assertEquals(listOf("lookup localhost", "connecting", "connected", "answered"), events.toList())
            // An IP literal: nothing to look up.
            fetch("http://127.0.0.1:$port/b")
            assertEquals(listOf("connecting", "connected", "answered"), events.toList())
            // A system proxy resolves the name itself; nothing is looked up
            // for its route. It and okhttp's direct fallback after it are
            // dialed as two connects (PR #409 R1-M1): the proxy (nothing
            // listening) fails, then the direct fallback — looked up now,
            // as it's dialed from here — answers.
            val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val http = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", closed))
            TorRouting.proxiesFor = { listOf(http) }
            fetch("http://localhost:$port/c")
            assertEquals(listOf("connecting fallback", "lookup localhost", "connecting", "connected", "answered"), events.toList())
            // PR #409 R2-F1: the failed proxy route is remembered, as
            // okhttp's RouteDatabase does — the next fetch dials the
            // direct route first and never the proxy.
            fetch("http://localhost:$port/c2")
            assertEquals("lookup localhost", events.first())
            assertTrue(events.toList().toString(), events[1].startsWith("connecting") && events[1].endsWith(" fallback"))
            assertEquals(listOf("connected", "answered"), events.drop(2))
            // PR #409 R3-F1: only for a while — once ROUTE_POSTPONE_MS has
            // passed the proxy is dialed first again, as main's fresh
            // RouteDatabase per connection did, so a proxy that recovered
            // gets its traffic back.
            val realClock = TorRouting.routeClock
            val later = realClock() + TorRouting.ROUTE_POSTPONE_MS
            TorRouting.routeClock = { later }
            try {
                fetch("http://localhost:$port/c2b")
                assertEquals(listOf("connecting fallback", "lookup localhost", "connecting", "connected", "answered"), events.toList())
            } finally {
                TorRouting.routeClock = realClock
            }
            // Another host's routes are its own: the proxy is dialed first there.
            fetch("http://127.0.0.1:$port/c3")
            assertEquals(listOf("connecting fallback", "connecting", "connected", "answered"), events.toList())
            // A selector that fails: not known to be direct, so not looked up either.
            TorRouting.proxiesFor = { throw IllegalArgumentException("bad uri") }
            fetch("http://localhost:$port/d")
            assertFalse(events.toList().toString(), events.any { it.startsWith("lookup") })
            // A failed lookup ahead is left to the connection to report, as it always did.
            TorRouting.proxiesFor = { listOf(java.net.Proxy.NO_PROXY) }
            TorRouting.resolve = { host -> events += "lookup $host"; throw java.net.UnknownHostException(host) }
            fetch("http://localhost:$port/e")
            assertEquals(listOf("lookup localhost", "connecting", "connected", "answered"), events.toList())
            // PR #409 R5-M1/M2: the name is looked up as the connection will
            // look it up — trailing dot kept, so it's the same cache entry —
            // and every address it resolved to gets its own connect timeout.
            TorRouting.resolve = { host ->
                events += "lookup $host"
                arrayOf("127.0.0.1", "127.0.0.2", "127.0.0.3").map { java.net.InetAddress.getByName(it) }.toTypedArray()
            }
            events.clear()
            runCatching {
                TorRouting.openFollowingRedirects(URL("http://LocalHost.:$port/f"), hops = watcher) {
                    connectTimeout = 2_000; readTimeout = 2_000
                }.disconnect()
            }
            assertEquals(listOf("lookup localhost.", "connecting 3"), events.take(2))
        } finally {
            TorRouting.resolve = realResolve
            TorRouting.proxiesFor = realProxies
            TorRouting.forgetFailedRoutes()
            server.stop(0)
        }
    }

    /**
     * PR #409 R1-M1: a system HTTP proxy that accepts TCP and never answers
     * an https hop's CONNECT costs that route its own share of the connect
     * clock, and the direct fallback is still dialed — on main the reply
     * timed out under the 10 s read timeout and the connection went direct;
     * a watchdog cut of the whole connect took the fallback down with it.
     */
    @Test
    fun `a system proxy that never answers CONNECT falls back to the direct route`() {
        val proxy = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val acceptor = Thread {
            runCatching { while (true) accepted += proxy.accept() } // read nothing, answer nothing
        }.apply { isDaemon = true; start() }
        val realProxies = TorRouting.proxiesFor
        val directs = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            TorRouting.proxiesFor = { listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, proxy.localSocketAddress)) }
            // Direct: the https server's port is closed, so the fallback
            // fails fast with a refusal — what matters is that it's dialed.
            val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val inner = HeaderDeadline(100, patience = PatientWaits(0), connectMs = 100, graceMs = 0) // 200 ms per route
            val deadline = object : TorRouting.HopWatcher by inner {
                override fun connecting(conn: java.net.HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
                    if (!fallback) directs.incrementAndGet()
                    inner.connecting(conn, routes, fallback, tunnel)
                }
            }
            val started = System.nanoTime()
            val e = runCatching {
                TorRouting.openFollowingRedirects(URL("https://127.0.0.1:$closed/x"), hops = deadline) {
                    connectTimeout = 2_000; readTimeout = 20_000
                }
            }.exceptionOrNull()
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("the proxy was never dialed", accepted.isNotEmpty())
            assertEquals("the direct fallback wasn't dialed", 1, directs.get())
            assertTrue("expected the direct route's refusal, got $e", e is java.net.ConnectException)
            assertTrue("took $ms ms: the stalled CONNECT held the connect", ms < 5_000)
            assertFalse("the proxy route's cut expired the attempt", inner.expired)
        } finally {
            TorRouting.proxiesFor = realProxies
            TorRouting.forgetFailedRoutes()
            proxy.close()
            accepted.forEach { runCatching { it.close() } }
            acceptor.join(1_000)
        }
    }

    /**
     * PR #409 R4-M2: a system proxy that never answers CONNECT is cut at
     * the reply's own wait ([tunnelStretchMs]: the connect and the reply
     * share it, near main's 10 s read timeout for the reply), not a
     * connect and a handshake timeout's worth.
     */
    @Test
    fun `a system proxy's unanswered CONNECT is cut at the reply's wait`() {
        val proxy = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val acceptor = Thread {
            runCatching { while (true) accepted += proxy.accept() } // read nothing, answer nothing
        }.apply { isDaemon = true; start() }
        val realProxies = TorRouting.proxiesFor
        val proxyCut = java.util.concurrent.atomic.AtomicLong(-1)
        try {
            TorRouting.proxiesFor = { listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, proxy.localSocketAddress)) }
            val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            // Reply's wait: max(300, 800) = 800 ms; a connect and a handshake: 1 100 ms.
            val inner = HeaderDeadline(800, patience = PatientWaits(0), connectMs = 300, graceMs = 0)
            val started = System.nanoTime()
            val deadline = object : TorRouting.HopWatcher by inner {
                override fun connecting(conn: java.net.HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
                    if (!fallback) proxyCut.set((System.nanoTime() - started) / 1_000_000)
                    assertEquals("the proxy route isn't timed as a tunnel", fallback, tunnel)
                    inner.connecting(conn, routes, fallback, tunnel)
                }
            }
            runCatching {
                TorRouting.openFollowingRedirects(URL("https://127.0.0.1:$closed/x"), hops = deadline) {
                    connectTimeout = 2_000; readTimeout = 20_000
                }
            }
            assertTrue("the proxy was never dialed", accepted.isNotEmpty())
            val ms = proxyCut.get()
            assertTrue("the direct route began $ms ms in, not at the reply's 800 ms wait", ms in 700..1_050)
        } finally {
            TorRouting.proxiesFor = realProxies
            TorRouting.forgetFailedRoutes()
            proxy.close()
            accepted.forEach { runCatching { it.close() } }
            acceptor.join(1_000)
        }
    }

    /**
     * PR #409 R5-M1: a system proxy named by a host is looked up ahead,
     * off the connect clock, and its route is timed for every address the
     * name resolves to (okhttp dials each in turn), not as one.
     */
    @Test
    fun `a system proxy's own addresses are looked up ahead and size its clock`() {
        val realProxies = TorRouting.proxiesFor
        val realResolve = TorRouting.resolve
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            TorRouting.resolve = { name ->
                events += "lookup $name"
                if (name == "dualstack.proxy.test") {
                    arrayOf(java.net.InetAddress.getByName("::1"), java.net.InetAddress.getByName("127.0.0.1"))
                } else {
                    realResolve(name)
                }
            }
            val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            TorRouting.proxiesFor = {
                listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("dualstack.proxy.test", closed)))
            }
            val inner = HeaderDeadline(800, patience = PatientWaits(0), connectMs = 300, graceMs = 0)
            val deadline = object : TorRouting.HopWatcher by inner {
                override fun connecting(conn: java.net.HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
                    events += "connecting tunnel=$tunnel routes=$routes"
                    inner.connecting(conn, routes, fallback, tunnel)
                }
            }
            runCatching {
                TorRouting.openFollowingRedirects(URL("https://127.0.0.1:$closed/x"), hops = deadline) {
                    connectTimeout = 2_000; readTimeout = 20_000
                }
            }
            val tunnelAt = events.indexOf("connecting tunnel=true routes=2")
            assertTrue("the proxy route wasn't timed for its two addresses: $events", tunnelAt >= 0)
            assertTrue("the proxy's name wasn't looked up ahead: $events", events.indexOf("lookup dualstack.proxy.test") in 0 until tunnelAt)
        } finally {
            TorRouting.proxiesFor = realProxies
            TorRouting.resolve = realResolve
            TorRouting.forgetFailedRoutes()
        }
    }

    /**
     * A SOCKS proxy, or one given as an IP literal, is one route: nothing
     * is looked up for it.
     */
    @Test
    fun `a literal or SOCKS system proxy is one route`() {
        val realProxies = TorRouting.proxiesFor
        val realResolve = TorRouting.resolve
        val lookups = java.util.Collections.synchronizedList(mutableListOf<String>())
        val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
        try {
            TorRouting.resolve = { name -> lookups += name; realResolve(name) }
            val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            for (proxy in listOf(
                java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("127.0.0.1", closed)),
                java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved("socks.proxy.test", closed)),
            )) {
                TorRouting.proxiesFor = { listOf(proxy) }
                val inner = HeaderDeadline(800, patience = PatientWaits(0), connectMs = 300, graceMs = 0)
                val deadline = object : TorRouting.HopWatcher by inner {
                    override fun connecting(conn: java.net.HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
                        if (tunnel) seen += routes
                        inner.connecting(conn, routes, fallback, tunnel)
                    }
                }
                runCatching {
                    TorRouting.openFollowingRedirects(URL("https://127.0.0.1:$closed/x"), hops = deadline) {
                        connectTimeout = 2_000; readTimeout = 20_000
                    }
                }
                TorRouting.forgetFailedRoutes()
            }
            assertEquals(listOf(1, 1), seen)
            assertTrue("looked up $lookups", lookups.none { it.contains("proxy") || it == "127.0.0.1" })
        } finally {
            TorRouting.proxiesFor = realProxies
            TorRouting.resolve = realResolve
            TorRouting.forgetFailedRoutes()
        }
    }

    /**
     * PR #409 R4-M1: once a failed proxy route's window is over, one
     * request at a time dials it first again; requests that start while
     * that re-probe runs keep it last, so a proxy still stalled is paid by
     * the one re-probe, not by every request a page starts meanwhile. A
     * failed re-probe opens a fresh window, after which the next request
     * re-probes again.
     */
    @Test
    fun `a failed proxy route is re-probed by one request at a time`() {
        val proxy = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val acceptor = Thread {
            runCatching { while (true) accepted += proxy.accept() } // read nothing, answer nothing
        }.apply { isDaemon = true; start() }
        val realProxies = TorRouting.proxiesFor
        val realClock = TorRouting.routeClock
        val now = java.util.concurrent.atomic.AtomicLong(realClock())
        TorRouting.routeClock = { now.get() }
        // The direct route: TCP connects, and its TLS is refused outright
        // (not a failure okhttp would move on from), so a request that
        // dials it first never goes on to the proxy, and it is never
        // recorded as a failed route itself.
        val direct = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val refusing = object : javax.net.ssl.SSLSocketFactory() {
            override fun getDefaultCipherSuites() = emptyArray<String>()
            override fun getSupportedCipherSuites() = emptyArray<String>()
            override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket =
                throw javax.net.ssl.SSLPeerUnverifiedException("refused")
            override fun createSocket(host: String?, port: Int): java.net.Socket = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, l: java.net.InetAddress?, lp: Int): java.net.Socket = throw UnsupportedOperationException()
            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = throw UnsupportedOperationException()
            override fun createSocket(a: java.net.InetAddress?, port: Int, l: java.net.InetAddress?, lp: Int): java.net.Socket = throw UnsupportedOperationException()
        }
        // Each request records which route it dialed first.
        fun fetch(): String {
            val first = java.util.concurrent.atomic.AtomicReference<String>()
            val inner = HeaderDeadline(600, patience = PatientWaits(0), connectMs = 100, graceMs = 0) // a proxy route: 600 ms
            val deadline = object : TorRouting.HopWatcher by inner {
                override fun connecting(conn: java.net.HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
                    first.compareAndSet(null, if (tunnel) "proxy" else "direct")
                    inner.connecting(conn, routes, fallback, tunnel)
                }
            }
            runCatching {
                TorRouting.openFollowingRedirects(URL("https://127.0.0.1:${direct.localPort}/x"), hops = deadline) {
                    (this as javax.net.ssl.HttpsURLConnection).sslSocketFactory = refusing
                    connectTimeout = 2_000; readTimeout = 20_000
                }
            }
            return first.get()
        }
        try {
            TorRouting.proxiesFor = { listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, proxy.localSocketAddress)) }
            assertEquals("proxy", fetch()) // fails: postponed
            assertEquals("direct", fetch())
            now.addAndGet(TorRouting.ROUTE_POSTPONE_MS) // the window is over
            val probe = java.util.concurrent.Executors.newSingleThreadExecutor()
            val probed = probe.submit<String> { fetch() }
            val until = System.nanoTime() + 2_000_000_000L
            while (accepted.size < 2 && System.nanoTime() < until) Thread.sleep(10)
            assertEquals("the re-probe didn't dial the proxy", 2, accepted.size)
            // While the re-probe holds the proxy, others dial direct first.
            repeat(3) { assertEquals("a request raced the re-probe to the stalled proxy", "direct", fetch()) }
            assertEquals("proxy", probed.get(5, java.util.concurrent.TimeUnit.SECONDS))
            probe.shutdown()
            assertEquals("the proxy was dialed by more than the one re-probe", 2, accepted.size)
            // The re-probe failed: a fresh window from now.
            assertEquals("direct", fetch())
            now.addAndGet(TorRouting.ROUTE_POSTPONE_MS - 1)
            assertEquals("direct", fetch())
            // That window over too: the next request re-probes, the re-probe
            // having been let go when its fetch ended.
            now.addAndGet(1)
            assertEquals("the next request didn't re-probe", "proxy", fetch())
            assertEquals(3, accepted.size)
        } finally {
            TorRouting.proxiesFor = realProxies
            TorRouting.routeClock = realClock
            TorRouting.forgetFailedRoutes()
            direct.close()
            proxy.close()
            accepted.forEach { runCatching { it.close() } }
            acceptor.join(1_000)
        }
    }

    /**
     * PR #409 R3-M1: a failed route's key doesn't depend on whether the
     * proxy's name was resolved on that request — `Proxy.toString` does.
     */
    @Test
    fun `a proxy route's key is the same resolved or not`() {
        val hop = URL("https://Gateway.Example/x")
        val unresolved = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("Proxy.Example", 8080))
        val resolved = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress(java.net.InetAddress.getByAddress("proxy.example", byteArrayOf(10, 0, 0, 5)), 8080),
        )
        assertNotEquals(unresolved.toString(), resolved.toString())
        assertEquals(TorRouting.routeKey(hop, unresolved), TorRouting.routeKey(hop, resolved))
        assertNotEquals(TorRouting.routeKey(hop, resolved), TorRouting.routeKey(hop, java.net.Proxy.NO_PROXY))
        assertNotEquals(
            TorRouting.routeKey(hop, resolved),
            TorRouting.routeKey(hop, java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("proxy.example", 8081))),
        )
    }

    /**
     * PR #409 R2-M1: a proxy route falls through to the next only on a
     * failure okhttp's route selection recovers from
     * (`StreamAllocation.isRecoverable`) — not a certificate refused, an
     * unverified peer, a protocol error or an interruption.
     */
    @Test
    fun `only a failure okhttp would recover from falls through to the next route`() {
        assertTrue(TorRouting.routeFailureRecoverable(java.net.ConnectException("refused")))
        assertTrue(TorRouting.routeFailureRecoverable(java.net.SocketTimeoutException("timed out")))
        assertTrue(TorRouting.routeFailureRecoverable(java.net.SocketException("Socket closed")))
        assertTrue(TorRouting.routeFailureRecoverable(java.io.IOException("Unexpected response code for CONNECT: 502")))
        assertTrue(TorRouting.routeFailureRecoverable(javax.net.ssl.SSLHandshakeException("connection reset")))
        val certRefused = javax.net.ssl.SSLHandshakeException("untrusted").apply {
            initCause(java.security.cert.CertificateException("no trust anchor"))
        }
        assertFalse(TorRouting.routeFailureRecoverable(certRefused))
        assertFalse(TorRouting.routeFailureRecoverable(javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch")))
        assertFalse(TorRouting.routeFailureRecoverable(java.net.ProtocolException("bad")))
        assertFalse(TorRouting.routeFailureRecoverable(java.io.InterruptedIOException("interrupted")))
    }

    /**
     * PR #409 R6-M1: the routes a connect may try, as okhttp's
     * `RouteSelector` builds them — every address of a direct hop, or each
     * proxy the selector names plus the one direct fallback after them
     * (counted as one route: its name isn't looked up here).
     */
    @Test
    fun `connect routes count each address direct, each proxy plus the direct fallback otherwise`() {
        val http = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("proxy.example", 8080))
        val socks = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved("socks.example", 1080))
        var looked = 0
        val three = { looked++; 3 }
        assertEquals(3, TorRouting.connectRoutes(listOf(java.net.Proxy.NO_PROXY), three))
        assertEquals(3, TorRouting.connectRoutes(emptyList(), three))
        assertEquals(1, TorRouting.connectRoutes(listOf(java.net.Proxy.NO_PROXY)) { 0 })
        assertEquals(2, looked)
        assertEquals(2, TorRouting.connectRoutes(listOf(http), three))
        assertEquals(3, TorRouting.connectRoutes(listOf(http, socks), three))
        assertEquals(2, TorRouting.connectRoutes(listOf(http, java.net.Proxy.NO_PROXY), three))
        assertEquals("a proxied hop's name was looked up", 2, looked)
    }

    /** Run [block] with [TorRouting.resolve] answering from [names] (anything else unresolvable). */
    private fun <T> withResolver(vararg names: Pair<String, String>, block: () -> T): T {
        val table = mapOf(
            "localtest.me" to listOf("127.0.0.1"),
            "127.0.0.1.nip.io" to listOf("127.0.0.1"),
            "loop6.example" to listOf("::1"),
            "mapped.example" to listOf("::ffff:7f00:1"),
            "mixed.example" to listOf("198.51.100.9", "127.0.0.2"),
        ) + names.associate { (k, v) -> k to listOf(v) }
        val real = TorRouting.resolve
        TorRouting.resolve = { host ->
            table[host]?.map { java.net.InetAddress.getByName(it) }?.toTypedArray()
                ?: throw java.net.UnknownHostException(host)
        }
        try {
            return block()
        } finally {
            TorRouting.resolve = real
        }
    }

    @Test
    fun `a gateway's redirect to another loopback service is never opened`() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val victim = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        victim.createContext("/") { ex ->
            hits.incrementAndGet()
            val out = """{"ethereum":"0xsecret"}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        val gateway = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        gateway.createContext("/") { ex ->
            ex.responseHeaders.add("Location", "http://localhost:${victim.address.port}/addresses")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        victim.start()
        gateway.start()
        try {
            try {
                TorRouting.openFollowingRedirects(URL("http://127.0.0.1:${gateway.address.port}/ipfs/bafyroot/")) {}
                fail("followed a gateway's redirect onto another loopback service")
            } catch (e: TorRouting.RedirectRefusedException) {
                // Unreachable to the gateway interceptor, so it isn't retried.
                assertTrue(e is java.net.ConnectException)
            }
            assertEquals(0, hits.get())
        } finally {
            gateway.stop(0)
            victim.stop(0)
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
        assertEquals(
            TorRouting.CODE_PROXY_DOWN,
            TorRouting.refusalCode(true, true, external = true, externalRunning = true),
        )
        // Tor stopped (or never started): nothing checked the proxy (#305 R1-M1).
        assertEquals(TorRouting.CODE_NOT_RUNNING, TorRouting.refusalCode(true, true, external = true))
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
    fun `native onion fetches waiting on a check leave a page its held slots`() {
        // #376 R2-F2: manifest discovery (awaitOnionRoute) holds count in
        // their own pool, so a burst of them during a re-check can't fill
        // MAX_HELD and get a page's onion document refused early.
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        val pending = java.util.Collections.synchronizedList(mutableListOf<Runnable>())
        TorRouting.setOverride = { _, _, done -> pending += done }
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            pending.removeAt(0).run()
            TorRouting.setExternal(context, orbot, confirmed = false, pending = true)

            // More native fetches than MAX_HELD: MAX_NATIVE_HELD wait, the rest are answered at once.
            val fetches = (1..TorRouting.MAX_HELD + 2).map { pool.submit<Boolean> { TorRouting.awaitOnionRoute(10_000) } }
            Thread.sleep(300)
            assertEquals(TorRouting.MAX_NATIVE_HELD, fetches.count { !it.isDone })
            var t0 = System.nanoTime()
            assertFalse(TorRouting.awaitOnionRoute(5_000))
            assertTrue(System.nanoTime() - t0 < 1_000_000_000L)

            // Every page slot is still free: MAX_HELD page requests are held, not answered at once.
            val pages = (1..TorRouting.MAX_HELD).map { pool.submit<Boolean> { TorRouting.awaitExternalVerdict(10_000) } }
            Thread.sleep(300)
            assertTrue(pages.none { it.isDone })
            t0 = System.nanoTime()
            assertFalse(TorRouting.awaitExternalVerdict(5_000)) // the (MAX_HELD + 1)th
            assertTrue(System.nanoTime() - t0 < 1_000_000_000L)

            // The check passes and the WebView confirms: every waiter, page and native, is let through.
            TorRouting.setExternal(context, orbot, confirmed = true)
            pending.removeAt(0).run()
            pages.forEach { assertTrue(it.get(2, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(TorRouting.MAX_NATIVE_HELD, fetches.count { it.get(2, java.util.concurrent.TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `an onion request waits for a pending external check instead of being refused`() {
        // R1-F1: a link from another app (or a form posted on return from an
        // authenticator) arrives with the Activity's start, before the
        // first check since can have passed.
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        val pending = java.util.Collections.synchronizedList(mutableListOf<Runnable>())
        TorRouting.setOverride = { _, _, done -> pending += done }
        fun confirm() = pending.removeAt(0).run()
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            confirm()
            // Not pending (a failed check): refused at once.
            TorRouting.setExternal(context, orbot, confirmed = false)
            var t0 = System.nanoTime()
            assertFalse(TorRouting.awaitExternalVerdict(5_000))
            assertTrue(System.nanoTime() - t0 < 1_000_000_000L)

            // Pending: waits, and is let through once the check passes and
            // the WebView has confirmed the override naming the proxy.
            TorRouting.setExternal(context, orbot, confirmed = false, pending = true)
            val waiting = pool.submit<Boolean> { TorRouting.awaitExternalVerdict(10_000) }
            Thread.sleep(200)
            assertFalse(waiting.isDone)
            TorRouting.setExternal(context, orbot, confirmed = true)
            Thread.sleep(200)
            assertFalse(waiting.isDone) // not before the WebView confirms
            confirm()
            assertTrue(waiting.get(2, java.util.concurrent.TimeUnit.SECONDS))

            // Stopped (pending again): nothing routed meanwhile; a check that
            // fails answers the waiter with a refusal at once.
            TorRouting.setExternal(context, orbot, confirmed = false, pending = true)
            assertFalse(TorRouting.isRouted)
            val refused = pool.submit<Boolean> { TorRouting.awaitExternalVerdict(10_000) }
            Thread.sleep(200)
            assertFalse(refused.isDone)
            TorRouting.setExternal(context, orbot, confirmed = false)
            assertFalse(refused.get(2, java.util.concurrent.TimeUnit.SECONDS))

            // No verdict at all: refused at the deadline.
            TorRouting.setExternal(context, orbot, confirmed = false, pending = true)
            t0 = System.nanoTime()
            assertFalse(TorRouting.awaitExternalVerdict(300))
            assertTrue(System.nanoTime() - t0 >= 250_000_000L)

            // At most MAX_HELD wait; the next is answered at once.
            val held = (1..TorRouting.MAX_HELD).map { pool.submit<Boolean> { TorRouting.awaitExternalVerdict(10_000) } }
            Thread.sleep(300)
            t0 = System.nanoTime()
            assertFalse(TorRouting.awaitExternalVerdict(5_000))
            assertTrue(System.nanoTime() - t0 < 1_000_000_000L)
            // Tor switched off: every waiter is refused.
            TorRouting.setEnabled(context, false)
            held.forEach { assertFalse(it.get(2, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `the hold spans the whole first check, and a document refused mid-check says so`() {
        // R2-F1: a slow but working Tor can take the probe's own deadlines
        // (the canary, then up to 45 s per probe onion) to pass; a shorter
        // hold refused it mid-check with "no Tor client answers".
        assertTrue(TorProxy.CHECK_MAX_MS >= 100_000L)
        assertTrue(TorRouting.HOLD_MS > TorProxy.CHECK_MAX_MS)
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        val pending = java.util.Collections.synchronizedList(mutableListOf<Runnable>())
        TorRouting.setOverride = { _, _, done -> pending += done }
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            pending.removeAt(0).run()
            TorRouting.setExternal(context, orbot, confirmed = false, pending = true)
            assertEquals(TorRouting.CODE_PROXY_CHECKING, TorRouting.documentRefusalCode())
            // Confirmed, the WebView not yet: still no verdict to show.
            TorRouting.setExternal(context, orbot, confirmed = true)
            assertEquals(TorRouting.CODE_PROXY_CHECKING, TorRouting.documentRefusalCode())
            // A verdict: the proxy page, as before.
            TorRouting.setExternal(context, orbot, confirmed = false, running = true)
            assertEquals(TorRouting.CODE_PROXY_DOWN, TorRouting.documentRefusalCode())
            TorRouting.setEnabled(context, false)
            assertEquals(TorRouting.CODE_OFF, TorRouting.documentRefusalCode())
        } finally {
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
        val checking = TorRouting.refusalHtml(onion, TorRouting.CODE_PROXY_CHECKING, TorInfo(), orbot)
        assertTrue(checking.contains("<h1>Checking the Tor proxy</h1>"))
        assertTrue(checking.contains("127.0.0.1:9050"))
        assertFalse(checking.contains("no Tor client"))
        assertFalse(checking.contains("Start Orbot"))
        // It asks again by itself (a meta refresh, still no script), so it
        // loads once the proxy passes.
        assertTrue(checking.contains("<meta http-equiv=\"refresh\" content=\"${TorRouting.CHECKING_REFRESH_S}\">"))
        assertFalse(checking.contains("<script"))
        // Only that page refreshes.
        val down = TorRouting.refusalHtml(onion, TorRouting.CODE_PROXY_DOWN, TorInfo(), orbot)
        assertFalse(down.contains("http-equiv=\"refresh\""))
    }

    @Test
    fun `an external proxy's Tor switched off (or never started) is "not running", not "no Tor client answers"`() {
        // R1-M1: stopping Tor on the Nodes page publishes the proxy with no
        // check pending and none running; nothing asked the proxy, so the
        // page says what the embedded client's would.
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        TorRouting.setOverride = { _, _, done -> done.run() }
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            // Settings loaded, Tor not started at launch.
            TorRouting.setExternal(context, orbot, confirmed = false)
            assertEquals(TorRouting.CODE_NOT_RUNNING, TorRouting.documentRefusalCode())
            // Started, and the check found nothing there.
            TorRouting.setExternal(context, orbot, confirmed = false, running = true)
            assertEquals(TorRouting.CODE_PROXY_DOWN, TorRouting.documentRefusalCode())
            // Stopped on the Nodes page (MainActivity.stopExternalTor).
            TorRouting.setExternal(context, orbot, confirmed = false)
            assertEquals(TorRouting.CODE_NOT_RUNNING, TorRouting.documentRefusalCode())
            // A stale embedded error doesn't reach the external page.
            TorRouting.onState(context, TorInfo(status = TorStatus.Error, error = HeldText.raw("arti failed")))
            val page = TorRouting.documentRefusalHtml(onion)
            assertTrue(page.contains("<h1>Tor isn't running</h1>"))
            assertTrue(page.contains("Nodes page"))
            assertFalse(page.contains("no Tor client"))
            assertFalse(page.contains("Orbot"))
            assertFalse(page.contains("arti failed"))
            assertFalse(page.contains("couldn't start"))
        } finally {
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
    }

    @Test
    fun `a document refused while the Activity is stopped doesn't promise a check or loop`() {
        // R3-M1: nothing checks until the Activity is back, so not "still
        // checking … loads by itself", and no refresh that would re-hold the
        // request every HOLD_MS + 5 s in the background.
        val context = android.content.ContextWrapper(null)
        val real = TorRouting.setOverride
        val pending = java.util.Collections.synchronizedList(mutableListOf<Runnable>())
        TorRouting.setOverride = { _, _, done -> pending += done }
        val orbot = SocksEndpoint("127.0.0.1", 9050)
        try {
            TorRouting.resetForTest(supported = true)
            TorRouting.setEnabled(context, true)
            pending.removeAt(0).run()
            // Stopped: still held (the start may be on its way), but idle.
            TorRouting.setExternal(context, orbot, confirmed = false, pending = true, idle = true)
            assertFalse(TorRouting.awaitExternalVerdict(200))
            assertEquals(TorRouting.CODE_PROXY_PAUSED, TorRouting.documentRefusalCode())
            // Started again: the check runs.
            TorRouting.setExternal(context, orbot, confirmed = false, pending = true)
            assertEquals(TorRouting.CODE_PROXY_CHECKING, TorRouting.documentRefusalCode())
            // Idle means nothing without a pending verdict.
            TorRouting.setExternal(context, orbot, confirmed = false, idle = true, running = true)
            assertEquals(TorRouting.CODE_PROXY_DOWN, TorRouting.documentRefusalCode())
        } finally {
            TorRouting.setOverride = real
            TorRouting.resetForTest(supported = null)
        }
        val paused = TorRouting.refusalHtml(onion, TorRouting.CODE_PROXY_PAUSED, TorInfo(), orbot)
        assertTrue(paused.contains("<h1>Tor proxy not checked yet</h1>"))
        assertTrue(paused.contains("127.0.0.1:9050"))
        assertTrue(paused.contains("background"))
        assertFalse(paused.contains("still checking"))
        assertFalse(paused.contains("by itself"))
        assertFalse(paused.contains("no Tor client"))
        assertFalse(paused.contains("http-equiv=\"refresh\""))
        assertFalse(paused.contains("<script"))
    }

    @Test
    fun `an onion request waits for the Tor settings on a cold start`() {
        // R3-M2: a link that cold-starts the app arrives before Settings →
        // Tor is read; judged by the defaults it got "Tor is off".
        TorRouting.resetForTest(supported = true)
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        try {
            // Nothing expected: no wait.
            var t0 = System.nanoTime()
            TorRouting.awaitSettings(5_000)
            assertTrue(System.nanoTime() - t0 < 1_000_000_000L)
            TorRouting.expectSettings()
            val waiting = pool.submit { TorRouting.awaitSettings(10_000) }
            Thread.sleep(200)
            assertFalse(waiting.isDone)
            TorRouting.settingsLoaded()
            waiting.get(2, java.util.concurrent.TimeUnit.SECONDS)
            // Bounded if they never land.
            TorRouting.expectSettings()
            t0 = System.nanoTime()
            TorRouting.awaitSettings(300)
            assertTrue(System.nanoTime() - t0 >= 250_000_000L)
        } finally {
            pool.shutdownNow()
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
