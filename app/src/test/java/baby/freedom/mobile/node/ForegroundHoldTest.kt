package baby.freedom.mobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundHoldTest {
    /** Stands in for ForegroundServiceStartNotAllowedException, which the JVM's android.jar stub can't build. */
    private class Refusal(message: String) : IllegalStateException(message)

    private val hold = ForegroundHold(isRefusal = { it is Refusal })

    /** What `startForeground` throws when Android refuses it. */
    private val refusal = Refusal("Time limit already exhausted for foreground service type dataSync")
    private val refused: () -> Unit = { throw refusal }

    @Test
    fun `a refused promotion demotes instead of throwing, and says why`() {
        val got = hold.promote(refused)
        assertSame(refusal, got)
        assertEquals("Time limit already exhausted for foreground service type dataSync", got?.message)
        assertTrue(hold.demoted)
    }

    @Test
    fun `a setup bug from startForeground is rethrown, not demoted`() {
        // MissingForegroundServiceTypeException and friends, a missing
        // permission, a bad notification: crash loudly as before.
        assertThrows(IllegalStateException::class.java) {
            hold.promote { throw IllegalStateException("missing foreground service type") }
        }
        assertThrows(SecurityException::class.java) {
            hold.promote { throw SecurityException("no FOREGROUND_SERVICE_DATA_SYNC") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            hold.promote { throw IllegalArgumentException("invalid notification") }
        }
        assertFalse(hold.demoted)
    }

    @Test
    fun `a granted promotion clears the demotion`() {
        hold.promote(refused)
        assertNull(hold.promote {})
        assertFalse(hold.demoted)
    }

    @Test
    fun `a refused sticky restart with no app bound stops`() {
        hold.promote(refused)
        assertTrue(hold.started(stickyRestart = true))
    }

    @Test
    fun `a refused sticky restart the app is bound to keeps running demoted`() {
        hold.promote(refused)
        hold.bind() // onBind comes before onStartCommand on a restart
        assertFalse(hold.started(stickyRestart = true))
    }

    @Test
    fun `the app's own start is never stopped, even before it binds`() {
        hold.promote(refused)
        assertFalse(hold.started(stickyRestart = false))
    }

    @Test
    fun `a promoted sticky restart keeps running unbound`() {
        hold.promote {}
        assertFalse(hold.started(stickyRestart = true))
    }

    @Test
    fun `the budget running out stops an unbound node, keeps a bound one`() {
        assertTrue(ForegroundHold(isRefusal = { it is Refusal }).timedOut())
        hold.bind()
        assertFalse(hold.timedOut())
        assertTrue(hold.demoted)
        hold.unbind()
        assertTrue("the app unbinding from a demoted node stops it", hold.shouldStop())
        hold.bind()
        assertFalse("bound again (onRebind) within the grace, it stays", hold.shouldStop())
    }

    @Test
    fun `an unbound node in the foreground keeps running`() {
        hold.promote {}
        hold.bind()
        hold.unbind()
        assertFalse(hold.shouldStop())
    }
}
