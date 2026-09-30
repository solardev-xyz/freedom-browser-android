package baby.freedom.mobile.browser

/**
 * Pure (Android-free) half of pause and resume for downloads (#265):
 * what a response says about resuming later, what a resume request
 * sends, and what its answer means. Kept apart from [DownloadManager]
 * so it can be unit-tested on the JVM.
 */

/**
 * The value a resume sends as `If-Range`: the response's `ETag` if it
 * is a strong one, else its `Last-Modified`. A weak ETag (`W/"…"`) can't
 * be used — RFC 9110 has a server ignore the range for one — so it falls
 * back to the date. Null when there's neither: a resume then can't tell
 * whether the file changed, and starts over.
 */
internal fun downloadValidator(etag: String?, lastModified: String?): String? {
    val tag = etag?.trim()?.takeIf { it.isNotEmpty() }
    if (tag != null && !tag.startsWith("W/", ignoreCase = true)) return tag
    return lastModified?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Does a response let a download resume where it stopped? Only with an
 * `Accept-Ranges` that lists `bytes` and a [validator] to guard it.
 */
internal fun downloadResumable(acceptRanges: String?, validator: String?): Boolean =
    validator != null &&
        acceptRanges?.split(',')?.any { it.trim().equals("bytes", ignoreCase = true) } == true

/**
 * The extra request headers of a resume from byte [offset]: a `Range`
 * guarded by `If-Range` against [validator]. None when there's nothing
 * to resume from — offset 0, or no validator (without one, a range could
 * splice a changed file onto the old bytes, so the download starts over).
 */
internal fun downloadResumeHeaders(offset: Long, validator: String?): Map<String, String> =
    if (offset <= 0 || validator == null) {
        emptyMap()
    } else {
        mapOf("Range" to "bytes=$offset-", "If-Range" to validator)
    }

/** A parsed `Content-Range: bytes <first>-<last>/<complete length or *>`. */
internal data class ContentRange(val first: Long, val last: Long, val total: Long)

/** [header] parsed; [ContentRange.total] is -1 for `*`. Null when malformed. */
internal fun parseContentRange(header: String?): ContentRange? {
    val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
        .matchEntire(header ?: return null) ?: return null
    val first = m.groupValues[1].toLongOrNull() ?: return null
    val last = m.groupValues[2].toLongOrNull() ?: return null
    val total = if (m.groupValues[3] == "*") -1L else m.groupValues[3].toLongOrNull() ?: return null
    if (last < first || (total >= 0 && last >= total)) return null
    return ContentRange(first, last, total)
}

/** What a download does with the answer to its request. */
internal sealed class ResumeAnswer {
    /** Go on from the requested offset; [total] is the whole file's length (-1: unknown). */
    data class Continue(val total: Long) : ResumeAnswer()

    /**
     * A whole body (200): the server ignored the range, or the file
     * changed (If-Range failed). Write it from byte 0; [restarted] when
     * a resume was asked for, so the row says it started over.
     */
    data class FromStart(val restarted: Boolean) : ResumeAnswer()

    /**
     * A partial answer the download can't use (a 416, or a 206 for some
     * other range): ask again for the whole file.
     */
    object AskWhole : ResumeAnswer()
}

/**
 * Judge a 2xx / 416 answer to a request that asked for byte [offset]
 * on (0: the whole file). [contentRange] and [contentLength] are the
 * response's headers.
 */
internal fun resumeAnswer(status: Int, offset: Long, contentRange: String?, contentLength: Long): ResumeAnswer =
    when {
        status == 206 && offset > 0 -> {
            val range = parseContentRange(contentRange)
            if (range == null || range.first != offset) {
                ResumeAnswer.AskWhole
            } else {
                ResumeAnswer.Continue(
                    when {
                        range.total >= 0 -> range.total
                        contentLength >= 0 -> offset + contentLength
                        else -> -1
                    },
                )
            }
        }
        // Nothing was asked for: a 206 is a server gone odd.
        status == 206 -> ResumeAnswer.AskWhole
        status == 416 -> ResumeAnswer.AskWhole
        else -> ResumeAnswer.FromStart(restarted = offset > 0)
    }

/** The note a resume that had to start over leaves on its row. */
internal const val DOWNLOAD_RESTARTED_NOTE = "Restarted from the beginning: the server couldn't resume it"

/** The note on a download a lost connection paused. */
internal const val DOWNLOAD_CONNECTION_LOST_NOTE = "Connection lost"

/** The note on a download the app's process died under. */
internal const val DOWNLOAD_INTERRUPTED_NOTE = "Interrupted"

/**
 * Can a running download be paused? A web one always can — a server
 * that can't resume it restarts it on resume, with a note. A dweb one
 * only if its gateway said it serves ranges ([resumable]); a `data:`
 * one never (it's already in memory).
 */
internal fun downloadCanPause(isWeb: Boolean, isData: Boolean, resumable: Boolean): Boolean =
    !isData && (isWeb || resumable)
