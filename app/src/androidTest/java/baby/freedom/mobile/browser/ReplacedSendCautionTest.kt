package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.OpenLvSession
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #434 R2-F1: a quote that takes the nonce of a send the user stopped
 * tracking says so on the surface of the remote-signing sheet and the Safe
 * review — not only under the collapsed Details — as the site's sheet does.
 */
@RunWith(AndroidJUnit4::class)
class ReplacedSendCautionTest {
    @get:Rule val rule = createComposeRule()

    private fun quote(
        replaces: String?,
        chain: baby.freedom.mobile.chains.Chain = BuiltInChains.GNOSIS,
        to: String = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
        data: ByteArray = ByteArray(0),
    ): SendQuote {
        val account = WalletAccount(0, "Account 1", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
        val value = if (data.isEmpty()) BigInteger.TEN.pow(18) else BigInteger.ZERO
        val request = SendRequest(chain, TokenRegistry.native(chain), account, to, value, if (data.isEmpty()) null else baby.freedom.mobile.wallet.DappCall(null, data, null))
        val tx = EthTransaction(
            chainId = chain.id,
            nonce = BigInteger.valueOf(7),
            gasLimit = BigInteger.valueOf(21_000),
            to = to,
            value = value,
            data = data,
            fees = EthTransaction.Fees.Eip1559(BigInteger.valueOf(2_000_000_000), BigInteger.ONE),
        )
        val trust = ChainTrust(ChainTrust.Level.VERIFIED, ChainSource.QUORUM, emptyList(), emptyList(), emptyList(), 3, 2, null)
        return SendQuote(request, tx, BigInteger.TEN.pow(19), null, 0, trust, replaces)
    }

    private val stopped = "0x" + "ab".repeat(32)

    @Test
    fun theRemoteSigningSheetShowsTheReplacedSendOnItsSurface() {
        rule.setContent {
            FreedomTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) { SendTransactionBody(OpenLvSession.Request.SendTransaction(quote(stopped))) } } }
        }
        rule.onNodeWithTag("warning-replaces").assertIsDisplayed()
    }

    @Test
    fun theSafeReviewShowsTheReplacedSendOnItsSurface() {
        rule.setContent {
            FreedomTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SafeCallReview(quote(stopped), "Activate this Safe on Gnosis", "Deploys the Safe", false, null, null, {}, {}, {})
                    }
                }
            }
        }
        rule.onNodeWithTag("warning-replaces").assertIsDisplayed()
    }

    /**
     * #434 R3-F1, R3-M1: an unlimited approve from desktop names its network
     * above the fold (the decoded headline doesn't) and carries the same
     * Danger the site's sheet gives it.
     */
    @Test
    fun theRemoteSigningSheetNamesTheNetworkAndAnUnlimitedApprovesDanger() {
        val usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        val spender = "1111111254EEB25477B68fb85Ed929f73A960582".lowercase().padStart(64, '0')
        val approve = ("095ea7b3" + spender + "f".repeat(64)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        rule.setContent {
            FreedomTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SendTransactionBody(OpenLvSession.Request.SendTransaction(quote(null, BuiltInChains.ETHEREUM, usdc, approve)))
                    }
                }
            }
        }
        rule.onNodeWithText("Allow 0x1111…0582 to spend UNLIMITED USDC").assertIsDisplayed()
        rule.onNodeWithText(BuiltInChains.ETHEREUM.name).assertIsDisplayed()
        rule.onNodeWithTag("warning-approve-unlimited").assertIsDisplayed()
    }

    @Test
    fun aQuoteThatReplacesNothingHasNoCaution() {
        rule.setContent {
            FreedomTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) { SendTransactionBody(OpenLvSession.Request.SendTransaction(quote(null))) } } }
        }
        rule.onNodeWithTag("warning-replaces").assertDoesNotExist()
    }
}
