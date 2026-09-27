package baby.freedom.mobile.browser

import android.util.Log
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/**
 * The WebView profile private tabs (#86) run on.
 *
 * Android WebView has no off-the-record mode: cookies, DOM storage,
 * IndexedDB, service workers and the HTTP cache all belong to a
 * *profile*, and every profile keeps them in its own directory. What it
 * does have is multiple profiles ([WebViewFeature.MULTI_PROFILE]), so
 * private tabs get one of their own — nothing they store is visible to
 * normal tabs, and nothing normal tabs store is visible to them.
 *
 * Chromium never unloads a profile once a WebView has used it, and
 * refuses to delete a loaded one ("Cannot delete in-use profile") for
 * the rest of the process. So a private session ends in two steps:
 *
 *  1. When the last private tab closes, [discard] wipes the profile in
 *     place — cookies, site storage, geolocation grants; the caller has
 *     already cleared its HTTP cache through the last private WebView,
 *     which is the only handle on it — and retires its name.
 *  2. At the next start, before anything can load it, [discardLeftovers]
 *     deletes the retired profile's directory outright. This also
 *     covers a process that died with private tabs open.
 *
 * Every private session gets a fresh name (`private-<uuid>`), never
 * reused, so a private tab opened after [discard] starts on an empty
 * profile even if something survived the wipe.
 *
 * All of [ProfileStore] is main-thread only, so is everything here
 * except [cookieManager].
 */
object PrivateProfile {
    private const val TAG = "PrivateProfile"
    private const val PREFIX = "private-"

    /** The live session's profile, or null while no private tab is open. */
    private var current: Profile? = null

    @Volatile
    private var cookies: CookieManager? = null

    /** Can this WebView run private tabs? Without it the UI offers none. */
    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)

    /**
     * Put a freshly-constructed [webView] on the private session's
     * profile, starting a session if none is live. Must come before
     * anything else is done with the WebView (Chromium refuses a profile
     * change once it has been used).
     */
    @MainThread
    fun attach(webView: WebView) {
        val profile = current ?: startSession()
        WebViewCompat.setProfile(webView, profile.name)
    }

    private fun startSession(): Profile {
        val profile = ProfileStore.getInstance().getOrCreateProfile(PREFIX + UUID.randomUUID())
        current = profile
        cookies = profile.cookieManager
        // Service-worker fetches go through the virtual-origin
        // interceptor in private tabs too — the controller is per
        // profile, so the default one's client ([ServiceWorkerInterception])
        // doesn't cover it.
        if (ServiceWorkerInterception.isSupported()) {
            runCatching {
                profile.serviceWorkerController.setServiceWorkerClient(
                    object : ServiceWorkerClient() {
                        override fun shouldInterceptRequest(
                            request: WebResourceRequest,
                        ): WebResourceResponse? = interceptVirtualRequest(request)
                    },
                )
            }.onFailure { Log.w(TAG, "service-worker interception not installed", it) }
        }
        Log.i(TAG, "private session started")
        return profile
    }

    /**
     * The private session's cookie jar, for requests the app makes on a
     * private tab's behalf (downloads, image saves) and for
     * [CookieHygiene]. Null while no private tab is open. Any thread.
     */
    fun cookieManager(): CookieManager? = cookies

    /**
     * The last private tab has closed (the caller cleared the HTTP cache
     * through its WebView before destroying it): wipe the session's
     * cookies, site storage and geolocation grants, and retire the
     * profile. Its directory goes at the next start ([discardLeftovers]).
     * The next private tab starts a new session.
     */
    @MainThread
    fun discard() {
        val profile = current ?: return
        current = null
        cookies = null
        wipe(profile)
        Log.i(TAG, "private session ended")
    }

    /**
     * "Clear cookies & site data" while private tabs are open: the
     * private session's cookies and site storage go too. (Its cache
     * is cleared per WebView, with every other tab's.)
     */
    @MainThread
    fun clearData() {
        current?.let(::wipe)
    }

    private fun wipe(profile: Profile) {
        runCatching {
            val jar = profile.cookieManager
            jar.removeAllCookies { jar.flush() }
        }.onFailure { Log.w(TAG, "clearing private cookies failed", it) }
        runCatching { profile.webStorage.deleteAllData() }
            .onFailure { Log.w(TAG, "clearing private storage failed", it) }
        runCatching { profile.geolocationPermissions.clearAll() }
    }

    /**
     * Delete the directories of earlier private sessions: retired by
     * [discard] in an earlier process, or left behind by one that died
     * with private tabs open. Call at startup, before any private tab
     * exists — a profile no WebView has loaded yet is one Chromium will
     * delete.
     */
    @MainThread
    fun discardLeftovers() {
        if (!isSupported()) return
        val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
        val names = runCatching { store.allProfileNames }.getOrNull() ?: return
        for (name in names) {
            if (!name.startsWith(PREFIX) || name == current?.name) continue
            runCatching { store.deleteProfile(name) }
                .onSuccess { Log.i(TAG, "earlier private profile deleted") }
                // Loaded by this process already (a screen rebuilt after
                // its private session ended): wiped then, gone next start.
                .onFailure { Log.i(TAG, "earlier private profile still loaded; deleted next start") }
        }
    }
}
