package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.ens.EnsTrust
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #465: a `.tez` name gets no ENSIP-15 confusable check to resolve, so a
 * lookalike (`pаypal.tez`, Cyrillic а) is *shown* `%XX`-escaped
 * everywhere the name is put in front of the user — the capsule, the
 * edit field (the display URL) and the trust sheet — and that form still
 * names it, and no other name (#490 R2-F1: not `xn--`, which in Tezos
 * Domains is a separate, registrable ASCII name).
 */
class TezLookalikeDisplayTest {

    private val uts46Was = WhatwgHost.uts46

    @Before fun setUp() {
        WhatwgHost.uts46 = Icu4jUts46
        EnsNormalize.warm()
    }

    @After fun tearDown() {
        WhatwgHost.uts46 = uts46Was
        KnownEnsNames.clear()
    }

    /** `pаypal.tez` with a Cyrillic а (U+0430). */
    private val spoof = "p\u0430ypal.tez"
    private val spoofShown = "p%D0%B0ypal.tez"

    @Test
    fun `a lookalike tez name is shown escaped`() {
        assertEquals(spoofShown, EnsNormalize.tezosDisplay(spoof))
        // Asked again (the capsule recomposes): the same answer (R2-M2).
        assertEquals(spoofShown, EnsNormalize.tezosDisplay(spoof))
        // A whole-script Cyrillic lookalike (`асе`) too: ENSIP-15
        // refuses whole-script confusables.
        val cyrillic = "\u0430\u0441\u0435.tez"
        assertEquals("%D0%B0%D1%81%D0%B5.tez", EnsNormalize.tezosDisplay(cyrillic))
        // Only the offending label: `pay.pаypal.tez` keeps `pay`.
        assertEquals("pay.$spoofShown", EnsNormalize.tezosDisplay("pay.$spoof"))
    }

    @Test
    fun `a bookmark key read at startup matches one read once warm`() {
        // #490 R4-M1: the star keys the page once and the bookmarks on
        // every change, so a key worked out before the ENSIP-15 tables
        // were decoded has to be the one worked out after.
        val honest = "caf\u00E9.tez"
        val warmKey = BookmarkUrls.key(honest)
        try {
            EnsNormalize.warmed = false
            val coldKey = BookmarkUrls.key("caf%C3%A9.tez")
            assertEquals(warmKey, coldKey)
            assertEquals(true, EnsNormalize.isWarm)
            EnsNormalize.warmed = false
            assertEquals(warmKey, BookmarkUrls.key(honest))
            assertEquals(warmKey, BookmarkUrls.key("caf%C3%A9.tez"))
        } finally {
            EnsNormalize.warm()
        }
        // A lookalike keeps one key too, escaped or not.
        assertEquals(BookmarkUrls.key(spoof), BookmarkUrls.key(spoofShown))
    }

    @Test
    fun `the end of the private session forgets the names shown`() {
        // #490 R4-M2: the memo holds a private tab's `.tez` names.
        EnsNormalize.tezosDisplay(spoof)
        EnsNormalize.tezosDisplay("caf\u00E9.tez")
        assertEquals(true, EnsNormalize.shownCount > 0)
        EnsNormalize.forgetShown()
        assertEquals(0, EnsNormalize.shownCount)
        // Still the same answers afterwards.
        assertEquals(spoofShown, EnsNormalize.tezosDisplay(spoof))
    }

    @Test
    fun `an honest unicode tez name stays readable`() {
        for (name in listOf("caf\u00E9.tez", "\u2764.tez", "\u03C3\u03BF\u03C6\u03BF\u03C2.tez", "alice.tez")) {
            assertEquals(name, EnsNormalize.tezosDisplay(name))
        }
        // ENS-family names are untouched: ENSIP-15 already vetted them.
        assertEquals("\u0444\u043E\u043E.eth", EnsNormalize.tezosDisplay("\u0444\u043E\u043E.eth"))
    }

    @Test
    fun `the escaped form names the same tez name`() {
        assertEquals(spoof, EnsNormalize.tezosForm(spoofShown))
        assertEquals(spoof, EnsNormalize.tezosForm(spoofShown.uppercase()))
        assertEquals(spoof, EnsInput.parse(spoofShown)!!.name)
        assertEquals(spoof, EnsInput.parse("ens://$spoofShown/x")!!.name)
        assertEquals(spoof, EnsInput.parseConstrained("ipfs://$spoofShown/x")!!.name)
        assertEquals(spoof, EnsNormalize.tezosForm(spoofShown.lowercase()))
        // An ASCII tez name is only lowercased, as before.
        assertEquals("ab--c.tez", EnsNormalize.tezosForm("AB--C.tez"))
        // Malformed escapes or bytes that aren't UTF-8 are no name's escape.
        assertEquals("p%zz.tez", EnsNormalize.tezosForm("p%ZZ.tez"))
        assertEquals("p%c3.tez", EnsNormalize.tezosForm("p%C3.tez"))
        assertEquals("p%d0.tez", EnsNormalize.tezosForm("p%D0.tez"))
        // `.eth` names are not unescaped: ENS spells them its own way.
        assertEquals("p%d0%b0ypal.eth", EnsInput.parse("p%D0%B0ypal.eth")!!.name)
    }

    @Test
    fun `an ascii escape is never decoded into a tez name`() {
        // #490 R3-M1: only escapes of bytes 0x80+ are a shown form being
        // read back. `%2F`, `%25`, `%0A` stay as written, so no decoded
        // name gets a `/`, `%` or control character, and the name rests
        // whole in the capsule rather than on `paypal.com`.
        assertEquals("paypal.com%2f.tez", EnsNormalize.tezosForm("paypal.com%2F.tez"))
        assertEquals("paypal.com%2f.tez", AddressLabel.resting("paypal.com%2F.tez"))
        assertEquals("x%0a.tez", EnsNormalize.tezosForm("x%0A.tez"))
        // `%25` is not decoded, so decoding twice gives what decoding
        // once did: no `x%2561.tez` → `x%61.tez` → `xa.tez`.
        assertEquals("x%2561.tez", EnsNormalize.tezosForm("x%2561.tez"))
        assertEquals("x%61.tez", EnsNormalize.tezosForm("x%61.tez"))
        assertEquals("x%61.tez", EnsInput.parse("x%61.tez")!!.name)
        assertEquals("x%61.tez", AddressLabel.resting("x%61.tez"))
        assertEquals("x%61.tez", DisplayUrl.settledName("x%61.tez"))
        assertEquals(
            ContentRoot.Ens("x%61.tez"),
            VirtualOrigin.parseContentUrl("ens://x%2561.tez/")!!.first,
        )
        // Mixed with a real escape, the non-ASCII part is still read
        // back and the ASCII escape kept: the shown form round-trips.
        assertEquals("p\u0430y%2fpal.tez", EnsNormalize.tezosForm("p%D0%B0y%2Fpal.tez"))
        assertEquals("p%D0%B0y%2Fpal.tez", EnsNormalize.tezosDisplay("p\u0430y%2Fpal.tez"))
    }

    @Test
    fun `an ascii xn-- tez name is its own name`() {
        // #490 R2-F1: Tezos Domains keys `xn--rh8hs4h.tez` (registered)
        // apart from `🌮🥷.tez`, and `xn--pypal-4ve.tez` apart from
        // `pаypal.tez`. The literal ASCII name is what is looked up.
        for (literal in listOf("xn--rh8hs4h.tez", "xn--pypal-4ve.tez", "pay.xn--pypal-4ve.tez")) {
            assertEquals(literal, EnsNormalize.tezosForm(literal))
            assertEquals(literal, EnsNormalize.tezosForm(literal.uppercase()))
            assertEquals(literal, EnsInput.parse(literal)!!.name)
            assertEquals(literal, EnsInput.parse("ens://$literal/x")!!.name)
            assertEquals(literal, EnsNormalize.tezosDisplay(literal))
            assertEquals(literal, AddressLabel.resting(literal))
            assertEquals("$literal/x", DisplayUrl.settledName("$literal/x"))
        }
        // The lookalike and the literal ASCII name never share a spelling.
        assert(EnsNormalize.tezosDisplay(spoof) != "xn--pypal-4ve.tez")
    }

    @Test
    fun `capsule, edit field and display URL show the escaped name`() {
        assertEquals(spoofShown, AddressLabel.resting(spoof))
        assertEquals(spoofShown, AddressLabel.resting("ipfs://$spoof/docs"))
        // Handed the shown form (lowercased on the way), it rests the same.
        assertEquals(spoofShown, AddressLabel.resting("ipfs://$spoofShown/docs"))
        assertEquals(spoofShown, AddressLabel.resting(spoofShown.lowercase()))
        assertEquals("caf\u00E9.tez", AddressLabel.resting("caf\u00E9.tez/x"))

        val virtual = VirtualOrigin.originFor(ContentRoot.Ens(spoof))!! + "/docs?q=1"
        assertEquals("$spoofShown/docs?q=1", VirtualOrigin.displayUrlFor(virtual))
        assertEquals("$spoofShown/docs?q=1", DisplayUrl.forActualUrl(virtual, null) { null })

        // Under its transport, and through a manifest override.
        KnownEnsNames.record("ipfs://$CID", spoof, EnsTrust(verified = true, agreed = listOf("a", "b")))
        assertEquals("ipfs://$spoofShown/docs", DisplayUrl.withTransport("$spoof/docs"))
        assertEquals("ipfs://$spoofShown/docs", DisplayUrl.withTransport("$spoofShown/docs"))
        assertEquals("ipfs://$spoofShown", BrowserState.Override("https://x.test/", spoof).shown)

        // Non-name URLs pass through untouched.
        assertEquals("https://example.com/\u0430", DisplayUrl.shownName("https://example.com/\u0430"))
    }

    @Test
    fun `the trust sheet names the lookalike escaped`() {
        KnownEnsNames.record(
            "ipfs://$CID",
            spoof,
            EnsTrust(verified = true, agreed = listOf("rpc.tzkt.io", "mainnet.tezos.ecadinfra.com"), block = 1L),
        )
        // Found from the shown form — the name is still the Unicode one.
        val trust = nameTrustFor("ipfs://$spoofShown/x")!!
        assertEquals(spoof, trust.name)
        // The facts' Name row prints [NameTrust.shown] (#490 R1-F1).
        assertEquals(spoofShown, trust.shown)
        assertEquals(
            "2 independent RPC servers returned the same Tezos Domains record for $spoofShown at block #1.",
            trust.summary,
        )
        assert(spoof !in trust.recipientSummary) { trust.recipientSummary }
    }

    @Test
    fun `a saved address is spelled the same whenever it is saved`() {
        // #490 R1-M2: an honest name shown escaped while the tables were
        // still decoding is saved in Unicode; a lookalike stays escaped.
        val cafe = "caf%C3%A9.tez"
        assertEquals("caf\u00E9.tez/x?q=1", DisplayUrl.settledName("$cafe/x?q=1"))
        assertEquals("ipfs://caf\u00E9.tez/x", DisplayUrl.settledName("ipfs://$cafe/x"))
        assertEquals("caf\u00E9.tez", DisplayUrl.settledName("caf\u00E9.tez"))
        assertEquals("$spoofShown/x", DisplayUrl.settledName("$spoofShown/x"))
        assertEquals("$spoofShown/x", DisplayUrl.settledName("$spoof/x"))
        assertEquals("$spoofShown/x", DisplayUrl.settledName("${spoofShown.lowercase()}/x"))
        // Anything that isn't a `.tez` name is left as it is.
        for (url in listOf("https://caf%C3%A9.example/", "caf%C3%A9.eth/x", "alice.tez/x", "bzz://abc/x")) {
            assertEquals(url, DisplayUrl.settledName(url))
        }
    }

    private companion object {
        const val CID = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
    }
}
