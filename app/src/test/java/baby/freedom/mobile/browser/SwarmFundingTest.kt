package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.ChainTrustsForTest
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.SwarmFundLabel
import baby.freedom.mobile.wallet.SwarmFunder
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The stamp the wallet buys for the node (#115), from its call going out to the node connecting it. */
class SwarmFundingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val node = "0xD5572300E441b77b72bdd318BBFA7b97A1F03096"
    private val batch = SwarmFunder.batchId(ByteArray(32) { 7 })
    private val label = SwarmFundLabel(node, batch, 17, 2)
    private val hash = "0x" + "aa".repeat(32)
    private val connects = mutableListOf<String>()

    private fun funding(file: File = File(tmp.root, "funding.json")) =
        SwarmFunding(file, connect = { connects += it; true }, spends = emptyFlow())

    private fun status(stage: SendStatus.Stage, l: SwarmFundLabel? = label): SendStatus {
        val gnosis = BuiltInChains.GNOSIS
        val from = WalletAccount(0, "Account 1", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
        val data = byteArrayOf(0x83.toByte(), 0x4a, 0xeb.toByte(), 0x80.toByte())
        return SendStatus(
            SendQuote(
                SendRequest(gnosis, TokenRegistry.native(gnosis), from, SwarmFunder.ADDRESS, BigInteger.TEN, DappCall(null, data, null, swarm = l)),
                EthTransaction(100, BigInteger.ONE, BigInteger.valueOf(400_000), SwarmFunder.ADDRESS, BigInteger.TEN, data, EthTransaction.Fees.Legacy(BigInteger.ONE)),
                BigInteger.TEN.pow(18), null, 0L, ChainTrustsForTest.unverified,
            ),
            stage,
            hash.takeIf { stage != SendStatus.Stage.Signing },
        )
    }

    @Test
    fun `going out it's recorded, mined it's connected once, and the record outlives the process`() {
        val f = funding()
        f.noteSend(status(SendStatus.Stage.Signing))
        assertEquals(SwarmFunding.Pending(node, batch, 17, 2, null, mined = false), f.pending.value)
        f.noteSend(status(SendStatus.Stage.Pending))
        assertEquals(hash, f.pending.value!!.hash)
        assertTrue(connects.isEmpty())
        // Not mined yet: nothing to connect.
        assertFalse(f.connectNow())

        f.noteSend(status(SendStatus.Stage.Confirmed(48_500_000, null)))
        assertTrue(f.pending.value!!.mined)
        assertEquals(listOf(batch), connects)
        // The sender replays its last status: not a second connect.
        f.noteSend(status(SendStatus.Stage.Confirmed(48_500_000, null)))
        assertEquals(1, connects.size)

        // A connect that failed (the node was off) is offered again after a restart.
        val again = funding()
        assertEquals(f.pending.value, again.pending.value)
        assertTrue(again.connectNow())
        assertEquals(listOf(batch, batch), connects)
        again.forget()
        assertNull(funding().pending.value)
    }

    @Test
    fun `one that reverted or certainly never went out bought nothing`() {
        val f = funding()
        f.noteSend(status(SendStatus.Stage.Pending))
        f.noteSend(status(SendStatus.Stage.Reverted(1, null)))
        assertNull(f.pending.value)

        f.noteSend(status(SendStatus.Stage.Signing))
        f.noteSend(status(SendStatus.Stage.Failed("no", mayHaveGone = true)))
        // It may have gone out: kept.
        assertEquals(batch, f.pending.value!!.batchId)
        f.noteSend(status(SendStatus.Stage.Failed("no", mayHaveGone = false)))
        assertNull(f.pending.value)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `a finished send with no record, or anyone else's, starts nothing`() {
        val f = funding()
        // Already connected (and so cleared) before the process restarted.
        f.noteSend(status(SendStatus.Stage.Confirmed(1, null)))
        f.noteSend(status(SendStatus.Stage.Pending, l = null))
        assertNull(f.pending.value)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `an unreadable record reads as none`() {
        val file = File(tmp.root, "funding.json").apply { writeText("{not json") }
        assertNull(funding(file).pending.value)
        file.writeText("""{"node":"$node","batchId":"xyz","depth":17,"days":2,"hash":"","mined":true}""")
        assertNull(funding(file).pending.value)
    }

    @Test
    fun `funding waits for light mode, the wallet's identity, and a stamp already bought to be connected`() {
        val light = NodeInfo(status = NodeStatus.Running, accountAddress = node, walletIdentity = true, lightMode = true)
        assertNull(fundNodeBlockedReason(light, null))
        assertTrue(fundNodeBlockedReason(light.copy(lightMode = false), null)!!.contains("light mode"))
        assertTrue(fundNodeBlockedReason(light.copy(walletIdentity = false), null) != null)
        val pending = SwarmFunding.Pending(node, batch, 17, 2, hash, mined = false)
        assertEquals("A stamp your wallet is buying for the node is still going out.", fundNodeBlockedReason(light, pending))
        assertEquals("Connect the stamp your wallet already bought for the node first.", fundNodeBlockedReason(light, pending.copy(mined = true)))
    }
}
