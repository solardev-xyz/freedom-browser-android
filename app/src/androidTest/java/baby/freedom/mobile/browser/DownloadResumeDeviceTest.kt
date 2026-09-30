package baby.freedom.mobile.browser

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Pause and resume (#265) end to end in [DownloadManager], against a
 * local server that serves byte ranges and one that doesn't.
 */
@RunWith(AndroidJUnit4::class)
class DownloadResumeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val manager = DownloadManager.get(context)
    private val rows = mutableListOf<Long>()
    private val files = mutableListOf<Uri>()

    @After
    fun cleanUp() {
        rows.forEach { manager.remove(it) }
        files.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    private val body = Random(265).nextBytes(512 * 1024)
    private val etag = "\"v1\""

    /**
     * Serves [content] with [etag]. [ranges]: honours `Range` guarded by
     * a matching `If-Range` with a 206; otherwise always the whole file.
     * The first answer is cut off half-way if [dropFirst], and throttled
     * if [slow], so a test can pause it part-way.
     */
    private inner class FileServer(
        private val content: () -> ByteArray,
        private val tag: () -> String?,
        private val ranges: Boolean,
        private val dropFirst: Boolean = false,
        private val slow: Boolean = false,
        /** A status to answer the nth (1-based) request with instead, if any. */
        private val failWith: (Int) -> Int? = { null },
        /** Answer a plain request with a 206 of the whole file. */
        private val wholeAs206: Boolean = false,
        /** Cap a range answer at this many bytes (a 206 that stops short). */
        private val rangeCap: Int? = null,
    ) : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val bytes = content()
            val first = requests.size == 1
            val range = request.getHeader("Range")
            val ifRange = request.getHeader("If-Range")
            val response = MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
            tag()?.let { response.setHeader("ETag", it) }
            if (ranges) response.setHeader("Accept-Ranges", "bytes")
            failWith(requests.size)?.let { return response.setResponseCode(it).setBody("nope") }
            if (ranges && range != null && (ifRange == null || ifRange == tag())) {
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                val until = rangeCap?.let { minOf(bytes.size, from + it) } ?: bytes.size
                response.setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-${until - 1}/${bytes.size}")
                    .setBody(Buffer().write(bytes, from, until - from))
            } else if (wholeAs206) {
                response.setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-${bytes.size - 1}/${bytes.size}")
                    .setBody(Buffer().write(bytes))
            } else {
                response.setResponseCode(200).setBody(Buffer().write(bytes))
            }
            if (first && dropFirst) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            if (first && slow) response.throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS)
            return response
        }
    }

    private fun startAndAccept(url: String, fileName: String) {
        manager.start(
            tabId = 265L,
            url = url,
            userAgent = null,
            contentDisposition = "attachment; filename=\"$fileName\"",
            mimeType = "application/octet-stream",
            contentLength = -1,
            pageUrl = null,
        )
        val offer = manager.offers.value.last { it.fileName == fileName }
        manager.accept(offer.key)
    }

    private fun await(fileName: String, what: (DownloadEntry) -> Boolean): DownloadEntry = runBlocking {
        withTimeout(30_000) {
            manager.downloads.first { list -> list.any { it.startsWith(fileName) && what(it) } }
                .first { it.startsWith(fileName) && what(it) }
        }.also { if (it.id !in rows) rows += it.id }
    }

    private fun DownloadEntry.startsWith(fileName: String) =
        this.fileName.startsWith(fileName.substringBeforeLast('.'))

    private fun partialOf(id: Long) = File(context.noBackupFilesDir, "downloads/$id.part")

    private fun savedBytes(entry: DownloadEntry): ByteArray {
        val uri = Uri.parse(entry.contentUri!!)
        files += uri
        return resolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    /** Waits until [id] has written some bytes, and returns. */
    private fun awaitProgress(id: Long) = runBlocking {
        withTimeout(30_000) { manager.progress.first { (it[id]?.received ?: 0) > 0 } }
    }

    @Test
    fun lostConnectionPausesAndResumeFetchesOnlyTheRest() {
        MockWebServer().use { server ->
            val files = FileServer({ body }, { etag }, ranges = true, dropFirst = true)
            server.dispatcher = files
            server.start()
            val name = "resume-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)

            val paused = await(name) { it.status == DownloadStatus.PAUSED }
            assertEquals(DOWNLOAD_CONNECTION_LOST_NOTE, paused.note)
            assertTrue(paused.resumable)
            assertEquals(etag, paused.validator)
            val kept = partialOf(paused.id).length()
            assertTrue("kept $kept of ${body.size}", kept > 0 && kept < body.size)
            assertEquals(kept, paused.receivedBytes)

            manager.resume(paused.id)
            val done = await(name) { it.status == DownloadStatus.COMPLETED }
            val resumed = files.requests.last()
            assertEquals("bytes=$kept-", resumed.getHeader("Range"))
            assertEquals(etag, resumed.getHeader("If-Range"))
            assertArrayEquals(body, savedBytes(done))
            assertNull(done.note)
            assertFalse(partialOf(done.id).exists())
        }
    }

    @Test
    fun serverIgnoringRangesRestartsCleanlyWithANote() {
        MockWebServer().use { server ->
            val files = FileServer({ body }, { etag }, ranges = false, slow = true)
            server.dispatcher = files
            server.start()
            val name = "norange-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)

            val running = await(name) { it.status == DownloadStatus.RUNNING && it.validator != null }
            // Web downloads can pause even when the server doesn't serve ranges.
            assertFalse(running.resumable)
            assertTrue(manager.canPause(running))
            awaitProgress(running.id)
            manager.pause(running.id)
            val paused = await(name) { it.status == DownloadStatus.PAUSED }
            assertTrue(paused.receivedBytes in 1 until body.size)

            manager.resume(paused.id)
            // The server answers the range with the whole file: written
            // from the start, and the row says so while it runs.
            val restarted = await(name) { it.status == DownloadStatus.RUNNING && it.note == DOWNLOAD_RESTARTED_NOTE }
            assertEquals(paused.id, restarted.id)
            val done = await(name) { it.status == DownloadStatus.COMPLETED }
            assertArrayEquals(body, savedBytes(done))
            assertEquals(2, files.requests.size)
        }
    }

    @Test
    fun aFileThatChangedRestartsInsteadOfSplicing() {
        MockWebServer().use { server ->
            var current = body
            var currentTag = etag
            val changed = Random(1).nextBytes(300 * 1024)
            val files = FileServer({ current }, { currentTag }, ranges = true, dropFirst = true)
            server.dispatcher = files
            server.start()
            val name = "changed-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)
            val paused = await(name) { it.status == DownloadStatus.PAUSED }

            current = changed
            currentTag = "\"v2\""
            manager.resume(paused.id)
            val done = await(name) { it.status == DownloadStatus.COMPLETED }
            // If-Range failed, so the server sent the new file whole.
            assertEquals(etag, files.requests.last().getHeader("If-Range"))
            assertArrayEquals(changed, savedBytes(done))
        }
    }

    @Test
    fun cancellingAPausedDownloadDeletesItsPartialFile() {
        MockWebServer().use { server ->
            server.dispatcher = FileServer({ body }, { etag }, ranges = true, dropFirst = true)
            server.start()
            val name = "discard-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)
            val paused = await(name) { it.status == DownloadStatus.PAUSED }
            assertTrue(partialOf(paused.id).exists())
            manager.cancel(paused.id)
            await(name) { it.status == DownloadStatus.CANCELLED }
            assertFalse(partialOf(paused.id).exists())
        }
    }

    @Test
    fun clearingSiteDataDeletesPartialFiles() {
        MockWebServer().use { server ->
            server.dispatcher = FileServer({ body }, { etag }, ranges = true, dropFirst = true)
            server.start()
            val name = "clear-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)
            val paused = await(name) { it.status == DownloadStatus.PAUSED }
            assertTrue(partialOf(paused.id).exists())
            manager.discardUnfinished()
            await(name) { it.status == DownloadStatus.CANCELLED }
            assertFalse(partialOf(paused.id).exists())
        }
    }

    @Test
    fun aResumeThatCantReachTheServerStaysPaused() {
        MockWebServer().use { server ->
            // Cut off half-way, then a 503 for the resume, then fine.
            val files = FileServer({ body }, { etag }, ranges = true, dropFirst = true, failWith = { if (it == 2) 503 else null })
            server.dispatcher = files
            server.start()
            val name = "unreachable-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)
            val paused = await(name) { it.status == DownloadStatus.PAUSED }
            val kept = partialOf(paused.id).length()

            manager.resume(paused.id)
            val still = await(name) { it.status == DownloadStatus.PAUSED && it.note == "Server error 503" }
            assertEquals(kept, partialOf(still.id).length())
            assertEquals(kept, still.receivedBytes)
            assertEquals("Paused: server error 503", downloadStatusLine(still, null, "t").substringBefore(" ·"))

            manager.resume(paused.id)
            val done = await(name) { it.status == DownloadStatus.COMPLETED }
            assertEquals("bytes=$kept-", files.requests.last().getHeader("Range"))
            assertArrayEquals(body, savedBytes(done))
        }
    }

    @Test
    fun aCancelRightAfterResumeCancels() {
        MockWebServer().use { server ->
            server.dispatcher = FileServer({ body }, { etag }, ranges = true, dropFirst = true)
            server.start()
            val name = "resumecancel-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)
            val paused = await(name) { it.status == DownloadStatus.PAUSED }

            manager.resume(paused.id)
            manager.cancel(paused.id)
            val over = await(name) { it.status != DownloadStatus.PAUSED && it.status != DownloadStatus.RUNNING }
            assertEquals(DownloadStatus.CANCELLED, over.status)
            assertNull(over.contentUri)
            assertFalse(partialOf(over.id).exists())
        }
    }

    @Test
    fun aServerThatAlwaysAnswers206WithTheWholeFileCompletes() {
        MockWebServer().use { server ->
            server.dispatcher = FileServer({ body }, { etag }, ranges = true, wholeAs206 = true)
            server.start()
            val name = "always206-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)
            val done = await(name) { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
            assertEquals(null, done.error)
            assertArrayEquals(body, savedBytes(done))
        }
    }

    @Test
    fun aRangeAnswerThatStopsShortFetchesTheWholeFileInstead() {
        MockWebServer().use { server ->
            val files = FileServer({ body }, { etag }, ranges = true, dropFirst = true, rangeCap = 64 * 1024)
            server.dispatcher = files
            server.start()
            val name = "capped-${System.nanoTime()}.bin"
            startAndAccept(server.url("/f.bin").toString(), name)
            val paused = await(name) { it.status == DownloadStatus.PAUSED }

            manager.resume(paused.id)
            val done = await(name) { it.status == DownloadStatus.COMPLETED }
            // The capped 206 was put aside, and the file asked for whole.
            assertNull(files.requests.last().getHeader("Range"))
            assertArrayEquals(body, savedBytes(done))
        }
    }
}
