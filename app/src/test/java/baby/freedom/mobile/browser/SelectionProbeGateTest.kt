package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selection toolbar asks the page for its selection only when the answer can have changed. */
class SelectionProbeGateTest {
    private val caret = listOf(3, 4) // Paste, Select all
    private val selection = listOf(1, 2, 3, 4) // Cut, Copy, Paste, Select all
    private val password = listOf(3, 4, 5)

    @Test
    fun `the first menu is probed, a repeat of it is not`() {
        val gate = SelectionProbeGate()
        assertTrue(gate.shouldProbe(selection))
        // Handle drags and our own invalidate re-prepare the same items.
        assertFalse(gate.shouldProbe(selection))
        assertFalse(gate.shouldProbe(selection.toList()))
    }

    @Test
    fun `a change in Chromium's items is probed again`() {
        val gate = SelectionProbeGate()
        assertTrue(gate.shouldProbe(caret))
        assertTrue(gate.shouldProbe(selection))
        assertTrue(gate.shouldProbe(password))
        assertFalse(gate.shouldProbe(password))
        assertTrue(gate.shouldProbe(caret))
    }

    @Test
    fun `an empty menu at create still lets the populated one probe`() {
        val gate = SelectionProbeGate()
        assertTrue(gate.shouldProbe(emptyList()))
        assertTrue(gate.shouldProbe(selection))
    }
}
