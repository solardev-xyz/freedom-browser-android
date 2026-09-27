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
         * Tezos Domains only: [uri] is an `http(s)` `web:redirect_url`,
         * navigated to as is — no address-bar path appended.
         */
        val redirect: Boolean = false,
        /**
         * Tezos Domains only: `false` when a single RPC provider gave this
         * answer and no second one could confirm it. The browser loads it
         * but says so.
         */
        val verified: Boolean = true,
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
