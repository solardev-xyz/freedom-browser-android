package baby.freedom.mobile.ens

/**
 * Outcome of [EnsResolver.resolveAddress] (#277): the account a name
 * names on one chain, for the wallet to send to. Every answer carries
 * its [EnsTrust], the same tiers a page reached through a name gets.
 */
sealed class EnsAddressResult {
    abstract val name: String

    /** [address]: lowercase `0x` + 40 hex, never the zero address. */
    data class Ok(override val name: String, val address: String, val trust: EnsTrust) : EnsAddressResult()

    /**
     * Nothing to send to. [reason]: `NO_RESOLVER` (the name isn't set
     * up), `NO_ADDRESS` (no address for this chain), `CHAIN_UNSUPPORTED`
     * (a `.wei`/`.gwei` name off Ethereum), `CHAIN_ID_UNSUPPORTED` (a
     * chain id of 2^31 or more, which has no ENSIP-11 coin type) or
     * `UNSUPPORTED_SYSTEM` (a `.tez` name). [trust] is how far a looked-up absence was checked;
     * `null` when nothing was looked up.
     */
    data class NoAddress(override val name: String, val reason: String, val trust: EnsTrust?) : EnsAddressResult()

    /** Servers disagreed (#96): no answer, and nothing may be sent. */
    data class Conflict(
        override val name: String,
        val subject: EnsResult.Conflict.Subject,
        val groups: List<EnsResult.Conflict.Group>,
        val block: Long?,
    ) : EnsAddressResult()

    /** The lookup failed; [reason] as [EnsResult.Error]'s. */
    data class Error(
        override val name: String,
        val reason: String,
        val error: String,
        val retryable: Boolean,
    ) : EnsAddressResult()

    companion object {
        /** The resolver's internal answer for an address record (see [EnsResolver.ADDRESS_PROTOCOL]). */
        internal fun of(result: EnsResult): EnsAddressResult = when (result) {
            is EnsResult.Ok -> if (result.protocol == EnsResolver.ADDRESS_PROTOCOL) {
                Ok(result.name, result.uri, result.trust)
            } else {
                Error(result.name, "RESOLUTION_ERROR", "not an address record", retryable = false)
            }
            is EnsResult.NotFound -> NoAddress(result.name, result.reason, result.trust)
            is EnsResult.Conflict -> Conflict(result.name, result.subject, result.groups, result.block)
            is EnsResult.Error -> when (result.reason) {
                "CHAIN_UNSUPPORTED", "UNSUPPORTED_SYSTEM" -> NoAddress(result.name, result.reason, null)
                else -> Error(result.name, result.reason, result.error, result.retryable)
            }
            is EnsResult.Unsupported -> Error(result.name, "RESOLUTION_ERROR", "not an address record", retryable = false)
        }
    }
}
