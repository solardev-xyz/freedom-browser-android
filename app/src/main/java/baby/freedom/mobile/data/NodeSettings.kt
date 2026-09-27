package baby.freedom.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import baby.freedom.mobile.browser.SearchEngines
import baby.freedom.mobile.ens.EnsRpcConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent toggles the user controls from the node details panel and
 * the settings screen (search engine, and the hidden "Other" section).
 *
 * Backed by a single [DataStore] under `freedom_node_settings` living
 * in the app's files directory. Flows surface the current value; the
 * corresponding suspend setter writes-through to disk.
 *
 * ## IPFS keys
 *
 * `show_ipfs_ui` gates visibility of every IPFS-related control in the
 * UI. The Settings screen "Other" section reveals a single row the
 * user can tap to flip this on before a demo.
 *
 * `ipfs_low_power` and `ipfs_routing_mode` are read at `:node` process
 * startup and re-applied on the next restart — there is no live
 * reconfig path on the freedom-ipfs wrapper.
 *
 * ## Search keys
 *
 * `search_engine` is a built-in id from
 * [baby.freedom.mobile.browser.SearchEngines.BUILT_IN] or `custom`;
 * `search_custom_template` holds the custom template, stored only once
 * it has passed [baby.freedom.mobile.browser.SearchEngines.normalizeTemplate].
 * [searchTemplate] resolves the pair to the template the address bar
 * searches with.
 *
 * ## Name resolution keys (#102)
 *
 * `ens_rpc_custom_endpoints` (JSON array of URLs, each passed
 * [EnsRpcConfig.normalizeEndpoint]), `ens_rpc_disabled_public` (the
 * built-in public endpoints switched off), `ens_rpc_api_keys` (JSON
 * object, keyed-provider id → API key) and `ens_ccip_read`. Read
 * together as [ensRpcConfig]; the resolver reads that for every lookup,
 * so edits apply without a restart.
 *
 * There is no persistent "run IPFS" flag by design. The IPFS node is
 * always off at cold launch (demo-surprise requirement) and driven
 * live via AIDL: [baby.freedom.mobile.node.INodeService.ensureIpfsStarted]
 * from the Settings toggle or the first `ipfs://` / `ipns://`
 * navigation, and [baby.freedom.mobile.node.INodeService.stopIpfs]
 * from the toggle. The UI derives the toggle's on/off state from the
 * live [baby.freedom.swarm.IpfsInfo.status] so it survives process
 * restarts naturally.
 */
class NodeSettings private constructor(
    private val store: DataStore<Preferences>,
) {
    /**
     * Whether the embedded Swarm node should be running. Defaults to
     * `true` on first launch so the app works out of the box; flipped
     * by the user via the node details dialog.
     */
    val runNodeEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.RUN_NODE_ENABLED] ?: true
    }

    suspend fun setRunNodeEnabled(enabled: Boolean) {
        store.edit { it[Keys.RUN_NODE_ENABLED] = enabled }
    }

    /**
     * Whether any IPFS UI is rendered. Off by default — IPFS support
     * is a hidden capability surfaced only from Settings → Other. The
     * IPFS node still runs regardless of this flag.
     */
    val showIpfsUi: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.SHOW_IPFS_UI] ?: false
    }

    suspend fun setShowIpfsUi(enabled: Boolean) {
        store.edit { it[Keys.SHOW_IPFS_UI] = enabled }
    }

    /**
     * Legacy Kubo "lowpower" toggle, retained for settings compatibility;
     * freedom-ipfs ignores it — its defaults are already mobile-budgeted.
     * Default `true`.
     */
    val ipfsLowPower: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.IPFS_LOW_POWER] ?: true
    }

    suspend fun setIpfsLowPower(enabled: Boolean) {
        store.edit { it[Keys.IPFS_LOW_POWER] = enabled }
    }

    /**
     * freedom-ipfs content-routing strategy. One of `auto`,
     * `delegated`, `light_dht`, `offline`. Default `auto` — delegated
     * routing for lookups with a client-only light-DHT fallback, the
     * cheapest mode that still resolves arbitrary CIDs on mobile.
     */
    val ipfsRoutingMode: Flow<String> = store.data.map { prefs ->
        prefs[Keys.IPFS_ROUTING_MODE] ?: DEFAULT_IPFS_ROUTING_MODE
    }

    suspend fun setIpfsRoutingMode(mode: String) {
        store.edit { it[Keys.IPFS_ROUTING_MODE] = mode }
    }

    /** Selected search engine id. Default DuckDuckGo, as on desktop. */
    val searchEngine: Flow<String> = store.data.map { prefs ->
        prefs[Keys.SEARCH_ENGINE] ?: SearchEngines.DEFAULT_ID
    }

    /** The saved custom template, or `""` if none has been saved. */
    val customSearchTemplate: Flow<String> = store.data.map { prefs ->
        prefs[Keys.SEARCH_CUSTOM_TEMPLATE] ?: ""
    }

    /**
     * The template address-bar searches use: the selected engine's, or
     * the default's for an unknown id / a `custom` selection without a
     * valid saved template.
     */
    val searchTemplate: Flow<String> = store.data.map { prefs ->
        SearchEngines.templateFor(
            prefs[Keys.SEARCH_ENGINE],
            prefs[Keys.SEARCH_CUSTOM_TEMPLATE],
        )
    }

    /** Select a built-in engine; the saved custom template is kept. */
    suspend fun setSearchEngine(id: String) {
        require(SearchEngines.BUILT_IN.any { it.id == id }) { "unknown engine $id" }
        store.edit { it[Keys.SEARCH_ENGINE] = id }
    }

    /**
     * Validate [template] and, if valid, save it and select `custom` in
     * one write. Returns `false` (and changes nothing) if it's invalid.
     */
    suspend fun setCustomSearchTemplate(template: String): Boolean {
        val normalized = SearchEngines.normalizeTemplate(template) ?: return false
        store.edit {
            it[Keys.SEARCH_CUSTOM_TEMPLATE] = normalized
            it[Keys.SEARCH_ENGINE] = SearchEngines.CUSTOM_ID
        }
        return true
    }

    /** Everything name resolution reads, as one value (see class kdoc). */
    val ensRpcConfig: Flow<EnsRpcConfig> = store.data.map(::readEnsRpc)

    private fun readEnsRpc(prefs: Preferences) = EnsRpcConfig(
        customEndpoints = EnsRpcConfig.decodeList(prefs[Keys.ENS_RPC_CUSTOM]),
        disabledPublicEndpoints = prefs[Keys.ENS_RPC_DISABLED_PUBLIC].orEmpty(),
        apiKeys = EnsRpcConfig.decodeKeys(prefs[Keys.ENS_RPC_API_KEYS]),
        ccipRead = prefs[Keys.ENS_CCIP_READ] ?: true,
    )

    private suspend fun editEnsRpc(change: (EnsRpcConfig) -> EnsRpcConfig) {
        store.edit { prefs ->
            val next = change(readEnsRpc(prefs))
            // Never write a configuration that leaves the resolver
            // nothing to ask; the page greys those controls out, this
            // is the backstop.
            if (next.endpoints.isEmpty()) return@edit
            prefs[Keys.ENS_RPC_CUSTOM] = EnsRpcConfig.encodeList(next.customEndpoints)
            prefs[Keys.ENS_RPC_DISABLED_PUBLIC] = next.disabledPublicEndpoints
            prefs[Keys.ENS_RPC_API_KEYS] = EnsRpcConfig.encodeKeys(next.apiKeys)
            prefs[Keys.ENS_CCIP_READ] = next.ccipRead
        }
    }

    /**
     * Add [url] to the user's own endpoints (after the ones already
     * there). `false`, changing nothing, if it isn't a valid endpoint,
     * is already listed, or the list is full.
     */
    suspend fun addEnsRpcEndpoint(url: String): Boolean {
        val normalized = EnsRpcConfig.normalizeEndpoint(url) ?: return false
        var added = false
        editEnsRpc { c ->
            if (normalized in c.customEndpoints ||
                c.customEndpoints.size >= EnsRpcConfig.MAX_CUSTOM_ENDPOINTS
            ) {
                c
            } else {
                added = true
                c.copy(customEndpoints = c.customEndpoints + normalized)
            }
        }
        return added
    }

    suspend fun removeEnsRpcEndpoint(url: String) =
        editEnsRpc { it.copy(customEndpoints = it.customEndpoints - url) }

    /** Move one of the user's endpoints up (-1) or down (+1) the order. */
    suspend fun moveEnsRpcEndpoint(url: String, by: Int) = editEnsRpc { c ->
        val list = c.customEndpoints.toMutableList()
        val from = list.indexOf(url)
        val to = from + by
        if (from < 0 || to !in list.indices) return@editEnsRpc c
        list.add(to, list.removeAt(from))
        c.copy(customEndpoints = list)
    }

    suspend fun setPublicEnsRpcEnabled(url: String, enabled: Boolean) = editEnsRpc { c ->
        c.copy(
            disabledPublicEndpoints = if (enabled) {
                c.disabledPublicEndpoints - url
            } else {
                c.disabledPublicEndpoints + url
            },
        )
    }

    /** Save (or, with a blank [key], remove) a keyed provider's API key. */
    suspend fun setRpcApiKey(providerId: String, key: String) = editEnsRpc { c ->
        val trimmed = key.trim()
        c.copy(apiKeys = if (trimmed.isEmpty()) c.apiKeys - providerId else c.apiKeys + (providerId to trimmed))
    }

    suspend fun setEnsCcipRead(enabled: Boolean) = editEnsRpc { it.copy(ccipRead = enabled) }

    private object Keys {
        val RUN_NODE_ENABLED = booleanPreferencesKey("run_node_enabled")
        val SHOW_IPFS_UI = booleanPreferencesKey("show_ipfs_ui")
        val IPFS_LOW_POWER = booleanPreferencesKey("ipfs_low_power")
        val IPFS_ROUTING_MODE = stringPreferencesKey("ipfs_routing_mode")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val SEARCH_CUSTOM_TEMPLATE = stringPreferencesKey("search_custom_template")
        val ENS_RPC_CUSTOM = stringPreferencesKey("ens_rpc_custom_endpoints")
        val ENS_RPC_DISABLED_PUBLIC = stringSetPreferencesKey("ens_rpc_disabled_public")
        val ENS_RPC_API_KEYS = stringPreferencesKey("ens_rpc_api_keys")
        val ENS_CCIP_READ = booleanPreferencesKey("ens_ccip_read")
    }

    companion object {
        const val DEFAULT_IPFS_ROUTING_MODE = "auto"

        /**
         * Valid freedom-ipfs routing strategies, in the order we want
         * them to appear in the settings picker. Legacy Kubo values
         * persisted by earlier builds ("autoclient", "dht", …) are
         * mapped to "auto" by the swarmnode IpfsNode wrapper.
         */
        val IPFS_ROUTING_MODES: List<String> = listOf(
            "auto",
            "delegated",
            "light_dht",
            "offline",
        )

        private val Context.nodeSettingsStore by preferencesDataStore(
            name = "freedom_node_settings",
        )

        @Volatile
        private var instance: NodeSettings? = null

        fun get(context: Context): NodeSettings =
            instance ?: synchronized(this) {
                instance ?: NodeSettings(
                    context.applicationContext.nodeSettingsStore,
                ).also { instance = it }
            }
    }
}
