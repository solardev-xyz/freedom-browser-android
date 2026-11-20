package baby.freedom.mobile.agent

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent loop (#574): tool round trips, its bounds, cancelling, and compaction. */
class AgentLoopTest {
    /** Answers each step from [answers], in turn, and records what it was asked. */
    private class Script(vararg answers: (StepRequest) -> StepOutcome) : ModelStream {
        val queue = ArrayDeque(answers.toList())
        val requests = ArrayList<StepRequest>()

        override suspend fun step(request: StepRequest, onText: (String) -> Unit): StepOutcome {
            requests += request
            val outcome = queue.removeFirst()(request)
            (outcome as? StepOutcome.Ok)?.result?.text?.let { if (it.isNotEmpty()) onText(it) }
            return outcome
        }
    }

    private fun reply(text: String, vararg calls: ToolCall, stop: StopReason? = null): (StepRequest) -> StepOutcome = {
        StepOutcome.Ok(StepResult(text, calls.toList(), stop ?: if (calls.isEmpty()) StopReason.Done else StopReason.ToolUse))
    }

    private val clock = Instant.parse("2026-11-20T09:30:00Z")
    private val tools = ToolRegistry.default({ clock }, { ZoneId.of("Europe/Vienna") })

    private fun loop(model: ModelStream, limits: ContextLimits = ContextLimits(128_000, 1000), maxSteps: Int = AgentLoop.MAX_STEPS) =
        AgentLoop(model, tools, limits, "m", { "system" }, maxSteps)

    private fun run(
        loop: AgentLoop,
        history: List<HistoryTurn> = emptyList(),
        summary: Summary? = null,
        compacted: MutableList<Summary> = ArrayList(),
        shown: MutableList<String> = ArrayList(),
    ) = runBlocking { loop.run(history, summary, "what time is it?", { shown += it.toString() }, { compacted += it }) }

    @Test
    fun `a tool call runs, its result goes back, and the texts of both steps join`() {
        val model = Script(reply("Checking.", ToolCall("c1", "current_time", "{}")), reply("It's 10:30."))
        val shown = ArrayList<String>()
        val outcome = run(loop(model), shown = shown)
        assertEquals(TurnOutcome("Checking.\n\nIt's 10:30."), outcome)
        assertEquals(listOf("Checking.", "Checking.\n\nIt's 10:30."), shown)
        val second = model.requests[1].items
        assertEquals(ChatItem.Assistant("Checking.", listOf(ToolCall("c1", "current_time", "{}"))), second[1])
        val result = second[2] as ChatItem.ToolResult
        assertEquals("c1", result.callId)
        val json = JSONObject(result.output)
        assertEquals("2026-11-20", json.getString("localDate"))
        assertEquals("10:30:00", json.getString("localTime"))
        assertEquals("UTC+01:00", json.getString("utcOffset"))
        assertEquals(listOf("current_time"), model.requests[0].tools.map { it.name })
    }

    @Test
    fun `an unknown tool and unusable arguments come back to the model as errors, one result per call`() {
        val model = Script(
            reply("", ToolCall("a", "rm_rf", "{}"), ToolCall("b", "current_time", "[1,2"), ToolCall("c", "current_time", "")),
            reply("Sorry."),
        )
        assertEquals(TurnOutcome("Sorry."), run(loop(model)))
        val results = model.requests[1].items.filterIsInstance<ChatItem.ToolResult>()
        assertEquals(listOf("a", "b", "c"), results.map { it.callId })
        assertEquals(listOf(true, true, false), results.map { it.isError })
        assertEquals(ToolRegistry.UNKNOWN_TOOL, results[0].output)
    }

    @Test
    fun `a model that never stops calling tools is stopped`() {
        val model = Script(*Array(3) { reply("", ToolCall("c$it", "current_time", "{}")) })
        assertEquals(ChatError.TooManySteps, run(loop(model, maxSteps = 3)).error)
        assertEquals(3, model.requests.size)
    }

    @Test
    fun `a failed step ends the turn with its error and what was said so far`() {
        val model = Script(reply("Part one.", ToolCall("c", "current_time", "{}")), { StepOutcome.Failed(ChatError.RateLimited) })
        assertEquals(TurnOutcome("Part one.", ChatError.RateLimited), run(loop(model)))
    }

    @Test
    fun `a reply cut at its token limit says so`() {
        assertEquals(ChatError.CutShort, run(loop(Script(reply("Half", stop = StopReason.Length)))).error)
    }

    @Test
    fun `history goes back as whole turns, and a summary replaces the turns it covers`() {
        val model = Script(reply("ok"))
        val history = listOf(HistoryTurn(0, "u0", "a0"), HistoryTurn(1, "u1", "a1"), HistoryTurn(2, "u2", "a2"))
        run(loop(model), history, Summary("S", through = 2))
        val items = model.requests.single().items
        assertEquals(4, items.size)
        val first = (items[0] as ChatItem.User).text
        assertTrue(first.startsWith(AgentLoop.SUMMARY_PREFIX) && first.contains("S"))
        assertEquals(listOf(ChatItem.User("u2"), ChatItem.Assistant("a2"), ChatItem.User("what time is it?")), items.drop(1))
    }

    @Test
    fun `near the window, older turns are summarised, the recent kept, and the summary handed back to keep`() {
        // 2000 tokens a turn; window 10000, reserve 2500, keep 2500: over at 7500.
        val big = "x".repeat(4000)
        val history = (0 until 4).map { HistoryTurn(it, "u$it $big", "a$it $big") }
        val model = Script(reply("## Goal\nsummary"), reply("answer"))
        val compacted = ArrayList<Summary>()
        val outcome = run(loop(model, ContextLimits(10_000, 1000)), history, compacted = compacted)
        assertEquals(TurnOutcome("answer"), outcome)
        // Everything but the last turn (its 2000 tokens fit the 2500 kept).
        assertEquals(listOf(Summary("## Goal\nsummary", through = 3)), compacted)
        val summaryRequest = model.requests[0]
        assertEquals(AgentLoop.SUMMARIZATION_SYSTEM_PROMPT, summaryRequest.system)
        assertTrue(summaryRequest.tools.isEmpty())
        val prompt = (summaryRequest.items.single() as ChatItem.User).text
        assertTrue(prompt.startsWith("<conversation>\n[User]: u0"))
        assertTrue("u2 " in prompt && "u3 " !in prompt)
        val items = model.requests[1].items
        assertEquals(listOf(ChatItem.User("u3 $big"), ChatItem.Assistant("a3 $big"), ChatItem.User("what time is it?")), items.drop(1))
    }

    @Test
    fun `a summary already kept is updated, not started again`() {
        val big = "x".repeat(4000)
        val history = (0 until 6).map { HistoryTurn(it, "u$it $big", "a$it $big") }
        val model = Script(reply("new"), reply("answer"))
        val compacted = ArrayList<Summary>()
        run(loop(model, ContextLimits(10_000, 1000)), history, Summary("old", 2), compacted)
        assertEquals(listOf(Summary("new", 5)), compacted)
        val prompt = (model.requests[0].items.single() as ChatItem.User).text
        assertTrue("<previous-summary>\nold\n</previous-summary>" in prompt)
        assertTrue(prompt.endsWith(AgentLoop.UPDATE_SUMMARIZATION_PROMPT))
        assertTrue("u1 " !in prompt && "u2 " in prompt && "u5 " !in prompt)
    }

    @Test
    fun `a summary that fails, is cut short or calls a tool leaves the history as it was`() {
        val big = "x".repeat(4000)
        val history = (0 until 4).map { HistoryTurn(it, "u$it $big", "a$it $big") }
        for (bad in listOf<(StepRequest) -> StepOutcome>(
            { StepOutcome.Failed(ChatError.ServerError) },
            reply("half", stop = StopReason.Length),
            reply("", ToolCall("c", "current_time", "{}")),
        )) {
            val model = Script(bad, reply("answer"))
            val compacted = ArrayList<Summary>()
            assertEquals(TurnOutcome("answer"), run(loop(model, ContextLimits(10_000, 1000)), history, compacted = compacted))
            assertTrue(compacted.isEmpty())
            assertEquals(9, model.requests[1].items.size)
        }
    }

    @Test
    fun `cancelling mid-step stops the turn`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val model = ModelStream { _, _ ->
            entered.complete(Unit)
            CompletableDeferred<StepOutcome>().await()
        }
        val job = async { loop(model).run(emptyList(), null, "hi", {}, {}) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `time context is in English with Latin digits and the device zone`() {
        val block = TimeContext.block(clock, ZoneId.of("Asia/Kolkata"))
        assertTrue(block.contains("\"localTime\":\"15:00:00\""))
        assertTrue(block.contains("\"weekday\":\"Friday\""))
        assertTrue(block.contains("\"utcOffset\":\"UTC+05:30\""))
        assertNull(Regex("[^\\x00-\\x7F]").find(block))
    }

    private val badRequest: (StepRequest) -> StepOutcome = { StepOutcome.Failed(ChatError.Refused, noTools = true) }

    @Test
    fun `a model refusing a request with tools is asked again without them, and told`() {
        var told = 0
        val model = Script(badRequest, reply("Hi."), reply("Again."))
        val l = AgentLoop(model, tools, ContextLimits(128_000, 1000), "m", { "system" }, onToolsUnsupported = { told++ })
        assertEquals(TurnOutcome("Hi."), run(l))
        assertEquals(listOf(listOf("current_time"), emptyList()), model.requests.map { r -> r.tools.map { it.name } })
        assertEquals(1, told)
        // The same loop's next step offers none either.
        assertEquals(TurnOutcome("Again."), run(l))
        assertTrue(model.requests[2].tools.isEmpty())
    }

    @Test
    fun `a model known to take no tools is offered none from the start`() {
        val model = Script(reply("Hi."))
        val l = AgentLoop(model, tools, ContextLimits(128_000, 1000), "m", { "system" }, toolsUnsupported = true)
        assertEquals(TurnOutcome("Hi."), run(l))
        assertTrue(model.requests.single().tools.isEmpty())
    }

    @Test
    fun `a 400 after a tool already ran, or one that isn't a bad request, isn't retried`() {
        var told = 0
        val afterTool = Script(reply("", ToolCall("c", "current_time", "{}")), badRequest)
        assertEquals(ChatError.Refused, run(AgentLoop(afterTool, tools, ContextLimits(128_000, 1000), "m", { "system" }, onToolsUnsupported = { told++ })).error)
        assertEquals(2, afterTool.requests.size)
        val refused = Script({ StepOutcome.Failed(ChatError.Refused) })
        assertEquals(ChatError.Refused, run(loop(refused)).error)
        assertEquals(1, refused.requests.size)
        val twice = Script(badRequest, badRequest, reply("ok"))
        assertEquals(ChatError.Refused, run(loop(twice)).error)
        assertEquals(2, twice.requests.size)
        assertEquals(0, told)
    }

    @Test
    fun `the user's Don't allow ends the turn, with no other call of the step and no further step`() {
        // browser_create_tab asks before it opens anything; Declined, it opens nothing.
        val asks = ArrayList<AgentApproval>()
        val registry = ToolRegistry(
            BrowserToolset(
                "chat",
                browser = { NoBrowser },
                ask = { asks += it; ApprovalOutcome.Declined },
                pageScript = { "PAGE" },
            ).tools,
        )
        val calls = (1..16).map { ToolCall("c$it", "browser_create_tab", "{\"url\":\"https://example.com/$it\"}") }.toTypedArray()
        val model = Script(reply("Opening.", *calls), reply("should never be asked"))
        val outcome = runBlocking {
            AgentLoop(model, registry, ContextLimits(128_000, 1000), "m", { "system" }).run(emptyList(), null, "go", {}, {})
        }
        assertEquals(TurnOutcome("Opening.", ChatError.Declined), outcome)
        assertEquals(1, asks.size)
        assertEquals(1, model.requests.size)
    }

    @Test
    fun `a withdrawn approval doesn't end the turn`() {
        val asks = ArrayList<AgentApproval>()
        val registry = ToolRegistry(
            BrowserToolset(
                "chat",
                browser = { NoBrowser },
                ask = { asks += it; ApprovalOutcome.Withdrawn },
                pageScript = { "PAGE" },
            ).tools,
        )
        val calls = (1..2).map { ToolCall("c$it", "browser_create_tab", "{\"url\":\"https://example.com/$it\"}") }.toTypedArray()
        val model = Script(reply("", *calls), reply("It was taken down."))
        val outcome = runBlocking {
            AgentLoop(model, registry, ContextLimits(128_000, 1000), "m", { "system" }).run(emptyList(), null, "go", {}, {})
        }
        assertEquals(TurnOutcome("It was taken down."), outcome)
        assertEquals(2, asks.size)
        assertEquals(2, model.requests.size)
    }

    /** A browser that's there, for a tool that asks before it touches it. */
    private object NoBrowser : AgentBrowserHost {
        override fun tabs() = emptyList<AgentTab>()
        override fun tab(id: Long): AgentTab? = null
        override fun openTab(url: String): Long = error("never opened")
        override fun navigate(id: Long, url: String) = Unit
        override fun close(id: Long) = Unit
        override fun stop(id: Long) = Unit
        override suspend fun evaluate(id: Long, script: String, timeoutMs: Long): String? = null
        override suspend fun bringToFront(id: Long, chat: String?) = false
        override suspend fun tap(id: Long, x: Float, y: Float, proceed: () -> Boolean) = AgentTap.NotTouched
        override fun key(id: Long, keyCode: Int) = false
        override suspend fun commitText(id: Long, text: String) = false
        override fun dialog(id: Long): AgentDialog? = null
        override fun answerDialog(id: Long, dialog: AgentDialog, accept: Boolean, promptText: String?) = false
    }
}
