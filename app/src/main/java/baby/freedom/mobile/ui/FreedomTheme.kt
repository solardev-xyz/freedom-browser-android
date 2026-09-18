package baby.freedom.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The app theme: Material 3 Expressive on one of two wordmark-keyed
 * schemes, following the OS light/dark setting.
 *
 * Both schemes take their accents from the wordmark (its teal and amber
 * dots) rather than from the wallpaper — dynamic colour is still
 * deliberately not used, because the brand accents shouldn't drift with
 * whatever the user's home screen happens to be. The light scheme is the
 * same two hues brought down to Material's light tones (a tone-85 teal
 * on white would fail every contrast bar there is); everything else on
 * both sides keeps the Material baseline so the existing
 * `surfaceVariant` cards and the WebView frame colour still sit
 * together.
 *
 * Nothing downstream reads the *system* flag to decide what it is
 * painting on: [baby.freedom.mobile.browser.HomeScreen] picks its
 * wordmark by background luminance and the capsule keys its alpha and
 * shadow off [isLight] below, so an explicit `darkTheme` override (a
 * preview, a screenshot test) stays honest.
 *
 * `MaterialExpressiveTheme` brings the expressive motion scheme
 * (springier transitions on every component) and the expressive
 * defaults for shape and type. It's still an opt-in API in Material 3
 * 1.5.0-alpha; the opt-in is confined to this file.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FreedomTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) FreedomDarkColors else FreedomLightColors,
        content = content,
    )
}

/**
 * Is the active scheme a light one?
 *
 * Derived from the scheme itself rather than from `isSystemInDarkTheme`,
 * so anything that paints differently on light and dark (the capsule's
 * alpha and shadow) follows the colours it is actually drawing with —
 * the same rule [baby.freedom.mobile.browser.HomeScreen] already uses to
 * choose its wordmark.
 */
internal val ColorScheme.isLight: Boolean
    get() = surface.luminance() > 0.5f

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

/**
 * Light scheme: the same two wordmark hues, at the tones a light
 * surface can carry.
 *
 * The teal and the amber move to Material's tone-40 band and become the
 * *ink* (primary / secondary), while the wordmark's own bright tones
 * move to the containers — which is where the brand colour still reads
 * as itself on white. This matters beyond decoration: `primary` is what
 * the capsule's load trace and its Stop control are drawn in, so it has
 * to clear a 3:1 non-text contrast bar against a near-white capsule.
 * `inversePrimary` is the wordmark teal itself, so the pairing stays
 * symmetric with the dark scheme (whose `inversePrimary` is this
 * scheme's `primary`).
 */
internal val FreedomLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF00695B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF70F7DE),
    onPrimaryContainer = Color(0xFF002019),
    inversePrimary = FreedomTeal,
    secondary = Color(0xFF6F5300),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = FreedomAmber,
    onSecondaryContainer = Color(0xFF241A00),
    tertiary = Color(0xFF00629E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1E4FF),
    onTertiaryContainer = Color(0xFF001D33),
)
