package baby.freedom.mobile.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/**
 * Settings → Appearance → Theme (#269): follow the system's light/dark
 * setting, or keep the app light or dark whatever the system says. The
 * desktop browser's Settings → Theme offers the same three.
 *
 * The choice is applied as the app's own night mode
 * ([UiModeManager.setApplicationNightMode], Android 12+), not only as a
 * Compose colour scheme, so everything that reads the configuration
 * follows it together, live and without an Activity restart (the
 * manifest keeps `uiMode` in `configChanges`):
 *
 *  - the Compose chrome ([FreedomTheme]) and the system bar icons;
 *  - the window background the platform paints before the first
 *    Compose frame (`values`/`values-night` themes) — Android keeps the
 *    per-app mode across restarts, so a cold start is already right;
 *  - every WebView's `prefers-color-scheme`, which WebView takes from
 *    the app theme's `isLightTheme`. That covers Freedom's own pages
 *    (the error page, the Radicle viewer), which style themselves with
 *    that media query, and web content too — the same as Chrome, whose
 *    pages follow its own theme setting rather than the system's.
 *
 * Android 11 has no per-app night mode: there the Compose chrome
 * follows the choice ([isDark]) and pages keep following the system.
 */
enum class Appearance(val key: String, val label: String) {
    System("system", "System default"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    /** Whether to paint dark, given what the configuration says ([systemDark]). */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    /** The [UiModeManager] night mode that states this choice. */
    val nightMode: Int
        get() = when (this) {
            // AUTO as an app's own mode is "no override": follow the system.
            System -> UiModeManager.MODE_NIGHT_AUTO
            Light -> UiModeManager.MODE_NIGHT_NO
            Dark -> UiModeManager.MODE_NIGHT_YES
        }

    companion object {
        /** The choice stored as [key]; [System] for none or an unknown one. */
        fun fromKey(key: String?): Appearance = entries.firstOrNull { it.key == key } ?: System

        /**
         * Make [appearance] the app's own night mode. Android persists
         * it and reconfigures the running app (a `uiMode` change, no
         * restart); a no-op below Android 12.
         */
        fun apply(context: Context, appearance: Appearance) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            context.getSystemService(UiModeManager::class.java)
                ?.setApplicationNightMode(appearance.nightMode)
        }
    }
}
