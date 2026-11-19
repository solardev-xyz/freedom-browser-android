package baby.freedom.mobile.browser

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentJobsTest {
    @Test
    fun `a new document cancels its tab's other documents' jobs and nothing else`() {
        val jobs = DocumentJobs()
        val outgoing = Job()
        val stale = Job()
        val current = Job()
        val otherTab = Job()
        jobs.add(outgoing, tab = 1, document = 1)
        jobs.add(stale, tab = 1, document = -1)
        jobs.add(current, tab = 1, document = 2)
        jobs.add(otherTab, tab = 2, document = 1)
        jobs.cancelOutgoing(tab = 1, current = 2)
        assertTrue(outgoing.isCancelled)
        assertTrue(stale.isCancelled)
        assertFalse(current.isCancelled)
        assertFalse(otherTab.isCancelled)
        assertEquals(2, jobs.size())
    }

    @Test
    fun `a closed tab cancels all its jobs, and a finished job is forgotten`() {
        val jobs = DocumentJobs()
        val a = Job()
        val b = Job()
        val done = Job()
        jobs.add(a, tab = 3, document = 1)
        jobs.add(b, tab = 3, document = 2)
        jobs.add(done, tab = 4, document = 1)
        jobs.remove(done)
        jobs.cancelTab(3)
        assertTrue(a.isCancelled && b.isCancelled)
        assertFalse(done.isCancelled)
        assertEquals(0, jobs.size())
    }
}
