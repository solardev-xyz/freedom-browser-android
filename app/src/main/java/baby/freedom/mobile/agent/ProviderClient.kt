package baby.freedom.mobile.agent

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which provider requests may run right now (#573): AI must be on, and a
 * request made with a connection's key or URL must still be on that
 * connection's current [revision][AgentConnection.revision]. Turning AI
 * off cancels every call in flight; rotating or removing a key (or
 * changing a server's URL) cancels the calls that used the old one
 * (desktop audit L1: a removed or replaced key is never used again).
 *
 * Registering a call and checking it's allowed happen under the same
 * lock as [disable] and [bump], so no call can pass the check and then
 * escape a cancel that happens before it registered.
 */
internal class RequestGate {
    private val lock = Any()
    private var enabled = false
    private val revisions = HashMap<String, Long>()
    private val calls = HashMap<Call, String>()

    /**
     * Calls this gate cancelled, until they're unregistered: how [stopped]
     * tells them from a call OkHttp cancelled itself at its deadline.
     */
    private val cancelled = HashSet<Call>()

    /** AI on, with each connection's current revision. */
    fun enable(current: Map<String, Long>) = synchronized(lock) {
        enabled = true
        revisions.clear()
        revisions.putAll(current)
    }

    /** AI off: nothing may start, and everything running is cancelled. */
    fun disable() {
        val running = synchronized(lock) {
            enabled = false
            calls.keys.toList().also { calls.clear(); cancelled.addAll(it) }
        }
        running.forEach(Call::cancel)
    }

    /**
     * [connectionId] moves to [revision] (or is gone, `null`): calls made
     * under any other revision are cancelled.
     */
    fun bump(connectionId: String, revision: Long?) {
        val stale = synchronized(lock) {
            if (revision == null) revisions.remove(connectionId) else revisions[connectionId] = revision
            calls.filterValues { it == connectionId }.keys.toList().onEach { calls.remove(it); cancelled.add(it) }
        }
        stale.forEach(Call::cancel)
    }

    fun allows(connectionId: String, revision: Long): Boolean = synchronized(lock) {
        enabled && revisions[connectionId] == revision
    }

    /** Registers [call] if it may run; `false` (and nothing registered) if not. */
    fun register(call: Call, connectionId: String, revision: Long): Boolean = synchronized(lock) {
        if (!enabled || revisions[connectionId] != revision) return false
        calls[call] = connectionId
        true
    }

    fun unregister(call: Call) {
        synchronized(lock) {
            calls.remove(call)
            cancelled.remove(call)
        }
    }

    /**
     * Whether [call] may no longer deliver: this gate cancelled it, or AI
     * is off or the connection has moved past [revision]. A call cancelled
     * by its own deadline is not stopped: it timed out.
     */
    fun stopped(call: Call, connectionId: String, revision: Long): Boolean = synchronized(lock) {
        call in cancelled || !enabled || revisions[connectionId] != revision
    }

    /** Calls in flight; for tests. */
    internal fun inFlight(): Int = synchronized(lock) { calls.size }
}

/** How a chat step's stream ended (#574), without anything the server said. */
internal sealed interface StreamEnd {
    /** The body ended; whether it was a whole answer is the parser's call. */
    data object Ended : StreamEnd

    /**
     * The server answered with [code]; its body is never kept or shown.
     * [noTools]: a 400 or 404 whose body says the model takes no tools
     * (see [refusesTools]), the one thing it's read for.
     */
    data class Status(val code: Int, val noTools: Boolean = false) : StreamEnd

    /** The stream carried an error, or an answer that can't be used. */
    data class Wire(val error: WireError) : StreamEnd
    data object Unreachable : StreamEnd

    /** The server stopped sending for [ProviderClient.STREAM_IDLE_MS]. */
    data object TimedOut : StreamEnd

    /** AI was turned off, or the key or URL changed, before or while it ran. */
    data object Cancelled : StreamEnd
}

/** What a request to a provider came back with, without anything the server said. */
internal sealed interface ProviderReply {
    data class Body(val text: String) : ProviderReply

    /** The server answered with [code]; its body is never read or shown (it can quote the request). */
    data class Status(val code: Int) : ProviderReply
    data object TooLarge : ProviderReply
    data object Unreachable : ProviderReply

    /** The server took the request but didn't finish answering within the call's deadline. */
    data object TimedOut : ProviderReply

    /** AI was turned off, or the key or URL changed, before or while it ran. */
    data object Cancelled : ProviderReply
}

/**
 * The requests made to model providers: Settings → AI's (#573) model
 * lists and one short Test connection prompt ([fetch]), and a chat's
 * streamed steps (#574, [stream]).
 *
 * One OkHttp client for every provider: no redirects (desktop's
 * `redirect: 'error'`: a server can't move a request, and its key, to
 * another host), no retries of a request that may have reached the
 * server (and so no pooled idle connections, which only a retry makes
 * safe to reuse), no cookies or cache, a direct connection (no system proxy) and
 * an overall deadline per call ([OkHttpClient.callTimeout]: DNS, connect,
 * sending, waiting and reading the body, all together), except a chat's
 * stream ([stream]), which has an idle deadline instead. `localhost` is
 * answered here with the loopback addresses, never asked of DNS, so the
 * name [ProviderUrls] treats as this phone is this phone.
 *
 * A key goes in a request header only, never in a URL, and nothing here
 * logs a request, a key or a response.
 */
internal class ProviderClient(
    private val gate: RequestGate,
    http: OkHttpClient = DEFAULT_HTTP,
    /** How long a chat's stream may go without a byte; tests shorten it. */
    streamIdleMs: Long = STREAM_IDLE_MS,
) {
    /** [http], noting on each request when it reached a connected server. */
    private val http: OkHttpClient = http.newBuilder().addNetworkInterceptor(SENT).build()

    /** The same, for a chat's streamed answers: no whole-call deadline, but an idle one. */
    private val streamHttp: OkHttpClient = this.http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(streamIdleMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Fetches [url] (a GET, or a JSON POST of [json]) for [connectionId]
     * at [revision], reading at most [maxBytes] of the answer. Cancelling
     * the caller cancels the call at once, as do [RequestGate.disable] and
     * [RequestGate.bump].
     */
    suspend fun fetch(
        connectionId: String,
        revision: Long,
        url: HttpUrl,
        headers: Map<String, String>,
        maxBytes: Long,
        json: JSONObject? = null,
        timeoutMs: Long = CALL_TIMEOUT_MS,
    ): ProviderReply {
        val sent = Sent()
        val request = Request.Builder().url(url).tag(Sent::class.java, sent).apply {
            header("Accept", "application/json")
            headers.forEach { (k, v) -> header(k, v) }
            if (json != null) post(json.toString().toRequestBody(JSON))
        }.build()
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        if (!gate.register(call, connectionId, revision)) return ProviderReply.Cancelled
        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resume(
                            when {
                                // Only the gate's own cancels (AI off, a new key
                                // or URL) are "stopped". OkHttp's deadline cancels
                                // the call too, so `call.isCanceled()` can't tell
                                // the two apart.
                                gate.stopped(call, connectionId, revision) -> ProviderReply.Cancelled
                                // The deadline reports `InterruptedIOException`.
                                // Only a server that took the request is "too
                                // slow"; a DNS lookup, connect or TLS handshake
                                // that never finished never reached one.
                                e is InterruptedIOException && sent.value -> ProviderReply.TimedOut
                                // Also a cancel from the caller: its coroutine is
                                // gone, so nobody reads this.
                                else -> ProviderReply.Unreachable
                            },
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Anything thrown here is on OkHttp's own thread: it
                        // would crash the app and leave the caller waiting.
                        val reply = try {
                            response.use { read(it, maxBytes) }
                        } catch (e: Throwable) {
                            ProviderReply.Unreachable
                        }
                        val stopped = gate.stopped(call, connectionId, revision)
                        cont.resume(if (stopped) ProviderReply.Cancelled else reply)
                    }
                })
            }
        } finally {
            gate.unregister(call)
        }
    }

    /**
     * Streams one chat step (#574): POSTs [json] to [url] for
     * [connectionId] at [revision] and hands each server-sent event to
     * [onEvent] (its `event:` name, if any, and its data) as it arrives,
     * on OkHttp's thread. [onEvent] may throw [WireException] to end the
     * stream. When the server refuses (a non-2xx status) its body is never
     * kept or shown: only a 400's or 404's first [REFUSAL_PEEK_BYTES] are
     * checked for whether they say the model takes no tools
     * ([StreamEnd.Status.noTools]).
     *
     * There's no deadline on the whole answer (a long one takes as long
     * as it takes) but the server must keep sending: [STREAM_IDLE_MS]
     * without a byte ends it as [StreamEnd.TimedOut]. Cancelling the
     * caller cancels the call at once, from the cancelling thread, as do
     * [RequestGate.disable] and [RequestGate.bump] ([StreamEnd.Cancelled]).
     */
    suspend fun stream(
        connectionId: String,
        revision: Long,
        url: HttpUrl,
        headers: Map<String, String>,
        json: JSONObject,
        onEvent: (event: String?, data: String) -> Unit,
    ): StreamEnd {
        val sent = Sent()
        val request = Request.Builder().url(url).tag(Sent::class.java, sent).apply {
            header("Accept", "text/event-stream")
            headers.forEach { (k, v) -> header(k, v) }
            post(json.toString().toRequestBody(JSON))
        }.build()
        val call = streamHttp.newCall(request)
        call.timeout().timeout(0, TimeUnit.MILLISECONDS)
        if (!gate.register(call, connectionId, revision)) return StreamEnd.Cancelled
        return try {
            suspendCancellableCoroutine { cont ->
                // From the thread that cancels, never the one blocked reading.
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resume(
                            when {
                                gate.stopped(call, connectionId, revision) -> StreamEnd.Cancelled
                                e is InterruptedIOException && sent.value -> StreamEnd.TimedOut
                                else -> StreamEnd.Unreachable
                            },
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val end = try {
                            // Events already buffered when the call is cancelled
                            // (Stop, the gate) are never handed on.
                            response.use {
                                readEvents(it) { e, d ->
                                    if (call.isCanceled()) throw IOException("cancelled")
                                    onEvent(e, d)
                                }
                            }
                        } catch (e: WireException) {
                            StreamEnd.Wire(e.error)
                        } catch (e: SseReader.TooLarge) {
                            StreamEnd.Wire(WireError.BadReply)
                        } catch (e: InterruptedIOException) {
                            StreamEnd.TimedOut
                        } catch (e: Throwable) {
                            // Anything thrown here is on OkHttp's own thread:
                            // it would crash the app and leave the caller waiting.
                            StreamEnd.Unreachable
                        }
                        // Checked before the call is unregistered, which forgets the gate's cancels.
                        val stopped = gate.stopped(call, connectionId, revision)
                        cont.resume(if (stopped) StreamEnd.Cancelled else end)
                    }
                })
            }
        } finally {
            gate.unregister(call)
        }
    }

    private fun readEvents(response: Response, onEvent: (String?, String) -> Unit): StreamEnd {
        if (!response.isSuccessful) {
            val code = response.code
            return StreamEnd.Status(code, noTools = (code == 400 || code == 404) && saysNoTools(response))
        }
        val body = response.body ?: return StreamEnd.Unreachable
        SseReader(body.source()).forEach(onEvent)
        return StreamEnd.Ended
    }

    /**
     * Whether a refusal's body says the model takes no tools. Reads at
     * most [REFUSAL_PEEK_BYTES]; the text goes no further than this
     * check. Anything going wrong while reading is "no": the server
     * still answered with its status.
     */
    private fun saysNoTools(response: Response): Boolean = try {
        val source = response.body?.source()
        if (source == null) {
            false
        } else {
            val buffer = Buffer()
            while (buffer.size < REFUSAL_PEEK_BYTES) {
                if (source.read(buffer, REFUSAL_PEEK_BYTES - buffer.size) == -1L) break
            }
            refusesTools(buffer.readUtf8(minOf(buffer.size, REFUSAL_PEEK_BYTES)))
        }
    } catch (e: Exception) {
        false
    }

    private fun read(response: Response, maxBytes: Long): ProviderReply {
        if (!response.isSuccessful) return ProviderReply.Status(response.code)
        val body = response.body ?: return ProviderReply.Unreachable
        if (body.contentLength() > maxBytes) return ProviderReply.TooLarge
        return try {
            val source = body.source()
            val buffer = Buffer()
            while (true) {
                val n = source.read(buffer, 64L * 1024)
                if (n == -1L) break
                // A chunked body says no length up front: count as it comes.
                if (buffer.size > maxBytes) return ProviderReply.TooLarge
            }
            ProviderReply.Body(buffer.readUtf8())
        } catch (e: InterruptedIOException) {
            ProviderReply.TimedOut
        } catch (e: IOException) {
            ProviderReply.Unreachable
        }
    }

    /** Set once a request is on a connection to the server, before it is written. */
    private class Sent {
        @Volatile var value = false
    }

    companion object {
        private val SENT = Interceptor { chain ->
            chain.request().tag(Sent::class.java)?.value = true
            chain.proceed(chain.request())
        }

        const val CALL_TIMEOUT_MS = 30_000L

        /**
         * How long a chat's answer may go without a byte (#574): a server
         * on the network may load the model from disk before the first.
         */
        const val STREAM_IDLE_MS = 120_000L
        const val CATALOG_TIMEOUT_MS = 20_000L

        /** How much of a 400's or 404's body is checked for a no-tools refusal. */
        const val REFUSAL_PEEK_BYTES = 4_096L

        /**
         * Test connection to a server on the network: a local server may
         * load the model from disk before it answers the first prompt.
         */
        const val LAN_TEST_TIMEOUT_MS = 90_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** `localhost` and `*.localhost` are loopback, as Chromium has it; everything else asks the system. */
        internal val LOOPBACK_DNS = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "localhost" || hostname.endsWith(".localhost")) {
                    listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
        }

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // No idle connection is kept: without retries, a pooled
            // connection the server has since closed (an idle timeout, an
            // HTTP/1.0 server) would fail the next request outright.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .proxy(Proxy.NO_PROXY)
            .dns(LOOPBACK_DNS)
            .connectTimeout(10, TimeUnit.SECONDS)
            // No separate read timeout: a prompt's answer can take a while to
            // start (a model loading), and each call's own deadline
            // ([CALL_TIMEOUT_MS], or the one [fetch] is given) bounds it.
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        private val DEFAULT_HTTP: OkHttpClient by lazy { httpClient() }

        /** What Test connection asks: a reply costs a handful of tokens. */
        const val TEST_PROMPT = "Reply with OK."
        private const val TEST_MAX_TOKENS = 32

        /** Request headers for [definition], with [key] if there is one. */
        fun authHeaders(definition: ProviderDefinition, key: String?): Map<String, String> = buildMap {
            if (definition.wire == WireFormat.AnthropicMessages) {
                put("anthropic-version", "2023-06-01")
                if (!key.isNullOrEmpty()) put("x-api-key", key)
            } else if (!key.isNullOrEmpty()) {
                put("Authorization", "Bearer $key")
            }
        }

        /** Where a model list is fetched from: [base] is the provider's API base (or a LAN server's URL). */
        fun catalogUrl(definition: ProviderDefinition, base: HttpUrl): HttpUrl? {
            val path = definition.catalogPath ?: return null
            return if (definition.id == ProviderCatalog.OLLAMA) {
                // Ollama's own API sits at the server's root; its OpenAI one under /v1.
                rootOfOllama(base).newBuilder().addPathSegments("api/tags").build()
            } else {
                (base.toString().removeSuffix("/") + path).toHttpUrlOrNull()
            }
        }

        /**
         * Where [definition]'s prompts go, at [base]: Test connection's
         * one and a chat's (#574).
         */
        fun endpoint(definition: ProviderDefinition, base: HttpUrl): HttpUrl = when (definition.wire) {
            WireFormat.AnthropicMessages -> base.plus("messages")
            WireFormat.OpenAiResponses -> base.plus("responses")
            WireFormat.OpenAiChat -> {
                val api = if (definition.id == ProviderCatalog.OLLAMA) {
                    rootOfOllama(base).newBuilder().addPathSegment("v1").build()
                } else {
                    base
                }
                api.plus("chat/completions")
            }
        }

        /** The Test connection request for [definition] at [base]: its URL and body. */
        fun testRequest(definition: ProviderDefinition, base: HttpUrl, modelId: String): Pair<HttpUrl, JSONObject> {
            val user = JSONObject().put("role", "user").put("content", TEST_PROMPT)
            val url = endpoint(definition, base)
            return when (definition.wire) {
                WireFormat.AnthropicMessages -> url to JSONObject()
                    .put("model", modelId)
                    .put("max_tokens", TEST_MAX_TOKENS)
                    .put("messages", JSONArray().put(user))
                WireFormat.OpenAiResponses -> url to JSONObject()
                    .put("model", modelId)
                    .put("input", TEST_PROMPT)
                    .put("max_output_tokens", TEST_MAX_TOKENS)
                    .put("store", false)
                WireFormat.OpenAiChat -> url to JSONObject()
                    .put("model", modelId)
                    .put("max_tokens", TEST_MAX_TOKENS)
                    .put("stream", false)
                    .put("messages", JSONArray().put(user))
            }
        }

        private fun HttpUrl.plus(segments: String): HttpUrl {
            val trimmed = pathSegments.dropLastWhile { it.isEmpty() }
            return newBuilder().encodedPath("/").apply {
                trimmed.forEach { addPathSegment(it) }
                addPathSegments(segments)
            }.build()
        }

        /** An Ollama URL given with or without its `/v1` (desktop's form) is the same server. */
        private fun rootOfOllama(base: HttpUrl): HttpUrl {
            val segments = base.pathSegments.dropLastWhile { it.isEmpty() }.let {
                if (it.lastOrNull() == "v1") it.dropLast(1) else it
            }
            return base.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build()
        }

        /** Whether a 2xx Test connection answer is a reply in [definition]'s format. */
        fun isReply(definition: ProviderDefinition, body: String): Boolean {
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                return false
            } catch (e: StackOverflowError) {
                return false
            }
            return when (definition.wire) {
                WireFormat.AnthropicMessages -> root.optJSONArray("content") != null
                WireFormat.OpenAiResponses -> root.optJSONArray("output") != null || root.has("status")
                WireFormat.OpenAiChat -> (root.optJSONArray("choices")?.length() ?: 0) > 0
            }
        }
    }
}

/**
 * Whether a refusal's [text] says the model takes no tools: Ollama's
 * "… does not support tools" (400) and OpenRouter's "No endpoints found
 * that support tool use" (404). Matched on the refusal's own wording, not
 * on "tools" alone, which a body quoting the request would contain.
 */
internal fun refusesTools(text: String): Boolean = NO_TOOLS.containsMatchIn(text)

private val NO_TOOLS = Regex("\\bsupports?\\s+(tools\\b|tool\\s+use\\b)", RegexOption.IGNORE_CASE)
