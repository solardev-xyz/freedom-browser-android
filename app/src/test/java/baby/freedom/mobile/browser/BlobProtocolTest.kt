package baby.freedom.mobile.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BlobProtocolTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUv12"

    private fun binary(token: String, offset: Long, payload: ByteArray) =
        "$token:$offset:".toByteArray(Charsets.US_ASCII) + payload

    // ------------------------------------------------------------------
    // tokens and messages
    // ------------------------------------------------------------------

    @Test
    fun `tokens are fresh, 24 alphanumerics`() {
        val tokens = List(200) { newBlobToken() }
        assertEquals(200, tokens.toSet().size)
        assertTrue(tokens.all { it.length == BLOB_TOKEN_LENGTH && it.all(Char::isLetterOrDigit) })
    }

    @Test
    fun `kotlin's messages carry only the token, the url and a range`() {
        assertEquals("p\n$token\nb\nblob:https://a.example/u", blobPrepareMessage(token, true, "blob:https://a.example/u"))
        assertEquals("p\n$token\nt\nblob:https://a.example/u", blobPrepareMessage(token, false, "blob:https://a.example/u"))
        assertEquals("r\n$token\n524288\n1000", blobChunkRequest(token, 524288, 1000))
        assertEquals("x\n$token", blobReleaseMessage(token))
    }

    @Test
    fun `a frame's answers parse`() {
        assertEquals(BlobMessage.Hello, parseBlobMessage("h"))
        assertEquals(
            BlobMessage.Ready(token, 139, "application/json", "export.json"),
            parseBlobMessage("o\n$token\n139\napplication/json\nexport.json"),
        )
        // No type, no name: nothing made up.
        assertEquals(BlobMessage.Ready(token, 0, null, null), parseBlobMessage("o\n$token\n0\n\n"))
        // A name may hold anything, newlines included (sanitized later).
        assertEquals("a\nb.txt", (parseBlobMessage("o\n$token\n1\ntext/plain\na\nb.txt") as BlobMessage.Ready).name)
        assertEquals(BlobMessage.Failed(token, BLOB_GONE), parseBlobMessage("e\n$token\ngone"))
        val chunk = parseBlobMessage("d\n$token\n5\n" + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))) as BlobMessage.Chunk
        assertEquals(5L, chunk.offset)
        assertArrayEquals(byteArrayOf(1, 2, 3), chunk.bytes)
    }

    @Test
    fun `malformed answers are dropped`() {
        for (m in listOf(
            "", "hello", "o", "o\n$token\n-1\nx\ny", "o\n$token\n12x\nx\ny", "o\nshort\n1\nx\ny",
            "o\n${token}!\n1\nx\ny", "e\n$token", "d\n$token\n0\n@@@", "d\n$token\n-4\nAAAA",
            "o\n$token\n1\n" + "t".repeat(300) + "\nn", "z\n$token\n1",
        )) {
            val parsed = parseBlobMessage(m)
            if (m.startsWith("o\n$token\n1\nttt")) {
                // An overlong type is dropped, not the message.
                assertNull((parsed as BlobMessage.Ready).mimeType)
            } else {
                assertNull(m, parsed)
            }
        }
    }

    @Test
    fun `binary chunks parse, and junk doesn't`() {
        val payload = ByteArray(1000) { it.toByte() }
        val chunk = parseBlobChunk(binary(token, 524288, payload))!!
        assertEquals(token, chunk.token)
        assertEquals(524288L, chunk.offset)
        assertArrayEquals(payload, chunk.bytes)
        // An empty last chunk of an empty tail is still a chunk.
        assertEquals(0, parseBlobChunk(binary(token, 7, ByteArray(0)))!!.bytes.size)
        assertNull(parseBlobChunk(ByteArray(0)))
        assertNull(parseBlobChunk("short:0:".toByteArray()))
        assertNull(parseBlobChunk("$token::abc".toByteArray()))
        assertNull(parseBlobChunk("$token:1a:abc".toByteArray()))
        assertNull(parseBlobChunk("$token:12".toByteArray()))
        assertNull(parseBlobChunk("$token:${"9".repeat(19)}:x".toByteArray()))
        assertNull(parseBlobChunk("${token.dropLast(1)}!:0:x".toByteArray()))
    }

    // ------------------------------------------------------------------
    // BlobTransfer: only the chunk asked for is taken
    // ------------------------------------------------------------------

    private fun chunk(offset: Long, size: Int, t: String = token) = BlobMessage.Chunk(t, offset, ByteArray(size))

    @Test
    fun `a transfer asks chunk by chunk and ends at the announced size`() {
        val t = BlobTransfer(token, size = 25, chunkBytes = 10)
        assertEquals(0L to 10, t.next())
        assertNull(t.next()) // one at a time
        assertTrue(t.accept(chunk(0, 10)))
        assertEquals(10L to 10, t.next())
        assertTrue(t.accept(chunk(10, 10)))
        assertEquals(20L to 5, t.next())
        assertTrue(t.accept(chunk(20, 5)))
        assertTrue(t.done)
        assertEquals(25L, t.received)
        assertNull(t.next())
    }

    @Test
    fun `an empty file needs no chunk`() {
        val t = BlobTransfer(token, size = 0)
        assertTrue(t.done)
        assertNull(t.next())
        assertFalse(t.accept(chunk(0, 0)))
    }

    @Test
    fun `nothing is taken that wasn't asked for`() {
        val t = BlobTransfer(token, size = 25, chunkBytes = 10)
        // Before any ask: a page can't push bytes.
        assertFalse(t.accept(chunk(0, 10)))
        t.next()
        assertFalse(t.accept(chunk(0, 10, t = newBlobToken()))) // another transfer's token
        assertFalse(t.accept(chunk(10, 10))) // the wrong offset
        assertFalse(t.accept(chunk(0, 11))) // too long
        assertFalse(t.accept(chunk(0, 9))) // too short
        assertEquals(0L, t.received)
        assertTrue(t.accept(chunk(0, 10)))
        // The same chunk again: already answered.
        assertFalse(t.accept(chunk(0, 10)))
        t.next()
        t.accept(chunk(10, 10))
        t.next()
        // Past the announced size: the last chunk is only what's left.
        assertFalse(t.accept(chunk(20, 10)))
        assertTrue(t.accept(chunk(20, 5)))
        assertFalse(t.accept(chunk(25, 1)))
        assertEquals(25L, t.received)
    }

    @Test
    fun `chunks default to half a MiB`() {
        val t = BlobTransfer(token, size = 100L * 1024 * 1024)
        assertEquals(0L to BLOB_CHUNK_BYTES, t.next())
        assertNotEquals(0, BLOB_CHUNK_BYTES)
        assertEquals(512 * 1024, BLOB_CHUNK_BYTES)
    }

    @Test
    fun `capture and working notes parse, and malformed ones don't`() {
        assertEquals(BlobMessage.Captured("blob:https://a.example/u"), parseBlobMessage("c\nblob:https://a.example/u"))
        assertEquals(BlobMessage.Working(token), parseBlobMessage("a\n$token"))
        assertEquals(BlobMessage.Failed(token, BLOB_TOO_BIG), parseBlobMessage("e\n$token\n$BLOB_TOO_BIG"))
        for (m in listOf(
            "c\nhttps://a.example/u", // not blob:
            "c\nblob:https://a.example/u\nmore",
            "c\nblob:" + "x".repeat(5000),
            "a\nshort",
            "a\n$token\nextra",
        )) assertNull(m, parseBlobMessage(m))
    }

    @Test
    fun `the copy limit is far below what a second copy fails at`() {
        // A 200 MB second copy fails on a 2.5 GB device (#408 R3-F1); 120 MB works.
        assertEquals(64L * 1024 * 1024, BLOB_COPY_MAX_BYTES)
    }
}
