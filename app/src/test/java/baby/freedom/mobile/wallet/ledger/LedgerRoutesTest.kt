package baby.freedom.mobile.wallet.ledger

import baby.freedom.mobile.wallet.ledger.Ledger.Companion.NotThisLedger
import baby.freedom.mobile.wallet.ledger.Ledger.Companion.Route
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * on it — including one that's unplugged, or never unlocked. Ledgers
 * waiting on the user are waited on at once, so none holds up another
 * (PR #350 R2-F1, R3-F1, R3-M1), and one let go with an APDU out is let
 * go once it's answered (R4-M2); anything else (a refusal on the device, Cancel,
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
    fun `a Ledger that answers at once is used without anything asked of the ones after it`() = runBlocking {
        val tried = ArrayList<Route>()
        val got = Ledger.inTurn(listOf(nanoXCable, nanoXRadio)) { r, turn ->
            tried += r
            Ledger.holding(account, turn::stage, readyMs = 5_000, pollMs = 10, read = reads(account))
            turn.claim()
            "signed on ${r.name}"
        }
        assertEquals("signed on Ledger Nano X", got)
        // The Bluetooth one is never connected to (nor offered pairing).
        assertEquals(listOf(nanoXCable), tried)
    }

    @Test
    fun `none holding it, a Ledger left locked is named, else the one that doesn't hold it, else the last reason`() = runBlocking {
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
        // R3-M2: an unlocked Ledger of another seed plugged in, the account's own left locked.
        failsWith(LedgerException.Kind.LOCKED, LedgerException.Kind.WRONG_DEVICE, LedgerException.Kind.LOCKED)
        failsWith(LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.WRONG_DEVICE)
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

    /** A fake Ledger that's locked until [unlocked], then holds [address]. */
    private fun lockedUntil(unlocked: () -> Boolean, address: String): suspend () -> String = {
        if (!unlocked()) throw ex(LedgerException.Kind.LOCKED)
        address
    }

    private val account = "0x1111111111111111111111111111111111111111"
    private val other = "0x2222222222222222222222222222222222222222"

    private suspend fun passedOver(read: suspend () -> String): NotThisLedger? = try {
        Ledger.holding(account, {}, readyMs = 200, pollMs = 10, read = read)
        null
    } catch (e: NotThisLedger) {
        e
    }

    @Test
    fun `a Ledger that's unplugged, never unlocked or of another seed is passed over`() = runBlocking {
        // Holds it once unlocked: waited on, it's used.
        assertEquals(null, passedOver(reads(LedgerException.Kind.LOCKED, LedgerException.Kind.APP_NOT_OPEN, account)))
        // Never opened on the Ethereum app.
        assertEquals(LedgerException.Kind.APP_NOT_OPEN, passedOver(reads(LedgerException.Kind.APP_NOT_OPEN))!!.reason.kind)
        // Unplugged while it's waited on.
        assertEquals(LedgerException.Kind.DISCONNECTED, passedOver(reads(LedgerException.Kind.LOCKED, LedgerException.Kind.DISCONNECTED))!!.reason.kind)
        // Another seed.
        assertEquals(LedgerException.Kind.WRONG_DEVICE, passedOver(reads(other))!!.reason.kind)
        // The address matches whatever its case.
        assertEquals(null, passedOver(reads(account.uppercase().replace("0X", "0x"))))
    }

    /**
     * [inTurn] over [routes], each answered by [ledgers]' fake: its read,
     * and how long it takes to connect. Returns the route that signed, and
     * which routes were let go (cancelled) along the way.
     */
    private class Run(val signed: Route, val letGo: List<Route>, val ms: Long)

    private fun run(routes: List<Route>, connectMs: Map<Route, Long> = emptyMap(), shown: MutableList<Pair<String, Ledger.Stage>>? = null, read: (Route) -> suspend () -> String): Run = runBlocking {
        val letGo = ArrayList<Route>()
        val started = System.currentTimeMillis()
        val signed = Ledger.inTurn(routes, show = { n, s -> shown?.add(n to s) }) { r, turn ->
            try {
                turn.stage(Ledger.Stage.CONNECTING)
                delay(connectMs[r] ?: 0)
                Ledger.holding(account, turn::stage, readyMs = 5_000, pollMs = 10, read = read(r))
                turn.claim()
                r
            } catch (e: CancellationException) {
                synchronized(letGo) { letGo += r }
                throw e
            }
        }
        Run(signed, letGo, System.currentTimeMillis() - started)
    }

    @Test
    fun `a locked Ledger plugged in doesn't hold up the account's own Ledger further down`() {
        // R2-F1: a Bluetooth Nano X account, a locked Nano S Plus of another seed plugged in.
        val r = run(listOf(nanoSPlusA, nanoXRadio)) { if (it == nanoSPlusA) reads(LedgerException.Kind.LOCKED) else reads(account) }
        assertEquals(nanoXRadio, r.signed)
        assertEquals(listOf(nanoSPlusA), r.letGo)
        assertTrue("took ${r.ms} ms", r.ms < 2_000)
    }

    @Test
    fun `the account's own Ledger plugged in and locked is picked up as soon as it's unlocked, while a Bluetooth one connects`() {
        // R3-F1: a Nano X account, the Nano X plugged in (locked), its Bluetooth route slow to connect.
        val start = System.currentTimeMillis()
        val unlocked = { System.currentTimeMillis() - start > 200 }
        val r = run(listOf(nanoXCable, nanoXRadio), connectMs = mapOf(nanoXRadio to 3_000L)) {
            if (it == nanoXCable) lockedUntil(unlocked, account) else reads(account)
        }
        assertEquals(nanoXCable, r.signed)
        assertEquals(listOf(nanoXRadio), r.letGo)
        assertTrue("took ${r.ms} ms", r.ms < 2_000)
    }

    @Test
    fun `two locked Ledgers are waited on at once, and the one the user unlocks is used`() {
        // R3-F1: a USB account's own Ledger plugged in, a paired Nano X of another seed in range; both locked.
        val start = System.currentTimeMillis()
        val unlocked = { System.currentTimeMillis() - start > 300 }
        val shown = ArrayList<Pair<String, Ledger.Stage>>()
        val r = run(listOf(nanoXCable, nanoXRadio), shown = shown) {
            if (it == nanoXCable) lockedUntil(unlocked, account) else reads(LedgerException.Kind.LOCKED)
        }
        assertEquals(nanoXCable, r.signed)
        assertEquals(listOf(nanoXRadio), r.letGo)
        assertTrue("took ${r.ms} ms", r.ms < 2_000)
        // While both wait, the dialog names both.
        assertTrue(shown.toString(), ("Ledger Nano X · Nano X 1A2B" to Ledger.Stage.UNLOCK) in shown)
    }

    @Test
    fun `Android's USB prompt for a Ledger plugged in doesn't hold up the account's own Ledger`() = runBlocking {
        // R3-M1: a Bluetooth account, an unrelated Nano S Plus newly plugged in, its access prompt ignored.
        val letGo = ArrayList<Route>()
        val started = System.currentTimeMillis()
        val got = Ledger.inTurn(listOf(nanoSPlusA, nanoXRadio)) { r, turn ->
            try {
                if (r == nanoSPlusA) {
                    turn.stage(Ledger.Stage.USB_PERMISSION)
                    delay(60_000) // Android's prompt, never answered
                }
                Ledger.holding(account, turn::stage, readyMs = 5_000, pollMs = 10, read = reads(account))
                turn.claim()
                r
            } catch (e: CancellationException) {
                letGo += r
                throw e
            }
        }
        assertEquals(nanoXRadio, got)
        assertEquals(listOf(nanoSPlusA), letGo)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `the dialog shows what most needs the user, then only the Ledger that took the conversation`() {
        val a = Route("a", "A")
        val b = Route("b", "B")
        assertEquals("A" to Ledger.Stage.UNLOCK, Ledger.shown(listOf(a, b), listOf(Ledger.Stage.UNLOCK, Ledger.Stage.CONNECTING)))
        // R4-M1: Android's own prompt is on screen anyway; unlocking is only ever said in the dialog.
        assertEquals("A" to Ledger.Stage.UNLOCK, Ledger.shown(listOf(a, b), listOf(Ledger.Stage.UNLOCK, Ledger.Stage.USB_PERMISSION)))
        assertEquals("B" to Ledger.Stage.OPEN_APP, Ledger.shown(listOf(a, b), listOf(Ledger.Stage.USB_PERMISSION, Ledger.Stage.OPEN_APP)))
        assertEquals("B" to Ledger.Stage.USB_PERMISSION, Ledger.shown(listOf(a, b), listOf(Ledger.Stage.CONNECTING, Ledger.Stage.USB_PERMISSION)))
        assertEquals("A · B" to Ledger.Stage.OPEN_APP, Ledger.shown(listOf(a, b), listOf(Ledger.Stage.OPEN_APP, Ledger.Stage.OPEN_APP)))
        assertEquals(null, Ledger.shown(listOf(a, b), listOf(null, null)))
        // Once B holds the account, A's stage no longer shows.
        val shown = ArrayList<Pair<String, Ledger.Stage>>()
        val race = Ledger.Companion.Race(listOf(a, b), show = { n, s -> shown += n to s })
        race.stage(0, Ledger.Stage.UNLOCK)
        race.stage(1, Ledger.Stage.CONNECTING)
        assertTrue(race.claim(1))
        race.stage(0, Ledger.Stage.UNLOCK)
        race.stage(1, Ledger.Stage.CONFIRM)
        assertEquals(listOf("A" to Ledger.Stage.UNLOCK, "A" to Ledger.Stage.UNLOCK, "B" to Ledger.Stage.CONNECTING, "B" to Ledger.Stage.CONFIRM), shown)
        assertFalse(race.claim(0))
    }

    @Test
    fun `a Ledger let go with an APDU out is let go once it's answered, before the one that holds the account goes on`() = runBlocking(Dispatchers.Default) {
        // R4-M2: one Nano X on a cable and over Bluetooth; the cable route's address read is still out when the radio one claims.
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val cableSent = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cableAnswer = kotlinx.coroutines.CompletableDeferred<Unit>()
        val got = Ledger.inTurn(listOf(nanoXCable, nanoXRadio)) { r, turn ->
            try {
                if (r == nanoXCable) {
                    turn.stage(Ledger.Stage.UNLOCK) // lets the radio route start
                    turn.apdu {
                        cableSent.complete(Unit)
                        cableAnswer.await()
                        events += "cable answered"
                    }
                    delay(60_000)
                    r
                } else {
                    cableSent.await()
                    launch { delay(300); cableAnswer.complete(Unit) }
                    turn.apdu { account }
                    turn.claim()
                    events += "radio signs"
                    r
                }
            } finally {
                if (r == nanoXCable) events += "cable closed"
            }
        }
        assertEquals(nanoXRadio, got)
        assertEquals(listOf("cable answered", "cable closed", "radio signs"), events)
    }

    @Test
    fun `a Ledger unlocked and checked no longer says Unlock while another Ledger is let go`() = runBlocking(Dispatchers.Default) {
        // R5-M1: the cable Nano X is locked; the radio route's address read stalls. The user unlocks the cable one,
        // which is checked and claims while the radio read is still out.
        val outer = this
        val shown = java.util.Collections.synchronizedList(ArrayList<Pair<String, Ledger.Stage>>())
        val radioSent = kotlinx.coroutines.CompletableDeferred<Unit>()
        val radioAnswer = kotlinx.coroutines.CompletableDeferred<Unit>()
        var shownWhileClaiming: Pair<String, Ledger.Stage>? = null
        val got = kotlinx.coroutines.withTimeout(20_000) { Ledger.inTurn(listOf(nanoXCable, nanoXRadio), show = { n, s -> shown += n to s }) { r, turn ->
            if (r == nanoXCable) {
                turn.stage(Ledger.Stage.UNLOCK) // locked: lets the radio route start
                var reads = 0
                Ledger.claimHolding(account, turn, readyMs = 5_000, pollMs = 10) {
                    if (reads++ == 0) {
                        radioSent.await()
                        throw ex(LedgerException.Kind.LOCKED)
                    }
                    // Unlocked: the radio read answers only well after this one's been checked.
                    outer.launch {
                        delay(300)
                        shownWhileClaiming = shown.last()
                        radioAnswer.complete(Unit)
                    }
                    account
                }
                r
            } else {
                turn.stage(Ledger.Stage.CONNECTING)
                turn.apdu {
                    radioSent.complete(Unit)
                    radioAnswer.await()
                    other
                }
                delay(60_000)
                r
            }
        } }
        assertEquals(nanoXCable, got)
        assertTrue(shown.any { it == nanoXCable.name to Ledger.Stage.UNLOCK })
        assertEquals(nanoXCable.name to Ledger.Stage.READING, shownWhileClaiming)
    }

    @Test
    fun `Cancel ends a Ledger's APDU at once`() = runBlocking(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val run = async {
            Ledger.inTurn(listOf(nanoXCable)) { r, turn ->
                turn.apdu { delay(60_000) }
                r
            }
        }
        delay(100)
        run.cancel()
        try {
            run.await()
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `a Ledger started just as another takes the conversation sends nothing`() {
        val race = Ledger.Companion.Race(listOf(nanoXCable, nanoXRadio), show = { _, _ -> })
        assertTrue(race.claim(0))
        val job = kotlinx.coroutines.Job()
        race.started(1, job)
        assertTrue(job.isCancelled)
        try {
            race.exchanging(1)
            fail("an APDU went out")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `on a thread pool, exactly one Ledger takes the conversation and the rest are let go`() = runBlocking(Dispatchers.Default) {
        repeat(200) {
            val routes = listOf(nanoSPlusA, nanoSPlusB, nanoXCable, nanoXRadio)
            val claimed = AtomicInteger()
            val got = Ledger.inTurn(routes) { r, turn ->
                // Every one locked at first, so all are started, then all hold the account at about the same time.
                Ledger.holding(account, turn::stage, readyMs = 5_000, pollMs = 1, read = reads(LedgerException.Kind.LOCKED, account))
                turn.claim()
                claimed.incrementAndGet()
                r
            }
            assertTrue(got in routes)
            assertEquals(1, claimed.get())
        }
    }

    @Test
    fun `never unlocked, the lock is what's reported`() = runBlocking {
        try {
            Ledger.inTurn(listOf(nanoSPlusA, nanoXRadio)) { r, turn ->
                if (r == nanoXRadio) throw NotThisLedger(ex(LedgerException.Kind.NOT_FOUND))
                Ledger.holding(account, turn::stage, readyMs = 50, pollMs = 10, read = reads(LedgerException.Kind.LOCKED))
            }
            fail("succeeded")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.LOCKED, e.kind)
        }
    }

    /**
     * Opening the Ethereum app on a Ledger over USB drops it off the bus
     * and brings it back under a new path (#350 R1-F1): waited on, it's
     * followed there and read again, not taken as unplugged.
     */
    @Test
    fun `a Ledger that re-enumerates while it's waited on is followed, not taken as unplugged`() = runBlocking {
        val follows = AtomicInteger()
        val stages = mutableListOf<Ledger.Stage>()
        val got = Ledger.awaitReady({ stages += it }, readyMs = 5_000, pollMs = 10, follow = { follows.incrementAndGet(); true }, read = reads(LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.DISCONNECTED, account))
        assertEquals(account, got)
        assertEquals(1, follows.get())
        // Said to be connecting while it's followed, not still "Open the Ethereum app" (#350 R2-M2).
        assertEquals(listOf(Ledger.Stage.OPEN_APP, Ledger.Stage.CONNECTING), stages)
        // Through holding too, and twice over (quitting another app, then opening this one).
        assertEquals(null, try {
            Ledger.holding(account, {}, readyMs = 5_000, pollMs = 10, follow = { true }, read = reads(LedgerException.Kind.LOCKED, LedgerException.Kind.DISCONNECTED, LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.DISCONNECTED, account))
            null
        } catch (e: NotThisLedger) {
            e
        })
    }

    @Test
    fun `a Ledger that drops out is followed only while it's waited on, and only if it comes back`() = runBlocking {
        val follows = AtomicInteger()
        // Gone at the first read: nothing was waited on, so it's unplugged; nothing is followed.
        try {
            Ledger.awaitReady({}, readyMs = 5_000, pollMs = 10, follow = { follows.incrementAndGet(); true }, read = reads(LedgerException.Kind.DISCONNECTED, account))
            fail("followed")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
        }
        assertEquals(0, follows.get())
        // Waited on, but never back: unplugged.
        try {
            Ledger.awaitReady({}, readyMs = 5_000, pollMs = 10, follow = { follows.incrementAndGet(); false }, read = reads(LedgerException.Kind.LOCKED, LedgerException.Kind.DISCONNECTED, account))
            fail("followed")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
        }
        assertEquals(1, follows.get())
        // Past the wait for unlocking, it isn't followed either.
        try {
            Ledger.awaitReady({}, readyMs = 30, pollMs = 40, follow = { follows.incrementAndGet(); true }, read = reads(LedgerException.Kind.LOCKED, LedgerException.Kind.DISCONNECTED, account))
            fail("followed")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
        }
        assertEquals(1, follows.get())
    }

    /** A [LedgerUsbTrail] on a hand-driven clock. */
    private class Clock { var ms = 0L }

    private fun trail(own: String, listed: List<String>, clock: Clock) = LedgerUsbTrail(own, listed, goneMs = 10_000, clock = { clock.ms })

    @Test
    fun `the path a Ledger comes back under is one that wasn't listed before`() {
        val a = "/dev/bus/usb/001/002"
        val b = "/dev/bus/usb/001/003"
        val back = "/dev/bus/usb/001/005"
        val t = trail(a, listOf(a, b), Clock())
        assertEquals(LedgerUsbTrail.Back(back, unsure = false), t.reappeared(emptySet(), listOf(b, back)))
        // Not back yet; another Ledger plugged in already is its own route.
        assertEquals(LedgerUsbTrail.Back(null, unsure = false), t.reappeared(emptySet(), listOf(b)))
    }

    /**
     * Two Nano S Plus on a hub (#350 R2-F1): the Ethereum app is opened on
     * B, then on A. B came back as B' while A was still answering, so A's
     * route knows B' isn't A, and takes A' — not B', the first by path.
     */
    @Test
    fun `a same-model Ledger that re-enumerated meanwhile is never taken for this one`() {
        val a = "/dev/bus/usb/001/002"
        val b = "/dev/bus/usb/001/003"
        val bBack = "/dev/bus/usb/001/005"
        val aBack = "/dev/bus/usb/001/006"
        val clock = Clock()
        val t = trail(a, listOf(a, b), clock)
        clock.ms = 1_500
        t.answered(listOf(a))
        clock.ms = 3_000
        // A answered with B' listed: B' isn't A's.
        t.answered(listOf(a, bBack))
        // B' answered for B: B isn't waited for, however soon A drops out.
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), t.reappeared(emptySet(), listOf(bBack, aBack)))

        // Not seen while A answered, but B's route has it open: still not A's.
        val u = trail(a, listOf(a, b), Clock())
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), u.reappeared(setOf(bBack), listOf(bBack, aBack)))
        // Both came back within one poll and neither is held yet: no guess at all.
        assertEquals(LedgerUsbTrail.Back(null, unsure = true), u.reappeared(emptySet(), listOf(bBack, aBack)))
        // Once B's route has taken its own, A's is the one left.
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), u.reappeared(setOf(bBack), listOf(aBack, bBack)))
    }

    /**
     * Two Nano S Plus, A tapped and B not (#350 R3-F1): the Ethereum app
     * is opened on B, then on A within a poll — B came back as B' after A
     * last answered, and before A dropped out. B' is the only new path
     * when A's follow starts, but B is gone too, so B' may be B's: it's
     * not taken for A — nor is A' once it comes back, with nothing to
     * tell the two apart. Once B's route holds B', A' is A's.
     */
    @Test
    fun `a path that appeared since the last answer while another Ledger is gone too is never taken`() {
        val a = "/dev/bus/usb/001/002"
        val b = "/dev/bus/usb/001/003"
        val bBack = "/dev/bus/usb/001/005"
        val aBack = "/dev/bus/usb/001/006"
        val t = trail(a, listOf(a, b), Clock())
        assertEquals(LedgerUsbTrail.Back(null, unsure = true), t.reappeared(emptySet(), listOf(bBack)))
        assertEquals(LedgerUsbTrail.Back(null, unsure = true), t.reappeared(emptySet(), listOf(bBack, aBack)))
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), t.reappeared(setOf(bBack), listOf(bBack, aBack)))
        // A Ledger unplugged meanwhile is as good as one that may come back: no guess.
        assertEquals(LedgerUsbTrail.Back(null, unsure = true), t.reappeared(emptySet(), listOf(aBack)))
        // Only A gone: the one new path is A's, however soon after the last answer it appeared.
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), t.reappeared(emptySet(), listOf(b, aBack)))
        // Nothing new at all: nothing to be unsure of — it's unplugged if it stays so.
        assertEquals(LedgerUsbTrail.Back(null, unsure = false), t.reappeared(emptySet(), listOf(b)))
    }

    /**
     * Two Nano S Plus, A tapped and B not (#350 R4-F1): B is off the bus
     * re-enumerating when A answers, so it isn't listed then — B is still
     * remembered as gone, and B' (back after that answer) isn't taken for
     * A once A drops out.
     */
    @Test
    fun `a same-model Ledger off the bus when this one answered is still waited for`() {
        val a = "/dev/bus/usb/001/002"
        val b = "/dev/bus/usb/001/003"
        val bBack = "/dev/bus/usb/001/005"
        val aBack = "/dev/bus/usb/001/006"
        val clock = Clock()
        val t = trail(a, listOf(a, b), clock)
        clock.ms = 1_500
        // A answers APP_NOT_OPEN while B is off the bus.
        t.answered(listOf(a))
        // B came back as B', then A dropped out before its next answer.
        assertEquals(LedgerUsbTrail.Back(null, unsure = true), t.reappeared(emptySet(), listOf(bBack)))
        assertEquals(LedgerUsbTrail.Back(null, unsure = true), t.reappeared(emptySet(), listOf(bBack, aBack)))
        // B's route holds B': A' is A's.
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), t.reappeared(setOf(bBack), listOf(bBack, aBack)))
        // Several answers with B still off the bus, not yet long enough to be taken as unplugged.
        clock.ms = 9_000
        t.answered(listOf(a))
        assertEquals(LedgerUsbTrail.Back(null, unsure = true), t.reappeared(emptySet(), listOf(bBack)))
    }

    /**
     * A same-model Ledger off the bus for longer than one is given to
     * come back, while this one answered, was unplugged: it's no longer
     * waited for, so this one is followed.
     */
    @Test
    fun `a same-model Ledger away long enough is taken as unplugged`() {
        val a = "/dev/bus/usb/001/002"
        val b = "/dev/bus/usb/001/003"
        val aBack = "/dev/bus/usb/001/006"
        val clock = Clock()
        val t = trail(a, listOf(a, b), clock)
        clock.ms = 1_500
        t.answered(listOf(a))
        clock.ms = 11_500
        t.answered(listOf(a))
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), t.reappeared(emptySet(), listOf(aBack)))
    }

    /** Followed once, its new path is its own, and the old ones are never taken for it on the next follow. */
    @Test
    fun `a Ledger followed once is followed again from its new path`() {
        val a = "/dev/bus/usb/001/002"
        val b = "/dev/bus/usb/001/003"
        val aBack = "/dev/bus/usb/001/006"
        val aAgain = "/dev/bus/usb/001/007"
        val t = trail(a, listOf(a, b), Clock())
        assertEquals(LedgerUsbTrail.Back(aBack, unsure = false), t.reappeared(emptySet(), listOf(b, aBack)))
        t.followed(aBack, listOf(b, aBack))
        assertEquals(LedgerUsbTrail.Back(aAgain, unsure = false), t.reappeared(emptySet(), listOf(b, aAgain)))
        assertEquals(LedgerUsbTrail.Back(null, unsure = false), t.reappeared(emptySet(), listOf(b)))
    }

    @Test
    fun `every answer while it's waited on is reported, so what's plugged in then is known`() = runBlocking {
        val alive = AtomicInteger()
        Ledger.awaitReady({}, readyMs = 5_000, pollMs = 1, alive = { alive.incrementAndGet() }, read = reads(LedgerException.Kind.LOCKED, LedgerException.Kind.APP_NOT_OPEN, account))
        assertEquals(2, alive.get())
    }

    /**
     * Following counts against the wait for unlocking (#350 R2-M1): a
     * Ledger that drops out near the end, whose follow (the wait for it to
     * come back, Android's USB prompt) runs past it, ends there — as left
     * on another app, as it was last seen.
     */
    @Test
    fun `a follow that runs past the wait for unlocking ends at it`() = runBlocking {
        val start = System.nanoTime()
        try {
            Ledger.awaitReady({}, readyMs = 200, pollMs = 10, follow = { kotlinx.coroutines.delay(10_000); true }, read = reads(LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.DISCONNECTED, account))
            fail("followed")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.APP_NOT_OPEN, e.kind)
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    /**
     * A follow that runs out on Android's USB prompt (#350 R3-M2): the app
     * is open (that's what re-enumerated it), so the wait ends as USB
     * access not given, not as "Open the Ethereum app".
     */
    @Test
    fun `a follow that runs out on the USB prompt ends as access not given`() = runBlocking {
        var stuck: LedgerException? = null
        try {
            Ledger.awaitReady(
                {}, readyMs = 200, pollMs = 10,
                follow = {
                    stuck = LedgerUsbLink.accessNotGiven()
                    kotlinx.coroutines.delay(10_000)
                    true
                },
                stuck = { stuck },
                read = reads(LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.DISCONNECTED, account),
            )
            fail("followed")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.PERMISSION, e.kind)
        }
    }

    /**
     * A follow held back because where the Ledger came back can't be told
     * from another same-model Ledger's (#350 R4-M1) ends saying so —
     * whether the follow gives up itself or the wait for unlocking runs
     * out on it — not that the Ledger was unplugged.
     */
    @Test
    fun `a follow held back by another same-model Ledger says so`() = runBlocking {
        try {
            Ledger.awaitReady({}, readyMs = 5_000, pollMs = 10, follow = { throw Ledger.cannotTell() }, read = reads(LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.DISCONNECTED, account))
            fail("followed")
        } catch (e: LedgerException) {
            assertEquals(Ledger.cannotTell().message, e.message)
        }
        var stuck: LedgerException? = null
        try {
            Ledger.awaitReady(
                {}, readyMs = 200, pollMs = 10,
                follow = {
                    stuck = Ledger.cannotTell()
                    kotlinx.coroutines.delay(10_000)
                    true
                },
                stuck = { stuck },
                read = reads(LedgerException.Kind.APP_NOT_OPEN, LedgerException.Kind.DISCONNECTED, account),
            )
            fail("followed")
        } catch (e: LedgerException) {
            assertEquals(Ledger.cannotTell().message, e.message)
        }
    }

    @Test
    fun `a link followed to its Ledger's new path talks there, and closes it once it's closed`() = runBlocking {
        class Fake(val id: Int) : LedgerLink {
            var closed = false
            override suspend fun exchange(apdu: ByteArray, timeoutMs: Long) = byteArrayOf(id.toByte())
            override fun close() { closed = true }
        }
        val first = Fake(1)
        val link = Ledger.Companion.FollowingLink(first)
        val second = Fake(2)
        link.replace(second)
        assertTrue(first.closed)
        assertEquals(2, link.exchange(byteArrayOf(), 10)[0].toInt())
        link.close()
        assertTrue(second.closed)
        // Followed after it was closed (a route let go meanwhile): the new link is closed at once.
        val third = Fake(3)
        link.replace(third)
        assertTrue(third.closed)
    }

    @Test
    fun `a tapped Ledger that re-enumerated is found where it was followed to (R6-F1)`() {
        val moves = mapOf("usb:/dev/bus/usb/001/005" to "usb:/dev/bus/usb/001/006", "usb:/dev/bus/usb/001/006" to "usb:/dev/bus/usb/001/007")
        // Followed twice (opened the Ethereum app, then quit it): the chain is walked to where it is now.
        assertEquals("usb:/dev/bus/usb/001/007", Ledger.movedTo("usb:/dev/bus/usb/001/005", setOf("usb:/dev/bus/usb/001/007"), moves))
        // Still at its path: never moved on, even with a move recorded from it.
        assertEquals("usb:/dev/bus/usb/001/005", Ledger.movedTo("usb:/dev/bus/usb/001/005", setOf("usb:/dev/bus/usb/001/005", "usb:/dev/bus/usb/001/007"), moves))
        // Followed, then unplugged: ends at a path that isn't listed, so it reads as unplugged.
        assertEquals("usb:/dev/bus/usb/001/007", Ledger.movedTo("usb:/dev/bus/usb/001/005", emptySet(), moves))
        // Never followed: as it is.
        assertEquals("usb:/dev/bus/usb/001/002", Ledger.movedTo("usb:/dev/bus/usb/001/002", emptySet(), moves))
        // A loop ends.
        val loop = mapOf("usb:a" to "usb:b", "usb:b" to "usb:a")
        assertTrue(Ledger.movedTo("usb:a", emptySet(), loop) in setOf("usb:a", "usb:b"))
    }

    @Test
    fun `a tapped Ledger replugged between reads is looked for among the Ledgers of its model (R1-F1)`() {
        val tapped = "usb:/dev/bus/usb/001/005"
        val back = Route("usb:/dev/bus/usb/001/009", "Ledger Nano S Plus")
        val other = Route("usb:/dev/bus/usb/001/010", "Ledger Nano S Plus")
        val nanoX = Route("usb:/dev/bus/usb/001/011", "Ledger Nano X")
        // Still where it was read: only there.
        assertEquals(listOf(Route(tapped, "Ledger Nano S Plus")), Ledger.replugRoutes(tapped, "Ledger Nano S Plus", listOf(Route(tapped, "Ledger Nano S Plus"), other), known = true))
        // Replugged under a new path: that one, never a Ledger of another model.
        assertEquals(listOf(back), Ledger.replugRoutes(tapped, "Ledger Nano S Plus", listOf(back, nanoX), known = false))
        // Two of its model, and it was read before: both, each checked against that read.
        assertEquals(listOf(back, other), Ledger.replugRoutes(tapped, "Ledger Nano S Plus", listOf(back, other, nanoX), known = true))
        // Two, and nothing to check them against: neither is taken.
        try {
            Ledger.replugRoutes(tapped, "Ledger Nano S Plus", listOf(back, other), known = false)
            fail("expected replugCannotTell")
        } catch (e: LedgerException) {
            assertEquals(Ledger.replugCannotTell().message, e.message)
        }
        // None of its model: unplugged — plugging it back in is what works now.
        try {
            Ledger.replugRoutes(tapped, "Ledger Nano S Plus", listOf(nanoX), known = true)
            fail("expected unplugged")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
        }
    }

    @Test
    fun `a replugged Ledger is read only if it gives what the tapped one gave (R1-F1)`() = runBlocking {
        val seen = "m/44'/60'/0'/0/0" to "0xAAA"
        // Same address at the same path: it.
        Ledger.checkSeen(seen, "m/44'/60'/0'/0/0", "0xaaa") { fail("no second read"); "" }
        // A read at another path (Show more): the remembered one is read again and matches.
        Ledger.checkSeen(seen, "m/44'/60'/5'/0/0", "0xBBB") { p -> if (p == seen.first) "0xAAA" else "0x0" }
        // Nothing read before: taken as is.
        Ledger.checkSeen(null, "m/44'/60'/0'/0/0", "0xCCC") { fail("no second read"); "" }
        // Another Ledger: passed over.
        try {
            Ledger.checkSeen(seen, "m/44'/60'/0'/0/0", "0xDDD") { "" }
            fail("expected NotThisLedger")
        } catch (e: NotThisLedger) {
            assertEquals(LedgerException.Kind.WRONG_DEVICE, e.reason.kind)
        }
        // Two of its model plugged in: only the one that is it is read.
        val addresses = mapOf("usb:a" to "0xDDD", "usb:b" to "0xAAA")
        val got = Ledger.inTurn(listOf(Route("usb:a", "Ledger Nano S Plus"), Route("usb:b", "Ledger Nano S Plus"))) { route, turn ->
            val a = addresses.getValue(route.id)
            Ledger.checkSeen(seen, seen.first, a) { a }
            turn.claim()
            route.id
        }
        assertEquals("usb:b", got)
    }
}
