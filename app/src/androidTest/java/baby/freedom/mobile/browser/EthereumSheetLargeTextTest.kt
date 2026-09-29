package baby.freedom.mobile.browser

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
