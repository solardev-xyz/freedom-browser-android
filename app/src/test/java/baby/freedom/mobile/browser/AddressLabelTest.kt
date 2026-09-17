package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressLabelTest {

    @Test
    fun `https url collapses to the registrable host`() {
        assertEquals("example.com", AddressLabel.resting("https://example.com/"))
        assertEquals("example.com", AddressLabel.resting("https://example.com/a/b?c=d#e"))
        assertEquals("example.com", AddressLabel.resting("http://example.com"))
    }

    @Test
    fun `www and deeper subdomains are dropped`() {
        assertEquals("example.com", AddressLabel.resting("https://www.example.com/x"))
        assertEquals("wikipedia.org", AddressLabel.resting("https://en.wikipedia.org/wiki/Swarm"))
        assertEquals("example.com", AddressLabel.resting("https://a.b.c.example.com/"))
    }

    @Test
    fun `compound public suffixes keep their registrable label`() {
        assertEquals("bbc.co.uk", AddressLabel.resting("https://www.bbc.co.uk/news"))
        assertEquals("bbc.co.uk", AddressLabel.resting("https://news.bbc.co.uk/"))
        assertEquals("example.com.au", AddressLabel.resting("https://shop.example.com.au/x"))
    }

    @Test
    fun `ports and userinfo are stripped`() {
        assertEquals("example.com", AddressLabel.resting("https://example.com:8443/x"))
        assertEquals("example.com", AddressLabel.resting("https://user:pw@www.example.com/x"))
    }

    @Test
    fun `ip literals and single-label hosts are left intact`() {
        assertEquals("127.0.0.1", AddressLabel.resting("http://127.0.0.1:1633/bzz/abc"))
        assertEquals("localhost", AddressLabel.resting("http://localhost:8080/"))
    }

    @Test
    fun `a backslash ends the authority on special schemes`() {
        // WHATWG / Chromium treat `\` as a path separator for special
        // schemes, so these all load `host` with `/@bank.com/x` as the
        // path — the label must not read `bank.com` out of them.
        assertEquals("10.0.2.2", AddressLabel.resting("http://10.0.2.2:8922\\@bank.com/x"))
        assertEquals("example.com", AddressLabel.resting("https://example.com\\@bank.co.uk/x"))
        assertEquals("example.com", AddressLabel.resting("https://example.com\\"))
        // Same for the bare-name form, which UrlParser loads as https.
        assertEquals("example.com", AddressLabel.resting("example.com\\@bank.com/x"))
    }

    @Test
    fun `backslashes are kept inside non-special authorities`() {
        // `bzz:` is not a special scheme, so its authority is not split
        // on `\` — but a backslash means it is not a host either, so it
        // is elided as an id rather than re-read as `bank.com`.
        assertEquals("bzz://swarm.….com", AddressLabel.resting("bzz://swarm.eth\\@bank.com/x"))
        assertEquals("bzz://a\\@b.co", AddressLabel.resting("bzz://a\\@b.co/x"))
    }

    @Test
    fun `bare ens display form keeps the name`() {
        assertEquals("swarm.eth", AddressLabel.resting("swarm.eth"))
        assertEquals("swarm.eth", AddressLabel.resting("swarm.eth/docs/index.html"))
        assertEquals("swarm.eth", AddressLabel.resting("bzz://swarm.eth/docs"))
        assertEquals("swarm.eth", AddressLabel.resting("ens://swarm.eth"))
        assertEquals("vitalik.eth", AddressLabel.resting("ipfs://vitalik.eth/posts"))
    }

    @Test
    fun `content hashes show the scheme and an elided id`() {
        val hash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        assertEquals("bzz://a1b2c3…8f90", AddressLabel.resting("bzz://$hash/index.html"))
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        assertEquals("ipfs://bafybe…bzdi", AddressLabel.resting("ipfs://$cid"))
    }

    @Test
    fun `short ids are shown whole`() {
        assertEquals("bzz://deadbeef", AddressLabel.resting("bzz://deadbeef/page"))
    }

    @Test
    fun `blank and unparseable inputs are passed through`() {
        assertEquals("", AddressLabel.resting(""))
        assertEquals("", AddressLabel.resting("   "))
        assertEquals("about:blank", AddressLabel.resting("about:blank"))
        assertEquals("data:text/html,hi", AddressLabel.resting("data:text/html,hi"))
    }
}
