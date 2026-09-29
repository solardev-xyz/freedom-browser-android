package baby.freedom.mobile.browser

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishTest {
    private val ref = "0123456789abcdef".repeat(4)
    private val batchA = "aa".repeat(32)
    private val batchB = "bb".repeat(32)

    private fun batch(id: String, depth: Int = 20, utilization: Long = 0, usable: Boolean = true, ttl: Long? = 86_400) =
        PostageBatch(id, usable, depth, 16, utilization, immutable = true, ttlSeconds = ttl)

    // History file

    @Test
    fun `the history file reads back what was written, an interrupted upload as failed`() {
        val done = PublishRecord("1", PublishKind.Folder, "site", PublishStatus.Completed, 2_000, ref, batchA, 1234, 2_500)
        val failed = PublishRecord("2", PublishKind.File, "a.pdf", PublishStatus.Failed, 1_000, error = "no room")
        val cut = PublishRecord("3", PublishKind.Text, "Text", PublishStatus.Uploading, 3_000, bytes = 5)
        val read = PublishHistoryCodec.decode(PublishHistoryCodec.encode(listOf(failed, done, cut)), now = 9_000)
        assertEquals(listOf("3", "1", "2"), read.map { it.id })
        assertEquals(done, read[1])
        assertEquals(failed, read[2])
        assertEquals(PublishStatus.Failed, read[0].status)
        assertEquals(PUBLISH_INTERRUPTED, read[0].error)
        assertEquals(9_000L, read[0].completedAt)
        assertEquals("bzz://$ref", read[1].bzzUrl)
        assertNull(read[2].bzzUrl)
    }

    @Test
    fun `a record that can't be read is dropped, the others kept`() {
        val o = PublishHistoryCodec.encode(
            listOf(PublishRecord("1", PublishKind.File, "a", PublishStatus.Completed, 1, ref)),
        )
        val records = o.getJSONArray("records")
        records.put(JSONObject(records.getJSONObject(0).toString()).put("id", "2").put("reference", "not-a-ref"))
        records.put(JSONObject(records.getJSONObject(0).toString()).put("id", "3").put("kind", "Video"))
        records.put(JSONObject(records.getJSONObject(0).toString()))
        assertEquals(listOf("1"), PublishHistoryCodec.decode(o, 0).map { it.id })
        assertEquals(emptyList<PublishRecord>(), PublishHistoryCodec.decode(JSONObject().put("version", 2), 0))
    }

    @Test
    fun `the file store saves and loads, and an empty history removes the file`() {
        val dir = Files.createTempDirectory("publish").toFile()
        try {
            val file = File(dir, "publish/history.json")
            val store = FilePublishHistoryStore(file)
            val r = PublishRecord("1", PublishKind.File, "a", PublishStatus.Completed, 1, ref, batchA, 3, 2)
            assertTrue(store.save(listOf(r)))
            assertEquals(listOf(r), store.load(0))
            assertTrue(store.save(emptyList()))
            assertFalse(file.exists())
            file.writeText("{not json")
            assertEquals(emptyList<PublishRecord>(), store.load(0))
        } finally {
            dir.deleteRecursively()
        }
    }

    private class MemoryStore(var saved: List<PublishRecord> = emptyList()) : PublishHistoryStore {
        val gate = java.util.concurrent.CountDownLatch(1)
        override fun save(records: List<PublishRecord>): Boolean {
            saved = records
            return true
        }
        override fun load(now: Long): List<PublishRecord> {
            gate.await()
            return PublishHistoryCodec.decode(PublishHistoryCodec.encode(saved), now)
        }
    }

    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(check())
    }

    @Test
    fun `a publish moves from uploading to completed or failed, and is written`() {
        val store = MemoryStore().apply { gate.countDown() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 100L
        val history = PublishHistory(store, scope) { now }
        eventually { store.saved.isEmpty() }
        val a = history.start(PublishKind.Folder, "site", 10)
        now = 200
        val b = history.start(PublishKind.Text, "Text", 3)
        assertEquals(listOf(b.id, a.id), history.records.value.map { it.id })
        assertEquals(PublishStatus.Uploading, history.records.value[0].status)
        now = 300
        history.completed(a.id, ref, batchA, 12)
        history.failed(b.id, "no room")
        val done = history.records.value.first { it.id == a.id }
        assertEquals(PublishStatus.Completed, done.status)
        assertEquals(ref, done.reference)
        assertEquals(batchA, done.batchId)
        assertEquals(12L, done.bytes)
        assertEquals(300L, done.completedAt)
        assertEquals("no room", history.records.value.first { it.id == b.id }.error)
        eventually { store.saved == history.records.value }
        history.remove(b.id)
        eventually { store.saved.map { it.id } == listOf(a.id) }
        history.clear()
        eventually { store.saved.isEmpty() }
        scope.cancel()
    }

    @Test
    fun `a publish started before the file is read is kept on top of it, and a clear before then drops the file`() {
        val old = PublishRecord("old", PublishKind.File, "a", PublishStatus.Completed, 1, ref)
        val gone = PublishRecord("gone", PublishKind.File, "b", PublishStatus.Completed, 2, ref)
        val store = MemoryStore(listOf(gone, old))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val history = PublishHistory(store, scope) { 50 }
        val fresh = history.start(PublishKind.Text, "Text", 1)
        history.remove("gone")
        // Nothing is written over the file before it has been read.
        assertEquals(listOf("gone", "old"), store.saved.map { it.id })
        store.gate.countDown()
        eventually { store.saved.map { it.id } == listOf(fresh.id, "old") }
        assertEquals(listOf(fresh.id, "old"), history.records.value.map { it.id })

        val store2 = MemoryStore(listOf(old))
        val history2 = PublishHistory(store2, scope) { 50 }
        history2.clear()
        store2.gate.countDown()
        eventually { store2.saved.isEmpty() }
        Thread.sleep(50)
        assertEquals(emptyList<PublishRecord>(), history2.records.value)
        scope.cancel()
    }

    @Test
    fun `clear all and remove keep an upload in flight, so its reference still lands`() {
        val store = MemoryStore().apply { gate.countDown() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 100L
        val history = PublishHistory(store, scope) { now }
        eventually { store.saved.isEmpty() }
        val earlier = history.start(PublishKind.File, "a", 1)
        history.completed(earlier.id, ref, batchA, 1)
        now = 200
        val running = history.start(PublishKind.Folder, "site", 10)
        history.clear()
        history.remove(running.id)
        assertEquals(listOf(running.id), history.records.value.map { it.id })
        history.completed(running.id, ref, batchA, 10)
        val done = history.records.value.single()
        assertEquals(PublishStatus.Completed, done.status)
        assertEquals("bzz://$ref", done.bzzUrl)
        eventually { store.saved == history.records.value }
        history.remove(running.id)
        assertEquals(emptyList<PublishRecord>(), history.records.value)
        scope.cancel()
    }

    @Test
    fun `a finished publish whose record was removed has no outcome left to show`() {
        val r = PublishRecord("1", PublishKind.File, "a", PublishStatus.Completed, 1, ref)
        assertEquals(Publisher.State.Finished("1"), Publisher.finishedState("1", listOf(r)))
        assertEquals(Publisher.State.Idle, Publisher.finishedState("1", emptyList()))
        assertEquals(Publisher.State.Idle, Publisher.finishedState("1", listOf(r.copy(id = "2"))))
    }

    @Test
    fun `a persisted grant no publish holds is stale`() {
        val a = "content://tree/a"
        val b = "content://doc/b"
        assertEquals(setOf(a, b), staleGrants(listOf(a, b), emptySet()))
        assertEquals(setOf(b), staleGrants(listOf(a, b), setOf(a)))
        assertEquals(emptySet<String>(), staleGrants(emptyList(), setOf(a)))
    }

    @Test
    fun `a document of unknown size is measured, and refused past the cap`() {
        assertEquals(70_000L, measureCapped(ByteArrayInputStream(ByteArray(70_000)), tooBig = "big"))
        assertEquals(0L, measureCapped(ByteArrayInputStream(ByteArray(0)), tooBig = "big"))
        val e = assertThrows(PublishException::class.java) {
            measureCapped(ByteArrayInputStream(ByteArray(70_000)), max = 65_536, tooBig = "big")
        }
        assertEquals("big", e.message)
        // Its real size decides the stamp: 60 MB doesn't fit where the
        // 8 KiB a missing size used to count as would.
        val nearlyFull = batch(batchA, depth = 17, utilization = 0)
        assertTrue(batchHasRoom(nearlyFull, publishStampEstimate(listOf(0))))
        assertFalse(batchHasRoom(nearlyFull, publishStampEstimate(listOf(60_000_000))))
    }

    // Stamps

    @Test
    fun `the stamp estimate counts whole chunks and a manifest chunk per file`() {
        assertEquals(8192L, publishStampEstimate(listOf(300)))
        assertEquals(8192L, publishStampEstimate(listOf(0)))
        // 4097 bytes fill two chunks, 1 byte one; each file adds one of manifest.
        assertEquals(4096L * 5, publishStampEstimate(listOf(4097, 1)))
        assertEquals(0L, publishStampEstimate(emptyList()))
    }

    @Test
    fun `the batch that lasts longest with room for it, desktop's rule`() {
        val short = batch(batchA, ttl = 3_600)
        val long = batch(batchB, ttl = 86_400)
        assertEquals(batchB, selectPublishBatch(listOf(short, long), 8192)?.id)
        // Not usable, expired, or too full: none of them.
        assertNull(selectPublishBatch(listOf(batch(batchA, usable = false)), 1))
        assertNull(selectPublishBatch(listOf(batch(batchA, ttl = 0)), 1))
        // Depth 17 holds ~40.9 kB; with the 1.5 margin, 27 kB fits and 28 kB doesn't.
        val small = batch(batchA, depth = 17)
        assertEquals(batchA, selectPublishBatch(listOf(small), 27_000)?.id)
        assertNull(selectPublishBatch(listOf(small), 28_000))
        // Half its fullest bucket used: half the room.
        val half = batch(batchA, depth = 17, utilization = 1)
        assertEquals(20_445L, batchRemainingBytes(half))
        assertNull(selectPublishBatch(listOf(half), 14_000))
        // A batch whose time left is unknown still counts, after any known one.
        val unknown = batch(batchB, ttl = null)
        assertEquals(batchB, selectPublishBatch(listOf(unknown), 1)?.id)
        assertEquals(batchA, selectPublishBatch(listOf(unknown, batch(batchA, ttl = 60)), 1)?.id)
    }

    @Test
    fun `the page says why there's no stamp to publish with`() {
        assertNull(noStampText(listOf(batch(batchA)), 8192))
        assertTrue(noStampText(emptyList(), 0)!!.contains("has none yet"))
        assertTrue(noStampText(listOf(batch(batchA, usable = false)), 0)!!.contains("has none yet"))
        assertTrue(noStampText(listOf(batch(batchA, depth = 17)), 100_000)!!.contains("room for 100 kB"))
    }

    // Folder archive

    /** The regular files in a tar, as ant's collection upload reads them: GNU long names applied, checksums checked. */
    private fun readTar(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var at = 0
        var longName: String? = null
        while (at + 512 <= bytes.size) {
            val h = bytes.copyOfRange(at, at + 512)
            if (h.all { it == 0.toByte() }) break
            val stored = String(h, 148, 6).trim().toInt(8)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            assertEquals("header checksum", stored, h.sumOf { it.toInt() and 0xff })
            val size = String(h, 124, 11).toLong(8).toInt()
            val type = h[156].toInt().toChar()
            val name = String(h, 0, 100).trimEnd('\u0000')
            val data = bytes.copyOfRange(at + 512, at + 512 + size)
            at += 512 + (size + 511) / 512 * 512
            when (type) {
                'L' -> {
                    assertEquals("ustar  \u0000", String(h, 257, 8))
                    longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
                }
                '0' -> {
                    assertEquals("ustar\u000000", String(h, 257, 8))
                    out += (longName ?: name) to data
                    longName = null
                }
                else -> error("unexpected entry type $type")
            }
        }
        assertEquals("archive ends with two zero blocks", bytes.size, at + 1024)
        assertEquals(0, bytes.size % 512)
        return out
    }

    private fun tar(maxBytes: Long = MAX_PUBLISH_BYTES, build: (TarWriter) -> Unit): ByteArray {
        val f = File.createTempFile("publish", ".tar")
        try {
            RandomAccessFile(f, "rw").use { build(TarWriter(it, maxBytes)) }
            return f.readBytes()
        } finally {
            f.delete()
        }
    }

    @Test
    fun `a folder becomes a tar ant reads back file for file`() {
        val long = "assets/" + "ü".repeat(60) + "/style.css"
        val big = ByteArray(5000) { it.toByte() }
        val bytes = tar { t ->
            assertEquals(5L, t.add("index.html", ByteArrayInputStream("hello".toByteArray())))
            assertEquals(0L, t.add("empty.txt", ByteArrayInputStream(ByteArray(0))))
            assertEquals(5000L, t.add(long, ByteArrayInputStream(big)))
            t.finish()
        }
        val files = readTar(bytes)
        assertEquals(listOf("index.html", "empty.txt", long), files.map { it.first })
        assertEquals("hello", String(files[0].second))
        assertEquals(0, files[1].second.size)
        assertTrue(big.contentEquals(files[2].second))
    }

    @Test
    fun `a folder over the upload cap is refused while it's written`() {
        assertThrows(PublishException::class.java) {
            tar(maxBytes = 4096) { t -> t.add("a", ByteArrayInputStream(ByteArray(4000))) }
        }
        // The same bytes fit a bigger cap.
        tar(maxBytes = 8192) { t ->
            t.add("a", ByteArrayInputStream(ByteArray(4000)))
            t.finish()
        }
    }

    @Test
    fun `names that can't be a path segment are left out, and a top-level index html is the page`() {
        assertEquals("a b.html", safePathSegment("a b.html"))
        listOf(null, "", ".", "..", "a/b", "a\u0000").forEach { assertNull(safePathSegment(it)) }
        assertEquals("index.html", indexDocumentFor(listOf("a.css", "index.html")))
        assertNull(indexDocumentFor(listOf("sub/index.html", "Index.HTML")))
    }

    // The gateway request

    @Test
    fun `what ant's gateway is sent for each kind`() {
        val file = PublishRequest(PublishKind.File, batchA, "my report+1.pdf", "application/pdf", null)
        assertEquals("/bzz?name=my%20report%2B1.pdf", file.path())
        assertEquals(
            mapOf("Swarm-Postage-Batch-Id" to batchA, "Swarm-Pin" to "true", "Content-Type" to "application/pdf"),
            file.headers(),
        )
        assertEquals("application/octet-stream", PublishRequest(PublishKind.File, batchA, "x", null, null).headers()["Content-Type"])
        val folder = PublishRequest(PublishKind.Folder, batchB, null, null, "index.html")
        assertEquals("/bzz", folder.path())
        assertEquals(
            mapOf(
                "Swarm-Postage-Batch-Id" to batchB, "Swarm-Pin" to "true", "Swarm-Collection" to "true",
                "Content-Type" to "application/x-tar", "Swarm-Index-Document" to "index.html",
            ),
            folder.headers(),
        )
        assertFalse("Swarm-Index-Document" in PublishRequest(PublishKind.Folder, batchB, null, null, null).headers())
    }

    @Test
    fun `ant's answer, or why there is none`() {
        assertEquals(ref, publishAnswer(201, """{"reference":"${ref.uppercase()}"}""").getOrThrow())
        assertTrue(publishAnswer(201, """{"reference":"abc"}""").isFailure)
        assertTrue(publishAnswer(201, null).isFailure)
        fun message(code: Int, body: String?) = publishAnswer(code, body).exceptionOrNull()!!.message!!
        assertEquals(
            "The postage stamp can't be used for this: batch not usable",
            message(400, """{"code":400,"message":"batch not usable"}"""),
        )
        assertTrue(message(422, """{"message":"rejected by peer: not found on-chain"}""").startsWith("The postage stamp can't be used"))
        assertTrue(message(413, """{"message":"request body too big"}""").startsWith("It's too big for one upload"))
        assertEquals("The node can't upload right now: uploads not configured", message(503, """{"message":"uploads not configured"}"""))
        assertEquals("The node refused the upload (HTTP 500)", message(500, null))
        assertEquals("The node refused the upload: boom", message(502, "boom"))
    }

    @Test
    fun `an upload goes to the gateway with its headers and body, and returns the reference`() {
        val seen = CopyOnWriteArrayList<String>()
        var body = ByteArray(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI}"
            seen += "batch=${ex.requestHeaders.getFirst("Swarm-Postage-Batch-Id")}"
            seen += "collection=${ex.requestHeaders.getFirst("Swarm-Collection")}"
            seen += "length=${ex.requestHeaders.getFirst("Content-Length")}"
            body = ex.requestBody.readBytes()
            val answer = """{"reference":"$ref"}""".toByteArray()
            ex.sendResponseHeaders(201, answer.size.toLong())
            ex.responseBody.use { it.write(answer) }
        }
        server.start()
        try {
            val payload = "a folder's tar".toByteArray()
            val got = uploadToGateway(
                "http://127.0.0.1:${server.address.port}",
                PublishRequest(PublishKind.Folder, batchA, null, null, "index.html"),
                { ByteArrayInputStream(payload) },
                payload.size.toLong(),
            )
            assertEquals(ref, got)
            assertEquals(
                listOf("POST /bzz", "batch=$batchA", "collection=true", "length=${payload.size}"),
                seen.toList(),
            )
            assertTrue(payload.contentEquals(body))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a refused upload says why`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val answer = """{"code":400,"message":"batch not usable"}""".toByteArray()
            ex.sendResponseHeaders(400, answer.size.toLong())
            ex.responseBody.use { it.write(answer) }
        }
        server.start()
        try {
            val e = assertThrows(PublishException::class.java) {
                uploadToGateway(
                    "http://127.0.0.1:${server.address.port}",
                    PublishRequest(PublishKind.Text, batchA, Publisher.TEXT_FILE_NAME, "text/plain", null),
                    { ByteArrayInputStream("hi".toByteArray()) },
                    2,
                )
            }
            assertEquals("The postage stamp can't be used for this: batch not usable", e.message)
        } finally {
            server.stop(0)
        }
    }

    // The page

    @Test
    fun `the page needs a running light node`() {
        assertEquals("The Swarm node is starting…", publishPageBlockedReason(NodeInfo(status = NodeStatus.Starting)))
        assertEquals("Turn on the Swarm node to publish.", publishPageBlockedReason(NodeInfo(status = NodeStatus.Stopped)))
        assertTrue(publishPageBlockedReason(NodeInfo(status = NodeStatus.Running))!!.startsWith("Publishing needs light mode"))
        assertNull(publishPageBlockedReason(NodeInfo(status = NodeStatus.Running, lightMode = true)))
    }

    @Test
    fun `the Publish entry is there for a light node, and while a publish has something to show`() {
        val light = NodeInfo(status = NodeStatus.Running, lightMode = true)
        val off = NodeInfo(status = NodeStatus.Stopped)
        assertTrue(publishEntryShown(light, Publisher.State.Idle))
        assertFalse(publishEntryShown(off, Publisher.State.Idle))
        assertFalse(publishEntryShown(NodeInfo(status = NodeStatus.Running), Publisher.State.Idle))
        assertTrue(publishEntryShown(off, Publisher.State.Running("1", "site", PublishKind.Folder)))
        assertTrue(publishEntryShown(off, Publisher.State.Finished("1")))
    }

    @Test
    fun `the confirmation says what goes out, with which stamp, and that it's public`() {
        val files = listOf(
            FolderFile("index.html", "doc", 300),
            FolderFile("style.css", "doc", 200),
        )
        val plan = PublishPlan(PublishSource.Text("x"), PublishKind.Folder, "site", 500, publishStampEstimate(listOf(300, 200)), files)
        val b = batch(batchA, depth = 17, ttl = 90_000)
        assertEquals(
            "2 files, 500 B, opening at its index.html. It's stamped with aaaaaaaa…aaaaaaaa (0% used, 1 day 1 hour). " +
                "Anyone with the link can read it, and it can't be deleted from Swarm.",
            confirmText(plan, b, listOf(b)),
        )
        val text = PublishPlan(PublishSource.Text("hi"), PublishKind.Text, "Text", 2, 8192)
        assertTrue(confirmText(text, null, emptyList()).contains("has none yet"))
    }

    @Test
    fun `a history row's status`() {
        val r = PublishRecord("1", PublishKind.File, "a", PublishStatus.Failed, 1, error = "no room")
        assertEquals("Failed: no room", publishStatusText(r))
        assertEquals("Published", publishStatusText(r.copy(status = PublishStatus.Completed)))
        assertEquals("Uploading…", publishStatusText(r.copy(status = PublishStatus.Uploading)))
    }

    @Test
    fun `finding the account's own stamps says what it found`() {
        assertTrue(discoverOutcomeText(0).startsWith("No other stamps"))
        assertEquals("Found 1 stamp this account owns; it's in the list below.", discoverOutcomeText(1))
        assertEquals("Found 3 stamps this account owns; they're in the list below.", discoverOutcomeText(3))
    }
}
