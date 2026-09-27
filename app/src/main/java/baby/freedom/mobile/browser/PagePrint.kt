package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView

/**
 * Print the current page (#89): hand [webView]'s document to the system
 * print framework, whose dialog offers every installed print service
 * plus "Save as PDF". Nothing here renders anything itself — the
 * WebView's own [WebView.createPrintDocumentAdapter] lays the page out
 * for paper, the way Chrome on Android does.
 *
 * [jobName] names the job in the spooler and is the default file name
 * "Save as PDF" suggests.
 */
internal fun printWebView(webView: WebView, jobName: String) {
    // PrintManager.print() insists on an Activity context (it starts the
    // print dialog as an activity) and throws on an application context.
    val activity = webView.context.findActivity() ?: return
    val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        ?: return
    val adapter = webView.createPrintDocumentAdapter(jobName)
    // A device whose print spooler is disabled throws here; losing the
    // menu action beats taking the browser down with it.
    runCatching { printManager.print(jobName, adapter, PrintAttributes.Builder().build()) }
}

/**
 * The print job's name: the page title when it has one, otherwise the
 * address the capsule shows (`vitalik.eth/docs`, `bzz://…`) — never the
 * loopback gateway URL the WebView fetched, which would make a
 * meaningless PDF file name.
 */
internal fun printJobName(title: String, addressBarText: String, url: String): String =
    title.trim().ifEmpty { urlActionTarget(addressBarText, url) ?: "Page" }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
