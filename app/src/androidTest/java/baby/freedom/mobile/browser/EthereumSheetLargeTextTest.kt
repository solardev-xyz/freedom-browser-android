package baby.freedom.mobile.browser

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.chains.ChainInput
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.WalletAccount
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #215 R2-M2: a site's 900k-character `personal_sign` froze the UI for
 * seconds while the sheet laid the whole text out. Only the start is laid
 * out now, with a note saying how much more there is.
 */
@RunWith(AndroidJUnit4::class)
class EthereumSheetLargeTextTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aHugeMessageShowsItsStartAndSaysHowMuchMoreThereIs() {
        val account = WalletAccount(0, "Account 1", "0x" + "ab".repeat(20))
        val text = "Sign in to example.com. " + "x".repeat(900_000)
        val request = EthereumPromptRequest(EthAsk.SignMessage("https://example.com", account, text, "0x"), setUpWallet = {})
        val started = SystemClock.uptimeMillis()
        rule.setContent { FreedomTheme { EthereumApprovalSheet(request) } }
        rule.waitForIdle()
        rule.onNodeWithText("more characters aren't shown", substring = true).assertExists()
        val took = SystemClock.uptimeMillis() - started
        assertTrue("sheet took ${took}ms to show", took < 2_000)
        rule.onNodeWithText("Sign in to example.com.", substring = true).assertExists()
    }

    /** #215 R3-M1: typed data whose domain names no chain says so, rather than naming the site's. */
    @Test
    fun typedDataTiedToNoChainSaysSo() {
        val account = WalletAccount(0, "Account 1", "0x" + "ab".repeat(20))
        fun ask(bound: Boolean) = EthAsk.SignTypedData(
            "https://example.com", account, baby.freedom.mobile.chains.BuiltInChains.GNOSIS, bound, "Ether Mail", null, "Mail", "{}",
        )
        var request by androidx.compose.runtime.mutableStateOf(EthereumPromptRequest(ask(false), setUpWallet = {}))
        rule.setContent { FreedomTheme { EthereumApprovalSheet(request) } }
        rule.onNodeWithText("Any — the signature names no chain").assertExists()
        rule.onNodeWithText("Chain ID 100").assertDoesNotExist()
        request = EthereumPromptRequest(ask(true), setUpWallet = {})
        rule.onNodeWithText("Gnosis Chain").assertExists()
        rule.onNodeWithText("Chain ID 100").assertExists()
    }

    /**
     * #370 R3-F1: a site's chain named "Ethereum (chain 1) Neeee…t" wraps on
     * the Switch sheet. The real ID is never part of the name's text: it's
     * its own line, right under the whole name.
     */
    @Test
    fun theSwitchSheetShowsTheChainIdOnItsOwnLineUnderTheName() {
        val name = "Ethereum (chain 1) N" + "e".repeat(40) + "t"
        val spoof = ChainInput.build("666", name, "ETH", "18", "", listOf("https://rpc.example.org"))!!
        val request = EthereumPromptRequest(
            EthAsk.SwitchChain("https://example.com", baby.freedom.mobile.chains.BuiltInChains.GNOSIS, spoof), setUpWallet = {},
        )
        rule.setContent { FreedomTheme { EthereumApprovalSheet(request) } }
        val shown = rule.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val id = rule.onNodeWithText("Chain ID 666").fetchSemanticsNode().boundsInRoot
        assertTrue("ID $id not right under the name $shown", id.top >= shown.bottom && id.top - shown.bottom < 24)
        rule.onNodeWithText("Chain ID 100").assertExists()
    }

    /** #239: typed data a Ledger can only sign by its hashes says so, with the hashes, before the user approves. */
    @Test
    fun typedDataALedgerCanOnlySignByItsHashesSaysSoWithTheHashes() {
        val ledger = WalletAccount(-1, "Ledger", "0x" + "cd".repeat(20), baby.freedom.mobile.wallet.ledger.LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Nano X"))
        val hashes = baby.freedom.mobile.wallet.ledger.LedgerTypedDataHashes(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })
        fun ask(h: baby.freedom.mobile.wallet.ledger.LedgerTypedDataHashes?) = EthAsk.SignTypedData(
            "https://example.com", ledger, baby.freedom.mobile.chains.BuiltInChains.GNOSIS, true, "Shop", null, "Batch", "{\n  \"ids\": [\n    7,\n    7,\n    7\n  ]\n}",
            ledgerHashes = h,
        )
        var request by androidx.compose.runtime.mutableStateOf(EthereumPromptRequest(ask(hashes), setUpWallet = {}))
        rule.setContent { FreedomTheme { EthereumApprovalSheet(request) } }
        rule.onNodeWithText("Your Ledger can’t show this data field by field", substring = true).assertExists()
        rule.onNodeWithText("0x" + "11".repeat(32)).assertExists()
        rule.onNodeWithText("0x" + "22".repeat(32)).assertExists()
        rule.onNodeWithText("where it shows only the two hashes above", substring = true).assertExists()
        // Data the Ledger shows field by field: no warning, the usual note.
        request = EthereumPromptRequest(ask(null), setUpWallet = {})
        rule.onNodeWithText("Your Ledger can’t show this data field by field", substring = true).assertDoesNotExist()
        rule.onNodeWithText("You’ll check and confirm this on your Ledger (Nano X) next.").assertExists()
    }
}
