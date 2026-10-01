package baby.freedom.mobile.browser

import android.content.ContentValues
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * *Ask where to save each file* (#322) end to end in [DownloadManager]:
 * a download handed the document a *Save as* picker returned. The
 * picker itself can't run in a test, so the "picked" document is an
 * empty item this test creates — what the picker gives a download too:
 * a writable content URI of an empty file with the name the user chose.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSaveToDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val manager = DownloadManager.get(context)
    private val rows = mutableListOf<Long>()
    private val files = mutableListOf<Uri>()
    private val body = Random(322).nextBytes(256 * 1024)

    @After
    fun cleanUp() {
        manager.afterFetchForTest = null
        manager.allocatableForTest = null
        manager.pickedRoomForTest = null
        rows.forEach { manager.remove(it) }
        files.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    /** An item named [name] under Download/, as the picker creates one: empty unless [content]. */
    private fun picked(name: String, content: ByteArray? = null): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FreedomTest")
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        files += uri
        // Written (if only with nothing) so the file exists and knows its size, as a picked one does.
        resolver.openOutputStream(uri)!!.use { out -> content?.let { out.write(it) } }
        return uri
    }

    private fun exists(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true

    private fun bytesOf(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }

    /** Offer [url] as the server names it, and accept it into [saveTo]. */
    private fun startAndAccept(url: String, saveTo: Uri, onNoLastingAccess: () -> Unit = {}): DownloadOffer {
        val serverName = "server-${System.nanoTime()}.bin"
        manager.start(
            tabId = 322L,
            url = url,
            userAgent = null,
            contentDisposition = "attachment; filename=\"$serverName\"",
            mimeType = "application/octet-stream",
            contentLength = -1,
            pageUrl = null,
        )
        val offer = manager.offers.value.last { it.fileName == serverName }
        assertEquals("application/octet-stream", offer.mimeType)
        manager.accept(offer.key, saveTo, onNoLastingAccess)
        return offer
    }

    private fun await(saveTo: Uri, what: (DownloadEntry) -> Boolean): DownloadEntry = runBlocking {
        withTimeout(30_000) {
            manager.downloads.first { list -> list.any { it.saveTo == saveTo.toString() && what(it) } }
                .first { it.saveTo == saveTo.toString() && what(it) }
        }.also { if (it.id !in rows) rows += it.id }
    }

    private fun server(vararg responses: MockResponse) = MockWebServer().apply {
        responses.forEach { enqueue(it) }
        start()
    }

    private fun file(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/octet-stream")
        .setHeader("ETag", "\"v1\"")
        .setHeader("Accept-Ranges", "bytes")
        .setBody(Buffer().write(body))

    @Test
    fun theFileIsWrittenThroughThePickedDocument() {
        server(file()).use { server ->
            val name = "picked-${System.nanoTime()}.bin"
            val saveTo = picked(name)
            startAndAccept(server.url("/f").toString(), saveTo)
            val done = await(saveTo) { it.status == DownloadStatus.COMPLETED }
            // Listed under the name given in the picker, not the server's,
            // and opened from the picked document.
            assertEquals(name, done.fileName)
            assertEquals(saveTo.toString(), done.contentUri)
            assertArrayEquals(body, bytesOf(saveTo))
        }
    }

    @Test
    fun aPausedDownloadResumesIntoThePickedDocument() {
        server(file()).use { server ->
            val saveTo = picked("paused-${System.nanoTime()}.bin")
            // Pause it once every byte is in, before the copy: the picked
            // document must stay put (and empty) across the pause.
            var paused = false
            manager.afterFetchForTest = { id ->
                if (!paused) {
                    paused = true
                    manager.pause(id)
                    Thread.sleep(300)
                }
            }
            startAndAccept(server.url("/f").toString(), saveTo)
            val held = await(saveTo) { it.status == DownloadStatus.PAUSED }
            assertEquals(saveTo.toString(), held.saveTo)
            assertTrue(exists(saveTo))
            assertEquals(0, bytesOf(saveTo).size)
            manager.afterFetchForTest = null
            manager.resume(held.id)
            await(saveTo) { it.status == DownloadStatus.COMPLETED }
            assertArrayEquals(body, bytesOf(saveTo))
        }
    }

    @Test
    fun aFailedDownloadDeletesTheEmptyPickedDocument() {
        server(MockResponse().setResponseCode(404).setBody("gone")).use { server ->
            val saveTo = picked("failed-${System.nanoTime()}.bin")
            startAndAccept(server.url("/f").toString(), saveTo)
            await(saveTo) { it.status == DownloadStatus.FAILED }
            assertFalse(exists(saveTo))
        }
    }

    @Test
    fun aFailedDownloadLeavesAReplacedFileAlone() {
        // The user picked an existing file to replace: a download that
        // never finished never touched it, so it stays as it was.
        val old = "old content".toByteArray()
        server(MockResponse().setResponseCode(404).setBody("gone")).use { server ->
            val saveTo = picked("existing-${System.nanoTime()}.bin", old)
            startAndAccept(server.url("/f").toString(), saveTo)
            await(saveTo) { it.status == DownloadStatus.FAILED }
            assertTrue(exists(saveTo))
            assertArrayEquals(old, bytesOf(saveTo))
        }
    }

    @Test
    fun aReplacedFileIsMarkedAsTheUsersAndNeverDeleted() {
        // R1-M1: the row knows which document the picker created (and may
        // go again) and which the user picked to replace (which never goes).
        val old = "old content".toByteArray()
        server(file(), file()).use { server ->
            val fresh = picked("fresh-${System.nanoTime()}.bin")
            val existing = picked("replaced-${System.nanoTime()}.bin", old)
            startAndAccept(server.url("/f").toString(), fresh)
            startAndAccept(server.url("/f").toString(), existing)
            assertTrue(await(fresh) { it.status == DownloadStatus.COMPLETED }.saveToCreated)
            assertFalse(await(existing) { it.status == DownloadStatus.COMPLETED }.saveToCreated)
            assertArrayEquals(body, bytesOf(existing))
        }
        // A replaced file emptied by a copy that failed part-way stays.
        val emptied = picked("emptied-${System.nanoTime()}.bin")
        DownloadSaveTo.discard(context, emptied, created = false)
        assertTrue(exists(emptied))
        // (MediaStore may take a moment to know a new item's size, and a
        // document of unknown size is never deleted.)
        val deadline = System.currentTimeMillis() + 10_000
        while (exists(emptied) && System.currentTimeMillis() < deadline) {
            DownloadSaveTo.discard(context, emptied, created = true)
            if (exists(emptied)) Thread.sleep(200)
        }
        assertFalse(exists(emptied))
    }

    @Test
    fun thePickedDocumentsOwnVolumeDecidesWhetherItFits() {
        // R1-F1: the primary volume being full doesn't hold back a pick
        // with room (an SD card) ...
        manager.allocatableForTest = { dir -> if (dir == manager.sharedStorageDirForTest()) 0L else Long.MAX_VALUE / 2 }
        manager.pickedRoomForTest = { Long.MAX_VALUE / 2 }
        server(file()).use { server ->
            val saveTo = picked("roomy-${System.nanoTime()}.bin")
            startAndAccept(server.url("/f").toString(), saveTo)
            await(saveTo) { it.status == DownloadStatus.COMPLETED }
            assertArrayEquals(body, bytesOf(saveTo))
        }
        // ... and a full pick pauses — before `wt` has touched the file
        // it would replace.
        manager.allocatableForTest = { Long.MAX_VALUE / 2 }
        manager.pickedRoomForTest = { 0L }
        val old = "old content".toByteArray()
        server(file()).use { server ->
            val saveTo = picked("full-${System.nanoTime()}.bin", old)
            startAndAccept(server.url("/f").toString(), saveTo)
            await(saveTo) { it.status == DownloadStatus.PAUSED }
            assertArrayEquals(old, bytesOf(saveTo))
        }
    }

    @Test
    fun theRoomOfAPickedDocumentIsItsVolumes() {
        val saveTo = picked("room-${System.nanoTime()}.bin", ByteArray(1000))
        val room = DownloadSaveTo.roomFor(resolver, saveTo)!!
        // At least what the file holds, and what the volume has free.
        assertTrue(room >= 1000)
        assertTrue(room >= android.os.Environment.getExternalStorageDirectory().usableSpace / 2)
    }

    @Test
    fun aPlainWriteIsTruncatedByHand() {
        // R1-M3: the `w` fallback must not leave the old file's tail.
        val file = java.io.File(context.cacheDir, "trunc-${System.nanoTime()}.bin")
        file.writeBytes(ByteArray(5000) { 7 })
        val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_WRITE_ONLY)
        DownloadSaveTo.truncating(fd) { error("a file is truncated, its size isn't asked") }.use { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        file.delete()
        // A pipe can't be emptied: fine into an empty document, refused
        // into one with something in it (or of unknown size).
        val (read, write) = android.os.ParcelFileDescriptor.createPipe()
        DownloadSaveTo.truncating(write) { 0L }.close()
        read.close()
        for (size in listOf(5L, null)) {
            val (r, w) = android.os.ParcelFileDescriptor.createPipe()
            try {
                DownloadSaveTo.truncating(w) { size }
                org.junit.Assert.fail("a pipe into a document holding $size bytes was accepted")
            } catch (_: java.io.FileNotFoundException) {
            }
            r.close()
        }
    }

    @Test
    fun noLastingGrantStartsNothingAndLeavesTheOfferUp() {
        // R1-M4: a document Freedom can't keep access to (no persistable
        // grant) isn't downloaded into: the user is told, and the prompt
        // stays up to pick elsewhere.
        val told = java.util.concurrent.CountDownLatch(1)
        val ungranted = android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download/ungranted-${System.nanoTime()}.bin",
        )
        val offer = startAndAccept("https://example.invalid/f", ungranted) { told.countDown() }
        assertTrue(told.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(manager.offers.value.any { it.key == offer.key })
        manager.decline(offer.key)
    }
}
