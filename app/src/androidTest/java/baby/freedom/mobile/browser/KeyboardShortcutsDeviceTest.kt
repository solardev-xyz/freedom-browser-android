package baby.freedom.mobile.browser

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.MainActivity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardware-keyboard shortcuts (#270) in the real browser: key events
 * injected into [MainActivity], sent as a hardware keyboard sends them.
 * Reserved shortcuts (Ctrl+T/W/L, tab switching) act without the page
 * ever seeing them, even from its text field; the rest reach a focused
 * text field first and act only when the page didn't use them — its
 * `preventDefault()` keeps the key — and act at once with focus on the
 * page but not in a field.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardShortcutsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                return when (path) {
                    "/one", "/two" -> MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setHeader("Cache-Control", "no-store")
                        .setBody(page(path.removePrefix("/")))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /**
     * A text field, and every keydown the page sees, as "C-S-A-key" —
     * kept in the tab's session storage under the page's name, so a
     * reload or a Back doesn't lose what the page saw before it.
     */
    private fun page(name: String) = """
        <!doctype html><meta name=viewport content="width=device-width"><title>$name</title>
        <input id=f style="font-size:20px"> <a id=two href="/two" style="font-size:20px">two</a>
        <div id=fs style="font-size:20px;background:#08f"><span id=go onclick="fs.requestFullscreen()">fullscreen</span>
          <input id=g style="font-size:20px"></div>
        <p style="height:3000px">$name</p>
        <script>
          window.__keys = JSON.parse(sessionStorage.getItem('$name') || '[]');
          window.__prevent = false;
          document.addEventListener('keydown', function (e) {
            __keys.push((e.ctrlKey ? 'C-' : '') + (e.shiftKey ? 'S-' : '') + (e.altKey ? 'A-' : '') + e.key);
            sessionStorage.setItem('$name', JSON.stringify(__keys));
            if (__prevent && e.ctrlKey) e.preventDefault();
          });
          document.addEventListener('keyup', function (e) {
            var ups = JSON.parse(sessionStorage.getItem('$name-up') || '[]');
            ups.push(e.key);
            sessionStorage.setItem('$name-up', JSON.stringify(ups));
          });
        </script>
    """.trimIndent()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        // `getLoopbackAddress()` is ::1 on Android; the page is loaded from 127.0.0.1.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        server.shutdown()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun launch(path: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url(path)))
            .setClass(instrumentation.targetContext, MainActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        // Resolved here, never first inside an onActivity block.
        tabs.hashCode()
    }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        val result = AtomicReference<T>()
        scenario.onActivity { result.set(block(it)) }
        return result.get()
    }

    private val tabs: TabsState by lazy { onActivity { ViewModelProvider(it)[TabsSession::class.java].tabs } }

    private fun waitFor(
        what: String,
        timeoutMs: Long = 20_000,
        detail: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what ${detail()}")
    }

    /** The WebView on screen: the only one shown (the others are parked, invisible). */
    private fun shownWebView(): WebView? = onActivity { activity ->
        fun find(v: View): WebView? {
            if (v is WebView && v.isShown) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(activity.window.decorView)
    }

    private fun js(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("")
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) {
                result.set(it ?: "null")
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out: $script", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    /**
     * Wait for [name]'s page on screen, then give it focus: its text field,
     * or with [field] false the page itself (nothing editable focused).
     */
    private fun focusField(name: String, field: Boolean = true): WebView {
        lateinit var view: WebView
        waitFor("page $name") {
            val v = shownWebView() ?: return@waitFor false
            view = v
            js(v, "document.title + ':' + !!window.__keys") == "\"$name:true\""
        }
        if (field) {
            // A tap, as the user focuses it: the on-screen keyboard then
            // passes hardware keys on the way a field focused from script
            // didn't get them (see [press]).
            tap(view, "f")
        } else {
            instrumentation.runOnMainSync { view.requestFocus() }
        }
        waitFor("focus in $name's ${if (field) "field" else "page"}") {
            val focused = if (field) "document.activeElement.id" else "document.activeElement.blur(); document.activeElement.tagName"
            js(view, focused) == (if (field) "\"f\"" else "\"BODY\"") &&
                // …and WebView has heard: the browser decides who gets a
                // shortcut first from this.
                onActivity { it.currentFocus === view && view.onCheckIsTextEditor() == field }
        }
        return view
    }

    /** A real tap on the page's element [id], as the user's finger. */
    private fun tap(view: WebView, id: String) {
        val box = js(view, "var r = document.getElementById('$id').getBoundingClientRect(); " +
            "[r.left + r.width / 2, r.top + r.height / 2].join(',')").trim('"').split(',')
        val (x, y) = onActivity {
            val at = IntArray(2).also(view::getLocationOnScreen)
            val density = view.resources.displayMetrics.density
            (at[0] + box[0].toFloat() * density).toInt() to (at[1] + box[1].toFloat() * density).toInt()
        }
        shell("input tap $x $y")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            // Returns once the command has run.
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    /** What [name]'s page has seen, read from whichever page [view] shows (same origin, same tab). */
    private fun keys(view: WebView, name: String) =
        js(view, "JSON.parse(sessionStorage.getItem('$name') || '[]').join(' ')").trim('"')
            // The modifier keys' own keydowns aren't what's being checked.
            .split(' ').filter { it.isNotEmpty() && it.substringAfterLast('-') !in MODIFIER_KEYS }
            .joinToString(" ")

    /** The keyups [name]'s page has seen, modifiers left out. */
    private fun keyUps(view: WebView, name: String) =
        js(view, "JSON.parse(sessionStorage.getItem('$name-up') || '[]').join(' ')").trim('"')
            .split(' ').filter { it.isNotEmpty() && it !in MODIFIER_KEYS }
            .joinToString(" ")

    private companion object {
        val MODIFIER_KEYS = setOf("Control", "Shift", "Alt")
    }

    /**
     * [keyCode] with [meta] held, through the shell's `input` command —
     * the same path as `adb shell input keycombination`: each modifier
     * key goes down first, then the key, then all come back up, from the
     * system's virtual keyboard. (Events from [android.app.Instrumentation.sendKeySync]
     * went to the on-screen IME first once a page field had an input
     * connection, and it kept them.) The field is focused by a tap for
     * the same reason ([focusField]).
     */
    private fun press(keyCode: Int, meta: Int = 0) {
        val modifiers = listOf(
            KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.META_SHIFT_ON to KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.META_ALT_ON to KeyEvent.KEYCODE_ALT_LEFT,
        ).filter { (bit, _) -> meta and bit != 0 }.map { it.second }
        val command = if (modifiers.isEmpty()) {
            "input keyevent $keyCode"
        } else {
            "input keycombination " + (modifiers + keyCode).joinToString(" ")
        }
        shell(command)
        instrumentation.waitForIdleSync()
    }

    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test
    fun typingReachesThePageField() {
        launch("/one")
        val view = focusField("one")
        press(KeyEvent.KEYCODE_H)
        press(KeyEvent.KEYCODE_I)
        // (The keydowns' `key` is up to the IME's composing, not checked.)
        waitFor("typed text") { js(view, "document.getElementById('f').value") == "\"hi\"" }
    }

    @Test
    fun ctrlFGoesToThePageFirstAndOpensFindWhenUnused() {
        launch("/one")
        val view = focusField("one")
        press(KeyEvent.KEYCODE_F, ctrl)
        waitFor("find bar") { onActivity { tabs.active.find.open } }
        assertEquals("C-f", keys(view, "one"))
    }

    @Test
    fun aPageThatPreventsCtrlFKeepsIt() {
        launch("/one")
        val view = focusField("one")
        js(view, "__prevent = true")
        press(KeyEvent.KEYCODE_F, ctrl)
        waitFor("the page's keydown") { keys(view, "one") == "C-f" }
        Thread.sleep(1_000)
        assertFalse(onActivity { tabs.active.find.open })
    }

    @Test
    fun ctrlRReloadsFromAPageField() {
        launch("/one")
        val view = focusField("one")
        val before = hits["/one"]?.get() ?: 0
        press(KeyEvent.KEYCODE_R, ctrl)
        waitFor("reload") { (hits["/one"]?.get() ?: 0) > before }
        assertEquals("C-r", keys(view, "one"))
    }

    @Test
    fun reservedTabShortcutsNeverReachThePage() {
        launch("/one")
        val view = focusField("one")
        // Even though the page would prevent them.
        js(view, "__prevent = true")

        press(KeyEvent.KEYCODE_T, ctrl)
        waitFor("a new tab") { onActivity { tabs.tabs.size == 2 && tabs.activeIndex == 1 } }

        press(KeyEvent.KEYCODE_TAB, ctrl or shift)
        waitFor("back on the first tab") { onActivity { tabs.activeIndex == 0 } }
        press(KeyEvent.KEYCODE_TAB, ctrl)
        waitFor("the second tab again") { onActivity { tabs.activeIndex == 1 } }
        press(KeyEvent.KEYCODE_PAGE_UP, ctrl)
        waitFor("the first tab again") { onActivity { tabs.activeIndex == 0 } }

        focusField("one")
        press(KeyEvent.KEYCODE_L, ctrl)
        // The address bar has focus: the page doesn't.
        waitFor("address bar focus") { onActivity { it.currentFocus !is WebView } }

        focusField("one")
        assertEquals("", keys(view, "one"))
        press(KeyEvent.KEYCODE_W, ctrl)
        waitFor("the page's tab closed") { onActivity { tabs.tabs.size == 1 && tabs.active.url.isBlank() } }

        press(KeyEvent.KEYCODE_T, ctrl or shift)
        waitFor("the page's tab reopened") {
            onActivity { tabs.tabs.size == 2 && tabs.active.url == url("/one") }
        }
    }

    @Test
    fun altArrowsGoBackAndForward() {
        launch("/one")
        // With focus on the page, not in its field.
        val one = focusField("one", field = false)
        // A tap: Chromium's Back skips an entry a page added with no gesture.
        tap(one, "two")
        val two = focusField("two", field = false)
        waitFor("history to go back to") { onActivity { tabs.active.canGoBack } }
        press(KeyEvent.KEYCODE_DPAD_LEFT, alt)
        waitFor("back on one") { onActivity { tabs.active.url == url("/one") } }
        assertEquals("", keys(two, "two"))
        focusField("one", field = false)
        waitFor("history to go forward to") { onActivity { tabs.active.canGoForward } }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, alt)
        waitFor("forward on two") { onActivity { tabs.active.url == url("/two") } }
    }

    @Test
    fun zoomKeysStepThePage() {
        launch("/one")
        // With focus on the page, not in its field (where WebView keeps
        // Ctrl+=/−/0 as the field's own).
        val view = focusField("one", field = false)
        val start = onActivity { view.settings.textZoom }
        press(KeyEvent.KEYCODE_EQUALS, ctrl)
        waitFor("zoomed in") { onActivity { view.settings.textZoom > start } }
        press(KeyEvent.KEYCODE_0, ctrl)
        waitFor("zoom reset") { onActivity { view.settings.textZoom == start } }
        press(KeyEvent.KEYCODE_MINUS, ctrl)
        waitFor("zoomed out") { onActivity { view.settings.textZoom < start } }
        press(KeyEvent.KEYCODE_0, ctrl)
        waitFor("zoom reset again") { onActivity { view.settings.textZoom == start } }
        assertEquals("", keys(view, "one"))
    }

    @Test
    fun altLeftFromATextFieldGoesToThePageFirst() {
        launch("/one")
        val one = focusField("one", field = false)
        // A tap: Chromium's Back skips an entry a page added with no gesture.
        tap(one, "two")
        val two = focusField("two")
        waitFor("history to go back to") { onActivity { tabs.active.canGoBack } }
        press(KeyEvent.KEYCODE_DPAD_LEFT, alt)
        waitFor("back on one") { onActivity { tabs.active.url == url("/one") } }
        assertEquals("A-ArrowLeft", keys(two, "two"))
    }

    // #307 R2-M1: a taken shortcut's release goes with its press — the
    // page never sees a keyup for a keydown it never saw. A plain key
    // typed after it shows the page's keyups are heard. (The API 36
    // WebView also drops such an orphan keyup itself, so this guards the
    // outcome; the router's own part is KeyboardShortcutsTest's.)
    @Test
    fun aTakenShortcutsReleaseNeverReachesThePage() {
        launch("/one")
        for (field in listOf(true, false)) {
            val view = focusField("one", field)
            js(view, "sessionStorage.removeItem('one-up')")
            // The only tab: Ctrl+Tab is taken and changes nothing, so
            // focus stays where it was for the release.
            press(KeyEvent.KEYCODE_TAB, ctrl)
            press(KeyEvent.KEYCODE_X)
            waitFor("x's keyup") { keyUps(view, "one").isNotEmpty() }
            Thread.sleep(500)
            assertFalse(keyUps(view, "one"), "Tab" in keyUps(view, "one").split(' '))
        }
    }

    // #307 R2-F1: a shortcut over a page's HTML5 fullscreen leaves it
    // first, so the tab it switches to is the one on screen.
    @Test
    fun aTabShortcutLeavesFullscreenFirst() {
        launch("/one")
        focusField("one", field = false)
        press(KeyEvent.KEYCODE_T, ctrl)
        waitFor("a new tab") { onActivity { tabs.tabs.size == 2 && tabs.activeIndex == 1 } }
        press(KeyEvent.KEYCODE_TAB, ctrl)
        waitFor("back on the page's tab") { onActivity { tabs.activeIndex == 0 } }
        val view = focusField("one", field = false)
        tap(view, "go")
        waitFor("fullscreen") { onActivity { tabs.fullscreen != null } }
        press(KeyEvent.KEYCODE_TAB, ctrl)
        waitFor("the other tab, out of fullscreen") {
            onActivity { tabs.activeIndex == 1 && tabs.fullscreen == null }
        }
    }

    /** Focus is in the view the page's HTML5 fullscreen is shown in (Chromium's view, or inside it). */
    private fun focusInFullscreen(activity: MainActivity): Boolean {
        val fs = tabs.fullscreen?.view ?: return false
        var v: View? = activity.currentFocus
        while (v != null && v !== fs) v = v.parent as? View
        return v != null
    }

    /**
     * [name]'s page in HTML5 fullscreen, with the text field inside the
     * fullscreen element focused by a tap on the fullscreen view.
     */
    private fun focusFieldInFullscreen(name: String): WebView {
        val view = focusField(name, field = false)
        tap(view, "go")
        waitFor("fullscreen") { onActivity { tabs.fullscreen != null } }
        waitFor("the fullscreen element laid out") {
            js(view, "document.fullscreenElement && document.fullscreenElement.id") == "\"fs\""
        }
        Thread.sleep(500)
        val box = js(view, "var r = document.getElementById('g').getBoundingClientRect(); " +
            "[r.left + r.width / 2, r.top + r.height / 2].join(',')").trim('"').split(',')
        val (x, y) = onActivity {
            val fs = tabs.fullscreen!!.view
            val at = IntArray(2).also(fs::getLocationOnScreen)
            val density = fs.resources.displayMetrics.density
            (at[0] + box[0].toFloat() * density).toInt() to (at[1] + box[1].toFloat() * density).toInt()
        }
        shell("input tap $x $y")
        // (The fullscreen view answers onCheckIsTextEditor() false even
        // now; the browser reads its input connection instead.)
        waitFor("focus in the fullscreen field") {
            js(view, "document.activeElement.id") == "\"g\"" &&
                onActivity {
                    val imm = it.getSystemService(InputMethodManager::class.java)
                    focusInFullscreen(it) && imm.isAcceptingText
                }
        }
        return view
    }

    // #307 R3-F1: in HTML5 fullscreen the focused view is the fullscreen
    // view, not a WebView; a text field there is still the page's, and
    // gets a non-reserved shortcut first.
    @Test
    fun aFullscreenPageThatPreventsCtrlFKeepsIt() {
        launch("/one")
        val view = focusFieldInFullscreen("one")
        js(view, "__prevent = true")
        press(KeyEvent.KEYCODE_F, ctrl)
        waitFor("the page's keydown") { keys(view, "one") == "C-f" }
        Thread.sleep(1_000)
        assertFalse(onActivity { tabs.active.find.open })
        assertTrue("still fullscreen", onActivity { tabs.fullscreen != null })
    }

    // …and off a field, fullscreen or not, the browser takes it first.
    @Test
    fun ctrlFOverAFullscreenPageBodyIsTheBrowsers() {
        launch("/one")
        val view = focusField("one", field = false)
        js(view, "__prevent = true")
        tap(view, "go")
        waitFor("fullscreen") { onActivity { tabs.fullscreen != null } }
        waitFor("the fullscreen view focused") {
            onActivity(::focusInFullscreen)
        }
        press(KeyEvent.KEYCODE_F, ctrl)
        waitFor("find bar, out of fullscreen") {
            onActivity { tabs.active.find.open && tabs.fullscreen == null }
        }
        assertEquals("", keys(view, "one"))
    }

    @Test
    fun ctrlFInAFullscreenFieldGoesToThePageFirst() {
        launch("/one")
        val view = focusFieldInFullscreen("one")
        press(KeyEvent.KEYCODE_F, ctrl)
        waitFor("find bar, out of fullscreen") {
            onActivity { tabs.active.find.open && tabs.fullscreen == null }
        }
        assertEquals("C-f", keys(view, "one"))
    }

    // #307 R2-M2: in the address bar Alt+←/→ move the caret; the page
    // behind stays where it is.
    @Test
    fun altArrowsInTheAddressBarStayTheFields() {
        launch("/one")
        val one = focusField("one", field = false)
        tap(one, "two")
        focusField("two", field = false)
        waitFor("history to go back to") { onActivity { tabs.active.canGoBack } }
        press(KeyEvent.KEYCODE_L, ctrl)
        waitFor("address bar focus") {
            onActivity { it.currentFocus !is WebView && it.currentFocus?.onCheckIsTextEditor() == true }
        }
        press(KeyEvent.KEYCODE_DPAD_LEFT, alt)
        Thread.sleep(1_500)
        assertEquals(url("/two"), onActivity { tabs.active.url })
        assertTrue(onActivity { it.currentFocus !is WebView })
    }
}
