package baby.freedom.mobile.agent

import baby.freedom.mobile.agent.ProviderCatalog.CatalogException.Reason
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Model discovery's parsing and bounds (#573), ported from desktop's `normalizeModels`. */
class ProviderCatalogTest {
    private fun def(id: String) = ProviderCatalog.definition(id)!!

    private fun reason(id: String, body: String): Reason? = try {
        ProviderCatalog.parseModels(def(id), body)
        null
    } catch (e: ProviderCatalog.CatalogException) {
        e.reason
    }

    private fun data(vararg items: JSONObject) = JSONObject().put("data", JSONArray(items.toList())).toString()

    @Test
    fun `the providers, their wire formats and base URLs match desktop`() {
        assertEquals(
            listOf(
                "openai", "anthropic", "xai", "meta", "openai-chatgpt", "openai-codex", "meta-subscription", "openrouter", "venice", "near-ai",
                "ollama", "openai-compatible",
            ),
            ProviderCatalog.ALL.map { it.id },
        )
        assertEquals(WireFormat.OpenAiResponses, def("openai").wire)
        assertEquals(WireFormat.AnthropicMessages, def("anthropic").wire)
        assertEquals("https://openrouter.ai/api/v1/models", def("openrouter").catalogUrl)
        assertEquals("https://api.venice.ai/api/v1/models?type=text", def("venice").catalogUrl)
        assertEquals("https://cloud-api.near.ai/v1/model/list?limit=2000", def("near-ai").catalogUrl)
        // OpenRouter's and NEAR AI's lists are public: no key is sent for them.
        assertFalse(def("openrouter").catalogKey)
        assertFalse(def("near-ai").catalogKey)
        assertTrue(def("openai").catalogKey)
        assertTrue(def("venice").catalogKey)
        // Anthropic, xAI and Meta use the bundled list, as desktop does.
        for (id in listOf("anthropic", "xai", "meta")) {
            assertNull(def(id).catalogPath)
            assertTrue(def(id).bundled.isNotEmpty())
            assertTrue(def(id).bundled.any { it.id == def(id).defaultModelId })
        }
        // The subscriptions (#576): desktop's base URLs, formats and defaults.
        // Sign in with ChatGPT (#602): OpenAI's own API and bundled list, no catalog URL.
        assertEquals(WireFormat.OpenAiResponses, def("openai-chatgpt").wire)
        assertEquals(ProviderKind.Subscription, def("openai-chatgpt").kind)
        assertEquals("https://api.openai.com/v1", def("openai-chatgpt").baseUrl)
        assertNull(def("openai-chatgpt").catalogPath)
        assertEquals("gpt-6.1-sol", def("openai-chatgpt").defaultModelId)
        assertTrue(def("openai-chatgpt").bundled.any { it.id == "gpt-6.1-sol" })
        assertEquals(WireFormat.CodexResponses, def("openai-codex").wire)
        assertEquals(
            "https://chatgpt.com/backend-api/codex/models?client_version=0.153.0",
            def("openai-codex").catalogUrl,
        )
        assertTrue(def("openai-codex").catalogKey)
        assertEquals("gpt-6-astra", def("openai-codex").defaultModelId)
        assertEquals(WireFormat.OpenAiResponses, def("meta-subscription").wire)
        assertEquals(def("meta").baseUrl, def("meta-subscription").baseUrl)
        assertEquals(def("meta").bundled, def("meta-subscription").bundled)
        assertEquals("muse-spark-1.3", def("meta-subscription").defaultModelId)
        // Every hosted base is https.
        for (d in ProviderCatalog.ALL.filter { it.kind != ProviderKind.Lan }) assertTrue(d.baseUrl!!.startsWith("https://"))
    }

    @Test
    fun `chatgpt lists only the models codex offers for picking`() {
        val list = JSONArray()
            .put(
                JSONObject().put("slug", "gpt-6.1-sol").put("display_name", "GPT-6.1 Sol").put("visibility", "list")
                    .put("input_modalities", JSONArray().put("text")),
            )
            .put(JSONObject().put("slug", "codex-auto-review").put("visibility", "hide"))
            .put(JSONObject().put("slug", "gpt-6-astra").put("visibility", "list"))
            .put(JSONObject().put("slug", "gpt-6-astra").put("visibility", "list"))
            .put(JSONObject().put("slug", "bad\u202Eid").put("visibility", "list"))
        val models = ProviderCatalog.parseModels(def("openai-codex"), JSONObject().put("models", list).toString())
        // Images (#601): as the list says, else as the bundled entry does.
        assertEquals(listOf(AgentModel("gpt-6.1-sol", "GPT-6.1 Sol", vision = false), AgentModel("gpt-6-astra", "GPT-6 Astra", vision = true)), models)
    }

    @Test
    fun `openai offers only the chat models it knows, with their names`() {
        val models = ProviderCatalog.parseModels(
            def("openai"),
            data(
                JSONObject().put("id", "gpt-6.1-sol"),
                JSONObject().put("id", "text-embedding-3-large"),
                JSONObject().put("id", "dall-e-3"),
                JSONObject().put("id", "o3"),
            ),
        )
        assertEquals(listOf(AgentModel("gpt-6.1-sol", "GPT-6.1 Sol", vision = true), AgentModel("o3", "o3", vision = true)), models)
    }

    @Test
    fun `which models take images, as each catalog says (#601)`() {
        val openrouter = ProviderCatalog.parseModels(
            def("openrouter"),
            data(
                JSONObject().put("id", "a/vision").put("architecture", JSONObject().put("input_modalities", JSONArray().put("text").put("image"))),
                JSONObject().put("id", "b/text").put("architecture", JSONObject().put("input_modalities", JSONArray().put("text"))),
                JSONObject().put("id", "c/unsaid"),
            ),
        )
        assertEquals(listOf(true, false, false), openrouter.map { it.vision })
        val venice = ProviderCatalog.parseModels(
            def("venice"),
            data(
                JSONObject().put("id", "v1").put("type", "text").put("model_spec", JSONObject().put("capabilities", JSONObject().put("supportsVision", true))),
                JSONObject().put("id", "v2").put("type", "text").put("model_spec", JSONObject().put("capabilities", JSONObject().put("supportsVision", "yes"))),
            ),
        )
        assertEquals(listOf(true, false), venice.map { it.vision })
        val near = ProviderCatalog.parseModels(
            def("near-ai"),
            JSONObject().put(
                "models",
                JSONArray()
                    .put(JSONObject().put("modelId", "n1").put("metadata", JSONObject().put("architecture", JSONObject().put("inputModalities", JSONArray().put("image")))))
                    .put(JSONObject().put("modelId", "n2")),
            ).toString(),
        )
        assertEquals(listOf(true, false), near.map { it.vision })
        // Pi's bundled lists: all but its text-only models.
        val bundled = def("openai").bundled.associate { it.id to it.vision }
        assertEquals(false, bundled["gpt-4"])
        assertEquals(false, bundled["o3-mini"])
        assertEquals(true, bundled["gpt-6.1-sol"])
        assertTrue(def("anthropic").bundled.all { it.vision })
        // A server on the network says nothing: no images.
        val ollama = ProviderCatalog.parseModels(def(ProviderCatalog.OLLAMA), JSONObject().put("models", JSONArray().put(JSONObject().put("name", "llava"))).toString())
        assertFalse(ollama.single().vision)
        // Stored and read back; a list stored before says nothing, so no images.
        val stored = AgentStore.encodeModels(openrouter)
        assertEquals(openrouter, AgentStore.decodeModels(stored))
        assertEquals(listOf(false), AgentStore.decodeModels("""[{"id":"a/vision"}]""").map { it.vision })
    }

    @Test
    fun `venice drops non-text, offline and end-to-end encrypted models`() {
        val models = ProviderCatalog.parseModels(
            def("venice"),
            data(
                JSONObject().put("id", "llama").put("type", "text").put("model_spec", JSONObject().put("name", "Llama")),
                JSONObject().put("id", "img").put("type", "image"),
                JSONObject().put("id", "e2ee-qwen").put("type", "text")
                    .put("model_spec", JSONObject().put("privacy", "e2ee")),
                JSONObject().put("id", "gone").put("type", "text").put("model_spec", JSONObject().put("offline", true)),
            ),
        )
        assertEquals(listOf(AgentModel("llama", "Llama")), models)
    }

    @Test
    fun `near ai reads models, skips non-text output, and refuses a cut list`() {
        val ok = JSONObject().put(
            "models",
            JSONArray()
                .put(JSONObject().put("modelId", "zai/glm").put("metadata", JSONObject().put("modelDisplayName", "GLM")))
                .put(
                    JSONObject().put("modelId", "img").put(
                        "metadata",
                        JSONObject().put("architecture", JSONObject().put("outputModalities", JSONArray().put("image"))),
                    ),
                ),
        ).put("total", 2)
        assertEquals(listOf(AgentModel("zai/glm", "GLM")), ProviderCatalog.parseModels(def("near-ai"), ok.toString()))
        assertEquals(Reason.TooMany, reason("near-ai", ok.put("total", 3).toString()))
    }

    @Test
    fun `openrouter drops non-text output`() {
        val models = ProviderCatalog.parseModels(
            def("openrouter"),
            data(
                JSONObject().put("id", "a/b").put("name", "A B"),
                JSONObject().put("id", "c/img").put(
                    "architecture",
                    JSONObject().put("output_modalities", JSONArray().put("image")),
                ),
            ),
        )
        assertEquals(listOf(AgentModel("a/b", "A B")), models)
    }

    @Test
    fun `ollama tags and a compatible server's models are read by name and id`() {
        val tags = JSONObject().put("models", JSONArray().put(JSONObject().put("name", "llama3.2:3b"))).toString()
        assertEquals(listOf(AgentModel("llama3.2:3b")), ProviderCatalog.parseModels(def("ollama"), tags))
        assertEquals(
            listOf(AgentModel("qwen")),
            ProviderCatalog.parseModels(def("openai-compatible"), data(JSONObject().put("id", "qwen"))),
        )
    }

    @Test
    fun `more models than the bound is refused, not cut short`() {
        val hosted = JSONArray().apply { repeat(2001) { put(JSONObject().put("id", "m$it")) } }
        assertEquals(Reason.TooMany, reason("openrouter", JSONObject().put("data", hosted).toString()))
        val atBound = JSONArray().apply { repeat(2000) { put(JSONObject().put("id", "m$it")) } }
        assertEquals(2000, ProviderCatalog.parseModels(def("openrouter"), JSONObject().put("data", atBound).toString()).size)
        // A server on the network gets desktop's tighter bound.
        val lan = JSONArray().apply { repeat(129) { put(JSONObject().put("name", "m$it")) } }
        assertEquals(Reason.TooMany, reason("ollama", JSONObject().put("models", lan).toString()))
    }

    @Test
    fun `malformed, nested and empty bodies are refused`() {
        assertEquals(Reason.Malformed, reason("openrouter", "not json"))
        assertEquals(Reason.Malformed, reason("openrouter", "{\"models\":[]}"))
        assertEquals(Reason.Malformed, reason("openrouter", "{\"data\":" + "[".repeat(200_000)))
        assertEquals(Reason.Empty, reason("openrouter", "{\"data\":[]}"))
        assertEquals(Reason.Empty, reason("openrouter", "{\"data\":[{\"id\":\"\"},{\"id\":5}]}"))
    }

    @Test
    fun `ids that hide or reorder text are skipped, names fall back to the id, duplicates once`() {
        val models = ProviderCatalog.parseModels(
            def("openrouter"),
            data(
                JSONObject().put("id", "evil‮loh"),
                JSONObject().put("id", "zero​width"),
                JSONObject().put("id", "tag󠁁"),
                JSONObject().put("id", "line break"),
                JSONObject().put("id", " padded"),
                JSONObject().put("id", "ok/model").put("name", "Nice‮Name"),
                JSONObject().put("id", "ok/model").put("name", "Second"),
                JSONObject().put("id", "x".repeat(201)),
            ),
        )
        assertEquals(listOf(AgentModel("ok/model", "ok/model")), models)
    }

    @Test
    fun `clean text keeps ordinary names in any script`() {
        assertEquals("Llama 3.2 · 3B (Q4)", ProviderCatalog.cleanText("Llama 3.2 · 3B (Q4)"))
        assertEquals("通义千问", ProviderCatalog.cleanText("通义千问"))
        assertNull(ProviderCatalog.cleanText("é"))
        assertNull(ProviderCatalog.cleanText("blankㅤ"))
        assertNull(ProviderCatalog.cleanText("var️"))
        assertNull(ProviderCatalog.cleanText(42))
    }

    @Test
    fun `test requests use each provider's wire format and headers`() {
        val base = okhttp3.HttpUrl.Builder().scheme("https").host("api.example.com").addPathSegment("v1").build()
        val (anthropicUrl, anthropicBody) = ProviderClient.testRequest(def("anthropic"), base, "claude-sonnet-5-5")
        assertEquals("https://api.example.com/v1/messages", anthropicUrl.toString())
        assertEquals(32, anthropicBody.getInt("max_tokens"))
        assertEquals(
            mapOf("anthropic-version" to "2023-06-01", "x-api-key" to "sk-ant"),
            ProviderClient.authHeaders(def("anthropic"), "sk-ant"),
        )
        val (responsesUrl, responsesBody) = ProviderClient.testRequest(def("openai"), base, "gpt-6.1-sol")
        assertEquals("https://api.example.com/v1/responses", responsesUrl.toString())
        assertFalse(responsesBody.getBoolean("store"))
        assertEquals(mapOf("Authorization" to "Bearer sk-x"), ProviderClient.authHeaders(def("openai"), "sk-x"))
        val (chatUrl, _) = ProviderClient.testRequest(def("venice"), base, "llama")
        assertEquals("https://api.example.com/v1/chat/completions", chatUrl.toString())
        // Ollama: the OpenAI API under /v1 and its own tags at the root, whether or not /v1 was typed.
        val ollama = okhttp3.HttpUrl.Builder().scheme("http").host("192.168.1.2").port(11434).build()
        val ollamaV1 = ollama.newBuilder().addPathSegment("v1").build()
        for (b in listOf(ollama, ollamaV1)) {
            assertEquals("http://192.168.1.2:11434/v1/chat/completions", ProviderClient.testRequest(def("ollama"), b, "m").first.toString())
            assertEquals("http://192.168.1.2:11434/api/tags", ProviderClient.catalogUrl(def("ollama"), b).toString())
        }
        assertEquals(emptyMap<String, String>(), ProviderClient.authHeaders(def("ollama"), null))
    }

    @Test
    fun `a reply is recognised in each format`() {
        assertTrue(ProviderClient.isReply(def("anthropic"), "{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}"))
        assertTrue(ProviderClient.isReply(def("openai"), "{\"status\":\"completed\",\"output\":[]}"))
        assertTrue(ProviderClient.isReply(def("venice"), "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"))
        assertFalse(ProviderClient.isReply(def("venice"), "{\"choices\":[]}"))
        assertFalse(ProviderClient.isReply(def("venice"), "<html>"))
        try {
            assertFalse(ProviderClient.isReply(def("venice"), "[".repeat(200_000)))
        } catch (e: Throwable) {
            fail("nested body threw $e")
        }
    }
}
