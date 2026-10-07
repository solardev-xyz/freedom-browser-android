package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import java.net.URLDecoder
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
 *  - `protocol` — optional hint (`swarm` | `ens` | `ipfs` | `ipns` | `web3`).
 *                Drives the page's copy (e.g. `ens` failures render
 *                "ENS name has no content" instead of the generic
 *                Swarm not-found message).
 *  - `retry`   — optional URL the "Try Again" button navigates to.
 *                Should be a scheme the WebView can resolve — pass
 *                the `bzz://<hash>` or `ens://<name>` form, which
 *                [BrowserWebView.shouldOverrideUrlLoading] will route
 *                back through [BrowserScreen]'s submit flow so the
 *                probe runs again.
 *  - `detail`  — optional free-form text appended to the details
 *                block (e.g. the resolver reason `NO_RESOLVER` or a
 *                contenthash codec tag).
 *  - `continue` — optional URL a "Continue once" button navigates to:
 *                the not-cross-checked ENS warning's way on (#96,
 *                [EnsGate.continueUrl]).
 *  - `resolved` — `ens_wrong_protocol` only: the transport the name
 *                does resolve to (#97), for "resolves to IPFS, not
 *                Swarm" and a button that opens it there.
 *
 * The page's text comes from string resources (#280). WebView loads
 * `file:///android_asset/` itself, without asking the interceptor, so
 * the app can't serve the page with its strings in it. Instead, once an
 * error page has committed, [BrowserWebView] runs [stringsScript] in it:
 * a JSON table of the `errorpage_` strings the page uses ([stringsJson]),
 * handed to the page's own `window.__errorPageStrings`. Nothing is served
 * or exposed to other pages; the page shows nothing until the table
 * arrives, or — should it never come — shows its built-in English copy
 * of [stringsJson] after 1.5 s (kept equal by `ErrorPageStringsTest`).
 */
object ErrorPage {
    const val URL: String = "file:///android_asset/error/error.html"

    fun url(
        errorCode: String,
        displayUrl: String,
        protocol: String? = null,
        retryUrl: String? = null,
        detail: String? = null,
        continueUrl: String? = null,
        /**
         * `ens_wrong_protocol` only (#97): the transport the name does
         * resolve to, for the page's "resolves to X, not Y" and the
         * button that opens it there.
         */
        resolvedProtocol: String? = null,
    ): String {
        val params = buildList {
            add("error=${encode(errorCode)}")
            add("url=${encode(displayUrl)}")
            if (protocol != null) add("protocol=${encode(protocol)}")
            if (retryUrl != null) add("retry=${encode(retryUrl)}")
            if (detail != null) add("detail=${encode(detail)}")
            if (continueUrl != null) add("continue=${encode(continueUrl)}")
            if (resolvedProtocol != null) add("resolved=${encode(resolvedProtocol)}")
        }
        return "$URL?${params.joinToString("&")}"
    }

    fun isErrorPage(url: String?): Boolean = url != null && url.startsWith(URL)


    /**
     * What `error.html`'s `fmt('key')` / `fmtNodes('key')` look up: each
     * key is its resource's name without the `errorpage_` prefix.
     */
    internal val PAGE_STRINGS: Map<String, Int> = linkedMapOf(
        "page_default_title" to R.string.errorpage_page_default_title,
        "page_default_description" to R.string.errorpage_page_default_description,
        "page_continue_once" to R.string.errorpage_page_continue_once,
        "page_try_again" to R.string.errorpage_page_try_again,
        "page_details" to R.string.errorpage_page_details,
        "ens_not_found_title" to R.string.errorpage_ens_not_found_title,
        "page_ens_not_found_tezos" to R.string.errorpage_page_ens_not_found_tezos,
        "page_ens_not_found_no_resolver" to R.string.errorpage_page_ens_not_found_no_resolver,
        "page_ens_not_found_unregistered" to R.string.errorpage_page_ens_not_found_unregistered,
        "ens_wrong_protocol_title" to R.string.errorpage_ens_wrong_protocol_title,
        "page_wrong_protocol_networks" to R.string.errorpage_page_wrong_protocol_networks,
        "page_open_on_network" to R.string.errorpage_page_open_on_network,
        "page_wrong_protocol_contenthash" to R.string.errorpage_page_wrong_protocol_contenthash,
        "page_wrong_protocol_website_record" to R.string.errorpage_page_wrong_protocol_website_record,
        "unsupported_format_title" to R.string.errorpage_unsupported_format_title,
        "page_unsupported_codec" to R.string.errorpage_page_unsupported_codec,
        "ens_invalid_name_title" to R.string.errorpage_ens_invalid_name_title,
        "ens_invalid_name_description" to R.string.errorpage_ens_invalid_name_description,
        "ens_name_too_long_title" to R.string.errorpage_ens_name_too_long_title,
        "ens_name_too_long_description" to R.string.errorpage_ens_name_too_long_description,
        "not_cross_checked_title" to R.string.errorpage_not_cross_checked_title,
        "page_ens_unverified" to R.string.errorpage_page_ens_unverified,
        "rpc_disagreed_title" to R.string.errorpage_rpc_disagreed_title,
        "page_ens_conflict" to R.string.errorpage_page_ens_conflict,
        "lookup_failed_title" to R.string.errorpage_lookup_failed_title,
        "ccip_disabled_description" to R.string.errorpage_ccip_disabled_description,
        "tezos_rpc_unreachable_description" to R.string.errorpage_tezos_rpc_unreachable_description,
        "ethereum_rpc_unreachable_description" to R.string.errorpage_ethereum_rpc_unreachable_description,
        "page_web3_unverified" to R.string.errorpage_page_web3_unverified,
        "page_web3_conflict" to R.string.errorpage_page_web3_conflict,
        "page_web3_unknown_chain_title" to R.string.errorpage_page_web3_unknown_chain_title,
        "page_web3_unknown_chain" to R.string.errorpage_page_web3_unknown_chain,
        "page_web3_not_an_app_title" to R.string.errorpage_page_web3_not_an_app_title,
        "page_web3_not_an_app" to R.string.errorpage_page_web3_not_an_app,
        "page_web3_too_large_title" to R.string.errorpage_page_web3_too_large_title,
        "page_web3_too_large" to R.string.errorpage_page_web3_too_large,
        "page_web3_invalid_title" to R.string.errorpage_page_web3_invalid_title,
        "page_web3_invalid" to R.string.errorpage_page_web3_invalid,
        "page_web3_lookup_failed_title" to R.string.errorpage_page_web3_lookup_failed_title,
        "page_web3_lookup_failed" to R.string.errorpage_page_web3_lookup_failed,
        "cert_title" to R.string.errorpage_cert_title,
        "cert_description_expired" to R.string.errorpage_cert_description_expired,
        "cert_description_not_yet_valid" to R.string.errorpage_cert_description_not_yet_valid,
        "cert_description_date_invalid" to R.string.errorpage_cert_description_date_invalid,
        "cert_description_wrong_host" to R.string.errorpage_cert_description_wrong_host,
        "cert_description_untrusted" to R.string.errorpage_cert_description_untrusted,
        "cert_description_invalid" to R.string.errorpage_cert_description_invalid,
        "cert_description_risk" to R.string.errorpage_cert_description_risk,
        "page_swarm_not_ready_title" to R.string.errorpage_page_swarm_not_ready_title,
        "page_swarm_not_ready" to R.string.errorpage_page_swarm_not_ready,
        "page_swarm_not_found_detail" to R.string.errorpage_page_swarm_not_found_detail,
        "page_swarm_node_stopped" to R.string.errorpage_page_swarm_node_stopped,
        "page_swarm_gateway_unreachable" to R.string.errorpage_page_swarm_gateway_unreachable,
        "page_unknown_error" to R.string.errorpage_page_unknown_error,
    )

    /**
     * [PAGE_STRINGS] as a JSON object literal of their text (unformatted:
     * the page fills in `%1$s` itself), safe inside a `<script>`
     * element: `<`, `>`, `&` and the JS line separators are `\u`-escaped.
     */
    internal fun stringsJson(text: (Int) -> String = { Strings.get(it) }): String =
        PAGE_STRINGS.entries.joinToString(",", "{", "}") { (key, id) ->
            "${jsonString(key)}:${jsonString(text(id))}"
        }

    /**
     * What [BrowserWebView] runs in a committed error page: hands the
     * page [stringsJson]; parks it in `window.__errorPageTable` if the
     * page's script hasn't run yet (it picks it up when it does).
     */
    internal fun stringsScript(json: String = stringsJson()): String =
        "(window.__errorPageStrings||function(t){window.__errorPageTable=t;})($json);"

    private fun jsonString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<', '>', '&', '\u2028', '\u2029' -> append("\\u%04x".format(c.code))
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }


    /**
     * Pull the user-facing URL (the `url=` query param originally passed
     * to [url]) back out of an error-page URL.
     *
     * Used by [BrowserWebView] to keep the address bar on the URL the
     * user actually typed (`ens://foo.eth`, `bzz://…`) while the tab is
     * parked on the internal `file:///android_asset/error/error.html`
     * page — otherwise the raw asset path would leak into the address
     * bar and the user would lose the ability to edit-and-resubmit.
     */
    fun displayUrlFor(url: String?): String? = paramFor(url, "url")

    /** The [name] query param of an error-page URL; `null` if absent or not one. */
    fun paramFor(url: String?, name: String): String? {
        if (!isErrorPage(url)) return null
        val query = url!!.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            if (part.substring(0, eq) != name) continue
            return runCatching {
                // The `Charset` overloads of [URLDecoder] / [URLEncoder]
                // are API 33; `minSdk` is 30 and the library isn't
                // desugared, so on 30–32 they are a `NoSuchMethodError`
                // on the error page itself. The name overloads have
                // been there since API 1 and decode identically.
                URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.getOrNull()
        }
        return null
    }

    // Percent-encode a single URL component. [URLEncoder] uses `application/
    // x-www-form-urlencoded`, which encodes ' ' as '+' rather than '%20';
    // that's fine for our query-string use but we flip it back so the
    // error-page script decodes displayable URLs cleanly.
    //
    // Name overload, not `Charsets.UTF_8` — see [displayUrlFor].
    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
