package baby.freedom.mobile.browser

/**
 * Unicode's `Bidi_Control` characters — the LRE…RLO embeddings and
 * overrides (U+202A–U+202E), the LRI…PDI isolates (U+2066–U+2069) and
 * the marks LRM, RLM and ALM (U+200E, U+200F, U+061C): the characters
 * that reorder the text after them.
 *
 * One list for every place the browser keeps them out of what it shows:
 * the address bar (resting label, edit field, Copy / Share), and the
 * resolver's refusal text ([baby.freedom.mobile.ens.EnsNormalize.cleanMessage]).
 * The error page's `shown()` uses the same property (`\p{Bidi_Control}`).
 * ZWJ / ZWNJ are not in it — emoji names need them.
 */
internal object BidiControls {

    fun isBidiControl(c: Char): Boolean =
        c == '؜' || c == '‎' || c == '‏' || c in '‪'..'‮' || c in '⁦'..'⁩'

    /**
     * [text] with every bidi control shown as U+FFFD — same length, so a
     * character offset into one is the same offset into the other.
     * Marked rather than dropped, so an address that carries one says
     * it is not what it seems instead of printing a cleaned-up spelling
     * nobody typed.
     */
    fun marked(text: String): String =
        if (text.none(::isBidiControl)) text
        else String(CharArray(text.length) { i -> text[i].let { if (isBidiControl(it)) '�' else it } })

    /** [text] with every bidi control removed. */
    fun stripped(text: String): String =
        if (text.none(::isBidiControl)) text else text.filterNot(::isBidiControl)
}
