package baby.freedom.mobile.l10n

import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The JVM stand-in for resources that every other test's text goes through (#280). */
class StringsTest {
    @Test
    fun `reads the English resources`() {
        assertEquals("Freedom", Strings.get(R.string.app_name))
        assertEquals("Search with Freedom", Strings.get(R.string.search_with_app))
    }

    @Test
    fun `said carries the app language and English, arguments in the matching one`() {
        inPseudoLanguage {
            val inner = Strings.said(R.string.send_no_gas_price)
            assertEquals(PseudoLanguage.MARK + "The network gave no usable gas price. Try again.", inner.text)
            assertEquals(Strings.english(R.string.send_no_gas_price), inner.english)
            val outer = Strings.said(R.string.send_ledger_failed, inner)
            // The English side has no app-language words in it, nested or not.
            assertEquals(false, outer.english.contains(PseudoLanguage.MARK))
            assertEquals(true, outer.text.contains(PseudoLanguage.MARK + inner.english))
            assertEquals(Said.of("node words"), Said("node words", "node words"))
        }
    }

    @Test
    fun `text held in state reads in the language of the moment`() {
        val held = Text.res(R.string.settings_updates_error_unreachable)
        val english = held.text
        inPseudoLanguage { assertEquals(PseudoLanguage.MARK + english, held.text) }
        assertEquals(english, held.text)
        assertEquals(Text.res(R.string.settings_updates_error_unreachable), held)
        assertNotEquals(Text.raw(english), held)
        assertEquals("as is", Text.raw("as is").text)
    }
}
