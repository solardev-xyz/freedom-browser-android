package baby.freedom.mobile.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `rad://` addresses and the repository browser's read API (#124). */
class RadBrowserTest {
    private val bare = "z4V1sjrXqjvFdnCUbxPFqd5p4DtH5"
    private val rid = "rad:$bare"
    private val rev = "00f079d0a1b2c3d4e5f60718293a4b5c6d7e8f90"

    // ---- URLs ----

    @Test
    fun `both rad forms map to the repository's page and back`() {
        assertEquals("https://rad.freedom.baby/$bare", RadUrl.toVirtualUrl("rad://$bare"))
        assertEquals("https://rad.freedom.baby/$bare", RadUrl.toVirtualUrl("rad:$bare"))
        assertEquals("https://rad.freedom.baby/$bare/tree/$rev/src", RadUrl.toVirtualUrl("rad://$bare/tree/$rev/src"))
        assertEquals("https://rad.freedom.baby/$bare/issues?status=closed", RadUrl.toVirtualUrl("rad:$bare/issues?status=closed"))
        assertEquals("https://rad.freedom.baby/$bare/?q", RadUrl.toVirtualUrl("rad://$bare?q"))
        assertEquals("rad://$bare", RadUrl.displayUrlFor("https://rad.freedom.baby/$bare"))
        assertEquals("rad://$bare/issues/abc123", RadUrl.displayUrlFor("https://rad.freedom.baby/$bare/issues/abc123"))
        assertEquals("rad://$bare/tree/$rev/src", Gateways.toDisplay(Gateways.toLoadable("rad://$bare/tree/$rev/src")))
    }

    @Test
    fun `the RID keeps its case and the scheme doesn't`() {
        assertEquals("https://rad.freedom.baby/$bare", RadUrl.toVirtualUrl("RAD://$bare"))
        // Base58 is case-sensitive: a case-folded RID is another (invalid) one.
        assertEquals(bare.lowercase() to "", RadUrl.parse("rad:${bare.lowercase()}"))
        assertNull(RadUrl.parse("rad:${bare.uppercase()}"))
    }

    @Test
    fun `an invalid RID goes to the viewer's not-an-ID page, named`() {
        val url = RadUrl.toVirtualUrl("rad://not-a-rid/x")!!
        assertEquals("https://rad.freedom.baby/_/invalid?id=not-a-rid%2Fx", url)
        assertNull(RadUrl.displayUrlFor(url))
        assertNull(RadUrl.toVirtualUrl("https://example.com"))
        assertNull(RadUrl.parse("rad:0OIl${bare.drop(4)}"))
    }

    @Test
    fun `only the exact https host is the viewer's`() {
        assertTrue(RadUrl.isVirtualUrl("https://rad.freedom.baby/"))
        assertTrue(RadUrl.isVirtualUrl("https://rad.freedom.baby"))
        assertFalse(RadUrl.isVirtualUrl("https://rad.freedom.baby.evil.com/"))
        assertFalse(RadUrl.isVirtualUrl("https://rad.freedom.baby:8443/"))
        assertFalse(RadUrl.isVirtualUrl("http://rad.freedom.baby/"))
        assertFalse(RadUrl.isVirtualUrl("https://x@rad.freedom.baby/"))
        assertNull(RadUrl.displayUrlFor("https://rad.freedom.baby/_/api/$bare"))
    }

    @Test
    fun `address bar sees rad as a dweb address and a link to one is detoured`() {
        assertEquals(AddressInput.Kind.Dweb, AddressInput.classify("rad:$bare"))
        assertEquals("Open on Radicle", AddressAction.Go("rad://$bare", AddressInput.Kind.Dweb).subtitle)
        assertTrue(submitDetourForNavigation("rad:$bare", isForMainFrame = true))
        assertTrue(submitDetourForNavigation("rad://$bare", isForMainFrame = true))
        assertFalse(submitDetourForNavigation("rad://$bare", isForMainFrame = false))
    }

    // ---- API paths ----

    @Test
    fun `api paths are split before decoding and refuse traversal`() {
        val ok = RadApi.parseApiPath("$bare/blob/$rev/a%20b/c%25.md?x=1&x=2")!!
        assertEquals(rid, ok.rid)
        assertEquals(listOf("blob", rev, "a b", "c%.md"), ok.segments)
        assertEquals("1", ok.query["x"])
        assertEquals(listOf("tree"), RadApi.parseApiPath("$bare/tree/")!!.segments)
        for (bad in listOf(
            "$bare/tree/$rev/../x", "$bare/tree/$rev/%2e%2e/x", "$bare/tree/$rev/a%2Fb",
            "$bare/tree//x", "$bare/tree/$rev/a\\b", "$bare/tree/$rev/a%5Cb", "$bare/tree/$rev/%00",
            "$bare/tree/$rev/%zz", "zbad/tree", "",
        )) {
            assertNull(bad, RadApi.parseApiPath(bad))
        }
    }

    private class FakeNode(
        private val visibility: String = "public",
        private val answers: Map<String, Any> = emptyMap(),
    ) : RadApi.Backend {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        override fun call(method: String, args: JSONObject): RadicleClient.Answer {
            calls += method to args
            if (method == "repoInfo") {
                return RadicleClient.Answer.Ok(
                    JSONObject().put("rid", args.getString("rid")).put("name", "demo").put("head", "h")
                        .put("issuesOpen", 2).put("visibility", JSONObject().put("type", visibility)),
                )
            }
            return when (val a = answers[method]) {
                null -> RadicleClient.Answer.Failed("$method not found", null)
                is RadicleClient.Answer -> a
                else -> RadicleClient.Answer.Ok(a)
            }
        }
    }

    private fun body(reply: RadApi.Reply) = String(reply.body)

    @Test
    fun `repository root is httpd-shaped and CORS-open`() {
        val node = FakeNode(answers = mapOf("seeders" to JSONObject().put("seeding", 7)))
        val reply = RadApi.serveApiWith(bare, node)
        assertEquals(200, reply.status)
        assertEquals("*", reply.headers["Access-Control-Allow-Origin"])
        val json = JSONObject(body(reply))
        val project = json.getJSONObject("payloads").getJSONObject("xyz.radicle.project")
        assertEquals("demo", project.getJSONObject("data").getString("name"))
        assertEquals(2, project.getJSONObject("meta").getJSONObject("issues").getInt("open"))
        assertEquals(7, json.getInt("seeding"))
    }

    @Test
    fun `a private repository is refused before anything else is read`() {
        val node = FakeNode(visibility = "private", answers = mapOf("issues" to JSONArray()))
        assertEquals(403, RadApi.serveApiWith("$bare/issues", node).status)
        assertEquals(listOf("repoInfo"), node.calls.map { it.first })
    }

    @Test
    fun `revisions must be full commit ids and endpoints map to node calls`() {
        val node = FakeNode(answers = mapOf("treeAt" to JSONObject().put("entries", JSONArray()), "blobAt" to JSONObject()))
        assertEquals(400, RadApi.serveApiWith("$bare/tree/master", node).status)
        assertEquals(400, RadApi.serveApiWith("$bare/tree/${rev.take(7)}", node).status)
        assertEquals(200, RadApi.serveApiWith("$bare/tree/$rev/src/main", node).status)
        assertEquals("src/main", node.calls.last().second.getString("path"))
        assertEquals(400, RadApi.serveApiWith("$bare/blob/$rev", node).status)
        assertEquals(200, RadApi.serveApiWith("$bare/blob/$rev/README.md", node).status)
        assertEquals(404, RadApi.serveApiWith("$bare/sessions", node).status)
        assertEquals(400, RadApi.serveApiWith("$bare/issues/NOTHEX", node).status)
    }

    @Test
    fun `node not-found maps to 404 and other failures to 500`() {
        val node = FakeNode(answers = mapOf("issue" to RadicleClient.Answer.Failed("boom", null)))
        assertEquals(404, RadApi.serveApiWith("$bare/patches/abcdef", node).status)
        assertEquals(500, RadApi.serveApiWith("$bare/issues/abcdef", node).status)
    }

    @Test
    fun `issue lists filter by status and paginate`() {
        val items = JSONArray((1..45).map { i ->
            JSONObject().put("id", "%06x".format(i)).put("state", JSONObject().put("status", if (i % 3 == 0) "closed" else "open"))
        })
        val node = FakeNode(answers = mapOf("issues" to items))
        assertEquals(30, JSONArray(body(RadApi.serveApiWith("$bare/issues?status=open", node))).length())
        assertEquals(0, JSONArray(body(RadApi.serveApiWith("$bare/issues?status=open&page=1&perPage=30", node))).length())
        assertEquals(15, JSONArray(body(RadApi.serveApiWith("$bare/issues?status=closed", node))).length())
        assertEquals(100 to 100, RadApi.pageParams(mapOf("page" to "100", "perPage" to "500")))
    }

    @Test
    fun `readme is the first candidate blob at the root`() {
        val tree = JSONObject().put(
            "entries",
            JSONArray()
                .put(JSONObject().put("name", "README").put("kind", "blob"))
                .put(JSONObject().put("name", "README.md").put("kind", "tree"))
                .put(JSONObject().put("name", "README.txt").put("kind", "blob")),
        )
        val node = FakeNode(answers = mapOf("treeAt" to tree, "blobAt" to JSONObject().put("content", "hi")))
        val reply = RadApi.serveApiWith("$bare/readme/$rev", node)
        assertEquals("README.txt", JSONObject(body(reply)).getString("path"))
        val none = FakeNode(answers = mapOf("treeAt" to JSONObject().put("entries", JSONArray())))
        assertEquals(404, RadApi.serveApiWith("$bare/readme/$rev", none).status)
    }

    @Test
    fun `viewer pages carry a CSP that only runs their own script, and only GET HEAD reach the API`() {
        val page = RadApi.serve("GET", "https://rad.freedom.baby/$bare/issues", FakeNode())
        assertEquals(200, page.status)
        assertTrue(page.mime.startsWith("text/html"))
        val csp = page.headers["Content-Security-Policy"]!!
        assertTrue(csp.contains("script-src 'self'"))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertEquals(405, RadApi.serve("POST", "https://rad.freedom.baby/_/api/$bare", FakeNode()).status)
        assertEquals(204, RadApi.serve("OPTIONS", "https://rad.freedom.baby/_/api/$bare", FakeNode()).status)
        assertEquals(404, RadApi.serve("GET", "https://rad.freedom.baby/_/secret", FakeNode()).status)
        // Radicle is off in a unit test: the API says so, with its reason.
        val off = RadApi.serve("GET", "https://rad.freedom.baby/_/api/$bare", FakeNode())
        assertEquals(403, off.status)
        assertEquals(RadicleClient.REASON_DISABLED, JSONObject(body(off)).getString("reason"))
        assertEquals(0, RadApi.serve("HEAD", "https://rad.freedom.baby/$bare", FakeNode()).body.size)
    }

    // ---- The client's parsing ----

    @Test
    fun `node answers parse, errors carry their reason, and deep nesting doesn't crash`() {
        assertTrue(RadicleClient.parseAnswer("[1]") is RadicleClient.Answer.Ok)
        val failed = RadicleClient.parseAnswer("""{"error":"nope","reason":"node-stopped"}""") as RadicleClient.Answer.Failed
        assertEquals("node-stopped", failed.reason)
        assertTrue(RadicleClient.parseAnswer("[".repeat(200_000)) is RadicleClient.Answer.Failed)
        assertTrue(RadicleClient.parseAnswer("") is RadicleClient.Answer.Failed)
        assertNotNull(RadicleClient.unavailableReason(false, true, baby.freedom.swarm.RadicleStatus.Running))
        assertNull(RadicleClient.unavailableReason(true, true, baby.freedom.swarm.RadicleStatus.Running))
        assertEquals(
            RadicleClient.REASON_NOT_READY,
            RadicleClient.unavailableReason(true, true, baby.freedom.swarm.RadicleStatus.Starting),
        )
        assertEquals(
            RadicleClient.REASON_STOPPED,
            RadicleClient.unavailableReason(true, false, baby.freedom.swarm.RadicleStatus.Running),
        )
    }
}
