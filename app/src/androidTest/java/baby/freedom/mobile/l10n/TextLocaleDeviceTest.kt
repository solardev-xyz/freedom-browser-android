package baby.freedom.mobile.l10n

import android.content.Context
import android.content.ContextWrapper
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
import baby.freedom.mobile.FreedomApplication
import baby.freedom.mobile.R
import baby.freedom.swarm.SwarmStrings
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
        // The form is English's, but the count is in the phone's digits, as
        // a plain `getString("%d …")` line beside it is (#313 R2-M1).
        val fa = phoneIn("fa-IR")
        val faDigits = String.format(Locale.forLanguageTag("fa-IR"), "%d", 17)
        assertEquals(true, faDigits != "17")
        assertEquals("$faDigits matches", TextLocale.plural(fa, R.plurals.browser_find_matches, 17, 17))
        // Identifiers stay in plain digits even as the plural's count or
        // beside it (#313 R4-M1, R4-M2): a list-update number, a chain's decimals.
        assertEquals(
            "Updated to version 42; the built-in EasyList stays, it's newer",
            TextLocale.plural(fa, R.plurals.settings_adblock_updated_builtin_newer, 1, 42, "EasyList"),
        )
        assertEquals(
            "xDAI (xDAI), 18 decimals",
            TextLocale.plural(fa, R.plurals.names_currency_detail, 18, "xDAI", "xDAI", 18),
        )
        // One copy per text language, whichever context asks (#313 R2-M2).
        assertEquals(true, TextLocale.resources(fa) === TextLocale.resources(ja))
        // An English phone keeps its own resources.
        val en = phoneIn("en-GB")
        assertEquals(true, TextLocale.resources(en) === en.resources)
        assertEquals("1 match", TextLocale.plural(en, R.plurals.browser_find_matches, 1, 1))
    }

    /**
     * #313 R3-M1: `:swarmnode`'s counts go through the same resolver the
     * app wires up in [FreedomApplication], so a Persian phone shows Persian
     * digits there too, in English's form, and English on the way out.
     */
    @Test
    fun swarmnodeCountsLikeTheApps() {
        val fa = phoneIn("fa-IR")
        // `init` takes the application context; stand in one set to Persian.
        val faApp = object : ContextWrapper(fa) {
            override fun getApplicationContext(): Context = this
        }
        val faDigits = String.format(Locale.forLanguageTag("fa-IR"), "%d", 17)
        try {
            // Exactly FreedomApplication.onCreate's wiring, on the Persian context.
            FreedomApplication.initSwarmStrings(faApp)
            val seeds = baby.freedom.swarm.R.plurals.swarmnode_radicle_candidate_seeds
            assertEquals("$faDigits candidate seeds", SwarmStrings.plural(seeds, 17, 17))
            assertEquals(TextLocale.plural(fa, seeds, 17, 17), SwarmStrings.plural(seeds, 17, 17))
            // Russian's `one` would claim 21; English's form, in the phone's digits.
            val ru = phoneIn("ru-RU")
            FreedomApplication.initSwarmStrings(object : ContextWrapper(ru) {
                override fun getApplicationContext(): Context = this
            })
            assertEquals("21 candidate seeds", SwarmStrings.plural(seeds, 21, 21))
            // What leaves the app stays en-US, digits included.
            assertEquals("17 candidate seeds", SwarmStrings.englishPlural(seeds, 17, 17))
        } finally {
            FreedomApplication.initSwarmStrings(app)
        }
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
