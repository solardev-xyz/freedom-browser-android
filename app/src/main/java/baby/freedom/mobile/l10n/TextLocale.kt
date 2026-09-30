package baby.freedom.mobile.l10n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.pluralText
import java.util.Locale

/**
 * The language the app's text is actually in, and resources that count in
 * it (#313 R1-F1).
 *
 * Android picks a `<plurals>` form with the plural rules of the phone's
 * (or per-app) language, `configuration.locales[0]`, whatever folder the
 * text itself came from. On a phone set to a language Freedom has no
 * `values-<lang>` for, the text is `values/`'s English but the rules are
 * that language's: Japanese has only `other` ("1 matches"), French puts 0
 * in `one` ("0 match"), Russian puts 21 in `one` ("21 minute ago").
 *
 * Each translation names its own language in `l10n_language`; when that
 * isn't the configuration's, [resources] are the same resources set to
 * that language, so the rules (and the digits a count is written in) match
 * the words.
 */
object TextLocale {
    /** The language [res]'s text is in (`l10n_language`). */
    fun of(res: Resources): Locale = Locale.forLanguageTag(res.getString(R.string.l10n_language))

    /**
     * [context]'s resources, or a copy set to the language their text is
     * in when that differs from the configuration's: for plural forms.
     */
    fun resources(context: Context): Resources {
        val res = context.resources
        val config = res.configuration
        val text = of(res)
        val first = config.locales.takeIf { !it.isEmpty }?.get(0)
        if (first != null && first.language == text.language) return res
        synchronized(this) {
            cached?.let { (key, value) -> if (key == config) return value }
            val fixed = context.createConfigurationContext(
                Configuration(config).apply { setLocales(LocaleList(text)) },
            ).resources
            cached = Configuration(config) to fixed
            return fixed
        }
    }

    /** Last configuration seen and its [resources]: one per process is all there is at a time. */
    private var cached: Pair<Configuration, Resources>? = null

    /** `getQuantityString` with the rules of the text's own language. */
    @Suppress("DevicePluralRules") // on resources set to that language
    fun plural(context: Context, @PluralsRes id: Int, count: Int, vararg args: Any?): String {
        val res = resources(context)
        return if (args.isEmpty()) res.getQuantityString(id, count) else res.getQuantityString(id, count, *args)
    }
}

/**
 * `pluralStringResource` with the plural rules of the language the text
 * is in ([TextLocale]), not the phone's. Use this, not Compose's own.
 */
@Composable
@ReadOnlyComposable
fun pluralText(@PluralsRes id: Int, count: Int, vararg args: Any): String {
    // Read so a configuration change (a language switch) redraws.
    LocalConfiguration.current
    return TextLocale.plural(LocalContext.current, id, count, *args)
}
