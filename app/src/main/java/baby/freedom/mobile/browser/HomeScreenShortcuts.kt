package baby.freedom.mobile.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import baby.freedom.mobile.IncomingLinkActivity

/**
 * The main menu's **Add to Home screen** (#400): a launcher shortcut
 * pinned to a page, opening it in Freedom the way a link from another
 * app does — a `VIEW` of the address to [IncomingLinkActivity], which
 * reads it with [IncomingLinks.from] and hands it on to the browser's own
 * task. So a shortcut can ask for nothing a link couldn't.
 *
 * Only ever offered for a regular tab ([homeScreenShortcutTarget]).
 */
internal object HomeScreenShortcuts {
    /**
     * The adaptive icon's full square, px: 108 dp at xxxhdpi. The
     * launcher masks it to its own shape and shows the middle 72 dp.
     */
    private const val ICON_PX = 432

    /** Whether the launcher takes pinned shortcuts at all. */
    fun isSupported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /**
     * The icon: the site's cached favicon on white (decoded from
     * [favicon], PNG bytes), else the bookmark tiles' letter on their
     * colour ([initialChar], [tileAccentFor]). Pure drawing; fine off the
     * main thread.
     */
    fun icon(url: String, label: String, favicon: ByteArray?): Bitmap {
        val decoded = favicon?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }?.takeIf { it.width > 0 && it.height > 0 }
        val out = createBitmap(ICON_PX, ICON_PX)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (decoded != null) {
            canvas.drawColor(android.graphics.Color.WHITE)
            // 44 dp of the 108 dp square: well inside the 66 dp circle
            // every launcher mask keeps, like a favicon on Chrome's tiles.
            val side = ICON_PX * 44f / 108f
            val scale = side / maxOf(decoded.width, decoded.height)
            val w = decoded.width * scale
            val h = decoded.height * scale
            val dst = RectF((ICON_PX - w) / 2f, (ICON_PX - h) / 2f, (ICON_PX + w) / 2f, (ICON_PX + h) / 2f)
            canvas.drawBitmap(decoded, Rect(0, 0, decoded.width, decoded.height), dst, paint)
        } else {
            canvas.drawColor(tileAccentFor(url).toArgb())
            paint.color = android.graphics.Color.WHITE
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = ICON_PX * 0.3f
            val letter = initialChar(label, url).toString()
            val bounds = Rect()
            paint.getTextBounds(letter, 0, letter.length, bounds)
            canvas.drawText(letter, ICON_PX / 2f, ICON_PX / 2f - bounds.exactCenterY(), paint)
        }
        return out
    }

    /**
     * Asks the launcher to pin a shortcut to [url] (already checked by
     * [homeScreenShortcutTarget]). The launcher shows its own
     * confirmation; false where it refused to even ask.
     */
    fun request(context: Context, url: String, label: String, icon: Bitmap): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClass(context, IncomingLinkActivity::class.java)
        return runCatching {
            val info = ShortcutInfoCompat.Builder(context, homeScreenShortcutId(url))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        }.getOrDefault(false)
    }
}
