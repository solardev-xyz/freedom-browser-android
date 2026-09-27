package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOffersTest {
    @Test
    fun nothingStartsBeforeAccept() {
        val offers = DownloadOffers()
        var started = 0
        offers.offer("a.apk", "https://example.com/a.apk", 10) { started++ }
        assertEquals(0, started)
        assertEquals(listOf("a.apk"), offers.pending.value.map { it.fileName })
    }

    @Test
    fun acceptStartsOnceAndLeavesTheQueue() {
        val offers = DownloadOffers()
        var started = 0
        offers.offer("a.txt", "x", -1) { started++ }
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
        offers.offer("a.txt", "x", -1) { started++ }
        offers.offer("b.txt", "x", -1) { started++ }
        offers.decline(offers.pending.value.first().key)
        assertEquals(listOf("b.txt"), offers.pending.value.map { it.fileName })
        offers.declineAll()
        assertTrue(offers.pending.value.isEmpty())
        assertEquals(0, started)
    }

    @Test
    fun aLoopingPageCantQueueMoreThanTheCap() {
        val offers = DownloadOffers()
        repeat(MAX_PENDING_OFFERS) { assertTrue(offers.offer("f$it", "x", -1) {}) }
        assertFalse(offers.offer("one too many", "x", -1) {})
        assertEquals(MAX_PENDING_OFFERS, offers.pending.value.size)
        // Answering one makes room again.
        offers.decline(offers.pending.value.first().key)
        assertTrue(offers.offer("next", "x", -1) {})
    }

    @Test
    fun keysAreUnique() {
        val offers = DownloadOffers()
        repeat(3) { offers.offer("same.txt", "x", -1) {} }
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
}
