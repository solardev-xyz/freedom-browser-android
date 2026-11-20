package baby.freedom.mobile.agent

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The attachment store (#601): desktop's caps (10 files and 50 MB a
 * message, 20 MB a file, 8 MB an image), what's taken as a text file,
 * image or PDF, and every way its files go: a chip removed, the process
 * gone before sending, a send that couldn't be saved, a chat deleted,
 * every chat deleted, and the sweep of folders whose chat is gone.
 */
class AgentAttachmentsTest {
    // Under the job's own temp dir when there is one: these files are tens of MB.
    @get:Rule
    val tmp = TemporaryFolder(System.getenv("TMPDIR")?.let(::File)?.takeIf { it.isDirectory })

    private val chat = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val other = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    /** Reads an image's type from a fake header: `PNG…` or `JPG…`. */
    private fun fakeImageType(file: File): String? = when (file.inputStream().use { String(it.readNBytes(3)) }) {
        "PNG" -> "image/png"
        "JPG" -> "image/jpeg"
        else -> null
    }

    private fun store(root: File = tmp.root) = AgentAttachments(root, ::fakeImageType)

    private fun bytes(n: Long, lead: String = ""): InputStream = object : InputStream() {
        var i = 0L
        val head = lead.toByteArray()
        override fun read(): Int = if (i >= n) -1 else (if (i < head.size) head[i.toInt()].toInt() else 'a'.code).also { i++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (i >= n) return -1
            val k = minOf(len.toLong(), n - i).toInt()
            for (j in 0 until k) b[off + j] = (if (i + j < head.size) head[(i + j).toInt()] else 'a'.code.toByte())
            i += k
            return k
        }
    }

    private fun text(s: String) = { ByteArrayInputStream(s.toByteArray()) as InputStream }

    private suspend fun AgentAttachments.stageOk(name: String, mime: String? = null, open: () -> InputStream): StagedAttachment {
        val r = stage(name, mime, open)
        if (r !is StageResult.Staged) fail("$name: $r")
        return (r as StageResult.Staged).attachment
    }

    private fun refusal(r: StageResult): StageRefusal = (r as? StageResult.Refused)?.reason ?: error("staged: $r")

    private fun stagedFiles() = File(tmp.root, "staged").listFiles().orEmpty().toList()

    private fun chatDir(id: String) = File(File(tmp.root, "chats"), id)

    @Test
    fun `kinds go by extension, then by the picker's type`() {
        assertEquals(AttachmentKind.Pdf to "application/pdf", AttachmentRules.classify("Invoice.PDF", null))
        assertEquals(AttachmentKind.Image to "image/webp", AttachmentRules.classify("a.webp", "application/octet-stream"))
        assertEquals(AttachmentKind.Text to "text/plain", AttachmentRules.classify("notes.md", null))
        assertEquals(AttachmentKind.Text to "text/plain", AttachmentRules.classify("main.kt", null))
        // No extension: the picker's type.
        assertEquals(AttachmentKind.Image to "image/jpeg", AttachmentRules.classify("IMG_2041", "image/jpeg"))
        assertEquals(AttachmentKind.Text to "text/plain", AttachmentRules.classify("README", "text/plain; charset=utf-8"))
        // A dot that isn't an extension Freedom knows: the picker's type.
        assertEquals(AttachmentKind.Pdf to "application/pdf", AttachmentRules.classify("Notes 20.11.2026", "application/pdf"))
        assertEquals(AttachmentKind.Text to "text/plain", AttachmentRules.classify("report.v2", "text/markdown"))
        assertNull(AttachmentRules.classify("setup.exe", "application/x-msdownload"))
        assertNull(AttachmentRules.classify("archive.zip", null))
        assertNull(AttachmentRules.classify("photo.heic", "image/heic"))
        assertNull(AttachmentRules.classify("noext", null))
    }

    @Test
    fun `a name loses what draws nothing or reorders it, and is cut`() {
        assertEquals("invoice_fdp.exe", AttachmentRules.cleanName("invoice_‮fdp.exe"))
        assertEquals("ab", AttachmentRules.cleanName("a\u0000\nb​"))
        assertEquals("Untitled", AttachmentRules.cleanName("‮"))
        assertEquals("Untitled", AttachmentRules.cleanName(null))
        assertEquals(AttachmentRules.MAX_NAME_CHARS, AttachmentRules.cleanName("x".repeat(500)).length)
        // Emoji joiners stay: they join, they don't hide or reorder.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 photo.png"
        assertEquals(family, AttachmentRules.cleanName(family))
    }

    @Test
    fun `a pick is copied in, counted by its bytes, and an image by what it really is`() = runBlocking {
        val s = store()
        val notes = s.stageOk("notes.txt") { ByteArrayInputStream("hello".toByteArray()) }
        assertEquals(5L, notes.bytes)
        assertEquals(AttachmentKind.Text, notes.kind)
        // Named .png, a JPEG inside: sent as what it is.
        val photo = s.stageOk("photo.png") { bytes(100, "JPG") }
        assertEquals("image/jpeg", photo.mimeType)
        assertEquals(listOf(notes, photo), s.staged.value)
        assertEquals(2, stagedFiles().size)
        assertEquals(StageRefusal.NotAnImage, refusal(s.stage("fake.png", null) { bytes(100, "GIF") }))
        assertEquals(StageRefusal.Unsupported, refusal(s.stage("tool.apk", null, text("x"))))
        assertEquals(StageRefusal.Unreadable, refusal(s.stage("gone.txt", null) { null }))
        assertEquals(StageRefusal.Unreadable, refusal(s.stage("broken.txt", null) { throw java.io.FileNotFoundException() }))
        // Nothing refused is left behind.
        assertEquals(2, stagedFiles().size)
    }

    @Test
    fun `caps - 10 files, 20 MB a file, 8 MB an image, 50 MB a message`() = runBlocking {
        val s = store()
        assertEquals(StageRefusal.TooLarge, refusal(s.stage("big.pdf", null) { bytes(AttachmentRules.MAX_FILE_BYTES + 1) }))
        assertEquals(StageRefusal.ImageTooLarge, refusal(s.stage("big.png", null) { bytes(AttachmentRules.MAX_IMAGE_BYTES + 1, "PNG") }))
        assertTrue(stagedFiles().isEmpty())
        s.stageOk("a.pdf") { bytes(AttachmentRules.MAX_FILE_BYTES) }
        s.stageOk("b.pdf") { bytes(AttachmentRules.MAX_FILE_BYTES) }
        val over = AttachmentRules.MAX_TOTAL_BYTES - 2 * AttachmentRules.MAX_FILE_BYTES + 1
        assertEquals(StageRefusal.TotalTooLarge, refusal(s.stage("c.pdf", null) { bytes(over) }))
        s.stageOk("c.pdf") { bytes(over - 1) }
        assertEquals(AttachmentRules.MAX_TOTAL_BYTES, s.staged.value.sumOf { it.bytes })
        s.clearStaged()
        assertTrue(stagedFiles().isEmpty())
        repeat(AttachmentRules.MAX_SELECTIONS) { s.stageOk("$it.txt", open = text("x")) }
        assertEquals(StageRefusal.TooMany, refusal(s.stage("11.txt", null, text("x"))))
        assertEquals(AttachmentRules.MAX_SELECTIONS, stagedFiles().size)
    }

    @Test
    fun `a chip removed, or the process gone before sending, leaves nothing staged`() = runBlocking {
        val s = store()
        val a = s.stageOk("a.txt", open = text("a"))
        s.stageOk("b.txt", open = text("b"))
        assertTrue(s.unstage(a.id))
        assertFalse(s.unstage(a.id))
        assertEquals(listOf("b.txt"), s.staged.value.map { it.name })
        assertEquals(1, stagedFiles().size)
        // A new process: the store starts empty, and the copies are gone.
        val again = store()
        assertTrue(again.staged.value.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `sending moves the files into the chat, and a failed save puts them back`() = runBlocking {
        val s = store()
        val a = s.stageOk("a.txt", open = text("alpha"))
        val b = s.stageOk("b.png") { bytes(10, "PNG") }
        assertNull(s.consume(chat, listOf(a.id, "selection_00000000000000000000")))
        assertEquals(2, s.staged.value.size)
        assertFalse(chatDir(chat).exists())

        val sent = s.consume(chat, listOf(a.id, b.id))!!
        assertTrue(s.staged.value.isEmpty())
        assertTrue(stagedFiles().isEmpty())
        assertEquals(listOf("a.txt", "b.png"), sent.map { it.name })
        assertTrue(sent.all { AttachmentRules.RESOURCE_ID.matches(it.id) })
        assertEquals(sent, s.list(chat))
        // Stored under its id, never the user's name.
        assertEquals(setOf("manifest.json") + sent.map { it.id }, chatDir(chat).list()!!.toSet())
        // A new process reads the same chat back.
        assertEquals(sent, store().list(chat))

        s.unconsume(chat, sent)
        assertEquals(listOf("a.txt", "b.png"), s.staged.value.map { it.name })
        assertFalse(chatDir(chat).exists())
        assertEquals(2, stagedFiles().size)
    }

    @Test
    fun `deleting a chat, every chat, and the sweep take the files`() = runBlocking {
        val s = store()
        val first = s.consume(chat, listOf(s.stageOk("a.txt", open = text("a")).id))!!
        s.consume(other, listOf(s.stageOk("b.txt", open = text("b")).id))
        val pending = s.stageOk("c.txt", open = text("c"))
        s.deleteChat(chat)
        assertFalse(chatDir(chat).exists())
        assertTrue(chatDir(other).exists())
        try {
            s.open(chat, first.single().id)
            fail("read a deleted chat's file")
        } catch (e: AttachmentException) {
            assertEquals(AttachmentException.Reason.NotFound, e.reason)
        }
        s.consume(chat, listOf(s.stageOk("d.txt", open = text("d")).id))
        // A folder changed since the chat list was read is a send under way: kept.
        assertEquals(0, s.sweep(setOf(chat), before = chatDir(other).lastModified()))
        assertTrue(chatDir(other).exists())
        assertEquals(1, s.sweep(setOf(chat), before = Long.MAX_VALUE))
        assertFalse(chatDir(other).exists())
        assertTrue(chatDir(chat).exists())
        s.deleteAll()
        assertFalse(File(tmp.root, "chats").exists())
        // The message being written keeps its file.
        assertEquals(listOf(pending), s.staged.value)
        // An id that isn't a chat's never names a folder.
        s.deleteChat("../staged")
        assertEquals(1, stagedFiles().size)
    }

    @Test
    fun `text is read in whole characters, with where the next read starts`() = runBlocking {
        val s = store()
        val body = "aé€😀" + "z".repeat(10)
        val id = s.consume(chat, listOf(s.stageOk("t.txt", open = text(body)).id))!!.single().id
        val total = body.toByteArray().size.toLong()
        val (_, whole) = s.readText(chat, id, 0, 4096)
        assertEquals(body, whole.text)
        assertEquals(total, whole.end)
        // "a" (1) + "é" (2) = 3; a 5-byte read stops before "€" (3 bytes).
        val (_, head) = s.readText(chat, id, 0, 5)
        assertEquals("aé", head.text)
        assertEquals(3L, head.end)
        val (_, next) = s.readText(chat, id, head.end, 6)
        assertEquals("€", next.text)
        assertEquals(6L, next.end)
        // An offset inside a character starts at the next one.
        val (_, inside) = s.readText(chat, id, 2, 8)
        assertEquals(3L, inside.start)
        assertTrue(inside.text.startsWith("€"))
        val (_, past) = s.readText(chat, id, 10_000, 10)
        assertEquals("", past.text)
        assertEquals(total, past.start)
    }

    @Test
    fun `an image is read as base64 for the model, and only an image`() = runBlocking {
        val s = store()
        val sent = s.consume(
            chat,
            listOf(s.stageOk("p.png") { bytes(6, "PNG") }.id, s.stageOk("t.txt", open = text("x")).id),
        )!!
        val (a, image) = s.readImage(chat, sent[0].id)
        assertEquals("p.png", a.name)
        assertEquals("image/png", image.mimeType)
        assertEquals(java.util.Base64.getEncoder().encodeToString("PNGaaa".toByteArray()), image.base64)
        try {
            s.readImage(chat, sent[1].id)
            fail("read text as an image")
        } catch (e: AttachmentException) {
            assertEquals(AttachmentException.Reason.WrongKind, e.reason)
        }
    }

    @Test
    fun `a manifest entry that isn't one the store writes is left out`() = runBlocking {
        chatDir(chat).mkdirs()
        File(chatDir(chat), "attachment_0123456789abcdef0123").writeText("x")
        File(chatDir(chat), "manifest.json").writeText(
            JSONObject().put(
                "resources",
                org.json.JSONArray()
                    .put(JSONObject().put("resourceId", "attachment_0123456789abcdef0123").put("name", "ok.txt").put("bytes", 1).put("mimeType", "text/plain").put("category", "text"))
                    .put(JSONObject().put("resourceId", "../../etc").put("name", "x").put("bytes", 1).put("mimeType", "text/plain").put("category", "text"))
                    .put(JSONObject().put("resourceId", "attachment_ffffffffffffffffffff").put("name", "x").put("bytes", 1).put("mimeType", "application/x-sh").put("category", "text")),
            ).toString(),
        )
        assertEquals(listOf("ok.txt"), store().list(chat).map { it.name })
        assertNotNull(Attachment.parseList(Attachment.listJson(store().list(chat))).singleOrNull())
        assertTrue(Attachment.parseList("{not json").isEmpty())
    }
}
