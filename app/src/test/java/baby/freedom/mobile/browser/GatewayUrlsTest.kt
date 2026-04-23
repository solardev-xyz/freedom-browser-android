package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayUrlsTest {

    @Test
    fun `bzz base`() {
        val b = GatewayUrls.extractBase("http://127.0.0.1:1633/bzz/abc123/some/path")
        assertEquals(
            GatewayUrls.Base(
                prefix = "http://127.0.0.1:1633/bzz/abc123",
                protocol = "bzz",
                hashOrCid = "abc123",
            ),
            b,
        )
    }

    @Test
    fun `ipfs base`() {
        val b = GatewayUrls.extractBase("http://127.0.0.1:5080/ipfs/bafybeigdy/p")
        assertEquals(
            GatewayUrls.Base(
                prefix = "http://127.0.0.1:5080/ipfs/bafybeigdy",
                protocol = "ipfs",
                hashOrCid = "bafybeigdy",
            ),
            b,
        )
    }

    @Test
    fun `ipns base`() {
        val b = GatewayUrls.extractBase("http://127.0.0.1:5080/ipns/docs.eth/docs")
        assertEquals(
            GatewayUrls.Base(
                prefix = "http://127.0.0.1:5080/ipns/docs.eth",
                protocol = "ipns",
                hashOrCid = "docs.eth",
            ),
            b,
        )
    }

    @Test
    fun `external url returns null`() {
        assertNull(GatewayUrls.extractBase("https://example.com/bzz/xyz"))
        // Wrong shape — `/bzz` without hash.
        assertNull(GatewayUrls.extractBase("http://127.0.0.1:1633/bzz"))
        assertNull(GatewayUrls.extractBase("http://127.0.0.1:1633/health"))
    }

    @Test
    fun `extractRoot includes trailing slash`() {
        assertEquals(
            "http://127.0.0.1:1633/bzz/deadbeef/",
            GatewayUrls.extractRoot("http://127.0.0.1:1633/bzz/deadbeef/page/index.html"),
        )
        assertEquals(
            "http://127.0.0.1:5080/ipfs/bafy/",
            GatewayUrls.extractRoot("http://127.0.0.1:5080/ipfs/bafy/index.html"),
        )
    }

    @Test
    fun `extractRoot null for non-gateway`() {
        assertNull(GatewayUrls.extractRoot(null))
        assertNull(GatewayUrls.extractRoot("https://example.com/"))
        // No path after the hash → no root.
        assertNull(GatewayUrls.extractRoot("http://127.0.0.1:1633/bzz/abc"))
    }
}
