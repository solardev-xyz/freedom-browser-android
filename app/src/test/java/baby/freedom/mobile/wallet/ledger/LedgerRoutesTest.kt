package baby.freedom.mobile.wallet.ledger

import baby.freedom.mobile.wallet.ledger.Ledger.Companion.NotThisLedger
import baby.freedom.mobile.wallet.ledger.Ledger.Companion.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Which Ledger a conversation goes to (#319, PR #350 R1): signing tries
 * every Ledger that may hold the account — plugged-in ones first, the one
 * it was added from first among them, then Bluetooth — and passes over
 * one that can't be reached or doesn't hold it, before anything is shown
 * on it; anything else (a refusal on the device, Cancel) ends it there.
 */
class LedgerRoutesTest {
    private val nanoSPlusA = Route("usb:/dev/bus/usb/001/002", "Ledger Nano S Plus")
    private val nanoSPlusB = Route("usb:/dev/bus/usb/001/003", "Ledger Nano S Plus")
    private val nanoXCable = Route("usb:/dev/bus/usb/001/004", "Ledger Nano X")
    private val nanoXRadio = Route("AA:BB:CC:DD:EE:FF", "Nano X 1A2B")
    private val staxRadio = Route("11:22:33:44:55:66", "Ledger Stax 9F")

    private fun key(route: Route) = LedgerKey("44'/60'/0'/0/0", route.id, route.name)

    private fun ex(kind: LedgerException.Kind) = LedgerException(kind)

    @Test
    fun `a Bluetooth account tries plugged-in Ledgers first, then its own Ledger over Bluetooth`() {
        assertEquals(listOf(nanoSPlusA, nanoXRadio), Ledger.accountRoutes(key(nanoXRadio), listOf(nanoSPlusA), listOf(nanoXRadio, staxRadio)))
        // Bluetooth unusable: only what's plugged in.
        assertEquals(listOf(nanoSPlusA), Ledger.accountRoutes(key(nanoXRadio), listOf(nanoSPlusA), null))
        assertEquals(emptyList<Route>(), Ledger.accountRoutes(key(nanoXRadio), emptyList(), null))
    }

    @Test
    fun `a USB account tries the Ledger at its own path first, then every other one plugged in`() {
        assertEquals(listOf(nanoSPlusB, nanoSPlusA), Ledger.accountRoutes(key(nanoSPlusB), listOf(nanoSPlusA, nanoSPlusB), emptyList()))
        // Replugged: its path is gone, every plugged-in Ledger is still tried.
        assertEquals(listOf(nanoSPlusA, nanoXCable), Ledger.accountRoutes(key(nanoSPlusB), listOf(nanoSPlusA, nanoXCable), emptyList()))
    }

    @Test
    fun `a USB account of a Ledger with Bluetooth falls back to paired Ledgers of its model`() {
        assertEquals(listOf(nanoXRadio), Ledger.accountRoutes(key(nanoXCable), emptyList(), listOf(staxRadio, nanoXRadio)))
        assertEquals(listOf(nanoSPlusA, nanoXRadio), Ledger.accountRoutes(key(nanoXCable), listOf(nanoSPlusA), listOf(nanoXRadio)))
        // A Nano S Plus has no Bluetooth: nothing to fall back to.
        assertEquals(emptyList<Route>(), Ledger.accountRoutes(key(nanoSPlusA), emptyList(), listOf(nanoXRadio, staxRadio)))
        assertTrue(Ledger.usbOnlyModel("Ledger Nano S Plus"))
        assertTrue(Ledger.usbOnlyModel("Ledger Nano S"))
        assertFalse(Ledger.usbOnlyModel("Ledger Nano X"))
        assertTrue(Ledger.bluetoothModelMatches("Ledger Stax", "Ledger Stax 9F"))
        assertFalse(Ledger.bluetoothModelMatches("Ledger Stax", "Nano X 1A2B"))
        // A model the app can't name: any paired Ledger may be it.
        assertTrue(Ledger.bluetoothModelMatches("Ledger", "Nano X 1A2B"))
    }

    @Test
    fun `a Ledger that doesn't hold the account, or can't be reached, is passed over`() = runBlocking {
        val tried = ArrayList<Route>()
        val got = Ledger.inTurn(listOf(nanoSPlusA, nanoSPlusB, nanoXRadio)) { r ->
            tried += r
            when (r) {
                nanoSPlusA -> throw NotThisLedger(ex(LedgerException.Kind.WRONG_DEVICE))
                nanoSPlusB -> throw NotThisLedger(ex(LedgerException.Kind.PERMISSION))
                else -> "signed on ${r.name}"
            }
        }
        assertEquals("signed on Nano X 1A2B", got)
        assertEquals(listOf(nanoSPlusA, nanoSPlusB, nanoXRadio), tried)
    }

    @Test
    fun `none holding it, the Ledger that doesn't is named, else the last reason`() = runBlocking {
        fun failsWith(expected: LedgerException.Kind, vararg reasons: LedgerException.Kind) = runBlocking {
            val routes = reasons.indices.map { Route("r$it", "r$it") }
            try {
                Ledger.inTurn(routes) { r -> throw NotThisLedger(ex(reasons[routes.indexOf(r)])) }
                fail("succeeded")
            } catch (e: LedgerException) {
                assertEquals(expected, e.kind)
            }
        }
        failsWith(LedgerException.Kind.WRONG_DEVICE, LedgerException.Kind.WRONG_DEVICE, LedgerException.Kind.NOT_FOUND)
        failsWith(LedgerException.Kind.NOT_FOUND, LedgerException.Kind.PERMISSION, LedgerException.Kind.NOT_FOUND)
        failsWith(LedgerException.Kind.NOT_FOUND)
    }

    @Test
    fun `a refusal on the device or a cancel ends it without trying another Ledger`() = runBlocking {
        for (kind in listOf(LedgerException.Kind.REJECTED, LedgerException.Kind.CANCELLED, LedgerException.Kind.LOCKED)) {
            val tried = ArrayList<Route>()
            try {
                Ledger.inTurn(listOf(nanoSPlusA, nanoXRadio)) { r ->
                    tried += r
                    throw ex(kind)
                }
                fail("succeeded")
            } catch (e: LedgerException) {
                assertEquals(kind, e.kind)
            }
            assertEquals(listOf(nanoSPlusA), tried)
        }
    }
}
