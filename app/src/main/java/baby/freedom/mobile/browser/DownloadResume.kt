package baby.freedom.mobile.browser

import androidx.annotation.StringRes
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/**
 * Pure (Android-free) half of pause and resume for downloads (#265):
 * what a response says about resuming later, what a resume request
 * sends, and what its answer means. Kept apart from [DownloadManager]
 * so it can be unit-tested on the JVM.
 */

/**
 * The value a resume sends as `If-Range`, per RFC 9110 §13.1.5: the
 * response's `ETag` if it is a strong one; with no ETag at all, its
 * `Last-Modified` — but only when that date is a strong validator
 * (§8.8.2.2: at least a second older than the response's [date], so a
 * file rewritten within the same second can't carry the same date).
 * A weak ETag (`W/"…"`) is no use, and rules the date out too (a client
 * that has an entity tag must not send a date). Null otherwise: a resume
 * then can't tell whether the file changed, and starts over.
 */
internal fun downloadValidator(etag: String?, lastModified: String?, date: String?): String? {
    val tag = etag?.trim()?.takeIf { it.isNotEmpty() }
    if (tag != null) return tag.takeUnless { it.startsWith("W/", ignoreCase = true) }
    val modified = lastModified?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val modifiedAt = parseHttpDate(modified) ?: return null
    val sentAt = parseHttpDate(date) ?: return null
    return modified.takeIf { sentAt - modifiedAt >= 1 }
}

/** An IMF-fixdate (`Tue, 29 Sep 2026 10:00:00 GMT`) in epoch seconds; null if it isn't one. */
internal fun parseHttpDate(value: String?): Long? = runCatching {
    java.time.ZonedDateTime.parse(value!!.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        .toEpochSecond()
}.getOrNull()

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
     * A whole body (a 200, or a 206 of the whole file): the server
     * ignored the range, or the file changed (If-Range failed). Write it from byte 0; [restarted] when
     * a resume was asked for, so the row says it started over.
     */
    data class FromStart(val restarted: Boolean) : ResumeAnswer()

    /**
     * A partial answer the download can't use (a 416, or a 206 for some
     * other range or one that stops short of the end): ask again for
     * the whole file.
     */
    object AskWhole : ResumeAnswer()
}

/**
 * Judge a 2xx / 416 answer to a request that asked for byte [offset]
 * on (0: the whole file). [contentRange] and [contentLength] are the
 * response's headers.
 *
 * A 206 is only taken when it runs to the end of the file: from
 * [offset] to the last byte (or to an unknown end, a `*` length) on a resume,
 * and the whole file (`bytes 0-(N-1)/N`, or with an unknown (`*`)
 * complete length) to a plain request — which some servers answer
 * with a 206. A range that stops short (a server capping
 * its range answers) would end the body early and pause the download
 * again after every chunk, so it asks for the whole file instead.
 */
internal fun resumeAnswer(status: Int, offset: Long, contentRange: String?, contentLength: Long): ResumeAnswer =
    when (status) {
        206 -> {
            val range = parseContentRange(contentRange)
            val toTheEnd = range != null && (range.total < 0 || range.last == range.total - 1)
            when {
                range == null || range.first != offset || !toTheEnd -> ResumeAnswer.AskWhole
                // The whole file, as a 206: the same as a 200. With an
                // unknown length (`*`) too — asking again would get the
                // same answer, and a plain request took it before (#265).
                offset == 0L -> ResumeAnswer.FromStart(restarted = false)
                else -> ResumeAnswer.Continue(
                    when {
                        range.total >= 0 -> range.total
                        contentLength >= 0 -> offset + contentLength
                        else -> -1
                    },
                )
            }
        }
        416 -> ResumeAnswer.AskWhole
        else -> ResumeAnswer.FromStart(restarted = offset > 0)
    }

/** The note a resume that had to start over leaves on its row ([DownloadNote]). */
internal val DOWNLOAD_RESTARTED_NOTE: String get() = DownloadNote.of(R.string.library_download_note_restarted)

/** The note on a download a lost connection paused ([DownloadNote]). */
internal val DOWNLOAD_CONNECTION_LOST_NOTE: String get() = DownloadNote.of(R.string.library_download_note_connection_lost)

/** The note on a download the app's process died under ([DownloadNote]). */
internal val DOWNLOAD_INTERRUPTED_NOTE: String get() = DownloadNote.of(R.string.library_download_note_interrupted)

/**
 * A download row's `note` or `error` as it's stored (#313 R1-M5): one of
 * the app's own lines is kept as a key and its arguments, and read in the
 * app language whenever the row is drawn ([shown]), so a paused row's
 * "Connection lost" follows a change of the per-app language. Words from
 * elsewhere (a redirect's refusal, an exception's) are kept as they are.
 * The key is a stable name, not the resource id, which can change
 * between builds while the row stays in the database.
 */
internal object DownloadNote {
    private const val MARK = '\u0001'
    private const val SEP = '\u001F'

    private val KEYS: Map<String, Int> = linkedMapOf(
        "restarted" to R.string.library_download_note_restarted,
        "connection_lost" to R.string.library_download_note_connection_lost,
        "interrupted" to R.string.library_download_note_interrupted,
        "file_deleted" to R.string.library_download_file_deleted,
        "failed" to R.string.library_download_failed,
        "network_error" to R.string.library_download_network_error,
        "network_error_detail" to R.string.library_download_network_error_detail,
        "create_file_failed" to R.string.library_download_create_file_failed,
        "save_file_failed" to R.string.library_download_save_file_failed,
        "partial_file" to R.string.library_download_partial_file,
        "write_failed" to R.string.library_download_write_failed,
        "not_enough_storage" to R.string.library_download_not_enough_storage,
        "malformed_data_uri" to R.string.library_download_malformed_data_uri,
        "ens_unresolved" to R.string.library_download_ens_unresolved,
        "node_not_running" to R.string.library_download_node_not_running,
        "scheme_unsupported" to R.string.library_download_scheme_unsupported,
        "blob_unreachable" to R.string.library_download_blob_unreachable,
        "blob_gone" to R.string.library_download_blob_gone,
        "blob_page_closed" to R.string.library_download_blob_page_closed,
        "blob_refused" to R.string.library_download_blob_refused,
        "blob_too_big" to R.string.library_download_blob_too_big,
        "gateway_no_answer" to R.string.library_download_gateway_no_answer,
        "content_not_found" to R.string.library_download_content_not_found,
        "gateway_error" to R.string.library_download_gateway_error,
        "server_error" to R.string.library_download_server_error,
        "too_many_redirects" to R.string.library_download_too_many_redirects,
        "redirect_refused" to R.string.library_download_redirect_refused,
        "write_to_downloads_failed" to R.string.library_download_write_to_downloads_failed,
    )
    private val NAMES: Map<Int, String> = KEYS.entries.associate { (k, v) -> v to k }

    /** [id] with [args] (kept as text), stored to be read later; resolved now if [id] has no key. */
    fun of(@StringRes id: Int, vararg args: Any?): String {
        val name = NAMES[id] ?: return Strings.get(id, *args)
        return buildString {
            append(MARK).append(name)
            for (a in args) append(SEP).append(a.toString().replace(SEP, ' '))
        }
    }

    /** [stored] as the user reads it now: a key resolved in the app language, anything else as it is. */
    fun shown(stored: String?): String? {
        if (stored == null || !stored.startsWith(MARK)) return stored
        val parts = stored.substring(1).split(SEP)
        val id = KEYS[parts[0]] ?: return stored
        return runCatching { Strings.get(id, *parts.drop(1).toTypedArray()) }.getOrDefault(stored)
    }
}

/**
 * Can a running download be paused? A web one always can — a server
 * that can't resume it restarts it on resume, with a note. A dweb one
 * only if its gateway said it serves ranges ([resumable]); a `data:`
 * one never (it's already in memory).
 */
internal fun downloadCanPause(isWeb: Boolean, isData: Boolean, resumable: Boolean): Boolean =
    !isData && (isWeb || resumable)
