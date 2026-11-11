package baby.freedom.mobile.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.runtime.Composable
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/*
 * Settings → Privacy & security → Passkeys (#524): where passkeys stand
 * on this phone ([Passkeys.availability]) and what the user has to set up
 * outside Freedom for them to work: a passkey manager picked in Android
 * settings, and Freedom on that manager's list of trusted browsers. A
 * manager that doesn't trust Freedom signs its own app origin instead of
 * the site's, so creating a passkey seems to work and signing in then
 * fails. The row opens Android's passkey-manager page.
 */

internal const val PASSKEYS_ROW_KEY = "passkeys"

internal val SECTION_PASSKEYS: String get() = Strings.get(R.string.settings_section_passkeys)

/** The row's subtitle: whether normal tabs offer passkeys here, and if not, why. */
internal fun passkeysSubtitle(availability: Passkeys.Availability): String = Strings.get(
    when (availability) {
        Passkeys.Availability.On -> R.string.settings_passkeys_on
        Passkeys.Availability.NeedsAndroid14 -> R.string.settings_passkeys_needs_android_14
        Passkeys.Availability.WebViewUnsupported -> R.string.settings_passkeys_webview_unsupported
    },
)

/** Settings search's index of the card. */
internal fun passkeyRows(availability: Passkeys.Availability) = listOf(
    settingsRow(
        PASSKEYS_ROW_KEY,
        Strings.get(R.string.settings_passkeys_title),
        passkeysSubtitle(availability),
        Strings.get(R.string.settings_passkeys_setup),
        *searchKeywords(R.string.settings_passkeys_keywords),
    ),
)

@Composable
internal fun PasskeysSection(visible: Set<Any>, availability: Passkeys.Availability, onOpen: () -> Unit) {
    SectionCard(title = SECTION_PASSKEYS) {
        if (PASSKEYS_ROW_KEY in visible) PageRow(
            title = Strings.get(R.string.settings_passkeys_title),
            subtitle = passkeysSubtitle(availability),
            // Wraps: the whole how-to is readable on a phone.
            thirdLine = Strings.get(R.string.settings_passkeys_setup),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Key,
            // Android's passkey-manager page exists from Android 14. It
            // opens on a WebView too old for passkeys as well: the
            // manager can be set up before the WebView updates.
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            onClick = onOpen,
        )
    }
}

/**
 * Android's *Passwords, passkeys & accounts* page, where the passkey
 * manager is picked (Android 14+).
 */
internal fun openPasskeyManagerSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_CREDENTIAL_PROVIDER, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Log.w("Passkeys", "no passkey-manager page", e)
    }
}
