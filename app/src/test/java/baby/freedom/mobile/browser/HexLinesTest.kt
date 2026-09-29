package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexLinesTest {
    @Test
    fun `call data is the selector, then one 32-byte word a line, nothing cut`() {
        assertEquals(emptyList<String>(), hexLines("0x", selector = true))
        assertEquals(listOf("0xa9059cbb"), hexLines("0xa9059cbb", selector = true))
        val word = "00".repeat(31) + "01"
        assertEquals(listOf("0xa9059cbb", word, "ff"), hexLines("0xa9059cbb" + word + "ff", selector = true))
        // A binary message has no selector: words from the start.
        assertEquals(listOf("0x$word", "ab"), hexLines("0x" + word + "ab", selector = false))
    }

    @Test
    fun `the largest call data the session accepts is all there, in lines`() {
        val hex = "0x" + "ab".repeat(baby.freedom.mobile.wallet.OpenLvSession.MAX_CALL_DATA)
        val lines = hexLines(hex, selector = true)
        assertEquals(hex, lines.joinToString(""))
        assertTrue(lines.all { it.length <= 66 })
        assertTrue(lines.size > HEX_INLINE_LINES)
    }
}
