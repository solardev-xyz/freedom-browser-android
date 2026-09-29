package baby.freedom.mobile.browser

import baby.freedom.mobile.data.X402Store.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which navigation an x402 402 or a paid request's answer belongs to (#218 R2). */
class X402FlowTest {
    private val settled = mutableListOf<Triple<String, Status, Int?>>()
    private val flow = X402Flow<String>(
        settle = { id, status, http -> settled += Triple(id, status, http) },
        originOf = { url -> url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/') },
    )
    private val tab = 1L
    private val a = "https://pay.example/a"
    private val b = "https://pay.example/b"
    private val origin = "https://pay.example"

    /** Pay's order: the epoch moves, the paid GET is loaded, then noted; the interceptor sees the GET. */
    private fun send(url: String, id: String, tabId: Long = tab) {
        flow.sending(tabId)
        flow.paid(tabId, url, id)
        flow.mainFrameRequested(tabId, url, "GET", flow.epoch(tabId), origin)
    }

    @Test
    fun `a 402 is paid for at its own commit`() {
        flow.detected(tab, a, "terms")
        assertEquals("terms", flow.committed(tab, a)?.value)
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `a paid request's own commit settles it paid`() {
        send(a, "r1")
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }

    @Test
    fun `R2-M1 a paid request redirected to a 402 is refused, not paid again`() {
        send(a, "r1")
        flow.redirected(tab, b)
        assertTrue(flow.httpError(tab, b, "GET", 402))
        // Its commit is the refused page's: nothing to pay.
        assertNull(flow.committed(tab, b))
        assertEquals(listOf(Triple("r1", Status.REFUSED, 402)), settled)
    }

    @Test
    fun `R2-M1 a paid request redirected to its page settles paid`() {
        send(a, "r1")
        flow.redirected(tab, b)
        assertNull(flow.committed(tab, b))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }

    @Test
    fun `R2-M2 a 402 stopped before commit isn't paid at a later commit of its URL`() {
        flow.detected(tab, a, "terms")
        flow.superseded(tab) // Stop, a download, a new load
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `R2-M2 a 402 whose load finished without committing is dropped`() {
        flow.detected(tab, a, "terms")
        flow.loadFinished(tab, "https://other.example/")
        flow.loadFinished(tab, a)
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `R2-M2 a redirect after a 402 drops it`() {
        flow.detected(tab, a, "terms")
        flow.redirected(tab, a)
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `R2-M3 a reload while a paid request is in flight ends it unconfirmed`() {
        send(a, "r1")
        flow.superseded(tab) // the user's Reload
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
        // The reload's own 402 is not the paid request's answer: it's asked about.
        assertFalse(flow.httpError(tab, a, "GET", 402))
        flow.detected(tab, a, "terms")
        assertEquals("terms", flow.committed(tab, a)?.value)
        assertEquals(1, settled.size)
    }

    @Test
    fun `a form POST's error isn't the paid request's answer`() {
        send(a, "r1")
        assertFalse(flow.httpError(tab, a, "POST", 403))
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `a network error on a hop settles unconfirmed, on another URL not`() {
        send(a, "r1")
        flow.failed(tab, b)
        assertTrue(settled.isEmpty())
        flow.redirected(tab, b)
        flow.failed(tab, b)
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `tabs are apart`() {
        send(a, "r1")
        flow.detected(2L, a, "terms")
        flow.superseded(2L)
        assertTrue(settled.isEmpty())
        assertTrue(flow.httpError(tab, a, "GET", 500))
    }

    @Test
    fun `R3-M1 a form POST during a paid request ends it unconfirmed, and its commit isn't Paid`() {
        send(a, "r1")
        flow.mainFrameRequested(tab, a, "POST", flow.epoch(tab), origin)
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R3-M1 a POST's redirect to a 402 is a fresh 402, not the paid request refused`() {
        send(a, "r1")
        flow.mainFrameRequested(tab, a, "POST", flow.epoch(tab), origin)
        flow.redirected(tab, b)
        assertFalse(flow.httpError(tab, b, "GET", 402))
        flow.detected(tab, b, "terms")
        assertEquals("terms", flow.committed(tab, b)?.value)
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R3-M1 the paid request's own GET and its hops don't end it`() {
        send(a, "r1")
        flow.mainFrameRequested(tab, a, "GET", flow.epoch(tab), origin)
        flow.redirected(tab, b)
        flow.mainFrameRequested(tab, b, "GET", flow.epoch(tab), origin)
        assertNull(flow.committed(tab, b))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }

    @Test
    fun `R4-M1 a POST the interceptor saw before the paid request went out doesn't end it`() {
        flow.detected(tab, a, "terms")
        flow.committed(tab, a)
        // The 402 page's form POST: seen, its post still queued...
        val before = flow.epoch(tab)
        // ...when Pay loads the paid request.
        flow.sending(tab)
        flow.paid(tab, a, "r1")
        flow.mainFrameRequested(tab, a, "GET", flow.epoch(tab), origin)
        flow.mainFrameRequested(tab, a, "POST", before, origin)
        assertTrue(settled.isEmpty())
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }

    @Test
    fun `R4-M1 a POST seen after the paid request went out still ends it`() {
        send(a, "r1")
        flow.mainFrameRequested(tab, a, "POST", flow.epoch(tab), origin)
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R4-M1 epochs are per tab`() {
        val before = flow.epoch(tab)
        flow.sending(2L)
        send(a, "r1")
        flow.mainFrameRequested(tab, a, "POST", before + 1, origin)
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R4-M2 a commit at the paid URL the interceptor never saw go out is unconfirmed`() {
        // A service worker answered the paid GET, or a form POST to its URL.
        flow.sending(tab)
        flow.paid(tab, a, "r1")
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R4-M2 the paid GET is matched without the page's fragment`() {
        flow.sending(tab)
        flow.paid(tab, "$a#part", "r1")
        flow.mainFrameRequested(tab, a, "GET", flow.epoch(tab), origin)
        flow.committed(tab, "$a#part")
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }

    @Test
    fun `R4-M2 another GET doesn't count as the paid one seen`() {
        flow.sending(tab)
        flow.paid(tab, a, "r1")
        flow.mainFrameRequested(tab, b, "GET", flow.epoch(tab), origin)
        flow.committed(tab, a)
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R4-M3 the user's own load lets an allowance pay`() {
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = null)
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R4-M3 the site's own page's navigation lets its allowance pay`() {
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = null, gesture = true)
        flow.redirected(tab, b) // before the 402: the site's own redirect
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R4-M3 another site's link, script or popup can't spend the allowance`() {
        flow.navigationStarted(tab, byUser = false, fromOrigin = "https://evil.example", url = null)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
        // A popup's first navigation: no page on screen.
        flow.navigationStarted(tab, byUser = false, fromOrigin = null, url = null)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R4-M3 a navigation nobody was seen starting can't spend it`() {
        // The site's link started one navigation, which committed...
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = null, gesture = true)
        assertNull(flow.committed(tab, "https://evil.example/"))
        // ...then evil's history.go() lands on the 402 with no start signal.
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R4-M3 Stop forgets who started the navigation`() {
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = null)
        flow.superseded(tab)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R4-M3 a form POST from another site can't spend it`() {
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = null)
        flow.mainFrameRequested(tab, a, "POST", flow.epoch(tab), "https://evil.example")
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R5-M1 the user's address redirected by another origin to the 402 can't spend it`() {
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = "https://evil.example/x")
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R5-M1 the site's link redirected back to it by another origin can't spend it`() {
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = "https://evil.example/r", gesture = true)
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
        // Nor through another origin and back again.
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = b)
        flow.redirected(tab, "https://evil.example/r")
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R5-M1 redirects that stay on the site still let its allowance pay`() {
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = b)
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
        // Reload / Back: the entry's own URL isn't named, only its redirects.
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = null)
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R5-M1 a form POST to another origin redirected to the 402 can't spend it`() {
        flow.mainFrameRequested(tab, "https://evil.example/f", "POST", flow.epoch(tab), origin)
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `R6-M1 the site's form POST redirected back to it can't spend it, its 307 hops unseen`() {
        // pay.example POST /x → 307 evil.example (POST, no callback) → 303 pay.example/a:
        // only the last hop is reported, all on the paying origin.
        flow.mainFrameRequested(tab, "https://pay.example/x", "POST", flow.epoch(tab), origin)
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
        // A GET's same-site redirect still pays (its hops all reach shouldOverrideUrlLoading).
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.redirected(tab, a)
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
    }

    @Test
    fun `#237 a page-driven chain of 402s isn't paid silently hop after hop`() {
        // The user opens the page: the allowance pays it.
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = a)
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
        send(a, "r1")
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
        // The paid page sets location.href to the next 402 on its own, with no tap.
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b)
        flow.detected(tab, b, "terms")
        assertFalse(flow.committed(tab, b)!!.allowanceMayPay(origin))
    }

    @Test
    fun `#237 after a Refused payment the site's allowance pays nothing until the user navigates`() {
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = a)
        flow.detected(tab, a, "terms")
        assertTrue(flow.committed(tab, a)!!.allowanceMayPay(origin))
        send(a, "r1")
        assertTrue(flow.httpError(tab, a, "GET", 402))
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.REFUSED, 402)), settled)
        // A link the user taps on the refused page: the sheet asks.
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.detected(tab, b, "terms")
        assertFalse(flow.committed(tab, b)!!.allowanceMayPay(origin))
        // Nor in another tab of the same site (a popup it opened, say).
        flow.navigationStarted(2L, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.detected(2L, b, "terms")
        assertFalse(flow.committed(2L, b)!!.allowanceMayPay(origin))
        // The hold is this site's: another site's allowance still pays.
        val other = "https://other.example"
        flow.navigationStarted(3L, byUser = false, fromOrigin = other, url = "$other/x", gesture = true)
        flow.detected(3L, "$other/x", "terms")
        assertTrue(flow.committed(3L, "$other/x")!!.allowanceMayPay(other))
        // The user's own address on another site doesn't lift it.
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = "$other/y")
        flow.committed(tab, "$other/y")
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.detected(tab, b, "terms")
        assertFalse(flow.committed(tab, b)!!.allowanceMayPay(origin))
        // The user's own Reload (pull-to-refresh) of the site's page lifts it.
        flow.superseded(tab)
        flow.usersStep(tab)
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = null)
        flow.detected(tab, b, "terms")
        assertTrue(flow.committed(tab, b)!!.allowanceMayPay(origin))
    }

    /** The refused page on [tab]: [a] answered Refused and committed. */
    private fun refusedOnScreen() {
        send(a, "r1")
        assertTrue(flow.httpError(tab, a, "GET", 402))
        flow.committed(tab, a)
    }

    /** A link the user taps on the site's page on [tab]: may its allowance pay silently? */
    private fun tappedLinkPays(): Boolean {
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.detected(tab, b, "terms")
        return flow.committed(tab, b)!!.allowanceMayPay(origin)
    }

    @Test
    fun `#237 R1-M1 the bar's Back on the held site's page lifts the hold`() {
        refusedOnScreen()
        // The bar's Back runs `history.back()` in the page: a `javascript:` URL, not the user's
        // own load for an allowance (it may stay in the document, with no commit to end it)...
        flow.superseded(tab)
        flow.usersStep(tab)
        flow.navigationStarted(tab, byUser = false, fromOrigin = null, url = HISTORY_BACK_JS)
        flow.detected(tab, b, "terms")
        assertFalse(flow.committed(tab, b)!!.allowanceMayPay(origin))
        // ...but it lifts the hold: the site's link the user taps next pays silently again.
        assertTrue(tappedLinkPays())
    }

    @Test
    fun `#237 R1-M1 the app's own reload of the held site's page doesn't lift the hold`() {
        refusedOnScreen()
        // A Tor-down or sweep reload: the app's, not the user's.
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = false, fromOrigin = null, url = null)
        flow.detected(tab, a, "terms")
        assertFalse(flow.committed(tab, a)!!.allowanceMayPay(origin))
        assertFalse(tappedLinkPays())
    }

    @Test
    fun `#237 the user's own address on a site held after a Refused payment lifts the hold`() {
        send(a, "r1")
        assertTrue(flow.httpError(tab, a, "GET", 402))
        flow.committed(tab, a)
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = true, fromOrigin = null, url = b)
        flow.detected(tab, b, "terms")
        assertTrue(flow.committed(tab, b)!!.allowanceMayPay(origin))
    }

    @Test
    fun `#237 a Reload of another site's page doesn't lift the hold`() {
        send(a, "r1")
        assertTrue(flow.httpError(tab, a, "GET", 402))
        val other = "https://other.example/"
        flow.navigationStarted(2L, byUser = true, fromOrigin = null, url = other)
        flow.committed(2L, other)
        flow.usersStep(2L)
        flow.navigationStarted(2L, byUser = true, fromOrigin = null, url = null)
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.detected(tab, b, "terms")
        assertFalse(flow.committed(tab, b)!!.allowanceMayPay(origin))
    }

    @Test
    fun `#237 the site's page pays silently for the user's tap, and its redirects on the site`() {
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = a, gesture = true)
        flow.redirected(tab, b)
        flow.detected(tab, b, "terms")
        assertTrue(flow.committed(tab, b)!!.allowanceMayPay(origin))
        // A page-driven hop's server redirect doesn't gain the gesture.
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = a)
        flow.redirected(tab, b)
        flow.detected(tab, b, "terms")
        assertFalse(flow.committed(tab, b)!!.allowanceMayPay(origin))
    }

    @Test
    fun `#237 an unconfirmed or paid request doesn't hold the site`() {
        send(a, "r1")
        flow.committed(tab, a) // paid
        flow.superseded(tab)
        flow.navigationStarted(tab, byUser = false, fromOrigin = origin, url = b, gesture = true)
        flow.detected(tab, b, "terms")
        assertTrue(flow.committed(tab, b)!!.allowanceMayPay(origin))
    }
}
