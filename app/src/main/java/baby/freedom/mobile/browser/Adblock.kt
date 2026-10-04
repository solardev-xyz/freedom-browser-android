package baby.freedom.mobile.browser

import android.content.Context
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.annotation.StringRes
import baby.freedom.mobile.R
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "Adblock"

/**
 * The filter-list categories (#126), as on desktop and iOS: ads and
 * trackers on by default, cookie notices and other annoyances opt-in —
 * plus, Android only so far, EasyList Germany (#393): EasyList covers
 * English-language sites, and German sites' ads are in the regional
 * list, on by default where the phone's languages include German or
 * its region is a German-speaking one ([germanListByDefault]), offered
 * to everyone else. [file] is the list's name under `assets/adblock/`
 * (`infra/adblock/vendor-lists.py` refreshes them).
 */
enum class AdblockCategory(
    val key: String,
    val file: String,
    @StringRes private val titleRes: Int,
    val listName: String,
    private val defaultOn: () -> Boolean,
) {
    ADS("ads", "easylist.txt", R.string.settings_adblock_block_ads, "EasyList", { true }),
    GERMAN(
        "german", "easylistgermany.txt", R.string.settings_adblock_block_german, "EasyList Germany",
        { germanListByDefault(systemLocales()) },
    ),
    PRIVACY("privacy", "easyprivacy.txt", R.string.settings_adblock_block_trackers, "EasyPrivacy", { true }),
    COOKIES(
        "cookies", "fanboy-cookiemonster.txt", R.string.settings_adblock_block_cookie_notices,
        "Fanboy's Cookiemonster", { false },
    ),
    ANNOYANCES(
        "annoyances", "fanboy-annoyance.txt", R.string.settings_adblock_block_annoyances,
        "Fanboy's Annoyances", { false },
    ),
    ;

    /** On until the user switches it (the stored choice wins); for [GERMAN], read from the phone's locales each time. */
    val enabledByDefault: Boolean get() = defaultOn()

    /** The category's switch in Settings ("Block ads"). */
    val title: String get() = Strings.get(titleRes)

    /**
     * The bundled-only lists the category also compiles (#318): uBlock
     * Origin's own filters, under *Block ads* as on desktop, and Freedom's
     * own fixes (#391). The update channel doesn't carry either: uBlock's
     * is refreshed by `vendor-lists.py` before each release, Freedom's is
     * edited by hand in this repository and nothing refreshes it.
     */
    val bundledExtras: List<BundledList> get() =
        if (this == ADS) listOf(BundledList.UBLOCK, BundledList.FREEDOM) else emptyList()

    /** Every list the category compiles, for its Settings line ("EasyList, uBlock filters"). */
    val listNames: List<String> get() = listOf(listName) + bundledExtras.map { it.listName }
}

/**
 * A bundled-only filter list (#318), compiled with its category's
 * [AdblockCategory.bundledExtras], always from the APK's assets — the
 * update channel's lists live elsewhere and name categories, not these.
 * [trustedScriptlets]: it may call the `trusted-*` scriptlets — uBlock
 * Origin's own list, as in uBlock, and Freedom's, which this repository
 * maintains.
 */
enum class BundledList(val file: String, val listName: String, val trustedScriptlets: Boolean) {
    UBLOCK("ublock-filters.txt", "uBlock filters", true),

    /**
     * Freedom's own fixes for sites upstream doesn't handle yet (#391),
     * maintained by hand: `vendor-lists.py` neither writes nor removes it.
     */
    FREEDOM("freedom-filters.txt", "Freedom filters", true),
}

/** The phone's (or, set per app, the app's) languages, in the user's order; the JVM default where there's no `LocaleList`. */
private fun systemLocales(): List<Locale> {
    val list: LocaleList? = runCatching { LocaleList.getDefault() }.getOrNull()
    if (list == null || list.isEmpty) return listOf(Locale.getDefault())
    return (0 until list.size()).map { list[it] }
}

/** Countries whose sites are largely in German, for [germanListByDefault]. */
private val GERMAN_SPEAKING_REGIONS = setOf("DE", "AT", "CH", "LI", "LU")

/**
 * Is EasyList Germany on by default (#393)? Where German is among the
 * phone's languages (Settings → Languages), or its main locale's region
 * is a German-speaking country (an English UI in Vienna still reads
 * Austrian sites).
 */
internal fun germanListByDefault(locales: List<Locale>): Boolean =
    locales.any { it.language == "de" || it.language == "gsw" } ||
        (locales.firstOrNull()?.country?.uppercase()?.let { it in GERMAN_SPEAKING_REGIONS } ?: false)

/** The uBlock Origin scriptlets the `+js(…)` rules call (#318), under `assets/adblock/`. */
internal const val SCRIPTLET_RESOURCES_FILE = "resources.json"

/**
 * The allowlist's form of a site: lower-case ASCII host, no scheme,
 * path, port or trailing dot, and no leading `www.` — or `null` for
 * input that isn't a host (no dot, a space, …). Takes a URL or a bare
 * host, as the Settings field and the page menu both hand it one.
 *
 * An internationalised name is stored as Chromium reports it, in
 * punycode (UTS-46, the same mapping the WebView applies — see
 * [WhatwgHost.domainToAscii]): `bücher.de` becomes `xn--bcher-kva.de`,
 * so the entry matches the requests it is meant for.
 */
internal fun normalizeAllowlistHost(input: String?): String? {
    var s = input?.trim()?.lowercase() ?: return null
    if (s.isEmpty()) return null
    if (s.contains("://")) s = hostOfUrl(s) ?: return null
    s = s.substringBefore('/').substringBefore('?').substringBefore('#')
    if (s.startsWith("[")) return null
    s = s.substringBefore(':').trimEnd('.')
    if (s.any { it.code >= 0x80 }) s = WhatwgHost.domainToAscii(s)?.trimEnd('.') ?: return null
    if (s.startsWith("www.")) s = s.removePrefix("www.")
    if (s.isEmpty() || !s.contains('.') || s.startsWith('.')) return null
    if (!s.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }) return null
    return s
}

/**
 * How Settings names allowlist entry [host] (stored in punycode, see
 * [normalizeAllowlistHost]): its Unicode form when it has an `xn--`
 * label that decodes cleanly and maps back to exactly [host], else
 * [host] itself. `xn--bcher-kva.de` reads `bücher.de`.
 *
 * Display only — matching always uses the stored form. Where this
 * differs from [host], Settings shows [host] too, on the sub-line, so
 * a lookalike name can't pass for another site.
 */
internal fun allowlistHostForDisplay(host: String): String {
    if (host.split('.').none { it.startsWith("xn--") }) return host
    val unicode = WhatwgHost.uts46.toUnicode(host) ?: return host
    return if (WhatwgHost.domainToAscii(unicode) == host) unicode else host
}

/**
 * Is [host] on the [allowlist] — an entry itself or a subdomain of one?
 * `m.news.example` is covered by `news.example`; `badnews.example` is not.
 */
internal fun isAllowlisted(host: String?, allowlist: Collection<String>): Boolean {
    if (allowlist.isEmpty()) return false
    val h = normalizeAllowlistHost(host) ?: return false
    var hit = false
    forEachHostSuffix(h) { if (it in allowlist) hit = true }
    return hit
}

/**
 * The site the page menu's ad-blocking switch is for, for the tab's
 * address [url]: an ordinary http(s) page's host in allowlist form, or
 * `null` for home, dweb content (`bzz://`, `ens://`, … — the address
 * the tab shows isn't a host) and the local nodes.
 *
 * A dweb page's own resources are never filtered (they come from its
 * virtual origin), but third-party http(s) resources it pulls in are,
 * and there is no switch to allow those per page: the only way out for
 * such a page is turning the category off. See the README's
 * *Ad and tracker blocking*.
 */
internal fun adblockSiteFor(url: String): String? {
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
    val host = hostOfUrl(url) ?: return null
    if (host == "localhost" || host == "127.0.0.1" || VirtualOrigin.isVirtualHost(host)) return null
    return normalizeAllowlistHost(host)
}

/**
 * What the page menu's ad-blocking switch says about the site on
 * screen (#126). Only [BLOCKING] shows the switch on; [BLOCKING] and
 * [ALLOWED] are the states a tap changes.
 */
internal enum class AdblockSiteState(@StringRes private val noteRes: Int?) {
    /** Filters apply to the page. */
    BLOCKING(null),
    /** The user allowlisted the site; a tap lifts that. */
    ALLOWED(R.string.settings_adblock_state_allowed),
    /** No engine yet: the lists are still compiling. */
    LOADING(R.string.settings_adblock_state_loading),
    /** No engine: every category is off in Settings. */
    OFF(R.string.settings_adblock_state_off),
    /** A list's `@@…$document` exception exempts the page. */
    EXEMPT(R.string.settings_adblock_state_exempt),
    ;

    /** What the page menu says under the switch; `null` while blocking. */
    val note: String? get() = noteRes?.let { Strings.get(it) }

    /** Whether the switch shows blocking on. */
    val checked: Boolean get() = this == BLOCKING

    /** Whether a tap does anything: allowlist, or lift the allowlisting. */
    val toggleable: Boolean get() = this == BLOCKING || this == ALLOWED
}

/**
 * The switch's state for the page [pageUrl] on [site] (its
 * [adblockSiteFor] form), given whether the site is [allowlisted], the
 * current [engine] and whether one is still [loading]. The user's own
 * allowlisting wins, so it can always be lifted.
 */
internal fun adblockSiteState(
    pageUrl: String,
    site: String,
    allowlisted: Boolean,
    engine: AdblockEngine?,
    loading: Boolean,
): AdblockSiteState = when {
    allowlisted -> AdblockSiteState.ALLOWED
    engine == null -> if (loading) AdblockSiteState.LOADING else AdblockSiteState.OFF
    engine.documentExempt(pageUrl, hostOfUrl(pageUrl) ?: site) -> AdblockSiteState.EXEMPT
    else -> AdblockSiteState.BLOCKING
}

/**
 * Whether a tap on the page menu's switch in state [current] must drop
 * the renderer's in-memory cache before the page reloads: when it
 * turns blocking back on ([AdblockSiteState.ALLOWED]). Blink reuses a
 * resource it still holds in memory without a network request, so
 * `shouldInterceptRequest` never sees it, and an ad image the page
 * loaded while the site was allowed would come back on the reload.
 * Allowing a site needs nothing: a blocked request's empty 403 is
 * `no-store`.
 */
internal fun dropsMemoryCache(current: AdblockSiteState): Boolean = current == AdblockSiteState.ALLOWED

/** An allowlist change to write to Settings: [add] or remove [host]. */
internal data class AllowlistWrite(val add: Boolean, val host: String)

/**
 * The saved allowlist and the changes on their way to it (#126).
 * [write] applies a change in memory at once and queues it; [run]
 * writes the queue through [persist] one at a time, in order, so a
 * quick allow-then-remove can't land the other way round.
 *
 * [saved] takes each allowlist read back from storage; [current] is
 * that with every write storage hasn't been seen to reflect yet on
 * top. A write stays on top after it lands, until a read shows storage
 * past it — so [current] never loses a landed write in the gap before
 * storage's next read arrives, and a read taken before a write landed
 * (reads arrive in order but can lag) never undoes it. This relies on
 * the store being the allowlist's only writer. [onChange] runs
 * whenever [current] may have changed.
 */
internal class AllowlistStore(
    private val persist: suspend (AllowlistWrite) -> Unit,
    private val onChange: (Set<String>) -> Unit,
) {
    private val queue = Channel<AllowlistWrite>(Channel.UNLIMITED)

    /** Queued or being written, in call order. */
    private val pending = ArrayList<AllowlistWrite>()

    /** Written, in order, but not yet seen in a read of storage. */
    private val landed = ArrayList<AllowlistWrite>()

    /** The last read of storage. */
    private var saved: Set<String> = emptySet()

    @Volatile
    var current: Set<String> = emptySet()
        private set

    fun write(add: Boolean, host: String) = update {
        val w = AllowlistWrite(add, host)
        pending += w
        queue.trySend(w)
    }

    /**
     * A read of storage. Storage has gone through [saved] and then each
     * [landed] write in turn; the read is one of those states, so the
     * writes up to the earliest one it matches are in it and the rest
     * stay on top. (Any match gives the same [current]; the earliest is
     * the one a later, lagging read can't contradict.) A read matching
     * none is taken as is, with every landed write kept on top.
     */
    fun saved(list: Collection<String>) = update {
        val read = list.toSet()
        var state = saved
        var seen = if (state == read) 0 else -1
        if (seen < 0) {
            for ((i, w) in landed.withIndex()) {
                state = w.applyTo(state)
                if (state == read) {
                    seen = i + 1
                    break
                }
            }
        }
        saved = read
        if (seen > 0) landed.subList(0, seen).clear()
    }

    /** Drain the queue, forever. */
    suspend fun run() {
        for (w in queue) {
            val ok = try {
                persist(w)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "allowlist write failed", e)
                false
            }
            // In the same step, so [current] never goes without it.
            update {
                pending.remove(w)
                if (ok) landed += w
            }
        }
    }

    private fun AllowlistWrite.applyTo(list: Set<String>) = if (add) list + host else list - host

    // [onChange] under the lock too, so its readers see updates in order.
    private inline fun update(change: () -> Unit) = synchronized(this) {
        change()
        current = (landed + pending).fold(saved) { acc, w -> w.applyTo(acc) }
        onChange(current)
    }
}

/**
 * How long after [Adblock.start] requests may wait for the first engine
 * build (#192). About 1 s on the AVD in a debug build, ~0.5 s in a
 * release one; the rest is headroom for a slow device, and the most a
 * page's requests can stall on a cold start if the build never lands.
 */
internal const val FIRST_BUILD_WAIT_MS = 5_000L

/**
 * The first engine build, for the requests that arrive before it (#192):
 * a tab restored after process death, or opened by a VIEW intent, loads
 * straight away, inside the build. They wait for it, up to one deadline
 * shared by every waiter and fixed when the build starts ([open]) — so
 * however many requests queue up, a cold start stalls by at most
 * [waitMs] in all, not per request.
 *
 * Nothing waits before [open] (no build under way) or after [ready] (the
 * first build landed, engine or none). A later rebuild never waits: the
 * previous engine keeps answering until the swap.
 */
internal class FirstBuildGate(
    private val waitMs: Long = FIRST_BUILD_WAIT_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val latch = CountDownLatch(1)

    /** Completed with [latch], for [awaitSuspending]: waiting on it parks no thread. */
    private val landed = CompletableDeferred<Unit>()

    /** When waiting stops, on [clock]; 0 before [open]. */
    @Volatile
    private var deadline = 0L

    /** The first build has started. Idempotent. */
    fun open() {
        if (deadline == 0L) deadline = clock() + waitMs
    }

    /** The first build has landed. */
    fun ready() {
        latch.countDown()
        landed.complete(Unit)
    }

    val isReady: Boolean get() = latch.count == 0L

    /** [isReady], or [open] never called (nothing will land). */
    val landedOrUnstarted: Boolean get() = isReady || deadline == 0L

    /**
     * Would a caller wait now: the build started, hasn't landed, and the
     * deadline hasn't passed?
     */
    val pending: Boolean get() = !isReady && deadline != 0L && deadline > clock()

    /** Block until the first build lands or the deadline passes; true if it landed. For WebView's IO threads. */
    fun await(): Boolean {
        if (isReady) return true
        if (deadline == 0L) return false
        val left = deadline - clock()
        if (left <= 0) return false
        return try {
            latch.await(left, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * [await] for a coroutine (a frame's deferred cosmetic answer on the
     * main thread): suspends instead of blocking, so however many frames
     * wait, none holds a thread — not the main one, not one of
     * [Dispatchers.IO]'s.
     */
    suspend fun awaitSuspending(): Boolean {
        if (isReady) return true
        if (deadline == 0L) return false
        val left = deadline - clock()
        if (left <= 0) return false
        return withTimeoutOrNull(left) { landed.await() } != null
    }
}


/**
 * What Settings shows about the engine: whether it's (re)building, its
 * filter count, and where its lists came from (#127) — the applied
 * update's feed version and build date (`null` when none is applied),
 * the lists (by [AdblockCategory.listName]) that update serves, and
 * those the bundled assets serve, because the update doesn't carry
 * them (or its copy no longer matches its hash) or its copy is older —
 * [newerBuiltInLists] is the subset of [builtInLists] for that last
 * reason only, [damagedLists] the subset whose update copy failed its
 * hash check.
 */
internal data class AdblockStatus(
    val loading: Boolean,
    val filterCount: Int,
    /** Per enabled category, its lists' rules and how many run (#318); empty while none is built. */
    val counts: Map<AdblockCategory, FilterListCounts> = emptyMap(),
    val listsVersion: Long? = null,
    val listsGeneratedAt: String? = null,
    val updatedLists: List<String> = emptyList(),
    val builtInLists: List<String> = emptyList(),
    val newerBuiltInLists: List<String> = emptyList(),
    val damagedLists: List<String> = emptyList(),
)

/** Where the engine's lists came from; see [AdblockStatus]. */
private data class AdblockListSources(
    val applied: AppliedUpdate?,
    val updated: List<String>,
    val builtIn: List<String>,
    val newerBuiltIn: List<String>,
    val damaged: List<String>,
    val counts: Map<AdblockCategory, FilterListCounts>,
)

/** What Settings shows about list updates (#127): a check under way, and how the last one ended. */
internal data class AdblockUpdateState(val checking: Boolean = false, val last: AdblockUpdateOutcome? = null)

/**
 * The ad blocker at runtime (#126): the [AdblockEngine] for the
 * categories switched on in Settings, and the per-site allowlist.
 *
 * The engine is compiled off the main thread at startup, and again
 * whenever the categories change or a list update lands (#127) — from
 * the applied update's lists where they are intact and newer than the
 * bundled ones, from the bundled lists otherwise. Until the first
 * build lands (about a second), requests wait for it, bounded by
 * [FIRST_BUILD_WAIT_MS] from startup ([FirstBuildGate], #192): a tab
 * restored after process death or opened by a VIEW intent loads inside
 * that window, and would otherwise go unfiltered. Past the deadline they
 * go through unfiltered rather than stall the page further. A later
 * rebuild never waits: the previous engine answers until the swap,
 * which is atomic — a request sees the old engine or the
 * new one. The allowlist is followed by its own collector, so a
 * rebuild never delays it, and the first engine waits for the first
 * allowlist read, so an allowlisted site is never blocked while
 * settings load.
 *
 * Allowlist changes take effect in memory at once and are written to
 * Settings one at a time, in the order they were made, by a single
 * writer ([AllowlistStore]); until a read of Settings shows a write,
 * it stays on top of that read, so neither a stale read nor the gap
 * before the next one undoes a newer change.
 *
 * Private tabs (#86) see the saved allowlist, but a site they allow is
 * kept for the private session only, in memory, like their site
 * permissions — it never reaches the settings file.
 *
 * The lists update themselves over Swarm (#127, [runAdblockUpdate]):
 * [AUTO_UPDATE_FIRST_DELAY_MS] after start, then every
 * [AUTO_UPDATE_PERIOD_MS] while the app runs, and from Settings on
 * request. An applied update rebuilds the engine the same way a
 * category change does, so it takes effect without a restart.
 */
internal object Adblock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: AdblockEngine? = null

    @Volatile
    private var allowlist: Set<String> = emptySet()

    @Volatile
    private var privateAllowlist: Set<String> = emptySet()

    private val _status = MutableStateFlow(AdblockStatus(loading = true, filterCount = 0))
    val status: StateFlow<AdblockStatus> = _status.asStateFlow()

    /** Bumped whenever blocking decisions may change, so the page menu re-reads its row. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var started = false

    /** Set in [start]: where updates keep their lists. */
    @Volatile
    private var lists: AdblockListStore? = null

    /** Set in [start]: for the bundled lists' dates an update check compares against. */
    @Volatile
    private var appContext: Context? = null

    /** Bumped when an update lands, to rebuild the engine from it. */
    private val listsRevision = MutableStateFlow(0)

    private val _updateState = MutableStateFlow(AdblockUpdateState())
    val updateState: StateFlow<AdblockUpdateState> = _updateState.asStateFlow()

    /** One update check at a time: the scheduled one and a Settings tap share it. */
    private val updateMutex = Mutex()

    private val firstBuild = FirstBuildGate()

    /** Set in [start]; allowlist writes made before it queue up. */
    @Volatile
    private var settings: NodeSettings? = null

    private val store = AllowlistStore(
        persist = { w ->
            val s = checkNotNull(settings)
            if (w.add) s.addAdblockAllowlistHost(w.host) else s.removeAdblockAllowlistHost(w.host)
        },
        onChange = { list ->
            val changed = list != allowlist
            allowlist = list
            _revision.value++
            if (changed) scriptletsChanged()
        },
    )

    /**
     * Bumped whenever a tab's scriptlet scripts may have to change (#318):
     * a new engine, or an allowlist change. [TabScriptlets] rebuilds on it.
     */
    @Volatile
    var scriptletGeneration = 0
        private set

    /**
     * Per host, its scriptlet code for the current engine, `""` for none
     * ([scriptletCode]). Bounded: a long session's hosts don't stay on
     * the heap until the next engine build; a host dropped is rebuilt on
     * demand. Its own lock also guards [engine] swaps.
     */
    private val scriptletCodes = ScriptletCodeCache()

    /** Loaded once, with the first engine build. */
    @Volatile
    private var scriptletCatalog: ScriptletCatalog? = null

    private fun scriptletsChanged() {
        synchronized(scriptletCodes) { scriptletGeneration++ }
        TabScriptlets.refreshAll()
    }

    /** Start following Settings. Idempotent; call from the activity's `onCreate`. */
    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        val settings = NodeSettings.get(app)
        this.settings = settings
        val lists = AdblockListStore(File(app.filesDir, "adblock"))
        this.lists = lists
        appContext = app
        firstBuild.open()
        scope.launch { store.run() }
        val allowlistRead = CompletableDeferred<Unit>()
        scope.launch {
            settings.adblockAllowlist.distinctUntilChanged().collect {
                store.saved(it)
                allowlistRead.complete(Unit)
            }
        }
        // Its own collector, so a rebuild never holds up allowlist reads.
        // Latest only: a newer category set abandons the build under way
        // (it checks in every thousand lines), so quick toggles don't
        // compile each stale set in turn before the last one applies.
        // An applied list update (#127) rebuilds it too.
        scope.launch {
            combine(settings.adblockCategories.distinctUntilChanged(), listsRevision) { categories, _ -> categories }
                .collectLatest { categories ->
                    _status.value = _status.value.copy(loading = true)
                    val built = withContext(Dispatchers.IO) { build(app, lists, categories) { ensureActive() } }
                    // An allowlisted site is never blocked while settings load.
                    allowlistRead.await()
                    synchronized(scriptletCodes) {
                        engine = built?.first
                        scriptletCodes.clear()
                    }
                    scriptletsChanged()
                    val sources = built?.second
                    _status.value = AdblockStatus(
                        loading = false,
                        filterCount = built?.first?.filterCount ?: 0,
                        counts = sources?.counts.orEmpty(),
                        listsVersion = sources?.applied?.version,
                        listsGeneratedAt = sources?.applied?.generatedAt,
                        updatedLists = sources?.updated.orEmpty(),
                        builtInLists = sources?.builtIn.orEmpty(),
                        newerBuiltInLists = sources?.newerBuiltIn.orEmpty(),
                        damagedLists = sources?.damaged.orEmpty(),
                    )
                    _revision.value++
                    firstBuild.ready()
                }
        }
        scope.launch {
            settings.adblockAutoUpdate.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                delay(AUTO_UPDATE_FIRST_DELAY_MS)
                while (true) {
                    val sinceLast = System.currentTimeMillis() - lastCheck(lists)
                    if (sinceLast in 0 until AUTO_UPDATE_PERIOD_MS) delay(AUTO_UPDATE_PERIOD_MS - sinceLast)
                    val outcome = checkForUpdatesNow()
                    // Feed unavailable doesn't count as a check (the node
                    // may still be finding peers): try again soon.
                    if (outcome == AdblockUpdateOutcome.FeedUnavailable) delay(AUTO_UPDATE_RETRY_MS)
                }
            }
        }
    }

    /**
     * The engine for [categories] and where its lists came from. Each
     * list is the applied update's
     * copy when it has one that still matches its hash and isn't older
     * than the bundled asset ([updatedListIsNewer]), else the bundled
     * asset.
     */
    private fun build(
        context: Context,
        lists: AdblockListStore,
        categories: Set<AdblockCategory>,
        checkpoint: () -> Unit,
    ): Pair<AdblockEngine, AdblockListSources>? {
        if (categories.isEmpty()) return null
        val t0 = SystemClock.elapsedRealtime()
        val applied = lists.applied()
        val fromUpdate = ArrayList<String>()
        val fromBundle = ArrayList<String>()
        val bundleNewer = ArrayList<String>()
        val damaged = ArrayList<String>()
        val catalog = scriptletCatalog ?: runCatching {
            context.assets.open("adblock/$SCRIPTLET_RESOURCES_FILE").bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "scriptlet resources unreadable", it) }.getOrNull()
            ?.let(ScriptletCatalog::parse)?.also { scriptletCatalog = it }
        // Which category each list compiled belongs to, for its counts.
        val owners = ArrayList<AdblockCategory>()
        val texts = AdblockCategory.entries.filter { it in categories }.flatMap { category ->
            checkpoint()
            val extras = category.bundledExtras.mapNotNull { extra ->
                runCatching {
                    context.assets.open("adblock/${extra.file}").bufferedReader().use { it.readText() }
                }.onFailure { Log.w(TAG, "list ${extra.file} unreadable", it) }.getOrNull()
                    ?.let { FilterListText(it, extra.trustedScriptlets) }
            }
            val main = mainListText(context, lists, category, applied, fromUpdate, fromBundle, bundleNewer, damaged)
            val out = listOfNotNull(main?.let { FilterListText(it) }) + extras
            repeat(out.size) { owners += category }
            out
        }
        if (texts.isEmpty()) return null
        val built = AdblockEngine.build(texts, catalog, checkpoint)
        val counts = HashMap<AdblockCategory, FilterListCounts>()
        built.listCounts.forEachIndexed { i, c -> counts[owners[i]] = counts[owners[i]]?.plus(c) ?: c }
        Log.i(
            TAG,
            "engine ready: ${categories.joinToString { it.key }}, ${built.filterCount} filters " +
                "(update ${applied?.version}: $fromUpdate, bundled: $fromBundle; " +
                "scriptlets ${counts.values.sumOf { it.scriptletsUsed }}/${counts.values.sumOf { it.scriptlets }}) " +
                "in ${SystemClock.elapsedRealtime() - t0} ms",
        )
        return built to AdblockListSources(applied, fromUpdate, fromBundle, bundleNewer, damaged, counts)
    }

    /**
     * [category]'s main list: the applied update's copy when it has one
     * that still matches its hash and isn't older than the bundled asset
     * ([updatedListIsNewer]), else the bundled asset; noting which in the
     * lists given.
     */
    private fun mainListText(
        context: Context,
        lists: AdblockListStore,
        category: AdblockCategory,
        applied: AppliedUpdate?,
        fromUpdate: MutableList<String>,
        fromBundle: MutableList<String>,
        bundleNewer: MutableList<String>,
        damaged: MutableList<String>,
    ): String? {
        // A missing or unreadable list costs its own category only.
        val bundled = runCatching {
            context.assets.open("adblock/${category.file}").bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "list ${category.file} unreadable", it) }.getOrNull()
        lists.updatedList(category.key)?.let { (text, update) ->
            // The bundled lists are the floor: an update older than
            // them (a newer APK, a stalled publisher) doesn't serve.
            if (bundled == null || updatedListIsNewer(text, bundled)) {
                fromUpdate += category.listName
                return text
            }
            Log.i(TAG, "bundled ${category.file} is newer than update ${update.version}'s; using it")
            bundleNewer += category.listName
        } ?: run {
            // The update carries it, but its copy failed the hash check.
            if (applied?.lists?.containsKey(category.key) == true) damaged += category.listName
        }
        if (bundled != null) fromBundle += category.listName
        return bundled
    }

    /**
     * The date in the header of [categoryKey]'s bundled list, epoch
     * minutes, or `null` (no such category, no date, unreadable).
     */
    private fun bundledListTime(context: Context, categoryKey: String): Long? {
        val category = AdblockCategory.entries.firstOrNull { it.key == categoryKey } ?: return null
        return runCatching {
            context.assets.open("adblock/${category.file}").bufferedReader().use { r ->
                filterListTimestamp(r.lineSequence().take(50).joinToString("\n"))
            }
        }.getOrNull()
    }

    /** Settings' "Check for updates": run a check now, whatever the schedule. */
    fun checkForUpdates() {
        scope.launch { checkForUpdatesNow() }
    }

    private suspend fun checkForUpdatesNow(): AdblockUpdateOutcome {
        val lists = lists
        val settings = settings
        val app = appContext
        if (lists == null || settings == null || app == null) {
            // Only before [start]; say so rather than leave the row blank.
            val outcome = AdblockUpdateOutcome.Failed(Text.res(R.string.settings_adblock_not_started))
            _updateState.value = AdblockUpdateState(checking = false, last = outcome)
            return outcome
        }
        return updateMutex.withLock {
            _updateState.value = _updateState.value.copy(checking = true)
            try {
                runAndRecordAdblockCheck(
                    context = Dispatchers.IO,
                    check = {
                        val enabled = runCatching { settings.adblockCategories.first() }.getOrDefault(emptySet())
                        Gateways.awaitExternalEndpoints()
                        val base = Gateways.swarmBase
                        runAdblockUpdate(
                            store = lists,
                            enabled = enabled.mapTo(HashSet()) { it.key },
                            signer = AdblockFeed.SIGNER,
                            readFeed = { readAdblockFeed(base) },
                            download = { ref, max -> downloadSwarmBytes(base, ref, max) },
                            bundledTime = { bundledListTime(app, it) },
                            activate = { listsRevision.value++ },
                        )
                    },
                    record = { outcome ->
                        if (outcome != AdblockUpdateOutcome.FeedUnavailable) stampLastCheck(lists)
                        Log.i(TAG, "update check: $outcome")
                        _updateState.value = AdblockUpdateState(checking = false, last = outcome)
                    },
                )
            } catch (e: CancellationException) {
                // Cancelled before an outcome (or after one was recorded):
                // the row keeps the last one it has.
                _updateState.value = _updateState.value.copy(checking = false)
                throw e
            }
        }
    }

    /**
     * When the last check that counts ran: the stamp on disk (so a
     * restart doesn't check again at once), or this process's own
     * record of it if that's later — a stamp that can't be written
     * (full or read-only storage) must not make the schedule loop
     * check back to back.
     */
    private fun lastCheck(lists: AdblockListStore): Long = maxOf(
        lastCheckInMemory,
        runCatching { File(lists.root, LAST_CHECK).readText().trim().toLong() }.getOrDefault(0L),
    )

    @Volatile
    private var lastCheckInMemory = 0L

    private fun stampLastCheck(lists: AdblockListStore) {
        lastCheckInMemory = System.currentTimeMillis()
        runCatching {
            lists.root.mkdirs()
            File(lists.root, LAST_CHECK).writeText(System.currentTimeMillis().toString())
        }
    }

    private const val LAST_CHECK = "last-check"

    /** Let the Swarm node find peers before the first feed read. */
    private const val AUTO_UPDATE_FIRST_DELAY_MS = 45_000L

    /** Lists move daily-ish and carry 1–4 day expiries: every six hours keeps them fresh. */
    private const val AUTO_UPDATE_PERIOD_MS = 6 * 60 * 60_000L

    /** After an unreadable feed (node not up yet, no peers). */
    private const val AUTO_UPDATE_RETRY_MS = 15 * 60_000L

    /**
     * The page menu switch's state for the page [pageUrl] in a tab that
     * is [private] or not, or `null` where the menu has no switch
     * ([adblockSiteFor]).
     */
    fun siteState(pageUrl: String, private: Boolean): AdblockSiteState? {
        val site = adblockSiteFor(pageUrl) ?: return null
        return adblockSiteState(pageUrl, site, isAllowlisted(site, private), engine, _status.value.loading)
    }

    /** Is ad blocking off for [host] in a tab that is [private] or not? */
    fun isAllowlisted(host: String?, private: Boolean): Boolean =
        isAllowlisted(host, allowlist) || (private && isAllowlisted(host, privateAllowlist))

    /**
     * Allow ([allowed] true) or block ads on [host] again, from the
     * page menu of a tab that is [private] or not. Takes effect for the
     * next request at once — the caller reloads right after — and is
     * saved in the background. A private tab's allow is kept for the
     * private session only; lifting an allow saved from a normal tab
     * lifts it for good, from either.
     */
    fun setAllowlisted(host: String, allowed: Boolean, private: Boolean) {
        val entry = normalizeAllowlistHost(host) ?: return
        val before = privateAllowlist
        if (allowed) {
            if (private) privateAllowlist = privateAllowlist + entry
            else store.write(add = true, host = entry)
        } else {
            // Every entry covering the host, so the page really is blocked again.
            val covering = { list: Set<String> -> list.filter { isAllowlisted(entry, setOf(it)) }.toSet() }
            privateAllowlist = privateAllowlist - covering(privateAllowlist)
            covering(allowlist).forEach { store.write(add = false, host = it) }
        }
        _revision.value++
        if (privateAllowlist != before) scriptletsChanged()
    }

    /** Settings' remove button: exactly the saved entry [site], its parents and children kept. */
    fun removeAllowlisted(site: String) {
        store.write(add = false, host = site)
        _revision.value++
    }

    /** The last private tab has closed: forget what it allowed. */
    fun onPrivateSessionEnded() {
        val had = privateAllowlist.isNotEmpty()
        privateAllowlist = emptySet()
        _revision.value++
        if (had) scriptletsChanged()
    }

    /**
     * The scriptlet code (#318) a frame on [host] runs with the current
     * engine ([ScriptletCatalog.code]), or `null` for none: no engine, a
     * host never filtered, or no rules for it. Memoised per engine; safe
     * from any thread.
     */
    fun scriptletCode(host: String): String? {
        if (isExempt(host)) return null
        val (e, catalog) = synchronized(scriptletCodes) { engine to scriptletCatalog }
        if (e == null || catalog == null) return null
        synchronized(scriptletCodes) { scriptletCodes[host] }?.let { return it.ifEmpty { null } }
        val calls = e.scriptletsFor(host)
        val code = if (calls.isEmpty()) "" else catalog.code(calls)
        synchronized(scriptletCodes) { if (engine === e) scriptletCodes.put(host, code) }
        return code.ifEmpty { null }
    }

    /**
     * The document-start script for frames on [host] in a tab that is
     * [private] or not ([scriptletFrameJs]), or `null` when there's none
     * to run. The tab's allowlist goes in it: a frame skips its
     * scriptlets when the page it's on is allowed.
     */
    fun scriptletScript(host: String, private: Boolean): String? {
        val code = scriptletCode(host) ?: return null
        val allowed = if (private) allowlist + privateAllowlist else allowlist
        return scriptletFrameJs(host, code, allowed)
    }

    /**
     * Should a subresource request be blocked? [pageUrl] is the tab's
     * top-level document, [private] whether the tab is private.
     * Called on WebView's IO threads for every request; never blocks a
     * main-frame load, the local nodes' gateways, or a virtual origin.
     * Before the first engine build lands, waits for it ([FirstBuildGate]).
     */
    fun shouldBlock(url: String, headers: Map<String, String>?, pageUrl: String?, private: Boolean): Boolean =
        blockedResponseFor(url, headers, pageUrl, private) != null

    /**
     * What a subresource request gets if it is to be blocked — the empty
     * 403, or the stand-in a `$redirect` filter names (#393) — or `null`
     * to let it through. As [shouldBlock], which it backs.
     */
    fun blockedResponseFor(
        url: String,
        headers: Map<String, String>?,
        pageUrl: String?,
        private: Boolean,
    ): WebResourceResponse? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val host = hostOfUrl(url) ?: return null
        if (isExempt(host) || Gateways.isLocalGateway(url)) return null
        if (engine == null) firstBuild.await()
        val e = engine ?: return null
        val pageHost = pageUrl?.let(::hostOfUrl)
        if (pageHost != null && isAllowlisted(pageHost, private)) return null
        val types = requestTypes(url, headers)
        if (!e.shouldBlock(url, host, types, pageUrl, pageHost)) return null
        val resource = e.redirectFor(url, host, types, pageHost)?.let { scriptletCatalog?.redirect(it) }
        return if (resource == null) blockedResponse() else redirectResponse(resource, headers)
    }

    /**
     * Is the first engine build still under way, within its deadline? A
     * frame's first cosmetic answer is then held back ([awaitFirstBuild])
     * rather than given as "no hiding here", which would stop the frame
     * asking for good.
     */
    val firstBuildPending: Boolean get() = firstBuild.pending

    /**
     * Has the first engine build landed — or was none ever started? Unlike
     * [firstBuildPending], stays `false` past the wait's deadline, for
     * work queued until the engine exists ([TabScriptlets]).
     */
    val firstBuildLanded: Boolean get() = firstBuild.landedOrUnstarted

    /** Block (a WebView network thread) until the first engine build lands or its deadline passes. */
    fun awaitFirstBuildBlocking() {
        if (engine == null) firstBuild.await()
    }

    /** Suspend until the first engine build lands or its deadline passes. */
    suspend fun awaitFirstBuild() {
        firstBuild.awaitSuspending()
    }

    /** The first CSS for a frame on [frameOrigin], or `null` when there's to be no hiding there. */
    fun initialCosmetics(frameOrigin: String, pageUrl: String?, private: Boolean): String? {
        val (e, frameHost, pageHost) = cosmeticContext(frameOrigin, pageUrl, private) ?: return null
        if (pageUrl != null && e.documentExempt(pageUrl, pageHost)) return null
        return e.initialCosmetics(frameOrigin, frameHost, pageUrl, pageHost)
    }

    /** The CSS for the generic rules keyed by [tokens] a frame on [frameOrigin] reported. */
    fun cosmeticsForTokens(tokens: Collection<String>, frameOrigin: String, pageUrl: String?, private: Boolean): String {
        val (e, frameHost, pageHost) = cosmeticContext(frameOrigin, pageUrl, private) ?: return ""
        return e.cosmeticsForTokens(tokens, frameOrigin, frameHost, pageUrl, pageHost)
    }

    private fun cosmeticContext(frameOrigin: String, pageUrl: String?, private: Boolean): Triple<AdblockEngine, String, String?>? {
        val e = engine ?: return null
        val frameHost = hostOfUrl(frameOrigin) ?: return null
        if (isExempt(frameHost)) return null
        val pageHost = pageUrl?.let(::hostOfUrl)
        if (isAllowlisted(pageHost ?: frameHost, private)) return null
        return Triple(e, frameHost, pageHost)
    }

    /** Loopback (the embedded nodes) and the virtual dweb origins are never filtered. */
    private fun isExempt(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "[::1]" || VirtualOrigin.isVirtualHost(host) ||
            host == RadUrl.HOST

    /**
     * A blocked request a `$redirect` filter names a stand-in for (#393):
     * the stand-in, as a 200 the page's element loads (an empty script
     * runs, a 1×1 GIF decodes) — the point of the filter, where a failed
     * load would break the page or trip its blocker check. A cross-origin
     * `fetch` / XHR may read it: it is our own no-op, not the site's.
     */
    private fun redirectResponse(resource: RedirectResource, headers: Map<String, String>?): WebResourceResponse {
        val responseHeaders = HashMap<String, String>()
        responseHeaders["Cache-Control"] = "no-store"
        val origin = headers?.entries?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
        if (origin != null) {
            responseHeaders["Access-Control-Allow-Origin"] = origin
            responseHeaders["Access-Control-Allow-Credentials"] = "true"
        }
        val text = resource.mimeType.startsWith("text/") || resource.mimeType.endsWith("javascript") ||
            resource.mimeType.endsWith("json") || resource.mimeType.endsWith("xml")
        return WebResourceResponse(
            resource.mimeType,
            if (text) "utf-8" else null,
            200,
            "OK",
            responseHeaders,
            ByteArrayInputStream(resource.body),
        )
    }

    /**
     * What a blocked request gets: an empty 403, so the page's element
     * fails the way it would behind any blocker (`onerror`, a rejected
     * cross-origin fetch) rather than loading an empty script.
     */
    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
}
