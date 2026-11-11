package baby.freedom.mobile.browser

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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

    /** Sets a tab's WebView [settings] to [mode] for this phone. */
    fun apply(context: Context, settings: WebSettings, privateTab: Boolean) {
        val mode = mode(featureSupported(), webViewMajor(context), Build.VERSION.SDK_INT, privateTab) ?: return
        set(settings, mode)
    }

    /** Sets [settings] to [mode], as [apply] does. */
    fun set(settings: WebSettings, mode: Int) {
        runCatching { WebSettingsCompat.setWebAuthenticationSupport(settings, mode) }
            .onFailure { Log.w(TAG, "WebAuthn mode not set", it) }
    }
}
