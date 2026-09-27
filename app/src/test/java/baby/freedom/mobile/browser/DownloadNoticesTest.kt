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
}
