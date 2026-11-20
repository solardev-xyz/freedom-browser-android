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
 * that offers the Custom Tabs service and opens https links: the default
 * one if it does, else the first that does. Freedom offers none, so it's
 * never picked. If there's no such browser, or it can't be started, the
 * page opens as a plain link in another browser (the default one, if that
 * isn't Freedom); with none but Freedom, nothing is opened and the page
 * asks the user to open the address themselves.
 */
internal object SignInPage {
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
    private const val SERVICE_ACTION = "android.support.customtabs.action.CustomTabsService"

    /** Opens [url], already checked by [SubscriptionAuth.verificationUrl]. False if nothing could. */
    fun open(context: Context, url: String): Boolean {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
        val default = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val withService = pm.queryIntentServices(Intent(SERVICE_ACTION), 0).mapNotNull { it.serviceInfo?.packageName }
        val tab = Intent(view).putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        return attempts(context.packageName, browsers, default, withService)
            .any { start(context, if (it.customTab) tab else view, it.browser) }
    }

    /** One way to open the page: in [browser], as a Custom Tab or a plain link. */
    data class Attempt(val browser: String, val customTab: Boolean)

    /**
     * What [open] tries, in order, until one starts: a Custom Tab in a
     * browser that opens the link ([browsers]) and offers the service
     * ([withService]), the [default] one if it does; then the link as is in
     * each other browser, the default one first. Never in [own] (Freedom).
     */
    fun attempts(own: String, browsers: List<String>, default: String?, withService: List<String>): List<Attempt> {
        val others = browsers.filter { it != own }.distinct()
        val first = default?.takeIf { it in others }
        val customTabs = withService.filter { it in others }.let { s -> first?.takeIf { it in s } ?: s.firstOrNull() }
        val plain = (listOfNotNull(first) + others).distinct().filter { it != customTabs }
        return listOfNotNull(customTabs?.let { Attempt(it, customTab = true) }) + plain.map { Attempt(it, customTab = false) }
    }

    private fun start(context: Context, intent: Intent, browser: String): Boolean {
        val i = Intent(intent).setPackage(browser)
        if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
