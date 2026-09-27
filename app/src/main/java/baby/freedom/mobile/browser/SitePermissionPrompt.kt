package baby.freedom.mobile.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The site-permission prompt (#81): "<site> wants to use your camera and
 * microphone", a "Remember this decision" box (ticked by default, as on
 * desktop), Block / Allow. Back or a tap outside is a dismissal — a
 * deny-once that counts towards the three-dismissals embargo.
 *
 * The site is always named in full: the title wraps rather than
 * ellipsising, since the tail of a host is exactly the part a spoof
 * would hide.
 *
 * Block / Allow — and dismissal (tap outside, Back) — ignore taps for
 * the first [PromptTapGuard.PROTECTION_MS] the prompt is on screen (the
 * buttons show as disabled meanwhile), so a page can't time its request
 * to catch a tap meant for the page.
 */
@Composable
fun SitePermissionPrompt(prompt: PermissionPrompt) {
    var remember by remember(prompt) { mutableStateOf(true) }
    val guard = remember(prompt) { PromptTapGuard(SystemClock::uptimeMillis) }
    var armed by remember(prompt) { mutableStateOf(false) }
    LaunchedEffect(prompt) {
        // Count from the first frame the prompt is actually drawn in,
        // not from composition.
        withFrameNanos { }
        guard.onShown()
        delay(guard.remainingMs())
        armed = true
    }
    val icon = when {
        SitePermission.CAMERA in prompt.permissions -> Icons.Filled.Videocam
        SitePermission.MICROPHONE in prompt.permissions -> Icons.Filled.Mic
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
                Text("wants to ${describePermissionRequest(prompt.permissions)}")
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { remember = !remember },
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(
                        "Remember this decision",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = armed,
                onClick = { if (guard.accepts()) prompt.respond(PromptAnswer.Allow(remember)) },
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(
                enabled = armed,
                onClick = { if (guard.accepts()) prompt.respond(PromptAnswer.Block(remember)) },
            ) {
                Text("Block")
            }
        },
    )
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
                p.androidPermissions.all { androidPermissionBlocked(activity, it) }
            }
            if (missing.isEmpty()) return@missing
            val what = missing.joinToString(" and ") { it.label.lowercase() }
            scope.launch {
                val r = snackbarHostState.showSnackbar(
                    message = "Freedom isn't allowed to use your $what. Turn it on in Android settings.",
                    actionLabel = "Settings",
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
        onDispose {
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
private fun noteAndroidRefusal(activity: Activity, permission: String) {
    if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) return
    val prefs = androidRefusals(activity)
    val seen = prefs.getStringSet(ANDROID_REFUSALS_KEY, emptySet()).orEmpty()
    if (permission in seen) return
    prefs.edit().putStringSet(ANDROID_REFUSALS_KEY, seen + permission).apply()
}

private fun androidPermissionBlocked(activity: Activity, permission: String): Boolean =
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
