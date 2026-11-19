package baby.freedom.mobile.browser

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.ui.FreedomTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Chequebook page's credit cards over mocked ant answers — funded and
 * paying, used up (0.011 xBZZ on chain, nothing left to spend), switched
 * off, and a lost cheque ledger — in both themes. No node, no funds.
 * With the instrumentation argument `chequebookShots=true` each state is
 * also saved as a PNG under the app's external files dir
 * (`files/chequebook-shots/`), for the PR's screenshots.
 */
@RunWith(AndroidJUnit4::class)
class ChequebookCreditCardsTest {
    @get:Rule val rule = createComposeRule()

    private val chequebook = "0x" + "37".repeat(20)
    private fun xbzz(s: String) = java.math.BigDecimal(s).movePointRight(16).toBigIntegerExact()

    private fun swap(enabled: Boolean, paying: Boolean) = SwapStatus(supported = true, swapEnabled = enabled, paying = paying)

    private data class Case(
        val name: String,
        val state: ChequebookState,
        val swap: SwapStatus?,
        val wanted: Boolean?,
        val expect: String,
        val spend: CreditSpendFigures? = null,
    )

    private val funded = ChequebookState(
        address = chequebook,
        balancePlur = xbzz("0.011"),
        walletPlur = xbzz("0.0034"),
        availablePlur = xbzz("0.0082"),
    )

    private val cases = listOf(
        Case("paying", funded, swap(enabled = true, paying = true), true, "Paying peers"),
        // On chain it still reads 0.011: the cheques peers haven't cashed
        // have spent it all.
        Case(
            "empty", funded.copy(availablePlur = xbzz("0.0000004")), swap(enabled = true, paying = true), true,
            "Free tier: the credit is used up. Downloads may be slow; deposit to pay peers again.",
        ),
        Case("off", funded, swap(enabled = false, paying = false), false, "Free tier: paying peers is switched off."),
        Case(
            "lost", funded.copy(availablePlur = xbzz("0.011"), availableUpperBound = true, ledgerLost = true),
            swap(enabled = true, paying = false), true, "Free tier: payments are paused. See below.",
        ),
        // Confirmed since: ant reports an exact-looking figure that leaves
        // out the pre-loss cheques, so the page keeps it an upper bound.
        Case(
            "confirmed", funded.copy(availablePlur = xbzz("0.011")).withConfirmedLedgers(setOf(chequebook)),
            swap(enabled = true, paying = true), true, "Paying peers",
        ),
    )

    private val shots = InstrumentationRegistry.getArguments().getString("chequebookShots") == "true"

    private fun render(
        case: Case,
        dark: Boolean,
        onSwap: (Boolean) -> Unit = {},
        onLost: (String) -> Unit = {},
        fontScale: Float? = null,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
            FreedomTheme(darkTheme = dark) {
                // As the page's scaffold does: the theme's content colour.
                Surface(color = MaterialTheme.colorScheme.background) {
                Box(
                    Modifier
                        .testTag("page")
                        .width(390.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        ChequebookCreditCards(
                            state = case.state,
                            swap = case.swap,
                            spend = case.spend,
                            swapWanted = case.wanted,
                            liabilityOutcome = null,
                            onSwapChange = onSwap,
                            onConfirmLost = onLost,
                        )
                    }
                }
                }
            }
            }
        }
    }

    private fun save(name: String) {
        if (!shots) return
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "chequebook-shots")
        dir.mkdirs()
        val bitmap = rule.onNodeWithTag("page").captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun showsState(case: Case, dark: Boolean) {
        render(case, dark)
        rule.onNodeWithText(case.expect).assertExists()
        // One headline figure; the rest waits under Details (#425, W44).
        rule.onNodeWithText("Spendable credit").assertDoesNotExist()
        rule.onNodeWithText("Details").performClick()
        // The spendable credit sits next to the on-chain figure.
        rule.onNodeWithText("Spendable credit").assertExists()
        rule.onNodeWithText("On-chain balance").assertExists()
        rule.onNodeWithText("0.011 xBZZ").assertExists()
        save("${case.name}-${if (dark) "dark" else "light"}")
        if (case.state.ledgerLost) {
            // The lost card sits below the fold.
            rule.onNodeWithText("Confirm and pay peers again").performScrollTo()
            save("${case.name}-${if (dark) "dark" else "light"}-card")
        }
    }

    @Test fun payingLight() = showsState(cases[0], dark = false)
    @Test fun payingDark() = showsState(cases[0], dark = true)
    @Test fun emptyLight() = showsState(cases[1], dark = false)
    @Test fun emptyDark() = showsState(cases[1], dark = true)
    @Test fun offLight() = showsState(cases[2], dark = false)
    @Test fun offDark() = showsState(cases[2], dark = true)
    @Test fun lostLight() = showsState(cases[3], dark = false)
    @Test fun lostDark() = showsState(cases[3], dark = true)

    @Test fun confirmedLight() = showsState(cases[4], dark = false)

    @Test
    fun aConfirmedLostLedgerStillReadsAsAnUpperBound() {
        render(cases[4], dark = false)
        // The headline says it's an upper bound, and so does the note under Details.
        rule.onNodeWithText("At most 0.011 xBZZ").assertExists()
        rule.onNodeWithText("may run out sooner", substring = true).assertExists()
        rule.onNodeWithText("Payments paused").assertDoesNotExist()
        rule.onNodeWithText("Details").performClick()
        rule.onNodeWithText("you confirmed it", substring = true).assertExists()
    }

    /** At 200% font the credit figures move under their labels whole, never broken mid-number. */
    private fun keepsNumbersWholeAtDoubleFont(case: Case, value: String) {
        render(case, dark = false, fontScale = 2f)
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(value).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        val layout = result.single()
        // No word (the figure, its unit) is split across lines.
        for (w in Regex("\\S+").findAll(value)) {
            assertEquals(
                "'${w.value}' of '$value' stays on one line",
                layout.getLineForOffset(w.range.first), layout.getLineForOffset(w.range.last),
            )
        }
        save("${case.name}-font2")
    }

    @Test fun emptyAtDoubleFont() = keepsNumbersWholeAtDoubleFont(cases[1], "< 0.000001 xBZZ")
    @Test fun confirmedAtDoubleFont() = keepsNumbersWholeAtDoubleFont(cases[4], "At most 0.011 xBZZ")

    // 2026-11-12 12:00 UTC: when the node started counting.
    private val spendCase = cases[0].copy(
        name = "spend",
        spend = CreditSpendFigures(xbzz("0.00042"), xbzz("0.0031"), 1_794_484_800_000L),
    )

    /** #543: what the credit paid peers in the last 24 hours and 7 days, in the card itself, as desktop shows it. */
    private fun showsRecentSpend(dark: Boolean) {
        render(spendCase, dark)
        rule.onNodeWithText("Spent, last 24 hours").assertExists()
        rule.onNodeWithText("0.00042 xBZZ").assertExists()
        rule.onNodeWithText("Spent, last 7 days").assertExists()
        rule.onNodeWithText("0.0031 xBZZ").assertExists()
        rule.onNodeWithText("counted while it runs, since", substring = true).assertExists()
        save("spend-${if (dark) "dark" else "light"}")
    }

    @Test fun recentSpendLight() = showsRecentSpend(dark = false)
    @Test fun recentSpendDark() = showsRecentSpend(dark = true)

    @Test
    fun recentSpendBeforeTheFirstFigures() {
        render(spendCase.copy(spend = CreditSpendFigures(null, null, null)), dark = false)
        rule.onNodeWithText("Spent, last 24 hours").assertExists()
        assertEquals(2, rule.onAllNodesWithText("Not counted yet").fetchSemanticsNodes().size)
        rule.onNodeWithText("within a few minutes", substring = true).assertExists()
    }

    @Test
    fun noRecentSpendWithoutAChequebook() {
        render(spendCase.copy(state = ChequebookState(address = "")), dark = false)
        rule.onNodeWithText("Spent, last 24 hours").assertDoesNotExist()
    }

    @Test fun recentSpendAtDoubleFont() = keepsNumbersWholeAtDoubleFont(spendCase, "0.0031 xBZZ")

    /** #543: the review a top-up in xDAI opens, the same spend sheet as a deposit's. Never confirmed. */
    @Test
    fun theTopUpReviewNamesTheSwapCapTheAmountAndTheGas() {
        val review = depositReview(
            funded.copy(walletPlur = xbzz("0.0034"), walletXdaiWei = java.math.BigInteger("1250000000000000000")),
            xbzz("0.1"),
        )!!
        var confirmed = false
        var dismissed = false
        rule.setContent {
            FreedomTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.testTag("page").width(390.dp).height(760.dp)) {
                        SpendConfirmDialog(
                            title = "Deposit ${formatBzzExact(review.amountPlur)}?",
                            body = depositConfirmText(review),
                            confirmLabel = "Deposit",
                            onConfirm = { confirmed = true },
                            onDismiss = { dismissed = true },
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Deposit 0.1 xBZZ?").assertExists()
        rule.onNodeWithText("spending at most 1.25 xDAI", substring = true).assertExists()
        rule.onNodeWithText("moves 0.1 xBZZ into its chequebook at $chequebook", substring = true).assertExists()
        rule.onNodeWithText("up to 0.02 xDAI of gas for the two transactions", substring = true).assertExists()
        if (shots) {
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "chequebook-shots")
            dir.mkdirs()
            val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(dir, "top-up-review.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Cancel, never confirm (the spend guard opens no permit here anyway).
        // The sheet's buttons arm after its tap-protection delay (real time).
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("Cancel").assertIsEnabled() }.isSuccess }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(false, confirmed)
        assertEquals(true, dismissed)
    }

    @Test
    fun theSwitchAsksForTheOtherValue() {
        val asked = mutableListOf<Boolean>()
        render(cases[0], dark = false, onSwap = { asked += it })
        rule.onNodeWithText("Pay peers from the chequebook").performClick()
        assertEquals(listOf(false), asked)
    }

    @Test
    fun aLostLedgerOffersItsConfirmationForThisChequebook() {
        val asked = mutableListOf<String>()
        render(cases[3], dark = false, onLost = { asked += it })
        rule.onNodeWithText("At most 0.011 xBZZ").assertExists()
        rule.onNodeWithText("Confirm and pay peers again").performScrollTo().performClick()
        assertEquals(listOf(chequebook), asked)
    }

    @Test
    fun noLostCardWithoutALoss() {
        render(cases[0], dark = false)
        rule.onNodeWithText("Payments paused").assertDoesNotExist()
    }
}
