package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.swarm.IpfsInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings as two levels (#400): which card is on which page, the top level, and grouped search results. */
class SettingsPagesTest {
    @Test
    fun `each sub-page holds its cards in order`() {
        for (isDefault in listOf(false, true)) {
            fun sections(page: SettingsPage) = settingsSections(page, isDefault)
            assertEquals(listOf(SettingsSection.Appearance), sections(SettingsPage.Appearance))
            assertEquals(listOf(SettingsSection.Downloads), sections(SettingsPage.Downloads))
            assertEquals(
                listOf(SettingsSection.Browsing, SettingsSection.Permissions, SettingsSection.Tor),
                sections(SettingsPage.Privacy),
            )
            assertEquals(listOf(SettingsSection.Adblock), sections(SettingsPage.Adblock))
            // The wallet's row, then Chains.
            assertEquals(listOf(SettingsSection.Wallet, SettingsSection.Chains), sections(SettingsPage.Wallet))
            // The whole expert block: resolution order, Colibri, CCIP-Read, then the RPC providers.
            assertEquals(listOf(SettingsSection.Ens, SettingsSection.Rpc), sections(SettingsPage.Names))
            // Swarm endpoint, IPFS and Radicle, then the IPFS node itself.
            assertEquals(listOf(SettingsSection.Nodes, SettingsSection.Ipfs), sections(SettingsPage.Nodes))
            assertEquals(listOf(SettingsSection.Search), sections(SettingsPage.SearchEngine))
        }
    }

    @Test
    fun `default browser is a top-level row until Freedom is the default, then a line on About`() {
        assertEquals(SettingsPage.DefaultBrowser, SettingsSection.DefaultBrowser.page(isDefaultBrowser = false))
        assertEquals(listOf(SettingsSection.About), settingsSections(SettingsPage.About, isDefaultBrowser = false))
        assertEquals(SettingsPage.About, SettingsSection.DefaultBrowser.page(isDefaultBrowser = true))
        assertEquals(
            listOf(SettingsSection.About, SettingsSection.DefaultBrowser),
            settingsSections(SettingsPage.About, isDefaultBrowser = true),
        )
        assertTrue(SettingsPage.DefaultBrowser in settingsTopLevel(false).flatMap { it.second })
        assertFalse(SettingsPage.DefaultBrowser in settingsTopLevel(true).flatMap { it.second })
        // The muted line is still found, under About Freedom.
        assertEquals(
            listOf(SettingsPage.About to listOf(SettingsSection.DefaultBrowser)),
            settingsResultGroups(mapOf(SettingsSection.DefaultBrowser to setOf<Any>("default")), true),
        )
    }

    @Test
    fun `the default-browser line on About still says how to change it`() {
        // R2-M1: once Freedom is the default, the About line is the only
        // way back to Android's Default apps page, and it says so.
        val rows = defaultBrowserRows(isDefault = true)
        assertEquals(setOf<Any>("default"), visibleSettingsRows("android settings", "Default browser", rows))
        assertEquals(emptySet<Any>(), visibleSettingsRows("android settings", "Default browser", defaultBrowserRows(false)))
    }

    @Test
    fun `top level is grouped basics, privacy, web3, about`() {
        val top = settingsTopLevel(isDefaultBrowser = false)
        assertEquals(
            listOf(SettingsGroup.General, SettingsGroup.Privacy, SettingsGroup.Web3, SettingsGroup.About),
            top.map { it.first },
        )
        assertEquals(
            listOf(
                SettingsPage.SearchEngine, SettingsPage.Appearance, SettingsPage.Downloads, SettingsPage.DefaultBrowser,
                SettingsPage.Privacy, SettingsPage.Adblock,
                SettingsPage.Wallet, SettingsPage.Names, SettingsPage.Nodes,
                SettingsPage.About,
            ),
            top.flatMap { it.second },
        )
        // Every page is on the top level once, and every card is on a page that is.
        assertEquals(SettingsPage.entries.toSet(), SETTINGS_PAGE_ORDER.toSet())
        assertEquals(SettingsPage.entries.size, SETTINGS_PAGE_ORDER.size)
        for (isDefault in listOf(false, true)) {
            val shown = settingsTopLevel(isDefault).flatMap { it.second }.toSet()
            for (section in SettingsSection.entries) {
                assertTrue("$section/$isDefault", section.page(isDefault) in shown)
            }
        }
    }

    @Test
    fun `only search engine and default browser have no sub-page`() {
        assertEquals(
            setOf(SettingsPage.SearchEngine, SettingsPage.DefaultBrowser),
            SettingsPage.entries.filterNot { it.hasSubPage }.toSet(),
        )
    }

    @Test
    fun `page titles are distinct and named`() {
        val titles = SettingsPage.entries.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("Privacy & security", SettingsPage.Privacy.title)
        assertEquals("Wallet & chains", SettingsPage.Wallet.title)
        assertEquals("Nodes & networks", SettingsPage.Nodes.title)
        assertEquals("Name resolution", SettingsPage.Names.title)
        assertEquals("About Freedom", SettingsPage.About.title)
    }

    @Test
    fun `results group cards under their page in top-level order`() {
        val visible = mapOf<SettingsSection, Set<Any>>(
            SettingsSection.About to setOf("version"),
            SettingsSection.Tor to setOf("tor-enabled"),
            SettingsSection.Chains to setOf(1L),
            SettingsSection.Browsing to setOf("history"),
            SettingsSection.Search to setOf("engine"),
            SettingsSection.Permissions to emptySet(),
            SettingsSection.Rpc to setOf("public"),
        )
        assertEquals(
            listOf(
                SettingsPage.SearchEngine to listOf(SettingsSection.Search),
                SettingsPage.Privacy to listOf(SettingsSection.Browsing, SettingsSection.Tor),
                SettingsPage.Wallet to listOf(SettingsSection.Chains),
                SettingsPage.Names to listOf(SettingsSection.Rpc),
                SettingsPage.About to listOf(SettingsSection.About),
            ),
            settingsResultGroups(visible, isDefaultBrowser = false),
        )
        assertEquals(emptyList<Any>(), settingsResultGroups(emptyMap(), isDefaultBrowser = false))
        assertEquals(
            emptyList<Any>(),
            settingsResultGroups(mapOf(SettingsSection.Tor to emptySet()), isDefaultBrowser = false),
        )
    }

    @Test
    fun `cards with no page of their own come before every page heading`() {
        // R3-M1: Default browser has no heading, so after Downloads it
        // read as part of the Downloads page.
        val visible = mapOf<SettingsSection, Set<Any>>(
            SettingsSection.Downloads to setOf("ask"),
            SettingsSection.DefaultBrowser to setOf("default"),
            SettingsSection.Appearance to setOf("theme"),
            SettingsSection.Search to setOf("engine"),
        )
        assertEquals(
            listOf(
                SettingsPage.SearchEngine to listOf(SettingsSection.Search),
                SettingsPage.DefaultBrowser to listOf(SettingsSection.DefaultBrowser),
                SettingsPage.Appearance to listOf(SettingsSection.Appearance),
                SettingsPage.Downloads to listOf(SettingsSection.Downloads),
            ),
            settingsResultGroups(visible, isDefaultBrowser = false),
        )
        val groups = settingsResultGroups(SettingsSection.entries.associateWith { setOf<Any>("x") }, false)
        assertEquals(groups.sortedBy { it.first.hasSubPage }, groups)
    }

    @Test
    fun `a query naming a page shows every row of its cards`() {
        val rows = torRows(enabled = false, startOnLaunch = false)
        assertEquals(emptySet<Any>(), visibleSettingsRows("privacy", "Tor", rows))
        assertEquals(
            rows.map { it.key }.toSet(),
            visibleSettingsRows("privacy", "Tor", rows, SettingsPage.Privacy.title),
        )
        // Without a page match, rows still match on their own text.
        assertEquals(setOf("tor-client"), visibleSettingsRows("orbot", "Tor", rows, SettingsPage.Privacy.title))
    }

    @Test
    fun `real rows land on their pages`() {
        fun groups(query: String): List<Pair<SettingsPage, List<SettingsSection>>> {
            val rows = mapOf(
                SettingsSection.Tor to torRows(false, false),
                SettingsSection.Chains to chainSettingsRows(BuiltInChains.ALL),
                SettingsSection.Nodes to nodeRows("", ""),
                SettingsSection.Ipfs to ipfsRows(IpfsInfo()),
                SettingsSection.Ens to ensSectionRows(EnsRpcConfig()),
                SettingsSection.Rpc to rpcSectionRows(EnsRpcConfig()),
            )
            val visible = rows.mapValues { (section, list) ->
                visibleSettingsRows(query, section.name, list, section.page(false).title)
            }
            return settingsResultGroups(visible, isDefaultBrowser = false)
        }
        assertEquals(listOf(SettingsPage.Wallet to listOf(SettingsSection.Chains)), groups("gnosis"))
        assertEquals(listOf(SettingsPage.Privacy to listOf(SettingsSection.Tor)), groups("orbot"))
        // IPFS, once behind "Show advanced options", is found without it.
        assertEquals(
            listOf(SettingsPage.Nodes to listOf(SettingsSection.Nodes, SettingsSection.Ipfs)),
            groups("ipfs"),
        )
        assertEquals(listOf(SettingsPage.Names to listOf(SettingsSection.Ens)), groups("colibri"))
        assertEquals(
            listOf(SettingsPage.Names to listOf(SettingsSection.Ens, SettingsSection.Rpc)),
            groups("name resolution"),
        )
        // The page's name finds the Nodes page row too.
        assertTrue("nodes-page" in visibleSettingsRows("node status", "Nodes", nodeRows("", "")))
    }

    @Test
    fun `top-level summaries`() {
        assertEquals("System default · Deutsch", appearancePageSummary("System default", "Deutsch"))
        assertEquals("Dark", appearancePageSummary("Dark", null))
        assertEquals("Saved to Download/Freedom", downloadsPageSummary(false))
        assertEquals("Asks where to save each file", downloadsPageSummary(true))
        assertEquals("No site permissions · Tor off", privacyPageSummary(0, false))
        assertEquals("1 site permission · Tor on", privacyPageSummary(1, true))
        assertEquals("4 site permissions · Tor off", privacyPageSummary(4, false))
        assertEquals("Off", adblockPageSummary(0, 4, 0))
        assertEquals("Off · 2 sites allowed", adblockPageSummary(0, 4, 2))
        assertEquals("2 of 4 blocklists on · 1 site allowed", adblockPageSummary(2, 4, 1))
        assertEquals("2 of 4 blocklists on", adblockPageSummary(2, 4, 0))
        assertEquals("Locked · 1 chain", walletPageSummary("Locked", 1))
        assertEquals("Locked · 3 chains", walletPageSummary("Locked", 3))
        assertEquals("Colibri proofs on · 5 RPC endpoints", namesPageSummary(true, 5))
        assertEquals("Colibri proofs off · 1 RPC endpoint", namesPageSummary(false, 1))
        assertEquals(
            "Swarm: embedded · IPFS: external · Radicle: Off",
            nodesPageSummary("", "https://gw.example", "Off"),
        )
    }
}
