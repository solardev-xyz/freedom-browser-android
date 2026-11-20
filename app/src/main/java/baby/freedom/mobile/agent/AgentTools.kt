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
internal data class ToolOutcome(
    val output: String,
    val isError: Boolean = false,
    val declined: Boolean = false,
    /** Images for the model (#601), next to [output]; only from a tool run for a model that takes them. */
    val images: List<ImagePart> = emptyList(),
)

/** A tool the agent can run. [run] gets the call's arguments as an object. */
internal interface AgentTool {
    val spec: ToolSpec

    /** Offered only to a model that takes images (#601, desktop's `attachment_render_page`). */
    val needsImages: Boolean get() = false

    suspend fun run(arguments: JSONObject): ToolOutcome
}

/**
 * What the model of the turn running a tool takes (#601): set by
 * [ToolRegistry.run] for the call, read by a tool whose answer depends
 * on it (an image attachment is shown only to a model that takes images).
 */
internal class ModelInput(val images: Boolean) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ModelInput>
}

/** Whether the model a tool is running for takes images ([ModelInput]); false outside a turn. */
internal suspend fun modelTakesImages(): Boolean = kotlin.coroutines.coroutineContext[ModelInput]?.images == true

/**
 * The tools a chat offers the model (#574): the harmless
 * [CurrentTimeTool], the browser tools ([BrowserToolset], #575),
 * whose interactions ask the user as the chat's approval mode says
 * (#595), the read-only [NodeStatusTool] (#577), [WalletTransferTool]
 * (#596), whose every transfer goes to the user on its own review sheet
 * whatever the mode, [SwarmPublishTools] (#597), whose every
 * publication does too, the node tools (#598): requests, their
 * receipts, start and stop, and diagnostics, each asking on its own
 * sheet as [NodeRequestTool], [NodeLifecycleTool] and
 * [NodeDiagnosticsTool] say, the name tools ([DomainToolset], #599),
 * whose every transaction does too, and the files attached to the chat
 * ([AttachmentTools], #601).
 */
internal class ToolRegistry(tools: List<AgentTool>) {
    private val byName = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> = tools.map { it.spec }

    /** The tools offered to a model: those for images ([AgentTool.needsImages]) only if it takes them. */
    fun specsFor(images: Boolean): List<ToolSpec> = if (images) specs else specs.filter { byName[it.name]?.needsImages != true }

    /**
     * Runs [call], always to one result: a tool the model made up or
     * arguments that aren't a JSON object come back to it as an error it
     * can correct, never as a failed turn.
     */
    suspend fun run(call: ToolCall, images: Boolean = false): ToolOutcome {
        val tool = byName[call.name]?.takeUnless { it.needsImages && !images } ?: return ToolOutcome(UNKNOWN_TOOL, isError = true)
        if (call.arguments.length > AgentWire.MAX_ARGUMENT_CHARS) return ToolOutcome(BAD_ARGUMENTS, isError = true)
        val args = AgentWire.parseObject(call.arguments.ifBlank { "{}" }) ?: return ToolOutcome(BAD_ARGUMENTS, isError = true)
        val outcome = kotlinx.coroutines.withContext(ModelInput(images)) { tool.run(args) }.let {
            // Never to a model that doesn't take them, whatever a tool returned.
            if (images || it.images.isEmpty()) it else it.copy(images = emptyList())
        }
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
         * wallet transfers (#596), Swarm publishing (#597), the node
         * tools (#598), whose receipts are kept in [operations], the name
         * tools (#599), when the app ships any, `read` for its built-in
         * [skills], and the chat's attachments (#601).
         */
        fun forChat(
            sessionId: String,
            approvals: ChatApprovals = ChatApprovals.AskEveryTime,
            operations: NodeOperations = NodeOperations(MemoryNodeOperationStore()),
            skills: AgentSkills? = null,
            app: () -> android.content.Context,
        ): ToolRegistry {
            val nodes = NodeToolDeps.app(sessionId, approvals)
            return ToolRegistry(
                listOf(CurrentTimeTool(Instant::now, ZoneId::systemDefault)) +
                    BrowserToolset(sessionId, approvals = approvals).tools + NodeStatusTool() + WalletTransferTool(sessionId) +
                    SwarmPublishTools(sessionId, { AgentPublications.get(app()) }, { NodePublishBackend(app()) }).tools +
                    NodeRequestTool(nodes, operations) + NodeOperationStatusTool(sessionId, operations) +
                    NodeLifecycleTool(nodes) + NodeDiagnosticsTool(nodes) +
                    DomainToolset(sessionId).tools + listOfNotNull(skills?.takeUnless { it.isEmpty }?.let(::SkillReadTool)) +
                    AttachmentTools(sessionId, { AgentAttachments.get(app()) }, { AgentPdf.get(app()) }).tools,
            )
        }
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
