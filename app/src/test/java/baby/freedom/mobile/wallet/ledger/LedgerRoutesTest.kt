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
 * on it — including one that's unplugged, or locked / on another app
 * (set aside at once unless it's the last, and only waited on after the
 * others, PR #350 R2-F1); anything else (a refusal on the device, Cancel,
 * a confirmation timing out) ends it there.
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
        val got = Ledger.inTurn(listOf(nanoSPlusA, nanoSPlusB, nanoXRadio)) { r, _ ->
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
                Ledger.inTurn(routes) { r, _ -> throw NotThisLedger(ex(reasons[routes.indexOf(r)])) }
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
    fun `a refusal on the device, a cancel or a confirmation timeout ends it without trying another Ledger`() = runBlocking {
        for (kind in listOf(LedgerException.Kind.REJECTED, LedgerException.Kind.CANCELLED, LedgerException.Kind.TIMEOUT)) {
            val tried = ArrayList<Route>()
            try {
                Ledger.inTurn(listOf(nanoSPlusA, nanoXRadio)) { r, _ ->
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

    /** A fake Ledger's address reads: [answers] in turn, the last repeated. */
    private fun reads(vararg answers: Any): suspend () -> String {
        var i = 0
        return {
            val a = answers[minOf(i++, answers.lastIndex)]
            if (a is LedgerException.Kind) throw ex(a)
            a as String
        }
    }

    private val account = "0x1111111111111111111111111111111111111111"
    private val other = "0x2222222222222222222222222222222222222222"

    private suspend fun passedOver(patient: Boolean, read: suspend () -> String): NotThisLedger? = try {
        Ledger.holding(account, patient, {}, readyMs = 200, pollMs = 10, read = read)
        null
    } catch (e: NotThisLedger) {
        e
    }

    @Test
    fun `a Ledger that's unplugged, locked or on another app before it's checked is passed over`() = runBlocking {
        // Holds it once unlocked: waited on, it's used.
        assertEquals(null, passedOver(true, reads(LedgerException.Kind.LOCKED, LedgerException.Kind.APP_NOT_OPEN, account)))
        // Not waited on: locked is set aside for later, not an end.
        passedOver(false, reads(LedgerException.Kind.LOCKED, account))!!.let {
            assertEquals(LedgerException.Kind.LOCKED, it.reason.kind)
            assertTrue(it.later)
        }
        // Waited on and never unlocked: passed over, not come back to.
        passedOver(true, reads(LedgerException.Kind.APP_NOT_OPEN))!!.let {
            assertEquals(LedgerException.Kind.APP_NOT_OPEN, it.reason.kind)
            assertFalse(it.later)
        }
        // Unplugged while it's waited on.
        passedOver(true, reads(LedgerException.Kind.LOCKED, LedgerException.Kind.DISCONNECTED))!!.let {
            assertEquals(LedgerException.Kind.DISCONNECTED, it.reason.kind)
            assertFalse(it.later)
        }
        // Another seed.
        assertEquals(LedgerException.Kind.WRONG_DEVICE, passedOver(true, reads(other))!!.reason.kind)
        // The address matches whatever its case.
        assertEquals(null, passedOver(false, reads(account.uppercase().replace("0X", "0x"))))
    }

    @Test
    fun `a locked Ledger plugged in doesn't hold up the account's own Ledger further down`() = runBlocking {
        // The finding: a Bluetooth Nano X account, a locked Nano S Plus of another seed plugged in.
        val asked = ArrayList<Pair<Route, Boolean>>()
        val got = Ledger.inTurn(listOf(nanoSPlusA, nanoXRadio)) { r, patient ->
            asked += r to patient
            Ledger.holding(account, patient, {}, readyMs = 5_000, pollMs = 10, read = if (r == nanoSPlusA) reads(LedgerException.Kind.LOCKED) else reads(account))
            "signed on ${r.name}"
        }
        assertEquals("signed on Nano X 1A2B", got)
        assertEquals(listOf(nanoSPlusA to false, nanoXRadio to true), asked)
    }

    @Test
    fun `a locked Ledger set aside is waited on once no other Ledger works`() = runBlocking {
        // A USB account's own Ledger, locked; the paired Nano X out of range.
        var unlocked = false
        val asked = ArrayList<Pair<Route, Boolean>>()
        val got = Ledger.inTurn(listOf(nanoXCable, nanoXRadio)) { r, patient ->
            asked += r to patient
            if (r == nanoXRadio) throw NotThisLedger(ex(LedgerException.Kind.NOT_FOUND))
            Ledger.holding(account, patient, { unlocked = true }, readyMs = 5_000, pollMs = 10) {
                if (!unlocked) throw ex(LedgerException.Kind.LOCKED)
                account
            }
            "signed on ${r.name}"
        }
        assertEquals("signed on Ledger Nano X", got)
        assertEquals(listOf(nanoXCable to false, nanoXRadio to true, nanoXCable to true), asked)
    }

    @Test
    fun `set aside and never unlocked, the lock is what's reported`() = runBlocking {
        try {
            Ledger.inTurn(listOf(nanoSPlusA, nanoXRadio)) { r, patient ->
                if (r == nanoXRadio) throw NotThisLedger(ex(LedgerException.Kind.NOT_FOUND))
                Ledger.holding(account, patient, {}, readyMs = 50, pollMs = 10, read = reads(LedgerException.Kind.LOCKED))
            }
            fail("succeeded")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.LOCKED, e.kind)
        }
    }
}
