package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.MainMenuRow.AddToHomeScreen
import baby.freedom.mobile.browser.MainMenuRow.BlockAds
import baby.freedom.mobile.browser.MainMenuRow.Bookmarks
import baby.freedom.mobile.browser.MainMenuRow.DesktopSite
import baby.freedom.mobile.browser.MainMenuRow.Downloads
import baby.freedom.mobile.browser.MainMenuRow.FindInPage
import baby.freedom.mobile.browser.MainMenuRow.HardReload
import baby.freedom.mobile.browser.MainMenuRow.History
import baby.freedom.mobile.browser.MainMenuRow.Home
import baby.freedom.mobile.browser.MainMenuRow.NameTrust
import baby.freedom.mobile.browser.MainMenuRow.NewPrivateTab
import baby.freedom.mobile.browser.MainMenuRow.NewTab
import baby.freedom.mobile.browser.MainMenuRow.Nodes
import baby.freedom.mobile.browser.MainMenuRow.Print
import baby.freedom.mobile.browser.MainMenuRow.Settings
import baby.freedom.mobile.browser.MainMenuRow.SitePermissions
import baby.freedom.mobile.browser.MainMenuRow.Wallet
import baby.freedom.mobile.browser.MainMenuRow.Zoom
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main menu's rows, groups and icon row (#400). */
class MainMenuTest {

    private val everything = MainMenuContext(
        isHome = false,
        hasNameTrust = true,
        canOpenPrivateTab = true,
        hasAdblock = true,
        hasSitePermissions = true,
        canAddToHomeScreen = true,
    )

    @Test
    fun `on a page every group shows, in Chrome's order`() {
        assertEquals(
            listOf(
                listOf(NameTrust),
                listOf(Home, NewTab, NewPrivateTab),
                listOf(FindInPage, Zoom, DesktopSite, Print, BlockAds, SitePermissions, HardReload, AddToHomeScreen),
                listOf(History, Bookmarks, Downloads, Wallet),
                listOf(Settings, Nodes),
            ),
            mainMenuGroups(everything),
        )
    }

    @Test
    fun `on the home surface the page group and Home are hidden, not disabled`() {
        val home = mainMenuGroups(everything.copy(isHome = true))
        assertEquals(
            listOf(
                listOf(NewTab, NewPrivateTab),
                listOf(History, Bookmarks, Downloads, Wallet),
                listOf(Settings, Nodes),
            ),
            home,
        )
        val shown = home.flatten()
        for (row in listOf(NameTrust, FindInPage, Zoom, DesktopSite, Print, HardReload, AddToHomeScreen, Home)) {
            assertFalse("$row on home", row in shown)
        }
    }

    @Test
    fun `a plain page keeps its always-there rows and leaves out the optional ones`() {
        assertEquals(
            listOf(
                listOf(Home, NewTab),
                listOf(FindInPage, Zoom, DesktopSite, Print, HardReload),
                listOf(History, Bookmarks, Downloads, Wallet),
                listOf(Settings, Nodes),
            ),
            mainMenuGroups(MainMenuContext(isHome = false, canOpenPrivateTab = false)),
        )
    }

    @Test
    fun `the name-trust row is the first list row`() {
        assertEquals(NameTrust, mainMenuGroups(everything).first().first())
    }

    @Test
    fun `no group is ever empty, so no two dividers meet`() {
        for (home in listOf(true, false)) for (bits in 0 until 32) {
            val c = MainMenuContext(
                isHome = home,
                hasNameTrust = bits and 1 != 0,
                canOpenPrivateTab = bits and 2 != 0,
                hasAdblock = bits and 4 != 0,
                hasSitePermissions = bits and 8 != 0,
                canAddToHomeScreen = bits and 16 != 0,
            )
            val groups = mainMenuGroups(c)
            assertTrue(groups.none { it.isEmpty() })
            // Each row at most once, and Wallet and Settings always there.
            assertEquals(groups.flatten().size, groups.flatten().toSet().size)
            assertTrue(Wallet in groups.flatten() && Settings in groups.flatten())
        }
    }

    private fun icons(
        canGoBack: Boolean = true,
        canGoForward: Boolean = false,
        isHome: Boolean = false,
        url: String = "https://example.org/",
        addressBarText: String = "https://example.org/",
        isBookmarked: Boolean = false,
        loading: Boolean = false,
    ) = mainMenuIconsFor(canGoBack, canGoForward, isHome, url, addressBarText, isBookmarked, loading)

    @Test
    fun `Forward is enabled exactly when the capsule shows its Forward half`() {
        for (back in listOf(true, false)) for (fwd in listOf(true, false)) for (home in listOf(true, false)) {
            assertEquals(
                navControlsFor(back, fwd, home).showsForward,
                icons(canGoBack = back, canGoForward = fwd, isHome = home).forwardEnabled,
            )
        }
        assertTrue(icons(canGoForward = true).forwardEnabled)
        assertFalse(icons(canGoForward = false).forwardEnabled)
    }

    @Test
    fun `Bookmark shows the page's bookmarked state and needs a page`() {
        assertTrue(icons(isBookmarked = true).bookmarked)
        assertFalse(icons(isBookmarked = false).bookmarked)
        assertFalse(icons(url = "", addressBarText = "", isHome = true).bookmarkEnabled)
    }

    @Test
    fun `Share shares what the address field's long-press Share does`() {
        assertEquals("https://example.org/", icons().shareUrl)
        assertEquals(urlActionTarget("ens://vitalik.eth", "ens://vitalik.eth"), icons(url = "ens://vitalik.eth", addressBarText = "ens://vitalik.eth").shareUrl)
        assertNull(icons(url = "", addressBarText = "", isHome = true).shareUrl)
    }

    @Test
    fun `Reload turns into Stop while a load runs, and is off on home`() {
        assertEquals(MainMenuReload.Reload, icons().reload)
        assertEquals(MainMenuReload.Stop, icons(loading = true).reload)
        assertEquals(MainMenuReload.None, icons(url = "", addressBarText = "", isHome = true).reload)
        // A typed address still loading before anything committed: Stop.
        assertEquals(MainMenuReload.Stop, icons(url = "", addressBarText = "ens://x.eth", loading = true).reload)
    }

    @Test
    fun `the Wallet row says Not set up only when there is no wallet`() {
        assertEquals("Not set up", walletMenuNote(Vault.State.Empty))
        assertEquals("Can’t be read", walletMenuNote(Vault.State.Unreadable))
        val info = Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = false, backedUp = true)
        assertNull(walletMenuNote(Vault.State.Locked(info)))
        assertNull(walletMenuNote(Vault.State.Unlocked(info)))
    }

    @Test
    fun `a shortcut is offered for web and dweb pages in a regular tab`() {
        assertEquals("https://example.org/a?b=c", homeScreenShortcutTarget("https://example.org/a?b=c", private = false))
        assertEquals("ens://app.swarmit.eth", homeScreenShortcutTarget("ens://app.swarmit.eth", private = false))
        assertEquals("bzz://abc/index.html", homeScreenShortcutTarget(" bzz://abc/index.html ", private = false))
    }

    @Test
    fun `never a shortcut in a private tab`() {
        assertNull(homeScreenShortcutTarget("https://example.org/", private = true))
        assertNull(homeScreenShortcutTarget("ens://app.swarmit.eth", private = true))
    }

    @Test
    fun `no shortcut for home, error pages, other schemes or bidi tricks`() {
        assertNull(homeScreenShortcutTarget("", private = false))
        assertNull(homeScreenShortcutTarget("about:blank", private = false))
        assertNull(homeScreenShortcutTarget(ErrorPage.URL + "?url=x", private = false))
        assertNull(homeScreenShortcutTarget("javascript:alert(1)", private = false))
        assertNull(homeScreenShortcutTarget("file:///sdcard/x.html", private = false))
        assertNull(homeScreenShortcutTarget("https://example.org/‮gnp.exe", private = false))
    }

    @Test
    fun `one shortcut id per address`() {
        val a = homeScreenShortcutId("https://example.org/")
        assertEquals(a, homeScreenShortcutId("https://example.org/"))
        assertTrue(a != homeScreenShortcutId("https://example.org/x"))
        assertTrue(a.startsWith("page-") && a.length == "page-".length + 32)
    }

    @Test
    fun `the shortcut label is the title, else the host or name`() {
        assertEquals("Example", homeScreenShortcutLabel("  Example ", "https://example.org/"))
        assertEquals("example.org", homeScreenShortcutLabel("", "https://example.org/path?q#f"))
        assertEquals("app.swarmit.eth", homeScreenShortcutLabel(" ", "ens://app.swarmit.eth"))
        assertEquals("abc", homeScreenShortcutLabel("a‮bc", "https://example.org/"))
    }
}
