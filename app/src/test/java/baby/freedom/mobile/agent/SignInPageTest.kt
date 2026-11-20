package baby.freedom.mobile.agent

import baby.freedom.mobile.agent.SignInPage.Attempt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which browser a subscription's sign-in page (#576) is opened in, and in what order. */
class SignInPageTest {
    private val own = "baby.freedom.mobile"

    @Test
    fun `a custom tab in the default browser if it offers them, then the others as a plain link`() {
        assertEquals(
            listOf(Attempt("chrome", true), Attempt("firefox", false)),
            SignInPage.attempts(own, listOf(own, "firefox", "chrome"), "chrome", listOf("firefox", "chrome")),
        )
    }

    @Test
    fun `an app with a custom tabs service that doesn't open links is never picked`() {
        // A non-browser exporting the service, first in the list and not the default.
        assertEquals(
            listOf(Attempt("firefox", true), Attempt("chrome", false)),
            SignInPage.attempts(own, listOf("chrome", "firefox"), "chrome", listOf("some.helper", "firefox")),
        )
    }

    @Test
    fun `with no custom tabs browser, a plain link in the default browser first`() {
        assertEquals(
            listOf(Attempt("chrome", false), Attempt("firefox", false)),
            SignInPage.attempts(own, listOf("firefox", "chrome"), "chrome", listOf("some.helper")),
        )
    }

    @Test
    fun `never freedom itself, even as the default`() {
        assertEquals(
            listOf(Attempt("firefox", false)),
            SignInPage.attempts(own, listOf(own, "firefox"), own, listOf(own)),
        )
        assertEquals(emptyList<Attempt>(), SignInPage.attempts(own, listOf(own), own, listOf(own)))
    }
}
