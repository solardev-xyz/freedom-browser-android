package baby.freedom.mobile.browser

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/**
 * Whether Settings → Appearance shows the Language row (#280): Android
 * 13+ ([sdkInt] 33), which has a per-app language page to open, and
 * only once the app ships more than one language ([localeCount], from
 * the generated locale config). An English-only build hides it; a
 * translation makes it appear with no code change.
 */
internal fun showLanguageRow(sdkInt: Int, localeCount: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && localeCount > 1

/** The app's own language (Android 13+ per-app language), as Settings → Appearance → Language shows it. */
internal object AppLanguage {
    private const val TAG = "AppLanguage"

    /** [showLanguageRow] for this device and build. */
    fun available(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.TIRAMISU) return false
        val count = runCatching { LocaleConfig(context).supportedLocales?.size() ?: 0 }.getOrDefault(0)
        return showLanguageRow(sdk, count)
    }

    /** The language picked for the app, by its own name ("Deutsch"), or "System default" for none. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun current(context: Context): String {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        val locale = locales?.takeIf { !it.isEmpty }?.get(0)
            ?: return Strings.get(R.string.settings_language_system_default)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** Android's per-app language page for Freedom. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no per-app language page", e)
        }
    }
}

/**
 * The Language row's subtitle — the app's current language — or `null`
 * while the row is hidden ([AppLanguage.available]). Re-read whenever
 * the page resumes: the user changes it on Android's page.
 */
@Composable
internal fun rememberAppLanguage(): String? {
    val context = LocalContext.current
    val available = remember(context) { AppLanguage.available(context) }
    if (!available || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    var current by remember(context) { mutableStateOf(AppLanguage.current(context)) }
    LifecycleResumeEffect(context) {
        current = AppLanguage.current(context)
        onPauseOrDispose {}
    }
    return current
}
