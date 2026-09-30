package baby.freedom.mobile.browser

import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pause and resume for downloads (#265): the pure half. */
class DownloadResumeTest {

    @Test
    fun `a strong ETag is the validator`() {
        assertEquals("\"abc\"", downloadValidator("\"abc\"", "Tue, 29 Sep 2026 10:00:00 GMT"))
    }

    @Test
    fun `a weak ETag falls back to Last-Modified`() {
        assertEquals(
            "Tue, 29 Sep 2026 10:00:00 GMT",
            downloadValidator("W/\"abc\"", "Tue, 29 Sep 2026 10:00:00 GMT"),
        )
        assertNull(downloadValidator("w/\"abc\"", null))
    }

    @Test
    fun `no validator without either header`() {
        assertNull(downloadValidator(null, null))
        assertNull(downloadValidator(" ", ""))
    }

    @Test
    fun `resumable needs Accept-Ranges bytes and a validator`() {
        assertTrue(downloadResumable("bytes", "\"a\""))
        assertTrue(downloadResumable("none, Bytes", "\"a\""))
        assertFalse(downloadResumable("none", "\"a\""))
        assertFalse(downloadResumable(null, "\"a\""))
        assertFalse(downloadResumable("bytes", null))
    }

    @Test
    fun `resume headers ask for the rest, guarded by If-Range`() {
        assertEquals(
            mapOf("Range" to "bytes=1000-", "If-Range" to "\"a\""),
            downloadResumeHeaders(1000, "\"a\""),
        )
    }

    @Test
    fun `no range from 0 or without a validator`() {
        assertEquals(emptyMap<String, String>(), downloadResumeHeaders(0, "\"a\""))
        assertEquals(emptyMap<String, String>(), downloadResumeHeaders(1000, null))
    }

    @Test
    fun `Content-Range parses, with or without a total`() {
        assertEquals(ContentRange(100, 199, 1000), parseContentRange("bytes 100-199/1000"))
        assertEquals(ContentRange(100, 199, -1), parseContentRange("bytes 100-199/*"))
        assertNull(parseContentRange("bytes */1000"))
        assertNull(parseContentRange("bytes 200-100/1000"))
        assertNull(parseContentRange("bytes 100-1000/1000"))
        assertNull(parseContentRange("items 1-2/3"))
        assertNull(parseContentRange(null))
    }

    @Test
    fun `a 206 for the asked-for offset continues`() {
        assertEquals(ResumeAnswer.Continue(1000), resumeAnswer(206, 100, "bytes 100-999/1000", 900))
        // No complete length: the offset plus what's coming.
        assertEquals(ResumeAnswer.Continue(1000), resumeAnswer(206, 100, "bytes 100-999/*", 900))
        assertEquals(ResumeAnswer.Continue(-1), resumeAnswer(206, 100, "bytes 100-999/*", -1))
    }

    @Test
    fun `a 206 for another range, or a 416, asks for the whole file`() {
        assertEquals(ResumeAnswer.AskWhole, resumeAnswer(206, 100, "bytes 0-999/1000", 1000))
        assertEquals(ResumeAnswer.AskWhole, resumeAnswer(206, 100, null, 900))
        assertEquals(ResumeAnswer.AskWhole, resumeAnswer(416, 100, "bytes */1000", 0))
        // Nothing asked for, part sent.
        assertEquals(ResumeAnswer.AskWhole, resumeAnswer(206, 0, "bytes 0-9/1000", 10))
    }

    @Test
    fun `a 200 to a resume restarts, to a first request doesn't`() {
        assertEquals(ResumeAnswer.FromStart(restarted = true), resumeAnswer(200, 100, null, 1000))
        assertEquals(ResumeAnswer.FromStart(restarted = false), resumeAnswer(200, 0, null, 1000))
    }

    @Test
    fun `web downloads can always pause, dweb only with ranges, data never`() {
        assertTrue(downloadCanPause(isWeb = true, isData = false, resumable = false))
        assertFalse(downloadCanPause(isWeb = false, isData = false, resumable = false))
        assertTrue(downloadCanPause(isWeb = false, isData = false, resumable = true))
        assertFalse(downloadCanPause(isWeb = false, isData = true, resumable = true))
    }

    private fun row(
        status: String,
        received: Long,
        total: Long,
        validator: String? = "\"a\"",
        note: String? = null,
    ) = DownloadEntry(
        id = 1,
        fileName = "f.bin",
        displayUrl = "https://example.com/f.bin",
        sourceUrl = "https://example.com/f.bin",
        mimeType = "application/octet-stream",
        contentUri = null,
        status = status,
        totalBytes = total,
        receivedBytes = received,
        error = null,
        startedAt = 0,
        finishedAt = null,
        validator = validator,
        resumable = validator != null,
        note = note,
    )

    @Test
    fun `a paused row says how far it got and why it paused`() {
        assertEquals("Paused · 1.0 KB of 2.0 KB", downloadStatusLine(row(DownloadStatus.PAUSED, 1024, 2048), null, "t"))
        assertEquals(
            "Paused: connection lost · 1.0 KB of 2.0 KB",
            downloadStatusLine(row(DownloadStatus.PAUSED, 1024, 2048, note = DOWNLOAD_CONNECTION_LOST_NOTE), null, "t"),
        )
        assertEquals(
            "Paused · 1.0 KB downloaded · resuming starts over",
            downloadStatusLine(row(DownloadStatus.PAUSED, 1024, -1, validator = null), null, "t"),
        )
    }

    @Test
    fun `a running row that restarted says so`() {
        assertEquals(
            "1.0 KB of 2.0 KB · $DOWNLOAD_RESTARTED_NOTE",
            downloadStatusLine(
                row(DownloadStatus.RUNNING, 0, 2048, note = DOWNLOAD_RESTARTED_NOTE),
                DownloadProgress(1024, 2048),
                "t",
            ),
        )
        assertEquals(
            "Saving to Downloads…",
            downloadStatusLine(row(DownloadStatus.RUNNING, 0, 2048), DownloadProgress(2048, 2048, saving = true), "t"),
        )
    }
}
