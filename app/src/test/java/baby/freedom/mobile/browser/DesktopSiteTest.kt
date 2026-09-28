package baby.freedom.mobile.browser

import androidx.webkit.UserAgentMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSiteTest {

    // --- the per-site key ---

    @Test
    fun `site key is the registrable domain, like Chrome's desktop-site exceptions`() {
        assertEquals("google.com", desktopSiteKey("https://Meet.Google.com/new?x#y"))
        // Meet's own landing page redirects here: switching it covers Meet.
        assertEquals(desktopSiteKey("https://meet.google.com/new"), desktopSiteKey("https://workspace.google.com/"))
        assertEquals("example.com", desktopSiteKey("http://example.com:8080/"))
        assertEquals(desktopSiteKey("http://example.com/"), desktopSiteKey("https://www.example.com/"))
        assertEquals("bbc.co.uk", desktopSiteKey("https://www.bbc.co.uk/news"))
        // A host that is its own public suffix's only label keeps its host.
        assertEquals("alice.github.io", desktopSiteKey("https://alice.github.io/"))
        assertEquals("bob.github.io", desktopSiteKey("https://bob.github.io/"))
    }

    @Test
    fun `hosts with no registrable domain are their own key`() {
        assertEquals("127.0.0.1", desktopSiteKey("http://127.0.0.1:8703/b"))
        assertEquals("10.0.2.2", desktopSiteKey("http://10.0.2.2/"))
        assertEquals("localhost", desktopSiteKey("http://localhost:8703/a"))
        assertEquals("[::1]", desktopSiteKey("http://[::1]:8080/"))
        assertEquals("github.io", desktopSiteKey("https://github.io/"))
    }

    @Test
    fun `dweb pages and non-sites have no key`() {
        // bzz / ens / ipfs pages, served from their virtual origins.
        assertNull(desktopSiteKey("https://vitalik-eth.ens.freedom.baby/"))
        assertNull(desktopSiteKey("https://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi.ipfs.freedom.baby/a"))
        assertNull(desktopSiteOf("swarm-eth.ens.freedom.baby"))
        assertNull(desktopSiteKey(null))
        assertNull(desktopSiteKey(ABOUT_BLANK))
        assertNull(desktopSiteKey("file:///android_asset/error/error.html?u=x"))
        assertNull(desktopSiteKey("data:text/html,hi"))
        assertNull(desktopSiteKey("javascript:history.back();void(0);"))
        // The base domain itself is no content root: an ordinary site.
        assertEquals("freedom.baby", desktopSiteKey("https://freedom.baby/"))
    }

    // --- the user agent ---

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.F3; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    @Test
    fun `desktop user agent is desktop Chrome on Linux at the WebView's major`() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/133.0.0.0 Safari/537.36",
            DesktopUserAgent.string(webViewUa),
        )
    }

    @Test
    fun `desktop user agent has no Android, Mobile or wv token`() {
        val ua = DesktopUserAgent.string(webViewUa)
        for (token in listOf("Android", "Mobile", "wv", "Version/4.0", "sdk_gphone")) {
            assertFalse("$token in $ua", ua.contains(token))
        }
    }

    @Test
    fun `desktop user agent without a Chrome version falls back`() {
        assertTrue(
            DesktopUserAgent.string("Mozilla/5.0 (Linux; Android 16; wv)")
                .contains("Chrome/${DesktopUserAgent.FALLBACK_MAJOR}.0.0.0 "),
        )
    }

    private fun brand(name: String, major: String, full: String) =
        UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(major).setFullVersion(full).build()

    private val webViewHints = UserAgentMetadata.Builder()
        .setBrandVersionList(
            listOf(
                brand("Not(A:Brand", "99", "99.0.0.0"),
                brand(DesktopUserAgent.WEBVIEW_BRAND, "133", "133.0.6943.137"),
                brand("Chromium", "133", "133.0.6943.137"),
            ),
        )
        .setFullVersion("133.0.6943.137")
        .setPlatform("Android")
        .setPlatformVersion("16.0.0")
        .setArchitecture("")
        .setModel("sdk_gphone64_x86_64")
        .setMobile(true)
        .build()

    @Test
    fun `client hints say desktop Linux, with the WebView's engine versions`() {
        val hints = DesktopUserAgent.metadata(webViewHints, kernel = "6.6.66", setFormFactors = false)
        assertEquals(
            listOf("Not(A:Brand" to "99", "Chromium" to "133"),
            hints.brandVersionList.map { it.brand to it.majorVersion },
        )
        assertEquals("133.0.6943.137", hints.brandVersionList.last().fullVersion)
        assertEquals("133.0.6943.137", hints.fullVersion)
        assertEquals("Linux", hints.platform)
        assertEquals("6.6.66", hints.platformVersion)
        assertEquals("x86", hints.architecture)
        assertEquals(64, hints.bitness)
        assertEquals("", hints.model)
        assertFalse(hints.isMobile)
        assertFalse(hints.isWow64)
    }

    @Test
    fun `form factor is left alone where the WebView can't take one`() {
        // The builder's setFormFactors throws on a WebView without
        // USER_AGENT_METADATA_FORM_FACTORS (and on the JVM, which has
        // none): building without it must not call it (R1-F3). The
        // device check covers the "Desktop" value itself.
        val hints = DesktopUserAgent.metadata(webViewHints, kernel = "6.6.66", setFormFactors = false)
        assertEquals(webViewHints.formFactors, hints.formFactors)
    }

    @Test
    fun `kernel version is the leading dotted number of os version`() {
        assertEquals("6.6.30", DesktopUserAgent.kernelVersion("6.6.30-android15-8-g1234abcd"))
        assertEquals("6.1", DesktopUserAgent.kernelVersion("6.1"))
        assertEquals("", DesktopUserAgent.kernelVersion("unknown"))
        assertEquals("", DesktopUserAgent.kernelVersion(null))
    }

    @Test
    fun `navigator platform script only where the kernel isn't already x86_64`() {
        assertFalse(DesktopUserAgent.needsPlatformScript("x86_64"))
        assertTrue(DesktopUserAgent.needsPlatformScript("aarch64"))
        assertTrue(DesktopUserAgent.needsPlatformScript("armv8l"))
        assertTrue(DesktopUserAgent.needsPlatformScript(null))
        assertTrue(DesktopUserAgent.platformScript.contains("'${DesktopUserAgent.PLATFORM}'"))
        assertTrue(DesktopUserAgent.string(webViewUa).contains("(X11; ${DesktopUserAgent.PLATFORM})"))
    }

    // --- the per-site store ---

    private class Harness(stored: Set<String> = emptySet(), deferLoad: Boolean = false) {
        val loaded = CompletableDeferred<Set<String>>()
        val writes = mutableListOf<Pair<String, Boolean>>()
        var clears = 0
        val sites = DesktopSites(
            scope = CoroutineScope(Dispatchers.Unconfined),
            load = { loaded.await() },
            save = { site, desktop -> writes += site to desktop },
            clear = { clears++ },
        )

        init {
            if (!deferLoad) loaded.complete(stored)
        }
    }

    @Test
    fun `off by default, remembered sites load`() {
        val h = Harness(setOf("google.com"))
        assertTrue(h.sites.isDesktop("google.com"))
        assertFalse(h.sites.isDesktop("example.com"))
        assertFalse(h.sites.isDesktop(null))
    }

    @Test
    fun `toggle is per site and remembered, off stored as no entry`() {
        val h = Harness()
        assertTrue(h.sites.toggle("a.com"))
        assertTrue(h.sites.isDesktop("a.com"))
        assertFalse(h.sites.isDesktop("b.com"))
        assertFalse(h.sites.toggle("a.com"))
        assertFalse(h.sites.isDesktop("a.com"))
        assertEquals(listOf("a.com" to true, "a.com" to false), h.writes)
    }

    @Test
    fun `a change made before the startup read lands wins over it`() {
        val h = Harness(deferLoad = true)
        h.sites.toggle("a.com") // on
        h.sites.toggle("c.com") // on…
        h.sites.toggle("c.com") // …and off again
        h.loaded.complete(setOf("b.com", "c.com"))
        assertTrue(h.sites.isDesktop("a.com"))
        assertTrue(h.sites.isDesktop("b.com"))
        assertFalse(h.sites.isDesktop("c.com"))
    }

    @Test
    fun `a private tab's choice is not persisted and overrides the remembered one`() {
        val h = Harness(setOf("a.com"))
        assertTrue(h.sites.isDesktop("a.com", private = true))
        assertFalse(h.sites.toggle("a.com", private = true))
        assertTrue(h.sites.toggle("b.com", private = true))
        assertFalse(h.sites.isDesktop("a.com", private = true))
        assertTrue(h.sites.isDesktop("b.com", private = true))
        // Regular tabs don't see it, and nothing was written.
        assertTrue(h.sites.isDesktop("a.com"))
        assertFalse(h.sites.isDesktop("b.com"))
        assertEquals(emptyList<Pair<String, Boolean>>(), h.writes)
        // The private session ends: back to the remembered choices.
        h.sites.clearPrivate()
        assertTrue(h.sites.isDesktop("a.com", private = true))
        assertFalse(h.sites.isDesktop("b.com", private = true))
    }

    @Test
    fun `clear site data forgets every site, and a late startup read too`() {
        val h = Harness(deferLoad = true)
        h.sites.toggle("a.com")
        h.sites.toggle("p.com", private = true)
        h.sites.clearAll()
        h.loaded.complete(setOf("b.com"))
        assertFalse(h.sites.isDesktop("a.com"))
        assertFalse(h.sites.isDesktop("b.com"))
        assertFalse(h.sites.isDesktop("p.com", private = true))
        assertEquals(1, h.clears)
    }
}
