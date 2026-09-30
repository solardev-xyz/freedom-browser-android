package baby.freedom.mobile.l10n

import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The JVM stand-in for resources that every other test's text goes through (#280). */
class StringsTest {
    @Test
    fun `reads the English resources`() {
        assertEquals("Freedom", Strings.get(R.string.app_name))
        assertEquals("Search with Freedom", Strings.get(R.string.search_with_app))
    }
}
