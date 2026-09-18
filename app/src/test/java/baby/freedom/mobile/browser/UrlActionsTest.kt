package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press URL actions, minus the menu that draws them: which URL
 * copy / share act on, and when "Paste and go" is on the menu at all.
 *
 * The gating matters twice over. It decides whether an item appears —
 * an item that appears and then does nothing is worse than one that
 * never appeared — and it is deliberately answered from the clip's
 * *description* rather than its contents, so that opening the menu is
 * not a clipboard read (which Android 12+ announces to the user with a
 * toast of its own).
 */
class UrlActionsTest {

    // ---- what copy / share act on -------------------------------------

    @Test
    fun `the committed address is what gets copied`() {
        assertEquals(
            "vitalik.eth/donate",
            urlActionTarget(addressBarText = "vitalik.eth/donate", url = "vitalik.eth/donate"),
        )
    }

    @Test
    fun `a pre-commit address is still the one the label is showing`() {
        // The user typed and submitted this and the capsule is already
        // showing it (PR #34's rule for user-named destinations). Copy
        // must hand back what they can see, not the page underneath.
        assertEquals(
            "example.com",
            urlActionTarget(addressBarText = "example.com", url = "https://old.example/x"),
        )
    }

    @Test
    fun `falls back to the loaded page when there is no committed address`() {
        assertEquals(
            "https://example.com/x",
            urlActionTarget(addressBarText = "", url = "https://example.com/x"),
        )
    }

    @Test
    fun `the home tab has no URL to act on`() {
        assertNull(urlActionTarget(addressBarText = "", url = ""))
        assertNull(urlActionTarget(addressBarText = "   ", url = ""))
    }

    // ---- paste gating -------------------------------------------------

    @Test
    fun `a text clip is pasteable`() {
        assertTrue(clipHasPasteableText(hasPrimaryClip = true, mimeTypes = listOf("text/plain")))
    }

    @Test
    fun `an html clip is pasteable`() {
        // A link copied out of a rendered page arrives as HTML with a
        // plain-text alternative, which is what `coerceToText` returns.
        assertTrue(
            clipHasPasteableText(
                hasPrimaryClip = true,
                mimeTypes = listOf("text/html", "text/plain"),
            ),
        )
    }

    @Test
    fun `an empty clipboard offers no paste`() {
        assertFalse(clipHasPasteableText(hasPrimaryClip = false, mimeTypes = emptyList()))
        // Belt and braces: a stale description with no live clip behind
        // it must not put the item on the menu either.
        assertFalse(clipHasPasteableText(hasPrimaryClip = false, mimeTypes = listOf("text/plain")))
    }

    @Test
    fun `a clipboard holding only an image offers no paste`() {
        assertFalse(
            clipHasPasteableText(hasPrimaryClip = true, mimeTypes = listOf("image/png")),
        )
    }

    @Test
    fun `a clipboard with no advertised types offers no paste`() {
        assertFalse(clipHasPasteableText(hasPrimaryClip = true, mimeTypes = emptyList()))
    }

    // ---- what paste-and-go submits ------------------------------------

    @Test
    fun `a pasted url is submitted verbatim`() {
        assertEquals("https://example.com/a", pasteAndGoTarget("https://example.com/a"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        // Copying a link out of a chat app routinely brings a newline.
        assertEquals("https://example.com", pasteAndGoTarget("  https://example.com\n"))
    }

    @Test
    fun `internal newlines collapse rather than reaching the single-line field`() {
        assertEquals(
            "https://example.com/very long",
            pasteAndGoTarget("https://example.com/very\n\tlong"),
        )
    }

    @Test
    fun `a dweb address survives the paste path unchanged`() {
        // `submit()` is what decides bzz:// is probe-gated; paste-and-go
        // must not pre-empt that by mangling the scheme.
        assertEquals("bzz://swarm.eth/docs", pasteAndGoTarget("bzz://swarm.eth/docs "))
    }

    @Test
    fun `nothing on the clipboard is nothing to go to`() {
        assertNull(pasteAndGoTarget(null))
        assertNull(pasteAndGoTarget(""))
        assertNull(pasteAndGoTarget("   \n\t "))
    }
}
