package baby.freedom.mobile.agent

import baby.freedom.mobile.node.NodeLogSource
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleStatus
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NodeToolsTest {
    // ---- canonical paths -------------------------------------------------

    /**
     * Desktop's `canonicalNodeRequestPath` on node 22 (`origin/main`,
     * `operations.js`), input → output; null: refused.
     */
    private val desktop = listOf(
        "/health" to "/health",
        "/bzz/abc/index.html" to "/bzz/abc/index.html",
        // Double slashes: refused at the start (another host), kept inside.
        "//evil" to null,
        "//stamps" to null,
        "/a//b" to "/a//b",
        // Dot segments, plain and encoded.
        "/a/../b" to null,
        "/a/./b" to null,
        "/%2e%2e/stamps" to null,
        "/a/%2E/b" to null,
        "/a/%2e" to null,
        "/a/..b" to "/a/..b",
        "/a/.b/" to "/a/.b/",
        // Encoded separators and percent.
        "/a%2fb" to null,
        "/x%2F?y=%2F" to null,
        "/a%5cb" to null,
        "/a%25b" to null,
        // Stray percent.
        "/a%2" to null,
        "/a%zz" to null,
        "/a?%" to null,
        "/a?q=%zz" to null,
        // Unreserved escapes decoded, others upper-cased (route only).
        "/%73tamps" to "/stamps",
        "/%7e~" to "/~~",
        "/%41%62" to "/Ab",
        "/A%7EB" to "/A~B",
        "/a%5F" to "/a_",
        "/a%2D" to "/a-",
        "/a%3a" to "/a%3A",
        "/a%7f" to "/a%7F",
        "/x%3F" to "/x%3F",
        "/x%23" to "/x%23",
        "/a?x=%2f.." to "/a?x=%2f..",
        "/a?x=%41" to "/a?x=%41",
        // WHATWG's encode sets.
        "/a b" to "/a%20b",
        "/a\"b" to "/a%22b",
        "/a^b" to "/a%5Eb",
        "/a`b" to "/a%60b",
        "/a{b}" to "/a%7Bb%7D",
        "/a<b>" to "/a%3Cb%3E",
        "/a'b" to "/a'b",
        // Desktop keeps `|`; OkHttp would encode it, so it is encoded here (R1-F3).
        "/a|b" to "/a%7Cb",
        "/a;b" to "/a;b",
        "/a@b" to "/a@b",
        "/a!\$&()*+,;=:@~" to "/a!\$&()*+,;=:@~",
        "/ä" to "/%C3%A4",
        "/€" to "/%E2%82%AC",
        "/%F0%9F%98%80" to "/%F0%9F%98%80",
        "/a?ä" to "/a?%C3%A4",
        "/a?x='" to "/a?x=%27",
        "/a?x=\"" to "/a?x=%22",
        "/a?<>" to "/a?%3C%3E",
        "/a?{}^`|~!\$&()*+,;=:@/" to "/a?{}^`|~!\$&()*+,;=:@/",
        "/a?x=a b" to "/a?x=a%20b",
        "/a b?c d" to "/a%20b?c%20d",
        "/a?b?c" to "/a?b?c",
        "/a?" to "/a",
        "/stamps?x=1&y=2" to "/stamps?x=1&y=2",
        // Fragments, backslashes, controls; JavaScript's trim.
        "/a#b" to null,
        "/a\\b" to null,
        "/a\tb" to null,
        "  /health  " to "/health",
        " /a " to "/a",
        "﻿/a" to "/a",
        "/ " to "/",
        "health" to null,
    )

    @Test
    fun `paths come out as desktop's canonicalNodeRequestPath makes them`() {
        for ((input, expected) in desktop) {
            val got = try {
                NodePaths.canonical(input)
            } catch (e: ToolError) {
                assertEquals(ToolError.INVALID_ARGUMENT, e.code)
                null
            }
            assertEquals("canonical(${JSONObject.quote(input)})", expected, got)
        }
    }

    @Test
    fun `the path the sheet shows is the one OkHttp puts on the wire (R1-F3)`() {
        val paths = listOf(
            "/bzz/a|b", "/bzz/a b", "/a\"b", "/a<b>", "/a`b", "/a{b}", "/a^b", "/a[b]", "/a;b,c=d", "/a@b:c", "/a!$&'()*+",
            "/😀/é", "/a%7cb", "/a%41", "/a?q=é", "/a?x=1&y=|", "/a?q=a b'c\"<>", "/a?q=%7c|+", "/a?x&&y=", "/a?=", "/a?a=b=c",
        )
        for (input in paths) {
            val canonical = NodePaths.canonical(input)
            val url = with(okhttp3.HttpUrl.Companion) { ("http://127.0.0.1:1633" + canonical).toHttpUrl() }
            val wire = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            assertEquals("canonical(${JSONObject.quote(input)})", canonical, wire)
        }
        assertEquals("/bzz/a%7Cb", NodePaths.canonical("/bzz/a|b"))
    }

    @Test
    fun `a lone surrogate and an over-long path are refused, not guessed at`() {
        for (p in listOf("/a\uD800b", "/a\uDC00", "/" + "a".repeat(2048))) {
            try {
                NodePaths.canonical(p)
                fail("accepted ${JSONObject.quote(p)}")
            } catch (e: ToolError) {
                assertEquals(ToolError.INVALID_ARGUMENT, e.code)
            }
        }
        assertEquals("/%F0%9F%98%80", NodePaths.canonical("/😀"))
    }

    // ---- the spending floor ---------------------------------------------

    @Test
    fun `every write to a spending route is financial, whatever its case, query or encoding`() {
        val spending = listOf(
            "/stamps/1000/20", "/STAMPS/topup/ab/1", "/stamps?x=1", "/stamps", "/Stake/1", "/chequebook/deposit?amount=1",
            "/wallet/withdraw/BZZ", "/transactions/0xab", "/v0/storage/buy", "/V0/Storage/extend", "/v0/settlement/deposit",
        )
        for (path in spending) {
            val p = NodePaths.canonical(path)
            for (m in listOf("POST", "PUT", "PATCH", "DELETE")) {
                assertTrue("$m $path", NodeSpending.isSpendingWrite("ant", m, p))
                assertEquals("$m $path", Effect.FINANCIAL, NodeSpending.minimumEffect("ant", m, p))
            }
        }
        // Decoded before it's matched: an escaped letter is the same route.
        assertTrue(NodeSpending.isSpendingWrite("ant", "POST", NodePaths.canonical("/%73tamps/1/20")))
    }

    @Test
    fun `reads, other routes and other services keep desktop's ordinary floor`() {
        assertFalse(NodeSpending.isSpendingWrite("ant", "GET", "/stamps"))
        assertEquals(Effect.READ, NodeSpending.minimumEffect("ant", "GET", "/stamps"))
        assertEquals(Effect.READ, NodeSpending.minimumEffect("ant", "HEAD", "/wallet"))
        assertFalse(NodeSpending.isSpendingWrite("ant", "POST", "/stampsx"))
        assertFalse(NodeSpending.isSpendingWrite("ant", "POST", "/bzz/stamps"))
        assertEquals(Effect.REVERSIBLE_ADMIN, NodeSpending.minimumEffect("ant", "POST", "/pins/abc"))
        assertEquals(Effect.DESTRUCTIVE, NodeSpending.minimumEffect("ant", "DELETE", "/pins/abc"))
        assertFalse(NodeSpending.isSpendingWrite("ipfs", "POST", "/stamps"))
    }

    @Test
    fun `the floor holds whatever the classifier says`() {
        val read = EffectClassification(Effect.READ, 1.0, "reads", emptyList(), emptyList())
        val d = EffectPolicy.decide(read, NodeSpending.minimumEffect("ant", "POST", "/stamps/1/20"))
        assertFalse(d.proceed)
        assertEquals(Effect.FINANCIAL, d.effect)
        assertFalse(EffectPolicy.decide(read, NodeSpending.minimumEffect("ant", "POST", "/pins/x")).proceed)
        assertTrue(EffectPolicy.decide(read, NodeSpending.minimumEffect("ant", "GET", "/stamps")).proceed)
    }

    // ---- argument checks ------------------------------------------------

    private fun request(service: String = "ant", method: String = "GET", path: String = "/health", extra: (JSONObject) -> Unit = {}) =
        JSONObject()
            .put("service", service)
            .put("transport", if (service == "ipfs") "gateway" else "http")
            .put("request", JSONObject().put("method", method).put("path", path).also(extra))

    private fun parseError(args: JSONObject): String = try {
        NodeRequestArgs.parse(args)
        fail("accepted $args")
        ""
    } catch (e: ToolError) {
        assertEquals(ToolError.INVALID_ARGUMENT, e.code)
        e.message!!
    }

    @Test
    fun `node_request arguments are checked as desktop's are, with the platform's framing headers refused too`() {
        val ok = NodeRequestArgs.parse(request(method = "post", path = "/pins/x?a=1") { it.put("headers", JSONObject().put("Content-Type", "text/plain")).put("body", "hi") })
        assertEquals("POST", ok.method)
        assertEquals(mapOf("content-type" to "text/plain"), ok.headers)
        assertEquals("hi", ok.body)
        parseError(request().put("transport", "gateway"))
        parseError(request(service = "ipfs", method = "POST"))
        parseError(request(method = "GET") { it.put("body", "x") })
        parseError(request(method = "TRACE"))
        parseError(request(service = "bee"))
        for (h in listOf("Authorization", "cookie", "Host", "origin", "referer", "sec-fetch-mode", "proxy-x", "Connection", "Transfer-Encoding", "content-length", "expect", "upgrade", "Accept-Encoding", "bad name")) {
            parseError(request(method = "POST") { it.put("headers", JSONObject().put(h, "1")) })
        }
        parseError(request(method = "POST") { it.put("headers", JSONObject().put("x-a", "1\r\nHost: evil")) })
        parseError(request(method = "POST") { it.put("headers", JSONObject().put("x-a", "café")) })
        parseError(request(method = "POST") { it.put("headers", JSONObject().put("x-a", "a\u0000b")) })
        parseError(request(method = "POST") { it.put("body", "é".repeat(NodeRequestArgs.MAX_BODY_BYTES / 2 + 1)) })
        parseError(request().put("extra", 1))
        parseError(request(path = "/a/../stamps"))
    }

    // ---- node_request ---------------------------------------------------

    private val running = NodeSnapshot(
        swarm = NodeInfo(status = NodeStatus.Running),
        runNode = true,
        ipfs = IpfsInfo(status = IpfsStatus.Running, gatewayUrl = "http://127.0.0.1:5050"),
        radicle = RadicleInfo(status = RadicleStatus.Running),
        radicleEnabled = true,
        tor = TorInfo(status = TorStatus.Stopped),
        torEnabled = true,
    )

    private class Approvals(
        override var mode: ApprovalMode = ApprovalMode.EveryInteraction,
        var effect: EffectClassification = EffectClassifier.unknown(ClassifierReason.UNAVAILABLE),
    ) : ChatApprovals {
        val classified = mutableListOf<JSONObject>()
        override suspend fun classifyInteraction(input: InteractionInput) = InteractionIntentClassifier.uncertain(ClassifierReason.UNAVAILABLE)
        override suspend fun classifyEffect(domain: String, action: JSONObject, trusted: JSONObject?): EffectClassification {
            classified += action
            return effect
        }
        override val provider: String = "My provider"
    }

    private class Asks(var answer: ApprovalOutcome = ApprovalOutcome.Approved) {
        val asked = mutableListOf<NodeAsk>()
        suspend fun ask(a: NodeAsk): ApprovalOutcome {
            asked += a
            return answer
        }
    }

    private fun deps(
        approvals: ChatApprovals = Approvals(),
        asks: Asks = Asks(),
        snapshot: () -> NodeSnapshot? = { running },
        controls: NodeControls? = null,
        hostUp: Boolean = true,
    ) = NodeToolDeps("chat-1", approvals, { snapshot() }, { controls }, asks::ask, { hostUp })

    private fun json(o: ToolOutcome) = JSONObject(o.output)

    private val sent = mutableListOf<Pair<String, NodeRequestArgs>>()

    private fun answering(response: NodeResponse = NodeResponse(200, "OK", mapOf("content-type" to "application/json"), "{}", 2)) =
        { base: String, args: NodeRequestArgs, _: (okhttp3.Call) -> Unit ->
            sent += base to args
            response
        }

    @Test
    fun `a spending write is refused before any sheet and nothing is sent`() = runBlocking {
        val asks = Asks()
        val ops = NodeOperations(MemoryNodeOperationStore())
        val tool = NodeRequestTool(deps(approvals = Approvals(ApprovalMode.AllowWebsiteInteractions), asks = asks), ops, answering())
        for (path in listOf("/stamps/1000/20", "/%73tamps/1000/20", "/chequebook/deposit?amount=1", "/v0/storage/buy")) {
            val out = tool.run(request(method = "POST", path = path))
            assertTrue(out.isError)
            assertTrue(out.output, out.output.startsWith("[POLICY_DENIED]"))
        }
        assertTrue(asks.asked.isEmpty())
        assertTrue(sent.isEmpty())
        assertTrue(ops.store.listRecent("chat-1", 20).isEmpty())
    }

    @Test
    fun `a write always asks, even when the model calls it a read and the mode lets reads run`() = runBlocking {
        val approvals = Approvals(ApprovalMode.AllowWebsiteInteractions, EffectClassification(Effect.READ, 1.0, "reads", emptyList(), emptyList()))
        val asks = Asks()
        val ops = NodeOperations(MemoryNodeOperationStore())
        val out = NodeRequestTool(deps(approvals, asks), ops, answering()).run(request(method = "POST", path = "/pins/abc"))
        assertFalse(out.output, out.isError)
        assertEquals(1, asks.asked.size)
        val kind = asks.asked[0].kind as NodeAskKind.Request
        assertEquals("/pins/abc", kind.args.path)
        assertEquals(Effect.REVERSIBLE_ADMIN, asks.asked[0].effect)
        // Classified on the canonical request.
        assertEquals("/pins/abc", approvals.classified[0].getJSONObject("request").getString("path"))
        val result = json(out).getJSONObject("result")
        assertEquals("responded", result.getJSONObject("operation").getString("state"))
        assertEquals("unsafe", result.getJSONObject("operation").getString("retrySafety"))
        assertEquals(200, result.getJSONObject("response").getInt("status"))
        assertEquals(NodeHttp.ANT_BASE, sent.single().first)
    }

    @Test
    fun `a read asks in every interaction, and runs unasked in a relaxed mode only when confidently a read`() = runBlocking {
        val confident = EffectClassification(Effect.READ, 0.95, "reads health", emptyList(), emptyList())
        // Every interaction: asks, whatever the model says.
        Asks().let { asks ->
            NodeRequestTool(deps(Approvals(ApprovalMode.EveryInteraction, confident), asks), NodeOperations(MemoryNodeOperationStore()), answering())
                .run(request())
            assertEquals(1, asks.asked.size)
        }
        // Relaxed and confident: runs.
        Asks().let { asks ->
            val out = NodeRequestTool(deps(Approvals(ApprovalMode.SensitiveActions, confident), asks), NodeOperations(MemoryNodeOperationStore()), answering())
                .run(request(path = "/%68ealth"))
            assertTrue(asks.asked.isEmpty())
            assertEquals("safe", json(out).getJSONObject("result").getJSONObject("operation").getString("retrySafety"))
            assertEquals("/health", sent.last().second.path)
        }
        // Relaxed and confident, but about the node's own account, wallet or peers: asks.
        for (path in listOf("/wallet", "/addresses", "/peers", "/stamps", "/HEALTH")) {
            val asks = Asks()
            NodeRequestTool(deps(Approvals(ApprovalMode.AllowWebsiteInteractions, confident), asks), NodeOperations(MemoryNodeOperationStore()), answering())
                .run(request(path = path))
            assertEquals(path, 1, asks.asked.size)
        }
        // IPFS content is public too.
        Asks().let { asks ->
            NodeRequestTool(deps(Approvals(ApprovalMode.AllowWebsiteInteractions, confident), asks), NodeOperations(MemoryNodeOperationStore()), answering())
                .run(request(service = "ipfs", path = "/ipfs/bafkqaaa"))
            assertTrue(asks.asked.isEmpty())
        }
        // Relaxed, but unsure (or no model): asks.
        for (c in listOf(confident.copy(confidence = 0.5), confident.copy(uncertainties = listOf("?")), EffectClassifier.unknown())) {
            val asks = Asks()
            NodeRequestTool(deps(Approvals(ApprovalMode.AllowWebsiteInteractions, c), asks), NodeOperations(MemoryNodeOperationStore()), answering())
                .run(request())
            assertEquals(1, asks.asked.size)
        }
    }

    @Test
    fun `declined is the user's no and sends nothing, a withdrawal isn't, and no host means no sheet`() = runBlocking {
        val ops = NodeOperations(MemoryNodeOperationStore())
        val no = NodeRequestTool(deps(asks = Asks(ApprovalOutcome.Declined)), ops, answering()).run(request(method = "DELETE", path = "/pins/x"))
        assertTrue(no.declined)
        assertTrue(no.output.startsWith("[USER_DECLINED]"))
        val gone = NodeRequestTool(deps(asks = Asks(ApprovalOutcome.Withdrawn)), ops, answering()).run(request(method = "DELETE", path = "/pins/x"))
        assertFalse(gone.declined)
        assertTrue(gone.output.startsWith("[APPROVAL_REQUIRED]"))
        val asks = Asks()
        val hostless = NodeRequestTool(deps(asks = asks, hostUp = false), ops, answering()).run(request())
        assertTrue(hostless.output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        assertTrue(asks.asked.isEmpty())
        assertTrue(sent.isEmpty())
        assertTrue(ops.store.listRecent("chat-1", 20).isEmpty())
    }

    @Test
    fun `only the node Freedom runs is reached, and that is checked again after the sheet`() = runBlocking {
        val ops = NodeOperations(MemoryNodeOperationStore())
        for (s in listOf(null, running.copy(externalSwarm = true), running.copy(swarm = NodeInfo(status = NodeStatus.Starting)), running.copy(runNode = false))) {
            val out = NodeRequestTool(deps(snapshot = { s }), ops, answering()).run(request())
            assertTrue(out.output, out.output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        }
        assertTrue(NodeRequestTool(deps(), ops, answering()).run(request(service = "radicle")).output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        assertTrue(
            NodeRequestTool(deps(snapshot = { running.copy(ipfs = IpfsInfo(status = IpfsStatus.Running, gatewayUrl = "http://10.0.0.5:8080")) }), ops, answering())
                .run(request(service = "ipfs", path = "/ipfs/bafy")).output.startsWith("[CAPABILITY_UNAVAILABLE]"),
        )
        // The node stopped while the sheet was up.
        var stopped = false
        val asks = object {
            suspend fun ask(a: NodeAsk): ApprovalOutcome {
                stopped = true
                return ApprovalOutcome.Approved
            }
        }
        val d = NodeToolDeps("chat-1", Approvals(), { if (stopped) running.copy(swarm = NodeInfo(status = NodeStatus.Stopped)) else running }, { null }, asks::ask, { true })
        assertTrue(NodeRequestTool(d, ops, answering()).run(request(method = "POST", path = "/pins/x")).output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        assertTrue(sent.isEmpty())
        // IPFS goes to the embedded node's own gateway.
        NodeRequestTool(deps(), ops, answering()).run(request(service = "ipfs", path = "/ipfs/bafy"))
        assertEquals("http://127.0.0.1:5050", sent.single().first)
    }

    @Test
    fun `a slow write leaves an in-flight receipt the status tool follows to its answer, sent once`() = runBlocking {
        val ops = NodeOperations(MemoryNodeOperationStore())
        val release = CountDownLatch(1)
        var calls = 0
        val slow = { _: String, _: NodeRequestArgs, _: (okhttp3.Call) -> Unit ->
            calls++
            release.await(5, TimeUnit.SECONDS)
            NodeResponse(201, "Created", emptyMap(), "{\"ok\":true}", 11)
        }
        val out = NodeRequestTool(deps(), ops, slow, observeMs = 100).run(request(method = "POST", path = "/pins/abc") { it.put("body", "x") })
        val op = json(out).getJSONObject("result").getJSONObject("operation")
        assertEquals("in_flight", op.getString("state"))
        val id = op.getString("operationId")
        assertTrue(NodeOperations.ID.matches(id))
        val stored = ops.store.get(id)!!
        assertEquals(sha256Hex("x"), stored.bodySha256)
        // Another chat can't read it.
        assertTrue(NodeOperationStatusTool("chat-2", ops).run(JSONObject().put("operationId", id)).output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        release.countDown()
        val later = json(NodeOperationStatusTool("chat-1", ops).run(JSONObject().put("operationId", id))).getJSONObject("result")
        assertEquals("responded", later.getJSONObject("operation").getString("state"))
        assertEquals(201, later.getJSONObject("response").getInt("status"))
        val list = json(NodeOperationStatusTool("chat-1", ops).run(JSONObject())).getJSONObject("result")
        assertEquals(1, list.getJSONObject("summary").getInt("count"))
        assertEquals(1, calls)
    }

    @Test
    fun `a write whose answer never came is delivery uncertain, unsafe to retry, and not sent again`() = runBlocking {
        val ops = NodeOperations(MemoryNodeOperationStore())
        var calls = 0
        val broken = { _: String, _: NodeRequestArgs, _: (okhttp3.Call) -> Unit ->
            calls++
            throw java.io.IOException("reset")
        }
        val out = NodeRequestTool(deps(), ops, broken).run(request(method = "PUT", path = "/tags/1"))
        val result = json(out).getJSONObject("result")
        assertEquals("delivery_uncertain", result.getJSONObject("operation").getString("state"))
        assertEquals("unsafe", result.getJSONObject("operation").getString("retrySafety"))
        assertEquals("NODE_DELIVERY_UNCERTAIN", result.getJSONObject("operation").getJSONObject("error").getString("code"))
        assertEquals(1, calls)
        // A read (as the classifier judged it) that fails is an error, recorded as not answered.
        val reads = Approvals(effect = EffectClassification(Effect.READ, 0.99, "reads", emptyList(), emptyList()))
        val read = NodeRequestTool(deps(reads), ops, broken).run(request())
        assertTrue(read.output, read.output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        assertEquals(2, calls)
        assertEquals("NODE_RESPONSE_UNAVAILABLE", ops.store.listRecent("chat-1", 1).single().errorCode)
        // A GET the classifier couldn't judge is treated as a change, as on desktop: a receipt, never retried.
        val unjudged = json(NodeRequestTool(deps(), ops, broken).run(request())).getJSONObject("result")
        assertEquals("unsafe", unjudged.getJSONObject("operation").getString("retrySafety"))
        assertEquals(3, calls)
    }

    @Test
    fun `a write answered with a body over the bound is responded with its status, not uncertain (R1-F5)`() = runBlocking {
        val ops = NodeOperations(MemoryNodeOperationStore())
        val big = { _: String, _: NodeRequestArgs, _: (okhttp3.Call) -> Unit ->
            throw NodeResponseTooLarge(NodeResponse(201, "Created", mapOf("content-type" to "application/json"), "", 0))
        }
        val op = json(NodeRequestTool(deps(), ops, big).run(request(method = "POST", path = "/pins/abc")))
            .getJSONObject("result")
        assertEquals("responded", op.getJSONObject("operation").getString("state"))
        assertEquals("NODE_RESPONSE_TOO_LARGE", op.getJSONObject("operation").getJSONObject("error").getString("code"))
        assertEquals(201, op.getJSONObject("response").getInt("status"))
        // With no status line at all it stays uncertain.
        val bare = { _: String, _: NodeRequestArgs, _: (okhttp3.Call) -> Unit -> throw NodeResponseTooLarge() }
        val uncertain = json(NodeRequestTool(deps(), ops, bare).run(request(method = "POST", path = "/pins/abc")))
            .getJSONObject("result").getJSONObject("operation")
        assertEquals("delivery_uncertain", uncertain.getString("state"))
    }

    @Test
    fun `a write whose chat was deleted while it ran leaves no receipt behind (R1-F2)`() = runBlocking {
        val ops = NodeOperations(MemoryNodeOperationStore())
        val sent = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = { _: String, _: NodeRequestArgs, _: (okhttp3.Call) -> Unit ->
            sent.countDown()
            release.await(5, TimeUnit.SECONDS)
            NodeResponse(201, "Created", emptyMap(), "{}", 2)
        }
        val out = NodeRequestTool(deps(), ops, slow, observeMs = 100).run(request(method = "POST", path = "/pins/abc"))
        val id = json(out).getJSONObject("result").getJSONObject("operation").getString("operationId")
        assertTrue(sent.await(5, TimeUnit.SECONDS))
        ops.store.deleteOwner("chat-1")
        release.countDown()
        ops.active(id)?.await()
        assertNull(ops.store.get(id))
        assertTrue(ops.store.listRecent("chat-1", 20).isEmpty())
    }

    @Test
    fun `the journal on disk changes a live receipt in place and never writes back a deleted chat's (R1-F2)`() = runBlocking {
        val dir = Files.createTempDirectory("nodeops").toFile()
        try {
            val store = FileNodeOperationStore(dir) { 1_000L }
            val live = NodeOperation(
                NodeOperations.newId(), "chat-1", NodeOperation.IN_FLIGHT, "ant", "http", "reversible_admin", "POST", "/pins/x",
                createdAt = 1L, updatedAt = 1L,
            )
            val gone = live.copy(operationId = NodeOperations.newId(), ownerId = "chat-2")
            store.put(live)
            store.put(gone)
            store.deleteOwner("chat-2")
            assertNull(store.update(gone.operationId) { it.copy(state = NodeOperation.RESPONDED) })
            assertFalse(File(dir, gone.operationId + ".json").exists())
            val done = store.update(live.operationId) { it.copy(state = NodeOperation.RESPONDED, updatedAt = 2L) }
            assertEquals(NodeOperation.RESPONDED, done!!.state)
            // The next process reads what the update wrote.
            val next = FileNodeOperationStore(dir) { 3_000L }
            assertEquals(NodeOperation.RESPONDED, next.get(live.operationId)!!.state)
            assertNull(next.get(gone.operationId))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the journal on disk marks a request a dead process left in flight as uncertain, and drops a deleted chat's`() = runBlocking {
        val dir = Files.createTempDirectory("nodeops").toFile()
        try {
            val first = FileNodeOperationStore(dir) { 1_000L }
            val op = NodeOperation(
                NodeOperations.newId(), "chat-1", NodeOperation.IN_FLIGHT, "ant", "http", "reversible_admin", "POST", "/pins/x",
                createdAt = 1L, updatedAt = 1L,
            )
            first.put(op)
            first.put(op.copy(operationId = NodeOperations.newId(), ownerId = "chat-2", state = NodeOperation.RESPONDED))
            File(dir, "junk.json").writeText("{")
            // The next process.
            val second = FileNodeOperationStore(dir) { 2_000L }
            val swept = second.get(op.operationId)!!
            assertEquals(NodeOperation.DELIVERY_UNCERTAIN, swept.state)
            assertEquals("NODE_DELIVERY_UNCERTAIN", swept.errorCode)
            assertEquals(2_000L, swept.updatedAt)
            assertFalse(File(dir, "junk.json").exists())
            second.deleteOwner("chat-1")
            assertNull(FileNodeOperationStore(dir).get(op.operationId))
            assertEquals(1, FileNodeOperationStore(dir).listRecent("chat-2", 20).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- the transport ---------------------------------------------------

    /**
     * A node that takes each request and hangs up without answering, as
     * one crashing mid-write would: [accepted] counts the connections.
     */
    private class HangUp : AutoCloseable {
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val thread = Thread {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                accepted.incrementAndGet()
                s.use {
                    val head = StringBuilder()
                    val input = it.getInputStream()
                    while (!head.endsWith("\r\n\r\n")) {
                        val c = input.read()
                        if (c < 0) break
                        head.append(c.toChar())
                    }
                    requests += head.toString()
                }
            }
        }.apply { isDaemon = true; start() }
        val base get() = "http://127.0.0.1:${server.localPort}"
        override fun close() = server.close()
    }

    @Test
    fun `a write the node hung up on was sent exactly once, on its own connection, with nothing added`() {
        for (method in listOf("DELETE", "POST", "PUT", "PATCH")) {
            HangUp().use { node ->
                val args = NodeRequestArgs("ant", "http", method, "/pins/abc", mapOf("x-test" to "1"))
                try {
                    NodeHttp.send(node.base, args)
                    fail("$method answered")
                } catch (e: java.io.IOException) {
                    // Expected: the node hung up.
                }
                Thread.sleep(200)
                assertEquals(method, 1, node.accepted.get())
                val head = node.requests.single()
                assertTrue(head, head.startsWith("$method /pins/abc HTTP/1.1\r\n"))
                assertTrue(head, head.contains("x-test: 1\r\n"))
                assertTrue(head, head.contains("Connection: close\r\n"))
                assertFalse(head, head.contains("Cookie"))
            }
        }
    }

    @Test
    fun `answers are bounded, a HEAD isn't, and a redirect comes back as it is`() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody("x".repeat(NodeResponse.MAX_BYTES + 1)))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Length", "1000000").setHeader("ETag", "\"e\""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(307).setHeader("Location", "http://evil.example/").setHeader("Set-Cookie", "a=b"))
            val base = server.url("/").toString()
            try {
                NodeHttp.send(base, NodeRequestArgs("ant", "http", "GET", "/bytes/x"))
                fail("read past the bound")
            } catch (e: NodeResponseTooLarge) {
                // The status line and headers did come back (R1-F5).
                assertEquals(201, e.answered!!.status)
                assertEquals("", e.answered!!.body)
            }
            val head = NodeHttp.send(base, NodeRequestArgs("ant", "http", "HEAD", "/bzz/x"))
            assertEquals(200, head.status)
            assertEquals("\"e\"", head.headers["etag"])
            val redirect = NodeHttp.send(base, NodeRequestArgs("ant", "http", "GET", "/bzz/y"))
            assertEquals(307, redirect.status)
            assertEquals("http://evil.example/", redirect.headers["location"])
            assertNull(redirect.headers["set-cookie"])
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `the request always asks for identity and closes, whatever headers came with it`() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok"))
            // Past the parser (which refuses both), the transport still sets its own last (R2-M1).
            val args = NodeRequestArgs("ant", "http", "GET", "/bzz/x", mapOf("accept-encoding" to "gzip", "connection" to "keep-alive"))
            assertEquals("ok", NodeHttp.send(server.url("/").toString(), args).body)
            val sent = server.takeRequest()
            assertEquals(listOf("identity"), sent.headers.values("Accept-Encoding"))
            assertEquals(listOf("close"), sent.headers.values("Connection"))
        }
    }

    @Test
    fun `a 503 with Retry-After 0 is answered once, never resent`() {
        for (method in listOf("POST", "PUT", "DELETE", "GET")) {
            okhttp3.mockwebserver.MockWebServer().use { server ->
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody("second"))
                val body = if (method == "POST" || method == "PUT") "{}" else null
                val args = NodeRequestArgs("ant", "http", method, "/tags", mapOf("content-type" to "application/json").takeIf { body != null }.orEmpty(), body)
                val answer = NodeHttp.send(server.url("/").toString(), args)
                // OkHttp would otherwise resend it on its own and hand back the 201 (R3-M1).
                assertEquals(method, 503, answer.status)
                assertEquals(method, "busy", answer.body)
                assertEquals(method, 1, server.requestCount)
            }
        }
    }

    @Test
    fun `the diagnostics sheet rounds the byte cap up, never down`() {
        assertEquals(1, diagnosticsKb(1024))
        assertEquals(2, diagnosticsKb(1025))
        assertEquals(2, diagnosticsKb(2047))
        assertEquals(64, diagnosticsKb(65536))
    }

    // ---- node_lifecycle -------------------------------------------------

    private class FakeControls(var snapshot: NodeSnapshot) : NodeControls {
        val calls = mutableListOf<String>()
        var deferStop = false
        var react = true
        override suspend fun runNode(on: Boolean): Boolean {
            calls += "runNode $on"
            if (react) {
                snapshot = if (on) snapshot.copy(runNode = true, swarm = NodeInfo(status = NodeStatus.Running))
                else snapshot.copy(runNode = false, swarm = NodeInfo(status = NodeStatus.Stopped), ipfs = IpfsInfo(), radicle = RadicleInfo())
            }
            return deferStop && !on
        }
        override fun ipfs(on: Boolean) {
            calls += "ipfs $on"
            if (react) snapshot = snapshot.copy(ipfs = IpfsInfo(status = if (on) IpfsStatus.Running else IpfsStatus.Stopped))
        }
        override fun radicle(on: Boolean) {
            calls += "radicle $on"
        }
        override fun tor(on: Boolean) {
            calls += "tor $on"
            if (react) snapshot = snapshot.copy(tor = TorInfo(status = if (on) TorStatus.Running else TorStatus.Stopped))
        }
        override fun myotis(network: MyotisNetwork, on: Boolean) {
            calls += "myotis $network $on"
        }
        override fun logs(source: NodeLogSource): String? = "one\ntwo\nthree\n"
    }

    private fun lifecycle(controls: FakeControls, asks: Asks = Asks(), approvals: ChatApprovals = Approvals()) =
        NodeLifecycleTool(deps(approvals, asks, snapshot = { controls.snapshot }, controls = controls), verifyTimeoutMs = 200, verifyIntervalMs = 10)

    private fun args(service: String, action: String) = JSONObject().put("service", service).put("action", action)

    @Test
    fun `starting and stopping always ask, flip the user's own switch and are verified`() = runBlocking {
        val controls = FakeControls(running.copy(ipfs = IpfsInfo(status = IpfsStatus.Stopped)))
        val asks = Asks()
        // Even a model that calls it a confident read, in the most relaxed mode, asks.
        val approvals = Approvals(ApprovalMode.AllowWebsiteInteractions, EffectClassification(Effect.READ, 1.0, "x", emptyList(), emptyList()))
        val start = json(lifecycle(controls, asks, approvals).run(args("ipfs", "start"))).getJSONObject("result")
        assertEquals("stopped", start.getString("beforeState"))
        assertEquals("running", start.getString("afterState"))
        assertTrue(start.getBoolean("verified"))
        assertEquals(Effect.REVERSIBLE_ADMIN.wire, start.getString("effect"))
        assertEquals(listOf("ipfs true"), controls.calls)
        val stop = json(lifecycle(controls, asks).run(args("tor", "start"))).getJSONObject("result")
        assertEquals("running", stop.getString("afterState"))
        assertEquals(2, asks.asked.size)
        assertEquals("start", (asks.asked[0].kind as NodeAskKind.Lifecycle).action)
    }

    @Test
    fun `stopping the Swarm node names what stops with it, and a stop waiting on a payment says so`() = runBlocking {
        val controls = FakeControls(running)
        val asks = Asks()
        val out = json(lifecycle(controls, asks).run(args("ant", "stop"))).getJSONObject("result")
        assertEquals(listOf("ipfs", "radicle"), (asks.asked[0].kind as NodeAskKind.Lifecycle).also)
        assertEquals("stopped", out.getString("afterState"))
        assertTrue(out.getBoolean("verified"))
        // Started again from Run node off: stopped, not disabled, on this phone.
        val start = json(lifecycle(controls, asks).run(args("ant", "start"))).getJSONObject("result")
        assertEquals("stopped", start.getString("beforeState"))
        assertEquals("running", start.getString("afterState"))
        // A payment running inside the node: the stop is noted, not verified, and a restart doesn't start it again.
        controls.deferStop = true
        controls.react = false
        val deferred = json(lifecycle(controls, asks).run(args("ant", "stop"))).getJSONObject("result")
        assertFalse(deferred.getBoolean("verified"))
        assertTrue(deferred.getString("note").contains("postage payment"))
        val restart = lifecycle(controls, asks).run(args("ant", "restart"))
        assertTrue(restart.output, restart.output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        assertEquals("runNode false", controls.calls.last())
    }

    @Test
    fun `what Settings has off isn't turned on, IPFS and Radicle need the Swarm node, and a no is the user's`() = runBlocking {
        val off = FakeControls(running.copy(torEnabled = false, radicleEnabled = false))
        val asks = Asks()
        assertTrue(lifecycle(off, asks).run(args("tor", "start")).output.contains("disabled"))
        assertTrue(lifecycle(off, asks).run(args("radicle", "start")).output.contains("disabled"))
        val noNode = FakeControls(running.copy(runNode = false))
        assertTrue(lifecycle(noNode, asks).run(args("ipfs", "start")).output.contains("Start ant first"))
        assertTrue(lifecycle(noNode, asks).run(args("radicle", "restart")).output.contains("Start ant first"))
        assertTrue(lifecycle(FakeControls(running.copy(externalSwarm = true)), asks).run(args("ant", "stop")).output.startsWith("[CAPABILITY_UNAVAILABLE]"))
        assertTrue(asks.asked.isEmpty())
        val controls = FakeControls(running)
        val no = lifecycle(controls, Asks(ApprovalOutcome.Declined)).run(args("ant", "stop"))
        assertTrue(no.declined)
        assertTrue(controls.calls.isEmpty())
        // A node that never gets there isn't reported done.
        val stuck = FakeControls(running.copy(ipfs = IpfsInfo(status = IpfsStatus.Stopped))).apply { react = false }
        assertTrue(lifecycle(stuck).run(args("ipfs", "start")).output.contains("could not verify"))
        // Bad arguments.
        assertTrue(lifecycle(controls).run(args("bee", "start")).output.startsWith("[INVALID_ARGUMENT]"))
        assertTrue(lifecycle(controls).run(args("ant", "reset")).output.startsWith("[INVALID_ARGUMENT]"))
    }

    @Test
    fun `a light client chain switched off is stopped and can be started`() = runBlocking {
        val controls = FakeControls(running)
        val asks = Asks()
        val out = lifecycle(controls, asks).run(args("myotis-gnosis", "start"))
        // The fake never brings the chain up: asked, flipped, and honestly not verified.
        assertEquals(1, asks.asked.size)
        assertEquals("stopped", (asks.asked[0].kind as NodeAskKind.Lifecycle).beforeState)
        assertEquals("myotis Gnosis true", controls.calls.single())
        assertTrue(out.output.contains("could not verify"))
    }

    // ---- node_diagnostics -----------------------------------------------

    @Test
    fun `diagnostics ask every time, name the provider, and keep only the most recent lines and bytes`() = runBlocking {
        val controls = FakeControls(running)
        val asks = Asks()
        val tool = NodeDiagnosticsTool(deps(Approvals(ApprovalMode.AllowWebsiteInteractions), asks, controls = controls))
        val out = json(tool.run(JSONObject().put("service", "ant").put("maxLines", 2))).getJSONObject("result")
        val kind = asks.asked.single().kind as NodeAskKind.Diagnostics
        assertEquals("My provider", kind.provider)
        assertEquals(2, kind.maxLines)
        val logs = out.getJSONObject("logs")
        assertEquals(listOf("two", "three"), (0 until 2).map { logs.getJSONArray("entries").getJSONObject(it).getString("text") })
        assertTrue(logs.getBoolean("truncated"))
        assertEquals("ant", out.getJSONObject("status").getString("id"))
        assertEquals(listOf("ant"), out.getJSONArray("logsCover").let { a -> (0 until a.length()).map(a::getString) })
        // The light clients write one log: the result (and the sheet) say both chains' lines go (R1-F4).
        val myotis = json(tool.run(JSONObject().put("service", "myotis-gnosis"))).getJSONObject("result")
        assertEquals(listOf("myotis-gnosis", "myotis-ethereum"), myotis.getJSONArray("logsCover").let { a -> (0 until a.length()).map(a::getString) })
        assertEquals(NodeDiagnosticsTool.logCovers("myotis-ethereum"), NodeDiagnosticsTool.logCovers("myotis-gnosis"))
        assertNotNull(out.getJSONObject("runtime").getString("platform"))
        val no = NodeDiagnosticsTool(deps(asks = Asks(ApprovalOutcome.Declined), controls = controls)).run(JSONObject().put("service", "tor"))
        assertTrue(no.declined)
        assertTrue(tool.run(JSONObject().put("service", "ant").put("maxBytes", 10)).output.startsWith("[INVALID_ARGUMENT]"))
    }

    @Test
    fun `diagnostic lines are cut by bytes from the newest back, a single long line at a character boundary`() {
        val r = DiagnosticLines.read("aa\nbb\ncc", 10, 4)
        assertEquals(2, r.getInt("lineCount"))
        assertEquals(4, r.getInt("bytes"))
        assertTrue(r.getBoolean("truncated"))
        val long = DiagnosticLines.read("x".repeat(10) + "€€", 10, 1_025)
        assertEquals(1, long.getInt("lineCount"))
        val all = DiagnosticLines.read("a\nb\n", 10, 1_024)
        assertEquals(2, all.getInt("lineCount"))
        assertFalse(all.getBoolean("truncated"))
        val cut = DiagnosticLines.read("€€€", 10, 7)
        assertEquals("€€", cut.getJSONArray("entries").getJSONObject(0).getString("text"))
    }

    @Test
    fun `the oldest sheet shows, whichever host it's on`() {
        assertTrue(agentSheetFirst(3, null, null))
        assertTrue(agentSheetFirst(3, 4, null))
        assertFalse(agentSheetFirst(3, null, 2))
        assertFalse(agentSheetFirst(5, 4, 6))
        // Four hosts (#595 approvals, #596 transfers, #598 node, #599 names): an older name sheet holds a node sheet back.
        assertFalse(agentSheetFirst(5, null, 6, 4))
    }

    @Test
    fun `the chat offers every node tool beside publishing and transfers`() {
        val names = ToolRegistry.forChat("s") { error("not needed to list the tools") }.specs.map { it.name }
        assertTrue(names.containsAll(listOf("node_status", "node_request", "node_operation_status", "node_lifecycle", "node_diagnostics")))
        // #597's tools stay in every chat beside #598's.
        assertTrue(names.containsAll(listOf("swarm_publish", "swarm_publication_status", "wallet_transfer")))
        // #599's name tools too.
        assertTrue(names.containsAll(listOf("domain_inspect", "domain_prepare", "domain_execute", "domain_operation_status")))
        assertEquals(names.size, names.toSet().size)
    }
}
