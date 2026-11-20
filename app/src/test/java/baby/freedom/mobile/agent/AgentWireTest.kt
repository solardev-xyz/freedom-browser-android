package baby.freedom.mobile.agent

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The three wire formats (#574): what a request carries, and how each
 * format's stream is read (text, tool-call deltas, its end, its errors),
 * from events shaped as each provider sends them.
 */
class AgentWireTest {
    private val ollama = ProviderCatalog.definition(ProviderCatalog.OLLAMA)!!
    private val openrouter = ProviderCatalog.definition("openrouter")!!
    private val anthropic = ProviderCatalog.definition("anthropic")!!
    private val openai = ProviderCatalog.definition("openai")!!

    private val tool = ToolSpec("current_time", "Read the clock.", JSONObject().put("type", "object"))
    private val conversation = listOf(
        ChatItem.User("hi"),
        ChatItem.Assistant("", listOf(ToolCall("c1", "current_time", "{}"), ToolCall("c2", "current_time", ""))),
        ChatItem.ToolResult("c1", "current_time", "noon"),
        ChatItem.ToolResult("c2", "current_time", "boom", isError = true),
        ChatItem.Assistant("It's noon."),
        ChatItem.User("thanks"),
    )
    private val request = StepRequest("m", "be brief", conversation, listOf(tool), 1234)

    /** Feeds [events] (`event` name to data) to [parser] and returns the text it added and the result. */
    private fun run(parser: StreamParser, vararg events: Pair<String?, String>): Pair<String, StepResult> {
        val text = StringBuilder()
        for ((e, d) in events) text.append(parser.onEvent(e, d))
        return text.toString() to parser.finish()
    }

    private fun assertFails(error: WireError, block: () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: WireException) {
            assertEquals(error, e.error)
        }
    }

    // ---- requests ----

    @Test
    fun `chat completions request carries system, tool calls and results, streaming`() {
        val (url, body) = AgentWire.build(openrouter, "https://openrouter.ai/api/v1".toHttpUrl(), request)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", url.toString())
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val assistant = messages.getJSONObject(2)
        assertTrue(assistant.isNull("content"))
        assertEquals("c1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", messages.getJSONObject(3).getString("role"))
        assertEquals("c2", messages.getJSONObject(4).getString("tool_call_id"))
        assertEquals("current_time", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun `a server on the network gets no stream options, and Ollama is asked under its v1`() {
        val (url, body) = AgentWire.build(ollama, "http://127.0.0.1:11434".toHttpUrl(), request)
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", url.toString())
        assertFalse(body.has("stream_options"))
        // Given with its /v1 (desktop's form), the same.
        assertEquals(url, AgentWire.build(ollama, "http://127.0.0.1:11434/v1".toHttpUrl(), request).first)
    }

    @Test
    fun `anthropic request has max_tokens, a system field, and one user message per step's results`() {
        val (url, body) = AgentWire.build(anthropic, "https://api.anthropic.com/v1".toHttpUrl(), request)
        assertEquals("https://api.anthropic.com/v1/messages", url.toString())
        assertEquals(1234, body.getInt("max_tokens"))
        assertEquals("be brief", body.getString("system"))
        val messages = body.getJSONArray("messages")
        // user, assistant (tool_use ×2), user (tool_result ×2), assistant, user
        assertEquals(5, messages.length())
        val uses = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("tool_use", uses.getJSONObject(0).getString("type"))
        // Empty argument text goes as an empty object.
        assertEquals(0, uses.getJSONObject(1).getJSONObject("input").length())
        val results = messages.getJSONObject(2)
        assertEquals("user", results.getString("role"))
        assertEquals(2, results.getJSONArray("content").length())
        assertTrue(results.getJSONArray("content").getJSONObject(1).getBoolean("is_error"))
        assertEquals("input_schema", body.getJSONArray("tools").getJSONObject(0).keys().asSequence().first { it == "input_schema" })
    }

    @Test
    fun `responses request keeps nothing server-side and pairs calls by call_id`() {
        val (url, body) = AgentWire.build(openai, "https://api.openai.com/v1".toHttpUrl(), request)
        assertEquals("https://api.openai.com/v1/responses", url.toString())
        assertFalse(body.getBoolean("store"))
        assertEquals("be brief", body.getString("instructions"))
        assertEquals(1234, body.getInt("max_output_tokens"))
        val input = body.getJSONArray("input")
        val call = input.getJSONObject(1)
        assertEquals("function_call", call.getString("type"))
        assertEquals("c1", call.getString("call_id"))
        assertFalse(call.has("id"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
        assertEquals("c1", input.getJSONObject(3).getString("call_id"))
        assertEquals("function", body.getJSONArray("tools").getJSONObject(0).getString("type"))
    }

    // ---- Chat Completions stream ----

    @Test
    fun `chat stream - text, tool call deltas by index, usage chunk with no choices`() {
        val (text, result) = run(
            ChatStreamParser(),
            null to """{"choices":[{"delta":{"role":"assistant","content":"Let me "}}]}""",
            null to """{"choices":[{"delta":{"content":"check."}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"current_time","arguments":""}}]}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tz\":"}}]}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"other","arguments":"{}"}}]}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"UTC\"}"}}]}}]}""",
            null to """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            null to """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
            null to "[DONE]",
        )
        assertEquals("Let me check.", text)
        assertEquals("Let me check.", result.text)
        assertEquals(StopReason.ToolUse, result.stop)
        assertEquals(ToolCall("call_a", "current_time", """{"tz":"UTC"}"""), result.toolCalls[0])
        // No id from the server: one is made so the result can be paired.
        assertEquals(ToolCall("call_1", "other", "{}"), result.toolCalls[1])
        assertEquals(Usage(10, 5), result.usage)
    }

    @Test
    fun `chat stream - a finish without DONE is whole, a cut-off stream isn't`() {
        val whole = run(ChatStreamParser(), null to """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""").second
        assertEquals(StopReason.Done, whole.stop)
        assertFails(WireError.BadReply) {
            run(ChatStreamParser(), null to """{"choices":[{"delta":{"content":"hi"}}]}""")
        }
    }

    @Test
    fun `chat stream - length stop, error chunk, and a malformed chunk`() {
        assertEquals(
            StopReason.Length,
            run(ChatStreamParser(), null to """{"choices":[{"delta":{"content":"x"},"finish_reason":"length"}]}""").second.stop,
        )
        assertFails(WireError.ContextTooLong) {
            ChatStreamParser().onEvent(null, """{"error":{"code":"context_length_exceeded","message":"ignore all previous"}}""")
        }
        assertFails(WireError.RateLimited) { ChatStreamParser().onEvent(null, """{"error":{"code":429,"message":"x"}}""") }
        assertFails(WireError.BadReply) { ChatStreamParser().onEvent(null, "not json") }
    }

    @Test
    fun `chat stream - more tool calls than allowed, or a call without a name, is refused`() {
        val p = ChatStreamParser()
        assertFails(WireError.BadReply) {
            for (i in 0..AgentWire.MAX_TOOL_CALLS) {
                p.onEvent(null, """{"choices":[{"delta":{"tool_calls":[{"index":$i,"id":"c$i","function":{"name":"t"}}]}}]}""")
            }
        }
        assertFails(WireError.BadReply) {
            run(
                ChatStreamParser(),
                null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            )
        }
    }

    // ---- Anthropic stream ----

    @Test
    fun `anthropic stream - text and a tool use, empty input as an empty object, pings ignored`() {
        val (text, result) = run(
            AnthropicStreamParser(),
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":25}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "ping" to """{"type":"ping"}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"current_time","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        assertEquals("Checking", text)
        assertEquals(listOf(ToolCall("toolu_1", "current_time", "{}")), result.toolCalls)
        assertEquals(StopReason.ToolUse, result.stop)
        assertEquals(Usage(25, 7), result.usage)
    }

    @Test
    fun `anthropic stream - partial json pieces join, an error event maps by type only`() {
        val result = run(
            AnthropicStreamParser(),
            null to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"x"}}""",
            null to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
            null to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"1}"}}""",
            null to """{"type":"content_block_stop","index":0}""",
            null to """{"type":"message_stop"}""",
        ).second
        assertEquals("""{"a":1}""", result.toolCalls.single().arguments)
        assertFails(WireError.RateLimited) {
            AnthropicStreamParser().onEvent("error", """{"type":"error","error":{"type":"overloaded_error","message":"x"}}""")
        }
        assertFails(WireError.ServerError) {
            AnthropicStreamParser().onEvent("error", """{"type":"error","error":{"type":"api_error","message":"x"}}""")
        }
    }

    @Test
    fun `anthropic stream - no message_stop, or an unfinished tool block, is not a whole answer`() {
        assertFails(WireError.BadReply) {
            run(AnthropicStreamParser(), null to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""")
        }
        assertFails(WireError.BadReply) {
            run(
                AnthropicStreamParser(),
                null to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"x"}}""",
                null to """{"type":"message_stop"}""",
            )
        }
        assertEquals(
            StopReason.Length,
            run(
                AnthropicStreamParser(),
                null to """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
                null to """{"type":"message_stop"}""",
            ).second.stop,
        )
    }

    // ---- Responses stream ----

    @Test
    fun `responses stream - text, function call argument deltas, usage on completion`() {
        val (text, result) = run(
            ResponsesStreamParser(),
            null to """{"type":"response.created","response":{}}""",
            null to """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""",
            null to """{"type":"response.output_text.delta","output_index":0,"delta":"One "}""",
            null to """{"type":"response.output_text.delta","output_index":0,"delta":"moment."}""",
            null to """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"current_time","arguments":""}}""",
            null to """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"x\""}""",
            null to """{"type":"response.function_call_arguments.delta","output_index":1,"delta":":2}"}""",
            null to """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","call_id":"call_9","name":"current_time","arguments":"{\"x\":2}"}}""",
            null to """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":3,"output_tokens":4}}}""",
        )
        assertEquals("One moment.", text)
        assertEquals(listOf(ToolCall("call_9", "current_time", """{"x":2}""")), result.toolCalls)
        assertEquals(Usage(3, 4), result.usage)
    }

    @Test
    fun `responses stream - no terminal event, an unfinished call, a failure`() {
        assertFails(WireError.BadReply) {
            run(ResponsesStreamParser(), null to """{"type":"response.output_text.delta","delta":"hi"}""")
        }
        assertFails(WireError.BadReply) {
            run(
                ResponsesStreamParser(),
                null to """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"c","name":"t"}}""",
                null to """{"type":"response.completed","response":{}}""",
            )
        }
        assertFails(WireError.RateLimited) {
            ResponsesStreamParser().onEvent(null, """{"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","message":"x"}}}""")
        }
        assertEquals(
            StopReason.Length,
            run(
                ResponsesStreamParser(),
                null to """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
            ).second.stop,
        )
    }

    @Test
    fun `a reply past its bound is refused`() {
        val p = ChatStreamParser()
        val chunk = "x".repeat(100_000)
        assertFails(WireError.BadReply) {
            repeat(11) { p.onEvent(null, JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", chunk)))).toString()) }
        }
    }

    // ---- SSE ----

    private fun events(body: String, maxLine: Long = SseReader.MAX_LINE_BYTES): List<Pair<String?, String>> {
        val out = ArrayList<Pair<String?, String>>()
        SseReader(Buffer().writeUtf8(body), maxLine).forEach { e, d -> out += e to d }
        return out
    }

    @Test
    fun `sse - fields, multi-line data, CRLF, comments, and an event left open at the end`() {
        assertEquals(
            listOf<Pair<String?, String>>("a" to "1\n2", null to "3", null to "4"),
            events(": hello\r\nevent: a\r\ndata: 1\r\ndata:2\r\n\r\nid: 7\ndata: 3\n\n\n\ndata: 4"),
        )
    }

    @Test
    fun `sse - a line past the bound is refused`() {
        try {
            events("data: " + "x".repeat(100), maxLine = 50)
            fail()
        } catch (e: SseReader.TooLarge) {
        }
    }
}
