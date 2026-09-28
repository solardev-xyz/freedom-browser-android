package baby.freedom.mobile.ens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import baby.freedom.mobile.browser.Icu4jUts46
import baby.freedom.mobile.browser.WhatwgHost

class EnsInputTest {

    private val uts46Was = WhatwgHost.uts46

    // `.tez` names get UTS-46 (ICU): icu4j here, android.icu on device.
    @Before fun useIcu4j() { WhatwgHost.uts46 = Icu4jUts46 }

    @After fun restoreUts46() { WhatwgHost.uts46 = uts46Was }

    @Test
    fun `bare name parses`() {
        assertEquals(
            EnsInput.Parsed("vitalik.eth", ""),
            EnsInput.parse("vitalik.eth"),
        )
    }

    @Test
    fun `ens alias parses and lowercases the name`() {
        assertEquals(
            EnsInput.Parsed("vitalik.eth", "/docs?q=1"),
            EnsInput.parse("ens://VITALIK.eth/docs?q=1"),
        )
    }

    @Test
    fun `non-ens input returns null`() {
        assertNull(EnsInput.parse("example.com"))
        assertNull(EnsInput.parse("bzz://vitalik.eth"))
        assertNull(EnsInput.parse("https://vitalik.eth.limo"))
    }

    @Test
    fun `constrained bzz name parses with protocol`() {
        assertEquals(
            EnsInput.Constrained("swarm.eth", "/p", "bzz"),
            EnsInput.parseConstrained("bzz://swarm.eth/p"),
        )
    }

    @Test
    fun `constrained ipfs and ipns names parse`() {
        assertEquals(
            EnsInput.Constrained("vitalik.eth", "", "ipfs"),
            EnsInput.parseConstrained("ipfs://vitalik.eth"),
        )
        assertEquals(
            EnsInput.Constrained("foo.box", "?x=1", "ipns"),
            EnsInput.parseConstrained("ipns://Foo.box?x=1"),
        )
    }

    @Test
    fun `raw content ids are not constrained ens`() {
        // 64-hex Swarm hash, CID, and a DNSLink host must stay on the
        // direct gateway path.
        assertNull(EnsInput.parseConstrained("bzz://" + "ab".repeat(32)))
        assertNull(EnsInput.parseConstrained("ipfs://bafybeigdyrzt5s"))
        assertNull(EnsInput.parseConstrained("ipns://ipfs.tech"))
        assertNull(EnsInput.parseConstrained("ens://vitalik.eth"))
        assertNull(EnsInput.parseConstrained("vitalik.eth"))
    }

    @Test
    fun `wns and gns names parse like ens names`() {
        assertEquals(EnsInput.Parsed("alice.wei", ""), EnsInput.parse("alice.wei"))
        assertEquals(EnsInput.Parsed("alice.wei", "/p?q=1"), EnsInput.parse("ens://ALICE.wei/p?q=1"))
        assertEquals(EnsInput.Parsed("apoorv.gwei", "#x"), EnsInput.parse("apoorv.gwei#x"))
        assertEquals(
            EnsInput.Constrained("meinhard.wei", "/", "bzz"),
            EnsInput.parseConstrained("bzz://meinhard.wei/"),
        )
    }

    @Test
    fun `lookalike suffixes are not names`() {
        assertNull(EnsInput.parse("example.weird"))
        assertNull(EnsInput.parse("alicewei"))
        assertNull(EnsInput.parse("https://alice.wei.example"))
    }
}
