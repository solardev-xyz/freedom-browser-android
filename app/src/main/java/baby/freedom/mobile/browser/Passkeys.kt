package baby.freedom.mobile.browser

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Passkeys (#524): WebAuthn in a tab's WebView, in *browser* mode.
 *
 * WebView's default ([WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE])
 * hides the API from pages altogether (`PublicKeyCredential` is
 * undefined). In browser mode
 * ([WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER]) a page's
 * `navigator.credentials` request goes to Android's Credential Manager
 * with the page's own origin, and the passkey manager the user picked in
 * Android settings answers it through the system's own sheet.
 *
 * It is on only where all of these hold — one decision ([mode]) that
 * both the tab's WebView and the Settings card ([availability]) read:
 *
 * - the WebView offers the setting ([WebViewFeature.WEB_AUTHENTICATION]);
 * - the WebView is version [MIN_WEBVIEW_MAJOR] or later (see below);
 * - Android 14 or later: Credential Manager honours an origin the
 *   calling browser sets only from API 34;
 * - not a private tab: a passkey created there would stay in the
 *   passkey manager after the private session ends, so private tabs
 *   keep the default, and the API stays hidden there.
 *
 * The setting is the whole WebView's, not a page's, so a tab in browser
 * mode offers WebAuthn to every page it shows — Freedom's own dweb
 * pages (`*.freedom.baby`) included. Those must not get it: until the
 * Public Suffix List names their parents, every dweb site shares
 * `freedom.baby` and could ask for passkeys scoped to it (`rp.id`
 * `freedom.baby`), another dweb site's or freedom.baby's own. Recent
 * WebViews refuse them by themselves: the page has no certificate,
 * and `AwContentBrowserClient::IsSecurityLevelAcceptableForWebAuthn`
 * (feature `WebViewWebAuthnRequiresSecureOrigin`) turns down WebAuthn on
 * a page whose security level isn't SECURE, `NotAllowedError` at once.
 * Older ones don't: on WebView 133 a dweb page's request, `rp.id`
 * `freedom.baby` included, reaches Credential Manager. So browser mode
 * waits for a WebView with that refusal: absent from Chromium 145, and
 * present and enabled by default at tag 155.0.8059.84, and seen
 * refusing dweb pages on Vanadium 155 (#524): the floor until a closer
 * version is confirmed. Below it, or when the version can't be read,
 * WebAuthn stays off.
 *
 * That refusal lets one kind of caller through: a host of this device
 * (`net::IsLocalhost`, so `localhost`, `*.localhost`, loopback
 * literals). The Swarm and IPFS gateways answer there too, and every
 * root loaded raw (`http://localhost:1633/bzz/<ref>/`) shares that one
 * origin, and `rp.id` `localhost` with every other port. So a tab is
 * also turned off, whatever the WebView, as soon as anything of its
 * document is on the device, and while the app is away, where a page's
 * script could otherwise raise the system's sheet over another app:
 * [PasskeyGate] (#524 R1-F1, R1-F2). Local development servers lose
 * passkeys with it.
 *
 * Not covered: that refusal judges the tab's *top-level* page. An https
 * page can embed a dweb page in an iframe and delegate it
 * `publickey-credentials-get`/`-create` with `allow=`. Whether the
 * WebView then still sees the top page as SECURE, and lets the dweb
 * frame's request through, is untested (#524). A dweb site's own
 * service worker can serve its documents with any headers, so a
 * `Permissions-Policy` on the documents Freedom serves wouldn't close
 * it either.
 */
internal object Passkeys {
    private const val TAG = "Passkeys"

    /** The oldest WebView major version that refuses WebAuthn on dweb pages (see above). */
    const val MIN_WEBVIEW_MAJOR = 155

    /** Where passkeys stand on this phone, for normal tabs. */
    enum class Availability {
        /** Normal tabs offer passkeys. */
        On,

        /** Android 13 or older: Credential Manager can't take a browser's origin. */
        NeedsAndroid14,

        /** This phone's WebView has no WebAuthn setting, or is older than [MIN_WEBVIEW_MAJOR]. */
        WebViewUnsupported,
    }

    /**
     * The value to give `setWebAuthenticationSupport`, or `null` when the
     * WebView doesn't offer the setting (calling it then throws, and the
     * WebView's default, no WebAuthn, stays).
     */
    fun mode(featureSupported: Boolean, webViewMajor: Int?, sdkInt: Int, privateTab: Boolean): Int? = when {
        !featureSupported -> null
        availability(featureSupported, webViewMajor, sdkInt) != Availability.On || privateTab ->
            WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
        else -> WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
    }

    /**
     * Android's version first: below 14 no WebView update helps, so
     * Settings mustn't suggest one.
     */
    fun availability(featureSupported: Boolean, webViewMajor: Int?, sdkInt: Int): Availability = when {
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> Availability.NeedsAndroid14
        !featureSupported || webViewMajor == null || webViewMajor < MIN_WEBVIEW_MAJOR ->
            Availability.WebViewUnsupported
        else -> Availability.On
    }

    /** The major version in a WebView's `versionName` ("155.0.8059.84" → 155), or `null`. */
    fun majorVersion(versionName: String?): Int? =
        versionName?.substringBefore('.')?.trim()?.toIntOrNull()

    /** [availability] on this phone. */
    fun availability(context: Context): Availability =
        availability(featureSupported(), webViewMajor(context), Build.VERSION.SDK_INT)

    private fun featureSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION) }.getOrDefault(false)

    private fun webViewMajor(context: Context): Int? =
        runCatching { majorVersion(WebViewCompat.getCurrentWebViewPackage(context)?.versionName) }.getOrNull()

    /** [mode] for a tab's WebView on this phone. */
    fun mode(context: Context, privateTab: Boolean): Int? =
        mode(featureSupported(), webViewMajor(context), Build.VERSION.SDK_INT, privateTab)

    /**
     * The mode a tab's WebView has right now, from what it may have at
     * all ([mode], `null` when the WebView has no setting): browser mode
     * only while the app is on screen ([appVisible]) and no frame of the
     * tab's document is on a host of this device ([deviceDocument]).
     */
    fun live(capable: Int?, appVisible: Boolean, deviceDocument: Boolean): Int? = when {
        capable == null -> null
        capable == WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER && appVisible && !deviceDocument ->
            WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
        else -> WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
    }

    /**
     * Whether a document at [url] is on a host of this device
     * ([reachesThisDevice]: `localhost`, `*.localhost`, `127.0.0.0/8`,
     * `[::1]` and the like), on any port. WebView exempts such a caller
     * from its secure-page check, and a passkey's `rp.id` `localhost`
     * spans every port, so the Swarm and IPFS gateways' own origin,
     * which every root loaded raw shares, would share it too.
     */
    fun onDevice(url: String?): Boolean =
        permissionOriginKey(url?.removePrefix("blob:"))?.let { deviceOriginPort(it) } != null

    /**
     * Whether [url] is an `http(s)` address, whose document has an origin
     * of its own. Any other (`about:blank`, `blob:`, `data:`) either
     * inherits its creator's origin or has none.
     */
    fun ownsOrigin(url: String?): Boolean =
        url != null && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    /**
     * The address a main-frame load the app starts at [url] heads for,
     * for [PasskeyGate.mainFrameRequested] (#526 R6-M1): [url] itself (an
     * address-bar submit, a bookmark, a retry), or for the chrome's Back
     * and Forward ([HISTORY_BACK_JS], [HISTORY_FORWARD_JS]) the history
     * entry [historyEntry] reads for that step; `null` for any other
     * `javascript:` URL, which loads nothing.
     */
    fun appLoadTarget(url: String, historyEntry: (steps: Int) -> String?): String? = when {
        url == HISTORY_BACK_JS -> historyEntry(-1)
        url == HISTORY_FORWARD_JS -> historyEntry(1)
        url.startsWith("javascript:", ignoreCase = true) -> null
        else -> url
    }

    /** Sets [settings] to [mode], as [PasskeyGate] does. */
    fun set(settings: WebSettings, mode: Int) {
        runCatching { WebSettingsCompat.setWebAuthenticationSupport(settings, mode) }
            .onFailure { Log.w(TAG, "WebAuthn mode not set", it) }
    }
}

/**
 * One tab WebView's WebAuthn mode, kept to [Passkeys.live] as the app
 * comes and goes from the screen and as the tab's documents load (#524).
 *
 * **Documents on this device** (R1-F1). Lowered before such a document
 * is answered, by whichever signal sees it first: a navigation, main
 * frame or iframe, to a host of this device ([Passkeys.onDevice]) at
 * `shouldOverrideUrlLoading` (where WebView calls it); the interceptor's document request (the tab's own, and a
 * service worker's, which holds every tab); a frame's detector speaking
 * at document start, by its browser-given origin (the one signal that
 * sees an iframe reached through a redirect hop); a pop-up opened by a
 * held page; a main-frame load the app starts itself (an address-bar
 * submit, a bookmark, `reload()`, Back and Forward: the tab's
 * `loadUrl`/`reload`/`goBack` family, R6-M1), by the address it heads
 * for, which a service worker may answer unseen by either of the first
 * two; and commit. A top-level redirect to the device lands in the
 * commit at the latest. Raised only at the commit of an `http(s)`
 * main-frame document off the device that had no such frame asked for
 * since its request. That document was made while the mode was down,
 * and WebView decides a document's WebAuthn as it makes it, so it has
 * none: the tab reloads it once, right after the commit
 * ([RaisedDocumentReload], R2-F1). The cost is a second load of the
 * first page after a held one; a POST result isn't resent (the reload
 * asks, and is told not to), and stays without WebAuthn until the user
 * reloads it. "Since its request" includes a device frame the outgoing
 * page asks for before the commit: the tab can't tell it from the
 * incoming document's own, so that document commits held, with no
 * WebAuthn and no reload, until reloaded by hand
 * ([DeviceDocumentHold], R5-M1). A `blob:` or `about:blank` document, which can
 * carry its creator's origin, keeps the tab held. The detector's word
 * travels on a pipe of its own, apart from WebAuthn's: an iframe
 * reached only through a redirect hop that calls WebAuthn in its very
 * first script may get its request in before the word lands. That race
 * is unmeasured (#524 R1).
 *
 * **The app away** (R1-F2). Lowered on the Activity's pause and raised on
 * its resume ([appVisible]): page script keeps running in the background,
 * and Credential Manager starts its sheet even then, over whatever app
 * is in front. Not on the pause the passkey sheet itself causes: it is
 * an activity over Freedom, started through Freedom's own
 * `startIntentSender` ([ownWindowStarting], [appPaused]), and a page's
 * request right after it settles must still go through. The stop lowers
 * the mode in any case. A request already in flight keeps the mode it
 * started with (Chromium reads it once per request, CredManHelper at
 * 155.0.8059.84). But a document that calls WebAuthn while lowered gets
 * nothing from it until it is reloaded: seen on WebView 133, a request
 * that never settles (and "A request is already pending" for every one
 * after it), or NotSupportedError for a page that first asked while
 * lowered. That is why tabs not on screen aren't lowered while the app
 * is: a page opened in the background that checks for passkeys as it
 * loads (webauthn.io does) would lose them. And a document that
 * commits while the app is away (a page still loading as the user
 * leaves, or one landing while the Activity is made again for a
 * configuration change it doesn't handle itself) is made without
 * WebAuthn: it is reloaded once as the app comes back and raises the
 * mode, as for a commit that raises it ([RaisedDocumentReload], R3-F1,
 * R3-M2).
 *
 * Everything runs on the main thread, the setter included: WebView
 * applies a settings change on its UI thread, blocking a caller from
 * any other until it has, so a lock held across it from both sides
 * would deadlock. A worker thread hands its request over ([fromWorker])
 * and, for a document on the device, waits until the mode is down
 * before the document is answered (and refuses it when the main thread
 * doesn't get there within [LOWER_WAIT_MS]).
 */
internal class PasskeyGate(private val settings: WebSettings, private val capable: Int?) {
    /** Whether anything of the tab's document is on the device. */
    val hold = DeviceDocumentHold()

    private var applied: Int? = null

    /** The reload owed to a document made while the mode was down; before [update] first runs. */
    private val reload = RaisedDocumentReload()

    init {
        update()
    }

    private val raised: Boolean
        get() = applied == WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER

    /** See [DeviceDocumentHold.openedBy]. Main thread. */
    fun openedBy(opener: PasskeyGate) {
        hold.openedBy(opener.hold)
        update()
    }

    /** See [DeviceDocumentHold.mainFrameRequested]. Main thread. */
    fun mainFrameRequested(url: String?) {
        hold.mainFrameRequested(url)
        update()
        reload.mainFrameRequested(url)
    }

    /** See [DeviceDocumentHold.frameRequested]. Main thread. */
    fun frameRequested(url: String?) {
        hold.frameRequested(url)
        update()
    }

    /**
     * See [DeviceDocumentHold.committed]. Main thread. A ticket for
     * [RaisedDocumentReload.take] when this commit raised the mode: the document it
     * committed was made while the mode was down, and has no WebAuthn
     * (R2-F1).
     */
    fun committed(url: String?): Int? {
        hold.committed(url)
        val before = raised
        committing = true
        try {
            update()
        } finally {
            committing = false
        }
        return reload.committed(madeLowered = !before, raisedNow = raised)
    }

    /**
     * [committed] for a main-frame document of [view]'s, reloading it
     * once, after this commit's callbacks, when the commit raised the
     * mode: still that document then, and the mode still up. A document
     * that commits while the mode stays down (the app away, R3-F1) is
     * reloaded the same way once the mode comes up ([update]). Main thread.
     */
    fun committed(url: String?, view: WebView) {
        page = view
        reloadFor(committed(url) ?: return)
    }

    /** The tab's WebView, from its first main-frame commit: the one [reloadFor] reloads. */
    private var page: WebView? = null

    /** Inside [committed]'s own [update], which hands out its ticket itself. */
    private var committing = false

    private fun reloadFor(ticket: Int) {
        val view = page ?: return
        // Not `view.post`, which waits for a tab in the background to be
        // attached to the window.
        main.post {
            if (!view.isDestroyed && reload.take(ticket, view.url, raised)) view.reload()
        }
    }

    private fun update() {
        val mode = Passkeys.live(capable, visible, hold.held) ?: return
        if (mode == applied) return
        val before = raised
        applied = mode
        Passkeys.set(settings, mode)
        // Raised outside a commit: the app back on screen (its resume, or
        // the Activity made again for a configuration change, R3-M2). A
        // document that committed while it was away was made without
        // WebAuthn and is reloaded once for it (R3-F1).
        if (!committing && !before && raised) reload.raised()?.let(::reloadFor)
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val gates = java.util.WeakHashMap<PasskeyGate, Unit>()

        /** How long a worker thread waits for the main thread to lower a mode. */
        private const val LOWER_WAIT_MS = 5_000L

        /** Whether the app is on screen ([appVisible]). Main thread. */
        private var visible = false

        /** Main thread. */
        fun register(gate: PasskeyGate) {
            gates[gate] = Unit
        }

        /** Main thread. */
        fun unregister(gate: PasskeyGate) {
            gates.remove(gate)
        }

        /** When Freedom last started a window of another app's over itself ([ownWindowStarting]). */
        private var ownWindowAt = Long.MIN_VALUE / 2

        /** How long after [ownWindowStarting] a pause is taken to be that window's. */
        private const val OWN_WINDOW_MS = 3_000L

        /**
         * Freedom is starting another app's window over itself: Credential
         * Manager's passkey sheet ([android.app.Activity.startIntentSender]).
         * Main thread.
         */
        fun ownWindowStarting() {
            ownWindowAt = SystemClock.uptimeMillis()
        }

        /**
         * The Activity paused: Freedom leaving the screen (Home, Recents,
         * another app), unless it is the sheet Freedom itself just
         * started ([ownWindowStarting]), which must keep the mode it
         * asked with. The stop lowers it either way. Main thread.
         */
        fun appPaused() {
            if (SystemClock.uptimeMillis() - ownWindowAt > OWN_WINDOW_MS) appVisible(false)
        }

        /** The app came on screen or left it (the Activity's resume, and pause or stop). Main thread. */
        fun appVisible(shown: Boolean) {
            if (visible == shown) return
            visible = shown
            gates.keys.toList().forEach { it.update() }
        }

        /**
         * Runs [step] on the main thread for a request at [url] seen on a
         * worker thread (the interceptor's, a service worker's). For a
         * document on the device, waits until it has run, so the mode is
         * down before the document is answered; `false` when the main
         * thread didn't get to it in time, and the document must then not
         * be answered at all.
         */
        fun fromWorker(url: String?, step: () -> Unit): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                step()
                return true
            }
            if (!Passkeys.onDevice(url)) {
                main.post(step)
                return true
            }
            val done = CountDownLatch(1)
            main.post {
                try {
                    step()
                } finally {
                    done.countDown()
                }
            }
            return done.await(LOWER_WAIT_MS, TimeUnit.MILLISECONDS)
        }

        /**
         * A service worker fetched a document at [url]: it belongs to no
         * one tab, so every tab is held to it. Any thread; `false` as for
         * [fromWorker].
         */
        fun workerDocumentRequested(url: String?): Boolean {
            if (!Passkeys.onDevice(url)) return true
            return fromWorker(url) { gates.keys.toList().forEach { it.frameRequested(url) } }
        }
    }
}

/**
 * Whether anything of a tab's document is on a host of this device
 * ([Passkeys.onDevice]), from its navigations, frames and commits; see
 * [PasskeyGate].
 */
internal class DeviceDocumentHold {
    /** The document on screen, or one of its frames, is on the device. */
    var held = false
        private set

    /**
     * A device frame or document was asked for since the last main-frame
     * request; the document that request commits is held for it.
     *
     * That counts the outgoing page's own frames too (R5-M1): one the
     * page still on screen asks for between the incoming request and its
     * commit holds the incoming document, which then commits at `NONE`
     * with no WebAuthn, and nothing reloads it (the mode stays down) until
     * the user reloads it by hand or a later clean navigation commits.
     * That holds for a page a service worker answers too: the app's own
     * loads (a reload, Back/Forward, a typed address) reach
     * [mainFrameRequested] from the tab itself, not only through the
     * interceptor the worker bypasses (R6-M1). A navigation the page
     * starts itself that WebView reports to neither callback, answered
     * by the worker, is still unseen: such a document commits held. The
     * tab can't tell the two apart: the incoming document's own frames
     * can be asked for in that same window (a preload scan beating
     * `onPageStarted`), and taking a frame for the outgoing page's would
     * let a device frame into a document with WebAuthn. So it fails
     * closed: a usability cost, never a hole.
     */
    private var since = false

    /**
     * A pop-up's: its first document (`about:blank`, written to by its
     * opener) has its opener's origin, so it starts held as [opener] is.
     */
    fun openedBy(opener: DeviceDocumentHold) {
        if (!opener.held && !opener.since) return
        held = true
        since = true
    }

    /** The tab asked for a new main-frame document at [url]. */
    fun mainFrameRequested(url: String?) {
        if (Passkeys.onDevice(url)) {
            since = true
            held = true
        } else if (Passkeys.ownsOrigin(url)) {
            since = false
        }
    }

    /** A frame (or a navigation the tab can't tell the frame of) asked for a document at [url]. */
    fun frameRequested(url: String?) {
        if (!Passkeys.onDevice(url)) return
        since = true
        held = true
    }

    /** A main-frame document at [url] committed. */
    fun committed(url: String?) {
        held = since || Passkeys.onDevice(url) ||
            // A document with no origin of its own (`about:blank`, a
            // `blob:` one) may have a device page's.
            (held && !Passkeys.ownsOrigin(url))
    }
}

/**
 * The one reload a document needs after the commit that raised its
 * tab's WebAuthn mode (#524 R2-F1); see [PasskeyGate].
 *
 * WebView decides whether a document has WebAuthn (`PublicKeyCredential`)
 * as it makes it, before `onPageStarted`, where the mode can first come
 * back up. So the first document off the device after a held one comes
 * up without it, and only a reload gives it the API. The mode isn't
 * raised any earlier, at the clean document's request, because the held
 * document is still on screen until the commit (for good, if the
 * navigation never commits: a `204`, a download, Stop), and nothing
 * would keep it, or a blank frame it makes, from WebAuthn meanwhile.
 *
 * Never for the commit of that reload itself: a page that frames
 * something on the device lowers the mode again after its commit,
 * and its reload's commit raises it again, which would reload forever.
 *
 * The same for a document that committed while the mode stayed down
 * for the app being away, once the app comes back ([raised], R3-F1).
 * Not for one made down whose commit happens only after the mode came
 * back up (the app returning in the instant between the two): the
 * commit is the earliest the tab hears of the document.
 */
internal class RaisedDocumentReload {
    private var tickets = 0

    /** The ticket of the reload due, 0 for none. */
    private var due = 0

    /** A reload was made, and its commit hasn't come. */
    private var reloading = false

    /**
     * The document on screen was made while the mode was down and hasn't
     * been reloaded for it since: a raise that isn't a commit's (the app
     * back on screen, R3-F1) owes it a reload.
     */
    private var lowered = false

    /**
     * A main-frame document committed: [madeLowered] if the mode was down
     * as it was made (before this commit's own update), [raisedNow] if
     * the mode is up after it. A ticket when it is owed a reload now.
     */
    fun committed(madeLowered: Boolean, raisedNow: Boolean): Int? {
        due = 0
        val ofReload = reloading
        reloading = false
        lowered = madeLowered
        if (!lowered || !raisedNow) return null
        // The commit of the reload itself raised the mode again (a page
        // that frames something on the device): no reload of its own.
        if (ofReload) {
            lowered = false
            return null
        }
        due = ++tickets
        return due
    }

    /**
     * The mode came up outside a commit. Only the app coming back on
     * screen does that, never the page itself, so the reload's own commit
     * may earn one here too: it can't loop. A ticket when the document on
     * screen is owed a reload.
     */
    fun raised(): Int? {
        if (!lowered) return null
        due = ++tickets
        return due
    }

    /**
     * The tab asked for a main-frame document: a reload not yet made is
     * superseded, and so is one the app's return would owe (a page that
     * went on to its next hop while the app was away, a sign-in's
     * redirect, mustn't have that navigation replaced by a reload). If
     * it never commits, the page stays without WebAuthn until reloaded
     * by hand.
     *
     * Only for a [url] WebView would make a document of (R4-M2): an
     * `intent:` or custom-scheme hop never leaves the page (refused
     * without a gesture, or handed to another app), so the page on
     * screen keeps what it is owed. An `http(s)` request that turns out
     * not to replace it (a download, an app link) still drops it: the
     * tab can't tell those apart here.
     */
    fun mainFrameRequested(url: String?) {
        if (!makesDocument(url)) return
        due = 0
        lowered = false
    }

    private fun makesDocument(url: String?): Boolean =
        Passkeys.ownsOrigin(url) ||
            DOCUMENT_SCHEMES.any { url?.startsWith(it, ignoreCase = true) == true }

    private companion object {
        /** Schemes besides `http(s)` a main-frame navigation can make a document of. */
        val DOCUMENT_SCHEMES = listOf("about:", "blob:", "data:")
    }

    /**
     * Whether to reload the view's document now for [ticket], with the
     * mode [raised] still. [url] is the view's address, `null` when it
     * has no document to reload. That it is still the document the
     * ticket was given for isn't read from [url] (a page's own
     * `pushState` moves it): every main-frame request for a document and
     * commit since drops the ticket ([mainFrameRequested], [committed]). The next
     * commit is then taken for that reload's, whatever its address (a
     * reload that redirects, to a new address each time, mustn't earn a
     * reload of its own): at worst, one the reload never reaches (a POST
     * result's, not resent) costs the next raising commit its reload.
     */
    fun take(ticket: Int, url: String?, raised: Boolean): Boolean {
        if (ticket == 0 || ticket != due) return false
        due = 0
        if (!raised || url == null) return false
        lowered = false
        reloading = true
        return true
    }
}
