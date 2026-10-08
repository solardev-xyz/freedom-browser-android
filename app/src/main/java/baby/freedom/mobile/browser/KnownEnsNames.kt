package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsTrust
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime registry of `<content-hash|cid> → <ens name>` and
 * `<ens name> → <protocol>` mappings, populated every time the ENS resolver
 * returns a `bzz://|ipfs://|ipns://` URI for a name.
 *
 * This is what lets the address bar show `bzz://swarm.eth` consistently:
 *  - after navigation under the resolved manifest (handled by
 *    [BrowserState.Override]);
 *  - after tab switches / new-tab loads of a bare `bzz://<hash>`, where no
 *    override exists for the tab but the hash is still known;
 *  - after back/forward to a URL first loaded under a different tab.
 *
 * Mirrors `state.knownEnsNames` + `state.ensProtocols` from
 * `freedom-browser/src/renderer/lib/state.js`, with the same semantics:
 * in-memory only, session-scoped (cleared when the process dies).
 *
 * Normal tabs share [KnownEnsNames] itself; private tabs (#86) have a
 * registry of their own ([of]), dropped when the private session ends
 * ([privateSessionEnded], #464) — a name a private tab resolved must
 * not name a hash, badge a page or serve a `<name>.ens.…` origin
 * instantly in a normal tab afterwards.
 *
 * TODO (porting §2, IPFS integration): mirror `extractEnsResolutionMetadata`'s
 * CIDv0 → CIDv1 base32 alias so that a subdomain-gateway redirect
 * `http://<Qm…>.ipfs.localhost` → `http://<bafybei…>.ipfs.localhost` still
 * collapses back to `ens://name` in the address bar.
 */
open class EnsNameRegistry internal constructor(
    /** A private session's registry (#464): its lookups stay out of the normal session's caches. */
    val private: Boolean,
) {
    private val hashToName = ConcurrentHashMap<String, String>()
    private val nameToProtocol = ConcurrentHashMap<String, String>()
    private val nameToUri = ConcurrentHashMap<String, String>()
    private val nameToTrust = ConcurrentHashMap<String, EnsTrust>()

    /**
     * When each name's answer was last [record]ed — only read under the
     * lock, to hand a shared root to the name that claimed it most
     * recently rather than to whichever one hash order yields first.
     */
    private val nameToSeq = HashMap<String, Long>()
    private var seq = 0L

    private val bzzRegex = Regex("^bzz://([a-fA-F0-9]+)")
    private val ipfsRegex = Regex("^ipfs://([A-Za-z0-9]+)")
    private val ipnsRegex = Regex("^ipns://([A-Za-z0-9.-]+)")

    /**
     * Extract the `(hash|cid, protocol)` from a resolved [uri] and remember
     * that it was reached via [name]. Safe to call with any URI — unrecognized
     * schemes are ignored.
     *
     * [trust] is how the answer was checked (#96) — what the address
     * bar's trust shield shows for the name (#97, [TrustShield]). No
     * default, for the reason [baby.freedom.mobile.ens.EnsResult.Ok.trust]
     * has none: an answer recorded without saying how it was checked
     * must not come out of here looking verified.
     *
     * A name that moved to a new root stops naming its old one (#97
     * R1-F2): a raw load of the old hash is not the name's page any
     * more, and must not be shown — or vouched for — as the name.
     */
    @Synchronized
    fun record(uri: String, name: String, trust: EnsTrust) {
        val lowerName = name.lowercase()
        nameToUri[lowerName] = uri
        nameToTrust[lowerName] = trust
        nameToSeq[lowerName] = ++seq
        val root = rootOf(uri)
        if (root != null) {
            hashToName[root.first] = lowerName
            nameToProtocol[lowerName] = root.second
        } else {
            nameToProtocol.remove(lowerName)
        }
        releaseStaleRoots(lowerName)
    }

    /**
     * The name's answer as one snapshot — its URI and how it was
     * checked, from the same [record] — for a document's trust shield
     * (#97), so the dialog's "Resolves to" can't come from a later
     * answer than the trust beside it.
     */
    @Synchronized
    fun answerFor(name: String): Pair<String, EnsTrust>? {
        val lowerName = name.lowercase()
        val uri = nameToUri[lowerName] ?: return null
        val trust = nameToTrust[lowerName] ?: return null
        return uri to trust
    }

    /**
     * Is [contentUri] (`bzz://<hash>/…`, `ipfs://<cid>/…`) under the
     * root [name] currently resolves to? `false` for a URI with no
     * content root, or a name with no answer.
     */
    fun isCurrentRoot(name: String, contentUri: String): Boolean {
        val root = rootOf(contentUri) ?: return false
        val current = uriFor(name)?.let(::rootOf) ?: return false
        return root.first == current.first
    }

    /**
     * Hash-to-name mappings to [lowerName] whose root it no longer
     * resolves to: handed to the other name that most recently
     * recorded that root, or dropped. The root [lowerName] resolves to
     * now stays with it — it is the name recorded last there (R2-F1).
     * Callers hold the lock.
     */
    private fun releaseStaleRoots(lowerName: String) {
        val own = nameToUri[lowerName]?.let(::rootOf)?.first
        for (hash in hashToName.filterValues { it == lowerName }.keys) {
            if (hash == own) continue
            val other = nameToUri.entries
                .filter { (other, uri) -> other != lowerName && rootOf(uri)?.first == hash }
                .maxByOrNull { nameToSeq[it.key] ?: 0L }
                ?.key
            if (other != null) hashToName[hash] = other else hashToName.remove(hash, lowerName)
        }
    }

    /** `(hash-to-name key, protocol)` of a `bzz|ipfs|ipns` [uri]. */
    private fun rootOf(uri: String): Pair<String, String>? {
        bzzRegex.find(uri)?.let { return it.groupValues[1].lowercase() to "bzz" }
        ipfsRegex.find(uri)?.let { return it.groupValues[1] to "ipfs" }
        ipnsRegex.find(uri)?.let { return it.groupValues[1] to "ipns" }
        return null
    }

    /**
     * Look up the ENS name that resolved to [hashOrCid]. For Swarm hashes
     * the lookup is case-insensitive (hex); IPFS CIDs and IPNS names are
     * matched exactly.
     */
    fun nameFor(hashOrCid: String): String? {
        hashToName[hashOrCid]?.let { return it }
        return hashToName[hashOrCid.lowercase()]
    }

    /** "bzz" | "ipfs" | "ipns" of a content [uri] (`ipfs://<cid>/…`), else `null`. */
    fun protocolOf(uri: String): String? = rootOf(uri)?.second

    /** "bzz" | "ipfs" | "ipns" for any name resolved this session. */
    fun protocolFor(name: String): String? = nameToProtocol[name.lowercase()]

    /**
     * Full resolved content URI (`bzz://<hash>` etc.) for a name, if
     * resolved this session. The virtual-origin interceptor uses this
     * to serve `<name>.ens.…` hosts without re-running the lookup.
     */
    fun uriFor(name: String): String? = nameToUri[name.lowercase()]

    /**
     * How the name's current answer ([uriFor]) was checked — the last
     * lookup that answered, typed or a document's re-check (#97).
     */
    fun trustFor(name: String): EnsTrust? = nameToTrust[name.lowercase()]

    fun forget(hashOrCid: String) {
        hashToName.remove(hashOrCid)
        hashToName.remove(hashOrCid.lowercase())
    }

    /**
     * [name] answered that it points at no loadable content any more
     * (#99): drop what it used to resolve to — its URI, its protocol,
     * and the hash-to-name mapping of that old root — so the address bar
     * stops describing content the name no longer points at. A root that
     * another name still resolves to keeps a mapping, to that name.
     */
    @Synchronized
    fun forgetName(name: String) {
        val lowerName = name.lowercase()
        nameToProtocol.remove(lowerName)
        nameToUri.remove(lowerName)
        nameToTrust.remove(lowerName)
        nameToSeq.remove(lowerName)
        releaseStaleRoots(lowerName)
    }

    /** Forget every name: a private session ending, or tests. */
    @Synchronized
    open fun clear() {
        hashToName.clear()
        nameToProtocol.clear()
        nameToUri.clear()
        nameToTrust.clear()
        nameToSeq.clear()
    }
}

object KnownEnsNames : EnsNameRegistry(private = false) {
    /**
     * The live private session's registry. Replaced, not emptied, when
     * the session ends: a lookup that was still running then holds the
     * old one ([of] read when it started) and records into that, never
     * into the next session's.
     */
    @Volatile
    private var privateSession = EnsNameRegistry(private = true)

    /** The registry a tab's lookups and address bar use: [private] tabs' own, or the normal session's. */
    fun of(private: Boolean): EnsNameRegistry = if (private) privateSession else this

    /** The last private tab has closed: nothing it resolved is known any more (#464). */
    fun privateSessionEnded() {
        privateSession = EnsNameRegistry(private = true)
    }

    /** Tests only: both sessions. */
    @Synchronized
    override fun clear() {
        super.clear()
        privateSessionEnded()
    }
}
