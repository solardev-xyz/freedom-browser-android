package baby.freedom.mobile.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #234: the send sheet's "Always approve" switch warns, in red, for a
 * function the wallet can't name, says nothing extra for a transfer, and
 * isn't there at all for an approval.
 */
@RunWith(AndroidJUnit4::class)
class AutoApproveWarningSheetTest {
    @get:Rule val rule = createComposeRule()

    private val site = "https://app.example"
    private val token = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

    private fun ask(selector: String): EthAsk.SendTransaction {
        val chain = BuiltInChains.GNOSIS
        val account = WalletAccount(0, "Account 1", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
        val data = (selector.removePrefix("0x") + "00".repeat(64)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val request = SendRequest(chain, TokenRegistry.native(chain), account, token, BigInteger.ZERO, DappCall(site, data, null))
        val tx = EthTransaction(
            chainId = chain.id,
            nonce = BigInteger.valueOf(7),
            gasLimit = BigInteger.valueOf(50_000),
            to = token,
            value = BigInteger.ZERO,
            data = data,
            fees = EthTransaction.Fees.Eip1559(BigInteger.valueOf(2_000_000_000), BigInteger.ONE),
        )
        val trust = ChainTrust(ChainTrust.Level.VERIFIED, ChainSource.QUORUM, emptyList(), emptyList(), emptyList(), 3, 2, null)
        val quote = SendQuote(request, tx, BigInteger.TEN.pow(18), null, 0, trust)
        return EthAsk.SendTransaction(site, quote, false, AutoApproveRule.eligible(site, token, BigInteger.ZERO, data, chain.id))
    }

    @Test
    fun anUnknownFunctionsSwitchWarnsInRed() {
        rule.setContent { FreedomTheme { EthereumApprovalSheet(EthereumPromptRequest(ask("0x38ed1739"), setUpWallet = {})) } }
        rule.onNodeWithTag("ethereum-always-approve-warning").performScrollTo().assertExists()
    }

    @Test
    fun aTransfersSwitchHasNoWarning() {
        rule.setContent { FreedomTheme { EthereumApprovalSheet(EthereumPromptRequest(ask("0xa9059cbb"), setUpWallet = {})) } }
        rule.onNodeWithTag("ethereum-always-approve").performScrollTo().assertExists()
        rule.onNodeWithTag("ethereum-always-approve-warning").assertDoesNotExist()
    }

    @Test
    fun anApprovesSheetHasNoSwitch() {
        rule.setContent { FreedomTheme { EthereumApprovalSheet(EthereumPromptRequest(ask("0x095ea7b3"), setUpWallet = {})) } }
        rule.onNodeWithTag("ethereum-approval").assertExists()
        rule.onNodeWithTag("ethereum-always-approve").assertDoesNotExist()
    }
}
