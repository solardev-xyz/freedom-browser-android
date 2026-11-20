package baby.freedom.mobile.agent

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Images in each wire format (#601), as Pi sends them: a message's own
 * images, and a tool's (an attached image read, a rendered PDF page).
 */
class AgentWireImagesTest {
    private val openrouter = ProviderCatalog.definition("openrouter")!!
    private val anthropic = ProviderCatalog.definition("anthropic")!!
    private val openai = ProviderCatalog.definition("openai")!!
    private val codex = ProviderCatalog.definition(SubscriptionAuth.CODEX)!!

    private val photo = ImagePart("image/png", "iVBORw0KGgo=")
    private val page = ImagePart("image/png", "UEFHRQ==")
    private val conversation = listOf(
        ChatItem.User("what is this?", listOf(photo)),
        ChatItem.Assistant("", listOf(ToolCall("c1", "attachment_render_page", "{}"), ToolCall("c2", "current_time", "{}"))),
        ChatItem.ToolResult("c1", "attachment_render_page", "Rendered PDF: a.pdf — page 1 of 2", images = listOf(page)),
        ChatItem.ToolResult("c2", "current_time", "noon"),
        ChatItem.Assistant("A receipt."),
        ChatItem.User("thanks"),
    )
    private val request = StepRequest("m", "be brief", conversation, emptyList(), 100)

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun `chat completions - image parts in the message, a tool's images in a user message after the step's results`() {
        val (_, body) = AgentWire.build(openrouter, "https://openrouter.ai/api/v1".toHttpUrl(), request)
        val messages = body.getJSONArray("messages").objects()
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user", "assistant", "user"), messages.map { it.getString("role") })
        val first = messages[1].getJSONArray("content").objects()
        assertEquals("text", first[0].getString("type"))
        assertEquals("what is this?", first[0].getString("text"))
        assertEquals("data:image/png;base64,iVBORw0KGgo=", first[1].getJSONObject("image_url").getString("url"))
        // The tool message keeps only text.
        assertEquals("Rendered PDF: a.pdf — page 1 of 2", messages[3].getString("content"))
        val follow = messages[5].getJSONArray("content").objects()
        assertEquals(AgentWire.TOOL_IMAGES_NOTE, follow[0].getString("text"))
        assertEquals("data:image/png;base64,UEFHRQ==", follow[1].getJSONObject("image_url").getString("url"))
        // A message without images stays a plain string.
        assertEquals("thanks", messages[7].getString("content"))
    }

    @Test
    fun `anthropic - base64 image blocks in the message and in the tool result`() {
        val (_, body) = AgentWire.build(anthropic, "https://api.anthropic.com/v1".toHttpUrl(), request)
        val messages = body.getJSONArray("messages").objects()
        val first = messages[0].getJSONArray("content").objects()
        assertEquals("text", first[0].getString("type"))
        assertEquals("image", first[1].getString("type"))
        assertEquals(
            JSONObject().put("type", "base64").put("media_type", "image/png").put("data", "iVBORw0KGgo=").toString(),
            first[1].getJSONObject("source").toString(),
        )
        val results = messages[2].getJSONArray("content").objects()
        val withImage = results[0].getJSONArray("content").objects()
        assertEquals(listOf("text", "image"), withImage.map { it.getString("type") })
        assertEquals("UEFHRQ==", withImage[1].getJSONObject("source").getString("data"))
        assertEquals("noon", results[1].getString("content"))
        assertEquals("thanks", messages.last().getString("content"))
    }

    @Test
    fun `responses and codex - input_image in the message and in the call's output`() {
        for (definition in listOf(openai, codex)) {
            val (_, body) = AgentWire.build(definition, "https://example.test/v1".toHttpUrl(), request)
            val input = body.getJSONArray("input").objects().filter { it.optString("role") != "system" }
            val first = input[0].getJSONArray("content").objects()
            assertEquals(listOf("input_text", "input_image"), first.map { it.getString("type") })
            assertEquals("data:image/png;base64,iVBORw0KGgo=", first[1].getString("image_url"))
            val outputs = input.filter { it.optString("type") == "function_call_output" }
            val withImage = outputs[0].getJSONArray("output").objects()
            assertEquals(listOf("input_text", "input_image"), withImage.map { it.getString("type") })
            assertEquals("data:image/png;base64,UEFHRQ==", withImage[1].getString("image_url"))
            assertEquals("noon", outputs[1].getString("output"))
        }
    }

    @Test
    fun `an image never shows its data in a log line`() {
        assertEquals("ImagePart(image/png, 12 chars)", photo.toString())
    }
}
