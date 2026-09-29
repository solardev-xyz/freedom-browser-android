package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.SecureRandom

/**
 * Funding the Swarm node and buying its postage stamp in one wallet
 * transaction (#115): `SwarmNodeFunder.fundNodeAndBuyStamp` on Gnosis,
 * the contract desktop's `swarm-funder.js` and iOS's `Swarm/Funder/` use,
 * priced from the BZZ/WXDAI Uniswap v3 pool it swaps through.
 *
 * One call, with `value = xdaiForSwap + xdaiForNode`: the contract wraps
 * and swaps `xdaiForSwap` for xBZZ (reverting below `minBzzOut`), sends
 * `xdaiForNode` to the node, buys the batch with the node as its owner,
 * sends the node the xBZZ the batch didn't take, and refunds any dust to
 * the wallet. The batch is `keccak256(abi.encode(funder, nonce))` — the
 * postage contract's id for a batch the funder creates — so it's known
 * before the transaction is sent. Stateless, admin-less and verified on
 * Blockscout (#115 in the PR has the source).
 *
 * Unlike iOS, which passes an empty stamp (depth 0: fund only), this
 * buys the stamp too; and the price math is exact integer arithmetic on
 * the pool's `sqrtPriceX96` rather than floating point.
 */
object SwarmFunder {
    const val CHAIN_ID = 100L

    /** `SwarmNodeFunder`, deployed 2026-04-24; the same address desktop and iOS pin. */
    const val ADDRESS = "0x508994B55C53E84d2d600A55da05f751aEf658d2"

    /** The Uniswap v3 0.3% BZZ/WXDAI pool the funder swaps through: token0 = BZZ (16 decimals), token1 = WXDAI (18). */
    const val POOL = "0x7583b9C573FA4FB5Ea21C83454939c4Cf6aacBc3"

    const val SIGNATURE = "fundNodeAndBuyStamp(address,uint256,uint256,(uint256,uint8,uint8,bytes32,bool))"

    /** `slot0()`: the pool's first word is `sqrtPriceX96`. */
    const val SLOT0_DATA = "0x3850c7bd"

    /** The pool's fee, 0.3%, taken off the xDAI going in. */
    const val POOL_FEE_BPS = 30

    /**
     * The slippage allowed below the spot quote, 5% as on desktop and
     * iOS: a swap the pool can't fill at this reverts, and only the
     * network fee is lost.
     */
    const val SLIPPAGE_BPS = 500

    /**
     * The xDAI sent on to the node: its gas for setting up the chequebook
     * and what it sends later. 0.05 xDAI, desktop's and iOS's amount.
     */
    val XDAI_FOR_NODE_WEI: BigInteger = BigInteger("50000000000000000")

    /** Bee's and ant's collision-bucket depth. */
    const val BUCKET_DEPTH = 16

    /** Everything is immutable, as the node's own buys are. */
    const val IMMUTABLE = true

    private val TWO_192: BigInteger = BigInteger.ONE.shiftLeft(192)
    private val BPS = BigInteger.valueOf(10_000)

    fun selector(): ByteArray = Keccak256.digest(SIGNATURE.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    /** A fresh random batch nonce. */
    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also(random::nextBytes)

    /** The id (64 lowercase hex) the postage contract gives the batch the funder creates with [nonce]. */
    fun batchId(nonce: ByteArray): String {
        require(nonce.size == 32) { "a 32-byte nonce" }
        return hex(Keccak256.digest(addressWord(ADDRESS) + nonce))
    }

    /** The pool's `sqrtPriceX96` from its `slot0()` answer, or null if it isn't one. */
    fun sqrtPriceFrom(slot0: String): BigInteger? {
        val h = slot0.removePrefix("0x")
        if (h.length < 64 || !h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return BigInteger(h.substring(0, 64), 16).takeIf { it.signum() > 0 && it.bitLength() <= 160 }
    }

    /** The spot price in xDAI per xBZZ, for showing. */
    fun spotXdaiPerBzz(sqrtPriceX96: BigInteger): BigDecimal =
        BigDecimal(sqrtPriceX96.pow(2)).divide(BigDecimal(TWO_192.multiply(BigInteger.valueOf(100))), 18, RoundingMode.HALF_UP)

    /** PLUR the pool gives for [xdaiWei] at spot, after its fee (the swap's own price impact left out). */
    fun expectedBzzOut(xdaiWei: BigInteger, sqrtPriceX96: BigInteger): BigInteger =
        xdaiWei.multiply(BPS.subtract(BigInteger.valueOf(POOL_FEE_BPS.toLong()))).multiply(TWO_192)
            .divide(BPS.multiply(sqrtPriceX96.pow(2)))

    /** The slippage floor under [expected]: the swap reverts if it yields less. */
    fun minBzzOut(expected: BigInteger): BigInteger =
        expected.multiply(BPS.subtract(BigInteger.valueOf(SLIPPAGE_BPS.toLong()))).divide(BPS)

    /** The least xDAI whose slippage floor still covers [plur] of xBZZ at [sqrtPriceX96]. */
    fun xdaiForBzz(plur: BigInteger, sqrtPriceX96: BigInteger): BigInteger {
        require(plur.signum() > 0) { "nothing to buy" }
        val num = plur.multiply(BPS).multiply(BPS).multiply(sqrtPriceX96.pow(2))
        val den = BPS.subtract(BigInteger.valueOf(POOL_FEE_BPS.toLong()))
            .multiply(BPS.subtract(BigInteger.valueOf(SLIPPAGE_BPS.toLong()))).multiply(TWO_192)
        var x = num.add(den).subtract(BigInteger.ONE).divide(den)
        // Integer division in the forward math can land a few wei short; step up to where it holds.
        while (minBzzOut(expectedBzzOut(x, sqrtPriceX96)) < plur) x = x.add(BigInteger.ONE)
        return x
    }

    /**
     * One fund-and-buy: [node] gets the batch ([depth] deep, [amountPerChunk]
     * per chunk, from the node's own price quote) and [XDAI_FOR_NODE_WEI];
     * the swap is sized so that even at its slippage floor it covers the
     * batch and [depositPlur] more — the chequebook's settlement deposit
     * the node makes from it when it connects the batch.
     */
    data class Plan(
        /** EIP-55. */
        val node: String,
        val depth: Int,
        val amountPerChunk: BigInteger,
        val nonce: ByteArray,
        val depositPlur: BigInteger,
        val sqrtPriceX96: BigInteger,
    ) {
        init {
            require(depth in 17..40) { "depth" }
            require(amountPerChunk.signum() > 0) { "amount" }
            require(nonce.size == 32) { "nonce" }
            require(depositPlur.signum() >= 0) { "deposit" }
            require(sqrtPriceX96.signum() > 0) { "price" }
        }

        /** What the batch costs, PLUR: `amountPerChunk × 2^depth`. */
        val stampCostPlur: BigInteger get() = amountPerChunk.shiftLeft(depth)

        /** The xBZZ the swap must yield at least. */
        val bzzNeededPlur: BigInteger get() = stampCostPlur.add(depositPlur)
        val xdaiForSwap: BigInteger get() = xdaiForBzz(bzzNeededPlur, sqrtPriceX96)
        val expectedBzz: BigInteger get() = expectedBzzOut(xdaiForSwap, sqrtPriceX96)
        val minBzz: BigInteger get() = minBzzOut(expectedBzz)
        val xdaiForNode: BigInteger get() = XDAI_FOR_NODE_WEI

        /** The transaction's value: the swap plus the node's xDAI. */
        val value: BigInteger get() = xdaiForSwap.add(xdaiForNode)
        val batchId: String get() = batchId(nonce)

        /** Call data for `fundNodeAndBuyStamp(node, xdaiForNode, minBzz, (amountPerChunk, depth, 16, nonce, true))`. */
        fun calldata(): ByteArray = calldata(node, xdaiForNode, minBzz, amountPerChunk, depth, nonce, IMMUTABLE)

        override fun equals(other: Any?) = other is Plan && node == other.node && depth == other.depth &&
            amountPerChunk == other.amountPerChunk && nonce.contentEquals(other.nonce) && depositPlur == other.depositPlur &&
            sqrtPriceX96 == other.sqrtPriceX96

        override fun hashCode() = listOf(node, depth, amountPerChunk, nonce.contentHashCode(), depositPlur, sqrtPriceX96).hashCode()
    }

    /**
     * The call data, ABI-encoded: the selector, the three head words, and
     * the stamp tuple inline — all five of its members are static, so it
     * has no offset word.
     */
    fun calldata(
        node: String,
        xdaiForNode: BigInteger,
        minBzzOut: BigInteger,
        amountPerChunk: BigInteger,
        depth: Int,
        nonce: ByteArray,
        immutable: Boolean,
    ): ByteArray {
        require(depth in 0..255 && nonce.size == 32)
        return selector() + addressWord(node) + uint(xdaiForNode) + uint(minBzzOut) +
            uint(amountPerChunk) + uint(BigInteger.valueOf(depth.toLong())) + uint(BigInteger.valueOf(BUCKET_DEPTH.toLong())) +
            nonce + uint(if (immutable) BigInteger.ONE else BigInteger.ZERO)
    }

    private fun addressWord(address: String): ByteArray {
        val h = address.removePrefix("0x").removePrefix("0X")
        require(h.length == 40 && h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "not an address" }
        return ByteArray(12) + ByteArray(20) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun uint(v: BigInteger): ByteArray {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "out of range" }
        val b = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - b.size) + b
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
