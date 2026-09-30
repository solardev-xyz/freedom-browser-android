package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeApiGuardTest {
    private fun refused(method: String, url: String) = NodeApiGuard.refuses(method, url)

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
    fun `the node's own API is refused for reads too`() {
        for (path in listOf(
            "/addresses", "/wallet", "/stamps", "/stamps/abc", "/stamps/abc/buckets",
            "/chequebook/address", "/chequebook/balance", "/chequebook/cheque",
            "/balances", "/balances/ab", "/consumed", "/settlements", "/timesettlements",
            "/peers", "/topology", "/node", "/status", "/chainstate", "/batches",
            "/pins", "/pins/check", "/tags", "/tags/1", "/grantee/ab", "/envelope/ab",
            "/v0/manifest/ab", "/", "", "?x=1", "#bzz", "/some-future-endpoint",
        )) {
            for (method in listOf("GET", "HEAD", "OPTIONS")) {
                assertTrue("$method $path", refused(method, "http://127.0.0.1:1633$path"))
            }
        }
    }

    @Test
    fun `the dapp surface stays open`() {
        for ((method, path) in listOf(
            "GET" to "/bzz/ab/index.html",
            "HEAD" to "/bzz/ab",
            "POST" to "/bzz?name=a.txt",
            "OPTIONS" to "/bzz",
            "GET" to "/bytes/ab",
            "POST" to "/bytes",
            "GET" to "/chunks/ab",
            "POST" to "/chunks",
            "GET" to "/soc/ab/cd",
            "POST" to "/soc/ab/cd",
            "GET" to "/feeds/ab/cd",
            "POST" to "/feeds/ab/cd",
            "POST" to "/pss/send/ab/cd",
            "GET" to "/gsoc/subscribe/ab",
            "GET" to "/health",
            "GET" to "/readiness",
            "GET" to "/BZZ/ab",
            "GET" to "//bytes/ab",
        )) {
            assertFalse("$method $path", refused(method, "http://127.0.0.1:1633$path"))
        }
    }

    @Test
    fun `any host on the gateway port counts, since any name can resolve to loopback`() {
        for (host in listOf(
            "localhost", "127.0.0.1", "[::1]", "[::ffff:7f00:1]", "0.0.0.0",
            "127.0.0.1.nip.io", "anything.example", "user:pw@127.0.0.1",
        )) {
            assertTrue(host, refused("POST", "http://$host:1633/stamps/1/17"))
            assertTrue(host, refused("GET", "http://$host:1633/wallet"))
            assertFalse(host, refused("GET", "http://$host:1633/bzz/ab"))
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
        assertFalse(refused("GET", "http://127.0.0.1:5001/api/v0/id"))
        assertFalse(refused("GET", "https://bee.example/wallet"))
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
        for (path in listOf("/%61ddresses", "/WALLET", "//addresses", "/./wallet", "/%2e%2e/addresses")) {
            assertTrue(path, refused("GET", "http://127.0.0.1:1633$path"))
        }
        // Broken escapes decode as themselves, which isn't the dapp surface either.
        assertTrue(refused("POST", "http://127.0.0.1:1633/%zzstamps"))
        assertTrue(refused("POST", "http://127.0.0.1:1633/%"))
        assertTrue(refused("GET", "http://127.0.0.1:1633/%zzbzz/ab"))
    }
}
