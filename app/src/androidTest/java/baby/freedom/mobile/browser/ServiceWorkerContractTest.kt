package baby.freedom.mobile.browser

import android.webkit.WebStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Service-worker leg of the compatibility contract — feature-gated on
 * [ServiceWorkerInterception.isSupported] exactly like production (the
 * contract documents SWs as unsupported where the WebView can't route
 * their fetches through the interceptor).
 *
 * This is the least battle-tested corner of WebView interception, which
 * is why it runs on a device/emulator rather than being reasoned about.
 */
@RunWith(AndroidJUnit4::class)
class ServiceWorkerContractTest {

    private val gateway = FixtureGateway()
    private val harness = WebViewHarness()

    private val originA = VirtualOrigin.toVirtualUrl("bzz://${FixtureGateway.REF_A}")!!

    @Before
    fun setUp() {
        assumeTrue(
            "WebView lacks SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST",
            ServiceWorkerInterception.isSupported(),
        )
        ServiceWorkerInterception.install()
        gateway.start()
        harness.setUp()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WebStorage.getInstance().deleteAllData()
        }
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        // Best-effort SW cleanup so later tests see a clean slate.
        runCatching {
            harness.js(
                "navigator.serviceWorker.getRegistrations()" +
                    ".then(rs => Promise.all(rs.map(r => r.unregister())))" +
                    ".then(() => caches.keys())" +
                    ".then(ks => Promise.all(ks.map(k => caches.delete(k))))",
            )
            Thread.sleep(500)
        }
        harness.tearDown()
        gateway.shutdown()
    }

    @Test
    fun serviceWorkerRegistersCachesAndServesOffline() {
        // Register + wait for activation (sw.html reports swState).
        harness.load(originA + "sw.html")
        harness.awaitJsTrue(
            "window.results && window.results.swState === 'active'",
            timeoutSeconds = 60,
        )

        // Give the install-time cache.addAll a moment to finish, then
        // take the gateway away entirely (connection refused — the
        // node-stopped shape).
        Thread.sleep(1_000)
        gateway.shutdown()

        // The shell must now come out of the SW cache.
        harness.load(originA, timeoutSeconds = 60)
        harness.awaitJsTrue(
            "window.results && window.results.loaded === true",
            timeoutSeconds = 30,
        )
        harness.awaitJsTrue("window.results.relJs === true")
    }
}
