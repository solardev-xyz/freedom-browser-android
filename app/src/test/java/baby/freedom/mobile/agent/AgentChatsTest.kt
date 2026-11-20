package baby.freedom.mobile.agent

import baby.freedom.mobile.data.AesGcmCipher
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Chats end to end against fake streaming servers (#574): each wire
 * format's stream with a tool round trip, Stop, AI off and a new key in
 * the middle of an answer, and the history a turn leaves behind.
 */
class AgentChatsTest {
    private val server = MockWebServer()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val gate = RequestGate()
    private lateinit var providers: AgentProviders
    private lateinit var base: HttpUrl
    private lateinit var dao: MemoryChatDao
    private lateinit var chats: AgentChats
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Answers by path, in order; each request's body is kept. */
    private val routes = HashMap<String, ArrayDeque<() -> MockResponse>>()
    private val bodies = ArrayList<JSONObject>()
    private val headers = ArrayList<okhttp3.Headers>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(bodies) {
                    bodies += JSONObject(request.body.readUtf8().ifEmpty { "{}" })
                    headers += request.headers
                }
                val queue = routes[request.requestUrl!!.encodedPath] ?: return MockResponse().setResponseCode(404)
                return synchronized(queue) { queue.removeFirstOrNull() }?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").newBuilder().host("127.0.0.1").build()
        providers = AgentProviders(
            AgentStore(MemoryStore(), MemoryStore(), AesGcmCipher { aes }),
            gate,
            ProviderClient(gate),
            hostedBase = { def -> base.toString().removeSuffix("/") + "/" + def.id },
        )
        dao = MemoryChatDao()
        chats = AgentChats(dao, providers, "You are Freedom Agent. {{identity}}", scope = scope, zone = { ZoneId.of("UTC") })
    }

    @After
    fun tearDown() {
        scope.cancel()
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
        }
    }

    private fun route(path: String, vararg answers: () -> MockResponse) {
        routes.getOrPut(path) { ArrayDeque() }.addAll(answers)
    }

    private fun sse(vararg events: String): MockResponse =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString("") { "$it\n\n" })

    private fun data(json: String) = "data: $json"

    private fun chatText(text: String) = data(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))).toString(),
    )

    private val chatStop = data("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")

    private suspend fun connectOllama(): ModelRef {
        assertTrue(providers.setEnabled(true))
        val id = providers.saveLan(ProviderCatalog.OLLAMA, null, "", base, key = null)!!
        return ModelRef(id, "llama3")
    }

    private suspend fun connectHosted(providerId: String, model: String): ModelRef {
        assertTrue(providers.setEnabled(true))
        // Saving a key fetches the model list once, when the provider lists them.
        assertTrue(providers.saveApiKey(providerId, "sk-test-123"))
        return ModelRef(providerId, model)
    }

    private suspend fun awaitDone(sessionId: String, on: AgentChats = chats): ChatTurn = withTimeout(10_000) {
        while (sessionId in on.live.value) delay(10)
        dao.turns(sessionId).last()
    }

    @Test
    fun `ollama - a reply streams in, a tool runs, and the turn is saved complete`() = runBlocking {
        val model = connectOllama()
        route(
            "/v1/chat/completions",
            {
                sse(
                    chatText("Let me look."),
                    data("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"current_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""),
                    "data: [DONE]",
                )
            },
            { sse(chatText("It is "), chatText("morning."), chatStop, "data: [DONE]") },
        )
        val id = chats.send(null, "What time is it?\nThanks", model)!!
        val turn = awaitDone(id)
        assertEquals(ChatStatus.COMPLETED, turn.status)
        assertEquals("Let me look.\n\nIt is morning.", turn.assistantText)
        assertNull(turn.error)
        assertEquals("What time is it?", dao.session(id)!!.title)
        val second = bodies.last()
        val messages = second.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").startsWith("You are Freedom Agent."))
        assertTrue(messages.getJSONObject(0).getString("content").contains("\"providerId\":\"ollama\""))
        assertTrue(messages.getJSONObject(0).getString("content").contains("Current time from Freedom's device clock"))
        val toolResult = messages.getJSONObject(3)
        assertEquals("tool", toolResult.getString("role"))
        assertEquals("call_0", toolResult.getString("tool_call_id"))
        assertTrue(toolResult.getString("content").contains("localDate"))
    }

    @Test
    fun `a chat keeps its approval mode, tells the model each request, and classifies on the turn's own model`() = runBlocking {
        val model = connectOllama()
        lateinit var withTools: AgentChats
        val seen = ArrayList<InteractionClassification>()
        val probe = object : AgentTool {
            override val spec = ToolSpec("probe", "test", JSONObject().put("type", "object"))
            override suspend fun run(arguments: JSONObject): ToolOutcome {
                val sessionId = dao.sessions().first().single().id
                val approvals = withTools.approvalsOf(sessionId)
                // The user switches mode mid-turn: the next request says so.
                assertTrue(withTools.setMode(sessionId, ApprovalMode.AllowWebsiteInteractions))
                assertEquals(ApprovalMode.AllowWebsiteInteractions, approvals.mode)
                seen += approvals.classifyInteraction(InteractionInput("browser_click", "Open it", "example.com", label = "Result"))
                return ToolOutcome("{}")
            }
        }
        withTools = AgentChats(dao, providers, "You are Freedom Agent. {{identity}}", toolsFor = { ToolRegistry(listOf(probe)) }, scope = scope, zone = { ZoneId.of("UTC") })
        route(
            "/v1/chat/completions",
            { sse(data("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"probe","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""), "data: [DONE]") },
            { sse(chatText("""{"kind":"ordinary","confidence":0.9,"summary":"Opens the result.","uncertainties":[]}"""), chatStop, "data: [DONE]") },
            { sse(chatText("Done."), chatStop, "data: [DONE]") },
        )
        val id = withTools.send(null, "open the first result", model, ApprovalMode.SensitiveActions)!!
        assertEquals(ApprovalMode.SensitiveActions.wire, dao.session(id)!!.approvalMode)
        assertEquals("Done.", awaitDone(id, withTools).assistantText)
        assertEquals(ApprovalMode.AllowWebsiteInteractions.wire, dao.session(id)!!.approvalMode)
        fun systemOf(body: JSONObject) = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(systemOf(bodies[0]).contains("approval mode is sensitive_actions"))
        // The classifier: the same model, its own system prompt, no tools, no history.
        val check = bodies[1]
        assertEquals("llama3", check.getString("model"))
        assertFalse(check.has("tools"))
        assertTrue(systemOf(check).startsWith(InteractionIntentClassifier.PROTOCOL))
        assertEquals(2, check.getJSONArray("messages").length())
        assertTrue(check.getJSONArray("messages").getJSONObject(1).getString("content").contains("\"userRequest\":\"open the first result\""))
        assertEquals(InteractionKind.ORDINARY, seen.single().kind)
        assertTrue(systemOf(bodies[2]).contains("approval mode is allow_website_interactions"))
        // No turn running: the classifier has no model, and says it's uncertain.
        assertEquals(InteractionKind.UNCERTAIN, withTools.approvalsOf(id).classifyInteraction(InteractionInput("browser_click", "", "example.com")).kind)
        // A new chat without a mode asks every time.
        route("/v1/chat/completions", { sse(chatText("Hi"), chatStop) })
        val plain = withTools.send(null, "hi", model)!!
        awaitDone(plain, withTools)
        assertEquals(ApprovalMode.EveryInteraction.wire, dao.session(plain)!!.approvalMode)
        assertFalse(withTools.setMode("gone", ApprovalMode.SensitiveActions))
    }

    @Test
    fun `a second message carries the first turn as history`() = runBlocking {
        val model = connectOllama()
        route("/v1/chat/completions", { sse(chatText("Hi!"), chatStop) }, { sse(chatText("Again."), chatStop) })
        val id = chats.send(null, "hello", model)!!
        awaitDone(id)
        assertEquals(id, chats.send(id, "once more", model))
        assertEquals("Again.", awaitDone(id).assistantText)
        val messages = bodies.last().getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals("Hi!", messages.getJSONObject(2).getString("content"))
        assertEquals(listOf(0, 1), dao.turns(id).map { it.position })
    }

    @Test
    fun `anthropic - streams with the key in its header and tool results paired`() = runBlocking {
        val model = connectHosted("anthropic", "claude-sonnet-5-5")
        route(
            "/anthropic/messages",
            {
                sse(
                    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}",
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"current_time\"}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}}",
                    "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}",
                )
            },
            {
                sse(
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Noon.\"}}",
                    "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}",
                )
            },
        )
        val id = chats.send(null, "time?", model)!!
        val turn = awaitDone(id)
        assertEquals(ChatStatus.COMPLETED, turn.status)
        assertEquals("Noon.", turn.assistantText)
        assertEquals("sk-test-123", headers.last()["x-api-key"])
        val messages = bodies.last().getJSONArray("messages")
        assertEquals("tool_result", messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("toolu_1", messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
    }

    @Test
    fun `responses - streams with a bearer key, store off, and a function call round trip`() = runBlocking {
        val model = connectHosted("xai", "grok-4.7")
        route(
            "/xai/responses",
            {
                sse(
                    data("""{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"current_time","arguments":""}}"""),
                    data("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}"""),
                    data("""{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"current_time","arguments":"{}"}}"""),
                    data("""{"type":"response.completed","response":{"status":"completed"}}"""),
                )
            },
            {
                sse(
                    data("""{"type":"response.output_text.delta","output_index":0,"delta":"Done."}"""),
                    data("""{"type":"response.completed","response":{"status":"completed"}}"""),
                )
            },
        )
        val id = chats.send(null, "time?", model)!!
        assertEquals("Done.", awaitDone(id).assistantText)
        assertEquals("Bearer sk-test-123", headers.last()["Authorization"])
        val body = bodies.last()
        assertFalse(body.getBoolean("store"))
        val input = body.getJSONArray("input")
        assertEquals("system", input.getJSONObject(0).getString("role"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
        assertEquals("call_1", input.getJSONObject(3).getString("call_id"))
    }

    @Test
    fun `ollama - a model that takes no tools still chats, and later turns offer it none`() = runBlocking {
        val model = connectOllama()
        val noTools = { MockResponse().setResponseCode(400).setBody("""{"error":"gemma3 does not support tools"}""") }
        route(
            "/v1/chat/completions",
            noTools,
            { sse(chatText("Hello there."), chatStop, "data: [DONE]") },
            { sse(chatText("Again."), chatStop, "data: [DONE]") },
        )
        val id = chats.send(null, "hi", model)!!
        val turn = awaitDone(id)
        assertEquals(ChatStatus.COMPLETED, turn.status)
        assertEquals("Hello there.", turn.assistantText)
        assertNull(turn.error)
        assertTrue(bodies[0].has("tools"))
        assertTrue(!bodies[1].has("tools"))
        // The system prompt still tells it the time.
        assertTrue(bodies[1].getJSONArray("messages").getJSONObject(0).getString("content").contains("Current time from Freedom's device clock"))
        assertEquals(id, chats.send(id, "once more", model))
        assertEquals("Again.", awaitDone(id).assistantText)
        assertEquals(3, bodies.size)
        assertTrue(!bodies[2].has("tools"))
    }

    @Test
    fun `a 400 that isn't about tools still fails as refused, after one try without them`() = runBlocking {
        val model = connectOllama()
        val bad = { MockResponse().setResponseCode(400) }
        route("/v1/chat/completions", bad, bad, { sse(chatText("Fine."), chatStop, "data: [DONE]") })
        val id = chats.send(null, "hi", model)!!
        val turn = awaitDone(id)
        assertEquals(ChatStatus.FAILED, turn.status)
        assertEquals(ChatError.Refused.name, turn.error)
        assertEquals(2, bodies.size)
        // Not remembered as a model without tools: the next turn offers them again.
        chats.send(id, "again", model)
        awaitDone(id)
        assertTrue(bodies[2].has("tools"))
    }

    /** The chat requests sent so far (saving a key also fetches the model list). */
    private fun chatBodies() = synchronized(bodies) { bodies.filter { it.has("messages") } }

    @Test
    fun `openrouter - a 404 saying no endpoint takes tools is asked again without them, and remembered`() = runBlocking {
        val model = connectHosted("openrouter", "deepseek/deepseek-r1:free")
        val noTools = {
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"No endpoints found that support tool use. To learn more about provider routing, visit: https://openrouter.ai/docs/provider-routing","code":404}}""")
        }
        route(
            "/openrouter/chat/completions",
            noTools,
            { sse(chatText("Hello there."), chatStop, "data: [DONE]") },
            { sse(chatText("Again."), chatStop, "data: [DONE]") },
        )
        val id = chats.send(null, "hi", model)!!
        val turn = awaitDone(id)
        assertEquals(ChatStatus.COMPLETED, turn.status)
        assertEquals("Hello there.", turn.assistantText)
        assertNull(turn.error)
        assertTrue(chatBodies()[0].has("tools"))
        assertTrue(!chatBodies()[1].has("tools"))
        assertEquals(id, chats.send(id, "once more", model))
        assertEquals("Again.", awaitDone(id).assistantText)
        assertEquals(3, chatBodies().size)
        assertTrue(!chatBodies()[2].has("tools"))
    }

    @Test
    fun `hosted - a 400 or 404 that doesn't say so is tried once, and tools stay offered`() = runBlocking {
        val model = connectHosted("openrouter", "some/model")
        // A request at the context limit: the body quotes it, tools and all.
        val tooLong = {
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"This endpoint's maximum context length is 128000 tokens.","request":{"tools":[{"type":"function"}]}}}""")
        }
        route(
            "/openrouter/chat/completions",
            tooLong,
            { MockResponse().setResponseCode(404).setBody("""{"error":{"message":"No such model"}}""") },
            { MockResponse().setResponseCode(404) },
            { sse(chatText("Fine."), chatStop, "data: [DONE]") },
        )
        val id = chats.send(null, "hi", model)!!
        val first = awaitDone(id)
        assertEquals(ChatStatus.FAILED, first.status)
        assertEquals(ChatError.Refused.name, first.error)
        assertEquals(1, chatBodies().size)
        chats.send(id, "again", model)
        assertEquals(ChatError.ModelNotFound.name, awaitDone(id).error)
        assertEquals(2, chatBodies().size)
        chats.send(id, "and again", model)
        assertEquals(ChatError.ModelNotFound.name, awaitDone(id).error)
        assertEquals(3, chatBodies().size)
        chats.send(id, "last", model)
        assertEquals("Fine.", awaitDone(id).assistantText)
        assertTrue(chatBodies().all { it.has("tools") })
    }

    @Test
    fun `hosted - a refusal saying so only past what's read, or cut off mid-body, isn't a no-tools one`() = runBlocking {
        val model = connectHosted("openrouter", "some/model")
        val late = {
            MockResponse().setResponseCode(404)
                .setBody("x".repeat(ProviderClient.REFUSAL_PEEK_BYTES.toInt()) + "No endpoints found that support tool use")
        }
        val cut = {
            MockResponse().setResponseCode(404).setBody("""{"error":"No endpoints found that support tool use"}""")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        route("/openrouter/chat/completions", late, cut)
        val id = chats.send(null, "hi", model)!!
        assertEquals(ChatError.ModelNotFound.name, awaitDone(id).error)
        assertEquals(1, chatBodies().size)
        chats.send(id, "again", model)
        assertEquals(ChatError.ModelNotFound.name, awaitDone(id).error)
        assertEquals(2, chatBodies().size)
    }

    @Test
    fun `venice - a 400 saying tools aren't supported is asked again without them, and remembered (R5-F1)`() = runBlocking {
        val model = connectHosted("venice", "venice-uncensored")
        val noTools = {
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"Invalid request","details":{"_errors":[],"tools":{"_errors":["tools is not supported by this model"]}}}""")
        }
        route(
            "/venice/chat/completions",
            noTools,
            { sse(chatText("Hello there."), chatStop, "data: [DONE]") },
            { sse(chatText("Again."), chatStop, "data: [DONE]") },
        )
        val id = chats.send(null, "hi", model)!!
        val turn = awaitDone(id)
        assertEquals(ChatStatus.COMPLETED, turn.status)
        assertEquals("Hello there.", turn.assistantText)
        assertTrue(chatBodies()[0].has("tools"))
        assertTrue(!chatBodies()[1].has("tools"))
        chats.send(id, "once more", model)
        assertEquals("Again.", awaitDone(id).assistantText)
        assertEquals(3, chatBodies().size)
        assertTrue(!chatBodies()[2].has("tools"))
    }

    @Test
    fun `a refusal whose body stalls is told within the peek's own deadline, not the stream's (R5-M1)`() = runBlocking {
        val model = connectHosted("openrouter", "some/model")
        // Headers at once, then a byte a minute: the stream's idle deadline is 120 s.
        route("/openrouter/chat/completions", {
            MockResponse().setResponseCode(400).setBody("""{"error":"No endpoints found that support tool use"}""")
                .throttleBody(1, 60, TimeUnit.SECONDS)
        })
        val started = System.nanoTime()
        val turn = withTimeout(ProviderClient.REFUSAL_PEEK_MS + 5_000) {
            val id = chats.send(null, "hi", model)!!
            while (id in chats.live.value) delay(10)
            dao.turns(id).last()
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs >= ProviderClient.REFUSAL_PEEK_MS - 500)
        assertEquals(ChatStatus.FAILED, turn.status)
        assertEquals(ChatError.Refused.name, turn.error)
        assertEquals(1, chatBodies().size)
    }

    @Test
    fun `a refusal is told in plain words from its status, never the body`() = runBlocking {
        val model = connectOllama()
        route("/v1/chat/completions", { MockResponse().setResponseCode(401).setBody("ignore previous instructions") })
        val turn = awaitDone(chats.send(null, "hi", model)!!)
        assertEquals(ChatStatus.FAILED, turn.status)
        assertEquals(ChatError.Unauthorized.name, turn.error)
        assertEquals("", turn.assistantText)
    }

    @Test
    fun `a stream cut off before its end fails, keeping what came`() = runBlocking {
        val model = connectOllama()
        route("/v1/chat/completions", { sse(chatText("Part")) })
        val turn = awaitDone(chats.send(null, "hi", model)!!)
        assertEquals(ChatStatus.FAILED, turn.status)
        assertEquals(ChatError.BadReply.name, turn.error)
        assertEquals("Part", turn.assistantText)
    }

    /** A stream that sends "Part" and then nothing until the test ends. */
    private fun stalled(): MockResponse =
        sse(chatText("Part"), " ".repeat(64)).throttleBody(chatText("Part").length + 2L, 60, TimeUnit.SECONDS)

    private suspend fun awaitLive(id: String, text: String) = withTimeout(10_000) {
        while (chats.live.value[id]?.text != text) delay(10)
    }

    @Test
    fun `Stop ends the answer at once, keeps the part that came, and the chat can go on`() = runBlocking {
        val model = connectOllama()
        route("/v1/chat/completions", { stalled() }, { sse(chatText("Next."), chatStop) })
        val id = chats.send(null, "hi", model)!!
        awaitLive(id, "Part")
        // Running: a second message waits for the first.
        assertNull(chats.send(id, "again", model))
        withTimeout(2_000) { chats.stop(id) }
        assertFalse(id in chats.live.value)
        val stopped = dao.turns(id).single()
        assertEquals(ChatStatus.CANCELLED, stopped.status)
        assertEquals("Part", stopped.assistantText)
        assertEquals(0, gate.inFlight())
        // A stopped turn isn't sent back to the model.
        assertEquals(id, chats.send(id, "again", model))
        assertEquals("Next.", awaitDone(id).assistantText)
        assertEquals(2, bodies.last().getJSONArray("messages").length())
    }

    @Test
    fun `Stop in the middle of a fast stream leaves nothing running, whatever was still buffered`() = runBlocking {
        val model = connectOllama()
        val chunks = Array(20_000) { chatText("w$it ") }
        // Many small pieces, quickly: some are always already buffered when Stop lands.
        route("/v1/chat/completions", { sse(*chunks, chatStop).throttleBody(4_096, 1, TimeUnit.MILLISECONDS) })
        val id = chats.send(null, "hi", model)!!
        withTimeout(10_000) { while ((chats.live.value[id]?.text?.length ?: 0) < 50) delay(1) }
        withTimeout(2_000) { chats.stop(id) }
        delay(300)
        assertTrue(chats.live.value.isEmpty())
        val turn = dao.turns(id).single()
        assertEquals(ChatStatus.CANCELLED, turn.status)
        assertTrue(turn.assistantText.startsWith("w0 w1 "))
        assertEquals(0, gate.inFlight())
        // And the chat takes the next message.
        route("/v1/chat/completions", { sse(chatText("ok"), chatStop) })
        assertEquals(id, chats.send(id, "again", model))
        assertEquals("ok", awaitDone(id).assistantText)
    }

    @Test
    fun `a server that stops sending ends the answer as timed out, keeping what came`() = runBlocking {
        val gate2 = RequestGate()
        val p2 = AgentProviders(
            AgentStore(MemoryStore(), MemoryStore(), AesGcmCipher { aes }),
            gate2,
            ProviderClient(gate2, streamIdleMs = 300),
        )
        assertTrue(p2.setEnabled(true))
        val model = ModelRef(p2.saveLan(ProviderCatalog.OLLAMA, null, "", base, key = null)!!, "llama3")
        val dao2 = MemoryChatDao()
        val c2 = AgentChats(dao2, p2, "{{identity}}", scope = scope)
        route("/v1/chat/completions", { stalled() })
        val id = c2.send(null, "hi", model)!!
        val turn = withTimeout(10_000) {
            while (id in c2.live.value) delay(10)
            dao2.turns(id).single()
        }
        assertEquals(ChatStatus.FAILED, turn.status)
        assertEquals(ChatError.TimedOut.name, turn.error)
        assertEquals("Part", turn.assistantText)
        assertEquals(0, gate2.inFlight())
    }

    @Test
    fun `turning AI off ends a running answer and says why`() = runBlocking {
        val model = connectOllama()
        route("/v1/chat/completions", { stalled() })
        val id = chats.send(null, "hi", model)!!
        awaitLive(id, "Part")
        assertTrue(providers.setEnabled(false))
        val turn = awaitDone(id)
        assertEquals(ChatStatus.CANCELLED, turn.status)
        assertEquals(ChatError.AiOff.name, turn.error)
        assertEquals("Part", turn.assistantText)
        // And nothing more is sent while it's off.
        val sent = server.requestCount
        assertNotNull(chats.send(id, "again", model))
        assertEquals(ChatError.AiOff.name, awaitDone(id).error)
        assertEquals(sent, server.requestCount)
    }

    @Test
    fun `a new key ends an answer the old one started (L1)`() = runBlocking {
        val model = connectHosted("openrouter", "some/model")
        route("/openrouter/chat/completions", { stalled() })
        val id = chats.send(null, "hi", model)!!
        awaitLive(id, "Part")
        assertTrue(providers.saveApiKey("openrouter", "sk-new-456"))
        val turn = awaitDone(id)
        assertEquals(ChatStatus.CANCELLED, turn.status)
        assertEquals(ChatError.KeyChanged.name, turn.error)
        assertEquals(0, gate.inFlight())
    }

    @Test
    fun `deleting a running chat stops it first`() = runBlocking {
        val model = connectOllama()
        route("/v1/chat/completions", { stalled() })
        val id = chats.send(null, "hi", model)!!
        awaitLive(id, "Part")
        assertTrue(withTimeout(2_000) { chats.delete(id) })
        assertNull(dao.session(id))
        assertTrue(dao.turns(id).isEmpty())
        assertTrue(chats.live.value.isEmpty())
        assertEquals(0, gate.inFlight())
    }

    /** Deletes a running chat from a scope that's cancelled while its turn's final write waits. */
    private suspend fun deleteFromALeftPage(all: Boolean) {
        val model = connectOllama()
        route("/v1/chat/completions", { stalled() })
        val id = chats.send(null, "hi", model)!!
        awaitLive(id, "Part")
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        dao.finishGate = release
        val page = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        page.launch { if (all) chats.deleteAll() else chats.delete(id) }
        withTimeout(2_000) { dao.finishing.await() }
        page.cancel()
        release.complete(Unit)
        withTimeout(2_000) { while (dao.session(id) != null || chats.live.value.isNotEmpty()) delay(10) }
        assertNull(dao.session(id))
        assertTrue(dao.turns(id).isEmpty())
        assertEquals(0, gate.inFlight())
    }

    @Test
    fun `a delete whose caller is cancelled while the turn winds down still deletes (R1-M2)`() = runBlocking {
        deleteFromALeftPage(all = false)
    }

    @Test
    fun `a delete-all whose caller is cancelled while a turn winds down still deletes (R1-M2)`() = runBlocking {
        deleteFromALeftPage(all = true)
    }

    @Test
    fun `publication and node operation receipts are forgotten only once the chats are really deleted`() = runBlocking {
        val forgotten = ArrayList<String?>()
        val nodeReceipts = ArrayList<String?>()
        val c = AgentChats(
            dao, providers, "{{identity}}", scope = scope,
            forgetNodeOperations = { nodeReceipts += it },
            forgetPublications = { forgotten += it },
        )
        dao.failDeletes = true
        assertFalse(c.deleteAll())
        assertFalse(c.delete("chat"))
        assertTrue(forgotten.isEmpty())
        // A chat that stays keeps its node receipts too (R1-F2).
        assertTrue(nodeReceipts.isEmpty())
        dao.failDeletes = false
        assertTrue(c.delete("chat"))
        assertTrue(c.deleteAll())
        assertEquals(listOf("chat", null), forgotten)
        assertEquals(listOf("chat", null), nodeReceipts)
    }

    @Test
    fun `a provider that's gone is said before anything is sent`() = runBlocking {
        connectOllama()
        val turn = awaitDone(chats.send(null, "hi", ModelRef("nope", "m"))!!)
        assertEquals(ChatError.NoModel.name, turn.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `turns a stopped app left running are marked interrupted before anything starts`() = runBlocking {
        val dao = MemoryChatDao()
        dao.start(ChatSession("s", "t", null, null, 0, 0), ChatTurn("t1", "s", 0, "hi", "par", ChatStatus.RUNNING, 0))
        val chats = AgentChats(dao, providers, "{{identity}}", scope = scope)
        val model = connectOllama()
        route("/v1/chat/completions", { sse(chatText("ok"), chatStop) })
        assertEquals("s", chats.send("s", "again", model))
        withTimeout(10_000) { while ("s" in chats.live.value) delay(10) }
        assertEquals(listOf(ChatStatus.INTERRUPTED, ChatStatus.COMPLETED), dao.turns("s").map { it.status })
        // The interrupted turn isn't sent back.
        assertEquals(2, bodies.last().getJSONArray("messages").length())
    }

    @Test
    fun `compaction keeps its summary on the session`() = runBlocking {
        val model = connectOllama()
        val small = AgentChats(
            dao, providers, "{{identity}}", scope = scope,
            limitsOf = { ContextLimits(window = 1_000, maxOutput = 100) },
        )
        val big = "y".repeat(1_400)
        route(
            "/v1/chat/completions",
            { sse(chatText(big), chatStop) },
            { sse(chatText(big), chatStop) },
            { sse(chatText("SUMMARY"), chatStop) },
            { sse(chatText("ok"), chatStop) },
        )
        val id = small.send(null, "one", model)!!
        withTimeout(10_000) { while (id in small.live.value) delay(10) }
        small.send(id, "two", model)
        withTimeout(10_000) { while (id in small.live.value) delay(10) }
        small.send(id, "three", model)
        withTimeout(10_000) { while (id in small.live.value) delay(10) }
        val session = dao.session(id)!!
        // Both earlier turns (each past the recent share on its own) went into it.
        assertEquals("SUMMARY", session.summary)
        assertEquals(2, session.summaryThrough)
        val summaryRequest = bodies[bodies.size - 2]
        assertFalse(summaryRequest.has("tools"))
        val messages = bodies.last().getJSONArray("messages")
        assertEquals(3, messages.length())
        assertTrue(messages.getJSONObject(1).getString("content").contains("<summary>\nSUMMARY\n</summary>"))
        assertEquals("three", messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `a title is the first line, bounded, with nothing hidden in it`() {
        assertEquals("Plan a trip", AgentChats.titleOf("\n  Plan a trip  \nto Vienna"))
        assertEquals("a b", AgentChats.titleOf("a‮b"))
        val long = AgentChats.titleOf("x".repeat(200))
        assertEquals(AgentChats.MAX_TITLE_CHARS + 1, long.length)
        assertTrue(long.endsWith("…"))
    }

    // ---- attachments (#601) ----

    private val anthropicReply = arrayOf(
        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"A cat.\"}}",
        "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
        "event: message_stop\ndata: {\"type\":\"message_stop\"}",
    )

    private fun attachmentStore(): AgentAttachments {
        val root = java.nio.file.Files.createTempDirectory(System.getenv("TMPDIR")?.let { java.io.File(it).toPath() } ?: java.io.File(System.getProperty("java.io.tmpdir")).toPath(), "attachments").toFile()
        root.deleteOnExit()
        return AgentAttachments(root) { f -> if (f.readBytes().take(3) == "PNG".toByteArray().toList()) "image/png" else null }
    }

    private fun withAttachments(store: AgentAttachments, forgetNodeOperations: suspend (String?) -> Unit = {}) = AgentChats(
        dao, providers, "You are Freedom Agent. {{identity}}", scope = scope, zone = { ZoneId.of("UTC") },
        forgetNodeOperations = forgetNodeOperations,
        // As the app's chats have them, with a PDF processor never reached here.
        toolsFor = { id ->
            ToolRegistry(
                AttachmentTools(
                    id, { store },
                    {
                        object : PdfProcessor {
                            override suspend fun extractText(file: java.io.File, page: Int, pageCount: Int) = error("unused")
                            override suspend fun renderPage(file: java.io.File, page: Int) = error("unused")
                        }
                    },
                ).tools,
            )
        },
        attachments = store, takesImages = { providers.takesImages(it) },
    )

    private suspend fun AgentAttachments.stageText(name: String, text: String): String =
        (stage(name, null) { java.io.ByteArrayInputStream(text.toByteArray()) } as StageResult.Staged).attachment.id

    @Test
    fun `files go with the message - named to the model in this turn and the next, and shown with the turn`() = runBlocking {
        val model = connectOllama()
        val store = attachmentStore()
        val chats = withAttachments(store)
        route("/v1/chat/completions", { sse(chatText("Read it."), chatStop) }, { sse(chatText("Again."), chatStop) })
        val staged = store.stageText("notes.txt", "hello")
        val id = chats.send(null, "summarise this", model, attachmentIds = listOf(staged))!!
        val turn = awaitDone(id, chats)
        // The text shown is the user's own; the files are kept beside it.
        assertEquals("summarise this", turn.userText)
        val sent = Attachment.parseList(turn.attachments).single()
        assertEquals("notes.txt", sent.name)
        assertTrue(store.staged.value.isEmpty())
        assertEquals(listOf(sent), store.list(id))
        val first = bodies.last().getJSONArray("messages")
        val userText = first.getJSONObject(1).getString("content")
        assertTrue(userText, userText.startsWith("summarise this\n\nFreedom attached these user-selected conversation resources."))
        assertTrue(userText.contains(sent.id))
        assertTrue(first.getJSONObject(0).getString("content").contains("attachment_read"))
        // The attachment tools are offered; render_page isn't to a model not known to take images.
        val tools = (0 until bodies.last().getJSONArray("tools").length()).map {
            bodies.last().getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertTrue("attachment_read" in tools && "attachment_list" in tools)
        assertFalse("attachment_render_page" in tools)

        chats.send(id, "and now?", model)!!
        awaitDone(id, chats)
        val history = bodies.last().getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(history.contains(sent.id))
    }

    @Test
    fun `a file sent with a turn that failed is still named in the next`() = runBlocking {
        val model = connectOllama()
        val store = attachmentStore()
        val chats = withAttachments(store)
        route("/v1/chat/completions", { MockResponse().setResponseCode(500) }, { sse(chatText("ok"), chatStop) })
        val id = chats.send(null, "read this", model, attachmentIds = listOf(store.stageText("a.txt", "a")))!!
        assertEquals(ChatStatus.FAILED, awaitDone(id, chats).status)
        val file = store.list(id).single()
        chats.send(id, "try again", model)!!
        awaitDone(id, chats)
        val messages = bodies.last().getJSONArray("messages")
        // The failed turn isn't replayed; its file is named in this one.
        assertEquals(listOf("system", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        val text = messages.getJSONObject(1).getString("content")
        assertTrue(text, text.startsWith("try again\n\nFiles attached earlier in this conversation"))
        assertTrue(text.contains(file.id))
    }

    @Test
    fun `a model list stored before images were known takes them as the bundled entry says`() = runBlocking {
        val model = connectHosted("anthropic", "claude-sonnet-5-5")
        val connection = providers.state.first().connection(model.connectionId)!!
        val old = AgentStore.decodeModels("""[{"id":"claude-sonnet-5-5"},{"id":"not-bundled"}]""")
        val models = providers.modelsOf(connection, mapOf(connection.id to old))
        assertEquals(listOf<Boolean?>(true, false), models.map { it.vision })
    }

    @Test
    fun `a model that takes images gets the message's images and render_page`() = runBlocking {
        val model = connectHosted("anthropic", "claude-sonnet-5-5")
        val store = attachmentStore()
        val chats = withAttachments(store)
        route("/anthropic/messages", { sse(*anthropicReply) })
        val image = (store.stage("cat.png", null) { java.io.ByteArrayInputStream("PNG-cat".toByteArray()) } as StageResult.Staged).attachment.id
        val id = chats.send(null, "what is it?", model, attachmentIds = listOf(image))!!
        assertEquals("A cat.", awaitDone(id, chats).assistantText)
        val body = bodies.last()
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals(java.util.Base64.getEncoder().encodeToString("PNG-cat".toByteArray()), content.getJSONObject(1).getJSONObject("source").getString("data"))
        val tools = (0 until body.getJSONArray("tools").length()).map { body.getJSONArray("tools").getJSONObject(it).getString("name") }
        assertTrue("attachment_render_page" in tools)
    }

    @Test
    fun `a model not known to take images gets no image, only the note`() = runBlocking {
        val model = connectOllama()
        val store = attachmentStore()
        val chats = withAttachments(store)
        route("/v1/chat/completions", { sse(chatText("I can't see it."), chatStop) })
        val image = (store.stage("cat.png", null) { java.io.ByteArrayInputStream("PNG-cat".toByteArray()) } as StageResult.Staged).attachment.id
        val id = chats.send(null, "what is it?", model, attachmentIds = listOf(image))!!
        awaitDone(id, chats)
        val content = bodies.last().getJSONArray("messages").getJSONObject(1).get("content")
        assertTrue(content is String && content.contains("cat.png"))
    }

    @Test
    fun `a message that can't be saved puts its files back under the composer`() = runBlocking {
        val model = connectOllama()
        val store = attachmentStore()
        val chats = withAttachments(store)
        store.stageText("a.txt", "a")
        dao.failInserts = true
        assertNull(chats.send(null, "hi", model, attachmentIds = store.staged.value.map { it.id }))
        assertEquals(listOf("a.txt"), store.staged.value.map { it.name })
        // A file no longer staged: nothing is sent.
        dao.failInserts = false
        assertNull(chats.send(null, "hi", model, attachmentIds = listOf("selection_00000000000000000000")))
        assertTrue(dao.sessionIds().isEmpty())
    }

    @Test
    fun `a launch sweep while a send is between moving its files and saving its chat keeps them`() = runBlocking {
        val model = connectOllama()
        val store = attachmentStore()
        val chats = withAttachments(store)
        // A chat with files already, so the sweep has a folder to judge.
        route("/v1/chat/completions", { sse(chatText("ok"), chatStop) }, { sse(chatText("ok"), chatStop) })
        val first = chats.send(null, "one", model, attachmentIds = listOf(store.stageText("a.txt", "a")))!!
        awaitDone(first, chats)
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        dao.holdInserts = hold
        val staged = store.stageText("b.txt", "b")
        val send = CoroutineScope(Dispatchers.IO).let { scope ->
            kotlinx.coroutines.CompletableDeferred<String?>().also { out ->
                scope.launch { out.complete(chats.send(null, "two", model, attachmentIds = listOf(staged))) }
            }
        }
        // The files have moved; the chat isn't in the database yet.
        withTimeout(5_000) { while (store.staged.value.isNotEmpty()) delay(10) }
        assertEquals(listOf(first), dao.sessionIds())
        // MainActivity's sweep at that instant.
        assertEquals(0, store.sweep { dao.sessionIds().toSet() })
        hold.complete(Unit)
        val second = withTimeout(5_000) { send.await() }!!
        awaitDone(second, chats)
        assertEquals(listOf("b.txt"), store.list(second).map { it.name })
        // Committed: the next sweep judges it by the chat list, which names it.
        assertEquals(0, store.sweep { dao.sessionIds().toSet() })
        assertEquals(1, store.list(second).size)
    }

    @Test
    fun `a send landing while Delete all chats is between the database and the folders keeps its files`() = runBlocking {
        val model = connectOllama()
        val store = attachmentStore()
        // Delete all holds after the database delete, before the folders go.
        val between = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val chats = withAttachments(store) { if (it == null) { between.complete(Unit); resume.await() } }
        route("/v1/chat/completions", { sse(chatText("ok"), chatStop) })
        val io = CoroutineScope(Dispatchers.IO)
        val deleted = kotlinx.coroutines.CompletableDeferred<Boolean>().also { out ->
            io.launch { out.complete(chats.deleteAll()) }
        }
        withTimeout(5_000) { between.await() }
        val staged = store.stageText("b.txt", "b")
        val send = kotlinx.coroutines.CompletableDeferred<String?>().also { out ->
            io.launch { out.complete(chats.send(null, "two", model, attachmentIds = listOf(staged))) }
        }
        // The send waits for Delete all rather than saving a chat whose folder is about to go.
        delay(300)
        assertFalse(send.isCompleted)
        resume.complete(Unit)
        assertTrue(withTimeout(5_000) { deleted.await() })
        val id = withTimeout(5_000) { send.await() }!!
        awaitDone(id, chats)
        // The chat that survives has its file.
        assertEquals(listOf(id), dao.sessionIds())
        assertEquals(listOf("b.txt"), store.list(id).map { it.name })
    }

    @Test
    fun `deleting a chat, or every chat, deletes its files, and a start sweeps a chat's that's gone`() = runBlocking {
        val model = connectOllama()
        val store = attachmentStore()
        val chats = withAttachments(store)
        route("/v1/chat/completions", { sse(chatText("ok"), chatStop) }, { sse(chatText("ok"), chatStop) })
        val one = chats.send(null, "one", model, attachmentIds = listOf(store.stageText("a.txt", "a")))!!
        awaitDone(one, chats)
        val two = chats.send(null, "two", model, attachmentIds = listOf(store.stageText("b.txt", "b")))!!
        awaitDone(two, chats)
        assertTrue(chats.delete(one))
        assertTrue(store.list(one).isEmpty())
        assertEquals(1, store.list(two).size)
        // A chat gone from the database while its files stayed (a crash): swept at the next start.
        dao.deleteSession(two)
        val restarted = withAttachments(store)
        withTimeout(5_000) { while (store.list(two).isNotEmpty()) delay(10) }
        route("/v1/chat/completions", { sse(chatText("ok"), chatStop) })
        val three = restarted.send(null, "three", model, attachmentIds = listOf(store.stageText("c.txt", "c")))!!
        awaitDone(three, restarted)
        assertTrue(restarted.deleteAll())
        assertTrue(store.list(three).isEmpty())
    }

}

/** [ChatDao] in memory, for tests. */
internal class MemoryChatDao : ChatDao {
    private val sessions = MutableStateFlow<Map<String, ChatSession>>(emptyMap())
    private val turns = MutableStateFlow<List<ChatTurn>>(emptyList())

    override fun sessions(limit: Int): Flow<List<ChatSession>> = sessions.map { it.values.sortedByDescending { s -> s.updatedAt }.take(limit) }
    override suspend fun session(id: String) = sessions.value[id]
    override fun sessionFlow(id: String) = sessions.map { it[id] }
    override fun turnsFlow(sessionId: String) = turns.map { all -> all.filter { it.sessionId == sessionId }.sortedBy { it.position } }
    override suspend fun turns(sessionId: String) = turnsFlow(sessionId).first()
    override suspend fun nextPosition(sessionId: String) = (turns(sessionId).maxOfOrNull { it.position } ?: -1) + 1
    /** Set: saving a new chat throws, as a database that can't be written would. */
    @Volatile var failInserts = false

    /** Set: saving a new chat waits for it, as a slow database would. */
    @Volatile var holdInserts: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun sessionIds(): List<String> = sessions.value.keys.toList()
    override suspend fun insertSession(session: ChatSession) {
        holdInserts?.await()
        synchronized(this) {
            if (failInserts) throw java.io.IOException("disk")
            require(session.id !in sessions.value)
            sessions.value = sessions.value + (session.id to session)
        }
    }
    override suspend fun insertTurn(turn: ChatTurn) {
        synchronized(this) {
            require(turn.sessionId in sessions.value)
            require(turns.value.none { it.sessionId == turn.sessionId && it.position == turn.position })
            turns.value = turns.value + turn
        }
    }
    override suspend fun touchSession(id: String, connectionId: String, modelId: String, at: Long) = edit(id) {
        it.copy(connectionId = connectionId, modelId = modelId, updatedAt = at)
    }
    override suspend fun setApprovalMode(id: String, mode: String): Int = synchronized(this) {
        val s = sessions.value[id] ?: return@synchronized 0
        sessions.value = sessions.value + (id to s.copy(approvalMode = mode))
        1
    }
    override suspend fun setSummary(id: String, summary: String, through: Int) = edit(id) {
        it.copy(summary = summary, summaryThrough = through)
    }
    private fun edit(id: String, change: (ChatSession) -> ChatSession) = synchronized(this) {
        sessions.value[id]?.let { sessions.value = sessions.value + (id to change(it)) }
        Unit
    }
    private fun editTurn(predicate: (ChatTurn) -> Boolean, change: (ChatTurn) -> ChatTurn): Int = synchronized(this) {
        var n = 0
        turns.value = turns.value.map { if (predicate(it)) change(it).also { n++ } else it }
        n
    }
    /** Set: a turn's final write waits for it, after completing [finishing]. */
    @Volatile var finishGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val finishing = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun finishTurn(id: String, text: String, status: String, durationMs: Long?, error: String?) {
        finishGate?.let {
            finishing.complete(Unit)
            it.await()
        }
        editTurn({ it.id == id }) { it.copy(assistantText = text, status = status, durationMs = durationMs, error = error) }
    }
    override suspend fun saveProgress(id: String, text: String) {
        editTurn({ it.id == id && it.status == ChatStatus.RUNNING }) { it.copy(assistantText = text) }
    }
    override suspend fun markRunningInterrupted(): Int =
        editTurn({ it.status == ChatStatus.RUNNING }) { it.copy(status = ChatStatus.INTERRUPTED) }
    /** Set: deleting throws, as a database that can't be written would. */
    @Volatile var failDeletes = false

    override suspend fun deleteSession(id: String) = synchronized(this) {
        if (failDeletes) throw java.io.IOException("disk")
        sessions.value = sessions.value - id
        turns.value = turns.value.filter { it.sessionId != id }
    }
    override suspend fun deleteAll() = synchronized(this) {
        if (failDeletes) throw java.io.IOException("disk")
        sessions.value = emptyMap()
        turns.value = emptyList()
    }
}
