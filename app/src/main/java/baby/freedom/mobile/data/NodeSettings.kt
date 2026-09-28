package baby.freedom.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import baby.freedom.mobile.browser.AdblockCategory
import androidx.datastore.preferences.preferencesDataStore
import baby.freedom.mobile.browser.ExternalEndpoints
import baby.freedom.mobile.browser.SearchEngines
import baby.freedom.mobile.browser.normalizeAllowlistHost
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
 * ## External node keys
 *
 * `external_swarm_endpoint` / `external_ipfs_gateway` hold a normalized
 * base URL, absent for the embedded node (#125). `MainActivity`
 * mirrors them into [baby.freedom.mobile.browser.Gateways].
 *
 * ## Ad-blocking keys
 *
 * `adblock_<category>` switches each [AdblockCategory] (#126), absent
 * for its default; `adblock_allowlist` holds the sites ad blocking is
 * off for, in [normalizeAllowlistHost] form; `adblock_auto_update` (absent
 * for on) lets the app fetch signed filter-list updates over Swarm (#127).
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
     * Whether the embedded Myotis Ethereum / Gnosis light client runs
     * (#72). Off by default — opt-in, as on desktop; switched on the node
     * page. `MainActivity` binds [baby.freedom.mobile.node.MyotisService]
     * while it's on.
     */
    val myotisEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.MYOTIS_ENABLED] ?: false
    }

    suspend fun setMyotisEnabled(enabled: Boolean) {
        store.edit { it[Keys.MYOTIS_ENABLED] = enabled }
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

    /**
     * External Swarm endpoint (#125): the base URL of a bee/ant HTTP API
     * that serves `bzz://` in place of the embedded node, or `""` for
     * the embedded node. Stored normalized
     * ([baby.freedom.mobile.browser.ExternalEndpoints.normalize]).
     */
    val externalSwarmEndpoint: Flow<String> = store.data.map { prefs ->
        prefs[Keys.EXTERNAL_SWARM_ENDPOINT] ?: ""
    }

    /**
     * External IPFS path gateway (#125) serving `ipfs://` / `ipns://`
     * in place of the embedded freedom-ipfs reader, or `""` for the
     * embedded one. Unverified — see
     * [baby.freedom.mobile.browser.ExternalEndpoints].
     */
    val externalIpfsGateway: Flow<String> = store.data.map { prefs ->
        prefs[Keys.EXTERNAL_IPFS_GATEWAY] ?: ""
    }

    /**
     * Save [endpoint] (validated and normalized) as the external Swarm
     * endpoint, or go back to the embedded node with `""`. Returns
     * `false` (and changes nothing) if it's invalid.
     */
    suspend fun setExternalSwarmEndpoint(endpoint: String): Boolean =
        setEndpoint(Keys.EXTERNAL_SWARM_ENDPOINT, endpoint)

    /** [setExternalSwarmEndpoint] for the IPFS gateway. */
    suspend fun setExternalIpfsGateway(endpoint: String): Boolean =
        setEndpoint(Keys.EXTERNAL_IPFS_GATEWAY, endpoint)

    private suspend fun setEndpoint(
        key: Preferences.Key<String>,
        endpoint: String,
    ): Boolean {
        if (endpoint.isBlank()) {
            store.edit { it.remove(key) }
            return true
        }
        val normalized = ExternalEndpoints.normalize(endpoint) ?: return false
        store.edit { it[key] = normalized }
        return true
    }

    /** The ad-blocking categories switched on (#126). */
    val adblockCategories: Flow<Set<AdblockCategory>> = store.data.map { prefs ->
        AdblockCategory.entries.filterTo(LinkedHashSet()) { category ->
            prefs[Keys.adblock(category)] ?: category.enabledByDefault
        }
    }

    suspend fun setAdblockCategory(category: AdblockCategory, enabled: Boolean) {
        store.edit { it[Keys.adblock(category)] = enabled }
    }

    /** Sites ad blocking is off for (#126), sorted. */
    val adblockAllowlist: Flow<List<String>> = store.data.map { prefs ->
        prefs[Keys.ADBLOCK_ALLOWLIST].orEmpty().sorted()
    }

    /**
     * Turn ad blocking off for [site] (a host or URL) and its
     * subdomains. Returns `false` (and changes nothing) if it isn't a host.
     */
    suspend fun addAdblockAllowlistHost(site: String): Boolean {
        val host = normalizeAllowlistHost(site) ?: return false
        store.edit { it[Keys.ADBLOCK_ALLOWLIST] = it[Keys.ADBLOCK_ALLOWLIST].orEmpty() + host }
        return true
    }

    suspend fun removeAdblockAllowlistHost(host: String) {
        store.edit { it[Keys.ADBLOCK_ALLOWLIST] = it[Keys.ADBLOCK_ALLOWLIST].orEmpty() - host }
    }

    /** Whether filter lists update themselves over Swarm (#127); on by default. */
    val adblockAutoUpdate: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.ADBLOCK_AUTO_UPDATE] ?: true
    }

    suspend fun setAdblockAutoUpdate(enabled: Boolean) {
        store.edit { it[Keys.ADBLOCK_AUTO_UPDATE] = enabled }
    }

    private object Keys {
        val RUN_NODE_ENABLED = booleanPreferencesKey("run_node_enabled")
        val MYOTIS_ENABLED = booleanPreferencesKey("myotis_enabled")
        val SHOW_IPFS_UI = booleanPreferencesKey("show_ipfs_ui")
        val IPFS_LOW_POWER = booleanPreferencesKey("ipfs_low_power")
        val IPFS_ROUTING_MODE = stringPreferencesKey("ipfs_routing_mode")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val SEARCH_CUSTOM_TEMPLATE = stringPreferencesKey("search_custom_template")
        val EXTERNAL_SWARM_ENDPOINT = stringPreferencesKey("external_swarm_endpoint")
        val EXTERNAL_IPFS_GATEWAY = stringPreferencesKey("external_ipfs_gateway")
        val ADBLOCK_ALLOWLIST = stringSetPreferencesKey("adblock_allowlist")
        val ADBLOCK_AUTO_UPDATE = booleanPreferencesKey("adblock_auto_update")
        private val ADBLOCK = AdblockCategory.entries.associateWith { booleanPreferencesKey("adblock_${it.key}") }
        fun adblock(category: AdblockCategory) = ADBLOCK.getValue(category)
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
