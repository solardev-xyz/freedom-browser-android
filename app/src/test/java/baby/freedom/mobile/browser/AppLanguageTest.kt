package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `language row needs Android 13 and more than one language`() {
        // #280: English only today, so the row stays hidden everywhere.
        assertFalse(showLanguageRow(sdkInt = 36, localeCount = 1))
        assertFalse(showLanguageRow(sdkInt = 36, localeCount = 0))
        // A translation makes it appear, on Android 13+ only.
        assertTrue(showLanguageRow(sdkInt = 33, localeCount = 2))
        assertTrue(showLanguageRow(sdkInt = 37, localeCount = 5))
        assertFalse(showLanguageRow(sdkInt = 32, localeCount = 2))
        assertFalse(showLanguageRow(sdkInt = 30, localeCount = 5))
    }
}
