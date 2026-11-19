package baby.freedom.mobile.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `window.radicle` request's node calls run for the tab and document
 * that sent it (#533): the bridge's coroutine carries its requester, and
 * the provider's hop to the IO dispatcher keeps it.
 */
class RadicleRequesterContextTest {
    @Test
    fun `the requester follows the request onto the IO dispatcher and nowhere else`() {
        val requester = RadicleAdmission.Requester(tab = 7, generation = 3)
        val seen = runBlocking(RadicleClient.requesterContext(requester)) {
            withContext(Dispatchers.IO) { RadicleClient.currentRequester() }
        }
        assertEquals(requester, seen)
        val elsewhere = runBlocking { withContext(Dispatchers.IO) { RadicleClient.currentRequester() } }
        assertNull(elsewhere)
        assertNull(RadicleClient.currentRequester())
    }
}
