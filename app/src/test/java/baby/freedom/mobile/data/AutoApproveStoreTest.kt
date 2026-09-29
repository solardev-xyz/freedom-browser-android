package baby.freedom.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import baby.freedom.mobile.browser.AutoApproveRule
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auto-approve rule store (#112): scoping, idempotent grants, revoking — as iOS's `AutoApproveStoreTests`. */
class AutoApproveStoreTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        var failWrites = false
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("disk full")
            return transform(data.value).also { data.value = it }
        }
    }

    private val uniswap = "https://app.uniswap.org"
    private val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val dai = "0x6b175474e89094c44da98b954eedeac495271d0f"
    private val transfer = "0xa9059cbb"
    private val transferFrom = "0x23b872dd"
    private var now = 1_790_000_000_000L
    private val memory = MemoryStore()
    private val store = AutoApproveStore(memory) { now }

    private fun rule(origin: String = uniswap, contract: String = usdc, selector: String = transfer, chainId: Long = 1) =
        AutoApproveRule.of(origin, contract, selector, chainId)!!

    @Test
    fun `a granted rule matches, with when it was granted`() = runBlocking {
        assertFalse(store.matches(rule()))
        assertTrue(store.grant(rule()))
        assertTrue(store.matches(rule()))
        assertEquals(listOf(rule().copy(grantedAt = now)), store.allOrUnreadable.first())
    }

    @Test
    fun `contract and selector match whatever their case`() = runBlocking {
        store.grant(AutoApproveRule.of(uniswap, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", transfer.uppercase().replace("0X", "0x"), 1)!!)
        assertTrue(store.matches(rule()))
        assertEquals(rule().key, store.allOrUnreadable.first()!!.single().key)
    }

    @Test
    fun `granting the same rule twice keeps one, first granted`() = runBlocking {
        store.grant(rule())
        val first = now
        now += 60_000
        store.grant(rule())
        assertEquals(listOf(rule().copy(grantedAt = first)), store.allOrUnreadable.first())
    }

    @Test
    fun `a rule matches only its own origin, contract, function and chain`() = runBlocking {
        store.grant(rule())
        assertFalse("another site", store.matches(rule(origin = "https://evil.example")))
        assertFalse("http of the same host", store.matches(rule(origin = "http://app.uniswap.org")))
        assertFalse("another port", store.matches(rule(origin = "https://app.uniswap.org:8443")))
        assertFalse("another contract", store.matches(rule(contract = dai)))
        assertFalse("another function", store.matches(rule(selector = transferFrom)))
        assertFalse("another chain", store.matches(rule(chainId = 100)))
        assertTrue(store.matches(rule()))
    }

    @Test
    fun `revoking drops that rule only, and a site's rules go together`() = runBlocking {
        val other = "https://other.example"
        store.grant(rule())
        store.grant(rule(selector = transferFrom))
        store.grant(rule(origin = other))
        assertTrue(store.revoke(rule()))
        assertFalse(store.matches(rule()))
        assertTrue(store.matches(rule(selector = transferFrom)))
        assertTrue(store.revokeOrigin(uniswap))
        assertEquals(listOf(rule(origin = other).key), store.allOrUnreadable.first()!!.map { it.key })
        assertTrue(store.clear())
        assertEquals(emptyList<AutoApproveRule>(), store.allOrUnreadable.first())
    }

    @Test
    fun `revoking a site doesn't touch a site whose origin merely starts the same`() = runBlocking {
        store.grant(rule(origin = "https://app.uniswap.org.evil.example"))
        store.grant(rule(origin = "https://app.uniswap.org:8443"))
        store.revokeOrigin(uniswap)
        assertEquals(2, store.allOrUnreadable.first()!!.size)
    }

    @Test
    fun `a failed write says so`() = runBlocking {
        memory.failWrites = true
        assertFalse(store.grant(rule()))
        assertFalse(store.revoke(rule()))
        assertFalse(store.revokeOrigin(uniswap))
        assertFalse(store.clear())
    }

    @Test
    fun `an unreadable store matches nothing, so the sheet shows`() = runBlocking {
        val broken = AutoApproveStore(object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = throw IOException("unreadable")
        })
        assertNull(broken.allOrUnreadable.first())
        assertFalse(broken.matches(rule()))
    }

    @Test
    fun `a stored key that isn't a valid rule is skipped, and never matches`() = runBlocking {
        val odd = AutoApproveStore(
            MemoryStore(
                mutablePreferencesOf(
                    stringPreferencesKey("rule:$uniswap|$usdc|$transfer|1") to """{"grantedAt":5}""",
                    // Not normalized, a zero selector, a bad chain, too few parts, bad JSON: all but the last dropped.
                    stringPreferencesKey("rule:$uniswap|${usdc.uppercase()}|$transferFrom|1") to """{"grantedAt":5}""",
                    stringPreferencesKey("rule:$uniswap|$usdc|0x00000000|1") to "{}",
                    stringPreferencesKey("rule:$uniswap|$usdc|$transferFrom|0") to "{}",
                    stringPreferencesKey("rule:$uniswap|$usdc|$transferFrom") to "{}",
                    stringPreferencesKey("rule:$uniswap|$dai|$transfer|1") to "not json",
                ),
            ),
        )
        assertEquals(
            listOf(rule().copy(grantedAt = 5), rule(contract = dai)),
            odd.allOrUnreadable.first(),
        )
        assertFalse(odd.matches(rule(selector = transferFrom)))
    }

    @Test
    fun `a rule stored for an approval, multicall or execute function before #234 is skipped, and never matches`() = runBlocking {
        val approve = "0x095ea7b3"
        val multicall = "0xac9650d8"
        val universalExecute = "0x3593564c"
        val old = AutoApproveStore(
            MemoryStore(
                mutablePreferencesOf(
                    stringPreferencesKey("rule:$uniswap|$usdc|$transfer|1") to """{"grantedAt":5}""",
                    stringPreferencesKey("rule:$uniswap|$usdc|$approve|1") to """{"grantedAt":5}""",
                    stringPreferencesKey("rule:$uniswap|$usdc|$multicall|1") to """{"grantedAt":5}""",
                    stringPreferencesKey("rule:$uniswap|$usdc|$universalExecute|1") to """{"grantedAt":5}""",
                ),
            ),
        )
        assertEquals(listOf(rule().copy(grantedAt = 5)), old.allOrUnreadable.first())
        for (selector in listOf(approve, multicall, universalExecute)) {
            assertFalse(selector, old.matches(AutoApproveRule(uniswap, usdc, selector, 1)))
        }
        assertTrue(old.matches(rule()))
        // Unseen, but still the site's: disconnecting it drops them too, and leaves another site's.
        memory.data.value = mutablePreferencesOf(
            stringPreferencesKey("rule:$uniswap|$usdc|$approve|1") to """{"grantedAt":5}""",
            stringPreferencesKey("rule:$uniswap.evil|$usdc|$approve|1") to """{"grantedAt":5}""",
        )
        assertTrue(store.revokeOrigin(uniswap))
        assertEquals(setOf("rule:$uniswap.evil|$usdc|$approve|1"), memory.data.value.asMap().keys.map { it.name }.toSet())
    }
}
