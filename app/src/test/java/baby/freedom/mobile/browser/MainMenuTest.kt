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
        url: String = "https://example.org/",
        addressBarText: String = "https://example.org/",
        isBookmarked: Boolean = false,
        loading: Boolean = false,
    ) = mainMenuIconsFor(url, addressBarText, isBookmarked, loading)

    @Test
    fun `the icon row has no Forward - the capsule's pill carries it`() {
        // Bookmark · Share · Reload only (#415): Forward lives on the
        // Back + Forward pill, which shows it exactly when it can act.
        val fields = MainMenuIcons::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("forward", ignoreCase = true) })
    }

    @Test
    fun `Bookmark shows the page's bookmarked state and needs a page`() {
        assertTrue(icons(isBookmarked = true).bookmarked)
        assertFalse(icons(isBookmarked = false).bookmarked)
        assertFalse(icons(url = "", addressBarText = "").bookmarkEnabled)
    }

    @Test
    fun `Share shares what the address field's long-press Share does`() {
        assertEquals("https://example.org/", icons().shareUrl)
        assertEquals(urlActionTarget("ens://vitalik.eth", "ens://vitalik.eth"), icons(url = "ens://vitalik.eth", addressBarText = "ens://vitalik.eth").shareUrl)
        assertNull(icons(url = "", addressBarText = "").shareUrl)
    }

    @Test
    fun `Reload turns into Stop while a load runs, and is off on home`() {
        assertEquals(MainMenuReload.Reload, icons().reload)
        assertEquals(MainMenuReload.Stop, icons(loading = true).reload)
        assertEquals(MainMenuReload.None, icons(url = "", addressBarText = "").reload)
        // A typed address still loading before anything committed: Stop.
        assertEquals(MainMenuReload.Stop, icons(url = "", addressBarText = "ens://x.eth", loading = true).reload)
    }

    @Test
    fun `the Wallet row says Not set up only when there is no wallet, and nothing once backed up`() {
        assertEquals("Not set up", walletMenuNote(Vault.State.Empty))
        assertEquals("Can’t be read", walletMenuNote(Vault.State.Unreadable))
        val info = Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = false, backedUp = true)
        assertNull(walletMenuNote(Vault.State.Locked(info)))
        assertNull(walletMenuNote(Vault.State.Unlocked(info)))
    }

    @Test
    fun `the Wallet row asks for a backup until the check has passed (W10)`() {
        val notBackedUp = Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = false, backedUp = false)
        assertEquals("Back up your wallet", walletMenuNote(Vault.State.Locked(notBackedUp)))
        assertEquals("Back up your wallet", walletMenuNote(Vault.State.Unlocked(notBackedUp)))
        // Shown but not checked (#421): still not backed up.
        val shown = notBackedUp.copy(phraseShown = true)
        assertEquals("Back up your wallet", walletMenuNote(Vault.State.Unlocked(shown)))
        // Google backup on doesn't count (#244 R5-F1).
        assertEquals("Back up your wallet", walletMenuNote(Vault.State.Unlocked(notBackedUp.copy(cloudBackup = true))))
    }

    @Test
    fun `a shortcut is offered for web and dweb pages in a regular tab`() {
        assertEquals("https://example.org/a?b=c", homeScreenShortcutTarget("https://example.org/a?b=c", private = false, errorPage = false))
        assertEquals("ens://app.swarmit.eth", homeScreenShortcutTarget("ens://app.swarmit.eth", private = false, errorPage = false))
        assertEquals("bzz://abc/index.html", homeScreenShortcutTarget(" bzz://abc/index.html ", private = false, errorPage = false))
    }

    @Test
    fun `never a shortcut in a private tab`() {
        assertNull(homeScreenShortcutTarget("https://example.org/", private = true, errorPage = false))
        assertNull(homeScreenShortcutTarget("ens://app.swarmit.eth", private = true, errorPage = false))
    }

    @Test
    fun `no shortcut for home, error pages, other schemes or bidi tricks`() {
        assertNull(homeScreenShortcutTarget("", private = false, errorPage = false))
        assertNull(homeScreenShortcutTarget("about:blank", private = false, errorPage = false))
        assertNull(homeScreenShortcutTarget(ErrorPage.URL + "?url=x", private = false, errorPage = false))
        assertNull(homeScreenShortcutTarget("javascript:alert(1)", private = false, errorPage = false))
        assertNull(homeScreenShortcutTarget("file:///sdcard/x.html", private = false, errorPage = false))
        assertNull(homeScreenShortcutTarget("https://example.org/‮gnp.exe", private = false, errorPage = false))
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

    @Test
    fun `no shortcut on an error page, which shows the address that failed`() {
        // What `state.url` holds on each kind of error page: the failed
        // address (ErrorPage's display URL, a failed web load's own
        // entry, a name refusal) — never the ErrorPage URL (R1-F1).
        assertNull(homeScreenShortcutTarget("http://10.0.2.2:8709/nothing", private = false, errorPage = true))
        assertNull(homeScreenShortcutTarget("ens://missing.eth", private = false, errorPage = true))
        assertEquals(
            "http://10.0.2.2:8709/nothing",
            homeScreenShortcutTarget("http://10.0.2.2:8709/nothing", private = false, errorPage = false),
        )
    }

    @Test
    fun `Desktop site is off on home, dweb pages and error pages`() {
        assertEquals("example.org", menuDesktopSite("www.example.org", "https://www.example.org/", errorPage = false))
        assertNull(menuDesktopSite("www.example.org", "https://www.example.org/", errorPage = true))
        assertNull(menuDesktopSite("www.example.org", "", errorPage = false))
        assertNull(menuDesktopSite(null, "ens://app.swarmit.eth", errorPage = false))
    }

    @Test
    fun `the shortcut label is one capped line with no hidden characters`() {
        assertEquals("Line one line two", homeScreenShortcutLabel("Line\none\u2028line\ttwo", "https://example.org/"))
        assertEquals("Bank", homeScreenShortcutLabel("B\u200Ba\uFEFFn\u00ADk\u202E", "https://example.org/"))
        assertEquals("a b", homeScreenShortcutLabel("a\u0000\u0085b", "https://example.org/"))
        // ZWJ sequences and tag-character flags survive.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, homeScreenShortcutLabel(family, "https://example.org/"))
        val scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        assertEquals(scotland, homeScreenShortcutLabel(scotland, "https://example.org/"))
        // A multi-kB title is cut, with an ellipsis.
        val long = homeScreenShortcutLabel("x".repeat(10_000), "https://example.org/")
        assertEquals("x".repeat(SHORTCUT_LABEL_MAX) + "…", long)
        // Exactly at the cap: kept whole.
        assertEquals("y".repeat(SHORTCUT_LABEL_MAX), homeScreenShortcutLabel("y".repeat(SHORTCUT_LABEL_MAX), "https://example.org/"))
        // Cut on a character boundary, never inside an emoji.
        val emoji = "\uD83D\uDE00".repeat(SHORTCUT_LABEL_MAX + 5)
        assertEquals("\uD83D\uDE00".repeat(SHORTCUT_LABEL_MAX) + "…", homeScreenShortcutLabel(emoji, "https://example.org/"))
        // However many grapheme clusters a run of ZWJ chains or tag
        // characters counts as, it is cut at the char cap, on a code point,
        // with no dangling joiner (R3-M1). (Android's ICU-backed
        // BreakIterator makes the 301-man chain one cluster; the JVM's may
        // not — see ShortcutLabelDeviceTest for the device's own answer.)
        val man = "\uD83D\uDC68"
        for (title in listOf(
            "$man\u200D".repeat(300) + man,
            "\uD83C\uDFF4" + "\uDB40\uDC67".repeat(1000),
            scotland.repeat(SHORTCUT_LABEL_MAX),
            family.repeat(SHORTCUT_LABEL_MAX) + "x",
        )) {
            val label = homeScreenShortcutLabel(title, "https://example.org/")
            assertTrue(label.endsWith("…"))
            val body = label.dropLast(1)
            assertTrue(body.length in 1..SHORTCUT_LABEL_CHARS)
            assertTrue(title.startsWith(body))
            assertFalse(body.endsWith("\u200D"))
            assertFalse(Character.isHighSurrogate(body.last()))
        }
        // Nothing left of the title: the host.
        assertEquals("example.org", homeScreenShortcutLabel("\u200B\u202E\n", "https://example.org/"))
        // A joiner leading a word, or straight after another joiner, means
        // nothing and is dropped — so a long run of them can't make one
        // cluster that cuts the label down to a lone ellipsis (R4-M1).
        assertEquals("Bank", homeScreenShortcutLabel("\u200D".repeat(300) + "Bank", "https://example.org/"))
        assertEquals("a b", homeScreenShortcutLabel("a \u200D\u200C\uDB40\uDC67b", "https://example.org/"))
        assertEquals("a\u200Db", homeScreenShortcutLabel("a" + "\u200D".repeat(300) + "b", "https://example.org/"))
        assertEquals(family, homeScreenShortcutLabel(family.replace("\u200D", "\u200D\u200D\u200C"), "https://example.org/"))
        // No joiner dangles before the ellipsis even with a space after it.
        val cut = homeScreenShortcutLabel("x".repeat(SHORTCUT_LABEL_MAX - 1) + "\u200D y", "https://example.org/")
        assertEquals("x".repeat(SHORTCUT_LABEL_MAX - 1) + "…", cut)
    }

    @Test
    fun `the shortcut label drops what draws blank and caps stacked marks`() {
        val url = "https://example.org/"
        // A title of only blank-drawing code points falls back to the host.
        for (blank in listOf("\u3164", "\uFFA0", "\u115F\u1160", "\u2800", "\uFE0F\uFE0F", "\u034F",
            "\uDB40\uDD00", "\u200D\u200C", "\u0301\u0301", " \u3164 \u2800 ")) {
            assertEquals("example.org", homeScreenShortcutLabel(blank, url))
        }
        assertEquals("ab", homeScreenShortcutLabel("a\u3164\u2800b\uFFA0", url))
        // One variation selector after a character that draws stays (emoji presentation).
        assertEquals("\u2764\uFE0F", homeScreenShortcutLabel("\u2764\uFE0F", url))
        assertEquals("\u2764\uFE0F", homeScreenShortcutLabel("\u2764\uFE0F\uFE0F\uDB40\uDD00", url))
        assertEquals("a b", homeScreenShortcutLabel("a \uFE0Fb", url))
        // A run of combining marks is cut at the cap, however long; dropped
        // code points and joiners between marks don't restart it.
        val e = "e" + "\u0301".repeat(SHORTCUT_LABEL_MARKS)
        assertEquals(e, homeScreenShortcutLabel("e" + "\u0301".repeat(2000), url))
        assertEquals(e, homeScreenShortcutLabel("e\u0301\u3164\u0301\u2065\u0301\u0301\u0301", url))
        assertEquals(e + "\u200D", homeScreenShortcutLabel("e\u0301\u0301\u0301\u200D\u0301\u0301", url))
        // A new character starts a new run: ordinary accented text is untouched.
        assertEquals("re\u0301sume\u0301 e\u0323\u0302", homeScreenShortcutLabel("re\u0301sume\u0301 e\u0323\u0302", url))
    }
}
