package baby.freedom.mobile.chains

import baby.freedom.mobile.browser.Icu4jUts46
import baby.freedom.mobile.browser.WhatwgHost
import baby.freedom.mobile.chains.RpcUrls.Rejection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RpcUrlsTest {
    @Before
    fun icu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    private fun rejection(url: String) = RpcUrls.validate(url).rejection

    @Test
    fun acceptsPublicHttpsAsTyped() {
        for (url in listOf(
            "https://eth.drpc.org",
            "https://1rpc.io/eth",
            "https://rpc.example.org:8545/v1?chain=1",
            "https://RPC.Example.org/",
            "https://[2606:4700::6810:84e5]/",
            "https://8.8.8.8",
            "https://xn--bcher-kva.example/rpc",
        )) {
            assertEquals(url, url, RpcUrls.normalize(url))
        }
        assertEquals("https://eth.drpc.org", RpcUrls.normalize("  https://eth.drpc.org \n"))
    }

    @Test
    fun httpOnlyToLoopback() {
        assertEquals(Rejection.SCHEME, rejection("http://eth.drpc.org"))
        for (url in listOf(
            "http://localhost:8545",
            "http://127.0.0.1:8545",
            "http://127.9.8.7",
            "http://[::1]:8545",
            "http://node.localhost",
            "https://localhost:8545",
        )) {
            assertEquals(url, url, RpcUrls.normalize(url))
        }
        assertEquals(Rejection.SCHEME, rejection("ws://localhost:8546"))
        assertEquals(Rejection.SCHEME, rejection("wss://eth.example.org"))
        assertEquals(Rejection.SCHEME, rejection("ftp://eth.example.org"))
    }

    @Test
    fun aDnsNameStartingWith127IsNotLoopback() {
        assertEquals(Rejection.SCHEME, rejection("http://127.0.0.1.evil.example"))
        assertEquals(Rejection.SCHEME, rejection("http://127.tracker.example"))
    }

    @Test
    fun refusesInternalHosts() {
        for (url in listOf(
            "https://10.0.0.1",
            "https://192.168.1.10:8545",
            "https://172.16.0.1",
            "https://169.254.169.254",
            "https://100.64.0.1",
            "https://0.0.0.0",
            "https://224.0.0.1",
            "https://[fd00::1]",
            "https://[fe80::1]",
            "https://[::ffff:c0a8:10a]",
            "https://[64:ff9b::a00:1]",
            "https://[2002:a00:1::]",
            "https://[::]",
            "https://nas.local",
            "https://nas",
            // Names that spell a loopback/LAN address for wildcard DNS to resolve.
            "https://127.0.0.1.nip.io",
            "https://10.0.0.1.nip.io/",
            "https://rpc.192.168.1.10.xip.io",
            "https://10-0-0-1.sslip.io",
            "https://app-192-168-0-1.traefik.me",
            "https://magic-0a000001.nip.io",
            "https://7f000001.nip.io",
            "https://0--1.sslip.io",
            "https://fe80--1.sslip.io",
            "https://localtest.me",
            "https://rpc.lvh.me",
        )) {
            assertEquals(url, Rejection.INTERNAL_HOST, rejection(url))
        }
        // …and plain `http` to one is still not loopback.
        assertEquals(Rejection.SCHEME, rejection("http://127.0.0.1.nip.io:8545"))
        // A public address spelled the same way is just a public name.
        for (url in listOf(
            "https://8.8.8.8.nip.io",
            "https://1-1-1-1.sslip.io",
            "https://studiochain-cf4a1621.calderachain.xyz",
            "https://dchain-2716446429837000-1.jsonrpc.sagarpc.io",
            "https://rpc-e4a1b2c3.example.org",
            "https://rpc-10-0.example.org",
        )) {
            assertEquals(url, url, RpcUrls.normalize(url))
        }
    }

    @Test
    fun refusesPlaceholdersAndCredentials() {
        assertEquals(Rejection.PLACEHOLDER, rejection("https://mainnet.infura.io/v3/\${INFURA_API_KEY}"))
        assertEquals(Rejection.PLACEHOLDER, rejection("https://eth-mainnet.g.alchemy.com/v2/{API_KEY}"))
        assertEquals(Rejection.CREDENTIALS, rejection("https://user:pass@rpc.example.org"))
        assertEquals(Rejection.CREDENTIALS, rejection("https://user@rpc.example.org"))
    }

    @Test
    fun refusesWhatTheTwoParsersReadDifferently() {
        // WHATWG reads these as a public host; java.net.URI (what the
        // app connects with) reads a different one, or none.
        assertEquals(Rejection.NOT_A_URL, rejection("https:\\\\rpc.example.org"))
        assertEquals(Rejection.NOT_A_URL, rejection("https:rpc.example.org"))
        assertEquals(Rejection.NOT_A_URL, rejection("https://127.1"))
        assertEquals(Rejection.NOT_A_URL, rejection("https://0x7f000001"))
        assertEquals(Rejection.NOT_A_URL, rejection("https://%6cocalhost"))
        assertEquals(Rejection.NOT_A_URL, rejection("https://rpc.example.org\\@10.0.0.1/"))
        assertEquals(Rejection.NOT_A_URL, rejection("https://rpc example.org"))
        assertEquals(Rejection.NON_ASCII, rejection("https://bücher.example"))
        assertEquals(Rejection.NON_ASCII, rejection("http://᠆localhost/"))
        assertEquals(Rejection.EMPTY, rejection("   "))
        assertEquals(Rejection.NOT_A_URL, rejection("rpc.example.org"))
        assertEquals(Rejection.TOO_LONG, rejection("https://a.example/" + "a".repeat(RpcUrls.MAX_LENGTH)))
    }
}
