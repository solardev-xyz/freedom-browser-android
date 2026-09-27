package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.ExternalEndpoints.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEndpointsTest {

    private fun rejection(raw: String) = ExternalEndpoints.validate(raw).rejection

    @Test
    fun `normalizes base urls`() {
        assertEquals("http://192.168.1.10:1633", ExternalEndpoints.normalize(" http://192.168.1.10:1633/ "))
        assertEquals("https://gw.example", ExternalEndpoints.normalize("HTTPS://GW.Example"))
        assertEquals("https://gw.example/sub", ExternalEndpoints.normalize("https://gw.example/sub//"))
        assertEquals("http://[::1]:8080", ExternalEndpoints.normalize("http://[::1]:8080"))
    }

    @Test
    fun `a bare host and port gets http`() {
        assertEquals("http://127.0.0.1:8080", ExternalEndpoints.normalize("127.0.0.1:8080"))
        assertEquals("http://bee.local:1633", ExternalEndpoints.normalize("bee.local:1633"))
    }

    @Test
    fun `refuses what can't be a gateway base`() {
        assertEquals(Rejection.EMPTY, rejection("  "))
        assertEquals(Rejection.SCHEME, rejection("ftp://gw.example"))
        assertEquals(Rejection.SCHEME, rejection("javascript://x"))
        assertEquals(Rejection.NOT_A_URL, rejection("http://"))
        assertEquals(Rejection.NOT_A_URL, rejection("http://bad host"))
        assertEquals(Rejection.QUERY_OR_FRAGMENT, rejection("https://gw.example/?x=1"))
        assertEquals(Rejection.QUERY_OR_FRAGMENT, rejection("https://gw.example/#top"))
        assertEquals(Rejection.QUERY_OR_FRAGMENT, rejection("https://gw.example/?"))
        assertEquals(Rejection.CREDENTIALS, rejection("https://me:pw@gw.example"))
        assertEquals(Rejection.TOO_LONG, rejection("https://gw.example/" + "a".repeat(3000)))
        assertNull(ExternalEndpoints.normalize("ftp://gw.example"))
    }

    @Test
    fun `a mistyped scheme is refused, not turned into a host named http`() {
        assertEquals(Rejection.NOT_A_URL, rejection("http:/nas:1633"))
        assertEquals(Rejection.NOT_A_URL, rejection("https:nas"))
        assertEquals(Rejection.NOT_A_URL, rejection("http//nas:1633"))
        assertEquals(Rejection.SCHEME, rejection("ftp:gw.example"))
        assertEquals("http://localhost", ExternalEndpoints.normalize("localhost"))
    }

    @Test
    fun `underscore hostnames are accepted`() {
        assertEquals("http://my_node:1633", ExternalEndpoints.normalize("my_node:1633"))
        assertEquals("https://my_node.lan/api", ExternalEndpoints.normalize("https://My_Node.lan/api/"))
        assertEquals(Rejection.NOT_A_URL, rejection("my_node:99999"))
        assertEquals(Rejection.CREDENTIALS, rejection("http://me@my_node:1633"))
    }

    @Test
    fun `the ipfs row carries the unverified warning only while external`() {
        val embedded = nodeRows("", "", showIpfsUi = true).single { it.key == "ipfs" }
        assertFalse(embedded.texts.any { it.startsWith("Unverified") })
        val external = nodeRows("", "https://gw.example", showIpfsUi = true).single { it.key == "ipfs" }
        assertTrue(external.texts.contains(ExternalEndpoints.IPFS_UNVERIFIED_WARNING))
    }

    @Test
    fun `the ipfs row stays listed while an external gateway is in use`() {
        assertEquals(listOf("swarm"), nodeRows("", "", showIpfsUi = false).map { it.key })
        assertEquals(
            listOf("swarm", "ipfs"),
            nodeRows("", "https://gw.example", showIpfsUi = false).map { it.key },
        )
    }
}
