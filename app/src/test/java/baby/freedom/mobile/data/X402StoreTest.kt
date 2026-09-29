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
import kotlinx.coroutines.runBlocking
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

    private fun store() = X402Store(MemoryStore()) { now }

    private suspend fun X402Store.grant(cap: Long, spent: Long = 0, origin: String = site, chainId: Long = 8453, asset: String = usdc) =
        grant(origin, chainId, asset, "USDC", 6, BigInteger.valueOf(cap), hour, BigInteger.valueOf(spent))

    @Test
    fun `an allowance pays up to its cap, counting the payment that granted it`() = runBlocking {
        val s = store()
        assertTrue(s.grant(cap = 30, spent = 10))
        val a = s.allowances.first().single()
        assertEquals(BigInteger.valueOf(20), a.remaining)
        assertEquals(usdc.lowercase(), a.asset)
        assertNotNull(s.covering(s.allowances.first(), site, 8453, usdc, BigInteger.valueOf(20)))
        assertNull(s.covering(s.allowances.first(), site, 8453, usdc, BigInteger.valueOf(21)))
        assertTrue(s.consume(site, 8453, usdc.uppercase().replace("0X", "0x"), BigInteger.valueOf(15)))
        assertFalse(s.consume(site, 8453, usdc, BigInteger.valueOf(6)))
        assertTrue(s.consume(site, 8453, usdc, BigInteger.valueOf(5)))
        // Used up: it's gone from the list, and pays nothing more.
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
        assertFalse(s.consume(site, 8453, usdc, BigInteger.ONE))
    }

    @Test
    fun `an allowance is for one site, one chain and one token`() = runBlocking {
        val s = store()
        s.grant(cap = 100)
        assertFalse(s.consume("https://other.example", 8453, usdc, BigInteger.ONE))
        assertFalse(s.consume(site, 1, usdc, BigInteger.ONE))
        assertFalse(s.consume(site, 8453, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", BigInteger.ONE))
        assertTrue(s.consume(site, 8453, usdc, BigInteger.ONE))
    }

    @Test
    fun `an allowance ends with its window, and a clock set back doesn't stretch it`() = runBlocking {
        val s = store()
        s.grant(cap = 100)
        now += hour - 1
        assertTrue(s.consume(site, 8453, usdc, BigInteger.ONE))
        now += 1
        assertFalse(s.consume(site, 8453, usdc, BigInteger.ONE))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())

        val t = store()
        t.grant(cap = 100)
        now -= 24 * hour
        assertFalse(t.consume(site, 8453, usdc, BigInteger.ONE))
    }

    @Test
    fun `a revoked allowance pays nothing, and a new grant replaces the old one`() = runBlocking {
        val s = store()
        s.grant(cap = 100, spent = 90)
        s.grant(cap = 50)
        assertEquals(BigInteger.valueOf(50), s.allowances.first().single().remaining)
        assertTrue(s.revoke(site, 8453, usdc))
        assertFalse(s.consume(site, 8453, usdc, BigInteger.ONE))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
    }

    @Test
    fun `nonsense grants are refused`() = runBlocking {
        val s = store()
        assertFalse(s.grant(cap = 10, spent = 11))
        assertFalse(s.grant(cap = 0))
        assertFalse(s.grant(site, 8453, usdc, "USDC", 6, BigInteger.TEN, 0, BigInteger.ZERO))
        assertFalse(s.consume(site, 8453, usdc, BigInteger.ZERO))
        assertEquals(emptyList<X402Store.Allowance>(), s.allowances.first())
    }

    @Test
    fun `payments racing for the last of an allowance can't both have it`() = runBlocking {
        val s = store()
        s.grant(cap = 10)
        val results = (1..20).map { async { s.consume(site, 8453, usdc, BigInteger.ONE) } }.awaitAll()
        assertEquals(10, results.count { it })
    }

    private fun payment(id: String, status: X402Store.Status = X402Store.Status.PENDING) = X402Store.Payment(
        id = id, at = now, origin = site, url = "$site/paid?" + "q".repeat(5000), chainId = 8453, asset = usdc,
        symbol = "USDC", decimals = 6, amount = BigInteger.valueOf(10_000), payTo = usdc, from = usdc,
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
        assertFalse(s.consume(site, 8453, usdc, BigInteger.ONE))
        assertFalse(s.record(payment("a")))
        assertFalse(s.revoke(site, 8453, usdc))
    }

    @Test
    fun `malformed records are skipped, not thrown`() {
        assertNull(X402Store.decodeAllowance("$site 8453 $usdc", "{}"))
        assertNull(X402Store.decodeAllowance("$site 8453 nope", """{"cap":"1","spent":"0","created":1,"expires":2,"symbol":"U","decimals":6}"""))
        assertEquals(emptyList<X402Store.Payment>(), X402Store.decodeHistory("not json"))
        assertEquals(emptyList<X402Store.Payment>(), X402Store.decodeHistory("""[{"id":"x"}]"""))
    }
}
