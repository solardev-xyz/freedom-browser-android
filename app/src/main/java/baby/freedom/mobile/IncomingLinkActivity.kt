package baby.freedom.mobile

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import baby.freedom.mobile.browser.IncomingLinks

/**
 * Where links, shares and searches from other apps arrive (#268) — every
 * external intent filter is on this activity, none on [MainActivity].
 *
 * Another app usually starts a `VIEW` / `SEND` / `PROCESS_TEXT` activity
 * inside its *own* task, without `FLAG_ACTIVITY_NEW_TASK`. Aimed at
 * [MainActivity] directly, that would build a second browser — a fresh
 * set of tabs and WebViews — on top of the other app, instead of the tab
 * the user expects in the browser they have open. So this invisible
 * activity reads the intent ([IncomingLinks.from]), hands only the link
 * or query on to [MainActivity] in the browser's own task (its
 * `onNewIntent` when it's running, a cold start when it isn't), and
 * finishes. A Ledger plugged in (#319, the `.LedgerPlugIn` alias, when
 * the user turned it on) only brings the browser forward.
 */
class IncomingLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = IncomingLinks.from(intent)
        val forward = when {
            incoming != null -> IncomingLinks.toIntent(incoming)
            // A Ledger plugged in, with "Open Freedom when a Ledger is plugged in"
            // on (#319, the .LedgerPlugIn alias): the browser, with nothing to open.
            intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED -> Intent(Intent.ACTION_MAIN)
            else -> null
        }
        if (forward != null) {
            forward
                .setClass(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            startActivity(forward)
        }
        finish()
    }
}
