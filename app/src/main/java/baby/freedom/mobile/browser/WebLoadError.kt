package baby.freedom.mobile.browser

import android.webkit.WebViewClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Freedom's own pages for an ordinary web (http/https) load that fails
 * (#259) — the dweb side has [ErrorPage].
 *
 * Two kinds of failure, reaching the tab two different ways:
 *
 *  - A **network failure** (DNS, refused, reset, timeout, offline).
 *    Chromium commits it: the history entry is the failed URL, holding
 *    WebView's stock "Webpage not available" document. That entry is the
 *    right one — Reload of it retries the URL, Back from it goes to the
 *    page before, and a Back onto it later fetches the URL again — so
 *    only its *document* is replaced, in place ([netErrorPageScript]).
 *    Loading [ErrorPage] on top instead would push a second entry behind
 *    which the failed one sits, and Back would land on it and fail again.
 *
 *  - A **certificate error**. WebView asks `onReceivedSslError`; the
 *    answer is always cancel (no proceed-anyway, #259), and a cancelled
 *    navigation commits nothing — the previous page would stay on screen
 *    under no warning at all. There is no entry to put a page in, so the
 *    tab goes to [ErrorPage] (`error=cert_*`) as for a dweb failure:
 *    Back from it is the page before.
 */
internal enum class NetFailure { OFFLINE, NOT_FOUND, REFUSED, RESET, TIMED_OUT, INSECURE, OTHER }

/**
 * What kind of failure a main-frame `onReceivedError` is. [description]
 * is Chromium's own `net::ERR_…` name when it has one, which is more
 * specific than [errorCode]'s `WebViewClient.ERROR_*` bucket. [online]
 * is whether the device has a network with internet at all: with none,
 * every failure is "you're offline", whatever Chromium called it
 * (airplane mode often surfaces as a failed DNS lookup).
 */
internal fun netFailureFor(description: String?, errorCode: Int, online: Boolean): NetFailure {
    val name = description.orEmpty().uppercase(Locale.ROOT)
    if (!online || "ERR_INTERNET_DISCONNECTED" in name) return NetFailure.OFFLINE
    return when {
        "ERR_NAME_NOT_RESOLVED" in name || "ERR_NAME_RESOLUTION_FAILED" in name ||
            "ERR_ADDRESS_UNREACHABLE" in name -> NetFailure.NOT_FOUND
        "ERR_CONNECTION_REFUSED" in name -> NetFailure.REFUSED
        "ERR_CONNECTION_RESET" in name || "ERR_CONNECTION_CLOSED" in name ||
            "ERR_CONNECTION_ABORTED" in name || "ERR_EMPTY_RESPONSE" in name ||
            "ERR_CONNECTION_FAILED" in name -> NetFailure.RESET
        "TIMED_OUT" in name -> NetFailure.TIMED_OUT
        "ERR_CERT_" in name || "ERR_SSL_" in name || "ERR_BAD_SSL" in name -> NetFailure.INSECURE
        "ERR_" in name -> NetFailure.OTHER
        else -> when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> NetFailure.NOT_FOUND
            WebViewClient.ERROR_CONNECT -> NetFailure.REFUSED
            WebViewClient.ERROR_TIMEOUT -> NetFailure.TIMED_OUT
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> NetFailure.INSECURE
            else -> NetFailure.OTHER
        }
    }
}

private fun escHtml(t: String) =
    t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Title and description (HTML, [host] escaped) of the page for [failure]. */
internal fun netErrorCopy(failure: NetFailure, host: String): Pair<String, String> {
    val h = "<b>${escHtml(host)}</b>"
    return when (failure) {
        NetFailure.OFFLINE -> "You're offline" to
            "Your device isn't connected to the internet. Check Wi-Fi or mobile data, or turn " +
            "off airplane mode, then try again."
        NetFailure.NOT_FOUND -> "Site can't be found" to
            "Couldn't find the server at $h. Check the address for typos. If it's right, the " +
            "site may be gone, or your network's DNS may not be answering."
        NetFailure.REFUSED -> "Site refused the connection" to
            "$h is reachable, but nothing there accepted the connection. The site may be " +
            "down, or not serving on this address."
        NetFailure.RESET -> "Connection was interrupted" to
            "The connection to $h was cut off before the page arrived. This is often " +
            "temporary; try again."
        NetFailure.TIMED_OUT -> "Site took too long to respond" to
            "$h didn't answer in time. The site may be overloaded, or your connection slow."
        NetFailure.INSECURE -> "Connection isn't secure" to
            "Freedom couldn't set up a secure connection to $h, so nothing was loaded. The " +
            "site may be misconfigured, or someone may be interfering with the connection."
        NetFailure.OTHER -> "Couldn't load this page" to
            "Something went wrong loading $h."
    }
}

/**
 * The page that stands in for Chromium's own on a failed load of [url]:
 * [inPlaceErrorPageHtml] with the address and Chromium's error name in
 * the details box. Try again is a link to [url] itself — a same-URL
 * navigation replaces the failed entry, and it's always a GET, so a
 * failed form POST is retried as a GET rather than resent (#259).
 */
internal fun netErrorPageHtml(url: String, host: String, failure: NetFailure, rawError: String?): String {
    val (title, description) = netErrorCopy(failure, host)
    val details = escHtml(url) + (rawError?.takeIf { it.isNotBlank() }?.let { "\n\n" + escHtml(it) } ?: "")
    return inPlaceErrorPageHtml(title, description, details, retryHref = escHtml(url))
}

/**
 * Script that swaps a failed load's document for [html] — only if the
 * document it lands in is Chromium's error page (`chrome-error:`), so a
 * script that arrives late, after another document has committed, never
 * touches a real page. Once swapped, a second run leaves it alone.
 */
internal fun netErrorPageScript(html: String): String =
    "(function(){if(location.protocol!=='chrome-error:')return false;" +
        "if(document.querySelector('meta[name=\"error-page\"]'))return true;" +
        "document.open();document.write(${JSONObject.quote(html.replaceFirst("<head>", "<head><meta name=\"error-page\">"))});" +
        "document.close();return true})()"

/**
 * What an `SslError` says about a certificate, as plain values
 * (`android.net.http.SslError` isn't constructible in a JVM test).
 * [errors] are its `SslError.SSL_*` flags.
 */
internal data class CertFacts(
    val errors: Set<Int>,
    val notBeforeMs: Long?,
    val notAfterMs: Long?,
    val issuedTo: String?,
    val issuedBy: String?,
)

// `android.net.http.SslError` constants, repeated so this file stays JVM-testable.
internal const val SSL_NOTYETVALID = 0
internal const val SSL_EXPIRED = 1
internal const val SSL_IDMISMATCH = 2
internal const val SSL_UNTRUSTED = 3
internal const val SSL_DATE_INVALID = 4

/**
 * The [ErrorPage] code for a certificate error: the one problem the page
 * leads with. Chromium often reports an expired certificate as the
 * generic `SSL_DATE_INVALID`, so the dates themselves decide expired vs
 * not-yet-valid.
 */
internal fun certErrorCode(facts: CertFacts, nowMs: Long): String {
    val e = facts.errors
    val dates = SSL_EXPIRED in e || SSL_NOTYETVALID in e || SSL_DATE_INVALID in e
    return when {
        SSL_IDMISMATCH in e -> "cert_wrong_host"
        SSL_UNTRUSTED in e -> "cert_untrusted"
        dates && (SSL_EXPIRED in e || (facts.notAfterMs != null && nowMs > facts.notAfterMs)) -> "cert_expired"
        dates && (SSL_NOTYETVALID in e || (facts.notBeforeMs != null && nowMs < facts.notBeforeMs)) ->
            "cert_not_yet_valid"
        dates -> "cert_date_invalid"
        else -> "cert_invalid"
    }
}

/** Every problem with the certificate, one per line, then who it names — the page's details box. */
internal fun certErrorDetail(facts: CertFacts, host: String, nowMs: Long): String {
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun day(ms: Long) = fmt.format(Date(ms))
    val e = facts.errors
    val dates = SSL_EXPIRED in e || SSL_NOTYETVALID in e || SSL_DATE_INVALID in e
    return buildList {
        if (SSL_IDMISMATCH in e) add("Not issued for $host")
        if (SSL_UNTRUSTED in e) add("Issuer not trusted by this device")
        if (dates) {
            val after = facts.notAfterMs
            val before = facts.notBeforeMs
            when {
                after != null && nowMs > after -> add("Expired on ${day(after)}")
                before != null && nowMs < before -> add("Not valid until ${day(before)}")
                else -> add("Dates not valid")
            }
        }
        if (isEmpty()) add("Certificate not valid")
        add("")
        facts.issuedTo?.takeIf { it.isNotBlank() }?.let { add("Issued to: $it") }
        facts.issuedBy?.takeIf { it.isNotBlank() }?.let { add("Issued by: $it") }
    }.joinToString("\n").trimEnd()
}
