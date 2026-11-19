package baby.freedom.mobile.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import baby.freedom.mobile.ens.Base32
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsResult
import kotlinx.coroutines.Job

/**
 * "Open without gateway" (#537), desktop's `src/main/gateway-offer.js`
 * (freedom-browser#70).
 *
 * A page served through a public gateway (`vitalik.eth.limo`,
 * `vitalik.eth.link`, `vitalik.bzz.link`, `<swarm-cid>.bzz.link`) is an
 * ordinary https site, and the address bar keeps showing it as one: the
 * gateway URL is never rewritten behind the user's back. What this works
 * out is whether there is a native version to *offer* — the `ipfs://`,
 * `ipns://` or `bzz://` address the gateway stands in for — which the
 * pill over the address bar ([GatewayOfferPill]) then opens through the
 * ordinary submit flow, so the name is resolved and checked exactly as if
 * the user had typed that address.
 *
 *   `<name>.eth.limo`, `<name>.eth.link`  ENS name `<name>.eth`, on
 *                         whatever transport its contenthash names.
 *   `<name>.bzz.link`     ENS name `<name>.eth` (the Swarm gateway appends
 *                         `.eth` itself), offered as `bzz://<name>.eth`
 *                         only when the contenthash really is Swarm: a
 *                         typed `bzz://` is an assertion the submit flow
 *                         enforces, and one bound to fail is a dead end.
 *   `<cid>.bzz.link`      A Swarm reference as a CID (`bah5…`, manifest or
 *                         feed codec — 64 hex characters don't fit a DNS
 *                         label): `bzz://<hex reference>`, no lookup.
 *
 * Path, query and fragment carry over unchanged. Anything else — another
 * host, a port, user info, an invalid CID, a name with no (or the wrong
 * kind of) contenthash, a failed lookup — is no offer, and the user simply
 * keeps the gateway page.
 */
internal object GatewayOffer {
    /** A gateway URL's shape, before any lookup. */
    data class Shape(
        /** `eth.limo`, `eth.link`, `bzz.link`. */
        val gateway: String,
        /** The gateway host the page is on, lowercase ASCII. */
        val host: String,
        /** The ENS name a lookup decides the destination by, or `null` for a CID. */
        val ensName: String? = null,
        /** The one transport that fits (`bzz` for `<name>.bzz.link`), or `null` for any. */
        val requireProtocol: String? = null,
        /** The 64-hex Swarm reference a `<cid>.bzz.link` host names. */
        val reference: String? = null,
    )

    /** A native address to offer for a gateway page. */
    data class Offer(
        val gateway: String,
        val gatewayHost: String,
        /** `ipfs`, `ipns` or `bzz`. */
        val protocol: String,
        /** The native origin, without a path: `ipfs://vitalik.eth`, `bzz://<hex>`. */
        val target: String,
    ) {
        /**
         * The native address for [url] — a page of this offer's gateway
         * host, with its own path, query and fragment — or `null` if [url]
         * is on another host (the tab moved on).
         */
        fun destinationFor(url: String): String? {
            val parts = split(url) ?: return null
            if (parts.host != gatewayHost) return null
            return target + parts.rest
        }
    }

    private class Gateway(val suffix: String, val gateway: String, val ens: Boolean)

    private val GATEWAYS = listOf(
        Gateway(".eth.limo", "eth.limo", ens = true),
        Gateway(".eth.link", "eth.link", ens = true),
        Gateway(".bzz.link", "bzz.link", ens = false),
    )

    /** Transports a native destination can use; a contenthash of any other kind has no address here. */
    private val NATIVE_PROTOCOLS = setOf("ipfs", "ipns", "bzz")

    /**
     * A CID label: multibase `b` (base32 lower), then the `ah5` the CIDv1
     * + Swarm-codec header always encodes to. Only routes the label to
     * [decodeSwarmCid], which decides whether it is real.
     */
    private val SWARM_CID_LABEL = Regex("^bah5[a-z2-7]+$")

    private val HOST = Regex("^[a-z0-9.-]+$")
    private const val MAX_DNS_LABEL = 63

    /** Swarm's manifest and feed codecs (`0xfa`, `0xfb`), the two the gateway takes. */
    private val SWARM_CODECS = setOf(0xfa, 0xfb)

    private class Parts(val host: String, val rest: String)

    /**
     * An http(s) URL's lowercase host (trailing dot dropped) and
     * everything after it (path, query, fragment; `/` when empty) — or
     * `null` for another scheme, a port, or user info: a gateway on a
     * non-default port, or behind credentials, isn't one of these public
     * gateways.
     */
    private fun split(url: String): Parts? {
        val sep = url.indexOf("://")
        if (sep <= 0) return null
        val scheme = url.substring(0, sep).lowercase()
        if (scheme != "https" && scheme != "http") return null
        val afterScheme = url.substring(sep + 3)
        val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterScheme.length else it }
        val authority = afterScheme.substring(0, end)
        if ('@' in authority || ':' in authority || '\\' in authority) return null
        val host = authority.lowercase().removeSuffix(".")
        if (host.isEmpty() || !HOST.matches(host)) return null
        val tail = afterScheme.substring(end)
        val rest = if (tail.isEmpty() || tail[0] != '/') "/$tail" else tail
        return Parts(host, rest)
    }

    /** Classify [url] as a gateway shape; pure, no lookup. */
    fun parse(url: String?): Shape? {
        val parts = split(url ?: return null) ?: return null
        val host = parts.host
        for (entry in GATEWAYS) {
            if (!host.endsWith(entry.suffix)) continue
            val sub = host.removeSuffix(entry.suffix)
            if (sub.isEmpty()) return null
            if (entry.ens) {
                val name = nameFromLabels(sub, ".eth") ?: return null
                return Shape(entry.gateway, host, ensName = name)
            }
            // bzz.link: a `bah5…` label is a CID and nothing else — an
            // invalid one is no offer, never an ENS name `bah5….eth`.
            if (SWARM_CID_LABEL.matches(sub)) {
                val reference = decodeSwarmCid(sub) ?: return null
                return Shape(entry.gateway, host, reference = reference)
            }
            val name = nameFromLabels(sub, ".eth") ?: return null
            return Shape(entry.gateway, host, ensName = name, requireProtocol = "bzz")
        }
        return null
    }

    /**
     * The ENS name a gateway subdomain stands for: every label a real DNS
     * label (1–63 characters), `xn--` labels turned back into the Unicode
     * the name was registered with (WebView hands over the ASCII form, and
     * namehashing that would look up another name), then [suffix], in the
     * form the address bar's own parser ([EnsInput]) gives it.
     */
    private fun nameFromLabels(labels: String, suffix: String): String? {
        val parts = labels.split('.')
        if (parts.any { it.isEmpty() || it.length > MAX_DNS_LABEL }) return null
        val unicode = parts.map { label ->
            if (label.startsWith("xn--")) Punycode.decode(label.substring(4)) ?: return null else label
        }
        return EnsInput.parse(unicode.joinToString(".") + suffix)?.name
    }

    /**
     * A `bah5…` Swarm CID label's 64-hex reference, or `null`. The label
     * must decode to exactly CIDv1 + manifest or feed codec + a 32-byte
     * keccak-256 multihash, and encode back to itself — which refuses a
     * wrong version, codec, multihash, length, trailing garbage or
     * non-canonical padding bits in one check.
     */
    fun decodeSwarmCid(label: String): String? {
        if (!SWARM_CID_LABEL.matches(label)) return null
        val bytes = base32Decode(label.substring(1)) ?: return null
        if (bytes.size != 5 + 32) return null
        val codec = bytes[1].toInt() and 0xff
        if (bytes[0].toInt() != 0x01 || codec !in SWARM_CODECS || bytes[2].toInt() != 0x01 ||
            bytes[3].toInt() != 0x1b || bytes[4].toInt() != 0x20
        ) {
            return null
        }
        if ("b" + Base32.encodeLower(bytes) != label) return null
        return bytes.copyOfRange(5, bytes.size).joinToString("") { "%02x".format(it) }
    }

    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    private fun base32Decode(input: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var value = 0
        for (c in input) {
            val v = BASE32.indexOf(c)
            if (v < 0) return null
            value = (value shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((value ushr bits) and 0xff)
            }
            value = value and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }

    /** The offer for a CID shape, which needs no lookup; `null` for a name. */
    fun offerWithoutLookup(shape: Shape): Offer? =
        shape.reference?.let { Offer(shape.gateway, shape.host, "bzz", "bzz://$it") }

    /**
     * The offer for a name [shape] given its contenthash lookup [result]:
     * only a settled answer on a native transport (and the one
     * [Shape.requireProtocol] demands) is offered.
     */
    fun offerFor(shape: Shape, result: EnsResult?): Offer? {
        val name = shape.ensName ?: return null
        val ok = result as? EnsResult.Ok ?: return null
        if (ok.redirect) return null
        val protocol = ok.protocol.lowercase()
        if (protocol !in NATIVE_PROTOCOLS) return null
        if (shape.requireProtocol != null && protocol != shape.requireProtocol) return null
        return Offer(shape.gateway, shape.host, protocol, "$protocol://$name")
    }
}

/**
 * One tab's "Open without gateway" state (#537): the offer for the page
 * on screen ([offer], which the pill shows), and the "Always open
 * without gateway" redirect's bookkeeping. Pure: [BrowserWebView] reports
 * commits ([committed]), same-document moves ([located]), links it
 * follows ([linkFollowed]) and navigations that end without a commit
 * ([stayed], [stopped], [loadFinished]); the screen reports its own loads
 * ([loadStarted]) and the chrome's Back / Forward ([historyStep]), runs
 * the lookups ([Lookup]) and acts on [answered]'s verdict.
 *
 * An answer is acted on only while nothing is taking the tab elsewhere:
 * a load the tab starts drops it, a navigation still on its way holds
 * it until that commits (dropped) or doesn't (answered again for the
 * page that stayed).
 */
internal class GatewayOfferTab {
    /**
     * The lookup a commit asks for; [seq] ties its answer to that commit.
     * [mayAutoOpen]: "always", if it is on when the answer comes, may send
     * the tab to the native address.
     */
    data class Lookup(val seq: Int, val url: String, val shape: GatewayOffer.Shape, val mayAutoOpen: Boolean)

    sealed interface Verdict {
        data object Nothing : Verdict
        data object Show : Verdict
        data class AutoOpen(val destination: String) : Verdict
    }

    /** The offer for the page on screen, or `null`: what the pill shows. */
    var offer by mutableStateOf<GatewayOffer.Offer?>(null)
        private set

    /**
     * The page on screen's raw URL, kept current through same-document
     * moves. State, so the pill's spoken destination follows `pushState`
     * (R1-M2).
     */
    var url by mutableStateOf("")
        private set

    /** The lookup the screen is to run next, if any; taken with [take]. */
    var pending by mutableStateOf<Lookup?>(null)
        private set

    private var seq = 0

    /** The lookup the screen is running for this tab, cancelled once it answers for nothing. */
    var running: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    /**
     * The gateway URL "always" last sent this tab away from, which it may
     * come back to once — a Back the page took itself (`history.back()`,
     * which the chrome never sees as a history step) — without being sent
     * away again. Only while the tab is still where "always" sent it: see
     * [redirectLanded].
     */
    private var redirectedFrom: String? = null

    /** A page other than a gateway's has committed since the redirect (its destination). */
    private var redirectLanded = false

    /** What is taking the tab away from the page on screen, before anything commits. */
    private enum class Leaving { Link, HistoryStep }

    /**
     * A navigation away from the page on screen is under way and hasn't
     * committed: a link the WebView follows, or the chrome's Back /
     * Forward. An answer arriving now is held ([held]), never acted on —
     * a redirect would cancel that navigation (R1-F1) — and given back
     * if the page stays after all ([stayed]): a link that became a
     * download, or went to another app, or a 204, or a Back that stayed
     * in the document (R1-M1).
     */
    private var leaving: Leaving? = null

    /** The lookup answered (or still running) while [leaving]: run again if the page stays. */
    private var held: Lookup? = null

    /**
     * The document "always" no longer sends anywhere unasked: the user
     * stopped a load on it, or stepped through history on it.
     */
    private var noAutoOpenSeq = -1

    /** The document whose pill the user closed: no offer for it again (R1-M4). */
    private var dismissedSeq = -1

    /**
     * A main-frame document committed at [url]. [traversal]: it is a
     * Back/Forward the chrome asked for — left alone by "always" (Back
     * could never get past it otherwise), and shown with the pill.
     * Returns the lookup to run, also left in [pending].
     */
    fun committed(url: String?, traversal: Boolean): Lookup? {
        seq += 1
        running = null
        offer = null
        pending = null
        leaving = null
        held = null
        val at = url.orEmpty()
        this.url = at
        val shape = GatewayOffer.parse(at)
        val from = redirectedFrom
        if (from != null) {
            when {
                // Back on the page "always" just took the tab away from:
                // stay, this once.
                at == from -> {
                    redirectedFrom = null
                    redirectLanded = false
                    if (shape == null) return null
                    return lookup(at, shape, mayAutoOpen = false)
                }
                // The tab has left the page "always" sent it to.
                redirectLanded -> {
                    redirectedFrom = null
                    redirectLanded = false
                }
                // The destination (or its error page) committed.
                shape == null -> redirectLanded = true
            }
        }
        if (shape == null) return null
        return lookup(at, shape, mayAutoOpen = !traversal)
    }

    private fun lookup(url: String, shape: GatewayOffer.Shape, mayAutoOpen: Boolean): Lookup =
        Lookup(seq, url, shape, mayAutoOpen).also { pending = it }

    /** The screen takes [lookup] to run it; `false` if it was already taken or is stale. */
    fun take(lookup: Lookup): Boolean {
        if (pending !== lookup) return false
        pending = null
        return true
    }

    /**
     * The page moved within its document (`pushState`, a fragment): the
     * destination follows. A Back / Forward the chrome asked for that
     * lands here stayed in the document: no commit is coming.
     */
    fun located(url: String?) {
        if (url != null) this.url = url
        if (leaving == Leaving.HistoryStep) stayed()
    }

    /**
     * The tab starts a load of its own (a submit): an answer still on
     * its way belongs to the page being left, and must neither show nor
     * redirect over what was asked for now.
     */
    fun loadStarted() {
        seq += 1
        running = null
        pending = null
        leaving = null
        held = null
    }

    /**
     * The WebView follows a main-frame link the page started. Its
     * lookup goes on: if the link never commits — a download, a 204 —
     * the page on screen still gets its pill (R1-M1).
     */
    fun linkFollowed() {
        leaving = Leaving.Link
    }

    /**
     * The chrome's Back / Forward. Never redirected over (R1-F1), and if
     * it stays in the document the page keeps its pill, with no redirect:
     * the user stepped through history on it.
     */
    fun historyStep() {
        leaving = Leaving.HistoryStep
        noAutoOpenSeq = seq
    }

    /**
     * Loading stopped (the chrome's Stop, Stay on a `beforeunload`
     * prompt): whatever was leaving didn't, and "always" leaves the page
     * where the user stopped it — the pill is still offered.
     */
    fun stopped() {
        noAutoOpenSeq = seq
        stayed()
    }

    /**
     * `onPageFinished` for [finishedUrl]. For a navigation that ended
     * with no commit (a 204, a failed hop) WebView reports it with that
     * navigation's URL; the page on screen finishing its own load reports
     * its own, and the link is still on its way.
     */
    fun loadFinished(finishedUrl: String?) {
        if (leaving == Leaving.Link && finishedUrl != null && finishedUrl != url) {
            // Inferred, not told: a superseded link's finish looks the
            // same and may land after the next link set out. The pill
            // only, then — never a redirect over that next link.
            noAutoOpenSeq = seq
            stayed()
        }
    }

    /**
     * The navigation that was [leaving] is over and the page on screen
     * stays — a link that became a download or another app's, or one of
     * the cases above. A lookup it held answers again for this page.
     */
    fun stayed() {
        if (leaving == null) return
        leaving = null
        val lookup = held ?: return
        held = null
        if (lookup.seq == seq && running?.isActive != true) pending = lookup
    }

    /**
     * [lookup]'s job ended without answering — cancelled with the screen
     * that ran it (an Activity recreation, R1-M6) — while it is still
     * this document's: back to [pending], for the next screen to run.
     */
    fun interrupted(lookup: Lookup) {
        if (lookup.seq == seq && pending == null) pending = lookup
    }

    /**
     * The address bar or a bookmark asks for an address. That is never
     * the Back the redirect guard protects, so the guard goes: typing the
     * same gateway address twice is redirected twice.
     */
    fun userNavigated() {
        redirectedFrom = null
        redirectLanded = false
    }

    /**
     * [lookup]'s answer: [found] is the offer, or `null`. [verified]: the
     * name's answer was cross-checked — an unverified one is offered but
     * never opened automatically, which would land on the not-cross-checked
     * warning unasked. [alwaysOpen] is the setting now.
     */
    fun answered(lookup: Lookup, found: GatewayOffer.Offer?, verified: Boolean, alwaysOpen: Boolean): Verdict {
        if (lookup.seq != seq || lookup.seq == dismissedSeq) return Verdict.Nothing
        if (leaving != null) {
            // Answered while the tab is on its way elsewhere: kept for
            // the page on screen in case it stays ([stayed]).
            held = lookup
            return Verdict.Nothing
        }
        if (found == null) return Verdict.Nothing
        if (lookup.mayAutoOpen && lookup.seq != noAutoOpenSeq && alwaysOpen && verified) {
            val destination = found.destinationFor(lookup.url) ?: return Verdict.Nothing
            redirectedFrom = lookup.url
            redirectLanded = false
            return Verdict.AutoOpen(destination)
        }
        offer = found
        return Verdict.Show
    }

    /** The user closed the pill: gone for this document (its next commit decides afresh, R1-M4). */
    fun dismissed() {
        dismissedSeq = seq
        offer = null
        held = null
    }

    /** Where the pill opens now: the offer's native address for the page's current URL. */
    fun destination(): String? = offer?.destinationFor(url)
}
