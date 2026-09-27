package baby.freedom.mobile.browser

// Audio indicator and per-tab mute (#91).
//
// Android WebView has no signal for "this page is making sound": no
// WebChromeClient / WebViewClient callback, and nothing in androidx.webkit
// (checked through 1.17). The platform's AudioPlaybackCallback is no help
// either — every WebView of the app plays through the one app process, and
// without MODIFY_AUDIO_ROUTING the configurations it reports are anonymised,
// so it can't even tell this app's playback from another app's, let alone
// one tab's from another's. So the indicator comes from the pages: every
// frame's document-start script ([bottomUiDetectorJs]) reports when its
// media elements become audible or fall silent, and [TabAudioFrames] folds
// the frames of one WebView into the tab's [BrowserState.playingAudio].
//
// Muting, on the other hand, is native: `WebViewCompat.setAudioMuted`
// (androidx.webkit 1.13+, feature `MUTE_AUDIO`) silences everything the
// WebView plays — Web Audio and detached elements included, which the
// indicator can't see.

/**
 * The audible frames of one WebView. [K] is whatever identifies a frame's
 * document to the listener (its `JavaScriptReplyProxy`); only [AUDIO_AUDIBLE]
 * frames are held, and a frame's own [AUDIO_SILENT] (sent when its media
 * stops, and on `pagehide`) drops it again.
 *
 * A silence can fail to arrive (`document.open()` erases the detector's
 * listeners, `pagehide` included), so the WebView also [clear]s the set
 * whenever a new main-frame document replaces the last one: on the new
 * document's ready, or at `onPageStarted` for a document no detector runs
 * in ([isHttpUrl]).
 */
internal class TabAudioFrames<K> {
    private val audible = HashSet<K>()

    /** Is any frame audible? */
    val any: Boolean get() = audible.isNotEmpty()

    /** Take [frame]'s report; returns the WebView-wide [any] after it. */
    fun onReport(frame: K, isAudible: Boolean): Boolean {
        if (isAudible) audible.add(frame) else audible.remove(frame)
        return any
    }

    /** Forget every frame: a new main-frame document replaced them all. */
    fun clear() = audible.clear()
}

/** Is [url] http(s), a document the detector runs in and reports from? */
internal fun isHttpUrl(url: String?): Boolean {
    val u = url ?: return false
    return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
}
