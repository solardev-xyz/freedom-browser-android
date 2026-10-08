package baby.freedom.mobile.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundHoldTest {
    private val hold = ForegroundHold()

    /** What `startForeground` throws when Android refuses it (ForegroundServiceStartNotAllowedException's base). */
    private val refused: () -> Unit = { throw IllegalStateException("startForeground not allowed") }

    @Test
    fun `a refused promotion demotes instead of throwing`() {
        assertFalse(hold.promote(refused))
        assertTrue(hold.demoted)
    }

    @Test
    fun `a security exception from startForeground demotes too`() {
        assertFalse(hold.promote { throw SecurityException("no FOREGROUND_SERVICE_DATA_SYNC") })
        assertTrue(hold.demoted)
    }

    @Test
    fun `a granted promotion clears the demotion`() {
        hold.promote(refused)
        assertTrue(hold.promote {})
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
        assertTrue(ForegroundHold().timedOut())
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
