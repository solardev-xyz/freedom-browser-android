package baby.freedom.mobile.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** x402 allowances and payment history (#140): capped, windowed, per site and token, revocable, never throwing. */
class X402StoreTest {
    private class MemoryStore : DataStore<Preferences> {
        private val lock = Mutex()
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(data.value).also { data.value = it } }
    }

    private class BrokenStore(private val error: IOException) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
    }

    private var now = 1_800_000_000_000L
    private val site = "https://api.example"
    private val usdc = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    private val hour = 3_600_000L
    private val me = "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
    private val other = "0x1111111111111111111111111111111111111111"

    /** Whom the test payments ([payment]) and allowances pay. */
    private val payee = usdc

    private fun store() = X402Store(MemoryStore()) { now }

    private suspend fun X402Store.grant(
        cap: Long,
        spent: Long = 0,
        origin: String = site,
        chainId: Long = 8453,
        asset: String = usdc,
        account: String = me,
        each: Long = cap,
    ) = grant(origin, chainId, asset, account, payee, BigInteger.valueOf(each), "USDC", 6, BigInteger.valueOf(cap), hour, BigInteger.valueOf(spent))

    @Test
    fun `an allowance pays up to its cap, counting the payment that granted it`() = runBlocking {
        val s = store()
        assertTrue(s.grant(cap = 30, spent = 10))
        val a = s.allowances.first().single()
        assertEquals(BigInteger.valueOf(20), a.remaining)
        assertEquals(usdc.lowercase(), a.asset)
        assertNotNull(s.covering(s.allowances.first(), site, 8453, usdc, me, payee, BigInteger.valueOf(20)))
        assertNull(s.covering(s.allowances.first(), site, 8453, usdc, me, payee, BigInteger.valueOf(21)))
        assertTrue(s.consume(site, 8453, usdc.uppercase().replace("0X", "0x"), me, payee, BigInteger.valueOf(15)))
        assertFalse(s.consume(site, 8453, usdc, me, payee, BigInteger.valueOf(6)))
        assertTrue(s.consume(site, 8453, usdc, me, payee, BigInteger.valueOf(5)))
        // Used up: it's gone from the list, and pays nothing more.
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
        assertFalse(s.consume(site, 8453, usdc, me, payee, BigInteger.ONE))
    }

    @Test
    fun `an allowance is for one site, one chain and one token`() = runBlocking {
        val s = store()
        s.grant(cap = 100)
        assertFalse(s.consume("https://other.example", 8453, usdc, me, payee, BigInteger.ONE))
        assertFalse(s.consume(site, 1, usdc, me, payee, BigInteger.ONE))
        assertFalse(s.consume(site, 8453, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", me, payee, BigInteger.ONE))
        assertTrue(s.consume(site, 8453, usdc, me, payee, BigInteger.ONE))
    }

    @Test
    fun `an allowance ends with its window, and a clock set back doesn't stretch it`() = runBlocking {
        val s = store()
        s.grant(cap = 100)
        now += hour - 1
        assertTrue(s.consume(site, 8453, usdc, me, payee, BigInteger.ONE))
        now += 1
        assertFalse(s.consume(site, 8453, usdc, me, payee, BigInteger.ONE))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())

        val t = store()
        t.grant(cap = 100)
        now -= 24 * hour
        assertFalse(t.consume(site, 8453, usdc, me, payee, BigInteger.ONE))
    }

    @Test
    fun `a revoked allowance pays nothing, and a new grant replaces the old one`() = runBlocking {
        val s = store()
        s.grant(cap = 100, spent = 90)
        s.grant(cap = 50)
        assertEquals(BigInteger.valueOf(50), s.allowances.first().single().remaining)
        assertTrue(s.revoke(site, 8453, usdc, me))
        assertFalse(s.consume(site, 8453, usdc, me, payee, BigInteger.ONE))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
    }

    @Test
    fun `nonsense grants are refused`() = runBlocking {
        val s = store()
        assertFalse(s.grant(cap = 10, spent = 11))
        assertFalse(s.grant(cap = 0))
        assertFalse(s.grant(site, 8453, usdc, me, payee, BigInteger.TEN, "USDC", 6, BigInteger.TEN, 0, BigInteger.ZERO))
        assertFalse(s.consume(site, 8453, usdc, me, payee, BigInteger.ZERO))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
    }

    @Test
    fun `payments racing for the last of an allowance can't both have it`() = runBlocking {
        val s = store()
        s.grant(cap = 10)
        val results = (1..20).map { async { s.consume(site, 8453, usdc, me, payee, BigInteger.ONE) } }.awaitAll()
        assertEquals(10, results.count { it })
    }

    private fun payment(id: String, status: X402Store.Status = X402Store.Status.PENDING) = X402Store.Payment(
        id = id, at = now, origin = site, url = "$site/paid?" + "q".repeat(5000), chainId = 8453, asset = usdc,
        symbol = "USDC", decimals = 6, amount = BigInteger.valueOf(10_000), payTo = usdc, from = me,
        auto = false, nonce = "0x" + "11".repeat(32), status = status,
    )

    @Test
    fun `the history lists every payment newest first, and settles each once`() = runBlocking {
        val s = store()
        assertTrue(s.record(payment("a")))
        assertTrue(s.record(payment("b")))
        assertEquals(listOf("b", "a"), s.history.first().map { it.id })
        assertEquals(X402Store.MAX_URL_CHARS, s.history.first().first().url.length)
        s.settle("a", X402Store.Status.REFUSED, 402)
        s.settle("a", X402Store.Status.PAID)
        val a = s.history.first().single { it.id == "a" }
        assertEquals(X402Store.Status.REFUSED, a.status)
        assertEquals(402, a.httpStatus)
        s.settleStale()
        assertEquals(X402Store.Status.UNCONFIRMED, s.history.first().single { it.id == "b" }.status)
    }

    @Test
    fun `the history keeps the newest payments`() = runBlocking {
        val s = store()
        repeat(X402Store.MAX_HISTORY + 3) { s.record(payment("p$it", X402Store.Status.PAID)) }
        val h = s.history.first()
        assertEquals(X402Store.MAX_HISTORY, h.size)
        assertEquals("p${X402Store.MAX_HISTORY + 2}", h.first().id)
    }

    @Test
    fun `clear forgets allowances and history`() = runBlocking {
        val s = store()
        s.grant(cap = 10)
        s.record(payment("a"))
        assertTrue(s.clear())
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
    }

    @Test
    fun `storage trouble reads as nothing and pays nothing`() = runBlocking {
        val s = X402Store(BrokenStore(CorruptionException("bad"))) { now }
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
        assertFalse(s.grant(cap = 10))
        assertFalse(s.consume(site, 8453, usdc, me, payee, BigInteger.ONE))
        assertFalse(s.record(payment("a")))
        assertFalse(s.revoke(site, 8453, usdc, me))
    }

    @Test
    fun `malformed records are skipped, not thrown`() {
        assertNull(X402Store.decodeAllowance("$site 8453 $usdc $me", "{}"))
        assertNull(X402Store.decodeAllowance("$site 8453 nope $me", """{"cap":"1","spent":"0","created":1,"expires":2,"symbol":"U","decimals":6}"""))
        assertEquals(emptyList<X402Store.Payment>(), X402Store.decodeHistory("not json"))
        assertEquals(emptyList<X402Store.Payment>(), X402Store.decodeHistory("""[{"id":"x"}]"""))
    }

    private fun paid(id: String, amount: Long, auto: Boolean) =
        payment(id).copy(amount = BigInteger.valueOf(amount), auto = auto)

    @Test
    fun `an allowance payment is counted in the same write that records it, or not at all`() = runBlocking {
        val s = store()
        s.grant(cap = 30)
        val first = s.commit(paid("a", 20, auto = true), grant = null)
        assertTrue(first is X402Store.Commit.Done)
        assertEquals(BigInteger.valueOf(20), s.allowances.first().single().spent)
        assertEquals(listOf("a"), s.history.first().map { it.id })
        // Not covered: neither counted nor listed.
        assertEquals(X402Store.Commit.NotCovered, s.commit(paid("b", 11, auto = true), grant = null))
        assertEquals(BigInteger.valueOf(20), s.allowances.first().single().spent)
        assertEquals(listOf("a"), s.history.first().map { it.id })
        // No allowance at all for another site.
        assertEquals(X402Store.Commit.NotCovered, s.commit(paid("c", 1, auto = true).copy(origin = "https://other.example"), grant = null))
    }

    @Test
    fun `an allowance payment that's never sent is given back and leaves no history (R1-M2)`() = runBlocking {
        val s = store()
        s.grant(cap = 30)
        val p = paid("a", 20, auto = true)
        val done = s.commit(p, grant = null) as X402Store.Commit.Done
        assertTrue(s.withdraw(p, done))
        assertEquals(BigInteger.ZERO, s.allowances.first().single().spent)
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
        // Revoked and granted again in between: the new allowance isn't touched.
        val q = paid("b", 20, auto = true)
        val again = s.commit(q, grant = null) as X402Store.Commit.Done
        now += 1
        s.grant(cap = 50, spent = 40)
        assertTrue(s.withdraw(q, again))
        assertEquals(BigInteger.valueOf(40), s.allowances.first().single().spent)
    }

    @Test
    fun `an allowance granted with a payment is written with its record, and goes if the payment isn't sent (R1-M4)`() = runBlocking {
        val s = store()
        val p = paid("a", 10, auto = false)
        val grant = X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)
        val done = s.commit(p, grant) as X402Store.Commit.Done
        val a = s.allowances.first().single()
        assertEquals(BigInteger.valueOf(10), a.spent)
        assertEquals(BigInteger.valueOf(100), a.cap)
        assertEquals(listOf("a"), s.history.first().map { it.id })
        assertTrue(s.withdraw(p, done))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
        // A manual payment with no grant touches no allowance.
        s.grant(cap = 50)
        val plain = s.commit(paid("b", 10, auto = false), grant = null) as X402Store.Commit.Done
        assertNull(plain.allowanceCreated)
        assertTrue(s.withdraw(paid("b", 10, auto = false), plain))
        assertEquals(BigInteger.ZERO, s.allowances.first().single().spent)
        // A grant below the payment is refused, nothing written.
        assertEquals(X402Store.Commit.Failed, s.commit(paid("c", 60, auto = false), X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(50), hour)))
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
    }

    @Test
    fun `withdrawing a payment whose grant replaced an allowance puts that allowance back (#346)`() = runBlocking {
        val s = store()
        s.grant(cap = 50, spent = 20)
        val before = s.allowances.first().single()
        now += 1
        val p = paid("a", 10, auto = false)
        val done = s.commit(p, X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)) as X402Store.Commit.Done
        assertEquals(BigInteger.valueOf(100), s.allowances.first().single().cap)
        assertTrue(s.withdraw(p, done))
        assertEquals(listOf(before), s.allowances.first())
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
        // The new allowance was revoked before the withdraw: nothing comes back.
        now += 1
        val q = paid("b", 10, auto = false)
        val again = s.commit(q, X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)) as X402Store.Commit.Done
        assertTrue(s.revoke(site, 8453, usdc, me))
        assertTrue(s.withdraw(q, again))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
        // An allowance that had already run out isn't brought back either.
        s.grant(cap = 50)
        now += hour + 1
        val r = paid("c", 10, auto = false)
        val third = s.commit(r, X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)) as X402Store.Commit.Done
        assertNull(third.replaced)
        assertTrue(s.withdraw(r, third))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
    }

    @Test
    fun `an allowance put back keeps the spend of a payment the grant made meanwhile (#346 R1-F1)`() = runBlocking {
        val s = store()
        s.grant(cap = 50, spent = 20)
        val before = s.allowances.first().single()
        now += 1
        val p2 = paid("p2", 10, auto = false)
        val done = s.commit(p2, X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)) as X402Store.Commit.Done
        // Another tab's automatic payment is counted against the new allowance, and sent.
        val p3 = paid("p3", 10, auto = true)
        assertTrue(s.commit(p3, grant = null) is X402Store.Commit.Done)
        assertTrue(s.withdraw(p2, done))
        // The old allowance is back, with p3 counted against it: nothing sent goes uncounted.
        assertEquals(listOf(before.copy(spent = BigInteger.valueOf(30))), s.allowances.first())
        assertEquals(listOf("p3"), s.history.first().map { it.id })
        // Withdrawing p3 later finds the allowance it was counted against gone, and changes nothing.
        assertTrue(s.withdraw(p3, X402Store.Commit.Done(done.allowanceCreated)))
        assertEquals(BigInteger.valueOf(30), s.allowances.first().single().spent)
    }

    @Test
    fun `a replaced allowance that can't be read isn't put back when the grant paid meanwhile (#346 R1-F1)`() = runBlocking {
        val s = store()
        now += 1
        val p2 = paid("p2", 10, auto = false)
        val granted = s.commit(p2, X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)) as X402Store.Commit.Done
        assertTrue(s.commit(paid("p3", 10, auto = true), grant = null) is X402Store.Commit.Done)
        assertTrue(s.withdraw(p2, granted.copy(replaced = "not json")))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
    }

    @Test
    fun `a commit that can't be written reports it, and nothing is paid`() = runBlocking {
        val s = X402Store(BrokenStore(IOException("disk"))) { now }
        assertEquals(X402Store.Commit.Failed, s.commit(paid("a", 10, auto = false), grant = null))
        assertFalse(s.withdraw(paid("a", 10, auto = false), X402Store.Commit.Done(null)))
    }

    @Test
    fun `an allowance pays only from the account it was granted for (R2-F1)`() = runBlocking {
        val s = store()
        s.grant(cap = 100)
        val all = s.allowances.first()
        assertEquals(me.lowercase(), all.single().account)
        assertNotNull(s.covering(all, site, 8453, usdc, me.uppercase().replace("0X", "0x"), payee, BigInteger.ONE))
        assertNull(s.covering(all, site, 8453, usdc, other, payee, BigInteger.ONE))
        assertFalse(s.consume(site, 8453, usdc, other, payee, BigInteger.ONE))
        // After a switch, an automatic payment signed by the other account isn't covered: nothing written.
        assertEquals(X402Store.Commit.NotCovered, s.commit(paid("x", 1, auto = true).copy(from = other), grant = null))
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
        // A grant from the other account is its own allowance; the first one is untouched.
        val done = s.commit(paid("y", 5, auto = false).copy(from = other), X402Store.NewAllowance("USDC", 6, BigInteger.TEN, hour))
        assertTrue(done is X402Store.Commit.Done)
        assertEquals(listOf(me.lowercase() to BigInteger.ZERO, other to BigInteger.valueOf(5)).sortedBy { it.first },
            s.allowances.first().map { it.account to it.spent }.sortedBy { it.first })
        // Revoking one account's allowance leaves the other's.
        assertTrue(s.revoke(site, 8453, usdc, other))
        assertEquals(listOf(me.lowercase()), s.allowances.first().map { it.account })
        // An allowance stored before the account was keyed doesn't decode, so it pays nothing.
        assertNull(X402Store.decodeAllowance("$site 8453 ${usdc.lowercase()}",
            """{"cap":"100","spent":"0","created":$now,"expires":${now + hour},"symbol":"USDC","decimals":6}"""))
    }

    @Test
    fun `an allowance leaves the list when its window ends, with nothing else written (R2-M2)`() = runBlocking {
        val s = X402Store(MemoryStore())
        assertTrue(s.grant(site, 8453, usdc, me, payee, BigInteger.TEN, "USDC", 6, BigInteger.TEN, 300, BigInteger.ZERO))
        val seen = withTimeout(5_000) { s.allowances.take(2).toList() }
        assertEquals(1, seen[0].size)
        assertEquals(emptyList<X402Store.Allowance>(), seen[1])
    }

    @Test
    fun `#237 an allowance pays each time no more than the payment approved on the sheet`() = runBlocking {
        val s = store()
        assertTrue(s.commit(paid("a", 10, auto = false), X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)) is X402Store.Commit.Done)
        // The rest of the cap in one request: not paid silently.
        assertEquals(X402Store.Commit.NotCovered, s.commit(paid("b", 90, auto = true), grant = null))
        assertEquals(X402Store.Commit.NotCovered, s.commit(paid("c", 11, auto = true), grant = null))
        assertTrue(s.commit(paid("d", 10, auto = true), grant = null) is X402Store.Commit.Done)
        val a = s.allowances.first().single()
        assertEquals(BigInteger.valueOf(20), a.spent)
        assertEquals(BigInteger.TEN, a.each)
        assertNotNull(s.covering(listOf(a), site, 8453, usdc, me, payee, BigInteger.TEN))
        assertNull(s.covering(listOf(a), site, 8453, usdc, me, payee, BigInteger.valueOf(11)))
        assertFalse(s.consume(site, 8453, usdc, me, payee, BigInteger.valueOf(80)))
        assertTrue(s.consume(site, 8453, usdc, me, payee, BigInteger.valueOf(9)))
        // A grant's per-payment amount can't be over its cap, nor nothing.
        assertFalse(s.grant(cap = 10, each = 11))
        assertFalse(s.grant(cap = 10, each = 0))
    }

    @Test
    fun `#237 an allowance pays only the address the approved payment went to`() = runBlocking {
        val s = store()
        assertTrue(s.commit(paid("a", 10, auto = false), X402Store.NewAllowance("USDC", 6, BigInteger.valueOf(100), hour)) is X402Store.Commit.Done)
        assertEquals(X402Store.Commit.NotCovered, s.commit(paid("b", 10, auto = true).copy(payTo = other), grant = null))
        assertTrue(s.commit(paid("c", 10, auto = true).copy(payTo = usdc.lowercase()), grant = null) is X402Store.Commit.Done)
        val all = s.allowances.first()
        assertEquals(payee.lowercase(), all.single().payTo)
        assertNotNull(s.covering(all, site, 8453, usdc, me, payee.uppercase().replace("0X", "0x"), BigInteger.ONE))
        assertNull(s.covering(all, site, 8453, usdc, me, other, BigInteger.ONE))
        assertFalse(s.consume(site, 8453, usdc, me, other, BigInteger.ONE))
    }

    @Test
    fun `#237 an allowance stored without its payee and per-payment amount pays nothing`() {
        val key = "$site 8453 ${usdc.lowercase()} ${me.lowercase()}"
        val old = """{"cap":"100","spent":"0","created":1,"expires":2,"symbol":"USDC","decimals":6}"""
        assertNull(X402Store.decodeAllowance(key, old))
        assertNull(X402Store.decodeAllowance(key, old.dropLast(1) + ""","payTo":"${payee.lowercase()}"}"""))
        assertNull(X402Store.decodeAllowance(key, old.dropLast(1) + ""","each":"10"}"""))
        assertNull(X402Store.decodeAllowance(key, old.dropLast(1) + ""","payTo":"nope","each":"10"}"""))
        assertNull(X402Store.decodeAllowance(key, old.dropLast(1) + ""","payTo":"${payee.lowercase()}","each":"0"}"""))
        val ok = X402Store.decodeAllowance(key, old.dropLast(1) + ""","payTo":"$payee","each":"10"}""")!!
        assertEquals(payee.lowercase(), ok.payTo)
        assertEquals(BigInteger.TEN, ok.each)
        assertEquals(ok, X402Store.decodeAllowance(key, X402Store.encodeAllowance(ok)))
    }

    @Test
    fun `#347 a hold is kept until lifted, and goes with the wallet`() = runBlocking {
        val s = store()
        assertEquals(emptySet<String>(), s.holds())
        assertTrue(s.grant(cap = 30))
        assertTrue(s.hold(site))
        assertTrue(s.hold("https://other.example"))
        assertEquals(setOf(site, "https://other.example"), s.holds())
        // Holds aren't allowances, nor history.
        assertEquals(1, s.allowances.first().size)
        assertEquals(emptyList<X402Store.Payment>(), s.history.first())
        assertTrue(s.lift(site))
        assertTrue(s.lift(site))
        assertEquals(setOf("https://other.example"), s.holds())
        assertTrue(s.clear())
        assertEquals(emptySet<String>(), s.holds())
    }

    @Test
    fun `#347 holds that can't be read are unknown, not none`() = runBlocking {
        val s = X402Store(BrokenStore(IOException("disk"))) { now }
        assertNull(s.holds())
        assertFalse(s.hold(site))
        assertFalse(s.lift(site))
    }

    @Test
    fun `#347 R2-M1 only a clear that landed counts as one`() = runBlocking {
        val broken = X402Store(BrokenStore(IOException("disk"))) { now }
        val before = broken.clearEra
        assertFalse(broken.clear())
        // Failed: what it would have removed is still there, so queued holds aren't stale.
        assertEquals(false, broken.clearedSince(before))
        val s = store()
        val era = s.clearEra
        assertEquals(false, s.clearedSince(era))
        assertTrue(s.clear())
        assertEquals(true, s.clearedSince(era))
        // A hold queued after the clear isn't stale.
        assertEquals(false, s.clearedSince(s.clearEra))
    }
}
