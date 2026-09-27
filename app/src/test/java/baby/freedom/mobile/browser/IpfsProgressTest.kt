package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IpfsProgress.message] is a port of the desktop's
 * `deriveIpfsProgressMessage`; these cases follow its own test file
 * (freedom-browser `src/renderer/lib/ipfs-progress-status.test.js`)
 * plus the snapshot shape freedom-ipfs actually emits.
 */
class IpfsProgressTest {

    @After
    fun tearDown() {
        Gateways.setIpfsBase("")
        KnownEnsNames.clear()
    }

    private val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"

    @Test
    fun `empty, blank and unparseable snapshots show nothing`() {
        assertNull(IpfsProgress.message(null))
        assertNull(IpfsProgress.message(""))
        assertNull(IpfsProgress.message("   "))
        assertNull(IpfsProgress.message("{not json"))
        assertNull(IpfsProgress.message("""{"active":[],"events":[]}"""))
    }

    @Test
    fun `a single active target shows its phase`() {
        assertEquals(
            "IPFS: Finding providers…",
            IpfsProgress.message(
                """{"active":[{"kind":"gateway_request","phase":"provider_lookup","status":"active"}]}""",
            ),
        )
    }

    @Test
    fun `the most telling active phase wins`() {
        // Fetching outranks a provider lookup running alongside it.
        val json = """{"active":[
            {"kind":"provider_lookup","phase":"provider_lookup","status":"active"},
            {"kind":"gateway_request","phase":"fetching_bitswap","status":"active"},
            {"kind":"gateway_request","phase":"started","status":"active"}
        ]}"""
        assertEquals("IPFS: Fetching from peers…", IpfsProgress.message(json))
    }

    @Test
    fun `kind and elapsed time break ties between equal phases`() {
        val json = """{"active":[
            {"kind":"block_fetch","phase":"streaming","status":"active","elapsed_ms":100},
            {"kind":"gateway_request","phase":"resolving_name","status":"active","elapsed_ms":9000}
        ]}"""
        // streaming 80 + block 6 + 0.1  vs  resolving 60 + gateway 20 + 9
        assertEquals("IPFS: Resolving IPNS name…", IpfsProgress.message(json))
    }

    @Test
    fun `terminal targets are ignored`() {
        val json = """{"active":[
            {"kind":"gateway_request","phase":"completed","status":"completed"},
            {"kind":"gateway_request","phase":"provider_lookup","status":"failed"}
        ]}"""
        assertNull(IpfsProgress.message(json))
    }

    @Test
    fun `falls back to recent events when nothing is active`() {
        val json = """{"active":[],"events":[
            {"kind":"gateway_request","phase":"started","status":"active"},
            {"kind":"gateway_request","phase":"dht_fallback_started","status":"active"},
            {"kind":"gateway_request","phase":"completed","status":"completed"}
        ]}"""
        assertEquals("IPFS: Searching the DHT…", IpfsProgress.message(json))
    }

    @Test
    fun `only the last twelve events are considered`() {
        val old = """{"kind":"gateway_request","phase":"retrying","status":"active"}"""
        val recent = """{"kind":"gateway_request","phase":"started","status":"active"}"""
        val events = listOf(old) + List(12) { recent }
        val json = """{"active":[],"events":[${events.joinToString(",")}]}"""
        assertEquals("IPFS: Starting request…", IpfsProgress.message(json))
    }

    @Test
    fun `phase and status tokens are normalized`() {
        assertEquals(
            "IPFS: Fetching from verified provider…",
            IpfsProgress.message(
                """{"active":[{"phase":" Fetching-HTTP provider ","status":"Running"}]}""",
            ),
        )
    }

    @Test
    fun `unknown phases fall back to the message, then to a generic line`() {
        assertEquals(
            "IPFS: warming caches",
            IpfsProgress.message("""{"active":[{"phase":"mystery","message":" warming caches "}]}"""),
        )
        assertEquals(
            "IPFS: Loading content…",
            IpfsProgress.message("""{"active":[{"phase":"mystery"}]}"""),
        )
    }

    @Test
    fun `a real freedom-ipfs snapshot`() {
        // Shape of `progress_snapshot_json` (ProgressSnapshot in
        // freedom-ipfs-mobile): a gateway request waiting on providers.
        val json = """{"generated_at_unix_ms":1790000000000,"active_count":1,"event_count":2,
            "active":[{"id":7,"request_id":7,"parent_id":null,"kind":"gateway_request",
              "path":"/ipfs/$cid/","top_level_path":"/ipfs/$cid/","namespace":"ipfs",
              "phase":"provider_lookup","status":"active","source":"delegated_routing",
              "transport":null,"delivery":null,"bytes_loaded":null,"bytes_total":null,
              "active_subrequests":0,"elapsed_ms":2400,"blocks_loaded":0,"retry_count":0,
              "last_error_code":null,"last_error_message":null,"last_event_id":2,
              "updated_ms":1790000000000}],
            "events":[{"event_id":1,"target_id":7,"kind":"gateway_request","phase":"started",
              "raw_phase":"request_start","status":"active","blocks_loaded":0,"retry_count":0,
              "timestamp_ms":1790000000000}]}"""
        assertEquals("IPFS: Finding providers…", IpfsProgress.message(json))
    }

    @Test
    fun `ipfs and ipns destinations are IPFS loads`() {
        assertTrue(ipfsLoadFor("ipfs://$cid/", current = false))
        assertTrue(ipfsLoadFor("ipns://docs.ipfs.tech/", current = false))
        assertTrue(ipfsLoadFor("https://$cid.ipfs.freedom.baby/index.html", current = false))
    }

    @Test
    fun `the raw loopback gateway is an IPFS load once the node has a base`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertTrue(ipfsLoadFor("http://127.0.0.1:58312/ipfs/$cid/", current = false))
    }

    @Test
    fun `swarm, the web, the error page and home are not`() {
        val ref64 = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
        assertFalse(ipfsLoadFor("bzz://$ref64/", current = true))
        assertFalse(ipfsLoadFor("https://example.com/", current = true))
        assertFalse(ipfsLoadFor(ErrorPage.url(errorCode = "x", displayUrl = "ipfs://$cid"), current = true))
        assertFalse(ipfsLoadFor(HOME_URL, current = true))
    }

    @Test
    fun `javascript URLs leave the flag alone`() {
        assertTrue(ipfsLoadFor("javascript:history.back();void(0);", current = true))
        assertFalse(ipfsLoadFor("javascript:history.back();void(0);", current = false))
    }

    @Test
    fun `ENS names follow the session's resolution, else are not IPFS yet`() {
        // Not resolved yet (restored tab, link to another name): never
        // inherits a previous IPFS page's flag — the interceptor
        // re-derives it once the name resolves.
        assertFalse(ipfsLoadFor("ens://vitalik.eth/", current = true))
        assertFalse(ipfsLoadFor("https://vitalik-eth.ens.freedom.baby/", current = true))
        assertFalse(ipfsLoadFor("ens://vitalik.eth/", current = false))

        KnownEnsNames.record("ipfs://$cid", "vitalik.eth")
        assertTrue(ipfsLoadFor("ens://vitalik.eth/", current = false))
        assertTrue(ipfsLoadFor("https://vitalik-eth.ens.freedom.baby/", current = false))

        KnownEnsNames.record("bzz://8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd", "swarm.eth")
        assertFalse(ipfsLoadFor("ens://swarm.eth/", current = true))
    }

    private fun counters(vararg v: Long) = IpfsProgress.Counters.of(v)!!

    // long[11]: blockCount, totalBytes, cacheHits, httpProviderBlocks,
    // bitswapBlocks, delegated lookups/results/errors, dht lookups/results/errors.
    private val zero = counters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    @Test
    fun `counters parse the long-11 layout and reject short arrays`() {
        assertEquals(
            IpfsProgress.Counters(
                cacheHits = 3, httpProviderBlocks = 4, bitswapBlocks = 5,
                delegatedLookups = 6, dhtLookups = 9, providerResults = 7 + 10,
            ),
            counters(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        )
        assertNull(IpfsProgress.Counters.of(LongArray(5)))
        assertNull(IpfsProgress.Counters.of(null))
    }

    @Test
    fun `counter phases follow a cold fetch from lookup to blocks`() {
        assertEquals("IPFS: Looking up content…", IpfsProgress.fromCounters(zero, zero))
        assertEquals(
            "IPFS: Finding providers…",
            IpfsProgress.fromCounters(zero, counters(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)),
        )
        assertEquals(
            "IPFS: Searching the DHT…",
            IpfsProgress.fromCounters(zero, counters(0, 0, 0, 0, 0, 2, 0, 0, 1, 0, 0)),
        )
        assertEquals(
            "IPFS: Connecting to providers…",
            IpfsProgress.fromCounters(zero, counters(0, 0, 0, 0, 0, 2, 3, 0, 1, 0, 0)),
        )
        assertEquals(
            "IPFS: Fetching from peers…",
            IpfsProgress.fromCounters(zero, counters(0, 0, 0, 1, 4, 2, 3, 0, 1, 0, 0)),
        )
        assertEquals(
            "IPFS: Fetching from verified provider…",
            IpfsProgress.fromCounters(zero, counters(0, 0, 0, 5, 1, 2, 3, 0, 1, 0, 0)),
        )
        assertEquals(
            "IPFS: Loading from local cache…",
            IpfsProgress.fromCounters(zero, counters(0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0)),
        )
    }

    @Test
    fun `counter phases read growth since the load's baseline, not totals`() {
        // A node that already fetched plenty for an earlier page.
        val before = counters(0, 0, 50, 20, 30, 10, 12, 0, 4, 2, 0)
        assertEquals("IPFS: Looking up content…", IpfsProgress.fromCounters(before, before))
        val lookingUp = counters(0, 0, 50, 20, 30, 11, 12, 0, 4, 2, 0)
        assertEquals("IPFS: Finding providers…", IpfsProgress.fromCounters(before, lookingUp))
    }

    @Test
    fun `a superseded load still running in the node doesn't lend the new load its phase`() {
        // The old page (baseline `earlier`) had pulled blocks off an
        // HTTP provider (one delegated lookup + providers per block)
        // and a burst of cache hits by the time the new load started…
        val earlier = counters(0, 0, 0, 10, 0, 10, 20, 0, 0, 0, 0)
        val atStart = counters(0, 0, 100, 12, 0, 12, 26, 0, 0, 0, 0)
        val carried = IpfsProgress.carriedOver(earlier, atStart)
        // …and keeps doing so, while the new, unprovided CID falls back
        // to the DHT.
        val later = counters(0, 0, 700, 20, 0, 20, 50, 0, 2, 0, 0)
        assertEquals(
            "IPFS: Fetching from verified provider…",
            IpfsProgress.fromCounters(atStart, later),
        )
        assertEquals(
            "IPFS: Searching the DHT…",
            IpfsProgress.fromCounters(atStart, later, carried),
        )
        assertEquals(
            "IPFS: Searching the DHT…",
            IpfsProgress.line(null, atStart, later, carried),
        )
        // Counters the old load wasn't moving still count: blocks from
        // Bitswap for the new load show.
        val fetching = counters(0, 0, 700, 20, 3, 20, 50, 0, 2, 0, 0)
        assertEquals(
            "IPFS: Fetching from peers…",
            IpfsProgress.fromCounters(atStart, fetching, carried),
        )
        // Nothing in flight before: nothing carried.
        assertNull(IpfsProgress.carriedOver(null, atStart))
        assertEquals(later - atStart, (later - atStart).without(null))
    }

    @Test
    fun `the snapshot wins over the counters when it has a phase`() {
        val snapshot = """{"active":[{"kind":"gateway_request","phase":"retrying","status":"active"}]}"""
        val now = counters(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
        assertEquals("IPFS: Retrying slow provider…", IpfsProgress.line(snapshot, zero, now))
        assertEquals("IPFS: Finding providers…", IpfsProgress.line("""{"active":[],"events":[]}""", zero, now))
        assertNull(IpfsProgress.line(null, null, null))
    }
}
