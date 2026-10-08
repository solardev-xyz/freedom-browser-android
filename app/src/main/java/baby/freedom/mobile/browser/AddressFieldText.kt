package baby.freedom.mobile.browser

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
 * address, not on the field. Only an edit works on the shortened text,
 * because that is the text the user is editing.
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
     * pasted 2 MB string can't take the field past the limit either. The
     * selection is clamped to the kept text by [TextFieldValue] itself.
     */
    fun capped(value: TextFieldValue): TextFieldValue =
        if (value.text.length <= MAX_CHARS) value
        else TextFieldValue(cut(value.text, MAX_CHARS), value.selection, value.composition)

    /** [text]'s first [max] chars, never ending on half a surrogate pair. */
    private fun cut(text: String, max: Int): String {
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }
}
