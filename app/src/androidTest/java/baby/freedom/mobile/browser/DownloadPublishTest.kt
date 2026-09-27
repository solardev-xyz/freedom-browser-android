package baby.freedom.mobile.browser

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pending items and publishing against the real MediaStore, and the
 * end of a real download's life in [DownloadManager].
 */
@RunWith(AndroidJUnit4::class)
class DownloadPublishTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val cleanup = mutableListOf<Uri>()
    private val manager = DownloadManager.get(context)
    private val rows = mutableListOf<Long>()

    @After
    fun cleanUp() {
        manager.afterPublishForTest = null
        cleanup.forEach { runCatching { resolver.delete(it, null, null) } }
        rows.forEach { manager.remove(it) }
    }

    private fun dataPath(uri: Uri): String =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }

    /**
     * Two same-name downloads inserted in the same second must not share
     * a file (MediaProvider's pending path is `.pending-<expiry s>-<name>`),
     * and both must end up as their own public file.
     */
    @Test
    fun sameNamePendingItemsGetTheirOwnFiles() {
        val name = "same-${System.nanoTime()}.txt"
        val a = insertPendingDownload(resolver, "Download/Freedom", "dl1", name, "text/plain")!!.also { cleanup += it }
        val b = insertPendingDownload(resolver, "Download/Freedom", "dl2", name, "text/plain")!!.also { cleanup += it }
        assertNotEquals(dataPath(a), dataPath(b))

        resolver.openOutputStream(a)!!.use { it.write(ByteArray(100_000) { 'A'.code.toByte() }) }
        resolver.openOutputStream(b)!!.use { it.write(ByteArray(100_000) { 'B'.code.toByte() }) }
        assertTrue(publishPendingDownload(resolver, a, name))
        assertTrue(publishPendingDownload(resolver, b, name))

        assertEquals(name, displayName(a))
        assertNotEquals(displayName(a), displayName(b))
        assertEquals(name.removeSuffix(".txt") + " (1).txt", displayName(b))
        assertEquals(setOf('A'.code.toByte()), resolver.openInputStream(a)!!.use { it.readBytes() }.toSet())
        assertEquals(setOf('B'.code.toByte()), resolver.openInputStream(b)!!.use { it.readBytes() }.toSet())
    }

    private fun startAndAccept(url: String, fileName: String) {
        manager.start(url, null, "attachment; filename=\"$fileName\"", "text/plain", -1, null)
        val offer = manager.offers.value.last { it.fileName == fileName }
        manager.accept(offer.key)
    }

    private fun awaitFinished(fileName: String): DownloadEntry = runBlocking {
        withTimeout(20_000) {
            manager.downloads.first { list ->
                list.any { it.fileName.startsWith(fileName.substringBeforeLast('.')) && it.status != DownloadStatus.RUNNING }
            }.first { it.fileName.startsWith(fileName.substringBeforeLast('.')) }
        }.also { rows += it.id }
    }

    /** A cancel that lands once the file is public neither deletes it nor marks the row cancelled. */
    @Test
    fun cancelAfterPublishKeepsTheFile() {
        val fileName = "late-cancel-${System.nanoTime()}.txt"
        manager.afterPublishForTest = { id -> manager.cancel(id) }
        startAndAccept("data:text/plain,hello", fileName)
        val entry = awaitFinished(fileName)
        assertEquals(DownloadStatus.COMPLETED, entry.status)
        assertNotNull(entry.contentUri)
        val uri = Uri.parse(entry.contentUri)
        cleanup += uri
        assertEquals(DownloadFileState.PRESENT, queryDownloadFileState(resolver, uri))
        assertEquals("hello", resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
    }

    /** A chunked body cut off mid-stream fails; it isn't published as complete. */
    @Test
    fun truncatedChunkedBodyFails() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setChunkedBody(Buffer().write(ByteArray(200_000) { 'x'.code.toByte() }), 4096)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            server.start()
            val fileName = "chunked-${System.nanoTime()}.txt"
            startAndAccept(server.url("/f").toString(), fileName)
            val entry = awaitFinished(fileName)
            assertEquals(DownloadStatus.FAILED, entry.status)
        }
    }
}
