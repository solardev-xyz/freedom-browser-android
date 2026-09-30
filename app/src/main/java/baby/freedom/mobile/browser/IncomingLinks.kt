package baby.freedom.mobile.browser

import android.app.SearchManager
import android.content.Intent

/**
 * What another app handed the browser (#268): a link to open, or text to
 * search for with the chosen engine.
 */
sealed interface Incoming {
    /** [url] as it came in (scheme lower-cased), not yet in its display form ([IncomingLinks.displayUrl]). */
    data class Open(val url: String) : Incoming

    data class Search(val query: String) : Incoming
}

/**
 * Reads links, shares and searches from other apps (#268): a `VIEW` of an
 * http(s) or dweb link, Share → Freedom (`SEND` text), `WEB_SEARCH`, and
 * the text-selection menu's **Search with Freedom** (`PROCESS_TEXT`).
 *
 * The one gate for all of them — [IncomingLinkActivity] parses with it
 * before handing anything on, and [baby.freedom.mobile.MainActivity]
 * parses what it receives again with it, since any app can start that
 * exported activity directly. Only the schemes the address bar itself
 * opens get through: never `file:`, `content:`, `javascript:`, `intent:`,
 * `data:` or the browser's own internal schemes.
 */
object IncomingLinks {
    /**
     * Longest search accepted, in chars. Text shared from another app can
     * be a whole article; the search URL (and the tab's saved address,
     * [TabsState.MAX_SAVED_ADDRESS]) has no room for that.
     */
    const val MAX_QUERY = 2048

    /** The schemes another app may open here: the web, and the dweb ones the address bar takes. */
    val SCHEMES = setOf("http", "https", "bzz", "ipfs", "ipns", "ens", "rad")

    /** Web links found inside shared text. */
    private val EMBEDDED_WEB_LINK = Regex("""(?i)\bhttps?://\S+""")

    /**
     * [raw] as a link another app may open here, scheme lower-cased; null
     * for any other scheme, blank input, or anything with whitespace or
     * control characters in it.
     */
    fun link(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s.any { it.isWhitespace() || it.isISOControl() }) return null
        val colon = s.indexOf(':')
        if (colon <= 0) return null
        val scheme = s.substring(0, colon).lowercase()
        if (scheme !in SCHEMES) return null
        val rest = s.substring(colon + 1)
        // A web link needs a host: `https:` or `https:///x` is nothing to load.
        if ((scheme == "http" || scheme == "https") &&
            (!rest.startsWith("//") || rest.length <= 2 || rest[2] == '/')
        ) {
            return null
        }
        if (rest.isEmpty()) return null
        return scheme + ":" + rest
    }

    /** A `VIEW` intent's data. */
    fun fromView(data: String?): Incoming? = link(data)?.let(Incoming::Open)

    /**
     * Shared text (`SEND`): a link opens — the whole text, or else the
     * first web link in it ("Headline https://…", the way news and video
     * apps share) — and anything else is searched for.
     */
    fun fromSharedText(text: String?): Incoming? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        link(t)?.let { return Incoming.Open(it) }
        EMBEDDED_WEB_LINK.find(t)?.value
            ?.let(::trimTrailingPunctuation)
            ?.let(::link)
            ?.let { return Incoming.Open(it) }
        return search(t)
    }

    /**
     * [url] (a link found inside text) without the sentence punctuation
     * that follows it: `.`, `,`, quotes, and a closing `)` or `]` — but
     * only a bracket with no partner inside the link, so the one closing
     * `/wiki/Mercury_(planet)` stays and the one closing "(see https://…)"
     * goes.
     */
    internal fun trimTrailingPunctuation(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val cut = when (c) {
                '.', ',', ';', ':', '!', '?', '"', '\'', '»', '”', '’' -> true
                ')' -> unbalanced(url, end, '(', ')')
                ']' -> unbalanced(url, end, '[', ']')
                else -> false
            }
            if (!cut) break
            end--
        }
        return url.substring(0, end)
    }

    /** More [close] than [open] in `url[0, end)`: the last [close] isn't the link's. */
    private fun unbalanced(url: String, end: Int, open: Char, close: Char): Boolean {
        var depth = 0
        for (i in 0 until end) {
            when (url[i]) {
                open -> depth++
                close -> depth--
            }
        }
        return depth < 0
    }

    /**
     * A search (`WEB_SEARCH`, **Search with Freedom**): a query that is
     * itself exactly a link opens it, as a typed one would; anything else
     * is searched for.
     */
    fun fromQuery(query: String?): Incoming? {
        val t = query?.trim().orEmpty()
        if (t.isEmpty()) return null
        link(t)?.let { return Incoming.Open(it) }
        return search(t)
    }

    private fun search(text: String): Incoming.Search {
        // Cut on a code-point boundary so a surrogate pair isn't split.
        var end = minOf(text.length, MAX_QUERY)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        return Incoming.Search(text.substring(0, end).trim())
    }

    /**
     * What [intent] asks the browser to open, or null if it isn't one of
     * ours or carries nothing usable. Never throws: the extras are another
     * app's, and reading them unparcels whatever it put there.
     */
    fun from(intent: Intent?): Incoming? {
        intent ?: return null
        return try {
            when (intent.action) {
                Intent.ACTION_VIEW -> fromView(intent.dataString)
                Intent.ACTION_SEND ->
                    if (intent.type?.startsWith("text/") == true) {
                        fromSharedText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                    } else {
                        null
                    }
                Intent.ACTION_WEB_SEARCH -> fromQuery(intent.getStringExtra(SearchManager.QUERY))
                Intent.ACTION_PROCESS_TEXT ->
                    fromQuery(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
                else -> null
            }
        } catch (e: RuntimeException) {
            // A BadParcelableException from a class we don't have, and the like.
            null
        }
    }

    /**
     * [incoming] as the plain intent [IncomingLinkActivity] hands on: a
     * `VIEW` of the link or a `WEB_SEARCH` of the query, nothing else of
     * the other app's intent carried over. [from] reads it back to the
     * same [Incoming].
     */
    fun toIntent(incoming: Incoming): Intent = when (incoming) {
        is Incoming.Open -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse(incoming.url))
        is Incoming.Search -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, incoming.query)
    }

    /**
     * The address-bar form of an incoming link: a virtual-origin share
     * link (`https://<label>.bzz.freedom.baby/…`) back to the content it
     * names, an `ens://` link with its name percent-decoded, anything else
     * as it is. May decode the ENSIP-15 tables
     * ([VirtualOrigin.needsEnsTables]).
     */
    fun displayUrl(url: String): String =
        VirtualOrigin.displayUrlFor(url) ?: decodedEnsName(url) ?: url

    /**
     * An `ens://` link whose name another app percent-encoded
     * (`ens://%F0%9F%A6%8A.eth`, the way `Uri` and most apps write a
     * non-ASCII authority) with the name decoded: the address bar reads
     * `ens://🦊.eth` as that name, but the encoded form as an invalid one.
     * Null when there's nothing to decode, or when the decoded name isn't
     * one the address bar could take as typed — a delimiter, whitespace,
     * a control character, a stray `%` or invalid UTF-8 — so an encoded
     * `/` can't turn part of the name into a path.
     */
    private fun decodedEnsName(url: String): String? {
        if (!url.startsWith("ens://")) return null
        val rest = url.substring(6)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val name = rest.substring(0, end)
        if ('%' !in name) return null
        val decoded = WhatwgHost.percentDecode(name)
        val usable = decoded.isNotEmpty() && decoded.none {
            it in "/?#@:%\\\uFFFD" || it.isWhitespace() || it.isISOControl()
        }
        return if (usable) "ens://" + decoded + rest.substring(end) else null
    }
}
