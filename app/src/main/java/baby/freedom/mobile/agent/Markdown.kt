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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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
    /**
     * The block's text in [Markdown.chunks], worked out once per block: a
     * reply that grows keeps its unchanged blocks (the same objects), so
     * only the one still growing is cut again.
     */
    val chunks: List<String>

    data class Paragraph(val text: String) : MdBlock {
        override val chunks by lazy(LazyThreadSafetyMode.NONE) { Markdown.chunks(text) }
    }
    data class Heading(val level: Int, val text: String) : MdBlock {
        override val chunks by lazy(LazyThreadSafetyMode.NONE) { Markdown.chunks(text) }
    }

    /** A list item: [marker] is `•` or its number, [depth] its nesting. */
    data class Item(val marker: String, val depth: Int, val text: String) : MdBlock {
        override val chunks by lazy(LazyThreadSafetyMode.NONE) { Markdown.chunks(text) }
    }
    data class Quote(val text: String) : MdBlock {
        override val chunks by lazy(LazyThreadSafetyMode.NONE) { Markdown.chunks(text) }
    }

    /** No inline Markdown in code: cut as plain text. */
    data class Code(val text: String) : MdBlock {
        override val chunks by lazy(LazyThreadSafetyMode.NONE) { Markdown.chunks(text, inline = false) }
    }
    data object Rule : MdBlock {
        override val chunks = listOf("")
    }
}

/**
 * What's drawn as one unit: [text], the whole of [block]'s text or its
 * [chunk]th piece ([last]: the block's last).
 */
internal data class MdPiece(val blockIndex: Int, val chunk: Int, val block: MdBlock, val text: String, val last: Boolean) {
    val key: String get() = "$blockIndex/$chunk"
}

internal object Markdown {
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)")

    /** The deepest list nesting drawn: past it, items line up with the deepest. */
    const val MAX_DEPTH = 3

    fun parse(text: String): List<MdBlock> = Incremental().parse(text)

    /**
     * Parses a reply as it grows: a streaming reply is shown again every
     * 80 ms, and parsing a long one from its start each time would cost
     * more with every piece. Everything before a blank line outside a
     * code fence parses the same whatever follows it, so the blocks up to
     * the last such line are kept and only the rest is parsed again.
     * Gives exactly what [parse] gives for the same text.
     */
    class Incremental {
        private var text = ""
        private var blocks: List<MdBlock> = emptyList()

        // A line start parsing can pick up from: [resumeCount] blocks came
        // before it, the last of them as it was then ([resumeLast]; a list
        // item can still take continuation lines after it).
        private var resumeAt = 0
        private var resumeCount = 0
        private var resumeLast: MdBlock? = null

        private var lastRaw: String? = null

        fun parse(raw: String): List<MdBlock> {
            // The same string again (a finished turn, each time the page redraws): nothing to do.
            if (raw === lastRaw) return blocks
            lastRaw = raw
            val t = raw.replace("\r\n", "\n")
            if (t == text) return blocks
            val out = ArrayList<MdBlock>()
            var from = 0
            if (resumeAt > 0 && t.length >= resumeAt && t.regionMatches(0, text, 0, resumeAt)) {
                for (k in 0 until resumeCount - 1) out += blocks[k]
                resumeLast?.let { out += it }
                from = resumeAt
            } else {
                resumeAt = 0
                resumeCount = 0
                resumeLast = null
            }
            parseInto(t, from, out) { at, count, last ->
                resumeAt = at
                resumeCount = count
                resumeLast = last
            }
            text = t
            blocks = out
            return out
        }
    }

    /**
     * Parses [text] from line start [start] on, adding to [out]; tells
     * [checkpoint] each line start where parsing could pick up again.
     */
    private fun parseInto(text: String, start: Int, out: ArrayList<MdBlock>, checkpoint: (Int, Int, MdBlock?) -> Unit) {
        val paragraph = ArrayList<String>()
        val quote = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(paragraph.joinToString("\n"))
            if (quote.isNotEmpty()) out += MdBlock.Quote(quote.joinToString("\n"))
            paragraph.clear()
            quote.clear()
        }
        // Lines as `split('\n')` gives them: [pos] a line's start, past the end once done.
        var pos = start
        var lineEnd = 0
        fun line(): String {
            val nl = text.indexOf('\n', pos)
            lineEnd = if (nl < 0) text.length else nl
            return text.substring(pos, lineEnd)
        }
        while (pos <= text.length) {
            val line = line()
            val terminated = lineEnd < text.length
            val fence = FENCE.find(line)
            if (fence != null) {
                flush()
                val marker = fence.groupValues[1]
                val code = ArrayList<String>()
                pos = lineEnd + 1
                // An unclosed fence (a reply still streaming) runs to the end.
                while (pos <= text.length) {
                    val l = line()
                    pos = lineEnd + 1
                    if (l.trimStart().startsWith(marker)) break
                    code += l
                }
                out += MdBlock.Code(code.joinToString("\n"))
                continue
            }
            when {
                line.isBlank() -> {
                    flush()
                    // A whole blank line: nothing after it changes what came before.
                    if (terminated) checkpoint(lineEnd + 1, out.size, out.lastOrNull())
                }
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
            pos = lineEnd + 1
        }
        flush()
    }

    /**
     * [blocks] as the pieces they're drawn in: one per block, or with
     * [chunked], a long block in [chunks]. A piece's [MdPiece.key] stays
     * the same while the reply grows, so a list keyed by it keeps what's
     * already drawn. Unchunked for a reply drawn whole: its pieces share
     * one selection, and a copy across a cut would lose its line break.
     */
    fun pieces(blocks: List<MdBlock>, chunked: Boolean): List<MdPiece> {
        val out = ArrayList<MdPiece>(blocks.size)
        blocks.forEachIndexed { b, block ->
            val text = when (block) {
                is MdBlock.Paragraph -> block.text
                is MdBlock.Heading -> block.text
                is MdBlock.Item -> block.text
                is MdBlock.Quote -> block.text
                is MdBlock.Code -> block.text
                MdBlock.Rule -> ""
            }
            val parts = if (chunked) block.chunks else listOf(text)
            parts.forEachIndexed { c, part -> out += MdPiece(b, c, block, part, c == parts.size - 1) }
        }
        return out
    }

    /** A block's text drawn as one [Text] up to about this long; past it, in pieces. */
    const val CHUNK = 2_000

    /**
     * [text] in pieces of about [CHUNK] characters, cut at a line end
     * where there is one (the line break is the cut), else after a space
     * (which stays at the end of its piece), else anywhere but inside a
     * surrogate pair. With [inline] (any block but code, whose pieces are
     * each parsed for inline Markdown on their own), never inside an
     * inline span (bold, italic, code, a link, an escape), so none is
     * split into two pieces that each show its markers as typed: a span
     * longer than a piece is cut at its end instead. Each piece is its
     * own [Text], so a long block that grows lays out only its last piece
     * again. A cut only moves when the text after it closes a span
     * across it, within [MAX_SPAN]; otherwise pieces already there stay
     * the same as the text grows.
     */
    fun chunks(text: String, inline: Boolean = true): List<String> {
        if (text.length <= CHUNK) return listOf(text)
        val out = ArrayList<String>()
        // The block's inline spans, [start, end) pairs, in one scan: a cut
        // is never inside one, so each piece's own scan matches this one.
        var spans = IntArray(0)
        var count = 0
        if (inline) {
            val finder = Finder(text)
            var i = 0
            while (i < text.length) {
                val e = spanEnd(text, i, finder)
                if (e < 0) {
                    i++
                } else {
                    if (count + 2 > spans.size) spans = spans.copyOf(maxOf(64, spans.size * 2))
                    spans[count++] = i
                    spans[count++] = e
                    i = e
                }
            }
        }
        // inside[k - start]: text[k] lies inside a span (after its first character).
        val inside = BooleanArray(CHUNK + 1)
        var sp = 0
        var start = 0
        while (text.length - start > CHUNK) {
            val limit = start + CHUNK
            inside.fill(false)
            while (sp < count && spans[sp + 1] <= start) sp += 2
            // The end of the span over [limit], if one runs past it.
            var over = -1
            var j = sp
            while (j < count && spans[j] < limit) {
                val e = spans[j + 1]
                for (k in spans[j] + 1 until minOf(e, limit + 1)) inside[k - start] = true
                if (e > limit) over = e
                j += 2
            }
            // The last [ch] in (start, limit] outside a span; −1 if none. Only
            // this piece's own window is scanned, never back to the text's start.
            fun last(ch: Char): Int {
                var k = limit
                while (k > start && (text[k] != ch || inside[k - start])) k--
                return if (k > start) k else -1
            }
            val line = last('\n')
            if (line > start) {
                out += text.substring(start, line)
                start = line + 1
                continue
            }
            val space = last(' ')
            if (space > start) {
                out += text.substring(start, space + 1)
                start = space + 1
                continue
            }
            val cut = when {
                over >= 0 -> over
                else -> {
                    // Anywhere outside a span, but not next to a marker (a piece
                    // starting `_` could open what mid-word wouldn't) or in a
                    // surrogate pair; failing that, anywhere but a surrogate pair.
                    fun clean(k: Int) = !inside[k - start] && !text[k - 1].isHighSurrogate() &&
                        (!inline || (text[k - 1] !in MARKERS && text[k] !in MARKERS))
                    var k = limit
                    while (k > start + 1 && !clean(k)) k--
                    when {
                        k > start + 1 -> k
                        text[limit - 1].isHighSurrogate() -> limit - 1
                        else -> limit
                    }
                }
            }
            if (cut >= text.length) break
            out += text.substring(start, cut)
            start = cut
        }
        out += text.substring(start)
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
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val end = spanEnd(text, i, finder)
            when {
                end < 0 -> append(c)
                c == '\\' -> append(text[i + 1])
                c == '`' -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = palette.code)) {
                    append(text, i + 1, end - 1)
                }
                (c == '*' || c == '_') && text.startsWith("$c$c", i) ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end - 2), palette, depth + 1)) }
                c == '*' || c == '_' ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end - 1), palette, depth + 1)) }
                else -> {
                    val open = if (c == '!') i + 1 else i
                    val close = finder.find("](", open + 1)
                    val label = text.substring(open + 1, close)
                    val address = text.substring(close + 2, end - 1).trim()
                    withStyle(SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)) {
                        append(inline(label.ifEmpty { address }, palette, depth + 1))
                    }
                    if (address.isNotEmpty() && address != label) {
                        withStyle(SpanStyle(color = palette.muted)) { append(" ($address)") }
                    }
                }
            }
            i = if (end < 0) i + 1 else end
        }
    }

    /**
     * Where the inline span opening at [i] of [text] ends (exclusive): an
     * escape, a code span, bold, italic or a link; −1 if none opens
     * there, and the character is shown as typed. The one test both
     * [inline] and [chunks] use, so a cut never lands inside a span.
     */
    private fun spanEnd(text: String, i: Int, finder: Finder): Int {
        val n = text.length
        val c = text[i]
        return when {
            c == '\\' -> if (i + 1 < n && text[i + 1] in ESCAPABLE) i + 2 else -1
            c == '`' -> finder.find("`", i + 1).let { if (it < 0) -1 else it + 1 }
            (c == '*' || c == '_') && text.startsWith("$c$c", i) ->
                finder.find("$c$c", i + 2).let { if (it <= i + 2) -1 else it + 2 }
            // `_` only around words, so snake_case stays as it is.
            (c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) &&
                i + 1 < n && !text[i + 1].isWhitespace() ->
                finder.closer(c, i + 1).let { if (it < 0) -1 else it + 1 }
            c == '[' || (c == '!' && i + 1 < n && text[i + 1] == '[') -> {
                val open = if (c == '!') i + 1 else i
                val close = finder.find("](", open + 1)
                val end = if (close < 0) -1 else finder.find(")", close + 2)
                if (close < 0 || end < 0 || (open + 1 until close).any { text[it] == '\n' }) -1 else end + 1
            }
            else -> -1
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

    /** Characters an inline span can start or end with. */
    private const val MARKERS = "\\`*_[]()!"

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!>|~"
}

/** [Markdown.Palette] from the theme. */
@Composable
internal fun markdownPalette(): Markdown.Palette {
    val colors = MaterialTheme.colorScheme
    return Markdown.Palette(code = colors.surfaceContainerHighest, link = colors.primary, muted = colors.onSurfaceVariant)
}

/**
 * A reply's [pieces] ([Markdown.pieces]) drawn as Markdown, in one
 * column. A reply still coming in is drawn again every 80 ms, so each
 * block keeps its styled text and layout while it's unchanged. A very
 * long reply is drawn piece by piece, its long blocks cut, in the chat's
 * own lazy list instead ([MdPieceView], `chatRows`).
 */
@Composable
internal fun MarkdownPieces(pieces: List<MdPiece>, modifier: Modifier = Modifier) {
    val palette = markdownPalette()
    Column(modifier) {
        for (piece in pieces) key(piece.key) { MdPieceView(piece, palette) }
    }
}

/** Space above a block that follows another; none between pieces of one block. */
private val BLOCK_GAP = 8.dp

/** One piece of a reply: a block, or one of a long block's chunks. */
@Composable
internal fun MdPieceView(piece: MdPiece, palette: Markdown.Palette, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val first = piece.chunk == 0
    val m = modifier.padding(top = if (first && piece.blockIndex > 0) BLOCK_GAP else 0.dp)
    when (val block = piece.block) {
        is MdBlock.Paragraph -> MdInline(piece.text, palette, type.bodyLarge, m)
        is MdBlock.Heading -> MdInline(
            piece.text,
            palette,
            (if (block.level <= 2) type.titleMedium else type.titleSmall).copy(fontWeight = FontWeight.SemiBold),
            m,
        )
        is MdBlock.Item -> Row(m.padding(start = (block.depth * 16).dp)) {
            // The marker on the first piece; later pieces line up under its text.
            Text(
                if (first) block.marker else "",
                style = type.bodyLarge,
                modifier = Modifier.widthIn(min = 20.dp).padding(end = 4.dp),
            )
            MdInline(piece.text, palette, type.bodyLarge, Modifier.weight(1f))
        }
        is MdBlock.Quote -> Row(m.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant))
            MdInline(piece.text, palette, type.bodyLarge.copy(color = colors.onSurfaceVariant), Modifier.padding(start = 10.dp))
        }
        is MdBlock.Code -> {
            // A long block's pieces join into one box: rounded at its ends only.
            val r = 8.dp
            val shape = RoundedCornerShape(
                topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
                bottomStart = if (piece.last) r else 0.dp, bottomEnd = if (piece.last) r else 0.dp,
            )
            Text(
                piece.text,
                style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = m
                    .fillMaxWidth()
                    .background(palette.code, shape)
                    .padding(start = 10.dp, end = 10.dp, top = if (first) 10.dp else 0.dp, bottom = if (piece.last) 10.dp else 0.dp),
            )
        }
        MdBlock.Rule -> HorizontalDivider(m)
    }
}

/** [text]'s inline Markdown, styled once while it's unchanged. */
@Composable
private fun MdInline(text: String, palette: Markdown.Palette, style: TextStyle, modifier: Modifier = Modifier) {
    val styled = remember(text, palette) { Markdown.inline(text, palette) }
    Text(styled, style = style, modifier = modifier)
}
