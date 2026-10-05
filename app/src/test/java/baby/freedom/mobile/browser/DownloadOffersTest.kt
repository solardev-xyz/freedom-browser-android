package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAGE = "https://example.com"

class DownloadOffersTest {
    @Test
    fun nothingStartsBeforeAccept() {
        val offers = DownloadOffers()
        var started = 0
        offers.offer(1, PAGE, "a.apk", "https://example.com/a.apk", 10) { started++ }
        assertEquals(0, started)
        assertEquals(listOf("a.apk"), offers.pending.value.map { it.fileName })
    }

    @Test
    fun anOfferSaysWhetherItIsPrivate() {
        // The notification-permission ask skips a private one (#265).
        val offers = DownloadOffers()
        offers.offer(1, PAGE, "a.txt", "x", -1) {}
        offers.offer(2, PAGE, "b.txt", "x", -1, private = true) {}
        assertEquals(listOf(false, true), offers.pending.value.map { it.private })
    }

    @Test
    fun acceptStartsOnceAndLeavesTheQueue() {
        val offers = DownloadOffers()
        var started = 0
        offers.offer(1, PAGE, "a.txt", "x", -1) { started++ }
        val key = offers.pending.value.single().key
        offers.accept(key)
        offers.accept(key) // a double tap on Download
        assertEquals(1, started)
        assertTrue(offers.pending.value.isEmpty())
    }

    @Test
    fun declineStartsNothing() {
        val offers = DownloadOffers()
        var started = 0
        offers.offer(1, null, "a.txt", "x", -1) { started++ }
        offers.offer(1, null, "b.txt", "x", -1) { started++ }
        offers.decline(offers.pending.value.first().key)
        assertEquals(listOf("b.txt"), offers.pending.value.map { it.fileName })
        offers.declineAll(1)
        assertTrue(offers.pending.value.isEmpty())
        assertEquals(0, started)
    }

    @Test
    fun aDeclinedPageCantAskAgainUntilTheUserNavigates() {
        // R5-F1: a page firing `<a download>` in a loop re-raised the
        // modal prompt as fast as it was cancelled.
        val offers = DownloadOffers()
        assertTrue(offers.offer(1, PAGE, "a.txt", "x", -1) {})
        assertTrue(offers.offer(1, PAGE, "b.txt", "x", -1) {})
        assertTrue(offers.offer(2, PAGE, "other-tab.txt", "x", -1) {})
        offers.decline(offers.pending.value.first().key)
        // The tab's waiting offer goes with the no; another tab's stays.
        assertEquals(listOf("other-tab.txt"), offers.pending.value.map { it.fileName })
        assertTrue(offers.isBlocked(1))
        assertFalse(offers.offer(1, PAGE, "again.txt", "x", -1) {})
        assertFalse(offers.offer(1, "https://elsewhere.example", "hop.txt", "x", -1) {})
        // The user's own request in that tab still asks.
        assertTrue(offers.offer(1, null, "typed.txt", "x", -1) {})
        // Their own navigation lifts the block.
        offers.allow(1)
        assertTrue(offers.offer(1, PAGE, "after-reload.txt", "x", -1) {})
    }

    @Test
    fun declineAllOnlyTouchesItsTab() {
        val offers = DownloadOffers()
        offers.offer(1, PAGE, "a.txt", "x", -1) {}
        offers.offer(1, PAGE, "b.txt", "x", -1) {}
        offers.offer(2, PAGE, "c.txt", "x", -1) {}
        offers.declineAll(1)
        assertEquals(listOf("c.txt"), offers.pending.value.map { it.fileName })
        assertTrue(offers.isBlocked(1))
        assertFalse(offers.isBlocked(2))
    }

    @Test
    fun decliningTheUsersOwnRequestBlocksNothing() {
        val offers = DownloadOffers()
        offers.offer(1, null, "typed.txt", "x", -1) {}
        offers.decline(offers.pending.value.single().key)
        assertFalse(offers.isBlocked(1))
    }

    @Test
    fun closedTabsTakeTheirOffersAndBlocks() {
        val offers = DownloadOffers()
        offers.offer(1, PAGE, "a.txt", "x", -1) {}
        offers.decline(offers.pending.value.single().key)
        offers.offer(2, PAGE, "b.txt", "x", -1) {}
        offers.offer(3, PAGE, "c.txt", "x", -1) {}
        offers.retainTabs(setOf(3))
        assertEquals(listOf("c.txt"), offers.pending.value.map { it.fileName })
        assertFalse(offers.isBlocked(1))
    }

    @Test
    fun requesterNamesThePage() {
        assertEquals(null, downloadRequester(null))
        assertEquals("https://example.com", downloadRequester("https://Example.com:443/a/b?c"))
        assertEquals("http://example.com:8080", downloadRequester("http://example.com:8080/x"))
        assertEquals("data:", downloadRequester("data:text/html,<a download>"))
        assertEquals("about:", downloadRequester("about:blank"))
        assertEquals("a page", downloadRequester(""))
        // A dweb page is named by its root, as the address bar shows it.
        val display = { u: String -> if (u.startsWith("https://abc.bzz.")) "bzz://abc/page.html?x" else u }
        assertEquals("bzz://abc", downloadRequester("https://abc.bzz.freedom.baby/page.html?x", display))
    }

    @Test
    fun dwebHeaderTimeoutEndsTheRetries() {
        // R5-F2: nine 60 s read timeouts would hold the row ~10 min.
        assertFalse(dwebHeaderFailureRetries(java.net.SocketTimeoutException("read timed out")))
        assertTrue(dwebHeaderFailureRetries(java.net.SocketException("Connection reset")))
        assertTrue(dwebHeaderFailureRetries(java.io.IOException("unexpected end of stream")))
    }

    @Test
    fun aLoopingPageCantQueueMoreThanTheCap() {
        val offers = DownloadOffers()
        repeat(MAX_PENDING_OFFERS) { assertTrue(offers.offer(1, PAGE, "f$it", "x", -1) {}) }
        assertFalse(offers.offer(1, PAGE, "one too many", "x", -1) {})
        assertEquals(MAX_PENDING_OFFERS, offers.pending.value.size)
        // Answering one makes room again (a yes: a no blocks the tab).
        offers.accept(offers.pending.value.first().key)
        assertTrue(offers.offer(1, PAGE, "next", "x", -1) {})
    }

    @Test
    fun aBackgroundTabAtTheCapDoesntBlockAnotherTab() {
        // R6-F1: the cap was process-wide, and only the tab in view shows
        // its offers, so an unanswered background tab refused every tab.
        val offers = DownloadOffers()
        repeat(MAX_PENDING_OFFERS + 3) { offers.offer(2, PAGE, "bg$it", "x", -1) {} }
        assertEquals(mapOf(2L to 3), offers.dropped.value)
        assertTrue(offers.offer(1, PAGE, "active.txt", "x", -1) {})
        assertEquals(listOf("active.txt"), offers.pending.value.filter { it.tabId == 1L }.map { it.fileName })
        assertEquals(null, offers.dropped.value[1])
    }

    @Test
    fun theUsersOwnDownloadIsQueuedWithAFullQueue() {
        val offers = DownloadOffers()
        repeat(MAX_PENDING_OFFERS) { offers.offer(1, PAGE, "f$it", "x", -1) {} }
        assertFalse(offers.offer(1, PAGE, "one too many", "x", -1) {})
        // A typed download in the same, full tab still asks.
        assertTrue(offers.offer(1, null, "typed.txt", "x", -1) {})
        // …and doesn't count toward the page's cap either.
        assertEquals(MAX_PENDING_OFFERS, offers.pending.value.count { it.requestedBy != null })
    }

    @Test
    fun closingATabFreesItsSlotsAndDropCount() {
        val offers = DownloadOffers()
        repeat(MAX_PENDING_OFFERS + 1) { offers.offer(2, PAGE, "bg$it", "x", -1) {} }
        assertEquals(1, offers.dropped.value[2])
        offers.retainTabs(setOf(1))
        assertTrue(offers.pending.value.isEmpty())
        assertTrue(offers.dropped.value.isEmpty())
        // Nothing of the closed tab pins the cap for the tab still open.
        repeat(MAX_PENDING_OFFERS) { assertTrue(offers.offer(1, PAGE, "f$it", "x", -1) {}) }
    }

    @Test
    fun theDropCountLastsUntilTheTabsQueueIsAnswered() {
        val offers = DownloadOffers()
        repeat(MAX_PENDING_OFFERS + 2) { offers.offer(1, PAGE, "f$it", "x", -1) {} }
        assertEquals(2, offers.dropped.value[1])
        offers.accept(offers.pending.value.first().key)
        assertEquals(2, offers.dropped.value[1])
        offers.declineAll(1)
        assertTrue(offers.dropped.value.isEmpty())
    }

    @Test
    fun droppedLine() {
        assertEquals("1 further download from this tab wasn't offered: 10 were already waiting.", downloadOfferDroppedLine(1))
        assertEquals("3 further downloads from this tab weren't offered: 10 were already waiting.", downloadOfferDroppedLine(3))
    }

    @Test
    fun keysAreUnique() {
        val offers = DownloadOffers()
        repeat(3) { offers.offer(1, PAGE, "same.txt", "x", -1) {} }
        assertEquals(3, offers.pending.value.map { it.key }.toSet().size)
    }

    @Test
    fun storageFloor() {
        val floor = STORAGE_FLOOR_BYTES
        // Unknown free space doesn't block.
        assertTrue(downloadFitsStorage(null, 10L shl 30, floor))
        assertTrue(downloadFitsStorage(floor + 100, 100, floor))
        assertFalse(downloadFitsStorage(floor + 100, 101, floor))
        // Unknown length: only the floor itself counts (re-checked while writing).
        assertFalse(downloadFitsStorage(floor - 1, 0, floor))
        assertTrue(downloadFitsStorage(floor, 0, floor))
    }

    @Test
    fun pendingNamesDifferPerDownloadAndKeepTheExtension() {
        val a = pendingDownloadName("dl1", "report.txt")
        val b = pendingDownloadName("dl2", "report.txt")
        assertTrue(a != b)
        assertTrue(a.endsWith(".txt") && b.endsWith(".txt"))
        assertFalse(a.startsWith("."))
    }

    @Test
    fun offerSizeLine() {
        assertEquals("Size unknown", downloadOfferSizeLine(-1))
        assertEquals("Size unknown", downloadOfferSizeLine(0))
        assertEquals(formatBytes(2048), downloadOfferSizeLine(2048))
    }

    @Test
    fun anOfferThatGoesUnstartedIsDiscardedOnce() {
        // A blob: offer's file is held by its page until the offer goes.
        val offers = DownloadOffers()
        val discarded = mutableListOf<String>()
        fun offer(tab: Long, name: String) =
            offers.offer(tab, PAGE, name, "x", -1, discard = { discarded += name }) {}
        offer(1, "declined")
        offer(1, "declined-with-all")
        offer(2, "closed-tab")
        offer(3, "accepted")
        offers.decline(offers.pending.value.first { it.fileName == "declined" }.key)
        // The no blocked tab 1: its other offer went with it.
        assertEquals(listOf("declined", "declined-with-all"), discarded)
        offers.retainTabs(setOf(3))
        assertEquals(listOf("declined", "declined-with-all", "closed-tab"), discarded)
        offers.accept(offers.pending.value.single().key)
        offers.declineAll(3)
        assertEquals(3, discarded.size)
        // A blocked tab's offer is refused at once (its caller lets go of
        // it, DownloadManager.start); there's nothing queued to discard.
        offer(4, "declined-on-4")
        offers.decline(offers.pending.value.single().key)
        assertFalse(offer(4, "while-blocked"))
        assertEquals(listOf("declined", "declined-with-all", "closed-tab", "declined-on-4"), discarded)
    }
}
