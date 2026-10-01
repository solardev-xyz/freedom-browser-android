package baby.freedom.mobile.browser

import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleNode
import baby.freedom.swarm.RadicleSeed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `window.radicle` provider (#124): the authority behind every
 * `radicle_*` request a page makes, per desktop's
 * `docs/radicle-provider-api.md` (spec 0.2; `radicle-provider-ipc.js`,
 * `cob-service.js`) and iOS's `RadicleBridge`. The page-side object and
 * the message channel are [RadicleProviderBridge]'s; the prompts are
 * [RadiclePrompt].
 *
 * Actions only — reads of public repository data are the read API's
 * ([RadApi]). Three tiers, each checked here on every call whatever the
 * page thinks it was granted:
 *
 *  - none: `radicle_getCapabilities`.
 *  - connection (`radicle_requestAccess` asks once, remembered per
 *    origin): `radicle_getNodeStatus`, `radicle_listSeededRepos`,
 *    `radicle_getSeedStatus`, `radicle_sync`, `radicle_disconnect`, and
 *    `radicle_seed` / `radicle_unseed`, which also ask per repository —
 *    seeding commits disk and bandwidth, unseeding drops a choice the user
 *    made.
 *  - signing (asked on first use, then remembered): `radicle_getIdentity`
 *    and the COB writes `radicle_createIssue`, `radicle_commentIssue`,
 *    `radicle_editIssueState`, `radicle_commentPatch`, which sign with the
 *    user's own node identity and can't be taken back once announced.
 *
 * [origin] is always the platform's word for the requesting top-level
 * document, never something the page says. Parameters are checked
 * (desktop's limits, byte for byte) before any prompt, so the user is
 * never asked about a request that would fail anyway.
 *
 * Seeding goes through the node page's own seed path (`RadicleNode.seed`),
 * one repository at a time: a second repository while one is fetching is
 * refused (`busy`). As on the node page — and unlike desktop — a first
 * fetch that fails takes the seeding policy back, so a failed seed reads
 * as not seeded, and `radicle_sync` then answers `not_seeded`.
 */
class RadicleProvider(
    private val grants: Grants,
    private val node: Node,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Where grants live: [baby.freedom.mobile.data.RadicleGrantStore] in the app. */
    interface Grants {
        /**
         * null: not connected; else the DID it may sign as (#328), or `""`
         * for the connection tier only. A signing grant covers only that
         * identity: the node may since run as another.
         */
        suspend fun signingFor(origin: String): String?
        suspend fun connect(origin: String): Boolean

        /**
         * The DID [origin] may sign as, or could before the Radicle identity
         * changed; null if it never could. Only for the prompt's wording.
         */
        suspend fun signedBefore(origin: String): String? = null

        /** Give connected [origin] the signing tier for [did] only. */
        suspend fun grantSigning(origin: String, did: String): Boolean
        suspend fun revoke(origin: String): Boolean
    }

    /** The node: [RadicleClient] in the app. [call] blocks. */
    interface Node {
        val state: StateFlow<RadicleInfo>
        fun unavailableReason(): String?
        fun call(method: String, args: JSONObject, timeoutMs: Long): RadicleClient.Answer
        fun seed(rid: String): Boolean
        fun unseed(rid: String): Boolean
    }

    /** A request's answer: a result for the page, or a provider error. */
    sealed interface Reply {
        data class Ok(val value: Any) : Reply

        data class Err(val code: Int, val message: String, val reason: String? = null) : Reply {
            fun toJson(): JSONObject = JSONObject().put("code", code).put("message", message).apply {
                if (reason != null) put("data", JSONObject().put("reason", reason))
            }
        }
    }

    /** Receives the provider's events for pages on [origin]: `connect`, `disconnect`, `seedStatus`. */
    fun interface Events {
        fun emit(origin: String, event: String, data: Any)
    }

    @Volatile
    var events: Events = Events { _, _, _ -> }

    private class Track(var startedAt: Long, var attemptCount: Int) {
        var finishedAt: Long? = null
        /** Asked of the node, and not yet seen on its seed line. */
        var pendingSince: Long? = null
        val recentAttempts = ArrayList<JSONObject>()
        var lastLine: RadicleSeed? = null
    }

    private val lock = Any()

    /**
     * rid → the origins that seeded, synced or asked the status of that
     * repository: they get its `seedStatus` events, and only its — desktop
     * keeps a listener per repository (`seed-status.js`), so a site never
     * hears what another site (or the user) seeds.
     */
    private val followers = HashMap<String, MutableSet<String>>()

    /**
     * origin → the repositories it follows, least recently asked about
     * first. The cap is per site: a page picks the RIDs it asks about, so
     * one site asking about thousands of them only ever drops its own
     * oldest follows, never another site's (#349 R1-F1).
     */
    private val followed = HashMap<String, LinkedHashSet<String>>()
    private val tracks = HashMap<String, Track>()
    private val writes = HashMap<String, ArrayDeque<Long>>()
    private var watcher: Job? = null

    /** Start pushing `seedStatus` events as the node's seed line moves. */
    fun start(scope: CoroutineScope) {
        if (watcher != null) return
        watcher = scope.launch {
            node.state.collect { info -> onNodeState(info) }
        }
    }

    /**
     * Answer one request from a page on [origin]. [ask] puts a consent
     * prompt up on the page's tab and says whether the user allowed it
     * (false for a refusal, a dismissal, or a prompt that couldn't be
     * shown).
     */
    suspend fun request(
        origin: String,
        method: String,
        params: JSONObject,
        ask: suspend (RadicleAsk) -> Boolean,
    ): Reply {
        val tier = TIERS[method] ?: return Reply.Err(UNSUPPORTED, "Unknown method: $method")
        if (node.unavailableReason() == RadicleClient.REASON_DISABLED) {
            return Reply.Err(UNAVAILABLE, "Radicle is turned off", RadicleClient.REASON_DISABLED)
        }
        if (method == "radicle_getCapabilities") return Reply.Ok(capabilities(origin))
        if (method == "radicle_requestAccess") return requestAccess(origin, ask)

        val signingAs = grants.signingFor(origin)
            ?: return Reply.Err(UNAUTHORIZED, "Origin not connected. Call radicle_requestAccess first.", "not_connected")
        if (method == "radicle_disconnect") return disconnect(origin)
        if (method == "radicle_getNodeStatus") return nodeStatus(signingAs)
        node.unavailableReason()?.let {
            return Reply.Err(UNAVAILABLE, RadicleClient.unavailableMessage(it), it)
        }
        return when (tier) {
            Tier.Connection -> connectionMethod(origin, method, params, ask)
            Tier.Signing -> signingMethod(origin, method, params, signingAs, ask)
            Tier.None -> Reply.Err(UNSUPPORTED, "Unknown method: $method")
        }
    }

    // ---------------------------------------------------------------
    // Connection tier
    // ---------------------------------------------------------------

    private suspend fun capabilities(origin: String): JSONObject {
        // Desktop's order: the integration being off, then the origin not
        // being connected, then the node's own state.
        val nodeReason = node.unavailableReason()
        val reason = when {
            nodeReason == RadicleClient.REASON_DISABLED -> nodeReason
            grants.signingFor(origin) == null -> "not-connected"
            else -> nodeReason
        }
        return JSONObject()
            .put("specVersion", SPEC_VERSION)
            .put("canUseNode", reason == null)
            .put("reason", reason ?: JSONObject.NULL)
            .put("writes", JSONArray(WRITES))
    }

    private suspend fun requestAccess(origin: String, ask: suspend (RadicleAsk) -> Boolean): Reply {
        if (grants.signingFor(origin) == null) {
            if (!ask(RadicleAsk.Connect(origin))) return rejected()
            if (!grants.connect(origin)) return Reply.Err(INTERNAL, "Couldn't save the connection")
            events.emit(origin, "connect", JSONObject().put("origin", origin))
        }
        return Reply.Ok(
            JSONObject().put("connected", true).put("origin", origin).put("capabilities", capabilities(origin)),
        )
    }

    private suspend fun disconnect(origin: String): Reply {
        if (!grants.revoke(origin)) return Reply.Err(INTERNAL, "Couldn't drop the connection")
        unfollowAll(origin)
        events.emit(origin, "disconnect", JSONObject().put("origin", origin))
        return Reply.Ok(JSONObject().put("connected", false))
    }

    private suspend fun nodeStatus(signingAs: String): Reply {
        val info = node.state.value
        val running = node.unavailableReason() == null
        val result = JSONObject().put("running", running).put("status", info.status.name.lowercase())
        if (running) {
            val status = callIo("status", JSONObject())
            ((status as? RadicleClient.Answer.Ok)?.value as? JSONObject)?.let {
                if (it.has("connectedPeers")) result.put("peers", it.optInt("connectedPeers"))
            }
            val id = (callIo("identity", JSONObject()) as? RadicleClient.Answer.Ok)?.value as? JSONObject
            // The alias is gossiped network-wide; the NID pins the user to
            // one node, so it waits for the signing tier.
            id?.optString("alias")?.takeIf { it.isNotEmpty() }?.let { result.put("alias", it) }
            // Only for the identity the site was allowed to know (#328).
            if (signingAs.isNotEmpty() && id?.optString("did") == signingAs) {
                id.optString("nid").takeIf { it.isNotEmpty() }?.let { result.put("nid", it) }
            }
        }
        return Reply.Ok(result)
    }

    private suspend fun connectionMethod(
        origin: String,
        method: String,
        params: JSONObject,
        ask: suspend (RadicleAsk) -> Boolean,
    ): Reply = when (method) {
        "radicle_listSeededRepos" -> when (val a = callIo("listSeededRepos", JSONObject())) {
            is RadicleClient.Answer.Ok -> Reply.Ok(a.value)
            is RadicleClient.Answer.Failed -> nativeError(a, "repo listing failed")
        }
        "radicle_getSeedStatus" -> {
            val rid = rid(params) ?: return invalidRid()
            follow(origin, rid)
            Reply.Ok(withContext(io) { status(rid) })
        }
        "radicle_seed" -> {
            val rid = rid(params) ?: return invalidRid()
            busy(rid)?.let { return it }
            if (!ask(RadicleAsk.Seed(origin, rid))) return rejected()
            // Another fetch may have started while the prompt was up: the
            // node would skip this one without a word.
            node.unavailableReason()?.let { return Reply.Err(UNAVAILABLE, RadicleClient.unavailableMessage(it), it) }
            busy(rid)?.let { return it }
            startFetch(origin, rid)?.let { return it }
            Reply.Ok(JSONObject().put("rid", rid).put("seeded", true).put("status", withContext(io) { status(rid) }))
        }
        "radicle_unseed" -> {
            val rid = rid(params) ?: return invalidRid()
            if (!ask(RadicleAsk.Unseed(origin, rid))) return rejected()
            if (!node.unseed(rid)) return Reply.Err(INTERNAL, "unseed failed", "unseed_failed")
            synchronized(lock) { tracks.remove(rid) }
            Reply.Ok(JSONObject().put("rid", rid).put("seeded", false))
        }
        "radicle_sync" -> {
            val rid = rid(params) ?: return invalidRid()
            // The retry path for a repository the user already seeds —
            // never a second, promptless way to start seeding one.
            val seeded = when (val a = callIo("listSeededRepos", JSONObject())) {
                is RadicleClient.Answer.Failed -> return nativeError(a, "repo listing failed")
                is RadicleClient.Answer.Ok -> (a.value as? JSONArray)?.let { list ->
                    (0 until list.length()).any { list.optJSONObject(it)?.optString("rid") == rid }
                } == true
            }
            if (!seeded) return Reply.Err(INVALID_PARAMS, "Repository is not seeded", "not_seeded")
            busy(rid)?.let { return it }
            startFetch(origin, rid)?.let { return it }
            Reply.Ok(JSONObject().put("rid", rid).put("status", withContext(io) { status(rid) }))
        }
        else -> Reply.Err(UNSUPPORTED, "Unknown method: $method")
    }

    /** A fetch of another repository is running: the node takes one at a time. */
    private fun busy(rid: String): Reply? {
        val line = node.state.value.seed
        if (line != null && line.active && line.rid != rid) {
            return Reply.Err(INTERNAL, "Another repository is being fetched; try again when it's done", "busy")
        }
        return null
    }

    private fun startFetch(origin: String, rid: String): Reply? {
        follow(origin, rid)
        val line = node.state.value.seed
        // Already fetching this one: report on that fetch.
        if (line != null && line.active && line.rid == rid) return null
        if (!node.seed(rid)) return Reply.Err(INTERNAL, "seed failed", "seed_failed")
        val now = clock()
        synchronized(lock) {
            val track = tracks.getOrPut(rid) { Track(now, 0) }
            track.startedAt = now
            track.attemptCount++
            track.finishedAt = null
            track.pendingSince = now
            track.recentAttempts.clear()
        }
        return null
    }

    private fun follow(origin: String, rid: String) = synchronized(lock) {
        val mine = followed.getOrPut(origin) { LinkedHashSet() }
        // Asked again: it moves to the newest end.
        mine.remove(rid)
        mine.add(rid)
        followers.getOrPut(rid) { HashSet() }.add(origin)
        if (mine.size <= MAX_FOLLOWED_REPOS) return@synchronized
        // Drop this site's least recently asked-about repository, sparing
        // one whose fetch is running or about to (and the one just asked).
        val running = node.state.value.seed?.takeIf { it.active }?.rid
        val evict = mine.firstOrNull { it != rid && it != running && tracks[it]?.pendingSince == null }
            ?: mine.first { it != rid }
        unfollow(origin, evict)
    }

    /** Caller holds [lock]. */
    private fun unfollow(origin: String, rid: String) {
        followed[origin]?.let { if (it.remove(rid) && it.isEmpty()) followed.remove(origin) }
        followers[rid]?.let { if (it.remove(origin) && it.isEmpty()) followers.remove(rid) }
    }

    /** [origin] stops hearing every repository's `seedStatus`. */
    private fun unfollowAll(origin: String) = synchronized(lock) {
        followed.remove(origin)?.forEach { rid ->
            followers[rid]?.let { if (it.remove(origin) && it.isEmpty()) followers.remove(rid) }
        }
    }

    /**
     * [origin]'s grant is gone (the user disconnected it from the Radicle
     * page): it stops hearing `seedStatus` (#201 R1-F2).
     */
    fun forget(origin: String) = unfollowAll(origin)

    /**
     * Where [rid]'s replication stands (desktop's `getSeedStatus` shape).
     * Blocking: it may ask the node whether the repository is in storage.
     */
    internal fun status(rid: String): JSONObject {
        val line = node.state.value.seed?.takeIf { it.rid == rid }
        val track = synchronized(lock) { tracks[rid] }
        // Asked of the node a moment ago, and its seed line hasn't moved yet.
        val pending = track?.pendingSince?.let { clock() - it < PENDING_MS } == true
        val fromLine = line != null && !pending
        val state = when {
            pending -> "fetching"
            fromLine -> stateOf(line!!)
            else -> null
        }
        val inStorage = if (state == "fetched") true else inStorage(rid)
        val finalState = state ?: if (inStorage) "fetched" else "idle"
        val progress: Any = when {
            pending -> JSONObject().put("phase", "starting")
            fromLine -> JSONObject().put("phase", line!!.phase).apply {
                if (line.detail.isNotEmpty()) put(if (line.phase == "failed" || line.phase == "peer-failed") "reason" else "detail", line.detail)
            }
            else -> JSONObject.NULL
        }
        val seeders = if (inStorage) {
            ((callBlocking("seeders", JSONObject().put("rid", rid)) as? RadicleClient.Answer.Ok)?.value as? JSONObject)
                ?.takeIf { it.has("seeding") }?.optInt("seeding")
        } else {
            null
        }
        return JSONObject()
            .put("rid", rid)
            .put("state", finalState)
            .put("inStorage", inStorage)
            .put("seedersKnown", seeders ?: JSONObject.NULL)
            .put("attemptCount", track?.attemptCount ?: 0)
            .put("recentAttempts", JSONArray(synchronized(lock) { track?.recentAttempts?.toList().orEmpty() }))
            .put("progress", progress)
            .put("lastError", if (fromLine && line!!.phase == "failed") line.detail.ifEmpty { "fetch failed" } else JSONObject.NULL)
            .put("startedAt", track?.startedAt ?: JSONObject.NULL)
            .put("finishedAt", track?.finishedAt ?: JSONObject.NULL)
    }

    private fun inStorage(rid: String): Boolean =
        callBlocking("repoInfo", JSONObject().put("rid", rid)) is RadicleClient.Answer.Ok

    private fun stateOf(line: RadicleSeed): String = when {
        line.active -> "fetching"
        line.phase == RadicleNode.PHASE_DONE -> "fetched"
        line.phase == RadicleNode.PHASE_FAILED -> "failed"
        line.phase == RadicleNode.PHASE_CANCELLED -> "cancelled"
        else -> "idle"
    }

    /** The node's seed line moved: bookkeeping, and a `seedStatus` event for a tracked repository. */
    private suspend fun onNodeState(info: RadicleInfo) {
        val line = info.seed ?: return
        val targets = synchronized(lock) {
            val track = tracks[line.rid] ?: return
            if (track.lastLine == line) return
            track.lastLine = line
            track.pendingSince = null
            val now = clock()
            if (line.phase == "peer-failed") {
                track.recentAttempts += JSONObject().put("nid", JSONObject.NULL).put("ok", false)
                    .put("error", line.detail).put("at", now)
            }
            if (line.phase == RadicleNode.PHASE_DONE) {
                track.recentAttempts += JSONObject().put("nid", JSONObject.NULL).put("ok", true).put("at", now)
            }
            while (track.recentAttempts.size > 5) track.recentAttempts.removeAt(0)
            if (!line.active) track.finishedAt = now
            followers[line.rid]?.toList().orEmpty()
        }
        if (targets.isEmpty()) return
        val status = withContext(io) { status(line.rid) }
        for (origin in targets) {
            // A follower whose grant was dropped some other way (another
            // process, a cleared store) doesn't hear it either.
            if (grants.signingFor(origin) == null) {
                forget(origin)
                continue
            }
            events.emit(origin, "seedStatus", status)
        }
    }

    // ---------------------------------------------------------------
    // Signing tier
    // ---------------------------------------------------------------

    private suspend fun signingMethod(
        origin: String,
        method: String,
        params: JSONObject,
        signingAs: String,
        ask: suspend (RadicleAsk) -> Boolean,
    ): Reply {
        // Checked before the prompt: nobody is asked about a write that
        // would be refused anyway.
        val write = if (method == "radicle_getIdentity") {
            null
        } else {
            when (val v = validateWrite(method, params)) {
                is Validated.Bad -> return v.error
                is Validated.Ok -> v
            }
        }
        // The identity the node runs as now. A grant covers only the one it
        // was given for (#328: the wallet's or the device's own), so a site
        // allowed to act as the other asks again before it learns this one.
        var identity = when (val a = callIo("identity", JSONObject())) {
            is RadicleClient.Answer.Failed -> return nativeError(a, "identity unavailable")
            is RadicleClient.Answer.Ok -> a.value as? JSONObject
        }
        val did = identity?.optString("did").orEmpty()
        if (did.isEmpty()) return Reply.Err(INTERNAL, "identity unavailable", "native_failed")
        if (did != signingAs) {
            // Name the identity asked about, and say when the site was allowed
            // another one: allowing this links the two for it.
            // Whether [did] is the wallet's comes with it from `:node`, not from
            // the UI's copy of the node state, which lags a restart.
            val wallet = identity?.optBoolean(RadicleNode.WALLET_IDENTITY) == true
            val before = previousIdentity(runCatching { grants.signedBefore(origin) }.getOrNull(), did, wallet)
            if (!ask(RadicleAsk.Signing(origin, did, wallet, before))) return rejected()
            // The node may have restarted as another identity while the
            // prompt was up: the grant is for the one the user was asked
            // about, and only while the node still runs as it.
            identity = when (val a = callIo("identity", JSONObject())) {
                is RadicleClient.Answer.Failed -> return nativeError(a, "identity unavailable")
                is RadicleClient.Answer.Ok -> a.value as? JSONObject
            }
            if (identity?.optString("did").orEmpty() != did) return identityChanged()
            if (!grants.grantSigning(origin, did)) return Reply.Err(UNAUTHORIZED, "Origin not connected", "not_connected")
        }
        if (write == null) return Reply.Ok(JSONObject((identity ?: JSONObject()).toString()).apply { remove(RadicleNode.WALLET_IDENTITY) })
        if (!takeWriteSlot(origin)) {
            return Reply.Err(INTERNAL, "Too many writes; try again in a minute", "rate_limited")
        }
        // `:node` refuses the write if the node has meanwhile restarted as another identity.
        val (call, args) = write.call to JSONObject(write.args.toString()).put(RadicleNode.AS_DID, did)
        return when (val a = callIo(call, args, RadicleClient.WRITE_TIMEOUT_MS)) {
            is RadicleClient.Answer.Failed -> nativeError(a, "write failed")
            is RadicleClient.Answer.Ok -> {
                val value = a.value as? JSONObject
                if (value?.has("id") == true) Reply.Ok(value) else Reply.Err(INTERNAL, "write failed", "native_failed")
            }
        }
    }

    /**
     * What a signing prompt for [did] says the site was allowed before
     * ([RadicleAsk.Signing.previousDid]), from what it could sign as
     * ([Grants.signedBefore]): null for nothing, or for [did] itself; `""`
     * for a grant from before #328 (the device's own identity, DID not
     * recorded) when [did] is the wallet's — for the device's own it's the
     * same one again.
     */
    private fun previousIdentity(before: String?, did: String, wallet: Boolean): String? = when {
        before == null || before == did -> null
        before.isEmpty() -> if (wallet) "" else null
        else -> before
    }

    /** [validateWrite]'s answer: the node call and its arguments, or why the write is refused. */
    sealed interface Validated {
        data class Ok(val call: String, val args: JSONObject) : Validated

        data class Bad(val error: Reply.Err) : Validated
    }

    private class Invalid(val error: Reply.Err) : Exception(null, null, false, false)

    /** Check a write's parameters (desktop's `cob-service.js` limits) and build its node call. */
    internal fun validateWrite(method: String, params: JSONObject): Validated = try {
        buildWrite(method, params)
    } catch (e: Invalid) {
        Validated.Bad(e.error)
    }

    private fun buildWrite(method: String, params: JSONObject): Validated {
        fun fail(message: String, reason: String): Nothing = throw Invalid(Reply.Err(INVALID_PARAMS, message, reason))
        val rid = rid(params) ?: fail("Invalid Radicle repository ID", "invalid_rid")
        val args = JSONObject().put("rid", rid)
        fun text(field: String, reason: String, max: Int): String {
            val v = params.opt(field) as? String
            if (v == null || v.isBlank()) fail("$field must be a non-empty string", reason)
            if (v.toByteArray(Charsets.UTF_8).size > max) fail("$field exceeds $max bytes", "payload_too_large")
            return v
        }
        fun cobId(field: String, optional: Boolean = false): String {
            val raw = params.opt(field)
            if (optional && (raw == null || raw == JSONObject.NULL)) return ""
            val v = raw as? String
            if (v == null || !COB_ID.matches(v)) fail("Invalid collaborative object id", "invalid_id")
            return v
        }
        return when (method) {
            "radicle_createIssue" -> {
                val title = text("title", "invalid_title", MAX_TITLE_BYTES)
                val description = text("description", "invalid_body", MAX_BODY_BYTES)
                val labels = params.opt("labels")
                val list = when {
                    labels == null || labels == JSONObject.NULL -> JSONArray()
                    labels is JSONArray && labels.length() <= MAX_LABELS &&
                        (0 until labels.length()).all { i ->
                            val l = labels.opt(i) as? String
                            l != null && l.isNotBlank() && l.toByteArray(Charsets.UTF_8).size <= MAX_LABEL_BYTES
                        } -> labels
                    else -> return fail("labels must be an array of short strings (max $MAX_LABELS)", "invalid_labels")
                }
                Validated.Ok(
                    "createIssue",
                    args.put("title", title).put("description", description).put("labelsJson", list.toString()),
                )
            }
            "radicle_commentIssue" -> {
                val issueId = cobId("issueId")
                val body = text("body", "invalid_body", MAX_BODY_BYTES)
                val replyTo = cobId("replyTo", optional = true)
                Validated.Ok(
                    "commentIssue",
                    args.put("issueId", issueId).put("body", body).apply {
                        if (replyTo.isNotEmpty()) put("replyTo", replyTo)
                    },
                )
            }
            "radicle_editIssueState" -> {
                val issueId = cobId("issueId")
                val state = params.opt("state") as? String
                if (state == null || state !in ISSUE_STATES) fail("state must be 'open', 'closed' or 'solved'", "invalid_state")
                Validated.Ok("editIssueState", args.put("issueId", issueId).put("state", state))
            }
            "radicle_commentPatch" -> {
                val patchId = cobId("patchId")
                val body = text("body", "invalid_body", MAX_BODY_BYTES)
                val revision = cobId("revisionId", optional = true)
                // The patch id doubles as its first revision's id.
                Validated.Ok("commentPatch", args.put("revisionId", revision.ifEmpty { patchId }).put("body", body))
            }
            else -> fail("Unknown method: $method", "invalid_method")
        }
    }

    /** At most [MAX_WRITES_PER_MINUTE] writes per origin in any minute. */
    private fun takeWriteSlot(origin: String): Boolean = synchronized(lock) {
        val now = clock()
        val times = writes.getOrPut(origin) { ArrayDeque() }
        while (times.isNotEmpty() && (now - times.first() >= WRITE_WINDOW_MS || now < times.first())) times.removeFirst()
        if (times.size >= MAX_WRITES_PER_MINUTE) return@synchronized false
        times.addLast(now)
        true
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private suspend fun callIo(
        method: String,
        args: JSONObject,
        timeoutMs: Long = RadicleClient.READ_TIMEOUT_MS,
    ): RadicleClient.Answer = withContext(io) { node.call(method, args, timeoutMs) }

    private fun callBlocking(method: String, args: JSONObject) =
        node.call(method, args, RadicleClient.READ_TIMEOUT_MS)

    /**
     * `params.rid` as the node takes it (`rad:z…`), given as `rad:z…`,
     * `rad://z…` or a bare `z…` (desktop's `validateAndNormalizeRid`), or
     * null. Nothing is trimmed or case-folded.
     */
    internal fun rid(params: JSONObject): String? {
        val raw = params.opt("rid") as? String ?: return null
        val bare = when {
            raw.startsWith("rad://") -> raw.removePrefix("rad://")
            raw.startsWith("rad:") -> raw.removePrefix("rad:")
            else -> raw
        }
        return if (RadUrl.RID.matches(bare)) "rad:$bare" else null
    }

    private fun invalidRid() = Reply.Err(INVALID_PARAMS, "Invalid Radicle repository ID", "invalid_rid")

    private fun rejected() = Reply.Err(USER_REJECTED, "User rejected the request")

    /**
     * The node runs as another identity than the one the site was allowed
     * to act as (#328): nothing was written; the next call asks again.
     */
    private fun identityChanged() =
        Reply.Err(UNAUTHORIZED, "The Radicle identity changed; call again to ask the user", "identity_changed")

    /** Desktop's `nativeError`: the node's message, with a reason read off it. */
    private fun nativeError(a: RadicleClient.Answer.Failed, fallback: String): Reply.Err {
        if (a.reason == RadicleClient.REASON_IDENTITY_CHANGED) return identityChanged()
        if (a.reason == RadicleClient.REASON_STOPPED || a.reason == RadicleClient.REASON_NOT_READY ||
            a.reason == RadicleClient.REASON_DISABLED
        ) {
            return Reply.Err(UNAVAILABLE, a.message, a.reason)
        }
        val message = a.message.ifEmpty { fallback }
        val reason = when {
            message.contains("announce refs failed", ignoreCase = true) -> "announce_failed"
            message.contains("not found", ignoreCase = true) -> "repo_not_found"
            else -> "native_failed"
        }
        return Reply.Err(INTERNAL, message, reason)
    }

    enum class Tier { None, Connection, Signing }

    companion object {
        const val SPEC_VERSION = "0.2"
        val WRITES = listOf("issue", "issueComment", "issueState", "patchComment")

        const val USER_REJECTED = 4001
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED = 4200
        const val UNAVAILABLE = 4900
        const val INVALID_PARAMS = -32602
        const val INTERNAL = -32603

        const val MAX_TITLE_BYTES = 200
        const val MAX_BODY_BYTES = 65536
        const val MAX_LABEL_BYTES = 100
        const val MAX_LABELS = 10

        const val MAX_WRITES_PER_MINUTE = 10
        const val WRITE_WINDOW_MS = 60_000L

        /** How long a seed just asked of the node reads as starting before its line shows up. */
        const val PENDING_MS = 5_000L

        /** Repositories each site's `seedStatus` follows are kept for ([followed]). */
        const val MAX_FOLLOWED_REPOS = 512

        private val COB_ID = Regex("^[0-9a-f]{6,40}$")
        private val ISSUE_STATES = setOf("open", "closed", "solved")

        val TIERS: Map<String, Tier> = mapOf(
            "radicle_getCapabilities" to Tier.None,
            "radicle_requestAccess" to Tier.Connection,
            "radicle_disconnect" to Tier.Connection,
            "radicle_getNodeStatus" to Tier.Connection,
            "radicle_listSeededRepos" to Tier.Connection,
            "radicle_getSeedStatus" to Tier.Connection,
            "radicle_seed" to Tier.Connection,
            "radicle_unseed" to Tier.Connection,
            "radicle_sync" to Tier.Connection,
            "radicle_getIdentity" to Tier.Signing,
            "radicle_createIssue" to Tier.Signing,
            "radicle_commentIssue" to Tier.Signing,
            "radicle_editIssueState" to Tier.Signing,
            "radicle_commentPatch" to Tier.Signing,
        )
    }
}

/** What a consent prompt asks the user, for a page on [origin]. */
sealed interface RadicleAsk {
    val origin: String

    /** Connect: node status, the seeded list, and asking to seed. */
    data class Connect(override val origin: String) : RadicleAsk

    /** Seed and fetch [rid]: disk and bandwidth, for as long as it's seeded. */
    data class Seed(override val origin: String, val rid: String) : RadicleAsk

    /** Stop seeding [rid]. */
    data class Unseed(override val origin: String, val rid: String) : RadicleAsk

    /**
     * The user's Radicle identity [did] (the wallet's when [wallet], else
     * the device's own), and writing as it. [previousDid] is the other
     * identity the site was allowed to act as before (#328), if any: `""`
     * when that was the device's own identity, from before grants named
     * one, whose DID wasn't recorded.
     */
    data class Signing(
        override val origin: String,
        val did: String = "",
        val wallet: Boolean = false,
        val previousDid: String? = null,
    ) : RadicleAsk
}
