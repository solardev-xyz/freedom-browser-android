package baby.freedom.mobile.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import baby.freedom.mobile.browser.AdblockCategory
import baby.freedom.mobile.browser.AdblockLocaleDefaults
import androidx.datastore.preferences.preferencesDataStore
import baby.freedom.mobile.browser.ExternalEndpoints
import baby.freedom.mobile.browser.SearchEngines
import baby.freedom.mobile.browser.normalizeAllowlistHost
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.mobile.ui.Appearance
import baby.freedom.swarm.MyotisNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Persistent toggles the user controls from the node details panel and
 * the settings screen.
 *
 * Backed by a single [DataStore] under `freedom_node_settings` living
 * in the app's files directory. Flows surface the current value; the
 * corresponding suspend setter writes-through to disk.
 *
 * ## IPFS keys
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
 * ## Appearance key
 *
 * `appearance` is an [Appearance.key] (`system`, `light`, `dark`),
 * absent until the user first makes a choice (#269) — picking System
 * default then stores `system`, so absent and `system` both mean follow
 * the system. `MainActivity` applies it as the app's night mode
 * ([Appearance.apply]).
 *
 * ## Introduction key
 *
 * `intro_dismissed` is set once the home page's first-run introduction
 * (#278) is dismissed, or on first start found the install already in use;
 * see [introDismissed].
 *
 * ## Name resolution keys (#102)
 *
 * `ens_rpc_disabled_public` (the built-in public endpoints switched
 * off) and `ens_ccip_read`. The rest of what name resolution reads is
 * kept elsewhere: your own endpoints are Ethereum mainnet's own RPCs in
 * [ChainStore] (#108 — one list for every mainnet read), and the keyed
 * providers' API keys are encrypted in [RpcKeyStore]. Read together as
 * [ensRpcConfig]; the resolver reads that for every lookup, so edits
 * apply without a restart. Earlier builds kept both here
 * (`ens_rpc_custom_endpoints`, and `ens_rpc_api_keys` in plain text);
 * the first read moves them.
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
    // Lazy: the `:node` process reads this file too, and never needs
    // (or should open) the chain list or the Keystore.
    chains: () -> ChainStore,
    keys: () -> RpcKeyStore,
    /**
     * Monotonic milliseconds for [migrateEnsRpc]'s back-off: the wall
     * clock would stall retries for as long as NTP set it back by.
     */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
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
     * Whether the embedded Swarm node runs in light mode (#114) rather than
     * ultra-light. Ultra-light by default, as on desktop and iOS: browsing
     * needs no chain. Light connects the node to Gnosis Chain so it can
     * publish. Stored as iOS's `BeeNodeMode` raw values ("light",
     * "ultra-light"). `MainActivity` relays it, with the Gnosis RPC it
     * needs ([baby.freedom.mobile.node.SwarmMode]), to the `:node` process
     * through [baby.freedom.mobile.node.INodeService.setSwarmMode].
     */
    val swarmLightMode: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.SWARM_NODE_MODE] == SWARM_MODE_LIGHT
    }

    suspend fun setSwarmLightMode(light: Boolean) {
        store.edit { it[Keys.SWARM_NODE_MODE] = if (light) SWARM_MODE_LIGHT else SWARM_MODE_ULTRA_LIGHT }
    }

    /**
     * Whether the Swarm node pays peers from its chequebook ("Pay peers
     * from the chequebook" on the Chequebook page): bee's swap-enable,
     * for faster downloads and uploads than the free tier. On by default,
     * as in bee, ant and desktop (`antSwapEnable`). ant doesn't persist
     * it, so this is the record: `MainActivity` relays it to the `:node`
     * process on every bind and every change
     * ([baby.freedom.mobile.node.INodeService.setSwapEnabled]), and the
     * node applies it after every init.
     */
    val swarmSwapEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.SWARM_SWAP_ENABLED] ?: true
    }

    suspend fun setSwarmSwapEnabled(enabled: Boolean) {
        store.edit { it[Keys.SWARM_SWAP_ENABLED] = enabled }
    }

    /**
     * The Swarm node's disk chunk cache size ([SwarmCacheSize]), 512 MB —
     * ant's own default — until the user picks another. ant doesn't
     * persist its cap, so this is the record: `MainActivity` relays it to
     * the `:node` process on every bind and every change
     * ([baby.freedom.mobile.node.INodeService.setSwarmCacheCapacity]),
     * which applies it live and hands it to every init; the `:node`
     * process reads it here itself before it has heard from the UI.
     */
    val swarmCacheSize: Flow<SwarmCacheSize> = store.data.map { prefs ->
        SwarmCacheSize.fromBytes(prefs[Keys.SWARM_CACHE_CAPACITY_BYTES])
    }

    /** [swarmCacheSize]'s cap in bytes. */
    val swarmCacheCapacityBytes: Flow<Long> = swarmCacheSize.map { it.bytes }

    suspend fun setSwarmCacheSize(size: SwarmCacheSize) {
        store.edit { it[Keys.SWARM_CACHE_CAPACITY_BYTES] = size.bytes }
    }

    /**
     * Chequebooks (lowercase `0x` addresses) whose lost cheque ledger the
     * user confirmed on the Chequebook page. Once confirmed, ant stops
     * reporting the loss and counts only the cheques written since, so its
     * `availableBalance` reads higher than what is really left; ant keeps
     * no sign of that the page can read, so this is the record the page
     * uses to keep calling the credit an upper bound. Written *before* the
     * node is asked to confirm ([baby.freedom.mobile.browser.confirmLostLedger]),
     * so a confirmation that lands late or unseen is still on record; an
     * entry for one that never landed is ignored while the loss is reported.
     */
    val swarmConfirmedLedgers: Flow<Set<String>> = store.data.map { prefs ->
        prefs[Keys.SWARM_CONFIRMED_LEDGERS].orEmpty()
    }

    suspend fun addSwarmConfirmedLedger(chequebook: String) {
        val key = chequebook.lowercase()
        store.edit { it[Keys.SWARM_CONFIRMED_LEDGERS] = it[Keys.SWARM_CONFIRMED_LEDGERS].orEmpty() + key }
    }

    /**
     * Whether the embedded Radicle node should run (#73). Off by default,
     * as on iOS: it's a publish-capable node that creates an identity key
     * and dials Radicle seeds, so it starts only once the user asks. The UI
     * relays it to the `:node` process on every bind, and live from the
     * Radicle page, through [baby.freedom.mobile.node.INodeService.startRadicle] /
     * [baby.freedom.mobile.node.INodeService.stopRadicle].
     */
    val radicleEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.RADICLE_ENABLED] ?: false
    }

    suspend fun setRadicleEnabled(enabled: Boolean) {
        store.edit { it[Keys.RADICLE_ENABLED] = enabled }
    }

    /**
     * The Myotis light client's chains that start at launch (#274), each
     * chosen on its own on the node page, as desktop's Settings → Startup
     * does. Off by default — opt-in, as on desktop. Once running, a chain
     * follows its switch on the node page ([baby.freedom.mobile.node.MyotisChains]).
     *
     * Before #274 one switch (`myotis_enabled`, #72) ran both chains, and
     * was on at every launch while on: a chain with no choice of its own
     * yet keeps that value.
     */
    val myotisStartOnLaunch: Flow<Set<MyotisNetwork>> = store.data.map { prefs ->
        MyotisNetwork.entries.filterTo(LinkedHashSet()) { network ->
            prefs[Keys.myotisStartOnLaunch(network)] ?: prefs[Keys.LEGACY_MYOTIS_ENABLED] ?: false
        }
    }

    suspend fun setMyotisStartOnLaunch(network: MyotisNetwork, enabled: Boolean) {
        store.edit { it[Keys.myotisStartOnLaunch(network)] = enabled }
    }

    /**
     * Settings → Tor (#143): whether `.onion` sites open through the
     * embedded Tor client. Off by default, as on desktop; while off, onion
     * sites are refused (never resolved directly) and Tor can't be started.
     * `MainActivity` relays it to `TorRouting` and stops Tor when it's
     * switched off.
     */
    val torEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.TOR_ENABLED] ?: false
    }

    suspend fun setTorEnabled(enabled: Boolean) {
        store.edit { it[Keys.TOR_ENABLED] = enabled }
    }

    /**
     * Settings → Tor → Start Tor at launch (#143): start the Tor
     * client at launch while [torEnabled] is on. Off by default; otherwise
     * Tor is started from the node page.
     */
    val torStartOnLaunch: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.TOR_START_ON_LAUNCH] ?: false
    }

    suspend fun setTorStartOnLaunch(enabled: Boolean) {
        store.edit { it[Keys.TOR_START_ON_LAUNCH] = enabled }
    }

    /**
     * Settings → Tor → Tor client (#275): `""` for the embedded Arti
     * client (the default), else an external Tor SOCKS proxy on this
     * device as `host:port` (`TorProxy.parse` accepted and normalized it),
     * e.g. Orbot's `127.0.0.1:9050`. `MainActivity` relays it to
     * `TorRouting` and runs the matching client.
     */
    val torExternalProxy: Flow<String> = store.data.map { prefs ->
        prefs[Keys.TOR_EXTERNAL_PROXY] ?: ""
    }

    suspend fun setTorExternalProxy(value: String) {
        store.edit { it[Keys.TOR_EXTERNAL_PROXY] = value }
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

    /**
     * Settings → Search → *Search suggestions* (#443): while on, what
     * is typed into a regular tab's address bar is sent to the selected
     * engine for suggestions (`SearchSuggestions`). Off by default; never
     * used in private tabs.
     *
     * The consent names one engine ("Sends what you type … to
     * DuckDuckGo"), so it is stored as that engine's id and holds only
     * while that engine is the one in use ([searchSuggestionsFor]):
     * picking another engine turns it off, and it stays off on a return
     * to the first one until the user turns it on again.
     */
    val searchSuggestions: Flow<Boolean> = store.data.map { prefs ->
        searchSuggestionsFor(
            prefs[Keys.SEARCH_SUGGESTIONS_ENGINE],
            prefs[Keys.SEARCH_ENGINE],
            prefs[Keys.SEARCH_CUSTOM_TEMPLATE],
        )
    }

    /**
     * Turn *Search suggestions* on for the engine in use now, or off. A
     * custom engine has no known suggestion service and can't be turned on.
     */
    suspend fun setSearchSuggestions(enabled: Boolean) {
        store.edit { prefs ->
            val engine = SearchEngines.effectiveId(prefs[Keys.SEARCH_ENGINE], prefs[Keys.SEARCH_CUSTOM_TEMPLATE])
            if (enabled && engine != SearchEngines.CUSTOM_ID) {
                prefs[Keys.SEARCH_SUGGESTIONS_ENGINE] = engine
            } else {
                prefs.remove(Keys.SEARCH_SUGGESTIONS_ENGINE)
            }
        }
    }

    /**
     * Select a built-in engine; the saved custom template is kept. A
     * *Search suggestions* consent given for another engine is dropped.
     */
    suspend fun setSearchEngine(id: String) {
        require(SearchEngines.BUILT_IN.any { it.id == id }) { "unknown engine $id" }
        store.edit {
            it[Keys.SEARCH_ENGINE] = id
            if (it[Keys.SEARCH_SUGGESTIONS_ENGINE] != id) it.remove(Keys.SEARCH_SUGGESTIONS_ENGINE)
        }
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
            it.remove(Keys.SEARCH_SUGGESTIONS_ENGINE)
        }
        return true
    }

    /**
     * Settings → Appearance → Theme (#269); [Appearance.System] until chosen.
     *
     * A read error doesn't end the flow: DataStore's `data` stops at the
     * first failure, and both readers (the app's night mode, the Compose
     * chrome) live as long as the Activity, so a transient `IOException`
     * would otherwise stop every later choice from being applied until
     * the next launch. It is logged and read again after a short back-off
     * (capped at [APPEARANCE_RETRY_MAX_MS]); readers keep the last value
     * meanwhile.
     */
    val appearance: Flow<Appearance> = store.data
        .map { prefs -> Appearance.fromKey(prefs[Keys.APPEARANCE]) }
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            Log.w(TAG, "reading the appearance setting failed (${cause.javaClass.simpleName}); retrying")
            delay((APPEARANCE_RETRY_FIRST_MS shl attempt.coerceAtMost(5L).toInt()).coerceAtMost(APPEARANCE_RETRY_MAX_MS))
            true
        }

    suspend fun setAppearance(appearance: Appearance) {
        store.edit { it[Keys.APPEARANCE] = appearance.key }
    }

    /**
     * Whether the home page's first-run introduction (#278) is done with:
     * `false` shows it, `true` never again, `null` while not yet decided.
     *
     * [settleIntro] decides it once, at the app's first start with this
     * build; Got it ([dismissIntro]) sets it to `true`. Nothing but clearing
     * the app's data removes the key — Delete browsing data leaves it.
     *
     * A read error doesn't end the flow (same reasoning and back-off as
     * [appearance]); the home page shows the card only once a read has said
     * `false`, so an unreadable file never shows it again to someone who
     * already dismissed it.
     */
    val introDismissed: Flow<Boolean?> = store.data
        .map { prefs -> prefs[Keys.INTRO_DISMISSED] }
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            Log.w(TAG, "reading the introduction setting failed (${cause.javaClass.simpleName}); retrying")
            delay((APPEARANCE_RETRY_FIRST_MS shl attempt.coerceAtMost(5L).toInt()).coerceAtMost(APPEARANCE_RETRY_MAX_MS))
            true
        }

    /**
     * Decide [introDismissed] if nothing has yet: a first launch shows the
     * introduction, but someone updating from a build that predates it
     * isn't on one. Either this store already holds a setting (only the
     * user's own choices write one, so a fresh install's is empty), or
     * [usedBefore] (the install already has pages, bookmarks or a wallet)
     * says so; either counts as already dismissed. Asked only while
     * undecided.
     */
    suspend fun settleIntro(usedBefore: suspend () -> Boolean) {
        val prefs = store.data.first()
        if (prefs[Keys.INTRO_DISMISSED] != null) return
        val used = prefs.asMap().keys.any { it != Keys.INTRO_DISMISSED } || usedBefore()
        store.edit { if (it[Keys.INTRO_DISMISSED] == null) it[Keys.INTRO_DISMISSED] = used }
    }

    suspend fun dismissIntro() {
        store.edit { it[Keys.INTRO_DISMISSED] = true }
    }

    private val chainStore: ChainStore by lazy(chains)
    private val keyStore: RpcKeyStore by lazy(keys)

    /** Serializes every name-resolution write, so the last-endpoint check holds across the stores. */
    private val ensLock = Mutex()

    @Volatile
    private var ensMigrated = false

    /** After a failed [migrateEnsRpc], when it may try again, and how long it waits after the next failure. */
    @Volatile
    private var ensMigrateRetryAt = 0L
    private var ensMigrateBackOffMs = MIGRATE_RETRY_MS

    /** Your own Ethereum mainnet RPCs ([ChainStore], #108) — "Your endpoints" here. */
    private fun mainnetUserRpcs(): Flow<List<String>> = chainStore.chains
        .map { chains -> chains.firstOrNull { it.id == MAINNET }?.userRpcUrls.orEmpty() }

    /**
     * Everything name resolution reads, as one value (see class kdoc):
     * this file's switches, your own mainnet RPCs from [ChainStore] and
     * the API keys from [RpcKeyStore]. The first read moves anything an
     * earlier build kept here ([migrateEnsRpc]).
     */
    val ensRpcConfig: Flow<EnsRpcConfig> = flow {
        migrateEnsRpc()
        emitAll(combine(store.data, mainnetUserRpcs(), keyStore.apiKeys, ::readEnsRpc))
    }

    /**
     * [apiKeys] are the encrypted store's; a key an earlier build left in
     * plain text here ([RpcKeyStore.LEGACY_KEY]) is used too until
     * [migrateEnsRpc] manages to move it — the encrypted copy wins — so
     * a Keystore that keeps failing doesn't silently cost the user keys
     * they saved.
     */
    private fun readEnsRpc(prefs: Preferences, mine: List<String>, apiKeys: Map<String, String>) =
        EnsRpcConfig(
            customEndpoints = mine,
            disabledPublicEndpoints = prefs[Keys.ENS_RPC_DISABLED_PUBLIC].orEmpty(),
            apiKeys = EnsRpcConfig.decodeKeys(prefs[RpcKeyStore.LEGACY_KEY]) + apiKeys,
            ccipRead = prefs[Keys.ENS_CCIP_READ] ?: true,
            colibri = prefs[Keys.ENS_COLIBRI] ?: true,
        )

    private suspend fun currentEnsRpc(): EnsRpcConfig =
        readEnsRpc(store.data.first(), mainnetUserRpcs().first(), keyStore.apiKeys.first())

    /**
     * Move what builds before the encrypted key store and the shared
     * mainnet RPC list kept in this file:
     *
     * - `ens_rpc_api_keys`, the API keys in plain text, into
     *   [RpcKeyStore] (encrypted; a key already saved there wins), and
     *   only once that write landed is the plain-text copy deleted;
     * - `ens_rpc_custom_endpoints`, the user's own endpoints, onto
     *   Ethereum mainnet's own RPCs in [ChainStore] — one list for
     *   name resolution, `web3://` apps and every other mainnet read.
     *   One that list doesn't take (a LAN `http://` address, which
     *   [baby.freedom.mobile.chains.RpcUrls] refuses for every chain;
     *   one past its cap) is dropped, counted in the log but never
     *   named there (a URL can carry a key).
     *
     * A write that fails leaves the old value in place (still used, see
     * [readEnsRpc]), and a read after [MIGRATE_RETRY_MS] — doubling on
     * each further failure, up to [MIGRATE_RETRY_MAX_MS] — tries again,
     * rather than every lookup repeating a failing Keystore write.
     */
    private suspend fun migrateEnsRpc() {
        if (ensMigrated || clock() < ensMigrateRetryAt) return
        ensLock.withLock {
            if (ensMigrated || clock() < ensMigrateRetryAt) return
            var done = true
            try {
                val prefs = store.data.first()
                prefs[RpcKeyStore.LEGACY_KEY]?.let { json ->
                    val legacy = EnsRpcConfig.decodeKeys(json)
                    if (keyStore.edit { saved -> if (legacy.isEmpty()) null else legacy + saved }) {
                        store.edit { it.remove(RpcKeyStore.LEGACY_KEY) }
                    } else {
                        done = false
                    }
                }
                prefs[Keys.LEGACY_ENS_RPC_CUSTOM]?.let { json ->
                    val skipped = chainStore.importUserRpcs(MAINNET, EnsRpcConfig.decodeList(json))
                    if (skipped == null) {
                        done = false
                    } else {
                        if (skipped.isNotEmpty()) {
                            Log.w(TAG, "${skipped.size} name-resolution endpoint(s) not taken by Ethereum's RPC list")
                        }
                        store.edit { it.remove(Keys.LEGACY_ENS_RPC_CUSTOM) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "moving name-resolution settings failed (${e.javaClass.simpleName})")
                done = false
            }
            ensMigrated = done
            if (!done) {
                ensMigrateRetryAt = clock() + ensMigrateBackOffMs
                ensMigrateBackOffMs = (ensMigrateBackOffMs * 2).coerceAtMost(MIGRATE_RETRY_MAX_MS)
            }
        }
    }

    /** What a name-resolution write did. */
    enum class EnsEdit {
        DONE,

        /** Refused, nothing written: it would leave the resolver no endpoint to ask. */
        LAST_ENDPOINT,

        /** The write didn't land. */
        FAILED,
    }

    /**
     * Run [write] if [change] still leaves the resolver an endpoint:
     * the page greys those controls out, this is the backstop for a
     * write that races past it (a double tap before recomposition, or
     * the Ethereum chain page removing one of the same RPCs), and the
     * caller tells the user it didn't happen.
     */
    private suspend fun editEnsRpc(
        change: (EnsRpcConfig) -> EnsRpcConfig,
        write: suspend () -> Boolean,
    ): EnsEdit {
        migrateEnsRpc()
        return ensLock.withLock {
            val ok = try {
                if (change(currentEnsRpc()).endpoints.isEmpty()) return@withLock EnsEdit.LAST_ENDPOINT
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "saving name-resolution settings failed (${e.javaClass.simpleName})")
                false
            }
            if (ok) EnsEdit.DONE else EnsEdit.FAILED
        }
    }

    /** Why [addEnsRpcEndpoint] didn't add. */
    enum class AddEndpointResult { ADDED, INVALID, DUPLICATE, PUBLIC, FULL, FAILED }

    /**
     * Add [url] to your own mainnet RPCs (after the ones already there),
     * unless it isn't a valid endpoint, is already listed
     * ([EnsRpcConfig.endpointKey]), is on the host of one of name
     * resolution's own built-in public endpoints, whatever its path or
     * query ([EnsRpcConfig.isPublicEndpoint] — already asked, under its
     * own switch; taking it as yours would label a third party's lone
     * answer as your RPC's, and give it a second vote) or the list is
     * full. One of the Ethereum chain page's *other* public RPCs is
     * taken: name resolution never asks those, so adding it here is the
     * only way to have it resolve names, and it becomes yours on the
     * chain page too, asked first.
     */
    suspend fun addEnsRpcEndpoint(url: String): AddEndpointResult {
        val normalized = EnsRpcConfig.normalizeEndpoint(url) ?: return AddEndpointResult.INVALID
        migrateEnsRpc()
        return ensLock.withLock {
            val current = try {
                currentEnsRpc()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock AddEndpointResult.FAILED
            }
            if (current.hasCustomEndpoint(normalized)) return@withLock AddEndpointResult.DUPLICATE
            if (current.isPublicEndpoint(normalized)) return@withLock AddEndpointResult.PUBLIC
            when (chainStore.addUserRpc(MAINNET, normalized, allowPublic = true)) {
                ChainStore.RpcAddResult.ADDED -> AddEndpointResult.ADDED
                ChainStore.RpcAddResult.INVALID -> AddEndpointResult.INVALID
                ChainStore.RpcAddResult.DUPLICATE -> AddEndpointResult.DUPLICATE
                ChainStore.RpcAddResult.FULL -> AddEndpointResult.FULL
                ChainStore.RpcAddResult.NAME_RESOLUTION_PUBLIC -> AddEndpointResult.PUBLIC
                ChainStore.RpcAddResult.PUBLIC, ChainStore.RpcAddResult.NO_CHAIN,
                ChainStore.RpcAddResult.FAILED -> AddEndpointResult.FAILED
            }
        }
    }

    /**
     * Remove one of your own mainnet RPCs — from here or from the
     * Ethereum chain page, which share the list. Refused if it was the
     * resolver's last endpoint.
     */
    suspend fun removeEnsRpcEndpoint(url: String): EnsEdit =
        editEnsRpc({ it.copy(customEndpoints = it.customEndpoints - url) }) {
            chainStore.removeUserRpc(MAINNET, url)
        }

    /** Move one of your mainnet RPCs up (-1) or down (+1) the order. */
    suspend fun moveEnsRpcEndpoint(url: String, by: Int): EnsEdit =
        editEnsRpc({ it }) { chainStore.moveUserRpc(MAINNET, url, by) }

    /** Refused if it would switch off the last endpoint. */
    suspend fun setPublicEnsRpcEnabled(url: String, enabled: Boolean): EnsEdit {
        fun Set<String>.with() = if (enabled) this - url else this + url
        return editEnsRpc({ it.copy(disabledPublicEndpoints = it.disabledPublicEndpoints.with()) }) {
            store.edit { prefs ->
                prefs[Keys.ENS_RPC_DISABLED_PUBLIC] = prefs[Keys.ENS_RPC_DISABLED_PUBLIC].orEmpty().with()
            }
            true
        }
    }

    /**
     * Save (or, with a blank [key], remove) a keyed provider's API key,
     * encrypted ([RpcKeyStore]). Refused if removing it would leave no
     * endpoint.
     */
    suspend fun setRpcApiKey(providerId: String, key: String): EnsEdit {
        val trimmed = key.trim()
        fun Map<String, String>.with() = if (trimmed.isEmpty()) this - providerId else this + (providerId to trimmed)
        return editEnsRpc({ it.copy(apiKeys = it.apiKeys.with()) }) {
            // A plain-text copy not yet moved ([migrateEnsRpc]) would
            // otherwise keep answering for this provider.
            keyStore.edit { it.with() } && dropLegacyKey(providerId)
        }
    }

    private suspend fun dropLegacyKey(providerId: String): Boolean {
        store.edit { prefs ->
            val legacy = prefs[RpcKeyStore.LEGACY_KEY] ?: return@edit
            val left = EnsRpcConfig.decodeKeys(legacy) - providerId
            if (left.isEmpty()) prefs.remove(RpcKeyStore.LEGACY_KEY) else prefs[RpcKeyStore.LEGACY_KEY] = JSONObject(left).toString()
        }
        return true
    }

    suspend fun setEnsCcipRead(enabled: Boolean): EnsEdit =
        editEnsRpc({ it }) {
            store.edit { it[Keys.ENS_CCIP_READ] = enabled }
            true
        }

    /**
     * The *Colibri proofs* switch alone, straight from this file (no
     * migration, no key store): what the chain-data router's Colibri tier
     * follows ([baby.freedom.mobile.chains.rpc.ColibriReads], #329).
     */
    val ensColibri: Flow<Boolean> = store.data.map { prefs -> prefs[Keys.ENS_COLIBRI] ?: true }

    /**
     * Whether name resolution asks the Colibri verifier for a proof first
     * (#100), and the chain-data router's reads go to it (#329).
     */
    suspend fun setEnsColibri(enabled: Boolean): EnsEdit =
        editEnsRpc({ it }) {
            store.edit { it[Keys.ENS_COLIBRI] = enabled }
            true
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

    /**
     * The ad-blocking categories switched on (#126). Re-emits when a
     * locale-dependent default changes ([AdblockLocaleDefaults], #405).
     */
    val adblockCategories: Flow<Set<AdblockCategory>> = combine(store.data, AdblockLocaleDefaults.german) { prefs, _ ->
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

    /**
     * Whether the app checks GitHub for a newer Freedom release (#272),
     * at most daily; on by default ([baby.freedom.mobile.browser.AppUpdates]).
     */
    val checkForUpdates: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.CHECK_FOR_UPDATES] ?: true
    }

    suspend fun setCheckForUpdates(enabled: Boolean) {
        store.edit { it[Keys.CHECK_FOR_UPDATES] = enabled }
    }

    /**
     * *Ask where to save each file* (#322): confirming a download opens
     * the system's *Save as* picker instead of saving to Download/Freedom.
     * Off by default, like desktop's.
     */
    val askWhereToSave: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.ASK_WHERE_TO_SAVE] ?: false
    }

    suspend fun setAskWhereToSave(enabled: Boolean) {
        store.edit { it[Keys.ASK_WHERE_TO_SAVE] = enabled }
    }

    private object Keys {
        val ASK_WHERE_TO_SAVE = booleanPreferencesKey("ask_where_to_save")
        /** The engine id *Search suggestions* was turned on for (see [searchSuggestions]). */
        val SEARCH_SUGGESTIONS_ENGINE = stringPreferencesKey("search_suggestions_engine")
        val RUN_NODE_ENABLED = booleanPreferencesKey("run_node_enabled")
        val SWARM_NODE_MODE = stringPreferencesKey("swarm_node_mode")
        val SWARM_SWAP_ENABLED = booleanPreferencesKey("swarm_swap_enabled")
        val SWARM_CACHE_CAPACITY_BYTES = longPreferencesKey("swarm_cache_capacity_bytes")
        val SWARM_CONFIRMED_LEDGERS = stringSetPreferencesKey("swarm_confirmed_ledgers")
        /** Both chains' start at launch before #274; see [myotisStartOnLaunch]. */
        val LEGACY_MYOTIS_ENABLED = booleanPreferencesKey("myotis_enabled")
        private val MYOTIS_START_ON_LAUNCH = MyotisNetwork.entries.associateWith {
            booleanPreferencesKey("myotis_${it.engineName}_start_on_launch")
        }
        fun myotisStartOnLaunch(network: MyotisNetwork) = MYOTIS_START_ON_LAUNCH.getValue(network)
        val TOR_ENABLED = booleanPreferencesKey("tor_enabled")
        val TOR_START_ON_LAUNCH = booleanPreferencesKey("tor_start_on_launch")
        val TOR_EXTERNAL_PROXY = stringPreferencesKey("tor_external_proxy")
        val RADICLE_ENABLED = booleanPreferencesKey("radicle_enabled")
        val IPFS_LOW_POWER = booleanPreferencesKey("ipfs_low_power")
        val IPFS_ROUTING_MODE = stringPreferencesKey("ipfs_routing_mode")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val SEARCH_CUSTOM_TEMPLATE = stringPreferencesKey("search_custom_template")
        val APPEARANCE = stringPreferencesKey("appearance")
        val INTRO_DISMISSED = booleanPreferencesKey("intro_dismissed")
        /** Moved onto Ethereum mainnet's own RPCs in [ChainStore]; see [migrateEnsRpc]. */
        val LEGACY_ENS_RPC_CUSTOM = stringPreferencesKey("ens_rpc_custom_endpoints")
        val ENS_RPC_DISABLED_PUBLIC = stringSetPreferencesKey("ens_rpc_disabled_public")
        val ENS_CCIP_READ = booleanPreferencesKey("ens_ccip_read")
        val ENS_COLIBRI = booleanPreferencesKey("ens_colibri")
        val EXTERNAL_SWARM_ENDPOINT = stringPreferencesKey("external_swarm_endpoint")
        val EXTERNAL_IPFS_GATEWAY = stringPreferencesKey("external_ipfs_gateway")
        val ADBLOCK_ALLOWLIST = stringSetPreferencesKey("adblock_allowlist")
        val ADBLOCK_AUTO_UPDATE = booleanPreferencesKey("adblock_auto_update")
        val CHECK_FOR_UPDATES = booleanPreferencesKey("check_for_updates")
        private val ADBLOCK = AdblockCategory.entries.associateWith { booleanPreferencesKey("adblock_${it.key}") }
        fun adblock(category: AdblockCategory) = ADBLOCK.getValue(category)
    }

    companion object {
        const val DEFAULT_IPFS_ROUTING_MODE = "auto"

        private const val SWARM_MODE_LIGHT = "light"
        private const val SWARM_MODE_ULTRA_LIGHT = "ultra-light"

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

        /**
         * A settings file that no longer parses (a write torn by a power
         * loss, a bad sector) reads as empty — every setting at its
         * default — and is replaced on the next write, as every other
         * store here does. Without it the read throws
         * [androidx.datastore.core.CorruptionException] into collectors
         * nobody catches (ad blocking's, at startup), and the app dies on
         * every launch until its data is cleared — wallet and all.
         *
         * The defaults aren't all the most private choice, so a reset can
         * quietly undo some of what the user had turned off: public
         * mainnet RPCs switched off for name lookups are used again
         * ([Keys.ENS_RPC_DISABLED_PUBLIC] empties), CCIP-Read and the
         * daily GitHub release check come back on, the search engine goes
         * back to DuckDuckGo, and the external Swarm/IPFS endpoints, Tor
         * and the ad-block allowlist and categories go back to theirs.
         * Clearing app data — the only way out before this handler —
         * lands on the same defaults; nothing tells the user a reset
         * happened yet (#372).
         */
        internal val corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }

        private val Context.nodeSettingsStore by preferencesDataStore(
            name = "freedom_node_settings",
            corruptionHandler = corruptionHandler,
        )

        @Volatile
        private var instance: NodeSettings? = null

        private const val TAG = "NodeSettings"
        internal const val APPEARANCE_RETRY_FIRST_MS = 1_000L
        internal const val APPEARANCE_RETRY_MAX_MS = 30_000L
        private const val MAINNET = 1L
        private const val MIGRATE_RETRY_MS = 30_000L
        private const val MIGRATE_RETRY_MAX_MS = 30 * 60_000L

        /** Over arbitrary stores, for unit tests. */
        internal fun forTesting(
            store: DataStore<Preferences>,
            chains: ChainStore,
            keys: RpcKeyStore,
            clock: () -> Long = { System.nanoTime() / 1_000_000 },
        ): NodeSettings = NodeSettings(store, { chains }, { keys }, clock)

        fun get(context: Context): NodeSettings =
            instance ?: synchronized(this) {
                val app = context.applicationContext
                instance ?: NodeSettings(
                    app.nodeSettingsStore,
                    { ChainStore.get(app) },
                    { RpcKeyStore.get(app) },
                ).also { instance = it }
            }
    }
}

/**
 * Whether *Search suggestions* is on: it was turned on for
 * [consentedEngine] and that is still the engine in use
 * ([SearchEngines.effectiveId] of [engineId]/[customTemplate]) — never
 * for a custom engine. A consent for DuckDuckGo doesn't carry over to
 * Google, nor to a stale `custom` that falls back to the default.
 */
internal fun searchSuggestionsFor(consentedEngine: String?, engineId: String?, customTemplate: String?): Boolean {
    if (consentedEngine == null || consentedEngine == SearchEngines.CUSTOM_ID) return false
    return consentedEngine == SearchEngines.effectiveId(engineId, customTemplate)
}
