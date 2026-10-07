package baby.freedom.mobile.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * One way to answer the site-permission prompt ([SitePermissionPrompt]),
 * Chrome-style (#419): explicit choices rather than an Allow / Block
 * pair under a "Remember this decision" box. That box used to be ticked
 * by default, so a single tap on Allow was a permanent grant.
 */
internal enum class PermissionChoice {
    /** Allowed for this run only ([PermissionSession]): not stored, gone once Freedom is closed. */
    ALLOW_WHILE_VISITING,

    /** Allowed and remembered: a standing grant, revocable from Settings. */
    ALLOW_EVERY_VISIT,

    /** Blocked and remembered: the site isn't asked about again until the user removes it in Settings. */
    DONT_ALLOW,
}

/**
 * The choices the prompt offers, top to bottom. A private tab has nothing
 * to remember a decision in (#86) — its answer lasts the private session
 * whichever button it is — so there "every visit" isn't offered.
 */
internal fun permissionChoices(private: Boolean): List<PermissionChoice> =
    if (private) {
        listOf(PermissionChoice.ALLOW_WHILE_VISITING, PermissionChoice.DONT_ALLOW)
    } else {
        listOf(PermissionChoice.ALLOW_WHILE_VISITING, PermissionChoice.ALLOW_EVERY_VISIT, PermissionChoice.DONT_ALLOW)
    }

/** [choice] as the broker's answer; nothing is remembered from a private tab. */
internal fun permissionAnswer(choice: PermissionChoice, private: Boolean): PromptAnswer = when (choice) {
    PermissionChoice.ALLOW_WHILE_VISITING -> PromptAnswer.Allow(remember = false)
    PermissionChoice.ALLOW_EVERY_VISIT -> PromptAnswer.Allow(remember = !private)
    PermissionChoice.DONT_ALLOW -> PromptAnswer.Block(remember = !private)
}

/**
 * The site-permission prompt (#81): "<site> wants to use your camera and
 * microphone", then one button per [PermissionChoice] (#419) — "Allow
 * while visiting" (this run only, not remembered), "Allow every visit"
 * (remembered) and "Don't allow" (a remembered block); in a private tab
 * just "Allow" and "Don't allow", both lasting the private session. A
 * standing grant is always its own, explicit tap: no box decides it.
 * Back or a tap outside is a dismissal — a deny-once that counts towards
 * the three-dismissals embargo.
 *
 * The site is always named in full: the title wraps rather than
 * ellipsising, since the tail of a host is exactly the part a spoof
 * would hide.
 *
 * Every button — and dismissal (tap outside, Back) — ignores taps for
 * the first [PromptTapGuard.PROTECTION_MS] the prompt is on screen (the
 * buttons show as disabled meanwhile), so a page can't time its request
 * to catch a tap meant for the page; a press on a button that Android
 * marks as having passed through another app's window is dropped too.
 */
@Composable
fun SitePermissionPrompt(prompt: PermissionPrompt) {
    val tap = rememberArmedTapGuard(prompt)
    val guard = tap.guard
    val armed = tap.armed
    val icon = when {
        prompt.permissions.any { it is ExternalScheme } -> Icons.AutoMirrored.Filled.OpenInNew
        SitePermission.CAMERA in prompt.permissions -> Icons.Filled.Videocam
        SitePermission.MICROPHONE in prompt.permissions -> Icons.Filled.Mic
        SitePermission.MIDI in prompt.permissions -> Icons.Filled.Piano
        else -> Icons.Filled.LocationOn
    }
    AlertDialog(
        // Guarded like the buttons: a tap meant for the page that lands
        // outside the freshly shown prompt must not count towards the
        // three-dismissals embargo. (The prompt just stays up.)
        onDismissRequest = { if (guard.accepts()) prompt.respond(PromptAnswer.Dismiss) },
        icon = { Icon(icon, contentDescription = null) },
        title = {
            Text(
                permissionOriginDisplay(prompt.origin),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.library_permission_prompt_wants_to, describePermissionRequest(prompt.permissions)))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        // A private tab's answer lasts the private session
                        // only (#86): there's nothing to remember it in.
                        if (prompt.private) {
                            R.string.library_permission_prompt_private
                        } else {
                            R.string.library_permission_prompt_visiting_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ObscuredTapNotice(tap)
            }
        },
        // Stacked, full width, in the button row's place: three labels
        // this long don't fit side by side, and a wrapped row would put
        // them in no clear order.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                for (choice in permissionChoices(prompt.private)) {
                    TextButton(
                        enabled = armed,
                        onClick = { if (guard.accepts()) prompt.respond(permissionAnswer(choice, prompt.private)) },
                        modifier = Modifier.heightIn(min = 48.dp).protectedPress(tap),
                    ) {
                        Text(
                            stringResource(permissionChoiceLabel(choice, prompt.private)),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        },
    )
}

private fun permissionChoiceLabel(choice: PermissionChoice, private: Boolean): Int = when (choice) {
    // In a private tab this is the only Allow, and "while visiting" would
    // undersell it: it lasts the private session (the note above says so).
    PermissionChoice.ALLOW_WHILE_VISITING ->
        if (private) R.string.common_allow else R.string.library_permission_prompt_allow_visiting
    PermissionChoice.ALLOW_EVERY_VISIT -> R.string.library_permission_prompt_allow_always
    PermissionChoice.DONT_ALLOW -> R.string.library_permission_prompt_dont_allow
}

/**
 * Plugs [broker] into the Activity: Android's runtime-permission dialog
 * (asked for only once a site has been allowed), and a snackbar with a
 * shortcut to the app's system settings when Android has refused the
 * app a permission the user just allowed a site to use *and won't ask
 * for it again* ([androidPermissionBlockedInSettings]). A refusal that a
 * re-request would simply ask about again — a first "Don't allow", or
 * backing out of the system dialog — gets no snackbar.
 */
@Composable
fun SitePermissionAndroidBridge(
    broker: SitePermissionBroker,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result = remember { arrayOfNulls<CompletableDeferred<Map<String, Boolean>>>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val activity = context.findActivity()
        if (activity != null) {
            for ((permission, ok) in granted) {
                if (!ok) noteAndroidRefusal(activity, permission)
            }
        }
        result[0]?.complete(granted)
        result[0] = null
    }
    DisposableEffect(broker, launcher) {
        broker.requestAndroidPermissions = { permissions ->
            val deferred = CompletableDeferred<Map<String, Boolean>>()
            result[0] = deferred
            launcher.launch(permissions.toTypedArray())
            deferred.await()
        }
        broker.onAndroidPermissionMissing = missing@{ refused ->
            val activity = context.findActivity() ?: return@missing
            val missing = refused.filter { p ->
                p.androidPermissions.isNotEmpty() && p.androidPermissions.all { androidPermissionBlocked(activity, it) }
            }
            if (missing.isEmpty()) return@missing
            val what = joinWithAnd(missing.map(::permissionNoun)) ?: return@missing
            scope.launch {
                val r = snackbarHostState.showSnackbar(
                    message = Strings.get(R.string.library_permission_android_refused, what),
                    actionLabel = Strings.get(R.string.library_permission_android_settings),
                    duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }
        }
        broker.onNoAppForLink = { scheme ->
            scope.launch {
                snackbarHostState.showSnackbar(Strings.get(R.string.library_permission_no_app_for_link, scheme.label))
            }
        }
        broker.onProtectedMediaRefused = {
            scope.launch {
                snackbarHostState.showSnackbar(PROTECTED_MEDIA_NOTICE, duration = SnackbarDuration.Long)
            }
        }
        onDispose {
            broker.onProtectedMediaRefused = null
            broker.onNoAppForLink = null
            broker.requestAndroidPermissions = null
            broker.onAndroidPermissionMissing = null
            // A dialog result that will never arrive must not strand
            // the request waiting on it.
            result[0]?.complete(emptyMap())
            result[0] = null
        }
    }
}

private const val ANDROID_REFUSALS_PREFS = "site_permissions_android"
private const val ANDROID_REFUSALS_KEY = "denied_before"

private fun androidRefusals(context: Context) =
    context.applicationContext.getSharedPreferences(ANDROID_REFUSALS_PREFS, Context.MODE_PRIVATE)

/**
 * Remember that Android has seen the user deny [permission] (the
 * rationale flag is up), so a later silent refusal can be read as "denied
 * for good" rather than "dialog backed out of".
 */
internal fun noteAndroidRefusal(activity: Activity, permission: String) {
    if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) return
    val prefs = androidRefusals(activity)
    val seen = prefs.getStringSet(ANDROID_REFUSALS_KEY, emptySet()).orEmpty()
    if (permission in seen) return
    prefs.edit().putStringSet(ANDROID_REFUSALS_KEY, seen + permission).apply()
}

internal fun androidPermissionBlocked(activity: Activity, permission: String): Boolean =
    androidPermissionBlockedInSettings(
        rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission),
        deniedBefore = permission in
            androidRefusals(activity).getStringSet(ANDROID_REFUSALS_KEY, emptySet()).orEmpty(),
    )

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
