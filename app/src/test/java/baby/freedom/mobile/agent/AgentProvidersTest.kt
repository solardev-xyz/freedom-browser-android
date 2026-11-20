package baby.freedom.mobile.agent

import baby.freedom.mobile.data.AesGcmCipher
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings → AI's providers against a fake server (#573): nothing is
 * sent while AI is off, turning it off or replacing a key stops what's
 * in flight and drops its result, and the bounds hold.
 */
class AgentProvidersTest {
    private val server = MockWebServer()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file = MemoryStore()
    private val modelFile = MemoryStore()
    private val gate = RequestGate()
    private lateinit var providers: AgentProviders
    private lateinit var base: HttpUrl

    /** Answers by path; [stall] holds the next matching answer's body until released. */
    private val routes = HashMap<String, () -> MockResponse>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.requestUrl!!.encodedPath]?.invoke() ?: MockResponse().setResponseCode(404)
        }
        server.start()
        // By its loopback address: the machine's own name isn't a local one to the rules.
        base = server.url("/").newBuilder().host("127.0.0.1").build()
        providers = AgentProviders(
            AgentStore(file, modelFile, AesGcmCipher { aes }),
            gate,
            ProviderClient(gate),
            hostedBase = { def -> base.toString().removeSuffix("/") + "/" + def.id },
        )
    }

    @After
    fun tearDown() {
        // A stalled answer the test walked away from is still sleeping on the server's side.
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
        }
    }

    private fun json(body: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun tags(vararg names: String) =
        JSONObject().put("models", JSONArray(names.map { JSONObject().put("name", it) }))

    private fun chatReply() = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "OK"))))

    private val state get() = runBlocking { providers.state.first() }
    private val models get() = runBlocking { providers.models.first() }

    private suspend fun connectOllama(): String {
        assertTrue(providers.setEnabled(true))
        return providers.saveLan(ProviderCatalog.OLLAMA, null, "", base, key = null)!!
    }

    @Test
    fun `nothing is sent while AI is off, whatever is stored`() = runBlocking {
        routes["/api/tags"] = { json(tags("llama3")) }
        val id = connectOllama()
        assertTrue(providers.saveApiKey("openrouter", "sk-or"))
        server.takeRequest(1, TimeUnit.SECONDS)
        val before = server.requestCount
        assertTrue(providers.setEnabled(false))
        assertEquals(RefreshResult.Cancelled, providers.refreshModels(id))
        assertEquals(RefreshResult.Cancelled, providers.refreshModels("openrouter"))
        assertEquals(TestResult.Cancelled, providers.testConnection(id, "llama3"))
        // Nor can anything be connected while it's off.
        assertFalse(providers.saveApiKey("openai", "sk-x"))
        assertNull(providers.saveLan(ProviderCatalog.OLLAMA, null, "", base, null))
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `a fresh process with AI off sends nothing for providers saved earlier`() = runBlocking {
        routes["/api/tags"] = { json(tags("llama3")) }
        val id = connectOllama()
        providers.setEnabled(false)
        val count = server.requestCount
        // A new process: a new gate, loaded from the stored switch.
        val fresh = RequestGate()
        val again = AgentProviders(AgentStore(file, modelFile, AesGcmCipher { aes }), fresh, ProviderClient(fresh))
        assertEquals(RefreshResult.Cancelled, again.refreshModels(id))
        assertEquals(TestResult.Cancelled, again.testConnection(id, "llama3"))
        assertEquals(count, server.requestCount)
    }

    @Test
    fun `ollama models are discovered and stored, and test connection works without a key`() = runBlocking {
        routes["/api/tags"] = { json(tags("llama3.2:3b", "qwen3:8b", "llama3.2:3b")) }
        routes["/v1/chat/completions"] = { json(chatReply()) }
        val id = connectOllama()
        assertEquals(RefreshResult.Done, providers.refreshModels(id))
        assertEquals(listOf(AgentModel("llama3.2:3b"), AgentModel("qwen3:8b")), models[id])
        assertEquals(TestResult.Ok, providers.testConnection(id, "qwen3:8b"))
        val tagsRequest = server.takeRequest()
        assertEquals("GET", tagsRequest.method)
        assertNull(tagsRequest.getHeader("Authorization"))
        val chat = server.takeRequest()
        assertEquals("/v1/chat/completions", chat.path)
        assertNull(chat.getHeader("Authorization"))
        val sent = JSONObject(chat.body.readUtf8())
        assertEquals("qwen3:8b", sent.getString("model"))
        assertEquals(ProviderClient.TEST_PROMPT, sent.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `a compatible server gets its key as a bearer header, never in the URL`() = runBlocking {
        routes["/v1/models"] = { json(JSONObject().put("data", JSONArray().put(JSONObject().put("id", "qwen")))) }
        routes["/v1/chat/completions"] = { json(chatReply()) }
        providers.setEnabled(true)
        val url = base.newBuilder().addPathSegment("v1").build()
        val id = providers.saveLan(ProviderCatalog.COMPATIBLE, null, "Home box", url, key = "lan-secret")!!
        assertTrue(id.startsWith("custom-"))
        assertEquals(RefreshResult.Done, providers.refreshModels(id))
        assertEquals(TestResult.Ok, providers.testConnection(id, "qwen"))
        repeat(2) {
            val r = server.takeRequest()
            assertEquals("Bearer lan-secret", r.getHeader("Authorization"))
            assertFalse(r.path!!.contains("lan-secret"))
        }
        assertEquals("Home box", state.connection(id)!!.name)
    }

    @Test
    fun `openrouter's public list is fetched without the key, the test prompt with it`() = runBlocking {
        routes["/openrouter/models"] = { json(JSONObject().put("data", JSONArray().put(JSONObject().put("id", "a/b")))) }
        routes["/openrouter/chat/completions"] = { json(chatReply()) }
        providers.setEnabled(true)
        assertTrue(providers.saveApiKey("openrouter", "  sk-or-123  "))
        assertEquals(RefreshResult.Done, providers.refreshModels("openrouter"))
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals(TestResult.Ok, providers.testConnection("openrouter", "a/b"))
        assertEquals("Bearer sk-or-123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `anthropic's test uses x-api-key and the Messages API`() = runBlocking {
        routes["/anthropic/messages"] = { json(JSONObject().put("content", JSONArray())) }
        providers.setEnabled(true)
        assertTrue(providers.saveApiKey("anthropic", "sk-ant"))
        // Connecting picks desktop's default model.
        assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), state.defaultModel)
        assertEquals(TestResult.Ok, providers.testConnection("anthropic", "claude-sonnet-5-5"))
        val r = server.takeRequest()
        assertEquals("sk-ant", r.getHeader("x-api-key"))
        assertEquals("2023-06-01", r.getHeader("anthropic-version"))
        assertNull(r.getHeader("Authorization"))
    }

    @Test
    fun `status codes map to what the page says, never the server's text`() = runBlocking {
        providers.setEnabled(true)
        providers.saveApiKey("venice", "sk-v")
        for ((code, expected) in listOf(
            401 to TestResult.Unauthorized, 403 to TestResult.Unauthorized, 404 to TestResult.ModelNotFound,
            429 to TestResult.RateLimited, 500 to TestResult.ServerError, 400 to TestResult.Refused,
        )) {
            routes["/venice/chat/completions"] = { MockResponse().setResponseCode(code).setBody("{\"error\":\"sk-v leaked\"}") }
            assertEquals("$code", expected, providers.testConnection("venice", "m"))
        }
        routes["/venice/chat/completions"] = { json("<html>not a reply</html>") }
        assertEquals(TestResult.BadReply, providers.testConnection("venice", "m"))
    }

    @Test
    fun `a redirect is not followed, so a key can't be moved to another host`() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            providers.setEnabled(true)
            providers.saveApiKey("venice", "sk-v")
            routes["/venice/chat/completions"] = {
                MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal").toString())
            }
            assertEquals(TestResult.Refused, providers.testConnection("venice", "m"))
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `a model list over the byte bound is refused, with or without a length`() = runBlocking {
        val id = connectOllama()
        val big = "{\"models\":[{\"name\":\"" + "a".repeat((ProviderCatalog.LAN_MAX_BYTES + 10).toInt()) + "\"}]}"
        routes["/api/tags"] = { json(big) }
        assertEquals(RefreshResult.TooLarge, providers.refreshModels(id))
        routes["/api/tags"] = { MockResponse().setChunkedBody(big, 8192) }
        assertEquals(RefreshResult.TooLarge, providers.refreshModels(id))
        assertNull(models[id])
    }

    /** A server that sends its headers, then nothing for 30 seconds. */
    private fun stalled() = MockResponse().setHeader("Content-Type", "application/json")
        .setChunkedBody(tags("late").toString(), 4)
        .setBodyDelay(30, TimeUnit.SECONDS)

    @Test
    fun `turning AI off stops a request in flight at once and drops its result`() = runBlocking {
        val id = connectOllama()
        routes["/api/tags"] = { stalled() }
        val refresh = async(kotlinx.coroutines.Dispatchers.IO) { providers.refreshModels(id) }
        // The request has reached the server, which is now holding its answer.
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        val start = System.nanoTime()
        assertTrue(providers.setEnabled(false))
        assertEquals(RefreshResult.Cancelled, withTimeout(2_000) { refresh.await() })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500)
        assertNull(models[id])
        assertEquals(0, gate.inFlight())
    }

    @Test
    fun `replacing or removing a key stops requests made with the old one`() = runBlocking {
        providers.setEnabled(true)
        providers.saveApiKey("venice", "old-key")
        routes["/venice/models"] = { stalled() }
        val refresh = async(kotlinx.coroutines.Dispatchers.IO) { providers.refreshModels("venice") }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        assertTrue(providers.saveApiKey("venice", "new-key"))
        assertEquals(RefreshResult.Cancelled, withTimeout(2_000) { refresh.await() })
        assertNull(models["venice"])
        assertEquals("new-key", state.keys["venice"])

        routes["/venice/chat/completions"] = { stalled() }
        val test = async(kotlinx.coroutines.Dispatchers.IO) { providers.testConnection("venice", "m") }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        assertTrue(providers.remove("venice"))
        assertEquals(TestResult.Cancelled, withTimeout(2_000) { test.await() })
        assertNull(state.connection("venice"))
        assertFalse("venice" in state.keys)
    }

    @Test
    fun `a request on a stale revision is refused before it is sent`() = runBlocking {
        providers.setEnabled(true)
        providers.saveApiKey("venice", "k1")
        val old = state.connection("venice")!!.revision
        providers.saveApiKey("venice", "k2")
        val client = ProviderClient(gate)
        val reply = client.fetch("venice", old, base.resolve("/venice/models")!!, emptyMap(), 1024)
        assertEquals(ProviderReply.Cancelled, reply)
        assertEquals(0, server.requestCount)
        assertTrue(state.connection("venice")!!.revision > old)
    }

    @Test
    fun `a slow first answer is waited for up to the call's deadline, not cut short by a read timeout`() = runBlocking {
        // A server loading a model sends nothing until it has a reply: no
        // separate read timeout may fire before the call's own deadline.
        assertEquals(0, ProviderClient.httpClient().readTimeoutMillis)
        assertTrue(ProviderClient.LAN_TEST_TIMEOUT_MS > ProviderClient.CALL_TIMEOUT_MS)
        val id = connectOllama()
        routes["/v1/chat/completions"] = { json(chatReply()).setHeadersDelay(1_500, TimeUnit.MILLISECONDS) }
        val client = ProviderClient(gate)
        val (url, body) = ProviderClient.testRequest(ProviderCatalog.definition(ProviderCatalog.OLLAMA)!!, base, "m")
        val revision = state.connection(id)!!.revision
        assertTrue(client.fetch(id, revision, url, emptyMap(), 4096, body, timeoutMs = 5_000) is ProviderReply.Body)
        assertEquals(TestResult.Ok, providers.testConnection(id, "m"))
    }

    @Test
    fun `a server that doesn't answer before the deadline reads as timed out, not unreachable`() = runBlocking {
        val id = connectOllama()
        val revision = state.connection(id)!!.revision
        val client = ProviderClient(gate)
        // Nothing at all before the deadline.
        routes["/api/tags"] = { json(tags("late")).setHeadersDelay(3, TimeUnit.SECONDS) }
        val url = base.resolve("/api/tags")!!
        assertEquals(ProviderReply.TimedOut, client.fetch(id, revision, url, emptyMap(), 4096, timeoutMs = 500))
        // Headers, then a body that stalls past it.
        routes["/api/tags"] = { stalled() }
        assertEquals(ProviderReply.TimedOut, client.fetch(id, revision, url, emptyMap(), 4096, timeoutMs = 500))
        assertEquals(0, gate.inFlight())
    }

    @Test
    fun `a connect that times out reads as unreachable, not as a slow server`() = runBlocking {
        val id = connectOllama()
        val revision = state.connection(id)!!.revision
        // A black-holed address: the connect itself never completes.
        val http = ProviderClient.httpClient().newBuilder()
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket() = object : java.net.Socket() {
                    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) =
                        throw java.net.SocketTimeoutException("connect timed out")
                }
                override fun createSocket(h: String?, p: Int) = throw UnsupportedOperationException()
                override fun createSocket(h: String?, p: Int, l: java.net.InetAddress?, lp: Int) =
                    throw UnsupportedOperationException()
                override fun createSocket(h: java.net.InetAddress?, p: Int) = throw UnsupportedOperationException()
                override fun createSocket(h: java.net.InetAddress?, p: Int, l: java.net.InetAddress?, lp: Int) =
                    throw UnsupportedOperationException()
            })
            .build()
        val reply = ProviderClient(gate, http).fetch(id, revision, base.resolve("/api/tags")!!, emptyMap(), 4096)
        assertEquals(ProviderReply.Unreachable, reply)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a deadline that passes before the server takes the request reads as unreachable, not stopped`() = runBlocking {
        val id = connectOllama()
        val revision = state.connection(id)!!.revision
        // Accepts the TCP connection and never answers the TLS handshake.
        java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            val held = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
            val acceptor = Thread {
                try {
                    while (true) held += silent.accept()
                } catch (e: java.io.IOException) {
                }
            }.apply { isDaemon = true; start() }
            val url = HttpUrl.Builder().scheme("https").host("127.0.0.1").port(silent.localPort).addPathSegment("x").build()
            val started = System.nanoTime()
            val reply = ProviderClient(gate).fetch(id, revision, url, emptyMap(), 4096, timeoutMs = 700)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(ProviderReply.Unreachable, reply)
            assertTrue("took $ms ms", ms in 500..5_000)
            assertTrue("the connection was accepted", held.isNotEmpty())
            assertEquals(0, gate.inFlight())
            silent.close()
            held.forEach { it.close() }
            acceptor.join(1_000)
        }
        // AI is still on and the revision unchanged, so the page doesn't say "stopped".
        assertTrue(gate.allows(id, revision))
    }

    @Test
    fun `turning AI off and on again still reads a call it stopped as stopped`() = runBlocking {
        val id = connectOllama()
        val revision = state.connection(id)!!.revision
        routes["/api/tags"] = { json(tags("late")).setHeadersDelay(3, TimeUnit.SECONDS) }
        val url = base.resolve("/api/tags")!!
        val reply = async { ProviderClient(gate).fetch(id, revision, url, emptyMap(), 4096) }
        withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
        gate.disable()
        gate.enable(mapOf(id to revision))
        assertEquals(ProviderReply.Cancelled, reply.await())
    }

    @Test
    fun `refresh models tells an answering server apart from one that can't be reached`() = runBlocking {
        val id = providers.run {
            setEnabled(true)
            saveLan(ProviderCatalog.COMPATIBLE, null, "lan", base.resolve("/v1")!!, key = null)!!
        }
        for ((code, expected) in listOf(
            401 to RefreshResult.Unauthorized, 403 to RefreshResult.Unauthorized, 404 to RefreshResult.NotFound,
            429 to RefreshResult.RateLimited, 500 to RefreshResult.ServerError, 503 to RefreshResult.ServerError,
            400 to RefreshResult.Refused, 302 to RefreshResult.Refused,
        )) {
            routes["/v1/models"] = {
                MockResponse().setResponseCode(code).setHeader("Location", "http://127.0.0.1:1/x").setBody("{}")
            }
            assertEquals("$code", expected, providers.refreshModels(id))
        }
        assertNull(models[id])
    }

    @Test
    fun `a dropped connection reads as unreachable`() = runBlocking {
        val id = connectOllama()
        routes["/api/tags"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertEquals(RefreshResult.Unreachable, providers.refreshModels(id))
    }

    @Test
    fun `favourites, default and remove`() = runBlocking {
        routes["/api/tags"] = { json(tags("a", "b")) }
        val id = connectOllama()
        providers.refreshModels(id)
        assertTrue(providers.setFavorite(id, "b", true))
        assertTrue(providers.setDefault(id, "b"))
        assertEquals(listOf("b"), state.connection(id)!!.favorites)
        assertEquals(ModelRef(id, "b"), state.defaultModel)
        assertEquals(listOf("b", "a"), orderedModels(models[id]!!, listOf("b"), "").map { it.id })
        assertEquals(AgentModel("b"), testModel(state, state.connection(id)!!, models[id]!!))
        // A favourite doesn't rewrite the model list's file.
        val modelsBefore = modelFile.state.value
        providers.setFavorite(id, "a", true)
        assertTrue(modelsBefore === modelFile.state.value)
        assertTrue(providers.remove(id))
        assertNull(state.defaultModel)
        assertNull(models[id])
    }

    @Test
    fun `changing a server's URL forgets its old models and stops its old requests`() = runBlocking {
        routes["/api/tags"] = { json(tags("a")) }
        val id = connectOllama()
        providers.refreshModels(id)
        val r1 = state.connection(id)!!.revision
        val moved = HttpUrl.Builder().scheme("http").host("192.168.1.2").port(11434).build()
        assertEquals(id, providers.saveLan(ProviderCatalog.OLLAMA, id, "", moved, null))
        assertNull(models[id])
        assertTrue(state.connection(id)!!.revision > r1)
        assertFalse(gate.allows(id, r1))
    }

    @Test
    fun `a new server address drops the old server's favourites and default model`() = runBlocking {
        routes["/api/tags"] = { json(tags("qwen3:8b", "gemma3:4b")) }
        val id = connectOllama()
        providers.refreshModels(id)
        providers.setFavorite(id, "gemma3:4b", true)
        providers.setDefault(id, "qwen3:8b")
        // The same address saved again keeps them.
        assertEquals(id, providers.saveLan(ProviderCatalog.OLLAMA, id, "", base, null))
        assertEquals(listOf("gemma3:4b"), state.connection(id)!!.favorites)
        assertEquals(ModelRef(id, "qwen3:8b"), state.defaultModel)
        // Another address is another server: nothing of the old one's follows it.
        val moved = HttpUrl.Builder().scheme("http").host("192.168.1.2").port(11434).build()
        assertEquals(id, providers.saveLan(ProviderCatalog.OLLAMA, id, "", moved, null))
        assertEquals(emptyList<String>(), state.connection(id)!!.favorites)
        assertNull(state.defaultModel)
    }

    @Test
    fun `a moved server keeps another connection's default model`() = runBlocking {
        routes["/api/tags"] = { json(tags("a")) }
        val id = connectOllama()
        assertTrue(providers.saveApiKey("openai", "sk-1"))
        providers.setDefault("openai", "gpt-x")
        val moved = HttpUrl.Builder().scheme("http").host("192.168.1.2").port(11434).build()
        providers.saveLan(ProviderCatalog.OLLAMA, id, "", moved, null)
        assertEquals(ModelRef("openai", "gpt-x"), state.defaultModel)
    }

    @Test
    fun `a compatible server whose key can't be read asks for it and sends nothing keyless`() = runBlocking {
        routes["/v1/models"] = { json(JSONObject().put("data", JSONArray().put(JSONObject().put("id", "qwen")))) }
        routes["/v1/chat/completions"] = { json(chatReply()) }
        providers.setEnabled(true)
        val url = base.newBuilder().addPathSegment("v1").build()
        val keyed = providers.saveLan(ProviderCatalog.COMPATIBLE, null, "Keyed", url, key = "lan-secret")!!
        val open = providers.saveLan(ProviderCatalog.COMPATIBLE, null, "Open", url, key = null)!!
        assertTrue(state.connection(keyed)!!.hasKey)
        assertFalse(state.connection(open)!!.hasKey)
        val before = server.requestCount
        // The Keystore key is gone.
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val g = RequestGate()
        val lost = AgentProviders(AgentStore(file, modelFile, AesGcmCipher { other }), g, ProviderClient(g))
        val s = lost.state.first()
        assertTrue(s.keysUnreadable)
        assertTrue(s.keyLost(s.connection(keyed)!!))
        assertFalse(s.keyLost(s.connection(open)!!))
        assertEquals(RefreshResult.NoKey, lost.refreshModels(keyed))
        assertEquals(TestResult.NoKey, lost.testConnection(keyed, "qwen"))
        assertEquals(before, server.requestCount)
        // A server saved without a key still works as one.
        assertEquals(TestResult.Ok, lost.testConnection(open, "qwen"))
        // Remove key: it's a server without a key again, not one with a lost key.
        assertEquals(keyed, lost.saveLan(ProviderCatalog.COMPATIBLE, keyed, "Keyed", url, key = ""))
        assertFalse(lost.state.first().keyLost(lost.state.first().connection(keyed)!!))
    }

    @Test
    fun `keys with spaces or line breaks are refused`() = runBlocking {
        providers.setEnabled(true)
        assertFalse(providers.saveApiKey("openai", "sk-a b"))
        assertFalse(providers.saveApiKey("openai", "sk-a\nInjected: 1"))
        assertFalse(providers.saveApiKey("openai", "   "))
        assertFalse(providers.saveApiKey("openai", "x".repeat(AgentProviders.MAX_KEY_LENGTH + 1)))
        assertFalse(providers.saveApiKey("ollama", "sk"))
        assertNull(state.connection("openai"))
    }

    @Test
    fun `a URL that fails the rules is never saved or dialled`() = runBlocking {
        providers.setEnabled(true)
        val public = HttpUrl.Builder().scheme("http").host("example.com").build()
        assertNull(providers.saveLan(ProviderCatalog.OLLAMA, null, "", public, null))
        // A stored URL that fails today's rules isn't dialled either.
        val id = connectOllama()
        val key = androidx.datastore.preferences.core.stringPreferencesKey("connections_v1")
        file.state.value = file.state.value.toMutablePreferences().apply {
            this[key] = AgentStore.encodeConnections(
                AgentStore.decodeConnections(this[key]).map { it.copy(baseUrl = "http://8.8.8.8") },
            )
        }
        assertEquals("http://8.8.8.8", state.connection(id)!!.baseUrl)
        assertEquals(RefreshResult.BadUrl, providers.refreshModels(id))
        assertEquals(TestResult.BadUrl, providers.testConnection(id, "m"))
    }

    @Test
    fun `keys are stored encrypted, and a blob that won't decrypt asks for them again`() = runBlocking {
        providers.setEnabled(true)
        providers.saveApiKey("openai", "sk-SECRET-123")
        val stored = file.state.value.asMap().values.joinToString()
        assertFalse(stored.contains("sk-SECRET-123"))
        // Another instance over the same file and Keystore key reads it back.
        assertEquals("sk-SECRET-123", AgentStore(file, modelFile, AesGcmCipher { aes }).state.first().keys["openai"])
        // The Keystore key is gone: the connection stays, its key reads as missing.
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val lost = AgentProviders(AgentStore(file, modelFile, AesGcmCipher { other }), RequestGate())
        val s = lost.state.first()
        assertTrue(s.keysUnreadable)
        assertTrue(s.connection("openai") != null)
        assertFalse("openai" in s.keys)
        assertEquals(TestResult.NoKey, lost.testConnection("openai", "gpt-6.1-sol"))
        // Toggling a favourite keeps the blob; saving a key replaces it.
        lost.setFavorite("openai", "o3", true)
        assertTrue(AgentStore(file, modelFile, AesGcmCipher { aes }).state.first().keys["openai"] == "sk-SECRET-123")
        assertTrue(lost.saveApiKey("openai", "sk-NEW"))
        assertEquals("sk-NEW", lost.state.first().keys["openai"])
    }

    /**
     * [aes], counting each call, and failing as a busy Keystore while [busy]
     * is set (an encrypt only while [busyEncrypt] is set too). [replaceKey]
     * clears both, as a new Keystore key would.
     */
    private inner class FlakyCipher : baby.freedom.mobile.data.SecretCipher {
        private val real = AesGcmCipher { aes }
        @Volatile var busy = false
        @Volatile var busyEncrypt = true
        @Volatile var encrypts = 0
        @Volatile var decrypts = 0
        @Volatile var replaced = 0

        override fun encrypt(plain: ByteArray): ByteArray {
            encrypts++
            if (busy && busyEncrypt) throw java.security.ProviderException("keystore busy")
            return real.encrypt(plain)
        }

        override fun replaceKey() {
            replaced++
            busy = false
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            decrypts++
            if (busy) throw java.security.ProviderException("keystore busy")
            return real.decrypt(blob)
        }
    }

    @Test
    fun `a favourite or default tap keeps the key blob and opens no key`() = runBlocking {
        val cipher = FlakyCipher()
        val p = AgentProviders(AgentStore(file, modelFile, cipher), RequestGate())
        p.setEnabled(true)
        assertTrue(p.saveApiKey("openai", "sk-ONE"))
        val key = androidx.datastore.preferences.core.stringPreferencesKey("keys_v1")
        val blob = file.state.value[key]
        assertEquals("sk-ONE", p.state.first().keys["openai"])
        val (enc, dec) = cipher.encrypts to cipher.decrypts
        p.setFavorite("openai", "o3", true)
        p.setDefault("openai", "o3")
        assertEquals(blob, file.state.value[key])
        assertEquals("sk-ONE", p.state.first().keys["openai"])
        assertEquals(enc, cipher.encrypts)
        assertEquals(dec, cipher.decrypts)
    }

    @Test
    fun `a Keystore that fails for a moment doesn't let a new key drop the others`() = runBlocking {
        val cipher = FlakyCipher()
        val p = AgentProviders(AgentStore(file, modelFile, cipher), RequestGate())
        p.setEnabled(true)
        assertTrue(p.saveApiKey("openai", "sk-ONE"))
        assertTrue(p.saveApiKey("anthropic", "sk-TWO"))
        // A fresh process, whose first read of the blob meets a busy Keystore.
        val flaky = FlakyCipher().apply { busy = true }
        val again = AgentProviders(AgentStore(file, modelFile, flaky, keyRetryMs = 20), RequestGate())
        val s = again.state.first()
        assertTrue(s.keysUnreadable)
        // The save waits for the blob rather than writing over it.
        val save = async(kotlinx.coroutines.Dispatchers.Default) { again.saveApiKey("xai", "sk-THREE") }
        delay(300)
        assertFalse(save.isCompleted)
        flaky.busy = false
        assertTrue(withTimeout(5_000) { save.await() })
        assertEquals(
            mapOf("openai" to "sk-ONE", "anthropic" to "sk-TWO", "xai" to "sk-THREE"),
            AgentStore(file, modelFile, AesGcmCipher { aes }).state.first().keys,
        )
    }

    @Test
    fun `a key blob that never opens holds up no other change, and a key saved over it gives up on it`() = runBlocking {
        val cipher = FlakyCipher()
        val p = AgentProviders(AgentStore(file, modelFile, cipher), RequestGate())
        p.setEnabled(true)
        assertTrue(p.saveApiKey("openai", "sk-ONE"))
        assertTrue(p.saveApiKey("anthropic", "sk-TWO"))
        val key = androidx.datastore.preferences.core.stringPreferencesKey("keys_v1")
        val blob = file.state.value[key]
        // Every decrypt fails as if the Keystore were busy, and never stops (a damaged Keystore key).
        val flaky = FlakyCipher().apply { busy = true; busyEncrypt = false }
        val stuck = AgentProviders(AgentStore(file, modelFile, flaky, keyRetryMs = 20, keyGiveUpMs = 300), RequestGate())
        assertTrue(stuck.state.first().keysUnreadable)
        assertTrue(stuck.setFavorite("openai", "o3", true))
        assertTrue(stuck.setDefault("openai", "o3"))
        assertTrue(stuck.remove("anthropic"))
        assertTrue(stuck.setEnabled(false))
        assertEquals(blob, file.state.value[key])
        // What's on file, read by an instance whose Keystore works.
        val fine = AgentStore(file, modelFile, AesGcmCipher { aes }).state.first()
        assertFalse(fine.enabled)
        assertNull(fine.connection("anthropic"))
        assertEquals(mapOf("openai" to "sk-ONE"), fine.keys)
        assertEquals(listOf("o3"), fine.connection("openai")!!.favorites.filter { it == "o3" })
        // A key typed again is saved once the blob has failed long enough.
        assertTrue(stuck.setEnabled(true))
        assertTrue(withTimeout(5_000) { stuck.saveApiKey("openai", "sk-AGAIN") })
        assertEquals(mapOf("openai" to "sk-AGAIN"), stuck.state.first().keys)
        assertFalse(stuck.state.first().keysUnreadable)
        assertEquals(0, flaky.replaced)
    }

    @Test
    fun `a Keystore key that can't encrypt either is replaced once its blob is given up on`() = runBlocking {
        val cipher = FlakyCipher()
        val p = AgentProviders(AgentStore(file, modelFile, cipher), RequestGate())
        p.setEnabled(true)
        assertTrue(p.saveApiKey("openai", "sk-ONE"))
        // Both directions fail until the key is replaced.
        val broken = FlakyCipher().apply { busy = true }
        val stuck = AgentProviders(AgentStore(file, modelFile, broken, keyRetryMs = 20, keyGiveUpMs = 200), RequestGate())
        assertTrue(stuck.state.first().keysUnreadable)
        assertTrue(withTimeout(5_000) { stuck.saveApiKey("anthropic", "sk-TWO") })
        assertEquals(1, broken.replaced)
        val s = stuck.state.first()
        assertEquals(mapOf("anthropic" to "sk-TWO"), s.keys)
        // The key it gave up on asks to be entered again.
        assertTrue(s.keyLost(s.connection("openai")!!))
    }

    @Test
    fun `a key removed while its blob can't be opened doesn't come back when it opens`() = runBlocking {
        val cipher = FlakyCipher()
        val p = AgentProviders(AgentStore(file, modelFile, cipher), RequestGate(), ProviderClient(gate))
        p.setEnabled(true)
        val url = base.newBuilder().addPathSegment("v1").build()
        val removed = p.saveLan(ProviderCatalog.COMPATIBLE, null, "Removed", url, key = "secret-one")!!
        val moved = p.saveLan(ProviderCatalog.COMPATIBLE, null, "Moved", url, key = "secret-two")!!
        val flaky = FlakyCipher().apply { busy = true }
        val stuck = AgentProviders(AgentStore(file, modelFile, flaky, keyRetryMs = 20), RequestGate())
        assertTrue(stuck.state.first().keysUnreadable)
        assertEquals(removed, stuck.saveLan(ProviderCatalog.COMPATIBLE, removed, "Removed", url, key = ""))
        val elsewhere = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(9).addPathSegment("v1").build()
        assertEquals(moved, stuck.saveLan(ProviderCatalog.COMPATIBLE, moved, "Moved", elsewhere, key = null))
        // The blob still holds both keys, but neither goes with its connection now.
        val s = AgentStore(file, modelFile, AesGcmCipher { aes }).state.first()
        assertFalse(s.keysUnreadable)
        assertTrue(s.keys.isEmpty())
        assertFalse(s.keyLost(s.connection(removed)!!))
        assertFalse(s.keyLost(s.connection(moved)!!))
    }

    @Test
    fun `a key blob the Keystore couldn't open for a moment reads again without a write`() = runBlocking {
        val p = AgentProviders(AgentStore(file, modelFile, AesGcmCipher { aes }), RequestGate())
        p.setEnabled(true)
        assertTrue(p.saveApiKey("openai", "sk-ONE"))
        // A fresh process whose first read meets a busy Keystore; nothing writes the file after.
        val flaky = FlakyCipher().apply { busy = true }
        val store = AgentStore(file, modelFile, flaky, keyRetryMs = 20)
        val seen = java.util.Collections.synchronizedList(ArrayList<AgentState>())
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { store.state.collect { seen += it } }
        withTimeout(5_000) { while (seen.isEmpty()) delay(5) }
        assertTrue(seen.first().keysUnreadable)
        assertTrue(seen.first().keyLost(seen.first().connection("openai")!!))
        val tries = flaky.decrypts
        withTimeout(5_000) { while (flaky.decrypts <= tries + 1) delay(5) }
        flaky.busy = false
        withTimeout(5_000) { while (seen.last().keysUnreadable) delay(5) }
        assertEquals("sk-ONE", seen.last().keys["openai"])
        // Once it opened, it isn't read again.
        val after = flaky.decrypts
        delay(200)
        assertEquals(after, flaky.decrypts)
        job.cancel()
    }

    @Test
    fun `a missing model list asks to check the URL only where the user typed one`() {
        fun text(id: String) = refreshNotFoundText(ProviderCatalog.definition(id)!!)
        for (hosted in listOf("openai", "anthropic", "openrouter", "venice", "near-ai")) {
            assertEquals(baby.freedom.mobile.R.string.agent_refresh_not_found_hosted, text(hosted))
        }
        assertEquals(baby.freedom.mobile.R.string.agent_refresh_not_found, text(ProviderCatalog.OLLAMA))
        assertEquals(baby.freedom.mobile.R.string.agent_refresh_not_found_compatible, text(ProviderCatalog.COMPATIBLE))
    }

    @Test
    fun `only a decrypt failure that will happen again counts as a lost key`() {
        assertTrue(AgentStore.isPermanent(javax.crypto.AEADBadTagException()))
        assertTrue(AgentStore.isPermanent(IllegalArgumentException()))
        assertTrue(AgentStore.isPermanent(org.json.JSONException("x")))
        assertFalse(AgentStore.isPermanent(java.security.ProviderException()))
        assertFalse(AgentStore.isPermanent(java.security.KeyStoreException()))
    }

    @Test
    fun `a test reply over the bound is not read`() = runBlocking {
        val id = connectOllama()
        routes["/v1/chat/completions"] = { MockResponse().setChunkedBody(Buffer().writeUtf8("x".repeat(400_000)), 4096) }
        assertEquals(TestResult.BadReply, providers.testConnection(id, "m"))
    }

    @Test
    fun `a server that closes its connection after each answer still answers the next request`() = runBlocking {
        // On the emulator an HTTP/1.0 server's closed connection, reused, failed the request after it.
        routes["/api/tags"] = { json(tags("a")).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END) }
        routes["/v1/chat/completions"] = { json(chatReply()).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END) }
        val id = connectOllama()
        assertEquals(RefreshResult.Done, providers.refreshModels(id))
        assertEquals(TestResult.Ok, providers.testConnection(id, "a"))
        assertEquals(RefreshResult.Done, providers.refreshModels(id))
    }

    @Test
    fun `a saved key stays with its server's address, and can be removed`() = runBlocking {
        routes["/v1/models"] = { json(JSONObject().put("data", JSONArray().put(JSONObject().put("id", "qwen")))) }
        providers.setEnabled(true)
        val url = base.newBuilder().addPathSegment("v1").build()
        val id = providers.saveLan(ProviderCatalog.COMPATIBLE, null, "Box", url, key = "lan-secret")!!
        // Same address, no key typed: the key stays.
        assertEquals(id, providers.saveLan(ProviderCatalog.COMPATIBLE, id, "Box", url, key = null))
        assertEquals("lan-secret", state.keys[id])
        // A new address with no key typed: the old key doesn't follow it there.
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(json(JSONObject().put("data", JSONArray().put(JSONObject().put("id", "x")))))
            val moved = other.url("/v1").newBuilder().host("127.0.0.1").build()
            assertEquals(id, providers.saveLan(ProviderCatalog.COMPATIBLE, id, "Box", moved, key = null))
            assertFalse(id in state.keys)
            assertFalse(state.connection(id)!!.hasKey)
            assertEquals(RefreshResult.Done, providers.refreshModels(id))
            assertNull(other.takeRequest().getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
        // A key saved, then removed with "".
        providers.saveLan(ProviderCatalog.COMPATIBLE, id, "Box", url, key = "k2")
        assertEquals("k2", state.keys[id])
        providers.saveLan(ProviderCatalog.COMPATIBLE, id, "Box", url, key = "")
        assertFalse(id in state.keys)
        assertFalse(state.connection(id)!!.hasKey)
    }
}
