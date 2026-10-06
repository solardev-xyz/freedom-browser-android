package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #422 R1: what the To field's Scan and Paste fill in, and what the page
 * says about it — a request naming no network says which one was assumed,
 * a pasted request fills in its asset as a scanned one does, and a
 * recovery phrase on the clipboard is never pasted.
 */
@RunWith(AndroidJUnit4::class)
class SendFillTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = WalletAccount(0, "Account 1", "0x1111111111111111111111111111111111111111")
    private val payee = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"

    private fun show(prefill: SendPrefill?, scanned: Boolean) {
        rule.setContent {
            FreedomTheme {
                Surface {
                    SendPage(
                        account = account,
                        chains = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS),
                        balances = emptyMap(),
                        vault = Vault.get(context),
                        auth = { cipher, _ -> cipher },
                        phraseBackedUp = true,
                        onOpenUrl = {},
                        onBack = {},
                        prefill = prefill,
                        scanned = scanned,
                    )
                }
            }
        }
    }

    private fun clip(clip: ClipData) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
    }

    @Test
    fun scannedRequestNamingNoNetworkSaysEthereumWasAssumed() {
        val read = scannedRecipient("ethereum:$payee?value=1e15") as ScannedRecipient.Fill
        show(read.sendPrefill(), scanned = true)
        rule.onNodeWithText("Filled in from the code you scanned", substring = true).assertExists()
        rule.onNodeWithTag("send-chain-guess").assertExists()
        rule.onNodeWithText("The request doesn’t name a network, so Ethereum is filled in", substring = true).assertExists()
        rule.onNodeWithText("ETH · Ethereum").assertExists()
    }

    @Test
    fun pastedRequestFillsItsAssetAndDropsAnAmountTypedForAnother() {
        // 100 xDAI typed (here: from a link), then a USDC request with no amount pasted.
        show(SendPrefill(null, "100:native", "0x209693Bc6afc0C5328bA36FaF03C514EF312287C", BigInteger("100000000000000000000")), scanned = false)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("100"))
        rule.onNodeWithText("100").assertExists()
        clip(ClipData.newPlainText("URL", "ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48@1/transfer?address=$payee"))
        rule.onNodeWithContentDescription("Paste").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Filled in from the payment request you pasted", substring = true).assertExists()
        rule.onAllNodesWithText(payee).onFirst().assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("USDC · Ethereum"))
        rule.onNodeWithText("USDC · Ethereum").assertExists()
        rule.onNodeWithText("100").assertDoesNotExist()
    }

    @Test
    fun aPlainAddressPastedOverARequestKeepsItsAssumedNetworkLine() {
        // R2-M1: the request's ETH and 0.001 stay, so the warning about them does too.
        val read = scannedRecipient("ethereum:0x209693Bc6afc0C5328bA36FaF03C514EF312287C?value=1e15") as ScannedRecipient.Fill
        show(read.sendPrefill(), scanned = true)
        clip(ClipData.newPlainText("address", payee))
        rule.onNodeWithContentDescription("Paste").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(payee).onFirst().assertExists()
        rule.onNodeWithText("The payee is no longer the one in the code you scanned", substring = true).assertExists()
        rule.onNodeWithText("Filled in from the code you scanned", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("send-chain-guess").assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("0.001"))
        rule.onNodeWithText("0.001").assertExists()
    }

    @Test
    fun typingAnotherPayeeOverAPastedRequestSaysSo() {
        // R2-M2: the note no longer says the whole form came from the request.
        show(null, scanned = false)
        clip(ClipData.newPlainText("URL", "ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48@1/transfer?address=$payee&uint256=2e6"))
        rule.onNodeWithContentDescription("Paste").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Filled in from the payment request you pasted", substring = true).assertExists()
        rule.onNode(hasSetTextAction() and hasText(payee)).performTextReplacement("0x209693Bc6afc0C5328bA36FaF03C514EF312287C")
        rule.waitForIdle()
        rule.onNodeWithText("Filled in from the payment request you pasted", substring = true).assertDoesNotExist()
        rule.onNodeWithText("The payee is no longer the one in the payment request you pasted", substring = true).assertExists()
    }

    @Test
    fun aRecoveryPhraseOnTheClipboardIsNeverPasted() {
        show(null, scanned = false)
        val words = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        clip(ClipData.newPlainText(PhraseClipboard.CLIP_LABEL, words))
        rule.onNodeWithContentDescription("Paste").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("isn’t pasted here", substring = true).assertExists()
        rule.onNodeWithText(words).assertDoesNotExist()
        assertEquals(0, rule.onAllNodesWithText("abandon", substring = true).fetchSemanticsNodes().size)
    }
}
