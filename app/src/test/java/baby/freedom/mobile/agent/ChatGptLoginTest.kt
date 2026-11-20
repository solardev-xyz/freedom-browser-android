package baby.freedom.mobile.agent

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sign in with ChatGPT (#602) against a fake auth server, with a real
 * loopback listener: the authorize page carries PKCE (S256) and desktop's
 * parameters, the token endpoint checks the verifier against the
 * challenge the page carried, a callback with the wrong state, host or
 * method is refused and the sign-in keeps waiting, one that never comes
 * times out, and the port is free again afterwards.
 */
class ChatGptLoginTest {
    private val server = MockWebServer()
    private val seen = ConcurrentLinkedQueue<RecordedRequest>()
    private val port = freePort()

    /** The challenge the fake server holds the exchange's verifier to: the one the authorize page carried. */
    @Volatile
    private var challenge: String? = null

    /** What the token endpoint answers once the verifier checks out. */
    @Volatile
    private var grant: JSONObject = grant()

    private val now = 1_000_000_000L
    private lateinit var login: ChatGptLogin

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (request.requestUrl!!.encodedPath != ChatGptLogin.TOKEN_PATH) return MockResponse().setResponseCode(404)
                val form = form(request)
                if (form["grant_type"] == "authorization_code") {
                    val verifier = form["code_verifier"] ?: return MockResponse().setResponseCode(400)
                    if (ChatGptLogin.challengeOf(verifier) != challenge) {
                        return json(JSONObject().put("error", "invalid_grant").put("access_token", "leak"), 400)
                    }
                }
                return json(grant)
            }
        }
        server.start()
        login = loginWith(maxWaitMs = 10_000)
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (e: IOException) {
        }
    }

    private fun loginWith(maxWaitMs: Long, at: Int = port) = ChatGptLogin(
        copy = ::chatGptPageCopy,
        authBase = server.url("/").toString().removeSuffix("/"),
        port = at,
        wallClock = { now },
        maxWaitMs = maxWaitMs,
    )

    private fun grant(
        scope: String = "openid offline_access ${ChatGptLogin.DIRECT_SCOPE}",
        expiresIn: Any = 3600,
        idToken: String? = "id-1",
    ) = JSONObject().put("access_token", "at-1").put("refresh_token", "rt-1").put("scope", scope)
        .put("expires_in", expiresIn).apply { idToken?.let { put("id_token", it) } }

    private fun json(body: Any, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun form(r: RecordedRequest): Map<String, String> =
        ("http://x/?" + r.body.clone().readUtf8()).toHttpUrl().let { u ->
            u.queryParameterNames.associateWith { u.queryParameter(it)!! }
        }

    private fun tokenRequests() = seen.filter { it.requestUrl!!.encodedPath == ChatGptLogin.TOKEN_PATH }

    private fun open(l: ChatGptLogin = login): ChatGptLogin.Session {
        val opened = l.open(DEVICE)
        assertTrue(opened.toString(), opened is ChatGptLogin.Opened.Listening)
        val session = (opened as ChatGptLogin.Opened.Listening).session
        challenge = session.authorizeUrl.toHttpUrl().queryParameter("code_challenge")
        return session
    }

    private fun stateOf(s: ChatGptLogin.Session) = s.authorizeUrl.toHttpUrl().queryParameter("state")!!

    private fun callback(state: String, extra: String = "&code=code-1&client_id=issued-client") =
        "/auth/callback?state=$state$extra"

    /** A raw HTTP request to the listener: the status line and the whole answer. */
    private fun get(
        target: String,
        host: String = "127.0.0.1:$port",
        method: String = "GET",
        to: Int = port,
    ): Pair<Int, String> = Socket().use { s ->
        s.soTimeout = 15_000
        s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), to), 5_000)
        s.getOutputStream().write("$method $target HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
        val answer = s.getInputStream().readBytes().toString(Charsets.UTF_8)
        answer.substringAfter(' ').take(3).toInt() to answer
    }

    /** The listener let go of the port: a new one can bind it, as the next sign-in would. */
    private fun portClosed(): Boolean = canBind(port)

    @Test
    fun `the authorize page carries PKCE and desktop's parameters`() {
        val session = open()
        try {
            val url = session.authorizeUrl
            assertTrue(ChatGptLogin.isAuthorizeUrl(url))
            val u = url.toHttpUrl()
            assertEquals("auth.openai.com", u.host)
            assertEquals("/api/accounts/authorize", u.encodedPath)
            assertEquals("dynamic_agent_client", u.queryParameter("client_id"))
            assertEquals("Freedom Browser", u.queryParameter("agent_name_hint"))
            assertEquals("urn:uuid:$DEVICE", u.queryParameter("ext_agent_host_id"))
            assertEquals("code", u.queryParameter("response_type"))
            assertEquals("http://127.0.0.1:$port/auth/callback", u.queryParameter("redirect_uri"))
            assertEquals("https://api.openai.com/v1", u.queryParameter("resource"))
            assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", u.queryParameter("scope"))
            assertEquals("S256", u.queryParameter("code_challenge_method"))
            // 32 random bytes each, base64url without padding.
            for (p in listOf("state", "nonce", "code_challenge")) assertEquals(p, 43, u.queryParameter(p)!!.length)
            assertFalse(u.queryParameter("state") == u.queryParameter("nonce"))
        } finally {
            session.close()
        }
        // A second sign-in has its own state and challenge.
        val again = open()
        again.close()
        assertFalse(again.authorizeUrl == session.authorizeUrl)
    }

    @Test
    fun `a callback with this sign-in's state is exchanged with the verifier, and the tab told once saved`() = runBlocking {
        val session = open()
        val finished = async(Dispatchers.IO) { login.finish(session) }
        // The browser's tab waits for its page until the caller has saved.
        val tab = async(Dispatchers.IO) { get(callback(stateOf(session))) }
        val done = finished.await()
        assertTrue(done.toString(), done is SubscriptionAuth.Finished.Done)
        val credential = (done as SubscriptionAuth.Finished.Done).credential
        assertEquals("at-1", credential.access)
        assertEquals("rt-1", credential.refresh)
        assertEquals("issued-client", credential.clientId)
        assertNull(credential.accountId)
        assertEquals(now + 3600_000 - ChatGptLogin.EXPIRY_MARGIN_MS, credential.expiresAt)
        val form = form(tokenRequests().single())
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("issued-client", form["client_id"])
        assertEquals("code-1", form["code"])
        assertEquals("http://127.0.0.1:$port/auth/callback", form["redirect_uri"])
        assertEquals("https://api.openai.com/v1", form["resource"])
        assertEquals(challenge, ChatGptLogin.challengeOf(form["code_verifier"]!!))
        assertFalse(tab.isCompleted)
        session.answer(CallbackPage.Success)
        val (status, page) = tab.await()
        assertEquals(200, status)
        assertTrue(page.contains("ChatGPT connected"))
        assertTrue(page.contains("Cache-Control: no-store"))
        assertTrue(page.contains("Referrer-Policy: no-referrer"))
        assertTrue(page.contains("Content-Security-Policy: default-src 'none'"))
        assertFalse(page.contains("<script"))
        session.close()
        assertTrue(portClosed())
    }

    @Test
    fun `a wrong state, host, method or path is refused and the sign-in keeps waiting`() = runBlocking {
        val session = open()
        val finished = async(Dispatchers.IO) { login.finish(session) }
        val state = stateOf(session)
        for ((target, host, method) in listOf(
            Triple(callback("stale"), "127.0.0.1:$port", "GET"),
            Triple(callback("stale", "&error=access_denied"), "127.0.0.1:$port", "GET"),
            Triple(callback(state.dropLast(1)), "127.0.0.1:$port", "GET"),
            Triple(callback(state), "evil.example", "GET"),
            Triple(callback(state), "localhost:$port", "GET"),
            Triple(callback(state), "127.0.0.1:$port", "POST"),
            Triple("/other?state=$state&code=c&client_id=x", "127.0.0.1:$port", "GET"),
            Triple(callback(state, "&code=code-1"), "127.0.0.1:$port", "GET"),
            Triple(callback(state, "&client_id=x"), "127.0.0.1:$port", "GET"),
        )) {
            val (status, page) = get(target, host, method)
            assertEquals("$method $host $target", 400, status)
            assertTrue(page.contains("Invalid sign-in link"))
        }
        assertFalse(finished.isCompleted)
        assertTrue(tokenRequests().isEmpty())
        // The real one still counts.
        val tab = async(Dispatchers.IO) { get(callback(state)) }
        assertTrue(finished.await() is SubscriptionAuth.Finished.Done)
        // A second callback, even a matching one, is refused.
        assertEquals(409, get(callback(state)).first)
        session.answer(CallbackPage.Success)
        assertEquals(200, tab.await().first)
        assertEquals(1, tokenRequests().size)
        session.close()
    }

    @Test
    fun `a verifier the token endpoint doesn't accept fails the sign-in and says nothing of the answer`() = runBlocking {
        val session = open()
        // The fake server now expects a different challenge: the verifier sent is wrong for it.
        challenge = ChatGptLogin.challengeOf("not-this-sign-ins-verifier")
        val finished = async(Dispatchers.IO) { login.finish(session) }
        val (status, page) = get(callback(stateOf(session)))
        assertEquals(400, status)
        assertTrue(page.contains("Connection unsuccessful"))
        assertFalse(page.contains("leak"))
        assertFalse(page.contains("invalid_grant"))
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.NotAccepted), finished.await())
        session.close()
        assertTrue(portClosed())
    }

    // Also pins Session.close() waiting for the accept thread: a blocked
    // accept() keeps the port until that thread finishes the close, and
    // without the wait this test fails (the port can't be bound again).
    @Test
    fun `no callback in time ends the sign-in and frees the port`() = runBlocking {
        val quick = loginWith(maxWaitMs = 300)
        val session = open(quick)
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.NoCallback), quick.finish(session))
        session.close()
        assertTrue(portClosed())
        // A new sign-in can listen on it again straight away.
        open(quick).close()
    }

    @Test
    fun `a spare connection that sends nothing doesn't hold up the callback`() = runBlocking {
        val session = open()
        val idle = Socket("127.0.0.1", port)
        try {
            val finished = async(Dispatchers.IO) { login.finish(session) }
            val tab = async(Dispatchers.IO) { get(callback(stateOf(session))) }
            assertTrue(finished.await() is SubscriptionAuth.Finished.Done)
            session.answer(CallbackPage.Success)
            assertEquals(200, tab.await().first)
        } finally {
            idle.close()
            session.close()
        }
    }

    @Test
    fun `declining on OpenAI's page tells the tab it was cancelled, and nothing is exchanged`() = runBlocking {
        val session = open()
        val finished = async(Dispatchers.IO) { login.finish(session) }
        val (status, page) = get(callback(stateOf(session), "&error=access_denied"))
        assertEquals(400, status)
        assertTrue(page.contains("Connection cancelled"))
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.Denied), finished.await())
        assertTrue(tokenRequests().isEmpty())
        session.close()
        // Another error is not the user's no.
        val next = open()
        val other = async(Dispatchers.IO) { login.finish(next) }
        get(callback(stateOf(next), "&error=server_error"))
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.NotCompleted), other.await())
        next.close()
    }

    @Test
    fun `an incomplete grant is refused rather than saved`() = runBlocking {
        for (bad in listOf(
            grant(scope = "openid offline_access"),
            grant(expiresIn = -1),
            grant(expiresIn = "3600"),
            grant(idToken = null),
            grant().put("access_token", "has space"),
            grant().apply { remove("refresh_token") },
        )) {
            grant = bad
            val session = open()
            val finished = async(Dispatchers.IO) { login.finish(session) }
            val (status, page) = get(callback(stateOf(session)))
            assertEquals(bad.toString(), 400, status)
            assertTrue(page.contains("Connection unsuccessful"))
            assertEquals(bad.toString(), SubscriptionAuth.Finished.Failed(LoginFailure.BadReply), finished.await())
            session.close()
        }
    }

    @Test
    fun `a busy port fails before any page is offered`() {
        val holder = ServerSocket()
        holder.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port))
        try {
            assertEquals(ChatGptLogin.Opened.Failed(LoginFailure.PortBusy), login.open(DEVICE))
        } finally {
            holder.close()
        }
        assertEquals(ChatGptLogin.Opened.Failed(LoginFailure.BadReply), login.open("not-a-uuid"))
    }

    @Test
    fun `a pasted address is checked as the listener checks it`() = runBlocking {
        val session = open()
        val finished = async(Dispatchers.IO) { login.finish(session) }
        val state = stateOf(session)
        val base = "http://127.0.0.1:$port"
        for (bad in listOf(
            "$base${callback("stale")}",
            "http://localhost:$port${callback(state)}",
            "https://127.0.0.1:$port${callback(state)}",
            "http://127.0.0.1:${port + 1}${callback(state)}",
            "$base/elsewhere?state=$state&code=c&client_id=x",
            "not a url",
        )) {
            assertEquals(bad, ChatGptLogin.Submitted.Invalid, session.submit(bad))
        }
        assertTrue(tokenRequests().isEmpty())
        assertEquals(ChatGptLogin.Submitted.Accepted, session.submit("  $base${callback(state)}\n"))
        assertTrue(finished.await() is SubscriptionAuth.Finished.Done)
        assertEquals(ChatGptLogin.Submitted.Late, session.submit("$base${callback(state)}"))
        // The listener refuses the browser's late callback too.
        assertEquals(409, get(callback(state)).first)
        session.close()
    }

    @Test
    fun `a refresh asks as the issued client, and a refused one means signing in again`() = runBlocking {
        val old = SubscriptionCredential("at-0", "rt-0", 0, clientId = "issued-client")
        grant = grant(idToken = null)
        val fresh = login.refresh(old)
        assertTrue(fresh.toString(), fresh is RefreshOutcome.Fresh)
        assertEquals("issued-client", (fresh as RefreshOutcome.Fresh).credential.clientId)
        val form = form(tokenRequests().single())
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("issued-client", form["client_id"])
        assertEquals("rt-0", form["refresh_token"])
        assertEquals("https://api.openai.com/v1", form["resource"])
        // Without the issued client there's nothing to refresh as.
        assertEquals(RefreshOutcome.SignInAgain, login.refresh(old.copy(clientId = null)))
        assertEquals(1, tokenRequests().size)
        grant = grant(scope = "openid")
        assertEquals(RefreshOutcome.Failed(LoginFailure.BadReply), login.refresh(old))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = json(JSONObject().put("error", "invalid_grant"), 400)
        }
        assertEquals(RefreshOutcome.SignInAgain, login.refresh(old))
    }

    @Test
    fun `the credential keeps its issued client, and only a usable one`() {
        val c = SubscriptionCredential("at", "rt", 5, clientId = "issued-client")
        assertEquals(c, SubscriptionCredential.decode(c.encode()))
        assertNull(SubscriptionCredential.decode(JSONObject(c.encode()).put("clientId", "bad id").toString()))
        assertNull(SubscriptionCredential.decode(JSONObject(c.encode()).put("clientId", "x".repeat(201)).toString()))
        // Device-code credentials have none.
        assertNull(SubscriptionCredential.decode(SubscriptionCredential("at", "rt", 5).encode())!!.clientId)
    }

    @Test
    fun `only OpenAI's authorize page may be opened`() {
        val ok = "https://auth.openai.com/api/accounts/authorize?client_id=x&state=y"
        assertTrue(ChatGptLogin.isAuthorizeUrl(ok))
        for (bad in listOf(
            "http://auth.openai.com/api/accounts/authorize?x=1",
            "https://auth.openai.com.evil.test/api/accounts/authorize?x=1",
            "https://auth.openai.com/other?x=1",
            "https://auth.openai.com:8443/api/accounts/authorize?x=1",
            "https://user@auth.openai.com/api/accounts/authorize?x=1",
            "https://auth.openai.com/api/accounts/authorize?x=1#frag",
            "https://auth.openai.com\\@evil.test/api/accounts/authorize?x=1",
            "https://auth.openai.com/api/accounts/authorize?x= 1",
        )) {
            assertFalse(bad, ChatGptLogin.isAuthorizeUrl(bad))
        }
    }

    @Test
    fun `closing a sign-in tells a waiting tab it was cancelled`() = runBlocking {
        val session = open()
        val finished = async(Dispatchers.IO) { login.finish(session) }
        val tab = async(Dispatchers.IO) { get(callback(stateOf(session))) }
        assertTrue(finished.await() is SubscriptionAuth.Finished.Done)
        // Not saved (cancelled meanwhile): the caller closes without answering.
        session.close()
        val (status, page) = tab.await()
        assertEquals(400, status)
        assertTrue(page.contains("Connection cancelled"))
        assertTrue(portClosed())
    }

    @Test
    fun `a saved subscription or hosted provider shows its current name, a server the user's`() {
        val saved = AgentStore.encodeConnections(
            listOf(
                AgentConnection(SubscriptionAuth.CODEX, SubscriptionAuth.CODEX, "ChatGPT", revision = 1),
                AgentConnection("openai", "openai", "Old", revision = 2),
                AgentConnection("custom-1", ProviderCatalog.COMPATIBLE, "My box", revision = 3),
            ),
        )
        assertEquals(
            listOf("ChatGPT (device code)", "OpenAI", "My box"),
            AgentStore.decodeConnections(saved).map { it.name },
        )
    }

    companion object {
        const val DEVICE = "bcb1cbdb-7eae-4566-bc6c-d843484b676b"

        /** Whether [port] can be bound on 127.0.0.1 now, as a new sign-in's listener binds it. */
        fun canBind(port: Int): Boolean = try {
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port))
            }
            true
        } catch (e: IOException) {
            false
        }

        fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
    }
}
