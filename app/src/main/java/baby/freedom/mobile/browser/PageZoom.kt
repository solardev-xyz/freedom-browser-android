package baby.freedom.mobile.browser

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import baby.freedom.mobile.data.SiteZoomStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Page zoom (#88): per-site zoom steps, reset, remembered per site.
 *
 * The same steps as the desktop browser's hamburger − / + controls:
 * 10 points a press, from [MIN] to [MAX], [DEFAULT] being "actual size".
 *
 * The level is applied as the WebView's text zoom
 * ([android.webkit.WebSettings.setTextZoom]) — the only reflowing zoom
 * WebView exposes. Its other zoom ([android.webkit.WebView.zoomBy]) is
 * the pinch scale: it doesn't reflow, a page's viewport meta can forbid
 * it, and every navigation resets it. Text zoom is set natively, so
 * nothing is injected into the page (no stylesheet, no marker for a
 * site to find), and it holds across navigations until we change it.
 */
object PageZoomLevels {
    const val DEFAULT = 100
    const val MIN = 25
    const val MAX = 500
    const val STEP = 10

    /** One step in or out from [current], clamped to [MIN]..[MAX]. */
    fun step(current: Int, zoomIn: Boolean): Int =
        (current + if (zoomIn) STEP else -STEP).coerceIn(MIN, MAX)

    /**
     * The WebView text zoom that shows a page at [level] percent.
     *
     * A WebView's own default text zoom isn't 100: it is the system font
     * scale (Settings → Display → Font size) times 100, so pages honour
     * the user's font-size / accessibility setting. The page level is
     * relative to that, the way desktop page zoom stacks on the default
     * font size — at [DEFAULT] a page renders exactly as it did before
     * page zoom existed, and every other level is off from that by the
     * level alone, not by the font scale as well.
     */
    fun textZoom(level: Int, fontScale: Float): Int =
        (level * fontScale).roundToInt().coerceAtLeast(1)

    /** How a level is written in the UI, e.g. "90%". */
    fun label(level: Int): String = "$level%"

    /**
     * Strings at least as wide as any [label] in [MIN]..[MAX], whatever
     * the font: every digit repeated to [MAX]'s digit count. Sizing the
     * menu's level slot to the widest of these keeps it one width at every
     * level and font scale, so − / + never shift under the user's finger
     * (a proportional font's digits needn't all be the same width, hence
     * all ten rather than a guess at the widest).
     */
    val widthProbes: List<String> =
        ('0'..'9').map { d -> d.toString().repeat(MAX.toString().length) + "%" }
}

/**
 * The key a page's zoom level is remembered under: the lower-cased
 * host of an http(s) URL, without scheme or port — Chromium's own rule
 * for zoom levels, so `http://` and `https://` of a site, or two of its
 * ports, share a level. A dweb page is keyed by the virtual host it is
 * served from (see [VirtualOrigin]), i.e. per content root / ENS name,
 * the same unit its storage is isolated by.
 *
 * Null for anything that isn't a site: the home sentinel
 * (`about:blank`), our error pages (`file:///android_asset/…`),
 * `data:`/`blob:` documents. Those always show at [PageZoomLevels.DEFAULT]
 * and the menu's zoom row is disabled on them.
 */
fun zoomSiteKey(url: String?): String? {
    val origin = permissionOriginKey(url) ?: return null
    val authority = origin.substringAfter("://")
    val host = if (authority.startsWith("[")) {
        // IPv6 literal: the port, if any, follows the closing bracket.
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }
    return host.takeIf { it.isNotEmpty() && it != "[]" }
}

/** The overflow menu's zoom row: −, +, and a tap on the percentage. */
enum class ZoomAction { In, Out, Reset }

/**
 * Remembered zoom levels, shared by every tab: changing a site's level
 * in one tab re-zooms every other tab showing that site, as Chrome does.
 *
 * [levels] is Compose state, so the menu's percentage and the WebViews'
 * text zoom (see [BrowserWebViewHost]) follow it directly. It is the
 * source of truth for the session: the store is read once, at startup,
 * and merged under anything the user changed before that read landed;
 * after that it is only written, in order, so a burst of presses can't
 * be overtaken by the store echoing an older value back.
 */
class PageZoom internal constructor(
    private val scope: CoroutineScope,
    private val load: suspend () -> Map<String, Int>,
    private val save: suspend (site: String, percent: Int?) -> Unit,
    private val clear: suspend () -> Unit,
) {
    private val levels = mutableStateMapOf<String, Int>()

    /** Sites changed this session, which the startup read must not override. */
    private val touched = HashSet<String>()

    /** Set by [clearAll]: the startup read, if still pending, is stale. */
    private var cleared = false
    private val writes = Mutex()

    init {
        scope.launch {
            val stored = load()
            if (cleared) return@launch
            for ((site, level) in stored) {
                if (site !in touched) levels[site] = level.coerceIn(PageZoomLevels.MIN, PageZoomLevels.MAX)
            }
        }
    }

    /**
     * Private tabs' levels (#86): what was changed in a private tab
     * this private session, kept in memory only and shared by the
     * private tabs alone. A site not changed there reads its remembered
     * level, as in Chrome's incognito.
     */
    private val privateLevels = mutableStateMapOf<String, Int>()

    /**
     * The level for [site] (a [zoomSiteKey]); [PageZoomLevels.DEFAULT]
     * for none. [private]: as a private tab sees it.
     */
    fun levelFor(site: String?, private: Boolean = false): Int {
        site ?: return PageZoomLevels.DEFAULT
        if (private) privateLevels[site]?.let { return it }
        return levels[site] ?: PageZoomLevels.DEFAULT
    }

    /**
     * Apply [action] to [site]'s level and remember the result — for
     * this private session only if [private].
     */
    fun apply(site: String, action: ZoomAction, private: Boolean = false) {
        val current = levelFor(site, private)
        val next = when (action) {
            ZoomAction.In -> PageZoomLevels.step(current, zoomIn = true)
            ZoomAction.Out -> PageZoomLevels.step(current, zoomIn = false)
            ZoomAction.Reset -> PageZoomLevels.DEFAULT
        }
        if (private) {
            // Kept even at the default: it overrides a remembered level.
            privateLevels[site] = next
            return
        }
        touched += site
        if (next == PageZoomLevels.DEFAULT) levels.remove(site) else levels[site] = next
        // Written even when the level didn't move (a press at a limit, a
        // reset at 100%): the startup read may not have landed yet, and
        // the file must still end up agreeing with what's on screen.
        val stored = next.takeIf { it != PageZoomLevels.DEFAULT }
        scope.launch { writes.withLock { save(site, stored) } }
    }

    /**
     * Forget every remembered level (part of "Clear cookies & site
     * data"): the file lists sites the user visited, so it goes with the
     * rest of the browsing trail. Open tabs drop back to the default.
     */
    fun clearAll() {
        cleared = true
        touched.clear()
        levels.clear()
        privateLevels.clear()
        scope.launch { writes.withLock { clear() } }
    }

    /** The private session is over (#86): its levels go with it. */
    fun clearPrivate() {
        privateLevels.clear()
    }

    companion object {
        @Volatile
        private var instance: PageZoom? = null

        fun get(context: Context): PageZoom =
            instance ?: synchronized(this) {
                instance ?: SiteZoomStore.get(context).let { store ->
                    PageZoom(MainScope(), store::load, store::set, store::clear)
                }.also { instance = it }
            }
    }
}
