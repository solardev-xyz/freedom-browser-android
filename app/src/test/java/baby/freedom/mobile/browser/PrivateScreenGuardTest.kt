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
    ) = privateContentOnScreen(activePrivate, anyPrivate, switcherShown, downloadsShown)

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
}
