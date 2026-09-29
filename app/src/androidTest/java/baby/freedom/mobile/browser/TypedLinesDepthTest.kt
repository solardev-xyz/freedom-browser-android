package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.Eip712
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #216 R3-F1: typed data nests without limit, and a line indented a step
 * per level ran off the sheet past ~26 levels — signed, never drawn. Every
 * line, however deep, lies inside the sheet's width with room for its text.
 */
@RunWith(AndroidJUnit4::class)
class TypedLinesDepthTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun deepFieldsStayOnTheSheet() {
        val types = JSONObject()
        types.put("EIP712Domain", JSONArray().put(JSONObject().put("name", "name").put("type", "string")))
        types.put("Order", JSONArray().put(JSONObject().put("name", "note").put("type", "string")).put(JSONObject().put("name", "n").put("type", "N0")))
        for (i in 0 until 29) types.put("N$i", JSONArray().put(JSONObject().put("name", "n").put("type", "N${i + 1}")))
        types.put("N29", JSONArray().put(JSONObject().put("name", "spender").put("type", "address")).put(JSONObject().put("name", "amount").put("type", "uint256")))
        var inner = JSONObject().put("spender", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("amount", "123456789")
        for (i in 29 downTo 1) inner = JSONObject().put("n", inner)
        val payload = JSONObject().put("types", types).put("domain", JSONObject().put("name", "x")).put("primaryType", "Order")
            .put("message", JSONObject().put("note", "hello").put("n", inner))
        val (_, message) = Eip712.lines(Eip712.parseStrict(payload.toString()))
        rule.setContent {
            FreedomTheme {
                Box(Modifier.width(358.dp).testTag("sheet").verticalScroll(rememberScrollState())) {
                    TypedLines("Message", message)
                }
            }
        }
        val sheet = rule.onNodeWithTag("sheet").getBoundsInRoot()
        for (text in listOf("level 30 · spender", "level 30 · amount", "123456789")) {
            val node = rule.onNodeWithText(text, useUnmergedTree = true)
            node.performScrollTo()
            val b = node.getBoundsInRoot()
            assertTrue("$text at $b", b.left >= sheet.left && b.right <= sheet.right && b.right - b.left > 40.dp)
        }
    }
}
