package baby.freedom.mobile.agent

import baby.freedom.mobile.R
import baby.freedom.mobile.agent.SubscriptionAuthTest.Companion.jwt
import baby.freedom.mobile.data.AesGcmCipher
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * Subscriptions in Settings → AI (#576) against a fake server: a
 * finished sign-in is saved as a connection and its token used, one
 * cancelled (by the user, signing out, AI off) never is, and a token is
 * renewed once, kept only for the sign-in it was renewed for.
 */
class SubscriptionProvidersTest {
    /** The settings file, with a hook run before each write: to hold one, or fail it. */
    private class HookedStore : DataStore<Preferences> {
        private val inner = MemoryStore()

        @Volatile
        var beforeWrite: (suspend () -> Unit)? = null
        override val data = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            beforeWrite?.invoke()
            return inner.updateData(transform)
        }
    }

    private val server = MockWebServer()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file = HookedStore()
    private val modelFile = MemoryStore()
    private val gate = RequestGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = HashMap<String, () -> MockResponse>()
    private val seen = ConcurrentLinkedQueue<RecordedRequest>()

    @Volatile
    private var now = 1_000_000_000L
    private lateinit var providers: AgentProviders

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return routes[request.requestUrl!!.encodedPath]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val root = server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")
        providers = AgentProviders(
            AgentStore(file, modelFile, AesGcmCipher { aes }),
            gate,
            ProviderClient(gate),
            hostedBase = { def -> "$root/${def.id}" },
            auth = SubscriptionAuth(
                endpoints = AuthEndpoints(openAiAuth = "$root/openai", metaAuth = "$root/meta", metaMint = "$root/mint"),
                wallClock = { now },
            ),
            wallClock = { now },
            loginScope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
        }
    }

    private fun json(body: Any, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun requests(path: String) = seen.filter { it.requestUrl!!.encodedPath == path }
    private val state get() = runBlocking { providers.state.first() }

    private fun codexServer(expiresIn: Int = 3600, pending: Boolean = false) {
        routes["/openai/api/accounts/deviceauth/usercode"] = {
            json(JSONObject().put("device_auth_id", "dev-1").put("user_code", "ABCD-1234").put("interval", "1"))
        }
        routes["/openai/api/accounts/deviceauth/token"] = {
            if (pending) {
                MockResponse().setResponseCode(403)
            } else {
                json(JSONObject().put("authorization_code", "ac").put("code_verifier", "cv"))
            }
        }
        routes["/openai/oauth/token"] = {
            json(JSONObject().put("access_token", jwt("acct-1")).put("refresh_token", "rt-1").put("expires_in", expiresIn))
        }
        routes["/openai-codex/codex/models"] = {
            json(
                JSONObject().put(
                    "models",
                    JSONArray()
                        .put(JSONObject().put("slug", "gpt-6.1-sol").put("display_name", "GPT-6.1 Sol").put("visibility", "list"))
                        .put(JSONObject().put("slug", "internal-x").put("visibility", "hide")),
                ),
            )
        }
    }

    private suspend fun signIn(providerId: String): LoginState = withTimeout(10_000) {
        assertTrue(providers.startLogin(providerId))
        providers.login.first { it is LoginState.Done || it is LoginState.Failed }!!
    }

    private fun sse(vararg events: JSONObject) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun reply(text: String) = sse(
        JSONObject().put("type", "response.output_text.delta").put("delta", text),
        JSONObject().put("type", "response.completed").put("response", JSONObject()),
    )

    private val request = StepRequest("gpt-6.1-sol", "be brief", listOf(ChatItem.User("hi")), emptyList(), 1234)

    @Test
    fun `a chatgpt sign-in is saved, lists the account's models, and chats with its token`() = runBlocking {
        codexServer()
        assertTrue(providers.setEnabled(true))
        assertEquals(LoginState.Done(SubscriptionAuth.CODEX), signIn(SubscriptionAuth.CODEX))
        val c = state.connection(SubscriptionAuth.CODEX)!!
        assertTrue(c.hasKey)
        assertEquals(ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra"), state.defaultModel)
        // The models Codex lists for picking, fetched right after signing in.
        withTimeout(5_000) { while (providers.models.first()[c.id] == null) delay(20) }
        assertEquals(listOf(AgentModel("gpt-6.1-sol", "GPT-6.1 Sol")), providers.models.first()[c.id])
        val catalog = requests("/openai-codex/codex/models").single()
        assertEquals("Bearer ${jwt("acct-1")}", catalog.getHeader("Authorization"))
        assertEquals("acct-1", catalog.getHeader("chatgpt-account-id"))
        assertEquals("0.153.0", catalog.requestUrl!!.queryParameter("client_version"))

        routes["/openai-codex/codex/responses"] = { reply("Hello") }
        val target = providers.chatTarget(ModelRef(c.id, "gpt-6.1-sol")) as ChatTarget.Ready
        assertEquals("acct-1", target.accountId)
        val out = StringBuilder()
        val step = providers.streamStep(target, request) { out.append(it) } as StepOutcome.Ok
        assertEquals("Hello", step.result.text)
        val sent = requests("/openai-codex/codex/responses").single()
        assertEquals("Bearer ${jwt("acct-1")}", sent.getHeader("Authorization"))
        assertEquals("acct-1", sent.getHeader("chatgpt-account-id"))
        assertEquals("pi", sent.getHeader("originator"))
        assertEquals("responses=experimental", sent.getHeader("OpenAI-Beta"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("be brief", body.getString("instructions"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("max_output_tokens"))
        // The system prompt isn't repeated as a message.
        assertEquals(1, body.getJSONArray("input").length())
        assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"))
    }

    @Test
    fun `a meta sign-in chats through the Model API with its minted key`() = runBlocking {
        routes["/meta/oidc/device/authorization/"] = {
            json(
                JSONObject().put("device_code", "dc").put("user_code", "WXYZ-9876").put("interval", 1)
                    .put("verification_uri", "https://auth.meta.com/device"),
            )
        }
        routes["/meta/oidc/device/token/"] = { json(JSONObject().put("access_token", "identity-1")) }
        routes["/mint"] = { json(JSONObject().put("api_key", "mk-1")) }
        assertTrue(providers.setEnabled(true))
        assertEquals(LoginState.Done(SubscriptionAuth.META), signIn(SubscriptionAuth.META))
        assertEquals(ModelRef(SubscriptionAuth.META, "muse-spark-1.3"), state.defaultModel)
        routes["/meta-subscription/responses"] = { reply("Hi") }
        val target = providers.chatTarget(ModelRef(SubscriptionAuth.META, "muse-spark-1.3")) as ChatTarget.Ready
        assertTrue(providers.streamStep(target, request) {} is StepOutcome.Ok)
        assertEquals("Bearer mk-1", requests("/meta-subscription/responses").single().getHeader("Authorization"))
        // Its plain Responses shape, with the system prompt as a message.
        val body = JSONObject(requests("/meta-subscription/responses").single().body.readUtf8())
        assertEquals("system", body.getJSONArray("input").getJSONObject(0).getString("role"))
    }

    @Test
    fun `no sign-in starts while AI is off`() = runBlocking {
        codexServer()
        assertTrue(providers.startLogin(SubscriptionAuth.CODEX))
        withTimeout(5_000) { providers.login.first { it == null } }
        assertEquals(0, server.requestCount)
        // Nor for a provider that isn't a subscription.
        assertFalse(providers.startLogin("openai"))
    }

    @Test
    fun `turning AI off, signing out or cancelling ends a sign-in, and it's never saved`() = runBlocking {
        codexServer(pending = true)
        assertTrue(providers.setEnabled(true))
        for (end in listOf<suspend () -> Unit>(
            { providers.setEnabled(false); providers.setEnabled(true) },
            { providers.remove(SubscriptionAuth.CODEX) },
            { providers.cancelLogin(SubscriptionAuth.CODEX) },
        )) {
            assertTrue(providers.startLogin(SubscriptionAuth.CODEX))
            val waiting = withTimeout(5_000) { providers.login.first { it is LoginState.Waiting } } as LoginState.Waiting
            assertEquals("ABCD-1234", waiting.code.userCode)
            end()
            assertNull(providers.login.value)
            // A poll already on its way when it ended may still land.
            delay(300)
            val polls = requests("/openai/api/accounts/deviceauth/token").size
            // Even if the user now enters the code, nothing comes of it.
            codexServer(pending = false)
            delay(2_500)
            assertEquals(polls, requests("/openai/api/accounts/deviceauth/token").size)
            assertNull(state.connection(SubscriptionAuth.CODEX))
            assertNull(providers.login.value)
            codexServer(pending = true)
        }
    }

    @Test
    fun `cancelling another provider's sign-in leaves this one running`() = runBlocking {
        codexServer(pending = true)
        assertTrue(providers.setEnabled(true))
        assertTrue(providers.startLogin(SubscriptionAuth.CODEX))
        withTimeout(5_000) { providers.login.first { it is LoginState.Waiting } }
        providers.cancelLogin(SubscriptionAuth.META)
        assertTrue(providers.remove(SubscriptionAuth.META))
        assertTrue(providers.login.value is LoginState.Waiting)
        codexServer(pending = false)
        withTimeout(5_000) { providers.login.first { it is LoginState.Done } }
        assertTrue(state.connection(SubscriptionAuth.CODEX) != null)
    }

    @Test
    fun `a due token is renewed once for two callers, and the new one kept`() = runBlocking {
        codexServer(expiresIn = 3600)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val ref = ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")
        // Not due yet: no refresh.
        assertTrue(providers.chatTarget(ref) is ChatTarget.Ready)
        assertEquals(1, requests("/openai/oauth/token").size)
        now += 3_600_000L
        val release = CountDownLatch(1)
        routes["/openai/oauth/token"] = {
            release.await(5, TimeUnit.SECONDS)
            json(JSONObject().put("access_token", jwt("acct-1")).put("refresh_token", "rt-2").put("expires_in", 3600))
        }
        val revision = state.connection(SubscriptionAuth.CODEX)!!.revision
        val a = async(Dispatchers.IO) { providers.chatTarget(ref) }
        val b = async(Dispatchers.IO) { providers.chatTarget(ref) }
        delay(300)
        release.countDown()
        assertTrue(a.await() is ChatTarget.Ready)
        assertTrue(b.await() is ChatTarget.Ready)
        val refreshes = requests("/openai/oauth/token").filter { "grant_type=refresh_token" in it.body.clone().readUtf8() }
        assertEquals(1, refreshes.size)
        assertTrue("refresh_token=rt-1" in refreshes.single().body.readUtf8())
        assertEquals("rt-2", SubscriptionCredential.decode(state.keys[SubscriptionAuth.CODEX])!!.refresh)
        // The same sign-in: a chat streaming meanwhile isn't cancelled.
        assertEquals(revision, state.connection(SubscriptionAuth.CODEX)!!.revision)
    }

    @Test
    fun `a refused renewal asks to sign in again, a failing one uses the token until it runs out`() = runBlocking {
        codexServer(expiresIn = 600)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val ref = ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")
        // Within five minutes of running out: due, but still good.
        now += 400_000L
        routes["/openai/oauth/token"] = { MockResponse().setResponseCode(503) }
        assertTrue(providers.chatTarget(ref) is ChatTarget.Ready)
        now += 300_000L
        assertEquals(ChatTarget.Unavailable(ChatError.ServerError), providers.chatTarget(ref))
        assertEquals(TestResult.ServerError, providers.testConnection(SubscriptionAuth.CODEX, "gpt-6-astra"))
        assertFalse(SubscriptionAuth.CODEX in providers.signedOut.value)
        routes["/openai/oauth/token"] = { json(JSONObject().put("error", "invalid_grant"), 401) }
        assertEquals(ChatTarget.Unavailable(ChatError.SignInAgain), providers.chatTarget(ref))
        assertEquals(RefreshResult.SignInAgain, providers.refreshModels(SubscriptionAuth.CODEX))
        assertTrue(SubscriptionAuth.CODEX in providers.signedOut.value)
        // Signing in again clears it.
        codexServer()
        signIn(SubscriptionAuth.CODEX)
        assertFalse(SubscriptionAuth.CODEX in providers.signedOut.value)
    }

    @Test
    fun `a token renewed while the user signs out is dropped`() = runBlocking {
        codexServer(expiresIn = 60)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val release = CountDownLatch(1)
        routes["/openai/oauth/token"] = {
            release.await(5, TimeUnit.SECONDS)
            json(JSONObject().put("access_token", jwt("acct-1")).put("refresh_token", "rt-2").put("expires_in", 3600))
        }
        val pending = async(Dispatchers.IO) { providers.chatTarget(ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")) }
        withTimeout(5_000) { while (requests("/openai/oauth/token").size < 2) delay(20) }
        assertTrue(providers.remove(SubscriptionAuth.CODEX))
        release.countDown()
        assertEquals(ChatTarget.Unavailable(ChatError.KeyChanged), pending.await())
        assertNull(state.connection(SubscriptionAuth.CODEX))
        assertTrue(state.keys.isEmpty())
    }

    private fun renewed(tag: String, refresh: String = "rt-2") = json(
        JSONObject().put("access_token", jwt("acct-1", tag)).put("refresh_token", refresh).put("expires_in", 3600),
    )

    @Test
    fun `a 401 renews the token once and tries again, and only a refused renewal means signing in again`() = runBlocking {
        codexServer()
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val target = providers.chatTarget(ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")) as ChatTarget.Ready
        // The first token was refused before it ran out; the renewed one works.
        routes["/openai-codex/codex/responses"] = {
            if (seen.count { it.requestUrl!!.encodedPath == "/openai-codex/codex/responses" } == 1) {
                MockResponse().setResponseCode(401)
            } else {
                reply("Hello")
            }
        }
        routes["/openai/oauth/token"] = { renewed("second") }
        val ok = providers.streamStep(target, request) {} as StepOutcome.Ok
        assertEquals("Hello", ok.result.text)
        val sent = requests("/openai-codex/codex/responses")
        assertEquals(listOf("Bearer ${jwt("acct-1")}", "Bearer ${jwt("acct-1", "second")}"), sent.map { it.getHeader("Authorization") })
        assertEquals("rt-2", SubscriptionCredential.decode(state.keys[SubscriptionAuth.CODEX])!!.refresh)
        // A 403 doesn't say the token's dead: no renewal for it, and not
        // signed out. The account was refused, not a key (it has none).
        val renewals = requests("/openai/oauth/token").size
        routes["/openai-codex/codex/responses"] = { MockResponse().setResponseCode(403) }
        assertEquals(StepOutcome.Failed(ChatError.AccountRefused), providers.streamStep(target, request) {})
        assertEquals(TestResult.Unauthorized, providers.testConnection(SubscriptionAuth.CODEX, "gpt-6-astra"))
        assertEquals(renewals, requests("/openai/oauth/token").size)
        assertFalse(SubscriptionAuth.CODEX in providers.signedOut.value)
        // The renewed token refused too: sign in again, and the row says so.
        routes["/openai-codex/codex/responses"] = { MockResponse().setResponseCode(401) }
        routes["/openai/oauth/token"] = { renewed("third", "rt-3") }
        assertEquals(StepOutcome.Failed(ChatError.SignInAgain), providers.streamStep(target, request) {})
        assertTrue(SubscriptionAuth.CODEX in providers.signedOut.value)
        // The renewal refused: sign in again.
        routes["/openai/oauth/token"] = { json(JSONObject().put("error", "invalid_grant"), 401) }
        assertEquals(StepOutcome.Failed(ChatError.SignInAgain), providers.streamStep(target, request) {})
        assertEquals(TestResult.SignInAgain, providers.testConnection(SubscriptionAuth.CODEX, "gpt-6-astra"))
        assertTrue(SubscriptionAuth.CODEX in providers.signedOut.value)
        // An API key's 403 is still the key's.
        assertEquals(R.string.agent_error_unauthorized, errorText(ChatError.Unauthorized))
        assertEquals(R.string.agent_error_account_refused, errorText(ChatError.AccountRefused))
    }

    @Test
    fun `a cancel tapped while a finished sign-in is saved waits for the save`() = runBlocking {
        codexServer(pending = true)
        assertTrue(providers.setEnabled(true))
        assertTrue(providers.startLogin(SubscriptionAuth.CODEX))
        withTimeout(5_000) { providers.login.first { it is LoginState.Waiting } }
        // Hold the save's write: past its check that the sign-in is current.
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        file.beforeWrite = {
            file.beforeWrite = null
            saving.complete(Unit)
            release.await()
        }
        codexServer(pending = false)
        withTimeout(5_000) { saving.await() }
        val cancel = async(Dispatchers.IO) { providers.cancelLogin(SubscriptionAuth.CODEX) }
        delay(300)
        // Cancel waits for the save rather than landing in its middle.
        assertFalse(cancel.isCompleted)
        assertTrue(providers.login.value is LoginState.Waiting)
        release.complete(Unit)
        withTimeout(5_000) { cancel.await() }
        // It landed after the save: signed in, and nothing on the page
        // claims otherwise (its Done is cleared, as for a finished one).
        assertTrue(state.connection(SubscriptionAuth.CODEX)!!.hasKey)
        assertNull(providers.login.value)
        // The same for starting the other provider's sign-in.
        assertTrue(providers.remove(SubscriptionAuth.CODEX))
        codexServer(pending = true)
        assertTrue(providers.startLogin(SubscriptionAuth.CODEX))
        withTimeout(5_000) { providers.login.first { it is LoginState.Waiting } }
        val saving2 = CompletableDeferred<Unit>()
        val release2 = CompletableDeferred<Unit>()
        file.beforeWrite = {
            file.beforeWrite = null
            saving2.complete(Unit)
            release2.await()
        }
        routes["/meta/oidc/device/authorization/"] = { MockResponse().setResponseCode(503) }
        codexServer(pending = false)
        withTimeout(5_000) { saving2.await() }
        val other = async(Dispatchers.IO) { providers.startLogin(SubscriptionAuth.META) }
        delay(300)
        assertFalse(other.isCompleted)
        release2.complete(Unit)
        assertTrue(withTimeout(5_000) { other.await() })
        assertTrue(state.connection(SubscriptionAuth.CODEX)!!.hasKey)
    }

    @Test
    fun `a renewed token the phone couldn't save is used, and saved on the next look-up`() = runBlocking {
        codexServer(expiresIn = 60)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val ref = ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")
        routes["/openai/oauth/token"] = { json(JSONObject().put("error", "invalid_grant"), 400) }
        assertEquals(ChatTarget.Unavailable(ChatError.SignInAgain), providers.chatTarget(ref))
        assertTrue(SubscriptionAuth.CODEX in providers.signedOut.value)
        routes["/openai/oauth/token"] = { renewed("second") }
        file.beforeWrite = { throw java.io.IOException("disk full") }
        // rt-1 is used up by this refresh: its answer is the sign-in now,
        // and the row no longer says signed out, though it's not saved yet.
        assertEquals(jwt("acct-1", "second"), (providers.chatTarget(ref) as ChatTarget.Ready).key)
        assertFalse(SubscriptionAuth.CODEX in providers.signedOut.value)
        assertEquals("rt-1", SubscriptionCredential.decode(state.keys[SubscriptionAuth.CODEX])!!.refresh)
        val refreshes = requests("/openai/oauth/token").size
        // Still not saved: the held one is used, never the spent rt-1 again.
        assertEquals(jwt("acct-1", "second"), (providers.chatTarget(ref) as ChatTarget.Ready).key)
        assertEquals(refreshes, requests("/openai/oauth/token").size)
        file.beforeWrite = null
        assertEquals(jwt("acct-1", "second"), (providers.chatTarget(ref) as ChatTarget.Ready).key)
        assertEquals("rt-2", SubscriptionCredential.decode(state.keys[SubscriptionAuth.CODEX])!!.refresh)
        assertEquals(refreshes, requests("/openai/oauth/token").size)
    }

    @Test
    fun `a held renewal never outlives signing out`() = runBlocking {
        codexServer(expiresIn = 60)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val ref = ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")
        routes["/openai/oauth/token"] = { renewed("second") }
        file.beforeWrite = { throw java.io.IOException("disk full") }
        assertEquals(jwt("acct-1", "second"), (providers.chatTarget(ref) as ChatTarget.Ready).key)
        file.beforeWrite = null
        assertTrue(providers.remove(SubscriptionAuth.CODEX))
        assertTrue(providers.chatTarget(ref) is ChatTarget.Unavailable)
        assertTrue(state.keys.isEmpty())
        // A new sign-in is its own, not the held renewal of the old one.
        codexServer(expiresIn = 3600)
        signIn(SubscriptionAuth.CODEX)
        assertEquals(jwt("acct-1"), (providers.chatTarget(ref) as ChatTarget.Ready).key)
        assertEquals("rt-1", SubscriptionCredential.decode(state.keys[SubscriptionAuth.CODEX])!!.refresh)
    }

    @Test
    fun `meta minting no key any more signs it out`() = runBlocking {
        routes["/meta/oidc/device/authorization/"] = {
            json(
                JSONObject().put("device_code", "dc").put("user_code", "WXYZ-9876").put("interval", 1)
                    .put("verification_uri", "https://auth.meta.com/device"),
            )
        }
        routes["/meta/oidc/device/token/"] = { json(JSONObject().put("access_token", "identity-1")) }
        routes["/mint"] = { json(JSONObject().put("api_key", "mk-1")) }
        assertTrue(providers.setEnabled(true))
        assertEquals(LoginState.Done(SubscriptionAuth.META), signIn(SubscriptionAuth.META))
        routes["/mint"] = { json(JSONObject()) }
        now += 2 * 24 * 3_600_000L
        val ref = ModelRef(SubscriptionAuth.META, "muse-spark-1.3")
        assertEquals(ChatTarget.Unavailable(ChatError.SignInAgain), providers.chatTarget(ref))
        assertTrue(SubscriptionAuth.META in providers.signedOut.value)
    }

    @Test
    fun `a token renewed while AI goes off is kept, so the sign-in still works once it's back on`() = runBlocking {
        codexServer(expiresIn = 60)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val release = CountDownLatch(1)
        routes["/openai/oauth/token"] = {
            release.await(5, TimeUnit.SECONDS)
            renewed("second")
        }
        val ref = ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")
        val pending = async(Dispatchers.IO) { providers.chatTarget(ref) }
        withTimeout(5_000) { while (requests("/openai/oauth/token").size < 2) delay(20) }
        assertTrue(providers.setEnabled(false))
        release.countDown()
        assertEquals(ChatTarget.Unavailable(ChatError.AiOff), pending.await())
        // ChatGPT used up rt-1 in that refresh: rt-2 is what's left to renew with.
        assertEquals("rt-2", SubscriptionCredential.decode(state.keys[SubscriptionAuth.CODEX])!!.refresh)
        assertTrue(providers.setEnabled(true))
        val target = providers.chatTarget(ref) as ChatTarget.Ready
        assertEquals(jwt("acct-1", "second"), target.key)
        assertEquals(2, requests("/openai/oauth/token").size)
    }

    @Test
    fun `a caller stopped mid-renewal still leaves the new token saved`() = runBlocking {
        codexServer(expiresIn = 3600)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        withTimeout(5_000) { while (providers.models.first()[SubscriptionAuth.CODEX] == null) delay(20) }
        now += 3_600_000L
        val release = CountDownLatch(1)
        routes["/openai/oauth/token"] = {
            release.await(5, TimeUnit.SECONDS)
            renewed("second")
        }
        // Stop pressed while the chat's step renews its token.
        val step = async(Dispatchers.IO) { providers.chatTarget(ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")) }
        withTimeout(5_000) { while (requests("/openai/oauth/token").size < 2) delay(20) }
        step.cancel()
        release.countDown()
        withTimeout(5_000) {
            while (SubscriptionCredential.decode(state.keys[SubscriptionAuth.CODEX])!!.refresh != "rt-2") delay(20)
        }
    }

    @Test
    fun `a renewal that works again clears signed out`() = runBlocking {
        codexServer(expiresIn = 60)
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        val ref = ModelRef(SubscriptionAuth.CODEX, "gpt-6-astra")
        routes["/openai/oauth/token"] = { json(JSONObject().put("error", "invalid_grant"), 400) }
        assertEquals(ChatTarget.Unavailable(ChatError.SignInAgain), providers.chatTarget(ref))
        assertTrue(SubscriptionAuth.CODEX in providers.signedOut.value)
        routes["/openai/oauth/token"] = { renewed("second") }
        assertTrue(providers.chatTarget(ref) is ChatTarget.Ready)
        assertFalse(SubscriptionAuth.CODEX in providers.signedOut.value)
    }

    @Test
    fun `leaving the page after signing in doesn't stop the model list it fetches`() = runBlocking {
        codexServer()
        val release = CountDownLatch(1)
        val list = routes["/openai-codex/codex/models"]!!
        routes["/openai-codex/codex/models"] = {
            release.await(5, TimeUnit.SECONDS)
            list()
        }
        assertTrue(providers.setEnabled(true))
        assertEquals(LoginState.Done(SubscriptionAuth.CODEX), signIn(SubscriptionAuth.CODEX))
        withTimeout(5_000) { while (requests("/openai-codex/codex/models").isEmpty()) delay(20) }
        providers.dismissLogin(SubscriptionAuth.CODEX)
        assertNull(providers.login.value)
        release.countDown()
        withTimeout(5_000) { while (providers.models.first()[SubscriptionAuth.CODEX] == null) delay(20) }
        // Dismissing touches only a finished sign-in.
        codexServer(pending = true)
        assertTrue(providers.startLogin(SubscriptionAuth.CODEX))
        withTimeout(5_000) { providers.login.first { it is LoginState.Waiting } }
        providers.dismissLogin(SubscriptionAuth.CODEX)
        assertTrue(providers.login.value is LoginState.Waiting)
    }

    @Test
    fun `a codex test connection reads the streamed answer`() = runBlocking {
        codexServer()
        assertTrue(providers.setEnabled(true))
        signIn(SubscriptionAuth.CODEX)
        routes["/openai-codex/codex/responses"] = { reply("OK") }
        assertEquals(TestResult.Ok, providers.testConnection(SubscriptionAuth.CODEX, "gpt-6-astra"))
        val body = JSONObject(requests("/openai-codex/codex/responses").single().body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("max_output_tokens"))
        routes["/openai-codex/codex/responses"] = { json(JSONObject().put("output", JSONArray())) }
        assertEquals(TestResult.BadReply, providers.testConnection(SubscriptionAuth.CODEX, "gpt-6-astra"))
    }
}
