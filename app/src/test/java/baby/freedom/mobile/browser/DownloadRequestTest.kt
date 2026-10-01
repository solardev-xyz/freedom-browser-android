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
    fun `a bidi override can't disguise the file's extension`() {
        // "invoice<RLO>fdp.apk" draws as "invoicekpa.pdf": an APK posing as a PDF.
        assertEquals(
            "invoice_fdp.apk",
            downloadFileName("attachment; filename*=UTF-8''invoice%E2%80%AEfdp.apk", "https://x.com/", null),
        )
        assertEquals("invoice_fdp.apk", downloadFileName(null, "https://x.com/invoice%E2%80%AEfdp.apk", null))
        // Every other bidi control too: embeddings, isolates, marks.
        for (c in listOf('\u202A', '\u202B', '\u202C', '\u202D', '\u2066', '\u2067', '\u2068', '\u2069', '\u200E', '\u200F', '\u061C')) {
            assertEquals("a_b.txt", sanitizeFileName("a${c}b.txt"))
        }
    }

    @Test
    fun `invisible format characters, separators and C1 controls are replaced`() {
        // Zero-width space, BOM, soft hyphen, word joiner, line / paragraph separators, a C1 control.
        for (c in listOf('\u200B', '\uFEFF', '\u00AD', '\u2060', '\u2028', '\u2029', '\u0085', '\u009B')) {
            assertEquals("a_b.txt", sanitizeFileName("a${c}b.txt"))
        }
        // A supplementary-plane format character, judged by code point (not as two surrogates).
        assertEquals("a_b.txt", sanitizeFileName("a\uDB40\uDC01b.txt"))
        // A lone surrogate can't be encoded into a file name.
        assertEquals("a_b.txt", sanitizeFileName("a\uD83Db.txt"))
    }

    @Test
    fun `names that need joiners and tags keep them`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍👧
        assertEquals("$family.png", sanitizeFileName("$family.png"))
        val persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645.pdf" // می‌خواهم (ZWNJ)
        assertEquals(persian, sanitizeFileName(persian))
        val scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        assertEquals("$scotland.jpg", sanitizeFileName("$scotland.jpg"))
        assertEquals("na\u00EFve caf\u00E9 \u65E5\u672C.txt", sanitizeFileName("na\u00EFve caf\u00E9 \u65E5\u672C.txt"))
    }

    @Test
    fun `clamping never splits an emoji in half`() {
        val emoji = "\uD83D\uDE00" // 😀, two chars
        // 116 chars kept before ".txt": the 116th would be the first half of an emoji.
        val name = downloadFileName(null, "https://x.com/" + "a".repeat(115) + emoji.repeat(10) + ".txt", null)
        assertTrue(name.endsWith(".txt"))
        assertTrue(name.length <= 120)
        assertTrue(name.indices.none { i ->
            name[i].isHighSurrogate() && (i + 1 >= name.length || !name[i + 1].isLowSurrogate()) ||
                name[i].isLowSurrogate() && (i == 0 || !name[i - 1].isHighSurrogate())
        })
        assertEquals("a".repeat(115) + ".txt", name)
    }

    @Test
    fun `clamping never splits a grapheme cluster`() {
        // 116 chars are kept before ".txt"; each case puts the cut inside a cluster.
        fun clamp(prefix: Int, cluster: String) =
            downloadFileName(null, "https://x.com/" + "a".repeat(prefix) + cluster + "b".repeat(200) + ".txt", null)
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍👧, 8 chars
        for (prefix in 109..115) assertEquals("a".repeat(prefix) + ".txt", clamp(prefix, family))
        assertEquals("a".repeat(108) + family + ".txt", clamp(108, family))
        val scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        for (prefix in 103..115) assertEquals("a".repeat(prefix) + ".txt", clamp(prefix, scotland))
        val thumbs = "\uD83D\uDC4D\uD83C\uDFFD" // 👍🏽
        assertEquals("a".repeat(114) + ".txt", clamp(114, thumbs))
        // Two flags back to back: the cut lands between the halves of the second.
        val flags = "\uD83C\uDDE9\uD83C\uDDEA\uD83C\uDDEB\uD83C\uDDF7" // 🇩🇪🇫🇷
        assertEquals("a".repeat(110) + "\uD83C\uDDE9\uD83C\uDDEA.txt", clamp(110, flags))
        val accented = "e\u0301\u0301" // é with two marks
        assertEquals("a".repeat(114) + ".txt", clamp(114, accented))
    }

    @Test
    fun `a name that is one long cluster is still cut, never emptied`() {
        val name = downloadFileName(null, "https://x.com/e" + "\u0301".repeat(300) + ".txt", null)
        assertEquals("e" + "\u0301".repeat(115) + ".txt", name)
    }

    @Test
    fun `a stored name is cleaned of hidden characters and nothing else`() {
        assertEquals("invoice_fdp.apk", cleanStoredFileName("invoice\u202Efdp.apk"))
        // What a picker named stays as it was otherwise: leading dot, spaces.
        assertEquals(".notes _x .txt", cleanStoredFileName(".notes \u200Bx .txt"))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67.png"
        assertEquals(family, cleanStoredFileName(family))
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

    // ------------------------------------------------------------------
    // downloadRefererOrigin / downloadReferer
    // ------------------------------------------------------------------

    @Test
    fun `only the page's origin is kept, never its path or query`() {
        assertEquals(
            "https://mail.example.com/",
            downloadRefererOrigin("https://user:pw@Mail.Example.com:443/inbox/msg?id=123&token=abc#top"),
        )
        assertEquals("http://h.example:8080/", downloadRefererOrigin("http://h.example:8080/a"))
    }

    @Test
    fun `no origin for home, about, data or virtual dweb pages`() {
        assertNull(downloadRefererOrigin(null))
        assertNull(downloadRefererOrigin(""))
        assertNull(downloadRefererOrigin("about:blank"))
        assertNull(downloadRefererOrigin("data:text/plain,hi"))
        assertNull(downloadRefererOrigin(VirtualOrigin.toVirtualUrl("bzz://$ref/index.html")))
    }

    @Test
    fun `same-origin request gets the bare origin`() {
        val origin = downloadRefererOrigin("https://mail.example.com/inbox/msg?id=123&token=abc")
        assertEquals("https://mail.example.com/", downloadReferer(origin, "https://mail.example.com/att/1.pdf"))
        assertEquals("https://mail.example.com/", downloadReferer(origin, "https://MAIL.example.com:443/x"))
    }

    @Test
    fun `cross-origin request gets no referer`() {
        val origin = downloadRefererOrigin("https://mail.example.com/inbox/msg?id=123&token=abc")
        assertNull(downloadReferer(origin, "https://files.other.net/tool.zip"))
        assertNull(downloadReferer(origin, "https://cdn.mail.example.com/tool.zip"))
        assertNull(downloadReferer(origin, "https://mail.example.com:8443/tool.zip"))
    }

    @Test
    fun `https to http downgrade gets no referer, even on the same host`() {
        val origin = downloadRefererOrigin("https://mail.example.com/inbox")
        assertNull(downloadReferer(origin, "http://files.other.net/tool.zip"))
        assertNull(downloadReferer(origin, "http://mail.example.com/tool.zip"))
    }

    @Test
    fun `referer is re-decided per redirect hop`() {
        val origin = downloadRefererOrigin("https://site.example/downloads")
        val hops = listOf(
            "https://site.example/get?id=1",
            "https://storage.cdn.example/signed?sig=x",
            "http://site.example/final.zip",
        )
        assertEquals(listOf("https://site.example/", null, null), hops.map { downloadReferer(origin, it) })
    }

    @Test
    fun `a typed navigation (no page) sends no referer`() {
        assertNull(downloadReferer(downloadRefererOrigin(null), "https://files.other.net/tool.zip"))
        assertNull(downloadReferer(null, "https://mail.example.com/x"))
    }

    // ------------------------------------------------------------------
    // downloadRedirect
    // ------------------------------------------------------------------

    @Test
    fun `redirects resolve against the answering url and may switch http and https`() {
        assertEquals(
            DownloadRedirect.Follow("https://example.com/files/b.zip"),
            downloadRedirect("https://example.com/files/a.zip", "b.zip"),
        )
        assertEquals(
            DownloadRedirect.Follow("https://cdn.example.net/x.zip"),
            downloadRedirect("http://example.com/a", "https://cdn.example.net/x.zip"),
        )
        assertEquals(
            DownloadRedirect.Follow("http://example.com/x"),
            downloadRedirect("https://example.com/a", "HTTP://example.com/x"),
        )
    }

    @Test
    fun `redirects to other schemes are refused with the scheme named`() {
        for ((location, scheme) in listOf(
            "ftp://host/file.zip" to "ftp",
            "intent://scan/#Intent;scheme=zxing;end" to "intent",
            "data:text/plain,hi" to "data",
            "file:///etc/hosts" to "file",
            "Market://details?id=x" to "market",
        )) {
            assertEquals(
                location,
                DownloadRedirect.Refuse("Redirected to an unsupported $scheme: link"),
                downloadRedirect("https://example.com/a", location),
            )
        }
    }

    @Test
    fun `missing or unusable locations are refused`() {
        assertEquals(
            DownloadRedirect.Refuse("Redirect without a location"),
            downloadRedirect("https://example.com/a", null),
        )
        assertEquals(
            DownloadRedirect.Refuse("Redirect without a location"),
            downloadRedirect("https://example.com/a", "  "),
        )
        assertEquals(
            DownloadRedirect.Refuse("Malformed redirect"),
            downloadRedirect("https://example.com/a", "http://"),
        )
    }
}
