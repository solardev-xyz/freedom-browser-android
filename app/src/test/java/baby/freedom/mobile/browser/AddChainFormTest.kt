package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.Chain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AddChainFormTest {
    @Before
    fun icu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    private val one = listOf("https://a.example")

    @Test
    fun aUrlLeftInTheRpcFieldIsSavedToo() {
        assertEquals(one + "https://b.example", rpcsWithPending(one, "  https://b.example "))
        // Also when it's the only one.
        assertEquals(listOf("https://b.example"), rpcsWithPending(emptyList(), "https://b.example"))
    }

    @Test
    fun blankDuplicateOrFullLeavesTheListAlone() {
        assertEquals(one, rpcsWithPending(one, ""))
        assertEquals(one, rpcsWithPending(one, "   "))
        assertEquals(one, rpcsWithPending(one, "https://a.example"))
        val full = (1..Chain.MAX_RPC_URLS).map { "https://r$it.example" }
        assertEquals(full, rpcsWithPending(full, "https://extra.example"))
    }

    @Test
    fun anInvalidUrlLeftInTheFieldBlocksAdd() {
        assertNull(rpcsWithPending(one, "http://192.168.1.10:8545"))
        assertNull(rpcsWithPending(one, "not a url"))
    }
}
