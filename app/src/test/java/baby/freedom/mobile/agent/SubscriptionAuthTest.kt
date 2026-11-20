package baby.freedom.mobile.agent

import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The device-code sign-ins (#576) against a fake auth server: both
 * complete, polling follows RFC 8628 (pending, slow_down), every failure
 * is a [LoginFailure], and a sign-in page that isn't the provider's own
 * is never offered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionAuthTest {
    private val server = MockWebServer()

    /** Answers by path, each a queue; the last answer of a queue repeats. */
    private val routes = HashMap<String, ArrayDeque<() -> MockResponse>>()
    private val seen = ConcurrentLinkedQueue<RecordedRequest>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val q = routes[request.requestUrl!!.encodedPath] ?: return MockResponse().setResponseCode(404)
                return synchronized(q) { (if (q.size > 1) q.removeFirst() else q.first())() }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
        }
    }

    private fun route(path: String, vararg answers: () -> MockResponse) {
        routes[path] = ArrayDeque(answers.toList())
    }

    private fun json(body: Any, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    private val root get() = server.url("/").toString().removeSuffix("/")

    private fun TestScope.auth(maxWaitMs: Long = SubscriptionAuth.MAX_WAIT_MS) = SubscriptionAuth(
        endpoints = AuthEndpoints(openAiAuth = "$root/openai", metaAuth = "$root/meta", metaMint = "$root/mint"),
        wallClock = { 1_000_000L + testScheduler.currentTime },
        clock = { testScheduler.currentTime },
        maxWaitMs = maxWaitMs,
    )

    private fun requests(path: String) = seen.filter { it.requestUrl!!.encodedPath == path }

    // --- ChatGPT ---

    private fun codexStart(interval: Any = "5", code: String = "ABCD-1234") {
        route(
            "/openai/api/accounts/deviceauth/usercode",
            { json(JSONObject().put("device_auth_id", "dev-1").put("user_code", code).put("interval", interval)) },
        )
    }

    private fun codexTokens(access: String = jwt("acct-1")) = json(
        JSONObject().put("access_token", access).put("refresh_token", "rt-1").put("expires_in", 3600),
    )

    @Test
    fun `chatgpt signs in after pending and slow_down`() = runTest {
        codexStart()
        route(
            "/openai/api/accounts/deviceauth/token",
            { MockResponse().setResponseCode(403) },
            { json(JSONObject().put("error", JSONObject().put("code", "deviceauth_authorization_pending")), 400) },
            { json(JSONObject().put("error", JSONObject().put("code", "slow_down")), 400) },
            { json(JSONObject().put("authorization_code", "ac-1").put("code_verifier", "cv-1")) },
        )
        route("/openai/oauth/token", { codexTokens() })
        val auth = auth()
        val code = (auth.start(SubscriptionAuth.CODEX) as SubscriptionAuth.Started.Code).code
        assertEquals("ABCD-1234", code.userCode)
        // Desktop's fixed page; the code isn't in it.
        assertEquals("https://auth.openai.com/codex/device", code.verificationUrl)
        val startedAt = testScheduler.currentTime
        val done = auth.await(code) as SubscriptionAuth.Finished.Done
        val credential = done.credential
        assertEquals("rt-1", credential.refresh)
        assertEquals("acct-1", credential.accountId)
        assertEquals(1_000_000L + testScheduler.currentTime + 3_600_000L, credential.expiresAt)
        // Polled at once, then 5 s, 5 s, and 10 s after the slow_down.
        assertEquals(20_000L, testScheduler.currentTime - startedAt)
        assertEquals(4, requests("/openai/api/accounts/deviceauth/token").size)
        val poll = JSONObject(requests("/openai/api/accounts/deviceauth/token").first().body.readUtf8())
        assertEquals("dev-1", poll.getString("device_auth_id"))
        assertEquals("ABCD-1234", poll.getString("user_code"))
        val exchange = requests("/openai/oauth/token").single().body.readUtf8()
        assertTrue(exchange, "grant_type=authorization_code" in exchange)
        assertTrue(exchange, "code=ac-1" in exchange && "code_verifier=cv-1" in exchange)
        assertTrue(exchange, "redirect_uri=" + java.net.URLEncoder.encode("$root/openai/deviceauth/callback", "UTF-8") in exchange)
        assertTrue(exchange, "client_id=app_EMoamEEZ73f0CkXaXp7hrann" in exchange)
    }

    @Test
    fun `chatgpt without device sign-in, or with an unusable answer, stops before showing anything`() = runTest {
        val auth = auth()
        route("/openai/api/accounts/deviceauth/usercode", { MockResponse().setResponseCode(404) })
        assertEquals(SubscriptionAuth.Started.Failed(LoginFailure.NotEnabled), auth.start(SubscriptionAuth.CODEX))
        route("/openai/api/accounts/deviceauth/usercode", { MockResponse().setResponseCode(503) })
        assertEquals(SubscriptionAuth.Started.Failed(LoginFailure.ServerError), auth.start(SubscriptionAuth.CODEX))
        // A code that isn't one to show, a missing interval, not JSON.
        codexStart(code = "<script>")
        assertEquals(SubscriptionAuth.Started.Failed(LoginFailure.BadReply), auth.start(SubscriptionAuth.CODEX))
        route("/openai/api/accounts/deviceauth/usercode", { json(JSONObject().put("device_auth_id", "d").put("user_code", "ABCD-1234")) })
        assertEquals(SubscriptionAuth.Started.Failed(LoginFailure.BadReply), auth.start(SubscriptionAuth.CODEX))
        route("/openai/api/accounts/deviceauth/usercode", { MockResponse().setBody("[[[[") })
        assertEquals(SubscriptionAuth.Started.Failed(LoginFailure.BadReply), auth.start(SubscriptionAuth.CODEX))
    }

    @Test
    fun `chatgpt's interval may be a number`() = runTest {
        codexStart(interval = 7)
        val code = (auth().start(SubscriptionAuth.CODEX) as SubscriptionAuth.Started.Code).code
        assertEquals(7_000L, code.intervalMs)
    }

    @Test
    fun `a token without an account, or one that can't go in a header, isn't kept`() = runTest {
        codexStart()
        route("/openai/api/accounts/deviceauth/token", { json(JSONObject().put("authorization_code", "a").put("code_verifier", "v")) })
        route("/openai/oauth/token", { codexTokens(access = "not-a-jwt") })
        val auth = auth()
        val code = (auth.start(SubscriptionAuth.CODEX) as SubscriptionAuth.Started.Code).code
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.BadReply), auth.await(code))
        route("/openai/oauth/token", { codexTokens(access = jwt("acct-1") + "\r\nX-Evil: 1") })
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.BadReply), auth.await(code))
        route("/openai/oauth/token", { codexTokens(access = jwt("acct\u0000")) })
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.BadReply), auth.await(code))
    }

    @Test
    fun `a poll that doesn't get through is tried again, and the wait ends when the code does`() = runTest {
        codexStart()
        route("/openai/api/accounts/deviceauth/token", { MockResponse().setResponseCode(403) })
        val auth = auth(maxWaitMs = 60_000)
        val code = (auth.start(SubscriptionAuth.CODEX) as SubscriptionAuth.Started.Code).code
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.Expired), auth.await(code))
        assertEquals(60_000L, testScheduler.currentTime)
        // Another error, though, ends it.
        route("/openai/api/accounts/deviceauth/token", { json(JSONObject().put("error", "nope"), 400) })
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.BadReply), auth.await(code))
    }

    @Test
    fun `cancelling a sign-in stops its polling`() = runTest {
        codexStart()
        route("/openai/api/accounts/deviceauth/token", { MockResponse().setResponseCode(403) })
        val auth = auth()
        val code = (auth.start(SubscriptionAuth.CODEX) as SubscriptionAuth.Started.Code).code
        val job = launch { auth.await(code) }
        testScheduler.advanceTimeBy(12_000)
        job.cancel()
        job.join()
        // A request already on its way may still land: let it.
        Thread.sleep(300)
        val count = requests("/openai/api/accounts/deviceauth/token").size
        testScheduler.advanceTimeBy(60_000)
        Thread.sleep(300)
        assertEquals(count, requests("/openai/api/accounts/deviceauth/token").size)
    }

    @Test
    fun `chatgpt refresh renews, and a refused refresh means signing in again`() = runTest {
        val auth = auth()
        val old = SubscriptionCredential("old", "rt-0", 0, "acct-1")
        route("/openai/oauth/token", { codexTokens() })
        val fresh = (auth.refresh(SubscriptionAuth.CODEX, old) as RefreshOutcome.Fresh).credential
        assertEquals("rt-1", fresh.refresh)
        val body = requests("/openai/oauth/token").last().body.readUtf8()
        assertTrue(body, "grant_type=refresh_token" in body && "refresh_token=rt-0" in body)
        for (code in listOf(400, 401)) {
            route("/openai/oauth/token", { json(JSONObject().put("error", "invalid_grant"), code) })
            assertEquals(RefreshOutcome.SignInAgain, auth.refresh(SubscriptionAuth.CODEX, old))
        }
        // A server error isn't a sign-out.
        route("/openai/oauth/token", { MockResponse().setResponseCode(502) })
        assertEquals(RefreshOutcome.Failed(LoginFailure.ServerError), auth.refresh(SubscriptionAuth.CODEX, old))
        // Pi wants a new refresh token every time.
        route("/openai/oauth/token", { json(JSONObject().put("access_token", jwt("a")).put("expires_in", 60)) })
        assertEquals(RefreshOutcome.Failed(LoginFailure.BadReply), auth.refresh(SubscriptionAuth.CODEX, old))
    }

    // --- Meta ---

    private fun metaStart(complete: String? = "https://auth.meta.com/device?user_code=WXYZ-9876", plain: String? = null) {
        route(
            "/meta/oidc/device/authorization/",
            {
                json(
                    JSONObject().put("device_code", "dc-1").put("user_code", "WXYZ-9876").put("interval", 5).put("expires_in", 600)
                        .apply {
                            complete?.let { put("verification_uri_complete", it) }
                            plain?.let { put("verification_uri", it) }
                        },
                )
            },
        )
    }

    @Test
    fun `meta signs in and mints a model API key`() = runTest {
        metaStart()
        route(
            "/meta/oidc/device/token/",
            { json(JSONObject().put("error", "authorization_pending"), 400) },
            { json(JSONObject().put("error", "slow_down").put("interval", 8), 400) },
            { json(JSONObject().put("access_token", "identity-1")) },
        )
        route("/mint", { json(JSONObject().put("api_key", "mk-1")) })
        val auth = auth()
        val code = (auth.start(SubscriptionAuth.META) as SubscriptionAuth.Started.Code).code
        assertEquals("https://auth.meta.com/device?user_code=WXYZ-9876", code.verificationUrl)
        assertEquals(600_000L, code.expiresInMs)
        val done = auth.await(code) as SubscriptionAuth.Finished.Done
        // A wait before the first poll, 5 s after pending, then the server's 8 s.
        assertEquals(18_000L, testScheduler.currentTime)
        assertEquals("mk-1", done.credential.access)
        assertEquals("identity-1", done.credential.refresh)
        assertNull(done.credential.accountId)
        assertEquals(1_000_000L + testScheduler.currentTime + 24 * 3_600_000L, done.credential.expiresAt)
        val poll = requests("/meta/oidc/device/token/").first().body.readUtf8()
        assertTrue(poll, "device_code=dc-1" in poll && "client_id=1031625952748946" in poll)
        assertTrue(poll, "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" in poll)
        val mint = requests("/mint").single()
        assertEquals("Bearer identity-1", mint.getHeader("Authorization"))
        assertEquals("1.0.0", mint.getHeader("x-api-version"))
    }

    @Test
    fun `meta refusals map to their reasons`() = runTest {
        metaStart()
        val auth = auth()
        val code = (auth.start(SubscriptionAuth.META) as SubscriptionAuth.Started.Code).code
        route("/meta/oidc/device/token/", { json(JSONObject().put("error", "access_denied"), 400) })
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.Denied), auth.await(code))
        route("/meta/oidc/device/token/", { json(JSONObject().put("error", "expired_token"), 400) })
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.Expired), auth.await(code))
        // Signed in, but no key to be had.
        route("/meta/oidc/device/token/", { json(JSONObject().put("access_token", "identity-1")) })
        route("/mint", { json(JSONObject().put("action_url", "https://evil.example/")) })
        assertEquals(SubscriptionAuth.Finished.Failed(LoginFailure.NoAccess), auth.await(code))
    }

    @Test
    fun `meta's key is renewed with the identity token until that's refused`() = runTest {
        val auth = auth()
        val old = SubscriptionCredential("mk-0", "identity-1", 0)
        route("/mint", { json(JSONObject().put("api_key", "mk-2")) })
        val fresh = (auth.refresh(SubscriptionAuth.META, old) as RefreshOutcome.Fresh).credential
        assertEquals("mk-2", fresh.access)
        assertEquals("identity-1", fresh.refresh)
        for (code in listOf(401, 403)) {
            route("/mint", { MockResponse().setResponseCode(code) })
            assertEquals(RefreshOutcome.SignInAgain, auth.refresh(SubscriptionAuth.META, old))
        }
        route("/mint", { MockResponse().setResponseCode(500) })
        assertEquals(RefreshOutcome.Failed(LoginFailure.ServerError), auth.refresh(SubscriptionAuth.META, old))
    }

    @Test
    fun `a meta sign-in page that isn't auth_meta_com stops the sign-in`() = runTest {
        val auth = auth()
        for (bad in LOOK_ALIKES) {
            metaStart(complete = bad)
            assertEquals(bad, SubscriptionAuth.Started.Failed(LoginFailure.BadReply), auth.start(SubscriptionAuth.META))
        }
        // A bad page with the code isn't swapped for a good plain one.
        metaStart(complete = "https://auth.meta.com.evil.test/device", plain = "https://auth.meta.com/device")
        assertEquals(SubscriptionAuth.Started.Failed(LoginFailure.BadReply), auth.start(SubscriptionAuth.META))
        // Without the page with the code, the plain one.
        metaStart(complete = null, plain = "https://auth.meta.com/device")
        val code = (auth.start(SubscriptionAuth.META) as SubscriptionAuth.Started.Code).code
        assertEquals("https://auth.meta.com/device", code.verificationUrl)
    }

    @Test
    fun `verification urls are only the providers' own`() {
        assertEquals(
            "https://auth.openai.com/codex/device",
            SubscriptionAuth.verificationUrl(SubscriptionAuth.CODEX, "https://auth.openai.com/codex/device"),
        )
        for (bad in listOf(
            "https://auth.openai.com/codex/device?x=1", "https://auth.openai.com/codex/device/",
            "http://auth.openai.com/codex/device", "https://auth.openai.com.evil.test/codex/device",
            "https://evil.test/codex/device", "https://auth.meta.com/device",
        )) {
            assertNull(bad, SubscriptionAuth.verificationUrl(SubscriptionAuth.CODEX, bad))
        }
        for (good in listOf(
            "https://auth.meta.com/device", "https://auth.meta.com/device?user_code=ABCD-1234",
            "https://auth.meta.com/",
        )) {
            assertEquals(good, SubscriptionAuth.verificationUrl(SubscriptionAuth.META, good))
        }
        for (bad in LOOK_ALIKES) assertNull(bad, SubscriptionAuth.verificationUrl(SubscriptionAuth.META, bad))
        assertNull(SubscriptionAuth.verificationUrl("openai", "https://auth.openai.com/codex/device"))
    }

    @Test
    fun `user codes and account ids`() {
        assertTrue(SubscriptionAuth.isUserCode("ABCD-1234"))
        for (bad in listOf("<script>", "abc", "ABCD 1234", " ABCD-1234", "ABCD-1234\n", "A".repeat(65), "ＡＢＣＤ-1234")) {
            assertTrue(bad, !SubscriptionAuth.isUserCode(bad))
        }
        assertEquals("acct-1", SubscriptionAuth.codexAccountId(jwt("acct-1")))
        assertNull(SubscriptionAuth.codexAccountId("a.b"))
        assertNull(SubscriptionAuth.codexAccountId("a.%%%.c"))
        assertNull(SubscriptionAuth.codexAccountId("a." + b64("{}") + ".c"))
        assertNull(SubscriptionAuth.codexAccountId("a." + b64("[[[") + ".c"))
    }

    @Test
    fun `a stored credential round-trips and never prints its tokens`() {
        val c = SubscriptionCredential("access-secret", "refresh-secret", 123, "acct")
        assertEquals(c, SubscriptionCredential.decode(c.encode()))
        assertTrue("secret" !in c.toString())
        assertNull(SubscriptionCredential.decode("sk-plain-api-key"))
        assertNull(SubscriptionCredential.decode(JSONObject().put("access", "a\nb").put("refresh", "r").toString()))
        assertTrue(c.due(123 - SubscriptionAuth.REFRESH_MARGIN_MS))
        assertTrue(!c.due(122 - SubscriptionAuth.REFRESH_MARGIN_MS))
    }

    companion object {
        /** Desktop's cases (`ipc.test.js`) and more: hosts that read like Meta's and aren't, and URLs a parser could read two ways. */
        val LOOK_ALIKES = listOf(
            "https://auth.meta.com.evil.test/device",
            "http://auth.meta.com/device",
            "https://user@auth.meta.com/device",
            "https://auth.meta.com/device#secret",
            "https://auth.meta.com@evil.test/device",
            "https://auth.meta.com\\@evil.test/device",
            "https://auth.meta.com\\evil.test/device",
            "https://auth.meta.com:8443/device",
            "https://auth.meta.com./device",
            "https://AUTH.META.COM/device",
            "https://evil.test/https://auth.meta.com/",
            "https://xn--uth-mta.meta.com/device",
            "https://аuth.meta.com/device",
            "https://auth.meta.com/de vice",
            "https://auth.meta.com/device\n",
            "https://auth.meta.com/device‮",
            "https://auth.meta.com",
            "https://auth.meta.com/" + "a".repeat(2100),
            "javascript:alert(1)//https://auth.meta.com/",
            "",
        )

        private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

        fun jwt(account: String): String =
            b64("{\"alg\":\"none\"}") + "." +
                b64(JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", account)).toString()) +
                ".sig"
    }
}
