package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A redirect cancelled to switch the user agent (#180) has its target
 * loaded by the browser in its place: that load is reported as a hop of
 * the navigation in flight (`redirectHop`), not as a fresh address the
 * user named — so x402 doesn't read it as the user's own navigation to
 * the target's site (#237 R2-F1).
 */
@RunWith(AndroidJUnit4::class)
class RedirectCorrectionLoadDeviceTest {
    private data class Load(val url: String?, val userNamed: Boolean, val usersStep: Boolean, val redirectHop: Boolean)

    @Test
    fun a_corrected_redirect_is_reported_as_a_hop_not_the_users_address() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loads = mutableListOf<Load>()
        lateinit var view: PageWebView
        val start = "http://127.0.0.1:9/start"
        val target = "http://127.0.0.1:9/desk"
        instrumentation.runOnMainSync {
            view = PageWebView(instrumentation.targetContext)
            view.wantsDesktop = { it?.endsWith("/desk") == true }
            view.onBrowserInitiatedLoad = { url, userNamed, usersStep, redirectHop ->
                loads += Load(url, userNamed, usersStep, redirectHop)
            }
            view.loadUrlNamedByUser(start)
            assertTrue(view.redirectCrossesUserAgent(target, named = true))
        }
        // The target's load is posted to the main thread.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            view.stopLoading()
            view.destroy()
        }
        assertEquals(
            listOf(
                Load(start, userNamed = true, usersStep = false, redirectHop = false),
                // Still the user's named chain for the app-link rules (#173), but a hop of it.
                Load(target, userNamed = true, usersStep = false, redirectHop = true),
            ),
            loads,
        )
    }

    @Test
    fun a_plain_load_is_not_a_hop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loads = mutableListOf<Load>()
        instrumentation.runOnMainSync {
            val view = PageWebView(instrumentation.targetContext)
            view.onBrowserInitiatedLoad = { url, userNamed, usersStep, redirectHop ->
                loads += Load(url, userNamed, usersStep, redirectHop)
            }
            view.loadUrl("http://127.0.0.1:9/desk")
            view.stopLoading()
            view.destroy()
        }
        assertEquals(listOf(Load("http://127.0.0.1:9/desk", userNamed = false, usersStep = false, redirectHop = false)), loads)
    }
}
