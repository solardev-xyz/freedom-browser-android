package baby.freedom.swarm

import baby.freedom.swarm.CreditSpend.BUCKET_MS
import baby.freedom.swarm.CreditSpend.DAY_MS
import baby.freedom.swarm.CreditSpend.MAX_SAMPLE_GAP_MS
import baby.freedom.swarm.CreditSpend.SAMPLE_MS
import baby.freedom.swarm.CreditSpend.WEEK_MS
import baby.freedom.swarm.CreditSpend.record
import baby.freedom.swarm.CreditSpend.spendWithin
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Browsing credit's recent spend (#543): desktop's browsing-credit-service.test.js cases, ported. */
class CreditSpendTest {
    private val t0 = 1_790_856_000_000L // 2026-10-01 12:00 UTC
    private fun xbzz(s: String) = BigDecimal(s).movePointRight(16).toBigIntegerExact()
    private fun row(peer: String, sent: Any) = SettlementRow(peer, BigInteger(sent.toString()))
    private fun big(n: Long) = BigInteger.valueOf(n)

    /** [record], as if the previous reading (the one [ledger] holds) had been at [lastAt]. */
    private fun recordAfter(ledger: SpendLedger, rows: List<SettlementRow>, at: Long, lastAt: Long) =
        record(ledger.copy(sampled = lastAt), rows, at)

    @Test
    fun `the first reading is a baseline, not spend`() {
        val (ledger, changed) = record(null, listOf(SettlementRow("aa", xbzz("0.5"))), t0)
        assertTrue(changed)
        assertEquals(t0, ledger.since)
        assertEquals(mapOf("aa" to SpendPeer(xbzz("0.5"), t0)), ledger.peers)
        assertEquals(BigInteger.ZERO, spendWithin(ledger, WEEK_MS, t0))
    }

    @Test
    fun `growth per peer is spend, bucketed by hour`() {
        var ledger = record(null, listOf(row("aa", 100)), t0).first
        ledger = record(ledger, listOf(row("aa", 150), row("bb", 30)), t0 + 60_000).first
        ledger = record(ledger, listOf(row("aa", 170), row("bb", 45)), t0 + 120_000).first
        // aa: +50 +20; bb: first seen at 30 (a baseline), then +15.
        assertEquals(listOf(SpendBucket(Math.floorDiv(t0, BUCKET_MS) * BUCKET_MS, big(85))), ledger.buckets)
        assertEquals(big(85), spendWithin(ledger, DAY_MS, t0 + 120_000))
    }

    @Test
    fun `a peer first seen after the baseline counts nothing for its lifetime total`() {
        var ledger = record(null, listOf(row("aa", 100)), t0).first
        val (next, changed) = record(ledger, listOf(row("aa", 100), SettlementRow("bb", xbzz("2"))), t0 + 10 * 60_000)
        assertTrue(changed)
        ledger = next
        assertEquals(SpendPeer(xbzz("2"), t0 + 10 * 60_000), ledger.peers["bb"])
        assertEquals(BigInteger.ZERO, spendWithin(ledger, WEEK_MS, t0 + 10 * 60_000))
        ledger = record(ledger, listOf(SettlementRow("bb", xbzz("2") + big(7))), t0 + 20 * 60_000).first
        assertEquals(big(7), spendWithin(ledger, DAY_MS, t0 + 20 * 60_000))
    }

    @Test
    fun `peers unseen for longer than the kept history are dropped, and come back as a baseline`() {
        var ledger = record(null, listOf(row("aa", 100), row("bb", 50)), t0).first
        for (d in 1..7) ledger = record(ledger, listOf(row("aa", 100)), t0 + d * DAY_MS).first
        assertEquals(setOf("aa", "bb"), ledger.peers.keys)
        val (next, changed) = record(ledger, listOf(row("aa", 100)), t0 + 8 * DAY_MS)
        assertTrue(changed)
        ledger = next
        assertEquals(setOf("aa"), ledger.peers.keys)
        // Sampled continuously: bb is a baseline because it was dropped, not because of a gap.
        ledger = recordAfter(ledger, listOf(row("aa", 100), row("bb", 80)), t0 + 9 * DAY_MS, t0 + 9 * DAY_MS - SAMPLE_MS).first
        assertEquals(BigInteger.ZERO, spendWithin(ledger, WEEK_MS, t0 + 9 * DAY_MS))
    }

    @Test
    fun `an unchanged figure does not rewrite the ledger until its last-seen is a day old`() {
        val ledger = record(null, listOf(row("aa", 100)), t0).first
        assertFalse(record(ledger, listOf(row("aa", 100)), t0 + DAY_MS - 1).second)
        val (next, changed) = record(ledger, listOf(row("aa", 100)), t0 + DAY_MS)
        assertTrue(changed)
        assertEquals(SpendPeer(big(100), t0 + DAY_MS), next.peers["aa"])
    }

    @Test
    fun `a peer that drops out and comes back with the same figure adds nothing`() {
        var ledger = record(null, listOf(row("aa", 100), row("bb", 50)), t0).first
        val (next, changed) = record(ledger, listOf(row("aa", 100)), t0 + 1000)
        assertFalse(changed)
        ledger = next
        // The gateway's peer in another case reads as the same peer.
        val rows = CreditSpend.rowsFrom("""{"settlements":[{"peer":"aa","sent":"100"},{"peer":"BB","received":"0","sent":"50"}]}""")!!
        ledger = record(ledger, rows, t0 + 2000).first
        assertEquals(BigInteger.ZERO, spendWithin(ledger, WEEK_MS, t0 + 2000))
    }

    @Test
    fun `a peer that drops out and comes back grown is a baseline, however recent the last reading`() {
        // /settlements lists only connected peers: bb was paid, disconnected,
        // and is listed again three days of five-minute readings later. When
        // it was paid has no reading to place it; aa, listed throughout, still counts.
        var ledger = record(null, listOf(row("aa", 100), row("bb", 100)), t0).first
        var at = t0
        while (at < t0 + 3 * DAY_MS) {
            at += SAMPLE_MS
            ledger = record(ledger, listOf(row("aa", 100)), at).first
        }
        assertEquals(setOf("aa"), ledger.listed)
        at += SAMPLE_MS
        ledger = record(ledger, listOf(row("aa", 130), row("bb", 150)), at).first
        assertEquals(big(30), spendWithin(ledger, DAY_MS, at))
        assertEquals(SpendPeer(big(150), at), ledger.peers["bb"])
        // Listed in both readings from there on, its growth counts again.
        at += SAMPLE_MS
        ledger = record(ledger, listOf(row("aa", 130), row("bb", 155)), at).first
        assertEquals(big(35), spendWithin(ledger, DAY_MS, at))
    }

    @Test
    fun `a lower figure never counts as negative spend`() {
        var ledger = record(null, listOf(row("aa", 100)), t0).first
        ledger = record(ledger, listOf(row("aa", 40)), t0 + 1000).first
        assertEquals(big(100), ledger.peers.getValue("aa").sent)
        ledger = record(ledger, listOf(row("aa", 130)), t0 + 2000).first
        assertEquals(big(30), spendWithin(ledger, DAY_MS, t0 + 2000))
    }

    @Test
    fun `day and week windows, and old buckets are dropped`() {
        var ledger = record(null, listOf(row("aa", 0)), t0).first
        ledger = record(ledger, listOf(row("aa", 10)), t0).first
        ledger = recordAfter(ledger, listOf(row("aa", 30)), t0 + 3 * DAY_MS, t0 + 3 * DAY_MS - SAMPLE_MS).first
        val at = t0 + 3 * DAY_MS + 1000
        assertEquals(big(20), spendWithin(ledger, DAY_MS, at))
        assertEquals(big(30), spendWithin(ledger, WEEK_MS, at))

        ledger = recordAfter(ledger, listOf(row("aa", 31)), t0 + 9 * DAY_MS, t0 + 9 * DAY_MS - SAMPLE_MS).first
        // The day-0 bucket is past the kept 8 days; day 3's is inside the week.
        assertEquals(listOf(big(20), big(1)), ledger.buckets.map { it.plur })
        assertEquals(big(21), spendWithin(ledger, WEEK_MS, t0 + 9 * DAY_MS))
        assertEquals(big(1), spendWithin(ledger, DAY_MS, t0 + 9 * DAY_MS))
    }

    @Test
    fun `growth across a gap in the sampling is a baseline, not spend in this hour`() {
        // The node off for days, or :node not running, while the chequebook paid.
        var ledger = record(null, listOf(row("aa", 100)), t0).first
        val back = t0 + 5 * DAY_MS
        val (next, changed) = record(ledger, listOf(SettlementRow("aa", xbzz("3"))), back)
        assertTrue(changed)
        ledger = next
        assertEquals(SpendPeer(xbzz("3"), back), ledger.peers["aa"])
        assertEquals(emptyList<SpendBucket>(), ledger.buckets)
        // Sampling again from there dates the growth as usual.
        ledger = record(ledger, listOf(SettlementRow("aa", xbzz("3") + big(9))), back + SAMPLE_MS).first
        assertEquals(big(9), spendWithin(ledger, DAY_MS, back + SAMPLE_MS))
        // The edge: a reading exactly MAX_SAMPLE_GAP_MS later still counts; one past it does not.
        val edge = back + SAMPLE_MS + MAX_SAMPLE_GAP_MS
        assertEquals(big(19), spendWithin(record(ledger, listOf(SettlementRow("aa", xbzz("3") + big(19))), edge).first, DAY_MS, edge))
        assertEquals(big(9), spendWithin(record(ledger, listOf(SettlementRow("aa", xbzz("3") + big(19))), edge + 1).first, DAY_MS, edge + 1))
        // A previous reading that listed nobody (a file from before `listed` was kept) dates nothing either.
        val unlisted = ledger.copy(listed = emptySet())
        assertEquals(big(9), spendWithin(record(unlisted, listOf(SettlementRow("aa", xbzz("4"))), back + 2 * SAMPLE_MS).first, DAY_MS, back + 2 * SAMPLE_MS))
    }

    @Test
    fun `a last-seen in the future (clock stepped back) is clamped to now`() {
        val future = t0 + WEEK_MS
        val old = SpendLedger(t0, t0, mapOf("aa" to SpendPeer(big(100), future), "bb" to SpendPeer(big(50), future)), emptyList())
        var (ledger, changed) = record(old, listOf(row("aa", 100)), t0)
        assertTrue(changed)
        assertEquals(mapOf("aa" to SpendPeer(big(100), t0), "bb" to SpendPeer(big(50), t0)), ledger.peers)
        // aa then gets its daily refresh, and bb is pruned on the normal clock.
        record(ledger, listOf(row("aa", 100)), t0 + DAY_MS).let { (l, c) -> ledger = l; changed = c }
        assertTrue(changed)
        assertEquals(SpendPeer(big(100), t0 + DAY_MS), ledger.peers["aa"])
        for (d in 2..8) ledger = record(ledger, listOf(row("aa", 100)), t0 + d * DAY_MS).first
        assertEquals(setOf("aa"), ledger.peers.keys)
    }

    @Test
    fun `a saved sampled time in the future dates nothing, and is rewritten to now`() {
        val old = SpendLedger(t0, t0 + 2 * DAY_MS, mapOf("aa" to SpendPeer(big(100), t0)), emptyList())
        val at = t0 + DAY_MS
        val grown = xbzz("1") + big(100)
        var (ledger, changed) = record(old, listOf(SettlementRow("aa", grown)), at)
        assertTrue(changed)
        assertEquals(SpendPeer(grown, at), ledger.peers["aa"])
        assertEquals(BigInteger.ZERO, spendWithin(ledger, DAY_MS, at))
        // Unchanged figures still rewrite the future `sampled` so it isn't kept.
        record(old.copy(peers = mapOf("aa" to SpendPeer(big(100), at))), listOf(row("aa", 100)), at).let { (l, c) -> ledger = l; changed = c }
        assertTrue(changed)
        assertEquals(at, ledger.sampled)
        // An explicit previous time later than now dates nothing either.
        ledger = recordAfter(ledger, listOf(row("aa", 150)), at + SAMPLE_MS, at + 2 * SAMPLE_MS).first
        assertEquals(BigInteger.ZERO, spendWithin(ledger, DAY_MS, at + SAMPLE_MS))
    }

    @Test
    fun `reads the gateway's settlements and ignores malformed rows`() {
        val rows = CreditSpend.rowsFrom(
            """{"totalReceived":"0","totalSent":"10","settlements":[
                {"peer":"aa","received":"0","sent":"x"},{"sent":"1"},null,{"peer":"bb","sent":"-5"},
                {"peer":"cc","received":"0","sent":"10"},{"peer":"dd","sent":7}]}""",
        )!!
        assertEquals(listOf(row("cc", 10), row("dd", 7)), rows)
        assertNull(CreditSpend.rowsFrom("""{"code":503,"message":"chain initializing"}"""))
        assertNull(CreditSpend.rowsFrom("not json"))
    }

    @Test
    fun `the store keeps each chequebook's ledger across a restart, and survives a bad file`() {
        val dir = Files.createTempDirectory("credit-spend").toFile()
        try {
            val file = File(dir, "credit-spend.json")
            var ledger = record(null, listOf(row("aa", 100)), t0).first
            ledger = record(ledger, listOf(row("aa", 160)), t0 + SAMPLE_MS).first
            CreditSpendStore(file).set("0xabc", ledger)
            assertEquals(ledger, CreditSpendStore(file).get("0xabc"))
            assertEquals(setOf("aa"), CreditSpendStore(file).get("0xabc")!!.listed)
            // A reading kept but not saved is this process's last reading; the file keeps the saved one.
            val unsaved = record(ledger, listOf(row("bb", 1)), t0 + 2 * SAMPLE_MS).first
            CreditSpendStore(file).let { s -> s.set("0xabc", unsaved, save = false); assertEquals(unsaved, s.get("0xabc")) }
            assertEquals(ledger, CreditSpendStore(file).get("0xabc"))
            assertNull(CreditSpendStore(file).get("0xdef"))
            assertFalse(File(dir, "credit-spend.json.tmp").exists())
            // A file that isn't a store reads as no history, and the next save replaces it.
            file.writeText("{")
            val store = CreditSpendStore(file)
            assertNull(store.get("0xabc"))
            store.set("0xabc", ledger)
            assertEquals(big(60), spendWithin(CreditSpendStore(file).get("0xabc"), DAY_MS, t0 + SAMPLE_MS))
        } finally {
            dir.deleteRecursively()
        }
    }
}
