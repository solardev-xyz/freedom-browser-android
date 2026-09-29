package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.OpenLvSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The phone-signing sheet's typed-data layout (#113, #216 R3). */
class TypedLinesTest {
    @Test
    fun `indent stops at a few levels so a deep field stays on the sheet`() {
        assertEquals(0, typedIndent(0))
        assertEquals(3, typedIndent(3))
        assertEquals(TYPED_MAX_INDENT, typedIndent(TYPED_MAX_INDENT))
        assertEquals(TYPED_MAX_INDENT, typedIndent(31))
        assertEquals(TYPED_MAX_INDENT, typedIndent(10_000))
        // At most 48dp in, out of a sheet ~358dp wide.
        assertEquals(48, typedIndent(Int.MAX_VALUE) * TYPED_INDENT_DP)
    }

    @Test
    fun `a line past the indent names its level`() {
        assertEquals("spender", typedLabel(Eip712.Line("spender", "0x", TYPED_MAX_INDENT)))
        assertEquals("level 31 · spender", typedLabel(Eip712.Line("spender", "0x", 31)))
    }

    @Test
    fun `status never names who is on the other end of a code`() {
        for (s in listOf(OpenLvSession.Status.Idle, OpenLvSession.Status.Connecting, OpenLvSession.Status.Connected, OpenLvSession.Status.Disconnected)) {
            assertFalse(remoteStatusText(s), remoteStatusText(s).contains("Freedom"))
        }
    }
}
