package baby.freedom.mobile.chains

/**
 * An EVM chain the browser knows about (#107): one of the [BuiltInChains]
 * it ships with, or a custom chain the user added in Settings → Chains —
 * typed in by hand or picked from the chainlist.org catalog
 * ([Chainlist]).
 *
 * [rpcUrls] are the chain's public, key-free JSON-RPC endpoints in the
 * order they're tried; every one has passed [RpcUrls.validate]. The
 * user's own RPCs for the chain ([userRpcUrls], #108) come before them.
 */
data class Chain(
    /** EIP-155 chain ID. */
    val id: Long,
    val name: String,
    /** Native currency ticker (`ETH`, `xDAI`). */
    val symbol: String,
    /** Native currency name (`Ether`); falls back to [symbol] when unknown. */
    val currencyName: String = symbol,
    val decimals: Int = 18,
    /** Block explorer base URL, or `null` when the chain has none. */
    val explorerUrl: String? = null,
    val rpcUrls: List<String>,
    val isTestnet: Boolean = false,
    /** Ships with the app; can't be removed. */
    val builtIn: Boolean = false,
    /**
     * RPCs the user added on the chain's Settings page ("Your RPCs",
     * #108), built-in chains included. The chain-data router tries them
     * before [rpcUrls], and one's answer on its own is labelled as the
     * user's own RPC's rather than unverified. Each has passed
     * [RpcUrls.validate]; none is also in [rpcUrls].
     */
    val userRpcUrls: List<String> = emptyList(),
) {
    /** `0x`-prefixed hex chain ID, the EIP-1193 wire format. */
    val hexId: String get() = "0x" + id.toString(16)

    companion object {
        /** Real native assets stay in 0..36; a typo'd 1800 would overflow `10^decimals`. */
        val DECIMALS_RANGE = 0..36

        /** EIP-155 IDs are positive and, per EIP-2294, below `2^53` so JS can hold them. */
        const val MAX_ID = (1L shl 53) - 1

        const val MAX_NAME_LENGTH = 64
        const val MAX_SYMBOL_LENGTH = 16
        const val MAX_RPC_URLS = 16
        const val MAX_USER_RPC_URLS = 10
    }
}

/**
 * The chains the browser ships with — the desktop browser's
 * `src/shared/chains.json` (Ethereum, Gnosis, Base). The RPCs are held to
 * the same bar as a catalog pick ([Chainlist.usableRpc]): key-free, and
 * marked `tracking: "none"` (or unmarked) on chainlist.org — and each one
 * must actually answer JSON-RPC. So desktop's `endpoint-sources.json` list
 * minus Blast API (shut down), Ankr (now keyed), Cloudflare (chainlist
 * marks it `tracking: "yes"`), LlamaRPC (answers with a Cloudflare
 * challenge page) and Merkle (rate-limit errors), plus Pocket Network and
 * 0xRPC, both `tracking: "none"`.
 */
object BuiltInChains {
    val ETHEREUM = Chain(
        id = 1,
        name = "Ethereum",
        symbol = "ETH",
        currencyName = "Ether",
        explorerUrl = "https://etherscan.io",
        rpcUrls = listOf(
            "https://ethereum.publicnode.com",
            "https://1rpc.io/eth",
            "https://eth.drpc.org",
            "https://rpc.flashbots.net",
            "https://eth.api.pocket.network",
            "https://0xrpc.io/eth",
        ),
        builtIn = true,
    )

    val GNOSIS = Chain(
        id = 100,
        name = "Gnosis Chain",
        symbol = "xDAI",
        explorerUrl = "https://gnosisscan.io",
        rpcUrls = listOf(
            "https://rpc.gnosischain.com",
            "https://gnosis-rpc.publicnode.com",
            "https://gnosis.drpc.org",
        ),
        builtIn = true,
    )

    val BASE = Chain(
        id = 8453,
        name = "Base",
        symbol = "ETH",
        currencyName = "Ether",
        explorerUrl = "https://basescan.org",
        rpcUrls = listOf(
            "https://mainnet.base.org",
            "https://base-rpc.publicnode.com",
            "https://base.drpc.org",
            "https://1rpc.io/base",
            "https://base.api.pocket.network",
        ),
        builtIn = true,
    )

    /** In the order Settings lists them. */
    val ALL: List<Chain> = listOf(ETHEREUM, GNOSIS, BASE)

    fun isBuiltIn(id: Long): Boolean = ALL.any { it.id == id }
}
