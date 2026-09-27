package baby.freedom.mobile.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime registry of `<content-hash|cid> → <ens name>` and
 * `<ens name> → <protocol>` mappings, populated every time the ENS resolver
 * returns a `bzz://|ipfs://|ipns://` URI for a name.
 *
 * This is what lets the address bar show `ens://swarm.eth` consistently:
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
 * TODO (porting §2, IPFS integration): mirror `extractEnsResolutionMetadata`'s
 * CIDv0 → CIDv1 base32 alias so that a subdomain-gateway redirect
 * `http://<Qm…>.ipfs.localhost` → `http://<bafybei…>.ipfs.localhost` still
 * collapses back to `ens://name` in the address bar.
 */
object KnownEnsNames {
    private val hashToName = ConcurrentHashMap<String, String>()
    private val nameToProtocol = ConcurrentHashMap<String, String>()
    private val nameToUri = ConcurrentHashMap<String, String>()

    private val bzzRegex = Regex("^bzz://([a-fA-F0-9]+)")
    private val ipfsRegex = Regex("^ipfs://([A-Za-z0-9]+)")
    private val ipnsRegex = Regex("^ipns://([A-Za-z0-9.-]+)")

    /**
     * Extract the `(hash|cid, protocol)` from a resolved [uri] and remember
     * that it was reached via [name]. Safe to call with any URI — unrecognized
     * schemes are ignored.
     */
    fun record(uri: String, name: String) {
        val lowerName = name.lowercase()
        nameToUri[lowerName] = uri
        val (hash, protocol) = rootOf(uri) ?: return
        hashToName[hash] = lowerName
        nameToProtocol[lowerName] = protocol
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

    /** "bzz" | "ipfs" | "ipns" for any name resolved this session. */
    fun protocolFor(name: String): String? = nameToProtocol[name.lowercase()]

    /**
     * Full resolved content URI (`bzz://<hash>` etc.) for a name, if
     * resolved this session. The virtual-origin interceptor uses this
     * to serve `<name>.ens.…` hosts without re-running the lookup.
     */
    fun uriFor(name: String): String? = nameToUri[name.lowercase()]

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
        val stillNamed = nameToUri.entries.mapNotNull { (other, uri) ->
            rootOf(uri)?.let { it.first to other }
        }.toMap()
        for (hash in hashToName.filterValues { it == lowerName }.keys) {
            val other = stillNamed[hash]
            if (other != null) hashToName[hash] = other else hashToName.remove(hash, lowerName)
        }
    }

    /** Tests only. */
    fun clear() {
        hashToName.clear()
        nameToProtocol.clear()
        nameToUri.clear()
    }
}
