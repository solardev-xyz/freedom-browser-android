package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsTrust
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

        KnownEnsNames.record("ipfs://$cid", "vitalik.eth", EnsTrust.ASSUMED)
        assertTrue(ipfsLoadFor("ens://vitalik.eth/", current = false))
        assertTrue(ipfsLoadFor("https://vitalik-eth.ens.freedom.baby/", current = false))

        KnownEnsNames.record("bzz://8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd", "swarm.eth", EnsTrust.ASSUMED)
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
    fun `a superseded load that starts moving new counters late doesn't lend them (R2-F1)`() {
        // The old ipns site was still in its delegated lookup when the
        // new, unprovided CID was submitted: at the new load's first
        // poll only lookups had moved…
        val meter = IpfsProgress.LoadMeter()
        val atStart = counters(0, 0, 0, 0, 0, 3, 2, 0, 0, 0, 0)
        assertEquals("IPFS: Looking up content…", meter.poll(null, atStart, supersededActive = true))
        // …then the old site's HTTP-provider and Bitswap blocks land,
        // while its request is still open.
        val blocks = counters(0, 0, 40, 30, 12, 4, 6, 0, 1, 0, 0)
        assertEquals("IPFS: Looking up content…", meter.poll(null, blocks, supersededActive = true))
        // The old request closes; the tail of its interval is still set
        // aside, the new load's DHT fallback afterwards is its own.
        val tail = counters(0, 0, 45, 31, 12, 4, 6, 0, 1, 0, 0)
        assertEquals("IPFS: Looking up content…", meter.poll(null, tail, supersededActive = false))
        val dht = counters(0, 0, 45, 31, 12, 4, 6, 0, 3, 0, 0)
        assertEquals("IPFS: Searching the DHT…", meter.poll(null, dht, supersededActive = false))
    }

    @Test
    fun `a superseded load masks nothing it could not have moved, and only while open (R2-F3)`() {
        // A same-site link tapped while the page is still loading: both
        // move HTTP-provider blocks and cache hits.
        val meter = IpfsProgress.LoadMeter()
        meter.poll(null, zero, supersededActive = true)
        val overlap = counters(0, 0, 10, 5, 0, 0, 0, 0, 0, 0, 0)
        assertEquals("IPFS: Looking up content…", meter.poll(null, overlap, supersededActive = true))
        // Once the old page's requests are closed (and one interval has
        // passed), the new page's blocks read as its own.
        meter.poll(null, overlap, supersededActive = false)
        val own = counters(0, 0, 10, 8, 0, 0, 0, 0, 0, 0, 0)
        assertEquals("IPFS: Fetching from verified provider…", meter.poll(null, own, supersededActive = false))
    }

    @Test
    fun `a load that superseded nothing reads its growth straight away`() {
        val meter = IpfsProgress.LoadMeter()
        val before = counters(0, 0, 50, 20, 30, 10, 12, 0, 4, 2, 0)
        assertEquals("IPFS: Looking up content…", meter.poll(null, before, supersededActive = false))
        assertEquals(
            "IPFS: Finding providers…",
            meter.poll(null, counters(0, 0, 50, 20, 30, 11, 12, 0, 4, 2, 0), supersededActive = false),
        )
        // A missed poll keeps the interval open: its overlap still counts.
        assertNull(meter.poll(null, null, supersededActive = true))
        assertEquals(
            "IPFS: Finding providers…",
            meter.poll(null, counters(0, 0, 90, 20, 30, 11, 12, 0, 4, 2, 0), supersededActive = false),
        )
        // The snapshot still wins when it has a phase.
        val snapshot = """{"active":[{"kind":"gateway_request","phase":"retrying","status":"active"}]}"""
        assertEquals("IPFS: Retrying slow provider…", meter.poll(snapshot, before, supersededActive = false))
    }

    @Test
    fun `gateway work reports only older loads' open requests`() {
        var clock = 0L
        val work = GatewayWork { clock }
        assertFalse(work.activeBefore(1))
        val old = work.start(1)
        val current = work.start(2)
        assertTrue(work.activeBefore(2))
        assertFalse(work.activeBefore(1))
        work.finish(old)
        work.finish(old)
        assertFalse(work.activeBefore(2))
        work.finish(current)
        // A body nobody closed stops counting after the stale limit.
        work.start(1)
        assertTrue(work.activeBefore(2))
        clock += GatewayWork.STALE_MS
        assertFalse(work.activeBefore(2))
    }

    @Test
    fun `an answered body is busy only while it is read (R4-F2)`() {
        var clock = 0L
        val work = GatewayWork { clock }
        val video = work.start(1)
        // Waiting for its answer: busy, however long that takes.
        clock += 30_000
        assertTrue(work.activeBefore(2))
        work.answered(video)
        assertTrue(work.activeBefore(2))
        // Chromium stops pulling the body: open, but idle.
        clock += GatewayWork.IDLE_MS
        assertFalse(work.activeBefore(2))
        assertTrue(work.openBefore(2))
        // A read blocked on the node is busy for as long as it blocks.
        work.reading(video, started = true)
        clock += 5_000
        assertTrue(work.activeBefore(2))
        work.reading(video, started = false)
        clock += GatewayWork.IDLE_MS - 1
        assertTrue(work.activeBefore(2))
        clock += 1
        assertFalse(work.activeBefore(2))
        work.finish(video)
        assertFalse(work.openBefore(2))
    }

    @Test
    fun `a tracked body reports each read around it`() {
        val events = mutableListOf<Boolean>()
        val stream = CloseNotifyingInputStream("abcdef".byteInputStream(), { events += it }) {}
        stream.read()
        stream.read(ByteArray(2))
        stream.skip(1)
        assertEquals(listOf(true, false, true, false, true, false), events)
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("reset")
        }
        events.clear()
        val broken = CloseNotifyingInputStream(failing, { events += it }) {}
        runCatching { broken.read() }
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `an idle superseded page masks neither the counters nor its absence (R4-F2)`() {
        // An IPFS page's paused <video> keeps its range body open while
        // an unprovided CID is submitted: its DHT search reads as such.
        val meter = IpfsProgress.LoadMeter()
        val start = counters(0, 0, 10, 0, 5, 3, 1, 0, 0, 0, 0)
        meter.poll(null, start, supersededActive = false, supersededOpen = true)
        val dht = counters(0, 0, 10, 0, 5, 4, 1, 0, 2, 0, 0)
        assertEquals(
            "IPFS: Searching the DHT…",
            meter.poll(null, dht, supersededActive = false, supersededOpen = true),
        )
    }

    @Test
    fun `the snapshot is not read while a superseded load has a request open (R4-F1)`() {
        // An ipns:// site still fetching in the node when an unprovided
        // CID is submitted: its Bitswap target tops the snapshot.
        val oldSite = """{"active":[
            {"kind":"gateway_request","phase":"fetching_bitswap","status":"active","top_level_path":"/ipns/docs.ipfs.tech/"},
            {"kind":"gateway_request","phase":"provider_lookup","status":"active","top_level_path":"/ipfs/bafyunprovided/"}
        ]}"""
        val meter = IpfsProgress.LoadMeter()
        val start = counters(0, 0, 0, 0, 0, 3, 2, 0, 0, 0, 0)
        assertEquals("IPFS: Looking up content…", meter.poll(oldSite, start, supersededActive = true))
        // Gone idle but still open (R4-F2): still not the snapshot, but
        // this load's own counter growth now reads through.
        val lookup = counters(0, 0, 0, 0, 0, 3, 2, 0, 0, 0, 0)
        meter.poll(oldSite, lookup, supersededActive = false, supersededOpen = true)
        val own = counters(0, 0, 0, 0, 0, 4, 2, 0, 0, 0, 0)
        assertEquals(
            "IPFS: Finding providers…",
            meter.poll(oldSite, own, supersededActive = false, supersededOpen = true),
        )
        // Every old request closed: the snapshot is this load's again.
        val mine = """{"active":[{"kind":"gateway_request","phase":"dht_fallback_started","status":"active"}]}"""
        assertEquals("IPFS: Searching the DHT…", meter.poll(mine, own, supersededActive = false, supersededOpen = false))
    }

    @Test
    fun `a tracked body reports its close once`() {
        var closes = 0
        val stream = CloseNotifyingInputStream("abc".byteInputStream()) { closes++ }
        assertEquals('a'.code, stream.read())
        assertEquals(0, closes)
        stream.close()
        stream.close()
        assertEquals(1, closes)
    }

    @Test
    fun `a main-frame request only sets the flag of its own load (R2-F4)`() {
        // The WebView is still on load 3 while a submit probes load 4.
        assertFalse(mainFrameNoteApplies(requestGeneration = 3, currentGeneration = 4))
        assertTrue(mainFrameNoteApplies(requestGeneration = 4, currentGeneration = 4))
    }

    @Test
    fun `the WebView's generation trails a submit until the hand-off (R2-F2, R2-F4)`() {
        val tab = BrowserState(id = 1)
        tab.beginLoad(inWebView = true) // a link, back/forward, a pull-to-refresh
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        assertEquals(1, tab.webViewGeneration)
        assertEquals(1, tab.documentGeneration)
        tab.beginLoad() // a submit, still probing
        assertEquals(2, tab.loadGeneration)
        assertEquals(1, tab.webViewGeneration)
        assertFalse(mainFrameNoteApplies(tab.webViewGeneration, tab.loadGeneration))
        tab.handLoadToWebView()
        assertTrue(mainFrameNoteApplies(tab.webViewGeneration, tab.loadGeneration))
        assertEquals(1, tab.documentGeneration)
    }

    @Test
    fun `a load's subresources are its own once its main frame is answered (R3-F1)`() {
        val tab = BrowserState(id = 1)
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        val oldAsset = tab.gatewayWork.start(tab.documentGeneration)
        tab.beginLoad()
        tab.handLoadToWebView()
        val generation = tab.mainFrameRequested()
        val mainFrame = tab.gatewayWork.start(generation)
        // Still fetching the main frame: the page on screen is superseded.
        assertEquals(1, tab.documentGeneration)
        assertTrue(tab.gatewayWork.activeBefore(tab.loadGeneration))
        // The answer goes out; no onPageStarted has run yet, and the
        // new document's CSS/JS start right away.
        tab.mainFrameAnswered(generation, replacesDocument = true)
        tab.gatewayWork.finish(oldAsset) // cancelled by the commit
        val css = tab.gatewayWork.start(tab.documentGeneration)
        assertEquals(2, tab.documentGeneration)
        assertFalse(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(mainFrame)
        tab.gatewayWork.finish(css)
        // A late answer of an older navigation moves nothing back.
        tab.mainFrameAnswered(1, replacesDocument = true)
        assertEquals(2, tab.documentGeneration)
    }

    @Test
    fun `a navigation that keeps the page adopts its open requests (R3-F2)`() {
        val tab = BrowserState(id = 1)
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        val image = tab.gatewayWork.start(tab.documentGeneration)
        // A download link: its answer ends the navigation.
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = false)
        assertEquals(2, tab.documentGeneration)
        assertFalse(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(image)

        // Back onto a hash entry: a history update with no main frame.
        val script = tab.gatewayWork.start(tab.documentGeneration)
        tab.beginLoad()
        tab.handLoadToWebView()
        assertTrue(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.historyUpdated(isHome = false)
        assertEquals(3, tab.documentGeneration)
        assertFalse(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(script)
    }

    @Test
    fun `an answer taken for a document that became a download adopts the page (R2-F1)`() {
        val tab = BrowserState(id = 1)
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        tab.documentCommitted()
        val image = tab.gatewayWork.start(tab.documentGeneration)
        // A link to an inline application/zip: counted as a new document…
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        assertTrue(tab.gatewayWork.activeBefore(tab.loadGeneration))
        // …until the download listener gets it and the page stays.
        tab.mainFrameKeptPage()
        assertFalse(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(image)
    }

    @Test
    fun `a redirect hop cancelled as an app link adopts the page (PR 160 R1-F1)`() {
        val tab = BrowserState(id = 1)
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        tab.documentCommitted()
        val image = tab.gatewayWork.start(tab.documentGeneration)
        // A tapped link: its first hop's 302 goes out as a new document…
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        assertTrue(tab.gatewayWork.activeBefore(tab.loadGeneration))
        // …and the next hop, zoomus:, is cancelled: nothing commits.
        assertTrue(externalLinkKeepsPage(isForMainFrame = true, isRedirect = true, popupFirstNavigation = false))
        tab.mainFrameKeptPage()
        assertFalse(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(image)
    }

    @Test
    fun `a committed document's page is not adopted by a later download (R2-F1)`() {
        val tab = BrowserState(id = 1)
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        tab.documentCommitted()
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        // Committed before the old page's image was cancelled.
        tab.documentCommitted()
        val stale = tab.gatewayWork.start(1)
        tab.mainFrameKeptPage()
        assertTrue(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(stale)
    }

    @Test
    fun `a cross-document commit's history update adopts nothing (R3-F2)`() {
        val tab = BrowserState(id = 1)
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        val oldAsset = tab.gatewayWork.start(tab.documentGeneration)
        tab.beginLoad(inWebView = true)
        tab.mainFrameAnswered(tab.mainFrameRequested(), replacesDocument = true)
        tab.historyUpdated(isHome = false)
        assertTrue(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(oldAsset)
        // Home loads without a request but replaces the page all the same.
        val asset = tab.gatewayWork.start(tab.documentGeneration)
        tab.beginLoad()
        tab.handLoadToWebView()
        tab.historyUpdated(isHome = true)
        assertEquals(2, tab.documentGeneration)
        assertTrue(tab.gatewayWork.activeBefore(tab.loadGeneration))
        tab.gatewayWork.finish(asset)
    }

    @Test
    fun `gateway work retags only the kept document's loads`() {
        val work = GatewayWork { 0L }
        val older = work.start(1)
        val kept = work.start(2)
        work.retag(from = 2, to = 3)
        assertTrue(work.activeBefore(3))
        work.finish(older)
        assertFalse(work.activeBefore(3))
        work.finish(kept)
    }

    @Test
    fun `only a renderable main-frame answer replaces the page (R3-F2)`() {
        assertTrue(mainFrameAnswerReplacesDocument(null))
        assertTrue(mainFrameAnswerReplacesDocument(200, mapOf("Content-Type" to "text/html"), "text/html"))
        assertTrue(mainFrameAnswerReplacesDocument(200, mapOf("content-disposition" to "inline"), "image/png"))
        assertFalse(mainFrameAnswerReplacesDocument(204, null, "text/plain"))
        assertFalse(mainFrameAnswerReplacesDocument(205, null, null))
        assertFalse(
            mainFrameAnswerReplacesDocument(
                200,
                mapOf("content-disposition" to "Attachment; filename=\"a.zip\""),
                "application/zip",
            ),
        )
        assertFalse(mainFrameAnswerReplacesDocument(200, emptyMap(), "application/octet-stream"))
    }

    @Test
    fun `the event tail only counts events after the floor`() {
        val json = """{"active":[],"events":[
            {"event_id":7,"kind":"gateway_request","phase":"fetching_bitswap","status":"active"},
            {"event_id":8,"kind":"gateway_request","phase":"provider_lookup","status":"active"}
        ]}"""
        assertEquals("IPFS: Fetching from peers…", IpfsProgress.message(json))
        assertEquals("IPFS: Finding providers…", IpfsProgress.message(json, afterEventId = 7))
        assertNull(IpfsProgress.message(json, afterEventId = 8))
        // Active targets are live, whatever their age.
        val active = """{"active":[{"kind":"gateway_request","phase":"retrying","status":"active"}],"events":[]}"""
        assertEquals("IPFS: Retrying slow provider…", IpfsProgress.message(active, afterEventId = 99))
    }

    @Test
    fun `a load doesn't show the phases the node logged before it started (#156)`() {
        // The previous page finished: its early events still read as
        // running, and they are all the node's tail has.
        val previousPage = """{"active":[],"events":[
            {"event_id":40,"kind":"gateway_request","phase":"fetching_bitswap","status":"active"},
            {"event_id":41,"kind":"gateway_request","phase":"first_byte","status":"active"},
            {"event_id":42,"kind":"gateway_request","phase":"completed","status":"completed"}
        ]}"""
        val meter = IpfsProgress.LoadMeter()
        val start = counters(0, 0, 0, 0, 0, 3, 2, 0, 0, 0, 0)
        assertEquals("IPFS: Looking up content…", meter.poll(previousPage, start, supersededActive = false))
        // This load's own request came and went between polls: its
        // events are past the floor and read through.
        val own = """{"active":[],"events":[
            {"event_id":41,"kind":"gateway_request","phase":"first_byte","status":"active"},
            {"event_id":42,"kind":"gateway_request","phase":"completed","status":"completed"},
            {"event_id":43,"kind":"gateway_request","phase":"dht_fallback_started","status":"active"}
        ]}"""
        assertEquals("IPFS: Searching the DHT…", meter.poll(own, start, supersededActive = false))
    }

    @Test
    fun `a superseded load's events stay below the floor once it closes (#156)`() {
        val meter = IpfsProgress.LoadMeter()
        val c = counters(0, 0, 0, 0, 0, 3, 2, 0, 0, 0, 0)
        meter.poll("""{"active":[],"events":[{"event_id":5,"phase":"started","status":"active"}]}""", c, supersededActive = true)
        // The old load logs more while its request is still open…
        val oldTail = """{"active":[],"events":[{"event_id":9,"kind":"gateway_request","phase":"retrying","status":"active"}]}"""
        meter.poll(oldTail, c, supersededActive = false, supersededOpen = true)
        // …and once it has closed, that tail is not this load's.
        assertEquals("IPFS: Looking up content…", meter.poll(oldTail, c, supersededActive = false, supersededOpen = false))
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
