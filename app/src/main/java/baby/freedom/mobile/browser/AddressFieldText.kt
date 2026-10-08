package baby.freedom.mobile.browser

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Bounds the text the address bar's edit field is ever laid out with (#488).
 *
 * The field is single-line, and Compose lays a single-line field out at its
 * whole intrinsic width so it can scroll it. A page can navigate to an
 * address of up to Chromium's 2 MiB limit; at ~24 px a character that is
 * some 50 million px, past the 16,777,215 px a Compose layout may be, and
 * the app died the moment such an address committed, taking every tab with
 * it. So the field is handed at most [MAX_CHARS]: a longer address is shown
 * as its first [MAX_CHARS] − 1 characters and an ellipsis, as Chromium's own
 * omnibox shortens what it displays (`kMaxURLDisplayChars`).
 *
 * Nothing is lost by that. Go on the unedited field submits the whole
 * address ([submitted]), and Copy / Share on the capsule act on the tab's
 * address, not on the field. A paste or suggestion fill past the bound is
 * shortened the same way, ellipsis and all ([capped]), and Go — or the
 * suggestions' "Go to address" row ([picked]) — still submits the whole
 * text, and their "Search with …" row searches for it. Only an edit works
 * on the shortened text, because that is the text the user is editing.
 *
 * 8 KiB, the same bound saved tabs keep ([TabsState.MAX_SAVED_ADDRESS]),
 * is about 200,000 px of ordinary URL text: far inside the limit even for
 * the widest glyphs at a large font scale.
 */
internal object AddressFieldText {

    const val MAX_CHARS = TabsState.MAX_SAVED_ADDRESS

    private const val ELLIPSIS = "…"

    /** What the field shows for the tab's committed [address]. */
    fun shown(address: String): String =
        if (address.length <= MAX_CHARS) address else cut(address, MAX_CHARS - 1) + ELLIPSIS

    /**
     * What Go submits for [fieldText]: the whole [address] when the field
     * still holds exactly the shortened form of it, else what was typed.
     */
    fun submitted(fieldText: String, address: String): String =
        if (address.length > MAX_CHARS && fieldText == shown(address)) address else fieldText

    /**
     * An edit (a paste, a suggestion fill) kept within [MAX_CHARS], so a
     * pasted 2 MB string can't take the field past the limit either. A
     * longer edit is shortened as [shown] shortens an address — its head and
     * an ellipsis, so the cut is visible — with the cursor at its end. The
     * caller keeps the edit's whole text and hands it to [submitted] and
     * [picked], so Go submits what was pasted or picked, not the cut.
     */
    fun capped(value: TextFieldValue): TextFieldValue {
        if (value.text.length <= MAX_CHARS) return value
        val text = shown(value.text)
        return TextFieldValue(text, TextRange(text.length))
    }

    /**
     * Whether [edit] of the field's [current] text is lost to the cut. The
     * field stems from a shortened form — [standsFor], the whole text it was
     * seeded from or last pasted / filled with, is past the bound, so the
     * field holds (or was edited from) its head and the ellipsis — and the
     * edit works inside that text: it keeps the ellipsis where it was, in
     * front of or behind what changed, and grows the text past the bound.
     * [capped] would then cut the edit's tail away again: a character typed
     * at or after the ellipsis vanishes from the field, and one typed or
     * pasted earlier pushes the last shown character out, while the caller
     * would take the edit — dropped characters, a literal ellipsis and all —
     * as the field's whole text and have Go submit it (#517 R4-F1, R5-F2).
     * The field is full, as a field with a maximum length is: the caller
     * drops such an edit whole and keeps the text the field stands for.
     *
     * Only the length of the edit counts, not that [current] is exactly
     * [shown] of [standsFor]: a shortened form one char short of the bound
     * (its cut backed off a surrogate pair) takes one more keystroke, which
     * stays on screen and is what Go then submits, and the next one is
     * dropped like any other (#517 R5-F1). A paste over the whole field
     * replaces the ellipsis too and is shortened as a paste, not dropped.
     *
     * [selection] is the field's selection before the edit. A keystroke or
     * paste replaces at least that span, so the common head and tail are
     * held outside it: a paste over a selection that holds the ellipsis
     * replaces it, even when the pasted text happens to end in an ellipsis
     * of its own that the scan would otherwise match against the field's
     * (#517 R6-F1).
     */
    fun swallowed(current: String, edit: String, standsFor: String, selection: TextRange): Boolean {
        if (edit.length <= MAX_CHARS || standsFor.length <= MAX_CHARS) return false
        val ellipsis = current.lastIndexOf(ELLIPSIS)
        if (ellipsis < 0) return false
        // What the edit left of [current]: a common head, then a common tail
        // that doesn't overlap it.
        val room = minOf(current.length, edit.length)
        val replacedFrom = selection.min.coerceIn(0, current.length)
        val replacedTo = selection.max.coerceIn(replacedFrom, current.length)
        var head = 0
        while (head < minOf(room, replacedFrom) && current[head] == edit[head]) head++
        var tail = 0
        while (tail < minOf(room - head, current.length - replacedTo) &&
            current[current.length - 1 - tail] == edit[edit.length - 1 - tail]
        ) tail++
        return ellipsis < head || ellipsis >= current.length - tail
    }

    /**
     * What a suggestion row's [pick] submits while the field stands for
     * [fullText]. The panel is built from the field's shortened form, so
     * its two address rows name that cut text: the "Go to address" row
     * (the shortened text, trimmed) submits the whole text instead, and
     * the "Search with …" row (the shortened text's search URL under
     * [searchTemplate]) searches for the whole text, as Go on the
     * keyboard does (#517 R1-F1). Any other row is submitted as it is.
     */
    fun picked(pick: String, fullText: String, searchTemplate: String): String {
        if (fullText.length <= MAX_CHARS) return pick
        val cut = shown(fullText).trim()
        return when (pick) {
            cut -> fullText.trim()
            UrlParser.searchUrl(cut, searchTemplate) -> UrlParser.searchUrl(fullText, searchTemplate)
            else -> pick
        }
    }

    /**
     * The most a one- or two-line row (a suggestion's title and address,
     * a tab switcher card's title) is laid out with. Such a row shows its
     * text's head and ellipsises the rest, but `Text` still breaks the
     * whole string into lines first: an open tab on a 2 MiB address
     * blocked the main thread in `LineBreaker` as soon as a typed letter
     * matched it (#517 R3-F1). 1 KiB is several full lines even on a
     * landscape tablet, so the ellipsis falls where it would have anyway.
     */
    const val MAX_ROW_CHARS = 1024

    /** [text] as a row shows it: its head, within [MAX_ROW_CHARS], ellipsis marking a cut. */
    fun row(text: String): String =
        if (text.length <= MAX_ROW_CHARS) text else cut(text, MAX_ROW_CHARS - 1) + ELLIPSIS

    /** [text]'s first [max] chars, never ending on half a surrogate pair. */
    private fun cut(text: String, max: Int): String {
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }
}
