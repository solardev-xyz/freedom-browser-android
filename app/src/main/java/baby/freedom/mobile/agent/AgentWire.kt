package baby.freedom.mobile.agent

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/*
 * The three request formats a chat speaks (#574), with streaming and
 * tool calls: OpenAI Chat Completions, Anthropic Messages and the OpenAI
 * Responses API. Which one a provider uses is its catalog entry's
 * [WireFormat] (desktop's choice of Pi `api`); the shapes follow Pi's own
 * providers (`pi-ai/dist/api/`), which desktop runs.
 *
 * Nothing here shows what a server wrote except the reply text and the
 * tool calls the model asked for: an error event is mapped by its type
 * or code only ([WireError]), never by its message.
 */

/** One entry of a conversation as the model sees it. */
internal sealed interface ChatItem {
    data class User(val text: String) : ChatItem

    /** What the model said in one step, and the tools it asked for. */
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : ChatItem

    /** A tool's answer to [callId]. */
    data class ToolResult(val callId: String, val name: String, val output: String, val isError: Boolean = false) : ChatItem
}

/** A tool call: [arguments] is the JSON text the model wrote. */
internal data class ToolCall(val id: String, val name: String, val arguments: String)

/** A tool the model may call: [parameters] is its JSON Schema. */
internal data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

/** Tokens a step used, as the server reported them (0 when it didn't). */
internal data class Usage(val input: Long = 0, val output: Long = 0)

internal enum class StopReason {
    /** The model finished its answer. */
    Done,

    /** It asked for tools. */
    ToolUse,

    /** It ran out of output tokens: the text is cut short. */
    Length,
}

/** One step's answer, complete. */
internal data class StepResult(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stop: StopReason,
    val usage: Usage = Usage(),
)

/** Why a stream couldn't be used: never the server's own words. */
internal enum class WireError {
    /** The server is busy or rate-limiting this key. */
    RateLimited,

    /** The conversation is longer than the model takes. */
    ContextTooLong,

    /** The server failed while answering. */
    ServerError,

    /** The provider refused the request (a policy, a bad request). */
    Refused,

    /** The answer isn't in the format, stopped before its end, or is past a bound. */
    BadReply,
}

internal class WireException(val error: WireError) : Exception(error.name)

/** What a request needs besides the conversation. */
internal data class StepRequest(
    val modelId: String,
    val system: String,
    val items: List<ChatItem>,
    val tools: List<ToolSpec>,
    /** A cap on the reply's tokens; Anthropic requires one. */
    val maxTokens: Int,
)

internal object AgentWire {
    /** A reply longer than this (characters) is refused, not kept growing. */
    const val MAX_REPLY_CHARS = 1_000_000

    /** Tool calls one step may ask for. */
    const val MAX_TOOL_CALLS = 16

    /** One tool call's arguments, characters. */
    const val MAX_ARGUMENT_CHARS = 64 * 1024

    /**
     * The URL and body that stream [request] to [definition] at [base]
     * (the provider's API base, or a LAN server's URL).
     */
    fun build(definition: ProviderDefinition, base: HttpUrl, request: StepRequest): Pair<HttpUrl, JSONObject> {
        val url = ProviderClient.endpoint(definition, base)
        val body = when (definition.wire) {
            WireFormat.OpenAiChat -> chatBody(definition, request)
            WireFormat.AnthropicMessages -> anthropicBody(request)
            WireFormat.OpenAiResponses -> responsesBody(request)
        }
        return url to body
    }

    private fun chatBody(definition: ProviderDefinition, r: StepRequest): JSONObject {
        val messages = JSONArray()
        if (r.system.isNotEmpty()) messages.put(JSONObject().put("role", "system").put("content", r.system))
        for (item in r.items) when (item) {
            is ChatItem.User -> messages.put(JSONObject().put("role", "user").put("content", item.text))
            is ChatItem.Assistant -> messages.put(
                JSONObject().put("role", "assistant").apply {
                    // `null` content with tool calls, as OpenAI writes it.
                    put("content", if (item.text.isEmpty() && item.toolCalls.isNotEmpty()) JSONObject.NULL else item.text)
                    if (item.toolCalls.isNotEmpty()) put(
                        "tool_calls",
                        JSONArray(
                            item.toolCalls.map {
                                JSONObject().put("id", it.id).put("type", "function").put(
                                    "function",
                                    JSONObject().put("name", it.name).put("arguments", it.arguments),
                                )
                            },
                        ),
                    )
                },
            )
            is ChatItem.ToolResult -> messages.put(
                JSONObject().put("role", "tool").put("tool_call_id", item.callId).put("content", item.output),
            )
        }
        return JSONObject()
            .put("model", r.modelId)
            .put("messages", messages)
            .put("stream", true)
            .apply {
                // The usage chunk; a server on the network may not know the
                // option, and some refuse what they don't know.
                if (definition.kind == ProviderKind.ApiKey) {
                    put("stream_options", JSONObject().put("include_usage", true))
                }
                if (r.tools.isNotEmpty()) put(
                    "tools",
                    JSONArray(
                        r.tools.map {
                            JSONObject().put("type", "function").put(
                                "function",
                                JSONObject().put("name", it.name).put("description", it.description)
                                    .put("parameters", it.parameters),
                            )
                        },
                    ),
                )
            }
    }

    private fun anthropicBody(r: StepRequest): JSONObject {
        val messages = JSONArray()
        // A step's tool results go back in one user message, in call order.
        var pendingResults: JSONArray? = null
        fun flush() {
            pendingResults?.let { messages.put(JSONObject().put("role", "user").put("content", it)) }
            pendingResults = null
        }
        for (item in r.items) when (item) {
            is ChatItem.User -> {
                flush()
                messages.put(JSONObject().put("role", "user").put("content", item.text))
            }
            is ChatItem.Assistant -> {
                flush()
                val content = JSONArray()
                if (item.text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", item.text))
                for (call in item.toolCalls) {
                    content.put(
                        JSONObject().put("type", "tool_use").put("id", call.id).put("name", call.name)
                            .put("input", argumentsObject(call.arguments)),
                    )
                }
                if (content.length() > 0) messages.put(JSONObject().put("role", "assistant").put("content", content))
            }
            is ChatItem.ToolResult -> {
                val results = pendingResults ?: JSONArray().also { pendingResults = it }
                results.put(
                    JSONObject().put("type", "tool_result").put("tool_use_id", item.callId)
                        .put("content", item.output).apply { if (item.isError) put("is_error", true) },
                )
            }
        }
        flush()
        return JSONObject()
            .put("model", r.modelId)
            .put("max_tokens", r.maxTokens)
            .put("stream", true)
            .put("messages", messages)
            .apply {
                if (r.system.isNotEmpty()) put("system", r.system)
                if (r.tools.isNotEmpty()) put(
                    "tools",
                    JSONArray(
                        r.tools.map {
                            JSONObject().put("name", it.name).put("description", it.description)
                                .put("input_schema", it.parameters)
                        },
                    ),
                )
            }
    }

    private fun responsesBody(r: StepRequest): JSONObject {
        val input = JSONArray()
        // As Pi sends it: a leading instruction message, not `instructions`
        // (which Pi keeps for the ChatGPT sign-in's Codex endpoint). `system`
        // rather than Pi's `developer` for reasoning models: every
        // Responses provider here takes it, and OpenAI reads it as `developer`.
        if (r.system.isNotEmpty()) input.put(JSONObject().put("role", "system").put("content", r.system))
        for (item in r.items) when (item) {
            is ChatItem.User -> input.put(JSONObject().put("role", "user").put("content", item.text))
            is ChatItem.Assistant -> {
                if (item.text.isNotEmpty()) input.put(
                    JSONObject().put("type", "message").put("role", "assistant").put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "output_text").put("text", item.text).put("annotations", JSONArray())),
                    ).put("status", "completed"),
                )
                // Without an item id: nothing is stored server-side
                // (`store: false`), so the call is replayed by its call_id.
                for (call in item.toolCalls) input.put(
                    JSONObject().put("type", "function_call").put("call_id", call.id).put("name", call.name)
                        .put("arguments", call.arguments),
                )
            }
            is ChatItem.ToolResult -> input.put(
                JSONObject().put("type", "function_call_output").put("call_id", item.callId).put("output", item.output),
            )
        }
        return JSONObject()
            .put("model", r.modelId)
            .put("input", input)
            .put("stream", true)
            // The conversation lives on this phone, not on the provider's servers.
            .put("store", false)
            .put("max_output_tokens", r.maxTokens)
            .apply {
                if (r.tools.isNotEmpty()) put(
                    "tools",
                    JSONArray(
                        r.tools.map {
                            JSONObject().put("type", "function").put("name", it.name)
                                .put("description", it.description).put("parameters", it.parameters)
                        },
                    ),
                )
            }
    }

    /** A call's arguments as an object; `{}` for empty or unusable text (Anthropic wants an object). */
    fun argumentsObject(text: String): JSONObject = parseObject(text.ifBlank { "{}" }) ?: JSONObject()

    /** [text] as a JSON object, or null. */
    fun parseObject(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    fun parser(wire: WireFormat): StreamParser = when (wire) {
        WireFormat.OpenAiChat -> ChatStreamParser()
        WireFormat.AnthropicMessages -> AnthropicStreamParser()
        WireFormat.OpenAiResponses -> ResponsesStreamParser()
    }
}

/**
 * Reads one step's server-sent events: [onEvent] for each, then
 * [finish] for the whole answer. [onEvent] returns the text that event
 * added (empty for none); both throw [WireException] for an answer that
 * can't be used.
 */
internal abstract class StreamParser {
    private val text = StringBuilder()
    protected var usage = Usage()

    abstract fun onEvent(event: String?, data: String): String

    abstract fun finish(): StepResult

    protected fun appendText(delta: String): String {
        if (text.length + delta.length > AgentWire.MAX_REPLY_CHARS) throw WireException(WireError.BadReply)
        text.append(delta)
        return delta
    }

    protected val textSoFar: String get() = text.toString()

    protected fun json(data: String): JSONObject = AgentWire.parseObject(data) ?: throw WireException(WireError.BadReply)

    /** A tool call being streamed in. */
    protected class PartialCall(var id: String?, var name: String?) {
        val arguments = StringBuilder()
        var done = false

        fun append(delta: String) {
            if (arguments.length + delta.length > AgentWire.MAX_ARGUMENT_CHARS) throw WireException(WireError.BadReply)
            arguments.append(delta)
        }
    }

    protected fun checkCallCount(n: Int) {
        if (n > AgentWire.MAX_TOOL_CALLS) throw WireException(WireError.BadReply)
    }

    protected fun finished(calls: List<PartialCall>, stop: StopReason?): StepResult {
        val toolCalls = calls.mapIndexed { i, c ->
            val name = c.name?.takeIf { it.isNotEmpty() } ?: throw WireException(WireError.BadReply)
            // A server that sends no id (some local ones) still needs one
            // to pair each call with its result.
            ToolCall(c.id?.takeIf { it.isNotEmpty() } ?: "call_$i", name, c.arguments.toString().ifBlank { "{}" })
        }
        val reason = when {
            stop == StopReason.Length -> StopReason.Length
            toolCalls.isNotEmpty() -> StopReason.ToolUse
            else -> StopReason.Done
        }
        return StepResult(textSoFar, toolCalls, reason, usage)
    }

    /** A provider's error code or type, as a [WireError]; never its message. */
    protected fun errorOf(code: String?, type: String? = null): WireException {
        val c = listOfNotNull(code, type).joinToString(" ").lowercase()
        return WireException(
            when {
                "context" in c || "too_long" in c || "too long" in c -> WireError.ContextTooLong
                "rate" in c || "overloaded" in c || "quota" in c || "429" in c -> WireError.RateLimited
                "server" in c || "api_error" in c || "internal" in c || "unavailable" in c -> WireError.ServerError
                else -> WireError.Refused
            },
        )
    }
}

/** OpenAI Chat Completions: `data: {…}` chunks, ending `data: [DONE]`. */
internal class ChatStreamParser : StreamParser() {
    private val calls = sortedMapOf<Int, PartialCall>()
    private var stop: StopReason? = null
    private var sawFinish = false
    private var sawDone = false

    override fun onEvent(event: String?, data: String): String {
        if (data.trim() == "[DONE]") {
            sawDone = true
            return ""
        }
        val root = json(data)
        root.optJSONObject("error")?.let { throw errorOf(it.opt("code")?.toString(), it.optString("type")) }
        root.optJSONObject("usage")?.let {
            usage = Usage(it.optLong("prompt_tokens"), it.optLong("completion_tokens"))
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta")
        var added = ""
        if (delta != null) {
            val content = delta.opt("content")
            if (content is String && content.isNotEmpty()) added = appendText(content)
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null) for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                // Keyed by `index`: the id and name come once, the arguments in pieces.
                val index = if (tc.has("index")) tc.optInt("index") else calls.size
                val call = calls.getOrPut(index) { PartialCall(null, null) }
                checkCallCount(calls.size)
                (tc.opt("id") as? String)?.takeIf { it.isNotEmpty() }?.let { call.id = it }
                val fn = tc.optJSONObject("function")
                (fn?.opt("name") as? String)?.takeIf { it.isNotEmpty() }?.let { call.name = it }
                (fn?.opt("arguments") as? String)?.let { call.append(it) }
            }
        }
        when (choice.opt("finish_reason")) {
            "length" -> { stop = StopReason.Length; sawFinish = true }
            "content_filter" -> throw WireException(WireError.Refused)
            is String -> sawFinish = true
        }
        return added
    }

    override fun finish(): StepResult {
        // A stream cut off before its end isn't a whole answer.
        if (!sawDone && !sawFinish) throw WireException(WireError.BadReply)
        return finished(calls.values.toList(), stop)
    }
}

/** Anthropic Messages: typed events from `message_start` to `message_stop`. */
internal class AnthropicStreamParser : StreamParser() {
    private val blocks = HashMap<Int, PartialCall>()
    private val order = ArrayList<PartialCall>()
    private var stop: StopReason? = null
    private var stopped = false

    override fun onEvent(event: String?, data: String): String {
        val root = json(data)
        return when (root.optString("type", event ?: "")) {
            "message_start" -> {
                root.optJSONObject("message")?.optJSONObject("usage")?.let {
                    usage = usage.copy(input = it.optLong("input_tokens"))
                }
                ""
            }
            "content_block_start" -> {
                val block = root.optJSONObject("content_block") ?: return ""
                when (block.optString("type")) {
                    "text" -> (block.opt("text") as? String)?.let { appendText(it) } ?: ""
                    "tool_use" -> {
                        val call = PartialCall(block.opt("id") as? String, block.opt("name") as? String)
                        blocks[root.optInt("index")] = call
                        order += call
                        checkCallCount(order.size)
                        ""
                    }
                    else -> ""
                }
            }
            "content_block_delta" -> {
                val delta = root.optJSONObject("delta") ?: return ""
                when (delta.optString("type")) {
                    "text_delta" -> appendText(delta.optString("text"))
                    "input_json_delta" -> {
                        val call = blocks[root.optInt("index")] ?: throw WireException(WireError.BadReply)
                        call.append(delta.optString("partial_json"))
                        ""
                    }
                    else -> ""
                }
            }
            "content_block_stop" -> {
                blocks[root.optInt("index")]?.done = true
                ""
            }
            "message_delta" -> {
                if (root.optJSONObject("delta")?.optString("stop_reason") == "max_tokens") stop = StopReason.Length
                if (root.optJSONObject("delta")?.optString("stop_reason") == "refusal") throw WireException(WireError.Refused)
                root.optJSONObject("usage")?.let { usage = usage.copy(output = it.optLong("output_tokens")) }
                ""
            }
            "message_stop" -> {
                stopped = true
                ""
            }
            "error" -> throw errorOf(null, root.optJSONObject("error")?.optString("type"))
            else -> "" // ping, and whatever comes later
        }
    }

    override fun finish(): StepResult {
        if (!stopped || order.any { !it.done }) throw WireException(WireError.BadReply)
        return finished(order, stop)
    }
}

/** The OpenAI Responses API: typed `response.*` events ending in `response.completed`. */
internal class ResponsesStreamParser : StreamParser() {
    private val calls = sortedMapOf<Int, PartialCall>()
    private var stop: StopReason? = null
    private var terminal = false

    override fun onEvent(event: String?, data: String): String {
        val root = json(data)
        return when (root.optString("type", event ?: "")) {
            "response.output_text.delta" -> appendText(root.optString("delta"))
            "response.output_item.added" -> {
                val item = root.optJSONObject("item")
                if (item?.optString("type") == "function_call") {
                    calls[root.optInt("output_index")] =
                        PartialCall(item.opt("call_id") as? String, item.opt("name") as? String)
                    checkCallCount(calls.size)
                }
                ""
            }
            "response.function_call_arguments.delta" -> {
                calls[root.optInt("output_index")]?.append(root.optString("delta"))
                ""
            }
            "response.function_call_arguments.done" -> {
                calls[root.optInt("output_index")]?.let { c ->
                    (root.opt("arguments") as? String)?.let { c.arguments.setLength(0); c.append(it) }
                }
                ""
            }
            "response.output_item.done" -> {
                val item = root.optJSONObject("item")
                val call = calls[root.optInt("output_index")]
                if (item?.optString("type") == "function_call" && call != null) {
                    (item.opt("arguments") as? String)?.let { call.arguments.setLength(0); call.append(it) }
                    (item.opt("call_id") as? String)?.takeIf { it.isNotEmpty() }?.let { call.id = it }
                    (item.opt("name") as? String)?.takeIf { it.isNotEmpty() }?.let { call.name = it }
                    call.done = true
                }
                ""
            }
            "response.completed", "response.incomplete" -> {
                terminal = true
                val response = root.optJSONObject("response")
                response?.optJSONObject("usage")?.let { usage = Usage(it.optLong("input_tokens"), it.optLong("output_tokens")) }
                if (response?.optJSONObject("incomplete_details")?.optString("reason") == "max_output_tokens") {
                    stop = StopReason.Length
                } else if (root.optString("type") == "response.incomplete") {
                    throw WireException(WireError.Refused)
                }
                ""
            }
            "response.failed" -> {
                val error = root.optJSONObject("response")?.optJSONObject("error")
                throw errorOf(error?.opt("code")?.toString())
            }
            "error" -> throw errorOf(root.opt("code")?.toString(), root.opt("type")?.toString())
            else -> ""
        }
    }

    override fun finish(): StepResult {
        // A call whose `output_item.done` never came may be cut off: never run it.
        if (!terminal || calls.values.any { !it.done }) throw WireException(WireError.BadReply)
        return finished(calls.values.toList(), stop)
    }
}
