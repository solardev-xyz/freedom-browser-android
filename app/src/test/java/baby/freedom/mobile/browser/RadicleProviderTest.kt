package baby.freedom.mobile.browser

import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleNode
import baby.freedom.swarm.RadicleSeed
import baby.freedom.swarm.RadicleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context as RhinoContext

private const val ME = "did:key:z6MkMe"

/** The `window.radicle` provider's tiers, prompts and checks (#124). */
class RadicleProviderTest {
    private val bare = "z4V1sjrXqjvFdnCUbxPFqd5p4DtH5"
    private val rid = "rad:$bare"
    private val site = "https://app.example"

    /** origin → the DID it may sign as, `""` for connection only. */
    private class MemoryGrants : RadicleProvider.Grants {
        val map = HashMap<String, String>()
        /** origin → the DID it could sign as before the identity changed. */
        val before = HashMap<String, String>()
        override suspend fun signingFor(origin: String) = map[origin]
        override suspend fun signedBefore(origin: String) = map[origin]?.ifEmpty { null } ?: before[origin]
        override suspend fun connect(origin: String): Boolean {
            if (origin !in map) map[origin] = ""
            return true
        }
        override suspend fun grantSigning(origin: String, did: String): Boolean {
            if (origin !in map) return false
            map[origin] = did
            return true
        }
        override suspend fun revoke(origin: String): Boolean {
            map.remove(origin)
            return true
        }
    }

    private class FakeNode : RadicleProvider.Node {
        override val state = MutableStateFlow(RadicleInfo(status = RadicleStatus.Running))
        var reason: String? = null
        var seeded = JSONArray()
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val seeds = mutableListOf<String>()
        val unseeds = mutableListOf<String>()
        var writeAnswer: RadicleClient.Answer = RadicleClient.Answer.Ok(JSONObject().put("id", "abcdef1234"))
        /** The identity the node runs as; [onIdentity] runs after each identity read. */
        var did = ME
        var onIdentity: () -> Unit = {}
        override fun unavailableReason() = reason
        override fun call(method: String, args: JSONObject, timeoutMs: Long): RadicleClient.Answer {
            calls += method to args
            // `:node`'s own check (RadicleNode.call).
            val asDid = args.optString(RadicleNode.AS_DID)
            if (asDid.isNotEmpty() && asDid != did) {
                return RadicleClient.Answer.Failed("Radicle identity changed", RadicleClient.REASON_IDENTITY_CHANGED)
            }
            return when (method) {
                "identity" -> RadicleClient.Answer.Ok(
                    JSONObject().put("did", did).put("nid", did.removePrefix("did:key:")).put("alias", "me"),
                ).also { onIdentity() }
                "status" -> RadicleClient.Answer.Ok(JSONObject().put("connectedPeers", 5))
                "listSeededRepos" -> RadicleClient.Answer.Ok(seeded)
                "repoInfo" -> RadicleClient.Answer.Failed("repository not found", null)
                "createIssue", "commentIssue", "editIssueState", "commentPatch" -> writeAnswer
                else -> RadicleClient.Answer.Failed("unexpected $method", null)
            }
        }
        override fun seed(rid: String): Boolean {
            seeds += rid
            return true
        }
        override fun unseed(rid: String): Boolean {
            unseeds += rid
            return true
        }
    }

    private val grants = MemoryGrants()
    private val node = FakeNode()
    private var now = 1_000_000L
    private val provider = RadicleProvider(grants, node, clock = { now }, io = Dispatchers.Unconfined)
    private val asked = mutableListOf<RadicleAsk>()
    private var answer = true

    private fun req(method: String, params: JSONObject = JSONObject(), origin: String = site) = runBlocking {
        provider.request(origin, method, params) { a ->
            asked += a
            answer
        }
    }

    private fun err(r: RadicleProvider.Reply) = r as RadicleProvider.Reply.Err
    private fun ok(r: RadicleProvider.Reply) = (r as RadicleProvider.Reply.Ok).value

    @Test
    fun `capabilities need nothing and say why the node can't be used`() {
        val caps = ok(req("radicle_getCapabilities")) as JSONObject
        assertEquals("0.2", caps.getString("specVersion"))
        assertFalse(caps.getBoolean("canUseNode"))
        assertEquals("not-connected", caps.getString("reason"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `everything but capabilities and requestAccess needs a connection`() {
        for (m in RadicleProvider.TIERS.keys - setOf("radicle_getCapabilities", "radicle_requestAccess")) {
            val e = err(req(m, JSONObject().put("rid", rid)))
            assertEquals(m, RadicleProvider.UNAUTHORIZED, e.code)
        }
        assertTrue(asked.isEmpty())
        assertEquals(RadicleProvider.UNSUPPORTED, err(req("radicle_importRepo")).code)
    }

    @Test
    fun `requestAccess asks once, remembers, and a refusal is 4001`() {
        answer = false
        assertEquals(RadicleProvider.USER_REJECTED, err(req("radicle_requestAccess")).code)
        answer = true
        val events = mutableListOf<String>()
        provider.events = RadicleProvider.Events { o, e, _ -> events += "$o $e" }
        val r = ok(req("radicle_requestAccess")) as JSONObject
        assertTrue(r.getBoolean("connected"))
        assertTrue(r.getJSONObject("capabilities").getBoolean("canUseNode"))
        req("radicle_requestAccess")
        assertEquals(2, asked.size)
        assertEquals(listOf("$site connect"), events)
    }

    @Test
    fun `turned-off Radicle is 4900 for everything, and a stopped node for node methods`() {
        grants.map[site] = ""
        node.reason = RadicleClient.REASON_DISABLED
        assertEquals(RadicleProvider.UNAVAILABLE, err(req("radicle_getCapabilities")).code)
        node.reason = RadicleClient.REASON_STOPPED
        assertEquals(RadicleProvider.UNAVAILABLE, err(req("radicle_listSeededRepos")).code)
        // These work while it's stopped.
        assertFalse((ok(req("radicle_getNodeStatus")) as JSONObject).getBoolean("running"))
        assertFalse((ok(req("radicle_disconnect")) as JSONObject).getBoolean("connected"))
    }

    @Test
    fun `node status hides the NID until signing`() {
        grants.map[site] = ""
        val s = ok(req("radicle_getNodeStatus")) as JSONObject
        assertEquals(5, s.getInt("peers"))
        assertEquals("me", s.getString("alias"))
        assertFalse(s.has("nid"))
        grants.map[site] = ME
        assertEquals("z6MkMe", (ok(req("radicle_getNodeStatus")) as JSONObject).getString("nid"))
    }

    @Test
    fun `seed asks per repository, validates first, and refuses a second repo while one fetches`() {
        grants.map[site] = ""
        assertEquals("invalid_rid", err(req("radicle_seed", JSONObject().put("rid", "nope"))).reason)
        assertEquals("invalid_rid", err(req("radicle_seed", JSONObject().put("rid", " $rid"))).reason)
        assertTrue(asked.isEmpty())
        answer = false
        assertEquals(RadicleProvider.USER_REJECTED, err(req("radicle_seed", JSONObject().put("rid", rid))).code)
        assertTrue(node.seeds.isEmpty())
        answer = true
        val r = ok(req("radicle_seed", JSONObject().put("rid", "rad://$bare"))) as JSONObject
        assertTrue(r.getBoolean("seeded"))
        assertEquals("fetching", r.getJSONObject("status").getString("state"))
        assertEquals(listOf(rid), node.seeds)
        assertEquals(RadicleAsk.Seed(site, rid), asked.last())
        node.state.value = RadicleInfo(status = RadicleStatus.Running, seed = RadicleSeed(rid, "fetching"))
        val other = "rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5"
        assertEquals("busy", err(req("radicle_seed", JSONObject().put("rid", other))).reason)
    }

    @Test
    fun `seed status follows the node's seed line and pushes events to followers`() {
        grants.map[site] = ""
        val events = mutableListOf<JSONObject>()
        provider.events = RadicleProvider.Events { _, e, d -> if (e == "seedStatus") events += d as JSONObject }
        runBlocking {
            val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
            provider.start(scope)
            req("radicle_seed", JSONObject().put("rid", rid))
            node.state.value = RadicleInfo(status = RadicleStatus.Running, seed = RadicleSeed(rid, "connecting", "a (1/3)"))
            node.state.value = RadicleInfo(status = RadicleStatus.Running, seed = RadicleSeed(rid, "failed", "no seeds found", active = false))
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        assertEquals(listOf("fetching", "failed"), events.map { it.getString("state") })
        val last = events.last()
        assertEquals("no seeds found", last.getString("lastError"))
        assertEquals(1, last.getInt("attemptCount"))
        assertFalse(last.isNull("finishedAt"))
    }

    @Test
    fun `a site disconnected from the Radicle page stops hearing seedStatus`() {
        grants.map[site] = ""
        grants.map["https://b.example"] = ""
        val heard = mutableListOf<String>()
        provider.events = RadicleProvider.Events { o, e, _ -> if (e == "seedStatus") heard += o }
        runBlocking {
            val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
            provider.start(scope)
            req("radicle_seed", JSONObject().put("rid", rid))
            req("radicle_getSeedStatus", JSONObject().put("rid", rid), origin = "https://b.example")
            node.state.value = RadicleInfo(status = RadicleStatus.Running, seed = RadicleSeed(rid, "connecting", "a (1/3)"))
            assertEquals(setOf(site, "https://b.example"), heard.toSet())
            // The Radicle page's Disconnect: the store drops the grant, then
            // the bridge tells the provider (MainActivity.onRadicleRevoke).
            heard.clear()
            grants.revoke(site)
            provider.forget(site)
            node.state.value = RadicleInfo(status = RadicleStatus.Running, seed = RadicleSeed(rid, "fetching", "b (2/3)"))
            assertEquals(listOf("https://b.example"), heard)
            // A grant dropped without forget() is caught at emit time too.
            heard.clear()
            grants.revoke("https://b.example")
            node.state.value = RadicleInfo(status = RadicleStatus.Running, seed = RadicleSeed(rid, "done", "", active = false))
            assertTrue(heard.isEmpty())
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `sync is only for repositories already seeded`() {
        grants.map[site] = ""
        assertEquals("not_seeded", err(req("radicle_sync", JSONObject().put("rid", rid))).reason)
        assertTrue(node.seeds.isEmpty())
        node.seeded = JSONArray().put(JSONObject().put("rid", rid))
        assertTrue((ok(req("radicle_sync", JSONObject().put("rid", rid))) as JSONObject).has("status"))
        assertEquals(listOf(rid), node.seeds)
        assertTrue("sync never prompts", asked.isEmpty())
    }

    @Test
    fun `unseed asks, then drops the policy`() {
        grants.map[site] = ""
        val r = ok(req("radicle_unseed", JSONObject().put("rid", rid))) as JSONObject
        assertFalse(r.getBoolean("seeded"))
        assertEquals(listOf(RadicleAsk.Unseed(site, rid)), asked)
        assertEquals(listOf(rid), node.unseeds)
    }

    @Test
    fun `signing is asked once, after the parameters check out`() {
        grants.map[site] = ""
        val bad = err(req("radicle_createIssue", JSONObject().put("rid", rid).put("title", " ").put("description", "d")))
        assertEquals("invalid_title", bad.reason)
        assertTrue("no prompt for a request that would fail", asked.isEmpty())
        answer = false
        assertEquals(RadicleProvider.USER_REJECTED, err(req("radicle_getIdentity")).code)
        assertEquals("", grants.map[site])
        answer = true
        assertEquals("did:key:z6MkMe", (ok(req("radicle_getIdentity")) as JSONObject).getString("did"))
        val issue = JSONObject().put("rid", rid).put("title", "t").put("description", "d").put("labels", JSONArray().put("bug"))
        assertEquals("abcdef1234", (ok(req("radicle_createIssue", issue)) as JSONObject).getString("id"))
        assertEquals(2, asked.size)
        val call = node.calls.last { it.first == "createIssue" }.second
        assertEquals("""["bug"]""", call.getString("labelsJson"))
        // Made as the identity the user allowed; `:node` refuses it as any other.
        assertEquals(ME, call.getString(RadicleNode.AS_DID))
    }

    @Test
    fun `a signing grant covers only the identity it was given for`() {
        // Allowed to sign as the device's own identity (#328)…
        grants.map[site] = "did:key:z6MkDevice"
        val s = ok(req("radicle_getNodeStatus")) as JSONObject
        assertFalse("the NID of an identity the site wasn't allowed to know", s.has("nid"))
        // …and the node now runs as the wallet's: the site is asked again
        // before it learns or writes as it, and nothing is written on a refusal.
        answer = false
        val issue = JSONObject().put("rid", rid).put("title", "t").put("description", "d")
        assertEquals(RadicleProvider.USER_REJECTED, err(req("radicle_createIssue", JSONObject(issue.toString()))).code)
        assertEquals(RadicleProvider.USER_REJECTED, err(req("radicle_getIdentity")).code)
        assertTrue(node.calls.none { it.first == "createIssue" })
        // Each prompt names the identity asked about and the one allowed before.
        val changed = RadicleAsk.Signing(site, ME, wallet = false, previousDid = "did:key:z6MkDevice")
        assertEquals(listOf(changed, changed), asked)
        assertTrue(radiclePromptCopy(changed).detail.contains("z6MkDe"))
        assertEquals(ME, radiclePromptIdentity(changed)?.second)
        assertEquals("did:key:z6MkDevice", grants.map[site])
        answer = true
        assertEquals(ME, (ok(req("radicle_getIdentity")) as JSONObject).getString("did"))
        assertEquals(ME, grants.map[site])
        ok(req("radicle_createIssue", JSONObject(issue.toString())))
        assertEquals(3, asked.size)
    }

    @Test
    fun `an identity change while the prompt is up grants nothing`() {
        grants.map[site] = ""
        // The node restarts as another identity right after the first read.
        node.onIdentity = {
            node.did = "did:key:z6MkOther"
            node.onIdentity = {}
        }
        val e = err(req("radicle_getIdentity"))
        assertEquals("identity_changed", e.reason)
        assertEquals("", grants.map[site])
    }

    @Test
    fun `a write the node refuses as another identity is identity_changed`() {
        grants.map[site] = ME
        // Restarted between the identity read and the write.
        node.onIdentity = { node.did = "did:key:z6MkOther" }
        val c = JSONObject().put("rid", rid).put("issueId", "abcdef").put("body", "b")
        val e = err(req("radicle_commentIssue", c))
        assertEquals(RadicleProvider.UNAUTHORIZED, e.code)
        assertEquals("identity_changed", e.reason)
    }

    @Test
    fun `write parameters follow desktop's limits`() {
        fun reason(method: String, p: JSONObject) = (provider.validateWrite(method, p) as? RadicleProvider.Validated.Bad)?.error?.reason
        val base = JSONObject().put("rid", rid)
        assertEquals("payload_too_large", reason("radicle_createIssue", JSONObject(base.toString()).put("title", "é".repeat(101)).put("description", "d")))
        assertNull(reason("radicle_createIssue", JSONObject(base.toString()).put("title", "x".repeat(200)).put("description", "d")))
        assertEquals("invalid_labels", reason("radicle_createIssue", JSONObject(base.toString()).put("title", "t").put("description", "d").put("labels", JSONArray((1..11).map { "l$it" }))))
        assertEquals("invalid_id", reason("radicle_commentIssue", JSONObject(base.toString()).put("issueId", "XYZ").put("body", "b")))
        assertEquals("invalid_id", reason("radicle_commentIssue", JSONObject(base.toString()).put("issueId", "abcdef").put("body", "b").put("replyTo", 5)))
        assertEquals("invalid_state", reason("radicle_editIssueState", JSONObject(base.toString()).put("issueId", "abcdef").put("state", "wontfix")))
        val patch = provider.validateWrite("radicle_commentPatch", JSONObject(base.toString()).put("patchId", "abcdef").put("body", "b"))
        assertEquals("abcdef", (patch as RadicleProvider.Validated.Ok).args.getString("revisionId"))
    }

    @Test
    fun `writes are rate limited per origin`() {
        grants.map[site] = ME
        val c = JSONObject().put("rid", rid).put("issueId", "abcdef").put("body", "b")
        repeat(RadicleProvider.MAX_WRITES_PER_MINUTE) { ok(req("radicle_commentIssue", JSONObject(c.toString()))) }
        assertEquals("rate_limited", err(req("radicle_commentIssue", JSONObject(c.toString()))).reason)
        grants.map["https://other.example"] = ME
        ok(req("radicle_commentIssue", JSONObject(c.toString()), origin = "https://other.example"))
        now += RadicleProvider.WRITE_WINDOW_MS
        ok(req("radicle_commentIssue", JSONObject(c.toString())))
    }

    @Test
    fun `native errors keep their reasons`() {
        grants.map[site] = ME
        node.writeAnswer = RadicleClient.Answer.Failed("announce refs failed: timeout", null)
        val c = JSONObject().put("rid", rid).put("issueId", "abcdef").put("state", "closed")
        assertEquals("announce_failed", err(req("radicle_editIssueState", c)).reason)
        node.writeAnswer = RadicleClient.Answer.Ok(JSONObject())
        assertEquals("native_failed", err(req("radicle_editIssueState", JSONObject(c.toString()))).reason)
    }

    // ---- The channel ----

    @Test
    fun `requests parse strictly`() {
        assertEquals("radicle_seed", parseRadicleRequest("""{"id":3,"method":"radicle_seed","params":{"rid":"x"}}""")!!.method)
        assertEquals(0, parseRadicleRequest("""{"id":1,"method":"m"}""")!!.params.length())
        assertNull(parseRadicleRequest("""{"method":"m"}"""))
        assertNull(parseRadicleRequest("""{"id":1,"method":"m","params":[1]}"""))
        assertNull(parseRadicleRequest("[" .repeat(100_000)))
        assertNull(parseRadicleRequest(null))
    }

    @Test
    fun `only secure origins get the provider, never the repository browser`() {
        assertEquals("https://app.example", providerOriginKey("https://App.Example:443/"))
        assertEquals("http://localhost:8700", providerOriginKey("http://localhost:8700"))
        assertEquals("http://127.0.0.1", providerOriginKey("http://127.0.0.1/"))
        assertEquals("http://[::1]:3000", providerOriginKey("http://[::1]:3000"))
        assertNull(providerOriginKey("http://example.com"))
        assertNull(providerOriginKey("http://127.evil.example"))
        assertNull(providerOriginKey("https://rad.freedom.baby"))
        assertNull(providerOriginKey("null"))
        assertNull(providerOriginKey("file:///x"))
    }

    @Test
    fun `a late message from the outgoing document is not the new page's`() {
        assertEquals(3, radicleDocumentFor(3, site, providerOriginKey("$site/next")))
        assertEquals(STALE_DOCUMENT, radicleDocumentFor(3, site, providerOriginKey("https://other.example/")))
        assertEquals(STALE_DOCUMENT, radicleDocumentFor(3, site, null))
        assertEquals(3, radicleDocumentFor(3, "http://localhost:8700", providerOriginKey("http://localhost:8700/a")))
    }

    @Test
    fun `the page on screen is still current while a load it didn't start is pending`() {
        // #201 R2-F2: the tab committed [site]; the user typed another
        // address, which `WebView.getUrl()` already reports, but it hasn't
        // committed. The page on screen still asks as the current document.
        val committed = providerOriginKey("$site/app")
        assertEquals(4, radicleDocumentFor(4, site, committed))
        // Once the other address commits, the old page is stale.
        assertEquals(STALE_DOCUMENT, radicleDocumentFor(5, site, providerOriginKey("https://other.example/")))
    }

    @Test
    fun `with Radicle off, later documents lose the channel and get no provider`() {
        val cx = RhinoContext.enter()
        try {
            cx.languageVersion = RhinoContext.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "var window = { abcdefghij: { postMessage: function () {} } };", "setup", 1, null)
            cx.evaluateString(scope, radicleChannelClosingJs("abcdefghij"), "closing.js", 1, null)
            assertEquals(
                "undefined undefined",
                cx.evaluateString(scope, "typeof window.abcdefghij + ' ' + typeof window.radicle", "check", 1, null),
            )
        } finally {
            RhinoContext.exit()
        }
        assertTrue(runCatching { radicleChannelClosingJs("a'b") }.isFailure)
    }

    @Test
    fun `the page script compiles and refuses a bad channel name`() {
        val cx = RhinoContext.enter()
        try {
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.compileString(radicleProviderJs("abcdefghij"), "radicle.js", 1, null)
        } finally {
            RhinoContext.exit()
        }
        assertTrue(runCatching { radicleProviderJs("a'b") }.isFailure)
    }

    @Test
    fun `the radicle prompt takes turns with the others`() {
        assertEquals(PromptTurn.Radicle, modalPromptTurn(false, false, false, false, radicleWaiting = true))
        assertEquals(PromptTurn.SitePermission, modalPromptTurn(true, false, false, false, radicleWaiting = true))
        assertEquals(PromptTurn.Radicle, modalPromptTurn(true, true, false, false, radicleWaiting = true, radicleHasTurn = true))
        assertEquals(PromptTurn.DownloadOffer, modalPromptTurn(false, true, true, false, radicleWaiting = true, radicleHasTurn = true))
        assertEquals(PromptTurn.None, modalPromptTurn(false, false, false, true, radicleWaiting = true, radicleHasTurn = true))
    }

    @Test
    fun `a site whose grant was taken back at an identity change hears it's a different identity`() {
        // dropSigning ran at the wallet import: connection tier, signed as the device's.
        grants.map[site] = ""
        grants.before[site] = "did:key:z6MkDevice"
        node.state.value = node.state.value.copy(walletIdentity = true)
        answer = false
        err(req("radicle_getIdentity"))
        val ask = asked.single() as RadicleAsk.Signing
        assertEquals(RadicleAsk.Signing(site, ME, wallet = true, previousDid = "did:key:z6MkDevice"), ask)
        val copy = radiclePromptCopy(ask)
        assertEquals("wants to act as your new Radicle identity", copy.request)
        assertTrue(copy.detail.contains("can link the two"))
        assertTrue(radiclePromptIdentity(ask)!!.first.startsWith("Your wallet's identity"))
        // One that was never allowed another gets the plain prompt, still naming the identity.
        asked.clear()
        grants.before.clear()
        node.state.value = node.state.value.copy(walletIdentity = false)
        err(req("radicle_getIdentity"))
        val plain = asked.single() as RadicleAsk.Signing
        assertNull(plain.previousDid)
        assertEquals("wants to act as you on Radicle", radiclePromptCopy(plain).request)
        assertEquals("This device's own identity" to ME, radiclePromptIdentity(plain))
        // Nor is the identity it was allowed before a "change".
        asked.clear()
        grants.before[site] = ME
        err(req("radicle_getIdentity"))
        assertNull((asked.single() as RadicleAsk.Signing).previousDid)
    }

    @Test
    fun `prompt copy names what each ask means`() {
        assertEquals("Connect", radiclePromptCopy(RadicleAsk.Connect(site)).allow)
        assertTrue(radiclePromptCopy(RadicleAsk.Signing(site)).detail.contains("can't be taken back"))
    }
}
