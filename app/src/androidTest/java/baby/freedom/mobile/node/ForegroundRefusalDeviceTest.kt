package baby.freedom.mobile.node

import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.app.MissingForegroundServiceTypeException
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [isForegroundRefusal] against the real framework exceptions, which the
 * JVM unit tests can't build: only Android's refusal demotes the node; the
 * foreground-service-type setup bugs, siblings under the same
 * `ServiceStartNotAllowedException` base, still crash (#458, R1-F2).
 */
@RunWith(AndroidJUnit4::class)
class ForegroundRefusalDeviceTest {
    @Test
    fun androidsRefusalDemotes() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val hold = ForegroundHold()
        val refusal = ForegroundServiceStartNotAllowedException("Time limit already exhausted")
        assertTrue(hold.promote { throw refusal } === refusal)
        assertTrue(hold.demoted)
    }

    @Test
    fun aForegroundServiceTypeBugIsRethrown() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val hold = ForegroundHold()
        assertFalse(isForegroundRefusal(MissingForegroundServiceTypeException("no type")))
        assertFalse(isForegroundRefusal(InvalidForegroundServiceTypeException("bad type")))
        assertThrows(MissingForegroundServiceTypeException::class.java) {
            hold.promote { throw MissingForegroundServiceTypeException("no type") }
        }
        assertFalse(hold.demoted)
    }

    @Test
    fun otherExceptionsAreRethrown() {
        val hold = ForegroundHold()
        assertFalse(isForegroundRefusal(SecurityException("no FOREGROUND_SERVICE_DATA_SYNC")))
        assertFalse(isForegroundRefusal(IllegalStateException("not a refusal")))
        assertThrows(SecurityException::class.java) {
            hold.promote { throw SecurityException("no FOREGROUND_SERVICE_DATA_SYNC") }
        }
        assertFalse(hold.demoted)
    }
}
