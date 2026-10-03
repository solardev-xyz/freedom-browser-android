package baby.freedom.mobile.node

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [RouterReadSlots] (#329, R2-M2): sites' reads get only a share of the light client's router slots. */
class RouterReadSlotsTest {
    private val lookupCap = 7

    @Test
    fun `sites together take at most two router slots and one EVM slot`() {
        val lookups = AtomicInteger()
        val slots = RouterReadSlots(lookups, lookupCap)
        // A page looping slow eth_calls: one runs, the rest are busy.
        assertNotNull(slots.admit(page = true, evm = true))
        assertNull(slots.admit(page = true, evm = true))
        assertEquals(1, lookups.get())
        // A page balance read may take the second page slot, not a third.
        assertNotNull(slots.admit(page = true, evm = false))
        assertNull(slots.admit(page = true, evm = false))
        // The wallet's own reads still get the other two, calls included.
        assertNotNull(slots.admit(page = false, evm = true))
        assertNotNull(slots.admit(page = false, evm = false))
        assertNull(slots.admit(page = false, evm = false))
        // Name resolution kept all but the one page call and the wallet's call.
        assertEquals(2, lookups.get())
    }

    @Test
    fun `a refused read gives back what it took, and a release counts once`() {
        val lookups = AtomicInteger(lookupCap)
        val slots = RouterReadSlots(lookups, lookupCap)
        // Every lookup slot is taken (names): a page call is refused and
        // leaves the page counters as they were.
        repeat(3) { assertNull(slots.admit(page = true, evm = true)) }
        assertEquals(lookupCap, lookups.get())
        lookups.set(0)
        val release = slots.admit(page = true, evm = true)!!
        release()
        release()
        assertEquals(0, lookups.get())
        // The page slots are free again.
        assertNotNull(slots.admit(page = true, evm = true))
        assertNotNull(slots.admit(page = true, evm = false))
        repeat(2) { assertNotNull(slots.admit(page = false, evm = false)) }
        assertNull(slots.admit(page = false, evm = false))
    }
}
