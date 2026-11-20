package baby.freedom.mobile.agent

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONObject

/**
 * What a tool returned: [output] goes back to the model as the call's
 * result. [declined]: the user said no on an approval card; the turn
 * ends there ([AgentLoop]), so nothing else is asked of them in it.
 */
internal data class ToolOutcome(val output: String, val isError: Boolean = false, val declined: Boolean = false)

/** A tool the agent can run. [run] gets the call's arguments as an object. */
internal interface AgentTool {
    val spec: ToolSpec

    suspend fun run(arguments: JSONObject): ToolOutcome
}

/**
 * The tools a chat offers the model (#574): the harmless
 * [CurrentTimeTool], the browser tools ([BrowserToolset], #575),
 * whose interactions ask the user as the chat's approval mode says
 * (#595), the read-only [NodeStatusTool] (#577), [WalletTransferTool]
 * (#596), whose every transfer goes to the user on its own review sheet
 * whatever the mode, and the name tools ([DomainToolset], #599), whose
 * every transaction does too.
 */
internal class ToolRegistry(tools: List<AgentTool>) {
    private val byName = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> = tools.map { it.spec }

    /**
     * Runs [call], always to one result: a tool the model made up or
     * arguments that aren't a JSON object come back to it as an error it
     * can correct, never as a failed turn.
     */
    suspend fun run(call: ToolCall): ToolOutcome {
        val tool = byName[call.name] ?: return ToolOutcome(UNKNOWN_TOOL, isError = true)
        if (call.arguments.length > AgentWire.MAX_ARGUMENT_CHARS) return ToolOutcome(BAD_ARGUMENTS, isError = true)
        val args = AgentWire.parseObject(call.arguments.ifBlank { "{}" }) ?: return ToolOutcome(BAD_ARGUMENTS, isError = true)
        val outcome = tool.run(args)
        return if (outcome.output.length > MAX_OUTPUT_CHARS) {
            outcome.copy(output = outcome.output.take(MAX_OUTPUT_CHARS) + "\n[truncated]")
        } else {
            outcome
        }
    }

    companion object {
        /** A tool result past this is cut, so one tool can't fill the context. */
        const val MAX_OUTPUT_CHARS = 64 * 1024

        // What the model reads back; English, like the rest of what it's told.
        const val UNKNOWN_TOOL = "Error: no tool by that name exists in this session."
        const val BAD_ARGUMENTS = "Error: the arguments must be a JSON object that follows the tool's schema."

        fun default(clock: () -> Instant = Instant::now, zone: () -> ZoneId = ZoneId::systemDefault) =
            ToolRegistry(listOf(CurrentTimeTool(clock, zone)))

        /**
         * A chat's tools: the clock, its own browser tools (#575), under
         * the chat's [approvals] (#595), the read-only node status (#577),
         * wallet transfers (#596), the name tools (#599) and, when the app
         * ships any, `read` for its built-in [skills].
         */
        fun forChat(sessionId: String, approvals: ChatApprovals = ChatApprovals.AskEveryTime, skills: AgentSkills? = null) = ToolRegistry(
            listOf(CurrentTimeTool(Instant::now, ZoneId::systemDefault)) +
                BrowserToolset(sessionId, approvals = approvals).tools + NodeStatusTool() + WalletTransferTool(sessionId) +
                DomainToolset(sessionId).tools + listOfNotNull(skills?.takeUnless { it.isEmpty }?.let(::SkillReadTool)),
        )
    }
}

/** `current_time`: the device clock, the same block the system prompt carries. */
internal class CurrentTimeTool(
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) : AgentTool {
    override val spec = ToolSpec(
        name = "current_time",
        description = "Read the current date, time and timezone from the device clock.",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false),
    )

    override suspend fun run(arguments: JSONObject) = ToolOutcome(TimeContext.json(clock(), zone()).toString())
}

/**
 * Desktop's time-context block (`currentTimeContext`), refreshed for
 * every model request: the local date, weekday, time, timezone and UTC
 * offset, in English with Latin digits whatever the phone's language,
 * since it's for the model.
 */
internal object TimeContext {
    fun json(now: Instant, zone: ZoneId): JSONObject {
        val local = ZonedDateTime.ofInstant(now, zone)
        val offset = local.offset.id.let { if (it == "Z") "+00:00" else it }
        return JSONObject()
            .put("localDate", local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)))
            .put("weekday", local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            .put("localTime", local.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)))
            .put("timeZone", zone.id)
            .put("utcOffset", "UTC$offset")
            .put("utc", DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
    }

    fun block(now: Instant, zone: ZoneId): String =
        "Current time from Freedom's device clock (refreshed for this model request): ${json(now, zone)}\n" +
            "Resolve \"today\", \"tomorrow\" and other relative dates in the current request using this local " +
            "date and timezone, unless the user specifies another timezone or historical context. This current " +
            "clock supersedes older clock snapshots in the conversation. A timezone does not establish the user's " +
            "physical location, language, or departure airport. It is a device-clock snapshot, not independently " +
            "verified network time."
}
