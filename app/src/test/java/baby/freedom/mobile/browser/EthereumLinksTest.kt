package baby.freedom.mobile.browser

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EIP-681 `ethereum:` links opening the Send page (#317): a page's link and the address bar. */
class EthereumLinksTest {
    private val payee = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val xbzz = "0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da"
    private val usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val site = "https://tips.example"

    private fun route(url: String, private: Boolean = false, wallet: Boolean = true, origin: String? = site) =
        ethereumLinkRoute(url, private, wallet, origin)

    private fun send(url: String): SendPrefill {
        val r = route(url)
        assertTrue("$url → $r", r is EthereumLinkRoute.OpenSend)
        return (r as EthereumLinkRoute.OpenSend).prefill
    }

    private fun refused(url: String, private: Boolean = false, wallet: Boolean = true): String {
        val r = route(url, private, wallet)
        assertTrue("$url → $r", r is EthereumLinkRoute.Refuse)
        return (r as EthereumLinkRoute.Refuse).reason
    }

    @Test
    fun `a tip link opens Send on its chain's native currency, with payee and amount`() {
        assertEquals(
            SendPrefill(site, "100:native", payee, BigInteger("1000000000000000")),
            send("ethereum:$payee@100?value=1e15"),
        )
        assertEquals(
            SendPrefill(site, "1:native", payee, BigInteger("2014000000000000000")),
            send("ethereum:pay-${payee.lowercase()}@1?value=2.014e18"),
        )
        // No amount: Send opens with the field empty.
        assertEquals(SendPrefill(site, "100:native", payee, null), send("ethereum:$payee@100"))
    }

    @Test
    fun `a link naming no network is filled in as Ethereum, and says so`() {
        assertEquals(
            SendPrefill(site, "1:native", payee, BigInteger.TEN, chainGuess = ChainGuess.ETHEREUM_DEFAULT),
            send("ethereum:$payee?value=10"),
        )
    }

    @Test
    fun `a name as payee goes to Send to be looked up there`() {
        assertEquals(SendPrefill(site, "1:native", "vitalik.eth", BigInteger.ONE), send("ethereum:vitalik.eth@1?value=1"))
    }

    @Test
    fun `an ERC-20 transfer opens Send on that token, as far as the scanner reads one`() {
        assertEquals(
            SendPrefill(site, "100:${xbzz.lowercase()}", payee, BigInteger("15000000000000000")),
            send("ethereum:$xbzz@100/transfer?address=$payee&uint256=1.5e16"),
        )
        // A known token with no network: the one wallet chain it's on.
        assertEquals(
            SendPrefill(site, "1:${usdc.lowercase()}", payee, BigInteger("5000000"), chainGuess = ChainGuess.ONLY_CHAIN_WITH_TOKEN),
            send("ethereum:$usdc/transfer?address=$payee&uint256=5e6"),
        )
        // …which may be Gnosis Chain, and the page then names Gnosis Chain, not Ethereum (R1-F1).
        val gnosisOnly = send("ethereum:$xbzz/transfer?address=$payee&uint256=1e16")
        assertEquals(
            SendPrefill(site, "100:${xbzz.lowercase()}", payee, BigInteger("10000000000000000"), chainGuess = ChainGuess.ONLY_CHAIN_WITH_TOKEN),
            gnosisOnly,
        )
        assertEquals("Gnosis Chain", prefillChainName(gnosisOnly))
        assertTrue(refused("ethereum:$xbzz@1/transfer?address=$payee&uint256=1").contains("isn’t one Send knows"))
        assertTrue(refused("ethereum:$payee/transfer?address=$xbzz&uint256=1").contains("which network"))
    }

    @Test
    fun `anything else is said to be unsupported, never an error page`() {
        assertTrue(refused("ethereum:$xbzz/approve?address=$payee&uint256=1").contains("approve"))
        assertTrue(refused("ethereum:$payee@8453?value=1").contains("Base"))
        assertTrue(refused("ethereum:$payee@137?value=1").contains("chain ID 137"))
        assertTrue(refused("ethereum:$payee@100?value=0.5").contains("amount"))
        assertTrue(refused("ethereum:hello").startsWith("Can’t open this payment link."))
    }

    @Test
    fun `a private tab and a missing wallet get their explanation first`() {
        val private = refused("ethereum:$payee@100?value=1", private = true)
        assertTrue(private.contains("private tabs"))
        // A private tab quotes nothing from the link.
        assertFalse(refused("ethereum:vitalik.eth@1", private = true).contains("vitalik"))
        assertTrue(refused("ethereum:$payee@100?value=1", wallet = false).contains("set up a wallet"))
        assertTrue(refused("ethereum:$payee@100?value=1", private = true, wallet = false).contains("private tabs"))
    }

    @Test
    fun `the address bar opens a typed or pasted link for the user, with no site`() {
        assertEquals(
            EthereumLinkRoute.OpenSend(SendPrefill(null, "100:native", payee, BigInteger("1000000000000000"))),
            addressBarEthereumLink("  ethereum:$payee@100?value=1e15 ", SubmitSource.User, private = false, walletReady = true),
        )
        assertEquals(
            EthereumLinkRoute.OpenSend(SendPrefill(null, "100:native", payee, null)),
            addressBarEthereumLink("ETHEREUM:$payee@100", SubmitSource.User, private = false, walletReady = true),
        )
        assertTrue(
            addressBarEthereumLink("ethereum:$payee", SubmitSource.User, private = true, walletReady = true) is EthereumLinkRoute.Refuse,
        )
        assertTrue(
            addressBarEthereumLink("ethereum:$payee", SubmitSource.User, private = false, walletReady = false) is EthereumLinkRoute.Refuse,
        )
    }

    @Test
    fun `the address bar drops a link nobody typed, and loads everything else as before`() {
        assertEquals(
            EthereumLinkRoute.Drop,
            addressBarEthereumLink("ethereum:$payee@100", SubmitSource.Renderer, private = false, walletReady = true),
        )
        assertEquals(
            EthereumLinkRoute.Drop,
            addressBarEthereumLink("ethereum:$payee@100", SubmitSource.External, private = false, walletReady = true),
        )
        assertNull(addressBarEthereumLink("https://example.com/", SubmitSource.User, private = false, walletReady = true))
        assertNull(addressBarEthereumLink("vitalik.eth", SubmitSource.User, private = false, walletReady = true))
        assertNull(addressBarEthereumLink(payee, SubmitSource.User, private = false, walletReady = true))
    }

    @Test
    fun `a page's link takes the app-link gate - main frame and one tap each - and is never handed to an app`() {
        fun verdict(main: Boolean, gesture: Boolean, tapped: Boolean = true, userNamed: Boolean = false): Pair<ExternalLinkVerdict, Boolean> {
            var consumed = false
            val v = externalLinkVerdict("ethereum:$payee@100?value=1", main, gesture, userNamed) { consumed = true; tapped }
            return v to consumed
        }
        assertEquals(ExternalLinkVerdict.Ask to true, verdict(main = true, gesture = true))
        // A script's navigation with no tap, or a tap already used up.
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict(main = true, gesture = false))
        assertEquals(ExternalLinkVerdict.Refuse to true, verdict(main = true, gesture = true, tapped = false))
        // An iframe's own navigation.
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict(main = false, gesture = true))
        assertEquals(ExternalLinkVerdict.AskUserNamed to false, verdict(main = true, gesture = false, userNamed = true))
        // Never another app's.
        assertNull(externalLinkScheme("ethereum:$payee"))
        assertFalse(isExternalSchemeAllowed("ethereum"))
        assertNull(ExternalScheme.forKey("external:ethereum"))
        assertNull(externalAppLaunch("ethereum:$payee", "baby.freedom.mobile"))
        assertTrue(isEthereumLink("Ethereum:$payee"))
        assertFalse(isEthereumLink("https://ethereum.org/"))
    }

    @Test
    fun `leaving a link's Send page pauses the tab only once the user has seen it`() {
        assertEquals(EthAnswer.Approved(), linkSendAnswer(started = true, shown = true))
        assertEquals(EthAnswer.Rejected, linkSendAnswer(started = false, shown = true))
        // Closed while the wallet was still loading: nothing was turned down (R1-M3).
        assertEquals(EthAnswer.Unseen, linkSendAnswer(started = false, shown = false))
    }

    @Test
    fun `R2-M3 - a payment link on a content gateway's own origin names nobody as asking`() {
        try {
            Gateways.setIpfsBase("http://127.0.0.1:58312")
            // Every root loaded through the raw gateway shares this origin: not the asker.
            assertNull(paymentLinkAsker("http://127.0.0.1:1633/bzz/aaaa/"))
            assertNull(paymentLinkAsker("http://localhost:1633/bzz/aaaa/"))
            assertNull(paymentLinkAsker("http://127.0.0.1:58312/ipfs/bafy/"))
            // No web origin, as before.
            assertNull(paymentLinkAsker("data:text/html,hi"))
            assertNull(paymentLinkAsker(null))
            // A site, and a dev server on another loopback port, still ask in their own name.
            assertEquals("https://shop.example", paymentLinkAsker("https://shop.example/checkout"))
            assertEquals("http://localhost:8730", paymentLinkAsker("http://localhost:8730/"))
        } finally {
            Gateways.setIpfsBase("")
        }
    }
}
