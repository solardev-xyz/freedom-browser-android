package baby.freedom.mobile

import androidx.lifecycle.viewModelScope
import baby.freedom.mobile.browser.Incoming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The queue of links from other apps lives in a ViewModel (#268 R4-M2):
 * an Activity relaunched before a link has its tab finds it still there,
 * and still parsing if it was, instead of losing it with the old
 * Activity's scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingSessionTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /** Every session a test made, stopped and waited for before Main is reset (#455). */
    private val sessions = mutableListOf<IncomingSession>()

    private fun session() = IncomingSession().also { sessions += it }

    @After
    fun tearDown() {
        runBlocking { sessions.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    @Test
    fun queuedLinkWaitsInTheSessionUntilHandled() {
        val session = session()
        assertNull(session.submit(Incoming.Open("https://example.com/")))
        assertNull(session.submit(Incoming.Search("fox")))
        // No Activity involved: whichever one composes next reads the same queue.
        val pending = session.queue.pending.value
        assertEquals(listOf("https://example.com/", "fox"), pending.map { it.url })
        assertEquals(listOf(false, true), pending.map { it.search })
        session.queue.handled(pending[0])
        assertEquals(listOf("fox"), session.queue.pending.value.map { it.url })
    }

    @Test
    fun coldStartStillParsingIsHandedToTheNextActivity() = runBlocking {
        val session = session()
        // A Unicode ENS virtual-origin link needs the ENSIP-15 tables: off Main.
        val job = session.submit(Incoming.Open("https://xn---eth-9y14c.ens.freedom.baby/"))
        session.coldStart = job
        if (job != null) {
            assertSame(job, session.coldStart)
            job.join()
        }
        // Parsed and queued in its address-bar form; nothing left to wait for.
        assertNull(session.coldStart)
        assertEquals(listOf("🦊.eth"), session.queue.pending.value.map { it.url })
    }
}
