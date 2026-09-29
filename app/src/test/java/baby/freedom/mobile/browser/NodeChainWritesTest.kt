package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeChainWritesTest {
    private fun refused(method: String, url: String) = NodeChainWrites.refuses(method, url)

    @Test
    fun `every on-chain write to the node is refused`() {
        for ((method, path) in listOf(
            "POST" to "/stamps/10000000/17",
            "PATCH" to "/stamps/topup/abc/100",
            "PATCH" to "/stamps/dilute/abc/18",
            "POST" to "/chequebook/deposit?amount=1",
            "POST" to "/chequebook/withdraw?amount=1",
            "POST" to "/chequebook/cashout/abc",
            "POST" to "/stake/1",
            "DELETE" to "/stake",
            "POST" to "/wallet/withdraw/nativetoken",
            "POST" to "/transactions/0xab",
            "DELETE" to "/transactions/0xab",
            "OPTIONS" to "/stamps/1/17",
            "PUT" to "/stamps",
            "post" to "/stamps/1/17",
        )) {
            assertTrue("$method $path", refused(method, "http://127.0.0.1:1633$path"))
        }
    }

    @Test
    fun `reads and the upload write path stay open`() {
        assertFalse(refused("GET", "http://127.0.0.1:1633/stamps"))
        assertFalse(refused("HEAD", "http://127.0.0.1:1633/wallet"))
        assertFalse(refused("GET", "http://127.0.0.1:1633/chequebook/address"))
        assertFalse(refused("POST", "http://127.0.0.1:1633/bzz?name=a.txt"))
        assertFalse(refused("POST", "http://127.0.0.1:1633/bytes"))
        assertFalse(refused("POST", "http://127.0.0.1:1633/soc/ab/cd"))
        assertFalse(refused("POST", "http://127.0.0.1:1633/feeds/ab/cd"))
        assertFalse(refused("OPTIONS", "http://127.0.0.1:1633/bzz"))
        assertFalse(refused("POST", "http://127.0.0.1:1633/"))
        assertFalse(refused("POST", "http://127.0.0.1:1633"))
    }

    @Test
    fun `any host on the gateway port counts, since any name can resolve to loopback`() {
        for (host in listOf(
            "localhost", "127.0.0.1", "[::1]", "[::ffff:7f00:1]", "0.0.0.0",
            "127.0.0.1.nip.io", "anything.example", "user:pw@127.0.0.1",
        )) {
            assertTrue(host, refused("POST", "http://$host:1633/stamps/1/17"))
        }
        assertTrue(refused("POST", "https://127.0.0.1:1633/stamps/1/17"))
        assertTrue(refused("POST", "http://127.0.0.1:01633/stamps/1/17"))
    }

    @Test
    fun `other ports and schemes are not the node`() {
        assertFalse(refused("POST", "http://127.0.0.1/stamps/1/17"))
        assertFalse(refused("POST", "http://127.0.0.1:1634/stamps/1/17"))
        assertFalse(refused("POST", "https://bee.example/stamps/1/17"))
        assertFalse(refused("POST", "http://127.0.0.1:16330/stamps/1/17"))
        assertFalse(refused("POST", "http://127.0.0.1:99999999999999/stamps/1/17"))
        assertFalse(refused("POST", "ws://127.0.0.1:1633/stamps/1/17"))
        assertFalse(refused("POST", "not a url"))
        // A port in the path or query isn't the authority's.
        assertFalse(refused("POST", "http://example.com/x:1633/stamps"))
        assertFalse(refused("POST", "http://example.com?h=a:1633/stamps"))
    }

    @Test
    fun `the endpoint can't hide behind encoding, case or empty and dot segments`() {
        for (path in listOf(
            "//stamps/1/17", "/./stamps/1/17", "/%2e%2e/stamps/1/17", "/%73tamps/1/17",
            "/STAMPS/1/17", "/Chequebook/deposit", "/%2Fstamps/1/17", "\\stamps/1/17",
        )) {
            assertTrue(path, refused("POST", "http://127.0.0.1:1633$path"))
        }
        // Broken escapes decode as themselves.
        assertFalse(refused("POST", "http://127.0.0.1:1633/%zzstamps"))
        assertFalse(refused("POST", "http://127.0.0.1:1633/%"))
    }
}
