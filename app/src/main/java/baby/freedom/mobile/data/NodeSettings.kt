package baby.freedom.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent toggles the user controls from the node details panel and
 * the hidden "Other" section of the settings screen.
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

    private object Keys {
        val RUN_NODE_ENABLED = booleanPreferencesKey("run_node_enabled")
        val SHOW_IPFS_UI = booleanPreferencesKey("show_ipfs_ui")
        val IPFS_LOW_POWER = booleanPreferencesKey("ipfs_low_power")
        val IPFS_ROUTING_MODE = stringPreferencesKey("ipfs_routing_mode")
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
