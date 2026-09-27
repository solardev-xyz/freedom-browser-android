package baby.freedom.mobile.browser

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNoticesTest {

    @Test
    fun `cancelAll withdraws the showing and the queued download notices but not others`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        val other = launch(start = CoroutineStart.UNDISPATCHED) { host.showSnackbar("Unrelated") }
        val first = notices.show(this, 1) { host.showSnackbar("Download failed: a") }
        val second = notices.show(this, 2) { host.showSnackbar("Download failed: b") }
        yield()
        assertEquals("Unrelated", host.currentSnackbarData?.visuals?.message)

        notices.cancelAll()
        yield()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertFalse(other.isCancelled)
        assertEquals("Unrelated", host.currentSnackbarData?.visuals?.message)

        // The unrelated one goes; the withdrawn download notices never show.
        host.currentSnackbarData!!.dismiss()
        other.join()
        yield()
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `cancelAll dismisses a download notice that is on screen`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        notices.show(this, 1) { host.showSnackbar("Download failed: a") }
        val queued = notices.show(this, 2) { host.showSnackbar("Downloaded b") }
        yield()
        assertEquals("Download failed: a", host.currentSnackbarData?.visuals?.message)
        notices.cancelAll()
        yield()
        assertNull(host.currentSnackbarData)
        assertTrue(queued.isCancelled)
    }

    @Test
    fun `supersedeStart withdraws only that download's start notice`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        val a = notices.show(this, 1, start = true) { host.showSnackbar("Downloading a") }
        val b = notices.show(this, 2, start = true) { host.showSnackbar("Downloading b") }
        val end = notices.show(this, 1) { host.showSnackbar("Downloaded a") }
        yield()
        notices.supersedeStart(1)
        notices.supersedeStart(1) // a second call is a no-op
        assertTrue(a.isCancelled)
        assertFalse(b.isCancelled)
        assertFalse(end.isCancelled)
        notices.cancelAll()
    }

    @Test
    fun `a finished notice is forgotten`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        val done = notices.show(this, 1, start = true) { host.showSnackbar("Downloading a") }
        yield()
        host.currentSnackbarData!!.dismiss()
        done.join()
        val next = notices.show(this, 1, start = true) { host.showSnackbar("Downloading a again") }
        yield()
        notices.supersedeStart(1)
        assertTrue(next.isCancelled)
        assertFalse(done.isCancelled)
    }

    @Test
    fun `a second tab starting to drop leaves the first tab's notice alone`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        val shown = mutableListOf<Long>()
        val announce: suspend kotlinx.coroutines.CoroutineScope.(Long) -> Unit = { tabId ->
            shown += tabId
            host.showSnackbar("tab $tabId dropping")
        }
        notices.announceDrops(this, setOf(1L), activeTab = 9, block = announce)
        yield()
        assertEquals("tab 1 dropping", host.currentSnackbarData?.visuals?.message)

        notices.announceDrops(this, setOf(1L, 2L), activeTab = 9, block = announce)
        yield()
        // Tab 1's notice is still up, tab 2's queued behind it; each once.
        assertEquals("tab 1 dropping", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.dismiss()
        repeat(5) { yield() }
        assertEquals("tab 2 dropping", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.dismiss()
        repeat(5) { yield() }
        notices.announceDrops(this, setOf(1L, 2L), activeTab = 9, block = announce)
        yield()
        assertNull(host.currentSnackbarData)
        assertEquals(listOf(1L, 2L), shown)
    }

    @Test
    fun `the active tab isn't announced and an ended episode is announced afresh`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        val shown = mutableListOf<Long>()
        val announce: suspend kotlinx.coroutines.CoroutineScope.(Long) -> Unit = { tabId ->
            shown += tabId
            host.showSnackbar("tab $tabId dropping")
        }
        notices.announceDrops(this, setOf(1L), activeTab = 1, block = announce)
        yield()
        assertNull(host.currentSnackbarData)

        notices.announceDrops(this, setOf(2L), activeTab = 1, block = announce)
        yield()
        assertEquals("tab 2 dropping", host.currentSnackbarData?.visuals?.message)
        // Tab 2 stops dropping (navigated or closed): its notice goes.
        notices.announceDrops(this, emptySet(), activeTab = 1, block = announce)
        yield()
        assertNull(host.currentSnackbarData)
        notices.announceDrops(this, setOf(2L), activeTab = 1, block = announce)
        yield()
        assertEquals("tab 2 dropping", host.currentSnackbarData?.visuals?.message)
        notices.cancelAll()
        assertEquals(listOf(2L, 2L), shown)
    }

    @Test
    fun `cancelAll withdraws a drop notice, showing or queued`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        val other = launch(start = CoroutineStart.UNDISPATCHED) { host.showSnackbar("Unrelated") }
        notices.announceDrops(this, setOf(1L, 2L), activeTab = 9) { tabId ->
            host.showSnackbar("tab $tabId dropping")
        }
        yield()
        notices.cancelAll()
        yield()
        assertEquals("Unrelated", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.dismiss()
        other.join()
        yield()
        assertNull(host.currentSnackbarData)
        // Same episode: not announced again after the list closes.
        notices.announceDrops(this, setOf(1L, 2L), activeTab = 9) { host.showSnackbar("again") }
        yield()
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `cancelAll dismisses a drop notice that is on screen`() = runBlocking {
        val host = SnackbarHostState()
        val notices = DownloadNotices()
        notices.announceDrops(this, setOf(1L), activeTab = 9) { host.showSnackbar("tab 1 dropping") }
        yield()
        assertEquals("tab 1 dropping", host.currentSnackbarData?.visuals?.message)
        notices.cancelAll()
        yield()
        assertNull(host.currentSnackbarData)
    }
}
