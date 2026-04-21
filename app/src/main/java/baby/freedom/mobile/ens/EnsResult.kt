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

    /** Transport / RPC / decode failure — retryable if [retryable] is true. */
    data class Error(
        override val name: String,
        val reason: String,
        val error: String,
        val retryable: Boolean = false,
    ) : EnsResult()
}
