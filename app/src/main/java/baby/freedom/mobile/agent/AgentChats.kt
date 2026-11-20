package baby.freedom.mobile.agent

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Freedom Agent's chats (#574): sends a message, runs its turn through
 * the [AgentLoop] and keeps the history ([ChatDao]). Process-wide, so a
 * reply keeps coming while the chat page is closed and a reopened page
 * picks it up; only the user's Stop, deleting the chat, turning AI off or
 * changing the provider's key ends a turn early.
 *
 * A session runs one turn at a time. It counts as running ([live]) until
 * its turn's end is written, so Stop followed at once by Send can't start
 * a second turn over the first one's wind-down.
 */
internal class AgentChats(
    private val dao: ChatDao,
    private val providers: AgentProviders,
    /** The system prompt with `{{identity}}` still in it. */
    private val promptTemplate: String,
    /** A chat's tools, by session: built once per chat, so its browser tools keep their tabs and references. */
    private val toolsFor: (String) -> ToolRegistry = { ToolRegistry.default() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** A deleted chat's node operation receipts go with it (#598); null: every chat's. */
    private val forgetNodeOperations: suspend (String?) -> Unit = {},
    private val limitsOf: (ProviderDefinition) -> ContextLimits = ContextLimits::of,
    /** A deleted chat's publication receipts go with it (#597); null: every chat's. */
    private val forgetPublications: (String?) -> Unit = {},
    /** The files attached to chats (#601); null: none can be. */
    private val attachments: AgentAttachments? = null,
    /** Whether a model takes images (#601): it's sent the message's images, and offered `attachment_render_page`. */
    private val takesImages: suspend (ModelRef) -> Boolean = { false },
) {
    /** A turn in progress: its reply so far. */
    data class Live(val sessionId: String, val turnId: String, val text: String)

    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())

    /** Running turns by session. */
    val live: StateFlow<Map<String, Live>> = _live.asStateFlow()

    private val lock = Mutex()
    private val jobs = HashMap<String, Job>()

    /**
     * Models (by connection, revision and id) seen to refuse a request
     * that offers tools and take it without: later turns offer them none
     * from the start, rather than paying a refused request each time.
     * Only in memory, so a server that gains tool support is tried again.
     */
    private val noTools: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val registries = java.util.concurrent.ConcurrentHashMap<String, ToolRegistry>()

    /** Each chat's approval mode (#595), as last read from or written to its session. */
    private val modes = java.util.concurrent.ConcurrentHashMap<String, ApprovalMode>()

    /** A running turn's classifier: the turn's own model, and the request it's for. */
    private class TurnCheck(val run: ClassifierRun, val userText: String, val provider: String)

    private val checks = java.util.concurrent.ConcurrentHashMap<String, TurnCheck>()

    /** [sessionId]'s approval mode, the strictest until its session is read. */
    fun modeOf(sessionId: String): ApprovalMode = modes[sessionId] ?: ApprovalMode.DEFAULT

    /**
     * [sessionId]'s approval mode and classifier, for its browser tools:
     * the mode as it is when an action is decided, the classifier on the
     * model of the turn that's running (none running: uncertain, so it asks).
     */
    fun approvalsOf(sessionId: String): ChatApprovals = object : ChatApprovals {
        override val mode: ApprovalMode get() = modeOf(sessionId)

        override suspend fun classifyInteraction(input: InteractionInput): InteractionClassification {
            val check = checks[sessionId] ?: return InteractionIntentClassifier.uncertain(ClassifierReason.UNAVAILABLE)
            return InteractionIntentClassifier.classify(check.run, input, check.userText)
        }

        override suspend fun classifyEffect(domain: String, action: JSONObject, trusted: JSONObject?): EffectClassification =
            EffectClassifier.classify(checks[sessionId]?.run, domain, action, trusted)

        override val provider: String? get() = checks[sessionId]?.provider
    }

    /**
     * Sets [sessionId]'s approval mode; it applies from the next action
     * the agent takes, in a turn already running too. False if it
     * couldn't be saved (the mode stays as it was).
     */
    suspend fun setMode(sessionId: String, mode: ApprovalMode): Boolean = withContext(NonCancellable) {
        try {
            if (dao.setApprovalMode(sessionId, mode.wire) == 0) return@withContext false
            modes[sessionId] = mode
            true
        } catch (e: Exception) {
            Log.w(TAG, "saving a chat's approval mode failed (${e.javaClass.simpleName})")
            false
        }
    }

    private fun toolsOf(sessionId: String): ToolRegistry = registries.getOrPut(sessionId) { toolsFor(sessionId) }

    /** Set once turns a stopped app left running are marked interrupted: nothing starts before. */
    private val swept = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                dao.markRunningInterrupted()
            } catch (e: Exception) {
                Log.w(TAG, "sweeping running chat turns failed (${e.javaClass.simpleName})")
            }
            try {
                // Files of a chat deleted while they couldn't be, or of a
                // message whose save the app's stop cut off (#601). Before
                // any send: one under way would look like an orphan.
                attachments?.sweep { dao.sessionIds().toSet() }
            } catch (e: Exception) {
                Log.w(TAG, "sweeping chat attachments failed (${e.javaClass.simpleName})")
            } finally {
                swept.complete(Unit)
            }
        }
    }

    val sessions: Flow<List<ChatSession>> get() = dao.sessions()

    fun session(id: String): Flow<ChatSession?> = dao.sessionFlow(id)

    fun turns(sessionId: String): Flow<List<ChatTurn>> = dao.turnsFlow(sessionId)

    /**
     * Sends [text] with [model] in session [sessionId] (a new chat when
     * null, under approval [mode]; an existing chat keeps its own, set
     * with [setMode]) and starts its turn, with the staged files
     * [attachmentIds] (#601), which move into the chat. Returns the
     * session's id, or null when nothing was sent: the session is still
     * running a turn, the text is empty or too long, a file is no longer
     * staged, or it couldn't be saved (then the files are staged again).
     */
    suspend fun send(
        sessionId: String?,
        text: String,
        model: ModelRef,
        mode: ApprovalMode = ApprovalMode.DEFAULT,
        attachmentIds: List<String> = emptyList(),
    ): String? {
        val message = text.trim()
        if (message.isEmpty() || message.length > MAX_MESSAGE_CHARS) return null
        if (attachmentIds.size > AttachmentRules.MAX_SELECTIONS) return null
        swept.await()
        return lock.withLock {
            if (sessionId != null && sessionId in jobs) return@withLock null
            val now = clock()
            val id = sessionId ?: UUID.randomUUID().toString()
            val files = if (attachmentIds.isEmpty()) {
                emptyList()
            } else {
                val store = attachments ?: return@withLock null
                withContext(NonCancellable) { store.consume(id, attachmentIds) } ?: return@withLock null
            }
            val turn = ChatTurn(
                id = UUID.randomUUID().toString(),
                sessionId = id,
                position = 0,
                userText = message,
                status = ChatStatus.RUNNING,
                startedAt = now,
                attachments = files.takeIf { it.isNotEmpty() }?.let(Attachment::listJson),
            )
            val saved = try {
                withContext(NonCancellable) {
                    if (sessionId == null) {
                        dao.start(
                            ChatSession(id, titleOf(message), model.connectionId, model.modelId, now, now, approvalMode = mode.wire),
                            turn,
                        )
                        modes[id] = mode
                        turn
                    } else {
                        dao.session(sessionId) ?: return@withContext null
                        turn.copy(position = dao.nextPosition(sessionId)).also {
                            dao.insertTurn(it)
                            dao.touchSession(sessionId, model.connectionId, model.modelId, now)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "saving a chat message failed (${e.javaClass.simpleName})")
                null
            }
            if (saved == null) {
                // Back under the composer, for the next try.
                if (files.isNotEmpty()) {
                    withContext(NonCancellable) {
                        attachments?.unconsume(id, files)
                        attachments?.settled(id)
                    }
                }
                return@withLock null
            }
            // Committed: the sweep may judge its folder by the chat list again.
            if (files.isNotEmpty()) withContext(NonCancellable) { attachments?.settled(id) }
            _live.update { it + (id to Live(id, saved.id, "")) }
            // Started only once it's in [jobs], so its own wind-down finds itself there.
            val job = scope.launch(start = CoroutineStart.LAZY) { runTurn(saved, model) }
            jobs[id] = job
            job.start()
            id
        }
    }

    private suspend fun runTurn(turn: ChatTurn, model: ModelRef) {
        val started = clock()
        // The loop's buffer: read (copied) only when shown or saved.
        var latest: CharSequence = ""
        var lastShown = 0L
        var lastSaved = started
        var outcome: TurnOutcome? = null
        var check: TurnCheck? = null
        try {
            outcome = when (val target = providers.chatTarget(model)) {
                is ChatTarget.Unavailable -> TurnOutcome("", target.error)
                is ChatTarget.Ready -> {
                    val session = dao.session(turn.sessionId)
                    // Only if not set since: a mode picked while the row was read wins.
                    session?.let { modes.putIfAbsent(turn.sessionId, ApprovalMode.of(it.approvalMode)) }
                    val stream = ModelStream { request, onText -> providers.streamStep(target, request, onText) }
                    check = TurnCheck(ClassifierRun(stream, model.modelId), turn.userText, target.connection.name).also { checks[turn.sessionId] = it }
                    // Desktop's hydration: only whole turns go back to the model,
                    // each naming the files sent with it (#601), never their images.
                    val whole = dao.turns(turn.sessionId)
                        .filter { it.position < turn.position && it.status == ChatStatus.COMPLETED && it.assistantText.isNotBlank() }
                    val history = whole.map { HistoryTurn(it.position, withAttachments(it), it.assistantText) }
                    val summary = session?.summary?.let { Summary(it, session.summaryThrough) }
                    // The chat's files no replayed turn names (one whose turn
                    // failed or was stopped, or is in the summary): named in
                    // this one, so their ids stay in reach.
                    val named = (whole.filter { summary == null || it.position >= summary.through } + turn)
                        .flatMap { Attachment.parseList(it.attachments) }.map { it.id }.toSet()
                    val earlier = try {
                        attachments?.list(turn.sessionId).orEmpty().filter { it.id !in named }
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val prompt = systemPromptFor(target)
                    val images = runCatching { takesImages(model) }.getOrDefault(false)
                    val (userImages, imagesLeftOut) = if (images) imagesOf(turn) else emptyList<ImagePart>() to 0
                    val toolsKey = "${target.connection.id}\u0000${target.connection.revision}\u0000${model.modelId}"
                    val loop = AgentLoop(
                        model = stream,
                        tools = toolsOf(turn.sessionId),
                        limits = limitsOf(target.definition),
                        modelId = model.modelId,
                        // The approval mode as it is for each request: the user can change it mid-turn.
                        systemPrompt = {
                            prompt + "\n\n" + ApprovalPolicy.prompt(modeOf(turn.sessionId)) + "\n\n" +
                                TimeContext.block(Instant.ofEpochMilli(clock()), zone())
                        },
                        toolsUnsupported = toolsKey in noTools,
                        onToolsUnsupported = { noTools += toolsKey },
                        images = images,
                    )
                    loop.run(
                        history, summary, withAttachments(turn) + AttachmentTools.earlierNote(earlier) +
                            "\n${AgentLoop.IMAGE_DROPPED}".repeat(imagesLeftOut),
                        onText = { text ->
                            latest = text
                            val now = clock()
                            // At most every [SHOW_EVERY_MS]: each update re-renders the reply.
                            if (now - lastShown >= SHOW_EVERY_MS) {
                                lastShown = now
                                val shown = text.toString()
                                // Only while this turn is still the session's
                                // live one: a late piece must never bring back
                                // an entry the turn's wind-down already removed.
                                _live.update { m ->
                                    if (m[turn.sessionId]?.turnId != turn.id) m
                                    else m + (turn.sessionId to Live(turn.sessionId, turn.id, shown))
                                }
                            }
                            // Now and then on disk too, so a stopped app keeps most of it.
                            if (now - lastSaved >= SAVE_EVERY_MS) {
                                lastSaved = now
                                val saved = text.toString()
                                scope.launch { runCatching { dao.saveProgress(turn.id, saved) } }
                            }
                        },
                        onCompacted = { s -> withContext(NonCancellable) { dao.setSummary(turn.sessionId, s.text, s.through) } },
                        userImages = userImages,
                    )
                }
            }
        } catch (e: CancellationException) {
            // Stop, or the chat was deleted: what came so far is kept.
        } catch (e: Throwable) {
            // Anything (an out-of-memory on a huge reply too): the turn still
            // ends, and its session stops counting as running.
            Log.w(TAG, "a chat turn failed (${e.javaClass.simpleName})")
            outcome = TurnOutcome(runCatching { latest.toString() }.getOrDefault(""), ChatError.BadReply)
        } finally {
            check?.let { checks.remove(turn.sessionId, it) }
            withContext(NonCancellable) {
                val o = outcome
                val status = when (o?.error) {
                    null -> if (o == null) ChatStatus.CANCELLED else ChatStatus.COMPLETED
                    ChatError.CutShort, ChatError.Declined -> ChatStatus.COMPLETED
                    ChatError.AiOff, ChatError.KeyChanged -> ChatStatus.CANCELLED
                    else -> ChatStatus.FAILED
                }
                try {
                    // After a Stop, a piece OkHttp was handing on at that
                    // instant may still be landing in the buffer.
                    val text = o?.text ?: runCatching { latest.toString() }.getOrDefault("")
                    dao.finishTurn(turn.id, text, status, clock() - started, o?.error?.name)
                } catch (e: Exception) {
                    Log.w(TAG, "saving a chat reply failed (${e.javaClass.simpleName})")
                }
                lock.withLock {
                    jobs.remove(turn.sessionId)
                    _live.update { it - turn.sessionId }
                }
            }
        }
    }

    private fun systemPromptFor(target: ChatTarget.Ready): String {
        val identity = JSONObject()
            .put("modelId", target.modelId.take(200))
            .put("providerId", target.definition.id.take(200))
        return promptTemplate.trim().replace("{{identity}}", identity.toString()) +
            if (attachments != null) "\n\n" + AttachmentTools.SYSTEM_PROMPT else ""
    }

    /** A turn's message as the model reads it: with the note naming its files (#601). */
    private fun withAttachments(turn: ChatTurn): String = turn.userText + AttachmentTools.note(Attachment.parseList(turn.attachments))

    /**
     * The images sent with [turn], for a model that takes them (desktop's
     * `promptImages`), and how many the request's image budget leaves out:
     * those are never read (#601). One that can't be read now is left out,
     * and the model can still try `attachment_read`.
     */
    private suspend fun imagesOf(turn: ChatTurn): Pair<List<ImagePart>, Int> {
        val store = attachments ?: return emptyList<ImagePart>() to 0
        val ids = Attachment.parseList(turn.attachments).filter { it.kind == AttachmentKind.Image }.map { it.id }
        return store.readImages(turn.sessionId, ids, AgentLoop.MAX_IMAGE_CHARS.toLong())
    }

    /** Stops [sessionId]'s turn; returns once its end is written. */
    suspend fun stop(sessionId: String) {
        val job = lock.withLock { jobs[sessionId] } ?: return
        job.cancelAndJoin()
    }

    /**
     * Deletes a chat, stopping its turn first. Stop and delete run as one
     * uncancellable unit: a caller cancelled while the turn winds down
     * (the page left mid-delete) still gets the chat deleted.
     */
    suspend fun delete(sessionId: String): Boolean = withContext(NonCancellable) {
        stop(sessionId)
        forgetTools(sessionId)
        try {
            dao.deleteSession(sessionId)
            // Only once the chat is gone: a chat that stays keeps its receipts and files.
            forgetOperations(sessionId)
            forgetPublications(sessionId)
            try {
                attachments?.deleteChat(sessionId)
            } catch (e: Exception) {
                // Swept at the next start.
                Log.w(TAG, "deleting a chat's attachments failed (${e.javaClass.simpleName})")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "deleting a chat failed (${e.javaClass.simpleName})")
            false
        }
    }

    /** Deletes every chat, stopping what's running first; uncancellable, like [delete]. */
    suspend fun deleteAll(): Boolean = withContext(NonCancellable) {
        lock.withLock { jobs.values.toList() }.forEach { it.cancelAndJoin() }
        for (id in registries.keys.toList()) forgetTools(id)
        modes.clear()
        try {
            dao.deleteAll()
            forgetOperations(null)
            forgetPublications(null)
            try {
                attachments?.deleteAll()
            } catch (e: Exception) {
                Log.w(TAG, "deleting chat attachments failed (${e.javaClass.simpleName})")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "deleting chats failed (${e.javaClass.simpleName})")
            false
        }
    }

    private suspend fun forgetOperations(sessionId: String?) {
        try {
            forgetNodeOperations(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "deleting node operation receipts failed (${e.javaClass.simpleName})")
        }
    }

    /** A deleted chat's tools go, and the tabs it held go back to the user (#575). */
    private fun forgetTools(sessionId: String) {
        registries.remove(sessionId)
        modes.remove(sessionId)
        AgentApprovals.withdrawSession(sessionId)
        AgentTransfers.withdrawSession(sessionId)
        AgentNodeAsks.withdrawSession(sessionId)
        AgentDomains.withdrawSession(sessionId)
        AgentTabs.releaseChat(sessionId)
    }

    companion object {
        private const val TAG = "AgentChats"

        /** A message longer than this isn't sent. */
        const val MAX_MESSAGE_CHARS = 100_000
        const val MAX_TITLE_CHARS = 80
        private const val SHOW_EVERY_MS = 80L
        private const val SAVE_EVERY_MS = 3_000L
        const val PROMPT_ASSET = "agent/system_prompt.md"

        /**
         * A chat's title: its first message's first line, cut at
         * [MAX_TITLE_CHARS] code points, with anything that draws nothing
         * or reorders the text (controls, bidi and other format
         * characters) turned into spaces.
         */
        fun titleOf(message: String): String {
            val line = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: message.trim()
            val clean = buildString {
                var i = 0
                var n = 0
                while (i < line.length && n < MAX_TITLE_CHARS) {
                    val cp = line.codePointAt(i)
                    when (Character.getType(cp).toByte()) {
                        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                        Character.PARAGRAPH_SEPARATOR, Character.SURROGATE, Character.UNASSIGNED,
                        -> append(' ')
                        else -> appendCodePoint(cp)
                    }
                    i += Character.charCount(cp)
                    n++
                }
                if (i < line.length) append('…')
            }
            return clean.trim().ifEmpty { "…" }
        }

        @Volatile
        private var instance: AgentChats? = null

        fun get(context: Context): AgentChats = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val skills = AgentSkills.load { path -> app.assets.open(path).use { it.readBytes() } }
                // Desktop's catalog of built-in skills follows the prompt, for the `read` tool (#599).
                val prompt = app.assets.open(PROMPT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }.trim() +
                    if (skills.isEmpty) "" else "\n\n" + skills.catalog()
                lateinit var chats: AgentChats
                chats = AgentChats(
                    AgentDatabase.get(app).chats(), AgentProviders.get(app), prompt,
                    toolsFor = { sessionId -> ToolRegistry.forChat(sessionId, chats.approvalsOf(sessionId), NodeOperations.get(app), skills) { app } },
                    forgetNodeOperations = { sessionId ->
                        val store = NodeOperations.get(app).store
                        if (sessionId == null) store.deleteAll() else store.deleteOwner(sessionId)
                    },
                    forgetPublications = { id ->
                        if (id == null) AgentPublications.forgetAll(app) else AgentPublications.forgetSession(app, id)
                    },
                    attachments = AgentAttachments.get(app),
                    takesImages = { ref -> AgentProviders.get(app).takesImages(ref) },
                )
                chats
            }.also { instance = it }
        }
    }
}
