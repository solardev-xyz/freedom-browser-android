package baby.freedom.mobile.browser

import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Downloads search and *Ask where to save each file* (#322). */
class DownloadSaveToTest {
    private fun entry(fileName: String, displayUrl: String) = DownloadEntry(
        fileName = fileName,
        displayUrl = displayUrl,
        sourceUrl = "https://$displayUrl",
        mimeType = "application/pdf",
        contentUri = null,
        status = DownloadStatus.COMPLETED,
        totalBytes = 1,
        receivedBytes = 1,
        error = null,
        startedAt = 0,
        finishedAt = 0,
    )

    @Test
    fun searchMatchesFileNameOrSourceIgnoringCase() {
        val report = entry("Annual Report.pdf", "reports.example.com/2026/annual.pdf")
        val swarm = entry("site.tar", "bzz://freedom.eth/site.tar")
        val all = listOf(report, swarm)
        fun find(q: String) = all.filter { downloadMatches(it, q) }
        assertEquals(all, find(""))
        assertEquals(all, find("   "))
        // By name, any case, and with stray spaces around it.
        assertEquals(listOf(report), find("  annual report "))
        // By where it came from.
        assertEquals(listOf(report), find("REPORTS.EXAMPLE"))
        assertEquals(listOf(swarm), find("freedom.eth"))
        assertEquals(listOf(swarm), find("bzz://"))
        assertEquals(emptyList<DownloadEntry>(), find("nothing like it"))
    }

    @Test
    fun onlyDownloadWriteGrantsNotKeptAreStale() {
        val persisted = listOf(
            // A publish's read grant (#118) is PublishGrants' to sweep.
            PersistedGrant("content://docs/publish", write = false),
            // A paused download still saves here.
            PersistedGrant("content://docs/paused", write = true),
            // Left by a download that ended while the process died.
            PersistedGrant("content://docs/ended", write = true),
        )
        assertEquals(
            setOf("content://docs/ended"),
            staleDownloadGrants(persisted, keep = setOf("content://docs/paused")),
        )
        assertEquals(
            setOf("content://docs/paused", "content://docs/ended"),
            staleDownloadGrants(persisted, keep = emptySet()),
        )
    }

    @Test
    fun thePickerGetsATypeItCanCreate() {
        assertEquals("application/pdf", saveAsMimeType("application/pdf"))
        assertEquals("application/octet-stream", saveAsMimeType(null))
        assertEquals("application/octet-stream", saveAsMimeType(""))
        assertEquals("application/octet-stream", saveAsMimeType("*/*"))
        assertEquals("application/octet-stream", saveAsMimeType("image/*"))
        assertEquals("application/octet-stream", saveAsMimeType("garbage"))
    }

    @Test
    fun privateTabsNeverAsk() {
        assertTrue(asksWhereToSave(setting = true, private = false))
        assertFalse(asksWhereToSave(setting = true, private = true))
        assertFalse(asksWhereToSave(setting = false, private = false))
        assertFalse(asksWhereToSave(setting = false, private = true))
    }

    @Test
    fun anAcceptedOfferGetsThePickedDocument() {
        val offers = DownloadOffers()
        val started = mutableListOf<PickedDocument?>()
        offers.offer(1, "https://example.com", "a.pdf", "x", -1, mimeType = "application/pdf") { started += it }
        offers.offer(1, "https://example.com", "b.pdf", "x", -1) { started += it }
        val (a, b) = offers.pending.value
        assertEquals("application/pdf", a.mimeType)
        assertEquals("application/octet-stream", b.mimeType)
        val pick = PickedDocument("content://docs/a", created = true)
        assertTrue(offers.accept(a.key, pick))
        assertTrue(offers.accept(b.key))
        assertEquals(listOf(pick, null), started)
    }

    @Test
    fun acceptingAnOfferThatWentSaysSo() {
        // Its tab closed while the Save as picker was up: nothing starts,
        // and the manager deletes the document the picker created.
        val offers = DownloadOffers()
        var started: PickedDocument? = PickedDocument("not started", created = false)
        offers.offer(7, "https://example.com", "a.pdf", "x", -1) { started = it }
        val key = offers.pending.value.single().key
        offers.retainTabs(emptySet())
        assertFalse(offers.accept(key, PickedDocument("content://docs/a", created = true)))
        assertEquals("not started", started?.uri)
        assertNull(offers.pending.value.firstOrNull())
    }

    @Test
    fun onlyAnEmptyJustModifiedPickCountsAsCreated() {
        val now = 1_000_000_000L
        // The picker's new, empty file.
        assertTrue(pickedDocumentIsNew(size = 0, lastModified = now - 2_000, now = now))
        // A provider that doesn't report the time: an empty pick is taken as new.
        assertTrue(pickedDocumentIsNew(size = 0, lastModified = null, now = now))
        // A little clock skew the other way.
        assertTrue(pickedDocumentIsNew(size = 0, lastModified = now + 30_000, now = now))
        // An existing file with something in it, or of unknown size: the user's.
        assertFalse(pickedDocumentIsNew(size = 10, lastModified = now, now = now))
        assertFalse(pickedDocumentIsNew(size = null, lastModified = now, now = now))
        // An existing empty file last touched long ago: the user's too (R1-M1).
        assertFalse(pickedDocumentIsNew(size = 0, lastModified = now - PICKED_NEW_WINDOW_MS - 1, now = now))
        assertFalse(pickedDocumentIsNew(size = 0, lastModified = now + 3_600_000, now = now))
    }
}
