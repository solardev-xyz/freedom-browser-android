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
 *   `swarm.eth/docs` / `bzz://swarm.eth` → `swarm.eth`
 *   `bzz://a1b2…f9/index.html` → `bzz://a1b2c3…d4e5`
 *   `http://127.0.0.1:1633/x` → `127.0.0.1`
 *
 * Anything we can't parse as a host (`about:…`, `data:…`) is passed
 * through untouched rather than mangled.
 */
object AddressLabel {

    /**
     * Two-label public suffixes common enough to be worth keeping the
     * third label for, so `bbc.co.uk` doesn't collapse to `co.uk`. This
     * is deliberately a short list rather than a vendored Public Suffix
     * List: the failure mode of a miss is cosmetic (one label too few
     * on a rare ccTLD) and a ~10 k-entry PSL in the APK for an address
     * label isn't a trade this stage wants to make.
     */
    private val COMPOUND_SUFFIXES: Set<String> = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "com.br", "com.cn", "net.cn", "org.cn", "gov.cn",
        "co.in", "net.in", "org.in", "co.za", "org.za",
        "com.mx", "com.ar", "com.tr", "com.sg", "com.hk", "com.tw",
        "co.kr", "co.nz", "co.il", "co.id", "co.th", "com.pl", "com.ua",
    )

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
            return if (looksLikeHost(authority)) registrableHost(authority) else raw
        }

        val scheme = raw.substring(0, sep).lowercase()
        val authority = authorityOf(raw.substring(sep + 3), scheme in SPECIAL_SCHEMES)
        if (authority.isEmpty()) return raw
        return when (scheme) {
            "http", "https" -> registrableHost(authority)
            // Content-addressed schemes carry either an ENS name (show
            // the name — the protocol badge already says which network
            // served it) or a raw hash / CID, which is only ever
            // recognisable by its head and tail.
            "bzz", "ipfs", "ipns", "ens" ->
                if (looksLikeHost(authority)) registrableHost(authority)
                else "$scheme://${elideId(authority)}"
            else -> raw
        }
    }

    /**
     * Strip `www.` and every label above the registrable one:
     * `www.en.example.co.uk` → `example.co.uk`. IP literals and
     * single-label hosts (`localhost`) are returned as-is — truncating
     * `127.0.0.1` to `0.1` would be worse than useless.
     */
    fun registrableHost(host: String): String {
        val h = hostOnly(host).lowercase().trimEnd('.')
        if (h.isEmpty()) return ""
        if (isIpLiteral(h)) return h
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val stripped = if (labels.first() == "www") labels.drop(1) else labels
        if (stripped.size <= 2) return stripped.joinToString(".")
        val lastTwo = stripped.takeLast(2).joinToString(".")
        val keep = if (lastTwo in COMPOUND_SUFFIXES) 3 else 2
        return stripped.takeLast(keep).joinToString(".")
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
