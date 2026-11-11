package baby.freedom.mobile.browser

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
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
 * held page; and commit. A top-level redirect to the device lands in the
 * commit at the latest. Raised only at the commit of an `http(s)`
 * main-frame document off the device that had no such frame asked for
 * since its request. A `blob:` or `about:blank` document, which can
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
 * loads (webauthn.io does) would lose them.
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

    init {
        update()
    }

    /** See [DeviceDocumentHold.openedBy]. Main thread. */
    fun openedBy(opener: PasskeyGate) {
        hold.openedBy(opener.hold)
        update()
    }

    /** See [DeviceDocumentHold.mainFrameRequested]. Main thread. */
    fun mainFrameRequested(url: String?) {
        hold.mainFrameRequested(url)
        update()
    }

    /** See [DeviceDocumentHold.frameRequested]. Main thread. */
    fun frameRequested(url: String?) {
        hold.frameRequested(url)
        update()
    }

    /** See [DeviceDocumentHold.committed]. Main thread. */
    fun committed(url: String?) {
        hold.committed(url)
        update()
    }

    private fun update() {
        val mode = Passkeys.live(capable, visible, hold.held) ?: return
        if (mode == applied) return
        applied = mode
        Passkeys.set(settings, mode)
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

    /** A device frame or document was asked for since the last main-frame request. */
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
