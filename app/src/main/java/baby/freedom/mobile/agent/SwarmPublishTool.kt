package baby.freedom.mobile.agent

import android.content.Context
import android.util.Log
import baby.freedom.mobile.browser.MIN_PUBLISH_STAMP_BYTES
import baby.freedom.mobile.browser.PostageBatch
import baby.freedom.mobile.browser.PublishOutcome
import baby.freedom.mobile.browser.PublishReport
import baby.freedom.mobile.browser.PublishSource
import baby.freedom.mobile.browser.Publisher
import baby.freedom.mobile.browser.batchHasRoom
import baby.freedom.mobile.browser.batchRemainingBytes
import baby.freedom.mobile.browser.gatewayGet
import baby.freedom.mobile.browser.noStampText
import baby.freedom.mobile.browser.planPublish
import baby.freedom.mobile.browser.publishPageBlockedReason
import baby.freedom.mobile.browser.publishStampEstimate
import baby.freedom.mobile.browser.selectPublishBatch
import baby.freedom.mobile.browser.stampsFrom
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** What the publish card says about postage, read before it's shown; never a purchase. */
internal sealed interface PostagePreview {
    /** [batch] has room for [stampBytes] (with the usual margin); the upload uses exactly it. */
    data class Ready(val batch: PostageBatch, val stampBytes: Long) : PostagePreview {
        val remainingBytes: Long get() = batchRemainingBytes(batch)
    }

    /** The node runs, but no usable stamp has room: [message] says which. */
    data class NoStamp(val message: String) : PostagePreview

    /** The node can't publish now (off, starting, not in light mode): [message]. */
    data class NodeUnavailable(val message: String) : PostagePreview

    /** The node's stamps couldn't be read. */
    data object Unknown : PostagePreview
}

/**
 * One `swarm_publish` in a chat's journal (desktop's
 * `agent-publications.json` record): what the model and the status tool
 * can learn about it. Never the published bytes: those stay in memory
 * only, so a restart can't start an upload again.
 */
internal data class AgentPublication(
    val publicationId: String,
    val sessionId: String,
    val state: String,
    /** The upload was handed to the node: from then on a failure may still have published something. */
    val dispatched: Boolean,
    val bytes: Long,
    val contentType: String,
    val batchId: String?,
    val reference: String? = null,
    val message: String? = null,
    val scan: JSONObject = JSONObject(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
) {
    val terminal: Boolean get() = state != UPLOADING

    /** Desktop's `publicReceipt`. */
    fun receipt(): JSONObject = JSONObject()
        .put("publicationId", publicationId)
        .put("state", state)
        .put(
            "applicationState",
            when (state) {
                COMPLETED -> "applied"
                FAILED -> if (dispatched) "possibly_applied" else "not_applied"
                else -> "possibly_applied"
            },
        )
        .put("kind", "text")
        .put("name", "Text")
        .put("target", "public")
        .put("public", true)
        .put("bytes", bytes)
        .apply {
            reference?.let { put("reference", it).put("bzzUrl", "bzz://$it") }
            message?.let { put("message", it) }
            batchId?.let { put("batchId", it) }
            put("prepublishScan", scan)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("publicationId", publicationId)
        .put("sessionId", sessionId)
        .put("state", state)
        .put("dispatched", dispatched)
        .put("bytes", bytes)
        .put("contentType", contentType)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("scan", scan)
        .apply {
            batchId?.let { put("batchId", it) }
            reference?.let { put("reference", it) }
            message?.let { put("message", it) }
        }

    companion object {
        const val UPLOADING = "uploading"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val OUTCOME_UNKNOWN = "outcome_unknown"

        val ID = Regex("^swarm_pub_[a-f0-9]{24}$")

        fun newId(): String = "swarm_pub_" + UUID.randomUUID().toString().replace("-", "").take(24)

        fun fromJson(o: JSONObject): AgentPublication? = runCatching {
            AgentPublication(
                publicationId = o.getString("publicationId").also { require(ID.matches(it)) },
                sessionId = o.getString("sessionId"),
                state = o.getString("state").also { require(it in setOf(UPLOADING, COMPLETED, FAILED, OUTCOME_UNKNOWN)) },
                dispatched = o.getBoolean("dispatched"),
                bytes = o.getLong("bytes"),
                contentType = o.getString("contentType"),
                batchId = o.optString("batchId").ifEmpty { null },
                reference = o.optString("reference").ifEmpty { null },
                message = o.optString("message").ifEmpty { null },
                scan = o.optJSONObject("scan") ?: JSONObject(),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.optLong("updatedAt", o.getLong("createdAt")),
            )
        }.getOrNull()
    }
}

/** Where the journal lives; a write that returns true is on flash. */
internal interface PublicationJournalStore {
    fun load(): List<AgentPublication>

    fun save(records: List<AgentPublication>): Boolean
}

internal class FilePublicationJournalStore(private val file: File) : PublicationJournalStore {
    override fun load(): List<AgentPublication> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val list = JSONObject(file.readText()).optJSONArray("publications") ?: JSONArray()
            // One record that can't be read back doesn't take the others with it.
            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(AgentPublication::fromJson) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    override fun save(records: List<AgentPublication>): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(JSONObject().put("publications", JSONArray(records.map { it.toJson() })).toString().toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
        // The rename itself survives a power loss only once the directory is synced too.
        runCatching { FileOutputStream(file.parentFile!!).use { it.fd.sync() } }
        true
    } catch (e: Exception) {
        Log.w("AgentPublications", "saving the journal failed (${e.javaClass.simpleName})")
        false
    }
}

/**
 * Freedom Agent's publication journal (#597): one record per approved
 * `swarm_publish`, written before the upload starts and updated from the
 * publisher's own report, so a turn that's stopped (or a chat page
 * that's closed) doesn't stop the record from learning the outcome. At
 * most [MAX_RECORDS], finished ones dropped first. On the first read
 * after a start, a record still uploading is one the app's end
 * interrupted: `outcome_unknown` once the upload was handed to the node
 * (it may be published), `failed` before. Nothing is ever retried.
 */
internal class AgentPublications(
    private val store: PublicationJournalStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _records = MutableStateFlow<Map<String, AgentPublication>>(emptyMap())
    val records: StateFlow<Map<String, AgentPublication>> = _records.asStateFlow()

    private var loaded = false
    private val lock = Any()

    /** Reads the journal once; blocking, so call it off the main thread. */
    fun ensureLoaded() = synchronized(lock) {
        if (loaded) return@synchronized
        val now = clock()
        val recovered = store.load().map { r ->
            if (r.state != AgentPublication.UPLOADING) {
                r
            } else if (r.dispatched) {
                r.copy(state = AgentPublication.OUTCOME_UNKNOWN, message = INTERRUPTED_SENT, updatedAt = now)
            } else {
                r.copy(state = AgentPublication.FAILED, message = INTERRUPTED_UNSENT, updatedAt = now)
            }
        }
        _records.value = recovered.associateBy { it.publicationId }
        loaded = true
        if (recovered.any { it.updatedAt == now }) store.save(_records.value.values.toList())
    }

    /** Records [record] before anything is uploaded; false if that couldn't be written, and then nothing may upload. */
    fun begin(record: AgentPublication): Boolean = synchronized(lock) {
        ensureLoaded()
        val next = pruned(_records.value + (record.publicationId to record))
        if (!store.save(next.values.toList())) return@synchronized false
        _records.value = next
        true
    }

    fun update(id: String, change: (AgentPublication) -> AgentPublication) = synchronized(lock) {
        val current = _records.value[id] ?: return@synchronized
        val next = _records.value + (id to change(current).copy(updatedAt = clock()))
        _records.value = next
        store.save(next.values.toList())
    }

    fun get(sessionId: String, id: String): AgentPublication? {
        ensureLoaded()
        return _records.value[id]?.takeIf { it.sessionId == sessionId }
    }

    /** The chat's most recent publications, newest first. */
    fun forSession(sessionId: String, max: Int = 20): List<AgentPublication> {
        ensureLoaded()
        return _records.value.values.filter { it.sessionId == sessionId }.sortedByDescending { it.createdAt }.take(max)
    }

    /** Waits up to [timeoutMs] for [id] to finish; the record as it then is. */
    suspend fun await(id: String, timeoutMs: Long): AgentPublication? {
        withTimeoutOrNull(timeoutMs) { records.first { it[id]?.terminal != false } }
        return _records.value[id]
    }

    /** A deleted chat's receipts go with it. */
    fun deleteSession(sessionId: String) = synchronized(lock) {
        ensureLoaded()
        if (_records.value.values.none { it.sessionId == sessionId }) return@synchronized
        _records.value = _records.value.filterValues { it.sessionId != sessionId }
        store.save(_records.value.values.toList())
    }

    fun deleteAll() = synchronized(lock) {
        ensureLoaded()
        if (_records.value.isEmpty()) return@synchronized
        _records.value = emptyMap()
        store.save(emptyList())
    }

    /** Finished records go first; one still uploading is never dropped to make room. */
    private fun pruned(all: Map<String, AgentPublication>): Map<String, AgentPublication> {
        if (all.size <= MAX_RECORDS) return all
        val drop = all.values.filter { it.terminal }.sortedBy { it.createdAt }.take(all.size - MAX_RECORDS).map { it.publicationId }.toSet()
        return all.filterKeys { it !in drop }
    }

    companion object {
        const val MAX_RECORDS = 500
        const val INTERRUPTED_SENT = "Freedom stopped while this was uploading. It may be published: check it before publishing again."
        const val INTERRUPTED_UNSENT = "Freedom stopped before uploading. Nothing was sent."

        @Volatile private var instance: AgentPublications? = null

        /** Off the caller's thread: deleting a chat may run on the main one. */
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        fun get(context: Context): AgentPublications = instance ?: synchronized(this) {
            instance ?: AgentPublications(
                FilePublicationJournalStore(File(context.applicationContext.filesDir, "agent-publications.json")),
            ).also { instance = it }
        }

        fun forgetSession(context: Context, sessionId: String) {
            val journal = get(context)
            io.launch { journal.deleteSession(sessionId) }
        }

        fun forgetAll(context: Context) {
            val journal = get(context)
            io.launch { journal.deleteAll() }
        }
    }
}

/** What publishing needs from the node; the real one is [NodePublishBackend], tests give a fake. */
internal interface PublishBackend {
    /** The stamp the card would show for [stampBytes]. */
    suspend fun postage(stampBytes: Long): PostagePreview

    /** [batchId] as the node reports it now, if it's still usable with room for [stampBytes]. */
    suspend fun batchNow(batchId: String, stampBytes: Long): PostageBatch?

    /** Starts the upload; false if a publish or stamp work that may restart the gateway is running. */
    fun start(source: PublishSource.Frozen, batch: PostageBatch, report: PublishReport): Boolean
}

internal class NodePublishBackend(private val app: Context) : PublishBackend {
    private suspend fun batches(): List<PostageBatch>? = gatewayGet("/stamps", STAMPS_TIMEOUT_MS)?.let(::stampsFrom)

    override suspend fun postage(stampBytes: Long): PostagePreview {
        val node = withContext(Dispatchers.Main) { AgentNodes.source?.invoke() }?.swarm ?: return PostagePreview.Unknown
        publishPageBlockedReason(node)?.let { return PostagePreview.NodeUnavailable(it) }
        val batches = batches() ?: return PostagePreview.Unknown
        val need = maxOf(stampBytes, MIN_PUBLISH_STAMP_BYTES)
        return selectPublishBatch(batches, need)?.let { PostagePreview.Ready(it, need) }
            ?: PostagePreview.NoStamp(noStampText(batches, need) ?: "")
    }

    override suspend fun batchNow(batchId: String, stampBytes: Long): PostageBatch? =
        batches()?.firstOrNull { it.id == batchId && it.usable && (it.ttlSeconds ?: 1) > 0 && batchHasRoom(it, stampBytes) }

    override fun start(source: PublishSource.Frozen, batch: PostageBatch, report: PublishReport): Boolean =
        Publisher.start(app, planPublish(app.contentResolver, source), batch, report)

    private companion object {
        const val STAMPS_TIMEOUT_MS = 15_000
    }
}

/**
 * Desktop's `swarm_publish` and `swarm_publication_status` for a chat
 * (#597), for text: the agent's text is frozen as bytes once, scanned
 * ([PublicationScan]) and shown on an approval card with what was found
 * and the stamp it would use; only the user's own tap publishes, and
 * only those bytes, with that stamp. A secret blocks until the user ticks
 * it to publish anyway, which is checked here again, not left to the
 * card. Nothing is bought, and an upload whose outcome isn't known is
 * never sent again.
 */
internal class SwarmPublishTools(
    private val sessionId: String,
    private val journal: () -> AgentPublications,
    private val backend: () -> PublishBackend,
    private val ask: suspend (AgentApproval) -> ApprovalOutcome = AgentApprovals::ask,
    private val clock: () -> Long = System::currentTimeMillis,
    private val interactiveWaitMs: Long = INTERACTIVE_WAIT_MS,
    private val statusWaitMs: Long = STATUS_WAIT_MS,
) {
    @Volatile private var preparing = false

    val tools: List<AgentTool> = listOf(
        tool(SPEC_PUBLISH) { publish(it) },
        tool(SPEC_STATUS) { status(it) },
    )

    private fun tool(spec: ToolSpec, run: suspend (JSONObject) -> ToolOutcome) = object : AgentTool {
        override val spec = spec

        override suspend fun run(arguments: JSONObject): ToolOutcome = try {
            withContext(Dispatchers.IO) { run(arguments) }
        } catch (e: ToolError) {
            e.outcome(spec.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolError(ToolError.INTERNAL, "The Swarm publication failed unexpectedly. Nothing was uploaded.").outcome(spec.name)
        }
    }

    private fun bad(message: String): Nothing = throw ToolError(ToolError.INVALID_ARGUMENT, message)

    private fun ok(result: JSONObject) = ToolOutcome(JSONObject().put("ok", true).put("result", result).toString())

    private suspend fun publish(a: JSONObject): ToolOutcome {
        val unsupported = a.keys().asSequence().filter { it !in setOf("text", "contentType") }.toList()
        if (unsupported.isNotEmpty()) {
            bad(
                "Only text can be published from Freedom on Android: ${unsupported.joinToString(", ")} " +
                    "is not available here (no attachments, private draft links or stable addresses yet).",
            )
        }
        val text = a.opt("text")
        if (text !is String || text.isEmpty()) bad("Swarm publication requires text")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) bad("text cannot exceed $MAX_TEXT_BYTES UTF-8 bytes")
        val contentType = when (val t = a.opt("contentType")) {
            null, JSONObject.NULL -> DEFAULT_CONTENT_TYPE
            is String -> t.trim().also {
                if (it.isEmpty() || it.length > 255 || it.any { c -> c.code < 0x20 || c.code == 0x7f }) {
                    bad("contentType must be a bounded media type")
                }
            }
            else -> bad("contentType must be a string")
        }

        val journal = journal()
        journal.ensureLoaded()
        // One upload at a time per chat: an earlier one still going is reported, not repeated.
        journal.forSession(sessionId).firstOrNull { !it.terminal }?.let { pending ->
            return ok(
                JSONObject().put("publication", pending.receipt()).put(
                    "note",
                    "An earlier publication in this chat is still uploading. Nothing new was started. Check it with " +
                        "swarm_publication_status; do not publish again while it may still apply.",
                ),
            )
        }
        if (preparing) {
            throw ToolError(
                ToolError.CAPABILITY_UNAVAILABLE,
                "A publication is already waiting for the user's approval in this chat. Do not submit another one.",
            )
        }
        preparing = true
        try {
            return prepareAndPublish(journal, bytes, contentType)
        } finally {
            preparing = false
        }
    }

    private suspend fun prepareAndPublish(journal: AgentPublications, raw: ByteArray, contentType: String): ToolOutcome {
        // Frozen once: the scan, the card and the upload all read these bytes.
        val frozen = PublishSource.Frozen(raw, Publisher.TEXT_FILE_NAME, contentType)
        val snapshot = frozen.bytes()
        val findings = try {
            withContext(Dispatchers.Default) { PublicationScan.scanPublication("Text", snapshot, contentType) }
        } catch (e: PublicationScan.TooManyFindings) {
            bad(
                "The pre-publish check found ${e.count} possible secrets, too many to review one by one. " +
                    "Remove them and publish again. Nothing was uploaded.",
            )
        }
        val stampBytes = publishStampEstimate(listOf(frozen.size.toLong()))
        val backend = backend()
        val postage = backend.postage(stampBytes)
        val action = AgentAction.Publish(
            text = String(snapshot, Charsets.UTF_8),
            bytes = frozen.size,
            contentType = contentType,
            findings = findings,
            postage = postage,
        )
        val approval = AgentApproval(
            id = AgentApprovals.nextId(),
            sessionId = sessionId,
            callId = UUID.randomUUID().toString(),
            tabId = NO_TAB,
            site = "",
            document = 0,
            action = action,
            intent = null,
        )
        val acknowledged = when (val answer = ask(approval)) {
            is ApprovalOutcome.Publish -> answer.acknowledged
            ApprovalOutcome.AskToFix -> {
                val lines = findings.take(40).joinToString("; ") { f -> "Text: ${f.rule}" + (f.line?.let { " (line $it)" } ?: "") }
                throw ToolError(
                    ToolError.PUBLICATION_FIX_REQUESTED,
                    "The user asked you to fix pre-publish scan findings before publishing. Nothing was uploaded. Findings: $lines.",
                )
            }
            ApprovalOutcome.Declined, ApprovalOutcome.Approved -> throw ToolError(
                ToolError.USER_DECLINED,
                "The user declined the Swarm publication. Nothing was uploaded.",
                declined = answer == ApprovalOutcome.Declined,
            )
            ApprovalOutcome.Withdrawn -> throw ToolError(
                ToolError.APPROVAL_REQUIRED,
                "The approval was taken down before the user answered (the turn was stopped or the chat deleted). Nothing was uploaded.",
            )
        }
        // The card enables its button only once every secret is ticked; this holds it either way.
        val unresolved = findings.filter { it.blocking && it.id !in acknowledged }
        if (unresolved.isNotEmpty()) {
            throw ToolError(
                ToolError.POLICY_DENIED,
                "Publication blocked: ${unresolved.size} pre-publish finding(s) (${unresolved.map { it.rule }.distinct().joinToString(", ")}) " +
                    "were not resolved. Nothing was uploaded.",
            )
        }
        val shown = postage as? PostagePreview.Ready ?: throw ToolError(
            ToolError.CAPABILITY_UNAVAILABLE,
            "There is no usable postage stamp to publish with. Nothing was uploaded. Tell the user to check the Swarm node " +
                "and its stamps (Nodes → Swarm → Stamps); never buy one for them.",
        )
        // The stamp the card showed is the one used, or none: never another one picked behind the user's back.
        val batch = backend.batchNow(shown.batch.id, shown.stampBytes) ?: throw ToolError(
            ToolError.CAPABILITY_UNAVAILABLE,
            "The postage stamp the user approved is no longer usable or has no room left. Nothing was uploaded.",
        )
        val record = AgentPublication(
            publicationId = AgentPublication.newId(),
            sessionId = sessionId,
            state = AgentPublication.UPLOADING,
            dispatched = false,
            bytes = frozen.size.toLong(),
            contentType = contentType,
            batchId = batch.id,
            scan = PublicationScan.summary(findings),
            createdAt = clock(),
        )
        if (!journal.begin(record)) {
            throw ToolError(
                ToolError.CAPABILITY_UNAVAILABLE,
                "Freedom couldn't record the publication, so nothing was uploaded. The phone may be out of storage.",
            )
        }
        val id = record.publicationId
        val started = backend.start(frozen, batch, object : PublishReport {
            override fun dispatched() = journal.update(id) { it.copy(dispatched = true) }

            override fun finished(outcome: PublishOutcome) = journal.update(id) { r ->
                when (outcome) {
                    is PublishOutcome.Completed -> r.copy(state = AgentPublication.COMPLETED, reference = outcome.reference, message = null)
                    is PublishOutcome.NotSent -> r.copy(state = AgentPublication.FAILED, dispatched = false, message = outcome.message)
                    is PublishOutcome.Refused -> r.copy(state = AgentPublication.FAILED, message = outcome.message)
                    is PublishOutcome.Unknown -> r.copy(state = AgentPublication.OUTCOME_UNKNOWN, message = outcome.message)
                }
            }
        })
        if (!started) {
            journal.update(id) {
                it.copy(state = AgentPublication.FAILED, message = "Another publish, or stamp work on the node, is running. Nothing was sent.")
            }
            throw ToolError(
                ToolError.CAPABILITY_UNAVAILABLE,
                "Another publish, or stamp work on the Swarm node, is running. Nothing was uploaded; try again once it has finished.",
            )
        }
        val now = journal.await(id, interactiveWaitMs) ?: record
        return ok(JSONObject().put("publication", now.receipt()).apply { stillGoing(now)?.let { put("note", it) } })
    }

    private suspend fun status(a: JSONObject): ToolOutcome {
        val journal = journal()
        val id = a.opt("publicationId")
        if (id == null || id == JSONObject.NULL) {
            val list = journal.forSession(sessionId).map { it.receipt() }
            return ok(JSONObject().put("publications", JSONArray(list)))
        }
        if (id !is String || !AgentPublication.ID.matches(id.trim())) bad("publicationId must be a Freedom Swarm publication ID")
        val record = journal.get(sessionId, id.trim()) ?: throw ToolError(
            ToolError.CAPABILITY_UNAVAILABLE,
            "That Swarm publication is not available in this chat.",
        )
        val now = if (record.terminal) record else journal.await(record.publicationId, statusWaitMs) ?: record
        return ok(JSONObject().put("publication", now.receipt()).apply { stillGoing(now)?.let { put("note", it) } })
    }

    private fun stillGoing(r: AgentPublication): String? = when (r.state) {
        AgentPublication.UPLOADING ->
            "Still uploading. Keep the publicationId and check it with swarm_publication_status; do not publish again."
        AgentPublication.OUTCOME_UNKNOWN ->
            "The outcome is unknown: the content may be published. Do not claim success or failure and do not publish " +
                "it again without asking the user."
        else -> null
    }

    companion object {
        const val PUBLISH = "swarm_publish"
        const val STATUS = "swarm_publication_status"
        const val MAX_TEXT_BYTES = 262_144
        const val DEFAULT_CONTENT_TYPE = "text/plain; charset=utf-8"
        const val NO_TAB = -1L
        const val INTERACTIVE_WAIT_MS = 30_000L
        const val STATUS_WAIT_MS = 10_000L

        val SPEC_PUBLISH = ToolSpec(
            name = PUBLISH,
            description = "Publish bounded inline text to the public Swarm network through Freedom. Inline text is data, " +
                "not a named file. The content is public, unencrypted and permanent. Freedom scans it for secrets and " +
                "personal data and always asks the user before uploading, separately for each publication; it uses an " +
                "existing postage stamp and never buys one. If the result is still uploading, keep its publicationId and " +
                "use swarm_publication_status; never blindly repeat a possibly applied publication.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "text",
                            JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_TEXT_BYTES)
                                .put("description", "Text content to publish as text data. Do not invent a filename."),
                        )
                        .put(
                            "contentType",
                            JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 255)
                                .put("description", "Optional media type for text content."),
                        ),
                )
                .put("required", JSONArray().put("text"))
                .put("additionalProperties", false),
        )

        val SPEC_STATUS = ToolSpec(
            name = STATUS,
            description = "Check a Swarm publication by its Freedom publication ID. Omit publicationId after an " +
                "interrupted model run to list this chat's recent publications. Use this to reconcile uploading or " +
                "outcome_unknown work before considering another publish.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put("publicationId", JSONObject().put("type", "string").put("pattern", "^swarm_pub_[a-f0-9]{24}$")),
                )
                .put("additionalProperties", false),
        )
    }
}
