package baby.freedom.mobile.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/*
 * A model's reply in Markdown (#574), drawn natively: paragraphs,
 * headings, lists, quotes, code blocks, rules, and bold, italic, code and
 * links inline. Nothing is fetched (an image is its alt text and
 * address) and nothing opens: a link shows its text with its address
 * after it, so a reply can't hide where it points, and the user copies
 * it if they want it. Everything wraps; nothing scrolls sideways or is
 * cut off on a narrow screen.
 */

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock

    /** A list item: [marker] is `•` or its number, [depth] its nesting. */
    data class Item(val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

internal object Markdown {
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)")

    /** The deepest list nesting drawn: past it, items line up with the deepest. */
    const val MAX_DEPTH = 3

    fun parse(text: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        val quote = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(paragraph.joinToString("\n"))
            if (quote.isNotEmpty()) out += MdBlock.Quote(quote.joinToString("\n"))
            paragraph.clear()
            quote.clear()
        }
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            if (fence != null) {
                flush()
                val marker = fence.groupValues[1]
                val code = ArrayList<String>()
                i++
                // An unclosed fence (a reply still streaming) runs to the end.
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                out += MdBlock.Code(code.joinToString("\n"))
                i++
                continue
            }
            when {
                line.isBlank() -> flush()
                isRule(line) -> {
                    flush()
                    out += MdBlock.Rule
                }
                heading(line) != null -> {
                    flush()
                    out += heading(line)!!
                }
                line.trimStart().startsWith(">") -> {
                    if (paragraph.isNotEmpty()) flush()
                    quote += line.trimStart().removePrefix(">").removePrefix(" ")
                }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.find(line)!!
                    out += MdBlock.Item("•", depthOf(m.groupValues[1]), m.groupValues[2])
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.find(line)!!
                    out += MdBlock.Item(m.groupValues[2] + ".", depthOf(m.groupValues[1]), m.groupValues[3])
                }
                else -> {
                    // A line straight after a list item continues it.
                    val last = out.lastOrNull()
                    if (paragraph.isEmpty() && quote.isEmpty() && last is MdBlock.Item && line.startsWith(" ")) {
                        out[out.size - 1] = last.copy(text = last.text + "\n" + line.trim())
                    } else {
                        if (quote.isNotEmpty()) flush()
                        paragraph += line
                    }
                }
            }
            i++
        }
        flush()
        return out
    }

    // Plain scans, not regular expressions: a reply line can be a
    // megabyte of `*`, and a backtracking pattern recurses per character.

    /** `---`, `***` or `___` (spaces between allowed), up to three spaces in. */
    private fun isRule(line: String): Boolean {
        val lead = line.length - line.trimStart().length
        val body = line.trim()
        if (lead > 3 || body.isEmpty()) return false
        val c = body[0]
        if (c != '-' && c != '*' && c != '_') return false
        var count = 0
        for (ch in body) {
            when {
                ch == c -> count++
                ch == ' ' || ch == '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    /** `#`–`######`, a space, then the text, without closing `#`s. */
    private fun heading(line: String): MdBlock.Heading? {
        var level = 0
        while (level < line.length && line[level] == '#') level++
        if (level !in 1..6 || level >= line.length || !line[level].isWhitespace()) return null
        val text = line.substring(level).trim().trimEnd('#').trimEnd()
        return MdBlock.Heading(level, text)
    }

    private fun depthOf(indent: String): Int =
        (indent.replace("\t", "    ").length / 2).coerceIn(0, MAX_DEPTH)

    /** Colors for [inline]. */
    data class Palette(val code: Color, val link: Color, val muted: Color)

    /**
     * [text]'s inline Markdown as styled text: `**bold**`, `*italic*`,
     * `` `code` ``, `[text](address)` and `![alt](address)` (shown as
     * text followed by its address), with `\` escapes. Unmatched markers
     * stay as typed.
     */
    fun inline(text: String, palette: Palette): AnnotatedString = inline(text, palette, 0)

    /** Emphasis nested past this many levels is shown as typed, so no reply can recurse deep. */
    private const val MAX_NESTING = 8

    private fun inline(text: String, palette: Palette, depth: Int): AnnotatedString = buildAnnotatedString {
        if (depth > MAX_NESTING) {
            append(text)
            return@buildAnnotatedString
        }
        val finder = Finder(text)
        fun find(needle: String, from: Int) = finder.find(needle, from)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < n && text[i + 1] in ESCAPABLE -> {
                    append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = find("`", i + 1)
                    if (end < 0) {
                        append(c)
                        i++
                    } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = palette.code)) {
                            append(text, i + 1, end)
                        }
                        i = end + 1
                    }
                }
                (c == '*' || c == '_') && text.startsWith("$c$c", i) -> {
                    val end = find("$c$c", i + 2)
                    if (end <= i + 2) {
                        append(c)
                        i++
                    } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), palette, depth + 1)) }
                        i = end + 2
                    }
                }
                // `_` only around words, so snake_case stays as it is.
                (c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) &&
                    i + 1 < n && !text[i + 1].isWhitespace() -> {
                    val end = finder.closer(c, i + 1)
                    if (end < 0) {
                        append(c)
                        i++
                    } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end), palette, depth + 1)) }
                        i = end + 1
                    }
                }
                (c == '[' || (c == '!' && i + 1 < n && text[i + 1] == '[')) -> {
                    val open = if (c == '!') i + 1 else i
                    val close = find("](", open + 1)
                    val end = if (close < 0) -1 else find(")", close + 2)
                    if (close < 0 || end < 0 || text.substring(open + 1, close).contains('\n')) {
                        append(c)
                        i++
                    } else {
                        val label = text.substring(open + 1, close)
                        val address = text.substring(close + 2, end).trim()
                        withStyle(SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)) {
                            append(inline(label.ifEmpty { address }, palette, depth + 1))
                        }
                        if (address.isNotEmpty() && address != label) {
                            withStyle(SpanStyle(color = palette.muted)) { append(" ($address)") }
                        }
                        i = end + 1
                    }
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    /** How far after its opening a marker's close may be. */
    const val MAX_SPAN = 4_000

    /**
     * Finds closing markers in one text, each needle's text scanned once
     * overall: a reply can be a megabyte of model-written text, and a
     * plain search per unmatched `[` or `*` would make every re-render
     * quadratic in it.
     */
    private class Finder(val text: String) {
        private val last = HashMap<String, IntArray>()

        /** The first [needle] at or after [from] (−1: none), within [MAX_SPAN]. */
        fun find(needle: String, from: Int): Int {
            val c = last.getOrPut(needle) { intArrayOf(-1, -2) }
            // [from, c[1]] had no other occurrence before c[1] (or none at all, −1).
            val found = if (c[0] in 0..from && (c[1] == -1 || c[1] >= from)) {
                c[1]
            } else {
                text.indexOf(needle, from).also { c[0] = from; c[1] = it }
            }
            return if (found < 0 || found - from > MAX_SPAN) -1 else found
        }

        private val closers = HashMap<Char, IntArray>()

        /**
         * Where a single [marker] (`*` or `_`) at or after [from] can
         * close an emphasis, within [MAX_SPAN]; −1 if none. Whether a
         * position can close doesn't depend on where the emphasis opened,
         * so it's cached the same way as [find].
         */
        fun closer(marker: Char, from: Int): Int {
            val c = closers.getOrPut(marker) { intArrayOf(-1, -2) }
            val found = if (c[0] in 0..from && (c[1] == -1 || c[1] >= from)) {
                c[1]
            } else {
                var k = text.indexOf(marker, from)
                while (k >= 0 && !closes(marker, k)) k = text.indexOf(marker, k + 1)
                c[0] = from
                c[1] = k
                k
            }
            return if (found < 0 || found - from > MAX_SPAN) -1 else found
        }

        /** Not after whitespace or another [marker], not before one, and `_` not inside a word. */
        private fun closes(marker: Char, k: Int): Boolean {
            if (k == 0) return false
            val before = text[k - 1]
            val after = text.getOrNull(k + 1)
            if (before.isWhitespace() || before == marker || after == marker) return false
            return !(marker == '_' && after != null && after.isLetterOrDigit())
        }
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!>|~"
}

/** [text] drawn as Markdown. */
@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    val colors = MaterialTheme.colorScheme
    val palette = Markdown.Palette(
        code = colors.surfaceContainerHighest,
        link = colors.primary,
        muted = colors.onSurfaceVariant,
    )
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) when (block) {
            is MdBlock.Paragraph -> Text(Markdown.inline(block.text, palette), style = type.bodyLarge)
            is MdBlock.Heading -> Text(
                Markdown.inline(block.text, palette),
                style = if (block.level <= 2) type.titleMedium else type.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                Text(block.marker, style = type.bodyLarge, modifier = Modifier.widthIn(min = 20.dp).padding(end = 4.dp))
                Text(Markdown.inline(block.text, palette), style = type.bodyLarge, modifier = Modifier.weight(1f))
            }
            is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant))
                Text(
                    Markdown.inline(block.text, palette),
                    style = type.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            is MdBlock.Code -> Text(
                block.text,
                style = type.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.code, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
            MdBlock.Rule -> HorizontalDivider()
        }
    }
}
