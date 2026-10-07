package baby.freedom.mobile.browser

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #317 R2-M1: a payment link's Send page hidden while a feature's request
 * has the wallet home comes back with what the user changed, not rebuilt
 * from the link.
 */
@RunWith(AndroidJUnit4::class)
class SendLinkDraftTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun editsOutliveThePageLeavingComposition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = Vault.get(context)
        val account = WalletAccount(0, "Account 1", "0x1111111111111111111111111111111111111111")
        val payee = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C"
        // 100 xDAI.
        val prefill = SendPrefill("https://shop.example", "100:native", payee, BigInteger("100000000000000000000"))
        var shown by mutableStateOf(true)
        rule.setContent {
            FreedomTheme {
                Surface {
                    val draft = remember(prefill) { SendDraft() }
                    if (shown) {
                        SendPage(
                            account = account,
                            chains = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS),
                            balances = emptyMap(),
                            vault = vault,
                            auth = { cipher, _ -> cipher },
                            phraseBackedUp = true,
                            onOpenUrl = {},
                            onBack = {},
                            prefill = prefill,
                            draft = draft,
                        )
                    }
                }
            }
        }
        // The amount field is further down the page's list.
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("100"))
        rule.onNodeWithText("100").performTextReplacement("10")
        rule.onNodeWithText("10").assertExists()
        // A request takes the wallet home, then is handled.
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        // The field and, under it, the address in full (#422).
        rule.onAllNodesWithText(payee).onFirst().assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("10"))
        rule.onNodeWithText("10").assertExists()
        rule.onNodeWithText("100").assertDoesNotExist()
    }
}
