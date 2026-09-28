package baby.freedom.mobile.ens

/**
 * Ethereum name systems the resolver speaks. Mirrors desktop's
 * `NAME_SYSTEMS` (`freedom-browser/src/main/ens-resolver.js`) and iOS's
 * `NameSystem.swift`.
 *
 * WNS (`.wei`, [z0r0z/wei-names](https://github.com/z0r0z/wei-names))
 * and GNS (`.gwei`, [lucadonnoh/gwei-names](https://github.com/lucadonnoh/gwei-names))
 * store resolver records directly on NameNFT-style ERC-721 registry
 * contracts, keyed by the ENS namehash of the full name and answering
 * the ENS resolver signatures (`contenthash(bytes32)`). So namehash,
 * calldata and contenthash decoding are shared with ENS; only the call
 * target differs — the registry contract instead of the ENS Universal
 * Resolver — and there is no CCIP-Read on that path.
 *
 * `.box` is ENS: 3DNS `.box` names live in the ENS registry.
 *
 * Tezos Domains (`.tez`) isn't Ethereum at all: [EnsResolver] hands
 * those names to [TezosDomainsResolver], and the rest of the browser's
 * name pipeline (the `ens://` compat alias, the name-derived virtual
 * origin, error pages) treats them like any other name.
 */
enum class NameSystem(
    val label: String,
    /** Name suffix this system owns; `null` for ENS (the fallback). */
    val suffix: String?,
    /** NameNFT registry on mainnet; `null` = resolve via the Universal Resolver. */
    val contractAddress: String?,
) {
    ENS("ENS", null, null),
    WNS("WNS", ".wei", "0x0000000000696760E15f265e828DB644A0c242EB"),
    GNS("GNS", ".gwei", "0x9D51D507BC7264d4fE8Ad1cf7Fe191933A0a81d6"),
    TEZOS("Tezos Domains", ".tez", null),
    ;

    companion object {
        /** Which system resolves [name]. Anything not `.wei`/`.gwei`/`.tez` is ENS. */
        fun forName(name: String): NameSystem {
            val lower = name.lowercase()
            return entries.firstOrNull { it.suffix != null && lower.endsWith(it.suffix) } ?: ENS
        }

        /**
         * Every suffix the browser routes through name resolution: ENS's
         * `.eth` / `.box` plus each other system's own.
         */
        val navigableSuffixes: List<String> =
            listOf(".eth", ".box") + entries.mapNotNull { it.suffix }
    }
}
