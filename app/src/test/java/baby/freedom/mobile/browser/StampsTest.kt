package baby.freedom.mobile.browser

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StampsTest {
    private val id = "ab".repeat(32)

    @Test
    fun `reads the gateway's stamps, keeping an unread TTL unknown`() {
        val body = """{"stamps":[
            {"batchID":"$id","utilization":2,"usable":true,"label":"","depth":17,"amount":"0","bucketDepth":16,
             "blockNumber":0,"immutableFlag":true,"exists":true,"batchTTL":172795},
            {"batchID":"0x${"CD".repeat(32)}","utilization":0,"usable":false,"depth":20,"bucketDepth":16,
             "immutableFlag":false,"batchTTL":315360000},
            {"batchID":"nope","depth":17},
            {"batchID":"${"ef".repeat(32)}","depth":18,"batchTTL":-1}
        ]}"""
        val stamps = stampsFrom(body)!!
        assertEquals(listOf(id, "cd".repeat(32), "ef".repeat(32)), stamps.map { it.id })
        val first = stamps[0]
        assertTrue(first.usable)
        assertTrue(first.immutable)
        assertEquals(172795L, first.ttlSeconds)
        // 2 of a bucket's 2^(17-16) = 2 slots: full.
        assertEquals(1.0, first.usedFraction, 0.0)
        // ant's stand-in when it can't read the chain, and bee's "unknown".
        assertNull(stamps[1].ttlSeconds)
        assertNull(stamps[2].ttlSeconds)
        assertEquals(0.0, stamps[1].usedFraction, 0.0)
        assertEquals(emptyList<PostageBatch>(), stampsFrom("""{"stamps":[]}"""))
        assertNull(stampsFrom("""{"code":503}"""))
        assertNull(stampsFrom("not json"))
    }

    @Test
    fun `capacity follows bee's effective sizes`() {
        assertEquals("40.9 kB", formatStampBytes(effectiveStampBytes(17)))
        assertEquals("6.1 MB", formatStampBytes(effectiveStampBytes(18)))
        assertEquals("629 MB", formatStampBytes(effectiveStampBytes(20)))
        assertEquals("2.4 GB", formatStampBytes(effectiveStampBytes(21)))
        assertEquals("43 GB", formatStampBytes(effectiveStampBytes(24)))
        assertEquals(0L, effectiveStampBytes(16))
        // 4 KiB × 2^17.
        assertEquals("537 MB", formatStampBytes(PostageBatch(id, true, 17, 16, 0, true, null).theoreticalBytes))
        assertEquals("999 B", formatStampBytes(999))
    }

    @Test
    fun `time left in its two largest units`() {
        assertEquals("2 days", formatStampTtl(2 * 86_400L))
        assertEquals("1 day 23 hours", formatStampTtl(86_400L + 23 * 3_600 + 59))
        assertEquals("5 hours 1 minute", formatStampTtl(5 * 3_600L + 60))
        assertEquals("12 minutes", formatStampTtl(12 * 60L + 5))
        assertEquals("Under a minute", formatStampTtl(30))
        assertEquals("Expired", formatStampTtl(0))
    }

    @Test
    fun `reads ant's quote, and only a usable one`() {
        val o = JSONObject(
            """{"depth":17,"days":2,"amount_per_chunk":"4325218560","total_cost_plur":"566914269429760",
            "total_cost_bzz":"0.0566914269429760","settlement_deposit_plur":"10000000000000",
            "settlement_deposit_bzz":"0.001","capacity_bytes":536870912,"account_bzz":"56587603839515",
            "account_bzz_display":"0.0056","account_xdai":"4986670673902293754","account_xdai_display":"4.98",
            "needed_bzz":"520326665590245","needed_bzz_display":"0.052","xdai_required":"26000000000000000",
            "xdai_required_display":"0.026","xdai_to_send":"0","xdai_to_send_display":"0","sufficient_funds":true}""",
        )
        val q = stampQuoteFrom(o)!!
        assertEquals(BigInteger("4325218560"), q.amountPerChunk)
        assertEquals(BigInteger("26000000000000000"), q.xdaiRequired)
        assertEquals("0.001", q.depositBzz)
        assertTrue(q.sufficientFunds)
        // No deposit once the chequebook has one; no quote without a price.
        assertNull(stampQuoteFrom(JSONObject(o.toString()).put("settlement_deposit_plur", "0"))!!.depositBzz)
        assertNull(stampQuoteFrom(JSONObject(o.toString()).put("amount_per_chunk", "0")))
        assertNull(stampQuoteFrom(JSONObject(o.toString()).apply { remove("xdai_required") }))
        assertEquals("0.026 xDAI", withUnit("0.026", "xDAI"))
        assertEquals("0.026 xDAI", withUnit("0.026 xDAI", "xDAI"))
    }

    @Test
    fun `stamps need a running light node, and spending the wallet's identity`() {
        val light = NodeInfo(status = NodeStatus.Running, lightMode = true, walletIdentity = true)
        assertNull(stampsBlockedReason(light))
        assertNull(stampSpendBlockedReason(light))
        assertNotNull(stampsBlockedReason(light.copy(lightMode = false)))
        assertNotNull(stampsBlockedReason(light.copy(status = NodeStatus.Stopped)))
        assertNotNull(stampsBlockedReason(light.copy(status = NodeStatus.Starting)))
        // The device-only key can read its stamps but never buys.
        assertNull(stampsBlockedReason(light.copy(walletIdentity = false)))
        assertTrue(stampSpendBlockedReason(light.copy(walletIdentity = false))!!.contains("wallet"))
    }

    @Test
    fun `a search for owned stamps and a spend never start over each other`() {
        val idle = StampClient.Discovery.Idle
        val searching = StampClient.Discovery.Running
        val found = StampClient.Discovery.Finished(Result.success(listOf(id)))
        val buying = StampClient.Spend.Running(StampClient.Kind.Buy, null)
        assertTrue(StampClient.canSpend(StampClient.Spend.Idle, idle))
        assertTrue(StampClient.canSpend(StampClient.Spend.Idle, found))
        assertFalse(StampClient.canSpend(buying, idle))
        assertFalse(StampClient.canSpend(StampClient.Spend.Idle, searching))
        assertNull(discoverStatusText(idle))
        assertTrue(discoverStatusText(searching)!!.startsWith("Searching"))
        assertEquals(discoverOutcomeText(1), discoverStatusText(found))
        assertEquals(
            "Couldn't look: no RPC",
            discoverStatusText(StampClient.Discovery.Finished(Result.failure(IllegalStateException("no RPC")))),
        )
    }

    @Test
    fun `a spend says what it's doing and how it ended`() {
        assertNull(spendStatusText(StampClient.Spend.Idle))
        assertTrue(spendStatusText(StampClient.Spend.Running(StampClient.Kind.Buy, null))!!.startsWith("Buying"))
        assertTrue(spendStatusText(StampClient.Spend.Running(StampClient.Kind.Extend, id))!!.startsWith("Extending"))
        assertEquals("Stamp extended.", spendStatusText(StampClient.Spend.Done(StampClient.Kind.Extend, id)))
        assertEquals(
            "Buying the stamp failed: not enough xDAI",
            spendStatusText(StampClient.Spend.Failed(StampClient.Kind.Buy, null, "not enough xDAI")),
        )
    }

    @Test
    fun `batch ids`() {
        assertEquals(id, normalizeBatchId(" 0x${id.uppercase()} "))
        assertNull(normalizeBatchId(id.dropLast(1)))
        assertNull(normalizeBatchId("zz".repeat(32)))
        assertEquals("abababab…abababab", shortBatchId(id))
        assertFalse(STAMP_BUY_DAYS.any { it < 2 })
    }

    @Test
    fun `the confirmation's xDAI bound includes the gas on top of the swap`() {
        val q = stampQuoteFrom(
            JSONObject(
                """{"depth":17,"days":2,"amount_per_chunk":"4325218560","total_cost_plur":"1","total_cost_bzz":"0.05",
                "capacity_bytes":536870912,"xdai_required":"26000000000000000","xdai_required_display":"0.026",
                "sufficient_funds":true}""",
            ),
        )!!
        val buy = spendCostText(q, buy = true)
        assertTrue(buy, buy.contains("swaps 0.026 xDAI and pays up to 0.05 xDAI of gas on top, across up to 5 transactions"))
        val extend = spendCostText(q, buy = false)
        assertTrue(extend, extend.contains("swaps 0.026 xDAI and pays up to 0.03 xDAI of gas on top, across up to 3 transactions"))
        // Never the old claim that the swap figure already bounds the gas.
        assertFalse(buy.contains("at most 0.026 xDAI including gas"))
    }

    @Test
    fun `the stated swap bound is never below the one SpendGuard enforces`() {
        // ant truncates its display to 4 decimals: 0.017899 xDAI reads "0.0178".
        val q = stampQuoteFrom(
            JSONObject(
                """{"depth":17,"days":2,"amount_per_chunk":"4325218560","total_cost_plur":"1","total_cost_bzz":"0.05",
                "capacity_bytes":536870912,"xdai_required":"17899000000000000","xdai_required_display":"0.0178",
                "sufficient_funds":true}""",
            ),
        )!!
        val text = spendCostText(q, buy = true)
        assertTrue(text, text.contains("about 0.0178 xDAI including gas"))
        assertTrue(text, text.contains("At most, it swaps 0.017899 xDAI and"))
        // Past 6 decimals it rounds up, never down.
        assertEquals("0.0179 xDAI", formatXdaiCeiling(java.math.BigInteger("17899000000000001")))
        assertEquals("0.000001 xDAI", formatXdaiCeiling(java.math.BigInteger.ONE))
        assertEquals("0.026 xDAI", formatXdaiCeiling(java.math.BigInteger("26000000000000000")))
    }

    @Test
    fun `a spend stays reachable with the node off`() {
        val light = NodeInfo(status = NodeStatus.Running, lightMode = true, walletIdentity = true)
        val off = NodeInfo()
        assertTrue(stampsEntryShown(light, StampClient.Spend.Idle))
        assertFalse(stampsEntryShown(off, StampClient.Spend.Idle))
        assertFalse(stampsEntryShown(light.copy(lightMode = false), StampClient.Spend.Idle))
        // Turned off mid-buy: its progress, then its outcome, can still be opened.
        assertTrue(stampsEntryShown(off, StampClient.Spend.Running(StampClient.Kind.Buy, null)))
        assertTrue(stampsEntryShown(off, StampClient.Spend.Failed(StampClient.Kind.Buy, null, "reverted")))
        assertTrue(stampsEntryShown(off, StampClient.Spend.Done(StampClient.Kind.Extend, id)))
    }
}
