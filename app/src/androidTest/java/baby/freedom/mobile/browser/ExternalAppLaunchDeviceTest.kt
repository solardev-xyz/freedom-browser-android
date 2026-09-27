package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [externalAppLaunch] on the device's real `Intent.parseUri` — the JVM
 * tests only see android.jar's stub, which can't throw what it throws.
 */
@RunWith(AndroidJUnit4::class)
class ExternalAppLaunchDeviceTest {

    private val own = "baby.freedom.mobile"

    @Test
    fun malformedIntentUrlsAreNoLinkNotACrash() {
        for (url in listOf(
            "intent://x#Intent;scheme=foo;i.n=zz;end",
            "intent://x#Intent;scheme=foo;l.n=zz;end",
            "intent://x#Intent;scheme=foo;f.n=zz;end",
            "intent://x#Intent;scheme=foo;d.n=zz;end",
            "intent://x#Intent;scheme=foo;launchFlags=zz;end",
            "intent://x#Intent;scheme=foo",
        )) {
            assertNull(url, externalAppLaunch(url, own))
        }
    }

    @Test
    fun wellFormedIntentUrlStillLaunches() {
        val launch = externalAppLaunch(
            "intent://scan/#Intent;scheme=zxing;package=com.example.scan;" +
                "i.n=3;S.browser_fallback_url=https%3A%2F%2Fexample.com%2F;end",
            own,
        )
        assertNotNull(launch)
        assertEquals("zxing", launch!!.intent.scheme)
        assertEquals(3, launch.intent.getIntExtra("n", 0))
        assertEquals("https://example.com/", launch.fallbackUrl)
    }
}
