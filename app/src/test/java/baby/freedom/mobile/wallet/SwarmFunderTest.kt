package baby.freedom.mobile.wallet

import java.math.BigInteger
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SwarmNodeFunder's call (#115). The expected bytes come from ethers v6
 * (`Interface.encodeFunctionData`, `keccak256(abiCoder.encode(...))`), not
 * from the code under test, so a slip in the hand-rolled encoding shows.
 */
class SwarmFunderTest {
    private val node = "0xD5572300E441b77b72bdd318BBFA7b97A1F03096"
    private val nonce = ByteArray(32) { 7 }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `the selector is the deployed contract's`() {
        // keccak256("fundNodeAndBuyStamp(address,uint256,uint256,(uint256,uint8,uint8,bytes32,bool))")[:4],
        // as iOS pins it too.
        assertEquals("834aeb80", hex(SwarmFunder.selector()))
    }

    @Test
    fun `the call data is ethers' encoding, the stamp tuple inline`() {
        val data = SwarmFunder.calldata(
            node, SwarmFunder.XDAI_FOR_NODE_WEI, BigInteger("601234567890123"), BigInteger("4325218560"), 17, nonce, true,
        )
        assertEquals(
            "834aeb80" +
                "000000000000000000000000d5572300e441b77b72bdd318bbfa7b97a1f03096" +
                "00000000000000000000000000000000000000000000000000b1a2bc2ec50000" +
                "000000000000000000000000000000000000000000000000000222d1d4d884cb" +
                "0000000000000000000000000000000000000000000000000000000101cd9900" +
                "0000000000000000000000000000000000000000000000000000000000000011" +
                "0000000000000000000000000000000000000000000000000000000000000010" +
                "0707070707070707070707070707070707070707070707070707070707070707" +
                "0000000000000000000000000000000000000000000000000000000000000001",
            hex(data),
        )
        // 4 + 3 head words + 5 tuple words.
        assertEquals(260, data.size)
    }

    @Test
    fun `the batch id is the postage contract's for a batch the funder creates`() {
        // keccak256(abi.encode(funder, nonce)): createBatch's msg.sender is the funder.
        assertEquals("2278688f7a7334f09055464f539c5b63244bfe7755caee9e769c10f2e3dc5411", SwarmFunder.batchId(nonce))
        assertThrows(IllegalArgumentException::class.java) { SwarmFunder.batchId(ByteArray(31)) }
        assertEquals(32, SwarmFunder.newNonce().size)
    }

    @Test
    fun `the funder address is EIP-55 checksummed, as the send takes it`() {
        assertEquals(SwarmFunder.ADDRESS, SafeProtocol.eip55(SwarmFunder.ADDRESS.lowercase()))
    }

    @Test
    fun `the pool price is read from slot0's first word`() {
        val slot0 = "0x000000000000000000000000000000000000000272eb7ed8bfa0b5c55e8c2197" +
            "00000000000000000000000000000000000000000000000000000000000045f9" + "0".repeat(64 * 5)
        val sqrt = SwarmFunder.sqrtPriceFrom(slot0)!!
        assertEquals(BigInteger("272eb7ed8bfa0b5c55e8c2197", 16), sqrt)
        // About 0.05997 xDAI per xBZZ (token0 = BZZ, 16 decimals; token1 = WXDAI, 18).
        assertEquals("0.0599714", SwarmFunder.spotXdaiPerBzz(sqrt).setScale(7, java.math.RoundingMode.HALF_UP).toPlainString())
        assertNull(SwarmFunder.sqrtPriceFrom("0x"))
        assertNull(SwarmFunder.sqrtPriceFrom("0x" + "0".repeat(64)))
        assertNull(SwarmFunder.sqrtPriceFrom("0x" + "zz".repeat(32)))
    }

    @Test
    fun `the quote takes the pool fee, then the slippage floor`() {
        // sqrtPriceX96 = 4 × 2^96: 16 raw WXDAI per raw BZZ, 0.16 xDAI per xBZZ.
        val sqrt = BigInteger.valueOf(4).shiftLeft(96)
        val oneXdai = BigInteger.TEN.pow(18)
        // 1 xDAI × 0.997 / 0.16 = 6.23125 xBZZ (16 decimals), exactly.
        assertEquals(BigInteger("62312500000000000"), SwarmFunder.expectedBzzOut(oneXdai, sqrt))
        assertEquals(BigInteger("59196875000000000"), SwarmFunder.minBzzOut(SwarmFunder.expectedBzzOut(oneXdai, sqrt)))
    }

    @Test
    fun `the swap is the least xDAI whose floor still covers what's needed`() {
        val random = Random(115)
        val prices = listOf(BigInteger("272eb7ed8bfa0b5c55e8c2197", 16), BigInteger.valueOf(4).shiftLeft(96), BigInteger.ONE.shiftLeft(90))
        repeat(200) {
            val sqrt = prices[it % prices.size]
            val need = BigInteger(60, random).add(BigInteger.ONE)
            val x = SwarmFunder.xdaiForBzz(need, sqrt)
            assertTrue(SwarmFunder.minBzzOut(SwarmFunder.expectedBzzOut(x, sqrt)) >= need)
            assertTrue(SwarmFunder.minBzzOut(SwarmFunder.expectedBzzOut(x.subtract(BigInteger.ONE), sqrt)) < need)
        }
    }

    @Test
    fun `a plan buys the stamp and the deposit even at the slippage floor, and sends the node its xDAI`() {
        val sqrt = BigInteger("272eb7ed8bfa0b5c55e8c2197", 16)
        val amount = BigInteger("4325218560")
        val deposit = BigInteger.TEN.pow(13)
        val plan = SwarmFunder.Plan(node, 17, amount, nonce, deposit, sqrt)
        assertEquals(amount.shiftLeft(17), plan.stampCostPlur)
        assertTrue(plan.minBzz >= plan.stampCostPlur.add(deposit))
        assertEquals(plan.xdaiForSwap.add(SwarmFunder.XDAI_FOR_NODE_WEI), plan.value)
        assertEquals(SwarmFunder.batchId(nonce), plan.batchId)
        val data = plan.calldata()
        // The head: the node, its xDAI, the floor; the tuple: this batch's price, depth, 16, nonce, immutable.
        assertEquals("000000000000000000000000" + node.removePrefix("0x").lowercase(), hex(data.copyOfRange(4, 36)))
        assertEquals(SwarmFunder.XDAI_FOR_NODE_WEI, BigInteger(1, data.copyOfRange(36, 68)))
        assertEquals(plan.minBzz, BigInteger(1, data.copyOfRange(68, 100)))
        assertEquals(amount, BigInteger(1, data.copyOfRange(100, 132)))
        assertEquals(BigInteger.valueOf(17), BigInteger(1, data.copyOfRange(132, 164)))
        assertEquals(BigInteger.valueOf(16), BigInteger(1, data.copyOfRange(164, 196)))
        assertEquals(hex(nonce), hex(data.copyOfRange(196, 228)))
        assertEquals(BigInteger.ONE, BigInteger(1, data.copyOfRange(228, 260)))
        // No deposit to make (the node already has a chequebook): only the stamp.
        val noDeposit = SwarmFunder.Plan(node, 17, amount, nonce, BigInteger.ZERO, sqrt)
        assertTrue(noDeposit.xdaiForSwap < plan.xdaiForSwap && noDeposit.minBzz >= noDeposit.stampCostPlur)
        assertThrows(IllegalArgumentException::class.java) { SwarmFunder.Plan(node, 16, amount, nonce, deposit, sqrt) }
        assertThrows(IllegalArgumentException::class.java) { SwarmFunder.Plan(node, 17, BigInteger.ZERO, nonce, deposit, sqrt) }
    }
}
