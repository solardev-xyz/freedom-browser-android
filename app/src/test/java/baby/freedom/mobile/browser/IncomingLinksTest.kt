package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingLinksTest {

    @Test
    fun webAndDwebLinksOpen() {
        for (url in listOf(
            "https://example.com",
            "http://example.com/a?b=c#d",
            "bzz://1234abcd/index.html",
            "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
            "ipns://docs.ipfs.tech",
            "ens://vitalik.eth",
            "rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5",
            "rad://z3gqcJUoA1n9HaHKufZs5FCSGazv5/tree",
        )) {
            assertEquals(url, Incoming.Open(url), IncomingLinks.fromView(url))
        }
    }

    @Test
    fun schemeIsLowerCasedAndTheRestKept() {
        assertEquals(Incoming.Open("https://Example.com/Path"), IncomingLinks.fromView("HTTPS://Example.com/Path"))
        assertEquals(Incoming.Open("https://example.com"), IncomingLinks.fromView("  https://example.com \n"))
    }

    @Test
    fun otherSchemesAreRefused() {
        for (url in listOf(
            "file:///data/data/baby.freedom.mobile/shared_prefs/x.xml",
            "content://baby.freedom.mobile.files/uploads/a.jpg",
            "javascript:alert(1)",
            "intent://x#Intent;scheme=https;end",
            "data:text/html,<b>hi</b>",
            "about:blank",
            "freedom-ens-continue:abc",
            "freedom://settings",
            "market://details?id=x",
        )) {
            assertNull(url, IncomingLinks.fromView(url))
        }
    }

    @Test
    fun malformedLinksAreRefused() {
        for (url in listOf(null, "", "   ", "https:", "https://", "https:///path", "https:example.com",
            "https://exa mple.com", "https://example.com/\u0000", "bzz:", ":foo", "example.com")) {
            assertNull(url.toString(), IncomingLinks.fromView(url))
        }
    }

    @Test
    fun bracketsThatBelongToTheLinkStay() {
        assertEquals(
            Incoming.Open("http://host/wiki/Mercury_(planet)"),
            IncomingLinks.fromSharedText("Mercury http://host/wiki/Mercury_(planet)"),
        )
        assertEquals(
            Incoming.Open("https://en.wikipedia.org/wiki/Mercury_(planet)"),
            IncomingLinks.fromSharedText("Read (https://en.wikipedia.org/wiki/Mercury_(planet))."),
        )
        assertEquals(
            Incoming.Open("https://example.com/a[1]"),
            IncomingLinks.fromSharedText("Item: https://example.com/a[1]"),
        )
        assertEquals(
            Incoming.Open("https://example.com/a"),
            IncomingLinks.fromSharedText("[see https://example.com/a]!"),
        )
        assertEquals("https://x/y_(z)", IncomingLinks.trimTrailingPunctuation("https://x/y_(z)\"),."))
        assertEquals("https://x/", IncomingLinks.trimTrailingPunctuation("https://x/)])"))
    }

    @Test
    fun sharedLinkOpens() {
        assertEquals(Incoming.Open("https://youtu.be/abc"), IncomingLinks.fromSharedText("https://youtu.be/abc"))
        assertEquals(Incoming.Open("bzz://abcd"), IncomingLinks.fromSharedText(" bzz://abcd\n"))
    }

    @Test
    fun linkInsideSharedTextOpens() {
        assertEquals(
            Incoming.Open("https://news.example/story?id=1"),
            IncomingLinks.fromSharedText("Big news today: https://news.example/story?id=1"),
        )
        // Sentence punctuation after the link isn't part of it.
        assertEquals(
            Incoming.Open("https://example.com/a"),
            IncomingLinks.fromSharedText("Read this (https://example.com/a)."),
        )
        // The first of several.
        assertEquals(
            Incoming.Open("https://a.example/"),
            IncomingLinks.fromSharedText("https://a.example/ and https://b.example/"),
        )
    }

    @Test
    fun sharedTextWithoutALinkIsSearched() {
        assertEquals(Incoming.Search("freedom browser"), IncomingLinks.fromSharedText("  freedom browser \n"))
        // A non-web scheme mid-text is not opened.
        assertEquals(
            Incoming.Search("see javascript:alert(1)"),
            IncomingLinks.fromSharedText("see javascript:alert(1)"),
        )
        assertNull(IncomingLinks.fromSharedText("   "))
        assertNull(IncomingLinks.fromSharedText(null))
    }

    @Test
    fun queryThatIsALinkOpensAnythingElseIsSearched() {
        assertEquals(Incoming.Open("https://example.com"), IncomingLinks.fromQuery("https://example.com"))
        assertEquals(Incoming.Search("what is swarm"), IncomingLinks.fromQuery("what is swarm"))
        // Selected text with a link inside it is still a search.
        assertEquals(
            Incoming.Search("go to https://example.com"),
            IncomingLinks.fromQuery("go to https://example.com"),
        )
        assertNull(IncomingLinks.fromQuery(""))
    }

    @Test
    fun longTextIsCutToTheQueryLimitOnACodePoint() {
        val long = "a".repeat(IncomingLinks.MAX_QUERY - 1) + "😀" + "tail"
        val search = IncomingLinks.fromSharedText(long) as Incoming.Search
        assertEquals("a".repeat(IncomingLinks.MAX_QUERY - 1), search.query)
        val longer = "b".repeat(IncomingLinks.MAX_QUERY * 10)
        assertEquals(IncomingLinks.MAX_QUERY, (IncomingLinks.fromQuery(longer) as Incoming.Search).query.length)
    }

    @Test
    fun ordinaryLinksKeepTheirFormForTheAddressBar() {
        assertEquals("https://example.com/x", IncomingLinks.displayUrl("https://example.com/x"))
        assertEquals("bzz://abcd", IncomingLinks.displayUrl("bzz://abcd"))
    }

    @Test
    fun percentEncodedEnsNameIsDecodedForTheAddressBar() {
        // How `Uri` and most apps write a non-ASCII authority.
        assertEquals("ens://🦊.eth", IncomingLinks.displayUrl("ens://%F0%9F%A6%8A.eth"))
        assertEquals("ens://ñandú.eth/a%20b?q=%41#f", IncomingLinks.displayUrl("ens://%C3%B1and%C3%BA.eth/a%20b?q=%41#f"))
        assertEquals("ens://🦊.eth", IncomingLinks.displayUrl(IncomingLinks.link("ENS://%f0%9f%a6%8a.eth")!!))
        // Already plain: as it is.
        assertEquals("ens://ñandú.eth", IncomingLinks.displayUrl("ens://ñandú.eth"))
        // Nothing the name decodes to may become a delimiter, whitespace,
        // a control character or garbage: those stay encoded.
        for (url in listOf(
            "ens://evil.eth%2Fvitalik.eth",
            "ens://a%3Fb.eth",
            "ens://a%23b.eth",
            "ens://user%40vitalik.eth",
            "ens://a%3A1.eth",
            "ens://a%20b.eth",
            "ens://a%0Ab.eth",
            "ens://a%2541.eth",
            "ens://%FF.eth",
            "ens://a%5Cb.eth",
        )) {
            assertEquals(url, url, IncomingLinks.displayUrl(url))
        }
    }
}
