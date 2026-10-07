package baby.freedom.mobile.browser

import android.webkit.WebViewClient
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.ui.FreedomDarkColors
import baby.freedom.mobile.ui.FreedomLightColors
import org.json.JSONObject
import java.text.DateFormat
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
        NetFailure.OFFLINE -> Strings.get(R.string.errorpage_net_offline_title) to
            Strings.get(R.string.errorpage_net_offline)
        NetFailure.NOT_FOUND -> Strings.get(R.string.errorpage_net_not_found_title) to
            Strings.get(R.string.errorpage_net_not_found, h)
        NetFailure.REFUSED -> Strings.get(R.string.errorpage_net_refused_title) to
            Strings.get(R.string.errorpage_net_refused, h)
        NetFailure.RESET -> Strings.get(R.string.errorpage_net_reset_title) to
            Strings.get(R.string.errorpage_net_reset, h)
        NetFailure.TIMED_OUT -> Strings.get(R.string.errorpage_net_timed_out_title) to
            Strings.get(R.string.errorpage_net_timed_out, h)
        NetFailure.INSECURE -> Strings.get(R.string.errorpage_cert_title) to
            Strings.get(R.string.errorpage_net_insecure, h)
        NetFailure.OTHER -> Strings.get(R.string.errorpage_net_other_title) to
            Strings.get(R.string.errorpage_net_other, h)
    }
}

/**
 * A typed address that the address bar turned into a web URL — `example.org`
 * as `https://example.org` — kept on the tab ([BrowserState.typedAddress])
 * so that, if that very URL then fails to resolve, its error page can offer
 * to search for what was typed instead ([netErrorPageHtml], #419), on the
 * search engine chosen in Settings when it was submitted ([searchUrl]).
 */
internal data class TypedAddress(val url: String, val query: String, val searchUrl: String) {
    /**
     * Is [other] this address? Fragments aside, and an empty path as `/`:
     * `example.org` typed loads `https://example.org`, which Chromium
     * reports back as `https://example.org/`.
     */
    fun isFor(other: String): Boolean = sameAddress(url) == sameAddress(other)

    private fun sameAddress(u: String): String =
        u.substringBefore('#').let { if (it.substringAfter("://").contains('/')) it else "$it/" }
}

/**
 * The [TypedAddress] for [input] typed by the user and loaded as [url]
 * (`UrlParser.toUrl`), or null when there's nothing a search could stand
 * in for: a search already, an address typed with its scheme (that one
 * was meant as written), or anything not on the web.
 */
internal fun typedAddressFor(input: String, url: String, searchTemplate: String): TypedAddress? {
    val typed = input.trim()
    if (typed.isEmpty() || url == typed || UrlParser.isSearch(typed)) return null
    val scheme = url.substringBefore("://", "").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    return TypedAddress(url, typed, UrlParser.searchUrl(typed, searchTemplate))
}

/**
 * The "Search for … instead" offer for a failed load of [failedUrl]: only
 * when the address couldn't be found ([NetFailure.NOT_FOUND]) and it is
 * the one the user typed ([typed]) — a link or a bookmark to a dead host
 * wasn't something to search for.
 */
internal fun searchInsteadFor(typed: TypedAddress?, failedUrl: String, failure: NetFailure): TypedAddress? =
    typed?.takeIf { failure == NetFailure.NOT_FOUND && it.isFor(failedUrl) }

/**
 * The page that stands in for Chromium's own on a failed load of [url]:
 * [inPlaceErrorPageHtml] with the address and Chromium's error name in
 * the details box. Try again is a link to [url] itself — a same-URL
 * navigation replaces the failed entry, and it's always a GET, so a
 * failed form POST is retried as a GET rather than resent (#259). With
 * [searchInstead] ([searchInsteadFor]), a second link searches for what
 * the user typed.
 */
internal fun netErrorPageHtml(
    url: String,
    host: String,
    failure: NetFailure,
    rawError: String?,
    searchInstead: TypedAddress? = null,
): String {
    val (title, description) = netErrorCopy(failure, host)
    val details = escHtml(url) + (rawError?.takeIf { it.isNotBlank() }?.let { "\n\n" + escHtml(it) } ?: "")
    val search = searchInstead?.let {
        escHtml(it.searchUrl) to escHtml(Strings.get(R.string.errorpage_net_search_instead, it.query))
    }
    return inPlaceErrorPageHtml(title, description, details, retryHref = escHtml(url), secondaryLink = search)
}

/** `#rrggbb` of [color], alpha dropped (the page's colours are opaque). */
private fun cssHex(color: Color): String = "#%06x".format(color.toArgb() and 0xFFFFFF)

private fun ColorScheme.cssVars(): String =
    "--bg:${cssHex(background)};--fg:${cssHex(onSurface)};--mut:${cssHex(onSurfaceVariant)};" +
        "--box:${cssHex(surfaceContainerHigh)};--line:${cssHex(outlineVariant)};" +
        "--pri:${cssHex(primary)};--onpri:${cssHex(onPrimary)};--warn:${cssHex(secondary)}"

/**
 * The error pages' colours (#419): the app's own light and dark schemes
 * ([FreedomLightColors], [FreedomDarkColors]) as CSS custom properties,
 * picked by `prefers-color-scheme` — a neutral heading in the scheme's
 * text colour, the details box on its container colour, Try again in its
 * primary teal. [inPlaceErrorPageHtml] embeds it; `error.html` carries
 * the same block between its `THEME` markers (`ErrorPageThemeTest`).
 */
internal fun errorPageThemeCss(): String =
    ":root{color-scheme:light dark;${FreedomLightColors.cssVars()}}" +
        "@media (prefers-color-scheme:dark){:root{${FreedomDarkColors.cssVars()}}}"

/**
 * The stylesheet both error pages share, after [errorPageThemeCss]: the
 * text start-aligned in a readable column, the primary action a filled
 * button, any second one outlined, the raw error collapsed under
 * "Details". Buttons are at least 48 px tall; long addresses wrap.
 */
internal const val ERROR_PAGE_CSS =
    "html,body{margin:0;min-height:100%}" +
        "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);" +
        "color:var(--fg);padding:48px 24px;box-sizing:border-box;overflow-wrap:break-word}" +
        ".c{max-width:560px;margin:0 auto}" +
        "h1{font-size:22px;font-weight:600;line-height:1.3;margin:0 0 12px;color:var(--fg)}" +
        "p{line-height:1.55;margin:0 0 24px;color:var(--mut);font-size:16px}p b{color:var(--fg)}" +
        ".b{display:flex;flex-wrap:wrap;gap:12px;margin:0 0 28px}" +
        ".btn{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;min-height:48px;" +
        "min-width:0;max-width:100%;" +
        "padding:12px 24px;border-radius:24px;font:inherit;font-size:15px;font-weight:600;text-decoration:none;" +
        "cursor:pointer;border:1px solid var(--line);background:transparent;color:var(--pri);text-align:center}" +
        ".btn.p{background:var(--pri);border-color:var(--pri);color:var(--onpri)}" +
        ".btn.w{color:var(--warn);border-color:var(--warn)}" +
        "details{border-top:1px solid var(--line);padding-top:8px}" +
        "summary{cursor:pointer;color:var(--mut);font-size:14px;line-height:48px;min-height:48px}" +
        ".d{background:var(--box);padding:14px 16px;border-radius:12px;font-family:ui-monospace,Menlo,monospace;" +
        "font-size:13px;color:var(--mut);margin:4px 0 0;word-break:break-all;white-space:pre-wrap}" +
        ".nodes-icon{display:inline-block;width:1.2em;height:1.2em;vertical-align:middle;margin:0 2px}"

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
    // For the user to read, in their locale's style; the certificate's own dates are UTC.
    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun day(ms: Long) = fmt.format(Date(ms))
    val e = facts.errors
    val dates = SSL_EXPIRED in e || SSL_NOTYETVALID in e || SSL_DATE_INVALID in e
    return buildList {
        if (SSL_IDMISMATCH in e) add(Strings.get(R.string.errorpage_cert_detail_wrong_host, host))
        if (SSL_UNTRUSTED in e) add(Strings.get(R.string.errorpage_cert_detail_untrusted))
        if (dates) {
            val after = facts.notAfterMs
            val before = facts.notBeforeMs
            when {
                after != null && nowMs > after ->
                    add(Strings.get(R.string.errorpage_cert_detail_expired_on, day(after)))
                before != null && nowMs < before ->
                    add(Strings.get(R.string.errorpage_cert_detail_not_valid_until, day(before)))
                else -> add(Strings.get(R.string.errorpage_cert_detail_dates_invalid))
            }
        }
        if (isEmpty()) add(Strings.get(R.string.errorpage_cert_detail_invalid))
        add("")
        facts.issuedTo?.takeIf { it.isNotBlank() }
            ?.let { add(Strings.get(R.string.errorpage_cert_detail_issued_to, it)) }
        facts.issuedBy?.takeIf { it.isNotBlank() }
            ?.let { add(Strings.get(R.string.errorpage_cert_detail_issued_by, it)) }
    }.joinToString("\n").trimEnd()
}

/**
 * The page's sentence on why the certificate failed ([certErrorCode]),
 * naming [hostHtml]; the same wording `error.html` uses.
 */
private fun certReason(code: String, hostHtml: String): String = Strings.get(
    when (code) {
        "cert_expired" -> R.string.errorpage_cert_description_expired
        "cert_not_yet_valid" -> R.string.errorpage_cert_description_not_yet_valid
        "cert_date_invalid" -> R.string.errorpage_cert_description_date_invalid
        "cert_wrong_host" -> R.string.errorpage_cert_description_wrong_host
        "cert_untrusted" -> R.string.errorpage_cert_description_untrusted
        else -> R.string.errorpage_cert_description_invalid
    },
    hostHtml,
)

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
    val description = certReason(certErrorCode(facts, nowMs), "<b>${escHtml(host)}</b>") + " " +
        Strings.get(R.string.errorpage_cert_description_risk)
    val details = escHtml(url) + "\n\n" + escHtml(certErrorDetail(facts, host, nowMs))
    return inPlaceErrorPageHtml(
        Strings.get(R.string.errorpage_cert_title), description, details, retryHref = escHtml(retryUrl),
    )
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
