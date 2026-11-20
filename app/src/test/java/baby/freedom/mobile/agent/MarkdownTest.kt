package baby.freedom.mobile.agent

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
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
