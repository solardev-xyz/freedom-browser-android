package baby.freedom.mobile.agent

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The attachment tools (#601) over a real store and a fake PDF
 * processor: what each returns to the model, when images go to it, and
 * desktop's refusals with their recovery.
 */
class AttachmentToolsTest {
    @get:Rule
    val tmp = TemporaryFolder(System.getenv("TMPDIR")?.let(::File)?.takeIf { it.isDirectory })

    private val chat = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private lateinit var store: AgentAttachments

    private class FakePdf : PdfProcessor {
        var failure: PdfFailure? = null
        val asked = ArrayList<Pair<Int, Int>>()

        override suspend fun extractText(file: File, page: Int, pageCount: Int): PdfText {
            failure?.let { throw PdfException(it, 3) }
            asked += page to pageCount
            val last = minOf(3, page + pageCount - 1)
            return PdfText(3, (page..last).map { it to if (it == 2) "" else "Text of page $it" }, truncated = false)
        }

        override suspend fun renderPage(file: File, page: Int): PdfPage {
            failure?.let { throw PdfException(it, 3) }
            if (page > 3) throw PdfException(PdfFailure.PageOutOfRange, 3)
            return PdfPage(page, 3, 600, 800, byteArrayOf(1, 2, 3))
        }
    }

    private val pdf = FakePdf()

    private fun registry() = ToolRegistry(AttachmentTools(chat, { store }, { pdf }).tools)

    private suspend fun attach(name: String, content: ByteArray): String {
        store = if (::store.isInitialized) store else AgentAttachments(tmp.root) { f ->
            if (f.readBytes().take(3) == "PNG".toByteArray().toList()) "image/png" else null
        }
        val staged = store.stage(name, null) { ByteArrayInputStream(content) as InputStream } as StageResult.Staged
        return store.consume(chat, listOf(staged.attachment.id))!!.single().id
    }

    private suspend fun ToolRegistry.call(name: String, args: JSONObject, images: Boolean = false) =
        run(ToolCall("c", name, args.toString()), images)

    @Test
    fun `list names the chat's files, never a path`() = runBlocking {
        val id = attach("report.pdf", "%PDF".toByteArray())
        val out = registry().call(AttachmentTools.LIST, JSONObject())
        assertFalse(out.isError)
        val resources = JSONObject(out.output).getJSONArray("resources")
        assertEquals(1, resources.length())
        val r = resources.getJSONObject(0)
        assertEquals(id, r.getString("resourceId"))
        assertEquals("report.pdf", r.getString("name"))
        assertEquals("pdf", r.getString("category"))
        assertFalse(out.output.contains(tmp.root.path))
        assertTrue(JSONObject(out.output).getString("untrusted").contains("untrusted data"))
    }

    @Test
    fun `text is read in bounded pieces, saying where the next starts`() = runBlocking {
        val body = "x".repeat(AttachmentRules.MAX_TEXT_READ_BYTES + 10)
        val id = attach("big.log", body.toByteArray())
        val first = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", id).put("limit", 10_000_000))
        assertTrue(first.output.contains("bytes 0–${AttachmentRules.MAX_TEXT_READ_BYTES} of ${body.length}"))
        assertTrue(first.output.contains("offset ${AttachmentRules.MAX_TEXT_READ_BYTES}"))
        // Under the registry's cut, so nothing it says was read goes missing.
        assertFalse(first.output.endsWith("[truncated]"))
        val rest = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", id).put("offset", AttachmentRules.MAX_TEXT_READ_BYTES))
        assertTrue(rest.output.endsWith("x".repeat(10)))
        assertFalse(rest.output.contains("More follows"))
    }

    @Test
    fun `an image goes to a model that takes images, and only to one`() = runBlocking {
        val id = attach("photo.png", "PNG-data".toByteArray())
        val blind = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", id))
        assertTrue(blind.isError)
        assertTrue(blind.output.startsWith("[CAPABILITY_UNAVAILABLE] The selected model cannot inspect image attachments"))
        assertTrue(blind.images.isEmpty())
        val seeing = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", id), images = true)
        assertFalse(seeing.isError)
        assertEquals("image/png", seeing.images.single().mimeType)
        assertTrue(seeing.output.startsWith("Attached image: photo.png"))
    }

    @Test
    fun `pdf text comes by page, at most four at a time, with what follows`() = runBlocking {
        val id = attach("doc.pdf", "%PDF".toByteArray())
        val out = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", id).put("page", 1).put("pageCount", 2))
        assertEquals(listOf(1 to 2), pdf.asked)
        assertTrue(out.output.contains("PDF: doc.pdf — page 1 of 3\nText of page 1"))
        assertTrue(out.output.contains("PDF: doc.pdf — page 2 of 3\n(no extractable text on this page)"))
        assertTrue(out.output.contains("call attachment_read with page 3"))
        val tooMany = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", id).put("pageCount", 5))
        assertTrue(tooMany.output, tooMany.output.startsWith("[INVALID_ARGUMENT] pageCount must be between 1 and 4"))
        assertEquals(1, pdf.asked.size)
    }

    @Test
    fun `a pdf that can't be read says why, in fixed words`() = runBlocking {
        val id = attach("doc.pdf", "%PDF".toByteArray())
        val cases = mapOf(
            PdfFailure.PasswordRequired to "[PDF_PASSWORD_REQUIRED] Password-protected PDFs are not supported",
            PdfFailure.PageLimit to "[PDF_PAGE_LIMIT] PDFs may contain at most 500 pages",
            PdfFailure.PageOutOfRange to "[PDF_PAGE_OUT_OF_RANGE] The PDF has 3 pages; page 1 is out of range",
            PdfFailure.Invalid to "[PDF_INVALID] The attached PDF is malformed or invalid",
            PdfFailure.TimedOut to "[PDF_PROCESSING_TIMEOUT] PDF processing timed out",
            PdfFailure.Failed to "[ATTACHMENT_UNAVAILABLE] The PDF could not be processed safely",
        )
        for ((failure, words) in cases) {
            pdf.failure = failure
            val out = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", id))
            assertTrue(out.isError)
            assertTrue(out.output, out.output.startsWith("$words\nRecovery: "))
        }
    }

    @Test
    fun `render_page is offered and run only for a model that takes images`() = runBlocking {
        val id = attach("doc.pdf", "%PDF".toByteArray())
        val r = registry()
        assertFalse(AttachmentTools.RENDER in r.specsFor(false).map { it.name })
        assertTrue(AttachmentTools.RENDER in r.specsFor(true).map { it.name })
        val args = JSONObject().put("resourceId", id).put("page", 2)
        assertEquals(ToolRegistry.UNKNOWN_TOOL, r.call(AttachmentTools.RENDER, args).output)
        val out = r.call(AttachmentTools.RENDER, args, images = true)
        assertTrue(out.output.startsWith("Rendered PDF: doc.pdf — page 2 of 3 (600×800)"))
        assertEquals(ImagePart("image/png", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), out.images.single())
        val past = r.call(AttachmentTools.RENDER, JSONObject().put("resourceId", id).put("page", 9), images = true)
        assertTrue(past.output.startsWith("[PDF_PAGE_OUT_OF_RANGE] The PDF has 3 pages; page 9 is out of range"))
        val text = attach("t.txt", "x".toByteArray())
        val wrong = r.call(AttachmentTools.RENDER, JSONObject().put("resourceId", text).put("page", 1), images = true)
        assertTrue(wrong.output.startsWith("[INVALID_ARGUMENT] Only PDF attachments can be rendered as PDF pages"))
    }

    @Test
    fun `an id from nowhere, or another chat's, isn't found`() = runBlocking {
        attach("a.txt", "a".toByteArray())
        for (bad in listOf("attachment_00000000000000000000", "../a.txt", "")) {
            val out = registry().call(AttachmentTools.READ, JSONObject().put("resourceId", bad))
            assertTrue(out.output, out.output.startsWith("[ATTACHMENT_NOT_FOUND]"))
            assertTrue(out.output.contains("\"tool\":\"attachment_list\""))
        }
        val elsewhere = AttachmentTools("7c9e6679-7425-40de-944b-e07fc1f90ae7", { store }, { pdf }).tools
        val id = store.list(chat).single().id
        val out = ToolRegistry(elsewhere).run(ToolCall("c", AttachmentTools.READ, JSONObject().put("resourceId", id).toString()))
        assertTrue(out.output.startsWith("[ATTACHMENT_NOT_FOUND]"))
    }

    @Test
    fun `the page's pdf text reply is checked again`() {
        val ok = PdfLimits.parseTextReply("""{"ok":true,"pageCount":3,"pages":[{"page":2,"text":"a"},{"page":3,"text":"b"}]}""", 2, 2)
        assertEquals(listOf(2 to "a", 3 to "b"), ok.pages)
        fun fails(json: String, page: Int = 1, count: Int = 1): PdfFailure = try {
            PdfLimits.parseTextReply(json, page, count)
            error("accepted $json")
        } catch (e: PdfException) {
            e.failure
        }
        assertEquals(PdfFailure.PasswordRequired, fails("""{"ok":false,"code":"PDF_PASSWORD_REQUIRED"}"""))
        assertEquals(PdfFailure.Failed, fails("""{"ok":false,"code":"anything else"}"""))
        assertEquals(PdfFailure.Failed, fails("not json"))
        // More pages than asked, out of order, beyond the document, past the text cap, wrong types.
        assertEquals(PdfFailure.Failed, fails("""{"ok":true,"pageCount":3,"pages":[{"page":1,"text":"a"},{"page":2,"text":"b"}]}"""))
        assertEquals(PdfFailure.Failed, fails("""{"ok":true,"pageCount":3,"pages":[{"page":3,"text":"a"}]}"""))
        assertEquals(PdfFailure.Failed, fails("""{"ok":true,"pageCount":1,"pages":[{"page":2,"text":"a"}]}""", page = 2))
        assertEquals(PdfFailure.Failed, fails("""{"ok":true,"pageCount":501,"pages":[]}"""))
        val huge = "a".repeat(PdfLimits.MAX_TEXT_CHARS + 1)
        assertEquals(PdfFailure.Failed, fails("""{"ok":true,"pageCount":1,"pages":[{"page":1,"text":"$huge"}]}"""))
        assertEquals(PdfFailure.Failed, fails("""{"ok":true,"pageCount":"1","pages":[]}"""))
        assertEquals(PdfFailure.Failed, fails("""{"ok":true,"pageCount":1,"pages":[{"page":1,"text":5}]}"""))
    }

    @Test
    fun `a render scale stays within desktop's bounds`() {
        // A letter page at twice its size.
        assertEquals(2.0, PdfLimits.renderScale(612.0, 792.0), 0.0)
        // A poster: held to 2048 a side and four million pixels.
        val s = PdfLimits.renderScale(5000.0, 5000.0)
        assertTrue(5000 * s <= PdfLimits.MAX_RENDER_DIMENSION)
        assertTrue(5000 * s * 5000 * s <= PdfLimits.MAX_RENDER_PIXELS)
    }
}
