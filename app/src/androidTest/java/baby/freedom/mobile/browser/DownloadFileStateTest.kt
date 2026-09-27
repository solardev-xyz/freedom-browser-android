package baby.freedom.mobile.browser

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [queryDownloadFileState] against the real MediaStore: a trashed item
 * must read as TRASHED (restorable) — neither GONE, which would make
 * open() drop a restorable download's URI, nor PRESENT, which would launch
 * a viewer on a file in the trash. (Whether a default query hides trashed
 * rows depends on OS version and query shape; on API 36 a single-item URI
 * query returns them regardless, so these cases don't rely on that.)
 */
@RunWith(AndroidJUnit4::class)
class DownloadFileStateTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private var uri: Uri? = null

    @Before
    fun insert() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "state-test-${System.nanoTime()}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri!!)!!.use { it.write("hello".toByteArray()) }
    }

    @After
    fun cleanUp() {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
    }

    private fun setTrashed(trashed: Boolean) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
        assertEquals(1, resolver.update(uri!!, values, null, null))
    }

    @Test
    fun presentItemIsPresent() {
        assertEquals(DownloadFileState.PRESENT, queryDownloadFileState(resolver, uri!!))
    }

    @Test
    fun trashedItemIsTrashedNotGone() {
        setTrashed(true)
        assertEquals(DownloadFileState.TRASHED, queryDownloadFileState(resolver, uri!!))
        setTrashed(false)
        assertEquals(DownloadFileState.PRESENT, queryDownloadFileState(resolver, uri!!))
    }

    @Test
    fun deletedItemIsGone() {
        assertEquals(1, resolver.delete(uri!!, null, null))
        assertEquals(DownloadFileState.GONE, queryDownloadFileState(resolver, uri!!))
        uri = null
    }
}
