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
 *    under no warning at all. The refused load is then issued again —
 *    the same history step (Back/Forward) if it was one, or a load of
 *    the URL if not, on the URL the entry holds — where a redirect onto
 *    the bad certificate started ([certPageReissue]) — and this time the interceptor
 *    answers it itself with the "connection isn't secure" page
 *    ([CertRefusalSlot], [certErrorPageHtml]), which never reaches the
 *    network. So the page sits in the refused load's own entry: a
 *    refused Back or Forward keeps the history on both sides of it,
 *    rather than pushing a page on top that cuts off the entries ahead
 *    and leaves Back going round the refused entry forever.
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

/** Why the certificate failed, for the page's description ([certErrorCode]). */
private fun certReason(code: String): String = when (code) {
    "cert_expired" -> "has expired"
    "cert_not_yet_valid" ->
        "isn't valid yet. If your device's date and time are wrong, correct them and try again"
    "cert_date_invalid" -> "has invalid dates"
    "cert_wrong_host" -> "was issued for a different site"
    "cert_untrusted" -> "wasn't issued by an authority this device trusts"
    else -> "is not valid"
}

/**
 * The "connection isn't secure" page for a certificate error on [url]
 * (#259), served in the refused load's own entry ([CertRefusalSlot]):
 * [inPlaceErrorPageHtml], [host] and [url] escaped. Try again is a link
 * to [retryUrl] — the entry's own URL, [url] itself unless the load
 * redirected onto it ([CertReissue]) — which asks the site again (and
 * so WebView asks `onReceivedSslError` again — nothing is remembered).
 */
internal fun certErrorPageHtml(
    url: String,
    host: String,
    facts: CertFacts,
    nowMs: Long,
    retryUrl: String = url,
): String {
    val description = "Freedom didn't load <b>${escHtml(host)}</b> because its security certificate " +
        certReason(certErrorCode(facts, nowMs)) + ". Someone could be impersonating the site or " +
        "intercepting the connection, or the site is misconfigured."
    val details = escHtml(url) + "\n\n" + escHtml(certErrorDetail(facts, host, nowMs))
    return inPlaceErrorPageHtml("Connection isn't secure", description, details, retryHref = escHtml(retryUrl))
}

/**
 * How to issue a certificate-refused load again, so the page for it
 * lands in the entry the load was for ([certPageReissue]): the history
 * [step] to take — `0` for the entry on screen (a Reload, Try again),
 * `null` for a new navigation, whose page gets an entry of its own —
 * and the [url] to issue, which the page is served on: the entry's own
 * URL, which for a load that redirected onto the bad certificate is
 * the URL the redirect started from, not the one refused.
 */
internal data class CertReissue(val step: Int?, val url: String)

/**
 * The [CertReissue] for a certificate-refused load whose redirect
 * [chain] (the main-frame URLs from its start to the refused one,
 * [MainFrameChain]) ended in the refusal: the step from [currentIndex]
 * to the nearest entry of [entryUrls] (the tab's back/forward list)
 * holding any URL of the chain, or, if none does, a new load of the
 * chain's start.
 *
 * A refused load commits nothing, so the list still stands where it
 * did, and nothing says whether it was a Back/Forward. Taking a history
 * step for a URL the list already holds is the safe reading: a refused
 * Back or Forward read as a new navigation would push the page on top,
 * cutting off the entries ahead and leaving Back to go round the
 * refused entry again; a link to a page already in the list read as a
 * step merely shows the page in that entry. The whole chain counts, not
 * only the refused URL: an entry that redirects onto a bad certificate
 * (http → a self-signed https) holds the URL it started from, and a
 * refused Back onto it names only the redirect target (R2-F1).
 * Fragments aside — a request never carries one.
 *
 * [steppedTo] is the entry the app's own history step was headed for
 * (the chrome's Back / Forward, [PageWebView.historyStepTarget]): when
 * it holds a URL of the chain, that step is the one refused, and it is
 * taken again — a refused Forward onto a URL the list also holds one
 * step behind (X → N → O → N → D, Forward from O) must land in the
 * entry ahead, or Forward from the page goes back to O and D is out of
 * reach (R3-F1). Otherwise (a step the page took itself, a link) the
 * nearer entry wins; Back on a tie; the earlier URL of the chain on a
 * tie of both.
 */
internal fun certPageReissue(
    entryUrls: List<String?>,
    currentIndex: Int,
    chain: List<String>,
    steppedTo: Int? = null,
): CertReissue {
    val keys = chain.map { it.substringBefore('#') }
    fun holdsChain(i: Int) = entryUrls[i]?.substringBefore('#') in keys
    val index = steppedTo?.takeIf { it in entryUrls.indices && holdsChain(it) }
        ?: entryUrls.indices
            .filter(::holdsChain)
            .minWithOrNull(compareBy<Int>({ kotlin.math.abs(it - currentIndex) }, { it }))
        ?: return CertReissue(null, chain.first())
    val entry = entryUrls[index]!!.substringBefore('#')
    return CertReissue(index - currentIndex, chain.first { it.substringBefore('#') == entry })
}

/** [certPageReissue]'s step for a load of [refusedUrl] that didn't redirect. */
internal fun certPageStep(entryUrls: List<String?>, currentIndex: Int, refusedUrl: String): Int? =
    certPageReissue(entryUrls, currentIndex, listOf(refusedUrl)).step

/** A certificate refusal of [url] awaiting its load's finish, with the [chain] it ended ([MainFrameChain]). */
internal data class PendingCertError(val url: String, val facts: CertFacts, val chain: List<String>)

/**
 * The certificate refusals since the last commit, keyed by URL, each
 * awaiting its load's finish (#259 R4-F1). WebView's
 * `onReceivedSslError` doesn't say which frame a refusal is for, and
 * the page still on screen goes on loading while a navigation is in
 * flight — its own refused images, trackers, every https host on a
 * hostile network — so a single slot would be overwritten by whichever
 * subresource refusal landed between the main frame's refusal and its
 * finish, and the finish would find nothing to match. Only a main-frame
 * load gets a finish ([takeFor]); a subresource's refusal just sits here
 * until the next commit [clear]s it all.
 *
 * Bounded: a page refusing endless subresources can't grow it without
 * limit, and it drops its oldest refusal *not* on [MainFrameChain]'s
 * navigation in flight first, so a flood of those can't push the main
 * frame's out either. Main thread only.
 */
internal class PendingCertErrors(private val capacity: Int = 32) {
    private class Entry(val error: PendingCertError, val inFlight: Boolean)

    private val entries = LinkedHashMap<String, Entry>()

    /** A refusal of [error]'s URL; [inFlight] if that URL is on the main-frame navigation in flight. */
    fun record(error: PendingCertError, inFlight: Boolean) {
        entries.remove(error.url)
        entries[error.url] = Entry(error, inFlight)
        while (entries.size > capacity) {
            val drop = entries.entries.firstOrNull { !it.value.inFlight } ?: entries.entries.first()
            entries.remove(drop.key)
        }
    }

    /** The refusal a finish for [finishedUrl] ends, taken ([certErrorEndsLoad]). */
    fun takeFor(finishedUrl: String?): PendingCertError? {
        val key = entries.keys.firstOrNull { certErrorEndsLoad(finishedUrl, it) } ?: return null
        return entries.remove(key)?.error
    }

    /** A document committed: every refusal pending belonged to another navigation or a subresource. */
    fun clear() = entries.clear()

    val size: Int get() = entries.size
}

/**
 * The main-frame URLs of the navigation in flight, from the one it
 * started at through each redirect hop the WebView followed — so a
 * certificate refusal on a redirect target can be issued again on the
 * URL its history entry holds ([certPageReissue], #259 R2-F1). A
 * request for any URL but the chain's latest starts a new one (the
 * interceptor sees a redirect hop, if at all, as a request for the URL
 * [redirected] just added, and a retried first request is the latest
 * too); a commit ends it. Written from the main thread and the interceptor's
 * IO thread alike.
 */
internal class MainFrameChain {
    private val urls = ArrayList<String>()

    private fun has(url: String) = urls.any { it.substringBefore('#') == url.substringBefore('#') }

    /** The page's own new navigation to [url] (not a redirect). */
    @Synchronized
    fun started(url: String) {
        urls.clear()
        urls.add(url)
    }

    /** The WebView requests [url] for the main frame. */
    @Synchronized
    fun requested(url: String) {
        if (urls.lastOrNull()?.substringBefore('#') != url.substringBefore('#')) started(url)
    }

    /** The WebView follows a main-frame redirect to [url]. */
    @Synchronized
    fun redirected(url: String) {
        if (!has(url)) urls.add(url)
    }

    /** A document committed: no navigation in flight. */
    @Synchronized
    fun committed() = urls.clear()

    /**
     * The chain that ended in a refusal of [refusedUrl]: the one in
     * flight if it reached that URL, else [refusedUrl] alone (a hop the
     * WebView reported to neither callback, a 307 keeping a POST).
     */
    @Synchronized
    fun endingAt(refusedUrl: String): List<String> =
        if (has(refusedUrl)) urls.toList() else listOf(refusedUrl)

    /** Is [url] on the navigation in flight? */
    @Synchronized
    fun reaches(url: String): Boolean = has(url)
}

/**
 * The certificate page a tab's interceptor is to answer its next
 * main-frame request with (#259, [certPageStep]). [arm]ed on the main
 * thread just before the refused load is issued again, taken — once,
 * and only by a request for that URL — by `shouldInterceptRequest` on
 * its IO thread; any other main-frame request disarms it, so it can't
 * answer a later load. [isServed] tells the page's own callbacks (on
 * the main thread) the document on screen is this page, to keep it out
 * of history — from its commit until the next one, so a refused retry
 * of the same URL (Try again), whose synthetic finish arrives while the
 * page is still on screen, doesn't pass for a visit either.
 */
internal class CertRefusalSlot {
    private data class Armed(val url: String, val html: String)

    @Volatile private var armed: Armed? = null
    /** The URL the last main-frame request was answered with the page on, if it was. */
    @Volatile private var answered: String? = null

    /** The document on screen is the page, on this URL. Main thread only. */
    private var servedUrl: String? = null

    /**
     * Has a re-issue for [url] already been made that no commit has
     * followed? Then the same URL failing again is that re-issue's own
     * refusal — it never reached the interceptor — and issuing it once
     * more would only go round: [arm] says no (once — the next refusal,
     * the user's own, arms again). Main thread only.
     */
    private var reissued: String? = null

    fun arm(url: String, html: String): Boolean {
        val key = url.substringBefore('#')
        if (reissued == key) {
            reissued = null
            return false
        }
        reissued = key
        armed = Armed(key, html)
        return true
    }

    /**
     * A document committed for [url]: the page if the request just
     * answered was, and the next refusal is a new one.
     */
    fun committed(url: String?) {
        reissued = null
        val key = url?.substringBefore('#')
        servedUrl = answered?.takeIf { it == key }
    }

    /** The page for main-frame [url], if it is the armed one; disarms either way. */
    @Synchronized
    fun take(url: String): String? {
        val a = armed
        armed = null
        val key = url.substringBefore('#')
        val html = a?.takeIf { it.url == key }?.html
        answered = if (html != null) key else null
        return html
    }

    fun isServed(url: String?): Boolean = url != null && url.substringBefore('#') == servedUrl
}
