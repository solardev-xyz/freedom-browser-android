package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a proven answer gets the proof's seal, above the quorum's shield, and names the prover`() {
        // #100: a Colibri proof checked on this device.
        val proven = EnsTrust(
            verified = true,
            agreed = listOf("mainnet1.colibri-proof.tech"),
            source = EnsTrust.Source.COLIBRI,
        )
        KnownEnsNames.record("ipfs://$CID", "vitalik.eth", proven)
        val trust = nameTrustFor("ipfs://vitalik.eth")!!
        assertEquals(TrustTier.Proven, trust.tier)
        assertEquals("Proven name", trust.tier.title)
        assertTrue(TrustTier.Proven.ordinal < TrustTier.Verified.ordinal)
        assertTrue(TrustTier.Proven.icon != TrustTier.Verified.icon)
        assertEquals(
            "This device checked a proof from mainnet1.colibri-proof.tech against Ethereum's sync committee: " +
                "vitalik.eth's ENS record is what the chain itself holds at the latest block, " +
                "not just what RPC servers agree on.",
            trust.summary,
        )
        // A CCIP-Read answer: the proof covers the resolver's acceptance, not an on-chain record.
        KnownEnsNames.record("ipfs://$CID", "vitalik.eth", proven.copy(offchain = true))
        val offchain = nameTrustFor("ipfs://vitalik.eth")!!
        assertEquals(TrustTier.Proven, offchain.tier)
        assertEquals(
            "vitalik.eth's ENS record comes from an off-chain gateway (CCIP-Read). This device checked a " +
                "proof from mainnet1.colibri-proof.tech against Ethereum's sync committee that the name's " +
                "resolver contract accepted that answer at the latest block; the record itself isn't on chain.",
            offchain.summary,
        )
        // Servers agreeing is still the verified shield, never the seal.
        KnownEnsNames.record("ipfs://$CID", "vitalik.eth", verified)
        assertEquals(TrustTier.Verified, nameTrustFor("vitalik.eth")!!.tier)
    }

    @Test
    fun `a Myotis light-client answer gets the proven seal and says it ran on this device`() {
        val myotis = EnsTrust(
            verified = true,
            agreed = listOf(baby.freedom.mobile.ens.EnsResolver.LIGHT_CLIENT_SOURCE),
            block = 21_000_000L,
            source = EnsTrust.Source.MYOTIS,
        )
        assertTrue(myotis.lightClient)
        assertTrue(myotis.proven)
        KnownEnsNames.record("ipfs://$CID", "vitalik.eth", myotis)
        val trust = nameTrustFor("ipfs://vitalik.eth")!!
        assertEquals(TrustTier.Proven, trust.tier)
        assertEquals(
            "The Myotis light client on this device read the ENS record for vitalik.eth at block #21000000 " +
                "and checked it against Ethereum state proofs signed off by the chain's sync committee. " +
                "No RPC server's word was involved.",
            trust.summary,
        )
        // An unverified Myotis label can't exist, but mustn't pass for proven either.
        assertFalse(myotis.copy(verified = false).proven)
        // Cached as a key, named in the app language of the moment (#313 R1-M3).
        assertEquals(listOf("Myotis light client (on this device)"), myotis.shownAgreed)
        baby.freedom.mobile.l10n.inPseudoLanguage {
            assertEquals(listOf("[xx] Myotis light client (on this device)"), myotis.shownAgreed)
        }
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
    fun `a tez name's shield speaks for Tezos Domains, base path and all`() {
        // #176: a `.tez` website record on IPFS may carry a base path.
        val tezos = EnsTrust(
            verified = true,
            agreed = listOf("rpc.tzkt.io", "mainnet.tezos.ecadinfra.com"),
            block = 15_133_172L,
        )
        KnownEnsNames.record("ipfs://$CID/site", "alice.tez", tezos)
        val trust = nameTrustFor("ipfs://alice.tez/about")!!
        assertEquals(TrustTier.Verified, trust.tier)
        assertEquals("ipfs://$CID/site", trust.answer)
        assertEquals(
            "2 independent RPC servers returned the same Tezos Domains record for alice.tez " +
                "at block #15133172.",
            trust.summary,
        )
        assertEquals("ipfs://alice.tez/about", DisplayUrl.withTransport("alice.tez/about"))
        // One Tezos server's word is the unverified tier, as for ENS.
        KnownEnsNames.record(
            "ipfs://$CID/site",
            "alice.tez",
            EnsTrust(verified = false, agreed = listOf("rpc.tzkt.io")),
        )
        assertEquals(TrustTier.Unverified, nameTrustFor("alice.tez")!!.tier)
    }

    @Test
    fun `gwei names get a shield too`() {
        KnownEnsNames.record("bzz://$REF", "bob.gwei", verified)
        val trust = nameTrustFor("bzz://bob.gwei/x")!!
        assertEquals(TrustTier.Verified, trust.tier)
        assertTrue(trust.summary, trust.summary.contains("GNS record for bob.gwei"))
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

    @Test
    fun `a document served from the tab's fallback answer shows that answer, not the session's`() {
        // R3-F1: tab A let name.eth → bzz://A through on one server's
        // word; tab B's lookup then recorded ipfs://B, verified. RPCs go
        // down and tab A follows a link on the name: its re-check serves
        // A from the tab's pin, so the bar says A, and the shield
        // doesn't borrow B's Verified.
        val origin = VirtualOrigin.originFor(ContentRoot.Ens("name.eth"))!!
        val tabA = EnsDocumentPins()
        val real = Gateways.ensLookup
        Gateways.resetEnsLookupState()
        try {
            KnownEnsNames.record("bzz://$REF", "name.eth", unverified)
            Gateways.ensLookup = { EnsResult.Ok(it, "bzz", "bzz://$REF", REF, unverified) }
            val first = tabA.beginNavigation("$origin/")
            assertNull(Gateways.reverifyEnsDocument("name.eth", tabA, first))
            tabA.documentStarted("$origin/")

            KnownEnsNames.record("ipfs://$CID", "name.eth", verified)

            Gateways.resetEnsLookupState()
            Gateways.ensLookup = { EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }
            val next = tabA.beginNavigation("$origin/page2")
            assertNull(Gateways.reverifyEnsDocument("name.eth", tabA, next))
            tabA.documentStarted("$origin/page2")

            val state = BrowserState(1L)
            val shown = displayFor("$origin/page2", state, tabA)
            assertEquals("bzz://name.eth/page2", shown)
            // …and no shield: the session's newer answer already points
            // elsewhere, so the session's Verified can't ride on A (R4-F1).
            assertNull(committedNameTrust("$origin/page2", shown, NameRefusalSlot(), tabA))
            // Without the tab's pins (another tab, a service worker), the
            // session's current answer.
            assertEquals("ipfs://name.eth/page2", displayFor("$origin/page2", state))
        } finally {
            Gateways.ensLookup = real
            Gateways.resetEnsLookupState()
        }
    }

    @Test
    fun `a fallback pin the session has seen the name leave gets no shield`() {
        // R4-F1: tab A loaded name.eth → bzz://A, verified; tab B's
        // re-check then recorded ipfs://B, verified. RPCs go down and tab
        // A follows a link: the page is served from pin A, but two
        // servers have already said the name no longer points there — no
        // shield, the same as a raw load of hash A.
        val origin = VirtualOrigin.originFor(ContentRoot.Ens("name.eth"))!!
        val tabA = EnsDocumentPins()
        val real = Gateways.ensLookup
        Gateways.resetEnsLookupState()
        try {
            KnownEnsNames.record("bzz://$REF", "name.eth", verified)
            Gateways.ensLookup = { EnsResult.Ok(it, "bzz", "bzz://$REF", REF, verified) }
            val first = tabA.beginNavigation("$origin/")
            assertNull(Gateways.reverifyEnsDocument("name.eth", tabA, first))
            tabA.documentStarted("$origin/")
            assertEquals(
                TrustTier.Verified,
                committedNameTrust("$origin/", "bzz://name.eth/", NameRefusalSlot(), tabA)?.tier,
            )
            assertFalse(ipfsLoadFor("$origin/", current = false, pins = tabA))

            KnownEnsNames.record("ipfs://$CID", "name.eth", verified)

            Gateways.resetEnsLookupState()
            Gateways.ensLookup = { EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }
            val next = tabA.beginNavigation("$origin/page2")
            assertNull(Gateways.reverifyEnsDocument("name.eth", tabA, next))
            tabA.documentStarted("$origin/page2")

            val shown = displayFor("$origin/page2", BrowserState(1L), tabA)
            assertEquals("bzz://name.eth/page2", shown)
            assertNull(committedNameTrust("$origin/page2", shown, NameRefusalSlot(), tabA))
            // The IPFS phase line follows the pin the page came from, not
            // the registry's newer IPFS answer.
            assertFalse(ipfsLoadFor("$origin/page2", current = false, pins = tabA))
            assertTrue(ipfsLoadFor("$origin/page2", current = false))
        } finally {
            Gateways.ensLookup = real
            Gateways.resetEnsLookupState()
        }
    }

    @Test
    fun `the phase line during the fetch follows the answer the document is served from`() {
        // R5-F1: tab A's pin is bzz://A, another tab moved the registry
        // to ipfs://B, and A's re-check fails — the fetch is from pin A,
        // so no IPFS phase line during it. And the reverse: pin IPFS,
        // registry Swarm → the phase line shows.
        val name = ContentRoot.Ens("name.eth")
        val real = Gateways.ensLookup
        Gateways.resetEnsLookupState()
        try {
            for ((pinned, registry, ipfs) in listOf(
                Triple("bzz://$REF", "ipfs://$CID", false),
                Triple("ipfs://$CID", "bzz://$REF", true),
            )) {
                val tab = EnsDocumentPins()
                KnownEnsNames.record(pinned, "name.eth", verified)
                Gateways.ensLookup = { EnsResult.Ok(it, pinned.substringBefore("://"), pinned, REF, verified) }
                val first = tab.beginNavigation("https://x/")
                assertNull(Gateways.reverifyEnsDocument("name.eth", tab, first))
                tab.documentStarted("https://x/")

                KnownEnsNames.record(registry, "name.eth", verified)
                Gateways.resetEnsLookupState()
                Gateways.ensLookup = { EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }
                val next = tab.beginNavigation("https://x/page2")
                assertNull(Gateways.reverifyEnsDocument("name.eth", tab, next))

                // What the interceptor hands `noteMainFrameContentLoad`
                // before the fetch — the same root the fetch uses.
                val served = Gateways.servedRootFor(name, page = next)
                assertEquals(VirtualOrigin.parseContentUrl(pinned)!!.first, served)
                assertEquals(ipfs, servedFromIpfs(served))
                // The session's answer alone would have said the opposite.
                assertEquals(!ipfs, servedFromIpfs(Gateways.servedRootFor(name)))
                KnownEnsNames.clear()
                Gateways.resetEnsLookupState()
            }
        } finally {
            Gateways.ensLookup = real
            Gateways.resetEnsLookupState()
        }
    }

    @Test
    fun `a refused or unresolved document is not an IPFS fetch`() {
        assertFalse(servedFromIpfs(null))
        assertFalse(servedFromIpfs(ContentRoot.Bzz(REF)))
        assertTrue(servedFromIpfs(ContentRoot.Ipfs(CID)))
        assertTrue(servedFromIpfs(ContentRoot.IpnsName("docs.ipfs.tech")))
    }

    @Test
    fun `a pin with no known check gets no shield`() {
        val origin = VirtualOrigin.originFor(ContentRoot.Ens("name.eth"))!!
        KnownEnsNames.record("bzz://$REF", "name.eth", verified)
        val pins = EnsDocumentPins()
        pins.pin("name.eth", "bzz://$REF")
        assertNull(committedNameTrust("$origin/", "bzz://name.eth/", NameRefusalSlot(), pins))
    }

    private companion object {
        const val REF = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
        const val CID = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
    }
}
