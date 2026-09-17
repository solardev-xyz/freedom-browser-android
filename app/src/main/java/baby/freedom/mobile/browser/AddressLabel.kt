package baby.freedom.mobile.browser

/**
 * The *resting* address-bar label: what the floating capsule shows while
 * the user is not editing.
 *
 * The brief's "Normal / Resting" state makes the domain the primary
 * element — the scheme, the path and the query are noise once a page is
 * open, and on a 56 dp capsule sharing its width with back / tabs / menu
 * there simply isn't room for a full URL. Tapping the pill still reveals
 * the complete URL ([BottomToolbar]'s text field keeps
 * [BrowserState.addressBarText] verbatim), so nothing is lost — only
 * hidden while resting.
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
     * TLDs whose names are ENS names rather than DNS domains (the two
     * [baby.freedom.mobile.ens.EnsInput] accepts).
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
    private val ENS_TLDS: Set<String> = setOf("eth", "box")

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
    fun resting(displayUrl: String): String {
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
            "bzz", "ipfs", "ipns", "ens" ->
                if (looksLikeHost(authority)) hostLabel(authority)
                else "$scheme://${elideId(authority)}"
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
        if (h.substringAfterLast('.', "") in ENS_TLDS) return h
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
        // the authority at it already); the rest fall through to the
        // elided-id form, which cannot be mistaken for a domain.
        if (authority.contains('\\')) return false
        val h = hostOnly(authority)
        if (!h.contains('.')) return false
        return h.split('.').all { label ->
            label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }

    private fun elideId(id: String): String =
        if (id.length <= MAX_ID_CHARS) id
        else "${id.take(6)}…${id.takeLast(4)}"
}
