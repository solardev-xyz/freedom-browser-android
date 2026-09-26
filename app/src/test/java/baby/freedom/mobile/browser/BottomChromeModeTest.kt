package baby.freedom.mobile.browser

import androidx.compose.ui.unit.dp
import baby.freedom.mobile.browser.BottomChromeMode.Overlay
import baby.freedom.mobile.browser.BottomChromeMode.Reserved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomChromeModeTest {

    private val footprint = CapsuleHeight + CapsuleBottomMargin // 58 dp resting
    private val editingFootprint = CapsuleEditingHeight + CapsuleBottomMargin
    private val nav = 24.dp

    // --- mode → page-area reserve ------------------------------------------

    @Test
    fun `overlay reserves nothing without the keyboard, the capsule with it`() {
        assertEquals(0.dp, contentBottomReserve(Overlay, false, footprint, nav, 0.dp))
        assertEquals(footprint, contentBottomReserve(Overlay, true, footprint, nav, 300.dp))
    }

    @Test
    fun `reserved reserves the resting capsule plus the navigation inset`() {
        assertEquals(58.dp + nav, contentBottomReserve(Reserved, false, footprint, nav, 0.dp))
        assertEquals(58.dp + nav, reservedFootprint(nav))
    }

    @Test
    fun `reserved ignores the editing footprint until the keyboard is up`() {
        // Focusing the address bar alone must not resize the WebView: the
        // reserved band is the resting footprint whatever the capsule does.
        assertEquals(58.dp + nav, contentBottomReserve(Reserved, false, editingFootprint, nav, 0.dp))
    }

    @Test
    fun `reserved with the keyboard never drops below the reserved band`() {
        // IME inset is already in the page area's padding; the reserve only
        // tops up the navigation inset while the rising IME is shorter.
        val rising = 10.dp
        val total = contentBottomReserve(Reserved, true, footprint, nav, rising) + rising
        assertEquals(58.dp + nav, total)
        // Fully up: exactly the overlay keyboard reserve.
        assertEquals(footprint, contentBottomReserve(Reserved, true, footprint, nav, 300.dp))
    }

    @Test
    fun `overlay native surfaces clear the whole capsule and nav inset`() {
        assertEquals(footprint + nav, capsuleOverlap(Overlay, false, footprint, nav))
        assertEquals(editingFootprint + nav, capsuleOverlap(Overlay, false, editingFootprint, nav))
        assertEquals(0.dp, capsuleOverlap(Overlay, true, editingFootprint, nav))
    }

    @Test
    fun `reserved native surfaces clear only the editing capsule's growth`() {
        // At rest the reserved band covers the capsule exactly.
        assertEquals(0.dp, capsuleOverlap(Reserved, false, footprint, nav))
        // Address bar focused with no IME (hardware keyboard): the editing
        // capsule sticks 16 dp past the band, over the suggestions panel.
        assertEquals(CapsuleEditingHeight - CapsuleHeight, capsuleOverlap(Reserved, false, editingFootprint, nav))
        assertEquals(16.dp, capsuleOverlap(Reserved, false, editingFootprint, nav))
        // Keyboard up: the keyboard reserve already clears it.
        assertEquals(0.dp, capsuleOverlap(Reserved, true, editingFootprint, nav))
    }

    @Test
    fun `the home surface is never reserved`() {
        assertEquals(Overlay, effectiveBottomChromeMode(Reserved, isHomeTab = true))
        assertEquals(Reserved, effectiveBottomChromeMode(Reserved, isHomeTab = false))
        assertEquals(Overlay, effectiveBottomChromeMode(Overlay, isHomeTab = false))
    }

    // --- colour fallback ---------------------------------------------------

    private val surface = 0xFF1D1B20.toInt()

    @Test
    fun `the reported colour fills the strip`() {
        assertEquals(0xFF6750A4.toInt(), bottomStripArgb("rgb(103, 80, 164)", surface))
        assertEquals(0xFF000000.toInt(), bottomStripArgb("rgb(0, 0, 0)", surface))
    }

    @Test
    fun `no colour, or one that doesn't validate, falls back to the theme surface`() {
        assertEquals(surface, bottomStripArgb(null, surface))
        assertEquals(surface, bottomStripArgb("rgb(256, 0, 0)", surface))
        assertEquals(surface, bottomStripArgb("rgba(1, 2, 3, 0.5)", surface))
        assertEquals(surface, bottomStripArgb("#ffffff", surface))
        assertEquals(surface, bottomStripArgb("rgb(1,2,3)", surface))
        assertEquals(surface, bottomStripArgb("url(javascript:1)", surface))
    }

    // --- message validation ------------------------------------------------

    private val token = "abc123"

    private fun msg(has: Any?, color: Any?, t: String = token) =
        "{\"token\":\"$t\",\"hasBottomUI\":${has},\"color\":${if (color is String) "\"$color\"" else color}}"

    @Test
    fun `a well-formed main-frame message for this document is accepted`() {
        assertEquals(
            BottomUiReport(true, "rgb(1, 2, 3)"),
            parseBottomUiMessage(msg(true, "rgb(1, 2, 3)"), isMainFrame = true, expectedToken = token),
        )
        assertEquals(BottomUiReport(true, null), parseBottomUiMessage(msg(true, null), true, token))
        assertEquals(BottomUiReport(false, null), parseBottomUiMessage(msg(false, null), true, token))
    }

    @Test
    fun `a stale document's message is rejected`() {
        assertNull(parseBottomUiMessage(msg(true, null, t = "old999"), true, token))
        assertNull(parseBottomUiMessage(msg(true, null), true, expectedToken = null))
    }

    @Test
    fun `subframe messages are rejected`() {
        assertNull(parseBottomUiMessage(msg(true, null), isMainFrame = false, expectedToken = token))
    }

    @Test
    fun `anything but the exact shape is rejected`() {
        assertNull(parseBottomUiMessage("not json", true, token))
        assertNull(parseBottomUiMessage("[]", true, token))
        assertNull(parseBottomUiMessage(msg("\"yes\"", null), true, token))
        assertNull(parseBottomUiMessage(msg(1, null), true, token))
        assertNull(parseBottomUiMessage(msg(true, "red"), true, token))
        assertNull(parseBottomUiMessage(msg(true, 5), true, token))
        assertNull(parseBottomUiMessage("{\"token\":\"$token\",\"hasBottomUI\":true}", true, token))
        assertNull(
            parseBottomUiMessage(
                "{\"token\":\"$token\",\"hasBottomUI\":true,\"color\":null,\"extra\":1}",
                true,
                token,
            ),
        )
        assertNull(parseBottomUiMessage(null, true, token))
        assertNull(parseBottomUiMessage("x".repeat(600), true, token))
    }

    @Test
    fun `a negative report carries no colour`() {
        assertEquals(BottomUiReport(false, null), parseBottomUiMessage(msg(false, "rgb(1, 2, 3)"), true, token))
    }

    // --- hysteresis --------------------------------------------------------

    private fun slot(): BottomChromeSlot {
        var n = 0
        return BottomChromeSlot { "t${++n}" }.also { it.startDocument() }
    }

    private val yes = BottomUiReport(true, "rgb(1, 2, 3)")
    private val no = BottomUiReport(false, null)

    @Test
    fun `the first positive reserves at once`() {
        val s = slot()
        assertEquals(Overlay, s.mode)
        assertTrue(s.accept(yes, 0).changed)
        assertEquals(Reserved, s.mode)
        assertEquals("rgb(1, 2, 3)", s.color)
    }

    @Test
    fun `a negative in overlay changes nothing and asks for nothing`() {
        val s = slot()
        assertEquals(BottomChromeSlot.Verdict(false, null), s.accept(no, 0))
        assertEquals(Overlay, s.mode)
    }

    @Test
    fun `one negative is not enough, it asks for a confirmation after the gap`() {
        val s = slot()
        s.accept(yes, 0)
        val v = s.accept(no, 5_000)
        assertFalse(v.changed)
        assertEquals(RESERVED_EXIT_GAP_MS, v.confirmInMs)
        assertEquals(Reserved, s.mode)
    }

    @Test
    fun `two negatives closer than the gap stay reserved`() {
        val s = slot()
        s.accept(yes, 0)
        s.accept(no, 5_000)
        val v = s.accept(no, 5_400)
        assertFalse(v.changed)
        assertEquals(600L, v.confirmInMs)
        assertEquals(Reserved, s.mode)
    }

    @Test
    fun `two negatives at least the gap apart go back to overlay`() {
        val s = slot()
        s.accept(yes, 0)
        s.accept(no, 5_000)
        assertTrue(s.accept(no, 6_000).changed)
        assertEquals(Overlay, s.mode)
        assertNull(s.color)
    }

    @Test
    fun `a positive between the negatives cancels the exit`() {
        val s = slot()
        s.accept(yes, 0)
        s.accept(no, 5_000)
        assertFalse(s.accept(yes, 5_500).changed)
        // The clock restarts: this negative is a first one again.
        assertEquals(RESERVED_EXIT_GAP_MS, s.accept(no, 6_100).confirmInMs)
        assertEquals(Reserved, s.mode)
    }

    @Test
    fun `a new colour while reserved is a change`() {
        val s = slot()
        s.accept(yes, 0)
        assertTrue(s.accept(BottomUiReport(true, "rgb(9, 9, 9)"), 100).changed)
        assertFalse(s.accept(BottomUiReport(true, "rgb(9, 9, 9)"), 200).changed)
    }

    @Test
    fun `a new document resets to overlay and drops the old document's reports`() {
        val s = slot()
        val old = s.token!!
        s.accept(yes, 0)
        s.startDocument()
        assertEquals(Overlay, s.mode)
        val stale = "{\"token\":\"$old\",\"hasBottomUI\":true,\"color\":null}"
        assertFalse(s.accept(stale, isMainFrame = true, nowMs = 100).changed)
        assertEquals(Overlay, s.mode)
        val fresh = "{\"token\":\"${s.token}\",\"hasBottomUI\":true,\"color\":null}"
        assertTrue(s.accept(fresh, isMainFrame = true, nowMs = 200).changed)
    }

    @Test
    fun `the detector is installed once per document`() {
        val s = slot()
        assertEquals(s.token, s.install())
        assertNull(s.install())
        s.startDocument()
        assertEquals(s.token, s.install())
    }

    @Test
    fun `a page that flips on every reserve is pinned to overlay after three quick exits`() {
        val s = slot()
        var t = 0L
        repeat(RESERVED_MAX_QUICK_EXITS) {
            assertTrue(s.accept(yes, t).changed)
            s.accept(no, t + 300)
            assertTrue(s.accept(no, t + 1_300).changed)
            t += 2_000
        }
        assertTrue(s.latched)
        assertFalse(s.accept(yes, t).changed)
        assertEquals(Overlay, s.mode)
        // …for this document only.
        s.startDocument()
        assertTrue(s.accept(yes, t + 10).changed)
    }

    @Test
    fun `slow exits (a banner dismissed by the user) never pin`() {
        val s = slot()
        var t = 0L
        repeat(10) {
            s.accept(yes, t)
            s.accept(no, t + 5_000)
            assertTrue(s.accept(no, t + 6_000).changed)
            t += 10_000
        }
        assertFalse(s.latched)
        assertTrue(s.accept(yes, t).changed)
    }
}
