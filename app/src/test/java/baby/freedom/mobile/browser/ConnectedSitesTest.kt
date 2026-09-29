package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.data.DappGrantStore
import baby.freedom.mobile.wallet.WalletAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/** How connected sites (#111) read in the wallet and in Settings → Site permissions, and how Settings search finds them. */
class ConnectedSitesTest {
    private val address = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val account = WalletAccount(0, "Account 1", address)
    private val grant = DappGrantStore.Grant("https://app.example", address, BuiltInChains.GNOSIS.id, 1L)
    private val chains = BuiltInChains.ALL
    private val camera = SitePermissionEntry("https://cam.example", SitePermission.CAMERA, PermissionDecision.ALLOW, remembered = true)

    @Test
    fun `a connection names the account, its address and the network`() {
        assertEquals(
            "Account 1 · ${shortAddress(address)} · ${BuiltInChains.GNOSIS.name}",
            connectedSiteSummary(grant, listOf(account), chains),
        )
        assertEquals(
            "Wallet · Account 1 · ${shortAddress(address)} · ${BuiltInChains.GNOSIS.name}",
            dappConnectionDetail(grant, listOf(account), chains),
        )
    }

    @Test
    fun `the account matches whatever the address's case`() {
        assertEquals("Account 1", grantAccount(grant.copy(account = address.lowercase()), listOf(account))?.name)
    }

    @Test
    fun `an account the wallet lost, or a chain it doesn't list, still reads`() {
        assertEquals(
            "$GONE_ACCOUNT · ${shortAddress(address)} · chain 777",
            connectedSiteSummary(grant.copy(chainId = 777), emptyList(), chains),
        )
    }

    @Test
    fun `Site permissions lists connections first, then decisions, and the explainer only when there's neither`() {
        val rows = sitePermissionRows(listOf(camera), listOf(grant), listOf(account), chains)
        assertEquals(listOf(DappConnectionRow("https://app.example"), camera), rows.map { it.key })
        assertEquals(listOf("empty"), sitePermissionRows(emptyList(), emptyList(), emptyList(), chains).map { it.key })
        assertEquals(
            listOf(DappConnectionRow("https://app.example")),
            sitePermissionRows(emptyList(), listOf(grant), listOf(account), chains).map { it.key },
        )
    }

    @Test
    fun `Settings search finds a connection by site, by wallet, by account and by network`() {
        val rows = sitePermissionRows(listOf(camera), listOf(grant), listOf(account), chains)
        val connection = setOf<Any>(DappConnectionRow("https://app.example"))
        assertEquals(connection, visibleSettingsRows("app.example", "Site permissions", rows))
        assertEquals(connection, visibleSettingsRows("wallet", "Site permissions", rows))
        assertEquals(connection, visibleSettingsRows("account 1", "Site permissions", rows))
        assertEquals(connection, visibleSettingsRows("gnosis", "Site permissions", rows))
        assertEquals(setOf<Any>(camera), visibleSettingsRows("camera", "Site permissions", rows))
    }
}
