package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.ManifestCapability.Feeds
import baby.freedom.mobile.browser.ManifestCapability.Messaging
import baby.freedom.mobile.browser.ManifestCapability.Publish
import baby.freedom.mobile.browser.ManifestCapability.Signing
import baby.freedom.mobile.browser.ManifestProjection.AutoFeeds
import baby.freedom.mobile.browser.ManifestProjection.AutoPublish
import baby.freedom.mobile.browser.ManifestProjection.AutoSigning
import baby.freedom.mobile.browser.ManifestProjection.Connection
import baby.freedom.mobile.browser.ManifestProjection.FeedGrant
import baby.freedom.mobile.browser.ManifestProjection.Identity
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Swarm-hosted permission manifests (#122): the strict version 1 format,
 * what a gateway's answer means, and the reconciliation desktop's
 * `permission-manifests.js` does — who owns which grant, what a
 * redeploy takes back, and which decisions are refused as stale.
 */
class SwarmManifestsTest {
    private val site = "https://app.bzz.freedom.baby"

    // -----------------------------------------------------------------
    // Format
    // -----------------------------------------------------------------

    private fun manifestJson(swarm: String, extra: String = "") =
        """{"schema":"freedom-manifest/1","name":"Notes"$extra,"capabilities":{"swarm":{$swarm}}}"""

    private fun parse(text: String) = SwarmManifestFormat.parse(text.toByteArray(Charsets.UTF_8))

    private fun assertInvalid(text: String) {
        try {
            parse(text)
            fail("accepted: $text")
        } catch (e: InvalidManifestException) {
            // expected
        }
    }

    @Test
    fun `a valid manifest parses, rows in registry order`() {
        val m = parse(
            manifestJson(
                """"signing":{"why":"Sign updates"},"publish":{"why":"Store your files"}""",
                ""","description":"Encrypted notes"""",
            ),
        )
        assertEquals("Notes", m.name)
        assertEquals("Encrypted notes", m.description)
        assertEquals(listOf(Publish, Signing), m.capabilities.keys.toList())
        assertEquals("Store your files", m.capabilities[Publish])
    }

    @Test
    fun `description is optional and may be empty`() {
        assertEquals("", parse(manifestJson(""""publish":{"why":"x"}""")).description)
        assertEquals("", parse(manifestJson(""""publish":{"why":"x"}""", ""","description":""""")).description)
    }

    @Test
    fun `unknown fields, groups, rows and schemas invalidate the whole manifest`() {
        assertInvalid(manifestJson(""""publish":{"why":"x"}""", ""","version":2"""))
        assertInvalid(manifestJson(""""publish":{"why":"x"},"wallet":{"why":"x"}"""))
        assertInvalid(manifestJson(""""publish":{"why":"x","scope":"all"}"""))
        assertInvalid("""{"schema":"freedom-manifest/1","name":"N","capabilities":{"swarm":{"publish":{"why":"x"}},"swarmid":{}}}""")
        assertInvalid("""{"schema":"freedom-manifest/2","name":"N","capabilities":{"swarm":{"publish":{"why":"x"}}}}""")
        assertInvalid("""{"name":"N","capabilities":{"swarm":{"publish":{"why":"x"}}}}""")
        assertInvalid("""{"schema":"freedom-manifest/1","name":"N"}""")
        assertInvalid("""{"schema":"freedom-manifest/1","name":"N","capabilities":{}}""")
        assertInvalid(manifestJson(""))
        assertInvalid(manifestJson(""""publish":{}"""))
        assertInvalid(manifestJson(""""publish":{"why":"   "}"""))
        assertInvalid(manifestJson(""""publish":"yes""""))
        assertInvalid(manifestJson(""""publish":{"why":7}"""))
        assertInvalid("""["freedom-manifest/1"]""")
    }

    @Test
    fun `lengths count code points`() {
        // 32 emoji (two UTF-16 units each) is a 32-code-point name.
        val emoji32 = "😀".repeat(32)
        assertEquals(emoji32, parse(manifestJson(""""publish":{"why":"x"}""").replace("\"Notes\"", "\"$emoji32\"")).name)
        assertInvalid(manifestJson(""""publish":{"why":"x"}""").replace("\"Notes\"", "\"${"a".repeat(33)}\""))
        assertInvalid(manifestJson(""""publish":{"why":"${"w".repeat(141)}"}"""))
        parse(manifestJson(""""publish":{"why":"${"w".repeat(140)}"}"""))
        assertInvalid(manifestJson(""""publish":{"why":"x"}""", ""","description":"${"d".repeat(161)}""""))
        assertInvalid(manifestJson(""""publish":{"why":"x"}""").replace("\"Notes\"", "\" \""))
    }

    @Test
    fun `control, separator, bidi characters and lone surrogates are refused, not stripped`() {
        for (bad in listOf("\\u0000", "\\u001f", "\\u007f", "\\u009f", "\\u2028", "\\u2029", "\\u202e", "\\u2066", "\\u2069", "\\ud800", "\\n")) {
            assertInvalid(manifestJson(""""publish":{"why":"a${bad}b"}"""))
            assertInvalid(manifestJson(""""publish":{"why":"x"}""").replace("\"Notes\"", "\"N${bad}\""))
        }
        // A raw bidi override, not escaped, too.
        assertInvalid(manifestJson(""""publish":{"why":"a‮b"}"""))
        parse(manifestJson(""""publish":{"why":"Stores files — über 😀"}"""))
    }

    @Test
    fun `only strict JSON and strict UTF-8 are read`() {
        assertInvalid("""{'schema':'freedom-manifest/1','name':'N','capabilities':{'swarm':{'publish':{'why':'x'}}}}""")
        assertInvalid("""{schema:"freedom-manifest/1","name":"N","capabilities":{"swarm":{"publish":{"why":"x"}}}}""")
        assertInvalid(manifestJson(""""publish":{"why":"x"},"""))
        assertInvalid(manifestJson(""""publish":{"why":"x"}""") + " // done")
        assertInvalid(manifestJson(""""publish":{"why":"x"}""") + "x")
        assertInvalid("""{"schema":"freedom-manifest/1","schema":"freedom-manifest/1","name":"N","capabilities":{"swarm":{"publish":{"why":"x"}}}}""")
        val bytes = manifestJson(""""publish":{"why":"x"}""").toByteArray()
        val broken = bytes.copyOf().also { it[it.indexOf('x'.code.toByte())] = 0xC3.toByte() }
        try {
            SwarmManifestFormat.parse(broken)
            fail("accepted malformed UTF-8")
        } catch (e: InvalidManifestException) {
            // expected
        }
    }

    @Test
    fun `a deeply nested or oversized body is refused without crashing`() {
        assertInvalid("[".repeat(6000) + "]".repeat(6000))
        val big = manifestJson(""""publish":{"why":"x"}""", ""","description":"${" ".repeat(9000)}"""")
        try {
            SwarmManifestFormat.parse(big.toByteArray())
            fail("accepted over 8 KiB")
        } catch (e: InvalidManifestException) {
            assertTrue(e.message!!.contains("8 KiB"))
        }
    }

    @Test
    fun `the fingerprint is the schema and the sorted rows, never the wording`() {
        val a = parse(manifestJson(""""publish":{"why":"one"},"feeds":{"why":"two"}"""))
        val b = parse(manifestJson(""""feeds":{"why":"changed"},  "publish":{"why":"reworded"}""", ""","description":"new""""))
        val c = parse(manifestJson(""""publish":{"why":"one"}"""))
        assertEquals(SwarmManifestFormat.fingerprint(a), SwarmManifestFormat.fingerprint(b))
        assertFalse(SwarmManifestFormat.fingerprint(a) == SwarmManifestFormat.fingerprint(c))
    }

    // -----------------------------------------------------------------
    // What the gateway's answer means
    // -----------------------------------------------------------------

    @Test
    fun `only a 404 is absence, other 4xx are invalid, 5xx transient`() {
        assertEquals(ManifestDiscovery.Absent, manifestFromAnswer(404, ByteArray(0)))
        assertTrue(manifestFromAnswer(403, ByteArray(0)) is ManifestDiscovery.Invalid)
        assertTrue(manifestFromAnswer(500, ByteArray(0)) is ManifestDiscovery.Unresolved)
        assertTrue(manifestFromAnswer(503, ByteArray(0)) is ManifestDiscovery.Unresolved)
        assertTrue(manifestFromAnswer(200, "<html>".toByteArray()) is ManifestDiscovery.Invalid)
        val found = manifestFromAnswer(200, manifestJson(""""publish":{"why":"x"}""").toByteArray())
        assertTrue(found is ManifestDiscovery.Found)
        found as ManifestDiscovery.Found
        assertEquals(64, found.rawHash.length)
    }

    /** Serves one connection with [respond] on a loopback port; returns the base URL. */
    private fun serveOnce(respond: (java.io.OutputStream) -> Unit): Pair<String, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            server.use { s ->
                s.accept().use { c ->
                    val input = c.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    runCatching { respond(c.getOutputStream()) }
                }
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${server.localPort}/bzz/ref/freedom-manifest.json" to t
    }

    @Test
    fun `an oversized body is refused while it is read`() {
        val (url, t) = serveOnce { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n".toByteArray())
            // No Content-Length and far more than 8 KiB: the limit must hold while streaming.
            repeat(64) { out.write(ByteArray(4096) { ' '.code.toByte() }) }
            out.flush()
        }
        val result = fetchManifest(url, timeoutMs = 5_000)
        assertTrue("$result", result is ManifestDiscovery.Invalid)
        t.join(5_000)
    }

    @Test
    fun `a body that dies part-way is transient, not invalid`() {
        val (url, t) = serveOnce { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n{\"schema\":".toByteArray())
            out.flush()
        }
        val result = fetchManifest(url, timeoutMs = 5_000)
        assertTrue("$result", result is ManifestDiscovery.Unresolved)
        t.join(5_000)
    }

    @Test
    fun `a stalled body is transient once the whole-call deadline passes`() {
        val (url, t) = serveOnce { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n{".toByteArray())
            out.flush()
            Thread.sleep(10_000)
        }
        val started = System.nanoTime()
        val result = fetchManifest(url, timeoutMs = 700)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("$result", result is ManifestDiscovery.Unresolved)
        // Well before the server lets go (the JVM's watchdog thread can be held up by an earlier test's disconnect).
        assertTrue("took $tookMs ms", tookMs < 6_000)
        t.join(12_000)
    }

    @Test
    fun `discovery's deadline covers blocking work before the fetch, and passes the rest on`() {
        val started = System.nanoTime()
        // A resolver that blocks far past the deadline and ignores cancellation.
        val result = runBlocking {
            withinDeadline(300) {
                try {
                    Thread.sleep(5_000)
                } catch (e: InterruptedException) {
                    // keep blocking semantics simple: ignore
                }
                ManifestDiscovery.Absent
            }
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("$result", result is ManifestDiscovery.Unresolved)
        assertTrue("took $tookMs ms", tookMs < 2_000)
        var left = -1L
        runBlocking {
            withinDeadline(10_000) { remaining ->
                Thread.sleep(200)
                left = remaining()
                ManifestDiscovery.Absent
            }
        }
        assertTrue("left $left", left in 1..9_850)
    }

    // -----------------------------------------------------------------
    // Reconciliation
    // -----------------------------------------------------------------

    private class MemoryStorage : SwarmManifests.Storage {
        var text: String? = null
        var failWrites = false
        override fun read() = text
        override fun write(text: String) {
            if (failWrites) throw IOException("disk full")
            this.text = text
        }
    }

    private class MemoryProjections : SwarmManifests.Projections {
        val on = HashSet<Pair<String, ManifestProjection>>()
        var wallet = true
        val sets = ArrayList<Triple<String, ManifestProjection, Boolean>>()
        /** Throws on the n-th [set] from now (a crash part-way through). */
        var crashAt = -1
        override fun available(projection: ManifestProjection) =
            wallet || (projection != Identity && projection != FeedGrant)
        override suspend fun enabled(origin: String, projection: ManifestProjection) = (origin to projection) in on
        override suspend fun set(origin: String, projection: ManifestProjection, on: Boolean) {
            if (crashAt == 0) {
                crashAt = -1
                throw IOException("crash")
            }
            if (crashAt > 0) crashAt--
            sets += Triple(origin, projection, on)
            // Identity is never taken away.
            if (on) this.on += origin to projection else if (projection != Identity) this.on -= origin to projection
        }
    }

    private class Env {
        val storage = MemoryStorage()
        val projections = MemoryProjections()
        var now = 1_000_000L
        var manifests = SwarmManifests(storage, projections, clock = { now })
        var discoveries = 0

        /** A fresh instance on the same storage and grant stores: an app restart. */
        fun restart() {
            manifests = SwarmManifests(storage, projections, clock = { now })
        }

        fun check(origin: String, found: ManifestDiscovery, eager: Boolean = true) = runBlocking {
            manifests.check(origin, eager) {
                discoveries++
                found
            }
        }

        fun decide(check: SwarmManifests.Check, outcome: SwarmManifests.Outcome) = runBlocking {
            manifests.decide((check as SwarmManifests.Check.Consent).token, outcome)
        }

        fun has(origin: String, p: ManifestProjection) = (origin to p) in projections.on
    }

    private fun found(vararg rows: ManifestCapability, why: String = "because"): ManifestDiscovery.Found {
        val m = SwarmAppManifest("Notes", "", rows.associateWith { why })
        return ManifestDiscovery.Found(m, "hash-$why-${rows.joinToString()}", SwarmManifestFormat.fingerprint(m))
    }

    @Test
    fun `an untracked origin isn't fetched unless it asks to connect`() {
        val env = Env()
        assertEquals(SwarmManifests.Check.Legacy, env.check(site, found(Publish), eager = false))
        assertEquals(0, env.discoveries)
    }

    @Test
    fun `Allow all projects each row and the manifest owns what it turned on`() {
        val env = Env()
        val check = env.check(site, found(Publish, Feeds))
        check as SwarmManifests.Check.Consent
        assertEquals(listOf(Publish, Feeds), check.consent.rows.map { it.first })
        assertTrue(check.consent.createsIdentity)
        assertFalse(check.consent.isUpdate)
        assertTrue(env.decide(check, SwarmManifests.Outcome.AllowAll))
        for (p in listOf(Connection, AutoPublish, AutoFeeds, FeedGrant, Identity)) assertTrue("$p", env.has(site, p))
        assertFalse(env.has(site, AutoSigning))
        val record = runBlocking { env.manifests.record(site) }!!
        assertEquals(listOf(Publish, Feeds), record.managed[Connection])
        assertEquals(SwarmManifests.MANAGED, record.acknowledged[Publish]!!.decision)
        assertEquals("because", record.receipts.single().rows.first().second)
        // The next navigation finds it fresh: no second sheet.
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish, Feeds), eager = false))
        assertEquals(mapOf(site to listOf(Publish, Feeds)), env.manifests.managedRows())
    }

    @Test
    fun `Use individual approvals connects, projects nothing else, and doesn't ask again`() {
        val env = Env()
        assertTrue(env.decide(env.check(site, found(Publish, Signing)), SwarmManifests.Outcome.Individual))
        assertTrue(env.has(site, Connection))
        assertFalse(env.has(site, AutoPublish))
        assertFalse(env.has(site, AutoSigning))
        assertFalse(env.has(site, FeedGrant))
        val record = runBlocking { env.manifests.record(site) }!!
        assertTrue(Connection in record.detached)
        assertTrue(record.managed.isEmpty())
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish, Signing), eager = false))
        // The connection is the user's: a manifest that disappears doesn't take it.
        assertEquals(SwarmManifests.Check.Legacy, env.check(site, ManifestDiscovery.Absent, eager = false))
        assertTrue(env.has(site, Connection))
    }

    @Test
    fun `Don't allow on first contact leaves nothing behind`() {
        val env = Env()
        assertFalse(env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.Deny))
        assertTrue(env.projections.on.isEmpty())
        assertNull(runBlocking { env.manifests.record(site) })
        assertEquals(SwarmManifests.Check.Legacy, env.check(site, found(Publish), eager = false))
    }

    @Test
    fun `a redeploy that drops a row takes back only what that row alone owned`() {
        val env = Env()
        env.decide(env.check(site, found(Feeds, Signing, Publish)), SwarmManifests.Outcome.AllowAll)
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Feeds, Publish), eager = false))
        assertFalse(env.has(site, AutoSigning))
        // Shared with feeds: kept.
        assertTrue(env.has(site, FeedGrant))
        assertTrue(env.has(site, Connection))
        env.check(site, found(Publish), eager = false)
        assertFalse(env.has(site, FeedGrant))
        assertFalse(env.has(site, AutoFeeds))
        // Removal never deletes an identity.
        assertTrue(env.has(site, Identity))
        assertTrue(env.has(site, AutoPublish))
        assertTrue(env.has(site, Connection))
        // A removed row added back is a new decision.
        assertTrue(env.check(site, found(Publish, Signing), eager = false) is SwarmManifests.Check.Consent)
    }

    @Test
    fun `a mixed update applies its removals even when its additions are refused`() {
        val env = Env()
        env.decide(env.check(site, found(Publish, Feeds)), SwarmManifests.Outcome.AllowAll)
        val update = env.check(site, found(Feeds, Signing), eager = false)
        update as SwarmManifests.Check.Consent
        assertTrue(update.consent.isUpdate)
        assertEquals(listOf(Publish), update.consent.removed)
        assertEquals(listOf(Signing), update.consent.rows.map { it.first })
        assertTrue(update.consent.preservedIdentity)
        assertFalse(env.has(site, AutoPublish))
        // Declining an update's new rows doesn't refuse what the site already has.
        assertTrue(env.decide(update, SwarmManifests.Outcome.Deny))
        assertFalse(env.has(site, AutoSigning))
        assertTrue(env.has(site, AutoFeeds))
        assertNotNull(runBlocking { env.manifests.record(site) })
    }

    @Test
    fun `declining an update's new rows keeps the request going and isn't asked again`() {
        val env = Env()
        env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        val update = env.check(site, found(Publish, Signing), eager = false)
        assertTrue(update is SwarmManifests.Check.Consent)
        val sets = env.projections.sets.size
        assertTrue(env.decide(update, SwarmManifests.Outcome.Deny))
        assertEquals(sets, env.projections.sets.size)
        assertTrue(env.has(site, AutoPublish))
        val record = runBlocking { env.manifests.record(site) }!!
        assertEquals(SwarmManifests.INDIVIDUAL, record.acknowledged[Signing]!!.decision)
        assertEquals(SwarmManifests.DECLINED, record.acknowledged[Signing]!!.source)
        assertEquals(SwarmManifests.DECLINED, record.receipts.last().outcome)
        assertEquals(listOf(Publish), env.manifests.managedRows()[site])
        // The next document finds it decided.
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish, Signing), eager = false))
    }

    @Test
    fun `an unanswered first contact leaves the origin untracked`() {
        val env = Env()
        assertTrue(env.check(site, found(Publish)) is SwarmManifests.Check.Consent)
        assertNull(runBlocking { env.manifests.record(site) })
        assertNull(env.storage.text?.takeIf { it.contains(site) })
        // The process dies before the user answers: the site is on the ordinary flow.
        env.restart()
        assertEquals(SwarmManifests.Check.Legacy, env.check(site, found(Publish), eager = false))
        assertEquals(1, env.discoveries)
        // Answered later (same process), the untracked base still works.
        val env2 = Env()
        val c = env2.check(site, found(Publish))
        assertTrue(env2.decide(c, SwarmManifests.Outcome.AllowAll))
        assertTrue(env2.has(site, AutoPublish))
        assertEquals(1, runBlocking { env2.manifests.record(site) }!!.revision)
    }

    @Test
    fun `a first-contact consent is stale once another answer tracked the origin`() {
        val env = Env()
        val a = env.check(site, found(Publish)) as SwarmManifests.Check.Consent
        val b = env.check(site, found(Publish, Feeds)) as SwarmManifests.Check.Consent
        assertTrue(env.decide(b, SwarmManifests.Outcome.Individual))
        try {
            env.decide(a, SwarmManifests.Outcome.AllowAll)
            fail("a stale first-contact consent was accepted")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertFalse(env.has(site, AutoPublish))
    }

    @Test
    fun `grants the user already had stay theirs through Allow all and removal`() {
        val env = Env()
        env.projections.on += site to Connection
        env.projections.on += site to AutoFeeds
        env.decide(env.check(site, found(Publish, Feeds)), SwarmManifests.Outcome.AllowAll)
        val record = runBlocking { env.manifests.record(site) }!!
        assertNull(record.managed[Connection])
        assertNull(record.managed[AutoFeeds])
        env.check(site, ManifestDiscovery.Absent, eager = false)
        assertTrue(env.has(site, Connection))
        assertTrue(env.has(site, AutoFeeds))
        assertFalse(env.has(site, AutoPublish))
        assertFalse(env.has(site, FeedGrant))
        assertNull(runBlocking { env.manifests.record(site) })
    }

    @Test
    fun `pruning keeps a manifest-owned connection that carries the user's own always-allow`() {
        for (gone in listOf<ManifestDiscovery>(ManifestDiscovery.Absent, found(Signing))) {
            val env = Env()
            env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
            // Later the user approves a createFeed sheet with Always allow, by hand.
            env.projections.on += site to AutoFeeds
            env.projections.on += site to FeedGrant
            val result = env.check(site, gone, eager = false)
            assertFalse("$gone", env.has(site, AutoPublish))
            assertTrue("$gone", env.has(site, Connection))
            assertTrue("$gone", env.has(site, AutoFeeds))
            assertTrue("$gone", env.has(site, FeedGrant))
            assertFalse("$gone", env.projections.sets.any { it.second == Connection && !it.third })
            if (result is SwarmManifests.Check.Consent) {
                // The connection is now the user's: a later removal leaves it too.
                assertTrue(Connection in runBlocking { env.manifests.record(site) }!!.detached)
            }
        }
        // Without the user's own grants the connection goes, as before.
        val env = Env()
        env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        env.check(site, ManifestDiscovery.Absent, eager = false)
        assertFalse(env.has(site, Connection))
    }

    @Test
    fun `a row the user already granted by hand is acknowledged without a sheet`() {
        val env = Env()
        env.projections.on += site to Connection
        env.projections.on += site to AutoPublish
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish)))
        val record = runBlocking { env.manifests.record(site) }!!
        assertEquals("existing-grant", record.acknowledged[Publish]!!.source)
        assertEquals(SwarmManifests.INDIVIDUAL, record.acknowledged[Publish]!!.decision)
    }

    @Test
    fun `an invalid or unsupported manifest empties a tracked origin's manifest authority`() {
        for (gone in listOf(ManifestDiscovery.Invalid("bad"), ManifestDiscovery.Unsupported, ManifestDiscovery.Absent)) {
            val env = Env()
            env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
            assertEquals(SwarmManifests.Check.Legacy, env.check(site, gone, eager = false))
            assertFalse("$gone", env.has(site, AutoPublish))
            assertFalse("$gone", env.has(site, Connection))
            assertNull(runBlocking { env.manifests.record(site) })
        }
    }

    @Test
    fun `a transient failure neither grants nor revokes, and backs off`() {
        val env = Env()
        env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        val down = ManifestDiscovery.Unresolved("node stopped")
        assertTrue(env.check(site, down, eager = false) is SwarmManifests.Check.Unresolved)
        assertTrue(env.has(site, AutoPublish))
        assertEquals(2, env.discoveries)
        // Within the 2 s backoff: no fetch, still unresolved.
        env.now += 1_000
        assertTrue(env.check(site, found(Publish), eager = false) is SwarmManifests.Check.Unresolved)
        assertEquals(2, env.discoveries)
        env.now += 1_500
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish), eager = false))
        assertEquals(3, env.discoveries)
        // An untracked origin just stays on the ordinary flow.
        assertEquals(SwarmManifests.Check.Legacy, env.check("https://other.bzz.freedom.baby", down))
    }

    @Test
    fun `a clock stepped back can't stretch the backoff`() {
        val env = Env()
        env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        env.check(site, ManifestDiscovery.Unresolved("x"), eager = false)
        env.now -= 3_600_000
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish), eager = false))
    }

    @Test
    fun `a wording-only redeploy needs no new consent, and the receipt keeps what was shown`() {
        val env = Env()
        env.decide(env.check(site, found(Publish, why = "first words")), SwarmManifests.Outcome.AllowAll)
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish, why = "new words"), eager = false))
        val record = runBlocking { env.manifests.record(site) }!!
        assertEquals("first words", record.receipts.single().rows.single().second)
        assertEquals("new words", record.observed!!.capabilities[Publish])
    }

    @Test
    fun `a decision against changed state is refused and grants nothing`() {
        val env = Env()
        val stale = env.check(site, found(Publish))
        // Meanwhile the app is redeployed with another set.
        val fresh = env.check(site, found(Publish, Feeds))
        try {
            env.decide(stale, SwarmManifests.Outcome.AllowAll)
            fail("a stale consent was accepted")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(env.projections.on.isEmpty())
        assertTrue(env.decide(fresh, SwarmManifests.Outcome.AllowAll))
    }

    @Test
    fun `two tabs checking the same state share one consent, and an answer landing while the first is recorded replays it`() {
        val env = Env()
        val a = env.check(site, found(Publish)) as SwarmManifests.Check.Consent
        val b = env.check(site, found(Publish)) as SwarmManifests.Check.Consent
        assertEquals(a.token, b.token)
        assertTrue(env.decide(a, SwarmManifests.Outcome.AllowAll))
        val sets = env.projections.sets.size
        assertTrue(env.decide(b, SwarmManifests.Outcome.Deny))
        assertEquals(sets, env.projections.sets.size)
    }

    @Test
    fun `an expired consent grants nothing`() {
        val env = Env()
        val check = env.check(site, found(Publish))
        env.now += SwarmManifests.TOKEN_TTL_MS + 1
        try {
            env.decide(check, SwarmManifests.Outcome.AllowAll)
            fail("an expired consent was accepted")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(env.projections.on.isEmpty())
    }

    @Test
    fun `a crash part-way through Allow all is finished on restart, still owned by the manifest`() {
        val env = Env()
        val check = env.check(site, found(Publish, Feeds))
        env.projections.crashAt = 2
        try {
            env.decide(check, SwarmManifests.Outcome.AllowAll)
            fail("the crash didn't happen")
        } catch (e: IOException) {
            // the process "dies" here
        }
        assertTrue(env.has(site, Connection))
        assertFalse(env.has(site, AutoFeeds))
        env.restart()
        val record = runBlocking { env.manifests.record(site) }!!
        for (p in listOf(Connection, AutoPublish, AutoFeeds, FeedGrant, Identity)) assertTrue("$p", env.has(site, p))
        assertEquals(listOf(Publish, Feeds), record.managed[Connection])
        // And the manifest can still take back what it granted.
        env.check(site, ManifestDiscovery.Absent, eager = false)
        assertFalse(env.has(site, Connection))
    }

    @Test
    fun `a decision that can't be saved grants nothing`() {
        val env = Env()
        val check = env.check(site, found(Publish))
        env.storage.failWrites = true
        try {
            env.decide(check, SwarmManifests.Outcome.AllowAll)
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertTrue(env.projections.on.isEmpty())
    }

    @Test
    fun `state survives a restart, and a corrupt file starts afresh without throwing`() {
        val env = Env()
        env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        env.restart()
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish), eager = false))
        env.storage.text = "{not json"
        env.restart()
        assertNull(runBlocking { env.manifests.record(site) })
        env.storage.text = "[".repeat(20_000)
        env.restart()
        assertNull(runBlocking { env.manifests.record(site) })
    }

    @Test
    fun `Ask each time takes back what the manifest granted but keeps the connection`() {
        val env = Env()
        env.decide(env.check(site, found(Publish, Signing)), SwarmManifests.Outcome.AllowAll)
        assertTrue(runBlocking { env.manifests.useIndividual(site) })
        assertTrue(env.has(site, Connection))
        assertFalse(env.has(site, AutoPublish))
        assertFalse(env.has(site, AutoSigning))
        assertTrue(env.manifests.managedRows().isEmpty())
        // The same rows aren't offered again as a batch.
        assertEquals(SwarmManifests.Check.Ready, env.check(site, found(Publish, Signing), eager = false))
        // And the manifest going away leaves the user's connection alone.
        env.check(site, ManifestDiscovery.Absent, eager = false)
        assertTrue(env.has(site, Connection))
    }

    @Test
    fun `forget drops tracking and pending consents`() {
        val env = Env()
        env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        val pending = env.check(site, found(Publish, Feeds), eager = false)
        runBlocking { env.manifests.forget(site) }
        assertNull(runBlocking { env.manifests.record(site) })
        try {
            env.decide(pending, SwarmManifests.Outcome.AllowAll)
            fail("a consent survived forget")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `with no wallet, feed access and identity are left to the signing sheet`() {
        val env = Env()
        env.projections.wallet = false
        val check = env.check(site, found(Feeds)) as SwarmManifests.Check.Consent
        assertTrue(check.consent.needsWallet)
        assertFalse(check.consent.createsIdentity)
        env.decide(check, SwarmManifests.Outcome.AllowAll)
        assertTrue(env.has(site, AutoFeeds))
        assertFalse(env.has(site, FeedGrant))
        assertFalse(env.has(site, Identity))
        val record = runBlocking { env.manifests.record(site) }!!
        assertNull(record.managed[FeedGrant])
    }

    @Test
    fun `messaging is acknowledged but only ever connects`() {
        val env = Env()
        env.decide(env.check(site, found(Messaging)), SwarmManifests.Outcome.AllowAll)
        assertEquals(setOf(site to Connection), env.projections.on)
    }

    @Test
    fun `receipts are bounded`() {
        val env = Env()
        repeat(SwarmManifests.MAX_RECEIPTS + 5) { i ->
            val rows = if (i % 2 == 0) arrayOf(Publish, Feeds) else arrayOf(Publish, Signing)
            val c = env.check(site, found(*rows), eager = true)
            if (c is SwarmManifests.Check.Consent) env.decide(c, SwarmManifests.Outcome.Individual)
        }
        assertEquals(SwarmManifests.MAX_RECEIPTS, runBlocking { env.manifests.record(site) }!!.receipts.size)
    }

    @Test
    fun `an unresolved fetch doesn't hold up a tracked origin whose manifest manages nothing (#226 R3-F3)`() {
        val down = ManifestDiscovery.Unresolved("node stopped")
        // Connected by hand, then Don't allow on the manifest sheet: a record, nothing managed.
        val declined = Env()
        declined.projections.on += site to Connection
        val c = declined.check(site, found(Publish))
        assertTrue(declined.decide(c, SwarmManifests.Outcome.Deny))
        assertNotNull(runBlocking { declined.manifests.record(site) })
        // After Ask each time, too.
        val individual = Env()
        individual.decide(individual.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        runBlocking { individual.manifests.useIndividual(site) }
        for (env in listOf(declined, individual)) {
            assertEquals(SwarmManifests.Check.Legacy, env.check(site, down, eager = false))
            // Within the backoff too.
            assertEquals(SwarmManifests.Check.Legacy, env.check(site, found(Publish), eager = false))
            assertTrue(env.has(site, Connection))
        }
        // A record that does manage something still waits.
        val managed = Env()
        managed.decide(managed.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        assertTrue(managed.check(site, down, eager = false) is SwarmManifests.Check.Unresolved)
    }

    // -----------------------------------------------------------------
    // The bridge's check: the sheet, the tab, and the document's cache
    // -----------------------------------------------------------------

    private val tabs = mutableListOf<BrowserState>()

    @After
    fun closeTabs() {
        tabs.forEach { SwarmProviders.onTabClosed(it.id) }
    }

    private fun tab() = BrowserState(9_500L + tabs.size).also { tabs += it }

    /** Runs the bridge's check on [tab], answering its sheet with [answer] (null: leave it to time out after [waitMs]). */
    private fun bridgeCheck(
        env: Env,
        tab: BrowserState,
        found: ManifestDiscovery,
        answer: SwarmProvider.Answer?,
        eager: Boolean = true,
        waitMs: Long = SwarmProviders.SHEET_WAIT_MS,
    ) = runBlocking {
        val r = async { SwarmProviders.manifestCheck(env.manifests, tab, 0, site, eager, { waitMs }) { found } }
        if (answer != null) {
            while (tab.swarmPrompt == null && !r.isCompleted) yield()
            tab.swarmPrompt?.respond(answer)
        }
        r.await()
    }

    @Test
    fun `a manifest sheet nobody answered refuses only that request (#226 R3-F1)`() {
        val env = Env()
        val tab = tab()
        val timedOut = bridgeCheck(env, tab, found(Publish), null, waitMs = 50)
        assertEquals(SwarmProvider.USER_REJECTED, timedOut.error!!.code)
        assertFalse("not the user's refusal: not held for the document", timedOut.holds)
        // The next request asks again, and the user's answer counts.
        val allowed = bridgeCheck(env, tab, found(Publish), SwarmProvider.Answer(true, always = true))
        assertNull(allowed.error)
        assertTrue(env.has(site, AutoPublish))
        // The user's own Don't allow does hold, and blocks the tab.
        val other = Env()
        val t2 = tab()
        val refused = bridgeCheck(other, t2, found(Publish), SwarmProvider.Answer.REJECTED)
        assertEquals(SwarmProvider.USER_REJECTED, refused.error!!.code)
        assertTrue(refused.holds)
        assertFalse(runBlocking { SwarmProviders.askOnTab(t2, 0, SwarmAsk.Connect(site), waitMs = 50) }.allowed)
        assertNull(t2.swarmPrompt)
    }

    @Test
    fun `a shared consent decided in one tab takes the other tab's sheet down, which follows it (#226 R3-F2)`() {
        for (first in listOf(SwarmProvider.Answer.REJECTED, SwarmProvider.Answer(true, always = true))) {
            val env = Env()
            val t1 = tab()
            val t2 = tab()
            runBlocking {
                val a = async { SwarmProviders.manifestCheck(env.manifests, t1, 0, site, true, { SwarmProviders.SHEET_WAIT_MS }) { found(Publish) } }
                val b = async { SwarmProviders.manifestCheck(env.manifests, t2, 0, site, true, { SwarmProviders.SHEET_WAIT_MS }) { found(Publish) } }
                while ((t1.swarmPrompt == null || t2.swarmPrompt == null) && !a.isCompleted && !b.isCompleted) yield()
                val shared = (t1.swarmPrompt!!.ask as SwarmAsk.Manifest).token
                assertEquals(shared, (t2.swarmPrompt!!.ask as SwarmAsk.Manifest).token)
                t1.swarmPrompt!!.respond(first)
                val ra = a.await()
                // Tab 2's sheet is gone without the user touching it, and its request follows the answer.
                val rb = b.await()
                assertNull(t2.swarmPrompt)
                assertEquals(first.allowed, ra.error == null)
                assertEquals(first.allowed, rb.error == null)
                assertTrue(rb.holds)
            }
            assertEquals(first.allowed, env.has(site, AutoPublish))
            // Tab 2's user refused nothing: its pages may still ask.
            val connect = runBlocking {
                val r = async { SwarmProviders.askOnTab(t2, 0, SwarmAsk.Connect(site)) }
                while (t2.swarmPrompt == null && !r.isCompleted) yield()
                t2.swarmPrompt?.respond(SwarmProvider.Answer(true))
                r.await()
            }
            assertTrue(connect.allowed)
        }
    }

    @Test
    fun `Don't allow on an update lets the request's own sheets show (#226 R3-F4)`() {
        val env = Env()
        env.decide(env.check(site, found(Publish)), SwarmManifests.Outcome.AllowAll)
        val tab = tab()
        val declined = bridgeCheck(env, tab, found(Publish, Signing), SwarmProvider.Answer.REJECTED, eager = false)
        assertNull("the request goes on under the authority the site had", declined.error)
        // The request's own per-action sheet is still shown.
        val sign = runBlocking {
            val r = async { SwarmProviders.askOnTab(tab, 0, SwarmAsk.Connect(site)) }
            while (tab.swarmPrompt == null && !r.isCompleted) yield()
            assertNotNull("a sheet was shown", tab.swarmPrompt)
            tab.swarmPrompt?.respond(SwarmProvider.Answer(true))
            r.await()
        }
        assertTrue(sign.allowed)
    }

    @Test
    fun `a blocked tab's manifest refusal holds for the document, without re-discovering (#226 R4-F1)`() {
        val env = Env()
        val tab = tab()
        // The user refuses a sheet: the tab is blocked for its document.
        runBlocking {
            val r = async { SwarmProviders.askOnTab(tab, 0, SwarmAsk.Connect(site)) }
            while (tab.swarmPrompt == null && !r.isCompleted) yield()
            tab.swarmPrompt!!.respond(SwarmProvider.Answer.REJECTED)
            assertFalse(r.await().allowed)
        }
        assertTrue(SwarmProviders.blocked(tab, 0))
        var discoveries = 0
        runBlocking {
            repeat(5) {
                val err = SwarmProviders.manifestCached(tab, 0, site, eager = true, on = this) { eager ->
                    SwarmProviders.manifestCheck(env.manifests, tab, 0, site, eager, { SwarmProviders.SHEET_WAIT_MS }) {
                        discoveries++
                        found(Publish)
                    }
                }
                assertEquals(SwarmProvider.USER_REJECTED, err!!.code)
                assertNull("no sheet on a blocked tab", tab.swarmPrompt)
            }
        }
        assertEquals("one check for the document, not one per request", 1, discoveries)
        assertFalse("nothing was decided", env.has(site, AutoPublish))
        // The user navigates the tab themselves: it asks again, and the answer counts.
        SwarmProviders.allowPrompts(tab.id)
        val allowed = runBlocking {
            val r = async {
                SwarmProviders.manifestCached(tab, 0, site, eager = true, on = this) { eager ->
                    SwarmProviders.manifestCheck(env.manifests, tab, 0, site, eager, { SwarmProviders.SHEET_WAIT_MS }) {
                        discoveries++
                        found(Publish)
                    }
                }
            }
            while (tab.swarmPrompt == null && !r.isCompleted) yield()
            tab.swarmPrompt!!.respond(SwarmProvider.Answer(true, always = true))
            r.await()
        }
        assertNull(allowed)
        assertEquals(2, discoveries)
        assertTrue(env.has(site, AutoPublish))
    }
}
