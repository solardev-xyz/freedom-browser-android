package baby.freedom.mobile.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Opens a subscription's sign-in page (#576) in a Custom Tab: the
 * provider's own page in the user's browser, with its own cookies and
 * password manager, never in a Freedom tab (whose pages can't see where
 * a sign-in came from, and must not hold the provider session).
 *
 * A Custom Tab is a `VIEW` intent with the Custom Tabs session extra
 * (what androidx.browser's `CustomTabsIntent` builds), sent to a browser
 * that offers the Custom Tabs service: the default one if it does, else
 * the first that does. Freedom offers none, so it's never picked. With
 * no such browser on the phone, the page opens as a plain link in
 * another browser (the default one, if that isn't Freedom); with none
 * but Freedom, nothing is opened and the page asks the user to open
 * the address themselves.
 */
internal object SignInPage {
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
    private const val SERVICE_ACTION = "android.support.customtabs.action.CustomTabsService"

    /** Opens [url], already checked by [SubscriptionAuth.verificationUrl]. False if nothing could. */
    fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        val browser = customTabsBrowser(context, intent) ?: otherBrowser(context, intent) ?: return false
        intent.setPackage(browser)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** A browser that isn't Freedom: the default one if it isn't, else the first. */
    private fun otherBrowser(context: Context, view: Intent): String? {
        val pm = context.packageManager
        val own = context.packageName
        val browsers = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != own }
            .distinct()
        if (browsers.isEmpty()) return null
        val default = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return default?.takeIf { it in browsers } ?: browsers.first()
    }

    private fun customTabsBrowser(context: Context, view: Intent): String? {
        val pm = context.packageManager
        val own = context.packageName
        val withService = pm.queryIntentServices(Intent(SERVICE_ACTION), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .filter { it != own }
            .distinct()
        if (withService.isEmpty()) return null
        val default = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return default?.takeIf { it in withService } ?: withService.first()
    }
}
