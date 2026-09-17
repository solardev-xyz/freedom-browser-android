package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cases from <https://publicsuffix.org/list/> that exercise each
 * branch of the matching algorithm, plus the PRIVATE-section boundaries
 * the address label depends on.
 */
class PublicSuffixListTest {

    @Test
    fun `plain icann suffixes`() {
        assertEquals("example.com", PublicSuffixList.registrableDomain("example.com"))
        assertEquals("example.com", PublicSuffixList.registrableDomain("www.example.com"))
        assertEquals("example.com", PublicSuffixList.registrableDomain("a.b.example.com"))
        assertNull(PublicSuffixList.registrableDomain("com"))
    }

    @Test
    fun `compound suffixes`() {
        assertEquals("bbc.co.uk", PublicSuffixList.registrableDomain("news.bbc.co.uk"))
        assertNull(PublicSuffixList.registrableDomain("co.uk"))
        assertEquals("test.ac.jp", PublicSuffixList.registrableDomain("test.ac.jp"))
    }

    @Test
    fun `wildcard rules consume exactly one label`() {
        // `*.ck`
        assertNull(PublicSuffixList.registrableDomain("b.ck"))
        assertEquals("a.b.ck", PublicSuffixList.registrableDomain("a.b.ck"))
        assertEquals("a.b.ck", PublicSuffixList.registrableDomain("x.y.a.b.ck"))
    }

    @Test
    fun `exception rules win over the wildcard they sit under`() {
        // `*.kobe.jp` + `!city.kobe.jp`
        assertNull(PublicSuffixList.registrableDomain("test.kobe.jp"))
        assertEquals("city.kobe.jp", PublicSuffixList.registrableDomain("city.kobe.jp"))
        assertEquals("city.kobe.jp", PublicSuffixList.registrableDomain("www.city.kobe.jp"))
        // `*.ck` + `!www.ck`
        assertEquals("www.ck", PublicSuffixList.registrableDomain("www.ck"))
        assertEquals("www.ck", PublicSuffixList.registrableDomain("foo.www.ck"))
    }

    @Test
    fun `unknown tlds fall back to the implicit star rule`() {
        assertNull(PublicSuffixList.registrableDomain("example"))
        assertEquals("example.example", PublicSuffixList.registrableDomain("example.example"))
        assertEquals("example.example", PublicSuffixList.registrableDomain("b.example.example"))
    }

    @Test
    fun `private section boundaries are honoured`() {
        assertNull(PublicSuffixList.registrableDomain("github.io"))
        assertEquals("google.github.io", PublicSuffixList.registrableDomain("google.github.io"))
        assertEquals("app.web.app", PublicSuffixList.registrableDomain("app.web.app"))
        assertEquals("x.netlify.app", PublicSuffixList.registrableDomain("x.netlify.app"))
        assertEquals("me.blogspot.com", PublicSuffixList.registrableDomain("me.blogspot.com"))
        // `*.eth.limo`: each ENS gateway host is a suffix of its own, so
        // there is no registrable domain to shorten it to — the label
        // falls back to the whole host (see AddressLabelTest).
        assertNull(PublicSuffixList.registrableDomain("vitalik.eth.limo"))
        assertEquals(
            "www.vitalik.eth.limo",
            PublicSuffixList.registrableDomain("www.vitalik.eth.limo"),
        )
        assertEquals("app.herokuapp.com", PublicSuffixList.registrableDomain("app.herokuapp.com"))
        assertEquals("site.pages.dev", PublicSuffixList.registrableDomain("site.pages.dev"))
    }

    @Test
    fun `virtual origin suffixes are one registrable domain per content root`() {
        for (suffix in VirtualOrigin.SUFFIXES) {
            assertNull(PublicSuffixList.registrableDomain(suffix))
            assertEquals("root.$suffix", PublicSuffixList.registrableDomain("root.$suffix"))
        }
    }

    @Test
    fun `idn rules match either form of the host`() {
        // The list carries `рф` and its punycode twin `xn--p1ai`.
        assertEquals("example.рф", PublicSuffixList.registrableDomain("a.example.рф"))
        assertEquals("example.xn--p1ai", PublicSuffixList.registrableDomain("a.example.xn--p1ai"))
    }

    @Test
    fun `warming off-thread leaves the same rule set a first label would build`() {
        // What MainActivity does at startup: build the list on a
        // background thread so the first resting label doesn't. The
        // answers afterwards must be the ones the lazy load gives.
        val warmers = List(4) { Thread { PublicSuffixList.warm() } }
        warmers.forEach { it.start() }
        warmers.forEach { it.join() }
        assertEquals("bbc.co.uk", PublicSuffixList.registrableDomain("news.bbc.co.uk"))
        assertEquals("google.github.io", PublicSuffixList.registrableDomain("google.github.io"))
        assertNull(PublicSuffixList.registrableDomain("co.uk"))
    }

    @Test
    fun `malformed hosts have no registrable domain`() {
        assertNull(PublicSuffixList.registrableDomain(""))
        assertNull(PublicSuffixList.registrableDomain("a..b"))
    }
}
