package baby.freedom.mobile.browser

import android.view.KeyEvent
import baby.freedom.mobile.browser.KeyboardShortcutRouter.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutsTest {
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test
    fun `the issue's shortcuts are bound as desktop binds them`() {
        assertEquals(Shortcut.NewTab, shortcutFor(KeyEvent.KEYCODE_T, ctrl))
        assertEquals(Shortcut.NewPrivateTab, shortcutFor(KeyEvent.KEYCODE_N, ctrl or shift))
        assertEquals(Shortcut.CloseTab, shortcutFor(KeyEvent.KEYCODE_W, ctrl))
        assertEquals(Shortcut.ReopenClosedTab, shortcutFor(KeyEvent.KEYCODE_T, ctrl or shift))
        assertEquals(Shortcut.NextTab, shortcutFor(KeyEvent.KEYCODE_TAB, ctrl))
        assertEquals(Shortcut.NextTab, shortcutFor(KeyEvent.KEYCODE_PAGE_DOWN, ctrl))
        assertEquals(Shortcut.PreviousTab, shortcutFor(KeyEvent.KEYCODE_TAB, ctrl or shift))
        assertEquals(Shortcut.PreviousTab, shortcutFor(KeyEvent.KEYCODE_PAGE_UP, ctrl))
        assertEquals(Shortcut.FocusAddressBar, shortcutFor(KeyEvent.KEYCODE_L, ctrl))
        assertEquals(Shortcut.Reload, shortcutFor(KeyEvent.KEYCODE_R, ctrl))
        assertEquals(Shortcut.Reload, shortcutFor(KeyEvent.KEYCODE_F5, 0))
        assertEquals(Shortcut.HardReload, shortcutFor(KeyEvent.KEYCODE_R, ctrl or shift))
        assertEquals(Shortcut.HardReload, shortcutFor(KeyEvent.KEYCODE_F5, shift))
        assertEquals(Shortcut.FindInPage, shortcutFor(KeyEvent.KEYCODE_F, ctrl))
        assertEquals(Shortcut.ZoomIn, shortcutFor(KeyEvent.KEYCODE_EQUALS, ctrl))
        assertEquals(Shortcut.ZoomIn, shortcutFor(KeyEvent.KEYCODE_EQUALS, ctrl or shift))
        assertEquals(Shortcut.ZoomIn, shortcutFor(KeyEvent.KEYCODE_NUMPAD_ADD, ctrl))
        assertEquals(Shortcut.ZoomOut, shortcutFor(KeyEvent.KEYCODE_MINUS, ctrl))
        assertEquals(Shortcut.ZoomReset, shortcutFor(KeyEvent.KEYCODE_0, ctrl))
        assertEquals(Shortcut.History, shortcutFor(KeyEvent.KEYCODE_H, ctrl))
        assertEquals(Shortcut.Downloads, shortcutFor(KeyEvent.KEYCODE_J, ctrl or shift))
        assertEquals(Shortcut.Back, shortcutFor(KeyEvent.KEYCODE_DPAD_LEFT, alt))
        assertEquals(Shortcut.Forward, shortcutFor(KeyEvent.KEYCODE_DPAD_RIGHT, alt))
    }

    @Test
    fun `modifiers must match exactly`() {
        // Plain typing, and editing keys a text field needs.
        assertNull(shortcutFor(KeyEvent.KEYCODE_T, 0))
        assertNull(shortcutFor(KeyEvent.KEYCODE_T, shift))
        assertNull(shortcutFor(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertNull(shortcutFor(KeyEvent.KEYCODE_DPAD_LEFT, ctrl))
        assertNull(shortcutFor(KeyEvent.KEYCODE_DPAD_LEFT, shift))
        assertNull(shortcutFor(KeyEvent.KEYCODE_A, ctrl))
        assertNull(shortcutFor(KeyEvent.KEYCODE_C, ctrl))
        assertNull(shortcutFor(KeyEvent.KEYCODE_V, ctrl))
        assertNull(shortcutFor(KeyEvent.KEYCODE_TAB, 0))
        // An extra modifier is a different chord.
        assertNull(shortcutFor(KeyEvent.KEYCODE_T, ctrl or alt))
        assertNull(shortcutFor(KeyEvent.KEYCODE_W, ctrl or shift))
        assertNull(shortcutFor(KeyEvent.KEYCODE_L, ctrl or shift))
    }

    @Test
    fun `Meta chords are the system's, and lock keys don't matter`() {
        assertNull(shortcutFor(KeyEvent.KEYCODE_T, ctrl or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON))
        assertNull(shortcutFor(KeyEvent.KEYCODE_SLASH, KeyEvent.META_META_ON))
        val locks = KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON
        assertEquals(Shortcut.NewTab, shortcutFor(KeyEvent.KEYCODE_T, ctrl or locks))
        // The right-hand Ctrl counts too.
        assertEquals(
            Shortcut.CloseTab,
            shortcutFor(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON),
        )
    }

    @Test
    fun `the browser reserves only the tab and address bar keys`() {
        val reserved = Shortcut.entries.filter { it.reserved }.toSet()
        assertEquals(
            setOf(
                Shortcut.NewTab, Shortcut.NewPrivateTab, Shortcut.CloseTab, Shortcut.ReopenClosedTab,
                Shortcut.NextTab, Shortcut.PreviousTab, Shortcut.FocusAddressBar,
                Shortcut.SelectTab1, Shortcut.SelectTab2, Shortcut.SelectTab3, Shortcut.SelectTab4,
                Shortcut.SelectTab5, Shortcut.SelectTab6, Shortcut.SelectTab7, Shortcut.SelectTab8,
                Shortcut.SelectLastTab,
            ),
            reserved,
        )
    }

    @Test
    fun `every shortcut has a binding`() {
        for (shortcut in Shortcut.entries) {
            assertTrue(shortcut.name, SHORTCUT_BINDINGS[shortcut].orEmpty().isNotEmpty())
            for (binding in SHORTCUT_BINDINGS.getValue(shortcut)) {
                assertEquals(shortcut, shortcutFor(binding.keyCode, binding.modifiers))
            }
        }
    }

    private class Recorder(var active: Boolean = true) : ShortcutTarget {
        val seen = mutableListOf<Pair<Shortcut, Boolean>>()
        override fun onShortcut(shortcut: Shortcut, repeat: Boolean): Boolean {
            if (!active) return false
            seen += shortcut to repeat
            return true
        }
    }

    private fun down(keyCode: Int, meta: Int, repeat: Int = 0) = Press(KeyEvent.ACTION_DOWN, keyCode, meta, repeat)

    @Test
    fun `a reserved shortcut is taken before a page's text field sees it`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_T, ctrl), pageEditing = true))
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_W, ctrl), pageEditing = true))
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_L, ctrl), pageEditing = true))
        assertEquals(
            listOf(Shortcut.NewTab to false, Shortcut.CloseTab to false, Shortcut.FocusAddressBar to false),
            target.seen,
        )
        // It never comes back as the page's leftover.
        assertFalse(router.unhandledInPage(down(KeyEvent.KEYCODE_T, ctrl)))
        assertEquals(3, target.seen.size)
    }

    @Test
    fun `any other shortcut goes to a page's text field first`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        for (key in listOf(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_MINUS)) {
            assertFalse(router.beforeViews(down(key, ctrl), pageEditing = true))
        }
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_DPAD_LEFT, alt), pageEditing = true))
        assertTrue(target.seen.isEmpty())
        // …and acts when the page didn't use it.
        assertTrue(router.unhandledInPage(down(KeyEvent.KEYCODE_F, ctrl)))
        assertEquals(listOf(Shortcut.FindInPage to false), target.seen)
    }

    @Test
    fun `with no page text field focused every shortcut acts at once`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_F, ctrl), pageEditing = false))
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_DPAD_RIGHT, alt), pageEditing = false))
        assertEquals(listOf(Shortcut.FindInPage to false, Shortcut.Forward to false), target.seen)
    }

    @Test
    fun `releases, unbound keys and an inactive or missing target pass through`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter()
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_T, ctrl), pageEditing = false))
        router.target = target
        assertFalse(router.beforeViews(Press(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T, ctrl), pageEditing = false))
        assertFalse(router.unhandledInPage(Press(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, ctrl)))
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_A, 0), pageEditing = false))
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_A, ctrl), pageEditing = false))
        target.active = false
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_T, ctrl), pageEditing = false))
        assertTrue(target.seen.isEmpty())
    }

    @Test
    fun `a held key reports its repeats`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        router.beforeViews(down(KeyEvent.KEYCODE_TAB, ctrl), pageEditing = true)
        router.beforeViews(down(KeyEvent.KEYCODE_TAB, ctrl, repeat = 1), pageEditing = true)
        assertEquals(listOf(Shortcut.NextTab to false, Shortcut.NextTab to true), target.seen)
        assertTrue(Shortcut.NextTab.repeats)
        assertTrue(Shortcut.ZoomIn.repeats)
        assertFalse(Shortcut.CloseTab.repeats)
        assertFalse(Shortcut.NewTab.repeats)
    }

    private fun up(keyCode: Int, meta: Int) = Press(KeyEvent.ACTION_UP, keyCode, meta)

    @Test
    fun `a taken press takes its release, whatever modifiers are still held`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_W, ctrl), pageEditing = true))
        // Ctrl let go first: the W's release is still the shortcut's.
        assertTrue(router.beforeViews(up(KeyEvent.KEYCODE_W, 0), pageEditing = true))
        // Only once.
        assertFalse(router.beforeViews(up(KeyEvent.KEYCODE_W, 0), pageEditing = true))
        // A held key: every repeat, then one release.
        router.beforeViews(down(KeyEvent.KEYCODE_TAB, ctrl), pageEditing = false)
        router.beforeViews(down(KeyEvent.KEYCODE_TAB, ctrl, repeat = 1), pageEditing = false)
        assertTrue(router.beforeViews(up(KeyEvent.KEYCODE_TAB, ctrl), pageEditing = false))
    }

    @Test
    fun `a press not taken keeps its release`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        // Page first: the page saw the press, so it sees the release.
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_F, ctrl), pageEditing = true))
        assertTrue(router.unhandledInPage(down(KeyEvent.KEYCODE_F, ctrl)))
        assertFalse(router.beforeViews(up(KeyEvent.KEYCODE_F, ctrl), pageEditing = true))
        // A panel up: nothing taken, nothing owed.
        target.active = false
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_T, ctrl), pageEditing = false))
        assertFalse(router.beforeViews(up(KeyEvent.KEYCODE_T, ctrl), pageEditing = false))
        // A taken press whose release went elsewhere owes nothing once the
        // key is pressed again and not taken.
        target.active = true
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_T, ctrl), pageEditing = false))
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_T, 0), pageEditing = false))
        assertFalse(router.beforeViews(up(KeyEvent.KEYCODE_T, 0), pageEditing = false))
    }

    @Test
    fun `the browser's own text field keeps Alt+arrows as caret keys`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_DPAD_LEFT, alt), pageEditing = false, fieldEditing = true))
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_DPAD_RIGHT, alt), pageEditing = false, fieldEditing = true))
        assertFalse(router.beforeViews(up(KeyEvent.KEYCODE_DPAD_RIGHT, alt), pageEditing = false, fieldEditing = true))
        assertTrue(target.seen.isEmpty())
        // Every other shortcut still acts from it.
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_F, ctrl), pageEditing = false, fieldEditing = true))
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_TAB, ctrl), pageEditing = false, fieldEditing = true))
        assertEquals(listOf(Shortcut.FindInPage to false, Shortcut.NextTab to false), target.seen)
        assertEquals(setOf(Shortcut.Back, Shortcut.Forward), Shortcut.entries.filter { it.caretKey }.toSet())
    }

    @Test
    fun `Ctrl+1 to Ctrl+8 select a tab by position, Ctrl+9 the last (#547)`() {
        val digits = listOf(
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
        )
        digits.forEachIndexed { i, key ->
            val shortcut = shortcutFor(key, ctrl)
            assertEquals(i + 1, shortcut?.tabPosition)
            assertTrue(shortcut!!.reserved)
            assertFalse(shortcut.repeats)
        }
        assertEquals(Shortcut.SelectLastTab, shortcutFor(KeyEvent.KEYCODE_9, ctrl))
        // Ctrl+0 stays Reset zoom; plain, Shift and Alt digits are typing.
        assertEquals(Shortcut.ZoomReset, shortcutFor(KeyEvent.KEYCODE_0, ctrl))
        assertNull(shortcutFor(KeyEvent.KEYCODE_1, 0))
        assertNull(shortcutFor(KeyEvent.KEYCODE_1, shift))
        assertNull(shortcutFor(KeyEvent.KEYCODE_1, alt))
        assertNull(shortcutFor(KeyEvent.KEYCODE_1, ctrl or shift))
        assertNull(shortcutFor(KeyEvent.KEYCODE_NUMPAD_1, ctrl))
        assertEquals("Select tab 3", Shortcut.SelectTab3.label)
        assertEquals("Select last tab", Shortcut.SelectLastTab.label)
    }

    @Test
    fun `a page's text field never sees Ctrl+digit`() {
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        assertTrue(router.beforeViews(down(KeyEvent.KEYCODE_2, ctrl), pageEditing = true))
        assertEquals(listOf(Shortcut.SelectTab2 to false), target.seen)
    }

    @Test
    fun `tab positions count within the pane on screen`() {
        // normal 0, private 1, normal 2, private 3, normal 4
        val tabs = listOf(false, true, false, true, false).mapIndexed { i, p -> BrowserState(i.toLong(), p) }
        assertEquals(0, selectTabIndex(Shortcut.SelectTab1, tabs, activeIndex = 4))
        assertEquals(2, selectTabIndex(Shortcut.SelectTab2, tabs, activeIndex = 4))
        assertEquals(4, selectTabIndex(Shortcut.SelectTab3, tabs, activeIndex = 0))
        assertEquals(4, selectTabIndex(Shortcut.SelectLastTab, tabs, activeIndex = 0))
        // Past the pane's last tab: nothing.
        assertNull(selectTabIndex(Shortcut.SelectTab4, tabs, activeIndex = 0))
        assertNull(selectTabIndex(Shortcut.SelectTab8, tabs, activeIndex = 0))
        // From a private tab, its own pane.
        assertEquals(1, selectTabIndex(Shortcut.SelectTab1, tabs, activeIndex = 3))
        assertEquals(3, selectTabIndex(Shortcut.SelectTab2, tabs, activeIndex = 1))
        assertEquals(3, selectTabIndex(Shortcut.SelectLastTab, tabs, activeIndex = 1))
        assertNull(selectTabIndex(Shortcut.SelectTab3, tabs, activeIndex = 1))
        // Not a Select-tab shortcut, or no tabs.
        assertNull(selectTabIndex(Shortcut.NextTab, tabs, activeIndex = 0))
        assertNull(selectTabIndex(Shortcut.SelectTab1, emptyList(), activeIndex = 0))
    }

    @Test
    fun `Ctrl+Enter publishes text, and is no browser shortcut (#547)`() {
        assertTrue(isPublishTextKey(KeyEvent.KEYCODE_ENTER, ctrl))
        assertTrue(isPublishTextKey(KeyEvent.KEYCODE_NUMPAD_ENTER, ctrl))
        assertTrue(isPublishTextKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON))
        assertTrue(isPublishTextKey(KeyEvent.KEYCODE_ENTER, ctrl or KeyEvent.META_CAPS_LOCK_ON))
        // Plain Enter, and Enter with anything else, still edit the text.
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_ENTER, 0))
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_ENTER, shift))
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_ENTER, alt))
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_ENTER, ctrl or shift))
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_ENTER, ctrl or alt))
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_ENTER, ctrl or KeyEvent.META_META_ON))
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON))
        assertFalse(isPublishTextKey(KeyEvent.KEYCODE_M, ctrl))
        // The router leaves it to whatever has focus: a page's own Ctrl+Enter still works.
        assertNull(shortcutFor(KeyEvent.KEYCODE_ENTER, ctrl))
        val target = Recorder()
        val router = KeyboardShortcutRouter().apply { this.target = target }
        assertFalse(router.beforeViews(down(KeyEvent.KEYCODE_ENTER, ctrl), pageEditing = false))
        assertFalse(router.unhandledInPage(down(KeyEvent.KEYCODE_ENTER, ctrl)))
        assertTrue(target.seen.isEmpty())
    }
}
