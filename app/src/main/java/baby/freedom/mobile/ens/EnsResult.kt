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
        /** `bzz`, `ipfs`, `ipns` — the scheme of [uri]. */
        val protocol: String,
        /** `bzz://<hash>`, `ipfs://<cidv0>`, `ipns://<cidv0>`. */
        val uri: String,
        /** Just the decoded hash / CID, for caching / display. */
        val decoded: String,
        /**
         * Whether independent RPC servers agreed on this answer (#96).
         * Defaults to verified so hand-built fixtures and test seams read
         * as the ordinary case; [EnsResolver] always sets it.
         */
        val trust: EnsTrust = EnsTrust.ASSUMED,
    ) : EnsResult()

    /**
     * Name has no contenthash we can display, or no resolver. [reason] is
     * one of `NO_RESOLVER`, `NO_CONTENTHASH`, `EMPTY_CONTENTHASH`.
     */
    data class NotFound(
        override val name: String,
        val reason: String,
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
 * How far an [EnsResult.Ok] was cross-checked (#96).
 *
 * [verified]: at least [EnsQuorum.M] independent RPC servers returned
 * byte-identical answers at a block whose hash a majority of them agreed
 * on. Otherwise only one server's word stands behind it — because only
 * one answered, or because too few servers were reachable to agree on a
 * block at all — and the browser asks before loading it.
 */
data class EnsTrust(
    val verified: Boolean,
    /** Hosts that returned this answer. */
    val agreed: List<String> = emptyList(),
    /** Hosts that returned a different one (outvoted). */
    val dissented: List<String> = emptyList(),
    /** Block number the answer was read at; `null` for `latest`. */
    val block: Long? = null,
) {
    companion object {
        /** The default for results built outside the resolver. */
        val ASSUMED = EnsTrust(verified = true)
    }
}
