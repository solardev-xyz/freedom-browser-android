package baby.freedom.mobile.agent

import baby.freedom.mobile.data.AesGcmCipher
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.async
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
    }
}
