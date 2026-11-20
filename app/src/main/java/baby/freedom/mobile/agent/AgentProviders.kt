package baby.freedom.mobile.agent

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What Refresh models came to. */
internal enum class RefreshResult {
    Done, NoKey, SignInAgain, Unauthorized, NotFound, RateLimited, ServerError, Refused, Unreachable, TimedOut, TooLarge, Unusable,
    BadUrl, Cancelled, Failed,
}

/** What Test connection came to. Never anything the server wrote. */
internal enum class TestResult {
    Ok, NoKey, SignInAgain, Unauthorized, ModelNotFound, RateLimited, ServerError, Refused, Unreachable, TimedOut, BadReply, BadUrl,
    Cancelled,
}

/**
 * Settings → AI's model providers (#573): the one place their settings
 * change and their requests are made. Every change goes through [lock],
 * and so does writing a request's result, so a result can be checked
 * against the [RequestGate] and written in one step: a model list that
 * lands after AI was turned off, or after its key was removed or
 * replaced, is dropped rather than saved (desktop audit L1).
 *
 * Nothing contacts a provider unless AI is on and the user connected
 * that provider: the gate refuses a request otherwise, and only the
 * user's Refresh models and Test connection (and the model list fetched
 * right after a key or server is saved, or a subscription signed in to),
 * a message the user sends in a chat (#574, [streamStep]), the user's
 * own Sign in (#576, [startLogin]) and the renewal of a signed-in
 * subscription's token one of those needs ([credentialOf]) make one.
 */
internal class AgentProviders(
    private val store: AgentStore,
    private val gate: RequestGate = RequestGate(),
    private val client: ProviderClient = ProviderClient(gate),
    /** A hosted provider's API base; tests point it at a fake server. */
    private val hostedBase: (ProviderDefinition) -> String? = { it.baseUrl },
    /** The subscription sign-ins (#576); tests point it at a fake server. */
    private val auth: SubscriptionAuth = SubscriptionAuth(),
    /** Wall clock, for a credential's expiry. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Where a sign-in runs: outliving the page that started it, while the user is in the browser. */
    private val loginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Mutex()
    private var gateLoaded = false

    /** One credential renewal at a time, never under [lock] (it goes to the network). */
    private val refreshLock = Mutex()

    /** The sign-in under way or just ended, if any; one at a time. */
    private val _login = MutableStateFlow<LoginState?>(null)
    val login: StateFlow<LoginState?> = _login.asStateFlow()
    private val loginLock = Any()
    private var loginJob: Job? = null

    /** Bumped by every start and cancel: only the current sign-in may publish or save. */
    private var loginGeneration = 0L

    /**
     * Subscriptions whose provider refused to renew them since they were
     * signed in to: they need signing in again. In memory only: after a
     * restart the next request finds out again.
     */
    private val _signedOut = MutableStateFlow<Set<String>>(emptySet())
    val signedOut: StateFlow<Set<String>> = _signedOut.asStateFlow()

    val state: Flow<AgentState> = store.state
    val models: Flow<Map<String, List<AgentModel>>> = store.models

    /** The models [connection] offers: its discovered list, else the provider's bundled one. */
    fun modelsOf(connection: AgentConnection, discovered: Map<String, List<AgentModel>>): List<AgentModel> =
        discovered[connection.id] ?: ProviderCatalog.definition(connection.providerId)?.bundled.orEmpty()

    /** The gate follows the stored switch from the first call on (under [lock]). */
    private suspend fun loadGate(s: AgentState) {
        if (gateLoaded) return
        if (s.enabled) gate.enable(s.connections.associate { it.id to it.revision }) else gate.disable()
        gateLoaded = true
    }

    private suspend fun <T> locked(block: suspend (AgentState) -> T): T = withContext(NonCancellable) {
        // A change that has started finishes, even if the page that asked
        // for it closes: the gate and the file must agree.
        lock.withLock {
            val s = store.state.first()
            loadGate(s)
            block(s)
        }
    }

    /** Turns AI on or off. Off cancels every provider request in flight first. */
    suspend fun setEnabled(on: Boolean): Boolean = locked { s ->
        if (!on) {
            gate.disable()
            endLogin(null)
        }
        val ok = store.edit { it.copy(enabled = on) }
        if (on && ok) gate.enable(s.connections.associate { it.id to it.revision })
        ok
    }

    /**
     * Saves [key] for hosted provider [providerId], connecting it if it
     * wasn't. A new key is a new revision: requests made with the old one
     * are cancelled before it's replaced.
     */
    suspend fun saveApiKey(providerId: String, key: String): Boolean {
        val definition = ProviderCatalog.definition(providerId) ?: return false
        if (definition.kind != ProviderKind.ApiKey || !isUsableKey(key)) return false
        return locked { s ->
            if (!s.enabled) return@locked false
            val old = s.connection(providerId)
            val revision = s.nextRevision
            gate.bump(providerId, revision)
            val connection = old?.copy(revision = revision, hasKey = true) ?: AgentConnection(
                id = providerId,
                providerId = providerId,
                name = definition.name,
                favorites = listOfNotNull(definition.defaultModelId),
                revision = revision,
                hasKey = true,
            )
            val ok = store.edit { cur ->
                cur.copy(
                    connections = cur.connections.filter { it.id != providerId } + connection,
                    keys = cur.keys + (providerId to key.trim()),
                    nextRevision = revision + 1,
                    defaultModel = cur.defaultModel
                        ?: definition.defaultModelId?.let { ModelRef(providerId, it) },
                )
            }
            if (!ok) gate.bump(providerId, old?.revision)
            ok
        }
    }

    /**
     * Saves a LAN server: Ollama (one, `ollama`) or an OpenAI-compatible
     * one ([connectionId] null adds a new one). [url] must have passed
     * [ProviderUrls]; [key] is optional (`null` keeps the saved one for
     * the same URL and drops it for a new one, `""` removes it). A new URL
     * also drops the old server's models, favourites and the default model
     * if it was one of them. Returns the connection's id, or null if not saved.
     */
    suspend fun saveLan(
        providerId: String,
        connectionId: String?,
        name: String,
        url: HttpUrl,
        key: String?,
    ): String? {
        val definition = ProviderCatalog.definition(providerId) ?: return null
        if (definition.kind != ProviderKind.Lan || ProviderUrls.check(url).url == null) return null
        if (!key.isNullOrEmpty() && !isUsableKey(key)) return null
        val label = ProviderCatalog.cleanText(name.trim(), max = 80) ?: definition.name
        val id = connectionId ?: if (providerId == ProviderCatalog.OLLAMA) providerId else "custom-${UUID.randomUUID()}"
        return locked { s ->
            if (!s.enabled) return@locked null
            val old = s.connection(id)
            if (old != null && old.providerId != providerId) return@locked null
            if (old == null && connectionId != null && providerId != ProviderCatalog.OLLAMA) return@locked null
            val revision = s.nextRevision
            gate.bump(id, revision)
            val urlText = ProviderUrls.display(url)
            // A different address is a different server (desktop: a new
            // endpoint is a new connection): its saved key, favourites and
            // default model were the old server's, and none of them follow
            // it there. A new address without a key typed again goes without one.
            val moved = old != null && old.baseUrl != urlText
            val hasKey = when {
                key == null -> !moved && old?.hasKey == true
                else -> key.isNotEmpty()
            }
            val connection = AgentConnection(
                id = id,
                providerId = providerId,
                name = label,
                baseUrl = urlText,
                favorites = if (moved) emptyList() else old?.favorites.orEmpty(),
                revision = revision,
                hasKey = hasKey,
            )
            val ok = store.edit { cur ->
                val keys = when {
                    key == null && moved -> cur.keys - id
                    key == null -> cur.keys
                    key.isEmpty() -> cur.keys - id
                    else -> cur.keys + (id to key.trim())
                }
                cur.copy(
                    connections = cur.connections.filter { it.id != id } + connection,
                    keys = keys,
                    nextRevision = revision + 1,
                    defaultModel = cur.defaultModel?.takeIf { !moved || it.connectionId != id },
                )
            }
            // Another server's models aren't this one's.
            if (ok && old?.baseUrl != urlText) store.setModels(id, null)
            if (!ok) gate.bump(id, old?.revision)
            if (ok) id else null
        }
    }

    /** Disconnects [connectionId]: its key, models, favourites and default go, and its requests are cancelled. */
    suspend fun remove(connectionId: String): Boolean = locked { s ->
        // Signing out also ends a sign-in to the same provider under way.
        endLogin(connectionId)
        val old = s.connection(connectionId) ?: return@locked true
        gate.bump(connectionId, null)
        val ok = store.edit { cur ->
            cur.copy(
                connections = cur.connections.filter { it.id != connectionId },
                keys = cur.keys - connectionId,
                defaultModel = cur.defaultModel?.takeIf { it.connectionId != connectionId },
            )
        }
        if (ok) {
            store.setModels(connectionId, null)
            _signedOut.update { it - connectionId }
        } else {
            gate.bump(connectionId, old.revision)
        }
        ok
    }

    suspend fun setFavorite(connectionId: String, modelId: String, favorite: Boolean): Boolean = locked {
        store.edit { cur ->
            val c = cur.connection(connectionId) ?: return@edit null
            val favorites = if (favorite) (c.favorites + modelId).distinct() else c.favorites - modelId
            cur.copy(connections = cur.connections.map { if (it.id == connectionId) c.copy(favorites = favorites) else it })
        }
    }

    suspend fun setDefault(connectionId: String, modelId: String): Boolean = locked {
        store.edit { cur ->
            if (cur.connection(connectionId) == null) return@edit null
            cur.copy(defaultModel = ModelRef(connectionId, modelId))
        }
    }

    /** The URL [connection]'s requests start from, re-checked against [ProviderUrls] for a LAN server. */
    private fun baseOf(connection: AgentConnection, definition: ProviderDefinition): HttpUrl? =
        if (definition.kind == ProviderKind.Lan) {
            connection.baseUrl?.toHttpUrlOrNull()?.let { ProviderUrls.check(it).url }
        } else {
            hostedBase(definition)?.toHttpUrlOrNull()
        }

    /** Fetches [connectionId]'s model list and stores it, if AI is still on and its key unchanged by then. */
    suspend fun refreshModels(connectionId: String): RefreshResult = withContext(Dispatchers.IO) {
        val s = locked { it }
        if (!s.enabled) return@withContext RefreshResult.Cancelled
        val connection = s.connection(connectionId) ?: return@withContext RefreshResult.Cancelled
        val definition = ProviderCatalog.definition(connection.providerId) ?: return@withContext RefreshResult.Failed
        val base = baseOf(connection, definition) ?: return@withContext RefreshResult.BadUrl
        val url = ProviderClient.catalogUrl(definition, base) ?: return@withContext RefreshResult.Done
        val key = s.keys[connectionId]
        // OpenRouter's and NEAR AI's lists are public: their key isn't sent for them.
        val sendKey = definition.catalogKey || (definition.kind == ProviderKind.Lan && definition.id != ProviderCatalog.OLLAMA)
        // A list that needs the key, or a server whose saved key can't be
        // read, isn't asked for without it.
        if ((definition.catalogKey && key == null) || (sendKey && s.keyLost(connection))) {
            return@withContext RefreshResult.NoKey
        }
        val (maxBytes, _) = ProviderCatalog.bounds(definition)
        val called = withAuth(s, connection, definition, { it is ProviderReply.Status && it.code == 401 }) { k, account ->
            client.fetch(
                connectionId, connection.revision, url,
                ProviderClient.authHeaders(definition, if (sendKey) k else null, account),
                maxBytes, timeoutMs = ProviderClient.CATALOG_TIMEOUT_MS,
            )
        }
        val reply = when (called) {
            is Called.Done -> called.value
            is Called.Failed -> return@withContext when (called.error) {
                ChatError.SignInAgain -> RefreshResult.SignInAgain
                ChatError.NoKey, ChatError.SignInLost -> RefreshResult.NoKey
                ChatError.Unreachable -> RefreshResult.Unreachable
                ChatError.TimedOut -> RefreshResult.TimedOut
                ChatError.RateLimited -> RefreshResult.RateLimited
                ChatError.ServerError -> RefreshResult.ServerError
                ChatError.AiOff, ChatError.KeyChanged -> RefreshResult.Cancelled
                else -> RefreshResult.Failed
            }
        }
        when (reply) {
            is ProviderReply.Body -> {
                val models = try {
                    ProviderCatalog.parseModels(definition, reply.text)
                } catch (e: ProviderCatalog.CatalogException) {
                    return@withContext if (e.reason == ProviderCatalog.CatalogException.Reason.TooMany) {
                        RefreshResult.TooLarge
                    } else {
                        RefreshResult.Unusable
                    }
                }
                locked {
                    if (!gate.allows(connectionId, connection.revision)) return@locked RefreshResult.Cancelled
                    if (store.setModels(connectionId, models)) RefreshResult.Done else RefreshResult.Failed
                }
            }
            // The server answered: never "couldn't reach" it.
            is ProviderReply.Status -> when (reply.code) {
                401, 403 -> RefreshResult.Unauthorized
                404 -> RefreshResult.NotFound
                429 -> RefreshResult.RateLimited
                in 500..599 -> RefreshResult.ServerError
                else -> RefreshResult.Refused
            }
            ProviderReply.TooLarge -> RefreshResult.TooLarge
            ProviderReply.Unreachable -> RefreshResult.Unreachable
            ProviderReply.TimedOut -> RefreshResult.TimedOut
            ProviderReply.Cancelled -> RefreshResult.Cancelled
        }
    }

    /** Sends [connectionId]'s [modelId] one short prompt and reports whether a reply came back. */
    suspend fun testConnection(connectionId: String, modelId: String): TestResult = withContext(Dispatchers.IO) {
        val s = locked { it }
        if (!s.enabled) return@withContext TestResult.Cancelled
        val connection = s.connection(connectionId) ?: return@withContext TestResult.Cancelled
        val definition = ProviderCatalog.definition(connection.providerId) ?: return@withContext TestResult.Cancelled
        val base = baseOf(connection, definition) ?: return@withContext TestResult.BadUrl
        // A server whose saved key can't be read isn't tried without it.
        if (s.keyLost(connection)) return@withContext TestResult.NoKey
        val (url, body) = ProviderClient.testRequest(definition, base, modelId)
        val called = withAuth(s, connection, definition, { it is ProviderReply.Status && it.code == 401 }) { key, account ->
            client.fetch(
                connectionId, connection.revision, url,
                ProviderClient.authHeaders(definition, key, account), ProviderCatalog.TEST_MAX_BYTES, json = body,
                timeoutMs = if (definition.kind == ProviderKind.Lan) {
                    ProviderClient.LAN_TEST_TIMEOUT_MS
                } else {
                    ProviderClient.CALL_TIMEOUT_MS
                },
            )
        }
        val reply = when (called) {
            is Called.Done -> called.value
            is Called.Failed -> return@withContext when (called.error) {
                ChatError.SignInAgain -> TestResult.SignInAgain
                ChatError.Unreachable -> TestResult.Unreachable
                ChatError.TimedOut -> TestResult.TimedOut
                ChatError.RateLimited -> TestResult.RateLimited
                ChatError.ServerError -> TestResult.ServerError
                ChatError.AiOff, ChatError.KeyChanged -> TestResult.Cancelled
                ChatError.BadReply -> TestResult.BadReply
                else -> TestResult.NoKey
            }
        }
        when (reply) {
            is ProviderReply.Body ->
                if (ProviderClient.isReply(definition, reply.text)) TestResult.Ok else TestResult.BadReply
            is ProviderReply.Status -> when (reply.code) {
                401, 403 -> TestResult.Unauthorized
                404 -> TestResult.ModelNotFound
                429 -> TestResult.RateLimited
                in 300..399 -> TestResult.Refused
                in 500..599 -> TestResult.ServerError
                else -> TestResult.Refused
            }
            ProviderReply.TooLarge -> TestResult.BadReply
            ProviderReply.Unreachable -> TestResult.Unreachable
            ProviderReply.TimedOut -> TestResult.TimedOut
            ProviderReply.Cancelled -> TestResult.Cancelled
        }
    }

    /**
     * What a chat needs to reach [ref]'s model (#574), pinned to its
     * connection's revision at that moment: a key rotated, a server moved
     * or AI turned off since then cancels the chat's requests rather than
     * sending them with what replaced it (desktop audit L1).
     */
    suspend fun chatTarget(ref: ModelRef): ChatTarget = withContext(Dispatchers.IO) {
        val s = locked { it }
        if (!s.enabled) return@withContext ChatTarget.Unavailable(ChatError.AiOff)
        val connection = s.connection(ref.connectionId) ?: return@withContext ChatTarget.Unavailable(ChatError.NoModel)
        val definition = ProviderCatalog.definition(connection.providerId)
            ?: return@withContext ChatTarget.Unavailable(ChatError.NoModel)
        val base = baseOf(connection, definition) ?: return@withContext ChatTarget.Unavailable(ChatError.BadUrl)
        if (s.keyLost(connection)) {
            val lost = if (definition.kind == ProviderKind.Subscription) ChatError.SignInLost else ChatError.NoKey
            return@withContext ChatTarget.Unavailable(lost)
        }
        when (val a = authOf(s, connection, definition)) {
            is Auth.Ready -> ChatTarget.Ready(connection, definition, base, a.key, ref.modelId, a.accountId)
            is Auth.Unavailable -> ChatTarget.Unavailable(a.error)
        }
    }

    /** Streams one step of a chat to [target]; [onText] gets each piece of reply text. */
    suspend fun streamStep(target: ChatTarget.Ready, request: StepRequest, onText: (String) -> Unit): StepOutcome =
        withContext(Dispatchers.IO) {
            // A subscription's token is looked up again for every step: a
            // long turn may outlast it. One renewed since is the same
            // sign-in (same revision); one from signing in again isn't,
            // and the gate stops the step.
            val (url, body) = AgentWire.build(target.definition, target.base, request)
            var parser = AgentWire.parser(target.definition.wire)
            suspend fun send(key: String?, accountId: String?): StreamEnd {
                // A refusal comes before any event: a second try starts clean.
                parser = AgentWire.parser(target.definition.wire)
                return client.stream(
                    target.connection.id, target.connection.revision, url,
                    ProviderClient.authHeaders(target.definition, key, accountId), body,
                ) { event, data ->
                    val delta = parser.onEvent(event, data)
                    if (delta.isNotEmpty()) onText(delta)
                }
            }
            val end = if (target.definition.kind == ProviderKind.Subscription) {
                val unauthorized = { e: StreamEnd -> e is StreamEnd.Status && e.code == 401 }
                when (val c = withAuth(store.state.first(), target.connection, target.definition, unauthorized, ::send)) {
                    is Called.Done -> c.value
                    is Called.Failed -> return@withContext StepOutcome.Failed(c.error)
                }
            } else {
                send(target.key, target.accountId)
            }
            when (end) {
                StreamEnd.Ended -> try {
                    StepOutcome.Ok(parser.finish())
                } catch (e: WireException) {
                    StepOutcome.Failed(chatErrorOf(e.error))
                }
                is StreamEnd.Wire -> StepOutcome.Failed(chatErrorOf(end.error))
                // The server answered: never "couldn't reach" it.
                is StreamEnd.Status -> StepOutcome.Failed(
                    when (end.code) {
                        // A subscription has no key: the account itself was refused.
                        401, 403 -> if (target.definition.kind == ProviderKind.Subscription) {
                            ChatError.AccountRefused
                        } else {
                            ChatError.Unauthorized
                        }
                        404 -> ChatError.ModelNotFound
                        413 -> ChatError.ContextTooLong
                        429, 529 -> ChatError.RateLimited
                        in 500..599 -> ChatError.ServerError
                        else -> ChatError.Refused
                    },
                    // A refusal that says so; on a server on the network
                    // (Ollama and the like, whose wording varies) any 400.
                    noTools = end.noTools || (end.code == 400 && target.definition.kind == ProviderKind.Lan),
                )
                StreamEnd.Unreachable -> StepOutcome.Failed(ChatError.Unreachable)
                StreamEnd.TimedOut -> StepOutcome.Failed(ChatError.TimedOut)
                // The gate stopped it: AI went off, or the connection changed under it.
                StreamEnd.Cancelled -> StepOutcome.Failed(
                    if (store.state.first().enabled) ChatError.KeyChanged else ChatError.AiOff,
                )
            }
        }

    // --- Subscriptions (#576) ---

    /** What a request to a connection is made with: its key, and ChatGPT's account. */
    private sealed interface Auth {
        data class Ready(val key: String?, val accountId: String?) : Auth {
            override fun toString() = "Ready"
        }

        data class Unavailable(val error: ChatError) : Auth
    }

    /**
     * [connection]'s key as [s] has it; for a subscription, its current
     * access token, renewed first if it's due ([credentialOf]).
     */
    private suspend fun authOf(
        s: AgentState,
        connection: AgentConnection,
        definition: ProviderDefinition,
        refused: String? = null,
    ): Auth {
        if (definition.kind != ProviderKind.Subscription) return Auth.Ready(s.keys[connection.id], null)
        return when (val c = credentialOf(connection.id, refused)) {
            is Credential.Ready -> Auth.Ready(c.credential.access, c.credential.accountId)
            is Credential.Unavailable -> Auth.Unavailable(c.error)
        }
    }

    private sealed interface Credential {
        data class Ready(val credential: SubscriptionCredential) : Credential
        data class Unavailable(val error: ChatError) : Credential
    }

    /**
     * Makes [call] with [connection]'s key; for a subscription, if the
     * provider answers 401 ([unauthorized]), renews that token once (it
     * may have ended early: revoked, or a clock that was wrong) and makes
     * the call again with the new one. Pi renews only on expiry; this
     * also covers a token that died before it. A renewal the provider
     * refuses is [ChatError.SignInAgain].
     */
    private suspend fun <T> withAuth(
        s: AgentState,
        connection: AgentConnection,
        definition: ProviderDefinition,
        unauthorized: (T) -> Boolean,
        call: suspend (key: String?, accountId: String?) -> T,
    ): Called<T> {
        val first = when (val a = authOf(s, connection, definition)) {
            is Auth.Ready -> a
            is Auth.Unavailable -> return Called.Failed(a.error)
        }
        val reply = call(first.key, first.accountId)
        if (definition.kind != ProviderKind.Subscription || !unauthorized(reply) || first.key == null) {
            return Called.Done(reply)
        }
        return when (val again = authOf(store.state.first(), connection, definition, refused = first.key)) {
            is Auth.Ready -> if (again.key == first.key || again.key == null) {
                markSignedOut(connection.id, first.key)
                Called.Failed(ChatError.SignInAgain)
            } else {
                val second = call(again.key, again.accountId)
                if (unauthorized(second)) {
                    // The renewed token is refused too: this sign-in is over.
                    markSignedOut(connection.id, again.key)
                    Called.Failed(ChatError.SignInAgain)
                } else {
                    Called.Done(second)
                }
            }
            is Auth.Unavailable -> Called.Failed(again.error)
        }
    }

    /**
     * Marks [connectionId] signed out ([signedOut]) if [access] is still
     * the token in effect for it, not one renewed or signed in to since.
     */
    private suspend fun markSignedOut(connectionId: String, access: String) = refreshLock.withLock {
        locked { cur -> if (inEffect(cur, connectionId)?.access == access) _signedOut.update { it + connectionId } }
    }

    /**
     * A renewed credential the phone couldn't save ([AgentStore.edit]
     * failed): the connection's [revision] and the stored blob it
     * [replaces]. It's the one used, and saved again on the next look-up,
     * as long as the connection is still that sign-in: the refresh token
     * the stored one holds was used up by the renewal. In memory only.
     * Guarded by [refreshLock].
     */
    private class Unsaved(val revision: Long, val replaces: String?, val credential: SubscriptionCredential)

    private val unsaved = HashMap<String, Unsaved>()

    /** [connectionId]'s credential in [s]: one renewed but not saved yet, else the stored one. Under [refreshLock]. */
    private fun inEffect(s: AgentState, connectionId: String): SubscriptionCredential? {
        val saved = s.keys[connectionId]
        val held = unsaved[connectionId]
        if (held != null && held.revision == s.connection(connectionId)?.revision && held.replaces == saved) {
            return held.credential
        }
        return SubscriptionCredential.decode(saved)
    }

    /**
     * Saves [fresh] as [connection]'s credential in place of [replaces],
     * if it's still that sign-in (under [lock], with [refreshLock] held).
     * `null`: it isn't any more, and [fresh] is dropped. `false`: the
     * write failed, and [fresh] is held ([unsaved]) to use and save later.
     */
    private suspend fun keepLocked(
        cur: AgentState,
        connection: AgentConnection,
        replaces: String?,
        fresh: SubscriptionCredential,
    ): Boolean? {
        val id = connection.id
        fun AgentState.unchanged() = connection(id)?.revision == connection.revision && keys[id] == replaces
        if (!cur.unchanged()) {
            unsaved.remove(id)
            return null
        }
        var wrote = false
        val ok = store.edit { now ->
            if (!now.unchanged()) return@edit null
            wrote = true
            now.copy(keys = now.keys + (id to fresh.encode()))
        }
        // The new token works, whether or not it's on flash yet: every
        // outcome but "not this sign-in any more" (ok, nothing written).
        if (!ok || wrote) _signedOut.update { it - id }
        return when {
            ok && wrote -> true.also { unsaved.remove(id) }
            ok -> null.also { unsaved.remove(id) }
            else -> false.also { unsaved[id] = Unsaved(connection.revision, replaces, fresh) }
        }
    }

    private sealed interface Called<out T> {
        data class Done<T>(val value: T) : Called<T>
        data class Failed(val error: ChatError) : Called<Nothing>
    }

    /**
     * [connectionId]'s credential, renewed if it's due, or if [refused]
     * is its access token (the provider just answered 401 to it). One renewal at a
     * time ([refreshLock]): the state is read again once it's held, so a
     * second caller finds the first one's new token instead of renewing
     * (ChatGPT's refresh tokens are used up by a refresh). A renewed
     * credential is kept if the connection is still the sign-in it was
     * renewed for (not signed out, nor signed in to again meanwhile), even
     * if AI went off meanwhile (the caller then gets [ChatError.AiOff]);
     * it doesn't change the connection's revision, so a chat streaming
     * with the old token isn't cancelled.
     */
    private suspend fun credentialOf(connectionId: String, refused: String? = null): Credential = refreshLock.withLock {
        // A renewal the phone couldn't save: saved now, if it can be.
        unsaved[connectionId]?.let { held ->
            locked { cur ->
                val c = cur.connection(connectionId)
                if (c == null || c.revision != held.revision) unsaved.remove(connectionId) else keepLocked(cur, c, held.replaces, held.credential)
            }
        }
        val s = locked { it }
        if (!s.enabled) return@withLock Credential.Unavailable(ChatError.AiOff)
        val connection = s.connection(connectionId) ?: return@withLock Credential.Unavailable(ChatError.KeyChanged)
        val saved = s.keys[connectionId]
        val credential = inEffect(s, connectionId) ?: return@withLock Credential.Unavailable(ChatError.SignInLost)
        // Renewed meanwhile by another caller: that one's token is the answer.
        val wasRefused = refused != null && refused == credential.access
        if (!wasRefused && !credential.due(wallClock())) return@withLock Credential.Ready(credential)
        // Finished and saved even if the caller is cancelled (Stop, or AI
        // off cancelling a model fetch's request): a ChatGPT refresh token is used
        // up once the server has it, so an answer dropped halfway would end
        // the sign-in. Each auth request is bounded (30 s). One block, so
        // the answer can't be dropped between the request and its save.
        withContext(NonCancellable) { renewed(connection, saved, credential, wasRefused) }
    }

    private suspend fun renewed(
        connection: AgentConnection,
        saved: String?,
        credential: SubscriptionCredential,
        wasRefused: Boolean,
    ): Credential {
        val connectionId = connection.id

        /** Still the same sign-in, with the credential this renewal started from. */
        fun AgentState.unchanged() = connection(connectionId)?.revision == connection.revision && keys[connectionId] == saved
        return when (val r = auth.refresh(connection.providerId, credential)) {
            is RefreshOutcome.Fresh -> locked { cur ->
                // Kept even if AI went off meanwhile: a ChatGPT refresh token
                // is used up by the refresh, so dropping the new one would
                // leave nothing to renew with once AI is back on. A save
                // that fails holds it in memory instead ([unsaved]).
                keepLocked(cur, connection, saved, r.credential)
                    ?: return@locked Credential.Unavailable(ChatError.KeyChanged)
                if (!gate.allows(connectionId, connection.revision)) {
                    Credential.Unavailable(if (cur.enabled) ChatError.KeyChanged else ChatError.AiOff)
                } else {
                    Credential.Ready(r.credential)
                }
            }
            RefreshOutcome.SignInAgain -> locked { cur ->
                // Only for the sign-in that was refused, not one made meanwhile.
                if (!cur.unchanged()) return@locked Credential.Unavailable(ChatError.KeyChanged)
                _signedOut.update { it + connectionId }
                Credential.Unavailable(ChatError.SignInAgain)
            }
            // Not signed out: a token that hasn't run out yet still works
            // (unless the provider has just refused it).
            is RefreshOutcome.Failed -> if (!wasRefused && wallClock() < credential.expiresAt) {
                Credential.Ready(credential)
            } else if (r.failure == LoginFailure.NoAccess) {
                // Meta minted no key: as over as a refused renewal.
                locked { cur ->
                    if (!cur.unchanged()) return@locked Credential.Unavailable(ChatError.KeyChanged)
                    _signedOut.update { it + connectionId }
                    Credential.Unavailable(ChatError.SignInAgain)
                }
            } else {
                Credential.Unavailable(
                    when (r.failure) {
                        LoginFailure.Unreachable -> ChatError.Unreachable
                        LoginFailure.TimedOut -> ChatError.TimedOut
                        LoginFailure.RateLimited -> ChatError.RateLimited
                        LoginFailure.ServerError -> ChatError.ServerError
                        else -> ChatError.BadReply
                    },
                )
            }
        }
    }

    /**
     * Starts signing in to subscription [providerId] by device code,
     * ending any other sign-in under way. Its progress is [login]; it
     * runs on its own, so the user can go to the browser and back. Only
     * while AI is on. Under [lock], like every other end of a sign-in
     * ([cancelLogin]), so it can't land between a save's check and its write.
     */
    suspend fun startLogin(providerId: String): Boolean {
        val definition = ProviderCatalog.definition(providerId) ?: return false
        if (definition.kind != ProviderKind.Subscription) return false
        locked {
            synchronized(loginLock) {
                loginJob?.cancel()
                val generation = ++loginGeneration
                _login.value = LoginState.Starting(providerId)
                loginJob = loginScope.launch { runLogin(definition, generation) }
            }
        }
        return true
    }

    /**
     * Forgets [providerId]'s finished or failed sign-in, so the page
     * doesn't show it again; one under way, and the model list fetched
     * after one finished, carry on.
     */
    fun dismissLogin(providerId: String) {
        synchronized(loginLock) {
            val last = _login.value
            if (last?.providerId == providerId && (last is LoginState.Done || last is LoginState.Failed)) _login.value = null
        }
    }

    /**
     * Ends the sign-in under way (or the result of one that just ended),
     * or only [providerId]'s, if given. Its requests are cancelled and
     * whatever it might still get is never saved.
     *
     * Under [lock], as the save is ([saveSubscription]): a Cancel tapped
     * while a finished sign-in is being saved waits for that save, and so
     * lands either before it (nothing is saved) or after it (the sign-in
     * is saved, [LoginState.Done] was shown, and this only clears that).
     */
    suspend fun cancelLogin(providerId: String? = null) = locked { endLogin(providerId) }

    /** [cancelLogin], for a caller that already holds [lock]. */
    private fun endLogin(providerId: String?) {
        synchronized(loginLock) {
            if (providerId != null && _login.value?.providerId != providerId) return
            loginJob?.cancel()
            loginJob = null
            loginGeneration++
            _login.value = null
        }
    }

    private fun publish(generation: Long, state: LoginState?) {
        synchronized(loginLock) { if (generation == loginGeneration) _login.value = state }
    }

    private fun isCurrent(generation: Long) = synchronized(loginLock) { generation == loginGeneration }

    private suspend fun runLogin(definition: ProviderDefinition, generation: Long) {
        val providerId = definition.id
        try {
            if (!locked { it.enabled }) return publish(generation, null)
            val code = when (val started = auth.start(providerId)) {
                is SubscriptionAuth.Started.Failed -> return publish(generation, LoginState.Failed(providerId, started.failure))
                is SubscriptionAuth.Started.Code -> started.code
            }
            publish(generation, LoginState.Waiting(providerId, code))
            val credential = when (val finished = auth.await(code)) {
                is SubscriptionAuth.Finished.Failed ->
                    return publish(generation, LoginState.Failed(providerId, finished.failure))
                is SubscriptionAuth.Finished.Done -> finished.credential
            }
            // Done is shown by the save itself, under the same lock.
            if (!saveSubscription(definition, credential, generation)) {
                return publish(generation, if (isCurrent(generation)) LoginState.Failed(providerId, LoginFailure.SaveFailed) else null)
            }
            // The account's own model list, where the provider has one: on
            // its own job, so a sign-in started or cancelled next (which
            // cancels [loginJob]) doesn't stop it. Signing out or in again
            // does, through the revision it's fetched for.
            if (definition.catalogPath != null) {
                loginScope.launch {
                    try {
                        refreshModels(providerId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Refresh models on the provider's page tries again.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publish(generation, LoginState.Failed(providerId, LoginFailure.BadReply))
        }
    }

    /**
     * Saves a finished sign-in as [definition]'s connection: a new
     * revision, so requests made with an earlier sign-in's token are
     * cancelled. Only if AI is still on and the sign-in wasn't cancelled
     * (by the user, signing out, AI off, another sign-in): all of those
     * happen under [lock] too, so none can slip between the check and the
     * write. [LoginState.Done] is published under it as well, so whatever
     * runs next sees the save and its Done together.
     */
    private suspend fun saveSubscription(
        definition: ProviderDefinition,
        credential: SubscriptionCredential,
        generation: Long,
    ): Boolean = locked { s ->
        if (!s.enabled || !isCurrent(generation)) return@locked false
        val id = definition.id
        val old = s.connection(id)
        val revision = s.nextRevision
        gate.bump(id, revision)
        val connection = old?.copy(revision = revision, hasKey = true) ?: AgentConnection(
            id = id,
            providerId = id,
            name = definition.name,
            favorites = listOfNotNull(definition.defaultModelId),
            revision = revision,
            hasKey = true,
        )
        val ok = store.edit { cur ->
            cur.copy(
                connections = cur.connections.filter { it.id != id } + connection,
                keys = cur.keys + (id to credential.encode()),
                nextRevision = revision + 1,
                defaultModel = cur.defaultModel ?: definition.defaultModelId?.let { ModelRef(id, it) },
            )
        }
        if (ok) {
            // A renewal held from the earlier sign-in ([unsaved]) no longer matches its revision.
            _signedOut.update { it - id }
            publish(generation, LoginState.Done(id))
        } else {
            gate.bump(id, old?.revision)
        }
        ok
    }

    companion object {
        /** Desktop's bound on a key's length. */
        const val MAX_KEY_LENGTH = 16_384

        /** A key is sent as a header value: no spaces, line breaks or other non-printing characters. */
        fun isUsableKey(key: String): Boolean {
            val k = key.trim()
            return k.isNotEmpty() && k.length <= MAX_KEY_LENGTH && k.all { it in '!'..'~' }
        }

        @Volatile
        private var instance: AgentProviders? = null

        fun get(context: Context): AgentProviders = instance ?: synchronized(this) {
            instance ?: AgentProviders(AgentStore.create(context)).also { instance = it }
        }
    }
}

/** Whether a chat can reach a model now (#574), and with what. */
internal sealed interface ChatTarget {
    data class Ready(
        val connection: AgentConnection,
        val definition: ProviderDefinition,
        val base: HttpUrl,
        val key: String?,
        val modelId: String,
        /** ChatGPT's account (#576), sent with its requests. */
        val accountId: String? = null,
    ) : ChatTarget {
        // Never print a key.
        override fun toString() = "Ready(${connection.id}, $modelId)"
    }

    data class Unavailable(val error: ChatError) : ChatTarget
}

internal fun chatErrorOf(error: WireError): ChatError = when (error) {
    WireError.RateLimited -> ChatError.RateLimited
    WireError.ContextTooLong -> ChatError.ContextTooLong
    WireError.ServerError -> ChatError.ServerError
    WireError.Refused -> ChatError.Refused
    WireError.BadReply -> ChatError.BadReply
}
