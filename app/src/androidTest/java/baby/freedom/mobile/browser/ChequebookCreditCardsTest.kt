package baby.freedom.mobile.browser

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
