package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.ens.NameSystem

/**
 * The *resting* address-bar label: what the floating capsule shows while
 * the user is not editing.
 *
 * The brief's "Normal / Resting" state makes the domain the primary
 * element — the scheme, the path and the query are noise once a page is
 * open, and on a 56 dp capsule sharing its width with back / tabs / menu
 * there simply isn't room for a full URL. Tapping the pill still reveals
 * the complete URL ([BottomToolbar]'s text field is seeded with
 * [BrowserState.addressBarText], bidi controls marked as here), so
 * nothing is lost — only hidden while resting.
 *
 * Mapping, applied to the *display* URL (i.e. after [DisplayUrl] has
 * already folded gateway URLs back to `bzz://` / `ipfs://` / bare ENS
 * names):
 *
 *   `https://www.example.com/a/b?c` → `example.com`
 *   `https://en.wikipedia.org/wiki/X` → `wikipedia.org`
 *   `https://foo.bbc.co.uk/x` → `bbc.co.uk` (compound public suffix)
 *   `https://google.github.io/styleguide/` → `google.github.io`
 *       (every tenant of a user-content platform is its own owner —
 *       see [PublicSuffixList])
 *   `swarm.eth/docs` / `bzz://swarm.eth` → `swarm.eth`
 *   `pay.vitalik.eth/x` → `pay.vitalik.eth` (ENS names never collapse)
 *   `bzz://a1b2…f9/index.html` → `bzz://a1b2c3…d4e5`
 *   `http://127.0.0.1:1633/x` → `127.0.0.1`
 *
 * Anything we can't parse as a host (`about:…`, `data:…`) is passed
 * through untouched rather than mangled.
 */
object AddressLabel {

    /**
     * TLDs whose names are ENS-style names rather than DNS domains —
     * the ones [baby.freedom.mobile.ens.EnsInput] accepts, ENS's `.eth`
     * / `.box`, WNS `.wei`, GNS `.gwei` and Tezos Domains `.tez`.
     *
     * DNS has a registrable-domain boundary — everything under
     * `example.com` is `example.com`'s to give away, so collapsing
     * `a.b.example.com` to `example.com` still names the party the user
     * trusts. ENS has no such boundary: every label is a name in its
     * own right, with its own owner and its own resolver, and a subname
     * can be emancipated from its parent (NameWrapper) or minted by an
     * open subname registrar. `pay.vitalik.eth` is therefore *not*
     * vitalik.eth and may point its contenthash anywhere; folding it
     * into a bold `vitalik.eth` would have the capsule vouch for a name
     * the user is not on. ENS names are shown whole.
     */
    private val ENS_TLDS: Set<String> =
        NameSystem.navigableSuffixes.map { it.removePrefix(".") }.toSet()

    /**
     * The WHATWG URL Standard's *special* schemes — the ones Chromium
     * parses with the backslash-as-path-separator rule. See
     * [authorityOf].
     */
    private val SPECIAL_SCHEMES: Set<String> =
        setOf("http", "https", "ws", "wss", "ftp", "file")

    /** Content-addressed ids longer than this get elided in the middle. */
    private const val MAX_ID_CHARS = 14

    /**
     * Label for [displayUrl] while the address bar is at rest. Returns
     * `""` for a blank URL (the home tab), which lets the caller fall
     * back to the "Search or type URL" placeholder.
     */
    fun resting(displayUrl: String): String = BidiControls.marked(restingUnmarked(displayUrl))

    // Every bidi control is shown as U+FFFD ([BidiControls.marked]). A
    // name ENSIP-15 refuses is still put in the bar as given, for its
    // resolver refusal to name it — and a refused name can carry any of
    // them (`ens://%E2%80%AEmoc.lapyap.eth`, from another app's link or
    // a page's own navigation). Such a name isn't host-shaped, so it
    // reaches the label whole, and one RLO in it reversed the rest of
    // the capsule: the label read `hte.paypal.com`. The edit field and
    // Copy / Share mark the same way (see [AddressField], [urlActionTarget]).

    private fun restingUnmarked(displayUrl: String): String {
        val raw = displayUrl.trim()
        if (raw.isEmpty()) return ""

        val sep = raw.indexOf("://")
        if (sep < 0) {
            // Bare-name display form (`swarm.eth`, `swarm.eth/docs`) —
            // what ENS navigations put in the bar. [UrlParser] loads
            // these as `https://…`, i.e. as a special scheme.
            val authority = authorityOf(raw, backslashSeparates = true)
            return if (looksLikeHost(authority)) hostLabel(authority) else raw
        }

        val scheme = raw.substring(0, sep).lowercase()
        val authority = authorityOf(raw.substring(sep + 3), scheme in SPECIAL_SCHEMES)
        if (authority.isEmpty()) return raw
        return when (scheme) {
            "http", "https" -> hostLabel(authority)
            // Content-addressed schemes carry either an ENS name (show
            // the name — the protocol badge already says which network
            // served it) or a raw hash / CID, which is only ever
            // recognisable by its head and tail.
            "bzz", "ipfs", "ipns", "ens" -> when {
                looksLikeHost(authority) -> hostLabel(authority)
                // Userinfo is never part of a name or a content id: show
                // it as typed rather than elide it into one (see
                // [looksLikeHost]). Eliding would hide the `@` and leave
                // something domain-shaped — `paypal…com` from
                // `paypal\\@aaaaaaaaaaaa.com`, `evil%4….eth` from
                // `evil%40vitalik.eth` — so this holds with a backslash
                // and for a percent-encoded `@` too.
                hasAt(authority) -> raw
                else -> "$scheme://${elideId(authority)}"
            }
            else -> raw
        }
    }

    /**
     * The name the capsule rests on for [host]: the registrable domain
     * for DNS, the *whole* name for ENS (see [ENS_TLDS]) — including
     * any `www` label, which under `.eth` is a subname like any other
     * rather than the conventional alias DNS makes it.
     */
    private fun hostLabel(host: String): String {
        val h = hostOnly(host).lowercase().trimEnd('.')
        // A lookalike `.tez` name rests `%XX`-escaped (#465), whatever
        // spelling the bar was handed (Unicode, or escaped and lowercased
        // above — read back and escaped again, in upper-case hex).
        if (h.substringAfterLast('.', "") in ENS_TLDS) {
            return EnsNormalize.tezosForm(h)?.let(EnsNormalize::tezosDisplay) ?: h
        }
        return registrableHost(h)
    }

    /**
     * Strip every label above the registrable one, the boundary drawn by
     * the [PublicSuffixList]: `www.en.example.co.uk` → `example.co.uk`,
     * but `google.github.io` → `google.github.io`, because under a
     * PRIVATE-section suffix each subdomain is a separate tenant and
     * collapsing them would have the capsule rest on a name shared with
     * whoever else signed up for the platform.
     *
     * IP literals, single-label hosts (`localhost`) and hosts that *are*
     * a public suffix are returned as-is — truncating `127.0.0.1` to
     * `0.1` would be worse than useless, and shrinking a name we can't
     * place is the one direction the label must never take.
     */
    fun registrableHost(host: String): String {
        val h = hostOnly(host).lowercase().trimEnd('.')
        if (h.isEmpty()) return ""
        if (isIpLiteral(h)) return h
        return PublicSuffixList.registrableDomain(h) ?: h
    }

    /**
     * Everything before the first `/`, `?` or `#` — and, when the scheme
     * is a WHATWG *special* one, before the first `\` too.
     *
     * Chromium follows the URL Standard, which makes a backslash an
     * authority terminator (a path separator) for the special schemes.
     * `http://host\@bank.com/x` therefore navigates to `host` with the
     * path `/@bank.com/x`; if we stopped only at `/` we would read the
     * whole `host\@bank.com` as the authority, take `bank.com` out of it
     * as the part after the userinfo `@`, and rest on a domain the user
     * never visits. The label is a trust surface, so it has to split the
     * authority the way the loader does rather than wait for
     * `onPageStarted` to overwrite the bar with the normalized URL.
     *
     * Content-addressed schemes are not special, so their authority
     * keeps backslashes verbatim — there the label is a hash or an ENS
     * name, neither of which a backslash can legitimately appear in.
     */
    private fun authorityOf(rest: String, backslashSeparates: Boolean): String {
        val end = rest.indexOfFirst {
            it == '/' || it == '?' || it == '#' || (backslashSeparates && it == '\\')
        }
        return if (end < 0) rest else rest.substring(0, end)
    }

    /** Drop `user:pass@` and a trailing `:port`. */
    private fun hostOnly(authority: String): String {
        val afterUserInfo = authority.substringAfterLast('@')
        if (afterUserInfo.startsWith("[")) return afterUserInfo // IPv6 literal
        val colon = afterUserInfo.lastIndexOf(':')
        val isPort = colon > 0 &&
            afterUserInfo.length > colon + 1 &&
            afterUserInfo.substring(colon + 1).all { it.isDigit() }
        return if (isPort) afterUserInfo.substring(0, colon) else afterUserInfo
    }

    private fun isIpLiteral(host: String): Boolean =
        host.startsWith("[") || host.all { it.isDigit() || it == '.' }

    /**
     * Does [authority] look like a hostname (dotted labels) rather than
     * a content hash / CID? `swarm.eth` yes, `bafybeigd…` no.
     */
    private fun looksLikeHost(authority: String): Boolean {
        // A backslash is never legal in a host, so an authority carrying
        // one is not a name — and must not become one via [hostOnly]'s
        // userinfo strip, which would otherwise turn the non-special
        // `bzz://swarm.eth\@bank.com` into the label `bank.com`. Special
        // schemes never get here with a backslash ([authorityOf] has cut
        // the authority at it already); the rest are shown as typed when
        // they carry an `@` (see [restingUnmarked]) and elided as an id
        // otherwise.
        if (authority.contains('\\')) return false
        // Nor is one carrying an `@`. A name or a content id has no
        // userinfo, so `ens://evil@vitalik.eth` (from another app's link
        // or a page's own navigation) is the name `evil@vitalik.eth`,
        // which the resolver refuses — and stripping the `evil@` here
        // would have the capsule rest on `vitalik.eth` over that error
        // page (#478). This runs only for the bare-name form and the
        // content-addressed schemes; `https://user@host` keeps its
        // userinfo strip in [hostLabel], where it matches what loads.
        if (authority.contains('@')) return false
        val h = hostOnly(authority)
        if (!h.contains('.')) return false
        // A lookalike `.tez` name is shown `%XX`-escaped (#465).
        val escapedTez = h.lowercase().endsWith(".tez")
        return h.split('.').all { label ->
            label.isNotEmpty() &&
                label.all { it.isLetterOrDigit() || it == '-' || it == '_' || (escapedTez && it == '%') }
        }
    }

    /**
     * [label] shortened, if it has to be, so that its `@` stays on screen.
     *
     * A label shown as typed because it carries userinfo
     * (`aaaa…aaaa@vitalik.eth`, from `ens://<64 a's>@vitalik.eth`) is only
     * honest while the `@` is visible. Left to the capsule's middle
     * ellipsis, a long userinfo is exactly the part that gets cut, and
     * the capsule rests on `aaaaaaa…italik.eth`, with the `@` gone and
     * the name after it reading like the name being visited.
     *
     * So for a label that carries an `@` (literal or `%40`, the last one,
     * which is where a userinfo strip would cut) and doesn't [fit], the
     * shortening is done here, around that `@`: first the part before it
     * keeps a shorter and shorter head (`ens://aaaa…@vitalik.eth`), and
     * only once that is down to one character does the part after it
     * give up its middle, keeping its tail (`e…@…lik.eth`). The result is
     * the longest such candidate that fits, so the capsule's own
     * ellipsis never runs on it. A label without an `@`, or one that
     * fits as it is, is returned unchanged. Tapping the pill still shows
     * the whole address.
     *
     * [fits] must be monotone in length (a shorter candidate never fits
     * worse), which a width measurement is.
     */
    fun keepingAt(label: String, fits: (String) -> Boolean): String {
        val at = maxOf(label.lastIndexOf('@'), label.lastIndexOf("%40", ignoreCase = true))
        if (at < 0 || fits(label)) return label
        val marker = if (label[at] == '@') "@" else label.substring(at, at + 3)
        val before = label.substring(0, at)
        val after = label.substring(at + marker.length)

        fun headed(k: Int) = before.take(cutBefore(before, k)) + "…" + marker + after
        // Largest head of [before] at which the whole [after] still fits.
        if (before.isNotEmpty()) {
            var lo = 1
            var hi = before.length - 1
            var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (fits(headed(mid))) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (best > 0) return headed(best)
        }
        val head = if (before.isEmpty()) "" else before.take(cutBefore(before, 1)) + "…"
        fun tailed(m: Int) = head + marker + "…" + after.substring(cutAfter(after, m))
        var lo = 1
        var hi = after.length - 1
        var best = 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (fits(tailed(mid))) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (after.length <= 1) head + marker + after else tailed(best)
    }

    /** [k] moved back off a surrogate pair's high half, never below 1. */
    private fun cutBefore(s: String, k: Int): Int {
        val n = k.coerceIn(1, s.length)
        return if (n in 2 until s.length && s[n - 1].isHighSurrogate()) n - 1 else n
    }

    /** Start of a [m]-char tail of [s], moved forward off a low surrogate. */
    private fun cutAfter(s: String, m: Int): Int {
        val start = s.length - m.coerceIn(1, s.length)
        return if (start in 1 until s.length - 1 && s[start].isLowSurrogate()) start + 1 else start
    }

    /** [authority] carries an `@`, literally or as `%40`. */
    private fun hasAt(authority: String): Boolean =
        authority.contains('@') || authority.contains("%40")

    private fun elideId(id: String): String =
        if (id.length <= MAX_ID_CHARS) id
        else "${id.take(6)}…${id.takeLast(4)}"
}
