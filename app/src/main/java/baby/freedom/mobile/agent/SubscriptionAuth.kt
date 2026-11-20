package baby.freedom.mobile.agent

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject

/*
 * Signing in with a subscription (#576): ChatGPT (desktop's
 * `openai-codex`) and Meta Muse (`meta-subscription`), both by device
 * code, the sign-in that works on a phone: the app shows a short code,
 * the user opens the provider's page and enters it there, and the app
 * polls until the provider says yes. The protocol is Pi's, which desktop
 * runs (`pi-ai/dist/auth/oauth/openai-codex.js`, `meta.js`,
 * `device-code.js`); the checks on what may be shown and opened are
 * desktop's (`ipc.js` `normalizeSubscriptionAuthEvent`), made stricter.
 *
 * Nothing a server writes is shown or logged: every failure is a
 * [LoginFailure] or [RefreshOutcome], never the server's words (Pi's
 * messages quote whole responses, tokens included).
 */

/**
 * What a sign-in leaves behind, stored like an API key (encrypted, in
 * [AgentStore]'s key blob). [access] goes in requests; [refresh] gets a
 * new one: ChatGPT's refresh token, or Meta's identity token, which mints
 * a new Model API key (a day's worth). [expiresAt] is wall-clock
 * milliseconds. [accountId] is ChatGPT's account, sent with its requests.
 */
internal data class SubscriptionCredential(
    val access: String,
    val refresh: String,
    val expiresAt: Long,
    val accountId: String? = null,
) {
    fun encode(): String = JSONObject()
        .put("access", access)
        .put("refresh", refresh)
        .put("expires", expiresAt)
        .apply { accountId?.let { put("accountId", it) } }
        .toString()

    /** Due for a new [access]: past [expiresAt], or within [margin] of it. */
    fun due(now: Long, margin: Long = SubscriptionAuth.REFRESH_MARGIN_MS): Boolean = now >= expiresAt - margin

    // Never print a token.
    override fun toString() = "SubscriptionCredential(expiresAt=$expiresAt)"

    companion object {
        /** The stored form back, or null if it isn't one (or holds anything that can't go in a header). */
        fun decode(text: String?): SubscriptionCredential? {
            val o = AgentWire.parseObject(text ?: return null) ?: return null
            val access = o.opt("access") as? String ?: return null
            val refresh = o.opt("refresh") as? String ?: return null
            val accountId = o.opt("accountId") as? String
            if (!SubscriptionAuth.headerSafe(access) || !SubscriptionAuth.headerSafe(refresh)) return null
            if (accountId != null && !SubscriptionAuth.headerSafe(accountId)) return null
            val expires = o.optLong("expires", 0)
            return SubscriptionCredential(access, refresh, expires, accountId)
        }
    }
}

/** What the user is shown while a sign-in waits: the code, and the page to enter it on. */
internal data class DeviceCode(
    val providerId: String,
    val userCode: String,
    /** Checked by [SubscriptionAuth.verificationUrl]: the only page the app opens. */
    val verificationUrl: String,
    /** How long the provider keeps the code, in milliseconds. */
    val expiresInMs: Long,
    internal val intervalMs: Long,
    /** ChatGPT's `device_auth_id`, Meta's `device_code`: what polling asks with. Never shown. */
    internal val handle: String,
) {
    override fun toString() = "DeviceCode($providerId)"
}

/** Why a sign-in didn't finish. */
internal enum class LoginFailure {
    /** The user said no on the provider's page. */
    Denied,

    /** The code ran out before the user entered it. */
    Expired,

    /** The provider doesn't offer device-code sign-in (ChatGPT's 404). */
    NotEnabled,

    /** Signed in, but the account has no API access (Meta minted no key). */
    NoAccess,
    RateLimited,
    ServerError,
    Unreachable,
    TimedOut,

    /** An answer that isn't what the protocol says, or a page that isn't the provider's own. */
    BadReply,

    /** Signed in, but the phone couldn't save it. */
    SaveFailed,
}

/** A sign-in's progress, as Settings → AI shows it ([AgentProviders.login]). */
internal sealed interface LoginState {
    val providerId: String

    /** Asking the provider for a code. */
    data class Starting(override val providerId: String) : LoginState

    /** The code is ready: waiting for the user to enter it on the provider's page. */
    data class Waiting(override val providerId: String, val code: DeviceCode) : LoginState

    data class Failed(override val providerId: String, val failure: LoginFailure) : LoginState

    /** Signed in and saved. */
    data class Done(override val providerId: String) : LoginState
}

/** What renewing a credential came to. */
internal sealed interface RefreshOutcome {
    data class Fresh(val credential: SubscriptionCredential) : RefreshOutcome

    /** The provider refused the refresh: only signing in again helps. */
    data object SignInAgain : RefreshOutcome

    /** It may work next time (no network, a 5xx, a timeout): not signed out. */
    data class Failed(val failure: LoginFailure) : RefreshOutcome
}

/** Where the sign-ins go; tests point them at a fake server. */
internal data class AuthEndpoints(
    val openAiAuth: String = "https://auth.openai.com",
    val metaAuth: String = "https://auth.meta.com",
    val metaMint: String = "https://api.meta.ai/muse-code/key",
)

/**
 * The two device-code sign-ins. [start] asks for a code, [await] polls
 * until the user has entered it and returns the credential, [refresh]
 * renews one. Cancelling the caller cancels the request in flight.
 */
internal class SubscriptionAuth(
    private val http: OkHttpClient = DEFAULT_HTTP,
    private val endpoints: AuthEndpoints = AuthEndpoints(),
    /** Wall clock, for [SubscriptionCredential.expiresAt]. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** A monotonic clock (milliseconds) for the polling deadline. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The longest a sign-in waits for the user, whatever the provider allows. */
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {
    sealed interface Started {
        data class Code(val code: DeviceCode) : Started
        data class Failed(val failure: LoginFailure) : Started
    }

    sealed interface Finished {
        data class Done(val credential: SubscriptionCredential) : Finished
        data class Failed(val failure: LoginFailure) : Finished
    }

    suspend fun start(providerId: String): Started = when (providerId) {
        CODEX -> startCodex()
        META -> startMeta()
        else -> Started.Failed(LoginFailure.BadReply)
    }

    /** Polls for [code] until the user has signed in, the code runs out, or [maxWaitMs] passes. */
    suspend fun await(code: DeviceCode): Finished {
        val waitFor = minOf(code.expiresInMs, maxWaitMs)
        val deadline = clock() + waitFor
        var interval = code.intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        // Meta asks for a wait before the first poll (Pi's `waitBeforeFirstPoll`).
        if (code.providerId == META) delay(minOf(interval, waitFor))
        while (clock() < deadline) {
            when (val p = if (code.providerId == CODEX) pollCodex(code) else pollMeta(code)) {
                is Poll.Complete -> return finish(code, p.value)
                is Poll.Failed -> return Finished.Failed(p.failure)
                Poll.Pending -> Unit
                is Poll.SlowDown -> interval = p.intervalMs?.coerceAtLeast(MIN_INTERVAL_MS)
                    ?: (interval + SLOW_DOWN_STEP_MS)
            }
            val left = deadline - clock()
            if (left <= 0) break
            delay(minOf(interval, left))
        }
        return Finished.Failed(LoginFailure.Expired)
    }

    suspend fun refresh(providerId: String, credential: SubscriptionCredential): RefreshOutcome = when (providerId) {
        CODEX -> {
            val reply = post(
                endpoints.openAiAuth + "/oauth/token",
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", credential.refresh)
                    .add("client_id", CODEX_CLIENT_ID)
                    .build(),
            )
            when {
                reply is Reply.Failed -> RefreshOutcome.Failed(reply.failure)
                // A refresh token that's been used, revoked or expired.
                reply is Reply.Answer && reply.code in 400..403 -> RefreshOutcome.SignInAgain
                else -> when (val t = codexTokens(reply)) {
                    is Finished.Done -> RefreshOutcome.Fresh(t.credential)
                    is Finished.Failed -> RefreshOutcome.Failed(t.failure)
                }
            }
        }
        // Meta's identity token can't be renewed itself; it mints the key.
        META -> when (val m = mintMeta(credential.refresh)) {
            is Mint.Key -> RefreshOutcome.Fresh(m.credential)
            Mint.SignInAgain -> RefreshOutcome.SignInAgain
            is Mint.Failed -> RefreshOutcome.Failed(m.failure)
        }
        else -> RefreshOutcome.SignInAgain
    }

    // --- ChatGPT (Pi's openai-codex device flow) ---

    private suspend fun startCodex(): Started {
        val reply = post(
            endpoints.openAiAuth + "/api/accounts/deviceauth/usercode",
            JSONObject().put("client_id", CODEX_CLIENT_ID).toString().toRequestBody(JSON),
        )
        val body = when (reply) {
            is Reply.Failed -> return Started.Failed(reply.failure)
            is Reply.Answer -> {
                if (reply.code == 404) return Started.Failed(LoginFailure.NotEnabled)
                if (reply.code !in 200..299) return Started.Failed(failureOf(reply.code))
                reply.json() ?: return Started.Failed(LoginFailure.BadReply)
            }
        }
        val id = body.opt("device_auth_id") as? String
        val userCode = body.opt("user_code") as? String
        val interval = when (val i = body.opt("interval")) {
            is Number -> i.toDouble()
            is String -> i.trim().toDoubleOrNull()
            else -> null
        }
        if (id.isNullOrEmpty() || !headerSafe(id) || interval == null || !interval.isFinite() || interval < 0) {
            return Started.Failed(LoginFailure.BadReply)
        }
        val url = verificationUrl(CODEX, CODEX_VERIFICATION_URL) ?: return Started.Failed(LoginFailure.BadReply)
        if (userCode == null || !isUserCode(userCode)) return Started.Failed(LoginFailure.BadReply)
        return Started.Code(
            DeviceCode(CODEX, userCode, url, CODEX_CODE_LIFETIME_MS, (interval * 1000).toLong(), id),
        )
    }

    private suspend fun pollCodex(code: DeviceCode): Poll {
        val reply = post(
            endpoints.openAiAuth + "/api/accounts/deviceauth/token",
            JSONObject().put("device_auth_id", code.handle).put("user_code", code.userCode).toString().toRequestBody(JSON),
        )
        if (reply is Reply.Failed) return pollFailure(reply.failure)
        reply as Reply.Answer
        if (reply.code in 200..299) {
            val body = reply.json() ?: return Poll.Failed(LoginFailure.BadReply)
            val authCode = body.opt("authorization_code") as? String
            val verifier = body.opt("code_verifier") as? String
            if (authCode.isNullOrEmpty() || verifier.isNullOrEmpty()) return Poll.Failed(LoginFailure.BadReply)
            return Poll.Complete(authCode + "\n" + verifier)
        }
        // Not entered yet.
        if (reply.code == 403 || reply.code == 404) return Poll.Pending
        val error = reply.json()?.let { j ->
            when (val e = j.opt("error")) {
                is JSONObject -> e.opt("code") as? String
                is String -> e
                else -> null
            }
        }
        return when (error) {
            "deviceauth_authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown(null)
            else -> Poll.Failed(failureOf(reply.code))
        }
    }

    private suspend fun finishCodex(value: String): Finished {
        val (authCode, verifier) = value.split('\n', limit = 2)
        val reply = post(
            endpoints.openAiAuth + "/oauth/token",
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CODEX_CLIENT_ID)
                .add("code", authCode)
                .add("code_verifier", verifier)
                .add("redirect_uri", endpoints.openAiAuth + "/deviceauth/callback")
                .build(),
        )
        return codexTokens(reply)
    }

    /** A token answer: access and refresh tokens, a lifetime, and the account inside the access token. */
    private fun codexTokens(reply: Reply): Finished {
        val body = when (reply) {
            is Reply.Failed -> return Finished.Failed(reply.failure)
            is Reply.Answer -> {
                if (reply.code !in 200..299) return Finished.Failed(failureOf(reply.code))
                reply.json() ?: return Finished.Failed(LoginFailure.BadReply)
            }
        }
        val access = body.opt("access_token") as? String
        val refresh = body.opt("refresh_token") as? String
        val expiresIn = (body.opt("expires_in") as? Number)?.toDouble()
        if (access == null || refresh == null || expiresIn == null || !expiresIn.isFinite() ||
            !headerSafe(access) || !headerSafe(refresh)
        ) {
            return Finished.Failed(LoginFailure.BadReply)
        }
        val accountId = codexAccountId(access) ?: return Finished.Failed(LoginFailure.BadReply)
        return Finished.Done(
            SubscriptionCredential(access, refresh, wallClock() + (expiresIn * 1000).toLong(), accountId),
        )
    }

    // --- Meta (RFC 8628 against auth.meta.com, then a Model API key) ---

    private suspend fun startMeta(): Started {
        val reply = post(
            endpoints.metaAuth + "/oidc/device/authorization/",
            FormBody.Builder().add("client_id", META_CLIENT_ID).build(),
        )
        val body = when (reply) {
            is Reply.Failed -> return Started.Failed(reply.failure)
            is Reply.Answer -> {
                if (reply.code !in 200..299) return Started.Failed(failureOf(reply.code))
                reply.json() ?: return Started.Failed(LoginFailure.BadReply)
            }
        }
        val deviceCode = body.opt("device_code") as? String
        val userCode = body.opt("user_code") as? String
        // Pi's choice: the page with the code filled in, else the plain one;
        // then desktop's check on it. A page that fails isn't swapped for
        // another: the sign-in stops.
        // A page with the code that's there at all (not missing or empty)
        // is the one checked, whatever its scheme.
        val offered = (body.opt("verification_uri_complete") as? String)?.takeIf { it.isNotEmpty() }
            ?: (body.opt("verification_uri") as? String)
        val url = offered?.let { verificationUrl(META, it) }
        if (deviceCode.isNullOrEmpty() || userCode == null || !isUserCode(userCode) || url == null) {
            return Started.Failed(LoginFailure.BadReply)
        }
        val interval = positive(body.opt("interval"))?.let { (it * 1000).toLong() } ?: DEFAULT_INTERVAL_MS
        val expires = positive(body.opt("expires_in"))?.let { (it * 1000).toLong() } ?: maxWaitMs
        return Started.Code(DeviceCode(META, userCode, url, expires, interval, deviceCode))
    }

    private suspend fun pollMeta(code: DeviceCode): Poll {
        val reply = post(
            endpoints.metaAuth + "/oidc/device/token/",
            FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", code.handle)
                .add("client_id", META_CLIENT_ID)
                .build(),
        )
        if (reply is Reply.Failed) return pollFailure(reply.failure)
        reply as Reply.Answer
        val body = reply.json()
        val token = body?.opt("access_token") as? String
        if (reply.code in 200..299 && !token.isNullOrEmpty()) {
            return if (headerSafe(token)) Poll.Complete(token) else Poll.Failed(LoginFailure.BadReply)
        }
        return when (body?.opt("error") as? String) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown(positive(body.opt("interval"))?.let { (it * 1000).toLong() })
            "access_denied" -> Poll.Failed(LoginFailure.Denied)
            "expired_token" -> Poll.Failed(LoginFailure.Expired)
            else -> Poll.Failed(if (reply.code in 200..299) LoginFailure.BadReply else failureOf(reply.code))
        }
    }

    private sealed interface Mint {
        data class Key(val credential: SubscriptionCredential) : Mint
        data object SignInAgain : Mint
        data class Failed(val failure: LoginFailure) : Mint
    }

    /** Exchanges Meta's identity token for a Model API key, good for about a day. */
    private suspend fun mintMeta(identity: String): Mint {
        val reply = post(
            endpoints.metaMint,
            "{}".toRequestBody(JSON),
            mapOf("Authorization" to "Bearer $identity", "x-api-version" to "1.0.0"),
        )
        val body = when (reply) {
            is Reply.Failed -> return Mint.Failed(reply.failure)
            is Reply.Answer -> {
                // The identity token can't be renewed: the session is over.
                if (reply.code == 401 || reply.code == 403) return Mint.SignInAgain
                if (reply.code !in 200..299) return Mint.Failed(failureOf(reply.code))
                reply.json() ?: return Mint.Failed(LoginFailure.BadReply)
            }
        }
        val key = body.opt("api_key") as? String
        if (key.isNullOrEmpty()) return Mint.Failed(LoginFailure.NoAccess)
        if (!headerSafe(key)) return Mint.Failed(LoginFailure.BadReply)
        return Mint.Key(SubscriptionCredential(key, identity, wallClock() + META_KEY_LIFETIME_MS))
    }

    private suspend fun finish(code: DeviceCode, value: String): Finished = when (code.providerId) {
        CODEX -> finishCodex(value)
        else -> when (val m = mintMeta(value)) {
            is Mint.Key -> Finished.Done(m.credential)
            // A token that was just issued and refused at once: as good as no access.
            Mint.SignInAgain -> Finished.Failed(LoginFailure.NoAccess)
            is Mint.Failed -> Finished.Failed(m.failure)
        }
    }

    // --- Polling and requests ---

    private sealed interface Poll {
        data class Complete(val value: String) : Poll
        data object Pending : Poll

        /** Poll less often: the provider's new interval, or 5 s more (RFC 8628 §3.5). */
        data class SlowDown(val intervalMs: Long?) : Poll
        data class Failed(val failure: LoginFailure) : Poll
    }

    /** A poll that didn't get through is tried again: a phone's network comes and goes. */
    private fun pollFailure(failure: LoginFailure): Poll =
        if (failure == LoginFailure.Unreachable || failure == LoginFailure.TimedOut) Poll.Pending else Poll.Failed(failure)

    private sealed interface Reply {
        /** The server answered: its status and as much of its body as [MAX_BODY_BYTES] allows. */
        data class Answer(val code: Int, val text: String?) : Reply {
            fun json(): JSONObject? = text?.let(AgentWire::parseObject)
            override fun toString() = "Answer($code)"
        }

        data class Failed(val failure: LoginFailure) : Reply
    }

    private suspend fun post(url: String, body: RequestBody, headers: Map<String, String> = emptyMap()): Reply {
        val target = url.toHttpUrlOrNull() ?: return Reply.Failed(LoginFailure.BadReply)
        val request = Request.Builder().url(target).post(body).apply {
            header("Accept", "application/json")
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resume(
                        Reply.Failed(if (e is InterruptedIOException) LoginFailure.TimedOut else LoginFailure.Unreachable),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    // On OkHttp's thread: nothing may escape.
                    val reply = try {
                        response.use { Reply.Answer(it.code, read(it)) }
                    } catch (e: InterruptedIOException) {
                        Reply.Failed(LoginFailure.TimedOut)
                    } catch (e: Throwable) {
                        Reply.Failed(LoginFailure.Unreachable)
                    }
                    cont.resume(reply)
                }
            })
        }
    }

    /** At most [MAX_BODY_BYTES] of the body; a longer one is no answer at all. */
    private fun read(response: Response): String? {
        val source = response.body?.source() ?: return null
        val buffer = Buffer()
        while (true) {
            if (source.read(buffer, 8L * 1024) == -1L) break
            if (buffer.size > MAX_BODY_BYTES) return null
        }
        return buffer.readUtf8()
    }

    private fun failureOf(code: Int): LoginFailure = when (code) {
        429 -> LoginFailure.RateLimited
        in 500..599 -> LoginFailure.ServerError
        else -> LoginFailure.BadReply
    }

    private fun positive(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }

    companion object {
        const val CODEX = "openai-codex"
        const val META = "meta-subscription"

        /** Pi's (the Codex CLI's) client id. */
        private const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

        /** The Muse Code CLI's client id. */
        private const val META_CLIENT_ID = "1031625952748946"

        /** Desktop's `OPENAI_DEVICE_VERIFICATION_URL`: the only page a ChatGPT sign-in opens. */
        const val CODEX_VERIFICATION_URL = "https://auth.openai.com/codex/device"

        /** Every Meta sign-in page starts with this. */
        const val META_PREFIX = "https://auth.meta.com/"

        /** Pi's: ChatGPT's code lasts 15 minutes. */
        const val CODEX_CODE_LIFETIME_MS = 15 * 60_000L

        /**
         * The longest a sign-in waits. Desktop gives up after 5 minutes,
         * with the app in front; here the user leaves it for the browser,
         * may have to sign in to the provider first (a password, a second
         * factor), and comes back. As long as ChatGPT's code lasts.
         */
        const val MAX_WAIT_MS = 15 * 60_000L

        /** Meta's minted keys live about a day. */
        const val META_KEY_LIFETIME_MS = 24 * 60 * 60_000L

        /** A credential is renewed this long before it runs out, so a step never starts with one about to. */
        const val REFRESH_MARGIN_MS = 5 * 60_000L

        // RFC 8628: 5 s without an interval, 5 s more on slow_down; Pi's 1 s floor.
        private const val DEFAULT_INTERVAL_MS = 5_000L
        private const val SLOW_DOWN_STEP_MS = 5_000L
        private const val MIN_INTERVAL_MS = 1_000L

        /** An auth answer is a few hundred bytes. */
        private const val MAX_BODY_BYTES = 64L * 1024

        private val JSON = "application/json".toMediaType()
        private val USER_CODE = Regex("^[A-Za-z0-9._-]{4,64}$")

        /** Each sign-in request (connect, send, answer) within 30 s, as Pi's Meta flow has it. */
        private val DEFAULT_HTTP: OkHttpClient by lazy {
            ProviderClient.httpClient().newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        }

        /** Desktop's user-code rule: what the page shows the user to type. */
        fun isUserCode(code: String): Boolean = USER_CODE.matches(code)

        /** A token can only go in a header made of visible ASCII. */
        fun headerSafe(value: String): Boolean =
            value.isNotEmpty() && value.length <= AgentProviders.MAX_KEY_LENGTH && value.all { it in '!'..'~' }

        /**
         * The page [providerId]'s sign-in may open, from what the server
         * gave ([raw]), or null if it isn't one of the provider's own.
         * ChatGPT's must be exactly [CODEX_VERIFICATION_URL] (desktop).
         * Meta's must be on `https://auth.meta.com` (desktop's origin
         * check), and more strictly: plain visible ASCII starting with
         * [META_PREFIX], so the host is spelled out before the first `/`,
         * and nothing a URL parser could read two ways (no `\`, `@`, `#`,
         * spaces or controls), so the host this check sees is the host the
         * browser opens.
         */
        fun verificationUrl(providerId: String, raw: String): String? = when (providerId) {
            CODEX -> raw.takeIf { it == CODEX_VERIFICATION_URL }
            META -> raw.takeIf { metaUrl(it) }
            else -> null
        }

        private fun metaUrl(raw: String): Boolean {
            if (raw.length > 2048 || !raw.startsWith(META_PREFIX)) return false
            if (raw.any { it !in '!'..'~' || it == '\\' || it == '@' || it == '#' }) return false
            val url = raw.toHttpUrlOrNull() ?: return false
            return url.scheme == "https" && url.host == "auth.meta.com" && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
        }

        /** The ChatGPT account inside an access token (a JWT's `https://api.openai.com/auth` claim). */
        fun codexAccountId(access: String): String? = try {
            val parts = access.split('.')
            if (parts.size != 3) {
                null
            } else {
                val payload = String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
                AgentWire.parseObject(payload)?.optJSONObject("https://api.openai.com/auth")
                    ?.opt("chatgpt_account_id")?.let { it as? String }
                    ?.takeIf { headerSafe(it) && it.length <= 200 }
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
