package baby.freedom.mobile.agent

import baby.freedom.mobile.data.DownloadEntry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The app's side of the upload and download tools, faked (#600). */
internal class FakeFiles(dir: File) : AgentFilesPort {
    var askWhere = false
    val rows = HashMap<Long, DownloadEntry>()
    var picked = listOf(PickedFile("photo.jpg", 1234, "image/jpeg"))

    fun entry(id: Long, name: String, status: String) = DownloadEntry(
        id = id, fileName = name, displayUrl = "example.com/files/$name", sourceUrl = "https://example.com/files/$name",
        mimeType = "application/pdf", contentUri = "content://media/external/downloads/$id", status = status,
        totalBytes = 2048, receivedBytes = 2048, error = null, startedAt = 1, finishedAt = 2,
    )

    override suspend fun askWhereToSave() = askWhere
    /** Called as [row] starts; then [row] waits for [rowGate] while it's set. */
    var onRow: () -> Unit = {}
    var rowGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun row(rowId: Long): DownloadEntry? {
        onRow()
        rowGate?.await()
        return rows[rowId]
    }
    /** Held open while set: the download is still running. */
    var running: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val cancelled = ArrayList<Long>()

    override suspend fun settled(rowId: Long): DownloadEntry? {
        running?.await()
        return rows[rowId]
    }

    override fun cancel(rowId: Long) {
        cancelled += rowId
    }

    override fun present(row: DownloadEntry) = row.contentUri != null
    override val records = AgentDownloadRecords(File(dir, "downloads.json"))
    override fun describe(picked: UploadIntent.Result.Picked) = this.picked
}

/**
 * Which download an approved `browser_download` covers (#600), and the
 * records `browser_list_downloads` reads.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentFilesTest {
    private val dir = Files.createTempDirectory("agent-files").toFile()

    @Before
    fun setUp() {
        AgentDownloads.clear()
        AgentDownloads.holderOf = { if (it == 1L) "chat" else null }
    }

    @After
    fun tearDown() {
        AgentDownloads.clear()
        AgentDownloads.holderOf = { AgentTabs.holder(it) }
        AgentDownloads.openerOf = { AgentTabs.opener(it) }
        dir.deleteRecursively()
    }

    @Test
    fun `the approved link matches, fragment aside`() {
        val link = "https://example.com/files/report.pdf"
        assertTrue(DownloadIntentMatch.matches(link, link))
        assertTrue(DownloadIntentMatch.matches("$link#page=2", link))
        assertTrue(DownloadIntentMatch.matches(link, " $link "))
    }

    @Test
    fun `nothing else on the link's origin matches, not even a same-origin redirect`() {
        val link = "https://example.com/download?id=7"
        // A same-origin redirect: WebView reports only where it ended, which proves nothing about the chain.
        assertFalse(DownloadIntentMatch.matches(link, "https://example.com/files/report-7.pdf"))
        assertFalse(DownloadIntentMatch.matches(link, "https://example.com/download?id=8"))
        assertFalse(DownloadIntentMatch.matches(link, "https://example.com/download?id=7&x=1"))
        assertFalse(DownloadIntentMatch.matches("https://example.com/a", "https://example.com/a/"))
        // A shared host: another tenant's file on the same origin is never the approved one.
        assertFalse(
            DownloadIntentMatch.matches(
                "https://github.com/good/project/releases/download/v1/app.zip",
                "https://github.com/evil/x/raw/main/payload.apk",
            ),
        )
        assertFalse(DownloadIntentMatch.matches("http://127.0.0.1:1633/bzz/aaaa/app.zip", "http://127.0.0.1:1633/bzz/bbbb/app.zip"))
        assertFalse(DownloadIntentMatch.matches("bzz://site.eth/a.zip", "bzz://site.eth/b.zip"))
    }

    @Test
    fun `a download from anywhere else never matches`() {
        val link = "https://example.com/download?id=7"
        assertFalse(DownloadIntentMatch.matches(link, "https://cdn.example.net/report.pdf"))
        assertFalse(DownloadIntentMatch.matches(link, "https://sub.example.com/report.pdf"))
        assertFalse(DownloadIntentMatch.matches(link, "http://example.com/download?id=7"))
        assertFalse(DownloadIntentMatch.matches(link, "https://example.com:8443/download?id=7"))
        assertFalse(DownloadIntentMatch.matches(link, "data:text/plain,hi"))
        assertFalse(DownloadIntentMatch.matches(link, ""))
        assertFalse(DownloadIntentMatch.matches("", link))
        assertFalse(DownloadIntentMatch.matches("https://user@example.com/a", "https://user@example.com/b"))
    }

    @Test
    fun `blob and data links must match exactly`() {
        val blob = "blob:https://example.com/0f1e2d3c"
        assertTrue(DownloadIntentMatch.matches(blob, blob))
        assertFalse(DownloadIntentMatch.matches(blob, "blob:https://example.com/99999999"))
        val data = "data:text/csv;base64,YSxiCjEsMg=="
        assertTrue(DownloadIntentMatch.matches(data, data))
        assertFalse(DownloadIntentMatch.matches(data, "data:text/csv;base64,eCx5"))
        // A link to a page's origin covers no blob it makes.
        assertFalse(DownloadIntentMatch.matches("https://example.com/a", blob))
    }

    @Test
    fun `origins are compared without a default port and in lower case`() {
        assertEquals("https://example.com", DownloadIntentMatch.originOf("https://Example.COM:443/a"))
        assertEquals("http://example.com:8080", DownloadIntentMatch.originOf("http://example.com:8080/"))
        assertEquals("ipfs://bafyabc", DownloadIntentMatch.originOf("ipfs://bafyabc/x"))
        assertNull(DownloadIntentMatch.originOf("blob:https://example.com/1"))
        assertNull(DownloadIntentMatch.originOf("file:///sdcard/x"))
        assertNull(DownloadIntentMatch.originOf("not a url"))
    }

    @Test
    fun `a pick Freedom refuses is not the user's cancel, and an app-side close is neither`() {
        val resolver = object : android.content.ContentResolver(null) {}
        val page = android.webkit.ValueCallback<Array<android.net.Uri>> {}
        fun answer(mark: (UploadIntent.Callback) -> Unit): UploadIntent.Result {
            val intent = UploadIntent("chat", 1)
            val callback = intent.Callback(page, resolver)
            mark(callback)
            callback.onReceiveValue(null)
            return intent.result.getCompleted()
        }
        assertEquals(UploadIntent.Result.Cancelled, answer {})
        // What FileChooser does when the picker said OK but every URI was filtered out.
        assertEquals(UploadIntent.Result.Refused, answer { AgentUploads.refusing(it) })
        assertEquals(UploadIntent.Result.Withdrawn, answer { AgentUploads.withdrawing(it) })
        // A page's own callback (no agent upload armed) is left alone.
        AgentUploads.refusing(page)
    }

    private fun intent(url: String = "https://example.com/a.zip", tab: Long = 1, direct: Boolean = true) =
        DownloadIntent("chat", tab, url, direct)

    @Test
    fun `an armed intent covers the first matching download, once`() {
        val i = intent()
        assertTrue(AgentDownloads.arm(i))
        val claim = AgentDownloads.claim(1, "https://example.com/a.zip")
        assertNotNull(claim)
        assertTrue(i.claimed.isCompleted)
        claim!!.started(5)
        assertEquals(DownloadIntent.Start.Started(5, "https://example.com/a.zip"), i.start.getCompleted())
        // Used up: the next download from the tab is the page's, held for the user.
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
        assertTrue(AgentDownloads.takeHeld(1))
    }

    @Test
    fun `a download that doesn't match ends the intent and waits for the user`() {
        val i = intent()
        AgentDownloads.arm(i)
        assertNull(AgentDownloads.claim(1, "https://evil.example/a.zip"))
        assertEquals(DownloadIntent.Start.Unapproved, i.start.getCompleted())
        assertFalse(i.claimed.isCompleted)
        // Nor does the approved one, coming after, slip through.
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
    }

    @Test
    fun `a download from a window the tab's page opened ends the tab's intent and is noted on both`() {
        AgentDownloads.holderOf = { if (it == 1L || it == 2L) "chat" else null }
        AgentDownloads.openerOf = { if (it == 2L) 1L else null }
        val i = intent()
        assertTrue(AgentDownloads.arm(i))
        // Even the approved address, from the popup, is the user's to answer.
        assertNull(AgentDownloads.claim(2, "https://example.com/a.zip"))
        assertEquals(DownloadIntent.Start.InPopup, i.start.getCompleted())
        assertFalse(i.claimed.isCompleted)
        assertTrue(AgentDownloads.takeHeld(1))
        assertTrue(AgentDownloads.takeHeld(2))
        // Used up: nothing armed on the opener any more.
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
    }

    @Test
    fun `a popup another chat holds doesn't end this chat's intent`() {
        AgentDownloads.holderOf = { when (it) { 1L -> "chat"; 2L -> "other"; else -> null } }
        AgentDownloads.openerOf = { if (it == 2L) 1L else null }
        val i = intent()
        assertTrue(AgentDownloads.arm(i))
        assertNull(AgentDownloads.claim(2, "https://example.com/a.zip"))
        assertFalse(i.start.isCompleted)
        assertFalse(AgentDownloads.takeHeld(1))
    }

    @Test
    fun `one intent per tab, and only on that tab`() {
        val i = intent()
        assertTrue(AgentDownloads.arm(i))
        assertFalse(AgentDownloads.arm(intent()))
        // Another tab's download is no business of this intent.
        assertNull(AgentDownloads.claim(2, "https://example.com/a.zip"))
        assertFalse(i.start.isCompleted)
        // A tab the agent doesn't hold isn't noted for it.
        assertFalse(AgentDownloads.takeHeld(2))
        AgentDownloads.disarm(i)
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
        assertFalse(i.start.isCompleted)
    }

    @Test
    fun `disarming says whether a download already met the intent`() {
        val i = intent()
        AgentDownloads.arm(i)
        // Nothing came: disarmed, and nothing can claim it now.
        assertTrue(AgentDownloads.disarm(i))
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
        assertFalse(i.claimed.isCompleted)
        // A download came just before the disarm (as a wait runs out): it's
        // claimed, and the disarm says so, so the tool answers it.
        val j = intent()
        AgentDownloads.arm(j)
        val claim = AgentDownloads.claim(1, "https://example.com/a.zip")!!
        assertFalse(AgentDownloads.disarm(j))
        assertTrue(j.claimed.isCompleted)
        claim.failed()
        assertEquals(DownloadIntent.Start.Failed, j.start.getCompleted())
    }

    @Test
    fun `the approved link's download after the wait ran out is noted apart, past the next step's start`() {
        AgentDownloads.holderOf = { if (it == 1L) "chat" else null }
        val i = intent()
        assertTrue(AgentDownloads.arm(i))
        assertTrue(AgentDownloads.disarm(i))
        AgentDownloads.lapse(i)
        // Another address is an ordinary held download.
        assertNull(AgentDownloads.claim(1, "https://evil.example/a.zip"))
        assertFalse(AgentDownloads.takeLate(1))
        assertTrue(AgentDownloads.takeHeld(1))
        // The approved link, late: waits for the user, not saved, and is
        // not cleared by the next step's start (which takes only held ones).
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip#x"))
        assertFalse(i.start.isCompleted)
        assertFalse(AgentDownloads.takeHeld(1))
        assertTrue(AgentDownloads.takeLate(1))
        assertFalse(AgentDownloads.takeLate(1))
        // Once only: the same link again is an ordinary held download.
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
        assertFalse(AgentDownloads.takeLate(1))
        assertTrue(AgentDownloads.takeHeld(1))
        // A new intent on the tab replaces the lapsed one.
        AgentDownloads.lapse(i)
        val j = intent()
        assertTrue(AgentDownloads.arm(j))
        assertTrue(AgentDownloads.disarm(j))
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
        assertFalse(AgentDownloads.takeLate(1))
    }

    @Test
    fun `a tab taken back before its download comes is the user's`() {
        val i = intent()
        AgentDownloads.arm(i)
        AgentDownloads.holderOf = { null }
        assertNull(AgentDownloads.claim(1, "https://example.com/a.zip"))
        assertEquals(DownloadIntent.Start.TakenBack, i.start.getCompleted())
    }

    @Test
    fun `an offered download reports the user's answer`() {
        val i = intent(direct = false)
        AgentDownloads.arm(i)
        val claim = AgentDownloads.claim(1, "https://example.com/a.zip")!!
        assertFalse(claim.direct)
        claim.declined()
        assertEquals(DownloadIntent.Start.Declined, i.start.getCompleted())
        val j = intent(direct = false)
        AgentDownloads.arm(j)
        AgentDownloads.claim(1, "https://example.com/a.zip")!!.notQueued()
        assertEquals(DownloadIntent.Start.NotQueued, j.start.getCompleted())
    }

    @Test
    fun `records last across restarts, per chat, and go with their chat`() = runBlocking {
        val file = File(dir, "agent/downloads.json")
        val records = AgentDownloadRecords(file)
        val a = records.add("chat", 3, "a‮exe.pdf", "https://example.com")
        val b = records.add("other", 4, "b.zip", "https://example.org")
        assertTrue(AgentDownloadRecords.ARTIFACT_ID.matches(a.artifactId))
        assertNotEquals(a.artifactId, b.artifactId)
        // A bidi override in a name is flattened before it's kept.
        assertFalse(a.fileName.contains('‮'))
        val reread = AgentDownloadRecords(file)
        assertEquals(listOf(a), reread.of("chat"))
        assertEquals(listOf(b), reread.of("other"))
        reread.forget("chat")
        assertTrue(AgentDownloadRecords(file).of("chat").isEmpty())
        assertEquals(listOf(b), AgentDownloadRecords(file).of("other"))
        AgentDownloadRecords(file).forget(null)
        assertTrue(AgentDownloadRecords(file).of("other").isEmpty())
    }

    @Test
    fun `one unreadable record doesn't lose the others`() = runBlocking {
        val file = File(dir, "downloads.json")
        file.writeText(
            """[{"session":"chat","artifact":"artifact_0123456789abcdef0123","row":5,"name":"ok.pdf","origin":""},""" +
                """{"session":"chat","artifact":"../../etc","row":6},"junk",""" +
                """{"session":"chat","artifact":"artifact_aaaaaaaaaaaaaaaaaaaa","row":-1}]""",
        )
        assertEquals(listOf(5L), AgentDownloadRecords(file).of("chat").map { it.rowId })
        file.writeText("not json")
        assertTrue(AgentDownloadRecords(file).of("chat").isEmpty())
    }

    @Test
    fun `a receipt says where the file stands, never where it is`() {
        val files = FakeFiles(dir)
        val record = AgentDownloadRecords.Record("chat", "artifact_0123456789abcdef0123", 9, "r.pdf", "https://example.com")
        val done = DownloadReceipt.of(record, files.entry(9, "r.pdf", "completed")) { true }
        assertEquals("completed", done.state)
        assertTrue(done.available)
        assertEquals(2048, done.bytes)
        assertFalse(done.json().toString().contains("content://"))
        assertFalse(DownloadReceipt.of(record, files.entry(9, "r.pdf", "completed")) { false }.available)
        // Not finished: never available, whatever is on disk.
        assertFalse(DownloadReceipt.of(record, files.entry(9, "r.pdf", "running")) { true }.available)
        val chosen = DownloadReceipt.of(record, files.entry(9, "r.pdf", "completed").copy(saveTo = "content://picked/doc")) { true }
        assertEquals("chosen_location", chosen.location)
        val removed = DownloadReceipt.of(record, null) { true }
        assertEquals("removed", removed.state)
        assertEquals("r.pdf", removed.fileName)
        assertFalse(removed.available)
    }
}
