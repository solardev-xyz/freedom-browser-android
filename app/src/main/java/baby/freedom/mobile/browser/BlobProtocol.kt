package baby.freedom.mobile.browser

import java.security.SecureRandom
import java.util.Base64

/**
 * The pure (Android-free) half of `blob:` downloads ([BlobDownloads]):
 * the messages the page-side reader and Kotlin exchange, and the rules a
 * transfer's chunks are checked against. Kept apart so it can be
 * unit-tested on the JVM.
 *
 * Page → Kotlin, over the tab's own channel:
 * - [BLOB_HELLO]: a frame's reader is there (sent once, at document start).
 * - `o⏎token⏎size⏎type⏎name`: the file is held, under that token.
 * - `e⏎token⏎code`: it isn't ([BLOB_GONE]: no such file — revoked, or
 *   another frame's; [BLOB_READ_FAILED]: a chunk couldn't be read).
 * - a chunk: an `ArrayBuffer` of `token:offset:` then the bytes, or the
 *   text `d⏎token⏎offset⏎base64` where `ArrayBuffer` messages aren't
 *   supported.
 *
 * Kotlin → page:
 * - `p⏎token⏎mode⏎url`: hold this file, under this token ([BLOB_MODE_BINARY]
 *   or [BLOB_MODE_TEXT] chunks).
 * - `r⏎token⏎offset⏎length`: send this chunk.
 * - `x⏎token`: let go of it.
 *
 * Kotlin only ever sends a token and the URL the page itself made; the
 * page can learn nothing through it. And the page can't push anything:
 * a token exists only for a download the page's tab offered and the
 * user saw, a chunk is taken only from the frame that holds the file,
 * only the one chunk asked for, at the offset asked for, of the length
 * asked for, and never past the size the page announced ([BlobTransfer]).
 */

/** A frame's first message. */
internal const val BLOB_HELLO = "h"

internal const val BLOB_MODE_BINARY = "b"
internal const val BLOB_MODE_TEXT = "t"

/** `e` codes. */
internal const val BLOB_GONE = "gone"
internal const val BLOB_READ_FAILED = "read"

/** Bytes per chunk: a 100 MB file is 200 messages, and never more than one chunk is in memory. */
internal const val BLOB_CHUNK_BYTES = 512 * 1024

/** Longest file name taken from a page's `download` attribute (before [sanitizeFileName]). */
private const val BLOB_MAX_NAME_CHARS = 1024

/** Longest MIME type taken from a page. */
private const val BLOB_MAX_TYPE_CHARS = 255

private val blobRandom = SecureRandom()
private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
internal const val BLOB_TOKEN_LENGTH = 24

/** A fresh one-time transfer token: 24 alphanumerics, ~143 bits. */
internal fun newBlobToken(): String =
    String(CharArray(BLOB_TOKEN_LENGTH) { TOKEN_CHARS[blobRandom.nextInt(TOKEN_CHARS.length)] })

private fun isToken(s: String) = s.length == BLOB_TOKEN_LENGTH && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

/** What a page sent, parsed; null for anything malformed. */
internal sealed class BlobMessage {
    object Hello : BlobMessage()
    data class Ready(val token: String, val size: Long, val mimeType: String?, val name: String?) : BlobMessage()
    data class Failed(val token: String, val code: String) : BlobMessage()
    class Chunk(val token: String, val offset: Long, val bytes: ByteArray) : BlobMessage()
}

/** Parse a text message from the page. */
internal fun parseBlobMessage(data: String): BlobMessage? {
    if (data == BLOB_HELLO) return BlobMessage.Hello
    val parts = data.split('\n', limit = 5)
    return when (parts[0]) {
        "o" -> {
            if (parts.size != 5 || !isToken(parts[1])) return null
            val size = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val type = parts[3].takeIf { it.isNotBlank() && it.length <= BLOB_MAX_TYPE_CHARS }
            val name = parts[4].takeIf { it.isNotBlank() }?.take(BLOB_MAX_NAME_CHARS)
            BlobMessage.Ready(parts[1], size, type, name)
        }
        "e" -> {
            if (parts.size != 3 || !isToken(parts[1])) return null
            BlobMessage.Failed(parts[1], parts[2])
        }
        "d" -> {
            if (parts.size != 4 || !isToken(parts[1])) return null
            val offset = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val bytes = try {
                Base64.getDecoder().decode(parts[3])
            } catch (_: IllegalArgumentException) {
                return null
            }
            BlobMessage.Chunk(parts[1], offset, bytes)
        }
        else -> null
    }
}

/** Parse a binary chunk: `token:offset:` then the bytes. */
internal fun parseBlobChunk(data: ByteArray): BlobMessage.Chunk? {
    if (data.size < BLOB_TOKEN_LENGTH + 3 || data[BLOB_TOKEN_LENGTH] != ':'.code.toByte()) return null
    val token = String(data, 0, BLOB_TOKEN_LENGTH, Charsets.US_ASCII)
    if (!isToken(token)) return null
    var i = BLOB_TOKEN_LENGTH + 1
    var offset = 0L
    val digitsFrom = i
    while (i < data.size && data[i] != ':'.code.toByte()) {
        val d = data[i] - '0'.code.toByte()
        if (d !in 0..9 || i - digitsFrom >= 18) return null
        offset = offset * 10 + d
        i++
    }
    if (i == digitsFrom || i >= data.size) return null
    return BlobMessage.Chunk(token, offset, data.copyOfRange(i + 1, data.size))
}

internal fun blobPrepareMessage(token: String, binary: Boolean, url: String) =
    "p\n$token\n${if (binary) BLOB_MODE_BINARY else BLOB_MODE_TEXT}\n$url"

internal fun blobChunkRequest(token: String, offset: Long, length: Int) = "r\n$token\n$offset\n$length"

internal fun blobReleaseMessage(token: String) = "x\n$token"

/**
 * One transfer's bookkeeping: the chunk asked for and the bytes taken.
 * Not thread-safe; [BlobDownloads] calls it under its own lock.
 */
internal class BlobTransfer(val token: String, val size: Long, private val chunkBytes: Int = BLOB_CHUNK_BYTES) {
    /** Bytes taken so far. */
    var received = 0L
        private set

    /** The offset of the chunk asked for and not yet answered, or null. */
    var asked: Long? = null
        private set

    val done: Boolean get() = received >= size

    /** Ask for the next chunk: its offset and length, or null when every byte is in or one is already asked for. */
    fun next(): Pair<Long, Int>? {
        if (done || asked != null) return null
        asked = received
        return received to minOf(chunkBytes.toLong(), size - received).toInt()
    }

    /**
     * Take [chunk] if it's exactly the one asked for: this token, the
     * offset asked, the length asked (the last one shorter). Anything
     * else — unasked, repeated, too long, past the announced size, for
     * another transfer — is refused, and changes nothing.
     */
    fun accept(chunk: BlobMessage.Chunk): Boolean {
        val at = asked ?: return false
        if (chunk.token != token || chunk.offset != at) return false
        val want = minOf(chunkBytes.toLong(), size - at)
        if (chunk.bytes.size.toLong() != want) return false
        received += want
        asked = null
        return true
    }
}
