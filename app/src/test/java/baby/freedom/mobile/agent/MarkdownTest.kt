package baby.freedom.mobile.agent

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply's Markdown (#574): its blocks, and inline text that hides nothing. */
class MarkdownTest {
    private val palette = Markdown.Palette(Color.Gray, Color.Blue, Color.Gray)

    private fun text(s: String) = Markdown.inline(s, palette).text

    @Test
    fun `blocks - headings, lists with depth, quotes, rules, code`() {
        val blocks = Markdown.parse(
            """
            ## Plan
            First line
            second line

            - one
              - nested
                continued
            3. three
            > quoted
            ---
            ```kotlin
            val x = 1

            val y = 2
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MdBlock.Heading(2, "Plan"),
                MdBlock.Paragraph("First line\nsecond line"),
                MdBlock.Item("•", 0, "one"),
                MdBlock.Item("•", 1, "nested\ncontinued"),
                MdBlock.Item("3.", 0, "three"),
                MdBlock.Quote("quoted"),
                MdBlock.Rule,
                MdBlock.Code("val x = 1\n\nval y = 2"),
            ),
            blocks,
        )
    }

    @Test
    fun `a code fence still streaming runs to the end`() {
        assertEquals(listOf(MdBlock.Paragraph("Here:"), MdBlock.Code("partial")), Markdown.parse("Here:\n```\npartial"))
    }

    @Test
    fun `a reply parsed as it streams gives what parsing it whole gives, at every length (R1-M3)`() {
        val doc = "# Title\r\n\r\nFirst para\nstill first\n\n- one\n  - two\n\n   continued after a blank\n" +
            "3. three\n\n> quote\n> more\n\n```kt\nval a = 1\n\nval b = 2\n```\n\n---\n\nlast **bold** words\n\n\n" +
            "~~~\nunclosed\n\nfence"
        for (step in listOf(1, 3, 7)) {
            val streaming = Markdown.Incremental()
            var n = 0
            while (n <= doc.length) {
                val prefix = doc.substring(0, n)
                assertEquals("prefix $n (step $step)", Markdown.Incremental().parse(prefix), streaming.parse(prefix))
                n += step
            }
            assertEquals(Markdown.parse(doc), streaming.parse(doc))
        }
        // A different text (another turn) is parsed from its start.
        val p = Markdown.Incremental()
        p.parse("a\n\nb")
        assertEquals(listOf(MdBlock.Paragraph("x")), p.parse("x"))
    }

    @Test
    fun `a long block is drawn in pieces that stay put as it grows (R1-M3)`() {
        assertEquals(listOf("short"), Markdown.chunks("short"))
        val lines = (1..2_000).joinToString("\n") { "line $it of a long code block" }
        val pieces = Markdown.chunks(lines)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= Markdown.CHUNK })
        // Cut at line ends: the pieces joined by line breaks are the text.
        assertEquals(lines, pieces.joinToString("\n"))
        // Growing the text keeps every piece but the last.
        val grown = Markdown.chunks(lines + "\nmore")
        assertEquals(pieces.dropLast(1), grown.take(pieces.size - 1))
        // No line breaks: cut at a space; none at all: anywhere but a surrogate pair.
        val words = (1..1_000).joinToString(" ") { "word$it" }
        assertEquals(words, Markdown.chunks(words).joinToString(" "))
        val emoji = "x" + "\uD83D\uDE00".repeat(3_000)
        val cut = Markdown.chunks(emoji)
        assertEquals(emoji, cut.joinToString(""))
        assertTrue(cut.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test
    fun `a long reply is listed piece by piece, keys kept as it grows, and a short one is one row (R1-M3)`() {
        fun turn(id: String, text: String, status: String = ChatStatus.COMPLETED) =
            ChatTurn(id, "s", 0, "q", text, status, 0)
        val parsers = HashMap<String, Markdown.Incremental>()
        val short = turn("a", "## Hi\n\n- one\n- two")
        val whole = chatRows(listOf(short), null, parsers).single() as ChatRow.Whole
        assertEquals(3, whole.pieces.size)

        val para = "A paragraph of **text**.\n\n"
        val long = para.repeat(LAZY_REPLY_CHARS / para.length + 10)
        val live = AgentChats.Live("s", "b", long)
        val rows = chatRows(listOf(short, turn("b", "", ChatStatus.RUNNING)), live, parsers)
        assertTrue(rows[0] is ChatRow.Whole)
        assertTrue(rows[1] is ChatRow.Head)
        assertTrue(rows.last() is ChatRow.Foot && (rows.last() as ChatRow.Foot).running)
        assertTrue(rows.subList(2, rows.size - 1).all { it is ChatRow.Piece })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        // More text: every row already there keeps its key and content but the last piece.
        val grown = chatRows(listOf(short, turn("b", "", ChatStatus.RUNNING)), live.copy(text = long + "More"), parsers)
        assertEquals(rows.dropLast(1), grown.take(rows.size - 1))
    }

    @Test
    fun `a 780k-character reply parses on from its last blank line, not from its start (R1-M3)`() {
        val para = "Some **bold** text with `code` and a [link](https://x.y) and more words.\n\n"
        val reply = para.repeat(780_000 / para.length)
        val p = Markdown.Incremental()
        p.parse(reply)
        val whole = (1..5).minOf { val t = System.nanoTime(); Markdown.parse(reply); System.nanoTime() - t }
        var grown = reply
        val step = (1..5).minOf {
            grown += "more "
            val t = System.nanoTime()
            p.parse(grown)
            System.nanoTime() - t
        }
        assertTrue("whole ${whole / 1_000} µs, step ${step / 1_000} µs", step * 3 < whole)
    }

    @Test
    fun `rules and headings`() {
        assertEquals(listOf(MdBlock.Rule, MdBlock.Rule, MdBlock.Rule), Markdown.parse("---\n* * *\n   ___"))
        assertEquals(listOf(MdBlock.Heading(3, "Title")), Markdown.parse("### Title ##"))
        assertEquals(listOf(MdBlock.Paragraph("#hashtag\n####### seven")), Markdown.parse("#hashtag\n####### seven"))
    }

    @Test
    fun `list nesting is capped`() {
        assertEquals(Markdown.MAX_DEPTH, (Markdown.parse(" ".repeat(40) + "- deep").single() as MdBlock.Item).depth)
    }

    @Test
    fun `inline - markers go, text stays, snake_case is left alone`() {
        assertEquals("bold and italic and code", text("**bold** and *italic* and `code`"))
        assertEquals("my_var_name stays", text("my_var_name stays"))
        assertEquals("_emph_ word".replace("_", ""), text("_emph_ word"))
        assertEquals("2 * 3 * 4", text("2 * 3 * 4"))
        assertEquals("*not italic*", text("\\*not italic\\*"))
        assertEquals("unclosed **bold", text("unclosed **bold"))
    }

    @Test
    fun `a link shows its address, and an image is only its alt text and address`() {
        assertEquals("the docs (https://example.org/a)", text("[the docs](https://example.org/a)"))
        assertEquals("https://x.example", text("[https://x.example](https://x.example)"))
        assertEquals("logo (https://evil.example/t.png)", text("![logo](https://evil.example/t.png)"))
    }

    @Test
    fun `unmatched markers in a megabyte reply don't make rendering quadratic`() {
        val pathological = listOf(
            "[".repeat(500_000), "*a ".repeat(200_000), "`x".repeat(250_000), "**".repeat(250_000),
            "* ".repeat(250_000), "_a ".repeat(200_000), "#".repeat(500_000), "# " + "#".repeat(500_000),
            " ".repeat(500_000) + "- x", "- " + "a".repeat(1_000_000), "1. " + " ".repeat(500_000) + "x",
            "[a](" + "b".repeat(500_000),
            // Emphasis nested as deep as it goes.
            (0 until 50_000).joinToString("") { if (it % 2 == 0) "**x " else "_y " } +
                (0 until 50_000).joinToString("") { if (it % 2 == 0) "x_ " else "y** " },
        )
        for (reply in pathological) {
            val start = System.nanoTime()
            Markdown.parse(reply).forEach { if (it is MdBlock.Paragraph) Markdown.inline(it.text, palette) }
            val ms = (System.nanoTime() - start) / 1_000_000
            org.junit.Assert.assertTrue("${reply.take(4)}…: $ms ms", ms < 3_000)
        }
    }
}
