package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** The co-sign page's Sign card: Sign only once every read is back, so its tap guard arms from Sign's own first frame (#287 R1-M2). */
class SafeCoSignStageTest {
    private fun stage(
        signed: Boolean = false,
        chainKnown: Boolean = true,
        readFailed: Boolean = false,
        readsDone: Boolean = true,
        ownsIt: Boolean = true,
        outdated: Boolean = false,
    ) = safeCoSignStage(signed, chainKnown, readFailed, readsDone, ownsIt, outdated)

    @Test fun `Sign shows only once every read is back`() {
        assertEquals(SafeCoSignStage.SIGN, stage())
        // Owners read and owned, but the nonce or self-call snapshot still pending: still checking.
        assertEquals(SafeCoSignStage.CHECKING, stage(readsDone = false))
        assertEquals(SafeCoSignStage.CHECKING, stage(readsDone = false, ownsIt = false))
    }

    @Test fun `the other stages keep their precedence`() {
        assertEquals(SafeCoSignStage.SIGNED, stage(signed = true, chainKnown = false, readFailed = true))
        assertEquals(SafeCoSignStage.NO_CHAIN, stage(chainKnown = false, readsDone = false))
        assertEquals(SafeCoSignStage.READ_FAILED, stage(readFailed = true))
        assertEquals(SafeCoSignStage.NOT_OWNER, stage(ownsIt = false, outdated = true))
        assertEquals(SafeCoSignStage.OUTDATED, stage(outdated = true))
    }
}
