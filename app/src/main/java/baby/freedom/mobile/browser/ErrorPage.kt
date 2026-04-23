package baby.freedom.mobile.browser

import java.net.URLEncoder

/**
 * URL builder for the in-app error page at
 * `file:///android_asset/error/error.html`.
 *
 * Called from two sides:
 *  - [BrowserScreen]'s submit flow, when [GatewayProbe] returns
 *    `Unreachable` / `NotFound` or the node isn't running yet.
 *  - [BrowserWebView]'s `onReceivedError` / `onReceivedHttpError`
 *    overrides, to replace Chromium's built-in error page when a
 *    top-level gateway load itself fails after navigation had started.
 *
 * Query-parameter contract matches `freedom-browser`'s desktop
 * `pages/error.html`:
 *  - `error`   — error code string (`swarm_content_not_found`,
 *                `ERR_CONNECTION_REFUSED`, raw Chromium error, …).
 *  - `url`     — what to show in the body; pass the user-facing
 *                display URL (`ens://foo.eth/p` or `bzz://…`) so it
 *                matches the address bar.
 *  - `protocol` — optional hint (`swarm` | `ipfs` | `ipns`). Mobile
 *                currently always passes `swarm`; IPFS lands under
 *                port-plan §2.
 *  - `retry`   — optional URL the "Try Again" button navigates to.
 *                Should be a scheme the WebView can resolve — pass
 *                the `bzz://<hash>` or `ens://<name>` form, which
 *                [BrowserWebView.shouldOverrideUrlLoading] will route
 *                back through [BrowserScreen]'s submit flow so the
 *                probe runs again.
 */
object ErrorPage {
    const val URL: String = "file:///android_asset/error/error.html"

    fun url(
        errorCode: String,
        displayUrl: String,
        protocol: String? = null,
        retryUrl: String? = null,
    ): String {
        val params = buildList {
            add("error=${encode(errorCode)}")
            add("url=${encode(displayUrl)}")
            if (protocol != null) add("protocol=${encode(protocol)}")
            if (retryUrl != null) add("retry=${encode(retryUrl)}")
        }
        return "$URL?${params.joinToString("&")}"
    }

    fun isErrorPage(url: String?): Boolean = url != null && url.startsWith(URL)

    // Percent-encode a single URL component. [URLEncoder] uses `application/
    // x-www-form-urlencoded`, which encodes ' ' as '+' rather than '%20';
    // that's fine for our query-string use but we flip it back so the
    // error-page script decodes displayable URLs cleanly.
    private fun encode(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
