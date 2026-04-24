package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [Gateways] object carries mutable process-wide state — the IPFS
 * base URL the UI mirrors from the `:node` process. Each test clears
 * it in [tearDown] so runs stay independent.
 */
class GatewaysTest {

    @After
    fun tearDown() {
        Gateways.setIpfsBase("")
    }

    @Test
    fun `toLoadable routes bzz through swarm resolver`() {
        assertEquals(
            "http://127.0.0.1:1633/bzz/abc/p",
            Gateways.toLoadable("bzz://abc/p"),
        )
    }

    @Test
    fun `toLoadable routes ipfs through ipfs gateway when base set`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "http://127.0.0.1:58312/ipfs/bafy/p",
            Gateways.toLoadable("ipfs://bafy/p"),
        )
    }

    @Test
    fun `toLoadable leaves ipfs unchanged when base empty`() {
        assertEquals(
            "ipfs://bafy/p",
            Gateways.toLoadable("ipfs://bafy/p"),
        )
    }

    @Test
    fun `toLoadable passes external urls through`() {
        assertEquals(
            "https://example.com/",
            Gateways.toLoadable("https://example.com/"),
        )
    }

    @Test
    fun `toDisplay round-trips bzz`() {
        assertEquals(
            "bzz://abc/p",
            Gateways.toDisplay("http://127.0.0.1:1633/bzz/abc/p"),
        )
    }

    @Test
    fun `toDisplay round-trips ipfs when base set`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "ipfs://bafy/p",
            Gateways.toDisplay("http://127.0.0.1:58312/ipfs/bafy/p"),
        )
    }

    @Test
    fun `toDisplay round-trips ipns when base set`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "ipns://docs.eth",
            Gateways.toDisplay("http://127.0.0.1:58312/ipns/docs.eth"),
        )
    }

    @Test
    fun `isLocalGateway recognizes swarm origin`() {
        assertTrue(Gateways.isLocalGateway("http://127.0.0.1:1633/health"))
        assertFalse(Gateways.isLocalGateway("https://example.com/health"))
    }

    @Test
    fun `isLocalGateway recognizes ipfs origin only when base set`() {
        assertFalse(Gateways.isLocalGateway("http://127.0.0.1:58312/ipfs/bafy"))
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertTrue(Gateways.isLocalGateway("http://127.0.0.1:58312/ipfs/bafy"))
    }
}
