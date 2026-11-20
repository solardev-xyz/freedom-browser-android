package baby.freedom.mobile.agent

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject

/*
 * Sign in with ChatGPT (#602, desktop's `openai-chatgpt`,
 * `src/main/agent/chatgpt-login.js`, after Pi 1.0.2's
 * `auth/oauth/openai-chatgpt.js`): OAuth with PKCE and a loopback
 * redirect. The phone listens on 127.0.0.1:1455 only while a sign-in is
 * waiting, the user signs in on OpenAI's page in a Custom Tab, and OpenAI
 * sends the browser back to the phone's listener with a code, which the
 * app exchanges, with its PKCE verifier, for a token used directly on
 * api.openai.com (the Responses API). The device-code sign-in (#576,
 * `openai-codex`) stays beside it.
 *
 * As on desktop: only a GET for exactly http://127.0.0.1:1455/auth/callback
 * with this sign-in's state counts; anything else gets a page saying the
 * link doesn't match and the sign-in keeps waiting; the first callback
 * that matches is the one used (later ones get 409); the browser's tab is
 * told how it ended only once the app knows. Nothing a server writes is
 * shown or logged.
 */

/** How a sign-in ended, as the browser tab that came back is told. */
internal enum class CallbackPage { Success, Cancelled, Failed, Invalid }

internal class ChatGptLogin(
    /** The page the browser tab shows for each ending: a title and a line. From string resources. */
    private val copy: (CallbackPage) -> Pair<String, String>,
    private val http: OkHttpClient = DEFAULT_HTTP,
    /** Where the token exchange and refreshes go; tests point it at a fake server. */
    private val authBase: String = AUTH_BASE,
    /** The listener's port; tests use their own. The redirect address is built from it. */
    private val port: Int = PORT,
    /** Wall clock, for [SubscriptionCredential.expiresAt]. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** The longest a sign-in waits for the browser to come back. */
    private val maxWaitMs: Long = SubscriptionAuth.MAX_WAIT_MS,
    private val random: SecureRandom = SecureRandom(),
) {
    val redirectUri: String get() = "http://127.0.0.1:$port$CALLBACK_PATH"

    sealed interface Opened {
        data class Listening(val session: Session) : Opened
        data class Failed(val failure: LoginFailure) : Opened
    }

    /** What a pasted callback address came to ([Session.submit]). */
    enum class Submitted { Accepted, Invalid, Late }

    /**
     * Starts a sign-in for this installation ([deviceId], a UUID, sent as
     * OpenAI's `ext_agent_host_id`): binds the listener, then builds the
     * page to open. A port that's taken fails here, before any page is
     * opened (desktop). The caller must [Session.close] what it gets.
     */
    fun open(deviceId: String): Opened {
        if (!UUID.matches(deviceId)) return Opened.Failed(LoginFailure.NoDeviceId)
        val verifier = randomValue()
        val state = randomValue()
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            // A literal 127.0.0.1: what the redirect names, and what the browser dials.
            server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), BACKLOG)
        } catch (e: IOException) {
            closeQuietly(server)
            return Opened.Failed(LoginFailure.PortBusy)
        } catch (e: SecurityException) {
            closeQuietly(server)
            return Opened.Failed(LoginFailure.PortBusy)
        }
        val url = HttpUrl.Builder()
            .scheme("https").host(AUTHORIZE_HOST).encodedPath(AUTHORIZE_PATH)
            .addQueryParameter("client_id", DYNAMIC_CLIENT_ID)
            .addQueryParameter("agent_name_hint", AGENT_NAME)
            .addQueryParameter("ext_agent_host_id", "urn:uuid:" + deviceId.lowercase())
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", randomValue())
            .addQueryParameter("code_challenge", challengeOf(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
        return Opened.Listening(Session(server, url, state, verifier))
    }

    /** What came back on the redirect: a code and the client OpenAI issued, or OpenAI's error. */
    internal sealed interface Callback {
        data class Code(val code: String, val clientId: String) : Callback {
            override fun toString() = "Code"
        }

        data class Error(val error: String) : Callback
    }

    /** The callback that was taken, and the browser connection it came on (none if pasted), held open for its page. */
    private class Arrival(val callback: Callback, val socket: Socket?)

    /**
     * One sign-in: the listener, the page to open ([authorizeUrl]) and
     * the secrets checked against what comes back. [close] stops
     * listening and answers any browser tab still waiting.
     */
    inner class Session internal constructor(
        private val server: ServerSocket,
        val authorizeUrl: String,
        private val state: String,
        internal val verifier: String,
    ) : Closeable {
        private val arrival = CompletableDeferred<Arrival>()
        private val connections = HashSet<Socket>()
        private val handling = AtomicInteger()

        /**
         * The page the sign-in ended with, once [answer] has run. Set, and
         * read together with [arrival], under [answerLock]: whichever of
         * [answer] and a callback comes second sends the page, so a
         * callback landing as the sign-in ends (a timeout, Cancel) still
         * gets it rather than an empty answer from [close].
         */
        private var answered: CallbackPage? = null
        private val answerLock = Any()
        private val closed = AtomicBoolean(false)

        private val acceptor = thread(isDaemon = true, name = "chatgpt-sign-in") { acceptLoop() }

        private fun acceptLoop() {
            while (!closed.get()) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                // Browsers open spare connections that may never send a
                // request: each is handled on its own, with a read deadline,
                // so none holds up the real callback.
                if (handling.incrementAndGet() > MAX_CONNECTIONS || !track(socket)) {
                    handling.decrementAndGet()
                    closeQuietly(socket)
                    continue
                }
                thread(isDaemon = true, name = "chatgpt-sign-in-request") {
                    try {
                        handle(socket)
                    } catch (e: Throwable) {
                        release(socket)
                    } finally {
                        handling.decrementAndGet()
                    }
                }
            }
        }

        private fun track(socket: Socket): Boolean = synchronized(connections) {
            if (closed.get()) false else connections.add(socket)
        }

        private fun release(socket: Socket) {
            synchronized(connections) { connections.remove(socket) }
            closeQuietly(socket)
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = READ_TIMEOUT_MS
            val head = readHead(socket) ?: return release(socket)
            val lines = head.split("\r\n")
            val request = lines.first().split(' ')
            val host = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.filter { it.first == "host" }
            val target = request.getOrNull(1)
            val callback = if (
                request.size == 3 && request[0] == "GET" && target != null && target.startsWith("/") &&
                host.size == 1 && host[0].second == "127.0.0.1:$port"
            ) {
                ("http://127.0.0.1:$port$target").toHttpUrlOrNull()?.let(::callbackOf)
            } else {
                null
            }
            if (callback == null) {
                respond(socket, 400, CallbackPage.Invalid)
                return release(socket)
            }
            // The first callback that matches is the one; the browser's tab
            // waits for its page until the sign-in has ended, or gets it now
            // if it already has.
            val (taken, page) = synchronized(answerLock) { arrival.complete(Arrival(callback, socket)) to answered }
            if (!taken) {
                respond(socket, 409, CallbackPage.Invalid)
                release(socket)
            } else if (page != null) {
                respond(socket, statusOf(page), page)
                release(socket)
            }
        }

        /** The request line and headers, or null past [MAX_HEAD_BYTES] or the read deadline. */
        private fun readHead(socket: Socket): String? = try {
            val input = socket.getInputStream()
            val bytes = java.io.ByteArrayOutputStream()
            var tail = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                bytes.write(b)
                if (bytes.size() > MAX_HEAD_BYTES) return null
                // \r\n\r\n
                tail = when {
                    b == '\r'.code && (tail == 0 || tail == 2) -> tail + 1
                    b == '\n'.code && (tail == 1 || tail == 3) -> tail + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (tail == 4) break
            }
            String(bytes.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
        } catch (e: IOException) {
            null
        }

        /**
         * This sign-in's callback in [url], or null if [url] isn't one:
         * exactly http://127.0.0.1:<port>/auth/callback, with this
         * sign-in's state, and either OpenAI's error or a code with the
         * client OpenAI issued (desktop's `callbackResult`).
         */
        internal fun callbackOf(url: HttpUrl): Callback? {
            if (url.scheme != "http" || url.host != "127.0.0.1" || url.port != port) return null
            if (url.encodedPath != CALLBACK_PATH || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val got = url.queryParameter("state") ?: return null
            if (!MessageDigest.isEqual(got.toByteArray(Charsets.UTF_8), state.toByteArray(Charsets.UTF_8))) return null
            url.queryParameter("error")?.takeIf { it.isNotEmpty() }?.let { return Callback.Error(it) }
            val code = url.queryParameter("code")?.takeIf { SubscriptionAuth.headerSafe(it) && it.length <= MAX_CODE } ?: return null
            val clientId = url.queryParameter("client_id")?.trim()?.takeIf(::isClientId) ?: return null
            return Callback.Code(code, clientId)
        }

        /**
         * The address the browser ended on, pasted by the user when the
         * redirect didn't reach the phone (desktop's manual fallback):
         * checked exactly as the listener checks it.
         */
        fun submit(pasted: String): Submitted {
            val text = pasted.trim()
            if (text.length > MAX_HEAD_BYTES) return Submitted.Invalid
            val callback = text.toHttpUrlOrNull()?.let(::callbackOf) ?: return Submitted.Invalid
            val taken = synchronized(answerLock) { arrival.complete(Arrival(callback, null)) }
            return if (taken) Submitted.Accepted else Submitted.Late
        }

        /** Waits for the callback, up to [timeoutMs]; null when none came. */
        internal suspend fun await(timeoutMs: Long): Callback? =
            withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { arrival.await().callback } }

        /** Tells the browser's tab, if it's waiting, how the sign-in ended. Only the first answer counts. */
        fun answer(page: CallbackPage) {
            val socket = synchronized(answerLock) {
                if (answered != null) return
                answered = page
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                if (arrival.isCompleted) arrival.getCompleted().socket else null
            }
            if (socket != null) {
                respond(socket, statusOf(page), page)
                release(socket)
            }
        }

        private fun statusOf(page: CallbackPage) = if (page == CallbackPage.Success) 200 else 400

        /** Stops listening; a tab still waiting is told the sign-in was cancelled. Idempotent. */
        override fun close() {
            answer(CallbackPage.Cancelled)
            if (!closed.compareAndSet(false, true)) return
            closeQuietly(server)
            val open = synchronized(connections) { connections.toList().also { connections.clear() } }
            open.forEach(::closeQuietly)
            // Closing a socket another thread is blocked accepting on returns
            // before the port is let go: that thread finishes the close. Wait
            // for it, so a sign-in started right after can bind the port.
            if (Thread.currentThread() !== acceptor) acceptor.join(CLOSE_WAIT_MS)
        }

        // The address carries the state and challenge.
        override fun toString() = "Session(port=$port)"
    }

    /**
     * Waits for [session]'s callback and exchanges its code. On anything
     * but [SubscriptionAuth.Finished.Done], the browser's tab has been
     * told; on Done, the caller tells it ([Session.answer]) once the
     * sign-in is saved.
     */
    suspend fun finish(session: Session, onCallback: () -> Unit = {}): SubscriptionAuth.Finished {
        val callback = session.await(maxWaitMs)
        if (callback == null) {
            session.answer(CallbackPage.Failed)
            return SubscriptionAuth.Finished.Failed(LoginFailure.NoCallback)
        }
        onCallback()
        val result = when (callback) {
            is Callback.Error -> SubscriptionAuth.Finished.Failed(
                if (callback.error == "access_denied") LoginFailure.Denied else LoginFailure.NotCompleted,
            )
            is Callback.Code -> exchange(callback, session.verifier)
        }
        if (result is SubscriptionAuth.Finished.Failed) {
            session.answer(if (result.failure == LoginFailure.Denied) CallbackPage.Cancelled else CallbackPage.Failed)
        }
        return result
    }

    private suspend fun exchange(callback: Callback.Code, verifier: String): SubscriptionAuth.Finished {
        val reply = AuthHttp.post(
            http,
            authBase + TOKEN_PATH,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", callback.clientId)
                .add("code", callback.code)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri)
                .add("resource", RESOURCE)
                .build(),
        )
        return tokens(reply, callback.clientId, needsIdToken = true, unusable = LoginFailure.BadGrant)
    }

    /** Renews [credential] as the client it was issued to (Pi's `refreshAccessToken`). */
    suspend fun refresh(credential: SubscriptionCredential): RefreshOutcome {
        val clientId = credential.clientId ?: return RefreshOutcome.SignInAgain
        val reply = AuthHttp.post(
            http,
            authBase + TOKEN_PATH,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", credential.refresh)
                .add("resource", RESOURCE)
                .build(),
        )
        // A refresh token that's been used, revoked or expired.
        if (reply is AuthReply.Answer && reply.code in 400..403) return RefreshOutcome.SignInAgain
        return when (val t = tokens(reply, clientId, needsIdToken = false, unusable = LoginFailure.BadReply)) {
            is SubscriptionAuth.Finished.Done -> RefreshOutcome.Fresh(t.credential)
            is SubscriptionAuth.Finished.Failed -> RefreshOutcome.Failed(t.failure)
        }
    }

    /**
     * A token answer, checked as desktop checks it: access and refresh
     * tokens, a scope that includes direct use of the token
     * ([DIRECT_SCOPE]), a lifetime, and on sign-in an ID token too. An
     * answer that falls short is [unusable]: [LoginFailure.BadGrant] on
     * sign-in, so only an answer OpenAI actually gave is blamed on it.
     */
    private fun tokens(
        reply: AuthReply,
        clientId: String,
        needsIdToken: Boolean,
        unusable: LoginFailure,
    ): SubscriptionAuth.Finished {
        val body = when (reply) {
            is AuthReply.Failed -> return SubscriptionAuth.Finished.Failed(reply.failure)
            is AuthReply.Answer -> {
                if (reply.code in 400..499 && reply.code != 429) {
                    return SubscriptionAuth.Finished.Failed(LoginFailure.NotAccepted)
                }
                if (reply.code !in 200..299) return SubscriptionAuth.Finished.Failed(AuthHttp.failureOf(reply.code))
                reply.json() ?: return SubscriptionAuth.Finished.Failed(unusable)
            }
        }
        fun text(key: String) = (body.opt(key) as? String)?.takeIf { it.isNotBlank() }
        val access = text("access_token")
        val refresh = text("refresh_token")
        val scope = text("scope")
        val expiresIn = (body.opt("expires_in") as? Number)?.toDouble()
        if (access == null || refresh == null || scope == null || (needsIdToken && text("id_token") == null) ||
            expiresIn == null || !expiresIn.isFinite() || expiresIn <= 0 ||
            DIRECT_SCOPE !in scope.trim().split(WHITESPACE) ||
            !SubscriptionAuth.headerSafe(access) || !SubscriptionAuth.headerSafe(refresh)
        ) {
            return SubscriptionAuth.Finished.Failed(unusable)
        }
        val lifetime = (expiresIn * 1000).toLong().coerceAtMost(MAX_LIFETIME_MS)
        return SubscriptionAuth.Finished.Done(
            SubscriptionCredential(access, refresh, wallClock() + lifetime - EXPIRY_MARGIN_MS, clientId = clientId),
        )
    }

    private fun randomValue(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Writes [page] as the whole answer; the browser may already have gone. */
    private fun respond(socket: Socket, status: Int, page: CallbackPage) {
        try {
            val body = pageHtml(copy(page)).toByteArray(Charsets.UTF_8)
            val reason = when (status) {
                200 -> "OK"
                409 -> "Conflict"
                else -> "Bad Request"
            }
            val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: $CSP\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        } catch (e: IOException) {
            // The tab was closed: nothing to tell.
        }
    }

    companion object {
        const val PROVIDER = "openai-chatgpt"
        const val PORT = 1455
        const val CALLBACK_PATH = "/auth/callback"
        const val AUTH_BASE = "https://auth.openai.com"
        const val AUTHORIZE_HOST = "auth.openai.com"
        const val AUTHORIZE_PATH = "/api/accounts/authorize"
        const val TOKEN_PATH = "/api/accounts/oauth/token"
        const val RESOURCE = "https://api.openai.com/v1"
        const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPE = "openid profile email offline_access resource.invoke $DIRECT_SCOPE"

        /** Every sign-in registers a new client; OpenAI names it in the callback. */
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"

        /** Desktop's `agent_name_hint`. */
        const val AGENT_NAME = "Freedom Browser"

        /** Desktop's (Pi's) margin: a token counts as run out 3 minutes early. */
        const val EXPIRY_MARGIN_MS = 3 * 60_000L

        /** No token is trusted to live longer than a year, whatever the answer says. */
        private const val MAX_LIFETIME_MS = 365L * 24 * 60 * 60_000L

        private const val BACKLOG = 8
        private const val MAX_CONNECTIONS = 16
        private const val READ_TIMEOUT_MS = 10_000
        private const val CLOSE_WAIT_MS = 2_000L
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_CODE = 4096

        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("\\s+")

        /** Desktop's callback page CSP: no scripts, no remote anything. */
        private const val CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; " +
            "frame-ancestors 'none'; form-action 'none'"

        private val DEFAULT_HTTP: OkHttpClient by lazy {
            ProviderClient.httpClient().newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        }

        /** An issued client id: it goes in a form and is stored with the token. */
        fun isClientId(value: String): Boolean = value.length <= 200 && SubscriptionAuth.headerSafe(value)

        /** The PKCE S256 challenge for [verifier]. */
        fun challengeOf(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /**
         * Whether [url] is a page this sign-in may open: OpenAI's
         * authorize page, on https://auth.openai.com, with nothing a URL
         * parser could read two ways (desktop's `normalizeSubscriptionAuthEvent`).
         */
        fun isAuthorizeUrl(url: String): Boolean {
            if (url.length > 4096 || url.any { it !in '!'..'~' || it == '\\' || it == '@' || it == '#' }) return false
            val u = url.toHttpUrlOrNull() ?: return false
            return u.scheme == "https" && u.host == AUTHORIZE_HOST && u.port == 443 && u.encodedPath == AUTHORIZE_PATH &&
                url.startsWith("https://$AUTHORIZE_HOST$AUTHORIZE_PATH?")
        }

        private fun pageHtml(copy: Pair<String, String>): String {
            val (title, line) = copy.first.escapeHtml() to copy.second.escapeHtml()
            return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>$title · Freedom</title><style>" +
                ":root{color-scheme:light dark;--bg:#f7f7f8;--text:#202126;--muted:#5e6069}" +
                "@media (prefers-color-scheme:dark){:root{--bg:#202020;--text:#f5f5f7;--muted:#bcbec5}}" +
                "*{box-sizing:border-box}" +
                "body{margin:0;min-height:100vh;display:grid;place-items:center;padding:32px;background:var(--bg);" +
                "color:var(--text);font:16px/1.6 system-ui,sans-serif;text-align:center}" +
                "main{max-width:480px}.brand{margin:0 0 32px;color:var(--muted);font-size:14px}" +
                "h1{margin:0 0 12px;font-size:28px;line-height:1.2}p{color:var(--muted);margin:0}" +
                "</style></head><body><main><p class=\"brand\">Freedom</p>" +
                "<h1>$title</h1><p>$line</p></main></body></html>"
        }

        private fun String.escapeHtml(): String = buildString {
            for (c in this@escapeHtml) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

        private fun closeQuietly(c: Closeable) {
            try {
                c.close()
            } catch (e: IOException) {
            }
        }
    }
}

/** The browser tab's page for [page], from string resources. */
internal fun chatGptPageCopy(page: CallbackPage): Pair<String, String> = when (page) {
    CallbackPage.Success -> R.string.agent_chatgpt_page_success_title to R.string.agent_chatgpt_page_success
    CallbackPage.Cancelled -> R.string.agent_chatgpt_page_cancelled_title to R.string.agent_chatgpt_page_cancelled
    CallbackPage.Failed -> R.string.agent_chatgpt_page_failed_title to R.string.agent_chatgpt_page_failed
    CallbackPage.Invalid -> R.string.agent_chatgpt_page_invalid_title to R.string.agent_chatgpt_page_invalid
}.let { (title, line) -> Strings.get(title) to Strings.get(line) }
