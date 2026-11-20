package baby.freedom.mobile.agent

/*
 * The agent loop (#574), native Kotlin rather than desktop's Pi runtime
 * (#550): stream a step, collect the tool calls it asks for, run them
 * through the [ToolRegistry], append the results and ask again, until
 * the model answers without a tool; the conversation is compacted into a
 * summary when it nears the model's context window, as Pi does
 * (`pi-coding-agent/dist/core/compaction`).
 */

/** Why a turn didn't end in a whole answer: shown in plain words, never the server's. */
internal enum class ChatError {
    /** No model chosen, or its provider isn't connected any more. */
    NoModel,
    NoKey,
    BadUrl,
    Unauthorized,
    ModelNotFound,
    RateLimited,
    ServerError,
    Refused,
    Unreachable,
    TimedOut,
    BadReply,
    ContextTooLong,

    /** The model kept calling tools past [AgentLoop.MAX_STEPS]. */
    TooManySteps,

    /** AI was turned off while it ran. */
    AiOff,

    /** The provider's key or address changed (or it was removed) while it ran. */
    KeyChanged,

    /** Not a failure: the answer stopped at its token limit. */
    CutShort,
}

/** A finished turn of a chat, as the model is given it again. */
internal data class HistoryTurn(val position: Int, val userText: String, val assistantText: String)

/** The conversation before [through] (a turn position), compacted. */
internal data class Summary(val text: String, val through: Int)

internal sealed interface StepOutcome {
    data class Ok(val result: StepResult) : StepOutcome
    data class Failed(val error: ChatError) : StepOutcome
}

/** Where a step goes: [onText] gets each piece of reply text as it streams in. */
internal fun interface ModelStream {
    suspend fun step(request: StepRequest, onText: (String) -> Unit): StepOutcome
}

/**
 * A model's context window and Pi's compaction settings
 * (`reserveTokens` 16384, `keepRecentTokens` 20000), scaled down for a
 * small window so the two still leave room for each other.
 */
internal data class ContextLimits(
    val window: Int,
    val maxOutput: Int,
    val reserve: Int = minOf(16_384, window / 4),
    val keepRecent: Int = minOf(20_000, window / 4),
) {
    companion object {
        /**
         * What's assumed of a model the app has no metadata for: a hosted
         * one takes at least 128k tokens (every current one does); a
         * server on the network is often run with far less.
         */
        fun of(definition: ProviderDefinition): ContextLimits =
            if (definition.kind == ProviderKind.Lan) ContextLimits(32_768, 4_096) else ContextLimits(128_000, 16_384)
    }
}

/** How a turn came out: [text] is everything the model said, [error] why it stopped short, if it did. */
internal data class TurnOutcome(val text: String, val error: ChatError? = null)

internal class AgentLoop(
    private val model: ModelStream,
    private val tools: ToolRegistry,
    private val limits: ContextLimits,
    private val modelId: String,
    /** The system prompt, with a time context fresh for each request. */
    private val systemPrompt: () -> String,
    private val maxSteps: Int = MAX_STEPS,
) {
    /**
     * Runs one turn for [userText] after [history] (finished turns, by
     * position, which [summary] may already cover in part). [onText]
     * gets the turn's whole reply text each time it grows; [onCompacted]
     * the new summary whenever the conversation is compacted, to keep.
     * Cancelling the caller stops it wherever it is (a stream, a tool, a
     * compaction).
     */
    suspend fun run(
        history: List<HistoryTurn>,
        summary: Summary?,
        userText: String,
        onText: (String) -> Unit,
        onCompacted: suspend (Summary) -> Unit,
    ): TurnOutcome {
        var currentSummary = summary
        val turn = ArrayList<ChatItem>()
        turn += ChatItem.User(userText)
        val text = StringBuilder()
        var compactionFailed = false
        for (step in 0 until maxSteps) {
            val system = systemPrompt()
            var items = itemsFor(history, currentSummary, turn)
            if (!compactionFailed && estimate(system, items) > limits.window - limits.reserve) {
                val compacted = compact(history, currentSummary)
                if (compacted == null) {
                    // History stays as it is; the provider may still take it.
                    compactionFailed = true
                } else {
                    currentSummary = compacted
                    onCompacted(compacted)
                    items = itemsFor(history, currentSummary, turn)
                }
            }
            val separator = if (text.isNotEmpty()) "\n\n" else ""
            var first = true
            val outcome = model.step(StepRequest(modelId, system, items, tools.specs, limits.maxOutput)) { delta ->
                if (delta.isEmpty()) return@step
                if (first) {
                    text.append(separator)
                    first = false
                }
                text.append(delta)
                onText(text.toString())
            }
            val result = when (outcome) {
                is StepOutcome.Failed -> return TurnOutcome(text.toString(), outcome.error)
                is StepOutcome.Ok -> outcome.result
            }
            if (result.stop == StopReason.Length) return TurnOutcome(text.toString(), ChatError.CutShort)
            if (result.toolCalls.isEmpty()) return TurnOutcome(text.toString())
            turn += ChatItem.Assistant(result.text, result.toolCalls)
            // Every call gets exactly one result, in the order asked.
            for (call in result.toolCalls) {
                val out = tools.run(call)
                turn += ChatItem.ToolResult(call.id, call.name, out.output, out.isError)
            }
        }
        return TurnOutcome(text.toString(), ChatError.TooManySteps)
    }

    /** What the model is sent: the summary, the turns after it, then this turn so far. */
    private fun itemsFor(history: List<HistoryTurn>, summary: Summary?, turn: List<ChatItem>): List<ChatItem> = buildList {
        if (summary != null) add(ChatItem.User(SUMMARY_PREFIX + summary.text + SUMMARY_SUFFIX))
        for (t in history) {
            if (summary != null && t.position < summary.through) continue
            add(ChatItem.User(t.userText))
            add(ChatItem.Assistant(t.assistantText))
        }
        addAll(turn)
    }

    private fun estimate(system: String, items: List<ChatItem>): Int =
        tokens(system.length) + items.sumOf { tokens(charsOf(it)) } +
            tools.specs.sumOf { tokens(it.name.length + it.description.length + it.parameters.toString().length) }

    /**
     * Summarises the oldest finished turns into [summary] (Pi's
     * checkpoint format), keeping the most recent ones (about
     * [ContextLimits.keepRecent] tokens' worth) as they are. Cuts only
     * between turns, so a tool call and its result never part. Null when
     * there's nothing to compact or the summary couldn't be made; the
     * history is untouched either way.
     */
    private suspend fun compact(history: List<HistoryTurn>, summary: Summary?): Summary? {
        val open = history.filter { summary == null || it.position >= summary.through }
        if (open.isEmpty()) return null
        var kept = 0
        var cut = open.size
        while (cut > 0) {
            val t = open[cut - 1]
            val cost = tokens(t.userText.length + t.assistantText.length)
            if (kept + cost > limits.keepRecent) break
            kept += cost
            cut--
        }
        // Everything fits in the recent share, yet the whole is too long:
        // the history goes into the summary and this turn stays as it is.
        if (cut == 0) cut = open.size
        val toSummarise = open.subList(0, cut)
        var conversation = toSummarise.joinToString("\n\n") { "[User]: ${it.userText}\n\n[Assistant]: ${it.assistantText}" }
        // The summary request itself must fit the window, with its
        // instructions and the previous summary: an over-long one keeps
        // its newest part.
        val budget = maxOf(
            0,
            (limits.window - limits.reserve) * 4 - UPDATE_SUMMARIZATION_PROMPT.length - 200 - (summary?.text?.length ?: 0),
        )
        if (conversation.length > budget) conversation = conversation.takeLast(budget)
        val prompt = buildString {
            append("<conversation>\n").append(conversation).append("\n</conversation>\n\n")
            if (summary != null) append("<previous-summary>\n").append(summary.text).append("\n</previous-summary>\n\n")
            append(if (summary != null) UPDATE_SUMMARIZATION_PROMPT else SUMMARIZATION_PROMPT)
        }
        val request = StepRequest(
            modelId,
            SUMMARIZATION_SYSTEM_PROMPT,
            listOf(ChatItem.User(prompt)),
            emptyList(),
            minOf((limits.reserve * 0.8).toInt(), limits.maxOutput),
        )
        val outcome = model.step(request) {}
        val result = (outcome as? StepOutcome.Ok)?.result ?: return null
        // A cut-off summary, or one that tried to call a tool, isn't a checkpoint (Pi).
        if (result.stop != StopReason.Done || result.text.isBlank()) return null
        return Summary(result.text.trim(), toSummarise.last().position + 1)
    }

    companion object {
        const val MAX_STEPS = 24

        /** Pi's estimate: about four characters a token. */
        fun tokens(chars: Int): Int = (chars + 3) / 4

        private fun charsOf(item: ChatItem): Int = when (item) {
            is ChatItem.User -> item.text.length
            is ChatItem.Assistant -> item.text.length + item.toolCalls.sumOf { it.name.length + it.arguments.length }
            is ChatItem.ToolResult -> item.output.length
        }

        // Pi's compaction prompts (`compaction/compaction.js`, `utils.js`,
        // `messages.js`), as they are: they're for the model.
        const val SUMMARY_PREFIX = "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
        const val SUMMARY_SUFFIX = "\n</summary>"

        const val SUMMARIZATION_SYSTEM_PROMPT = "You are a context summarization assistant. Your task is to read a " +
            "conversation between a user and an AI assistant, then produce a structured summary following the exact " +
            "format specified.\n\nDo NOT continue the conversation. Do NOT respond to any questions in the " +
            "conversation. ONLY output the structured summary."

        private const val FORMAT = """## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or "(none)" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

        const val SUMMARIZATION_PROMPT = "The messages above are a conversation to summarize. Create a structured " +
            "context checkpoint summary that another LLM will use to continue the work.\n\nUse this EXACT format:\n\n" + FORMAT

        const val UPDATE_SUMMARIZATION_PROMPT = "The messages above are NEW conversation messages to incorporate into " +
            "the existing summary provided in <previous-summary> tags.\n\nUpdate the existing structured summary with " +
            "new information. RULES:\n- PRESERVE all existing information from the previous summary\n- ADD new " +
            "progress, decisions, and context from the new messages\n- UPDATE the Progress section: move items from " +
            "\"In Progress\" to \"Done\" when completed\n- UPDATE \"Next Steps\" based on what was accomplished\n- " +
            "PRESERVE exact file paths, function names, and error messages\n- If something is no longer relevant, you " +
            "may remove it\n\nUse this EXACT format:\n\n" + FORMAT
    }
}
