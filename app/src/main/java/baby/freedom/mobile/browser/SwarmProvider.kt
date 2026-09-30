package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.PublisherIdentity
import baby.freedom.mobile.wallet.PublisherKeys
import baby.freedom.mobile.wallet.SitePublisher
import baby.freedom.mobile.wallet.VaultLockedException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `window.swarm` provider (#120): the authority behind every
 * `swarm_*` request a page makes — desktop's `swarm-provider-ipc.js`
 * (spec 1.0; `publish-service.js`, `chunk-service.js`, `feed-service.js`)
 * and iOS's `SwarmBridge`, with the same method names, parameters,
 * results, error codes and `data.reason`s. The page-side object and its
 * channel are [SwarmProviders]'; the approval sheets are [SwarmPrompt].
 *
 * Four tiers, each checked here on every call, whatever the page thinks
 * it was granted:
 *
 *  - none: `swarm_getCapabilities`, and the reads of public Swarm data —
 *    `swarm_readFeedEntry`, `swarm_readChunk`,
 *    `swarm_readSingleOwnerChunk` — plus `swarm_listFeeds` (the site's
 *    own feed records). Reads are rate-limited per origin (desktop's
 *    budgets: more for a connected site).
 *  - connection (`swarm_requestAccess` asks once, remembered per origin):
 *    `swarm_getUploadStatus`, and the publish methods below.
 *  - publish (a sheet per upload, unless the user ticked "always allow"
 *    for the site): `swarm_publishData`, `swarm_publishFiles`,
 *    `swarm_publishChunk`. Uploads spend the node's postage stamps.
 *  - feeds / signing (a sheet the first time, which grants the site
 *    feed access; then one per call unless "always allow" was ticked for
 *    that kind): `swarm_createFeed`, `swarm_updateFeed`,
 *    `swarm_writeFeedEntry` (feeds), and `swarm_writeSingleOwnerChunk`,
 *    `swarm_getSigningIdentity` (signing). These sign with the site's
 *    publisher identity (#119), derived from the wallet's seed for the
 *    one signature and zeroed after; a locked wallet always asks, since
 *    approving is what unlocks it.
 *
 *  - messaging (#121; desktop's messaging extension): a sheet the first
 *    time, which grants the site the messaging tier —
 *    `swarm_getMessagingIdentity`, `swarm_subscribe` — and one per
 *    message for `swarm_sendPss` / `swarm_sendGsoc` unless "always
 *    allow" was ticked for messaging (the first send's grant sheet covers
 *    that send). `swarm_unsubscribe` never asks. Sends spend postage
 *    stamps; subscriptions ([SwarmSubscriptions]) hold a node receive
 *    pipeline for as long as the document that made them is there. The
 *    messaging identity is the node's own PSS key (desktop's and iOS's
 *    `bee-wallet` mode: the node decrypts with it), so only the first two
 *    bytes of the node's overlay leave with it, never the whole address.
 *
 * [origin] is always the platform's word for the requesting top-level
 * document ([providerOriginKey]), never something the page says; grants
 * are keyed by it. Feed topics are derived from the site's desktop-form
 * key ([swarmOriginKey]: `bzz://<ref>`, `name.eth`, `https://host`), so
 * a feed written here is the same feed on desktop and iOS. Parameters are
 * checked (desktop's limits) before any sheet, so the user is never asked
 * about a request that would fail anyway.
 */
class SwarmProvider(
    private val grants: Grants,
    private val feeds: Feeds,
    private val publishers: Publishers,
    private val node: Http,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val subscriptions: SwarmSubscriptions = SwarmSubscriptions({ _, _, _ -> NO_SOCKET }),
) {
    /** Connection and auto-approve grants: [baby.freedom.mobile.data.SwarmGrantStore] in the app. */
    interface Grants {
        suspend fun connected(origin: String): Boolean
        suspend fun connect(origin: String): Boolean
        suspend fun autoApprove(origin: String, kind: AutoApprove): Boolean
        suspend fun setAutoApprove(origin: String, kind: AutoApprove): Boolean

        /** Whether connected [origin] holds the messaging tier (#121). */
        suspend fun messaging(origin: String): Boolean

        /** Grant connected [origin] the messaging tier; false if it couldn't be saved. */
        suspend fun grantMessaging(origin: String): Boolean
    }

    /**
     * The sites' feed access and feed records, per wallet
     * ([baby.freedom.mobile.data.SwarmFeedStore]): a new wallet starts
     * with none. Reads never throw; writes throw [IOException].
     */
    interface Feeds {
        fun granted(origin: String): Boolean
        fun grant(origin: String)
        fun feed(origin: String, name: String): FeedRecord?
        fun all(origin: String): List<FeedRecord>
        fun put(origin: String, record: FeedRecord)
    }

    /** The wallet and the sites' publisher identities (#119). */
    interface Publishers {
        /**
         * Whether there is a wallet at all. With none, a signing method's
         * sheet ([SwarmAsk.Sign.needsWallet]) sets one up once approved:
         * [SwarmProviders] opens the wallet page from the approved sheet,
         * never straight from a page's request.
         */
        fun walletExists(): Boolean
        fun unlocked(): Boolean

        /** [origin]'s identities, or null if it has none. */
        fun site(origin: String): SitePublisher?

        /** [origin]'s identities, giving it an app-scoped one first if it has none. Throws [IOException], or [IllegalStateException] with no wallet. */
        fun ensureSite(origin: String): SitePublisher

        /**
         * [identity]'s 32-byte key, which the caller zeroes. Throws
         * [VaultLockedException]. Not wallet activity by itself (#236):
         * a site's always-allowed identity read must not keep the wallet open.
         */
        fun signingKey(identity: PublisherIdentity): ByteArray

        /**
         * The user approved a sheet, or a write they allowed went out to
         * the node: keeps the wallet from idling into its lock (desktop's
         * `resetVaultAutoLockTimer`). Never for a read answered with no
         * sheet and no write (#236). A write needn't spend new postage:
         * rewriting the same SOC can reuse its stamp slot.
         */
        fun noteActivity()
    }

    /** The node's bee-shaped HTTP API. [request] blocks and throws [IOException] if the node can't be reached. */
    fun interface Http {
        fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): Answer

        class Answer(val status: Int, headers: Map<String, String>, val body: ByteArray) {
            /** Header names lower-cased. */
            val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }
            val ok: Boolean get() = status in 200..299
            fun json(): JSONObject? = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
            fun message(): String = json()?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP $status"
        }
    }

    enum class AutoApprove(val wire: String) { Publish("publish"), Feeds("feeds"), Signing("signing"), Messaging("messaging") }

    /** One of a site's feeds, desktop's `swarm-feeds.json` record. */
    data class FeedRecord(
        val name: String,
        /** 64 lower-case hex, no `0x`. */
        val topic: String,
        /** EIP-55, `0x`. */
        val owner: String,
        val manifestReference: String,
        val identityId: String,
        val createdAt: Long,
        val lastUpdated: Long? = null,
        val lastReference: String? = null,
    )

    /** A request's answer: a result for the page, or a provider error. */
    sealed interface Reply {
        data class Ok(val value: Any) : Reply

        data class Err(val code: Int, val message: String, val data: JSONObject? = null) : Reply {
            val reason: String? get() = data?.optString("reason")?.takeIf { it.isNotEmpty() }

            fun toJson(): JSONObject = JSONObject().put("code", code).put("message", message).apply {
                if (data != null) put("data", data)
            }
        }
    }

    /** What the user said on an approval sheet. */
    data class Answer(val allowed: Boolean, val always: Boolean = false, val ownerGone: Boolean = false) {
        companion object {
            val REJECTED = Answer(false)

            /** No sheet: by the time it could go up, the feed it signs for had lost its own identity ([current]). */
            val OWNER_GONE = Answer(false, ownerGone = true)
        }
    }

    /** Receives the provider's events for pages on [origin] (its [providerOriginKey]): `connect`. */
    fun interface Events {
        fun emit(origin: String, event: String, data: Any)
    }

    @Volatile
    var events: Events = Events { _, _, _ -> }

    private class Budget(var startedAt: Long, var requests: Int, var bytes: Long)

    /** Per-origin read budgets; a window that has run out is dropped on the next read ([spendBudget]). */
    private val budgets = HashMap<String, Budget>()

    /** How many origins have a read budget running: for tests. */
    internal fun budgetOrigins(): Int = synchronized(budgets) { budgets.size }

    /** Upload tag → the origin that made it: nobody else may read its progress. Session only. */
    private val tagOwners = ConcurrentHashMap<Long, String>()

    /** Per-topic write locks: two writes to one feed never race for the same index. */
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Answer one request from a page on [origin]. [ask] puts an approval
     * sheet up on the page's tab and returns the user's answer
     * ([Answer.REJECTED] for a refusal, a dismissal, or a sheet that
     * couldn't be shown). [committed] is called once a write or signature
     * is approved (by a sheet or an "always allow") and about to run: from
     * then on the page must wait for the real answer rather than time out,
     * or it would lose the reference to something that did get published.
     */
    suspend fun request(
        origin: String,
        method: String,
        params: JSONObject,
        committed: () -> Unit = {},
        /** The asking document, where a `swarm_subscribe`'s messages go; null where there's none. */
        subscriber: SwarmSubscriptions.Subscriber? = null,
        ask: suspend (SwarmAsk) -> Answer,
    ): Reply = try {
        dispatch(origin, method, params, Calls(ask, committed, subscriber))
    } catch (e: Invalid) {
        e.error
    } catch (e: CancellationException) {
        throw e
    } catch (e: VaultLockedException) {
        Reply.Err(INTERNAL, VAULT_LOCKED)
    } catch (e: SocketTimeoutException) {
        // The node is up but slow (a big upload over the whole-call
        // deadline): not "stopped" — it may even have stored the data.
        Reply.Err(INTERNAL, "The Swarm node didn't finish in time", reason("node-timeout"))
    } catch (e: IOException) {
        // Only the node's calls get here: the stores' writes are [saving].
        Reply.Err(UNAVAILABLE, "Swarm node is not available", reason("node-stopped"))
    }

    /** One request's way back to its page: [ask] a sheet, say it's [committed], and where its messages go. */
    private class Calls(
        val ask: suspend (SwarmAsk) -> Answer,
        val committed: () -> Unit,
        val subscriber: SwarmSubscriptions.Subscriber?,
    )

    private suspend fun dispatch(
        origin: String,
        method: String,
        params: JSONObject,
        calls: Calls,
    ): Reply {
        val ask = calls.ask
        if (method !in KNOWN_METHODS) return Reply.Err(UNSUPPORTED, "Unknown method: $method")
        return when (method) {
            "swarm_requestAccess" -> requestAccess(origin, ask)
            "swarm_getCapabilities" -> Reply.Ok(capabilities(origin))
            "swarm_readChunk" -> readChunk(origin, params)
            "swarm_readSingleOwnerChunk" -> readSoc(origin, params)
            "swarm_readFeedEntry" -> readFeedEntry(origin, params)
            "swarm_listFeeds" -> listFeeds(origin)
            else -> {
                if (!grants.connected(origin)) return notConnected()
                when (method) {
                    "swarm_publishData" -> publishData(origin, params, calls)
                    "swarm_publishFiles" -> publishFiles(origin, params, calls)
                    "swarm_publishChunk" -> publishChunk(origin, params, calls)
                    "swarm_getUploadStatus" -> uploadStatus(origin, params)
                    "swarm_createFeed", "swarm_updateFeed", "swarm_writeFeedEntry",
                    "swarm_writeSingleOwnerChunk", "swarm_getSigningIdentity",
                    -> signing(origin, method, params, calls)
                    "swarm_getMessagingIdentity" -> messagingIdentity(origin, calls)
                    "swarm_sendPss" -> sendPss(origin, params, calls)
                    "swarm_sendGsoc" -> sendGsoc(origin, params, calls)
                    "swarm_subscribe" -> subscribe(origin, params, calls)
                    "swarm_unsubscribe" -> unsubscribe(origin, params)
                    else -> Reply.Err(INTERNAL, "Internal error")
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------

    private suspend fun requestAccess(origin: String, ask: suspend (SwarmAsk) -> Answer): Reply {
        if (!grants.connected(origin)) {
            if (!ask(SwarmAsk.Connect(origin)).allowed) return rejected()
            if (!grants.connect(origin)) return Reply.Err(INTERNAL, "Couldn't save the connection")
            events.emit(origin, "connect", JSONObject().put("origin", swarmOriginKey(origin)))
        }
        return Reply.Ok(
            JSONObject()
                .put("connected", true)
                .put("origin", swarmOriginKey(origin))
                .put("capabilities", JSONArray(listOf("publish"))),
        )
    }

    private suspend fun capabilities(origin: String): JSONObject {
        val connected = grants.connected(origin)
        val preflight = withContext(io) { preflight() }
        return JSONObject()
            .put("specVersion", SPEC_VERSION)
            .put("canPublish", connected && preflight == null)
            .put("reason", if (!connected) "not-connected" else preflight ?: JSONObject.NULL)
            .put("publisherIdentityModes", JSONArray(listOf("app-scoped", "bee-wallet")))
            .put("features", JSONArray(listOf("messaging")))
            .put("extensions", JSONObject().put("publisherSigning", true))
            .put(
                "limits",
                JSONObject()
                    .put("maxDataBytes", MAX_DATA_BYTES)
                    .put("maxFilesBytes", MAX_FILES_BYTES)
                    .put("maxFileCount", MAX_FILE_COUNT)
                    .put("maxPathBytes", MAX_PATH_BYTES)
                    .put("maxChunkPayloadBytes", SwarmChunks.MAX_PAYLOAD)
                    .put("maxMessageBytes", MAX_MESSAGE_BYTES)
                    .put("maxTargetDepth", MAX_TARGET_DEPTH)
                    .put("maxSubscriptions", SwarmSubscriptions.MAX_SUBSCRIPTIONS),
            )
    }

    // ---------------------------------------------------------------
    // Publishing
    // ---------------------------------------------------------------

    private suspend fun publishData(origin: String, params: JSONObject, calls: Calls): Reply {
        val data = params.opt("data")
        if (data == null || data == JSONObject.NULL) fail("data is required")
        val contentType = params.opt("contentType") as? String
        if (contentType.isNullOrEmpty()) fail("contentType is required", "missing_content_type")
        // It goes to the node as a header: one the HTTP stack would refuse
        // (a line break, a non-ASCII character) fails here, before the
        // user is asked, not after they allowed it.
        if (contentType.length > MAX_CONTENT_TYPE_CHARS || contentType.any { it !in ' '..'~' }) {
            fail("contentType must be at most $MAX_CONTENT_TYPE_CHARS printable ASCII characters", "invalid_content_type")
        }
        val payload = payloadOf(data) ?: fail("data must be a string, Uint8Array, or ArrayBuffer")
        if (payload.size > MAX_DATA_BYTES) tooLarge("Payload exceeds maximum size of $MAX_DATA_BYTES bytes", MAX_DATA_BYTES, payload.size)
        val name = (params.opt("name") as? String)?.takeIf { it.isNotEmpty() }
        preflightOrFail()
        approvePublish(origin, calls, SwarmAsk.Publish(origin, SwarmAsk.Publish.Kind.Data, payload.size.toLong(), contentType, name, emptyList()))
        val batch = batchFor(payload.size.toLong())
        val query = "name=" + java.net.URLEncoder.encode(name ?: "data", "UTF-8").replace("+", "%20")
        val answer = call(
            "POST", "/bzz?$query",
            uploadHeaders(batch, deferred = false) + ("content-type" to contentType),
            payload, UPLOAD_TIMEOUT_MS,
        )
        val reference = uploadedReference(answer)
        publishers.noteActivity()
        return Reply.Ok(JSONObject().put("reference", reference).put("bzzUrl", "bzz://$reference"))
    }

    private suspend fun publishFiles(origin: String, params: JSONObject, calls: Calls): Reply {
        val files = params.opt("files") as? JSONArray
        if (files == null || files.length() == 0) fail("files must be a non-empty array", "empty_files")
        if (files.length() > MAX_FILE_COUNT) {
            fail(
                "File count exceeds maximum of $MAX_FILE_COUNT", "too_many_files",
                JSONObject().put("limit", MAX_FILE_COUNT).put("actual", files.length()),
            )
        }
        val seen = HashSet<String>()
        val entries = ArrayList<Pair<String, ByteArray>>()
        var total = 0L
        for (i in 0 until files.length()) {
            val file = files.opt(i) as? JSONObject ?: fail("files[$i] is not a valid file object")
            val path = file.opt("path")
            virtualPathError(path)?.let { fail("files[$i].path: $it", "invalid_path") }
            path as String
            if (!seen.add(path)) fail("Duplicate path: $path", "duplicate_path", JSONObject().put("path", path))
            val bytes = bytesOf(file.opt("bytes")) ?: fail("files[$i].bytes must be a Buffer, Uint8Array, or ArrayBuffer")
            total += bytes.size
            entries += path to bytes
        }
        if (total > MAX_FILES_BYTES) tooLarge("Total size exceeds maximum of $MAX_FILES_BYTES bytes", MAX_FILES_BYTES, total)
        val index = params.opt("indexDocument").takeUnless { it == null || it == JSONObject.NULL }
        if (index != null && (index !is String || index !in seen)) {
            fail("indexDocument must match an existing file path", "invalid_index_document")
        }
        preflightOrFail()
        approvePublish(origin, calls, SwarmAsk.Publish(origin, SwarmAsk.Publish.Kind.Files, total, null, null, entries.map { it.first }))
        val batch = batchFor(total)
        val headers = uploadHeaders(batch, deferred = true) + ("swarm-collection" to "true") +
            ("content-type" to "application/x-tar") +
            (if (index != null) mapOf("swarm-index-document" to index as String) else emptyMap())
        val answer = call("POST", "/bzz", headers, tar(entries), UPLOAD_TIMEOUT_MS)
        val reference = uploadedReference(answer)
        val tag = (answer.headers["swarm-tag"] ?: answer.headers["swarm-tag-uid"])?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        if (tag != null) tagOwners[tag] = origin
        publishers.noteActivity()
        return Reply.Ok(
            JSONObject().put("reference", reference).put("bzzUrl", "bzz://$reference").put("tagUid", tag ?: JSONObject.NULL),
        )
    }

    private suspend fun publishChunk(origin: String, params: JSONObject, calls: Calls): Reply {
        emptyOptions(params)
        val payload = chunkPayload(params.opt("data"))
        val span = span(params.opt("span"))
        preflightOrFail()
        approvePublish(origin, calls, SwarmAsk.Publish(origin, SwarmAsk.Publish.Kind.Chunk, payload.size.toLong(), null, null, emptyList()))
        val batch = batchFor(SwarmChunks.MAX_PAYLOAD.toLong())
        val cac = SwarmChunks.cac(payload, span)
        val answer = call("POST", "/chunks", uploadHeaders(batch, deferred = false), cac.data(), UPLOAD_TIMEOUT_MS)
        if (!answer.ok) throw Invalid(Reply.Err(INTERNAL, "Chunk upload failed: ${answer.message()}"))
        publishers.noteActivity()
        return Reply.Ok(JSONObject().put("reference", cac.address.swarmHex()))
    }

    private suspend fun uploadStatus(origin: String, params: JSONObject): Reply {
        val raw = params.opt("tagUid")
        val uid = (raw as? Number)?.takeIf { isWholeNumber(it) && it.toDouble() > 0 }?.toLong()
            ?: fail("tagUid must be a positive integer")
        if (tagOwners[uid] != origin) return notAuthorized("tag_ownership_mismatch")
        val answer = call("GET", "/tags/$uid", emptyMap(), null, READ_TIMEOUT_MS)
        val tag = answer.takeIf { it.ok }?.json() ?: return Reply.Err(INTERNAL, "Upload status failed: ${answer.message()}")
        val split = tag.optLong("split")
        val sent = tag.optLong("sent")
        val done = split > 0 && sent >= split
        if (done) tagOwners.remove(uid)
        return Reply.Ok(
            JSONObject()
                .put("tagUid", tag.optLong("uid", uid))
                .put("split", split)
                .put("seen", tag.optLong("seen"))
                .put("stored", tag.optLong("stored"))
                .put("sent", sent)
                .put("synced", tag.optLong("synced"))
                .put("progress", if (split > 0) Math.round(minOf(1.0, sent.toDouble() / split) * 100).toInt() else 0)
                .put("done", done),
        )
    }

    private suspend fun approvePublish(origin: String, calls: Calls, what: SwarmAsk.Publish) {
        if (!grants.autoApprove(origin, AutoApprove.Publish)) {
            val answer = calls.ask(what)
            if (!answer.allowed) throw Invalid(rejected())
            stillConnected(origin)
            if (answer.always) grants.setAutoApprove(origin, AutoApprove.Publish)
        }
        calls.committed()
    }

    // ---------------------------------------------------------------
    // Feeds and signing
    // ---------------------------------------------------------------

    /** Parameters a signing method was checked to have, for its sheet and its work. */
    private class Signed(
        val feedName: String?,
        /** The feed it signs for, whose own identity signs ([signerOf]); null for a new feed or a SOC. */
        val feed: FeedRecord?,
        val detail: String?,
        /** Whether it writes to Swarm, so the node must be able to publish before anyone is asked. */
        val writes: Boolean,
        val work: suspend () -> Reply,
    )

    /** The key a signing method signs with, and which identity it is. */
    private class Resolved(val identity: PublisherIdentity, val key: ByteArray)

    private suspend fun signing(origin: String, method: String, params: JSONObject, calls: Calls): Reply {
        val kind = if (method == "swarm_writeSingleOwnerChunk" || method == "swarm_getSigningIdentity") AutoApprove.Signing else AutoApprove.Feeds
        val signed = checkSigning(origin, method, params)
        if (signed.writes) preflightOrFail()
        // No wallet: the sheet comes first, gated like any other (its
        // tab on screen, the asking document still there, a refused
        // sheet blocking the tab), and approving it is what opens wallet
        // setup (SwarmProviders.askOnTab). A page never opens the wallet
        // page by itself.
        val needsWallet = !publishers.walletExists()
        val granted = !needsWallet && feeds.granted(origin)
        val autoApproved = granted && grants.autoApprove(origin, kind)
        if (!granted || !autoApproved || !publishers.unlocked()) {
            // The identity that will actually sign: a feed's own, not the site's active one.
            val site = publishers.site(origin)
            val identity = site?.let { signerOf(it, signed.feed) }
            // A feed whose own identity is gone can't be signed for: no sheet for it.
            if (signed.feed != null && identity == null) return feedOwnerGone(signed.feed)
            val answer = calls.ask(SwarmAsk.Sign(origin, method, kind, !granted, signed.feedName, signed.detail, identity, needsWallet))
            // Lost while queued behind another sheet: the same answer as above, not a refusal nobody made.
            // Also for a feed that was new when this was checked: an earlier queued ask may have
            // created it since, and its identity gone before this sheet was due ([current]).
            if (answer.ownerGone) return feedOwnerGone(signed.feed?.name ?: signed.feedName.orEmpty())
            if (!answer.allowed) return rejected()
            if (needsWallet && !publishers.walletExists()) return rejected()
            // Before feed access is given back to a site the user has disconnected since.
            stillConnected(origin)
            if (!feeds.granted(origin)) saving("the site's feed access") { feeds.grant(origin) }
            if (answer.always) grants.setAutoApprove(origin, kind)
            // The user's own yes counts as wallet activity (#236).
            publishers.noteActivity()
        }
        calls.committed()
        val reply = signed.work()
        // So does a write that went out, sheet or no sheet: a Swarm write the
        // user allowed, like a publish (#236's scope). It need not spend new
        // postage — rewriting the same SOC identifier can reuse the chunk's
        // stamp slot — so a page with Signing on "always allow" can still hold
        // the lock off by rewriting one SOC; that rule is the user's to revoke.
        // A read "always allow" answers with no sheet and no write (the signing
        // identity, an existing feed) doesn't count, or a page could keep the
        // wallet open just by polling it.
        if (signed.writes && reply is Reply.Ok) publishers.noteActivity()
        return reply
    }

    /**
     * [ask] as it stands now, for a sheet that only goes up once the
     * tab's earlier asks are over ([SwarmProviders]' askOnTab) — one of
     * which may have set up the wallet, granted feed access or created
     * the site's identity since [ask] was built. A [SwarmAsk.Sign]'s
     * "Signs as", first-grant wording and wallet setup are worked out
     * again; null when the feed it signs for has lost its own identity
     * meanwhile, so there's nothing to show.
     */
    suspend fun current(ask: SwarmAsk): SwarmAsk? {
        if (ask !is SwarmAsk.Sign) return ask
        return withContext(io) {
            val needsWallet = !publishers.walletExists()
            val granted = !needsWallet && feeds.granted(ask.origin)
            val feed = ask.feedName?.let { feeds.feed(ask.origin, it) }
            val identity = publishers.site(ask.origin)?.let { signerOf(it, feed) }
            if (feed != null && identity == null) return@withContext null
            ask.copy(grant = !granted, identity = identity, needsWallet = needsWallet)
        }
    }

    /**
     * Which of [site]'s identities signs for [feed]: the one it was
     * created with, or the site's active one for a new feed or a SOC. The
     * sheet's "Signs as" and the signature itself both come from here.
     * Null when [feed]'s own identity isn't [site]'s any more: never the
     * active one in its place, whose key doesn't own the feed.
     */
    private fun signerOf(site: SitePublisher, feed: FeedRecord?): PublisherIdentity? =
        if (feed == null) site.active else site.identities.firstOrNull { it.id == feed.identityId }

    /** [feed]'s owner isn't on this device (any more): nothing can sign for it. */
    private fun feedOwnerGone(feed: FeedRecord) = feedOwnerGone(feed.name)

    private fun feedOwnerGone(name: String) = Reply.Err(
        INTERNAL,
        "The identity that owns feed $name is no longer on this device",
        reason("feed_owner_unavailable"),
    )

    /**
     * Runs a write to this device's stores: its [IOException] (or
     * [IllegalStateException], with no wallet) is a storage failure,
     * never the node's.
     */
    private suspend fun <T> saving(what: String, block: () -> T): T = withContext(io) {
        try {
            block()
        } catch (e: IOException) {
            throw Invalid(Reply.Err(INTERNAL, "Couldn't save $what on this device"))
        } catch (e: IllegalStateException) {
            throw Invalid(Reply.Err(INTERNAL, "There is no wallet on this device"))
        }
    }

    /** Checks [method]'s parameters (before any sheet) and returns its work. */
    private fun checkSigning(origin: String, method: String, params: JSONObject): Signed = when (method) {
        "swarm_getSigningIdentity" -> Signed(null, null, Strings.get(R.string.swarm_sign_detail_identity), writes = false) {
            withKey(origin, null) { r ->
                Reply.Ok(
                    JSONObject().put("owner", PublisherKeys.address(r.key)).put("identityMode", r.identity.mode.wire),
                )
            }
        }
        "swarm_writeSingleOwnerChunk" -> {
            emptyOptions(params)
            val identifier = hex32(params.opt("identifier"), "invalid_identifier", "identifier")
            val payload = chunkPayload(params.opt("data"))
            val span = span(params.opt("span"))
            Signed(null, null, Strings.get(R.string.swarm_sign_detail_soc, identifier.swarmHex()), writes = true) {
                withKey(origin, null) { r -> writeSoc(r, identifier, payload, span) }
            }
        }
        "swarm_createFeed" -> {
            val name = feedName(params.opt("name"))
            // Creating an existing feed only returns it: nothing is written.
            val existing = feeds.feed(origin, name)
            Signed(name, existing, null, writes = existing == null) { createFeed(origin, name) }
        }
        "swarm_updateFeed" -> {
            val feedId = (params.opt("feedId") as? String)?.takeIf { it.isNotEmpty() } ?: fail("feedId is required")
            val reference = (params.opt("reference") as? String)?.takeIf { HEX64.matches(it) }
                ?: fail("reference must be a 64-character hex string", "invalid_reference")
            val feed = feeds.feed(origin, feedId) ?: fail("Feed not found: $feedId", "feed_not_found")
            Signed(feedId, feed, null, writes = true) { updateFeed(origin, feedId, reference.lowercase()) }
        }
        "swarm_writeFeedEntry" -> {
            val name = feedName(params.opt("name"))
            val data = params.opt("data")
            if (data == null || data == JSONObject.NULL) fail("data is required")
            val payload = payloadOf(data) ?: fail("data must be a string, Uint8Array, or ArrayBuffer")
            if (payload.isEmpty()) fail("data must not be empty")
            if (payload.size > MAX_DATA_BYTES) tooLarge("Payload exceeds maximum size of $MAX_DATA_BYTES bytes", MAX_DATA_BYTES, payload.size)
            val index = index(params.opt("index"))
            val feed = feeds.feed(origin, name) ?: fail("Feed not found: $name. Create it with createFeed first.", "feed_not_found")
            Signed(name, feed, null, writes = true) { writeFeedEntry(origin, name, payload, index) }
        }
        else -> fail("Unknown method: $method")
    }

    /**
     * Runs [block] with the signing key of [feed]'s identity (the site's
     * active one for a new feed or a SOC), zeroing it after. A feed whose
     * identity is gone, or whose key no longer derives its owner, is
     * refused ([feedOwnerGone]) rather than signed with another key.
     */
    private suspend fun withKey(origin: String, feed: FeedRecord?, block: suspend (Resolved) -> Reply): Reply {
        if (!feeds.granted(origin)) return notAuthorized("feed_not_granted")
        // An existing feed never gets the site a new identity: its own has to be there.
        val site = if (feed == null) {
            saving("the site's publisher identity") { publishers.ensureSite(origin) }
        } else {
            withContext(io) { publishers.site(origin) } ?: return feedOwnerGone(feed)
        }
        val identity = signerOf(site, feed) ?: return feedOwnerGone(feed!!)
        val key = withContext(io) { publishers.signingKey(identity) }
        if (feed != null && !PublisherKeys.address(key).equals(feed.owner, ignoreCase = true)) {
            key.fill(0)
            return feedOwnerGone(feed)
        }
        return try {
            block(Resolved(identity, key))
        } finally {
            key.fill(0)
        }
    }

    private suspend fun writeSoc(r: Resolved, identifier: ByteArray, payload: ByteArray, span: ULong?): Reply {
        val batch = batchFor(SwarmChunks.MAX_PAYLOAD.toLong())
        val soc = SwarmChunks.sign(identifier, SwarmChunks.cac(payload, span), r.key)
        uploadSoc(soc, batch)
        return Reply.Ok(
            JSONObject()
                .put("reference", soc.address.swarmHex())
                .put("owner", SwarmChunks.checksum(soc.owner))
                .put("identifier", identifier.swarmHex()),
        )
    }

    private suspend fun uploadSoc(soc: SwarmChunks.Soc, batch: String) {
        val path = "/soc/${soc.owner.swarmHex()}/${soc.identifier.swarmHex()}?sig=${soc.signature.swarmHex()}"
        val answer = call("POST", path, uploadHeaders(batch, deferred = false), soc.cac.data(), UPLOAD_TIMEOUT_MS)
        if (!answer.ok) throw Invalid(Reply.Err(INTERNAL, "SOC upload failed (${answer.status}): ${answer.message()}"))
        val reference = answer.json()?.optString("reference")?.removePrefix("0x")?.lowercase()
        if (reference.isNullOrEmpty()) throw Invalid(Reply.Err(INTERNAL, "SOC upload response missing reference"))
        if (reference != soc.address.swarmHex()) throw Invalid(Reply.Err(INTERNAL, "SOC upload returned unexpected reference: $reference"))
    }

    private suspend fun createFeed(origin: String, name: String): Reply {
        if (!feeds.granted(origin)) return notAuthorized("feed_not_granted")
        feeds.feed(origin, name)?.let { existing ->
            // The same answer updateFeed/writeFeedEntry give ([withKey]):
            // a feed whose own identity is gone, or whose key no longer
            // derives its owner, is refused — sheet or no sheet.
            return withKey(origin, existing) { r -> Reply.Ok(feedResult(existing, r.identity.mode.wire)) }
        }
        return withKey(origin, null) { r ->
            val owner = PublisherKeys.address(r.key)
            val topic = SwarmChunks.topic(feedTopicString(origin, name)).swarmHex()
            val batch = batchFor(SwarmChunks.MAX_PAYLOAD.toLong())
            val answer = call(
                "POST", "/feeds/${owner.removePrefix("0x").lowercase()}/$topic",
                mapOf("swarm-postage-batch-id" to batch, "swarm-pin" to "true"), null, UPLOAD_TIMEOUT_MS,
            )
            val manifest = answer.takeIf { it.ok }?.json()?.optString("reference")?.removePrefix("0x")?.lowercase()
                ?.takeIf { HEX64.matches(it) }
                ?: return@withKey Reply.Err(INTERNAL, "Feed manifest upload failed: ${answer.message()}")
            val record = FeedRecord(name, topic, owner, manifest, r.identity.id, clock())
            saving("the new feed's record (it was created on Swarm)") { feeds.put(origin, record) }
            Reply.Ok(feedResult(record, r.identity.mode.wire))
        }
    }

    private fun feedResult(record: FeedRecord, identityMode: String?) = JSONObject()
        .put("feedId", record.name)
        .put("owner", record.owner)
        .put("topic", record.topic)
        .put("manifestReference", record.manifestReference)
        .put("bzzUrl", "bzz://${record.manifestReference}")
        .put("identityMode", identityMode ?: JSONObject.NULL)

    private suspend fun updateFeed(origin: String, feedId: String, reference: String): Reply {
        if (!feeds.granted(origin)) return notAuthorized("feed_not_granted")
        val feed = feeds.feed(origin, feedId)
            ?: return invalid("Feed not found: $feedId", "feed_not_found")
        return withKey(origin, feed) { r ->
            val index = locked(feed.topic) {
                val next = nextIndex(ownerOf(r), feed.topic)
                val payload = SwarmChunks.referenceUpdate(reference.hexToBytesOrNull()!!, clock() / 1000)
                val batch = batchFor(SwarmChunks.MAX_PAYLOAD.toLong())
                uploadSoc(SwarmChunks.sign(SwarmChunks.feedIdentifier(feed.topic.hexToBytesOrNull()!!, next), SwarmChunks.cac(payload), r.key), batch)
                next
            }
            saving("the feed's record (the update was published)") {
                feeds.put(origin, feed.copy(lastUpdated = clock(), lastReference = reference))
            }
            Reply.Ok(
                JSONObject()
                    .put("feedId", feedId)
                    .put("reference", reference)
                    .put("bzzUrl", "bzz://${feed.manifestReference}")
                    .put("index", index),
            )
        }
    }

    private suspend fun writeFeedEntry(origin: String, name: String, payload: ByteArray, index: Long?): Reply {
        if (!feeds.granted(origin)) return notAuthorized("feed_not_granted")
        val feed = feeds.feed(origin, name)
            ?: return invalid("Feed not found: $name. Create it with createFeed first.", "feed_not_found")
        return withKey(origin, feed) { r ->
            val owner = ownerOf(r)
            val topic = feed.topic.hexToBytesOrNull()!!
            val written = locked(feed.topic) {
                val at = if (index != null) {
                    val existing = chunk(SwarmChunks.socAddress(SwarmChunks.feedIdentifier(topic, index), owner.hexToBytesOrNull()!!))
                    if (existing != null) {
                        throw Invalid(invalid("Feed entry already exists at index $index", "index_already_exists"))
                    }
                    index
                } else {
                    nextIndex(owner, feed.topic)
                }
                val batch = batchFor(maxOf(payload.size, SwarmChunks.MAX_PAYLOAD).toLong())
                val cac = if (payload.size <= SwarmChunks.MAX_PAYLOAD) SwarmChunks.cac(payload) else wrapLarge(payload, batch)
                uploadSoc(SwarmChunks.sign(SwarmChunks.feedIdentifier(topic, at), cac, r.key), batch)
                at
            }
            Reply.Ok(JSONObject().put("index", written))
        }
    }

    /** The key's owner address, 40 lower-case hex. */
    private fun ownerOf(r: Resolved) = PublisherKeys.address(r.key).removePrefix("0x").lowercase()

    /**
     * A payload over one chunk: uploaded as bytes, and its root chunk
     * wrapped in the SOC, as bee-js does.
     */
    private suspend fun wrapLarge(payload: ByteArray, batch: String): SwarmChunks.Cac {
        val answer = call("POST", "/bytes", uploadHeaders(batch, deferred = false), payload, UPLOAD_TIMEOUT_MS)
        val reference = uploadedReference(answer)
        val raw = chunk(reference.hexToBytesOrNull()!!) ?: throw Invalid(Reply.Err(INTERNAL, "Uploaded data's root chunk can't be read"))
        return SwarmChunks.parseCac(reference.hexToBytesOrNull()!!, raw)
            ?: throw Invalid(Reply.Err(INTERNAL, "Uploaded data's root chunk doesn't match its reference"))
    }

    private suspend fun <T> locked(topic: String, block: suspend () -> T): T =
        writeLocks.getOrPut(topic) { Mutex() }.withLock { block() }

    /** The index after the feed's latest entry, 0 for an empty feed. */
    private suspend fun nextIndex(owner: String, topic: String): Long {
        val latest = latestIndex(owner, topic) ?: return 0
        return latest.second ?: (latest.first + 1)
    }

    /** The feed's latest index and the next one (if the node said), or null for an empty feed. */
    private suspend fun latestIndex(owner: String, topic: String): Pair<Long, Long?>? {
        val answer = call("GET", "/feeds/$owner/$topic", mapOf("swarm-only-root-chunk" to "true"), null, READ_TIMEOUT_MS)
        if (answer.status == 404) return null
        if (!answer.ok) throw Invalid(Reply.Err(INTERNAL, "Feed lookup failed: ${answer.message()}"))
        val index = answer.headers["swarm-feed-index"]?.let { parseFeedIndex(it) }
            ?: throw Invalid(Reply.Err(INTERNAL, "Feed lookup returned no index"))
        return index to answer.headers["swarm-feed-index-next"]?.let { parseFeedIndex(it) }
    }

    // ---------------------------------------------------------------
    // Messaging (#121)
    // ---------------------------------------------------------------

    /**
     * The messaging tier's consent for [what]: the grant sheet the first
     * time (it covers a send that asked too), and then, for a send, a sheet
     * per message unless the site's messaging is "always allow". Throws a
     * refusal. The page is told the request is approved either way.
     */
    private suspend fun approveMessaging(origin: String, calls: Calls, what: SwarmAsk.Message) {
        if (!grants.messaging(origin)) {
            if (!calls.ask(what.copy(grant = true)).allowed) throw Invalid(rejected())
            if (!grants.grantMessaging(origin)) throw Invalid(Reply.Err(INTERNAL, "Couldn't save the messaging permission"))
        } else if (what.send != null && !grants.autoApprove(origin, AutoApprove.Messaging)) {
            val answer = calls.ask(what)
            if (!answer.allowed) throw Invalid(rejected())
            stillConnected(origin)
            if (answer.always) grants.setAutoApprove(origin, AutoApprove.Messaging)
        }
        calls.committed()
    }

    /**
     * desktop's `getMessagingIdentity`: the node's PSS key, which peers
     * encrypt to, and the first [DEFAULT_TARGET_DEPTH] bytes of its
     * overlay, which they send toward. Both are node-wide, so every site
     * holding the messaging grant gets the same key and can link the user
     * across those sites by it (the grant sheet says so); that's inherent
     * to PSS, since peers must encrypt to the one key the node decrypts
     * with. The overlay is cut to its prefix only because the full address
     * pins the node's exact place in the network and a sender needs no
     * more than the prefix; it isn't what keeps sites apart.
     */
    private suspend fun messagingIdentity(origin: String, calls: Calls): Reply {
        reachableOrFail()
        approveMessaging(origin, calls, SwarmAsk.Message(origin, SwarmAsk.Message.Op.Identity, null, null, 0))
        val answer = call("GET", "/addresses", emptyMap(), null, PREFLIGHT_TIMEOUT_MS)
        val addresses = answer.takeIf { it.ok }?.json()
            ?: return Reply.Err(INTERNAL, "Couldn't read the node's addresses: ${answer.message()}")
        val key = compressedKey(addresses.optString("pssPublicKey"))
            ?: return Reply.Err(INTERNAL, "The node has no usable PSS key")
        val overlay = addresses.optString("overlay").removePrefix("0x").lowercase()
        if (!HEX64.matches(overlay)) return Reply.Err(INTERNAL, "The node has no usable overlay address")
        return Reply.Ok(
            JSONObject()
                .put("pssPublicKey", key)
                .put("pssTarget", overlay.substring(0, DEFAULT_TARGET_DEPTH * 2))
                .put("identityMode", "bee-wallet"),
        )
    }

    /** desktop's `sendPss`: an encrypted message to [recipient]'s key, mined toward `targets` by the node. */
    private suspend fun sendPss(origin: String, params: JSONObject, calls: Calls): Reply {
        emptyOptions(params)
        val topic = messagingTopic(params.opt("topic"))
        val recipient = (params.opt("recipient") as? String)?.removePrefix("0x")
        if (recipient == null || !COMPRESSED_KEY.matches(recipient)) {
            fail("recipient must be a 66-character hex compressed secp256k1 public key", "invalid_recipient")
        }
        val targets = params.opt("targets") as? String
        if (targets == null || !EVEN_HEX.matches(targets) || targets.length / 2 !in DEFAULT_TARGET_DEPTH..MAX_TARGET_DEPTH) {
            fail("targets must be hex encoding $DEFAULT_TARGET_DEPTH-$MAX_TARGET_DEPTH whole bytes", "invalid_target")
        }
        val payload = messagePayload(params.opt("data"), emptyReason = null)
        preflightOrFail()
        approveMessaging(origin, calls, SwarmAsk.Message(origin, SwarmAsk.Message.Op.Send, SwarmAsk.Message.Kind.Pss, topic, payload.size))
        val batch = batchFor(SwarmChunks.MAX_PAYLOAD.toLong(), allowFullMutable = true)
        val path = "/pss/send/${SwarmChunks.topic(topic).swarmHex()}/${targets.lowercase()}?recipient=${recipient.lowercase()}"
        val answer = call("POST", path, mapOf("swarm-postage-batch-id" to batch), payload, UPLOAD_TIMEOUT_MS)
        if (!answer.ok) return Reply.Err(INTERNAL, "PSS send failed: ${answer.message()}")
        return Reply.Ok(JSONObject().put("sent", true))
    }

    /**
     * desktop's `sendGsoc`: a message to [topic]'s room, a SOC signed with
     * the room's mined key ([SwarmGsoc]). Not to a bare address: the key
     * only comes out of the topic, so an address alone has nothing to sign with.
     */
    private suspend fun sendGsoc(origin: String, params: JSONObject, calls: Calls): Reply {
        emptyOptions(params)
        if (params.opt("address").let { it != null && it != JSONObject.NULL }) {
            fail(
                "Sending to a raw GSOC address is not supported: the signing key derives from the topic. Provide topic instead.",
                "invalid_address",
            )
        }
        val topic = messagingTopic(params.opt("topic"))
        // A GSOC message is a SOC, and the node refuses an empty one.
        val payload = messagePayload(params.opt("data"), emptyReason = "invalid_payload")
        preflightOrFail()
        approveMessaging(origin, calls, SwarmAsk.Message(origin, SwarmAsk.Message.Op.Send, SwarmAsk.Message.Kind.Gsoc, topic, payload.size))
        val room = withContext(io) { SwarmGsoc.derive(topic) }
        val batch = batchFor(SwarmChunks.MAX_PAYLOAD.toLong(), allowFullMutable = true)
        uploadSoc(SwarmChunks.sign(room.identifier, SwarmChunks.cac(payload), room.privateKey), batch)
        return Reply.Ok(JSONObject().put("sent", true).put("address", room.address))
    }

    /**
     * desktop's `subscribe`: a room's messages (`gsoc`, by topic or its
     * address) or the PSS messages this node receives on a topic (`pss`),
     * pushed to the asking document as `message` events until it
     * unsubscribes or goes away.
     */
    private suspend fun subscribe(origin: String, params: JSONObject, calls: Calls): Reply {
        emptyOptions(params)
        val kind = params.opt("kind")
        if (kind != "gsoc" && kind != "pss") fail("kind must be \"gsoc\" or \"pss\"", "invalid_kind")
        kind as String
        fun given(key: String) = params.opt(key).let { it != null && it != JSONObject.NULL }
        val hasTopic = given("topic")
        val hasAddress = given("address")
        var key: String? = null
        if (kind == "gsoc") {
            if (hasTopic == hasAddress) fail("Provide either topic or address, not both")
            if (hasAddress) {
                val address = params.opt("address") as? String
                if (address == null || !HEX64.matches(address)) fail("address must be a 64-character hex string", "invalid_address")
                key = address.lowercase()
            }
        } else {
            if (hasAddress) fail("address is only valid for gsoc subscriptions")
            if (!hasTopic) fail("topic is required", "invalid_topic")
        }
        val topic = if (hasTopic) messagingTopic(params.opt("topic")) else null
        val subscriber = calls.subscriber ?: fail("subscribe requires a page to deliver to")
        reachableOrFail()
        approveMessaging(origin, calls, SwarmAsk.Message(origin, SwarmAsk.Message.Op.Subscribe, null, topic, 0, address = key))
        // Topic-derived: GSOC mines the room's address, PSS hashes the topic.
        val resolved = key ?: if (kind == "gsoc") withContext(io) { SwarmGsoc.derive(topic!!).address } else SwarmChunks.topic(topic!!).swarmHex()
        if (!subscriber.live()) return subscriptionCancelled()
        val id = try {
            // Still the site's once it's up: a Disconnect while the room
            // was mined or the socket came up must not leave it live.
            subscriptions.subscribe(origin, kind, resolved, subscriber) { grants.messaging(origin) }
        } catch (e: SwarmSubscriptions.Failure) {
            return when (e.reason) {
                SwarmSubscriptions.REVOKED -> notConnected()
                "too_many_subscriptions" -> invalid(
                    e.message.orEmpty(), e.reason, JSONObject().put("limit", SwarmSubscriptions.MAX_SUBSCRIPTIONS),
                )
                "node_subscription_limit" -> Reply.Err(
                    UNAVAILABLE, "Node subscription capacity exhausted: ${e.message}", reason(e.reason),
                )
                // establish_timeout, subscription_cancelled, cancelled (its page went while it came up): retryable.
                else -> Reply.Err(UNAVAILABLE, e.message.orEmpty(), reason(e.reason))
            }
        }
        return Reply.Ok(JSONObject().put("subscriptionId", id).put("kind", kind).put("key", resolved))
    }

    private fun subscriptionCancelled() = Reply.Err(
        UNAVAILABLE, "Page navigated away before the subscription was established", reason("subscription_cancelled"),
    )

    /** desktop's `unsubscribe`: never asks, and a site can only close its own. */
    private fun unsubscribe(origin: String, params: JSONObject): Reply {
        val id = (params.opt("subscriptionId") as? String)?.takeIf { it.isNotEmpty() } ?: fail("subscriptionId is required")
        if (!subscriptions.unsubscribe(origin, id)) return invalid("No active subscription: $id", "subscription_not_found")
        return Reply.Ok(JSONObject().put("unsubscribed", true))
    }

    /**
     * desktop's `validateMessagingTopic`: hashed before the wire, so only
     * sanity limits. Like desktop it refuses only C0 controls (U+0000 to
     * U+001F): DEL and C1 controls pass, so a topic a desktop page joins
     * can be joined here too, and the sheet writes them out escaped
     * ([swarmShownTopic]). The error names exactly that range.
     */
    private fun messagingTopic(value: Any?): String {
        if (value !is String || value.isEmpty()) fail("topic must be a non-empty string", "invalid_topic")
        if (value.toByteArray(Charsets.UTF_8).size > MAX_TOPIC_BYTES) fail("topic exceeds $MAX_TOPIC_BYTES UTF-8 bytes", "invalid_topic")
        if (value.any { it.code < 32 }) fail("topic must not contain C0 control characters (U+0000 to U+001F)", "invalid_topic")
        return value
    }

    /** A message's bytes, at most [MAX_MESSAGE_BYTES]; empty only where [emptyReason] is null (PSS carries its length). */
    private fun messagePayload(data: Any?, emptyReason: String?): ByteArray {
        val payload = payloadOf(data) ?: fail("data must be a string, Uint8Array, or ArrayBuffer")
        if (payload.isEmpty() && emptyReason != null) fail("data must not be empty", emptyReason)
        if (payload.size > MAX_MESSAGE_BYTES) {
            tooLarge("Payload exceeds maximum message size of $MAX_MESSAGE_BYTES bytes", MAX_MESSAGE_BYTES, payload.size)
        }
        return payload
    }

    // ---------------------------------------------------------------
    // Reads (no permission)
    // ---------------------------------------------------------------

    private suspend fun readChunk(origin: String, params: JSONObject): Reply {
        emptyOptions(params)
        val reference = hex32(params.opt("reference"), "invalid_reference", "reference")
        spendBudget(origin, requests = 1)
        reachableOrFail()
        val raw = chunk(reference) ?: return invalid("Chunk not found: ${reference.swarmHex()}", "chunk_not_found")
        val cac = SwarmChunks.parseCac(reference, raw)
            ?: return invalid("Downloaded bytes do not validate as the requested content-addressed chunk", "chunk_type_mismatch")
        spendBudget(origin, bytes = base64Length(cac.payload.size))
        return Reply.Ok(
            JSONObject()
                .put("data", Base64.getEncoder().encodeToString(cac.payload))
                .put("encoding", "base64")
                .put("span", spanJson(cac.spanValue)),
        )
    }

    private suspend fun readSoc(origin: String, params: JSONObject): Reply {
        emptyOptions(params)
        fun given(key: String) = params.opt(key).let { it != null && it != JSONObject.NULL }
        val hasAddress = given("address")
        val hasOwner = given("owner")
        val hasIdentifier = given("identifier")
        if (hasAddress && (hasOwner || hasIdentifier)) fail("Provide either address, or owner + identifier, not both")
        if (!hasAddress && (!hasOwner || !hasIdentifier)) fail("Either address, or owner + identifier, is required")
        val address = if (hasAddress) {
            hex32(params.opt("address"), "invalid_reference", "address")
        } else {
            val owner = ownerParam(params.opt("owner"))
            val identifier = hex32(params.opt("identifier"), "invalid_identifier", "identifier")
            SwarmChunks.socAddress(identifier, owner)
        }
        spendBudget(origin, requests = 1)
        reachableOrFail()
        val raw = chunk(address) ?: return invalid("Single Owner Chunk not found", "chunk_not_found")
        val soc = SwarmChunks.parseSoc(address, raw)
            ?: return invalid("Downloaded bytes do not validate as the requested Single Owner Chunk", "chunk_type_mismatch")
        spendBudget(origin, bytes = base64Length(soc.cac.payload.size))
        return Reply.Ok(
            JSONObject()
                .put("data", Base64.getEncoder().encodeToString(soc.cac.payload))
                .put("encoding", "base64")
                .put("span", spanJson(soc.cac.spanValue))
                .put("reference", address.swarmHex())
                .put("owner", SwarmChunks.checksum(soc.owner))
                .put("identifier", soc.identifier.swarmHex())
                .put("signature", soc.signature.swarmHex()),
        )
    }

    private suspend fun readFeedEntry(origin: String, params: JSONObject): Reply {
        fun given(key: String) = params.opt(key).let { it != null && it != JSONObject.NULL }
        val hasTopic = given("topic")
        val hasName = given("name")
        if (hasTopic && hasName) fail("Provide either topic or name, not both")
        if (!hasTopic && !hasName) fail("Either topic or name is required")
        val index = index(params.opt("index"))
        val topic: String
        val owner: String
        if (hasTopic) {
            topic = (params.opt("topic") as? String)?.takeIf { HEX64.matches(it) }?.lowercase()
                ?: fail("topic must be a 64-character hex string", "invalid_topic")
            val rawOwner = params.opt("owner") as? String
            if (rawOwner.isNullOrEmpty()) fail("owner is required when using topic", "invalid_owner")
            owner = ownerParam(rawOwner).swarmHex()
        } else {
            val name = feedName(params.opt("name"))
            topic = SwarmChunks.topic(feedTopicString(origin, name)).swarmHex()
            owner = if (given("owner")) {
                ownerParam(params.opt("owner")).swarmHex()
            } else {
                feeds.feed(origin, name)?.owner?.removePrefix("0x")?.lowercase()
                    ?: fail("Feed not found: $name. Create it first or provide owner explicitly.", "feed_not_found")
            }
        }
        spendBudget(origin, requests = 1)
        reachableOrFail()
        val at = index ?: (latestIndex(owner, topic)?.first ?: return invalid("Feed is empty — no entries to read", "feed_empty"))
        val address = SwarmChunks.socAddress(SwarmChunks.feedIdentifier(topic.hexToBytesOrNull()!!, at), owner.hexToBytesOrNull()!!)
        val raw = chunk(address) ?: return if (index != null) {
            invalid("Feed entry not found at index $index", "entry_not_found")
        } else {
            invalid("Feed is empty — no entries to read", "feed_empty")
        }
        val soc = SwarmChunks.parseSoc(address, raw)
            ?: return Reply.Err(INTERNAL, "Feed entry at index $at does not validate as its owner's chunk")
        val payload = if (soc.cac.spanValue <= SwarmChunks.MAX_PAYLOAD.toULong()) {
            soc.cac.payload
        } else {
            val answer = call("GET", "/bytes/${soc.cac.address.swarmHex()}", emptyMap(), null, READ_TIMEOUT_MS)
            if (!answer.ok) return Reply.Err(INTERNAL, "Feed entry content can't be read: ${answer.message()}")
            answer.body
        }
        spendBudget(origin, bytes = payload.size.toLong())
        return Reply.Ok(
            JSONObject()
                .put("data", Base64.getEncoder().encodeToString(payload))
                .put("encoding", "base64")
                .put("index", at)
                .put("nextIndex", at + 1),
        )
    }

    private suspend fun listFeeds(origin: String): Reply {
        val list = JSONArray()
        for (f in feeds.all(origin)) {
            list.put(
                JSONObject()
                    .put("name", f.name)
                    .put("topic", f.topic)
                    .put("owner", f.owner)
                    .put("manifestReference", f.manifestReference)
                    .put("bzzUrl", "bzz://${f.manifestReference}")
                    .put("createdAt", f.createdAt)
                    .put("lastUpdated", f.lastUpdated ?: JSONObject.NULL)
                    .put("lastReference", f.lastReference ?: JSONObject.NULL),
            )
        }
        spendBudget(origin, requests = 1, bytes = list.toString().toByteArray(Charsets.UTF_8).size.toLong())
        return Reply.Ok(list)
    }

    /** Desktop's permission-free read budget: per origin, a rolling window of requests and bytes. */
    private suspend fun spendBudget(origin: String, requests: Int = 0, bytes: Long = 0) {
        val connected = grants.connected(origin)
        val maxRequests = if (connected) 600 else 120
        val maxBytes = if (connected) 5L * 1024 * 1024 else 512L * 1024
        val over = synchronized(budgets) {
            val now = clock()
            // Drop every window that has run out, so origins that stopped reading don't stay forever.
            budgets.values.removeAll { now - it.startedAt !in 0 until BUDGET_WINDOW_MS }
            val existing = budgets[origin]
            val bucket = if (existing != null && now - existing.startedAt in 0 until BUDGET_WINDOW_MS) existing else Budget(now, 0, 0)
            bucket.requests += requests
            bucket.bytes += bytes
            budgets[origin] = bucket
            bucket.requests > maxRequests || bucket.bytes > maxBytes
        }
        if (over) {
            fail(
                "Permission-free read budget exceeded", "rate_limited",
                JSONObject().put("windowMs", BUDGET_WINDOW_MS).put("maxRequests", maxRequests).put("maxBytes", maxBytes),
            )
        }
    }

    // ---------------------------------------------------------------
    // The node
    // ---------------------------------------------------------------

    /**
     * Why the node can't publish now — `node-stopped`, `ultra-light-mode`,
     * `node-not-ready`, `no-usable-stamps` — or null if it can. Blocking.
     */
    internal fun preflight(): String? = try {
        fun get(path: String) = node.request("GET", path, emptyMap(), null, PREFLIGHT_TIMEOUT_MS)
        val info = get("/node")
        when {
            !info.ok -> "node-stopped"
            info.json()?.optString("beeMode")?.lowercase() in setOf("ultra-light", "ultralight") -> "ultra-light-mode"
            !get("/readiness").ok -> "node-not-ready"
            usableBatches().isEmpty() -> "no-usable-stamps"
            else -> null
        }
    } catch (e: IOException) {
        "node-stopped"
    }

    private suspend fun preflightOrFail() {
        val reason = withContext(io) { preflight() } ?: return
        throw Invalid(Reply.Err(UNAVAILABLE, "Node not available: $reason", reason(reason)))
    }

    private suspend fun reachableOrFail() {
        val ok = withContext(io) {
            try {
                node.request("GET", "/node", emptyMap(), null, PREFLIGHT_TIMEOUT_MS).ok
            } catch (e: IOException) {
                false
            }
        }
        if (!ok) throw Invalid(Reply.Err(UNAVAILABLE, "Node not available: node-stopped", reason("node-stopped")))
    }

    private fun usableBatches(): List<PostageBatch> {
        val answer = node.request("GET", "/stamps", emptyMap(), null, PREFLIGHT_TIMEOUT_MS)
        if (!answer.ok) return emptyList()
        return stampsFrom(String(answer.body, Charsets.UTF_8)).orEmpty().filter { it.usable }
    }

    /**
     * desktop's `selectBestBatch`: of the usable batches with room for
     * half again [size], the one that lasts longest.
     */
    private suspend fun batchFor(size: Long, allowFullMutable: Boolean = false): String {
        val batches = withContext(io) { usableBatches() }
        return selectBatch(batches, size, allowFullMutable)
            ?: throw Invalid(Reply.Err(INTERNAL, "No usable postage batch available. Purchase stamps first."))
    }

    private suspend fun call(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): Http.Answer = withContext(io) { node.request(method, path, headers, body, timeoutMs) }

    /** The chunk at [address] (`/chunks`), or null if the node can't find it. */
    private suspend fun chunk(address: ByteArray): ByteArray? {
        val answer = call("GET", "/chunks/${address.swarmHex()}", emptyMap(), null, READ_TIMEOUT_MS)
        return when {
            answer.ok -> answer.body
            answer.status == 404 -> null
            // bee says "read chunk failed" with a 500 for a chunk it can't find.
            answer.status == 500 && answer.message().trim().equals("read chunk failed", ignoreCase = true) -> null
            else -> throw Invalid(Reply.Err(INTERNAL, "Chunk read failed: ${answer.message()}"))
        }
    }

    private fun uploadedReference(answer: Http.Answer): String {
        if (!answer.ok) throw Invalid(Reply.Err(INTERNAL, "Upload failed: ${answer.message()}"))
        return answer.json()?.optString("reference")?.removePrefix("0x")?.lowercase()?.takeIf { HEX64.matches(it) }
            ?: throw Invalid(Reply.Err(INTERNAL, "Upload response missing reference"))
    }

    private fun uploadHeaders(batch: String, deferred: Boolean) = mapOf(
        "swarm-postage-batch-id" to batch,
        "swarm-pin" to "true",
        "swarm-deferred-upload" to deferred.toString(),
    )

    // ---------------------------------------------------------------
    // Parameters
    // ---------------------------------------------------------------

    private class Invalid(val error: Reply.Err) : Exception(null, null, false, false)

    private fun fail(message: String, reason: String = "invalid_params", extra: JSONObject? = null): Nothing =
        throw Invalid(invalid(message, reason, extra))

    private fun tooLarge(message: String, limit: Int, actual: Number): Nothing =
        fail(message, "payload_too_large", JSONObject().put("limit", limit).put("actual", actual))

    private fun emptyOptions(params: JSONObject) {
        val options = params.opt("options")
        if (options == null || options == JSONObject.NULL) return
        if (options !is JSONObject) fail("options must be an object")
        options.keys().asSequence().firstOrNull()?.let {
            fail("Unsupported option: $it", "unsupported_option", JSONObject().put("option", it))
        }
    }

    private fun hex32(value: Any?, reason: String, field: String): ByteArray {
        val s = value as? String
        if (s == null || !HEX64.matches(s)) fail("$field must be a 64-character hex string", reason)
        return s.hexToBytesOrNull()!!
    }

    private fun ownerParam(value: Any?): ByteArray {
        val s = (value as? String)?.removePrefix("0x")
        if (s == null || !HEX40.matches(s)) fail("owner must be a valid 40-character hex address", "invalid_owner")
        return s.hexToBytesOrNull()!!
    }

    private fun chunkPayload(data: Any?): ByteArray {
        val payload = payloadOf(data) ?: fail("data must be a string, Uint8Array, or ArrayBuffer")
        if (payload.isEmpty()) fail("data must not be empty")
        if (payload.size > SwarmChunks.MAX_PAYLOAD) {
            tooLarge("Payload exceeds maximum chunk size of ${SwarmChunks.MAX_PAYLOAD} bytes", SwarmChunks.MAX_PAYLOAD, payload.size)
        }
        return payload
    }

    private fun span(value: Any?): ULong? {
        if (value == null || value == JSONObject.NULL) return null
        val parsed: ULong? = when {
            value is Number && isWholeNumber(value) && value.toDouble() >= 0 && value.toDouble() <= MAX_SAFE_INTEGER ->
                value.toLong().toULong()
            value is JSONObject && value.length() == 1 && value.opt(BIGINT) is String ->
                (value.getString(BIGINT)).toULongOrNull()
            else -> null
        }
        return parsed ?: fail("span must be a non-negative unsigned 64-bit integer", "invalid_span")
    }

    private fun index(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        if (value !is Number || !isWholeNumber(value) || value.toDouble() < 0 || value.toDouble() > MAX_SAFE_INTEGER) {
            fail("index must be a non-negative integer")
        }
        return value.toLong()
    }

    private fun feedName(value: Any?): String {
        feedNameError(value)?.let { fail(it, "invalid_feed_name") }
        return value as String
    }

    private fun invalid(message: String, reason: String, extra: JSONObject? = null) =
        Reply.Err(INVALID_PARAMS, message, (extra ?: JSONObject()).put("reason", reason))

    private fun notConnected() = notAuthorized("not_connected")

    /**
     * After a sheet: [origin] is still connected. The user may have
     * disconnected it (the wallet page) while its sheet was up; an Allow
     * tapped after that neither carries the request out nor gives the
     * site back the feed access or "always allow" the disconnect took.
     */
    private suspend fun stillConnected(origin: String) {
        if (!grants.connected(origin)) throw Invalid(notConnected())
    }

    private fun notAuthorized(reason: String) =
        Reply.Err(UNAUTHORIZED, "The origin is not authorized for this operation", reason(reason))

    private fun rejected() = Reply.Err(USER_REJECTED, "User rejected the request")

    private fun reason(reason: String): JSONObject = JSONObject().put("reason", reason)

    /** The topic string for [origin]'s feed [name]: desktop's `buildTopicString`. */
    private fun feedTopicString(origin: String, name: String) = "${swarmOriginKey(origin)}/$name"

    companion object {
        const val SPEC_VERSION = "1.0"

        const val USER_REJECTED = 4001
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED = 4200
        const val UNAVAILABLE = 4900
        const val INVALID_PARAMS = -32602
        const val INTERNAL = -32603

        const val MAX_DATA_BYTES = 10 * 1024 * 1024
        const val MAX_CONTENT_TYPE_CHARS = 256
        const val MAX_FILES_BYTES = 50 * 1024 * 1024
        const val MAX_FILE_COUNT = 100
        const val MAX_PATH_BYTES = 100
        const val BUDGET_WINDOW_MS = 60_000L

        /** What a PSS or GSOC message may carry: ant's 4096 − 3×32 (`MAX_PAYLOAD_SIZE`). */
        const val MAX_MESSAGE_BYTES = 4000

        /**
         * PSS target prefixes, in bytes: [DEFAULT_TARGET_DEPTH] is the
         * network's convention (ant's receiver assumes senders mine 2
         * bytes) and the floor below which no storer keeps the message;
         * [MAX_TARGET_DEPTH] is ant's cap on the sender's mining work.
         */
        const val DEFAULT_TARGET_DEPTH = 2
        const val MAX_TARGET_DEPTH = 3
        const val MAX_TOPIC_BYTES = 256

        /** How an unsigned value over JS's safe-integer range crosses the channel: `{"$bigint": "…"}`. */
        const val BIGINT = "\$bigint"

        /** How bytes cross the channel: `{"$b64": "…"}`. */
        const val BYTES = "\$b64"

        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

        const val VAULT_LOCKED = "The wallet is locked. Unlock it and try again."

        private const val PREFLIGHT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val UPLOAD_TIMEOUT_MS = 5 * 60_000

        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
        private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
        private val COMPRESSED_KEY = Regex("^0[23][0-9a-fA-F]{64}$")
        private val EVEN_HEX = Regex("^([0-9a-fA-F]{2})+$")

        /** No node sockets (a provider without messaging wiring): every subscription fails to come up. */
        private val NO_SOCKET = object : SwarmSubscriptions.Socket {
            override val established = kotlinx.coroutines.CompletableDeferred<Unit>().apply {
                completeExceptionally(SwarmSubscriptions.Failure("node-stopped", "Swarm node is not available"))
            }
            override fun cancel() = Unit
        }

        /**
         * A secp256k1 public key as 66 lower-case hex (SEC1 compressed), from
         * the compressed or uncompressed hex the node reports; null if it's neither.
         */
        internal fun compressedKey(hex: String): String? {
            val h = hex.removePrefix("0x").lowercase()
            if (COMPRESSED_KEY.matches(h)) return h
            if (h.length != 130 || !h.startsWith("04")) return null
            val y = h.substring(66).toBigIntegerOrNull(16) ?: return null
            if (h.substring(2, 66).toBigIntegerOrNull(16) == null) return null
            return (if (y.testBit(0)) "03" else "02") + h.substring(2, 66)
        }

        val KNOWN_METHODS = setOf(
            "swarm_requestAccess",
            "swarm_getCapabilities",
            "swarm_publishData",
            "swarm_publishFiles",
            "swarm_getUploadStatus",
            "swarm_createFeed",
            "swarm_updateFeed",
            "swarm_writeFeedEntry",
            "swarm_readFeedEntry",
            "swarm_listFeeds",
            "swarm_publishChunk",
            "swarm_readChunk",
            "swarm_writeSingleOwnerChunk",
            "swarm_readSingleOwnerChunk",
            "swarm_getSigningIdentity",
            "swarm_getMessagingIdentity",
            "swarm_subscribe",
            "swarm_unsubscribe",
            "swarm_sendPss",
            "swarm_sendGsoc",
        )

        /** The messaging extension's (#121). */
        val MESSAGING_METHODS = setOf(
            "swarm_getMessagingIdentity",
            "swarm_subscribe",
            "swarm_unsubscribe",
            "swarm_sendPss",
            "swarm_sendGsoc",
        )

        private fun isWholeNumber(n: Number): Boolean = when (n) {
            is Int, is Long, is Short, is Byte -> true
            is Double -> !n.isNaN() && !n.isInfinite() && n == Math.floor(n)
            is Float -> !n.isNaN() && !n.isInfinite() && n.toDouble() == Math.floor(n.toDouble())
            else -> false
        }

        private fun base64Length(n: Int): Long = ((n + 2) / 3) * 4L

        private fun spanJson(v: ULong): Any =
            if (v.toDouble() <= MAX_SAFE_INTEGER) v.toLong() else JSONObject().put(BIGINT, v.toString())

        /** A `swarm-feed-index` header (16 hex digits, big-endian) as a number, or null. */
        internal fun parseFeedIndex(hex: String): Long? =
            hex.trim().takeIf { it.isNotEmpty() && it.length <= 16 }?.toULongOrNull(16)?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()

        /**
         * Bytes as the page script sends them (`{"$b64": "…"}`), or the
         * JSON form of a Node `Buffer` (`{"type":"Buffer","data":[…]}`);
         * null for anything else.
         */
        internal fun bytesOf(value: Any?): ByteArray? {
            if (value !is JSONObject) return null
            val b64 = value.opt(BYTES)
            if (b64 is String && value.length() == 1) {
                return try {
                    Base64.getDecoder().decode(b64)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            if (value.opt("type") == "Buffer") {
                val data = value.opt("data") as? JSONArray ?: return null
                val out = ByteArray(data.length())
                for (i in 0 until data.length()) {
                    val v = data.opt(i) as? Number ?: return null
                    if (!isWholeNumber(v) || v.toInt() !in 0..255) return null
                    out[i] = v.toInt().toByte()
                }
                return out
            }
            return null
        }

        /** A payload parameter: a string as its UTF-8, or bytes ([bytesOf]). */
        internal fun payloadOf(value: Any?): ByteArray? = when (value) {
            is String -> value.toByteArray(Charsets.UTF_8)
            else -> bytesOf(value)
        }

        /** Why [p] can't be a path in a published collection (desktop's `validateVirtualPath`), or null if it can. */
        internal fun virtualPathError(p: Any?): String? {
            if (p !is String || p.isEmpty()) return "Path must be a non-empty string"
            if (p.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) return "Path exceeds $MAX_PATH_BYTES UTF-8 bytes"
            if ('\\' in p) return "Backslashes are not allowed"
            if (p.startsWith("/")) return "Leading slash is not allowed"
            if (p.any { it.code < 32 }) return "Control characters are not allowed"
            for (seg in p.split('/')) {
                if (seg.isEmpty()) return "Empty path segments are not allowed"
                if (seg == "." || seg == "..") return "\".\" and \"..\" segments are not allowed"
            }
            return null
        }

        /** Why [name] can't be a feed name (desktop's `validateFeedName`), or null if it can. */
        internal fun feedNameError(name: Any?): String? {
            if (name !is String || name.isEmpty()) return "Feed name must be a non-empty string"
            if (name.length > 64) return "Feed name exceeds 64 characters"
            if ('/' in name) return "Feed name must not contain \"/\""
            if (name.any { it.code < 32 }) return "Feed name must not contain control characters"
            return null
        }

        /**
         * desktop's `selectBestBatch`: the usable batch with room for half
         * again [size] that lasts longest, or null. Room is the batch's
         * effective capacity less what its fullest bucket has used. With
         * [allowFullMutable], failing that, the longest-lasting usable
         * mutable batch.
         */
        internal fun selectBatch(batches: List<PostageBatch>, size: Long, allowFullMutable: Boolean = false): String? =
            batches.filter { it.usable && it.capacityBytes * (1 - it.usedFraction) >= size * 1.5 }
                .maxByOrNull { it.ttlSeconds ?: 0L }?.id
                // Messages only: a full mutable batch still takes stamps, by
                // overwriting its oldest ones — fine for ephemeral traffic,
                // never for content (desktop's `allowFullMutable`).
                ?: batches.takeIf { allowFullMutable }?.filter { it.usable && !it.immutable }?.maxByOrNull { it.ttlSeconds ?: 0L }?.id

        /**
         * [entries] as an uncompressed ustar archive, what `POST /bzz` takes
         * for a collection. Paths are at most [MAX_PATH_BYTES] UTF-8 bytes,
         * so each fits the header's name field.
         */
        internal fun tar(entries: List<Pair<String, ByteArray>>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((path, bytes) in entries) {
                val header = ByteArray(512)
                fun field(offset: Int, value: ByteArray) = value.copyInto(header, offset)
                fun octal(offset: Int, length: Int, value: Long) =
                    field(offset, value.toString(8).padStart(length - 1, '0').toByteArray(Charsets.US_ASCII))
                val name = path.toByteArray(Charsets.UTF_8)
                require(name.size <= 100) { "path too long for a tar header" }
                field(0, name)
                octal(100, 8, 420) // 0644
                octal(108, 8, 0)
                octal(116, 8, 0)
                octal(124, 12, bytes.size.toLong())
                octal(136, 12, 0)
                header[156] = '0'.code.toByte()
                field(257, "ustar\u000000".toByteArray(Charsets.US_ASCII))
                for (i in 148 until 156) header[i] = ' '.code.toByte()
                val sum = header.sumOf { it.toInt() and 0xff }
                field(148, (sum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray(Charsets.US_ASCII))
                out.write(header)
                out.write(bytes)
                val pad = (512 - bytes.size % 512) % 512
                out.write(ByteArray(pad))
            }
            out.write(ByteArray(1024))
            return out.toByteArray()
        }
    }
}

/**
 * The desktop-form key for [origin] (its [providerOriginKey]): what a
 * site's feed topics are derived from, and the `origin` its pages are
 * told — `bzz://<ref>`, `ipfs://<cid>`, `ipns://<name>` for content, the
 * bare lower-case name for an ENS or Tezos site (`name.eth`), and the
 * origin itself for https (and loopback http). Desktop's
 * `getPermissionKey` over the same page, so a feed keeps its topic across
 * platforms.
 */
fun swarmOriginKey(origin: String): String {
    val display = VirtualOrigin.displayUrlFor(origin) ?: return origin
    val host = display.substringAfter("://", display).substringBefore('/').substringBefore('?').substringBefore('#')
    if (!display.contains("://")) return host.lowercase()
    val scheme = display.substringBefore("://").lowercase()
    val lower = host.lowercase()
    if (DWEB_NAME.containsMatchIn(lower)) return lower
    return "$scheme://$host"
}

private val DWEB_NAME = Regex("\\.(eth|box|wei|gwei|tez)$")

/** What an approval sheet asks the user, for a page on [origin]. */
sealed interface SwarmAsk {
    val origin: String

    /** Connect: publishing (each upload asks), and asking for feed access. */
    data class Connect(override val origin: String) : SwarmAsk

    /** Publish [size] bytes: data (with its [contentType] and [name]), files ([paths]), or one chunk. */
    data class Publish(
        override val origin: String,
        val kind: Kind,
        val size: Long,
        val contentType: String?,
        val name: String?,
        val paths: List<String>,
    ) : SwarmAsk {
        enum class Kind { Data, Files, Chunk }
    }

    /**
     * The messaging tier (#121): see a messaging identity, subscribe
     * (to [topic], or to a room by its [address]), or send a [kind]
     * message of [size] bytes on [topic]. [grant] is the first time:
     * approving gives the site the tier. [send] is the send's kind, null otherwise.
     */
    data class Message(
        override val origin: String,
        val op: Op,
        val kind: Kind?,
        val topic: String?,
        val size: Int,
        val grant: Boolean = false,
        val address: String? = null,
    ) : SwarmAsk {
        enum class Op { Identity, Subscribe, Send }
        enum class Kind { Pss, Gsoc }

        val send: Kind? get() = kind.takeIf { op == Op.Send }
    }

    /**
     * Sign with the site's publisher identity: a feed method ([kind]
     * Feeds, on [feedName]) or a SOC / the signing identity (Signing,
     * [detail]). [grant] is the first time: it gives the site feed
     * access. [identity] is the one that will sign, if the site has one yet.
     */
    data class Sign(
        override val origin: String,
        val method: String,
        val kind: SwarmProvider.AutoApprove,
        val grant: Boolean,
        val feedName: String?,
        val detail: String?,
        val identity: PublisherIdentity?,
        /** There's no wallet yet: approving sets one up first ([SwarmProviders]' askOnTab). */
        val needsWallet: Boolean = false,
    ) : SwarmAsk

    /**
     * The app's permission manifest (#122) has rows to decide: [consent],
     * under the consent [token] (shared by the tabs that check the same
     * state). Answered through [outcomeOf].
     */
    data class Manifest(override val origin: String, val consent: SwarmManifests.Consent, val token: String = "") : SwarmAsk
}

/**
 * A Swarm app's permission manifest (#122) asks for [consent]'s rows
 * together. The answer maps onto [SwarmProvider.Answer]: allowed with
 * `always` is Allow all, allowed without it Use individual approvals,
 * and a refusal Don't allow.
 */
internal fun SwarmAsk.Manifest.outcomeOf(answer: SwarmProvider.Answer): SwarmManifests.Outcome = when {
    !answer.allowed -> SwarmManifests.Outcome.Deny
    answer.always -> SwarmManifests.Outcome.AllowAll
    else -> SwarmManifests.Outcome.Individual
}

/** Why the wallet page opens, for a site whose approved signing sheet needs a wallet. */
internal fun swarmWalletReason(origin: String) =
    Strings.get(R.string.swarm_wallet_reason, permissionOriginDisplay(origin))
