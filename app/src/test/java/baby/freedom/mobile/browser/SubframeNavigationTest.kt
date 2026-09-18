package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which navigations `shouldOverrideUrlLoading` may hand to the screen's
 * submit flow (#36).
 *
 * The detour ends in `submit()`, which navigates the *whole tab*. Only
 * the main frame ever asks for that; a subframe asking — an
 * `<iframe src="bzz://…">` on any page, https included — would move the
 * tab out from under the document the user is reading, under a pill
 * that (since #34) goes on naming the old page until the new one
 * commits.
 */
class SubframeNavigationTest {

    @Test
    fun `a main-frame content-scheme navigation takes the probe gate`() {
        for (url in listOf(
            "bzz://a1b2c3d4e5f60718293a4b5c6d7e8f90",
            "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
            "ipns://k51qzi5uqu5dkkciu33khkzbcmxtyhn376i1e83tya8kuy7z9euedzyr5nhoew",
            "ens://vitalik.eth",
        )) {
            assertTrue(url, submitDetourForNavigation(url, isForMainFrame = true))
        }
    }

    @Test
    fun `a subframe asking for the same content never moves the tab`() {
        for (url in listOf(
            "bzz://a1b2c3d4e5f60718293a4b5c6d7e8f90",
            "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
            "ipns://k51qzi5uqu5dkkciu33khkzbcmxtyhn376i1e83tya8kuy7z9euedzyr5nhoew",
            "ens://vitalik.eth",
        )) {
            // False here isn't "blocked": the request falls through to
            // the interceptor, which serves content-scheme URLs off the
            // local gateway exactly as it does for `<img src="bzz://…">`.
            assertFalse(url, submitDetourForNavigation(url, isForMainFrame = false))
        }
    }

    @Test
    fun `ordinary web navigation is left to Chromium either way`() {
        assertFalse(submitDetourForNavigation("https://example.com/", isForMainFrame = true))
        assertFalse(submitDetourForNavigation("https://example.com/", isForMainFrame = false))
        assertFalse(
            submitDetourForNavigation(
                "https://swarm.bzz.freedom.baby/index.html",
                isForMainFrame = true,
            ),
        )
        // A query string that merely mentions a content scheme is not one.
        assertFalse(
            submitDetourForNavigation(
                "https://example.com/?go=bzz://a1b2c3",
                isForMainFrame = true,
            ),
        )
    }

    @Test
    fun `the scheme match is case-insensitive and needs an authority`() {
        assertTrue(submitDetourForNavigation("BZZ://a1b2c3", isForMainFrame = true))
        assertTrue(submitDetourForNavigation("Ens://vitalik.eth", isForMainFrame = true))
        assertFalse(submitDetourForNavigation("bzz:a1b2c3", isForMainFrame = true))
        assertFalse(submitDetourForNavigation("://nothing", isForMainFrame = true))
        assertFalse(submitDetourForNavigation("", isForMainFrame = true))
    }
}
