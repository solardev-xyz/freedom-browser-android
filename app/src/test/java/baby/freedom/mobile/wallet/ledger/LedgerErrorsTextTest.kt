package baby.freedom.mobile.wallet.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

/** [LedgerException.Kind.saysNothingSent] matches what each English message says (#280). */
class LedgerErrorsTextTest {
    @Test
    fun `saysNothingSent is exactly the kinds whose message says nothing went out`() {
        for (kind in LedgerException.Kind.entries) {
            assertEquals(kind.name, kind.message.contains("Nothing was"), kind.saysNothingSent)
        }
    }
}
