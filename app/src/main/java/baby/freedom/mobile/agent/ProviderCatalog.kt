package baby.freedom.mobile.agent

import androidx.annotation.StringRes
import baby.freedom.mobile.R
import org.json.JSONArray
import org.json.JSONObject

/*
 * The model providers Freedom Agent can talk to (#573), ported from
 * desktop's `src/main/agent/provider-catalog.js` (`PROVIDER_DEFINITIONS`)
 * and `compatible-provider.js`. URLs and wire formats belong to Freedom,
 * never to a server's model metadata: a catalog response only names
 * models, it can't move where requests go.
 */

/** How a provider is reached and authenticated. */
internal enum class ProviderKind {
    /** A hosted API with the user's API key. */
    ApiKey,

    /** A server on this phone or the local network, by URL (Ollama, OpenAI-compatible). */
    Lan,
}

/**
 * The three request formats every provider speaks (#550): OpenAI's Chat
 * Completions, its Responses API, and Anthropic's Messages. Which one a
 * provider uses is desktop's choice (its Pi runtime's `api`).
 */
internal enum class WireFormat { OpenAiChat, OpenAiResponses, AnthropicMessages }

/** The group a provider is listed under on Settings → AI → Models & providers. */
internal enum class ProviderGroup(@StringRes val titleRes: Int) {
    Labs(R.string.agent_group_labs),
    Marketplaces(R.string.agent_group_marketplaces),
    Network(R.string.agent_group_network),
}

/**
 * One provider. [baseUrl] is fixed for hosted ones; a LAN provider's is
 * the user's ([ProviderUrls]). [catalogPath], when set, is where its
 * model list is discovered (relative to the base for LAN providers);
 * without one, [bundled] is its list, as on desktop (Pi's bundled
 * catalog). [catalogKey]: the listing needs the API key (OpenRouter's and
 * NEAR AI's don't, so their key is never sent to list models).
 */
internal data class ProviderDefinition(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val group: ProviderGroup,
    val wire: WireFormat,
    @StringRes val privacyRes: Int,
    @StringRes val descriptionRes: Int,
    val baseUrl: String? = null,
    val catalogPath: String? = null,
    val catalogKey: Boolean = false,
    val bundled: List<AgentModel> = emptyList(),
    /** Desktop's default pick, used until the user chooses one. */
    val defaultModelId: String? = null,
    /** Where to get a key, shown on the provider's page. */
    val keyUrl: String? = null,
) {
    /** The full URL the model list is fetched from, for a hosted provider. */
    val catalogUrl: String? get() = if (baseUrl != null && catalogPath != null) baseUrl + catalogPath else null
}

/** A model as the picker shows it. [name] falls back to [id]. */
internal data class AgentModel(val id: String, val name: String = id)

internal object ProviderCatalog {
    /** Desktop's bounds on a hosted provider's model list: bytes read and entries. */
    const val MAX_BYTES = 4L * 1024 * 1024
    const val MAX_MODELS = 2000

    /** Desktop's tighter bounds for a server on the network (Ollama's tags, an OpenAI-compatible `/models`). */
    const val LAN_MAX_BYTES = 1L * 1024 * 1024
    const val LAN_MAX_MODELS = 128

    /** A Test connection reply is a few hundred bytes; anything past this is refused unread. */
    const val TEST_MAX_BYTES = 256L * 1024

    const val OLLAMA = "ollama"
    const val COMPATIBLE = "openai-compatible"

    private fun models(vararg pairs: Pair<String, String>) = pairs.map { (id, name) -> AgentModel(id, name) }

    // Pi's bundled lists, whole (`pi-ai/dist/providers/data/*.json`, in
    // reverse of Pi's alphabetical order), which desktop uses for these four.
    private val OPENAI_MODELS = models(
        "o4-mini" to "o4-mini", "o3-pro" to "o3-pro", "o3-mini" to "o3-mini", "o3" to "o3", "o1-pro" to "o1-pro",
        "o1" to "o1", "gpt-realtime-2.1" to "GPT-Realtime-2.1", "gpt-daybreak-red-latest" to "Daybreak Red",
        "gpt-daybreak-blue-latest" to "Daybreak Blue", "gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-6-sol" to "GPT-6 Sol",
        "gpt-6-luna" to "GPT-6 Luna", "gpt-6-astra" to "GPT-6 Astra", "gpt-5.6-terra" to "GPT-5.6 Terra",
        "gpt-5.6-sol" to "GPT-5.6 Sol", "gpt-5.6-luna" to "GPT-5.6 Luna", "gpt-5.5-pro" to "GPT-5.5 Pro",
        "gpt-5.5" to "GPT-5.5", "gpt-5.4-pro" to "GPT-5.4 Pro", "gpt-5.4-nano" to "GPT-5.4 nano",
        "gpt-5.4-mini" to "GPT-5.4 mini", "gpt-5.4" to "GPT-5.4", "gpt-5.3-codex-spark" to "GPT-5.3 Codex Spark",
        "gpt-5.3-codex" to "GPT-5.3 Codex", "gpt-5.3-chat-latest" to "GPT-5.3 Chat (latest)",
        "gpt-5.2-pro" to "GPT-5.2 Pro", "gpt-5.2-chat-latest" to "GPT-5.2 Chat", "gpt-5.2" to "GPT-5.2",
        "gpt-5.1" to "GPT-5.1", "gpt-5-pro" to "GPT-5 Pro", "gpt-5-nano" to "GPT-5 Nano",
        "gpt-5-mini" to "GPT-5 Mini", "gpt-5-chat-latest" to "GPT-5 Chat Latest", "gpt-5" to "GPT-5",
        "gpt-4o-mini" to "GPT-4o mini", "gpt-4o-2024-11-20" to "GPT-4o (2024-11-20)",
        "gpt-4o-2024-08-06" to "GPT-4o (2024-08-06)", "gpt-4o-2024-05-13" to "GPT-4o (2024-05-13)",
        "gpt-4o" to "GPT-4o", "gpt-4.1-nano" to "GPT-4.1 nano", "gpt-4.1-mini" to "GPT-4.1 mini",
        "gpt-4.1" to "GPT-4.1", "gpt-4-turbo" to "GPT-4 Turbo", "gpt-4" to "GPT-4",
    )
    private val ANTHROPIC_MODELS = models(
        "claude-sonnet-5-5" to "Claude Sonnet 5.5", "claude-sonnet-5" to "Claude Sonnet 5",
        "claude-sonnet-4-6" to "Claude Sonnet 4.6", "claude-sonnet-4-5-20250929" to "Claude Sonnet 4.5",
        "claude-sonnet-4-5" to "Claude Sonnet 4.5 (latest)", "claude-opus-5-5" to "Claude Opus 5.5",
        "claude-opus-5" to "Claude Opus 5", "claude-opus-4-8" to "Claude Opus 4.8",
        "claude-opus-4-7" to "Claude Opus 4.7", "claude-opus-4-6" to "Claude Opus 4.6",
        "claude-opus-4-5-20251101" to "Claude Opus 4.5", "claude-opus-4-5" to "Claude Opus 4.5 (latest)",
        "claude-haiku-4-5-20251001" to "Claude Haiku 4.5", "claude-haiku-4-5" to "Claude Haiku 4.5 (latest)",
        "claude-fable-5-1" to "Claude Fable 5.1", "claude-fable-5" to "Claude Fable 5",
    )
    private val XAI_MODELS = models(
        "grok-4.7" to "Grok 4.7", "grok-4.6" to "Grok 4.6", "grok-4.5" to "Grok 4.5", "grok-4.3" to "Grok 4.3",
    )
    private val META_MODELS = models(
        "muse-spark-1.3-contributor" to "Muse Spark 1.3 Contributor", "muse-spark-1.3" to "Muse Spark 1.3",
        "muse-spark-1.2-contributor" to "Muse Spark 1.2 Contributor", "muse-spark-1.2" to "Muse Spark 1.2",
        "muse-spark-1.1" to "Muse Spark 1.1",
    )

    val ALL: List<ProviderDefinition> = listOf(
        ProviderDefinition(
            "openai", "OpenAI", ProviderKind.ApiKey, ProviderGroup.Labs, WireFormat.OpenAiResponses,
            R.string.agent_privacy_openai, R.string.agent_desc_openai,
            baseUrl = "https://api.openai.com/v1", catalogPath = "/models", catalogKey = true,
            bundled = OPENAI_MODELS, defaultModelId = "gpt-6.1-sol", keyUrl = "https://platform.openai.com/api-keys",
        ),
        ProviderDefinition(
            "anthropic", "Anthropic", ProviderKind.ApiKey, ProviderGroup.Labs, WireFormat.AnthropicMessages,
            R.string.agent_privacy_anthropic, R.string.agent_desc_anthropic,
            baseUrl = "https://api.anthropic.com/v1", bundled = ANTHROPIC_MODELS,
            defaultModelId = "claude-sonnet-5-5", keyUrl = "https://console.anthropic.com/settings/keys",
        ),
        ProviderDefinition(
            "xai", "xAI (Grok)", ProviderKind.ApiKey, ProviderGroup.Labs, WireFormat.OpenAiResponses,
            R.string.agent_privacy_xai, R.string.agent_desc_xai,
            baseUrl = "https://api.x.ai/v1", bundled = XAI_MODELS, defaultModelId = "grok-4.7",
            keyUrl = "https://console.x.ai",
        ),
        ProviderDefinition(
            "meta", "Meta (Muse)", ProviderKind.ApiKey, ProviderGroup.Labs, WireFormat.OpenAiResponses,
            R.string.agent_privacy_meta, R.string.agent_desc_meta,
            baseUrl = "https://api.meta.ai/v1", bundled = META_MODELS, defaultModelId = "muse-spark-1.3",
        ),
        ProviderDefinition(
            "openrouter", "OpenRouter", ProviderKind.ApiKey, ProviderGroup.Marketplaces, WireFormat.OpenAiChat,
            R.string.agent_privacy_openrouter, R.string.agent_desc_openrouter,
            baseUrl = "https://openrouter.ai/api/v1", catalogPath = "/models",
            keyUrl = "https://openrouter.ai/keys",
        ),
        ProviderDefinition(
            "venice", "Venice", ProviderKind.ApiKey, ProviderGroup.Marketplaces, WireFormat.OpenAiChat,
            R.string.agent_privacy_venice, R.string.agent_desc_venice,
            baseUrl = "https://api.venice.ai/api/v1", catalogPath = "/models?type=text", catalogKey = true,
            keyUrl = "https://venice.ai/settings/api",
        ),
        ProviderDefinition(
            "near-ai", "NEAR AI", ProviderKind.ApiKey, ProviderGroup.Marketplaces, WireFormat.OpenAiChat,
            R.string.agent_privacy_near, R.string.agent_desc_near,
            baseUrl = "https://cloud-api.near.ai/v1", catalogPath = "/model/list?limit=2000",
            keyUrl = "https://cloud.near.ai",
        ),
        ProviderDefinition(
            OLLAMA, "Ollama", ProviderKind.Lan, ProviderGroup.Network, WireFormat.OpenAiChat,
            R.string.agent_privacy_ollama, R.string.agent_desc_ollama, catalogPath = "/api/tags",
        ),
        ProviderDefinition(
            COMPATIBLE, "OpenAI-compatible", ProviderKind.Lan, ProviderGroup.Network, WireFormat.OpenAiChat,
            R.string.agent_privacy_compatible, R.string.agent_desc_compatible, catalogPath = "/models",
        ),
    )

    private val byId = ALL.associateBy { it.id }

    fun definition(id: String): ProviderDefinition? = byId[id]

    /** The byte and entry bounds for [definition]'s model list. */
    fun bounds(definition: ProviderDefinition): Pair<Long, Int> =
        if (definition.kind == ProviderKind.Lan) LAN_MAX_BYTES to LAN_MAX_MODELS else MAX_BYTES to MAX_MODELS

    /**
     * Desktop's `text()`, made stricter for what the phone shows: a
     * non-empty, trimmed string of at most [max] characters made only of
     * letters, digits, punctuation, symbols and plain spaces. Anything
     * that draws nothing or reorders the text around it (controls, format
     * and bidi characters, other separators, combining marks, lone
     * surrogates, unassigned code points, blank-looking fillers) makes the
     * whole string unusable, so a model list can't hide or overprint
     * anything on the page.
     */
    fun cleanText(value: Any?, max: Int = 200): String? {
        if (value !is String || value.isEmpty() || value.length > max || value != value.trim()) return null
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (!printable(cp)) return null
            i += Character.charCount(cp)
        }
        return value
    }

    private val BLANK_LETTERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800)

    private fun printable(cp: Int): Boolean {
        if (cp == ' '.code) return true
        if (cp in BLANK_LETTERS) return false
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            -> true
            else -> false
        }
    }

    /** A catalog that can't be used: the reason says nothing of what the server sent. */
    class CatalogException(val reason: Reason) : Exception(reason.name) {
        enum class Reason { Malformed, TooLarge, TooMany, Empty }
    }

    /**
     * The models [definition]'s catalog [body] names, normalised the way
     * desktop's `normalizeModels` does for each provider. Throws
     * [CatalogException] on a body that isn't the expected shape, names
     * more than the provider's bound (refused, not cut short: a cut list
     * would quietly lose models), or names no usable model. Duplicates and
     * entries with an unusable id are skipped.
     */
    fun parseModels(definition: ProviderDefinition, body: String): List<AgentModel> {
        val (_, maxModels) = bounds(definition)
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw CatalogException(CatalogException.Reason.Malformed)
        } catch (e: StackOverflowError) {
            // A deeply nested body (`{"a":[[[[…`): org.json recurses.
            throw CatalogException(CatalogException.Reason.Malformed)
        }
        val entries: JSONArray = when (definition.id) {
            "near-ai", OLLAMA -> root.optJSONArray("models")
            else -> root.optJSONArray("data")
        } ?: throw CatalogException(CatalogException.Reason.Malformed)
        if (entries.length() > maxModels) throw CatalogException(CatalogException.Reason.TooMany)
        // NEAR AI pages its list: a total past what came back means a cut list.
        if (definition.id == "near-ai" && root.optInt("total", 0) > entries.length()) {
            throw CatalogException(CatalogException.Reason.TooMany)
        }
        val known = definition.bundled.associateBy { it.id }
        val seen = HashSet<String>()
        val out = ArrayList<AgentModel>()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val model = when (definition.id) {
                "openai" -> {
                    // /v1/models also lists audio, image and embedding
                    // products; as on desktop, only models the app knows
                    // as chat models are offered.
                    val id = cleanText(item.opt("id")) ?: continue
                    known[id] ?: continue
                }
                "venice" -> {
                    if (item.optString("type") != "text") continue
                    val id = cleanText(item.opt("id")) ?: continue
                    val spec = item.optJSONObject("model_spec")
                    // An end-to-end encrypted model needs its own protocol;
                    // never downgrade it to plain requests (desktop).
                    if (spec?.optString("privacy") == "e2ee" || spec?.optBoolean("offline") == true) continue
                    AgentModel(id, cleanText(spec?.opt("name")) ?: id)
                }
                "near-ai" -> {
                    val id = cleanText(item.opt("modelId") ?: item.opt("id")) ?: continue
                    val metadata = item.optJSONObject("metadata")
                    val output = metadata?.optJSONObject("architecture")?.optJSONArray("outputModalities")
                    if (output != null && !output.contains("text")) continue
                    if (metadata?.opt("isReady") == false) continue
                    AgentModel(id, cleanText(metadata?.opt("modelDisplayName")) ?: id)
                }
                "openrouter" -> {
                    val id = cleanText(item.opt("id")) ?: continue
                    val output = item.optJSONObject("architecture")?.optJSONArray("output_modalities")
                    if (output != null && !output.contains("text")) continue
                    AgentModel(id, cleanText(item.opt("name")) ?: id)
                }
                OLLAMA -> AgentModel(cleanText(item.opt("name")) ?: continue)
                COMPATIBLE -> AgentModel(cleanText(item.opt("id")) ?: continue)
                else -> {
                    val id = cleanText(item.opt("id")) ?: continue
                    AgentModel(id, cleanText(item.opt("display_name") ?: item.opt("name")) ?: id)
                }
            }
            if (seen.add(model.id)) out += model
        }
        if (out.isEmpty()) throw CatalogException(CatalogException.Reason.Empty)
        return out
    }

    private fun JSONArray.contains(value: String): Boolean =
        (0 until length()).any { optString(it) == value }
}
