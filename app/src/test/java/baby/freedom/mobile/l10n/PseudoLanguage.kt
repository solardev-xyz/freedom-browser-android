package baby.freedom.mobile.l10n

/**
 * A stand-in translated build for tests (#313 R1-F1): every string read
 * in the app language starts with [MARK], while [Strings.english] stays
 * the English resources. A line that reaches a page or a peer with
 * [MARK] in it has leaked the user's language.
 */
internal object PseudoLanguage : StringSource {
    const val MARK = "[xx] "
    private val english = ResourceXmlStrings()

    override fun string(id: Int, vararg args: Any?): String = MARK + english.string(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any?): String = MARK + english.plural(id, count, *args)
    override fun english(id: Int, vararg args: Any?): String = english.string(id, *args)
}

/** Runs [block] with [Strings] in [PseudoLanguage], then back to English. */
internal fun <T> inPseudoLanguage(block: () -> T): T {
    Strings.useForTest(PseudoLanguage)
    try {
        return block()
    } finally {
        Strings.useForTest(null)
    }
}
