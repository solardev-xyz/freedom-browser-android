package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.X402
import baby.freedom.mobile.wallet.ledger.LedgerKey
import java.math.BigInteger
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #218 M0 (the merge with #142): an x402 sheet for a Ledger account names
 * it as one, says the payment is confirmed on the Ledger, and offers no
 * allowance; a seed account's sheet still offers one.
 */
@RunWith(AndroidJUnit4::class)
class X402LedgerSheetTest {
    @get:Rule val rule = createComposeRule()

    private fun ask(account: WalletAccount, timeout: Int = 60): X402Ask {
        val offer = JSONObject().put("scheme", "exact").put("network", "eip155:8453").put("amount", "10000")
            .put("asset", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913")
            .put("payTo", "0x209693Bc6afc0C5328bA36FaF03C514EF312287C").put("maxTimeoutSeconds", timeout)
            .put("extra", JSONObject().put("name", "USD Coin").put("version", "2"))
        val json = JSONObject().put("x402Version", 2).put("accepts", JSONArray().put(offer))
        val offers = X402.parseRequired(Base64.getEncoder().encodeToString(json.toString().toByteArray()))!!.offers
        return X402Ask(
            url = "https://api.example/paid",
            description = "One article",
            account = account,
            options = offers.map { X402Option(it, BuiltInChains.BASE, "USDC", 6, listed = true, balance = BigInteger.valueOf(1_000_000)) },
            unusable = emptyList(),
            allowanceWaitingOnUnlock = false,
        )
    }

    private fun show(account: WalletAccount, timeout: Int = 60) {
        val a = ask(account, timeout)
        rule.setContent {
            FreedomTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                        X402PaymentBody(a, X402SheetState(a), account, noWallet = false, locked = false, onSetUp = {})
                    }
                }
            }
        }
    }

    @Test
    fun aLedgerAccountIsConfirmedOnTheLedgerAndGrantsNoAllowance() {
        show(
            WalletAccount(
                2, "Account 3", "0x3333333333333333333333333333333333333333",
                LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Ledger Nano X"),
            ),
        )
        rule.onNodeWithText("Account 3 · Ledger").assertExists()
        rule.onNodeWithText("you confirm each payment on the Ledger", substring = true).assertExists()
        rule.onNodeWithTag("x402-auto").assertDoesNotExist()
    }

    private val ledgerAccount = WalletAccount(
        2, "Account 3", "0x3333333333333333333333333333333333333333",
        LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Ledger Nano X"),
    )

    @Test
    fun aLedgerSheetSaysHowLongTheSiteAllowsToConfirm() {
        // #218 R1-F1: 60 s less the 20 s runway leaves 40 s for the review on the device.
        show(ledgerAccount, timeout = 60)
        rule.onNodeWithText("The site allows only 40 s to confirm once the Ledger shows the payment", substring = true).assertExists()
    }

    @Test
    fun aLedgerSheetWithTimeToReviewHasNoWarning() {
        show(ledgerAccount, timeout = 300)
        rule.onNodeWithText("to confirm once the Ledger shows the payment", substring = true).assertDoesNotExist()
    }

    @Test
    fun aSeedAccountStillOffersAnAllowance() {
        show(WalletAccount(0, "Account 1", "0x1111111111111111111111111111111111111111"), timeout = 30)
        rule.onNodeWithTag("x402-auto").assertExists()
        rule.onNodeWithText("you confirm each payment on the Ledger", substring = true).assertDoesNotExist()
        rule.onNodeWithText("to confirm once the Ledger shows the payment", substring = true).assertDoesNotExist()
    }
}
