package baby.freedom.mobile.agent

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
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
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import org.json.JSONArray
import org.json.JSONException
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
 * Settings → AI as stored. [keys] maps a connection to its API key, or
 * for a subscription (#576) to its [SubscriptionCredential]'s encoded
 * form (only one marked [AgentConnection.hasKey]); [keysUnreadable] is set when the
 * saved key blob can't be decrypted (the Keystore key is gone, or failed
 * this time), so its connections ask for the key again rather than show
 * as connected.
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
        return c.hasKey || ProviderCatalog.definition(c.providerId)?.kind.let {
            it == ProviderKind.ApiKey || it == ProviderKind.Subscription
        }
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
 *    It also keeps this installation's id ([deviceId]) for Sign in
 *    with ChatGPT (#602).
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
    /** The first wait before a key blob the Keystore couldn't open just now is tried again. */
    private val keyRetryMs: Long = KEY_RETRY_MS,
    /**
     * How long a key blob must have failed to open before a key saved
     * over it gives up on it (see [edit]).
     */
    private val keyGiveUpMs: Long = KEY_GIVE_UP_MS,
    /** A monotonic clock, in milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val state: Flow<AgentState> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading AI settings failed (${e.javaClass.simpleName})")
            emit(emptyPreferences())
        }
        // The file only emits when it's written, so a blob the Keystore
        // couldn't open for a moment is tried again here (backing off),
        // until it opens, turns out lost, or the file changes; otherwise
        // the page would ask for a key that reads fine again a second later.
        .transformLatest { prefs ->
            var wait = keyRetryMs
            while (true) {
                val (decoded, transient) = decodeRead(prefs)
                emit(decoded)
                if (transient == null) break
                delay(wait)
                wait = (wait * 2).coerceAtMost(KEY_RETRY_MAX_MS)
            }
        }
        .distinctUntilChanged()
        // Opening the key blob is a Keystore call (a binder round trip):
        // never on the thread that collects this for the UI.
        .flowOn(Dispatchers.IO)

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
     * [change] leaves it as it is and counts as done. [change] may be
     * called more than once.
     *
     * A key blob that can't be opened doesn't hold anything else up: a
     * change that saves no key (the AI switch, a removal, a favourite)
     * leaves the blob as it is. One that saves a key would write a new
     * blob over it, dropping every other connection's key, so over a blob
     * that failed only this time (a busy Keystore) it waits, trying the
     * blob again, until the blob opens (and the new key joins the others)
     * or has failed for [keyGiveUpMs]: then the blob is given up on, as
     * the page already does when it asks for those keys again.
     */
    suspend fun edit(change: (AgentState) -> AgentState?): Boolean = guarded("settings") {
        while (true) {
            var wait = 0L
            store.edit { prefs ->
                val (current, transient) = decodeRead(prefs)
                val next = change(current) ?: return@edit
                if (transient != null && storedKeys(next).isNotEmpty()) {
                    val since = failing?.takeIf { it.first == prefs[KEYS] }?.second ?: clock()
                    val left = keyGiveUpMs - (clock() - since)
                    if (left > 0) {
                        wait = left.coerceAtMost(keyRetryMs)
                        return@edit
                    }
                }
                encode(next, prefs, current.keys, overUnopened = current.keysUnreadable)
            }
            if (wait == 0L) break
            // Outside the write, so other changes aren't held up meanwhile.
            delay(wait)
        }
    }

    /**
     * This installation's id (a random UUID), which Sign in with ChatGPT
     * (#602) gives OpenAI as its `ext_agent_host_id`, as desktop does:
     * made on first use and kept. Sent nowhere else. Null if it couldn't
     * be read or saved.
     */
    suspend fun deviceId(): String? {
        var id: String? = null
        val ok = guarded("installation id") {
            store.edit { prefs ->
                val saved = prefs[DEVICE_ID]?.takeIf { UUID_TEXT.matches(it) }
                id = saved ?: java.util.UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it }
            }
        }
        return id.takeIf { ok }
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

    /**
     * [prefs] decoded, and the failure when the key blob couldn't be
     * opened only for now (it then reads as unreadable), else `null`.
     */
    private fun decodeRead(prefs: Preferences): Pair<AgentState, IOException?> {
        val connections = runCatching { decodeConnections(prefs[CONNECTIONS]) }.getOrDefault(emptyList())
        val read = decodeKeys(prefs[KEYS])
        val (keys, unreadable) = when (read) {
            is KeyRead.Opened -> read.keys to false
            KeyRead.Lost -> emptyMap<String, String>() to true
            // Read as unreadable for now (nothing is sent keyless); not cached,
            // so the next read tries again.
            is KeyRead.Unavailable -> emptyMap<String, String>() to true
        }
        val default = prefs[DEFAULT]?.split('\n')?.takeIf { it.size == 2 }?.let { ModelRef(it[0], it[1]) }
        return AgentState(
            enabled = prefs[ENABLED] ?: false,
            connections = connections,
            defaultModel = default?.takeIf { d -> connections.any { it.id == d.connectionId } },
            // A key goes with a connection only while it's marked as having
            // one: a key removed (or a server moved) while the blob couldn't
            // be opened stays in that blob, and mustn't come back with it.
            keys = keys.filterKeys { id -> connections.any { it.id == id && it.hasKey } },
            keysUnreadable = unreadable,
            nextRevision = prefs[NEXT_REVISION] ?: 1,
        ) to (read as? KeyRead.Unavailable)?.cause
    }

    /** The keys [state] would store: those of its connections marked as having one. */
    private fun storedKeys(state: AgentState): Map<String, String> =
        state.keys.filterKeys { id -> state.connections.any { it.id == id && it.hasKey } }

    /**
     * [previous]: the keys [prefs]' blob holds now, so an unchanged set
     * keeps its blob. [overUnopened]: that blob couldn't be opened, so a
     * new one replaces it for good.
     */
    private fun encode(
        state: AgentState,
        prefs: MutablePreferences,
        previous: Map<String, String>,
        overUnopened: Boolean,
    ) {
        prefs[ENABLED] = state.enabled
        prefs[NEXT_REVISION] = state.nextRevision
        prefs[CONNECTIONS] = encodeConnections(state.connections)
        val default = state.defaultModel
        if (default == null) prefs.remove(DEFAULT) else prefs[DEFAULT] = default.connectionId + "\n" + default.modelId
        val keys = storedKeys(state)
        when {
            // An unreadable blob stays until a key is saved over it, so
            // toggling a favourite doesn't count as giving up on it.
            keys.isEmpty() -> if (!state.keysUnreadable) prefs.remove(KEYS)
            // The same keys keep the same blob: a favourite or default tap
            // doesn't re-encrypt them (a fresh IV, so a new blob the next
            // read would have to decrypt again).
            keys == previous && prefs[KEYS] != null -> Unit
            else -> {
                val plain = JSONObject(keys).toString().toByteArray(Charsets.UTF_8)
                val sealed = try {
                    cipher.encrypt(plain)
                } catch (e: Exception) {
                    // Nothing is to be read under the old Keystore key any
                    // more, and a key the Keystore can't use (a damaged or
                    // invalidated one) fails every new blob too: replace it.
                    if (!overUnopened) throw e
                    Log.w(TAG, "AI key blob can't be written; replacing its Keystore key (${e.javaClass.simpleName})")
                    cipher.replaceKey()
                    cipher.encrypt(plain)
                }
                val blob = Base64.getEncoder().encodeToString(sealed)
                prefs[KEYS] = blob
                opened = blob to keys
                failing = null
            }
        }
    }

    /** What reading the key blob came to. */
    private sealed interface KeyRead {
        data class Opened(val keys: Map<String, String>) : KeyRead

        /** It can never be opened: the Keystore key is gone or replaced, or the blob is damaged. */
        data object Lost : KeyRead

        /** The Keystore failed this time; the blob may well open next time. */
        data class Unavailable(val cause: IOException) : KeyRead
    }

    /** The last blob opened and what was in it: every request reads the keys. */
    @Volatile
    private var opened: Pair<String, Map<String, String>>? = null

    /** A blob the Keystore couldn't open just now, and since when ([clock]) it has failed. */
    @Volatile
    private var failing: Pair<String, Long>? = null

    private fun decodeKeys(blob: String?): KeyRead {
        if (blob.isNullOrEmpty()) return KeyRead.Opened(emptyMap())
        opened?.let { (b, keys) -> if (b == blob) return KeyRead.Opened(keys) }
        return try {
            val plain = String(cipher.decrypt(Base64.getDecoder().decode(blob)), Charsets.UTF_8)
            val json = JSONObject(plain)
            val keys = json.keys().asSequence().associateWith { json.getString(it) }
            opened = blob to keys
            failing = null
            KeyRead.Opened(keys)
        } catch (e: Exception) {
            Log.w(TAG, "AI provider keys can't be decrypted (${e.javaClass.simpleName})")
            if (isPermanent(e)) {
                failing = null
                KeyRead.Lost
            } else {
                if (failing?.first != blob) failing = blob to clock()
                KeyRead.Unavailable(IOException("AI provider keys unavailable", e))
            }
        }
    }

    companion object {
        private const val TAG = "AgentStore"
        private val ENABLED = booleanPreferencesKey("enabled")
        private val CONNECTIONS = stringPreferencesKey("connections_v1")
        private val DEFAULT = stringPreferencesKey("default_model_v1")
        private val KEYS = stringPreferencesKey("keys_v1")
        private val NEXT_REVISION = longPreferencesKey("next_revision")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val UUID_TEXT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private const val MODELS_PREFIX = "models:"
        private const val KEY_RETRY_MS = 2_000L
        private const val KEY_RETRY_MAX_MS = 60_000L
        private const val KEY_GIVE_UP_MS = 10_000L

        /** The DataStore files' names, excluded from backup in `res/xml`. */
        const val FILE_NAME = "freedom_agent"
        const val MODELS_FILE_NAME = "freedom_agent_models"

        /** Distinct from the RPC keys' and the wallet's aliases. */
        const val KEY_ALIAS = "freedom_agent_api_keys"

        /**
         * A decrypt failure that will happen again every time: a wrong key
         * (a lost Keystore key is regenerated under the same alias, so its
         * old blob fails the GCM tag), a key the system invalidated, or a
         * blob that isn't Base64, IV and ciphertext, or JSON inside. Anything
         * else (a Keystore that's busy or restarting) may pass next time.
         */
        internal fun isPermanent(e: Throwable): Boolean = e is AEADBadTagException ||
            e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException ||
            e is IllegalArgumentException ||
            e is JSONException

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
                val definition = ProviderCatalog.definition(provider) ?: return@mapNotNull null
                val favorites = o.optJSONArray("favorites")
                AgentConnection(
                    id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                    providerId = provider,
                    // A hosted provider or subscription is named by the app,
                    // not the user: its current name, so one renamed since
                    // (#602: ChatGPT's device-code sign-in is now "ChatGPT
                    // (device code)") isn't shown under its old one.
                    name = if (definition.kind == ProviderKind.Lan) o.optString("name") else definition.name,
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
            val key = KeystoreAesKey(KEY_ALIAS)
            return AgentStore(app.agentSettings, app.agentModels, AesGcmCipher(replace = key::reset, key = key::get))
        }
    }
}
