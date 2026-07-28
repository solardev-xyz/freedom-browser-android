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

    private val ref64 = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
    private val bzzLabel = "3kescpgjpg23w0jk9ccszdtmgq3mcqnthwg1oxbfcnb6w68t71"

    @Test
    fun `toLoadable maps bzz to its virtual origin`() {
        assertEquals(
            "https://$bzzLabel.bzz.freedom.baby/p",
            Gateways.toLoadable("bzz://$ref64/p"),
        )
    }

    @Test
    fun `toLoadable maps ipfs and ens to virtual origins`() {
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        assertEquals(
            "https://$cid.ipfs.freedom.baby/p",
            Gateways.toLoadable("ipfs://$cid/p"),
        )
        assertEquals(
            "https://vitalik-eth.ens.freedom.baby/",
            Gateways.toLoadable("ens://vitalik.eth"),
        )
    }

    @Test
    fun `toLoadable falls back to the gateway for malformed refs`() {
        // "abc" can't be label-encoded (not a 64/128-hex ref) — fall
        // back to the direct gateway URL so its error surfaces instead
        // of ERR_UNKNOWN_URL_SCHEME.
        assertEquals(
            "http://127.0.0.1:1633/bzz/abc/p",
            Gateways.toLoadable("bzz://abc/p"),
        )
    }

    @Test
    fun `toGatewayUrl keeps the direct mapping for the probe`() {
        assertEquals(
            "http://127.0.0.1:1633/bzz/$ref64/p",
            Gateways.toGatewayUrl("bzz://$ref64/p"),
        )
        assertEquals(
            "http://127.0.0.1:1633/bzz/$ref64/",
            Gateways.toGatewayUrl("bzz://$ref64"),
        )
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "http://127.0.0.1:58312/ipfs/bafy/p",
            Gateways.toGatewayUrl("ipfs://bafy/p"),
        )
    }

    @Test
    fun `toGatewayUrl leaves ipfs unchanged when base empty`() {
        assertEquals(
            "ipfs://bafy/p",
            Gateways.toGatewayUrl("ipfs://bafy/p"),
        )
    }

    @Test
    fun `gatewayUrlFor maps roots onto the gateways`() {
        assertEquals(
            "http://127.0.0.1:1633/bzz/$ref64/x?q=1",
            Gateways.gatewayUrlFor(ContentRoot.Bzz(ref64), "/x?q=1"),
        )
        // No IPFS gateway yet → null, so the interceptor synthesizes a
        // clean error instead of fetching nowhere.
        assertEquals(
            null,
            Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/"),
        )
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "http://127.0.0.1:58312/ipfs/bafy/",
            Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/"),
        )
        assertEquals(
            "http://127.0.0.1:58312/ipns/ipfs.tech/",
            Gateways.gatewayUrlFor(ContentRoot.IpnsName("ipfs.tech"), "/"),
        )
    }

    @Test
    fun `gatewayUrlFor resolves ens roots from the session registry`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        try {
            assertEquals(
                "http://127.0.0.1:1633/bzz/$ref64/p",
                Gateways.gatewayUrlFor(ContentRoot.Ens("swarm.eth"), "/p"),
            )
        } finally {
            KnownEnsNames.clear()
        }
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
