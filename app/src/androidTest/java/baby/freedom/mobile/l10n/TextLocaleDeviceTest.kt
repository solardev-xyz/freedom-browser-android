package baby.freedom.mobile.l10n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #313 R1-F1 on a real `Resources`: with the phone in a language Freedom
 * has no translation for, the English text counts by English's rules, not
 * the phone's.
 */
@RunWith(AndroidJUnit4::class)
class TextLocaleDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun phoneIn(tag: String): Context = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) },
    )

    @Test
    @Suppress("DevicePluralRules") // the platform's own choice, to show what's being fixed
    fun englishTextCountsByEnglishRulesWhateverThePhonesLanguage() {
        val ja = phoneIn("ja-JP")
        // Android's own choice: Japanese has only `other`.
        assertEquals("1 matches", ja.resources.getQuantityString(R.plurals.browser_find_matches, 1, 1))
        assertEquals(Locale.US, TextLocale.of(ja.resources))
        assertEquals("1 match", TextLocale.plural(ja, R.plurals.browser_find_matches, 1, 1))
        // French puts 0 in `one`, Russian 21.
        assertEquals("0 matches", TextLocale.plural(phoneIn("fr-FR"), R.plurals.browser_find_matches, 0, 0))
        assertEquals("21 matches", TextLocale.plural(phoneIn("ru-RU"), R.plurals.browser_find_matches, 21, 21))
        assertEquals("21 minutes ago", TextLocale.plural(phoneIn("ru-RU"), R.plurals.radicle_viewer_minutes_ago, 21, 21))
        // And the count in the English line is in English's digits.
        assertEquals("17 matches", TextLocale.plural(phoneIn("fa-IR"), R.plurals.browser_find_matches, 17, 17))
        // An English phone keeps its own resources.
        val en = phoneIn("en-GB")
        assertEquals(true, TextLocale.resources(en) === en.resources)
        assertEquals("1 match", TextLocale.plural(en, R.plurals.browser_find_matches, 1, 1))
    }

    @Test
    fun composeCountsByTheTextsLanguage() {
        val ja = phoneIn("ja-JP")
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides ja,
                LocalConfiguration provides ja.resources.configuration,
            ) {
                Text(pluralText(R.plurals.browser_find_matches, 1, 1))
            }
        }
        compose.onNodeWithText("1 match").assertExists()
    }
}
