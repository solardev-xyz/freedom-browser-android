package baby.freedom.mobile.browser

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.OpenLvSession
import baby.freedom.mobile.wallet.WalletAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #287 R3-M1: a standing-grant switch on an approval sheet — the
 * wallet's "Always approve … on this contract" and Swarm's "always allow"
 * — is guarded like the sheet's confirm button. Once the sheet has armed,
 * a tap Android marks as having passed through another app's window
 * (what an overlay steering the tap onto the switch looks like on
 * Android 11, where other apps' overlays can't be hidden) doesn't turn
 * the switch on, and the sheet says why; a clean tap does. Taps are
 * injected through the real input pipeline on real clocks, like
 * [ApprovalTapjackDeviceTest].
 *
 * #287 R4-M1: the same holds for the account picker on both Connect
 * sheets (a site's and a scanned code's): neither a tap before the sheet
 * armed nor an obscured one after changes which account is shared; a
 * clean one does.
 *
 * #287 R5-M1, #419: the site-permission prompt's "Allow every visit",
 * which stores a standing grant: neither an obscured tap nor one before
 * the prompt armed answers the prompt; a clean armed tap does, and
 * "Allow while visiting" is not remembered.
 */
@RunWith(AndroidJUnit4::class)
class StandingGrantTapjackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    private val rule = AutoApproveRule(
        "https://game.example", "0x2222222222222222222222222222222222222222", "0xa9059cbb", 100,
    )

    @Composable
    private fun ethereumSwitch() {
        val tap = rememberArmedTapGuard(Unit)
        var on by remember { mutableStateOf(false) }
        Column(Modifier.padding(16.dp)) {
            AutoApproveSwitch(rule, "Gnosis", on, tap, enabled = true) { on = it }
            ObscuredTapNotice(tap)
        }
    }

    /** [confirm]: the label of the button on the confirm step a clean tap opens first (#423), if any. */
    private fun showing(content: @Composable () -> Unit, label: String, confirm: String? = null) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FreedomTheme { content() } } }
            findToggle(label)
            // Well past arming (SwarmPrompt's publish ask arms at the ordinary delay; 2 s covers both).
            SystemClock.sleep(2_000)
            tap(findToggle(label), MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            SystemClock.sleep(500)
            assertFalse("an obscured tap turned the switch on", checked(label))
            assertTrue(onScreen(OBSCURED_TAP_MESSAGE))
            // The notice grows a bottom sheet upwards: find the switch again where it is now.
            tap(findToggle(label))
            if (confirm != null) {
                // Only the confirm turns it on (#423, W25); it arms like any prompt.
                SystemClock.sleep(500)
                assertFalse("the row turned the switch on without its confirm", checked(label))
                val button = findText(confirm)
                SystemClock.sleep(1_500)
                tap(button)
            }
            assertTrue("a clean tap didn't turn the switch on", waitChecked(label))
        }
    }

    @Test
    fun anObscuredTapDoesNotTurnOnAlwaysApprove() =
        showing({ ethereumSwitch() }, autoApproveSwitchLabel(rule), confirm = InstrumentationRegistry.getInstrumentation().targetContext.getString(baby.freedom.mobile.R.string.send_eth_always_turn_on))

    @Test
    fun anObscuredTapDoesNotTurnOnSwarmAlwaysAllow() {
        val ask = SwarmAsk.Publish("https://game.example", SwarmAsk.Publish.Kind.Data, 10, "text/plain", null, emptyList())
        val label = swarmPromptCopy(ask).always!!
        showing({ SwarmPromptSheet(SwarmPromptRequest(ask)) }, label)
    }

    private val first = WalletAccount(0, "Alpha", "0x1111111111111111111111111111111111111111")
    private val second = WalletAccount(1, "Bravo", "0x2222222222222222222222222222222222222222")

    @Composable
    private fun ethereumPicker(protectionMs: Long) {
        val tap = rememberArmedTapGuard(Unit, protectionMs)
        var picked by remember { mutableStateOf(first) }
        Column(Modifier.padding(16.dp)) {
            ConnectBody(
                EthAsk.Connect("https://game.example", BuiltInChains.GNOSIS), listOf(first, second), picked,
                noWallet = false, tap = tap, enabled = true, onPick = { picked = it }, onSetUp = {},
            )
            ObscuredTapNotice(tap)
        }
    }

    @Composable
    private fun remotePicker(protectionMs: Long) {
        val tap = rememberArmedTapGuard(Unit, protectionMs)
        var picked by remember { mutableStateOf(first) }
        Column(Modifier.padding(16.dp)) {
            ConnectBody(OpenLvSession.Request.Connect(listOf(first, second), first), picked, tap, enabled = true) { picked = it }
            ObscuredTapNotice(tap)
        }
    }

    private fun picking(content: @Composable () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FreedomTheme { content() } } }
            findRow(second.name)
            SystemClock.sleep(2_000)
            // Found again once laid out and armed: an address line can still settle after the first frame.
            tap(findRow(second.name), MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            SystemClock.sleep(500)
            assertFalse("an obscured tap picked the other account", rowChecked(second.name))
            assertTrue(rowChecked(first.name))
            assertTrue(onScreen(OBSCURED_TAP_MESSAGE))
            tap(findRow(second.name))
            assertTrue("a clean tap didn't pick the other account", waitRowChecked(second.name))
            assertFalse(rowChecked(first.name))
        }
    }

    /** A picker that hasn't armed (a minute's protection): a clean tap on another account changes nothing. */
    private fun pickingEarly(content: @Composable () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FreedomTheme { content() } } }
            tap(findRow(second.name))
            SystemClock.sleep(500)
            assertFalse("a tap before the sheet armed picked the other account", rowChecked(second.name))
            assertTrue(rowChecked(first.name))
        }
    }

    @Test
    fun anObscuredTapDoesNotChangeTheSitesConnectAccount() = picking { ethereumPicker(PromptTapGuard.PROTECTION_MS) }

    @Test
    fun anObscuredTapDoesNotChangeTheScannedCodesConnectAccount() = picking { remotePicker(PromptTapGuard.PROTECTION_MS) }

    @Test
    fun aTapBeforeArmingDoesNotChangeTheSitesConnectAccount() = pickingEarly { ethereumPicker(60_000) }

    @Test
    fun aTapBeforeArmingDoesNotChangeTheScannedCodesConnectAccount() = pickingEarly { remotePicker(60_000) }

    private val cameraPrompt get() = PermissionPrompt("https://game.example", listOf(SitePermission.CAMERA))

    @Test
    fun anObscuredTapDoesNotAllowEveryVisitOnASitePermissionPrompt() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val prompt = cameraPrompt
            scenario.onActivity { it.setContent { FreedomTheme { SitePermissionPrompt(prompt) } } }
            val button = findStableText(ALLOW_EVERY_VISIT)
            SystemClock.sleep(2_000)
            tap(button, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            SystemClock.sleep(500)
            assertFalse("an obscured tap answered the prompt", prompt.answer.isCompleted)
            assertTrue(onScreen(OBSCURED_TAP_MESSAGE))
            tap(findStableText(ALLOW_EVERY_VISIT))
            assertEquals(PromptAnswer.Allow(remember = true), awaitAnswer(prompt))
        }
    }

    @Test
    fun aTapBeforeArmingDoesNotAllowEveryVisitOnASitePermissionPrompt() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val prompt = cameraPrompt
            scenario.onActivity { it.setContent { FreedomTheme { SitePermissionPrompt(prompt) } } }
            // Tapped as soon as the button is laid out: inside the prompt's 500 ms guard.
            tap(findText(ALLOW_EVERY_VISIT))
            SystemClock.sleep(300)
            assertFalse("a tap before the prompt armed answered it", prompt.answer.isCompleted)
        }
    }

    @Test
    fun allowWhileVisitingIsNotRememberedOnASitePermissionPrompt() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val prompt = cameraPrompt
            scenario.onActivity { it.setContent { FreedomTheme { SitePermissionPrompt(prompt) } } }
            val button = findStableText(ALLOW_WHILE_VISITING)
            SystemClock.sleep(1_000)
            tap(button)
            assertEquals(PromptAnswer.Allow(remember = false), awaitAnswer(prompt))
        }
    }

    private fun awaitAnswer(prompt: PermissionPrompt): PromptAnswer? {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            if (prompt.answer.isCompleted) return runBlocking { prompt.answer.await() }
            SystemClock.sleep(50)
        }
        return null
    }

    /** Where the text [label] is once it has stopped moving (the dialog can still be animating in). */
    private fun findStableText(label: String): Rect {
        val until = SystemClock.uptimeMillis() + 8_000
        var last: Rect? = null
        while (SystemClock.uptimeMillis() < until) {
            val r = roots().firstNotNullOfOrNull { find(it, label) }
                ?.let { node -> Rect().also { node.getBoundsInScreen(it) } }?.takeUnless { it.isEmpty }
            if (r != null && r == last) return r
            last = r
            SystemClock.sleep(300)
        }
        throw AssertionError("no \"$label\" on screen")
    }

    /** The radio row whose (merged) text starts with [name]. */
    private fun row(name: String): AccessibilityNodeInfo? =
        roots().firstNotNullOfOrNull { findRowIn(it, name) }

    private fun findRowIn(node: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        if (node.isCheckable && node.text?.toString()?.startsWith(name) == true) return node
        if (node.text?.toString() == name) node.parent?.takeIf { it.isCheckable }?.let { return it }
        return (0 until node.childCount).firstNotNullOfOrNull { i -> node.getChild(i)?.let { findRowIn(it, name) } }
    }

    private fun findRow(name: String): Rect {
        val until = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < until) {
            row(name)?.let { node ->
                val r = Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) return r
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("no \"$name\" account row on screen")
    }

    private fun rowChecked(name: String): Boolean = row(name)?.isChecked == true

    private fun waitRowChecked(name: String): Boolean {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            if (rowChecked(name)) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun roots() = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)

    /** The toggleable row that [label] is in (merged semantics: the text's node or its parent). */
    private fun toggle(label: String): AccessibilityNodeInfo? {
        val text = roots().firstNotNullOfOrNull { find(it, label) } ?: return null
        return if (text.isCheckable) text else text.parent?.takeIf { it.isCheckable }
    }

    /**
     * Where [label]'s switch is once it has stopped moving: a bottom sheet
     * can still be sliding up well after its content is in the tree on a
     * loaded host, and a tap aimed at an earlier position misses.
     */
    private fun findToggle(label: String): Rect {
        val until = SystemClock.uptimeMillis() + 8_000
        var last: Rect? = null
        while (SystemClock.uptimeMillis() < until) {
            val r = toggle(label)?.let { node -> Rect().also { node.getBoundsInScreen(it) } }?.takeUnless { it.isEmpty }
            if (r != null && r == last) return r
            last = r
            SystemClock.sleep(300)
        }
        throw AssertionError("no \"$label\" switch on screen")
    }

    private fun checked(label: String): Boolean = toggle(label)?.isChecked == true

    /** Where the text [label] is (a button's label: tapping its middle taps the button). */
    private fun findText(label: String): Rect {
        val until = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < until) {
            roots().firstNotNullOfOrNull { find(it, label) }?.let { node ->
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (!r.isEmpty) return r
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("no \"$label\" on screen")
    }

    private fun waitChecked(label: String, want: Boolean = true): Boolean {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            if (toggle(label) != null && checked(label) == want) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun onScreen(text: String): Boolean {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            if (roots().any { find(it, text) != null }) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label) return node
        return (0 until node.childCount).firstNotNullOfOrNull { i -> node.getChild(i)?.let { find(it, label) } }
    }

    private fun tap(r: Rect, flags: Int = 0) {
        val x = r.exactCenterX()
        val y = r.exactCenterY()
        val down = SystemClock.uptimeMillis()
        inject(down, down, MotionEvent.ACTION_DOWN, x, y, flags)
        inject(down, down + 40, MotionEvent.ACTION_UP, x, y, flags)
        instrumentation.waitForIdleSync()
    }

    private fun inject(down: Long, at: Long, action: Int, x: Float, y: Float, flags: Int) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val event = MotionEvent.obtain(
            down, at, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
        try {
            assertTrue(automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val ALLOW_EVERY_VISIT = "Allow every visit"
        const val ALLOW_WHILE_VISITING = "Allow while visiting"
    }
}
