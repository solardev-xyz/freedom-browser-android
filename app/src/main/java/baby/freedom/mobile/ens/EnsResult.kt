package baby.freedom.mobile.ens

/**
 * Outcome of an ENS `contenthash` lookup.
 *
 * Mirrors the shape of the result the Freedom Browser's JS resolver returns
 * (`{type, name, protocol, uri, decoded, reason}`) but as a proper Kotlin
 * sealed hierarchy so callers can `when`-exhaust it.
 */
sealed class EnsResult {
    abstract val name: String

    /** Successful resolution to a content-addressed URI. */
    data class Ok(
        override val name: String,
        /**
         * `bzz`, `ipfs`, `ipns` — the scheme of [uri]. A `.tez` name's
         * website record may also be `http` / `https`.
         */
        val protocol: String,
        /**
         * `bzz://<hash>`, `ipfs://<cidv0>`, `ipns://<cidv0>`. A `.tez`
         * record can add a base path (`ipfs://<cid>/site`), or be a web URL.
         */
        val uri: String,
        /** Just the decoded hash / CID, for caching / display. */
        val decoded: String,
        /**
         * Whether independent RPC servers agreed on this answer (#96).
         * No default: a result must say how it was checked, so a path
         * that forgets to label one can't pass it off as verified.
         */
        val trust: EnsTrust,
        /**
         * Tezos Domains only: [uri] is an `http(s)` `web:redirect_url`,
         * navigated to as is — no address-bar path appended.
         */
        val redirect: Boolean = false,
    ) : EnsResult()

    /**
     * Name has no contenthash we can display, or no resolver. [reason] is
     * one of `NO_RESOLVER`, `NO_CONTENTHASH`, `EMPTY_CONTENTHASH`.
     */
    data class NotFound(
        override val name: String,
        val reason: String,
        /**
         * Whether servers agreed there's nothing here (#96) — one
         * server's "no resolver" is a claim like any other answer.
         */
        val trust: EnsTrust,
        val error: String? = null,
    ) : EnsResult()

    /**
     * Name resolves, but the contenthash codec is not one Freedom mobile
     * can load (e.g. IPFS without an embedded IPFS node). [codec] is the
     * raw multicodec prefix for debugging.
     */
    data class Unsupported(
        override val name: String,
        val codec: String,
        val rawContentHash: String,
        /** As for [Ok.trust] (#96). */
        val trust: EnsTrust,
    ) : EnsResult()

    /**
     * RPC servers gave different answers at the same block and none of
     * them had the agreement the quorum needs (#96). Not an answer: the
     * browser shows a warning instead of loading anything.
     */
    data class Conflict(
        override val name: String,
        /** What they disagreed about. */
        val subject: Subject,
        /** One entry per distinct answer, largest first. */
        val groups: List<Group>,
        /** Block the answers were read at (the anchor's), if one was fixed. */
        val block: Long?,
    ) : EnsResult() {
        enum class Subject {
            /** The name's record, read at a block the servers agreed on. */
            RECORD,
            /** Which block that is: the servers' hashes for it differ. */
            BLOCK,
            /**
             * Tezos Domains: which block is the chain's head — too far
             * apart to share an anchor, with no majority either way.
             */
            HEAD,
        }

        /** [answer] in readable form, and the hosts that gave it. */
        data class Group(val answer: String, val hosts: List<String>)
    }

    /** Transport / RPC / decode failure — retryable if [retryable] is true. */
    data class Error(
        override val name: String,
        val reason: String,
        val error: String,
        val retryable: Boolean = false,
    ) : EnsResult()
}

/**
 * How far an answer ([EnsResult.Ok], [EnsResult.NotFound],
 * [EnsResult.Unsupported]) was checked (#96, #100).
 *
 * [verified]: either proven — the Colibri verifier checked a proof of
 * the record against Ethereum's sync committee on this device
 * ([Source.COLIBRI]) — or cross-checked: at least [EnsQuorum.M]
 * independent RPC servers returned byte-identical answers at a block
 * whose hash a majority of them agreed on ([Source.RPC]). Otherwise only
 * one server's word stands behind it — because only one answered, or
 * because too few servers were reachable to agree on a block at all —
 * and the browser asks before loading it.
 */
data class EnsTrust(
    val verified: Boolean,
    /**
     * Hosts that returned this answer: the RPC servers, or for a
     * [Source.COLIBRI] answer the prover(s) whose proof was checked.
     */
    val agreed: List<String> = emptyList(),
    /** Hosts that returned a different one (outvoted). */
    val dissented: List<String> = emptyList(),
    /** Block number the answer was read at; `null` for `latest`. */
    val block: Long? = null,
    /**
     * Unverified because fewer than [EnsQuorum.MIN_PROVIDERS] RPC
     * endpoints are enabled in Settings (#102), so no cross-check was
     * even possible — as opposed to too few of them answering this time.
     */
    val tooFewServers: Boolean = false,
    /**
     * What stands behind the answer. Defaults to [Source.RPC], the
     * weaker claim: only the Colibri path says [Source.COLIBRI].
     */
    val source: Source = Source.RPC,
) {
    enum class Source {
        /** RPC servers' answers: cross-checked if [verified], one server's word if not. */
        RPC,

        /** A Colibri proof, checked on this device (#100). Always [verified]. */
        COLIBRI,
    }

    /** Proven by the Colibri verifier, not just agreed on by servers. */
    val proven: Boolean get() = verified && source == Source.COLIBRI

    companion object {
        /**
         * Verified with no provenance: for results built outside the
         * resolver (fixtures, test seams). Never a default — each use
         * says so explicitly.
         */
        val ASSUMED = EnsTrust(verified = true)

        /**
         * Not (yet) cross-checked: what the resolver's decoding starts
         * from before the vote labels the answer, so an unlabelled one
         * fails closed.
         */
        val UNCHECKED = EnsTrust(verified = false)
    }
}
