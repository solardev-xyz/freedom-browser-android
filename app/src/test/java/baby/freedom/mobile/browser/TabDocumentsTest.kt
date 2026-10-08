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

    @Test
    fun `a page a service worker answered prunes what was requested before its navigation started (R5-F1)`() {
        val tab = TabDocuments()
        tab.mainFrameAnswered("$ipfs/")
        tab.committed("$ipfs/", ipfs)
        tab.requested(frame)
        // An answer for a load that never committed is still queued.
        tab.mainFrameAnswered("https://stale.example/")
        // The user types a URL whose page a service worker answers: no
        // answer reaches the interceptor, only the start was seen.
        tab.navigationStarted("HTTP://LocalHost:8700/pwa#top")
        // The outgoing document requests one more frame; kept, in doubt.
        tab.requested(other)
        tab.committed("http://localhost:8700/pwa", null)
        assertEquals(setOf(other), tab.origins())
        assertEquals(
            emptySet<String>(),
            sweptOrigins(setOf(ipfs, frame), tab.origins(), "http://localhost:8700/pwa"),
        )
        // The worker's fetch of an old frame predates the new document.
        assertFalse(tab.mayHoldWorkerFetchAt(tab.committedAt - 1))
    }

    @Test
    fun `an answer for the committed URL wins over the start of its navigation`() {
        val tab = TabDocuments()
        tab.navigationStarted("https://a.example/")
        tab.requested(frame)
        tab.mainFrameAnswered("https://a.example/")
        tab.requested(other)
        tab.committed("https://a.example/", null)
        assertEquals(setOf(other), tab.origins())
        // Both are used up: a later commit with neither prunes nothing.
        tab.committed("https://a.example/", null)
        assertEquals(setOf(other), tab.origins())
    }

    @Test
    fun `a commit with a fragment or other casing still finds its answer and source (#511 R2-F1)`() {
        val tab = TabDocuments()
        // Opened at …/#about, served by this device's node; the address
        // bar's start and Chromium's commit both carry the fragment, the
        // interceptor's request URL may not.
        tab.navigationStarted("HTTPS://BAFYA.ipfs.freedom.baby#about")
        tab.mainFrameAnswered("$ipfs/", ipfsGateway = null)
        tab.committed("$ipfs/#about", ipfs)
        assertEquals(DocumentSource(null), tab.committedSource)

        // The same through an external gateway keeps that gateway named.
        tab.navigationStarted("$other/#x")
        tab.mainFrameAnswered("$other#x", ipfsGateway = "https://gw.example")
        tab.committed("HTTPS://BAFYOTHER.ipfs.freedom.baby/#x", other)
        assertEquals(DocumentSource("https://gw.example"), tab.committedSource)
    }

    @Test
    fun `document keys match a started URL to its commit`() {
        assertEquals("http://localhost:8700/", documentKey("HTTP://LocalHost:8700"))
        assertEquals("https://a.example/p?q=%20", documentKey("https://a.example/p?q=%20#f"))
        assertEquals("about:blank", documentKey("about:blank"))
    }
}
