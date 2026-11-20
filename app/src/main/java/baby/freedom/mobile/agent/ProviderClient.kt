package baby.freedom.mobile.agent

import java.io.IOException
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
            calls.keys.toList().also { calls.clear() }
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
            calls.filterValues { it == connectionId }.keys.toList().onEach { calls.remove(it) }
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
        synchronized(lock) { calls.remove(call) }
    }

    /** Calls in flight; for tests. */
    internal fun inFlight(): Int = synchronized(lock) { calls.size }
}

/** What a request to a provider came back with, without anything the server said. */
internal sealed interface ProviderReply {
    data class Body(val text: String) : ProviderReply

    /** The server answered with [code]; its body is never read or shown (it can quote the request). */
    data class Status(val code: Int) : ProviderReply
    data object TooLarge : ProviderReply
    data object Unreachable : ProviderReply

    /** AI was turned off, or the key or URL changed, before or while it ran. */
    data object Cancelled : ProviderReply
}

/**
 * The requests Settings → AI makes (#573): list a provider's models and
 * send one short Test connection prompt. No chat yet (#574).
 *
 * One OkHttp client for every provider: no redirects (desktop's
 * `redirect: 'error'`: a server can't move a request, and its key, to
 * another host), no retries of a request that may have reached the
 * server (and so no pooled idle connections, which only a retry makes
 * safe to reuse), no cookies or cache, a direct connection (no system proxy) and
 * an overall deadline per call ([OkHttpClient.callTimeout]: DNS, connect,
 * sending, waiting and reading the body, all together). `localhost` is
 * answered here with the loopback addresses, never asked of DNS, so the
 * name [ProviderUrls] treats as this phone is this phone.
 *
 * A key goes in a request header only, never in a URL, and nothing here
 * logs a request, a key or a response.
 */
internal class ProviderClient(
    private val gate: RequestGate,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
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
        val request = Request.Builder().url(url).apply {
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
                        cont.resume(if (call.isCanceled()) ProviderReply.Cancelled else ProviderReply.Unreachable)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Anything thrown here is on OkHttp's own thread: it
                        // would crash the app and leave the caller waiting.
                        val reply = try {
                            response.use { read(it, maxBytes) }
                        } catch (e: Throwable) {
                            ProviderReply.Unreachable
                        }
                        cont.resume(if (call.isCanceled()) ProviderReply.Cancelled else reply)
                    }
                })
            }
        } finally {
            gate.unregister(call)
        }
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
        } catch (e: IOException) {
            ProviderReply.Unreachable
        }
    }

    companion object {
        const val CALL_TIMEOUT_MS = 30_000L
        const val CATALOG_TIMEOUT_MS = 20_000L
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
            .readTimeout(20, TimeUnit.SECONDS)
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

        /** The Test connection request for [definition] at [base]: its URL and body. */
        fun testRequest(definition: ProviderDefinition, base: HttpUrl, modelId: String): Pair<HttpUrl, JSONObject> {
            val user = JSONObject().put("role", "user").put("content", TEST_PROMPT)
            return when (definition.wire) {
                WireFormat.AnthropicMessages -> base.plus("messages") to JSONObject()
                    .put("model", modelId)
                    .put("max_tokens", TEST_MAX_TOKENS)
                    .put("messages", JSONArray().put(user))
                WireFormat.OpenAiResponses -> base.plus("responses") to JSONObject()
                    .put("model", modelId)
                    .put("input", TEST_PROMPT)
                    .put("max_output_tokens", TEST_MAX_TOKENS)
                    .put("store", false)
                WireFormat.OpenAiChat -> {
                    val api = if (definition.id == ProviderCatalog.OLLAMA) {
                        rootOfOllama(base).newBuilder().addPathSegment("v1").build()
                    } else {
                        base
                    }
                    api.plus("chat/completions") to JSONObject()
                        .put("model", modelId)
                        .put("max_tokens", TEST_MAX_TOKENS)
                        .put("stream", false)
                        .put("messages", JSONArray().put(user))
                }
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
