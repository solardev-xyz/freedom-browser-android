package baby.freedom.mobile.agent

import baby.freedom.mobile.browser.PostageBatch
import baby.freedom.mobile.browser.PublishOutcome
import baby.freedom.mobile.browser.PublishReport
import baby.freedom.mobile.browser.PublishSource
import baby.freedom.mobile.browser.sha256Of
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmPublishToolTest {
    private class MemoryStore(var saved: List<AgentPublication> = emptyList(), var failSaves: Boolean = false) : PublicationJournalStore {
        var saves = 0
        var unreadable = false

        override fun load(): List<AgentPublication>? = if (unreadable) null else saved

        override fun save(records: List<AgentPublication>): Boolean {
            if (failSaves) return false
            saves++
            saved = records
            return true
        }
    }

    private val batch = PostageBatch(
        id = "ab".repeat(32), usable = true, depth = 22, bucketDepth = 16, utilization = 1, immutable = true, ttlSeconds = 30L * 86_400,
    )

    private inner class FakeBackend : PublishBackend {
        var postage: PostagePreview = PostagePreview.Ready(batch, 8192)
        var now: PostageBatch? = batch
        var startResult = true
        val started = ArrayList<Pair<ByteArray, PostageBatch>>()
        var sources = ArrayList<PublishSource.Frozen>()
        var report: PublishReport? = null

        /** What the fake node does once started; null leaves it uploading. */
        var outcome: ((PublishReport) -> Unit)? = { r ->
            // As Publisher does: nothing goes out unless the dispatch was recorded.
            if (r.dispatching()) {
                r.finished(PublishOutcome.Completed("cd".repeat(32), 10, batch.id))
            } else {
                r.finished(PublishOutcome.NotSent("not recorded"))
            }
        }

        override suspend fun postage(stampBytes: Long) = postage

        override suspend fun batchNow(batchId: String, stampBytes: Long) = now?.takeIf { it.id == batchId }

        override fun start(source: PublishSource.Frozen, batch: PostageBatch, report: PublishReport): Boolean {
            if (!startResult) return false
            started += source.bytes() to batch
            sources += source
            this.report = report
            outcome?.invoke(report)
            return true
        }
    }

    private val store = MemoryStore()
    private val journal = AgentPublications(store)
    private val backend = FakeBackend()
    private val asked = ArrayList<AgentApproval>()
    private var answer: (AgentApproval) -> ApprovalOutcome = { ApprovalOutcome.Publish(emptySet()) }

    private fun tools(session: String = "chat") = SwarmPublishTools(
        session,
        journal = { journal },
        backend = { backend },
        ask = { a -> asked += a; answer(a) },
        interactiveWaitMs = 200,
        statusWaitMs = 200,
    )

    private fun SwarmPublishTools.call(name: String, args: JSONObject): ToolOutcome =
        runBlocking { tools.first { it.spec.name == name }.run(args) }

    private fun publish(text: String, session: String = "chat", contentType: String? = null) =
        tools(session).call(SwarmPublishTools.PUBLISH, JSONObject().put("text", text).apply { contentType?.let { put("contentType", it) } })

    private fun ToolOutcome.code() = output.substringAfter('[').substringBefore(']')

    private fun ToolOutcome.publication() = JSONObject(output).getJSONObject("result").getJSONObject("publication")

    private val github = listOf("ghp_", "a8Fh2kL9mQ3rT6vW1yZ4bC7dE0gJ5n").joinToString("")

    @Test
    fun `frozen bytes are copied in and out, so nothing after freezing changes what is published`() {
        val raw = "Hello Swarm".toByteArray()
        val frozen = PublishSource.Frozen(raw, "text.txt", "text/plain")
        val digest = frozen.sha256.copyOf()
        raw[0] = 'J'.code.toByte()
        frozen.bytes()[1] = 'a'.code.toByte()
        assertArrayEquals("Hello Swarm".toByteArray(), frozen.bytes())
        assertArrayEquals(digest, sha256Of("Hello Swarm".toByteArray()))
        val file = Files.createTempFile("frozen", ".bin").toFile()
        try {
            frozen.writeTo(file)
            // What Publisher stages is what was frozen, which its digest check holds it to.
            assertArrayEquals(frozen.sha256, sha256Of(file))
            file.appendBytes(byteArrayOf(1))
            assertFalse(frozen.sha256.contentEquals(sha256Of(file)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the card shows the scanned snapshot, and the upload sends exactly those bytes with the shown stamp`() {
        val text = "Notes for the team\nNothing secret here.\n"
        val out = publish(text)
        val action = asked.single().action as AgentAction.Publish
        assertEquals(text, action.text)
        assertEquals(text.toByteArray().size, action.bytes)
        assertEquals("text/plain; charset=utf-8", action.contentType)
        assertEquals(emptyList<Any>(), action.findings)
        assertEquals(SwarmPublishTools.NO_TAB, asked.single().tabId)
        val (sent, used) = backend.started.single()
        assertArrayEquals(text.toByteArray(), sent)
        assertEquals(batch, used)
        assertEquals("text/plain; charset=utf-8", backend.sources.single().contentType)
        val receipt = out.publication()
        assertEquals("completed", receipt.getString("state"))
        assertEquals("applied", receipt.getString("applicationState"))
        assertEquals("bzz://" + "cd".repeat(32), receipt.getString("bzzUrl"))
        assertTrue(Regex("^swarm_pub_[a-f0-9]{24}$").matches(receipt.getString("publicationId")))
        assertEquals("public", receipt.getString("target"))
    }

    @Test
    fun `text that is not valid UTF-16 is frozen once and the card shows what will go out`() {
        val text = "a\uD800b"
        publish(text)
        val action = asked.single().action as AgentAction.Publish
        assertEquals(String(backend.started.single().first, Charsets.UTF_8), action.text)
    }

    @Test
    fun `a secret blocks publishing until it is ticked, and the tool checks that itself`() {
        publish("deploy token $github").let { out ->
            assertTrue(out.isError)
            assertEquals(ToolError.POLICY_DENIED, out.code())
            assertFalse(out.declined)
        }
        assertTrue(backend.started.isEmpty())
        val finding = (asked.single().action as AgentAction.Publish).findings.single()
        assertEquals("github-token", finding.rule)
        assertTrue(finding.blocking)
        assertFalse(publishAllowed(asked.single().action as AgentAction.Publish, emptySet()))
        assertTrue(publishAllowed(asked.single().action as AgentAction.Publish, setOf(finding.id)))

        answer = { a -> ApprovalOutcome.Publish((a.action as AgentAction.Publish).findings.map { it.id }.toSet()) }
        val out = publish("deploy token $github")
        assertFalse(out.isError)
        assertEquals(1, backend.started.size)
        // The model learns rule names and counts, never the value or its excerpt.
        assertFalse(out.output.contains(github))
        assertFalse(out.output.contains("•"))
        assertEquals("github-token", out.publication().getJSONObject("prepublishScan").getJSONArray("rules").getString(0))
    }

    @Test
    fun `ask agent to fix names rules and lines only, publishes nothing and does not end the turn`() {
        answer = { ApprovalOutcome.AskToFix }
        val out = publish("line one\nalice.smith@proton.me\n$github")
        assertTrue(out.isError)
        assertFalse(out.declined)
        assertEquals(ToolError.PUBLICATION_FIX_REQUESTED, out.code())
        assertTrue(out.output.contains("github-token (line 3)"))
        assertTrue(out.output.contains("email-address (line 2)"))
        assertFalse(out.output.contains("proton"))
        assertFalse(out.output.contains(github))
        assertTrue(backend.started.isEmpty())
        assertTrue(journal.forSession("chat").isEmpty())
    }

    @Test
    fun `do not allow ends the turn and a withdrawn card is not a no`() {
        answer = { ApprovalOutcome.Declined }
        val declined = publish("hello")
        assertTrue(declined.declined)
        assertEquals(ToolError.USER_DECLINED, declined.code())
        answer = { ApprovalOutcome.Withdrawn }
        val withdrawn = publish("hello")
        assertFalse(withdrawn.declined)
        assertEquals(ToolError.APPROVAL_REQUIRED, withdrawn.code())
        assertTrue(backend.started.isEmpty())
    }

    @Test
    fun `with no usable stamp the card is still shown, and nothing can be published from it`() {
        backend.postage = PostagePreview.NoStamp("No stamp")
        val out = publish("hello")
        assertEquals(PostagePreview.NoStamp("No stamp"), (asked.single().action as AgentAction.Publish).postage)
        assertFalse(publishAllowed(asked.single().action as AgentAction.Publish, emptySet()))
        assertEquals(ToolError.CAPABILITY_UNAVAILABLE, out.code())
        assertTrue(backend.started.isEmpty())
        assertTrue(out.output.contains("never buy one"))
    }

    @Test
    fun `the stamp the card showed is used or none, a gone stamp refuses and never another`() {
        backend.now = null
        val out = publish("hello")
        assertEquals(ToolError.CAPABILITY_UNAVAILABLE, out.code())
        assertTrue(backend.started.isEmpty())
        assertTrue(journal.forSession("chat").isEmpty())
    }

    @Test
    fun `a journal that cannot be written means no upload`() {
        store.failSaves = true
        val out = publish("hello")
        assertEquals(ToolError.CAPABILITY_UNAVAILABLE, out.code())
        assertTrue(backend.started.isEmpty())
    }

    @Test
    fun `a journal that cannot be read is never written over, and nothing is asked or uploaded`() {
        val earlier = AgentPublication(
            publicationId = AgentPublication.newId(), sessionId = "chat", state = AgentPublication.OUTCOME_UNKNOWN, dispatched = true,
            bytes = 5, contentType = "text/plain", batchId = batch.id, createdAt = 1,
        )
        store.saved = listOf(earlier)
        store.unreadable = true
        val out = publish("hello")
        assertEquals(ToolError.CAPABILITY_UNAVAILABLE, out.code())
        assertTrue(asked.isEmpty())
        assertTrue(backend.started.isEmpty())
        assertFalse(journal.begin(earlier.copy(publicationId = AgentPublication.newId(), state = AgentPublication.UPLOADING)))
        journal.deleteAll()
        journal.deleteSession("chat")
        assertEquals(0, store.saves)
        assertEquals(listOf(earlier), store.saved)
        // Once it reads again, the earlier record is there and named on the card.
        store.unreadable = false
        publish("hello")
        assertEquals(1, (asked.single().action as AgentAction.Publish).earlierUnknown)
        assertTrue(store.saved.any { it.publicationId == earlier.publicationId })
    }

    @Test
    fun `a journal file that can't be read or parsed reads as unreadable, not empty`() {
        val dir = Files.createTempDirectory("journal").toFile()
        try {
            val file = File(dir, "agent-publications.json")
            assertEquals(emptyList<AgentPublication>(), FilePublicationJournalStore(file).load())
            file.writeText("{\"publications\": [")
            assertNull(FilePublicationJournalStore(file).load())
            val j = AgentPublications(FilePublicationJournalStore(file))
            assertFalse(j.begin(AgentPublication(
                publicationId = AgentPublication.newId(), sessionId = "chat", state = AgentPublication.UPLOADING, dispatched = false,
                bytes = 5, contentType = "text/plain", batchId = batch.id, createdAt = 1,
            )))
            assertEquals("{\"publications\": [", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the record is on disk before the upload starts`() {
        backend.outcome = { _ ->
            val saved = store.saved.single()
            assertEquals(AgentPublication.UPLOADING, saved.state)
            assertFalse(saved.dispatched)
        }
        publish("hello")
        assertEquals(1, backend.started.size)
    }

    @Test
    fun `a lost answer after dispatch is outcome_unknown, one never sent is not applied, and neither is retried`() {
        backend.outcome = { r ->
            r.dispatching()
            r.finished(PublishOutcome.Unknown("The Swarm node didn't finish the upload in time"))
        }
        val unknown = publish("hello").publication()
        assertEquals("outcome_unknown", unknown.getString("state"))
        assertEquals("possibly_applied", unknown.getString("applicationState"))
        assertEquals(1, backend.started.size)

        backend.outcome = { r -> r.finished(PublishOutcome.NotSent("Turn on the Swarm node")) }
        val notSent = publish("hello", session = "other").publication()
        assertEquals("failed", notSent.getString("state"))
        assertEquals("not_applied", notSent.getString("applicationState"))

        backend.outcome = { r ->
            r.dispatching()
            r.finished(PublishOutcome.Refused("refused"))
        }
        assertEquals("possibly_applied", publish("hello", session = "third").publication().getString("applicationState"))
        assertEquals(3, backend.started.size)
    }

    @Test
    fun `an upload still going is reported, not repeated, and its outcome lands after the turn returned`() {
        backend.outcome = null
        val first = publish("hello").publication()
        assertEquals("uploading", first.getString("state"))
        val second = publish("hello again")
        assertEquals(1, asked.size)
        assertEquals(1, backend.started.size)
        assertEquals(first.getString("publicationId"), second.publication().getString("publicationId"))

        backend.report!!.dispatching()
        backend.report!!.finished(PublishOutcome.Completed("ef".repeat(32), 5, batch.id))
        val status = tools().call(SwarmPublishTools.STATUS, JSONObject().put("publicationId", first.getString("publicationId")))
        assertEquals("completed", status.publication().getString("state"))
    }

    @Test
    fun `a restart turns an interrupted upload into an unknown outcome or a failure, and never restarts it`() {
        val base = AgentPublication(
            publicationId = AgentPublication.newId(), sessionId = "chat", state = AgentPublication.UPLOADING, dispatched = true,
            bytes = 5, contentType = "text/plain", batchId = batch.id, createdAt = 1,
        )
        val unsent = base.copy(publicationId = AgentPublication.newId(), dispatched = false)
        val restarted = AgentPublications(MemoryStore(listOf(base, unsent)))
        val list = restarted.forSession("chat").associateBy { it.publicationId }
        assertEquals(AgentPublication.OUTCOME_UNKNOWN, list.getValue(base.publicationId).state)
        assertEquals(AgentPublication.FAILED, list.getValue(unsent.publicationId).state)
        assertEquals("not_applied", list.getValue(unsent.publicationId).receipt().getString("applicationState"))
    }

    @Test
    fun `the journal survives a round trip through its file and drops finished records first`() {
        val dir = Files.createTempDirectory("journal").toFile()
        try {
            val file = File(dir, "agent-publications.json")
            val fileStore = FilePublicationJournalStore(file)
            val records = (0 until AgentPublications.MAX_RECORDS).map {
                AgentPublication(
                    publicationId = AgentPublication.newId(), sessionId = "chat", state = AgentPublication.COMPLETED, dispatched = true,
                    bytes = 5, contentType = "text/plain", batchId = batch.id, reference = "cd".repeat(32), createdAt = it.toLong(),
                )
            }
            assertTrue(fileStore.save(records))
            val j = AgentPublications(fileStore)
            val pending = records[0].copy(publicationId = AgentPublication.newId(), state = AgentPublication.UPLOADING, createdAt = 10_000)
            assertTrue(j.begin(pending))
            val back = FilePublicationJournalStore(file).load()!!
            assertEquals(AgentPublications.MAX_RECORDS, back.size)
            assertTrue(back.none { it.publicationId == records[0].publicationId })
            assertTrue(back.any { it.publicationId == pending.publicationId })
            assertFalse(File(dir, "agent-publications.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `status is per chat, and a deleted chat takes its receipts with it`() {
        publish("hello")
        publish("hi", session = "other")
        val mine = JSONObject(tools().call(SwarmPublishTools.STATUS, JSONObject()).output).getJSONObject("result").getJSONArray("publications")
        assertEquals(1, mine.length())
        val otherId = journal.forSession("other").single().publicationId
        val foreign = tools().call(SwarmPublishTools.STATUS, JSONObject().put("publicationId", otherId))
        assertEquals(ToolError.CAPABILITY_UNAVAILABLE, foreign.code())
        journal.deleteSession("other")
        assertTrue(journal.forSession("other").isEmpty())
        assertEquals(1, journal.forSession("chat").size)
        assertNull(store.saved.firstOrNull { it.sessionId == "other" })
    }

    @Test
    fun `only text is accepted, within desktop bounds`() {
        for (args in listOf(
            JSONObject().put("resourceId", "attachment_" + "a".repeat(20)),
            JSONObject().put("text", "x").put("target", "draft"),
            JSONObject().put("text", "x").put("stableAddress", "site"),
            JSONObject(),
            JSONObject().put("text", ""),
            JSONObject().put("text", "x".repeat(SwarmPublishTools.MAX_TEXT_BYTES + 1)),
            JSONObject().put("text", "x").put("contentType", "text/plain\r\nX: y"),
        )) {
            val out = tools().call(SwarmPublishTools.PUBLISH, args)
            assertEquals(args.toString().take(80), ToolError.INVALID_ARGUMENT, out.code())
        }
        assertTrue(asked.isEmpty())
        assertEquals(ToolError.INVALID_ARGUMENT, tools().call(SwarmPublishTools.STATUS, JSONObject().put("publicationId", "nope")).code())
    }

    @Test
    fun `html published as text gets the third-party check`() {
        publish("<script src=\"https://cdn.example.net/x.js\"></script>", contentType = "text/html; charset=utf-8")
        val finding = (asked.single().action as AgentAction.Publish).findings.single()
        assertEquals("third-party-script", finding.rule)
        assertEquals("https://cdn.example.net", finding.excerpt)
        assertFalse(finding.blocking)
    }

    @Test
    fun `a finding past the preview shows the text around it on the card`() {
        val filler = "x".repeat(PUBLISH_PREVIEW_CHARS + 500) + "\n"
        val text = filler + "deploy with " + github + " today"
        publish(text)
        val action = asked.single().action as AgentAction.Publish
        val f = action.findings.single { it.rule == "github-token" }
        val context = findingContext(action.text, f)!!
        assertTrue(context, context.contains(github) && context.contains("deploy with") && context.startsWith("…"))
        // One inside the preview needs none: the preview shows it.
        assertNull(findingContext(github + " " + filler, PublicationScan.scanText("Text", github).single()))
    }

    @Test
    fun `the card text preview says when it is cut`() {
        val long = "a".repeat(PUBLISH_PREVIEW_CHARS + 10)
        val shown = ApprovalText.typed(long, PUBLISH_PREVIEW_CHARS) as ApprovalText.Typed.Shown
        assertTrue(shown.text.endsWith("…"))
        assertEquals(PUBLISH_PREVIEW_CHARS + 1, shown.text.length)
    }

    @Test
    fun `an upload whose dispatch can't be recorded is not sent`() {
        backend.outcome = { r ->
            store.failSaves = true
            val recorded = r.dispatching()
            store.failSaves = false
            assertFalse(recorded)
            r.finished(PublishOutcome.NotSent("not recorded"))
        }
        val receipt = publish("hello").publication()
        assertEquals("failed", receipt.getString("state"))
        assertEquals("not_applied", receipt.getString("applicationState"))
    }

    @Test
    fun `a publisher that throws on start leaves no upload blocking the chat`() {
        val throwing = object : PublishBackend by backend {
            override fun start(source: PublishSource.Frozen, batch: PostageBatch, report: PublishReport): Boolean = error("boom")
        }
        val t = SwarmPublishTools("chat", { journal }, { throwing }, ask = { a -> asked += a; answer(a) }, interactiveWaitMs = 200)
        val out = runBlocking { t.tools.first { it.spec.name == SwarmPublishTools.PUBLISH }.run(JSONObject().put("text", "hello")) }
        assertEquals(ToolError.CAPABILITY_UNAVAILABLE, out.code())
        assertTrue(journal.forSession("chat").all { it.terminal })
        // The next publish is asked for, not answered with the stuck one.
        publish("hello again")
        assertEquals(2, asked.size)
    }

    @Test
    fun `the card says when an earlier publication in the chat may already be out`() {
        backend.outcome = { r ->
            r.dispatching()
            r.finished(PublishOutcome.Unknown("lost"))
        }
        publish("first")
        publish("second")
        assertEquals(0, (asked[0].action as AgentAction.Publish).earlierUnknown)
        assertEquals(1, (asked[1].action as AgentAction.Publish).earlierUnknown)
    }

    @Test
    fun `a content type that isn't printable ASCII is refused`() {
        val out = tools().call(SwarmPublishTools.PUBLISH, JSONObject().put("text", "x").put("contentType", "text/plain; name=\u00e9"))
        assertEquals(ToolError.INVALID_ARGUMENT, out.code())
    }

    @Test
    fun `every rule the card can show has a label in the app's strings`() {
        val strings = listOf(File("src/main/res/values/strings_agent.xml"), File("app/src/main/res/values/strings_agent.xml"))
            .first { it.exists() }.readText()
        for (rule in PUBLISH_RULE_IDS) {
            assertTrue(rule, strings.contains("name=\"agent_publish_rule_${rule.replace('-', '_')}\""))
        }
        val hygiene = PublicationScan.hygieneFindings("a.html", "<img src=\"https://x.example/a\"><script src=\"https://x.example/b\"></script>" +
            "<link rel=stylesheet href=\"https://x.example/c\"><form action=\"https://x.example/d\"></form><script>fetch(\"https://x.example/e\")</script>",
            markup = true, script = true).map { it.rule }.toSet()
        assertTrue(PUBLISH_RULE_IDS.containsAll(hygiene))
    }
}
