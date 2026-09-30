package baby.freedom.mobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun `clear forgets every node's lines, and a line read before it isn't kept after`() {
        val before = NodeLogs.generation()
        assertTrue(NodeLogs.keep(before, NodeLogSource.Ipfs, "ipfs line"))
        assertTrue(NodeLogs.keep(before, NodeLogSource.Tor, "tor line"))
        NodeLogs.clear()
        for (s in NodeLogSource.entries) assertEquals("", NodeLogs.text(s))
        // The reader was mid-way through logcat's output when the clear came.
        assertFalse(NodeLogs.keep(before, NodeLogSource.Ipfs, "logged before the clear"))
        assertEquals("", NodeLogs.text(NodeLogSource.Ipfs))
        // Started over, it keeps what comes after.
        val after = NodeLogs.generation()
        assertTrue(NodeLogs.keep(after, NodeLogSource.Ipfs, "after name=\"docs.ipfs.tech\""))
        assertEquals("after name=<redacted>", NodeLogs.text(NodeLogSource.Ipfs))
        NodeLogs.clear()
    }

    // ---- LogcatLine ----

    @Test
    fun `logcat never restarts from before the latest clear`() {
        // logcat went away at 1000.250; the user cleared at 1003.007 while the reader slept (R5-M1).
        assertEquals("1003.007", NodeLogs.startFrom("1000.250", 1_003_007L))
        // A clear before the restart point leaves it be; no clear yet (0) too.
        assertEquals("1000.250", NodeLogs.startFrom("1000.250", 999_000L))
        assertEquals("1000.250", NodeLogs.startFrom("1000.250", 0L))
        assertEquals(1_000_050L, NodeLogs.parseSince(NodeLogs.formatSince(1_000_050L)))
        assertEquals("1000.050", NodeLogs.formatSince(1_000_050L))
    }

    @Test
    fun `a threadtime line parses, colours and tracing's timestamp dropped`() {
        val raw = "09-30 07:13:07.733  3826  3889 I ant-ffi : \u001B[2m2026-09-30T05:13:07.733503Z\u001B[0m " +
            "\u001B[32m INFO\u001B[0m \u001B[2mant_gateway\u001B[0m\u001B[2m:\u001B[0m HTTP API listening on 127.0.0.1:1633"
        val line = LogcatLine.parse(raw)
        assertNotNull(line)
        line!!
        assertEquals("ant-ffi", line.tag)
        assertEquals('I', line.level)
        assertEquals("07:13:07.733 I ant-ffi: INFO ant_gateway: HTTP API listening on 127.0.0.1:1633", line.format())
    }

    @Test
    fun `a kotlin line parses, and a message may hold colons`() {
        val line = LogcatLine.parse("09-30 07:13:06.693  3826  3826 W NodeService: stamp call x failed: IOException: boom")
        assertEquals("NodeService", line?.tag)
        assertEquals("stamp call x failed: IOException: boom", line?.message)
    }

    @Test
    fun `the hand parser reads lines as the threadtime regex did`() {
        // The regex LogcatLine.parse replaced (R3-M1), verbatim.
        val threadtime = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""")
        val ansi = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
        val tracingTime = Regex("""^\d{4}-\d\d-\d\dT[0-9:.]+Z\s+""")
        fun reference(raw: String): LogcatLine? {
            val m = threadtime.find(raw) ?: return null
            val (time, level, tag, message) = m.destructured
            return LogcatLine(time, level[0], tag.trim(), tracingTime.replace(ansi.replace(message, ""), ""))
        }
        val esc = "\u001B"
        val lines = listOf(
            "09-30 07:13:07.733  3826  3889 I ant-ffi : ${esc}[2m2026-09-30T05:13:07.733503Z${esc}[0m ${esc}[32m INFO${esc}[0m x",
            "09-30 07:13:06.693  3826  3826 W NodeService: stamp call x failed: IOException: boom",
            "09-30 07:13:06.693 13826 13826 E Tag with spaces : m",
            "09-30 07:13:06.693  1  2 D :empty tag",
            "09-30 07:13:06.693  1  2 D t:",
            "09-30 07:13:06.693  1  2 D t:  two spaces",
            "09-30 07:13:06.693  1  2 X t: bad level",
            "09-30 07:13:06.693  1  2 II t: level run",
            "09-30 07:13:06.693  x  2 I t: bad pid",
            "09-30 07:13:06.693  1  2 I no colon",
            "09-30 07:13:06.69  1  2 I t: short ms",
            "--------- beginning of main",
            "",
            "09-30 07:13:06.693  1  2 I t: ${esc}[1mbold${esc}[ not an escape ${esc}[12;3mok",
            "09-30 07:13:06.693  1  2 I t: 2026-09-30T05:13:07Z   after",
            "09-30 07:13:06.693  1  2 I t: 2026-09-30T05:13:07Z",
            "09-30 07:13:06.693  1  2 I t: 2026-09-30X05:13:07Z m",
            "09-30 07:13:06.693  1  2 I t: ${esc}[2m2026-09-30T05:13:07.1Z${esc}[0m\tm",
        )
        for (l in lines) assertEquals(l, reference(l), LogcatLine.parse(l))
    }

    // ---- A write logcat split into lines (R6-F1) ----

    /** Parse [raw], join, route and scrub it the way NodeLogs.follow does. */
    private fun joined(raw: List<String>, flushEvery: Boolean = false): List<Pair<NodeLogSource, String>> {
        val out = mutableListOf<Pair<NodeLogSource, String>>()
        val joiner = LogcatJoiner { l -> out += nodeProcessSource(l.tag, l.message) to LogScrub.scrub(l.format()) }
        for (r in raw) {
            LogcatLine.parse(r)?.let(joiner::add)
            if (flushEvery) joiner.flush()
        }
        joiner.flush()
        return out
    }

    private val esc = "\u001B"
    private val cid = "k2jmtxt6f4hf4f8e0j7xq5d7sxkqk1n2ajr8lkq0f2pcw6qg0tmckx0ae"

    /** The lines logcat printed for a visit to `ipfs://…/r6probe%0Anlsecretdiary.html` (R6-F1). */
    private val splitRequest = listOf(
        "09-30 09:20:11.402  5104  5190 I ant-ffi : ${esc}[2m2026-09-30T07:20:11.402113Z${esc}[0m ${esc}[32m INFO${esc}[0m " +
            "gateway_request{request_id=11 path=/ipfs/$cid/r6probe",
        "09-30 09:20:11.402  5104  5190 I ant-ffi : nlsecretdiary.html range=}: freedom_ipfs_gateway: " +
            "phase=\"request_start\" request_id=11 path=/ipfs/$cid/r6probe",
        "09-30 09:20:11.402  5104  5190 I ant-ffi : nlsecretdiary.html",
        "09-30 09:20:11.403  5104  5190 I ant-ffi : ${esc}[2m2026-09-30T07:20:11.403001Z${esc}[0m ${esc}[32m INFO${esc}[0m " +
            "gateway_request{request_id=11 path=/ipfs/$cid/r6probe",
        "09-30 09:20:11.403  5104  5190 I ant-ffi : nlsecretdiary.html range=}: freedom_ipfs_gateway: " +
            "phase=\"gateway_limiter\" request_id=11",
    )

    @Test
    fun `a path logcat split at a decoded newline is joined and scrubbed whole`() {
        val lines = joined(splitRequest)
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
    fun `the rest of a split line whose start already went out isn't kept`() {
        // The reader handed the first part over before the rest came in.
        val lines = joined(splitRequest, flushEvery = true)
        assertTrue(lines.none { "nlsecretdiary" in it.second })
        assertEquals(5, lines.size)
        assertTrue(lines[1].second, lines[1].second.endsWith(LogcatJoiner.CUT))
        // A continuation with no event before it at all.
        val orphan = joined(listOf("09-30 09:20:11.402  5104  5190 I ant-ffi : secret.html range=}: x"))
        assertEquals(listOf(NodeLogSource.Swarm to "09:20:11.402 I ant-ffi: ${LogcatJoiner.CUT}"), orphan)
    }

    @Test
    fun `separate events, and other tags' lines, aren't joined`() {
        val event = "09-30 09:20:11.402  5104  5190 I ant-ffi : ${esc}[2m2026-09-30T07:20:11.402113Z${esc}[0m  INFO ant_p2p: one"
        val lines = joined(
            listOf(
                event,
                event.replace("one", "two"),
                "09-30 09:20:11.402  5104  5190 W NodeService: first",
                "09-30 09:20:11.402  5104  5190 W NodeService: second",
            ),
        )
        assertEquals(
            listOf(
                "09:20:11.402 I ant-ffi: INFO ant_p2p: one",
                "09:20:11.402 I ant-ffi: INFO ant_p2p: two",
                "09:20:11.402 W NodeService: first",
                "09:20:11.402 W NodeService: second",
            ),
            lines.map { it.second },
        )
    }

    @Test
    fun `logcat's own banners aren't lines`() {
        assertNull(LogcatLine.parse("--------- beginning of main"))
        assertNull(LogcatLine.parse(""))
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
