package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsTrust
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust shield's state (#97): which tier a name-addressed page gets,
 * which pages get none, and the typed-scheme assertion a tab carries.
 */
class TrustShieldTest {

    @After
    fun tearDown() {
        KnownEnsNames.clear()
    }

    private val verified = EnsTrust(
        verified = true,
        agreed = listOf("eth.llamarpc.com", "rpc.flashbots.net"),
        dissented = listOf("liar.example"),
        block = 21_000_000L,
    )
    private val unverified = EnsTrust(verified = false, agreed = listOf("eth.llamarpc.com"), block = 21_000_001L)

    @Test
    fun `a verified answer gets the verified shield, with its evidence in words`() {
        KnownEnsNames.record("ipfs://$CID", "vitalik.eth", verified)
        val trust = nameTrustFor("ipfs://vitalik.eth/about")!!
        assertEquals("vitalik.eth", trust.name)
        assertEquals(TrustTier.Verified, trust.tier)
        assertEquals(
            "2 independent RPC servers returned the same ENS record for vitalik.eth at block #21000000.",
            trust.summary,
        )
    }

    @Test
    fun `one server's answer gets the unverified shield and names the server`() {
        KnownEnsNames.record("bzz://$REF", "alice.wei", unverified)
        val trust = nameTrustFor("bzz://alice.wei")!!
        assertEquals(TrustTier.Unverified, trust.tier)
        assertTrue(trust.summary, trust.summary.startsWith("Only eth.llamarpc.com answered for alice.wei"))
        assertTrue(trust.summary, trust.summary.contains("WNS record"))
    }

    @Test
    fun `every display form of a name finds its trust`() {
        KnownEnsNames.record("bzz://$REF", "swarm.eth", verified)
        for (display in listOf("swarm.eth", "swarm.eth/docs", "bzz://swarm.eth/x", "ens://SWARM.eth", "ipns://swarm.eth")) {
            assertEquals(display, "swarm.eth", nameTrustFor(display)?.name)
        }
    }

    @Test
    fun `pages that aren't a name's get no shield`() {
        KnownEnsNames.record("bzz://$REF", "swarm.eth", verified)
        assertNull(nameTrustFor("https://example.com/"))
        assertNull(nameTrustFor("bzz://$REF/index.html"))
        assertNull(nameTrustFor(""))
        // A name never resolved this session: nothing to vouch for.
        assertNull(nameTrustFor("other.eth"))
    }

    @Test
    fun `a forgotten name loses its shield`() {
        KnownEnsNames.record("bzz://$REF", "swarm.eth", verified)
        KnownEnsNames.forgetName("swarm.eth")
        assertNull(nameTrustFor("bzz://swarm.eth"))
    }

    @Test
    fun `error pages and refused documents get no shield`() {
        KnownEnsNames.record("bzz://$REF", "swarm.eth", verified)
        val refusal = NameRefusalSlot()
        val virtual = VirtualOrigin.toVirtualUrl("ens://swarm.eth/")!!
        assertEquals(TrustTier.Verified, committedNameTrust(virtual, "bzz://swarm.eth/", refusal)?.tier)
        val error = ErrorPage.url("ens_wrong_protocol", displayUrl = "ipfs://swarm.eth")
        assertNull(committedNameTrust(error, "ipfs://swarm.eth", refusal))
        refusal.onMainFrameResponse(virtual, "ens_wrong_protocol")
        assertNull(committedNameTrust(virtual, "bzz://swarm.eth/", refusal))
    }

    @Test
    fun `a tab asserts a transport only for the name its typed override is on`() {
        val state = BrowserState(id = 1L)
        assertNull(state.assertedProtocolFor("swarm.eth"))
        val origin = VirtualOrigin.originFor(ContentRoot.Ens("swarm.eth"))!!
        state.override = BrowserState.Override(baseUrl = origin, prefix = "bzz://swarm.eth")
        assertEquals("bzz", state.assertedProtocolFor("swarm.eth"))
        assertEquals("bzz", state.assertedProtocolFor("Swarm.eth"))
        assertNull(state.assertedProtocolFor("other.eth"))
        // A bare name asserts nothing.
        state.override = BrowserState.Override(baseUrl = origin, prefix = "swarm.eth")
        assertNull(state.assertedProtocolFor("swarm.eth"))
    }

    @Test
    fun `reload of a generic load keeps it generic, of a typed one keeps the assertion`() {
        KnownEnsNames.record("ipfs://$CID", "vitalik.eth", verified)
        val state = BrowserState(id = 1L)
        val origin = VirtualOrigin.originFor(ContentRoot.Ens("vitalik.eth"))!!
        state.override = BrowserState.Override(baseUrl = origin, prefix = "vitalik.eth")
        state.url = "ipfs://vitalik.eth/about"
        // The bare form, which [BrowserState.effectiveFetchUrl] maps onto
        // the loaded manifest — not a new `ipfs://` assertion.
        assertEquals("vitalik.eth/about", state.reloadUrl())
        assertEquals("$origin/about", state.effectiveFetchUrl(state.reloadUrl()))

        state.override = BrowserState.Override(baseUrl = origin, prefix = "ipfs://vitalik.eth")
        assertEquals("ipfs://vitalik.eth/about", state.reloadUrl())

        state.override = null
        state.url = "https://example.com/"
        assertEquals("https://example.com/", state.reloadUrl())
    }

    @Test
    fun `the wrong-transport page is told where the name does resolve`() {
        val url = ErrorPage.url(
            errorCode = "ens_wrong_protocol",
            displayUrl = "bzz://vitalik.eth",
            retryUrl = "ipfs://vitalik.eth",
            resolvedProtocol = "ipfs",
        )
        assertEquals("ipfs", ErrorPage.paramFor(url, "resolved"))
        assertEquals("ipfs://vitalik.eth", ErrorPage.paramFor(url, "retry"))
        assertNull(ErrorPage.paramFor(ErrorPage.url("ens_not_found", "x.eth"), "resolved"))
    }

    @Test
    fun `reload keeps the transport the bar showed, not the one the name has moved to`() {
        // R1-F1: shown as bzz://, then another lookup moved the name.
        KnownEnsNames.record("bzz://$REF", "name.eth", verified)
        val state = BrowserState(id = 1L)
        val origin = VirtualOrigin.originFor(ContentRoot.Ens("name.eth"))!!
        state.override = BrowserState.Override(baseUrl = origin, prefix = "name.eth")
        state.url = "bzz://name.eth/"
        KnownEnsNames.record("ipfs://$CID", "name.eth", verified)
        assertEquals("name.eth/", state.reloadUrl())
        assertEquals("$origin/", state.effectiveFetchUrl("bzz://name.eth/"))
    }

    @Test
    fun `editing the shown generic address stays generic, typing another scheme asserts`() {
        // R1-F4.
        KnownEnsNames.record("ipfs://$CID", "vitalik.eth", verified)
        val state = BrowserState(id = 1L)
        val origin = VirtualOrigin.originFor(ContentRoot.Ens("vitalik.eth"))!!
        state.override = BrowserState.Override(baseUrl = origin, prefix = "vitalik.eth")
        state.url = "ipfs://vitalik.eth/"
        assertEquals("$origin/general/", state.effectiveFetchUrl("ipfs://vitalik.eth/general/"))
        assertEquals("$origin/general/", state.effectiveFetchUrl("vitalik.eth/general/"))
        assertEquals("$origin?q=1", state.effectiveFetchUrl("ipfs://vitalik.eth?q=1"))
        assertEquals("bzz://vitalik.eth/", state.effectiveFetchUrl("bzz://vitalik.eth/"))
        assertEquals("ipfs://vitalik.ethx/", state.effectiveFetchUrl("ipfs://vitalik.ethx/"))
        assertEquals("vitalik.ethx", state.effectiveFetchUrl("vitalik.ethx"))
        // …and the load it maps to keeps the tab's override.
        assertTrue(state.isUnderOverride("$origin/general/"))
        assertTrue(!state.isUnderOverride("${origin}x/"))
        assertTrue(!state.isUnderOverride("https://example.com/"))
    }

    @Test
    fun `a hash the name no longer resolves to gets no shield`() {
        // R1-F2: name.eth moved from A to B.
        val a = REF
        val b = "1".repeat(64)
        KnownEnsNames.record("bzz://$a", "name.eth", unverified)
        KnownEnsNames.record("bzz://$b", "name.eth", verified)
        assertNull(KnownEnsNames.nameFor(a))
        val refusal = NameRefusalSlot()
        val rawA = VirtualOrigin.toVirtualUrl("bzz://$a/")!!
        val rawB = VirtualOrigin.toVirtualUrl("bzz://$b/")!!
        assertEquals("bzz://$a", DisplayUrl.forActualUrl(rawA, null))
        assertNull(committedNameTrust(rawA, "bzz://$a/", refusal))
        // Even a display that still says the name.
        assertNull(committedNameTrust(rawA, "bzz://name.eth/", refusal))
        assertEquals(TrustTier.Verified, committedNameTrust(rawB, "bzz://name.eth/", refusal)?.tier)
    }

    @Test
    fun `the shield keeps the answer it was taken with`() {
        // R1-F3.
        KnownEnsNames.record("bzz://$REF", "name.eth", unverified)
        val shown = nameTrustFor("bzz://name.eth")!!
        KnownEnsNames.record("ipfs://$CID", "name.eth", verified)
        assertEquals("bzz://$REF", shown.answer)
        assertEquals(unverified, shown.trust)
        assertEquals("ipfs://$CID", nameTrustFor("ipfs://name.eth")!!.answer)
    }

    private companion object {
        const val REF = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
        const val CID = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
    }
}
