package baby.freedom.mobile.agent

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import baby.freedom.mobile.data.AesGcmCipher
import baby.freedom.mobile.data.KeystoreAesKey
import baby.freedom.mobile.data.SecretCipher
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * A provider the user connected (#573). Hosted providers have one
 * connection each, keyed by the provider's id; Ollama has one
 * (`ollama`); each OpenAI-compatible server its own (`custom-<uuid>`).
 * [baseUrl] is a LAN server's URL. [revision] changes whenever the key
 * or URL does, and is never reused (see [RequestGate]).
 */
internal data class AgentConnection(
    val id: String,
    val providerId: String,
    val name: String,
    val baseUrl: String? = null,
    val favorites: List<String> = emptyList(),
    val revision: Long,
    /**
     * Whether a key was saved for it. Kept outside the encrypted blob so a
     * key the Keystore can no longer open still reads as lost for a LAN
     * server, whose key is optional, rather than as a server without one.
     */
    val hasKey: Boolean = false,
)

/** The model chats will use unless told otherwise. */
internal data class ModelRef(val connectionId: String, val modelId: String)

/**
 * Settings → AI as stored. [keys] maps a connection to its API key;
 * [keysUnreadable] is set when a saved key blob can't be decrypted (the
 * Keystore key is gone), so its connections ask for the key again rather
 * than show as connected.
 */
internal data class AgentState(
    val enabled: Boolean = false,
    val connections: List<AgentConnection> = emptyList(),
    val defaultModel: ModelRef? = null,
    val keys: Map<String, String> = emptyMap(),
    val keysUnreadable: Boolean = false,
    val nextRevision: Long = 1,
) {
    fun connection(id: String): AgentConnection? = connections.firstOrNull { it.id == id }

    /**
     * A key was saved for [c] (a hosted provider always has one) but can't
     * be read now: its connection asks for it again, and makes no request
     * without it rather than going out keyless.
     */
    fun keyLost(c: AgentConnection): Boolean {
        if (c.id in keys) return false
        return c.hasKey || ProviderCatalog.definition(c.providerId)?.kind == ProviderKind.ApiKey
    }
}

/**
 * Where Settings → AI keeps its state (#573): two DataStore files, both
 * excluded from cloud backup and device transfer
 * (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`).
 *
 *  - [FILE_NAME]: the AI switch, the connections, the default model, and
 *    the API keys, which are AES-GCM encrypted under their own Android
 *    Keystore key ([KEY_ALIAS], [KeystoreAesKey]) and Base64-encoded as
 *    one blob, like the RPC keys (`RpcKeyStore`). The Keystore key never
 *    leaves the phone, so a copied file could never be read anyway.
 *  - [MODELS_FILE_NAME]: each connection's discovered model list, apart
 *    so a list of up to 2000 models isn't rewritten on every favourite.
 *
 * Nothing here logs a key; a failure is logged by exception class only.
 * A file that can't be read reads as AI off with nothing connected, so a
 * read error can never start a request.
 */
internal class AgentStore(
    private val store: DataStore<Preferences>,
    private val modelStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    val state: Flow<AgentState> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading AI settings failed (${e.javaClass.simpleName})")
            emit(emptyPreferences())
        }
        .map(::decode)

    /** Each connection's discovered models, by connection id. */
    val models: Flow<Map<String, List<AgentModel>>> = modelStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading AI models failed (${e.javaClass.simpleName})")
            emit(emptyPreferences())
        }
        .map { prefs ->
            prefs.asMap().mapNotNull { (k, v) ->
                if (!k.name.startsWith(MODELS_PREFIX) || v !is String) return@mapNotNull null
                k.name.removePrefix(MODELS_PREFIX) to decodeModels(v)
            }.toMap()
        }

    /**
     * Replaces the state with [change] applied to it, in one write.
     * `false` when the write failed (nothing changed); a `null` from
     * [change] leaves it as it is and counts as done.
     */
    suspend fun edit(change: (AgentState) -> AgentState?): Boolean = guarded("settings") {
        store.edit { prefs ->
            val next = change(decode(prefs)) ?: return@edit
            encode(next, prefs)
        }
    }

    /** Stores [models] for [connectionId], or forgets its list (`null`). */
    suspend fun setModels(connectionId: String, models: List<AgentModel>?): Boolean = guarded("models") {
        modelStore.edit { prefs ->
            val key = stringPreferencesKey(MODELS_PREFIX + connectionId)
            if (models == null) prefs.remove(key) else prefs[key] = encodeModels(models)
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "writing AI $what failed (${e.javaClass.simpleName})")
        false
    }

    private fun decode(prefs: Preferences): AgentState {
        val connections = runCatching { decodeConnections(prefs[CONNECTIONS]) }.getOrDefault(emptyList())
        val (keys, unreadable) = decodeKeys(prefs[KEYS])
        val default = prefs[DEFAULT]?.split('\n')?.takeIf { it.size == 2 }?.let { ModelRef(it[0], it[1]) }
        return AgentState(
            enabled = prefs[ENABLED] ?: false,
            connections = connections,
            defaultModel = default?.takeIf { d -> connections.any { it.id == d.connectionId } },
            keys = keys.filterKeys { id -> connections.any { it.id == id } },
            keysUnreadable = unreadable,
            nextRevision = prefs[NEXT_REVISION] ?: 1,
        )
    }

    private fun encode(state: AgentState, prefs: MutablePreferences) {
        prefs[ENABLED] = state.enabled
        prefs[NEXT_REVISION] = state.nextRevision
        prefs[CONNECTIONS] = encodeConnections(state.connections)
        val default = state.defaultModel
        if (default == null) prefs.remove(DEFAULT) else prefs[DEFAULT] = default.connectionId + "\n" + default.modelId
        val keys = state.keys.filterKeys { id -> state.connections.any { it.id == id } }
        if (keys.isEmpty()) {
            // An unreadable blob stays until a key is saved over it, so
            // toggling a favourite doesn't count as giving up on it.
            if (!state.keysUnreadable) prefs.remove(KEYS)
        } else {
            val plain = JSONObject(keys).toString().toByteArray(Charsets.UTF_8)
            prefs[KEYS] = Base64.getEncoder().encodeToString(cipher.encrypt(plain))
        }
    }

    /** The last blob opened and what was in it: every request reads the keys. */
    @Volatile
    private var opened: Pair<String, Map<String, String>>? = null

    private fun decodeKeys(blob: String?): Pair<Map<String, String>, Boolean> {
        if (blob.isNullOrEmpty()) return emptyMap<String, String>() to false
        opened?.let { (b, keys) -> if (b == blob) return keys to false }
        return try {
            val plain = String(cipher.decrypt(Base64.getDecoder().decode(blob)), Charsets.UTF_8)
            val json = JSONObject(plain)
            val keys = json.keys().asSequence().associateWith { json.getString(it) }
            opened = blob to keys
            keys to false
        } catch (e: Exception) {
            Log.w(TAG, "AI provider keys can't be decrypted (${e.javaClass.simpleName})")
            emptyMap<String, String>() to true
        }
    }

    companion object {
        private const val TAG = "AgentStore"
        private val ENABLED = booleanPreferencesKey("enabled")
        private val CONNECTIONS = stringPreferencesKey("connections_v1")
        private val DEFAULT = stringPreferencesKey("default_model_v1")
        private val KEYS = stringPreferencesKey("keys_v1")
        private val NEXT_REVISION = longPreferencesKey("next_revision")
        private const val MODELS_PREFIX = "models:"

        /** The DataStore files' names, excluded from backup in `res/xml`. */
        const val FILE_NAME = "freedom_agent"
        const val MODELS_FILE_NAME = "freedom_agent_models"

        /** Distinct from the RPC keys' and the wallet's aliases. */
        const val KEY_ALIAS = "freedom_agent_api_keys"

        internal fun encodeConnections(list: List<AgentConnection>): String = JSONArray().apply {
            for (c in list) put(
                JSONObject()
                    .put("id", c.id)
                    .put("provider", c.providerId)
                    .put("name", c.name)
                    .put("revision", c.revision)
                    .put("favorites", JSONArray(c.favorites))
                    .put("hasKey", c.hasKey)
                    .apply { c.baseUrl?.let { put("baseUrl", it) } },
            )
        }.toString()

        internal fun decodeConnections(json: String?): List<AgentConnection> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val provider = o.optString("provider")
                if (ProviderCatalog.definition(provider) == null) return@mapNotNull null
                val favorites = o.optJSONArray("favorites")
                AgentConnection(
                    id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                    providerId = provider,
                    name = o.optString("name"),
                    baseUrl = o.optString("baseUrl").takeIf { it.isNotEmpty() },
                    favorites = if (favorites == null) emptyList() else (0 until favorites.length()).map { favorites.getString(it) },
                    revision = o.optLong("revision"),
                    hasKey = o.optBoolean("hasKey"),
                )
            }.distinctBy { it.id }
        }

        internal fun encodeModels(models: List<AgentModel>): String = JSONArray().apply {
            for (m in models) put(JSONObject().put("id", m.id).apply { if (m.name != m.id) put("name", m.name) })
        }.toString()

        internal fun decodeModels(json: String): List<AgentModel> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = ProviderCatalog.cleanText(o.opt("id")) ?: return@mapNotNull null
                AgentModel(id, ProviderCatalog.cleanText(o.opt("name")) ?: id)
            }
        } catch (e: Exception) {
            emptyList()
        }

        private val Context.agentSettings by preferencesDataStore(
            name = FILE_NAME,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )
        private val Context.agentModels by preferencesDataStore(
            name = MODELS_FILE_NAME,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        fun create(context: Context): AgentStore {
            val app = context.applicationContext
            return AgentStore(app.agentSettings, app.agentModels, AesGcmCipher(KeystoreAesKey(KEY_ALIAS)::get))
        }
    }
}
