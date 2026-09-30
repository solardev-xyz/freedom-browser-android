package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BookmarkUrls.canonical] spells a typed path, query and fragment the
 * way this device's WebView serialises the page's own URL (#296 R3-F1) —
 * the WebView's Chromium can be older than the one the JVM test's
 * expectations were taken from.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkUrlsWebViewTest {

    private val harness = WebViewHarness()

    @Before fun setUp() = harness.setUp()

    @After fun tearDown() = harness.tearDown()

    private val tails = listOf(
        "/Straße/", "/x/../Stra%C3%9Fe/", "/a b\"<>`{}|^\\c", "/%41%2e%7e%c3%9f/%2E%2e/x",
        "/a/./b/.%2E/c/%2e", "/?q=a b\"'<>`{}|^ß#f a\"<>`{}|^ß", "/a%2fb%zz%", "/a\tb\nc",
        "/\u0001\u007f", "/..", "/a/..", "/a/.", "/p?%41%c3", "/p#%41", "/[]@!$&()*+,;=:~",
        "/p?[]@!$&()*+,;=:~/?#[]@!$&()*+,;=:~/?#", "/😀", "//a//b", "/%2E",
        "/a/%2e%2E/b", "/.%2e", "/?", "/#", "/p%", "/p?a\u007fb\\c", "/p#a\u007fb\\c",
        "/a/..%2f", "\\a\\b", "/a/b/../../../c", "/a/ ./b", "/p?a#b#c", "/p#a?b",
    )

    @Test
    fun matchesTheWebViewsUrlParser() {
        harness.load("about:blank")
        val urls = tails.map { "http://h$it" }
        val script = "JSON.stringify(" + JSONArray(urls) + ".map(u => new URL(u).href))"
        // evaluateJavascript JSON-encodes the returned string once more.
        val chromium = JSONArray(JSONArray("[" + harness.js(script) + "]").getString(0))
        urls.forEachIndexed { i, url ->
            assertEquals(url, chromium.getString(i), BookmarkUrls.canonical(url))
        }
    }

    /** A rad page's address is [RadUrl.displayUrlFor] of the WebView's own URL (#296 R5-F1). */
    @Test
    fun radAddressesMatchTheirPage() {
        harness.load("about:blank")
        val rid = "z3gqcJUoA1n9HaHKufZs5FCSGazv5"
        val typed = listOf(
            "rad://$rid/tree/a b", "rad://$rid/tree/Straße", "rad://$rid/tree/x/../y",
            "rad://$rid/tree/x/%2e%2E/y", "rad:$rid?q=ä", "rad://$rid/tree#x y", "rad://$rid/",
        )
        val virtual = typed.map { RadUrl.toVirtualUrl(it)!! }
        val script = "JSON.stringify(" + JSONArray(virtual) + ".map(u => new URL(u).href))"
        val chromium = JSONArray(JSONArray("[" + harness.js(script) + "]").getString(0))
        typed.forEachIndexed { i, url ->
            assertEquals(url, RadUrl.displayUrlFor(chromium.getString(i)), BookmarkUrls.canonical(url))
        }
    }
}
