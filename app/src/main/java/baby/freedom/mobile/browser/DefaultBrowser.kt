package baby.freedom.mobile.browser

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/**
 * Settings → Default browser (#268): Freedom as the app that opens links
 * from other apps, through Android's browser role.
 */
internal object DefaultBrowser {
    val SECTION: String get() = Strings.get(R.string.settings_default_browser)
    val ROW_SET: String get() = Strings.get(R.string.settings_default_browser_set)
    val ROW_SET_SUBTITLE: String get() = Strings.get(R.string.settings_default_browser_set_subtitle)
    val ROW_IS: String get() = Strings.get(R.string.settings_default_browser_is)
    val ROW_IS_SUBTITLE: String get() = Strings.get(R.string.settings_default_browser_is_subtitle)

    /** Under [ROW_SET] once Android's own prompt came back without the role. */
    val DECLINED_LINE: String get() = Strings.get(R.string.settings_default_browser_declined)

    fun isDefault(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            roles.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    /** Android's own "Set Freedom as your default browser app?" prompt, or null where there is none. */
    fun requestIntent(context: Context): Intent? {
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
    }

    /** Android's Default apps page, where the browser can always be picked by hand. */
    fun openDefaultAppsSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** The section's one row for Settings search (the section title matches "default" and "browser" too). */
internal fun defaultBrowserRows(isDefault: Boolean) = listOf(
    if (isDefault) {
        settingsRow("default", DefaultBrowser.ROW_IS, DefaultBrowser.ROW_IS_SUBTITLE)
    } else {
        settingsRow("default", DefaultBrowser.ROW_SET, DefaultBrowser.ROW_SET_SUBTITLE)
    },
)

/**
 * Whether Freedom holds the browser role, re-read whenever the page
 * resumes (the user may have changed it in Android's settings meanwhile),
 * and the action for its row. Kept at the page's level, not inside the
 * row's lazy-list item, so the launcher and the "declined" state survive
 * the row scrolling out of view.
 */
internal class DefaultBrowserState(
    val isDefault: Boolean,
    val declined: Boolean,
    val onClick: () -> Unit,
)

@Composable
internal fun rememberDefaultBrowserState(): DefaultBrowserState {
    val context = LocalContext.current
    var isDefault by remember(context) { mutableStateOf(DefaultBrowser.isDefault(context)) }
    // Android's prompt came back without the role: declined, or Android
    // no longer shows it after repeated declines. The next tap goes to
    // the Default apps page instead of a prompt that may never appear.
    var declined by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        isDefault = DefaultBrowser.isDefault(context)
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = DefaultBrowser.isDefault(context)
        declined = !isDefault
    }
    return DefaultBrowserState(isDefault, declined && !isDefault) {
        val request = if (isDefault || declined) null else DefaultBrowser.requestIntent(context)
        if (request != null) {
            try {
                launcher.launch(request)
                return@DefaultBrowserState
            } catch (e: ActivityNotFoundException) {
                // No prompt on this device: the settings page below.
            }
        }
        DefaultBrowser.openDefaultAppsSettings(context)
    }
}

@Composable
internal fun DefaultBrowserSection(state: DefaultBrowserState) {
    SectionCard(title = stringResource(R.string.settings_default_browser)) {
        PageRow(
            title = stringResource(
                if (state.isDefault) R.string.settings_default_browser_is else R.string.settings_default_browser_set,
            ),
            subtitle = stringResource(
                if (state.isDefault) R.string.settings_default_browser_is_subtitle
                else R.string.settings_default_browser_set_subtitle,
            ),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.OpenInBrowser,
            thirdLine = if (state.declined) stringResource(R.string.settings_default_browser_declined) else null,
            onClick = state.onClick,
        )
    }
}
