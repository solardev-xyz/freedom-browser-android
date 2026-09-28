package baby.freedom.mobile.chains

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ChainlistService.download] on Android's own `HttpURLConnection`:
 * cancelling the caller mid-body must tear the connection down at once,
 * not leave the read running until its 30 s timeout. (The desktop JDK's
 * `disconnect()` waits for a blocked read, so only the device can show
 * this; the JVM test covers the caller being released.)
 */
@RunWith(AndroidJUnit4::class)
class ChainlistDownloadTest {
    @Test
    fun cancellingTheCallerDisconnectsTheStalledDownload() = runBlocking {
        StallingServer().use { server ->
            val job = launch(Dispatchers.Default) { ChainlistService.download(server.url) }
            assertTrue(server.responded.await(5, TimeUnit.SECONDS))
            delay(200)
            job.cancel()
            withTimeout(1_000) { job.join() }
            assertTrue(
                "the server saw no disconnect within 3 s of cancelling",
                server.closed.await(3, TimeUnit.SECONDS),
            )
        }
    }
}
