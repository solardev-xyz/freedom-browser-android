package baby.freedom.mobile.browser

import android.graphics.Bitmap

/**
 * What a `<video>` shows before its first frame when the page set no
 * `poster`.
 *
 * WebView asks `WebChromeClient.getDefaultVideoPoster()` for that
 * placeholder, and without an override draws its own built-in one: a
 * large grey play triangle. It shows on every video still loading,
 * autoplaying muted backgrounds and control-less custom players
 * included, where it reads as a broken page. Chrome, Firefox and Safari
 * leave the box blank until the first frame (and show a play button only
 * through the page's own `controls`), so the tab answers with a 1×1
 * fully transparent bitmap instead. A page's real `poster` attribute
 * never reaches this callback and is unaffected.
 *
 * One bitmap, created on first use and handed to every WebView: Chromium
 * only reads its pixels, and allocating one per call would churn a new
 * bitmap for each video element.
 */
internal object VideoPoster {
    val blank: Bitmap by lazy {
        // ARGB_8888 starts zero-filled: every pixel fully transparent.
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
}
