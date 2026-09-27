package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabDocumentsTest {

    private val ipfs = "https://bafya.ipfs.freedom.baby"
    private val frame = "https://bafyframe.ipfs.freedom.baby"
    private val other = "https://bafyother.ipfs.freedom.baby"

    @Test
    fun `leaving an origin stops the tab counting as on it (R4-F1)`() {
        val tab = TabDocuments()
        tab.requested(ipfs)
        tab.mainFrameAnswered("ipfs-page")
        tab.committed("ipfs-page", ipfs)
        tab.requested(frame)
        assertEquals(setOf(ipfs, frame), tab.origins())

        // An unrelated http page in the same tab: neither is on screen now.
        tab.mainFrameAnswered("http://10.0.2.2:8700/form")
        tab.committed("http://10.0.2.2:8700/form", null)
        assertTrue(tab.origins().isEmpty())
        assertEquals(
            emptySet<String>(),
            sweptOrigins(setOf(ipfs, frame), tab.origins(), "http://10.0.2.2:8700/form"),
        )
    }

    @Test
    fun `a frame requested before its document's commit reaches the UI thread is kept (R3-F1)`() {
        val tab = TabDocuments()
        tab.requested(other)
        tab.mainFrameAnswered("https://example.org/")
        // Chromium has the answer and fetches the frame before onPageStarted runs.
        tab.requested(frame)
        tab.committed("https://example.org/", null)
        assertEquals(setOf(frame), tab.origins())
    }

    @Test
    fun `a frame the outgoing document re-requests after the answer is kept, in doubt`() {
        val tab = TabDocuments()
        tab.requested(frame)
        tab.mainFrameAnswered("https://example.org/")
        tab.requested(frame)
        tab.committed("https://example.org/", null)
        assertEquals(setOf(frame), tab.origins())
    }

    @Test
    fun `a commit without an answer prunes nothing`() {
        val tab = TabDocuments()
        tab.requested(frame)
        // A page a service worker answered, or a history entry restored
        // without a request.
        tab.committed("https://example.org/", null)
        assertEquals(setOf(frame), tab.origins())
        assertTrue(tab.mayHoldWorkerFetchAt(DocumentClock.next()))
    }

    @Test
    fun `a commit matches its own answer, or the latest one after a network redirect`() {
        val tab = TabDocuments()
        tab.mainFrameAnswered("https://a.example/")
        tab.requested(frame)
        tab.mainFrameAnswered("https://b.example/")
        // A's document commits while B's answer is already out: A's
        // frame stays.
        tab.committed("https://a.example/", null)
        assertEquals(setOf(frame), tab.origins())
        // B was redirected on the network: its commit URL is new, and the
        // latest answer is taken.
        tab.committed("https://b.example/redirected", null)
        assertTrue(tab.origins().isEmpty())
    }

    @Test
    fun `a worker fetch only counts for a tab whose document predates it`() {
        val tab = TabDocuments()
        tab.mainFrameAnswered("https://a.example/")
        tab.committed("https://a.example/", null)
        val fetched = DocumentClock.next()
        assertTrue(tab.mayHoldWorkerFetchAt(fetched))
        tab.mainFrameAnswered("https://b.example/")
        // Answered but not yet committed: A's frames are still on screen.
        assertTrue(tab.mayHoldWorkerFetchAt(fetched))
        tab.committed("https://b.example/", null)
        assertFalse(tab.mayHoldWorkerFetchAt(fetched))
    }
}
