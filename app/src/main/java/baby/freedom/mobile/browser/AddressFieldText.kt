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
 * text. Only an edit works on the shortened text, because that is the
 * text the user is editing.
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
     * Whether [edit] of the field's [current] text is lost to the cut: the
     * field already holds a shortened form (its head and the ellipsis, at the
     * bound), the edit keeps that ellipsis and grows the text past the
     * bound, and [capped] would cut it straight back to [current] — a
     * character typed or pasted at the ellipsis or after it. Such an edit
     * changes nothing on screen, so it must change nothing behind it either:
     * the caller drops it whole and keeps the text the field stands for, or
     * Go would submit the cut, a literal ellipsis and the typed character
     * instead of the whole address (#517 R4-F1). A paste that replaces the
     * whole field is not caught by this even if it happens to begin with
     * the same head, unless it carries an ellipsis of its own past the cut.
     */
    fun swallowed(current: String, edit: String): Boolean =
        edit.length > MAX_CHARS &&
            current.length == MAX_CHARS && current.endsWith(ELLIPSIS) &&
            edit.indexOf(ELLIPSIS, MAX_CHARS - 1) >= 0 &&
            shown(edit) == current

    /**
     * What a suggestion row's [pick] submits while the field stands for
     * [fullText]: the whole text when the pick is the "Go to address" row
     * for the field's shortened form (which the row trims), else the pick.
     */
    fun picked(pick: String, fullText: String): String =
        if (fullText.length > MAX_CHARS && pick == shown(fullText).trim()) fullText.trim() else pick

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
