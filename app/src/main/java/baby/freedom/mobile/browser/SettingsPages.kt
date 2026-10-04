package baby.freedom.mobile.browser

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/*
 * Settings as two levels (#400, items 6–9 and 12): a short top-level
 * list, grouped like Chrome's and Safari's, whose rows open sub-pages
 * holding the cards the page used to list one after another. Search
 * still covers every card: its results are the matching rows of every
 * sub-page, under the sub-page's name.
 *
 * This file is the model — which page a card lives on, in what order,
 * which top-level rows show, and the one-line summary under each — kept
 * free of Compose so it is unit-tested; [SettingsScreen] draws it.
 */

/**
 * A top-level row of Settings. Most open a sub-page; [SearchEngine]
 * opens the engine dialog straight away, and [DefaultBrowser] asks
 * Android for the browser role ([hasSubPage] false for both).
 */
internal enum class SettingsPage(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val hasSubPage: Boolean = true,
) {
    SearchEngine(R.string.settings_search_engine, Icons.Filled.Search, hasSubPage = false),
    Appearance(R.string.settings_section_appearance, Icons.Filled.Contrast),
    Downloads(R.string.settings_section_downloads, Icons.Filled.Download),
    DefaultBrowser(R.string.settings_default_browser, Icons.Filled.OpenInBrowser, hasSubPage = false),
    Privacy(R.string.settings_page_privacy, Icons.Filled.Lock),
    Adblock(R.string.settings_section_adblock, Icons.Filled.Shield),
    Wallet(R.string.settings_page_wallet, Icons.Filled.AccountBalanceWallet),
    Names(R.string.names_section_ens, Icons.Filled.Translate),
    Nodes(R.string.settings_page_nodes, Icons.Filled.Hub),
    About(R.string.settings_page_about, Icons.Filled.Info),
    ;

    val title: String get() = Strings.get(titleRes)
}

/** The top level's groups, in order: basics, privacy, web3, about. */
internal enum class SettingsGroup(@StringRes val titleRes: Int, val pages: List<SettingsPage>) {
    General(
        R.string.settings_group_general,
        listOf(SettingsPage.SearchEngine, SettingsPage.Appearance, SettingsPage.Downloads, SettingsPage.DefaultBrowser),
    ),
    Privacy(R.string.settings_group_privacy, listOf(SettingsPage.Privacy, SettingsPage.Adblock)),
    Web3(R.string.settings_group_web3, listOf(SettingsPage.Wallet, SettingsPage.Names, SettingsPage.Nodes)),
    About(R.string.settings_group_about, listOf(SettingsPage.About)),
}

/**
 * The cards Settings is made of — each one a [SectionCard] (or, for
 * [DefaultBrowser] once Freedom holds the role, a muted line) with its
 * own rows and search index. Declared in the order they appear on their
 * page.
 */
internal enum class SettingsSection {
    Search,
    Appearance,
    Downloads,
    Browsing,
    Permissions,
    Tor,
    Adblock,
    Wallet,
    Chains,
    Ens,
    Rpc,
    Nodes,
    Ipfs,
    About,
    // Last: under the About card once it's a line there.
    DefaultBrowser,
    ;

    /**
     * The page this card lives on. Default browser is a top-level row
     * only while Freedom isn't the default browser; once it is, it's a
     * muted line on About Freedom (#400 item 12).
     */
    fun page(isDefaultBrowser: Boolean): SettingsPage = when (this) {
        Search -> SettingsPage.SearchEngine
        DefaultBrowser -> if (isDefaultBrowser) SettingsPage.About else SettingsPage.DefaultBrowser
        Appearance -> SettingsPage.Appearance
        Downloads -> SettingsPage.Downloads
        Browsing, Permissions, Tor -> SettingsPage.Privacy
        Adblock -> SettingsPage.Adblock
        Wallet, Chains -> SettingsPage.Wallet
        Ens, Rpc -> SettingsPage.Names
        Nodes, Ipfs -> SettingsPage.Nodes
        About -> SettingsPage.About
    }
}

/** Every page in top-level order. */
internal val SETTINGS_PAGE_ORDER: List<SettingsPage> = SettingsGroup.entries.flatMap { it.pages }

/** The cards [page] shows, in order. */
internal fun settingsSections(page: SettingsPage, isDefaultBrowser: Boolean): List<SettingsSection> =
    SettingsSection.entries.filter { it.page(isDefaultBrowser) == page }

/**
 * The top level's groups with the rows each shows: everything but the
 * Default browser row once Freedom is the default browser.
 */
internal fun settingsTopLevel(isDefaultBrowser: Boolean): List<Pair<SettingsGroup, List<SettingsPage>>> =
    SettingsGroup.entries.map { group ->
        group to group.pages.filter { it != SettingsPage.DefaultBrowser || !isDefaultBrowser }
    }.filter { it.second.isNotEmpty() }

/**
 * Search results (#93, #400): every card with a match ([visible] maps
 * each card to its matching rows' keys), grouped under the page it
 * lives on — pages in top-level order, cards in page order. A card with
 * no match, and a page with none left, is dropped.
 */
internal fun settingsResultGroups(
    visible: Map<SettingsSection, Set<Any>>,
    isDefaultBrowser: Boolean,
): List<Pair<SettingsPage, List<SettingsSection>>> =
    SETTINGS_PAGE_ORDER.mapNotNull { page ->
        settingsSections(page, isDefaultBrowser)
            .filter { visible[it].orEmpty().isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { page to it }
    }

// One-line summaries of each top-level row's current state.

internal fun appearancePageSummary(theme: String, language: String?): String =
    listOfNotNull(theme, language).joinToString(" · ")

internal fun downloadsPageSummary(askWhereToSave: Boolean): String = Strings.get(
    if (askWhereToSave) R.string.settings_page_downloads_on else R.string.settings_page_downloads_off,
)

/** "3 site permissions · Tor off": wallet connections count as site permissions, as on the page. */
internal fun privacyPageSummary(permissions: Int, torEnabled: Boolean): String = listOf(
    if (permissions == 0) {
        Strings.get(R.string.settings_page_privacy_no_permissions)
    } else {
        Strings.plural(R.plurals.settings_page_privacy_permissions, permissions, permissions)
    },
    Strings.get(if (torEnabled) R.string.settings_page_privacy_tor_on else R.string.settings_page_privacy_tor_off),
).joinToString(" · ")

/** "2 of 4 blocklists on · 1 site allowed", or "Off". */
internal fun adblockPageSummary(enabled: Int, total: Int, allowed: Int): String = listOfNotNull(
    if (enabled == 0) {
        Strings.get(R.string.settings_page_adblock_off)
    } else {
        Strings.get(R.string.settings_page_adblock_on, enabled, total)
    },
    if (allowed > 0) Strings.plural(R.plurals.settings_page_adblock_allowed, allowed, allowed) else null,
).joinToString(" · ")

internal fun walletPageSummary(wallet: String, chains: Int): String =
    "$wallet · ${Strings.plural(R.plurals.settings_page_wallet_chains, chains, chains)}"

/** "Colibri proofs on · 5 RPC endpoints": [endpoints] is the resolution order's length. */
internal fun namesPageSummary(colibri: Boolean, endpoints: Int): String = listOf(
    Strings.get(if (colibri) R.string.settings_page_names_colibri_on else R.string.settings_page_names_colibri_off),
    rpcCountLabel(endpoints),
).joinToString(" · ")

internal fun nodesPageSummary(externalSwarm: String, externalIpfs: String, radicle: String): String {
    fun source(external: String) = Strings.get(
        if (external.isEmpty()) R.string.settings_page_nodes_embedded else R.string.settings_page_nodes_external,
    )
    return Strings.get(R.string.settings_page_nodes_summary, source(externalSwarm), source(externalIpfs), radicle)
}
