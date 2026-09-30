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

    private val date = "Tue, 29 Sep 2026 10:00:05 GMT"

    @Test
    fun `a strong ETag is the validator`() {
        assertEquals("\"abc\"", downloadValidator("\"abc\"", "Tue, 29 Sep 2026 10:00:00 GMT", date))
        assertEquals("\"abc\"", downloadValidator("\"abc\"", null, null))
    }

    @Test
    fun `with no ETag, a Last-Modified a second or more before Date is the validator`() {
        assertEquals(
            "Tue, 29 Sep 2026 10:00:00 GMT",
            downloadValidator(null, "Tue, 29 Sep 2026 10:00:00 GMT", date),
        )
        assertEquals(
            "Tue, 29 Sep 2026 10:00:04 GMT",
            downloadValidator(null, "Tue, 29 Sep 2026 10:00:04 GMT", date),
        )
    }

    @Test
    fun `a Last-Modified within the second of Date, or with no Date, is weak`() {
        // RFC 9110 8.8.2.2: rewritten in that same second, the file could
        // carry the same date, so it can't guard a range.
        assertNull(downloadValidator(null, date, date))
        assertNull(downloadValidator(null, "Tue, 29 Sep 2026 10:00:09 GMT", date))
        assertNull(downloadValidator(null, "Tue, 29 Sep 2026 10:00:00 GMT", null))
        assertNull(downloadValidator(null, "Tue, 29 Sep 2026 10:00:00 GMT", "yesterday"))
        assertNull(downloadValidator(null, "Tuesday, 29-Sep-26 10:00:00 GMT", date))
    }

    @Test
    fun `a weak ETag is no validator, and rules out the date`() {
        // RFC 9110 13.1.5: never a weak tag, and never a date when the
        // client has an entity tag.
        assertNull(downloadValidator("W/\"abc\"", "Tue, 29 Sep 2026 10:00:00 GMT", date))
        assertNull(downloadValidator("w/\"abc\"", null, date))
    }

    @Test
    fun `no validator without either header`() {
        assertNull(downloadValidator(null, null, date))
        assertNull(downloadValidator(" ", "", date))
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
    fun `a 206 that stops short of the end asks for the whole file`() {
        // A server capping its range answers: taking the chunk would end
        // the body early and pause again after every one.
        assertEquals(ResumeAnswer.AskWhole, resumeAnswer(206, 100, "bytes 100-199/1000", 100))
    }

    @Test
    fun `a 206 of the whole file to a plain request is taken as a 200`() {
        assertEquals(ResumeAnswer.FromStart(restarted = false), resumeAnswer(206, 0, "bytes 0-999/1000", 1000))
        // With no complete length it can't be told whole.
        assertEquals(ResumeAnswer.AskWhole, resumeAnswer(206, 0, "bytes 0-999/*", 1000))
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
