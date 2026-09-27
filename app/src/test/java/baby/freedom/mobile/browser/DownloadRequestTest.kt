package baby.freedom.mobile.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRequestTest {

    private val ref = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
    private val ext = mapOf("application/pdf" to "pdf", "image/png" to "png", "text/plain" to "txt")

    // ------------------------------------------------------------------
    // classifyDownloadUrl
    // ------------------------------------------------------------------

    @Test
    fun `virtual bzz origin maps to its root and shows as bzz`() {
        val virtual = VirtualOrigin.toVirtualUrl("bzz://$ref/files/report.pdf?x=1")!!
        val t = classifyDownloadUrl(virtual) as DownloadTarget.Dweb
        assertEquals(ContentRoot.Bzz(ref), t.root)
        assertEquals("/files/report.pdf?x=1", t.pathAndQuery)
        assertEquals("bzz://$ref/files/report.pdf?x=1", t.displayUrl)
    }

    @Test
    fun `scheme urls are dweb too, bare root gets a slash path`() {
        val t = classifyDownloadUrl("bzz://$ref") as DownloadTarget.Dweb
        assertEquals("/", t.pathAndQuery)
        assertEquals("bzz://$ref", t.displayUrl)
        val ens = classifyDownloadUrl("ens://site.eth/a.zip") as DownloadTarget.Dweb
        assertEquals(ContentRoot.Ens("site.eth"), ens.root)
        assertEquals("site.eth/a.zip", ens.displayUrl)
    }

    @Test
    fun `virtual ipfs origin is dweb`() {
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        val virtual = VirtualOrigin.toVirtualUrl("ipfs://$cid/a.txt")!!
        val t = classifyDownloadUrl(virtual) as DownloadTarget.Dweb
        assertTrue(t.root is ContentRoot.Ipfs)
        assertEquals("ipfs://$cid/a.txt", t.displayUrl)
    }

    @Test
    fun `plain web, local gateway, data and blob`() {
        assertEquals(
            DownloadTarget.Web("https://example.com/a.zip", "https://example.com/a.zip"),
            classifyDownloadUrl("https://example.com/a.zip"),
        )
        val gw = "http://127.0.0.1:1633/bzz/$ref/a.zip"
        assertEquals(
            DownloadTarget.LocalGateway(gw, "bzz://$ref/a.zip"),
            classifyDownloadUrl(gw, isLocalGateway = { it.startsWith("http://127.0.0.1:1633") }) {
                "bzz://$ref/a.zip"
            },
        )
        assertTrue(classifyDownloadUrl("data:text/plain,hi") is DownloadTarget.Data)
        val blob = classifyDownloadUrl("blob:https://example.com/uuid")
        assertEquals("blob", (blob as DownloadTarget.Unsupported).scheme)
    }

    @Test
    fun `malformed dweb url is unsupported, not a crash`() {
        assertTrue(classifyDownloadUrl("bzz://nothex") is DownloadTarget.Unsupported)
    }

    @Test
    fun `long data uri display is shortened`() {
        val uri = "data:text/plain;base64," + "A".repeat(500)
        val shown = classifyDownloadUrl(uri).displayUrl
        assertTrue(shown.length < 60)
        assertTrue(shown.endsWith("…"))
    }

    // ------------------------------------------------------------------
    // parseDataUri
    // ------------------------------------------------------------------

    @Test
    fun `data uri base64 and percent-encoded forms decode`() {
        val b64 = parseDataUri("data:image/png;base64,iVBORw0KGgo=")!!
        assertEquals("image/png", b64.mimeType)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), b64.bytes)

        val text = parseDataUri("data:text/csv;charset=utf-8,a%2Cb%0A1,2")!!
        assertEquals("text/csv", text.mimeType)
        assertEquals("a,b\n1,2", String(text.bytes, Charsets.UTF_8))

        val bare = parseDataUri("data:,hello%20world")!!
        assertEquals("text/plain", bare.mimeType)
        assertEquals("hello world", String(bare.bytes))

        // Percent-encoded base64 ('=' as %3D) still decodes.
        assertEquals("hi", String(parseDataUri("data:;base64,aGk%3D")!!.bytes))
    }

    @Test
    fun `malformed data uris are rejected`() {
        assertNull(parseDataUri("data:text/plain"))
        assertNull(parseDataUri("data:;base64,@@@"))
        assertNull(parseDataUri("https://example.com"))
    }

    // ------------------------------------------------------------------
    // downloadFileName
    // ------------------------------------------------------------------

    @Test
    fun `content-disposition wins, filename-star over filename`() {
        assertEquals(
            "report.pdf",
            downloadFileName("attachment; filename=\"report.pdf\"", "https://x.com/dl?id=1", null),
        )
        assertEquals(
            "naïve €.txt",
            downloadFileName(
                "attachment; filename=\"naive.txt\"; filename*=UTF-8''na%C3%AFve%20%E2%82%AC.txt",
                "https://x.com/dl",
                null,
            ),
        )
        assertEquals("a;b.txt", downloadFileName("attachment; filename=\"a;b.txt\"", "https://x.com/", null))
        assertEquals("plain.bin", downloadFileName("attachment; filename=plain.bin; size=3", "https://x.com/", null))
    }

    @Test
    fun `falls back to decoded last path segment`() {
        assertEquals("my file.zip", downloadFileName(null, "https://x.com/a/my%20file.zip?t=1#f", null))
        assertEquals("a+b.txt", downloadFileName("inline", "https://x.com/a+b.txt", null))
    }

    @Test
    fun `no name anywhere falls back to default plus mime extension`() {
        assertEquals("download.pdf", downloadFileName(null, "https://x.com/", "application/pdf", ext::get))
        assertEquals("download.txt", downloadFileName(null, "data:text/plain,hi", "text/plain", ext::get))
        assertEquals("download", downloadFileName(null, "bzz://$ref", "application/octet-stream", ext::get))
        // A name with an extension keeps it.
        assertEquals("pic.jpeg", downloadFileName(null, "https://x.com/pic.jpeg", "image/png", ext::get))
    }

    @Test
    fun `path traversal and reserved characters are stripped`() {
        assertEquals("passwd", downloadFileName("attachment; filename=\"../../etc/passwd\"", "https://x.com/", null))
        assertEquals("evil.sh", downloadFileName("attachment; filename=\"..\\\\evil.sh\"", "https://x.com/", null))
        assertEquals("a_b_.txt", downloadFileName("attachment; filename=\"a:b?.txt\"", "https://x.com/", null))
        assertEquals("hidden", sanitizeFileName(".hidden"))
    }

    @Test
    fun `overlong names are clamped keeping the extension`() {
        val name = downloadFileName(null, "https://x.com/" + "a".repeat(300) + ".tar.gz", null)
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".gz"))
    }

    @Test
    fun `byte sizes format on a 1024 ladder`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("4.0 MB", formatBytes(4L * 1024 * 1024))
        assertEquals("120 MB", formatBytes(120L * 1024 * 1024))
    }

    // ------------------------------------------------------------------
    // downloadEndsPendingNavigation
    // ------------------------------------------------------------------

    @Test
    fun `a download ends an uncommitted navigation`() {
        // Typed a file URL over swarm.eth: the address never committed.
        assertTrue(downloadEndsPendingNavigation("swarm.eth", "https://x.com/a.pdf", resolving = false, downloadIsNavigationResponse = true))
        // …from home, too.
        assertTrue(downloadEndsPendingNavigation("", "https://x.com/a.pdf", resolving = false, downloadIsNavigationResponse = true))
    }

    @Test
    fun `a download from a committed page leaves the tab alone`() {
        assertEquals(false, downloadEndsPendingNavigation("swarm.eth", "swarm.eth", resolving = false, downloadIsNavigationResponse = true))
        assertEquals(false, downloadEndsPendingNavigation("", "", resolving = false, downloadIsNavigationResponse = true))
    }

    @Test
    fun `a download the committed page starts leaves the pending navigation alone`() {
        // Typed a new address; before it commits, the page on screen
        // fires a delayed download of its own — a URL the pending
        // navigation never requested.
        assertEquals(
            false,
            downloadEndsPendingNavigation(
                "https://mirror.example/get",
                "https://x.com/next",
                resolving = false,
                downloadIsNavigationResponse = false,
            ),
        )
    }

    @Test
    fun `a pending navigation still resolving can't be the download`() {
        assertEquals(false, downloadEndsPendingNavigation("swarm.eth", "bzz://abc", resolving = true, downloadIsNavigationResponse = true))
    }
}
