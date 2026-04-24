package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpfsGatewayTest {

    @Test
    fun `ipfs toLoadable prepends base`() {
        assertEquals(
            "http://127.0.0.1:58312/ipfs/bafybeigdy/p",
            IpfsGateway.toLoadable("ipfs://bafybeigdy/p", "http://127.0.0.1:58312"),
        )
    }

    @Test
    fun `ipns toLoadable prepends base`() {
        assertEquals(
            "http://127.0.0.1:58312/ipns/docs.eth/",
            IpfsGateway.toLoadable("ipns://docs.eth/", "http://127.0.0.1:58312"),
        )
    }

    @Test
    fun `empty base leaves ipfs url unchanged`() {
        assertEquals(
            "ipfs://bafy/p",
            IpfsGateway.toLoadable("ipfs://bafy/p", ""),
        )
    }

    @Test
    fun `toLoadable passes through non-ipfs urls`() {
        assertEquals(
            "https://example.com/",
            IpfsGateway.toLoadable("https://example.com/", "http://127.0.0.1:58312"),
        )
    }

    @Test
    fun `ipfs toDisplay strips base`() {
        assertEquals(
            "ipfs://bafy/p",
            IpfsGateway.toDisplay("http://127.0.0.1:58312/ipfs/bafy/p", "http://127.0.0.1:58312"),
        )
    }

    @Test
    fun `ipns toDisplay strips base`() {
        assertEquals(
            "ipns://docs.eth",
            IpfsGateway.toDisplay("http://127.0.0.1:58312/ipns/docs.eth", "http://127.0.0.1:58312"),
        )
    }

    @Test
    fun `toDisplay passes through when base unrelated`() {
        assertEquals(
            "https://example.com/ipfs/bafy",
            IpfsGateway.toDisplay("https://example.com/ipfs/bafy", "http://127.0.0.1:58312"),
        )
    }

    @Test
    fun `isIpfsScheme`() {
        assertTrue(IpfsGateway.isIpfsScheme("ipfs://bafy"))
        assertTrue(IpfsGateway.isIpfsScheme("ipns://docs.eth"))
        assertFalse(IpfsGateway.isIpfsScheme("bzz://abc"))
        assertFalse(IpfsGateway.isIpfsScheme("https://example.com"))
    }
}
