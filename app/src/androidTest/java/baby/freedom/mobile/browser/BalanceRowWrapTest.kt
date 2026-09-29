package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #208 R1-F1: a balance never soft-wraps between two digits, at any font
 * scale — a wrapped `60,562.1027` / `99` reads as a smaller amount. It
 * fits on one line (beside the symbol, or on its own line); only a number
 * too long even for that breaks, and then only after a separator. Which
 * amounts are "too long" depends on the screen width, so the test checks
 * each wrap against the row's actual width rather than a fixed length
 * (#208 R2-M1).
 */
@RunWith(AndroidJUnit4::class)
class BalanceRowWrapTest {
    @get:Rule val rule = createComposeRule()

    private val trust = ChainTrust(
        ChainTrust.Level.UNVERIFIED, ChainSource.entries.last(), listOf("a"), emptyList(), listOf("a"), 1, 1, null,
    )

    @Test
    fun amountsNeverBreakBetweenDigits() {
        val gnosis = BuiltInChains.GNOSIS
        val (native, xbzz, eure) = TokenRegistry.tokens(gnosis)
        // 60,562.102799 xBZZ (16 decimals), 1,234,567.123456 xDAI, and an absurd EURe amount.
        val balances = mapOf(
            xbzz.key to TokenBalance.Known(BigInteger("605621027990000000000"), trust),
            native.key to TokenBalance.Known(BigInteger("1234567123456000000000000"), trust),
            eure.key to TokenBalance.Known(BigInteger("123456789012345678901234567890123456789012"), trust),
        )
        val amounts = listOf(
            balanceText(balances[xbzz.key], xbzz.decimals, false).amount!!,
            balanceText(balances[native.key], native.decimals, false).amount!!,
            balanceText(balances[eure.key], eure.decimals, false).amount!!,
        )
        var scale by mutableFloatStateOf(1f)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                FreedomTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        BalancesSection(listOf(gnosis), balances, refreshing = false, onRefresh = {})
                    }
                }
            }
        }
        for (s in listOf(1f, 1.3f, 1.5f, 2f)) {
            scale = s
            rule.waitForIdle()
            for (amount in amounts) {
                val nodes = rule.onAllNodesWithText(amount).fetchSemanticsNodes() +
                    rule.onAllNodesWithText(amountBreaks(amount)).fetchSemanticsNodes()
                assertEquals("$amount at $s", 1, nodes.size)
                val layouts = mutableListOf<TextLayoutResult>()
                nodes[0].config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val layout = layouts.single()
                // Every line's ink fits the node (hasVisualOverflow misfires on a one-line
                // softWrap=false text, whose paragraph is laid out at the full max width).
                assertFalse("$amount cut off at $s", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    val w = layout.multiParagraph.getLineWidth(line)
                    assertTrue("$amount line $line clipped at $s: $w > ${layout.size.width}", w <= layout.size.width + 0.5f)
                }
                val text = layout.layoutInput.text.text
                for (line in 0 until layout.lineCount - 1) {
                    val end = text.substring(0, layout.getLineEnd(line)).trimEnd('​')
                    assertTrue("$amount broke mid-number at $s: '$end'", end.last() == ',' || end.last() == '.')
                }
                // Breaking is the last resort, whatever the screen width: a wrapped amount
                // must not have fit the row on one line even shrunk as far as the row
                // shrinks it. fittedAddressSize steps down by 0.01 to MIN_ADDRESS_SCALE, so
                // it always tries some scale below MIN_ADDRESS_SCALE + 0.01.
                if (layout.lineCount > 1) {
                    val input = layout.layoutInput
                    val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
                    val shrunk = measurer.measure(
                        amount,
                        input.style.copy(fontSize = input.style.fontSize * (MIN_ADDRESS_SCALE + 0.01f)),
                        softWrap = false,
                        maxLines = 1,
                    ).size.width
                    assertTrue(
                        "$amount wrapped at $s though it fits one line: $shrunk <= ${layout.size.width}",
                        shrunk > layout.size.width,
                    )
                }
            }
        }
    }
}
