package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry

/**
 * The address field's clipboard ([androidx.compose.ui.platform.LocalClipboard]):
 * its own Copy and Cut write through [setClipEntry], and the text they
 * write is the field's buffer — the address with its bidi controls
 * intact, so Go submits what the tab holds. What goes on the clipboard
 * is what the field shows instead ([BidiControls.marked]), as the
 * long-press menu's Copy / Share hand on ([urlActionTarget]): a copied
 * `ens://‮moc.lapyap.eth` must not paste elsewhere reading
 * `hte.paypal.com`. Paste reads through untouched.
 */
internal class BidiMarkingClipboard(private val inner: Clipboard) : Clipboard {
    override val nativeClipboard: ClipboardManager get() = inner.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = inner.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val data = clipEntry?.clipData
        val text = data?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
        val marked = markedClipText(text)
        inner.setClipEntry(
            if (marked == null) clipEntry
            else ClipData.newPlainText(data?.description?.label, marked).toClipEntry(),
        )
    }
}

/**
 * [text] with its bidi controls marked, or `null` when it carries none
 * (the clip goes on the clipboard as it is).
 */
internal fun markedClipText(text: CharSequence?): String? {
    val plain = text?.toString() ?: return null
    val marked = BidiControls.marked(plain)
    return if (marked === plain) null else marked
}
