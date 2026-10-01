package baby.freedom.mobile.browser

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Copy image / Share image from a private tab (#86) leaves no picture of
 * the private page in the app's storage once the private session ends:
 * its file goes in `cache/shared/private/`, which
 * [discardPrivateImageShares] empties. A normal tab's stays where it was.
 */
@RunWith(AndroidJUnit4::class)
class PrivateImageSharesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val shared = File(context.cacheDir, "shared")
    private val image = FetchedImage(
        // A 1×1 GIF.
        byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x00, 0x3b,
        ),
        "image/gif",
    )

    @After
    fun tearDown() {
        shared.deleteRecursively()
    }

    /** Copy image, on the main thread as the page menu runs it (it may toast). */
    private fun copy(url: String, private: Boolean): Boolean {
        var ok = false
        instrumentation.runOnMainSync { ok = runBlocking { copyImageToClipboard(context, image, url, private) } }
        return ok
    }

    private fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun privateSharesGoWhenThePrivateSessionEnds() {
        shared.deleteRecursively()
        assertTrue(copy("https://a.example/secret.gif", private = true))
        assertTrue(copy("https://b.example/public.gif", private = false))
        val private = File(shared, "private")
        assertEquals(listOf("secret.gif"), filesUnder(private).map { it.name })

        discardPrivateImageShares(context)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (filesUnder(private).isNotEmpty() && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)

        assertEquals(emptyList<File>(), filesUnder(private))
        // The normal tab's copy isn't the private session's to take.
        assertEquals(listOf("public.gif"), filesUnder(shared).map { it.name })
    }
}
