package baby.freedom.mobile.agent

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What Refresh models came to. */
internal enum class RefreshResult { Done, NoKey, Unauthorized, Unreachable, TooLarge, Unusable, BadUrl, Cancelled, Failed }

/** What Test connection came to. Never anything the server wrote. */
internal enum class TestResult {
    Ok, NoKey, Unauthorized, ModelNotFound, RateLimited, ServerError, Refused, Unreachable, BadReply, BadUrl, Cancelled,
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
 * right after a key or server is saved) make one.
 */
internal class AgentProviders(
    private val store: AgentStore,
    private val gate: RequestGate = RequestGate(),
    private val client: ProviderClient = ProviderClient(gate),
    /** A hosted provider's API base; tests point it at a fake server. */
    private val hostedBase: (ProviderDefinition) -> String? = { it.baseUrl },
) {
    private val lock = Mutex()
    private var gateLoaded = false

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
        if (!on) gate.disable()
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
        val old = s.connection(connectionId) ?: return@locked true
        gate.bump(connectionId, null)
        val ok = store.edit { cur ->
            cur.copy(
                connections = cur.connections.filter { it.id != connectionId },
                keys = cur.keys - connectionId,
                defaultModel = cur.defaultModel?.takeIf { it.connectionId != connectionId },
            )
        }
        if (ok) store.setModels(connectionId, null) else gate.bump(connectionId, old.revision)
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
        val reply = client.fetch(
            connectionId, connection.revision, url,
            ProviderClient.authHeaders(definition, if (sendKey) key else null),
            maxBytes, timeoutMs = ProviderClient.CATALOG_TIMEOUT_MS,
        )
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
            is ProviderReply.Status ->
                if (reply.code == 401 || reply.code == 403) RefreshResult.Unauthorized else RefreshResult.Unreachable
            ProviderReply.TooLarge -> RefreshResult.TooLarge
            ProviderReply.Unreachable -> RefreshResult.Unreachable
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
        val key = s.keys[connectionId]
        // A server whose saved key can't be read isn't tried without it.
        if (s.keyLost(connection)) return@withContext TestResult.NoKey
        val (url, body) = ProviderClient.testRequest(definition, base, modelId)
        val reply = client.fetch(
            connectionId, connection.revision, url,
            ProviderClient.authHeaders(definition, key), ProviderCatalog.TEST_MAX_BYTES, json = body,
        )
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
            ProviderReply.Cancelled -> TestResult.Cancelled
        }
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
