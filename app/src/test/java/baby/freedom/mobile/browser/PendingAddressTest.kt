package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pre-commit half of the address bar's trust contract: which
 * submits may move the tab's committed address (and therefore the bold
 * resting label) *before* the destination has committed.
 *
 * The label is the capsule asserting "this is the site you are on", so
 * it may never describe a page the user cannot see yet while the page
 * they *can* see is a different one.
 */
class PendingAddressTest {

    @Test
    fun `user submit echoes the destination it was given`() {
        assertEquals(
            "vitalik.eth",
            pendingAddressBarText(
                current = "https://attacker.example/donate",
                submitted = "vitalik.eth",
                source = SubmitSource.User,
            ),
        )
    }

    @Test
    fun `renderer submit leaves the address on the page still on screen`() {
        // The attacker page runs `location.href = 'ens://vitalik.eth'`.
        // Resolution + probe can take tens of seconds on a cold node,
        // and the attacker's document stays painted the whole time —
        // so the bar must keep naming the attacker until the new
        // document commits.
        val pending = pendingAddressBarText(
            current = "https://attacker.example/donate",
            submitted = "vitalik.eth",
            source = SubmitSource.Renderer,
        )
        assertEquals("https://attacker.example/donate", pending)
        assertEquals("attacker.example", AddressLabel.resting(pending))
    }

    @Test
    fun `renderer submit of a raw content id does not relabel either`() {
        val pending = pendingAddressBarText(
            current = "swarm.eth/docs",
            submitted = "bzz://a1b2c3d4e5f60718293a4b5c6d7e8f90",
            source = SubmitSource.Renderer,
        )
        assertEquals("swarm.eth", AddressLabel.resting(pending))
    }

    @Test
    fun `a home tab stays blank under a renderer submit`() {
        assertEquals(
            "",
            pendingAddressBarText(
                current = "",
                submitted = "ens://vitalik.eth",
                source = SubmitSource.Renderer,
            ),
        )
    }
}
