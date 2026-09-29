package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.PublisherIdentity
import baby.freedom.mobile.wallet.PublisherKeys
import baby.freedom.mobile.wallet.SitePublisher
import baby.freedom.mobile.wallet.VaultLockedException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context as RhinoContext

/**
 * The `window.swarm` provider (#120): its tiers and sheets, desktop's
 * parameter checks and error shapes, and what it sends the node — against
 * a fake node that stores chunks and resolves feeds the way ant does.
 */
class SwarmProviderTest {
    private val site = "https://app.example"
    private val other = "https://other.example"

    private class MemoryGrants : SwarmProvider.Grants {
        val connected = HashSet<String>()
        val auto = HashSet<Pair<String, SwarmProvider.AutoApprove>>()
        override suspend fun connected(origin: String) = origin in connected
        override suspend fun connect(origin: String) = connected.add(origin).let { true }
        override suspend fun autoApprove(origin: String, kind: SwarmProvider.AutoApprove) = (origin to kind) in auto
        override suspend fun setAutoApprove(origin: String, kind: SwarmProvider.AutoApprove) = auto.add(origin to kind).let { true }
        val messaging = HashSet<String>()
        override suspend fun messaging(origin: String) = origin in messaging
        override suspend fun grantMessaging(origin: String) = messaging.add(origin).let { true }
    }

    private class MemoryFeeds : SwarmProvider.Feeds {
        val granted = HashSet<String>()
        val feeds = HashMap<String, MutableMap<String, SwarmProvider.FeedRecord>>()
        /** Thrown by the next writes: the store's IOException, or IllegalStateException with no wallet. */
        var failWrites: Exception? = null
        override fun granted(origin: String) = origin in granted
        override fun grant(origin: String) {
            failWrites?.let { throw it }
            granted += origin
        }
        override fun feed(origin: String, name: String) = feeds[origin]?.get(name)
        override fun all(origin: String) = feeds[origin]?.values?.sortedBy { it.createdAt }.orEmpty()
        override fun put(origin: String, record: SwarmProvider.FeedRecord) {
            failWrites?.let { throw it }
            feeds.getOrPut(origin) { LinkedHashMap() }[record.name] = record
        }
    }

    /** One app-scoped identity per site, index = sites so far; keys are fixed test keys, never funded. */
    private class FakePublishers : SwarmProvider.Publishers {
        var wallet = true
        var unlocked = true
        val sites = HashMap<String, SitePublisher>()
        var activity = 0
        val keysHandedOut = mutableListOf<ByteArray>()
        override fun walletExists() = wallet
        override fun unlocked() = unlocked
        override fun site(origin: String) = sites[origin]
        override fun ensureSite(origin: String) = sites.getOrPut(origin) {
            val identity = PublisherIdentity(PublisherIdentity.Mode.APP_SCOPED, sites.size, "App-scoped identity ${sites.size + 1}", 1)
            SitePublisher(origin, identity.id, listOf(identity), 1)
        }
        override fun signingKey(identity: PublisherIdentity): ByteArray {
            if (!unlocked) throw VaultLockedException()
            val byte = (0x11 + (identity.publisherKeyIndex ?: 0x40)).toByte()
            return ByteArray(32) { byte }.also { keysHandedOut += it }
        }
        override fun noteActivity() {
            activity++
        }
    }

    /** ant's gateway, in memory: chunks by address, SOCs checked, feeds resolved by walking indexes. */
    private class FakeNode : SwarmProvider.Http {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        val bodies = HashMap<String, ByteArray>()
        val chunks = HashMap<String, ByteArray>()
        var beeMode = "light"
        var stamps = JSONArray().put(
            JSONObject().put("batchID", "ab".repeat(32)).put("usable", true).put("depth", 20).put("bucketDepth", 16)
                .put("utilization", 0).put("immutableFlag", true).put("batchTTL", 86_400),
        )
        var up = true
        /** Uploads run past the gateway's whole-call deadline (a slow, working node). */
        var slowUploads = false
        var tag = 7L

        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): SwarmProvider.Http.Answer {
            if (!up) throw java.io.IOException("connection refused")
            if (slowUploads && method == "POST") throw java.net.SocketTimeoutException("the node didn't answer in $timeoutMs ms")
            requests += Triple(method, path, headers)
            if (body != null) bodies["$method $path"] = body
            val bare = path.substringBefore('?')
            fun json(o: Any, status: Int = 200, h: Map<String, String> = emptyMap()) =
                SwarmProvider.Http.Answer(status, h, o.toString().toByteArray())
            fun notFound() = json(JSONObject().put("message", "not found"), 404)
            return when {
                method == "GET" && bare == "/node" -> json(JSONObject().put("beeMode", beeMode))
                method == "GET" && bare == "/readiness" -> json(JSONObject())
                method == "GET" && bare == "/addresses" -> json(
                    JSONObject().put("overlay", OVERLAY).put("pssPublicKey", PSS_KEY).put("publicKey", PSS_KEY)
                        .put("ethereum", "0x" + "11".repeat(20)),
                )
                method == "POST" && bare.startsWith("/pss/send/") -> SwarmProvider.Http.Answer(201, emptyMap(), ByteArray(0))
                method == "GET" && bare == "/stamps" -> json(JSONObject().put("stamps", stamps))
                method == "POST" && bare == "/bzz" -> json(
                    JSONObject().put("reference", "b0".repeat(32)), 201,
                    mapOf("Swarm-Tag" to tag.toString()),
                )
                method == "POST" && bare == "/bytes" -> {
                    // A tree's root: the first 4096 bytes stand in for it here.
                    val cac = SwarmChunks.cac(body!!.copyOf(minOf(body.size, 4096)), body.size.toULong())
                    chunks[cac.address.swarmHex()] = cac.data()
                    json(JSONObject().put("reference", cac.address.swarmHex()), 201)
                }
                method == "POST" && bare == "/chunks" -> {
                    val cac = SwarmChunks.cac(body!!.copyOfRange(8, body.size), SwarmChunks.spanOf(body.copyOfRange(0, 8)))
                    chunks[cac.address.swarmHex()] = body
                    json(JSONObject().put("reference", cac.address.swarmHex()), 201)
                }
                method == "POST" && bare.startsWith("/soc/") -> {
                    val (owner, id) = bare.removePrefix("/soc/").split('/')
                    val sig = path.substringAfter("sig=").hexToBytesOrNull()!!
                    val address = SwarmChunks.socAddress(id.hexToBytesOrNull()!!, owner.hexToBytesOrNull()!!)
                    val wire = id.hexToBytesOrNull()!! + sig + body!!
                    // Like bee: refuse a SOC whose signature isn't the owner's.
                    if (SwarmChunks.parseSoc(address, wire) == null) return json(JSONObject().put("message", "invalid chunk"), 400)
                    chunks[address.swarmHex()] = wire
                    json(JSONObject().put("reference", address.swarmHex()), 201)
                }
                method == "GET" && bare.startsWith("/chunks/") -> chunks[bare.removePrefix("/chunks/")]
                    ?.let { SwarmProvider.Http.Answer(200, emptyMap(), it) } ?: notFound()
                method == "GET" && bare.startsWith("/feeds/") -> {
                    val (owner, topic) = bare.removePrefix("/feeds/").split('/')
                    var latest = -1L
                    while (chunks.containsKey(
                            SwarmChunks.socAddress(SwarmChunks.feedIdentifier(topic.hexToBytesOrNull()!!, latest + 1), owner.hexToBytesOrNull()!!).swarmHex(),
                        )
                    ) latest++
                    if (latest < 0) notFound() else json(
                        JSONObject(), 200,
                        mapOf("Swarm-Feed-Index" to "%016x".format(latest), "Swarm-Feed-Index-Next" to "%016x".format(latest + 1)),
                    )
                }
                method == "POST" && bare.startsWith("/feeds/") -> json(JSONObject().put("reference", "fe".repeat(32)), 201)
                method == "GET" && bare.startsWith("/tags/") -> json(
                    JSONObject().put("uid", bare.removePrefix("/tags/").toLong()).put("split", 10).put("seen", 0)
                        .put("stored", 10).put("sent", 5).put("synced", 0),
                )
                else -> notFound()
            }
        }

        fun uploads() = requests.filter { it.first == "POST" }
    }

    /** A node receive pipeline that is up at once; the test pushes its messages. */
    private class FakeSocket(val kind: String, val key: String, val onMessage: (ByteArray) -> Unit) : SwarmSubscriptions.Socket {
        override val established = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    /** The asking document: live until the test says otherwise, collecting what it's sent. */
    private class FakeSubscriber(override val tab: Long = 1, override val document: Int = 1) : SwarmSubscriptions.Subscriber {
        var alive = true
        val got = mutableListOf<JSONObject>()
        override fun live() = alive
        override fun deliver(message: JSONObject) {
            got += message
        }
    }

    private val sockets = mutableListOf<FakeSocket>()
    private val subscriptions = SwarmSubscriptions({ kind, key, onMessage -> FakeSocket(kind, key, onMessage).also { sockets += it } }, clock = { 42 })
    private val page = FakeSubscriber()

    private val grants = MemoryGrants()
    private val feeds = MemoryFeeds()
    private val publishers = FakePublishers()
    private val node = FakeNode()
    private var now = 1_000_000L
    private val provider = SwarmProvider(grants, feeds, publishers, node, clock = { now }, io = Dispatchers.Unconfined, subscriptions = subscriptions)
    private val asked = mutableListOf<SwarmAsk>()
    private var answer = SwarmProvider.Answer(true)
    /** Run when a sheet is answered allowed: stands in for what approving it does (setting up a wallet). */
    private var onApproved: () -> Unit = {}
    private var commits = 0
    private val events = mutableListOf<Triple<String, String, Any>>()

    init {
        provider.events = SwarmProvider.Events { origin, event, data -> events += Triple(origin, event, data) }
    }

    private fun call(method: String, params: JSONObject = JSONObject(), origin: String = site, subscriber: SwarmSubscriptions.Subscriber? = page) = runBlocking {
        provider.request(origin, method, params, { commits++ }, subscriber) { ask ->
            asked += ask
            answer.also { if (it.allowed) onApproved() }
        }
    }

    private fun ok(r: SwarmProvider.Reply): Any = (r as? SwarmProvider.Reply.Ok)?.value ?: throw AssertionError("expected ok, got $r")
    private fun okJson(r: SwarmProvider.Reply) = ok(r) as JSONObject
    private fun err(r: SwarmProvider.Reply) = r as? SwarmProvider.Reply.Err ?: throw AssertionError("expected an error, got $r")
    private fun b64(bytes: ByteArray) = JSONObject().put(SwarmProvider.BYTES, Base64.getEncoder().encodeToString(bytes))
    private fun connect(origin: String = site) = grants.connected.add(origin)

    // -------------------------------------------------------------------
    // Connection and capabilities
    // -------------------------------------------------------------------

    @Test
    fun `unknown methods are not supported, and messaging needs a connection`() {
        assertEquals(4200, err(call("swarm_nope")).code)
        for (m in SwarmProvider.MESSAGING_METHODS) {
            val e = err(call(m))
            assertEquals(m, 4100, e.code)
            assertEquals(m, "not_connected", e.reason)
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `requestAccess asks once, then answers from the grant`() {
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_requestAccess")).code)
        assertEquals(listOf<SwarmAsk>(SwarmAsk.Connect(site)), asked)
        answer = SwarmProvider.Answer(true)
        val first = okJson(call("swarm_requestAccess"))
        assertTrue(first.getBoolean("connected"))
        assertEquals(site, first.getString("origin"))
        assertEquals("[\"publish\"]", first.getJSONArray("capabilities").toString())
        assertEquals(Triple(site, "connect", site), events.single().let { Triple(it.first, it.second, (it.third as JSONObject).getString("origin")) })
        asked.clear()
        okJson(call("swarm_requestAccess"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `capabilities say why publishing can't happen yet`() {
        val caps = okJson(call("swarm_getCapabilities"))
        assertEquals("1.0", caps.getString("specVersion"))
        assertFalse(caps.getBoolean("canPublish"))
        assertEquals("not-connected", caps.getString("reason"))
        assertEquals(10 * 1024 * 1024, caps.getJSONObject("limits").getInt("maxDataBytes"))
        assertEquals(4096, caps.getJSONObject("limits").getInt("maxChunkPayloadBytes"))
        assertEquals("[\"messaging\"]", caps.getJSONArray("features").toString())
        assertEquals("[\"app-scoped\",\"bee-wallet\"]", caps.getJSONArray("publisherIdentityModes").toString())
        connect()
        assertTrue(okJson(call("swarm_getCapabilities")).getBoolean("canPublish"))
        node.beeMode = "ultra-light"
        assertEquals("ultra-light-mode", okJson(call("swarm_getCapabilities")).getString("reason"))
        node.beeMode = "light"
        node.stamps = JSONArray()
        assertEquals("no-usable-stamps", okJson(call("swarm_getCapabilities")).getString("reason"))
        node.up = false
        assertEquals("node-stopped", okJson(call("swarm_getCapabilities")).getString("reason"))
        assertTrue(asked.isEmpty())
    }

    // -------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------

    @Test
    fun `publishing needs a connection, and never asks without one`() {
        val e = err(call("swarm_publishData", JSONObject().put("data", "hi").put("contentType", "text/plain")))
        assertEquals(4100, e.code)
        assertEquals("not_connected", e.reason)
        assertTrue(asked.isEmpty())
        assertTrue(node.uploads().isEmpty())
    }

    @Test
    fun `publishData is checked before the sheet`() {
        connect()
        assertEquals("missing_content_type", err(call("swarm_publishData", JSONObject().put("data", "hi"))).reason)
        assertEquals("invalid_params", err(call("swarm_publishData", JSONObject().put("contentType", "text/plain"))).reason)
        assertEquals(
            "invalid_params",
            err(call("swarm_publishData", JSONObject().put("data", 5).put("contentType", "text/plain"))).reason,
        )
        val big = err(call("swarm_publishData", JSONObject().put("data", b64(ByteArray(10 * 1024 * 1024 + 1))).put("contentType", "a/b")))
        assertEquals(-32602, big.code)
        assertEquals("payload_too_large", big.reason)
        assertEquals(10 * 1024 * 1024, big.data!!.getInt("limit"))
        assertEquals(10 * 1024 * 1024 + 1, big.data!!.getInt("actual"))
        node.stamps = JSONArray()
        val noStamps = err(call("swarm_publishData", JSONObject().put("data", "hi").put("contentType", "text/plain")))
        assertEquals(4900, noStamps.code)
        assertEquals("no-usable-stamps", noStamps.reason)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `publishData asks, uploads the bytes as one file, and remembers always-allow`() {
        connect()
        answer = SwarmProvider.Answer.REJECTED
        val params = JSONObject().put("data", "hello").put("contentType", "text/plain").put("name", "note one.txt")
        assertEquals(4001, err(call("swarm_publishData", params)).code)
        assertTrue(node.uploads().isEmpty())
        val ask = asked.single() as SwarmAsk.Publish
        assertEquals(SwarmAsk.Publish.Kind.Data, ask.kind)
        assertEquals(5L, ask.size)
        assertEquals("text/plain", ask.contentType)
        assertEquals("note one.txt", ask.name)

        answer = SwarmProvider.Answer(true, always = true)
        val result = okJson(call("swarm_publishData", params))
        assertEquals("b0".repeat(32), result.getString("reference"))
        assertEquals("bzz://" + "b0".repeat(32), result.getString("bzzUrl"))
        val (_, path, headers) = node.uploads().single()
        assertEquals("/bzz?name=note%20one.txt", path)
        assertEquals("ab".repeat(32), headers["swarm-postage-batch-id"])
        assertEquals("text/plain", headers["content-type"])
        assertEquals("false", headers["swarm-deferred-upload"])
        assertArrayEquals("hello".toByteArray(), node.bodies["POST $path"])

        asked.clear()
        okJson(call("swarm_publishData", JSONObject().put("data", b64(byteArrayOf(1, 2, 3))).put("contentType", "application/octet-stream")))
        assertTrue("always allow skips the sheet", asked.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), node.bodies["POST /bzz?name=data"])
    }

    @Test
    fun `a JSON-serialized Buffer is bytes too, as on desktop`() {
        connect()
        val buffer = JSONObject().put("type", "Buffer").put("data", JSONArray(listOf(104, 105)))
        okJson(call("swarm_publishData", JSONObject().put("data", buffer).put("contentType", "text/plain")))
        assertArrayEquals("hi".toByteArray(), node.bodies["POST /bzz?name=data"])
    }

    @Test
    fun `publishFiles checks every path, then uploads a tar collection with its index document`() {
        connect()
        fun files(vararg paths: String) = JSONObject().put(
            "files",
            JSONArray(paths.map { JSONObject().put("path", it).put("bytes", b64(it.toByteArray())) }),
        )
        assertEquals("empty_files", err(call("swarm_publishFiles", JSONObject().put("files", JSONArray()))).reason)
        for (bad in listOf("/abs", "a//b", "../up", "a\\b", "x".repeat(101), "tab\there")) {
            assertEquals(bad, "invalid_path", err(call("swarm_publishFiles", files(bad))).reason)
        }
        assertEquals("duplicate_path", err(call("swarm_publishFiles", files("a.txt", "a.txt"))).reason)
        assertEquals(
            "invalid_index_document",
            err(call("swarm_publishFiles", files("a.txt").put("indexDocument", "index.html"))).reason,
        )
        assertEquals(
            "invalid_params",
            err(call("swarm_publishFiles", JSONObject().put("files", JSONArray().put(JSONObject().put("path", "a").put("bytes", "text"))))).reason,
        )
        val tooMany = JSONArray((0..100).map { JSONObject().put("path", "f$it").put("bytes", b64(byteArrayOf(1))) })
        assertEquals("too_many_files", err(call("swarm_publishFiles", JSONObject().put("files", tooMany))).reason)
        assertTrue(asked.isEmpty())

        val result = okJson(call("swarm_publishFiles", files("index.html", "css/site.css", "img/a.png", "b.txt").put("indexDocument", "index.html")))
        assertEquals(7L, result.getLong("tagUid"))
        val ask = asked.single() as SwarmAsk.Publish
        assertEquals(listOf("index.html", "css/site.css", "img/a.png", "b.txt"), ask.paths)
        assertEquals("index.html, css/site.css, img/a.png …and 1 more", swarmPathsPreview(ask.paths))
        val (_, _, headers) = node.uploads().single()
        assertEquals("true", headers["swarm-collection"])
        assertEquals("index.html", headers["swarm-index-document"])
        assertEquals("application/x-tar", headers["content-type"])
        // The tar holds each file under its path, 512-byte aligned, ending in two zero blocks.
        val tar = node.bodies["POST /bzz"]!!
        assertEquals(0, tar.size % 512)
        assertEquals("index.html", String(tar.copyOfRange(0, 10)))
        assertEquals("ustar", String(tar.copyOfRange(257, 262)))
        assertEquals("index.html", String(tar.copyOfRange(512, 522)))
        assertEquals("css/site.css", String(tar.copyOfRange(1024, 1036)))
        assertTrue(tar.copyOfRange(tar.size - 1024, tar.size).all { it == 0.toByte() })
        // The header checksum is right.
        val header = tar.copyOfRange(0, 512)
        val stored = String(header.copyOfRange(148, 154)).toInt(8)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        assertEquals(header.sumOf { it.toInt() and 0xff }, stored)
    }

    @Test
    fun `upload status is only the uploading origin's`() {
        connect()
        connect(other)
        okJson(call("swarm_publishFiles", JSONObject().put("files", JSONArray().put(JSONObject().put("path", "a").put("bytes", b64(byteArrayOf(1)))))))
        val stranger = err(call("swarm_getUploadStatus", JSONObject().put("tagUid", 7), origin = other))
        assertEquals(4100, stranger.code)
        assertEquals("tag_ownership_mismatch", stranger.reason)
        assertEquals("invalid_params", err(call("swarm_getUploadStatus", JSONObject().put("tagUid", -1))).reason)
        val status = okJson(call("swarm_getUploadStatus", JSONObject().put("tagUid", 7)))
        assertEquals(7, status.getInt("tagUid"))
        assertEquals(50, status.getInt("progress"))
        assertFalse(status.getBoolean("done"))
    }

    @Test
    fun `publishChunk uploads one CAC and answers its address`() {
        connect()
        assertEquals("unsupported_option", err(call("swarm_publishChunk", JSONObject().put("data", "x").put("options", JSONObject().put("pin", true)))).reason)
        assertEquals("payload_too_large", err(call("swarm_publishChunk", JSONObject().put("data", b64(ByteArray(4097))))).reason)
        assertEquals("invalid_params", err(call("swarm_publishChunk", JSONObject().put("data", ""))).reason)
        assertEquals("invalid_span", err(call("swarm_publishChunk", JSONObject().put("data", "x").put("span", -1))).reason)
        assertEquals("invalid_span", err(call("swarm_publishChunk", JSONObject().put("data", "x").put("span", 1.5))).reason)
        val reference = okJson(call("swarm_publishChunk", JSONObject().put("data", "chunk"))).getString("reference")
        assertEquals(SwarmChunks.cac("chunk".toByteArray()).address.swarmHex(), reference)
        assertEquals(SwarmAsk.Publish.Kind.Chunk, (asked.single() as SwarmAsk.Publish).kind)
        // A span past JS's safe range comes as a bigint.
        val bigSpan = JSONObject().put(SwarmProvider.BIGINT, "18446744073709551615")
        val r2 = okJson(call("swarm_publishChunk", JSONObject().put("data", "chunk").put("span", bigSpan))).getString("reference")
        assertEquals(SwarmChunks.cac("chunk".toByteArray(), ULong.MAX_VALUE).address.swarmHex(), r2)
    }

    // -------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------

    @Test
    fun `readChunk needs no connection and validates what the node returns`() {
        val cac = SwarmChunks.cac("public".toByteArray())
        node.chunks[cac.address.swarmHex()] = cac.data()
        val read = okJson(call("swarm_readChunk", JSONObject().put("reference", cac.address.swarmHex())))
        assertEquals("public", String(Base64.getDecoder().decode(read.getString("data"))))
        assertEquals("base64", read.getString("encoding"))
        assertEquals(6, read.getInt("span"))
        assertEquals("invalid_reference", err(call("swarm_readChunk", JSONObject().put("reference", "0x" + "a".repeat(64)))).reason)
        assertEquals("chunk_not_found", err(call("swarm_readChunk", JSONObject().put("reference", "a".repeat(64)))).reason)
        node.chunks["b".repeat(64)] = cac.data()
        assertEquals("chunk_type_mismatch", err(call("swarm_readChunk", JSONObject().put("reference", "b".repeat(64)))).reason)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `readSingleOwnerChunk takes an address or owner plus identifier`() {
        val key = ByteArray(32) { 0x22 }
        val identifier = ByteArray(32) { 7 }
        val soc = SwarmChunks.sign(identifier, SwarmChunks.cac("soc!".toByteArray()), key)
        node.chunks[soc.address.swarmHex()] = identifier + soc.signature + soc.cac.data()
        val owner = PublisherKeys.address(key)
        val byOwner = okJson(call("swarm_readSingleOwnerChunk", JSONObject().put("owner", owner).put("identifier", identifier.swarmHex())))
        assertEquals("soc!", String(Base64.getDecoder().decode(byOwner.getString("data"))))
        assertEquals(owner, byOwner.getString("owner"))
        assertEquals(soc.address.swarmHex(), byOwner.getString("reference"))
        assertEquals(130, byOwner.getString("signature").length)
        okJson(call("swarm_readSingleOwnerChunk", JSONObject().put("address", soc.address.swarmHex())))
        assertEquals("invalid_params", err(call("swarm_readSingleOwnerChunk", JSONObject().put("address", soc.address.swarmHex()).put("owner", owner))).reason)
        assertEquals("invalid_params", err(call("swarm_readSingleOwnerChunk", JSONObject().put("owner", owner))).reason)
        assertEquals("invalid_owner", err(call("swarm_readSingleOwnerChunk", JSONObject().put("owner", "0x12").put("identifier", identifier.swarmHex()))).reason)
    }

    @Test
    fun `permission-free reads are rate limited, more generously once connected`() {
        repeat(120) { call("swarm_listFeeds") }
        val limited = err(call("swarm_listFeeds"))
        assertEquals("rate_limited", limited.reason)
        assertEquals(120, limited.data!!.getInt("maxRequests"))
        now += SwarmProvider.BUDGET_WINDOW_MS
        ok(call("swarm_listFeeds"))
        connect(other)
        repeat(600) { ok(call("swarm_listFeeds", origin = other)) }
        assertEquals("rate_limited", err(call("swarm_listFeeds", origin = other)).reason)
    }

    @Test
    fun `read budgets of origins that stopped reading are dropped`() {
        for (i in 0 until 50) ok(call("swarm_listFeeds", origin = "https://s$i.example"))
        assertEquals(50, provider.budgetOrigins())
        now += SwarmProvider.BUDGET_WINDOW_MS
        ok(call("swarm_listFeeds"))
        assertEquals(1, provider.budgetOrigins())
    }

    // -------------------------------------------------------------------
    // Feeds and signing
    // -------------------------------------------------------------------

    @Test
    fun `the first feed call asks for feed access, then always-allow skips the sheet`() {
        connect()
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_createFeed", JSONObject().put("name", "posts"))).code)
        val first = asked.single() as SwarmAsk.Sign
        assertTrue(first.grant)
        assertEquals(SwarmProvider.AutoApprove.Feeds, first.kind)
        assertEquals("posts", first.feedName)
        assertNull("no identity yet: the sheet says a new one is made", first.identity)
        assertFalse(feeds.granted(site))
        assertTrue(node.uploads().isEmpty())

        answer = SwarmProvider.Answer(true, always = true)
        val created = okJson(call("swarm_createFeed", JSONObject().put("name", "posts")))
        assertTrue(feeds.granted(site))
        val owner = PublisherKeys.address(ByteArray(32) { 0x11 })
        assertEquals(owner, created.getString("owner"))
        assertEquals(SwarmChunks.topic("$site/posts").swarmHex(), created.getString("topic"))
        assertEquals("fe".repeat(32), created.getString("manifestReference"))
        assertEquals("bzz://" + "fe".repeat(32), created.getString("bzzUrl"))
        assertEquals("app-scoped", created.getString("identityMode"))
        assertEquals("posts", created.getString("feedId"))
        val manifest = node.uploads().single()
        assertEquals("/feeds/${owner.removePrefix("0x").lowercase()}/${created.getString("topic")}", manifest.second)
        // Every key handed out was zeroed after its one use.
        assertTrue(publishers.keysHandedOut.all { k -> k.all { it == 0.toByte() } })

        asked.clear()
        val again = okJson(call("swarm_createFeed", JSONObject().put("name", "posts")))
        assertEquals(created.toString(), again.toString())
        assertTrue(asked.isEmpty())
        assertEquals("creating it again writes nothing", 1, node.uploads().size)
    }

    @Test
    fun `updateFeed and writeFeedEntry sign the next index, and readFeedEntry reads it back`() {
        connect()
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        feeds.grant(site)
        assertEquals("feed_not_found", err(call("swarm_updateFeed", JSONObject().put("feedId", "posts").put("reference", "cd".repeat(32)))).reason)
        assertEquals("feed_empty", err(call("swarm_readFeedEntry", JSONObject().put("name", "posts").put("owner", PublisherKeys.address(ByteArray(32) { 0x11 })))).reason)
        ok(call("swarm_createFeed", JSONObject().put("name", "posts")))
        assertEquals("invalid_reference", err(call("swarm_updateFeed", JSONObject().put("feedId", "posts").put("reference", "xyz"))).reason)

        val u0 = okJson(call("swarm_updateFeed", JSONObject().put("feedId", "posts").put("reference", "CD".repeat(32))))
        assertEquals(0, u0.getInt("index"))
        assertEquals("cd".repeat(32), u0.getString("reference"))
        assertEquals("bzz://" + "fe".repeat(32), u0.getString("bzzUrl"))
        val w1 = okJson(call("swarm_writeFeedEntry", JSONObject().put("name", "posts").put("data", "second")))
        assertEquals(1, w1.getInt("index"))
        val taken = err(call("swarm_writeFeedEntry", JSONObject().put("name", "posts").put("data", "again").put("index", 1)))
        assertEquals("index_already_exists", taken.reason)
        assertEquals(5, okJson(call("swarm_writeFeedEntry", JSONObject().put("name", "posts").put("data", b64(byteArrayOf(9))).put("index", 5))).getInt("index"))
        assertTrue("always-allow for feeds: no sheet", asked.isEmpty())
        assertEquals(site to "cd".repeat(32), site to feeds.feed(site, "posts")!!.lastReference)

        val latest = okJson(call("swarm_readFeedEntry", JSONObject().put("name", "posts")))
        assertEquals(1, latest.getInt("index"))
        assertEquals(2, latest.getInt("nextIndex"))
        assertEquals("second", String(Base64.getDecoder().decode(latest.getString("data"))))
        val first = okJson(call("swarm_readFeedEntry", JSONObject().put("name", "posts").put("index", 0)))
        val update = Base64.getDecoder().decode(first.getString("data"))
        assertEquals(40, update.size)
        assertEquals("cd".repeat(32), update.copyOfRange(8, 40).swarmHex())
        assertEquals("entry_not_found", err(call("swarm_readFeedEntry", JSONObject().put("name", "posts").put("index", 3))).reason)
        // By raw topic and owner, from any site and with no connection.
        val owner = PublisherKeys.address(ByteArray(32) { 0x11 })
        val byTopic = okJson(
            call("swarm_readFeedEntry", JSONObject().put("topic", SwarmChunks.topic("$site/posts").swarmHex()).put("owner", owner).put("index", 5), origin = other),
        )
        assertArrayEquals(byteArrayOf(9), Base64.getDecoder().decode(byTopic.getString("data")))
        assertEquals("invalid_owner", err(call("swarm_readFeedEntry", JSONObject().put("topic", "a".repeat(64)))).reason)
        assertEquals("invalid_params", err(call("swarm_readFeedEntry", JSONObject().put("topic", "a".repeat(64)).put("name", "x"))).reason)

        val list = ok(call("swarm_listFeeds")) as JSONArray
        assertEquals(1, list.length())
        assertEquals("posts", list.getJSONObject(0).getString("name"))
        assertEquals(0, (ok(call("swarm_listFeeds", origin = other)) as JSONArray).length())
    }

    @Test
    fun `a feed keeps the identity it was created with`() {
        connect()
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        feeds.grant(site)
        val created = okJson(call("swarm_createFeed", JSONObject().put("name", "log")))
        // The site switches to the Ant wallet identity.
        val wallet = PublisherIdentity.antWallet(2)
        publishers.sites[site] = publishers.sites[site]!!.let { it.copy(activeId = wallet.id, identities = it.identities + wallet) }
        ok(call("swarm_updateFeed", JSONObject().put("feedId", "log").put("reference", "cd".repeat(32))))
        val soc = node.uploads().last()
        assertTrue(soc.second.startsWith("/soc/${created.getString("owner").removePrefix("0x").lowercase()}/"))
    }

    @Test
    fun `a feed whose own identity is gone is refused, never signed with another key`() {
        connect()
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        feeds.grant(site)
        ok(call("swarm_createFeed", JSONObject().put("name", "log")))
        val uploads = node.uploads().size
        val update = JSONObject().put("feedId", "log").put("reference", "cd".repeat(32))
        val entry = JSONObject().put("name", "log").put("data", "x")

        // The identities file was set aside (unparseable): the feed record survived, its identity didn't.
        publishers.sites.clear()
        for (params in listOf("swarm_updateFeed" to update, "swarm_writeFeedEntry" to entry)) {
            assertEquals(params.first, "feed_owner_unavailable", err(call(params.first, params.second)).reason)
        }
        assertTrue("no new identity made for the site", publishers.sites.isEmpty())

        // The site has identities again, but not the feed's.
        publishers.ensureSite(other)
        publishers.ensureSite(site)
        assertEquals("feed_owner_unavailable", err(call("swarm_updateFeed", update)).reason)

        // The feed's identity is listed, but its key no longer derives the feed's owner (a different wallet).
        publishers.sites.clear()
        publishers.ensureSite(site)
        feeds.put(site, feeds.feed(site, "log")!!.copy(owner = PublisherKeys.address(ByteArray(32) { 0x7f })))
        assertEquals("feed_owner_unavailable", err(call("swarm_writeFeedEntry", entry)).reason)
        assertTrue("its key was zeroed", publishers.keysHandedOut.last().all { it == 0.toByte() })

        assertEquals("nothing was uploaded", uploads, node.uploads().size)
        assertTrue(asked.isEmpty())
        assertEquals(site to null, site to feeds.feed(site, "log")!!.lastReference)
    }

    @Test
    fun `no sheet for a feed whose own identity is gone`() {
        connect()
        feeds.grant(site)
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        ok(call("swarm_createFeed", JSONObject().put("name", "log")))
        grants.auto.clear()
        publishers.sites.clear()
        assertEquals("feed_owner_unavailable", err(call("swarm_updateFeed", JSONObject().put("feedId", "log").put("reference", "cd".repeat(32)))).reason)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `createFeed on a feed whose own identity is gone is refused, sheet or no sheet`() {
        connect()
        feeds.grant(site)
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        ok(call("swarm_createFeed", JSONObject().put("name", "notes")))
        publishers.sites.clear()
        // Always-allowed and unlocked: no sheet on the way.
        assertEquals("feed_owner_unavailable", err(call("swarm_createFeed", JSONObject().put("name", "notes"))).reason)
        // Locked, or always-allow off: the sheet path gives the same answer.
        publishers.unlocked = false
        assertEquals("feed_owner_unavailable", err(call("swarm_createFeed", JSONObject().put("name", "notes"))).reason)
        publishers.unlocked = true
        grants.auto.clear()
        assertEquals("feed_owner_unavailable", err(call("swarm_createFeed", JSONObject().put("name", "notes"))).reason)
        assertTrue(asked.isEmpty())
        assertTrue("no new identity made for the site", publishers.sites.isEmpty())
    }

    @Test
    fun `a sign sheet queued behind wallet setup is shown as things stand once it's up`() = runBlocking {
        publishers.wallet = false
        val stale = SwarmAsk.Sign(site, "swarm_getSigningIdentity", SwarmProvider.AutoApprove.Signing, true, null, "Signing identity", null, needsWallet = true)
        // An earlier sheet's approval set up the wallet, granted feed access and made the site's identity.
        publishers.wallet = true
        feeds.grant(site)
        val identity = publishers.ensureSite(site).active
        val shown = provider.current(stale) as SwarmAsk.Sign
        assertEquals(identity, shown.identity)
        assertFalse("not a first grant any more", shown.grant)
        assertFalse(shown.needsWallet)

        // A feed's sheet signs as the feed's own identity; once that's gone there's nothing to show.
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        connect()
        ok(call("swarm_createFeed", JSONObject().put("name", "log")))
        val update = SwarmAsk.Sign(site, "swarm_updateFeed", SwarmProvider.AutoApprove.Feeds, false, "log", null, null)
        assertEquals(identity, (provider.current(update) as SwarmAsk.Sign).identity)
        publishers.sites.clear()
        assertNull(provider.current(update))
    }

    @Test
    fun `feed names are desktop's`() {
        connect()
        for (bad in listOf("", "a/b", "x".repeat(65), "new\nline")) {
            assertEquals(bad, "invalid_feed_name", err(call("swarm_createFeed", JSONObject().put("name", bad))).reason)
        }
        assertEquals("invalid_feed_name", err(call("swarm_createFeed", JSONObject())).reason)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `signing a SOC and disclosing the signer ask under the signing kind`() {
        connect()
        val identifier = "01".repeat(32)
        val params = JSONObject().put("identifier", identifier).put("data", "payload")
        val soc = okJson(call("swarm_writeSingleOwnerChunk", params))
        val ask = asked.single() as SwarmAsk.Sign
        assertEquals(SwarmProvider.AutoApprove.Signing, ask.kind)
        assertTrue(ask.grant)
        assertEquals("Single Owner Chunk $identifier", ask.detail)
        val owner = PublisherKeys.address(ByteArray(32) { 0x11 })
        assertEquals(owner, soc.getString("owner"))
        assertEquals(identifier, soc.getString("identifier"))
        assertNotNull(node.chunks[soc.getString("reference")])

        asked.clear()
        val identity = okJson(call("swarm_getSigningIdentity"))
        assertEquals(owner, identity.getString("owner"))
        assertEquals("app-scoped", identity.getString("identityMode"))
        val second = asked.single() as SwarmAsk.Sign
        assertFalse("granted already", second.grant)
        assertEquals("App-scoped identity 1 (App-scoped)", swarmSignIdentity(second))
        assertEquals("invalid_identifier", err(call("swarm_writeSingleOwnerChunk", JSONObject().put("identifier", "zz").put("data", "x"))).reason)
    }

    @Test
    fun `a locked wallet always gets the sheet, even with always-allow`() {
        connect()
        feeds.grant(site)
        grants.auto += site to SwarmProvider.AutoApprove.Signing
        publishers.unlocked = false
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_getSigningIdentity")).code)
        assertEquals(1, asked.size)
        // Approved but still locked (the sheet couldn't open it): no key, a clear error.
        answer = SwarmProvider.Answer(true)
        val locked = err(call("swarm_getSigningIdentity"))
        assertEquals(-32603, locked.code)
        assertEquals(SwarmProvider.VAULT_LOCKED, locked.message)
    }

    @Test
    fun `with no wallet, the sheet comes first and approving it is what sets a wallet up`() {
        connect()
        publishers.wallet = false
        // Refused: nothing further, and the page isn't told to wait.
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_getSigningIdentity")).code)
        val sign = asked.single() as SwarmAsk.Sign
        assertTrue(sign.needsWallet)
        assertTrue(sign.grant)
        assertEquals(0, commits)
        // Approved, but no wallet came of it (setup backed out of): refused, nothing granted.
        answer = SwarmProvider.Answer(true)
        assertEquals(4001, err(call("swarm_getSigningIdentity")).code)
        assertFalse(feeds.granted(site))
        assertTrue(publishers.keysHandedOut.isEmpty())
        // Approved, and the wallet set up: signs, and feed access is granted.
        onApproved = { publishers.wallet = true }
        publishers.wallet = false
        okJson(call("swarm_getSigningIdentity"))
        assertTrue((asked.last() as SwarmAsk.Sign).needsWallet)
        assertTrue(feeds.granted(site))
        // Even an always-allow left from an earlier wallet doesn't skip the sheet with none now.
        grants.auto += site to SwarmProvider.AutoApprove.Signing
        onApproved = {}
        publishers.wallet = false
        asked.clear()
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_getSigningIdentity")).code)
        assertEquals(1, asked.size)
    }

    @Test
    fun `the page is told a request is committed only once it is approved`() {
        connect()
        answer = SwarmProvider.Answer.REJECTED
        err(call("swarm_publishData", JSONObject().put("data", "x").put("contentType", "text/plain")))
        assertEquals(0, commits)
        answer = SwarmProvider.Answer(true)
        ok(call("swarm_publishData", JSONObject().put("data", "x").put("contentType", "text/plain")))
        assertEquals(1, commits)
        // Auto-approved: no sheet, still committed before the upload.
        grants.auto += site to SwarmProvider.AutoApprove.Publish
        asked.clear()
        ok(call("swarm_publishData", JSONObject().put("data", "x").put("contentType", "text/plain")))
        assertTrue(asked.isEmpty())
        assertEquals(2, commits)
    }

    @Test
    fun `the sign sheet names the identity that actually signs a feed`() {
        connect()
        feeds.grant(site)
        okJson(call("swarm_createFeed", JSONObject().put("name", "notes")))
        // The site switches its active identity to the Ant wallet one.
        val wallet = PublisherIdentity.antWallet(2)
        publishers.sites[site] = publishers.sites[site]!!.let { it.copy(activeId = wallet.id, identities = it.identities + wallet) }
        asked.clear()
        ok(call("swarm_updateFeed", JSONObject().put("feedId", "notes").put("reference", "cd".repeat(32))))
        ok(call("swarm_writeFeedEntry", JSONObject().put("name", "notes").put("data", "hello")))
        for (ask in asked) assertEquals("App-scoped identity 1 (App-scoped)", swarmSignIdentity(ask as SwarmAsk.Sign))
        assertEquals(2, asked.size)
        // A new feed or a SOC signs with (and names) the active one.
        asked.clear()
        ok(call("swarm_createFeed", JSONObject().put("name", "other")))
        assertEquals(wallet.id, (asked.single() as SwarmAsk.Sign).identity!!.id)
    }

    @Test
    fun `a failed save on this device is not reported as the node being down`() {
        connect()
        feeds.grant(site)
        feeds.failWrites = java.io.IOException("disk full")
        val created = err(call("swarm_createFeed", JSONObject().put("name", "notes")))
        assertEquals(-32603, created.code)
        assertNull(created.reason)
        assertTrue(created.message, created.message.contains("on this device"))
        feeds.failWrites = IllegalStateException("there is no wallet")
        val noWallet = err(call("swarm_createFeed", JSONObject().put("name", "notes")))
        assertEquals(-32603, noWallet.code)
        assertEquals("There is no wallet on this device", noWallet.message)
        // The node itself being down still says so.
        feeds.failWrites = null
        node.up = false
        assertEquals("node-stopped", err(call("swarm_createFeed", JSONObject().put("name", "notes"))).reason)
    }

    @Test
    fun `an upload over the gateway's deadline is a timeout, not the node being stopped`() {
        connect()
        grants.auto += site to SwarmProvider.AutoApprove.Publish
        node.slowUploads = true
        val e = err(call("swarm_publishData", JSONObject().put("data", "hi").put("contentType", "text/plain")))
        assertEquals(-32603, e.code)
        assertEquals("node-timeout", e.reason)
        // A node that can't be reached at all still says so.
        node.slowUploads = false
        node.up = false
        assertEquals("node-stopped", err(call("swarm_publishData", JSONObject().put("data", "hi").put("contentType", "text/plain"))).reason)
    }

    @Test
    fun `a queued feed ask whose identity went away is feed_owner_unavailable, not a user rejection`() {
        connect()
        feeds.grant(site)
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        ok(call("swarm_createFeed", JSONObject().put("name", "log")))
        grants.auto.clear()
        val before = commits
        // What askOnTab answers when SwarmProvider.current finds the feed's identity gone once the lock is ours.
        answer = SwarmProvider.Answer.OWNER_GONE
        val update = JSONObject().put("feedId", "log").put("reference", "cd".repeat(32))
        assertEquals("feed_owner_unavailable", err(call("swarm_updateFeed", update)).reason)
        assertEquals("feed_owner_unavailable", err(call("swarm_writeFeedEntry", JSONObject().put("name", "log").put("data", "x"))).reason)
        assertEquals("nothing committed", before, commits)
        // A plain refusal is still one.
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_updateFeed", update)).code)
    }

    @Test
    fun `a queued createFeed for a feed made and orphaned meanwhile is feed_owner_unavailable too`() {
        connect()
        feeds.grant(site)
        // New when checked (no record, so no feed to carry); by the time its sheet was due an earlier
        // queued createFeed had made it and its identity had gone: askOnTab answers OWNER_GONE.
        answer = SwarmProvider.Answer.OWNER_GONE
        val before = commits
        val e = err(call("swarm_createFeed", JSONObject().put("name", "notes")))
        assertEquals("feed_owner_unavailable", e.reason)
        assertEquals(-32603, e.code)
        assertEquals("nothing committed", before, commits)
        assertNull("no feed made", feeds.feed(site, "notes"))
    }

    @Test
    fun `createFeed on a feed whose key no longer derives its owner is refused like updateFeed`() {
        connect()
        feeds.grant(site)
        grants.auto += site to SwarmProvider.AutoApprove.Feeds
        ok(call("swarm_createFeed", JSONObject().put("name", "notes")))
        // The identity id is still listed, but its key derives a different address (a different wallet).
        feeds.put(site, feeds.feed(site, "notes")!!.copy(owner = PublisherKeys.address(ByteArray(32) { 0x7f })))
        assertEquals("feed_owner_unavailable", err(call("swarm_createFeed", JSONObject().put("name", "notes"))).reason)
        assertEquals("feed_owner_unavailable", err(call("swarm_updateFeed", JSONObject().put("feedId", "notes").put("reference", "cd".repeat(32)))).reason)
        assertTrue("its key was zeroed", publishers.keysHandedOut.last().all { it == 0.toByte() })
        // A healthy existing feed is still answered from its record.
        feeds.feeds.clear()
        val created = okJson(call("swarm_createFeed", JSONObject().put("name", "notes")))
        assertEquals(created.toString(), okJson(call("swarm_createFeed", JSONObject().put("name", "notes"))).toString())
    }

    @Test
    fun `signing needs a connection first`() {
        assertEquals("not_connected", err(call("swarm_createFeed", JSONObject().put("name", "posts"))).reason)
        assertTrue(asked.isEmpty())
    }

    // -------------------------------------------------------------------
    // Origins, batches, the page script
    // -------------------------------------------------------------------

    @Test
    fun `topics use desktop's origin keys`() {
        assertEquals("https://app.example", swarmOriginKey("https://app.example"))
        assertEquals("http://127.0.0.1:8080", swarmOriginKey("http://127.0.0.1:8080"))
        val bzz = "1234567890abcdef".repeat(4)
        val bzzOrigin = VirtualOrigin.originFor(ContentRoot.Bzz(bzz))!!
        assertEquals("bzz://$bzz", swarmOriginKey(bzzOrigin))
        assertEquals("vitalik.eth", swarmOriginKey(VirtualOrigin.originFor(ContentRoot.Ens("vitalik.eth"))!!))
    }

    @Test
    fun `a batch is picked for room and lifetime, as desktop does`() {
        fun batch(id: Char, depth: Int, utilization: Long, ttl: Long?, usable: Boolean = true) =
            PostageBatch(id.toString().repeat(64), usable, depth, 16, utilization, true, ttl)
        val small = batch('a', 17, 0, 1_000_000)
        val full = batch('b', 20, 16, 9_000_000)
        val roomy = batch('c', 20, 1, 500_000)
        val unusable = batch('d', 24, 0, 99_000_000, usable = false)
        assertEquals(roomy.id, SwarmProvider.selectBatch(listOf(small, full, roomy, unusable), 30_000))
        assertEquals(small.id, SwarmProvider.selectBatch(listOf(small, roomy), 4096))
        assertNull(SwarmProvider.selectBatch(listOf(small), 30_000))
    }

    @Test
    fun `the swarm sheet takes its turn after ethereum's`() {
        assertEquals(PromptTurn.Swarm, modalPromptTurn(false, false, false, false, swarmWaiting = true))
        assertEquals(PromptTurn.Ethereum, modalPromptTurn(false, false, false, false, ethereumWaiting = true, swarmWaiting = true))
        assertEquals(
            PromptTurn.Swarm,
            modalPromptTurn(true, false, false, false, ethereumWaiting = true, swarmWaiting = true, swarmHasTurn = true),
        )
        assertEquals(PromptTurn.None, modalPromptTurn(false, false, false, true, swarmWaiting = true, swarmHasTurn = true))
    }

    @Test
    fun `sheet copy names what is asked`() {
        val publish = SwarmAsk.Publish(site, SwarmAsk.Publish.Kind.Data, 5, "text/plain", null, emptyList())
        assertEquals("text/plain", swarmPublishWhat(publish))
        assertEquals("Swarm chunk", swarmPublishWhat(publish.copy(kind = SwarmAsk.Publish.Kind.Chunk)))
        assertEquals("1 file", swarmPublishWhat(publish.copy(kind = SwarmAsk.Publish.Kind.Files, paths = listOf("a"))))
        assertEquals("Publish", swarmPromptCopy(publish).approve)
        assertNull(swarmPromptCopy(SwarmAsk.Connect(site)).always)
        val sign = SwarmAsk.Sign(site, "swarm_createFeed", SwarmProvider.AutoApprove.Feeds, true, "posts", null, null)
        assertEquals("Feed access", swarmPromptCopy(sign).title)
        assertEquals("posts", swarmSignRequest(sign))
        assertEquals("A new app-scoped identity for this site", swarmSignIdentity(sign))
        assertEquals("Publisher signing", swarmPromptCopy(sign.copy(kind = SwarmProvider.AutoApprove.Signing)).title)
    }

    @Test
    fun `a Swarm site's line says what it may do without asking`() {
        fun grant(vararg auto: String) = baby.freedom.mobile.data.SwarmGrantStore.Grant(site, 1, auto.toSet())
        assertEquals("Asks before each upload and signature", swarmSiteSummary(grant()))
        assertEquals("Publishes without asking", swarmSiteSummary(grant("publish")))
        assertEquals("Publishes, manages feeds, signs without asking", swarmSiteSummary(grant("signing", "publish", "feeds")))
        val messaging = baby.freedom.mobile.data.SwarmGrantStore.Grant(site, 1, setOf("messaging"), messaging = true)
        assertEquals("Sends messages without asking · Can send and receive messages", swarmSiteSummary(messaging))
        assertEquals("Asks before each upload and signature · Can send and receive messages", swarmSiteSummary(messaging.copy(autoApprove = emptySet())))
    }

    @Test
    fun `requests off the channel are parsed defensively`() {
        assertEquals("swarm_readChunk", parseSwarmRequest("""{"id":3,"method":"swarm_readChunk","params":{"reference":"ab"}}""")!!.method)
        assertNull(parseSwarmRequest("""{"id":"x","method":"m"}"""))
        assertNull(parseSwarmRequest("""{"id":1,"method":"m","params":[1]}"""))
        assertNull(parseSwarmRequest("[".repeat(10_000)))
        assertNull(parseSwarmRequest(null))
    }

    @Test
    fun `the page script encodes bytes and bigints and decodes results`() {
        val cx = RhinoContext.enter()
        try {
            cx.languageVersion = RhinoContext.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                var sent = [], handler = null;
                var window = {
                  location: { protocol: 'https:' },
                  setTimeout: function () { return 1; }, clearTimeout: function () {},
                  Promise: Promise, Error: Error, Map: Map, Uint8Array: Uint8Array, ArrayBuffer: ArrayBuffer,
                  BigInt: undefined,
                  btoa: function (s) { var o = ''; for (var i = 0; i < s.length; i++) o += s.charCodeAt(i) + ','; return o; },
                  abcdefghij: { postMessage: function (m) { sent.push(m); }, addEventListener: function (t, h) { handler = h; } }
                };
                window.top = window;
                """.trimIndent(),
                "setup", 1, null,
            )
            cx.evaluateString(scope, swarmProviderJs("abcdefghij"), "swarm.js", 1, null)
            cx.evaluateString(scope, "window.swarm.publishData({ data: new Uint8Array([1, 2, 255]), contentType: 'a/b' });", "call", 1, null)
            val sent = JSONObject(cx.evaluateString(scope, "sent[0]", "sent", 1, null).toString())
            assertEquals("swarm_publishData", sent.getString("method"))
            assertEquals("1,2,255,", sent.getJSONObject("params").getJSONObject("data").getString("\$b64"))
            assertEquals("a/b", sent.getJSONObject("params").getString("contentType"))
            assertEquals("undefined", cx.evaluateString(scope, "typeof window.abcdefghij", "gone", 1, null).toString())
            assertEquals("true", cx.evaluateString(scope, "String(window.swarm.isFreedomBrowser)", "flag", 1, null).toString())

        } finally {
            RhinoContext.exit()
        }
        assertTrue(runCatching { swarmProviderJs("a'b") }.isFailure)
    }

    @Test
    fun `an approved request stops the page's timer and still waits for its result`() {
        val cx = RhinoContext.enter()
        try {
            cx.languageVersion = RhinoContext.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                var handler = null, timers = [], cleared = [], settled = null;
                var window = {
                  location: { protocol: 'https:' },
                  setTimeout: function (f, ms) { timers.push({ f: f, ms: ms }); return timers.length; },
                  clearTimeout: function (id) { cleared.push(id); },
                  Promise: Promise, Error: Error, Map: Map, Uint8Array: Uint8Array, ArrayBuffer: ArrayBuffer,
                  BigInt: undefined, btoa: function (s) { return s; },
                  abcdefghij: { postMessage: function (m) {}, addEventListener: function (t, h) { handler = h; } }
                };
                window.top = window;
                """.trimIndent(),
                "setup", 1, null,
            )
            cx.evaluateString(scope, swarmProviderJs("abcdefghij"), "swarm.js", 1, null)
            cx.evaluateString(
                scope,
                """
                window.swarm.publishData({ data: 'x', contentType: 'text/plain' })
                  .then(function (r) { settled = 'ok:' + r.reference; }, function (e) { settled = 'err:' + e.message; });
                handler({ data: JSON.stringify({ id: 1, approved: true }) });
                """.trimIndent(),
                "call", 1, null,
            )
            cx.processMicrotasks()
            assertEquals("300000", cx.evaluateString(scope, "String(timers[0].ms)", "ms", 1, null).toString())
            assertEquals("1", cx.evaluateString(scope, "cleared.join(',')", "cleared", 1, null).toString())
            assertEquals("null", cx.evaluateString(scope, "String(settled)", "settled", 1, null).toString())
            cx.evaluateString(scope, "handler({ data: JSON.stringify({ id: 1, result: { reference: 'ab' } }) });", "result", 1, null)
            cx.processMicrotasks()
            assertEquals("ok:ab", cx.evaluateString(scope, "String(settled)", "settled", 1, null).toString())
        } finally {
            RhinoContext.exit()
        }
    }

    // -------------------------------------------------------------------
    // Messaging (#121)
    // -------------------------------------------------------------------

    private fun pssParams(
        data: Any = "hi",
        topic: Any = "chat",
        recipient: Any = RECIPIENT,
        targets: Any = "a1b2",
    ) = JSONObject().put("topic", topic).put("recipient", recipient).put("targets", targets).put("data", data)

    @Test
    fun `capabilities advertise messaging and its limits`() {
        val caps = okJson(call("swarm_getCapabilities"))
        assertEquals("messaging", caps.getJSONArray("features").getString(0))
        val limits = caps.getJSONObject("limits")
        assertEquals(4000, limits.getInt("maxMessageBytes"))
        assertEquals(3, limits.getInt("maxTargetDepth"))
        assertEquals(32, limits.getInt("maxSubscriptions"))
    }

    @Test
    fun `the messaging identity asks once, then discloses the node key and only two bytes of its overlay`() {
        connect()
        val first = okJson(call("swarm_getMessagingIdentity"))
        val sheet = asked.single() as SwarmAsk.Message
        assertTrue(sheet.grant)
        assertEquals(SwarmAsk.Message.Op.Identity, sheet.op)
        assertEquals("Messaging access", swarmPromptCopy(sheet).title)
        assertNull(swarmPromptCopy(sheet).always)
        assertEquals(PSS_KEY, first.getString("pssPublicKey"))
        assertEquals(OVERLAY.substring(0, 4), first.getString("pssTarget"))
        assertEquals("bee-wallet", first.getString("identityMode"))
        assertFalse(first.toString().contains(OVERLAY))
        assertTrue(site in grants.messaging)
        // Granted: never asks again.
        okJson(call("swarm_getMessagingIdentity"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `refusing the messaging sheet grants nothing`() {
        connect()
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_getMessagingIdentity")).code)
        assertEquals(4001, err(call("swarm_sendPss", pssParams())).code)
        assertTrue(grants.messaging.isEmpty())
        assertTrue(node.requests.none { it.second.startsWith("/pss") || it.second == "/addresses" })
    }

    @Test
    fun `a node key reported uncompressed is compressed`() {
        val x = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val y = "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8"
        assertEquals("02$x", SwarmProvider.compressedKey("04$x$y"))
        assertEquals("02$x", SwarmProvider.compressedKey("0x02${x.uppercase()}"))
        assertNull(SwarmProvider.compressedKey("05$x$y"))
        assertNull(SwarmProvider.compressedKey("nope"))
    }

    @Test
    fun `sendPss checks its parameters before any sheet`() {
        connect()
        fun reason(p: JSONObject) = err(call("swarm_sendPss", p)).reason
        assertEquals("invalid_topic", reason(pssParams(topic = "")))
        assertEquals("invalid_topic", reason(pssParams(topic = "a\u0001b")))
        assertEquals("invalid_topic", reason(pssParams(topic = "x".repeat(257))))
        assertEquals("invalid_recipient", reason(pssParams(recipient = "04" + "ab".repeat(32))))
        assertEquals("invalid_recipient", reason(pssParams(recipient = "02" + "ab".repeat(31))))
        assertEquals("invalid_target", reason(pssParams(targets = "a1")))
        assertEquals("invalid_target", reason(pssParams(targets = "a1b2c3d4")))
        assertEquals("invalid_target", reason(pssParams(targets = "a1b")))
        assertEquals("invalid_target", reason(pssParams(targets = "zzzz")))
        assertEquals("invalid_params", reason(pssParams(data = 5)))
        val big = err(call("swarm_sendPss", pssParams(data = b64(ByteArray(4001)))))
        assertEquals("payload_too_large", big.reason)
        assertEquals(4000, big.data!!.getInt("limit"))
        assertEquals(4001, big.data!!.getInt("actual"))
        assertEquals("unsupported_option", reason(pssParams().put("options", JSONObject().put("x", 1))))
        assertTrue(asked.isEmpty())
        // And the node's state before any sheet, as for publishing.
        node.beeMode = "ultra-light"
        assertEquals("ultra-light-mode", reason(pssParams()))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `sendPss posts the hashed topic, the targets and the recipient with a stamp`() {
        connect()
        // An empty PSS message is a valid ping: its framing carries a length.
        assertEquals(true, okJson(call("swarm_sendPss", pssParams(data = "", recipient = "0x" + RECIPIENT.uppercase(), targets = "A1B2C3"))).getBoolean("sent"))
        val (method, path, headers) = node.requests.last()
        assertEquals("POST", method)
        assertEquals("/pss/send/${SwarmChunks.topic("chat").swarmHex()}/a1b2c3?recipient=$RECIPIENT", path)
        assertEquals("ab".repeat(32), headers["swarm-postage-batch-id"])
        assertEquals(0, node.bodies["POST $path"]!!.size)
        okJson(call("swarm_sendPss", pssParams(data = b64(byteArrayOf(1, 2, 3)))))
        assertArrayEquals(byteArrayOf(1, 2, 3), node.bodies["POST ${node.requests.last().second}"])
    }

    @Test
    fun `the first send's grant sheet covers it, then each send asks unless messaging is always allowed`() {
        connect()
        okJson(call("swarm_sendPss", pssParams()))
        assertTrue((asked.single() as SwarmAsk.Message).grant)
        assertEquals(1, commits)
        okJson(call("swarm_sendGsoc", JSONObject().put("topic", "room").put("data", "yo")))
        val send = asked.last() as SwarmAsk.Message
        assertFalse(send.grant)
        assertEquals(SwarmAsk.Message.Kind.Gsoc, send.send)
        assertEquals("room", send.topic)
        assertEquals(2, send.size)
        assertEquals("Confirm message", swarmPromptCopy(send).title)
        assertEquals("wants to broadcast a message (GSOC)", swarmPromptCopy(send).request)
        assertEquals("Always allow this site to send messages without asking", swarmPromptCopy(send).always)
        answer = SwarmProvider.Answer(true, always = true)
        okJson(call("swarm_sendPss", pssParams()))
        assertEquals("wants to send a private message (PSS)", swarmPromptCopy(asked.last()).request)
        assertTrue((site to SwarmProvider.AutoApprove.Messaging) in grants.auto)
        val before = asked.size
        okJson(call("swarm_sendPss", pssParams()))
        okJson(call("swarm_sendGsoc", JSONObject().put("topic", "room").put("data", "yo")))
        assertEquals(before, asked.size)
        // Subscribing never asks once granted.
        okJson(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "chat")))
        assertEquals(before, asked.size)
    }

    @Test
    fun `a refused send sheet sends nothing`() {
        connect()
        grants.messaging += site
        answer = SwarmProvider.Answer.REJECTED
        assertEquals(4001, err(call("swarm_sendGsoc", JSONObject().put("topic", "room").put("data", "yo"))).code)
        assertTrue(node.uploads().isEmpty())
    }

    @Test
    fun `sendGsoc writes a SOC signed by the room's mined key at the room's address`() {
        connect()
        grants.messaging += site
        grants.auto += site to SwarmProvider.AutoApprove.Messaging
        val result = okJson(call("swarm_sendGsoc", JSONObject().put("topic", "room:doc-42").put("data", b64(byteArrayOf(9, 8, 7)))))
        // bee-js's gsocMine for this topic (desktop's derivation).
        assertEquals("457d444476f6de5d990d9465662d55462efd4be2ef34303bf922cedc7d89b1a9", result.getString("address"))
        val stored = SwarmChunks.parseSoc(result.getString("address").hexToBytesOrNull()!!, node.chunks[result.getString("address")]!!)!!
        assertArrayEquals(byteArrayOf(9, 8, 7), stored.cac.payload)
        assertEquals(PublisherKeys.address(SwarmGsoc.derive("room:doc-42").privateKey).lowercase(), "0x" + stored.owner.swarmHex())
        // Nothing of the site's own identities is touched.
        assertTrue(publishers.keysHandedOut.isEmpty())
    }

    @Test
    fun `sendGsoc refuses a bare address and an empty message before any sheet`() {
        connect()
        assertEquals("invalid_address", err(call("swarm_sendGsoc", JSONObject().put("address", "ab".repeat(32)).put("data", "x"))).reason)
        assertEquals("invalid_payload", err(call("swarm_sendGsoc", JSONObject().put("topic", "t").put("data", ""))).reason)
        assertEquals("payload_too_large", err(call("swarm_sendGsoc", JSONObject().put("topic", "t").put("data", "x".repeat(4001)))).reason)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `messages may use a full mutable batch, publishing never does`() {
        connect()
        grants.messaging += site
        grants.auto += site to SwarmProvider.AutoApprove.Messaging
        grants.auto += site to SwarmProvider.AutoApprove.Publish
        node.stamps = JSONArray().put(
            JSONObject().put("batchID", "cd".repeat(32)).put("usable", true).put("depth", 17).put("bucketDepth", 16)
                .put("utilization", 2).put("immutableFlag", false).put("batchTTL", 86_400),
        )
        okJson(call("swarm_sendPss", pssParams()))
        assertEquals("cd".repeat(32), node.requests.last().third["swarm-postage-batch-id"])
        assertEquals(-32603, err(call("swarm_publishData", JSONObject().put("data", "x").put("contentType", "text/plain"))).code)
    }

    @Test
    fun `subscribe checks its parameters before any sheet`() {
        connect()
        fun reason(p: JSONObject) = err(call("swarm_subscribe", p)).reason
        assertEquals("invalid_kind", reason(JSONObject().put("kind", "feed").put("topic", "t")))
        assertEquals("invalid_params", reason(JSONObject().put("kind", "gsoc")))
        assertEquals("invalid_params", reason(JSONObject().put("kind", "gsoc").put("topic", "t").put("address", "ab".repeat(32))))
        assertEquals("invalid_address", reason(JSONObject().put("kind", "gsoc").put("address", "ab")))
        assertEquals("invalid_params", reason(JSONObject().put("kind", "pss").put("address", "ab".repeat(32))))
        assertEquals("invalid_topic", reason(JSONObject().put("kind", "pss")))
        assertEquals("invalid_topic", reason(JSONObject().put("kind", "gsoc").put("topic", "")))
        assertEquals("invalid_params", err(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "t"), subscriber = null)).reason)
        assertTrue(asked.isEmpty())
        node.up = false
        assertEquals("node-stopped", reason(JSONObject().put("kind", "pss").put("topic", "t")))
        assertTrue(asked.isEmpty())
        assertTrue(sockets.isEmpty())
    }

    @Test
    fun `subscribe opens the node pipeline for the room or topic and delivers its messages to the page`() {
        connect()
        val room = okJson(call("swarm_subscribe", JSONObject().put("kind", "gsoc").put("topic", "room:doc-42")))
        assertTrue((asked.single() as SwarmAsk.Message).grant)
        assertEquals("gsoc", room.getString("kind"))
        assertEquals("457d444476f6de5d990d9465662d55462efd4be2ef34303bf922cedc7d89b1a9", room.getString("key"))
        val pss = okJson(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "chat")))
        assertEquals(SwarmChunks.topic("chat").swarmHex(), pss.getString("key"))
        val byAddress = okJson(call("swarm_subscribe", JSONObject().put("kind", "gsoc").put("address", "AB".repeat(32))))
        assertEquals("ab".repeat(32), byAddress.getString("key"))
        assertEquals(listOf("gsoc" to room.getString("key"), "pss" to pss.getString("key"), "gsoc" to "ab".repeat(32)), sockets.map { it.kind to it.key })
        sockets[0].onMessage(byteArrayOf(104, 105))
        val msg = page.got.single()
        assertEquals("swarm_subscription", msg.getString("type"))
        assertEquals(room.getString("subscriptionId"), msg.getString("subscription"))
        val result = msg.getJSONObject("result")
        assertEquals("gsoc", result.getString("kind"))
        assertEquals(room.getString("key"), result.getString("key"))
        assertEquals("aGk=", result.getString("data"))
        assertEquals("base64", result.getString("encoding"))
        assertEquals(42L, result.getLong("receivedAt"))
    }

    @Test
    fun `a page that went away while its subscription came up gets subscription_cancelled`() {
        connect()
        grants.messaging += site
        page.alive = false
        assertEquals("subscription_cancelled", err(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "t"))).reason)
        assertEquals(0, subscriptions.count(site))
    }

    @Test
    fun `unsubscribe never asks and only closes the site's own subscription`() {
        connect()
        connect(other)
        grants.messaging += site
        val id = okJson(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "t"))).getString("subscriptionId")
        assertEquals("subscription_not_found", err(call("swarm_unsubscribe", JSONObject().put("subscriptionId", id), origin = other)).reason)
        assertEquals("invalid_params", err(call("swarm_unsubscribe", JSONObject())).reason)
        assertEquals(true, okJson(call("swarm_unsubscribe", JSONObject().put("subscriptionId", id))).getBoolean("unsubscribed"))
        assertTrue(sockets.single().cancelled)
        assertEquals("subscription_not_found", err(call("swarm_unsubscribe", JSONObject().put("subscriptionId", id))).reason)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a site gets at most 32 subscriptions`() {
        connect()
        grants.messaging += site
        repeat(32) { okJson(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "t$it"))) }
        val e = err(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "one more")))
        assertEquals(-32602, e.code)
        assertEquals("too_many_subscriptions", e.reason)
        assertEquals(32, e.data!!.getInt("limit"))
        connect(other)
        grants.messaging += other
        okJson(call("swarm_subscribe", JSONObject().put("kind", "pss").put("topic", "t0"), origin = other))
    }

    @Test
    fun `a messaging sheet names the topic, and a send its size`() {
        val sub = SwarmAsk.Message(site, SwarmAsk.Message.Op.Subscribe, null, "room", 0, grant = true)
        assertEquals("wants to send and receive real-time messages", swarmPromptCopy(sub).request)
        assertEquals("Allow", swarmPromptCopy(sub).approve)
        val send = SwarmAsk.Message(site, SwarmAsk.Message.Op.Send, SwarmAsk.Message.Kind.Pss, "room", 12)
        assertEquals(SwarmAsk.Message.Kind.Pss, send.send)
        assertNull(sub.send)
        assertEquals("Send", swarmPromptCopy(send).approve)
    }

    @Test
    fun `the page script hands a subscription's messages to message listeners`() {
        val cx = RhinoContext.enter()
        try {
            cx.languageVersion = RhinoContext.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                var handler = null, got = [], timers = [];
                var window = {
                  location: { protocol: 'https:' },
                  setTimeout: function (f, ms) { timers.push(ms); return timers.length; }, clearTimeout: function () {},
                  Promise: Promise, Error: Error, Map: Map, Uint8Array: Uint8Array, ArrayBuffer: ArrayBuffer,
                  BigInt: undefined, btoa: function (s) { return s; },
                  abcdefghij: { postMessage: function (m) {}, addEventListener: function (t, h) { handler = h; } }
                };
                window.top = window;
                """.trimIndent(),
                "setup", 1, null,
            )
            cx.evaluateString(scope, swarmProviderJs("abcdefghij"), "swarm.js", 1, null)
            cx.evaluateString(
                scope,
                """
                function h(m) { got.push(m.subscription + ':' + m.result.data); }
                window.swarm.on('message', h);
                handler({ data: JSON.stringify({ event: 'message', data: { type: 'swarm_subscription', subscription: 's1', result: { data: 'aGk=' } } }) });
                window.swarm.removeListener('message', h);
                handler({ data: JSON.stringify({ event: 'message', data: { subscription: 's2', result: { data: 'x' } } }) });
                window.swarm.subscribe({ kind: 'pss', topic: 't' });
                window.swarm.sendGsoc({ topic: 't', data: 'x' });
                window.swarm.unsubscribe({ subscriptionId: 's1' });
                """.trimIndent(),
                "call", 1, null,
            )
            assertEquals("s1:aGk=", cx.evaluateString(scope, "got.join(',')", "got", 1, null).toString())
            // A subscribe or send can wait on a sheet: the long timer. Unsubscribe never asks.
            assertEquals("300000,300000,60000", cx.evaluateString(scope, "timers.join(',')", "timers", 1, null).toString())
        } finally {
            RhinoContext.exit()
        }
    }

    private companion object {
        val OVERLAY = "a1b2" + "cd".repeat(30)
        val PSS_KEY = "03" + "ef".repeat(32)
        val RECIPIENT = "02" + "12".repeat(32)
    }
}
