package baby.freedom.mobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLogsTest {

    // ---- LogRing: the bound (#276) ----

    @Test
    fun `the ring keeps at most maxLines, dropping the oldest`() {
        val ring = LogRing(maxLines = 3, maxChars = 1_000, maxLineChars = 100)
        for (i in 1..10) ring.add("line $i")
        assertEquals(listOf("line 8", "line 9", "line 10"), ring.snapshot())
        assertEquals(3, ring.size)
    }

    @Test
    fun `the ring keeps at most maxChars, dropping the oldest`() {
        val ring = LogRing(maxLines = 100, maxChars = 25, maxLineChars = 10)
        for (i in 0 until 10) ring.add("0123456789")
        assertEquals(2, ring.size)
        assertTrue(ring.totalChars <= 25)
        ring.add("abc")
        assertEquals(listOf("0123456789", "0123456789", "abc"), ring.snapshot())
        assertEquals(23, ring.totalChars)
    }

    @Test
    fun `a long line is cut to maxLineChars`() {
        val ring = LogRing(maxLines = 10, maxChars = 100, maxLineChars = 8)
        ring.add("abcdefghijklmnop")
        assertEquals(listOf("abcdefg…"), ring.snapshot())
        assertEquals(8, ring.totalChars)
    }

    @Test
    fun `the default ring stays bounded under a flood`() {
        val ring = LogRing()
        val line = "x".repeat(5_000)
        repeat(20_000) { ring.add("$it $line") }
        assertTrue(ring.size <= LogRing.MAX_LINES)
        assertTrue(ring.totalChars <= LogRing.MAX_CHARS)
        assertEquals(ring.totalChars, ring.snapshot().sumOf { it.length })
        assertTrue(ring.snapshot().all { it.length <= LogRing.MAX_LINE_CHARS })
        // Newest last.
        assertTrue(ring.snapshot().last().startsWith("19999 "))
        assertTrue(ring.text().length < LogRing.MAX_CHARS + LogRing.MAX_LINES)
    }

    @Test
    fun `bulk chatter only pushes out its own older lines, rare lines stay`() {
        // A page load as freedom-ipfs logs it (R3-M2): a request_start and a
        // name_resolve, then hundreds of per-block lines.
        val ring = LogRing(maxLines = 1_000, maxChars = 2_000, maxLineChars = 100)
        ring.add("request_start 1", "request_start")
        ring.add("name_resolve 1", "name_resolve")
        repeat(500) { ring.add("block_store_get $it " + "x".repeat(40), "block_store_get") }
        ring.add("provider_lookup 1", "provider_lookup")
        repeat(500) { ring.add("block_fetch_total $it " + "y".repeat(40), "block_fetch_total") }
        val kept = ring.snapshot()
        assertTrue(ring.totalChars <= 2_000)
        assertEquals(ring.totalChars, kept.sumOf { it.length })
        assertEquals(listOf("request_start 1", "name_resolve 1"), kept.take(2))
        assertTrue("provider_lookup 1" in kept)
        // Both bulk kinds keep their newest lines, and the order is the order they came in.
        assertTrue(kept.any { it.startsWith("block_store_get 499 ") })
        assertTrue(kept.last().startsWith("block_fetch_total 499 "))
        assertTrue(kept.indexOf("provider_lookup 1") > kept.indexOfLast { it.startsWith("block_store_get") })
        assertEquals(
            kept.filter { it.startsWith("block_fetch_total") },
            kept.filter { it.startsWith("block_fetch_total") }.sortedBy { it.split(' ')[1].toInt() },
        )
    }

    @Test
    fun `the line bound goes by kind too`() {
        val ring = LogRing(maxLines = 5, maxChars = 10_000, maxLineChars = 100)
        ring.add("err", "error")
        repeat(20) { ring.add("chat $it", "chat") }
        assertEquals(listOf("err", "chat 16", "chat 17", "chat 18", "chat 19"), ring.snapshot())
        assertEquals(5, ring.size)
    }

    @Test
    fun `once the rare kind is the biggest, its own oldest line goes`() {
        val ring = LogRing(maxLines = 3, maxChars = 10_000, maxLineChars = 100)
        ring.add("a1", "a")
        ring.add("a2", "a")
        ring.add("b1", "b")
        ring.add("a3", "a")
        assertEquals(listOf("a2", "b1", "a3"), ring.snapshot())
    }

    @Test
    fun `a line's kind is its tracing phase, else its tag`() {
        assertEquals(
            "block_store_get",
            LogRing.kindOf("ant-ffi", """INFO gateway_request{namespace="ipns"}: freedom_ipfs_retrieval: phase="block_store_get" cache_hit=false"""),
        )
        assertEquals("ant-ffi", LogRing.kindOf("ant-ffi", "INFO ant_p2p: peer set below floor"))
        assertEquals("NodeService", LogRing.kindOf("NodeService", "swarm → Running"))
        assertEquals("ant-ffi", LogRing.kindOf("ant-ffi", "phase=\"unterminated"))
    }

    @Test
    fun `an empty ring reads as no text`() {
        assertEquals("", LogRing().text())
    }

    // ---- LogScrub: no visited page's address is kept ----

    @Test
    fun `an ipfs gateway request's path and cids are taken out`() {
        val line = """I ant-ffi: INFO gateway_request{process_id=3826 request_id=1 top_level_path= namespace="ipfs" """ +
            """path=/ipfs/bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi range=}: freedom_ipfs_gateway: """ +
            """phase="mime_total" cid=bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi """ +
            """file_cid=bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi unixfs_path="/docs/secret.html" mime=text/html"""
        val out = LogScrub.scrub(line)
        assertFalse(out, "bafybei" in out)
        assertFalse(out, "secret" in out)
        assertTrue(out, """namespace="ipfs"""" in out)
        assertTrue(out, "mime=text/html" in out)
        assertTrue(out, "request_id=1" in out)
    }

    @Test
    fun `a dnslink name of a visited ipns site is taken out`() {
        // The name_* phases of freedom-ipfs's namesys, as logged opening ipns://docs.ipfs.tech/.
        val lines = listOf(
            """INFO gateway_request{request_id=3 top_level_path= namespace="ipns" path=/ipns/docs.ipfs.tech/ range=}: """ +
                """freedom_ipfs_namesys: phase="name_cache" name="docs.ipfs.tech" cache_hit=false""",
            """INFO freedom_ipfs_gateway: phase="name_persistent_cache" name="docs.ipfs.tech" cache_hit=true """ +
                """resolved_target=/ipfs/bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi""",
            """WARN freedom_ipfs_gateway: phase="name_persistent_cache_store" name="docs.ipfs.tech" """ +
                """resolved_target="/ipns/docs.ipfs.tech" error=disk full""",
            """WARN freedom_ipfs_gateway: phase="name_resolve" ok=false error=dnslink record not found for docs.ipfs.tech elapsed_ms=12""",
            """WARN freedom_ipfs_gateway: phase="name_resolve" ok=false error="invalid dnslink record: dnslink=/ipns/docs.ipfs.tech"""",
            "DEBUG doh query _dnslink.docs.ipfs.tech TXT",
            """INFO freedom_ipfs_gateway: phase="gateway_conditional" etag="\"fi1:bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi:docs/a.html:12\"" """,
            """INFO freedom_ipfs_gateway: phase="x" directory_cid=QmT5NvUtoM5nWFfrQdVrFtvGfKFmG7AHE8P34isapyhCxX source_peer_previous_top_level_path=/ipns/docs.ipfs.tech""",
        )
        for (l in lines) {
            val out = LogScrub.scrub(l)
            assertFalse(out, "docs" in out)
            assertFalse(out, "bafybei" in out)
            assertFalse(out, "QmT5" in out)
        }
        assertEquals(
            """WARN freedom_ipfs_gateway: phase="name_resolve" ok=false error=dnslink record not found for <redacted> elapsed_ms=12""",
            LogScrub.scrub(lines[3]),
        )
        assertEquals(
            """INFO freedom_ipfs_namesys: phase="name_cache" name=<redacted> cache_hit=false""",
            LogScrub.scrub("""INFO freedom_ipfs_namesys: phase="name_cache" name="docs.ipfs.tech" cache_hit=false"""),
        )
    }

    @Test
    fun `a bare cid and an ipns key are taken out`() {
        val out = LogScrub.scrub(
            "resolving bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi via " +
                "k51qzi5uqu5dlvj2baxnqndepeb86cbk3ng7n3i46uzyxzyqj2xjonzllnv0v8",
        )
        assertEquals("resolving <cid> via <cid>", out)
    }

    @Test
    fun `a cids list and base58 cids are taken out`() {
        val root = "QmT5NvUtoM5nWFfrQdVrFtvGfKFmG7AHE8P34isapyhCxX"
        val block = "QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        val v1 = "zdj7WWeQ43G6JJvLWQWZpyHuAMq6uYWRjkBXFad11vE2LHhQ7"
        for (line in listOf(
            """phase="bitswap_dial_plan" cid=$root cids=$root,$block peers=3""",
            """phase="bitswap_dial_plan" cids=["$root", "$block"] peers=3""",
            """phase="bitswap_dial_plan" cids="$root,$block" peers=3""",
            "fetching block $block for $root, then $v1",
        )) {
            val out = LogScrub.scrub(line)
            assertFalse(out, "Qm" in out)
            assertFalse(out, v1 in out)
        }
        assertEquals(
            "fetching block <cid> for <cid>, then <cid>",
            LogScrub.scrub("fetching block $block for $root, then $v1"),
        )
        assertEquals(
            "phase=\"bitswap_dial_plan\" cids=<redacted> peers=3",
            LogScrub.scrub("phase=\"bitswap_dial_plan\" cids=[\"$root\", \"$block\"] peers=3"),
        )
    }

    @Test
    fun `a qm peer id named as a peer is kept, radicle node ids too`() {
        val peer = "QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN"
        val lines = listOf(
            "INFO ant_p2p: libp2p connected peer_id=$peer",
            "INFO freedom_ipfs: dial peer=\"$peer\" ok",
            "dial /dnsaddr/bootstrap.libp2p.io/p2p/$peer",
            "INFO freedom_ipfs: dial peer_id=12D3KooWDpJ7As7BWAwRMfu1VU2WCqNjvq387JEYKDBj4kx6nXTN",
            "I RadicleNode: node id z6MksFqXN3Yhqk8pTJdUGLwATkRfQvwZXPqR2qMEhbS9wzpT",
        )
        for (l in lines) assertEquals(l, LogScrub.scrub(l))
    }

    @Test
    fun `urls of every scheme are taken out`() {
        val out = LogScrub.scrub(
            "fetch https://example.com/private?q=1 and bzz://site.eth/page and ipfs://bafy/x and rad://z3gq/tree",
        )
        assertEquals("fetch <url> and <url> and <url> and <url>", out)
    }

    @Test
    fun `a swarm reference is taken out, an overlay or account is kept`() {
        val ref = "d1a7ccbfb34e28a2e4c1d1f8ae1f2c1b0b1f4d6e7a8b9c0d1e2f3a4b5c6d7e8f"
        val out = LogScrub.scrub("WARN ant_p2p: fetch chunk $ref: all peers failed for chunk $ref attempt=1")
        assertEquals("WARN ant_p2p: fetch chunk <ref>: all peers failed for chunk <ref> attempt=1", out)
        val encrypted = LogScrub.scrub("ref ${ref}$ref.")
        assertEquals("ref <ref>.", encrypted)
        val node = "ant-ffi node started eth=0xcefda87aab813e5c5814bd77daf7dab50c4d05c4 " +
            "overlay=0xe584521f0be9bb36ee968b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d"
        assertEquals(node, LogScrub.scrub(node))
    }

    @Test
    fun `a gateway path is taken out`() {
        assertEquals(
            "GET /bzz/<redacted> 404, /bytes/<redacted> 200",
            LogScrub.scrub("GET /bzz/swarm.eth/index.html 404, /bytes/abc 200"),
        )
    }

    @Test
    fun `an onion name is taken out`() {
        val onion = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
        assertEquals("connecting to <onion>:443", LogScrub.scrub("connecting to $onion:443"))
    }

    @Test
    fun `peer ids, multiaddrs and node lines are kept`() {
        val lines = listOf(
            "INFO ant_p2p: libp2p connected agent=\"bee/2.8.2\" peer_id=QmTxX73q8dDiVbmXU7GqMNwG3gWmjSFECuMoCsTW4x",
            "INFO ant_p2p: advertising external address addr=/ip4/65.109.31.252/tcp/47198 source=\"observed\"",
            "INFO ant_p2p: bzz handshake ok overlay=6b2bba…91 full_node=true peer_set_size=1",
            "I NodeService: swarm → Running  peers=113",
            "I RadicleNode: seed rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5 → done",
        )
        for (l in lines) assertEquals(l, LogScrub.scrub(l))
    }

    @Test
    fun `a path with a comma is taken out whole`() {
        // Opening ipfs://<cid>/alan%2520probe%2520secret,diary%2520entry.html (R4-F1).
        val cid = "QmT5NvUtoM5nWFfrQdVrFtvGfKFmG7AHE8P34isapyhCxX"
        val lines = listOf(
            """INFO gateway_request{request_id=2 path=/ipfs/$cid/alan%20probe%20secret,diary%20entry.html}: """ +
                """freedom_ipfs_gateway: phase="request_start" path=/ipfs/$cid/alan%20probe%20secret,diary%20entry.html method=GET""",
            """INFO x: phase="y" top_level_path=/a,b,diary%20entry.html,""",
            """INFO gateway_request{path=/ipfs/x,diary}""",
        )
        for (l in lines) {
            val out = LogScrub.scrub(l)
            assertFalse(out, "diary" in out)
            assertFalse(out, "secret" in out)
        }
        assertEquals(
            """INFO gateway_request{request_id=2 path=<redacted>}: freedom_ipfs_gateway: phase="request_start" path=<redacted> method=GET""",
            LogScrub.scrub(lines[0]),
        )
        assertEquals("""INFO gateway_request{path=<redacted>}""", LogScrub.scrub(lines[2]))
    }

    @Test
    fun `a decoded path with spaces is taken out whole`() {
        // Opening ipfs://<cid>/alan%20r5%20secret%20diary.html: freedom-ipfs logs the decoded path (R5-F1).
        val cid = "QmT5NvUtoM5nWFfrQdVrFtvGfKFmG7AHE8P34isapyhCxX"
        val lines = listOf(
            """INFO gateway_request{request_id=4 path=/ipfs/$cid/alan r5 secret diary.html range=}: """ +
                """freedom_ipfs_gateway: phase="request_start" unixfs_path=/alan r5 secret diary.html method=GET""",
            """INFO gateway_request{path=/ipfs/$cid/alan r5 secret diary.html}: freedom_ipfs_gateway: served""",
            """INFO x: phase="resolve" resolved_target=/ipfs/$cid/alan r5 secret diary.html""",
            """INFO x: phase="resolve" resolved_target=/ipfs/$cid/alan r5 secret diary.html  status=200""",
        )
        for (l in lines) {
            val out = LogScrub.scrub(l)
            assertFalse(out, "diary" in out)
            assertFalse(out, "secret" in out)
        }
        assertEquals(
            """INFO gateway_request{request_id=4 path=<redacted> range=}: freedom_ipfs_gateway: """ +
                """phase="request_start" unixfs_path=<redacted> method=GET""",
            LogScrub.scrub(lines[0]),
        )
        assertEquals(
            """INFO gateway_request{path=<redacted>}: freedom_ipfs_gateway: served""",
            LogScrub.scrub(lines[1]),
        )
        assertTrue(LogScrub.scrub(lines[3]).endsWith("status=200"))
    }

    @Test
    fun `a field key after a digit is still taken out`() {
        assertEquals("v2path=<redacted> ok=1", LogScrub.scrub("v2path=/ipfs/secret ok=1"))
    }

    /**
     * The scrubber before R3-M1 made it cheaper, verbatim but for R4-F1's
     * bare field value (to the next field, not the first comma or space, R5-F1): the fast
     * one must take out everything it did (R3-M1 only skips a regex where
     * it can't match).
     */
    private object ReferenceScrub {
        const val R = "<redacted>"
        val FIELD = Regex(
            """\b([A-Za-z_]*(?:path|paths|cid|cids|name|names|target|targets|url|uri|href|referer|referrer|host|hostname|domain|dnslink|etag|reference))=("(?:[^"\\]|\\.)*"|\[[^\]]*]|.*?(?=\}+:|\}*$|\}*\s+[A-Za-z_][\w.]*=))""",
        )
        val NAME_ERROR = Regex(
            """(?i)(dnslink record not found for|invalid dnslink record:|invalid ipns name:|invalid ipns record:|http resolver:)\s*.*?(?=\s+[A-Za-z_]+=|"|$)""",
        )
        val DNSLINK_NAME = Regex("""(?i)\b_dnslink\.[A-Za-z0-9._-]+""")
        val URL = Regex("""\b[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]*""")
        val GATEWAY_PATH = Regex("""/(bzz|bytes|chunks|ipfs|ipns|feeds|soc)/[^\s"'<>]+""")
        val SWARM_REF = Regex("""(?<![0-9A-Fa-fXx])[0-9A-Fa-f]{64}(?:[0-9A-Fa-f]{64})?(?![0-9A-Fa-f])""")
        val CID = Regex("""\b(?:b[a-z2-7]{50,}|k[0-9a-z]{50,}|f01[0-9a-f]{60,})\b""")
        val CID_B58 = Regex("""(?<![1-9A-HJ-NP-Za-km-z])(?:Qm[1-9A-HJ-NP-Za-km-z]{44}|z(?!6M)[1-9A-HJ-NP-Za-km-z]{44,})(?![1-9A-HJ-NP-Za-km-z])""")
        val PEER_CONTEXT = Regex("""(?:peer\w*[=:]\s*"?|/p2p/)$""")
        val ONION = Regex("""\b[a-z2-7]{56}\.onion\b""")

        fun scrub(line: String): String {
            var s = line
            s = FIELD.replace(s) { "${it.groupValues[1]}=$R" }
            s = NAME_ERROR.replace(s) { "${it.groupValues[1]} $R" }
            s = DNSLINK_NAME.replace(s, "_dnslink.$R")
            s = URL.replace(s, "<url>")
            s = GATEWAY_PATH.replace(s) { "/${it.groupValues[1]}/$R" }
            s = SWARM_REF.replace(s, "<ref>")
            s = CID.replace(s, "<cid>")
            val b58 = s
            s = CID_B58.replace(b58) { m ->
                val peer = m.value.startsWith("Qm") &&
                    PEER_CONTEXT.containsMatchIn(b58.substring(maxOf(0, m.range.first - 24), m.range.first))
                if (peer) m.value else "<cid>"
            }
            s = ONION.replace(s, "<onion>")
            return s
        }
    }

    @Test
    fun `the fast scrubber takes out what the regex-only one did`() {
        val tokens = listOf(
            "path=", "top_level_path=", "unixfs_path=", "cid=", "cids=", "file_cid=", "name=", "resolved_target=",
            "etag=", "host=", "url=", "namespace=", "request_id=", "phase=", "error=", "peer_id=", "peer=",
            "\"", "\\\"", "[", "]", ",", " ", "  ", "}", "{", ":", "=", "/", ".", "-", "_", "1", "v2",
            "/ipfs/", "/ipns/", "/bzz/", "/p2p/", "https://", "ipfs://", "rad://", "docs.ipfs.tech", "_dnslink.",
            "dnslink record not found for ", "invalid dnslink record: ", "Invalid IPNS name: ", "http resolver: ",
            "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
            "k51qzi5uqu5dlvj2baxnqndepeb86cbk3ng7n3i46uzyxzyqj2xjonzllnv0v8",
            "f01701220c3c4733ec8affd06cf9e9ff50ffc6bcd2ec85a6170004bb709669c31de94391a",
            "QmT5NvUtoM5nWFfrQdVrFtvGfKFmG7AHE8P34isapyhCxX", "zdj7WWeQ43G6JJvLWQWZpyHuAMq6uYWRjkBXFad11vE2LHhQ7",
            "12D3KooWDpJ7As7BWAwRMfu1VU2WCqNjvq387JEYKDBj4kx6nXTN",
            "z6MksFqXN3Yhqk8pTJdUGLwATkRfQvwZXPqR2qMEhbS9wzpT",
            "d1a7ccbfb34e28a2e4c1d1f8ae1f2c1b0b1f4d6e7a8b9c0d1e2f3a4b5c6d7e8f", "0x",
            "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad", ".onion", "INFO ", "gateway_request",
        )
        val rnd = java.util.Random(276)
        repeat(30_000) {
            val line = buildString { repeat(1 + rnd.nextInt(14)) { append(tokens[rnd.nextInt(tokens.size)]) } }
            val want = ReferenceScrub.scrub(line)
            val got = LogScrub.scrub(line)
            // The fast one also takes out a key after a digit (`v2path=`),
            // which the reference let through: compare without those lines.
            if (!Regex("""[0-9][A-Za-z_]*(?:path|paths|cid|cids|name|names|target|targets|url|uri|href|referer|referrer|host|hostname|domain|dnslink|etag|reference)=""").containsMatchIn(line)) {
                assertEquals(line, want, got)
            }
        }
    }

    // ---- Clear cookies & site data forgets the kept lines ----

    @Test
    fun `a cleared ring is empty and takes lines again`() {
        val ring = LogRing(maxLines = 10, maxChars = 100, maxLineChars = 50)
        ring.add("one")
        ring.add("two")
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals(0, ring.totalChars)
        ring.add("three")
        assertEquals(listOf("three"), ring.snapshot())
    }

    /** Runs [body] with stand-in clocks: wall-clock and monotonic ms, both movable. */
    private class Clocks(var wall: Long, var elapsed: Long)

    private fun withClocks(wall: Long, elapsed: Long, body: (Clocks) -> Unit) {
        val c = Clocks(wall, elapsed)
        val (oldWall, oldElapsed) = NodeLogs.wallMs to NodeLogs.elapsedMs
        NodeLogs.wallMs = { c.wall }
        NodeLogs.elapsedMs = { c.elapsed }
        try {
            body(c)
        } finally {
            NodeLogs.clear()
            NodeLogs.wallMs = oldWall
            NodeLogs.elapsedMs = oldElapsed
        }
    }

    @Test
    fun `clear forgets every node's lines, and a line read before it isn't kept after`() = withClocks(10_000_000L, 1_000L) { c ->
        NodeLogs.clear()
        c.wall += NodeLogs.SETTLE_MS; c.elapsed += NodeLogs.SETTLE_MS // long settled
        val before = NodeLogs.generation()
        assertTrue(NodeLogs.keep(before, NodeLogSource.Ipfs, "ipfs line"))
        assertTrue(NodeLogs.keep(before, NodeLogSource.Tor, "tor line"))
        assertEquals("ipfs line", NodeLogs.text(NodeLogSource.Ipfs))
        NodeLogs.clear()
        for (s in NodeLogSource.entries) assertEquals("", NodeLogs.text(s))
        // The reader was mid-way through logcat's output when the clear came.
        assertFalse(NodeLogs.keep(before, NodeLogSource.Ipfs, "logged before the clear"))
        assertEquals("", NodeLogs.text(NodeLogSource.Ipfs))
        // Started over, it keeps what comes after the settle window.
        val after = NodeLogs.generation()
        c.wall += NodeLogs.SETTLE_MS; c.elapsed += NodeLogs.SETTLE_MS
        assertTrue(NodeLogs.keep(after, NodeLogSource.Ipfs, "after name=\"docs.ipfs.tech\"", atMs = c.wall))
        assertEquals("after name=<redacted>", NodeLogs.text(NodeLogSource.Ipfs))
    }

    @Test
    fun `lines logged just after a clear aren't kept, and the reader goes on`() = withClocks(5_000_000L, 70_000L) { c ->
        // A closed private tab's request still in flight logs for a few seconds after the clear (R1-M1).
        val at = c.wall
        NodeLogs.clear()
        val gen = NodeLogs.generation()
        // Dropped, but true: not a clear under the reader, so it doesn't start logcat over.
        c.wall += 2_000; c.elapsed += 2_000
        assertTrue(NodeLogs.keep(gen, NodeLogSource.Ipfs, "request_start", atMs = at + 2_000))
        c.wall = at + NodeLogs.SETTLE_MS; c.elapsed = 70_000L + NodeLogs.SETTLE_MS
        // Read once the window is over, but logged inside it.
        assertTrue(NodeLogs.keep(gen, NodeLogSource.Swarm, "fetch chunk", atMs = at + NodeLogs.SETTLE_MS - 1))
        for (s in NodeLogSource.entries) assertEquals("", NodeLogs.text(s))
        assertTrue(NodeLogs.keep(gen, NodeLogSource.Ipfs, "settled", atMs = at + NodeLogs.SETTLE_MS))
        assertEquals("settled", NodeLogs.text(NodeLogSource.Ipfs))
    }

    @Test
    fun `a clock set back after a clear doesn't stretch the settle window`() = withClocks(5_000_000L, 70_000L) { c ->
        // NTP (or the user) sets the clock back an hour right after the clear (R2-M1).
        val hour = 3_600_000L
        NodeLogs.clear()
        val gen = NodeLogs.generation()
        c.wall -= hour
        c.wall += 5_000; c.elapsed += 5_000
        assertTrue(NodeLogs.keep(gen, NodeLogSource.Ipfs, "in the window", atMs = c.wall))
        assertEquals("", NodeLogs.text(NodeLogSource.Ipfs))
        // SETTLE_MS on, by the monotonic clock: kept, though its stamp is an hour before the clear's.
        c.wall += NodeLogs.SETTLE_MS; c.elapsed += NodeLogs.SETTLE_MS
        assertTrue(NodeLogs.keep(gen, NodeLogSource.Ipfs, "settled", atMs = c.wall))
        assertEquals("settled", NodeLogs.text(NodeLogSource.Ipfs))
    }

    @Test
    fun `a clock set forward after a clear doesn't cut the settle window short`() = withClocks(5_000_000L, 70_000L) { c ->
        NodeLogs.clear()
        val gen = NodeLogs.generation()
        c.wall += 3_600_000L
        c.wall += 5_000; c.elapsed += 5_000
        assertTrue(NodeLogs.keep(gen, NodeLogSource.Ipfs, "in the window", atMs = c.wall))
        assertEquals("", NodeLogs.text(NodeLogSource.Ipfs))
    }

    @Test
    fun `a logcat entry carries when it was logged`() {
        assertEquals(1_790_752_391_733L, read(entry("x")).single().atMs)
    }

    // ---- Restarting logcat ----

    @Test
    fun `logcat never restarts from before the latest clear`() {
        // The last entry read was stamped 1000.250; the user cleared at 1003.007 while the reader slept (R5-M1).
        assertEquals(1_003_007L, NodeLogs.restartFrom(900_000L, 1_000_250L, 1_003_007L))
        // A clear before the last entry read leaves it be: just after that entry, not again.
        assertEquals(1_000_251L, NodeLogs.restartFrom(900_000L, 1_000_250L, 999_000L))
        // Nothing read and no clear yet (0): from the process's start.
        assertEquals(900_000L, NodeLogs.restartFrom(900_000L, 0L, 0L))
        assertEquals("1000.050", NodeLogs.formatSince(1_000_050L))
    }

    /**
     * What logd sends for `logcat -T [startMs]` (LogReader.cpp,
     * LogReaderThread.cpp). Of the entries already in the buffer when the
     * reader attaches ([buffered], in the order they were logged): from the
     * first one stamped after [startMs], every later one but those stamped at
     * or before it. Then every entry logged once the reader is attached
     * ([live]), whatever its stamp: the start time only picks where the
     * buffer is read from (checked on emulator-5556, API 36: `logcat -T`
     * an hour ahead still got live writes).
     */
    private fun logdReplay(buffered: List<Long>, startMs: Long, live: List<Long> = emptyList()): List<Long> {
        val first = buffered.indexOfFirst { it > startMs }
        val replay = if (first < 0) emptyList() else buffered.drop(first).filter { it > startMs }
        return replay + live
    }

    @Test
    fun `a clock set back after a clear doesn't bring the cleared lines back on a restart`() = withClocks(1_790_757_602_000L, 70_000L) { c ->
        // Swarm lines logged 10:38:54-10:39:25, read live; the user clears at 10:40:02 (R3-M1).
        val hour = 3_600_000L
        val clearAt = c.wall
        val before = (0 until 32).map { clearAt - 68_000L + it * 1_000L }
        val seen = before.last()
        NodeLogs.clear()
        // 30 s on, the clock is set back an hour, then logcat goes away and restarts.
        c.wall += 30_000; c.elapsed += 30_000
        c.wall -= hour
        // Logged during the restart delay, so already in the buffer when logcat is back.
        val gap = listOf(c.wall - 2_000, c.wall - 1_000, c.wall + 1_000)
        val from = NodeLogs.restartFrom(clearAt - 200_000L, seen, NodeLogs.clearedAtWallMs())
        // Logged once logcat is back, still stamped an hour below the restart point.
        val live = listOf(c.wall + NodeLogs.SETTLE_MS)
        val replayed = logdReplay(before + gap, from, live)
        // Not one line from before the clear comes back.
        assertTrue(replayed.none { it in before })
        // What a clock set back costs: the lines logged during the restart delay, stamped below the floor.
        assertTrue(replayed.none { it in gap })
        // A line logged once logcat is back comes live despite its stamp.
        assertEquals(live, replayed)
        // Before this fix, "now" by today's clock: logd would send every one of them again.
        assertEquals(before, logdReplay(before + gap, c.wall).filter { it in before })
        // Past the settle window, that live line is kept.
        c.wall += NodeLogs.SETTLE_MS; c.elapsed += NodeLogs.SETTLE_MS
        assertTrue(NodeLogs.keep(NodeLogs.generation(), NodeLogSource.Swarm, "live", atMs = c.wall))
        assertEquals("live", NodeLogs.text(NodeLogSource.Swarm))
    }

    // ---- Binary logcat entries (R1-F1) ----

    private val esc = "\u001B"
    private val utc = java.time.ZoneOffset.UTC

    /** One `logger_entry` v4, as `logcat -B` writes it. */
    private fun entry(
        message: String,
        tag: String = "ant-ffi",
        priority: Int = 4,
        sec: Long = 1_790_752_391L, // 2026-09-30T07:13:11Z
        nsec: Int = 733_503_000,
        lid: Int = 0,
        hdrSize: Int = 28,
    ): ByteArray {
        val payload = byteArrayOf(priority.toByte()) + tag.toByteArray() + 0 + message.toByteArray() + 0
        val b = java.nio.ByteBuffer.allocate(hdrSize + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putShort(payload.size.toShort()).putShort(hdrSize.toShort())
        b.putInt(5104).putInt(5190).putInt(sec.toInt()).putInt(nsec)
        if (hdrSize >= 24) b.putInt(lid)
        while (b.position() < hdrSize) b.put(0)
        b.put(payload)
        return b.array()
    }

    private fun read(vararg entries: ByteArray): List<LogcatLine> {
        val r = LogcatEntries(java.io.ByteArrayInputStream(entries.reduce { a, b -> a + b }), utc)
        return generateSequence { r.next() }.toList()
    }

    /** Read, route and scrub [entries] the way NodeLogs.follow does. */
    private fun kept(vararg entries: ByteArray): List<Pair<NodeLogSource, String>> =
        read(*entries).map { nodeProcessSource(it.tag, it.message) to LogScrub.scrub(it.format()) }

    @Test
    fun `an entry reads, colours and tracing's timestamp dropped`() {
        val lines = read(
            entry(
                "$esc[2m2026-09-30T07:13:11.733503Z$esc[0m $esc[32m INFO$esc[0m $esc[2mant_gateway$esc[0m$esc[2m:$esc[0m " +
                    "HTTP API listening on 127.0.0.1:1633",
            ),
            entry("stamp call x failed: IOException: boom", tag = "NodeService", priority = 5, nsec = 5_000_000),
        )
        assertEquals(
            listOf(
                "07:13:11.733 I ant-ffi: INFO ant_gateway: HTTP API listening on 127.0.0.1:1633",
                "07:13:11.005 W NodeService: stamp call x failed: IOException: boom",
            ),
            lines.map { it.format() },
        )
        assertEquals("stamp call x failed: IOException: boom", lines[1].message)
    }

    @Test
    fun `a v3 header reads, and other buffers' entries are skipped`() {
        val lines = read(
            entry("from events", lid = 2),
            entry("v1", hdrSize = 24),
            entry("from security", lid = 6),
            entry("from system", tag = "t", lid = 3),
        )
        assertEquals(listOf("07:13:11.733 I ant-ffi: v1", "07:13:11.733 I t: from system"), lines.map { it.format() })
    }

    @Test
    fun `a stream out of step fails rather than guess`() {
        val bad = entry("x", hdrSize = 28).also { it[2] = 3 }
        assertTrue(runCatching { read(entry("ok"), bad) }.exceptionOrNull() is java.io.IOException)
        // A v1 entry: its __pad (where hdr_size would be) is 0.
        assertTrue(runCatching { read(entry("ok"), entry("v1", hdrSize = 20).also { it[2] = 0 }) }.exceptionOrNull() is java.io.IOException)
        // Cut off mid-entry.
        assertTrue(runCatching { read(entry("ok").copyOf(30)) }.exceptionOrNull() is java.io.EOFException)
    }

    @Test
    fun `tracing's timestamp only goes from the start, whitespace after it too`() {
        val lines = read(
            entry("2026-09-30T05:13:07Z   after"),
            entry("2026-09-30T05:13:07Z"),
            entry("2026-09-30X05:13:07Z m"),
            entry("$esc[2m2026-09-30T05:13:07.1Z$esc[0m\tm"),
            entry("$esc[1mbold$esc[ not an escape $esc[12;3mok"),
        )
        assertEquals(
            listOf("after", "2026-09-30T05:13:07Z", "2026-09-30X05:13:07Z m", "m", "bold$esc[ not an escape ok"),
            lines.map { it.message },
        )
    }

    @Test
    fun `a line or paragraph separator inside a bare value is taken out with it`() {
        for (sep in listOf("\u2028", "\u2029", "\u0085", "\u2028\u2029")) {
            val out = LogScrub.scrub(
                "gateway_request{path=/ipfs/x/r2probe${sep}zzsecret diary.html range=}: phase=\"a\" path=/ipfs/x/a${sep}secret b",
            )
            assertFalse(out, "secret" in out)
            assertFalse(out, "diary" in out)
            assertTrue(out, "range=" in out && "phase=" in out)
        }
        // Also as the whole message, run through the reader: no failure, no line lost.
        val lines = read(
            request("r2probe\u2028zzsecret diary.html", "start"),
            entry("after"),
        )
        assertEquals(2, lines.size)
        assertFalse(lines[0].message, "secret" in LogScrub.scrub(lines[0].message))
    }

    private val cid = "k2jmtxt6f4hf4f8e0j7xq5d7sxkqk1n2ajr8lkq0f2pcw6qg0tmckx0ae"

    /** What freedom-ipfs writes for a visit to `ipfs://…/<name>`, [name] percent-decoded. */
    private fun request(name: String, phase: String) = entry(
        "$esc[2m2026-09-30T07:20:11.402113Z$esc[0m $esc[32m INFO$esc[0m gateway_request{request_id=11 " +
            "path=/ipfs/$cid/$name range=}: freedom_ipfs_gateway: phase=\"$phase\" request_id=11 path=/ipfs/$cid/$name\n",
    )

    @Test
    fun `a path with a decoded newline stays one line, scrubbed whole`() {
        val lines = kept(request("r6probe\nnlsecretdiary.html", "request_start"), request("r6probe\nnlsecretdiary.html", "gateway_limiter"))
        assertEquals(2, lines.size)
        for ((source, line) in lines) {
            assertEquals(line, NodeLogSource.Ipfs, source)
            assertFalse(line, "nlsecretdiary" in line)
            assertFalse(line, "r6probe" in line)
        }
        assertTrue(lines[0].second, "phase=\"request_start\"" in lines[0].second)
        assertTrue(lines[1].second, "phase=\"gateway_limiter\"" in lines[1].second)
    }

    @Test
    fun `a forged tracing timestamp or logcat header after a break starts no line`() {
        // ipfs://…/r8probe%0A2026-01-01T00:00:00.0Z%20zzr8leak.html (R1-F1)
        val forgedTracing = "r8probe\n2026-01-01T00:00:00.0Z zzr8leak.html"
        // …%0D09-30%2009:20:11.402%20%205104%20%205190%20I%20ant-ffi%20:%20zzr9leak.html
        val forgedHeader = "r9probe\r09-30 09:20:11.402  5104  5190 I ant-ffi : zzr9leak.html"
        val crlf = "r10probe\r\n$esc[2m2026-01-01T00:00:00.0Z$esc[0m zzr10leak.html"
        val lines = kept(
            request(forgedTracing, "request_start"),
            request(forgedHeader, "request_start"),
            request(crlf, "request_start"),
        )
        assertEquals(3, lines.size)
        for ((source, line) in lines) {
            assertEquals(line, NodeLogSource.Ipfs, source)
            assertFalse(line, "leak" in line)
            assertFalse(line, "probe" in line)
        }
    }

    @Test
    fun `line breaks inside a write are joined, trailing ones dropped`() {
        val lines = read(entry("one\ntwo\r\nthree\rfour\n\r\n"), entry("five"))
        assertEquals(listOf("one ⏎ two ⏎ three ⏎ four", "five"), lines.map { it.message })
    }

    @Test
    fun `logcat's priority letters`() {
        assertEquals("VDIWEFS", (2..8).map { LogcatLine.levelOf(it) }.joinToString(""))
        assertEquals('?', LogcatLine.levelOf(0))
        assertEquals('?', LogcatLine.levelOf(42))
    }

    // ---- Which :node node a line is ----

    @Test
    fun `node process lines go to their node`() {
        assertEquals(NodeLogSource.Ipfs, nodeProcessSource("IpfsNode", "freedom-ipfs 0.4.4 gateway at …"))
        assertEquals(NodeLogSource.Ipfs, nodeProcessSource("NodeService", "ipfs → Running peers=6"))
        assertEquals(NodeLogSource.Radicle, nodeProcessSource("NodeService", "radicle → Stopped  peers=0"))
        assertEquals(NodeLogSource.Swarm, nodeProcessSource("NodeService", "swarm → Running  peers=113"))
        assertEquals(NodeLogSource.Radicle, nodeProcessSource("RadicleNode", "radicle running as did:key:z6Mk"))
        assertEquals(
            NodeLogSource.Ipfs,
            nodeProcessSource("ant-ffi", """INFO gateway_request{namespace="ipfs"}: freedom_ipfs_gateway: phase="request_done""""),
        )
        assertEquals(NodeLogSource.Ipfs, nodeProcessSource("ant-ffi", "INFO freedom_ipfs_routing: phase=\"x\""))
        assertEquals(NodeLogSource.Radicle, nodeProcessSource("ant-ffi", "INFO radicle_node::service: connected"))
        assertEquals(NodeLogSource.Swarm, nodeProcessSource("ant-ffi", "INFO ant_p2p: peer set below floor"))
        assertEquals(NodeLogSource.Swarm, nodeProcessSource("BootnodeSeeder", "seeded peers.json with 8 bootnodes"))
    }

    @Test
    fun `the source ordinal round-trips the binder`() {
        for (s in NodeLogSource.entries) assertEquals(s, NodeLogSource.of(s.ordinal))
        assertNull(NodeLogSource.of(-1))
        assertNull(NodeLogSource.of(NodeLogSource.entries.size))
    }
}
