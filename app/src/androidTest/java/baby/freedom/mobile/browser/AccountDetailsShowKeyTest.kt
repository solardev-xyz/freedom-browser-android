package baby.freedom.mobile.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.WalletAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #334 R1-M2: Show private key — Export private key… on the account's
 * details page since W5 — is gated on `busy` like its sibling account
 * actions (Remove, switching, Rename), so it can't open while an account
 * operation is still pending.
 */
@RunWith(AndroidJUnit4::class)
class AccountDetailsShowKeyTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun showKeyFollowsBusy() {
        val account = WalletAccount(0, "Account 1", "0x000000000000000000000000000000000000dEaD")
        var busy by mutableStateOf(true)
        rule.setContent {
            FreedomTheme {
                AccountDetailsPage(
                    account = account,
                    active = true,
                    busy = busy,
                    error = null,
                    onRename = {},
                    onUse = {},
                    onReceive = {},
                    onExportKey = {},
                    onRemoveLedger = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("wallet-show-key").assertIsNotEnabled()
        busy = false
        rule.onNodeWithTag("wallet-show-key").assertIsEnabled()
    }
}
