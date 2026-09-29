package baby.freedom.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connected-sites store (#110, #111): what a grant keeps, and that revoking drops it. */
class DappGrantStoreTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val a = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private var now = 1_790_000_000_000L
    private val memory = MemoryStore()
    private val store = DappGrantStore(memory) { now }

    @Test
    fun `a grant keeps the account, the chain and when it was made`() = runBlocking {
        assertTrue(store.grant("https://app.example", a, 100))
        assertEquals(listOf(DappGrantStore.Grant("https://app.example", a, 100, now)), store.all.first())
    }

    @Test
    fun `switching chain keeps when the site was connected`() = runBlocking {
        store.grant("https://app.example", a, 100)
        val connected = now
        now += 60_000
        assertTrue(store.setChain("https://app.example", 1))
        assertEquals(DappGrantStore.Grant("https://app.example", a, 1, connected), store.all.first().single())
    }

    @Test
    fun `revoke drops only that site`() = runBlocking {
        store.grant("https://one.example", a, 100)
        store.grant("https://two.example", a, 1)
        assertTrue(store.revoke("https://one.example"))
        assertEquals(listOf("https://two.example"), store.all.first().map { it.origin })
        assertFalse(store.setChain("https://one.example", 1))
    }

    @Test
    fun `a grant saved before connection times were kept still reads, with no time`() = runBlocking {
        val old = DappGrantStore(
            MemoryStore(mutablePreferencesOf(stringPreferencesKey("grant:https://old.example") to """{"account":"$a","chainId":100}""")),
        )
        assertEquals(listOf(DappGrantStore.Grant("https://old.example", a, 100, null)), old.all.first())
        // …and a chain switch doesn't invent one.
        assertTrue(old.setChain("https://old.example", 1))
        assertNull(old.all.first().single().connectedAt)
    }

    @Test
    fun `a bad connection time is dropped, not the grant`() {
        for (bad in listOf("\"soon\"", "-5", "0", "null")) {
            val g = DappGrantStore.decode("https://x.example", """{"account":"$a","chainId":100,"connectedAt":$bad}""")
            assertEquals(DappGrantStore.Grant("https://x.example", a, 100, null), g)
        }
    }

    @Test
    fun `encoding round-trips, with and without a time`() {
        assertEquals(
            DappGrantStore.Grant("https://x.example", a, 5, 42),
            DappGrantStore.decode("https://x.example", DappGrantStore.encode(a, 5, 42)),
        )
        assertEquals(
            DappGrantStore.Grant("https://x.example", a, 5, null),
            DappGrantStore.decode("https://x.example", DappGrantStore.encode(a, 5, null)),
        )
    }
}
