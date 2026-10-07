package baby.freedom.mobile.browser

import baby.freedom.mobile.l10n.PseudoLanguage
import baby.freedom.mobile.l10n.inPseudoLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeApiGuardTest {
    private fun refused(method: String, url: String, externalSwarm: String = "") =
        NodeApiGuard.refuses(method, url, externalSwarm)

    @Test
    fun `every on-chain write to the node is refused`() {
        for ((method, path) in listOf(
            "POST" to "/stamps/10000000/17",
            "PATCH" to "/stamps/topup/abc/100",
            "PATCH" to "/stamps/dilute/abc/18",
            "POST" to "/chequebook/deposit?amount=1",
            "POST" to "/chequebook/withdraw?amount=1",
            "POST" to "/chequebook/cashout/abc",
            "POST" to "/stake/1",
            "DELETE" to "/stake",
            "POST" to "/wallet/withdraw/nativetoken",
            "POST" to "/transactions/0xab",
            "DELETE" to "/transactions/0xab",
            "OPTIONS" to "/stamps/1/17",
            "PUT" to "/stamps",
            "post" to "/stamps/1/17",
        )) {
            assertTrue("$method $path", refused(method, "http://127.0.0.1:1633$path"))
        }
    }

    @Test
    fun `ant's v0 spending routes are refused as chain writes on every host`() {
        val spends = listOf(
            "/v0/storage/buy", "/v0/storage/extend", "/v0/settlement/deposit",
            "/V0/Storage/Buy", "//v0/./storage/buy", "/%76%30/storage/buy", "/v0%2Fsettlement%2Fdeposit",
            "/v0/storage/buy?depth=17", "/v0/storage/some-future-spend",
        )
        val spendRefusal = NodeApiGuard.refusalText("POST", "http://127.0.0.1:1633/stamps/1/17")
        for (path in spends) {
            for (host in listOf("127.0.0.1", "192.168.1.20", "nas")) {
                val url = "http://$host:1633$path"
                assertTrue(url, refused("POST", url, externalSwarm = "http://nas:1633"))
                assertEquals(url, spendRefusal, NodeApiGuard.refusalText("POST", url))
            }
        }
        // A read of those paths isn't a spend: refused on the device with the
        // read text, let through to another node. A write to any other v0
        // route isn't a spend either, but no page writes through a node (#358).
        val writeRefusal = NodeApiGuard.refusalText("POST", "http://127.0.0.1:1633/bzz")
        assertEquals(NodeApiGuard.READ_REFUSAL, NodeApiGuard.refusalText("GET", "http://127.0.0.1:1633/v0/storage/buy"))
        assertEquals(writeRefusal, NodeApiGuard.refusalText("POST", "http://127.0.0.1:1633/v0/manifest/ab"))
        assertFalse(refused("GET", "http://192.168.1.20:1633/v0/storage/buy"))
        assertTrue(refused("POST", "http://192.168.1.20:1633/v0/manifest/ab"))
        assertEquals(writeRefusal, NodeApiGuard.refusalText("POST", "http://192.168.1.20:1633/storage/buy"))
    }

    @Test
    fun `the node's own API is refused for reads too`() {
        for (path in listOf(
            "/addresses", "/wallet", "/stamps", "/stamps/abc", "/stamps/abc/buckets",
            "/chequebook/address", "/chequebook/balance", "/chequebook/cheque",
            "/balances", "/balances/ab", "/consumed", "/settlements", "/timesettlements",
            "/peers", "/topology", "/node", "/status", "/chainstate", "/batches",
            "/pins", "/pins/check", "/tags", "/tags/1", "/grantee/ab", "/envelope/ab",
            "/v0/manifest/ab", "/", "", "?x=1", "#bzz", "/some-future-endpoint",
        )) {
            for (method in listOf("GET", "HEAD", "OPTIONS")) {
                assertTrue("$method $path", refused(method, "http://127.0.0.1:1633$path"))
            }
        }
    }

    @Test
    fun `the refusal names every path a page may use`() {
        for (path in NodeApiGuard.DAPP_PATHS) {
            assertTrue(path, "/$path" in NodeApiGuard.READ_REFUSAL)
        }
        for (path in listOf("/pss", "/gsoc", "/health", "/readiness")) {
            assertTrue(path, path in NodeApiGuard.READ_REFUSAL)
        }
    }

    @Test
    fun `every refusal a page can read stays English in another app language`() {
        val cases = listOf(
            "POST" to "http://127.0.0.1:1633/stamps/1/17",
            "POST" to "http://127.0.0.1:1633/bzz",
            "GET" to "http://127.0.0.1:1633/addresses",
            "GET" to "http://nas.lan:1633/addresses",
        )
        val english = cases.map { (m, u) -> NodeApiGuard.refusalText(m, u) }
        val pseudo = inPseudoLanguage { cases.map { (m, u) -> NodeApiGuard.refusalText(m, u) } }
        assertEquals(english, pseudo)
        for (text in pseudo) {
            assertTrue(text, text!!.isNotEmpty() && !text.contains(PseudoLanguage.MARK.trim()))
        }
    }

    @Test
    fun `the refusal is recognised by its header, whatever its case, and nothing else is`() {
        val wallet = "http://127.0.0.1:1633/wallet"
        assertTrue(NodeApiGuard.isRefusal("GET", wallet, mapOf(NodeApiGuard.REFUSAL_HEADER to "1"), ""))
        assertTrue(NodeApiGuard.isRefusal("GET", wallet, mapOf("x-node-api-refused" to "1"), ""))
        assertFalse(NodeApiGuard.isRefusal("GET", wallet, mapOf("Content-Type" to "text/plain"), ""))
        assertFalse(NodeApiGuard.isRefusal("GET", wallet, emptyMap(), ""))
        assertFalse(NodeApiGuard.isRefusal("GET", wallet, null, ""))
    }

    @Test
    fun `a server's own response can't pass for the refusal`() {
        // Loaded straight from the server, unstripped (R6-F1): the external
        // node's content page, a LAN node's read, the dapp surface.
        val header = mapOf(NodeApiGuard.REFUSAL_HEADER to "1")
        val external = "http://192.168.1.10:1633"
        assertFalse(NodeApiGuard.isRefusal("GET", "$external/bzz/abc/", header, external))
        assertFalse(NodeApiGuard.isRefusal("GET", "http://nas.lan:1633/wallet", header, "http://nas.lan:1633"))
        assertFalse(NodeApiGuard.isRefusal("GET", "http://192.168.1.20:1633/wallet", header, ""))
        assertFalse(NodeApiGuard.isRefusal("GET", "http://127.0.0.1:1633/bzz/abc/", header, ""))
        assertFalse(NodeApiGuard.isRefusal("GET", "https://example.com/wallet", header, ""))
        // A request the guard does refuse is answered before the network.
        assertTrue(NodeApiGuard.isRefusal("POST", "http://192.168.1.20:1633/stamps/1/17", header, ""))
    }

    @Test
    fun `a gateway response can't pass for the refusal`() {
        for (name in listOf(NodeApiGuard.REFUSAL_HEADER, "x-node-api-refused", "X-NODE-API-REFUSED")) {
            val passed = gatewayResponseHeaders(
                mapOf(null to listOf("HTTP/1.1 404 Not Found"), name to listOf("1"), "Content-Type" to listOf("text/html")),
            )
            assertFalse(name, NodeApiGuard.isRefusal("GET", "http://127.0.0.1:1633/wallet", passed, ""))
            assertFalse(name, nameResolutionErrorIn(passed) != null)
            assertTrue(passed["Content-Type"] == "text/html")
        }
        assertFalse(
            nameResolutionErrorIn(gatewayResponseHeaders(mapOf(NAME_RESOLUTION_ERROR_HEADER to listOf("ens_not_found")))) != null,
        )
    }

    @Test
    fun `the dapp surface stays open to reads`() {
        for ((method, path) in listOf(
            "GET" to "/bzz/ab/index.html",
            "HEAD" to "/bzz/ab",
            "GET" to "/bytes/ab",
            "GET" to "/chunks/ab",
            "GET" to "/soc/ab/cd",
            "GET" to "/feeds/ab/cd",
            "GET" to "/gsoc/subscribe/ab",
            "GET" to "/health",
            "GET" to "/readiness",
            "GET" to "/BZZ/ab",
            "GET" to "//bytes/ab",
        )) {
            assertFalse("$method $path", refused(method, "http://127.0.0.1:1633$path"))
        }
    }

    @Test
    fun `no page writes through a node, on any host, the dapp surface included`() {
        val writes = listOf(
            "POST" to "/bzz?name=a.txt", "POST" to "/bzz", "PUT" to "/bzz/ab", "OPTIONS" to "/bzz",
            "POST" to "/bytes", "POST" to "/chunks", "POST" to "/soc/ab/cd", "POST" to "/feeds/ab/cd",
            "POST" to "/pss/send/ab/cd", "POST" to "/gsoc/send/ab", "POST" to "/pins/ab", "DELETE" to "/pins/ab",
            "POST" to "/connect/ip4/1.2.3.4/tcp/1634", "POST" to "/tags", "PATCH" to "/tags/1", "DELETE" to "/tags/1",
            "POST" to "/grantee", "PATCH" to "/grantee/ab", "POST" to "/envelope/ab", "POST" to "/stewardship/ab",
            "PUT" to "/stewardship/ab", "DELETE" to "/peers/ab", "POST" to "/health", "post" to "/BZZ",
            "POST" to "/some-future-endpoint",
        )
        val hosts = listOf("127.0.0.1", "localhost", "[::1]", "192.168.1.20", "10.0.2.2", "[fe80::1]", "8.8.8.8", "nas", "bee.example")
        val writeRefusal = NodeApiGuard.refusalText("POST", "http://127.0.0.1:1633/bzz")!!
        assertTrue(writeRefusal, "window.swarm" in writeRefusal)
        for ((method, path) in writes) {
            for (host in hosts) {
                val url = "http://$host:1633$path"
                assertTrue("$method $url", refused(method, url))
                assertTrue("$method $url", refused(method, url, externalSwarm = "http://$host:1633"))
                assertEquals("$method $url", writeRefusal, NodeApiGuard.refusalText(method, url))
            }
            assertTrue("$method https", refused(method, "https://127.0.0.1:1633$path"))
        }
    }

    @Test
    fun `the external Swarm node takes no page writes on its own port, and only it`() {
        val external = "https://bee.example:8443"
        for (url in listOf(
            "https://bee.example:8443/bzz", "https://BEE.example.:8443/bzz", "https://bee.example:8443/pins/ab",
            "https://bee.example:08443/tags",
        )) {
            assertTrue(url, refused("POST", url, externalSwarm = external))
        }
        // Its reads aren't the device's API and pass as before.
        assertFalse(refused("GET", "https://bee.example:8443/bzz/ab/", externalSwarm = external))
        assertFalse(refused("GET", "https://bee.example:8443/wallet", externalSwarm = external))
        // Another port, scheme or host is another server.
        assertFalse(refused("POST", "https://bee.example/bzz", externalSwarm = external))
        assertFalse(refused("POST", "http://bee.example:8443/bzz", externalSwarm = external))
        assertFalse(refused("POST", "https://other.example:8443/bzz", externalSwarm = external))
        assertFalse(refused("POST", "https://bee.example.evil.example:8443/bzz", externalSwarm = external))
        // A default port matches the external node's implicit one.
        assertTrue(refused("POST", "https://gw.example:443/bzz", externalSwarm = "https://gw.example"))
        assertTrue(refused("POST", "https://gw.example/feeds/a/b", externalSwarm = "https://gw.example/"))
        // No external node: nothing off the gateway port.
        assertFalse(refused("POST", "https://bee.example:8443/bzz"))
    }

    @Test
    fun `an external Swarm node behind a path is only that path, not its whole origin`() {
        val external = ExternalEndpoints.normalize("https://me.example/bee/")!!
        assertEquals("https://me.example/bee", external)
        for (url in listOf(
            "https://me.example/bee", "https://me.example/bee/", "https://me.example/bee/bzz",
            "https://me.example/bee/pins/ab", "https://me.example/BEE/bytes", "https://me.example/%62ee/feeds/a/b",
            "https://me.example//bee/tags",
            // A proxy that decodes `%2F` and resolves the `..` hands these to /bee (R2-F1).
            "https://me.example/x%2F..%2Fbee/bzz", "https://me.example/x%2f..%2fbee/pins/ab",
            "https://me.example/a/b%2F..%2F..%2Fbee/feeds/a/b", "https://me.example/x%5C..%5Cbee/bzz",
            "https://me.example/%2E%2E%2Fbee/bzz", "https://me.example/x/%2e%2e/bee/tags",
        )) {
            assertTrue(url, refused("POST", url, externalSwarm = external))
        }
        // The rest of the origin is another site: its logins and forms aren't the node's.
        for (url in listOf(
            "https://me.example/login", "https://me.example/", "https://me.example/beehive/upload",
            "https://me.example/nextcloud/bee/x", "https://me.example/be",
            "https://me.example/x%2F..%2Flogin",
        )) {
            assertFalse(url, refused("POST", url, externalSwarm = external))
        }
        // Deeper mounts too.
        val deep = ExternalEndpoints.normalize("https://me.example/a/b")!!
        assertTrue(refused("PUT", "https://me.example/a/b/feeds/x", externalSwarm = deep))
        assertFalse(refused("PUT", "https://me.example/a/feeds/x", externalSwarm = deep))
        // Reads anywhere on it still pass.
        assertFalse(refused("GET", "https://me.example/bee/bzz/ab/", externalSwarm = external))
    }

    @Test
    fun `a CORS preflight is judged as the request it asks for`() {
        fun preflight(asks: String?) =
            NodeApiGuard.pageMethod("OPTIONS", asks?.let { mapOf("Access-Control-Request-Method" to it) } ?: emptyMap())
        assertEquals("GET", preflight("GET"))
        assertEquals("POST", preflight("POST"))
        assertEquals("PUT", NodeApiGuard.pageMethod("OPTIONS", mapOf("access-control-request-method" to " PUT ")))
        // A page's own OPTIONS, and one with no or a blank header, stays a write.
        assertEquals("OPTIONS", preflight(null))
        assertEquals("OPTIONS", preflight(""))
        assertEquals("OPTIONS", NodeApiGuard.pageMethod("OPTIONS", null))
        // Only OPTIONS is read through the header.
        assertEquals("POST", NodeApiGuard.pageMethod("POST", mapOf("Access-Control-Request-Method" to "GET")))

        assertFalse(refused(preflight("GET"), "http://127.0.0.1:1633/bzz/ab/"))
        assertFalse(refused(preflight("HEAD"), "http://127.0.0.1:1633/bytes/ab"))
        assertTrue(refused(preflight("POST"), "http://127.0.0.1:1633/bzz"))
        assertTrue(refused(preflight("PUT"), "http://127.0.0.1:1633/feeds/ab/cd"))
        assertTrue(refused(preflight("GET"), "http://127.0.0.1:1633/wallet"))
        assertTrue(refused(preflight(null), "http://127.0.0.1:1633/bzz"))
    }

    @Test
    fun `a page's batch and access-control headers aren't forwarded to the gateway`() {
        for (name in listOf(
            "Swarm-Postage-Batch-Id", "swarm-postage-batch-id", "SWARM-POSTAGE-BATCH-ID",
            "Swarm-Act", "Swarm-Act-Publisher", "Swarm-Act-History-Address", "Swarm-Act-Timestamp", "swarm-act-anything",
        )) {
            assertTrue(name, isNodeAuthorityHeader(name))
        }
        for (name in listOf(
            "Range", "Accept", "Swarm-Chunk-Retrieval-Timeout", "Swarm-Redundancy-Strategy", "Swarm-Feed-Index",
            "Swarm-Postage-Stamp", "Swarm-Tag", "X-Swarm-Act",
        )) {
            assertFalse(name, isNodeAuthorityHeader(name))
        }
    }

    @Test
    fun `any host on the gateway port that may be the device counts, since any name can resolve to loopback`() {
        for (host in listOf(
            "localhost", "127.0.0.1", "[::1]", "[::ffff:7f00:1]", "[::ffff:127.0.0.1]", "[::]",
            "[::7f00:1]", "0.0.0.0", "0", "0.1.2.3", "127.1", "0x7f.1", "2130706433", "127.9.9.9",
            "app.localhost", "127.0.0.1.nip.io", "anything.example", "nas", "user:pw@127.0.0.1",
            "LOCALHOST.",
        )) {
            assertTrue(host, refused("POST", "http://$host:1633/stamps/1/17"))
            assertTrue(host, refused("GET", "http://$host:1633/wallet"))
            assertFalse(host, refused("GET", "http://$host:1633/bzz/ab"))
        }
        assertTrue(refused("POST", "https://127.0.0.1:1633/stamps/1/17"))
        assertTrue(refused("POST", "http://127.0.0.1:01633/stamps/1/17"))
    }

    @Test
    fun `another machine's Bee node is reachable by IP literal, and by the external node's own name`() {
        for (host in listOf("192.168.1.20", "10.0.2.2", "[fe80::1]", "[2001:db8::1]", "[::1:0:0:0]", "8.8.8.8", "0xc0.0xa8.1.20")) {
            assertFalse(host, refused("GET", "http://$host:1633/wallet"))
            assertFalse(host, refused("GET", "http://$host:1633/stamps"))
            assertFalse(host, refused("GET", "http://$host:1633/pins"))
            // But no page writes to it (#358): a no-cors POST needs no
            // preflight, so pinning, tagging or dialing peers would be CSRF.
            assertTrue(host, refused("POST", "http://$host:1633/pins/ab"))
            assertTrue(host, refused("POST", "http://$host:1633/connect/ip4/1.2.3.4/tcp/1634"))
            assertTrue(host, refused("POST", "http://$host:1633/tags"))
            // Spending stays refused on every host, as before #283.
            assertTrue(host, refused("POST", "http://$host:1633/stamps/1/17"))
        }
        // The user named this node in Settings: it's theirs, not the device.
        assertFalse(refused("GET", "http://nas:1633/wallet", externalSwarm = "http://nas:1633"))
        assertFalse(refused("GET", "http://NAS.:1633/addresses", externalSwarm = "http://nas:1633"))
        assertTrue(refused("POST", "http://nas:1633/stamps/1/17", externalSwarm = "http://nas:1633"))
        // Only that name, and only on the gateway port.
        assertTrue(refused("GET", "http://other:1633/wallet", externalSwarm = "http://nas:1633"))
        assertTrue(refused("GET", "http://nas:1633/wallet", externalSwarm = "http://nas:8080"))
        // A loopback name is still the device, even if it's the external node.
        assertTrue(refused("GET", "http://localhost:1633/wallet", externalSwarm = "http://localhost:1633"))
        assertTrue(refused("GET", "http://127.0.0.1:1633/wallet", externalSwarm = "http://127.0.0.1:1633"))
    }

    @Test
    fun `other ports and schemes are not the node`() {
        assertFalse(refused("POST", "http://127.0.0.1/stamps/1/17"))
        assertFalse(refused("POST", "http://127.0.0.1:1634/stamps/1/17"))
        assertFalse(refused("POST", "https://bee.example/stamps/1/17"))
        assertFalse(refused("POST", "http://127.0.0.1:16330/stamps/1/17"))
        assertFalse(refused("GET", "http://127.0.0.1:5001/api/v0/id"))
        assertFalse(refused("GET", "https://bee.example/wallet"))
        assertFalse(refused("POST", "http://127.0.0.1:99999999999999/stamps/1/17"))
        assertFalse(refused("POST", "ws://127.0.0.1:1633/stamps/1/17"))
        assertFalse(refused("POST", "not a url"))
        // A port in the path or query isn't the authority's.
        assertFalse(refused("POST", "http://example.com/x:1633/stamps"))
        assertFalse(refused("POST", "http://example.com?h=a:1633/stamps"))
    }

    @Test
    fun `the endpoint can't hide behind encoding, case or empty and dot segments`() {
        for (path in listOf(
            "//stamps/1/17", "/./stamps/1/17", "/%2e%2e/stamps/1/17", "/%73tamps/1/17",
            "/STAMPS/1/17", "/Chequebook/deposit", "/%2Fstamps/1/17", "\\stamps/1/17",
        )) {
            assertTrue(path, refused("POST", "http://127.0.0.1:1633$path"))
        }
        for (path in listOf("/%61ddresses", "/WALLET", "//addresses", "/./wallet", "/%2e%2e/addresses")) {
            assertTrue(path, refused("GET", "http://127.0.0.1:1633$path"))
        }
        // Broken escapes decode as themselves, which isn't the dapp surface either.
        assertTrue(refused("POST", "http://127.0.0.1:1633/%zzstamps"))
        assertTrue(refused("POST", "http://127.0.0.1:1633/%"))
        assertTrue(refused("GET", "http://127.0.0.1:1633/%zzbzz/ab"))
    }
}
