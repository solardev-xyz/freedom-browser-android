package baby.freedom.mobile.wallet

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.ref.WeakReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [WalletAccounts] is a process-lifetime singleton, usually first built from
 * `MainActivity.onCreate`. It must hold on to the application context only,
 * never the one it was handed — otherwise that first Activity (and its view
 * tree and WebViews) outlives every later relaunch (#208 R3-M1).
 */
@RunWith(AndroidJUnit4::class)
class WalletAccountsLeakTest {

    /** Stands in for an Activity: its own object, same application behind it. */
    private class ShortLivedContext(base: Context) : ContextWrapper(base)

    @Test
    fun instanceDoesNotRetainTheContextItWasBuiltFrom() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        var caller: Context? = ShortLivedContext(app)
        val ref = WeakReference(caller)
        val accounts = WalletAccounts.create(caller!!)
        caller = null

        repeat(20) {
            if (ref.get() == null) return@repeat
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(50)
        }

        assertNull("WalletAccounts keeps the context it was built from reachable", ref.get())
        // Keep the instance itself strongly reachable until after the check.
        assertNotNull(accounts)
    }
}
