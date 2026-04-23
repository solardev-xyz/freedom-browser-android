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
        bzzRegex.find(uri)?.let {
            hashToName[it.groupValues[1].lowercase()] = lowerName
            nameToProtocol[lowerName] = "bzz"
            return
        }
        ipfsRegex.find(uri)?.let {
            hashToName[it.groupValues[1]] = lowerName
            nameToProtocol[lowerName] = "ipfs"
            return
        }
        ipnsRegex.find(uri)?.let {
            hashToName[it.groupValues[1]] = lowerName
            nameToProtocol[lowerName] = "ipns"
        }
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

    fun forget(hashOrCid: String) {
        hashToName.remove(hashOrCid)
        hashToName.remove(hashOrCid.lowercase())
    }

    /** Tests only. */
    fun clear() {
        hashToName.clear()
        nameToProtocol.clear()
    }
}
