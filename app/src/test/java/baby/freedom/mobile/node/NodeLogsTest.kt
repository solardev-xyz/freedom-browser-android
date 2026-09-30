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
