package baby.freedom.mobile.agent

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDFs for the agent's attachments (#601) on the device: pdf.js in its
 * own WebView extracts a PDF's text, PdfRenderer renders a page, and a
 * file that isn't a PDF is refused rather than taking anything down.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentPdfDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val processor = AndroidPdfProcessor(context)

    private fun pdf(vararg pages: String): File {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 18f; color = Color.BLACK }
        pages.forEachIndexed { i, text ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(300, 400, i + 1).create())
            page.canvas.drawText(text, 20f, 60f, paint)
            doc.finishPage(page)
        }
        val file = File.createTempFile("attachment", ".pdf", context.cacheDir.apply { mkdirs() })
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    @Test
    fun extractsTextFromTheAskedPages() = runBlocking<Unit> {
        val file = pdf("Invoice number 4711", "Total due 99 EUR", "Thank you")
        val text = processor.extractText(file, page = 2, pageCount = 2)
        assertEquals(3, text.pageCount)
        assertEquals(listOf(2, 3), text.pages.map { it.first })
        assertTrue(text.pages[0].second, "Total due 99 EUR" in text.pages[0].second)
        assertTrue("Thank you" in text.pages[1].second)
        file.delete()
    }

    @Test
    fun pageOutOfRangeIsRefused() = runBlocking<Unit> {
        val file = pdf("one page")
        try {
            processor.extractText(file, page = 5, pageCount = 1)
            fail("read a page that isn't there")
        } catch (e: PdfException) {
            assertEquals(PdfFailure.PageOutOfRange, e.failure)
        }
        file.delete()
    }

    @Test
    fun notAPdfIsInvalid() = runBlocking<Unit> {
        val file = File.createTempFile("attachment", ".pdf", context.cacheDir.apply { mkdirs() }).apply { writeText("not a pdf at all") }
        try {
            processor.extractText(file, page = 1, pageCount = 1)
            fail("read text from a file that isn't a PDF")
        } catch (e: PdfException) {
            assertTrue(e.failure.toString(), e.failure == PdfFailure.Invalid || e.failure == PdfFailure.Failed)
        }
        try {
            processor.renderPage(file, 1)
            fail("rendered a file that isn't a PDF")
        } catch (e: PdfException) {
            assertEquals(PdfFailure.Invalid, e.failure)
        }
        file.delete()
    }

    @Test
    fun rendersAPageOnWhite() = runBlocking<Unit> {
        val file = pdf("Rendered", "Second")
        val page = processor.renderPage(file, 2)
        assertEquals(2, page.page)
        assertEquals(2, page.pageCount)
        // 300 x 400 points at the 2x cap.
        assertEquals(600, page.width)
        assertEquals(800, page.height)
        val bitmap = BitmapFactory.decodeByteArray(page.png, 0, page.png.size)
        assertEquals(Color.WHITE, bitmap.getPixel(2, 2))
        file.delete()
    }
}
