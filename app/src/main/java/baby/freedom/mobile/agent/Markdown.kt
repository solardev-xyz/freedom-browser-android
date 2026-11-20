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
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
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
                RULE.matches(line) -> {
                    flush()
                    out += MdBlock.Rule
                }
                HEADING.matches(line) -> {
                    flush()
                    val m = HEADING.find(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
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
    fun inline(text: String, palette: Palette): AnnotatedString = buildAnnotatedString {
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
                    val end = text.indexOf('`', i + 1)
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
                    val end = text.indexOf("$c$c", i + 2)
                    if (end <= i + 2) {
                        append(c)
                        i++
                    } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), palette)) }
                        i = end + 2
                    }
                }
                // `_` only around words, so snake_case stays as it is.
                (c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) &&
                    i + 1 < n && !text[i + 1].isWhitespace() -> {
                    val end = closing(text, c, i + 1)
                    if (end < 0) {
                        append(c)
                        i++
                    } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end), palette)) }
                        i = end + 1
                    }
                }
                (c == '[' || (c == '!' && i + 1 < n && text[i + 1] == '[')) -> {
                    val open = if (c == '!') i + 1 else i
                    val close = text.indexOf("](", open + 1)
                    val end = if (close < 0) -1 else text.indexOf(')', close + 2)
                    if (close < 0 || end < 0 || text.substring(open + 1, close).contains('\n')) {
                        append(c)
                        i++
                    } else {
                        val label = text.substring(open + 1, close)
                        val address = text.substring(close + 2, end).trim()
                        withStyle(SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)) {
                            append(inline(label.ifEmpty { address }, palette))
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

    /** Where a single [marker] closes after [from], not before whitespace; -1 if it doesn't. */
    private fun closing(text: String, marker: Char, from: Int): Int {
        var j = from
        while (j < text.length) {
            val k = text.indexOf(marker, j)
            if (k < 0) return -1
            val doubled = k + 1 < text.length && text[k + 1] == marker
            val wordAfter = marker == '_' && k + 1 < text.length && text[k + 1].isLetterOrDigit()
            if (!text[k - 1].isWhitespace() && !doubled && !wordAfter) return k
            j = k + if (doubled) 2 else 1
        }
        return -1
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
