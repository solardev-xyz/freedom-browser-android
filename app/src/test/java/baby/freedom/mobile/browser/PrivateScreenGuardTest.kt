package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateScreenGuardTest {
    private fun onScreen(
        activePrivate: Boolean = false,
        anyPrivate: Boolean = activePrivate,
        switcherShown: Boolean = false,
        downloadsShown: Boolean = false,
        switcherPrivatePane: Boolean = true,
    ) = privateContentOnScreen(activePrivate, anyPrivate, switcherShown, downloadsShown, switcherPrivatePane)

    @Test
    fun privateTabOnScreenIsSecure() {
        assertTrue(onScreen(activePrivate = true))
    }

    @Test
    fun normalTabWithoutPrivateSessionIsNot() {
        assertFalse(onScreen())
        assertFalse(onScreen(switcherShown = true))
        assertFalse(onScreen(downloadsShown = true))
    }

    @Test
    fun normalTabWhilePrivateSessionLivesIsNot() {
        assertFalse(onScreen(activePrivate = false, anyPrivate = true))
    }

    @Test
    fun switcherAndDownloadsListPrivateSessionContent() {
        assertTrue(onScreen(activePrivate = false, anyPrivate = true, switcherShown = true))
        assertTrue(onScreen(activePrivate = false, anyPrivate = true, downloadsShown = true))
    }

    @Test
    fun privateTabStaysSecureUnderPanels() {
        assertTrue(onScreen(activePrivate = true, switcherShown = true))
        assertTrue(onScreen(activePrivate = true, downloadsShown = true))
    }

    // The switcher's panes (#418).

    @Test
    fun switcherTabsPaneShowsNothingPrivate() {
        assertFalse(onScreen(activePrivate = false, anyPrivate = true, switcherShown = true, switcherPrivatePane = false))
        // Not even with a private tab active under it.
        assertFalse(onScreen(activePrivate = true, switcherShown = true, switcherPrivatePane = false))
    }

    @Test
    fun switcherPrivatePaneIsSecureOnlyWithPrivateTabs() {
        assertTrue(onScreen(activePrivate = false, anyPrivate = true, switcherShown = true, switcherPrivatePane = true))
        assertFalse(onScreen(activePrivate = false, anyPrivate = false, switcherShown = true, switcherPrivatePane = true))
    }

    @Test
    fun downloadsListOverTheSwitcherStillCounts() {
        assertTrue(
            onScreen(activePrivate = true, switcherShown = true, switcherPrivatePane = false, downloadsShown = true),
        )
    }
}
