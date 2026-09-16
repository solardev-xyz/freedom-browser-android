package baby.freedom.mobile.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app theme: Material 3 Expressive on a fixed dark scheme.
 *
 * Dark is forced regardless of the OS setting — the home surface and
 * the wordmark are designed for it, and [HomeScreen] keys its logo
 * choice off the theme's background luminance rather than the system
 * flag for exactly that reason. Dynamic colour is deliberately not
 * used: the brand accents come from the wordmark (its teal and amber
 * dots) and shouldn't drift with the wallpaper.
 *
 * `MaterialExpressiveTheme` brings the expressive motion scheme
 * (springier transitions on every component) and the expressive
 * defaults for shape and type. It's still an opt-in API in Material 3
 * 1.5.0-alpha; the opt-in is confined to this file.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FreedomTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = FreedomDarkColors, content = content)
}

/** Teal dot of the wordmark (`ic_freedom_wordmark_*`). */
private val FreedomTeal = Color(0xFF00E9C4)

/** Amber dot of the wordmark. */
private val FreedomAmber = Color(0xFFFFB111)

/**
 * Dark scheme with the wordmark's teal as primary and amber as
 * secondary. Everything else — surfaces, outlines, error — keeps the
 * Material dark baseline so existing `surfaceVariant` cards and the
 * WebView frame colour still sit together.
 */
internal val FreedomDarkColors: ColorScheme = darkColorScheme(
    primary = FreedomTeal,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF70F7DE),
    inversePrimary = Color(0xFF006B5D),
    secondary = FreedomAmber,
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5D4200),
    onSecondaryContainer = Color(0xFFFFDEA3),
    tertiary = Color(0xFF9CCAFF),
    onTertiary = Color(0xFF003259),
    tertiaryContainer = Color(0xFF00497E),
    onTertiaryContainer = Color(0xFFD1E4FF),
)
