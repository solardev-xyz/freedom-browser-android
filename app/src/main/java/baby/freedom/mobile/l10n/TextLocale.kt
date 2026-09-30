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
 * isn't the configuration's, the form is picked from the same resources
 * set to that language ([resources]), so the rules match the words. The
 * count and other arguments are still formatted in the configuration's
 * locale, exactly as a plain `getString("%d …")` line next to it is, so a
 * Persian phone shows the same digits in both (#313 R2-M1).
 */
object TextLocale {
    /** The language [res]'s text is in (`l10n_language`). */
    fun of(res: Resources): Locale = Locale.forLanguageTag(res.getString(R.string.l10n_language))

    /**
     * Resources to pick a plural form from: [context]'s own, or a copy set
     * to the language their text is in when that differs from the
     * configuration's. Only for choosing the form (`getQuantityText`);
     * format with [context]'s locale ([plural] does).
     */
    fun resources(context: Context): Resources {
        val res = context.resources
        val text = of(res)
        val first = res.configuration.locales.takeIf { !it.isEmpty }?.get(0)
        if (first != null && first.language == text.language) return res
        // Keyed on the text's language alone (#313 R2-M2): only the text and
        // its plural rules are read from these, and both follow the locale,
        // so an Activity and the Application (whose configurations differ in
        // window bounds and the like) share one copy instead of evicting
        // each other's on every call.
        synchronized(this) {
            return byLanguage.getOrPut(text) {
                val base = context.applicationContext ?: context
                base.createConfigurationContext(
                    Configuration(base.resources.configuration).apply { setLocales(LocaleList(text)) },
                ).resources
            }
        }
    }

    /** [resources] per text language: one or two languages a process ever sees. */
    private val byLanguage = HashMap<Locale, Resources>()

    /**
     * `getQuantityString` with the rules of the text's own language, its
     * [args] formatted in [context]'s locale like any other string's.
     */
    @Suppress("DevicePluralRules") // the form comes from resources set to the text's language
    fun plural(context: Context, @PluralsRes id: Int, count: Int, vararg args: Any?): String {
        val form = resources(context).getQuantityText(id, count).toString()
        if (args.isEmpty()) return form
        val locales = context.resources.configuration.locales
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
        return String.format(locale, form, *args)
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
