package baby.freedom.mobile.wallet

import baby.freedom.mobile.chains.Chain
import java.math.BigInteger

/**
 * An asset the wallet shows a balance for (#104): a chain's native
 * currency ([address] null) or an ERC-20 contract on it.
 */
data class Token(
    val chainId: Long,
    /** The ERC-20 contract, EIP-55 checksummed; null for the native currency. */
    val address: String?,
    val symbol: String,
    val name: String,
    val decimals: Int,
) {
    /** `chainId:native` or `chainId:0x…` in lower case — desktop's and iOS's token key. */
    val key: String get() = "$chainId:${address?.lowercase() ?: "native"}"

    val isNative: Boolean get() = address == null
}

/**
 * The tokens the wallet knows (#104): iOS's `TokenRegistry` builtins —
 * the same list as desktop's `src/shared/tokens.json` for these two
 * chains, bar Gnosis' USDC.e, which iOS leaves out — plus each chain's
 * native currency, taken from the [Chain] itself.
 */
object TokenRegistry {
    const val ETHEREUM = 1L
    const val GNOSIS = 100L

    /** The chains the wallet shows balances on, in the order it shows them. */
    val WALLET_CHAIN_IDS = listOf(ETHEREUM, GNOSIS)

    val builtins: List<Token> = listOf(
        Token(ETHEREUM, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", "USD Coin", 6),
        Token(ETHEREUM, "0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", "Tether USD", 6),
        Token(ETHEREUM, "0x6B175474E89094C44Da98b954EedeAC495271d0F", "DAI", "Dai Stablecoin", 18),
        Token(ETHEREUM, "0x1aBaEA1f7C830bD89Acc67eC4af516284b1bC33c", "EURC", "Euro Coin", 6),
        Token(ETHEREUM, "0x19062190B1925b5b6689D7073fDfC8c2976EF8Cb", "BZZ", "Swarm Token", 16),
        Token(GNOSIS, "0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da", "xBZZ", "Swarm Token", 16),
        Token(GNOSIS, "0x420CA0f9B9b604cE0fd9C18EF134C705e5Fa3430", "EURe", "Monerium EUR emoney", 18),
    )

    /** [chain]'s native currency first, then its ERC-20s in the order above. */
    fun tokens(chain: Chain): List<Token> = listOf(native(chain)) + builtins.filter { it.chainId == chain.id }

    fun native(chain: Chain) = Token(chain.id, null, chain.symbol, chain.currencyName, chain.decimals)
}

/** The two ERC-20 bits a balance needs: the `balanceOf` call and its answer. */
internal object Erc20 {
    private const val BALANCE_OF = "0x70a08231"
    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    /** `balanceOf(holder)` call data. */
    fun balanceOfData(holder: String): String {
        require(ADDRESS.matches(holder)) { "not an address" }
        return BALANCE_OF + "0".repeat(24) + holder.substring(2).lowercase()
    }

    /**
     * The `uint256` a `balanceOf` returns, or null when [hex] isn't one
     * 32-byte word — `0x` (no contract there) is not a zero balance.
     */
    fun decodeUint256(hex: String): BigInteger? {
        if (hex.length != 66 || !hex.startsWith("0x")) return null
        val digits = hex.substring(2)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return BigInteger(digits, 16)
    }
}

/** Token amounts for display. */
object TokenAmounts {
    /**
     * [raw] base units as a decimal number with at most [maxFraction]
     * digits after the point, cut (never rounded up — a balance must
     * not show more than there is) and without trailing zeros. A
     * non-zero amount too small to show reads `<0.000001`, never `0`.
     */
    fun format(raw: BigInteger, decimals: Int, maxFraction: Int = 6): String {
        require(raw.signum() >= 0 && decimals >= 0 && maxFraction >= 1)
        val divisor = BigInteger.TEN.pow(decimals)
        val (whole, rest) = raw.divideAndRemainder(divisor)
        val grouped = "%,d".format(java.util.Locale.ROOT, whole)
        if (rest.signum() == 0) return grouped
        val fraction = rest.toString().padStart(decimals, '0').take(maxFraction).trimEnd('0')
        if (fraction.isEmpty()) {
            return if (whole.signum() == 0) "<0.${"0".repeat(maxFraction - 1)}1" else grouped
        }
        return "$grouped.$fraction"
    }
}
