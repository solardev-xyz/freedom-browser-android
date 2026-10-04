package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.Vault
import java.security.MessageDigest
import java.text.BreakIterator

/**
 * The main menu's rows (#400), the popup from the ≡ in the address
 * capsule. Above them sits a row of icons for the page actions people
 * use most ([MainMenuIcons]); the rows themselves come in groups, with a
 * divider between each, in the order Chrome uses.
 */
internal enum class MainMenuRow {
    /** How the page's name was checked (#97); opens the shield's details. */
    NameTrust,

    // Tabs.
    Home,
    NewTab,
    NewPrivateTab,

    // The page.
    FindInPage,
    Zoom,
    DesktopSite,
    Print,
    BlockAds,
    SitePermissions,
    HardReload,
    AddToHomeScreen,

    // Library.
    History,
    Bookmarks,
    Downloads,
    Wallet,

    // The app.
    Settings,
    Nodes,
}

/** What decides which of [MainMenuRow] the menu shows. */
internal data class MainMenuContext(
    /** The tab is on its home surface: there's no page to act on. */
    val isHome: Boolean,
    /** The page has a name-trust shield (#97). */
    val hasNameTrust: Boolean = false,
    /** Private tabs can run here (#86). */
    val canOpenPrivateTab: Boolean = true,
    /** The page has an ad-blocking switch (#126). */
    val hasAdblock: Boolean = false,
    /** The site holds a permission (#266). */
    val hasSitePermissions: Boolean = false,
    /**
     * The page can be pinned to the launcher: not a private tab, an
     * address another app could open, and a launcher that takes pins.
     */
    val canAddToHomeScreen: Boolean = false,
)

/**
 * The menu's groups, top to bottom, each a non-empty list of rows; the
 * menu draws a divider between groups.
 *
 * On the home surface the page group's rows are *hidden* rather than
 * shown disabled — there is no page there for any of them, as on Chrome's
 * New Tab Page. Where a page exists but a row can't apply to it (Desktop
 * site on a dweb page, Find in a tab whose renderer went away) the row
 * stays, disabled, so the menu doesn't change shape from page to page.
 * Home itself goes too: the tab is already there.
 */
internal fun mainMenuGroups(c: MainMenuContext): List<List<MainMenuRow>> {
    val trust = listOfNotNull(MainMenuRow.NameTrust.takeIf { c.hasNameTrust && !c.isHome })
    val tabs = listOfNotNull(
        MainMenuRow.Home.takeIf { !c.isHome },
        MainMenuRow.NewTab,
        MainMenuRow.NewPrivateTab.takeIf { c.canOpenPrivateTab },
    )
    val page = if (c.isHome) {
        emptyList()
    } else {
        listOfNotNull(
            MainMenuRow.FindInPage,
            MainMenuRow.Zoom,
            MainMenuRow.DesktopSite,
            MainMenuRow.Print,
            MainMenuRow.BlockAds.takeIf { c.hasAdblock },
            MainMenuRow.SitePermissions.takeIf { c.hasSitePermissions },
            MainMenuRow.HardReload,
            MainMenuRow.AddToHomeScreen.takeIf { c.canAddToHomeScreen },
        )
    }
    val library = listOf(
        MainMenuRow.History,
        MainMenuRow.Bookmarks,
        MainMenuRow.Downloads,
        MainMenuRow.Wallet,
    )
    val app = listOf(MainMenuRow.Settings, MainMenuRow.Nodes)
    return listOf(trust, tabs, page, library, app).filter { it.isNotEmpty() }
}

/** What the icon row's last button does. */
internal enum class MainMenuReload {
    Reload,

    /** A load is running: the button stops it, as the capsule's own × does. */
    Stop,

    /** Nothing to reload (the home surface): Reload, disabled. */
    None,
}

/**
 * The icon row at the top of the menu: Forward · Bookmark · Share ·
 * Reload. Always all four, in the same places, disabled where they can't
 * act, so a finger that learned where Reload is finds it on every page.
 */
internal data class MainMenuIcons(
    val forwardEnabled: Boolean,
    val bookmarkEnabled: Boolean,
    val bookmarked: Boolean,
    /** What Share hands to the share sheet, or null (disabled). */
    val shareUrl: String?,
    val reload: MainMenuReload,
)

/**
 * The icon row for a tab. Forward follows the capsule's own rule
 * ([navControlsFor]: the pill grows a Forward half exactly then), Share
 * shares what the address field's long-press Share does
 * ([urlActionTarget]), and Reload/Stop is the capsule's trailing control
 * ([capsuleTrailingControl]) as it reads at rest — the menu only opens
 * while the field isn't being edited.
 */
internal fun mainMenuIconsFor(
    canGoBack: Boolean,
    canGoForward: Boolean,
    isHome: Boolean,
    url: String,
    addressBarText: String,
    isBookmarked: Boolean,
    loading: Boolean,
): MainMenuIcons {
    val reload = when (
        capsuleTrailingControl(
            addressFocused = false,
            addressBarEdited = false,
            editBufferEmpty = false,
            loading = loading,
            canReload = url.isNotBlank() || addressBarText.isNotBlank(),
        )
    ) {
        CapsuleTrailingControl.Stop -> MainMenuReload.Stop
        CapsuleTrailingControl.Reload -> MainMenuReload.Reload
        else -> MainMenuReload.None
    }
    return MainMenuIcons(
        forwardEnabled = navControlsFor(canGoBack, canGoForward, isHome).showsForward,
        bookmarkEnabled = url.isNotBlank(),
        bookmarked = isBookmarked,
        shareUrl = urlActionTarget(addressBarText, url),
        reload = reload,
    )
}

/**
 * The Wallet row's sub-line: only what needs saying and is already known
 * without unlocking anything — that there is no wallet yet, or that the
 * one on the device can't be read. Locked/unlocked isn't news.
 */
internal fun walletMenuNote(state: Vault.State): String? = when (state) {
    Vault.State.Empty -> Strings.get(R.string.browser_menu_wallet_not_set_up)
    Vault.State.Unreadable -> Strings.get(R.string.wallet_summary_unreadable)
    is Vault.State.Locked, is Vault.State.Unlocked -> null
}

/**
 * The address a Home-screen shortcut to the page on screen opens, or null
 * where the menu doesn't offer one: a private tab (a launcher icon is a
 * trace of the visit, and it would open in a regular tab anyway), the
 * home surface, one of the browser's own error pages, and anything the
 * incoming-link path ([IncomingLinks.link]) wouldn't open — so the
 * shortcut can only ever ask for what a link from another app could. An
 * address with a bidi control in it is refused too: the launcher would
 * show its label in an order the address doesn't have.
 *
 * [url] is the tab's committed address in its display form (what the
 * address bar shows: `ens://name`, `bzz://…`, `https://…`). On an error
 * page that is the address that *failed* — the [ErrorPage] URL itself
 * never reaches it — so [errorPage] ([BrowserState.showsErrorPage]) is
 * what says the page is one (R1-F1).
 */
internal fun homeScreenShortcutTarget(url: String, private: Boolean, errorPage: Boolean): String? {
    if (private || errorPage) return null
    val u = url.trim()
    if (u.isEmpty() || ErrorPage.isErrorPage(u)) return null
    if (u.any(BidiControls::isBidiControl)) return null
    return IncomingLinks.link(u)
}

/**
 * The menu's Desktop site switch for the page on screen: the site it
 * applies to, or null where the row shows disabled — the home surface, a
 * dweb page (no site key, [desktopSiteOf]) and an error page, where
 * there is no site's page to ask for again (R1-M1). [zoomSite] is
 * [BrowserState.zoomSite], which an error page served in a failed web
 * load's own entry still has.
 */
internal fun menuDesktopSite(zoomSite: String?, url: String, errorPage: Boolean): String? =
    desktopSiteOf(zoomSite)?.takeIf { url.isNotBlank() && !errorPage }

/**
 * The shortcut's id: one per address, so pinning the same page again
 * finds that shortcut rather than adding another. Hashed to a fixed
 * length, however long the address.
 */
internal fun homeScreenShortcutId(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    return "page-" + digest.take(16).joinToString("") { "%02x".format(it) }
}

/** At most this many characters (grapheme clusters) of a page title in a launcher label. */
internal const val SHORTCUT_LABEL_MAX = 32

/** At most this many combining marks in a row on one character of a launcher label. */
internal const val SHORTCUT_LABEL_MARKS = 3

/**
 * Code points that draw nothing but aren't format, control or unassigned
 * characters: the Default_Ignorable_Code_Point ones of other categories
 * (Hangul fillers, combining grapheme joiner, Khmer inherent vowels,
 * Mongolian variation selectors, U+FFF0–FFF8 are unassigned anyway), and
 * U+2800, the blank Braille pattern. Variation selectors are handled
 * separately.
 */
private fun drawsBlank(cp: Int): Boolean =
    cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp in 0x17B4..0x17B5 ||
        cp in 0x180B..0x180F || cp == 0x2800 || cp == 0x3164 || cp == 0xFFA0 ||
        cp in 0x1BCA0..0x1BCA3 || cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF && cp !in 0xE0020..0xE007F

private fun isVariationSelector(cp: Int): Boolean =
    cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
    // Spacing marks (Mc) advance like letters; these stack on their base.
    Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

/**
 * The launcher label: the page's title, or where it has none, its host
 * (or name, for a dweb address). The title is the page's own text, so it
 * is made fit for a one-line label under an icon first (R1-M2): line
 * breaks, tabs, other controls and the line and paragraph separators
 * (U+2028/U+2029) become spaces, and runs of spaces collapse; format
 * characters (bidi overrides and isolates, zero-width spaces, U+FEFF),
 * unassigned, private-use and lone surrogate code points are dropped —
 * keeping ZWJ, ZWNJ and the tag characters emoji are built from; so are
 * the code points that draw blank without being any of those (the rest of
 * Default_Ignorable_Code_Point — Hangul fillers U+115F/U+1160/U+3164/
 * U+FFA0, U+034F and the like — and U+2800), and every variation selector
 * but one straight after a character that draws (R2-M1). A run of
 * combining marks on one character is cut at [SHORTCUT_LABEL_MARKS], so a
 * stack of them can't draw over the labels around it. A title left with
 * nothing that draws falls back to the host. It is cut to
 * [SHORTCUT_LABEL_MAX] characters, on a character boundary, with an
 * ellipsis.
 */
internal fun homeScreenShortcutLabel(title: String, url: String): String {
    val t = shortcutLabelText(title)
    if (t.isNotEmpty()) return t
    val host = url.substringAfter("://", url)
        .substringBefore('/').substringBefore('?').substringBefore('#')
    return shortcutLabelText(host).ifEmpty { url.take(SHORTCUT_LABEL_MAX) }
}

private fun shortcutLabelText(text: String): String {
    val clean = StringBuilder(minOf(text.length, 4 * SHORTCUT_LABEL_MAX + 16))
    var i = 0
    // Only so much of a multi-kB title is worth looking at.
    val end = minOf(text.length, 64 * SHORTCUT_LABEL_MAX)
    // What was last appended, for the selector and mark rules: whether it
    // was a character that draws (not a space, mark, joiner or selector),
    // and how many marks have followed it. Dropped code points append
    // nothing, so they don't end a run either.
    var afterVisible = false
    var marks = 0
    var visible = false
    while (i < end) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.getType(cp) == Character.CONTROL.toInt() ||
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> {
                clean.append(' ')
                afterVisible = false
                marks = 0
            }
            isVariationSelector(cp) -> if (afterVisible) {
                clean.appendCodePoint(cp)
                afterVisible = false
            }
            drawsBlank(cp) -> Unit
            // Not a run end: marks after a joiner still stack on the base.
            cp == 0x200C || cp == 0x200D || cp in 0xE0020..0xE007F -> {
                clean.appendCodePoint(cp)
                afterVisible = false
            }
            when (Character.getType(cp)) {
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                Character.SURROGATE.toInt(),
                Character.UNASSIGNED.toInt(),
                Character.PRIVATE_USE.toInt() -> true
                else -> false
            } -> Unit
            isMark(cp) -> if (marks < SHORTCUT_LABEL_MARKS) {
                clean.appendCodePoint(cp)
                marks++
                afterVisible = false
            }
            else -> {
                clean.appendCodePoint(cp)
                afterVisible = true
                visible = true
                marks = 0
            }
        }
    }
    if (!visible) return ""
    val collapsed = clean.toString().replace(Regex(" {2,}"), " ").trim()
    val chars = BreakIterator.getCharacterInstance()
    chars.setText(collapsed)
    var cut = chars.first()
    repeat(SHORTCUT_LABEL_MAX) {
        val next = chars.next()
        if (next == BreakIterator.DONE) return collapsed
        cut = next
    }
    if (chars.next() == BreakIterator.DONE) return collapsed
    return collapsed.substring(0, cut).trimEnd() + "…"
}
